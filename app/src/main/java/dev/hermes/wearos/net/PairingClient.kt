package dev.hermes.wearos.net

import dev.hermes.wearos.domain.PairingCode
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ProxySelector
import java.util.concurrent.TimeUnit

/**
 * The claim packet the plugin serves ONCE at `GET /pair/claim?pin=` (200). Everything the watch
 * needs to enroll + point itself at the gateway in one shot. THE AUTHKEY IS THE SECRET: it never
 * appears in a log line — [redacted] is the only way this leaves into logcat.
 */
@Serializable
data class PairPacket(
    @SerialName("authkey") val authKey: String = "",
    @SerialName("tailnet") val tailnet: String = "",
    @SerialName("gateway_fqdn") val gatewayFqdn: String = "",
    @SerialName("gateway_port") val gatewayPort: Int = 0,
    @SerialName("watchkit_port") val watchkitPort: Int = 0,
    @SerialName("profile") val profile: String = "",
    @SerialName("api_key") val apiKey: String = "",
    // NULLABLE per the claim contract (plugin serves an explicit null when no watchkit
    // bearer is configured — proven live 2026-11-06; a non-null type deserialized that
    // null, threw, and the retry loop reported a happily-consumed PIN as "spent").
    @SerialName("watchkit_token") val watchkitToken: String? = null,
) {
    /** One safe line for logcat: identities + LENGTHS, never a key value. */
    fun redacted(): String =
        "tailnet=$tailnet gateway=$gatewayFqdn:$gatewayPort wk_port=$watchkitPort profile=$profile " +
            "auth.len=${authKey.length} key.len=${apiKey.length} wkToken.len=${watchkitToken?.length ?: 0}"
}

/**
 * THE BOOTSTRAP DOOR (SPEC §4/§11.4): the ONE OkHttp client in the app with NO TailnetRoute
 * selector — during pairing there IS no tunnel yet, so the claim API is dialed over plain HTTPS
 * to the plugin's funnel host `<handle>.ts.net`. Measured reality (2026-11-06): a Funnel answers
 * on the NODE's MagicDNS name — the wizard prints the exact line to type (e.g.
 * `tuxdesktop.tailb12aa5`); there is no `pair.` prefix on any hostname Tailscale mints. Everything else stays the tunnel's; this client is used
 * for nothing else and is disposable ("destroy when done").
 *
 * Protocol (server side: hermes-plugin pair endpoints):
 *   POST /pair/claim {"pin","tailnet","hostname"} → 202 queued
 *   GET  /pair/claim?pin=…                        → 204 pending | 200 packet | 410 spent/expired
 *   429 = rate-limited: back off and KEEP THE PIN UP.
 *
 * [Http] is the seam the JVM tests inject; [LiveHttp] is the real one. All pure pieces
 * (URL building, body encoding, status → outcome, packet parsing) sit in the companion so the
 * protocol is tested without a socket, mirroring the GatewaySessions test style.
 */
class PairingClient(private val http: Http = LiveHttp()) {

    /** Blocking one-shot exchange; bodyJson null = GET. Runs on the caller's IO context. */
    fun interface Http {
        fun exchange(method: String, url: String, bodyJson: String?): Res
    }

    data class Res(val code: Int, val body: String)

    /** One step of the flow, honest about what the funnel said. */
    sealed class Outcome {
        /** data object needs Kotlin 1.9+; this module is on 2.0 — fine. */
        data object Queued : Outcome()          // POST accepted, claim is parked
        data object Pending : Outcome()         // 204 — nothing claimed yet
        data class Claimed(val packet: PairPacket) : Outcome() // 200 — the one-shot payload
        data object Gone : Outcome()            // 410 — spent or expired; re-mint the PIN
        data object RateLimited : Outcome()     // 429 — back off, the PIN stays good
        /** 200 arrived but the body was unreadable — the packet is CONSUMED; retrying
         *  only trades the truth for a misleading "spent". Terminal, never re-polls. */
        data object Malformed : Outcome()
        data class Failed(val why: String) : Outcome()
    }

    suspend fun knock(handle: String, pin: String, hostname: String): Outcome =
        exchange { http.exchange("POST", claimUrl(handle), claimBody(pin, handle, hostname)) }

    suspend fun poll(handle: String, pin: String): Outcome =
        exchange { http.exchange("GET", pollUrl(handle, pin), null) }

    /**
     * KNOCK, then POLL with exponential backoff until the packet lands (or [maxWaitMs] runs
     * out — default 170 s, comfortably inside the PIN's 5-min TTL, so the wearer can always
     * knock again with the SAME PIN). 429/transport blips back off harder and NEVER burn the
     * PIN: only a 200 or 410 ends the wait early.
     */
    suspend fun claim(
        handle: String,
        pin: String,
        hostname: String,
        maxWaitMs: Long = 170_000L,
        sleep: suspend (Long) -> Unit = { delay(it) },
        now: () -> Long = { System.currentTimeMillis() },
    ): Outcome {
        val first = knock(handle, pin, hostname)
        if (first !is Outcome.Queued) return first
        val until = now() + maxWaitMs
        var backoff = 1_500L
        var last: Outcome = first
        while (now() < until) {
            sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(8_000L)
            when (val o = poll(handle, pin)) {
                is Outcome.Claimed, Outcome.Gone, Outcome.Malformed -> return o
                Outcome.Pending -> last = o
                // RateLimited / Failed: keep the PIN up, back off harder, keep waiting.
                else -> { last = o; backoff = (backoff * 3).coerceAtMost(20_000L) }
            }
        }
        return last
    }

    private inline fun exchange(block: () -> Res): Outcome = try {
        val r = block()
        outcomeOf(r.code, r.body)
    } catch (e: Exception) {
        // The funnel URL carries no secret; the message may carry a host — fine. NEVER the body.
        Outcome.Failed(e.message?.take(80) ?: "pair unreachable")
    }

    /** The real transport — and the reason this file is the bootstrap door: NO TailnetRoute. */
    class LiveHttp : Http {
        private val client = OkHttpClient.Builder()
            // Explicit DIRECT (a hand-rolled selector: ProxySelector.of is JDK 21, not Android):
            // never TailnetRoute.SELECTOR — pairing happens BEFORE a tunnel exists, and a
            // selector pointing at a dead SOCKS port would deadlock the flow.
            .proxySelector(object : ProxySelector() {
                override fun select(uri: java.net.URI?) = listOf(java.net.Proxy.NO_PROXY)
                override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {}
            })
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        override fun exchange(method: String, url: String, bodyJson: String?): Res =
            client.newCall(
                Request.Builder().url(url)
                    .method(
                        method,
                        bodyJson?.toRequestBody("application/json; charset=utf-8".toMediaType()),
                    )
                    .build(),
            ).execute().use { r -> Res(r.code, r.body?.string().orEmpty()) }
    }

    companion object {
        private val Json_ = Json { ignoreUnknownKeys = true }

        @Serializable
        private data class ClaimRequest(val pin: String, val tailnet: String, val hostname: String)

        /** `tux-desktop.tailb12aa5` → `https://tux-desktop.tailb12aa5.ts.net/pair/claim`.
         *  The handle IS the door host (the node prefix of its MagicDNS name — what
         *  `hermes watch setup --fix serve` prints); no synthetic `pair.` prefix. */
        fun claimUrl(handle: String): String {
            val h = PairingCode.normalizeHandle(handle)
            require(h.isNotEmpty()) { "pairing address needed (the host the wizard prints, before .ts.net)" }
            return "https://$h.ts.net/pair/claim"
        }

        fun pollUrl(handle: String, pin: String): String {
            require(PairingCode.isPin(pin)) { "pin must be 6 digits, no leading zero" }
            return "${claimUrl(handle)}?pin=$pin"
        }

        fun claimBody(pin: String, handle: String, hostname: String): String {
            require(PairingCode.isPin(pin)) { "pin must be 6 digits, no leading zero" }
            val h = PairingCode.normalizeHandle(handle)
            require(h.isNotEmpty()) { "tailnet handle needed (the name before .ts.net)" }
            return Json_.encodeToString(
                ClaimRequest.serializer(),
                ClaimRequest(pin = pin, tailnet = h, hostname = hostname.trim().take(64)),
            )
        }

        /** 200-body → packet; garbage → null (callers surface Failed, never a crash). */
        fun parsePacket(body: String): PairPacket? =
            runCatching { Json_.decodeFromString<PairPacket>(body) }.getOrNull()
            ?.takeIf { it.gatewayFqdn.isNotBlank() }

        /** The status-code contract, as pure data (what the JVM tests pin down). */
        fun outcomeOf(code: Int, body: String): Outcome = when (code) {
            202 -> Outcome.Queued
            204 -> Outcome.Pending
            200 -> parsePacket(body)?.let { Outcome.Claimed(it) } ?: Outcome.Malformed
            410 -> Outcome.Gone
            429 -> Outcome.RateLimited
            else -> Outcome.Failed("HTTP $code")
        }
    }
}
