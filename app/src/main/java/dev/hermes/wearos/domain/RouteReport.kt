package dev.hermes.wearos.domain

/**
 * Turns a raw network exception into a line a wrist can act on (SPEC §11.5).
 *
 * WHY THIS EXISTS (owner report, 2026-10-29: *"it said it couldn't connect over tailnet to gateway
 * when I was on wifi — I even had the phone on tailnet so I thought it should have worked"*): the
 * chat surface used to paste OkHttp's own words, so what appeared on the watch was
 * `Unable to resolve host "labhost.tailca7ee1.ts.net": No address associated with hostname`.
 * That sentence is true, useless, and — worse — actively misleading: the phone's tailnet membership
 * says NOTHING about the watch, which has its own network stack, no tailscaled, and therefore no
 * MagicDNS. A `.ts.net` name can only resolve on a device that is itself in the tailnet — and the
 * shipped embedded node (SPEC §11.4, "tailnet only, always") IS that device, so a `.ts.net` DNS
 * miss now means the watch's own tunnel is not enrolled (or not up). The fix is to enroll or
 * repair the tunnel — Pair — which is exactly what the message has to say.
 *
 * Rules:
 *  • only rewrite what is genuinely a routing failure (DNS miss / connect timeout / refused).
 *    Anything else keeps its own message: lying about a real error is worse than a dry one.
 *  • name the ACTION and where to do it, in roughly the length the 450 ms spirit line taught us.
 *  • never claim a route was tried when the probe map says otherwise ([explain]'s [tried] is the
 *    failover machine's own record, so the count cannot drift from what really happened).
 */
object RouteReport {

    /** MagicDNS names (.ts.net) and the tailnet's CGNAT range — routable ONLY inside the tailnet. */
    fun isTailnetUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        if (u.isEmpty()) return false
        val host = run {
            val scheme = u.indexOf("://")
            val from = if (scheme < 0) u else u.substring(scheme + 3)
            from.substringBefore('/').substringBefore('@').substringAfterLast('@').substringBeforeLast(':')
        }
        if (host.isEmpty()) return false
        if (host == ".ts.net".removePrefix(".") || host.endsWith(".ts.net")) return true
        // 100.64.0.0/10 — Tailscale's CGNAT block (a literal tailnet IP is just as unroutable off-net).
        val octets = host.split('.')
        return octets.size == 4 && octets[0] == "100" &&
            (octets.getOrNull(1)?.toIntOrNull() ?: -1) in 64..127
    }

    /** The hostname a DNS failure names, or "" (OkHttp quotes it: `Unable to resolve host "x": …`). */
    fun unresolvedHost(t: Throwable?): String {
        val msg = t?.message ?: return ""
        val quoted = Regex("\"([^\"]+)\"").find(msg)?.groupValues?.get(1).orEmpty()
        return quoted.ifEmpty { msg.substringAfter("Unable to resolve host ", "").substringBefore(':').trim() }
    }

    private fun isDns(t: Throwable?) =
        t is java.net.UnknownHostException ||
            (t?.message ?: "").contains("Unable to resolve host", ignoreCase = true)

    private fun isTimeout(t: Throwable?) =
        t is java.net.SocketTimeoutException || (t?.message ?: "").contains("timeout", ignoreCase = true)

    private fun isRefused(t: Throwable?) = t is java.net.ConnectException

    /**
     * The wrist-length answer for a failed call. [tried] is the failover machine's probe record
     * (endpoint → answered); it is allowed to be empty (a call that failed before any probe).
     */
    fun explain(t: Throwable?, tried: Map<String, Boolean> = emptyMap()): String {
        if (t == null) return "no route to gateway — Settings ▸ Gateways"
        val dead = tried.filterValues { it == false }.keys
        if (isDns(t)) {
            val host = unresolvedHost(t)
            return if (isTailnetUrl(host) || dead.any { isTailnetUrl(it) }) {
                // Post-§11.4 truth: every ask rides the watch's OWN node, so a `.ts.net` miss is
                // never a Wi-Fi problem — the tunnel itself is not enrolled (or not up). The
                // actionable sentence names the repair door, not a LAN workaround.
                "tailnet name won't resolve on the watch — enroll / repair the tunnel (Settings ▸ Pair)"
            } else {
                "gateway host won't resolve${host.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""} " +
                    "— wrong Wi-Fi or a stale endpoint (Settings ▸ Gateways)"
            }
        }
        if (isTimeout(t) || isRefused(t)) {
            val n = tried.size.takeIf { it > 0 } ?: dead.size
            return if (n >= 2) {
                "no answer from $n endpoints — is the gateway up on this network? (Settings ▸ Gateways)"
            } else {
                if (isTimeout(t)) "gateway not answering — Settings ▸ Gateways ▸ test"
                else "gateway refused the connection — check the API port (Settings ▸ Gateways)"
            }
        }
        return t.message ?: "network error"
    }
}
