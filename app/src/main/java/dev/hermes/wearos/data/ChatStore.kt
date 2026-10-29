package dev.hermes.wearos.data

import android.content.Context
import android.util.Log
import dev.hermes.wearos.domain.ChatMessage
import dev.hermes.wearos.domain.ProfileLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * One watch chat = one gateway session. Registry entry for SPEC §3/§4 (chats list, continue,
 * new, last). `sessionTitle` is the Hermes `session_id` we submit runs under; `label` is what
 * the chat list shows (user rename, or synced from the gateway session title — see
 * ChatSession.enrichLabel).
 */
@Serializable
data class ChatMeta(
    val id: String,
    val sessionTitle: String,
    val label: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastRunId: String = "",
    /** M5: answer/approval awaiting the user (drives notification + complication; cleared on open). */
    val pendingAttention: Boolean = false,
    /** Gateway agent (profile) this chat belongs to (owner ask #1). Legacy entries default `wear`. */
    val profile: String = "wear",
    /**
     * Model picked for THIS chat from the gateway's enabled catalog (SPEC §12.7, orb 🧠 lane):
     * sent as the run `model` on every ask here, so the choice outlives restarts. `""` = never
     * picked — the gateway default answers, and the session's own stamped model (adopted from
     * `GET /api/sessions/{id}`) fills in for adopted/continued sessions.
     */
    val model: String = "",
    /**
     * PER-CHAT APPROVAL MODE (owner 2026-10-29, domain/ApprovalMode): persisted pick for how much
     * the wrist engages with this chat's approvals — `""` = inherit the agent's default. Watch-side
     * enforced at approval arrival (the gateway's own lever is profile-scoped; SPEC §12.8).
     */
    val approvalMode: String = "",
)

/**
 * Chat registry, local-first: the watch owns the list (source of truth), the gateway only
 * supplies titles. The whole list is a JSON blob persisted via AppSettings (DataStore) and
 * mirrored in memory, so reads/mutations are synchronous for the UI and every mutation
 * schedules an async persist. The active chat id lives under its own key (active_chat).
 *
 * Per-chat transcripts are separate JSON files (filesDir/transcripts/<id>.json), written
 * atomically (tmp + rename) and capped to the newest [MAX_TRANSCRIPT] messages.
 */
class ChatStore(private val settings: AppSettings, private val appContext: Context, private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "HermesWear"
        private const val MAX_CHATS = 30
        private const val MAX_TRANSCRIPT = 50
    }

    @Serializable
    private data class PersistedMsg(val id: Long, val role: String, val text: String)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val metaList = ListSerializer(ChatMeta.serializer())
    private val msgList = ListSerializer(PersistedMsg.serializer())

    private val _chats = MutableStateFlow<List<ChatMeta>>(emptyList())
    val chats: StateFlow<List<ChatMeta>> = _chats

    private val _activeId = MutableStateFlow("")
    val activeId: StateFlow<String> = _activeId

    private val loadMutex = Mutex()
    private var loaded = false

    /** Read the registry from DataStore; first-launch migration folds the legacy single-chat keys. */
    suspend fun loadIfNeeded() = loadMutex.withLock {
        if (loaded) return@withLock
        loaded = true
        val raw = settings.chatsJson()
        val list = try {
            if (raw.isNotEmpty()) json.decodeFromString(metaList, raw) else emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "chats registry unreadable, starting fresh", e)
            emptyList()
        }
        if (list.isNotEmpty()) {
            _chats.value = list
            val saved = settings.activeChatId()
            _activeId.value = list.find { it.id == saved }?.id
                ?: list.maxByOrNull { it.updatedAt }?.id ?: ""
        } else {
            // Migration: pre-registry installs kept one session in session_id + last_run.
            val legacy = settings.sessionId()
            if (legacy.isNotEmpty()) {
                val m = ChatMeta(
                    id = shortId(), sessionTitle = legacy,
                    lastRunId = settings.lastRun(),
                    updatedAt = if (settings.lastRun().isNotEmpty()) System.currentTimeMillis() else 0L,
                )
                _chats.value = listOf(m)
                _activeId.value = m.id
                Log.i(TAG, "migrated legacy session id=${m.id} session=${m.sessionTitle} lastRun=${m.lastRunId}")
            }
        }
        persist()
        Log.i(TAG, "registry loaded n=${_chats.value.size} active=${_activeId.value}")
    }

    fun get(id: String): ChatMeta? = _chats.value.find { it.id == id }

    /** Pure generator so createChat() can return the meta before the async load settles. */
    fun draftMeta(label: String?, profile: String = "wear"): ChatMeta = ChatMeta(
        id = shortId(),
        sessionTitle = ProfileLogic.newSessionId(profile),
        label = label?.trim().orEmpty(),
        profile = profile,
    )

    /** Chat that ADOPTS an existing gateway session id (sessions screen → resume a desktop chat). */
    fun draftMetaWith(sessionTitle: String, label: String?, profile: String): ChatMeta = ChatMeta(
        id = shortId(),
        sessionTitle = sessionTitle,
        label = label?.trim().orEmpty(),
        profile = profile,
    )

    /** Insert (or move to top of) the registry and make [meta] the active chat. Caller awaits load. */
    fun addActive(meta: ChatMeta) {
        _chats.value = (listOf(meta) + _chats.value.filter { it.id != meta.id })
        _activeId.value = meta.id
        evict()
        persist()
    }

    fun setActive(id: String) {
        if (get(id) == null) return
        _activeId.value = id
        touch(id)
        persist()
    }

    fun update(id: String, transform: (ChatMeta) -> ChatMeta) {
        val cur = get(id) ?: return
        _chats.value = _chats.value.map { if (it.id == id) transform(cur) else it }
        persist()
    }

    /** Bump updatedAt (drives chat-list ordering + eviction). */
    fun touch(id: String) = update(id) { it.copy(updatedAt = System.currentTimeMillis()) }

    fun rename(id: String, label: String) = update(id) { it.copy(label = label.trim(), updatedAt = System.currentTimeMillis()) }

    /** Drop a chat (never the active one — caller switches first). */
    fun delete(id: String) {
        if (_activeId.value == id) return
        _chats.value = _chats.value.filter { it.id != id }
        transcriptFile(id).delete()
        persist()
    }

    /** Keep ~MAX_CHATS; evict oldest by updatedAt, never the active chat. */
    private fun evict() {
        var chats = _chats.value
        while (chats.size > MAX_CHATS) {
            val victim = chats.filter { it.id != _activeId.value }.minByOrNull { it.updatedAt } ?: break
            chats = chats.filter { it.id != victim.id }
            transcriptFile(victim.id).delete()
            Log.i(TAG, "evicted chat ${victim.id} session=${victim.sessionTitle}")
        }
        _chats.value = chats
    }

    private fun persist() {
        val snapshot = _chats.value
        val active = _activeId.value
        scope.launch { settings.setChatsJson(json.encodeToString(metaList, snapshot)); settings.setActiveChatId(active) }
    }

    // ---- transcripts (files, not DataStore: cap + atomic rename is simpler on disk) ----

    private fun transcriptFile(id: String) = File(File(appContext.filesDir, "transcripts"), "$id.json")

    /** Offline restore (F13-lite): reload a chat's transcript; empty list when none/corrupt. */
    fun loadTranscript(id: String): List<ChatMessage> {
        val f = transcriptFile(id)
        if (!f.exists()) return emptyList()
        return try {
            val msgs = json.decodeFromString(msgList, f.readText()).map {
                ChatMessage(it.id, runCatching { ChatMessage.Role.valueOf(it.role) }.getOrDefault(ChatMessage.Role.System), it.text)
            }
            Log.i(TAG, "loaded transcript id=$id n=${msgs.size}")
            msgs
        } catch (e: Exception) {
            Log.w(TAG, "transcript load failed id=$id", e)
            emptyList()
        }
    }

    /** Atomic write (tmp + rename), newest [MAX_TRANSCRIPT] messages only. */
    fun saveTranscript(id: String, messages: List<ChatMessage>) {
        if (id.isEmpty()) return
        val payload = json.encodeToString(
            msgList,
            messages.takeLast(MAX_TRANSCRIPT).map { PersistedMsg(it.id, it.role.name, it.text) },
        )
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(appContext.filesDir, "transcripts").apply { mkdirs() }
                val tmp = File(dir, "$id.json.tmp")
                tmp.writeText(payload)
                val dst = File(dir, "$id.json")
                if (!tmp.renameTo(dst)) tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            } catch (e: Exception) {
                Log.w(TAG, "transcript save failed id=$id", e)
            }
        }
    }

    private fun shortId() = UUID.randomUUID().toString().take(8)
}
