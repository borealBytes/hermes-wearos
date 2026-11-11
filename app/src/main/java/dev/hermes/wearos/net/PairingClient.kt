package dev.hermes.wearos.net

import dev.hermes.wearos.domain.PairingCode
import dev.hermes.wearos.net.HermesApi.Companion.str
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.net.ProxySelector
import java.util.concurrent.TimeUnit

/**
 * THE BOOTSTRAP DOOR — PAIRING v2 (SPEC §4, review 2026-11-09). Still the ONE OkHttp client
 * with no TailnetRoute (pairing happens BEFORE a tunnel exists), but the protocol flipped:
 *
 *   POST /pair/begin {"v":2,"secret":"<64 hex>","label":"<watch>","tailnet":"…"}
 *                    → 201 {"code":"429183","expires_in":300}   — the GATEWAY issues the code
 *   GET  /pair/poll?secret=<hex>
 *        → 204 still waiting · 200 {"state":"ready","authkey":…} ONCE · 410 gone · 429 slow down
 *   POST /pair/ack   {"v":2,"secret":"<hex>"} → 200 {"token":"wtk-…"} (device token, once)
 *   *  /pair/claim   → 426 "update the watch app" (v1 PIN protocol retired)
 *
 * The code NEVER rides a URL (pickup speaks the device secret) and the public packet carries
 * NO keys: `api_key` + the watchkit bearer arrive later, over the PRIVATE lane, after this
 * watch's node joined the tailnet (see net/PrivateLane + WearApp.pairFinishSession).
 * [Http] is the JVM-test seam; the pure companion (URLs, bodies, status→outcome) is what the
 * tests pin, mirroring the GatewaySessions style.
 */
@Serializable
data class PickupPacket(
    @SerialName("authkey") val authKey: String = "",
    @SerialName("key_id") val keyId: String = "",
    @SerialName("profile") val profile: String = "",
    @SerialName("gateway_fqdn") val gatewayFqdn: String = "",
    @SerialName("gateway_port") val gatewayPort: Int = 0,
    @SerialName("watchkit_port") val watchkitPort: Int = 0,
    @SerialName("private_port") val privatePort: Int = 0,
) {
    /** One safe line for logcat: identities + LENGTHS, never a key value. */
    fun redacted(): String =
        "key_id=$keyId gateway=$gatewayFqdn:$gatewayPort wk_port=$watchkitPort lane=$privatePort " +
            "profile=$profile auth.len=${authKey.length}"
}

class PairingClient(private val http: Http = LiveHttp()) {

    /** Blocking one-shot exchange; bodyJson null = GET. Runs on the caller's IO context. */
    fun interface Http {
        fun exchange(method: String, url: String, bodyJson: String?): Res
    }

    data class Res(val code: Int, val body: String)

    /** One step of the flow, honest about what the door said. */
    sealed class Outcome {
        /** begin accepted: SHOW THE CODE (the gateway issued it — the watch never chooses). */
        data class Begun(val code: String, val expiresInSec: Int) : Outcome()
        data object Pending : Outcome()          // 204 — a human still must type the code
        data class Ready(val packet: PickupPacket) : Outcome()  // approved — collect it
        data class Tokened(val deviceToken: String) : Outcome() // acked — the device token, once
        /** pair() terminal success: the pickup AND the device token, in one hand. */
        data class Completed(val packet: PickupPacket, val deviceToken: String) : Outcome()
        data object Gone : Outcome()             // 410 — expired/unknown/already collected
        data object RateLimited : Outcome()      // 429 — back off; the flow stays alive
        /** 403 with a message the human should read (window closed / claim locked / too fast). */
        data class Refused(val why: String) : Outcome()
        /** 426 — a v1 door: the gateway's plugin is older than this app. */
        data object ServerTooOld : Outcome()
        /** 200 arrived but the body was unreadable — the pickup is CONSUMED; terminal. */
        data object Malformed : Outcome()
        data class Failed(val why: String) : Outcome()
    }

    /** A fresh 256-bit device secret (hex, 64 chars) — generated ONCE per pairing flow. */
    fun newSecret(): String {
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    suspend fun begin(handle: String, secret: String, label: String): Outcome =
        exchange { http.exchange("POST", doorUrl(handle, "begin"), beginBody(secret, handle, label)) }

    suspend fun poll(handle: String, secret: String): Outcome =
        exchange { http.exchange("GET", pollUrl(handle, secret), null) }

    suspend fun ack(handle: String, secret: String): Outcome =
        exchange { http.exchange("POST", doorUrl(handle, "ack"), ackBody(secret)) }

    /**
     * KNOCK (begin) → POLL with backoff until the code is approved → ACK for the device token.
     * [onCode] fires the moment the gateway's code lands (the UI shows it; it is NOT ours).
     * 429/transport blips back off harder and never burn the flow: only 410 (or the caller's
     * deadline) ends the wait. [maxWaitMs] defaults inside the code's 5-min TTL.
     */
    suspend fun pair(
        handle: String,
        secret: String,
        label: String,
        maxWaitMs: Long = 170_000L,
        sleep: suspend (Long) -> Unit = { delay(it) },
        now: () -> Long = { System.currentTimeMillis() },
        onCode: (String) -> Unit = {},
    ): Outcome {
        val begun = begin(handle, secret, label)
        if (begun !is Outcome.Begun) return begun
        onCode(begun.code)
        val until = now() + maxWaitMs
        var backoff = 2_000L
        var last: Outcome = Outcome.Pending
        while (now() < until) {
            sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(8_000L)
            when (val o = poll(handle, secret)) {
                is Outcome.Ready -> {
                    val a = ack(handle, secret)
                    if (a is Outcome.Tokened) return Outcome.Completed(o.packet, a.deviceToken)
                    last = a
                    if (a is Outcome.Malformed) return a // pickup consumed; ack unreadable is terminal
                }
                Outcome.Gone, Outcome.Malformed, Outcome.ServerTooOld -> return o
                Outcome.Pending -> last = o
                // RateLimited / Refused / Failed: keep the code up, back off harder, keep waiting.
                else -> { last = o; backoff = (backoff * 3).coerceAtMost(20_000L) }
            }
        }
        return last
    }

    private inline fun exchange(block: () -> Res): Outcome = try {
        val r = block()
        outcomeOf(r.code, r.body)
    } catch (e: Exception) {
        // The door URL carries no secret; the message may carry a host — fine. NEVER the body.
        Outcome.Failed(e.message?.take(80) ?: "pairing door unreachable")
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
        // encodeDefaults ON: `"v":2` is a PROTOCOL FIELD (the door distinguishes v2 from a v1
        // body by it) — it must ride the wire even when equal to the default, never be omitted
        // by kotlinx's default-skip.
        private val Json_ = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        @Serializable
        private data class BeginRequest(
            val v: Int = 2,
            val secret: String,
            val label: String,
            val tailnet: String,
        )

        @Serializable
        private data class AckRequest(val v: Int = 2, val secret: String)

        @Serializable
        private data class BeginReply(val code: String = "", val expires_in: Int = 0)

        @Serializable
        private data class AckReply(val token: String = "")

        /** The funnel door host for one verb: `tux-desktop.tailb12aa5` → https://…/pair/<verb>. */
        fun doorUrl(handle: String, verb: String): String {
            val h = PairingCode.normalizeHandle(handle)
            require(h.isNotEmpty()) { "pairing address needed (the host the wizard prints, before .ts.net)" }
            return "https://$h.ts.net/pair/$verb"
        }

        /** THE review fix: the poll URL carries the 256-bit secret — the CODE rides no URL. */
        fun pollUrl(handle: String, secret: String): String {
            require(SECRET_RE.matches(secret)) { "device secret must be 64 hex chars" }
            return "${doorUrl(handle, "poll")}?secret=$secret"
        }

        fun beginBody(secret: String, handle: String, label: String): String {
            require(SECRET_RE.matches(secret)) { "device secret must be 64 hex chars" }
            val h = PairingCode.normalizeHandle(handle)
            require(h.isNotEmpty()) { "tailnet handle needed (the name before .ts.net)" }
            return Json_.encodeToString(
                BeginRequest.serializer(),
                BeginRequest(secret = secret, label = label.trim().take(64), tailnet = h),
            )
        }

        fun ackBody(secret: String): String {
            require(SECRET_RE.matches(secret)) { "device secret must be 64 hex chars" }
            return Json_.encodeToString(AckRequest.serializer(), AckRequest(secret = secret))
        }

        private val SECRET_RE = Regex("^[0-9a-f]{64}$")

        fun parsePickup(body: String): PickupPacket? =
            runCatching { Json_.decodeFromString<PickupPacket>(body) }.getOrNull()
                ?.takeIf { it.gatewayFqdn.isNotBlank() && it.authKey.isNotBlank() }

        fun parseCode(body: String): Outcome.Begun? =
            runCatching { Json_.decodeFromString<BeginReply>(body) }.getOrNull()
                ?.takeIf { PairingCode.isPin(it.code) }
                ?.let { Outcome.Begun(it.code, it.expires_in) }

        fun parseToken(body: String): Outcome.Tokened? =
            runCatching { Json_.decodeFromString<AckReply>(body) }.getOrNull()
                ?.takeIf { it.token.startsWith("wtk-") }
                ?.let { Outcome.Tokened(it.token) }

        private fun errorOf(body: String): String =
            runCatching { Json_.decodeFromString(kotlinx.serialization.json.JsonObject.serializer(), body) }
                .getOrNull()?.get("error")?.toString()?.trim('"')?.take(120).orEmpty()
                .ifEmpty { "pairing door refused" }

        /** The status-code contract, as pure data (what the JVM tests pin down). */
        /** Status-code contract (pure; the JVM tests pin this). THE 200 BRANCH PARSES, IT DOES
         *  NOT SNIFF: the door serializes with stdlib json (`"state": "ready"` — a space after
         *  every colon), so substring probes on compact JSON silently fell through to
         *  Malformed on the live door (caught in the 2026-11-09 ceremony). Key presence after a
         *  real parse is the whole contract. */
        fun outcomeOf(code: Int, body: String): Outcome = when (code) {
            201 -> parseCode(body) ?: Outcome.Malformed
            204 -> Outcome.Pending
            200 -> {
                val o = runCatching {
                    Json_.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
                }.getOrNull()
                when {
                    o == null -> Outcome.Malformed
                    o.containsKey("authkey") || o.str("state") == "ready" ->
                        parsePickup(body)?.let { Outcome.Ready(it) } ?: Outcome.Malformed
                    o.containsKey("token") -> parseToken(body) ?: Outcome.Malformed
                    else -> Outcome.Malformed
                }
            }
            410 -> Outcome.Gone
            429 -> Outcome.RateLimited
            426 -> Outcome.ServerTooOld
            403, 423 -> Outcome.Refused(errorOf(body))
            else -> Outcome.Failed("HTTP $code")
        }
    }
}
