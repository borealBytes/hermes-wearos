package dev.hermes.wearos.net

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.hermes.wearos.data.AppSettings
import dev.hermes.wearos.domain.ImageDecode
import dev.hermes.wearos.domain.SessionHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest

/**
 * watchkit helper daemon client (server/watchkit/watchkit.mjs) — the two heavy services the
 * watch can't run: POST /render/mermaid (PNG) + POST /stt/whisper. Shares HermesApi.DEFAULT
 * OkHttp client (tailnet-only bearer; the daemon caps renders at 60s, reads never time out).
 *
 * Config is a StateFlow: a pushed token/baseUrl (Settings Test, adb `set_watchkit`) makes any
 * already-composed MermaidBlockView re-key its LaunchedEffect and auto-retry its render.
 * Decoded images + raw bytes share ONE byte-aware LruCache (~24 MB, owner batch 2): full-res
 * images land on the watch and are downsampled HERE at decode, never re-encoded server-side.
 */
object Watchkit {
    data class Config(val baseUrl: String, val token: String)

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val _config = MutableStateFlow(Config(AppSettings.DEFAULT_WATCHKIT_URL, ""))
    val config: StateFlow<Config> = _config.asStateFlow()

    fun setConfig(baseUrl: String, token: String) {
        _config.value = Config(baseUrl.trim().trimEnd('/').ifEmpty { AppSettings.DEFAULT_WATCHKIT_URL }, token.trim())
    }

    /**
     * BATCH-4 #9: which STT engine watchkit's `/health` says will answer `/stt/stream`
     * (null = offline/unreachable). Settings ▸ Voice in names it in the Gateway picker row;
     * the endpoint is PUBLIC, so no bearer is spent on the probe.
     */
    private val _sttEngine = MutableStateFlow<String?>(null)
    val sttEngine: StateFlow<String?> = _sttEngine.asStateFlow()

    /**
     * #16 ENGINE SELECTION (owner: *"clicking … should let you select … installed models …
     * set up to expand in the future"*): the FULL inventory /health reports — every engine the
     * watchkit box has, with its honest streaming + ready flags. Empty = never answered yet
     * (the picker then shows only the single-engine name, the pre-#16 behavior).
     */
    private val _sttEngines = MutableStateFlow<List<dev.hermes.wearos.domain.SttProviders.SttEngineInfo>>(emptyList())
    val sttEngines: StateFlow<List<dev.hermes.wearos.domain.SttProviders.SttEngineInfo>> = _sttEngines.asStateFlow()

    suspend fun refreshSttEngine() = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("${_config.value.baseUrl}/health").get().build()
            val text = clientCall(req) { it.body?.string().orEmpty() }
            val stt = HermesApi.parse(text)["stt"] as? kotlinx.serialization.json.JsonObject
            fun flag(o: kotlinx.serialization.json.JsonObject, k: String) =
                (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            _sttEngine.value = stt?.let { s -> flag(s, "engine")?.takeIf { flag(s, "ready") == "true" } }
            _sttEngines.value = parseSttEngines(stt)
        }.onFailure { _sttEngine.value = null; _sttEngines.value = emptyList() }
    }

    /** Pure-ish (kotlinx JSON only → JVM testable): /health `stt.engines[]` → rows. */
    internal fun parseSttEngines(
        stt: kotlinx.serialization.json.JsonObject?,
    ): List<dev.hermes.wearos.domain.SttProviders.SttEngineInfo> {
        val arr = stt?.get("engines") as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { row ->
            val o = row as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun f(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val id = f("engine")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            dev.hermes.wearos.domain.SttProviders.SttEngineInfo(
                id = id,
                streaming = f("streaming") == "true",
                ready = f("ready") == "true",
                missing = f("missing").orEmpty(),
            )
        }
    }

    /**
     * Byte-aware image cache (owner batch 2: full-res to the watch): a few big photos must not
     * OOM a watch, so the budget is BYTES (~24 MB), not entry count. Weighs raw byte arrays by
     * size and bitmaps by their real allocation (allocationByteCount). Entries heavier than the
     * whole budget simply never enter the cache (LruCache semantics) — a huge full-res bitmap
     * lives only as long as the zoom viewer holds it.
     */
    private const val MAX_CACHE_BYTES = 24 * 1024 * 1024

    private fun weightOf(value: Any): Int = when (value) {
        is ByteArray -> value.size
        is ImageBitmap -> try {
            val bytes = value.asAndroidBitmap().allocationByteCount.toLong()
            if (bytes > Int.MAX_VALUE) Int.MAX_VALUE else bytes.toInt()
        } catch (e: Exception) { value.width * value.height * 4 }
        else -> 1
    }

    private val cache = object : LruCache<String, Any>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Any): Int = weightOf(value)
    }

    private fun sha256(s: String) =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun esc(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t") + "\""

    /**
     * BATCH-6 #25 (owner: *"notifications or approvals from sessions started in the desktop …
     * only stuff I start in the watch [get them] — even if not ever opened yet on the watch"*):
     * the DESKTOP-PULSE inbox — approval parks (activity beacon) + recently-active sessions everywhere
     * on the gateway box (watchkit reads the state stores directly). Raw JSON in, parsed by the
     * pure domain/AwayWatch; null = watchkit unset/offline (the poller just tries again later).
     */
    suspend fun approvalsWaiting(): String? = withContext(Dispatchers.IO) {
        val cfg = _config.value
        try {
            clientCall(
                Request.Builder().url("${cfg.baseUrl}/approvals_waiting")
                    .header("Authorization", "Bearer ${cfg.token}").build(),
            ) { r -> if (r.isSuccessful) r.body?.string() else null }
        } catch (e: Exception) {
            null // offline / watchkit down / bearer refused: the next tick retries
        }
    }

    /** Raw render fetch (also used by the dev `render_test` hook). Throws ApiException/IOException. */
    suspend fun renderBytes(code: String, width: Int = 460): ByteArray = withContext(Dispatchers.IO) {
        val cfg = _config.value
        val body = "{\"code\":${esc(code)},\"width\":$width}"
        val req = Request.Builder()
            .url("${cfg.baseUrl}/render/mermaid")
            .post(body.toRequestBody(JSON_MEDIA))
            .header("Authorization", "Bearer ${cfg.token}")
            .build()
        clientCall(req) { r ->
            val bytes = r.body?.bytes()
            if (!r.isSuccessful || bytes == null || bytes.isEmpty())
                throw ApiException(r.code, "render ${r.code}: ${(bytes?.let { String(it) } ?: "").take(160)}")
            bytes
        }
    }

    /** Decoded image for a fence, byte-aware cache keyed sha256(code|width). */
    suspend fun renderBitmap(code: String, width: Int = 460): ImageBitmap {
        val key = sha256("render|$code|$width")
        (cache.get(key) as? ImageBitmap)?.let { return it }
        val bytes = renderBytes(code, width)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("png decode failed (${bytes.size} B)")
        return bmp.asImageBitmap().also { cache.put(key, it) }
    }

    /**
     * F6 raw image fetch. http(s) targets load DIRECT (public URLs — no watchkit bearer leak);
     * anything else is a gateway-host absolute path routed through watchkit GET /img, which
     * allowlists roots. width > 0 asks the server to scale to WebP (legacy); width <= 0 OMITS
     * the param ⇒ watchkit serves the ORIGINAL bytes (owner batch 2: full res to the watch,
     * scaling happens HERE, at decode time). Throws ApiException/IOException.
     */
    suspend fun fetchImageBytes(target: String, width: Int = 0): ByteArray = withContext(Dispatchers.IO) {
        val cfg = _config.value
        val direct = target.startsWith("http://") || target.startsWith("https://")
        val ub = cfg.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("img")
            .addQueryParameter("path", target)
        if (width > 0) ub.addQueryParameter("width", width.toString())
        val url: String = if (direct) target else ub.build().toString()
        val rb = Request.Builder().url(url).get()
        if (!direct) rb.header("Authorization", "Bearer ${cfg.token}") // never leak the bearer to public URLs
        val req = rb.build()
        clientCall(req) { r ->
            val bytes = r.body?.bytes()
            if (!r.isSuccessful || bytes == null || bytes.isEmpty())
                throw ApiException(r.code, "img ${r.code}: ${(bytes?.let { String(it) } ?: "").take(160)}")
            bytes
        }
    }

    /** Full-res bytes for a target, fetched ONCE and shared by card + viewer (byte-weighted). */
    private suspend fun imageBytes(target: String): ByteArray {
        val key = sha256("bytes|img|$target")
        (cache.get(key) as? ByteArray)?.let { return it }
        return fetchImageBytes(target, width = 0).also { cache.put(key, it) }
    }

    /**
     * Card bitmap for an `![alt](target)` line: full-res bytes decoded DOWN to ~[maxPx] via
     * BitmapFactory inSampleSize (owner: "scaling should happen on the watch") — crisp on the
     * card, ~1-2 MB instead of tens of MB held per photo.
     */
    suspend fun fetchImageCard(target: String, maxPx: Int = 512): ImageBitmap {
        val key = sha256("card|img|$target|$maxPx")
        (cache.get(key) as? ImageBitmap)?.let { return it }
        val bytes = imageBytes(target)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        opts.inSampleSize = ImageDecode.sampleSize(opts.outWidth, opts.outHeight, maxPx)
        opts.inJustDecodeBounds = false
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IOException("image decode failed (${bytes.size} B)")
        return bmp.asImageBitmap().also { cache.put(key, it) }
    }

    /** Full bitmap for the pinch-zoom viewer — the whole point of shipping full-res. */
    suspend fun fetchImageFull(target: String): ImageBitmap {
        val key = sha256("full|img|$target")
        (cache.get(key) as? ImageBitmap)?.let { return it }
        val bytes = imageBytes(target)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("image decode failed (${bytes.size} B)")
        return bmp.asImageBitmap().also { cache.put(key, it) } // oversize entries simply don't cache
    }

    // ─── MEDIA EPIC (owner 2026-11-02) ─────────────────────────────────────────────────────
    /**
     * The A/V shape the watch asks watchkit /media for. Mirrored from AppSettings by WearApp so
     * every media URL is built with the CURRENT knobs and a Settings change re-keys the cards.
     */
    data class MediaPrefs(val q: Int = 50, val mono: Boolean = true, val audioKbps: Int = 64)
    private val _mediaPrefs = MutableStateFlow(MediaPrefs())
    val mediaPrefs: StateFlow<MediaPrefs> = _mediaPrefs
    fun setMediaPrefs(p: MediaPrefs) { _mediaPrefs.value = p }

    /** true ⇒ route through watchkit (bearer needed); false ⇒ public http(s), play direct. */
    fun mediaNeedsAuth(target: String): Boolean =
        !(target.startsWith("http://") || target.startsWith("https://"))

    /** Bearer for media3's own HTTP data source (ExoPlayer does not ride OkHttp's tunnel client). */
    fun currentToken(): String = _config.value.token

    /** Fullscreen playback URL for a media target (local paths ⇒ watchkit /media, scaled per
     *  [mediaPrefs]; remote URLs pass through untouched). */
    fun mediaUrl(target: String): String {
        if (!mediaNeedsAuth(target)) return target
        val cfg = _config.value
        val m = _mediaPrefs.value
        return cfg.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("media")
            .addQueryParameter("path", target)
            .addQueryParameter("q", m.q.toString())
            .addQueryParameter("mono", if (m.mono) "1" else "0")
            .addQueryParameter("ab", m.audioKbps.toString())
            .build().toString()
    }

    /**
     * Inline-card poster frame for a LOCAL video (watchkit /media?poster=1 — ffmpeg grabs one
     * JPEG). Remote videos get no poster (watchkit can't reach them with its own fs) — callers
     * show the ▶ glyph card instead. Throws on any failure; the card falls back to the glyph.
     */
    suspend fun fetchPoster(target: String): ImageBitmap = withContext(Dispatchers.IO) {
        val key = sha256("poster|$target")
        (cache.get(key) as? ImageBitmap)?.let { return@withContext it }
        val cfg = _config.value
        val url = cfg.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("media")
            .addQueryParameter("path", target)
            .addQueryParameter("poster", "1")
            .build().toString()
        val req = Request.Builder().url(url).get()
            .header("Authorization", "Bearer ${cfg.token}").build()
        val bytes = clientCall(req) { r ->
            val b = r.body?.bytes()
            if (!r.isSuccessful || b == null || b.isEmpty())
                throw ApiException(r.code, "poster ${r.code}: ${(b?.let { String(it) } ?: "").take(120)}")
            b
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("poster decode failed (${bytes.size} B)")
        bmp.asImageBitmap().also { cache.put(key, it) }
    }

    /**
     * Agent switcher (owner ask #1): GET /agents → gateway profiles list (["default", ...]).
     * Bearer like every other watchkit endpoint; throws ApiException/IOException on any failure —
     * the caller falls back to the cached/DEFAULT list.
     */
    suspend fun fetchAgentList(): List<String> = withContext(Dispatchers.IO) {
        val cfg = _config.value
        val req = Request.Builder()
            .url("${cfg.baseUrl}/agents")
            .get()
            .header("Authorization", "Bearer ${cfg.token}")
            .build()
        clientCall(req) { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "agents ${r.code}: ${text.take(80)}")
            GatewaySessions.parseAgents(text)
        }
    }

    /**
     * PER-AGENT GATEWAY KEYS (SPEC §12.2): `GET /agent_keys` — watchkit reads
     * `~/.hermes/profiles/<p>/.env` on the gateway box, which is the only place the truth lives: a
     * multiplexed gateway resolves `API_SERVER_KEY` per profile and fails CLOSED for a named
     * profile with no key, while the unauthenticated `/v1/health` keeps answering 200. Learning
     * the keys here is what makes "switch agent" work the first time instead of 401-ing until the
     * owner types 64 hex characters on a watch.
     */
    suspend fun agentKeys(): List<AgentKey> = withContext(Dispatchers.IO) {
        val cfg = _config.value
        val req = Request.Builder()
            .url("${cfg.baseUrl}/agent_keys")
            .get()
            .header("Authorization", "Bearer ${cfg.token}")
            .build()
        clientCall(req) { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "agent_keys ${r.code}: ${text.take(120)}")
            AgentKeysResponse.parse(text).agents.filter { it.profile.isNotBlank() }
        }
    }

    /**
     * FULL-TEXT SESSION SEARCH (SPEC §12.4): watchkit `GET /search` runs the store's own FTS5
     * index (`messages_fts`, trigger-maintained) — the Sessions box searches EVERY chat the agent
     * ever had, not the 20 rows /sessions lists. Snippets arrive with the matched words in
     * [brackets]; they are shown verbatim so a wrist sees WHY a row matched.
     */
    suspend fun search(query: String, profile: String): SearchResponse = withContext(Dispatchers.IO) {
        val cfg = _config.value
        val url = cfg.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", query)
            .addQueryParameter("profile", profile)
            .addQueryParameter("limit", "8")
            .build()
        val req = Request.Builder().url(url).get().header("Authorization", "Bearer ${cfg.token}").build()
        clientCall(req) { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "search ${r.code}: ${text.take(120)}")
            SearchResponse.parse(text)
        }
    }

    /**
     * THE COMMAND CATALOG (SPEC §12.3): `GET /catalog` on watchkit — the gateway's own slash-command
     * list plus the capability flags the 6:00 overlay dims by. watchkit is the only place that can
     * read `GET /v1/capabilities` and `GET /v1/skills` for EVERY profile with no profile key (it also
     * merges the gateway's `command_catalog.json` if the operator wrote one); the watch has no
     * reason to reach the gateway directly for a static list.
     *
     * Shape (documented in the watchkit header): `{commands:[{name,args,desc,honored,group,requires,
     * source}], capabilities:{run_steer:true,…}}`. Failures throw — the caller keeps the built-in
     * catalog, which is why the overlay works on a watch that has never reached this endpoint.
     */
    suspend fun catalog(profile: String): CatalogResponse = withContext(Dispatchers.IO) {
        val cfg = _config.value
        val url = cfg.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("catalog")
            .addQueryParameter("profile", profile)
            .build()
        val req = Request.Builder().url(url).get().header("Authorization", "Bearer ${cfg.token}").build()
        clientCall(req) { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "catalog ${r.code}: ${text.take(120)}")
            CatalogResponse.parse(text)
        }
    }

    /**
     * SESSION FORK for the Sessions long-press menu (owner: "hold a session → fork or delete").
     * watchkit WRITES `state.db` directly, which is what makes this work for every agent — the
     * gateway's own fork/delete needs the SOURCE profile's key, and `default` never has one the
     * watch could hold (SPEC §12.2). Throws — the sheet shows the message verbatim.
     */
    suspend fun forkSession(fromProfile: String, sessionId: String, toProfile: String, newId: String): ForkResult =
        withContext(Dispatchers.IO) {
            val cfg = _config.value
            val body = "{\"profile\":${esc(fromProfile)},\"session_id\":${esc(sessionId)}," +
                "\"to_profile\":${esc(toProfile)},\"new_id\":${esc(newId)}}"
            val req = Request.Builder()
                .url(cfg.baseUrl.toHttpUrl().newBuilder().addPathSegment("session_fork").build())
                .post(body.toRequestBody(JSON_MEDIA))
                .header("Authorization", "Bearer ${cfg.token}")
                .build()
            clientCall(req) { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiException(r.code, "fork ${r.code}: ${text.take(140)}")
                ForkResult.parse(text)
            }
        }

    /**
     * SESSION ARCHIVE / UNARCHIVE (owner batch 5: "archive instead of delete"): flips the flag the
     * lists already filter on — desktop semantics, hide now show later. The archived list arrives
     * through [fetchSessionsJson] with `archived = true`.
     */
    suspend fun archiveSession(profile: String, sessionId: String, archived: Boolean): Unit =
        withContext(Dispatchers.IO) {
            val cfg = _config.value
            val body = "{\"profile\":${esc(profile)},\"session_id\":${esc(sessionId)}," +
                "\"archived\":${if (archived) 1 else 0}}"
            val req = Request.Builder()
                .url(cfg.baseUrl.toHttpUrl().newBuilder().addPathSegment("session_archive").build())
                .post(body.toRequestBody(JSON_MEDIA))
                .header("Authorization", "Bearer ${cfg.token}")
                .build()
            clientCall(req) { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiException(r.code, "archive ${r.code}: ${text.take(140)}")
            }
        }

    /** SESSION DELETE (double-confirmed on the wrist before this fires). Already-gone = success. */
    suspend fun deleteSession(profile: String, sessionId: String): DeleteResult = withContext(Dispatchers.IO) {
        val cfg = _config.value
        val body = "{\"profile\":${esc(profile)},\"session_id\":${esc(sessionId)}}"
        val req = Request.Builder()
            .url(cfg.baseUrl.toHttpUrl().newBuilder().addPathSegment("session_delete").build())
            .post(body.toRequestBody(JSON_MEDIA))
            .header("Authorization", "Bearer ${cfg.token}")
            .build()
        clientCall(req) { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "delete ${r.code}: ${text.take(140)}")
            DeleteResult.parse(text)
        }
    }

    /**
     * Cross-profile sessions (owner walkthrough): GET /sessions?profile=<p>&limit=<n> — watchkit
     * reads the profile's state.db directly (see server/watchkit). Body is the gateway
     * `/api/sessions` shape + a per-row `profile`, so GatewaySessions.parse handles it verbatim.
     * Throws ApiException/IOException — the caller degrades to the single-profile list.
     */
    suspend fun fetchSessionsJson(
        profile: String,
        limit: Int = GatewaySessions.MAX_ROWS,
        archived: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val cfg = _config.value
        val url = cfg.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("sessions")
            .addQueryParameter("profile", profile)
            .addQueryParameter("limit", limit.coerceIn(1, GatewaySessions.MAX_ROWS).toString())
            .apply { if (archived) addQueryParameter("archived", "1") }
            .build().toString()
        val req = Request.Builder().url(url).get()
            .header("Authorization", "Bearer ${cfg.token}")
            .build()
        clientCall(req) { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "sessions ${r.code}: ${text.take(80)}")
            text
        }
    }

    /**
     * History backfill (owner bug: adopted computer sessions opened EMPTY): GET
     * /session_messages?profile=<p>&session_id=<id>&limit=<n≤120> — the profile's state.db
     * transcript, cleaned + capped server-side (see server/watchkit). Raw JSON straight to
     * SessionHistory.parse, which owns the mapping. Throws ApiException/IOException.
     */
    suspend fun fetchSessionMessagesJson(profile: String, sessionId: String, limit: Int = SessionHistory.MAX_MESSAGES): String = withContext(Dispatchers.IO) {
        val cfg = _config.value
        val url = cfg.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("session_messages")
            .addQueryParameter("profile", profile)
            .addQueryParameter("session_id", sessionId)
            .addQueryParameter("limit", limit.coerceIn(1, SessionHistory.MAX_MESSAGES).toString())
            .build().toString()
        val req = Request.Builder().url(url).get()
            .header("Authorization", "Bearer ${cfg.token}")
            .build()
        clientCall(req) { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "session_messages ${r.code}: ${text.take(80)}")
            text
        }
    }

    /** Settings ▸ Watchkit "Test": GET /health — public probe, no token needed. */
    suspend fun health(baseUrl: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(baseUrl.trim().trimEnd('/') + "/health").get().build()
        clientCall(req) { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "watchkit health ${r.code}: ${text.take(80)}")
            try {
                val obj = HermesApi.parse(text)
                fun field(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive?)?.content
                "ok · watchkit v${field("version") ?: "?"} · up ${field("uptime_s") ?: "?"}s"
            } catch (e: Exception) { "ok (unparsed)" }
        }
    }

    private inline fun <T> clientCall(req: Request, crossinline block: (okhttp3.Response) -> T): T =
        HermesApi.DEFAULT.newCall(req).execute().use { block(it) }
}

/** One catalog row from watchkit `GET /catalog` (SPEC §12.3); `honored`/`group` are the
 *  CommandKind/CommandGroup names, lowercase, with the same meaning as on the watch side. */
@kotlinx.serialization.Serializable
data class CommandInfo(
    val name: String = "",
    val args: String = "",
    val desc: String = "",
    val honored: String = "",
    val group: String = "",
    val requires: String? = null,
    val source: String = "",
)

/**
 * The catalog payload. `honored` reaches the UI verbatim (the overlay shows "inject" / "prompt" /
 * "branch" per row) because WHAT the watch can do with a command is the whole point of the list —
 * the Runs API does not dispatch slashes, so an honest client must never pretend otherwise.
 */
@kotlinx.serialization.Serializable
data class CatalogResponse(
    val commands: List<CommandInfo> = emptyList(),
    val capabilities: Map<String, Boolean> = emptyMap(),
) {
    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        fun parse(text: String): CatalogResponse =
            json.decodeFromJsonElement(CatalogResponse.serializer(), json.parseToJsonElement(text))
    }
}

/** One row of watchkit `GET /agent_keys`. `key` is empty exactly when `has_key` is false. */
@kotlinx.serialization.Serializable
data class AgentKey(val profile: String = "", val has_key: Boolean = false, val key: String = "")

@kotlinx.serialization.Serializable
data class AgentKeysResponse(val agents: List<AgentKey> = emptyList()) {
    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        fun parse(text: String): AgentKeysResponse =
            json.decodeFromJsonElement(AgentKeysResponse.serializer(), json.parseToJsonElement(text))
    }
}

/** One /search row (SPEC §12.4). `snippet` carries [bracketed] hits; `title_match` is server-side
 *  because only the store knows whether the CHAT NAME matched. */
@kotlinx.serialization.Serializable
data class SearchHitDto(
    val id: String = "",
    val title: String = "",
    // Gateway timestamps arrive as unix SECONDS with fractional digits (`1793034407.203…`); a
    // Long field throws and the whole profile's search silently drops out — take it as Double.
    val last_active: Double = 0.0,
    val message_count: Int = 0,
    val profile: String = "",
    val hits: Int = 0,
    val snippet: String = "",
    val snippet_role: String = "",
    val title_match: Boolean = false,
) {
    /** Server sends epoch SECONDS (same convention as /sessions); watch maths in ms. */
    fun lastActiveMs(): Long = (last_active * 1000.0).toLong()
}

@kotlinx.serialization.Serializable
data class SearchResponse(val query: String = "", val data: List<SearchHitDto> = emptyList()) {
    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        fun parse(text: String): SearchResponse =
            json.decodeFromJsonElement(SearchResponse.serializer(), json.parseToJsonElement(text))
    }
}

/** `POST /session_fork` answer — [copied_messages] is what the toast reports. */
@kotlinx.serialization.Serializable
data class ForkResult(val id: String = "", val title: String = "", val copied_messages: Int = 0) {
    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        fun parse(text: String): ForkResult =
            json.decodeFromJsonElement(ForkResult.serializer(), json.parseToJsonElement(text))
    }
}

/** `POST /session_delete` answer. */
@kotlinx.serialization.Serializable
data class DeleteResult(val id: String = "", val deleted: Boolean = false, val messages: Int = 0) {
    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        fun parse(text: String): DeleteResult =
            json.decodeFromJsonElement(DeleteResult.serializer(), json.parseToJsonElement(text))
    }
}
