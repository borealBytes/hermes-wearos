package dev.hermes.wearos.net

import dev.hermes.wearos.net.HermesApi.Companion.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * THE PRIVATE LANE (SPEC §11.4, D8/D13/D17): the plugin's tailnet-IP-bound server
 * (`hermes-plugin/private_api.py`, default :42654) — the ONLY path that ever carries a
 * profile's `API_SERVER_KEY` to the watch. It rides TAILNET ROUTE (every other client does):
 * Funnel cannot front it, an anonymous dial gets 401, and the FIRST session call must carry
 * the pairing secret so the gateway can bind this device to the tailnet node it dialed from
 * (whois-on-socket identity law). After that the secret is dead weight and gets wiped.
 *
 *   POST /watch/<profile>/session  {"v":2,"secret":"<hex>"} → 200 {api_key, watchkit_token?, …}
 *   GET  /watch/<profile>/events?since=N                     → 200 {since, events:[…]}
 *   POST /watch/<profile>/ack      {"up_to":N}               → 200 {remaining}
 *   POST /watch/<profile>/forget   {}                        → 200 (full revoke started)
 *
 * The events queue is the watch's PRIVATE outbox (cron alerts D14 + agent messages): every
 * row {id,kind,text,agent,title,fold?}. Cross-agent keys (D17): `sessionFor(agent)` answers
 * that agent's key only when the Wear Kit pane granted it — 403 otherwise, which the learner
 * reports as "no grant", never as a silent empty map (the v1 /agent_keys wholesale export is
 * retired). Process singleton like Watchkit; [http] is the JVM-test seam.
 */
data class LaneSession(
    val apiKey: String,
    val watchkitToken: String?,
    val gatewayFqdn: String,
    val gatewayPort: Int,
    val watchkitPort: Int,
)

/** One outbox row. `fold` = extra collapsed events of the same kind+agent+title (cron D14). */
data class LaneEvent(
    val id: Long,
    val kind: String,
    val text: String,
    val agent: String,
    val title: String,
    val fold: Int,
)

data class LaneEvents(val since: Long, val events: List<LaneEvent>)

object PrivateLane {

    /** Blocking one-shot exchange; bodyJson null = GET. Runs on the caller's IO context. */
    fun interface Http {
        fun exchange(method: String, url: String, bodyJson: String?, bearer: String): Res
    }

    data class Res(val code: Int, val body: String)

    /** Outcomes of a lane call, honest about what the tailnet said. */
    sealed class Outcome {
        data class Session(val session: LaneSession) : Outcome()
        data class Events(val events: LaneEvents) : Outcome()
        data object Acked : Outcome()
        data object Forgotten : Outcome()
        /** 401 — token unknown/revoked: the watch is NOT paired (or just was unpaired). */
        data object Unpaired : Outcome()
        /**
         * 403 — refused: not a tailnet connection / node rebind / cross-agent WITHOUT a
         * grant (D17) / an agent with no key at all. The message says which.
         */
        data class Refused(val why: String) : Outcome()
        /** 410 — device row gone (revoked from the PC): treat exactly like Unpaired. */
        data object Gone : Outcome()
        data class Failed(val why: String) : Outcome()
    }

    // ── config (WearApp sets it after pairing and at boot from AppSettings) ──────────
    @Volatile var baseUrl: String = ""   // http://<gateway-fqdn>:42654
    @Volatile var token: String = ""     // wtk-… device token
    @Volatile var profile: String = ""   // the AGENT this watch is paired to

    val configured: Boolean get() = baseUrl.isNotEmpty() && token.isNotEmpty() && profile.isNotEmpty()

    fun setConfig(baseUrl: String, token: String, profile: String) {
        this.baseUrl = baseUrl.trim().trimEnd('/'); this.token = token.trim(); this.profile = profile.trim()
    }

    fun clearConfig() { baseUrl = ""; token = ""; profile = "" }

    /** THE test seam (JVM scripts record/replay; production is the tunnel client below). */
    @Volatile var http: Http = LiveHttp()

    suspend fun session(secret: String): Outcome = sessionFor(profile, secret)

    /** Session against ANOTHER agent's lane route: the D17 grant path for per-agent keys. */
    suspend fun sessionFor(agent: String, secret: String): Outcome =
        post("watch/$agent/session", sessionBody(secret))

    suspend fun events(since: Long): Outcome = withContext(Dispatchers.IO) {
        exchange { http.exchange("GET", "${url("events")}?since=$since", null, token) }
    }

    suspend fun ack(upTo: Long): Outcome = post("watch/$profile/ack", "{\"v\":2,\"up_to\":$upTo}")

    /** Self-unpair (the watch's own Unpair confirm): the plugin runs the FULL revoke. */
    suspend fun forget(): Outcome = post("watch/$profile/forget", "{\"v\":2}")

    private suspend fun post(path: String, bodyJson: String): Outcome = withContext(Dispatchers.IO) {
        exchange { http.exchange("POST", "$baseUrl/${path.trim('/')}", bodyJson, token) }
    }

    private inline fun exchange(block: () -> Res): Outcome = try {
        val r = block()
        outcomeOf(r.code, r.body)
    } catch (e: Exception) {
        Outcome.Failed(e.message?.take(80) ?: "private lane unreachable")
    }

    /** THE tunnel client: TailnetRoute.SELECTOR is installed — plain sockets would 401 here. */
    class LiveHttp : Http {
        private val client = OkHttpClient.Builder()
            .proxySelector(TailnetRoute.SELECTOR)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        override fun exchange(method: String, url: String, bodyJson: String?, bearer: String): Res =
            client.newCall(
                Request.Builder().url(url)
                    .header("Authorization", "Bearer $bearer")
                    .method(method, bodyJson?.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build(),
            ).execute().use { r -> Res(r.code, r.body?.string().orEmpty()) }
    }

    private fun url(verb: String): String = "$baseUrl/watch/${profile.trim()}/$verb"

    // ── pure wire layer (what the JVM tests pin; GatewaySessions style) ──────────────
    private val Json_ = Json { ignoreUnknownKeys = true }

    fun sessionBody(secret: String): String =
        if (secret.matches(Regex("^[0-9a-f]{64}$"))) "{\"v\":2,\"secret\":\"$secret\"}"
        else "{\"v\":2}"

    fun parseSession(body: String): LaneSession? =
        runCatching { Json_.parseToJsonElement(body) as? JsonObject }.getOrNull()?.let { o ->
            LaneSession(
                apiKey = o.str("api_key").orEmpty(),
                watchkitToken = o.str("watchkit_token"),
                gatewayFqdn = o.str("gateway_fqdn").orEmpty(),
                gatewayPort = o.intOf("gateway_port") ?: 0,
                watchkitPort = o.intOf("watchkit_port") ?: 0,
            )
        }

    fun parseEvents(body: String): LaneEvents? =
        runCatching { Json_.parseToJsonElement(body) as? JsonObject }.getOrNull()?.let { o ->
            val rows = o["events"] as? JsonArray ?: return null
            LaneEvents(
                since = (o["since"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L,
                events = rows.mapNotNull { el ->
                    val r = el as? JsonObject ?: return@mapNotNull null
                    val id = (r["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                        ?: return@mapNotNull null
                    LaneEvent(
                        id = id,
                        kind = r.str("kind") ?: "msg",
                        text = r.str("text").orEmpty(),
                        agent = r.str("agent").orEmpty(),
                        title = r.str("title").orEmpty(),
                        fold = (r["fold"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                    )
                },
            )
        }

    private fun JsonObject.intOf(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    /** Status-code contract (pure; the JVM tests pin this). */
    fun outcomeOf(code: Int, body: String): Outcome = when (code) {
        200 -> when {
            body.contains("\"api_key\"") ->
                parseSession(body)?.let { Outcome.Session(it) } ?: Outcome.Failed("session payload unreadable")
            body.contains("\"events\"") ->
                parseEvents(body)?.let { Outcome.Events(it) } ?: Outcome.Failed("events payload unreadable")
            else -> Outcome.Acked // ack + forget both answer plain 200 JSON
        }
        401 -> Outcome.Unpaired
        410 -> Outcome.Gone
        403 -> Outcome.Refused(errorOf(body))
        else -> Outcome.Failed("HTTP $code")
    }

    private fun errorOf(body: String): String =
        runCatching { Json_.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?.str("error")?.take(120).orEmpty().ifEmpty { "private lane refused" }
}
