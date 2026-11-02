package dev.hermes.wearos.domain

/**
 * Endpoint failover state machine (SPEC §11, pure + JVM-tested with a fake prober/clock).
 *
 * The owner's directive: the tailnet URL is the DEFAULT entry, LAN the home fallback. At home
 * the tailnet probe fails FAST (~2.5 s connect timeout from the EndpointResolver) and LAN wins —
 * so "tailnet default" is true today even though the watch itself is not on the tailnet.
 *
 * Rules:
 *  - candidates are tried in list order until one answers health;
 *  - the winner is remembered for the session (sticky for [reprobeMs], then revalidated);
 *  - once a FALLBACK wins, the PRIMARY gets retried on a short backoff ([primaryRetryMs]) —
 *    one probe per backoff window, never a per-request penalty on a dead primary;
 *  - a mid-session network failure on the winner clears it → next [ensure] advances to the
 *    next endpoint (that's the "advance on connect timeout" the gateway system rides on);
 *  - when NOTHING answers, the previous winner stands (requests fail naturally; a stale
 *    pointer beats a null one) and the full list retries next cycle.
 */
class Failover(
    val endpoints: List<String>,
    private val reprobeMs: Long = DEFAULT_REPROBE_MS,
    private val primaryRetryMs: Long = DEFAULT_PRIMARY_RETRY_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /** A healthy winner is revalidated every 5 min (also rides every force-probe). */
        const val DEFAULT_REPROBE_MS = 300_000L
        /** Fallback winner → retry primary after 60 s (short backoff, SPEC §11). */
        const val DEFAULT_PRIMARY_RETRY_MS = 60_000L
    }

    /** Currently-winning endpoint; null = nothing probed yet / winner just failed. */
    var winner: String? = null
        private set

    /**
     * The last endpoint that ever ANSWERED since this machine was bound, winner-or-not, never
     * cleared by a failure. This is the difference between "the network blinked" and "the watch
     * pins itself to a hostname it cannot resolve": with every probe dead, [effective] used to
     * fall back to the PRIMARY — the tailnet entry — which for a watch that is not IN the tailnet
     * is a name DNS can never answer. So a hiccup on LAN handed [EndpointResolver.advanceFrom] a
     * doomed endpoint, the mirror was rewritten to it, and the retry was guaranteed to die on
     * "Unable to resolve host …" — which is exactly how an ordinary network change reached the
     * owner as *"couldn't connect over tailnet"* (SPEC §11.5). Something that answered once is a
     * better pointer than a candidate that has never answered at all.
     */
    var lastKnownGood: String? = null
        private set

    private var winnerAtMs = 0L
    private var primaryRetryAtMs = 0L

    /** Last probe outcome per endpoint (insertion-ordered) — drives the Settings dots. */
    private val _lastResult = LinkedHashMap<String, Boolean>()
    val lastResult: Map<String, Boolean> get() = _lastResult

    /**
     * What to dial RIGHT NOW: the winner, else the last endpoint that answered, else the primary.
     * The middle term is the bug fix — a never-answered primary (a tailnet name the watch cannot
     * resolve) must not outrank a LAN endpoint that worked 30 seconds ago.
     */
    fun effective(): String = winner ?: lastKnownGood ?: endpoints.firstOrNull().orEmpty()

    /**
     * Pointer for a caller that must NOT be handed a doomed endpoint ([EndpointResolver.advanceFrom]).
     * NULL means "we know nothing better than the base you are already holding", so the caller keeps
     * dialing its own endpoint instead of being re-aimed at a candidate that just failed.
     */
    fun fallbackPointer(): String? = winner ?: lastKnownGood

    fun isWinner(url: String): Boolean = winner != null && normalize(url) == normalize(winner!!)

    /** True when [ensure] should hit the network (winner unknown/stale, or primary-backoff due). */
    fun needsProbe(now: Long = clock()): Boolean {
        val w = winner ?: return endpoints.isNotEmpty()
        if (now - winnerAtMs >= reprobeMs) return true
        if (normalize(w) != normalize(endpoints.first()) && now >= primaryRetryAtMs) return true
        return false
    }

    /** Candidate order for the next probe round (primary-first once its backoff elapsed). */
    fun probeOrder(now: Long = clock()): List<String> {
        if (endpoints.isEmpty()) return emptyList()
        val w = winner
        val primary = endpoints.first()
        return when {
            w == null -> endpoints
            normalize(w) != normalize(primary) && now >= primaryRetryAtMs ->
                listOf(primary) + endpoints.filter { normalize(it) != normalize(primary) }
            else -> listOf(w) + endpoints.filter { normalize(it) != normalize(w) }
        }
    }

    fun onSuccess(url: String, now: Long = clock()) {
        winner = url
        winnerAtMs = now
        lastKnownGood = url
        _lastResult[url] = true
        val primary = endpoints.firstOrNull()
        if (primary != null && normalize(url) == normalize(primary)) primaryRetryAtMs = 0L
    }

    fun onFailure(url: String, now: Long = clock()) {
        _lastResult[url] = false
        val lostWinner = winner != null && normalize(winner!!) == normalize(url)
        if (lostWinner) winner = null
        // A dead primary gets re-touched only once per backoff window (primary OR any probe
        // round that touched it re-arms the timer while a fallback holds the crown).
        val primary = endpoints.firstOrNull()
        if (primary != null && normalize(url) == normalize(primary)) primaryRetryAtMs = now + primaryRetryMs
    }

    /** Walk [probe] order, probe each; first ok wins. Returns the winner or null (all dead). */
    suspend fun resolve(now: Long = clock(), probe: suspend (String) -> Boolean): String? {
        for (e in probeOrder(now)) {
            val ok = try { probe(e) } catch (t: Throwable) { false }
            if (ok) {
                onSuccess(e, clock())
                return winner
            }
            onFailure(e, clock())
        }
        return null
    }

    /**
     * Fresh winner without unnecessary network: sticky within [reprobeMs] (unless [force]);
     * all-probes-dead RESTORES the previous winner as a stale-but-usable pointer (requests
     * fail naturally with a clear error; a storm of dead probes must not null the winner).
     */
    suspend fun ensure(force: Boolean = false, probe: suspend (String) -> Boolean): String? {
        if (endpoints.isEmpty()) return null
        val now = clock()
        if (!force && winner != null && !needsProbe(now)) return winner
        val prev = winner
        val r = resolve(now, probe)
        if (r == null && prev != null) {
            winner = prev
            winnerAtMs = clock() // quiet revalidation window instead of probe-per-request churn
        }
        return winner
    }

    /** Mid-session failure hook: the winner just broke → dethrone, next ensure advances on. */
    fun reportNetworkFailure(url: String, now: Long = clock()) = onFailure(url, now)

    /**
     * The LINK itself moved (`net/NetworkMonitor`): the network the winner was learned on may not
     * exist any more, and staleness timers learned on that network say nothing about this one. So
     * the winner is dethroned WITHOUT waiting for a failed request, [lastKnownGood] survives as the
     * pointer, and the primary's backoff is cleared — a new network is a fair chance for the
     * tailnet entry to be reachable (SPEC §11.6: the exit-node-tether case). [needsProbe] is then
     * trivially true, so the very next [ensure] re-picks for THIS link instead of the ask paying
     * for a route that died in the other room.
     */
    fun invalidate(now: Long = clock()) {
        winner?.let { lastKnownGood = it }
        winner = null
        winnerAtMs = 0L
        primaryRetryAtMs = 0L
    }

    /** Short human line for logcat/Settings ("winner 192.168.1.50:8642 (primary down)"). */
    fun describe(): String {
        val w = winner ?: return "no winner · ${endpoints.size} endpoints"
        val h = host(w)
        val primary = endpoints.firstOrNull() ?: return h
        return if (normalize(w) == normalize(primary)) h else "$h (primary ${host(primary)} down)"
    }

    private fun host(url: String): String =
        url.substringAfter("://").substringBefore('/').ifEmpty { url }

    private fun normalize(url: String): String = url.trim().trimEnd('/')
}
