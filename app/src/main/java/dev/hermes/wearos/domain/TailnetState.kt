package dev.hermes.wearos.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * TAILNET (SPEC §11.4, shipped here): the app embeds a tsnet node; the tunnel — not the
 * phone, not the LAN — is THE transport ("tailnet only, always", replacing §11.2's
 * tailnet-first/LAN-fallback doctrine). This file is the pure truth over the Go engine's
 * status JSON: parse + phase derivation + the wrist string. No android imports — the
 * phase rules are unit-tested like Failover's.
 *
 * Phase rules (owner doctrine 2026-11-02): every Hermes call rides the tunnel. So the
 * health bar is not "backendState == Running" — it is *the gateway peer answers*: a
 * Running node whose gateway peer is down is DEGRADED, exactly as an ask timing out is
 * felt on the wrist. The engine emits `stopped` between runs; NeedsLogin with an authURL
 * is NEEDS_AUTH (the authkey path is the design; the URL is the honest last resort).
 */
enum class TailnetPhase { DISABLED, STOPPED, STARTING, NEEDS_AUTH, ACTIVE, DEGRADED }

@Serializable
data class TailnetSelf(
    val hostname: String = "",
    val dnsName: String = "",
    val ips: List<String>? = null,
    val online: Boolean = false,
)

@Serializable
data class TailnetPeer(
    val hostname: String = "",
    val dnsName: String = "",
    val ip: String = "",
    val online: Boolean = false,
    val relay: String = "",
)

@Serializable
data class TailnetStatus(
    val backendState: String = "stopped",
    val socksPort: Int = 0,
    val authURL: String = "",
    val error: String = "",
    val self: TailnetSelf? = null,
    val peers: List<TailnetPeer>? = null,
)

object TailnetLogic {
    private val Json_ = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): TailnetStatus =
        runCatching { Json_.decodeFromString<TailnetStatus>(raw) }.getOrElse { TailnetStatus(error = "bad status json: ${it.message}") }

    /** The gateway's peer row: match the endpoint URL's host against dnsName (exact,
     *  case-insensitive) or its tailnet IP (a 100.64/10 literal carried in the endpoint,
     *  SPEC §11.6). Blank host (no gateway configured yet) → no row, ACTIVE when Running. */
    fun gatewayPeer(s: TailnetStatus, gatewayHost: String): TailnetPeer? {
        val h = gatewayHost.trim().lowercase().ifEmpty { return null }
        return s.peers?.firstOrNull { p ->
            p.dnsName.lowercase().trimEnd('.') == h.trimEnd('.') || (p.ip.isNotEmpty() && p.ip == h)
        }
    }

    fun phase(s: TailnetStatus, enabled: Boolean, gatewayHost: String): TailnetPhase {
        if (!enabled) return TailnetPhase.DISABLED
        return when {
            s.backendState == "stopped" || s.backendState == "error" -> TailnetPhase.STOPPED
            s.backendState == "NeedsLogin" -> TailnetPhase.NEEDS_AUTH
            s.backendState != "Running" -> TailnetPhase.STARTING
            s.socksPort <= 0 -> TailnetPhase.STARTING      // Running but the proxy isn't up yet
            gatewayHost.isBlank() -> TailnetPhase.ACTIVE   // nothing to reach; node alone is up
            gatewayPeer(s, gatewayHost)?.online == true -> TailnetPhase.ACTIVE
            else -> TailnetPhase.DEGRADED                  // node up, gateway unreachable
        }
    }

    /** One honest line for Settings ▸ Tailnet + logcat (RouteReport-grade brevity). */
    fun describe(p: TailnetPhase, s: TailnetStatus, gatewayHost: String): String = when (p) {
        TailnetPhase.DISABLED -> "tailnet off"
        TailnetPhase.STOPPED -> s.error.ifBlank { "tailnet down" }
        TailnetPhase.STARTING -> "tailnet starting…"
        TailnetPhase.NEEDS_AUTH -> if (s.authURL.isNotBlank()) "tailnet needs auth — ${s.authURL}" else "tailnet needs a key"
        TailnetPhase.ACTIVE -> buildString {
            append("tailnet up · ").append(s.self?.dnsName?.ifBlank { s.self?.hostname ?: "?" } ?: "?")
            gatewayPeer(s, gatewayHost)?.let { append(" · gateway ${it.ip.ifBlank { it.dnsName }} online") }
        }
        TailnetPhase.DEGRADED -> if (s.peers.isNullOrEmpty()) "tailnet up · gateway $gatewayHost not on the tailnet"
            else "tailnet up · gateway ${gatewayPeer(s, gatewayHost)?.dnsName ?: gatewayHost} offline"
    }
}
