package dev.hermes.wearos.domain

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.hermes.wearos.WearApp
import dev.hermes.wearos.data.AppSettings
import dev.hermes.wearos.data.ChatMeta
import dev.hermes.wearos.data.ChatStore
import dev.hermes.wearos.net.GatewaySessions
import dev.hermes.wearos.net.ApiException
import dev.hermes.wearos.net.HermesApi
import dev.hermes.wearos.net.Watchkit
import dev.hermes.wearos.net.HermesApi.Companion.choiceNames
import dev.hermes.wearos.net.HermesApi.Companion.parse
import dev.hermes.wearos.net.HermesApi.Companion.str
import dev.hermes.wearos.notify.Notifs
import dev.hermes.wearos.notify.RunWakeLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ChatMessage(val id: Long, val role: Role, val text: String) {
    enum class Role { User, Hermes, System }
}

data class ApprovalInfo(val runId: String, val requestId: String?, val question: String, val choices: List<String>)

/** Sessions-screen status: cache-served fallback + staleness hint (owner ask #2). */
data class SessionsMeta(val stale: Boolean = false, val fetchedAtMs: Long = 0L, val error: String = "")

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val live: String = "",            // streaming buffer for the answer in flight
    val status: String = "",          // one-line "what the agent is doing"
    val approval: ApprovalInfo? = null,
    val busy: Boolean = false,
    val gen: Long = 0L,               // bumped on chat switch: transcript reloads must not re-speak
    val activeChatId: String = "",
    val activeChatLabel: String = "",
)

/**
 * Multi-chat (SPEC §3): each watch chat = one Hermes session (`sessionTitle` from the local
 * ChatStore registry); per-chat transcript files give offline restore; `active_chat` is the
 * "last session" the watch reopens on launch. Durable idempotency per ask, SSE while
 * foreground, status polling as the fallback/resume path (run status carries terminal output,
 * so a dropped stream or an app restart never loses an answer). See docs/SPEC.md §1, §3, F12.
 */
class ChatSession(
    val settings: AppSettings,
    private val store: ChatStore,
    private val scope: CoroutineScope,
    private val app: Context,
) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state

    companion object { private const val TAG = "HermesWear" }

    private val http = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)   // events stream: server keepalive is every 10 s
        .proxySelector(dev.hermes.wearos.net.TailnetRoute.SELECTOR)   // SPEC §11.4: SSE rides the tunnel
        .build()

    private var askJob: Job? = null
    private var eventSource: EventSource? = null
    private var messageLog = mutableListOf<ChatMessage>()
    private var idCounter = 0L
    private var currentRunId = ""
    private var lastFinishedRun: String? = null
    private var lastApprovalNotified: String? = null // request_id already notified (dedups SSE vs poll)
    private var lastAutoApproved: String? = null      // request_id the per-chat mode already answered
    private var gen = 0L
    private var stoppedRunId = ""   // run the user STOPPED (owner ask #3): late events/polls ignored
    private var deltaCount = 0L     // deltas seen for the live run — stop_mid_run logcat proof

    /** Active gateway agent (owner ask #1): mirror of AppSettings.profile for the UI/bar label. */
    val profile: StateFlow<String> = settings.profile

    /** Gateway session list for the sessions fast-path (owner ask #2). */
    private val _gatewaySessions = MutableStateFlow<List<GatewaySessions.Info>>(emptyList())
    val gatewaySessions: StateFlow<List<GatewaySessions.Info>> = _gatewaySessions

    /** Archived rows for the current agent (🗃 chip; refreshed with every list refresh). */
    private val _archivedSessions = MutableStateFlow<List<GatewaySessions.Info>>(emptyList())
    val archivedSessions: StateFlow<List<GatewaySessions.Info>> = _archivedSessions
    private val _sessionsMeta = MutableStateFlow(SessionsMeta())
    val sessionsMeta: StateFlow<SessionsMeta> = _sessionsMeta

    /** The chat the UI is showing; registry mirror is store.activeId (settles after async load). */
    private var activeId = ""

    val chats: StateFlow<List<ChatMeta>> = store.chats
    val activeChat: StateFlow<String> = store.activeId

    fun chats(): StateFlow<List<ChatMeta>> = chats

    private fun nextId() = ++idCounter

    private fun addMessage(role: ChatMessage.Role, text: String) {
        messageLog += ChatMessage(nextId(), role, text)
        _state.value = _state.value.copy(messages = messageLog.toList())
    }

    /**
     * The API handle for the ACTIVE agent, signed with THAT agent's key (SPEC §12.2). One Hermes
     * box multiplexes every profile, but `API_SERVER_KEY` resolves per profile from the URL and
     * fails CLOSED for a named profile that has none — so a single global token is exactly why
     * "switching agents gives errors on some agents": the wear key is a valid 401 everywhere else.
     * Resolution order lives in `GatewayModel.tokenFor` (agent key → gateway token → mirror).
     */
    private suspend fun api(): HermesApi = apiTo(settings.baseUrl())

    /** Same signing for a FAILOVER endpoint (the base changes, the agent — and its key — do not). */
    private suspend fun apiTo(baseUrl: String): HermesApi =
        HermesApi(baseUrl, settings.tokenFor(settings.profileSync()), http)

    // ── MODEL PICKER (SPEC §12.7) ────────────────────────────────────────────────────────────
    // Owner: swap the never-used notes lane for a model selector. Semantics that fall out of the
    // gateway (all verified live against 0.21.7): the run body's `model` routes the run AND the
    // gateway stamps the session column with whatever it served, so (a) picking a model here just
    // resends it on every ask for this chat, (b) adopted/continued sessions KEEP their last-served
    // model by adopting the stamped column when the chat is opened, and (c) new sessions with no
    // stamp and no pick ride the profile default. The session `POST /model` LOCK endpoint is NOT
    // used: /v1/runs ignores it (only /api/sessions/{id}/chat honors it) — verified by running a
    // locked session and watching `runtime.model` come back unchanged.

    data class ModelState(
        val loading: Boolean = false,
        val error: String = "",
        val catalog: ModelCatalog.Catalog? = null,
        /** What THIS chat will run on ("" = gateway default). Mirrors ChatMeta.model. */
        val picked: String = "",
    )

    private val _modelState = MutableStateFlow(ModelState())
    val modelState: StateFlow<ModelState> = _modelState

    /** Open the 🧠 lane: refresh picker state from cache/gateway (catalog is per-agent+endpoint). */
    fun refreshModels(force: Boolean = false) {
        _modelState.value = _modelState.value.copy(loading = true, error = "", picked = pickedModel())
        scope.launch {
            try {
                val cat = withContext(Dispatchers.IO) { ModelCatalog.load(api(), force) }
                _modelState.value = ModelState(catalog = cat, picked = pickedModel())
            } catch (e: Exception) {
                _modelState.value = _modelState.value.copy(loading = false, error = explain(e))
            }
        }
    }

    fun pickedModel(): String = activeId.takeIf { it.isNotEmpty() }?.let { store.get(it)?.model } ?: ""

    /** Remember the pick for this chat ("" = back to gateway default). Selection is local-fast;
     *  it takes effect on the NEXT ask — the running turn keeps its model, like the gateway itself. */
    fun pickModel(model: String) {
        if (activeId.isEmpty()) return
        store.update(activeId) { it.copy(model = model, updatedAt = System.currentTimeMillis()) }
        _modelState.value = _modelState.value.copy(picked = model)
        Log.i("HermesWear", "model picked chat=$activeId model=${model.ifBlank { "gateway default" }}")
    }

    /**
     * MODEL CONTINUITY on chat entry (owner: "if we're already using a model … keep using it"):
     * a chat with no local pick inherits the gateway's stamped model — adopted sessions, desktop
     * sessions, other devices. A dead/404 read just means "nothing stamped yet → default".
     */
    private fun adoptSessionModel(meta: ChatMeta) {
        if (meta.model.isNotEmpty() || meta.sessionTitle.isBlank()) return
        scope.launch {
            try {
                val stamped = withContext(Dispatchers.IO) { api().sessionModel(meta.sessionTitle) }
                if (!stamped.isNullOrBlank() && store.get(meta.id)?.model.isNullOrEmpty()) {
                    store.update(meta.id) { it.copy(model = stamped) }
                    Log.i("HermesWear", "model adopted chat=${meta.id} model=$stamped")
                }
            } catch (e: Exception) { /* default answers; nothing to log loudly about */ }
        }
    }

    /**
     * Turn a raw failure into something a wrist can act on. Two mistakes this exists for:
     *  • a 401 on a multiplexed gateway usually means THIS agent has no `API_SERVER_KEY` on the box
     *    (SPEC §12.2) — `run submit 401: {"error":{…}}` told the owner nothing, so name the agent
     *    and say where to fix it;
     *  • a routing failure used to print OkHttp's sentence, naming whichever endpoint happened to
     *    be first in the list, so a watch off its LAN reported "Unable to resolve host
     *    <tailnet name>" and the owner reasonably read it as "tailnet is broken" rather than "add
     *    this Wi-Fi's LAN address" (SPEC §11.5). [RouteReport] owns that translation, and it gets
     *    the failover machine's own probe record so the "tried N endpoints" count cannot drift.
     *
     * Everything else keeps its own message — no lying about real errors.
     */
    private fun explain(e: Throwable): String {
        val code = (e as? ApiException)?.code
        if (code == 401) return "no key for ${settings.profileSync()} — Settings ▸ Gateways ▸ Agent keys"
        val tried = runCatching {
            if (WearApp.Graph.hasEndpoints()) WearApp.Graph.endpoints.status.value.apiResults else emptyMap()
        }.getOrDefault(emptyMap())
        return RouteReport.explain(e, tried)
    }

    /**
     * Endpoint failover on request failure (SPEC §11): dethrone the endpoint [usedBaseUrl]
     * points at, probe the gateway's remaining endpoints, return the NEW base (with /p/agent)
     * or null when nothing better answered. Fail-soft: no resolver yet / probe storm → null.
     */
    private suspend fun advanceEndpoint(usedBaseUrl: String): String? = try {
        if (WearApp.Graph.hasEndpoints()) WearApp.Graph.endpoints.advanceFrom(usedBaseUrl).takeIf { it.isNotEmpty() }
        else null
    } catch (e: Exception) {
        Log.w(TAG, "endpoint advance check failed: ${e.message}"); null
    }

    /** App start: load registry (with legacy migration), restore the active chat offline,
     *  then pick up a run that was in flight when we died (F10/F12/F13-lite).
     *
     *  OWNER BATCH 2 (last-agent memory): loadProfile() FIRST rebuilds the base URL's `/p/`
     *  segment from the persisted agent (default `wear`), so a cold start opens as the last
     *  profile used; the CHAT SCOPE follows — if the persisted active chat belongs to a
     *  different agent, reopen the newest chat of the remembered agent instead. */
    fun resume() {
        scope.launch {
            settings.loadProfile() // agent mirror + base-URL rebuild from the persisted agent (ask #4)
            store.loadIfNeeded()
            val agent = settings.profileSync()
            val id = store.activeId.value
            val meta = if (id.isNotEmpty()) store.get(id) else null
            val scoped = meta?.takeIf { it.profile == agent }
            if (scoped == null) {
                // First launch, registry emptied, or active chat belongs to ANOTHER agent:
                // continue the remembered agent's newest chat, else start a fresh one.
                val mine = store.chats.value.filter { it.profile == agent }.maxByOrNull { it.updatedAt }
                if (mine != null) {
                    store.setActive(mine.id)
                    enterChat(mine, store.loadTranscript(mine.id))
                } else {
                    val draft = store.draftMeta(null, settings.profileSync())
                    store.addActive(draft)
                    enterChat(draft, emptyList())
                }
            } else {
                enterChat(scoped, store.loadTranscript(scoped.id))
            }
            resumeRun()
            // AUTO CATCH-UP ON RESTORE (owner: *"it should pretty much do it on its own"*): the
            // chat the app reopens on launch re-pulls its gateway tail too. Guards inside skip
            // it silently when streaming, unbound, cooled-down, or offline.
            catchUpActive()
        }
    }

    /** Swap UI state to [meta] with its persisted transcript; cancels any stream we own. */
    private fun enterChat(meta: ChatMeta, transcript: List<ChatMessage>) {
        eventSource?.cancel()
        askJob?.cancel()
        activeId = meta.id
        messageLog = transcript.toMutableList()
        idCounter = transcript.maxOfOrNull { it.id } ?: 0L
        currentRunId = ""
        lastFinishedRun = null
        lastApprovalNotified = null
        lastAutoApproved = null
        gen++
        // Opening a chat acknowledges it: cancel its notifications + attention flag (M5).
        Notifs.cancelChat(app, meta.id, meta.lastRunId)
        adoptSessionModel(meta) // SPEC §12.7: un-picked chats keep the model their last turn used
        _modelState.value = ModelState(picked = meta.model) // picker always opens on THIS chat's pick
        RunWakeLock.release("chat switch → ${meta.id}") // re-acquired by resumeRun if a run lives here
        store.update(meta.id) { it.copy(pendingAttention = false) }
        _state.value = ChatUiState(
            messages = messageLog.toList(), gen = gen,
            activeChatId = meta.id, activeChatLabel = meta.label,
        )
    }

    /**
     * "OPEN SESSION" from a notification (owner: *"go straight to that session, not just open
     * the app — it may need to switch agents from wherever the app currently is"*). Same-agent
     * ids take the plain path; cross-agent ids switch first, then land on THAT chat.
     */
    fun openFromNotification(id: String) {
        scope.launch {
            store.loadIfNeeded()
            val meta = store.get(id)
            val (route, agent) = NotificationDeepLink.plan(settings.profileSync(), meta?.profile ?: "", meta != null)
            if (route == NotificationDeepLink.Route.SWITCH_THEN_OPEN) {
                Log.i("HermesWear", "deep link: chat $id lives on '$agent' — switching first")
                switchAgent(agent) { _, _, _ -> openChat(id) }
            } else {
                openChat(id)
            }
        }
    }

    /** Continue chat [id] (chats-list tap / dev `open_chat`): persist current, load theirs. */
    fun openChat(id: String) {
        if (id == activeId) return
        scope.launch {
            store.loadIfNeeded()
            val meta = store.get(id) ?: return@launch
            if (activeId.isNotEmpty()) store.saveTranscript(activeId, messageLog)
            store.setActive(id)
            enterChat(meta, store.loadTranscript(id))
            resumeRun()
            // AUTO CATCH-UP (owner: *"it should pretty much do it on its own"*): entering a
            // session-bound chat re-pulls whatever the desktop added since the last look.
            // No-op for local-only chats and empty transcripts; silently skips mid-stream.
            catchUpActive()
        }
    }

    /** Fresh chat + fresh gateway session; the old chat lands in history instantly (SPEC §3). */
    fun createChat(label: String? = null): ChatMeta {
        val draft = store.draftMeta(label, settings.profileSync())
        if (activeId.isNotEmpty()) store.saveTranscript(activeId, messageLog)
        scope.launch {
            store.loadIfNeeded()
            store.addActive(draft)
            enterChat(draft, emptyList())
        }
        return draft
    }

    fun newChat() = createChat(null)

    fun renameChat(id: String, label: String) {
        store.rename(id, label)
        if (id == activeId) _state.value = _state.value.copy(activeChatLabel = label.trim())
    }

    /** Delete a chat; deleting the active one opens the most-recently-touched survivor (or new). */
    fun deleteChat(id: String) {
        scope.launch {
            store.loadIfNeeded()
            if (store.get(id) == null) return@launch
            if (store.activeId.value == id) {
                val next = store.chats.value.filter { it.id != id }.maxByOrNull { it.updatedAt }
                if (next != null) {
                    store.delete(id)
                    store.setActive(next.id)
                    enterChat(next, store.loadTranscript(next.id))
                    resumeRun()
                } else {
                    store.delete(id)
                    newChat()
                }
            } else {
                store.delete(id)
            }
        }
    }

    /** Dev `chat_dump`. */
    fun dumpChats() {
        scope.launch {
            store.loadIfNeeded()
            Log.i(TAG, "chat_dump active=$activeId registry_n=${store.chats.value.size}")
            store.chats.value.forEach {
                Log.i(TAG, "chat_dump chat id=${it.id} session=${it.sessionTitle} label='${it.label}' " +
                    "lastRun=${it.lastRunId} updated=${it.updatedAt}")
            }
        }
    }

    /** If the active chat still has an unfinished run recorded, re-attach / settle it. */
    private suspend fun resumeRun() {
        val meta = store.get(activeId) ?: return
        val runId = meta.lastRunId
        if (runId.isEmpty()) return
        currentRunId = runId
        try {
            val a = api()
            val snap = a.runStatus(runId)
            when (snap.status) {
                "completed" -> finishWith(snap.output ?: "")
                "failed" -> finishWith("⚠️ run failed", error = true)
                "cancelled" -> finishWith("(cancelled)")
                else -> {
                    _state.value = _state.value.copy(busy = true, status = "still working…")
                    openEventStream(a, runId)
                }
            }
        } catch (e: Exception) { /* gateway unreachable: offline reads still work */ }
    }

    fun ask(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.busy) return
        stoppedRunId = ""
        deltaCount = 0L
        addMessage(ChatMessage.Role.User, trimmed)
        _state.value = _state.value.copy(busy = true, live = "", status = "sending…")
        // W8: a chat may consume body tools (MCP) — refresh the watchkit snapshot first
        // (fire-and-forget, rate-limited inside; no-op when all tools are off).
        try { WearApp.Graph.pusher.refreshOnAsk() } catch (e: Exception) { /* Graph not ready */ }
        askJob?.cancel()
        askJob = scope.launch {
            try {
                store.loadIfNeeded()
                if (activeId.isEmpty() || store.get(activeId) == null) {
                    val draft = store.draftMeta(null, settings.profileSync())
                    store.addActive(draft)
                    enterChat(draft, messageLog.toList())
                }
                // Winner freshness (SPEC §11): a no-op while the winner is new (sticky StateFlow),
                // at worst one 2.5 s probe when the 5-min revalidation / primary-backoff is due.
                runCatching { if (WearApp.Graph.hasEndpoints()) WearApp.Graph.endpoints.ensure(false) }
                val a = api()
                val metaNow = store.get(activeId)!!
                val session = metaNow.sessionTitle
                // Per-chat model (SPEC §12.7): "" = never picked → the virtual alias rides the
                // gateway default. A picked id routes every turn of this chat on the gateway.
                val modelSel = metaNow.model.ifBlank { HermesApi.VIRTUAL_MODEL }
                val idem = UUID.randomUUID().toString()
                val accepted = try {
                    a.submitRun(trimmed, session, idem, modelSel)
                } catch (e: Exception) {
                    // Endpoint failover (SPEC §11): the winner just died mid-session? Advance to
                    // the gateway's next endpoint BEFORE retrying; the Idempotency-Key makes the
                    // retry a replay, never a double-run, whichever endpoint lands the request.
                    Log.w(TAG, "submit failed (${e.message}) — endpoint failover check")
                    val nextBase = advanceEndpoint(a.baseUrl)
                    val retryApi = nextBase?.let { apiTo(it) } ?: run { delay(1_500); a }
                    retryApi.submitRun(trimmed, session, idem, modelSel)
                }
                store.update(activeId) { it.copy(lastRunId = accepted.runId, updatedAt = System.currentTimeMillis()) }
                settings.setLastRun(accepted.runId) // legacy key: pre-registry resume path (migration source)
                currentRunId = accepted.runId
                Log.i(TAG, "run accepted ${accepted.runId} chat=$activeId session=$session replayed=${accepted.replayed}")
                _state.value = _state.value.copy(status = "thinking…")
                openEventStream(a, accepted.runId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e // chat/agent switch mid-send is not an error
                Log.e(TAG, "ask failed", e)
                finishWith("⚠️ ${explain(e)}", error = true)
            }
        }
    }

    private fun openEventStream(a: HermesApi, runId: String) {
        eventSource?.cancel()
        RunWakeLock.acquire(app, "run=$runId") // keep doze off our network until settle
        val (hk, hv) = a.authHeader()
        val request = Request.Builder()
            .url(a.eventsUrl(runId))
            .header(hk, hv)
            .header("Accept", "text/event-stream")
            .build()
        val listener = object : EventSourceListener() {
            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) = handleEvent(runId, data)
            override fun onFailure(es: EventSource, t: Throwable?, response: okhttp3.Response?) {
                // Stream dropped mid-run: fall back to status polling (never lose the answer).
                scope.launch { watchRun(a, runId) }
            }
            override fun onClosed(es: EventSource) {
                // Not only terminal events close streams: a run parked in approval (or a proxy
                // timeout) ends the SSE too. If we haven't settled, keep watching via polling —
                // finishWith's exactly-once guard makes this safe alongside a real terminal event.
                if (lastFinishedRun != runId) scope.launch { watchRun(a, runId) }
            }
        }
        eventSource = EventSources.createFactory(http).newEventSource(request, listener)
        // APPROVAL WATCHDOG (found live 2026-10-26): a run parked on manual approval keeps the
        // SSE open and SILENT — no approval.request event ever arrives — so the card would never
        // show. One status poller per run surfaces parked approvals within 3 s and doubles as
        // the never-lose-the-answer net; watchRun dedups vs the closed-stream fallbacks.
        scope.launch { watchRun(a, runId) }
    }

    private var watchPollRun = ""

    /** One [pollUntilTerminal] per run, whoever asks for it first. */
    private suspend fun watchRun(a: HermesApi, runId: String) {
        if (watchPollRun == runId) return
        watchPollRun = runId
        pollUntilTerminal(a, runId)
    }

    private fun handleEvent(runId: String, data: String) {
        if (runId != currentRunId || runId == stoppedRunId) return // moved on, or the user hit STOP
        val obj = try { parse(data) } catch (e: Exception) { return }
        when (obj.str("event")) {
            "message.delta" -> obj.str("delta")?.let { d ->
                deltaCount++
                _state.value = _state.value.copy(live = _state.value.live + d, status = "")
            }
            "message.interim" -> if (obj["already_streamed"]?.toString() != "true") obj.str("text")?.let { t ->
                _state.value = _state.value.copy(live = _state.value.live + t)
            }
            "tool.started" -> _state.value = _state.value.copy(status = "🔧 ${obj.str("tool") ?: "tool"}…")
            "tool.completed" -> _state.value = _state.value.copy(status = "")
            "approval.request" -> {
                val q = obj.str("command") ?: obj.str("question") ?: obj.str("message") ?: "Approve this action?"
                onApprovalArrived(ApprovalInfo(runId, obj.str("request_id") ?: obj.str("id"), q, obj.choiceNames()))
            }
            "run.completed" -> finishWith(obj.str("output") ?: _state.value.live)
            "run.failed" -> finishWith("⚠️ ${obj.str("error") ?: obj.str("turn_exit_reason") ?: "run failed"}", error = true)
            "run.cancelled" -> finishWith("(cancelled)")
            // keepalive comments and unknown events: ignored per Hermes docs guidance
        }
    }

    private suspend fun pollUntilTerminal(a: HermesApi, runId: String) {
        Log.i(TAG, "poll fallback attached run=$runId")
        var netLogged = false
        var n = 0
        while (runId == currentRunId) {
            val snap = try { a.runStatus(runId) } catch (e: Exception) {
                if (!netLogged) { Log.w(TAG, "status poll degraded run=$runId: ${e.message}"); netLogged = true }
                delay(3_000); continue
            }
            netLogged = false
            if (++n % 5 == 0) Log.d(TAG, "watch poll #$n run=$runId status=${snap.status} appr=${snap.approval != null}")
            when (snap.status) {
                "completed" -> return finishWith(snap.output ?: _state.value.live)
                "failed" -> return finishWith("⚠️ run failed", error = true)
                "cancelled" -> return finishWith("(cancelled)")
                "waiting_for_approval" -> snap.approval?.let { ap ->
                    onApprovalArrived(ApprovalInfo(
                        runId, ap.str("request_id") ?: ap.str("id"),
                        ap.str("command") ?: "Approve?", ap.choiceNames(),
                    ))
                }
            }
            delay(3_000)
        }
    }

    /**
     * Approval now waits on the user (SSE event or status poll). Foreground: the in-app card is
     * enough. Backgrounded: HIGH-priority notification with inline Approve/Deny + attention flag
     * (dedup by request_id — pollUntilTerminal re-reports the same approval every 3 s).
     */
    private fun onApprovalArrived(info: ApprovalInfo) {
        Log.i(TAG, "approval arrived run=${info.runId} req=${info.requestId} active=$activeId choices=${info.choices}")
        _state.value = _state.value.copy(approval = info)
        // PER-CHAT APPROVAL MODE (owner 2026-10-29, domain/ApprovalMode, SPEC §12.8): this chat's
        // persisted pick answers for itself — Off always, Smart only while the human is away.
        // Dedup by request id: the status poll re-reports a pending approval every 3 s.
        val mode = ApprovalMode.fromId(store.get(activeId)?.approvalMode)
        if (mode != ApprovalMode.Inherit && mode.autoApprove(viewingThisChat(activeId))) {
            val key = "${info.requestId ?: info.runId}"
            if (key != lastAutoApproved && !info.choices.isNullOrEmpty()) {
                lastAutoApproved = key
                Log.i(TAG, "approval auto-answered run=${info.runId} mode=${mode.id} chat=$activeId")
                respondApproval(info.choices.first())
            }
            return
        }
        // Owner rule 2026-10-29: approvals notify from EVERY surface except the very chat the
        // approval belongs to (its inline Approve/Deny card is already on screen there).
        if (viewingThisChat(activeId)) return
        val key = info.requestId ?: info.runId
        if (lastApprovalNotified == key) return
        lastApprovalNotified = key
        store.update(activeId) { it.copy(pendingAttention = true) }
        Notifs.approval(app, activeId, info)
        Log.i(TAG, "approval notif run=${info.runId} chat=$activeId")
    }

    /** The approvals sheet: read/write the ACTIVE chat's per-chat mode (owner ask 2026-10-29). */
    fun activeChatApprovalMode(): ApprovalMode =
        ApprovalMode.fromId(store.get(activeId)?.approvalMode)

    fun setChatApprovalMode(mode: ApprovalMode) {
        if (activeId.isEmpty()) return
        store.update(activeId) { it.copy(approvalMode = mode.id) }
        Log.i(TAG, "approval mode set chat=$activeId mode='${mode.id.ifEmpty { "inherit" }}'")
    }

    fun respondApproval(choice: String) {
        val info = _state.value.approval ?: return
        _state.value = _state.value.copy(approval = null, status = "running…")
        lastApprovalNotified = null
        // lastAutoApproved deliberately NOT cleared here: the status poll re-reports a pending
        // approval every 3 s until the gateway drains it — resetting on answer would auto-answer
        // the same request again. It resets with the next chat open (see openChat path above).
        Notifs.cancelApproval(app, info.runId)
        scope.launch {
            try { api().respondApproval(info.runId, choice, info.requestId) }
            catch (e: Exception) { finishWith("⚠️ approval failed: ${e.message}", error = true) }
        }
    }

    /**
     * Approve/Deny tapped on the notification (or sent via adb) for run [runId]:
     * first server choice = affirmative, last = denial. If the approval is already in our UI
     * state we route through [respondApproval]; otherwise (cold process / race) we give the
     * resume path a few seconds to surface it, then fall back to the run-status snapshot —
     * the gateway keeps pending approvals there. [done] releases the receiver's goAsync token.
     */
    fun respondApprovalFromNotification(runId: String, approve: Boolean, done: () -> Unit = {}) {
        scope.launch {
            try {
                if (_state.value.approval?.runId != runId) {
                    withTimeoutOrNull(6_000) {
                        while (_state.value.approval?.runId != runId) delay(250)
                    }
                }
                val info = _state.value.approval?.takeIf { it.runId == runId }
                if (info != null) {
                    respondApproval(if (approve) info.choices.first() else info.choices.last())
                } else {
                    val ap = try { api().runStatus(runId).approval } catch (e: Exception) {
                        Log.w(TAG, "approval fallback status failed: ${e.message}"); null
                    }
                    val names = ap?.choiceNames() ?: listOf("approve", "deny")
                    api().respondApproval(runId, if (approve) names.first() else names.last(),
                        ap?.str("request_id") ?: ap?.str("id"))
                    Log.i(TAG, "approval $runId answered via status fallback approve=$approve")
                }
            } catch (e: Exception) {
                Log.e(TAG, "approval response failed run=$runId", e)
            } finally {
                done()
            }
        }
    }

    /** UI/dev helpers: first server choice = affirmative, last = denial (cards use these). */
    fun approve() = _state.value.approval?.let { respondApproval(it.choices.first()) }
    fun deny() = _state.value.approval?.let { respondApproval(it.choices.last()) }

    /** DEV-only (DevAskReceiver `-e approval_demo`): park a fake approval on the open chat so
     *  the orb-flanking ✅/❌ (batch-4 #5) and the bottom-only rule are provable on hardware
     *  without driving gateway state into manual-approval mode. Answering fail-softs like the
     *  notify_demo cards: the local card clears, the POST to the unknown run no-ops. */
    fun devInjectApproval(info: ApprovalInfo) {
        Log.i(TAG, "dev approval injected run=${info.runId} choices=${info.choices}")
        _state.value = _state.value.copy(approval = info)
    }

    /**
     * STOP (owner ask #3): the moment the user taps the orb while a run streams, we detach from the
     * stream (SSE + polling ignored via stoppedRunId/currentRunId), settle the partial answer and
     * mark the transcript `— stopped —`. Then a best-effort SERVER cancel: the route table in
     * gateway/platforms/api_server_runs.py `_http_routes` has `POST /v1/runs/{run_id}/stop`
     * (`run_stop` capability) — called, but never required: client-side stop is the deliverable
     * even when the gateway rejects or is unreachable.
     */
    /**
     * DEV RENDER HARNESS (SPEC §13.3): inject a canned answer into the live transcript WITHOUT the
     * gateway (`-e render tables` → the 5-column grid, `-e render mermaid` → the fence, …). It lands
     * exactly like a streamed Hermes answer — same markdown path, same scroll anchoring — so a
     * screenshot proves the renderer rather than the model's taste.
     */
    fun injectAnswer(text: String) {
        addMessage(ChatMessage.Role.Hermes, text)
        if (activeId.isNotEmpty()) store.saveTranscript(activeId, messageLog)
        Log.i(TAG, "dev: injected ${text.length} chars of canned answer (chat=$activeId)")
    }

    /**
     * FORK ON EDIT (SPEC §12.5, owner: editing an old prompt should REDO it "like the desktop",
     * not pile a second answer onto the pile). As far as this gateway allows, it does exactly
     * that: the local transcript rolls back to just before the edited turn, the gateway session
     * is FORKED and this chat re-points at the fork, then the edited text is asked fresh on the
     * branch. What cannot be undone (measured, not guessed): the fork copies the FULL history —
     * the child is `#2` with every message — because there is no truncate route (SPEC §13.5
     * asks for one), so the superseded answer stays in the MODEL's context even though the wrist
     * never sees it again. The system line says so; silence would be a lie. No run in flight /
     * fork refused (offline, key-less agent) → degrade honestly to the old append behavior with
     * a visible note, because "my edit vanished" is worse than "my edit appended".
     */
    fun editAndResend(messageId: Long, newText: String) {
        val t = newText.trim()
        if (t.isEmpty()) return
        val idx = messageLog.indexOfFirst { it.id == messageId }
        if (idx < 0 || messageLog[idx].role != ChatMessage.Role.User) { ask(t); return }
        // A run answering the OLD version of this turn must stop before the rollback erases it.
        eventSource?.cancel()
        askJob?.cancel()
        val kept = messageLog.subList(0, idx).toMutableList()
        scope.launch {
            val chat = store.get(activeId)
            val old = chat?.sessionTitle.orEmpty()
            val forked = if (old.isNotEmpty()) {
                runCatching { api().forkSession(old) }
                    .onFailure { Log.w(TAG, "edit-fork failed (${it.message}) → append fallback") }
                    .getOrDefault("")
            } else ""
            if (forked.isNotEmpty() && activeId.isNotEmpty()) {
                store.update(activeId) { it.copy(sessionTitle = forked, updatedAt = System.currentTimeMillis()) }
            }
            messageLog = kept
            _state.value = _state.value.copy(
                messages = kept.toList(), live = "", status = "", busy = false,
                activeChatId = activeId,
            )
            if (activeId.isNotEmpty()) store.saveTranscript(activeId, kept)
            addMessage(
                ChatMessage.Role.System,
                if (forked.isNotEmpty()) "— edited · branched (the superseded answer stays in context — no truncate yet) —"
                else "— edited · fork unavailable, appended instead —",
            )
            Log.i(TAG, "edit-and-resend: kept=$idx fork=${forked.ifEmpty { "none" }}")
            ask(t)
        }
    }

    /**
     * `/fork` (SPEC §12.3, honored=branch): branch the current chat's gateway session and keep
     * typing HERE — the watch chat re-points at the child, the local transcript stays put (it IS
     * the history you can see), and the parent remains untouched on the gateway.
     */
    fun forkActive() {
        scope.launch {
            val chat = store.get(activeId)
            val old = chat?.sessionTitle.orEmpty()
            if (old.isEmpty()) {
                addMessage(ChatMessage.Role.System, "— /fork: this chat has no gateway session yet —")
                return@launch
            }
            val forked = runCatching { api().forkSession(old) }.getOrDefault("")
            if (forked.isEmpty()) {
                addMessage(ChatMessage.Role.System, "— /fork failed (offline or no key) —")
                return@launch
            }
            store.update(activeId) { it.copy(sessionTitle = forked, updatedAt = System.currentTimeMillis()) }
            addMessage(ChatMessage.Role.System, "— branched → ${forked.take(18)} · parent untouched, history carried —")
            Log.i(TAG, "forked chat=$activeId $old -> $forked")
        }
    }

    /**
     * `/btw <aside>` (SPEC §12.3, the owner's favourite): the side conversation gets its OWN
     * watch chat on a forked session — this thread stays exactly as clean as it was, and dial
     * 7:30 (chats) jumps between them. The fork carries full context, so the aside can reference
     * the real chat; the parent session never sees the aside at all.
     */
    fun sideAsk(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        scope.launch {
            store.loadIfNeeded()
            val chat = store.get(activeId)
            val old = chat?.sessionTitle.orEmpty()
            val forked = if (old.isNotEmpty()) runCatching { api().forkSession(old) }.getOrDefault("") else ""
            val draft = if (forked.isNotEmpty()) {
                store.draftMetaWith(forked, "btw: ${t.take(24)}", settings.profileSync())
            } else {
                store.draftMeta("btw: ${t.take(24)}", settings.profileSync())
            }
            if (activeId.isNotEmpty()) store.saveTranscript(activeId, messageLog)
            store.addActive(draft)
            enterChat(draft, emptyList())
            addMessage(
                ChatMessage.Role.System,
                if (forked.isNotEmpty()) "— side branch · this chat can see the parent's context; dial 7:30 to switch back —"
                else "— /btw without a fork (offline or no session yet) —",
            )
            ask(t)
            Log.i(TAG, "btw side branch chat=${draft.id} fork=${forked.ifEmpty { "none" }}")
        }
    }

    fun stopActiveRun() {
        if (!_state.value.busy) { Log.i(TAG, "stop ignored: no run in flight"); return }
        val runId = currentRunId
        val live = _state.value.live
        stoppedRunId = runId
        eventSource?.cancel()
        askJob?.cancel()
        if (runId.isNotEmpty()) {
            lastFinishedRun = runId // exactly-once guard: racing terminal event/poll cannot re-settle
            scope.launch {
                try { Log.i(TAG, "stop: gateway cancel run=$runId resp=${api().stopRun(runId)}") }
                catch (e: Exception) { Log.w(TAG, "stop: gateway cancel failed, client-side stop stands: ${e.message}") }
            }
        }
        currentRunId = ""
        RunWakeLock.release("stopped run=$runId")
        if (live.isNotBlank()) addMessage(ChatMessage.Role.Hermes, live)
        addMessage(ChatMessage.Role.System, "— stopped —")
        _state.value = _state.value.copy(live = "", status = "", busy = false, approval = null)
        Log.i(TAG, "stop: run=$runId deltas=$deltaCount stream ceased")
        val chatId = activeId
        if (chatId.isNotEmpty()) {
            store.update(chatId) { it.copy(lastRunId = "", updatedAt = System.currentTimeMillis()) }
            scope.launch { settings.setLastRun("") }
            store.saveTranscript(chatId, messageLog)
        }
    }

    /**
     * `/steer` (SPEC §12.3, gateway cap `run_steer`): inject guidance into the run IN FLIGHT
     * (`POST /v1/runs/{id}/steer`) instead of queueing another turn — the dial/overlay lane the
     * owner lives by ("I like to use the slash BTW and the slash steer a lot"). The transcript
     * shows it as a user line with a `⟳ steer` marker so the thread still reads honestly.
     *
     * Fallback is deliberate: nothing in flight, or the gateway lacks the cap, or the run ended
     * between tap and request → ask it as a normal turn. A steer that vanished silently is worse
     * than one that lands one beat late.
     */
    fun steer(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val runId = currentRunId
        if (runId.isEmpty() || runId == lastFinishedRun || !_state.value.busy) {
            Log.i(TAG, "steer: nothing in flight → plain ask")
            ask(t)
            return
        }
        scope.launch {
            try {
                api().steerRun(runId, t)
                addMessage(ChatMessage.Role.User, "\u27f3 steer: $t")
                if (activeId.isNotEmpty()) store.saveTranscript(activeId, messageLog)
                Log.i(TAG, "steer injected into run=$runId")
            } catch (e: Exception) {
                Log.w(TAG, "steer failed (${e.message}) → asked instead")
                ask(t)
            }
        }
    }

    /**
     * AGENT SWITCH (owner ask #1): swap the `/p/<profile>` segment of the base URL (everything up
     * to `/p/` stays), adopt the `<profile>-watch-` session prefix for new chats, then show that
     * agent's chat list — newest surviving chat or a fresh one. Invalid name → clean error, no
     * hardcoding, old base stands. [onDone] drives the toast/label.
     */
    fun switchAgent(agent: String, onDone: (ok: Boolean, base: String, error: String) -> Unit = { _, _, _ -> }) {
        scope.launch {
            val old = settings.baseUrl()
            val newBase = ProfileLogic.swapProfile(old, agent)
            if (newBase == null) {
                Log.w(TAG, "switchAgent: rejected profile name '$agent'")
                onDone(false, old, "bad agent name"); return@launch
            }
            settings.setBaseUrl(newBase, foldRegistry = false) // mirror rewrite only — the
            // /p/ segment swap is NOT a user endpoint edit; folding it (a) re-polluted the
            // gateway list with the failover winner's host on every agent tap and (b) could
            // never add anything the registry didn't already own (SPEC §11 fold, wrist 11-01).
            eventSource?.cancel(); askJob?.cancel()
            if (_state.value.busy || _state.value.live.isNotEmpty() || _state.value.approval != null)
                _state.value = _state.value.copy(busy = false, live = "", status = "", approval = null)
            store.loadIfNeeded()
            val target = store.chats.value.filter { it.profile == agent }.maxByOrNull { it.updatedAt }
            if (target != null) {
                if (activeId.isNotEmpty() && target.id != activeId) store.saveTranscript(activeId, messageLog)
                store.setActive(target.id)
                enterChat(target, store.loadTranscript(target.id))
                resumeRun()
            } else {
                createChat(null) // new agent gets its own chat with the <profile>-watch- prefix
            }
            Log.i(TAG, "agent switched -> $agent base=$newBase chat=$activeId prefix=${ProfileLogic.sessionPrefix(agent)}")
            // THE SECOND HALF OF THE BUG (SPEC §12.2): the old per-profile 401 also STARVED the
            // sessions refresh — the gateway fetch failed, the stale cache was served, and a
            // brand-new agent showed the PREVIOUS agent's list. Learn this box's keys (watchkit
            // reads profiles/<p>/.env) and refresh the list for the agent now selected, so the
            // Sessions screen shows its own recents the moment the switch lands.
            scope.launch {
                val learned = runCatching { settings.refreshAgentKeys() }.getOrDefault(0)
                Log.i(TAG, "agent keys refreshed for $agent ($learned known)")
                refreshGatewaySessions()
            }
            onDone(true, newBase, "")
        }
    }

    /**
     * RESUME A GATEWAY SESSION (owner ask #2, SPEC M4): adopting gateway session [sessionId] means
     * the next asks carry it as session_id — the watch continues a desktop-started conversation.
     * [sourceProfile] is the profile the Sessions row came from ("" = the current agent).
     * OWNER BUG FIX: a newly-adopted chat used to open EMPTY — its history lives in the
     * profile's state.db, so we backfill it async via watchkit /session_messages after the
     * chat opens (fail-soft: never block or blank the adoption on the fetch).
     */
    fun resumeGatewaySession(sessionId: String, title: String = "", sourceProfile: String = "") {
        if (sessionId.isBlank()) return
        scope.launch {
            store.loadIfNeeded()
            val existing = store.chats.value.firstOrNull { it.sessionTitle == sessionId }
            if (existing != null) {
                var localSize = -1
                if (existing.id != activeId) {
                    if (activeId.isNotEmpty()) store.saveTranscript(activeId, messageLog)
                    store.setActive(existing.id)
                    val tr = store.loadTranscript(existing.id)
                    localSize = tr.size
                    enterChat(existing, tr)
                    resumeRun()
                }
                Log.i(TAG, "gateway session resumed chat=${existing.id} session=$sessionId")
                // The OTHER face of the empty-adopt bug: an older adoption sits in the registry
                // with an EMPTY transcript — reopening it showed blank forever. Seed those too.
                backfillHistory(
                    existing,
                    localTranscriptSize = if (existing.id == activeId) messageLog.size else localSize,
                    profileHint = sourceProfile,
                )
                // ...and CATCH UP the non-empty ones (owner's half-cut answer): tapping a session
                // in the list always shows the gateway's latest tail, no manual pull needed.
                // The empty case self-skips here (empty transcript guard) — no double-seed.
                catchUpActive()
                return@launch
            }
            if (activeId.isNotEmpty()) store.saveTranscript(activeId, messageLog)
            val meta = store.draftMetaWith(sessionId, title.ifBlank { null }, settings.profileSync())
            store.addActive(meta)
            enterChat(meta, emptyList())
            Log.i(TAG, "gateway session adopted chat=${meta.id} session=$sessionId")
            backfillHistory(meta, localTranscriptSize = 0, profileHint = sourceProfile)
        }
    }

    /**
     * CHAT CATCH-UP (owner: *"somehow it should just sync… I shouldn't have to do the manual
     * reload"* — and the pull-up gesture remains as the manual door). Re-pulls the ACTIVE chat's
     * gateway session and appends the missing tail via [SessionHistory.catchUpPlan]. Rules: never
     * while a run streams here (can't fight the token spigot); one flight at a time; per-chat
     * cooldown so re-taps don't hammer the box; unanchored diffs refuse rather than duplicate;
     * failures are silent in the log — the local transcript is never touched. Auto-called on
     * every entry into a session-bound chat; [manual] adds the toast.
     */
    private var catchUpJob: Job? = null
    private val catchUpAt = mutableMapOf<String, Long>()

    fun catchUpActive(manual: Boolean = false, onDone: ((Boolean, Int) -> Unit)? = null) {
        val chatId = activeId
        if (chatId.isEmpty() || catchUpJob?.isActive == true) return
        if (_state.value.busy) return // our own stream owns the transcript right now
        val last = catchUpAt[chatId] ?: 0L
        if (!manual && SystemClock.elapsedRealtime() - last < 5_000) return
        catchUpAt[chatId] = SystemClock.elapsedRealtime()
        catchUpJob = scope.launch {
            store.loadIfNeeded()
            val meta = store.get(chatId) ?: return@launch
            val sessionId = meta.sessionTitle
            if (sessionId.isBlank() || messageLog.isEmpty()) {
                if (manual) onDone?.invoke(false, 0)
                return@launch
            }
            val localSnapshot = messageLog.toList()
            var appended = -1
            var sawEmpty = false
            for (p in SessionHistory.profileProbes(meta.profile)) {
                val probe = scope.async(Dispatchers.IO) {
                    SessionHistory.parse(Watchkit.fetchSessionMessagesJson(p, sessionId))
                }
                val msgs = try {
                    withTimeoutOrNull(historyFetchTimeoutMs) { probe.await() }
                } catch (e: Exception) { null }
                probe.cancel()
                if (msgs == null) continue // this probe failed (network/503) — try the next profile
                if (msgs.isEmpty()) { sawEmpty = true; continue } // store answered, session not there
                val plan = SessionHistory.catchUpPlan(msgs, localSnapshot)
                if (plan == null) { appended = 0; break }
                val stamped = plan.map { ChatMessage(nextId(), it.role, it.text) }
                messageLog = ArrayList(messageLog + stamped) // live sends during the fetch stay ahead of it
                _state.value = _state.value.copy(messages = messageLog.toList())
                store.saveTranscript(chatId, messageLog)
                Log.i(TAG, "chat catch-up +${stamped.size} chat=$chatId session=$sessionId profile=$p")
                appended = stamped.size
                break
            }
            if (appended < 0 && sawEmpty) appended = 0 // stores answered; session simply wasn't there — not an outage
            if (appended < 0) Log.i(TAG, "chat catch-up unavailable chat=$chatId")
            else if (appended == 0) Log.i(TAG, "chat catch-up up to date chat=$chatId session=$sessionId")
            if (manual) onDone?.invoke(appended >= 0, appended.coerceAtLeast(0))
        }
    }

    /**
     * LONG-PRESS → FORK (owner: "hold a session and get fork or delete, delete double-checked"):
     * copies [info]'s session INTO THE CURRENT AGENT's store under a watch-shaped id
     * (`<profile>-watch-x`, so the row keeps its ⌚ marker) and opens a chat on it — "continue this
     * desktop chat on my wrist" becomes one hold. The copy is watchkit's, not the gateway fork:
     * a gateway fork needs the SOURCE profile's key and most profiles have none (SPEC §12.2),
     * `default` never will. Lineage rides the store (`parent_session_id`) and the title gets the
     * " (fork)" suffix there; [SessionHistory] backfill shows the copied messages on open.
     */
    fun forkBrowserSession(info: GatewaySessions.Info, onDone: (Boolean, String) -> Unit) {
        scope.launch {
            val agent = settings.profileSync()
            val newId = ProfileLogic.newSessionId(agent)
            val res = runCatching { Watchkit.forkSession(info.profile.ifBlank { agent }, info.id, agent, newId) }
            val r = res.getOrNull()
            if (r == null || r.id.isBlank()) {
                val why = (res.exceptionOrNull()?.message ?: "fork failed")
                Log.w(TAG, "session fork ${info.id} failed: $why")
                onDone(false, why.take(140))
                return@launch
            }
            Log.i(TAG, "session forked ${info.id} -> $newId (${r.copied_messages} msgs)")
            resumeGatewaySession(newId, SessionManage.chatLabel(info.title), agent)
            // The child must show up in the list the user comes BACK to — they jumped to the chat,
            // but Sessions behind it was stale and the fresh fork looked like it never happened.
            refreshGatewaySessions()
            onDone(true, "forked ${r.copied_messages} msgs")
        }
    }

    /**
     * LONG-PRESS → DELETE (the sheet double-confirms before this is ever called): a REAL delete in
     * `state.db` via watchkit — messages then session, FTS triggers keep the search index honest —
     * plus the local mirror chat if one exists (a ⌚ row deleted from the list must vanish from
     * Chats too, and vice versa). "Already gone" counts as success: the button means *get it off
     * my list*, whoever removed it meanwhile.
     */
    fun deleteBrowserSession(info: GatewaySessions.Info, onDone: (Boolean, String) -> Unit) {
        scope.launch {
            val res = runCatching { Watchkit.deleteSession(info.profile.ifBlank { settings.profileSync() }, info.id) }
            if (res.isFailure) {
                val why = (res.exceptionOrNull()?.message ?: "delete failed")
                Log.w(TAG, "session delete ${info.id} failed: $why")
                onDone(false, why.take(140))
                return@launch
            }
            Log.i(TAG, "gateway session deleted ${info.id} (${res.getOrNull()?.messages ?: 0} msgs)")
            store.loadIfNeeded()
            SessionManage.localMirror(store.chats.value, info.id)?.let { mirror ->
                if (mirror.id == store.activeId.value) {
                    // The open chat IS the doomed mirror — `delete` refuses the active one, so move
                    // to the freshest sibling first; with no sibling, a fresh draft takes the seat.
                    val next = store.chats.value
                        .filter { it.id != mirror.id && it.profile == mirror.profile }
                        .maxByOrNull { it.updatedAt }
                    if (next != null) {
                        store.setActive(next.id)
                        enterChat(next, store.loadTranscript(next.id))
                    } else createChat(null)
                }
                store.delete(mirror.id)
                Log.i(TAG, "watch mirror chat removed ${mirror.id}")
            }
            refreshGatewaySessions()
            onDone(true, "deleted")
        }
    }

    /**
     * LONG-PRESS → 🗃 ARCHIVE / UNARCHIVE (owner batch 5: *"archive instead of delete"*): flips
     * the flag every list already filters on — desktop semantics, hide now / show later, and the
     * undo lives on the wrist too (the 🗃 chip). Hard delete keeps its double check; the watch's
     * own CHAT of the session is deliberately left alone — archiving history should not
     * vaporize the conversation you are looking at.
     */
    fun archiveBrowserSession(info: GatewaySessions.Info, archived: Boolean, onDone: (Boolean, String) -> Unit) {
        scope.launch {
            val res = runCatching {
                Watchkit.archiveSession(info.profile.ifBlank { settings.profileSync() }, info.id, archived)
            }
            if (res.isFailure) {
                val why = (res.exceptionOrNull()?.message ?: "archive failed")
                Log.w(TAG, "session archive ${info.id} failed: $why")
                onDone(false, why.take(140))
                return@launch
            }
            Log.i(TAG, "session ${if (archived) "archived" else "unarchived"} ${info.id}")
            refreshGatewaySessions() // both lists: the row must leave one and appear in the other
            onDone(true, if (archived) "archived" else "unarchived")
        }
    }

    /** Dev-hook entry (`-e session_archive/-e session_unarchive <id>`): resolve profile from either cached list. */
    fun manageArchiveById(sessionId: String, archived: Boolean, onDone: (Boolean, String) -> Unit) {
        val id = sessionId.trim()
        if (id.isEmpty()) return
        val row = _gatewaySessions.value.firstOrNull { it.id == id }
            ?: _archivedSessions.value.firstOrNull { it.id == id }
            ?: GatewaySessions.Info(id, "", 0L, 0, settings.profileSync(), archived = !archived)
        archiveBrowserSession(row, archived, onDone)
    }

    /** Dev-hook entry (`-e session_fork/-e session_delete <id>`): resolve the row's profile from the cached list. */
    fun manageSessionById(sessionId: String, fork: Boolean, onDone: (Boolean, String) -> Unit) {
        val id = sessionId.trim()
        if (id.isEmpty()) return
        val row = _gatewaySessions.value.firstOrNull { it.id == id }
            ?: GatewaySessions.Info(id, "", 0L, 0, settings.profileSync())
        if (fork) forkBrowserSession(row, onDone) else deleteBrowserSession(row, onDone)
    }

    /** Max wait per watchkit probe; the daemon answers in tens of ms — this is a leash, not a budget. */
    private val historyFetchTimeoutMs = 10_000L

    /**
     * Seed a freshly-adopted chat with its gateway transcript (see SessionHistory for rules).
     * Async + fail-soft: watchkit down/timeout/no rows → the chat simply stays empty with a
     * toast; success prepends caption + history under whatever the user already typed.
     */
    private fun backfillHistory(meta: ChatMeta, localTranscriptSize: Int, profileHint: String) {
        if (!SessionHistory.shouldBackfill(meta.sessionTitle, localTranscriptSize)) return
        scope.launch {
            for (p in SessionHistory.profileProbes(profileHint.ifBlank { meta.profile })) {
                // async on the CHAT scope (not withContext children — those would have to finish
                // before the timeout could return): the await below is what the leash controls.
                val probe = scope.async(Dispatchers.IO) {
                    SessionHistory.parse(Watchkit.fetchSessionMessagesJson(p, meta.sessionTitle))
                }
                val msgs = try {
                    withTimeoutOrNull(historyFetchTimeoutMs) { probe.await() }
                } catch (e: Exception) {
                    Log.i(TAG, "history probe profile=$p failed: ${e.message}"); null
                }
                probe.cancel()
                if (msgs.isNullOrEmpty()) continue
                val stamped = ArrayList<ChatMessage>(msgs.size + 1)
                stamped += ChatMessage(nextId(), ChatMessage.Role.System, SessionHistory.captionLine(msgs.size, p))
                msgs.forEach { stamped += ChatMessage(nextId(), it.role, it.text) }
                if (activeId == meta.id) {
                    stamped += messageLog // messages the user sent during the fetch stay newest/bottom
                    messageLog = stamped
                    _state.value = _state.value.copy(messages = messageLog.toList())
                    store.saveTranscript(meta.id, messageLog)
                } else if (store.loadTranscript(meta.id).isEmpty()) {
                    store.saveTranscript(meta.id, stamped) // navigated away mid-fetch: next open has it
                }
                Log.i(TAG, "history backfill n=${msgs.size} chat=${meta.id} session=${meta.sessionTitle} profile=$p")
                return@launch
            }
            Log.i(TAG, "history backfill unavailable chat=${meta.id} session=${meta.sessionTitle}")
            WearApp.Graph.runOnUi {
                android.widget.Toast.makeText(app, "history unavailable — continuing live", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Gateway sessions fetch for the fast-path screen (owner ask #2, cross-profile upgrade):
     *  1. CURRENT agent's list — `GET {base}/api/sessions` (gateway, wear-token auth) as before;
     *     success caches, failure serves the cache with a stale hint.
     *  2. `default` = the DESKTOP/computer profile — `GET {watchkit}/sessions?profile=default`
     *     (watchkit runs beside the stores, reads state.db directly; see server/watchkit). Its
     *     failure is silent: the screen just shows this agent's chats, exactly like before.
     * Merged newest-first, capped to 30 (owner: "don't cache too many, gets out of hand"), NO
     * transcripts cached — tapping a foreign-profile row adopts it (agent swap + session resume).
     */
    /**
     * SESSION SEARCH (SPEC §12.4, owner: "a tap-to-search box, searchable via on-watch Vosk voice
     * or keyboard"). One query answers in two passes: the watch's OWN chats land instantly (labels
     * + saved transcripts, works with the gateway offline), then gateway full-text hits (watchkit
     * FTS, this agent + the desktop profile) merge in under the rules in `SessionSearch.merge`.
     * Every keystroke may call this — the previous search is simply cancelled.
     */
    private val _searchHits = MutableStateFlow<List<SessionHit>>(emptyList())
    val searchHits: StateFlow<List<SessionHit>> = _searchHits
    private var searchJob: Job? = null

    fun searchSessions(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            searchJob?.cancel()
            _searchHits.value = emptyList()
            return
        }
        searchJob?.cancel()
        searchJob = scope.launch {
            val local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SessionSearch.localHits(chats.value, q) { store.loadTranscript(it) }
            }
            _searchHits.value = local // keystroke feedback while the box is still reachable
            val agent = settings.profileSync()
            val gateway = mutableListOf<SessionHit>()
            for (p in listOf(agent, ProfileLogic.PROFILE_COMPUTER).distinct()) {
                try {
                    gateway += Watchkit.search(q, p).data.map { dto ->
                        SessionHit(
                            id = dto.id,
                            title = dto.title.ifBlank { dto.id },
                            profile = dto.profile.ifBlank { p },
                            snippet = dto.snippet,
                            source = SessionHit.Source.GATEWAY,
                            lastActiveMs = dto.lastActiveMs(),
                            hits = dto.hits,
                            titleMatch = dto.title_match,
                        )
                    }
                } catch (e: Exception) {
                    // One profile's store being unreachable must not swallow the other's answer —
                    // and the local hits already on screen stay exactly where they are.
                    Log.i(TAG, "search '$q' profile=$p skipped (${e.message})")
                }
            }
            _searchHits.value = SessionSearch.merge(local, gateway)
            Log.i(TAG, "search '$q' -> ${_searchHits.value.size} hits (${local.size} local)")
        }
    }

    fun refreshGatewaySessions() {
        scope.launch {
            val current = settings.profileSync()
            // The 🗃 chip's content + visibility (owner: archive instead of delete). Fail-soft: no
            // archived list simply means no chip — the normal list is untouched by its failure.
            _archivedSessions.value = try {
                GatewaySessions.parseProfiled(Watchkit.fetchSessionsJson(current, archived = true), current)
            } catch (e: Exception) { emptyList() }
            val (mine, error) = try {
                GatewaySessions.parseProfiled(api().sessionsJson(), current) to ""
            } catch (e: Exception) {
                // Key-less agent? watchkit reads that profile's state.db with no profile key at all
                // (SPEC §12.2) — the difference between "show me this agent's recents" and a list
                // that silently belongs to whoever had a working token.
                val viaWatchkit = try {
                    GatewaySessions.parseProfiled(Watchkit.fetchSessionsJson(current), current)
                } catch (e3: Exception) {
                    emptyList()
                }
                if (viaWatchkit.isNotEmpty()) {
                    Log.i(TAG, "sessions for $current via watchkit (${viaWatchkit.size}) — gateway said: ${e.message}")
                    return@launch run {
                        val computer = try {
                            GatewaySessions.parseProfiled(
                                Watchkit.fetchSessionsJson(ProfileLogic.PROFILE_COMPUTER), ProfileLogic.PROFILE_COMPUTER,
                            )
                        } catch (e4: Exception) { emptyList() }
                        val merged = GatewaySessions.merge(viaWatchkit + computer)
                        _gatewaySessions.value = merged
                        val ts = System.currentTimeMillis()
                        _sessionsMeta.value = SessionsMeta(stale = false, fetchedAtMs = ts, error = "")
                        settings.setSessionsCache(GatewaySessions.encode(merged), ts)
                    }
                }
                // Offline/gateway down: serve the merged cache (already profile-tagged rows).
                val (text, _) = settings.sessionsCache()
                val cached = if (text.isNotEmpty()) try { GatewaySessions.parse(text) }
                    catch (e2: Exception) { Log.w(TAG, "sessions cache unreadable: ${e2.message}"); emptyList() }
                else emptyList()
                Log.w(TAG, "sessions refresh degraded (cache shown): ${e.message}")
                cached to (e.message ?: "unreachable")
            }
            val computer = try {
                GatewaySessions.parseProfiled(
                    Watchkit.fetchSessionsJson(ProfileLogic.PROFILE_COMPUTER), ProfileLogic.PROFILE_COMPUTER,
                )
            } catch (e: Exception) {
                Log.i(TAG, "computer sessions skipped (${e.message})")
                emptyList()
            }
            val merged = GatewaySessions.merge(mine + computer)
            _gatewaySessions.value = merged
            val ts = System.currentTimeMillis()
            val stale = error.isNotEmpty()
            _sessionsMeta.value = SessionsMeta(stale = stale, fetchedAtMs = ts, error = error)
            if (!stale) settings.setSessionsCache(GatewaySessions.encode(merged), ts)
        }
    }

    private fun finishWith(text: String, error: Boolean = false) {
        // Exactly-once settle: SSE terminal event, polling fallback, or resume may race.
        if (currentRunId.isNotEmpty() && lastFinishedRun == currentRunId) return
        lastFinishedRun = currentRunId
        Log.i(TAG, "finish run=$currentRunId error=$error len=${text.length}")
        eventSource?.cancel()
        RunWakeLock.release("settle run=$currentRunId")
        if (currentRunId.isNotEmpty()) Notifs.cancelApproval(app, currentRunId)
        addMessage(if (error) ChatMessage.Role.System else ChatMessage.Role.Hermes, text)
        _state.value = _state.value.copy(live = "", status = "", busy = false, approval = null)
        val chatId = activeId
        store.update(chatId) { it.copy(lastRunId = "", updatedAt = System.currentTimeMillis()) }
        scope.launch { settings.setLastRun("") }
        store.saveTranscript(chatId, messageLog)
        // First answer settled: pull the gateway's session title into the list label (§3 titles).
        if (!error && messageLog.count { it.role == ChatMessage.Role.Hermes } == 1) enrichLabel(chatId)
        // M5 + owner rule 2026-10-29: a settle goes to Wear OS notifications unless THIS chat is
        // the front screen (see Graph.viewingChatId) — another chat open, another surface, or
        // screen-off all notify; only the chat you're literally watching renders inline instead.
        if (chatId.isNotEmpty() && !viewingThisChat(chatId)) {
            store.update(chatId) { it.copy(pendingAttention = true) }
            Notifs.settle(app, chatId, store.get(chatId)?.label ?: "", text, error)
        } else if (chatId.isNotEmpty()) {
            Log.i(TAG, "settle suppressed — viewing chat=$chatId")
        }
    }

    /** Notification suppress gate — true only while [chatId]'s transcript is on screen. */
    /** BATCH-6 #31: THE brain is domain/AttentionPolicy — the notification half and the voice
     *  half read the SAME function, so "buzzed while watching" and "spoke in my pocket" can
     *  never drift apart again (the owner's reversion complaint in one fix). */
    private fun viewingThisChat(chatId: String) =
        AttentionPolicy.shouldSpeak(WearApp.Graph.isForeground, WearApp.Graph.viewingChatId, chatId)

    /**
     * Gateway label sync (verified probe): `GET {base}/api/sessions` → 200
     * `{object:"list", data:[{id,title,...}]}` on Hermes Agent 0.21.7 (also probed: /v1/sessions
     * and /api/sessions/list → 404). Fills an empty label only, so user renames always win.
     */
    private fun enrichLabel(chatId: String) {
        scope.launch {
            val meta = store.get(chatId) ?: return@launch
            if (meta.label.isNotEmpty()) return@launch
            try {
                val s = api().listSessions().firstOrNull { it.id == meta.sessionTitle }
                if (s != null && s.title.isNotEmpty()) {
                    store.update(chatId) { it.copy(label = s.title) }
                    if (chatId == activeId) _state.value = _state.value.copy(activeChatLabel = s.title)
                    Log.i(TAG, "label synced chat=$chatId session=${meta.sessionTitle} title='${s.title}'")
                }
            } catch (e: Exception) {
                Log.w(TAG, "label sync skipped: ${e.message}") // offline/older gateway: label stays session-based
            }
        }
    }
}
