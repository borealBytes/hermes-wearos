package dev.hermes.wearos.net

import dev.hermes.wearos.domain.ModelCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Hermes API server (runs surface) over the tailnet. Wire contract verified against
 * Hermes Agent 0.21.7 (gateway/platforms/api_server_runs.py):
 *  - POST /v1/runs {input, session_id} + `Idempotency-Key` header → {run_id, status, replayed}
 *  - GET  /v1/runs/{id}/events → SSE frames `id: <seq>` + `data: {"event": ..., ...}`
 *  - events: message.delta {delta}, message.interim {text, already_streamed},
 *    tool.started {tool, preview}, tool.completed, approval.request {command?, choices?},
 *    run.completed {output,...}, run.failed, run.cancelled
 *  - POST /v1/runs/{id}/approval {choice, request_id?}
 *  - GET  /v1/runs/{id} → status; terminal snapshots carry `output` (resume-safe final answer)
 */
class HermesApi(
    val baseUrl: String,              // e.g. http://hermes.tailnet.ts.net:8642/p/wear (failover winner + /p/agent)
    private val token: String,
    private val client: OkHttpClient = DEFAULT,
) {
    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val Json_ = Json { ignoreUnknownKeys = true }
        /** The gateway's virtual model alias = "serve this profile's configured default". */
        const val VIRTUAL_MODEL = "hermes-agent"
        val DEFAULT = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)   // SSE: rely on server keepalive
            .proxySelector(TailnetRoute.SELECTOR)    // SPEC §11.4: the tunnel, when it's up
            .build()

        fun parse(data: String): JsonObject = Json_.parseToJsonElement(data).jsonObject
        /** true/false as BOOLEANs only — a `"true"` string is a value, not a flag. */
        fun JsonElement.booleanOrNull(): Boolean? = (this as? JsonPrimitive)?.let { p ->
            when {
                p.isString -> null
                p.content == "true" -> true
                p.content == "false" -> false
                else -> null
            }
        }

        fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
        fun JsonObject.choiceNames(): List<String> =
            ((this["choices"] as? JsonArray)?.mapNotNull { el ->
                (el as? JsonObject)?.str("id") ?: (el as? JsonPrimitive)?.contentOrNull
            })?.takeIf { it.isNotEmpty() } ?: listOf("approve", "deny")
    }

    private fun request(url: String, body: String? = null, headers: Map<String, String> = emptyMap()): Request =
        Request.Builder()
            .url(url)
            .method(if (body == null) "GET" else "POST", body?.toRequestBody(JSON_MEDIA))
            .header("Authorization", "Bearer $token")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

    private fun esc(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t") + "\""

    data class RunAccepted(val runId: String, val status: String, val replayed: Boolean)

    /**
     * Durably idempotent: retrying with the same [idempotencyKey] never starts a second run.
     * [model] is the per-session pick (SPEC §12.7) — the literal `"hermes-agent"` virtual alias
     * means "whatever this profile defaults to", anything else routes the run on the gateway
     * (`_resolve_route` / requested-model, verified live: `runtime.model` comes back as asked).
     */
    fun submitRun(input: String, sessionId: String, idempotencyKey: String, model: String = VIRTUAL_MODEL): RunAccepted {
        val body = "{\"input\":${esc(input)},\"session_id\":${esc(sessionId)},\"model\":${esc(model)}}"
        client.newCall(request("$baseUrl/v1/runs", body, mapOf("Idempotency-Key" to idempotencyKey))).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "run submit ${r.code}: ${text.take(160)}")
            val obj = parse(text)
            return RunAccepted(obj.str("run_id") ?: throw ApiException(r.code, "no run_id"), obj.str("status") ?: "started", obj.str("replayed")?.toBoolean() ?: false)
        }
    }

    data class RunSnapshot(val status: String, val output: String?, val approval: JsonObject?)

    /** Poll run state; terminal snapshots carry the final `output`. */
    fun runStatus(runId: String): RunSnapshot {
        client.newCall(request("$baseUrl/v1/runs/$runId")).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "run status ${r.code}")
            val obj = parse(text)
            return RunSnapshot(obj.str("status") ?: "unknown", obj.str("output"), obj["approval"] as? JsonObject)
        }
    }

    fun eventsUrl(runId: String) = "$baseUrl/v1/runs/$runId/events"
    fun authHeader(): Pair<String, String> = "Authorization" to "Bearer $token"

    /**
     * MODEL PICKER inventory (SPEC §12.7): GET /api/model/options → providers catalog. The
     * gateway advertises this via `model_options` in /v1/capabilities; "enabled on this gateway"
     * means the provider is authenticated AND lists models (the dashboard/TUI picker rule).
     * Parsing/filtering lives in [ModelCatalog]; throws on any transport/HTTP failure.
     */
    fun modelOptions(): ModelCatalog.Catalog {
        client.newCall(request("$baseUrl/api/model/options")).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "model options ${r.code}: ${text.take(120)}")
            return ModelCatalog.parse(parse(text))
        }
    }

    /**
     * The model THIS session last ran on: GET /api/sessions/{id} → `session.model`. The gateway
     * stamps the column with every served model (verified live), so an adopted or multi-device
     * session keeps answering on the model its last turn used. 404 (fresh session) or no column →
     * null = "gateway default, nothing picked yet".
     */
    fun sessionModel(sessionId: String): String? {
        return try {
            client.newCall(request("$baseUrl/api/sessions/${java.net.URLEncoder.encode(sessionId, "UTF-8")}"))
                .execute().use { r ->
                    if (!r.isSuccessful) return null
                    val obj = parse(r.body?.string().orEmpty())
                    val session = obj["session"] as? JsonObject ?: obj
                    session.str("model")?.takeIf { it != VIRTUAL_MODEL }
                }
        } catch (e: Exception) {
            null // model continuity is a nice-to-have; a dead read must never sink the chat open
        }
    }

    /**
     * Gateway transcript route (SPEC §12.13): GET /api/sessions/{id}/messages?limit=&order=latest
     * → `{object:"list", data:[{role,content,…}]}` — bearer-authed, cross-source (answers for a
     * desktop row too, unlike /v1/runs). Raw JSON out; SessionHistory.parseGateway maps it. Throws
     * ApiException (401 = no key for this profile, 404 = not this lane) — the caller falls back
     * to watchkit's keyless read.
     */
    fun sessionMessagesJson(sessionId: String, limit: Int): String {
        client.newCall(request(
            "$baseUrl/api/sessions/${java.net.URLEncoder.encode(sessionId, "UTF-8")}/messages" +
                "?limit=$limit&order=latest"))
            .execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "messages ${r.code}: ${text.take(120)}")
            return text
        }
    }

    /**
     * Gateway health probe (Settings "Test"). Verified: GET /v1/health → 200
     * `{status:"ok", platform:"hermes-agent", version:"0.21.7"}`. Throws on any failure.
     */
    fun health(): String {
        client.newCall(request("$baseUrl/v1/health")).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "health ${r.code}: ${text.take(120)}")
            val obj = try { parse(text) } catch (e: Exception) { return "ok (unparsed: ${text.take(60)})" }
            return "${obj.str("status") ?: "ok"} · ${obj.str("platform") ?: "hermes"} v${obj.str("version") ?: "?"}"
        }
    }

    data class SessionInfo(val id: String, val title: String, val lastActiveMs: Long = 0L, val messages: Int = 0)

    /**
     * Gateway session list for label sync + the sessions fast-path (owner ask #2). Verified: GET
     * /api/sessions → 200 `{object:"list", data:[{id, title, last_active, message_count, ...}]}`
     * (sessions are keyed by the same id we submit as session_id). /v1/sessions and
     * /api/sessions/list were 404.
     */
    fun listSessions(): List<SessionInfo> =
        GatewaySessions.parse(sessionsJson()).map { SessionInfo(it.id, it.title, it.lastActiveMs, it.messages) }

    /** Raw `/api/sessions` body — the sessions screen caches this text for offline reads. */
    fun sessionsJson(): String {
        client.newCall(request("$baseUrl/api/sessions")).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "sessions ${r.code}")
            return text
        }
    }

    /**
     * Server-side run cancel (ask #3). Route table verified in
     * gateway/platforms/api_server_runs.py `_http_routes`: POST /v1/runs/{run_id}/stop →
     * `{run_id, status:"stopping"}` (`run_stop` capability advertised by /v1/capabilities).
     * Best-effort from the watch: the client-side stop already detached the stream either way.
     */
    fun stopRun(runId: String): String {
        client.newCall(request("$baseUrl/v1/runs/$runId/stop", "{}")).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, "run stop ${r.code}: ${text.take(120)}")
            return text.take(120)
        }
    }

    /**
     * `/steer` (SPEC §12.3, gateway cap `run_steer: true`): inject guidance into the run IN
     * FLIGHT — `POST /v1/runs/{run_id}/steer` — instead of queueing another turn. The dial's
     * "steer" lane and the slash overlay both land here, so `runId` is whatever the session last
     * accepted; a 404/409 means the run already ended and the caller falls back to a plain ask.
     * Returns the gateway's echo text (short); throws ApiException/IOException otherwise.
     */
    /**
     * GATEWAY FEATURE FLAGS (`GET {base}/v1/capabilities`, the key-holder route). The slash
     * overlay dims a row whose `requires` gate the gateway does not advertise, so `/steer` on a
     * gateway without `run_steer` shows the reason instead of firing a request that must fail —
     * the honest half of SPEC §12.3. 401/unreachable → empty map = "unknown", which the overlay
     * renders as enabled rather than falsely greyed.
     */
    suspend fun capabilities(): Map<String, Boolean> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(request("$baseUrl/v1/capabilities")).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiException(r.code, "capabilities ${r.code}: ${text.take(120)}")
                parse(text).entries.mapNotNull { (k, v) ->
                    val b = (v as? JsonPrimitive)?.booleanOrNull() ?: return@mapNotNull null
                    k to b
                }.toMap()
            }
        }

    suspend fun steerRun(runId: String, text: String): String {
        val body = "{\"input\":${esc(text)}}"
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(request("$baseUrl/v1/runs/$runId/steer", body)).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiException(r.code, "run steer ${r.code}: ${txt.take(160)}")
                txt.take(160)
            }
        }
    }

    /**
     * Fork a gateway session (`POST /api/sessions/{id}/fork`, capability `session_fork`) and
     * return the NEW session id. Live probe facts this relies on: the copy is the FULL history
     * (`up_to_message_id` is accepted and ignored — the truncate route does not exist yet,
     * SPEC §13.5) and the gateway titles the child "<same title> #2". Fork-on-edit therefore
     * says "branched" on the transcript instead of pretending the superseded turn vanished.
     */
    suspend fun forkSession(sessionId: String): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(request("$baseUrl/api/sessions/$sessionId/fork", "{}")).execute().use { r ->
                val txt = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiException(r.code, "session fork ${r.code}: ${txt.take(160)}")
                val sessionObj = (parse(txt)["session"] as? kotlinx.serialization.json.JsonObject?)
                    ?: throw ApiException(r.code, "session fork: no session in response")
                (sessionObj["id"] as? JsonPrimitive?)?.content
                    ?: throw ApiException(r.code, "session fork: no id")
            }
        }
    }

    fun respondApproval(runId: String, choice: String, requestId: String?) {
        val reqId = if (requestId != null) ",\"request_id\":${esc(requestId)}" else ""
        val body = "{\"choice\":${esc(choice)}$reqId}"
        client.newCall(request("$baseUrl/v1/runs/$runId/approval", body)).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "approval ${r.code}: ${r.body?.string()?.take(120)}")
        }
    }
}

class ApiException(val code: Int, message: String) : Exception(message)
