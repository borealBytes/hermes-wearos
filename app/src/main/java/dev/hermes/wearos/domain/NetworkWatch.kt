package dev.hermes.wearos.domain

/**
 * One snapshot of "what network does the watch actually have", reduced to the facts a route
 * decision uses (built by `net/NetworkMonitor` from ConnectivityManager; kept here, pure, so the
 * policy is JVM-tested instead of Activity-tested).
 *
 * This exists because every routing question in this app bottoms out in a link-layer fact: a
 * `.ts.net` name is answered by MagicDNS, which only exists on a device that is IN the tailnet
 * (SPEC §11.5). The owner's watch is not; the phone's tailnet membership does not reach it unless
 * the phone is an exit node FOR LAN devices. Rather than infer all that from a timeout, the app
 * now reads the capability flags and says what it sees — [NetworkWatch.describe] is exactly the
 * sentence that was missing from the logcat that took a bug report to answer.
 */
data class NetInfo(
    /**
     * The interface carrying the default route (`wlan0`, `rmnet0`, `tun0`…), "" when there is none.
     * This is the STABLE identity, and the word stable is doing work: the first version of this
     * class keyed on `System.identityHashCode(network)`, and ConnectivityManager builds a NEW
     * `Network` object for every callback — so every report of one unchanged Wi-Fi looked like a
     * new network and the watch re-probed every 3 s on the emulator (measured, logcat in §11.6).
     * Interface names come from LinkProperties, never change under a live link, and `tun0`/
     * `utun*` is itself a useful signal: that is what a userspace tailnet looks like.
     */
    val iface: String = "",
    /** Link kinds carrying the default network: `wifi` / `cell` / `bt` / `eth` / `other`. */
    val links: Set<String> = emptySet(),
    /** A VPN interface owns the default route — the shape a userspace tailnet (or any tunnel) takes. */
    val vpn: Boolean = false,
    val internet: Boolean = false,
    /** Android's own probe says packets go through (lags a real attach on tethered links). */
    val validated: Boolean = false,
    val dns: List<String> = emptyList(),
) {
    /** Anything at all to send on. Deliberately NOT gated on validated: Android's validation lags
     *  on tethered/portal links, and the gateway health probe is the validator that matters here. */
    val up: Boolean get() = links.isNotEmpty()

    /** MagicDNS is Tailscale's resolver at 100.100.100.100 — if it is in the resolver list, a
     *  `.ts.net` endpoint has a real chance of resolving; if it is not, it has none. */
    val tailscaleDns: Boolean get() = dns.any { it == TAILSCALE_RESOLVER }
    val magicDnsReady: Boolean get() = vpn || tailscaleDns

    /** What changed, as far as the route machine cares — a new interface, link kind, tunnel, or
     *  resolver. Validation is in here on purpose (a portal that just opened is a real change), but
     *  nothing object-identity-shaped is: see [iface]. */
    val identity: String
        get() = "$iface|${links.sorted().joinToString(",")}|vpn=$vpn|int=$internet|v=$validated|dns=${dns.sorted().joinToString(",")}"

    companion object {
        const val TAILSCALE_RESOLVER = "100.100.100.100"

        /** The "no default network" sentinel — also the event [NetworkWatch.onNet] gets on lost. */
        val DOWN = NetInfo()
    }
}

/**
 * The POLICY for "the watch's network just moved — should the gateway route be re-picked?"
 *
 * WHY (owner: *"make it flawless for discovering what's available network wise"*): failover
 * (§11.2) was entirely demand-driven. The app learned the network had changed by failing one ask,
 * so walking out of Wi-Fi range cost a slow first message, and — worse — a winner learned on the
 * home LAN could look "fresh" for five minutes while the watch was already on a tether where that
 * address means nothing. ConnectivityManager tells us the truth the moment the link attaches; this
 * class decides what to do about it, in the open, with a fake clock.
 *
 * The three things that make it safe rather than noisy:
 *  • **identity, not event count.** A connect is a BURST (available → capabilities → validated →
 *    dns…) for one network. Same identity ⇒ no re-probe, or the probe storm chases every report.
 *  • **debounce with a debt.** A real change inside the window is remembered as `pending` and the
 *    next settled event pays it — so Wi-Fi↔cellular flapping costs one probe, and a change is
 *    never silently dropped on the floor.
 *  • **a quiet opening.** [QUIET_START_MS] after boot the FIRST route is recorded but not acted on:
 *     `WearApp` already probes while it starts, and forcing a second round would rewrite the mirror
 *     mid-launch for nothing. A LOST network is never quiet — dethroning an endpoint that just lost
 *     its link is always worth doing now.
 */
class NetworkWatch(
    private val debounceMs: Long = DEBOUNCE_MS,
    private val quietStartMs: Long = QUIET_START_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /** Collapse a connect burst / radio flap into one re-route. */
        const val DEBOUNCE_MS = 1_500L

        /** Cold start owns its own probe; the monitor joins after it. */
        const val QUIET_START_MS = 4_000L

        /**
         * The wrist/logcat sentence for the current network — NO SSID and no address literals
         * beyond resolver identity, per this repo's privacy rule (no personal network identity in
         * anything the owner can screenshot).
         *
         * The tailnet clause has TWO tiers, because the two claims behind them are different kinds
         * of statement and the earlier single-tier wording got one of them wrong:
         *
         *  • **[tried] proves it.** A tailnet endpoint the failover machine probed and lost is
         *    measured unreachability — the exact evidence the failover itself acts on (§11.2).
         *  • **[endpoints] + capabilities show the shape.** No tunnel interface and no Tailscale
         *    resolver means THIS device has no tailnet route. That is structural, and it is NOT a
         *    claim about DNS: the Wear emulator is the counter-example, with no tunnel and no
         *    resolver in its own `LinkProperties` yet `.ts.net` answering happily, because slirp
         *    forwards the HOST's on-tailnet resolver. Saying "can't resolve" there was simply
         *    wrong; "no tailnet route on this device" is true even there — it borrows the host's.
         *
         * The second tier is what keeps the line useful in the common case: on a LAN-first list the
         * tailnet entry is never probed at all (a probe round stops at the first 2xx), so a purely
         * evidence-based clause would stay silent forever on the owner's watch. Evidence still wins
         * in both directions when it exists — an entry that ANSWERED is a working route, and the
         * emulator is living proof (it wins on a `.ts.net` endpoint while its own capabilities claim
         * no tailnet at all, and printing the hint next to that winner said two opposite things).
         */
        fun describe(
            info: NetInfo,
            endpoints: List<String> = emptyList(),
            tried: Map<String, Boolean> = emptyMap(),
        ): String {
            if (!info.up) return "no network"
            val parts = mutableListOf(
                (info.links.sorted().ifEmpty { listOf("net") }).joinToString("+") +
                    info.iface.takeIf { it.isNotEmpty() }?.let { "($it)" }.orEmpty(),
            )
            if (info.vpn) parts += "vpn"
            parts += if (info.validated) "validated" else if (info.internet) "not validated" else "no internet"
            if (info.tailscaleDns) parts += "ts-resolver"
            val line = parts.joinToString(" · ")
            // Evidence outranks the structural hint in BOTH directions: a tailnet entry that answered
            // is a route that works (the Wear emulator wins on exactly such an endpoint while its own
            // capabilities claim no tunnel), and one that died is a route that does not.
            val tailnetTried = tried.filterKeys { RouteReport.isTailnetUrl(it) }
            if (tailnetTried.values.any { it == true }) return line
            if (tailnetTried.values.any { it == false }) {
                return "$line — tailnet entries unreachable from here"
            }
            if (!info.magicDnsReady && endpoints.any { RouteReport.isTailnetUrl(it) }) {
                return "$line — no tailnet route on this device"
            }
            return line
        }
    }

    private val startedAt = clock()
    private var lastIdentity: String? = null
    private var lastActedAt = 0L
    private var pending = false
    private var wasUp = false
    private var everUp = false

    /**
     * Feed one observation; get back WHY the app should re-pick its gateway route, or null when the
     * event is noise. The reason string is logged verbatim — "route re-check" with no reason behind
     * it is how you end up debugging a probe storm by eye.
     */
    fun onNet(info: NetInfo, now: Long = clock()): String? {
        if (!info.up) {
            if (!wasUp) return null // it was never up: cold start with no radio, nothing to dethrone
            wasUp = false
            lastIdentity = null
            pending = false
            return "network lost"
        }
        wasUp = true
        val id = info.identity
        if (id == lastIdentity && !pending) return null // one network, nothing moved

        val reason = when {
            !everUp -> "first route"          // cold start: the boot probe owns this round
            lastIdentity == null -> "route restored" // it came back after a lost link — act on it
            else -> "network changed"         // wifi → tether, tether → cell, new resolvers…
        }
        everUp = true
        wasUp = true
        lastIdentity = id
        // The whole opening belongs to the boot probe, not just the first callback: start() primes
        // the readout with the CURRENT network, and ConnectivityManager refines it a beat later
        // (validated/dns land ~44 ms behind the prime on an SM-L705U). The app refining its own
        // observation is not the watch moving, and the boot probe is already in flight.
        // A lost link is exempt — it returns above, because dethroning is urgent even at boot.
        if (now - startedAt < quietStartMs) {
            pending = false
            return null
        }
        if (now - lastActedAt < debounceMs) {
            pending = true // debt: paid by the next settled event, never dropped
            return null
        }
        pending = false
        lastActedAt = now
        return reason
    }

    /** Test/UI peek: was the last word that the link is down? */
    fun isDown(): Boolean = !wasUp
}
