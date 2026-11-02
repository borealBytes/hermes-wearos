package dev.hermes.wearos.net

import android.util.Log
import dev.hermes.wearos.data.AppSettings
import dev.hermes.wearos.domain.Failover
import dev.hermes.wearos.domain.Gateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Health probe — one GET, true on 2xx. Injectable so the state machine tests stay pure. */
fun interface Prober {
    suspend fun ok(url: String): Boolean
}

/**
 * Endpoint failover for the SELECTED gateway (SPEC §11). The prober GETs
 * `{endpoint}/p/{agent}/v1/health` (falls back to bare `{endpoint}/health`) with a 2.5 s leash;
 * the first endpoint to answer becomes the session winner and is written into the legacy
 * mirror keys via [onWinner] — every consumer (ChatSession/Watchkit/pusher) follows instantly.
 *
 * Home reality (owner directive): the tailnet URL is the DEFAULT entry but the watch is NOT on
 * the tailnet, so the tailnet probe fails fast at home (~2.5 s) and LAN wins — documented in
 * SPEC §11. Winner is in-memory only ("for the session"); a restart re-probes.
 */
class EndpointResolver(
    private val settings: AppSettings,
    private val prober: Prober = HttpProber(),
    private val onWinner: suspend (Gateway, api: String, watchkit: String) -> Unit = { _, _, _ -> },
) {
    companion object { private const val TAG = "HermesWear" }

    data class Status(
        val gatewayId: String = "",
        val apiWinner: String = "",
        val watchkitWinner: String = "",
        val apiResults: Map<String, Boolean> = emptyMap(),
        val watchkitResults: Map<String, Boolean> = emptyMap(),
        val probing: Boolean = false,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private var apiF: Failover? = null
    private var wkF: Failover? = null
    private var boundGatewayId = ""
    private val mutex = Mutex()

    /** (Re)arm both state machines for [gw]; a no-op while the same gateway stays bound. */
    fun bind(gw: Gateway) {
        if (boundGatewayId == gw.id && apiF != null) return
        apiF = Failover(gw.apiEndpoints)
        wkF = Failover(gw.watchkitEndpoints)
        boundGatewayId = gw.id
        _status.value = _status.value.copy(gatewayId = gw.id, apiWinner = gw.primaryApi, watchkitWinner = gw.primaryWatchkit)
    }

    /** Force a re-probe even while the same gateway is bound (gateway edit/save). */
    fun rebind(gw: Gateway) {
        boundGatewayId = ""
        bind(gw)
    }

    /** Endpoint to dial right now without probing (winner else primary). */
    fun currentApi(): String = apiF?.effective().orEmpty()

    fun currentWatchkit(): String = wkF?.effective().orEmpty()

    /** True when [url] (base with or without /p/segment) is the winning api endpoint. */
    fun isCurrentApi(url: String): Boolean {
        val f = apiF ?: return true
        return f.effective() == dev.hermes.wearos.domain.GatewayModel.normalizeEndpoint(url)
    }

    /**
     * Pick the api+watchkit endpoint for the selected gateway. Probes only when stale/forced;
     * returns the api endpoint ("" when nothing is known). Fires [onWinner] on every winner
     * change (mirror keys + Watchkit config follow).
     */
    suspend fun ensure(force: Boolean = false): String = mutex.withLock { ensureLocked(force) }

    /** [ensure] with the mutex already held (network-change re-route needs both halves at once). */
    private suspend fun ensureLocked(force: Boolean): String {
        val gw = settings.selectedGateway() ?: return ""
        bind(gw)
        val profile = settings.profileSync()
        _status.value = _status.value.copy(probing = true)
        val prevApi = apiF?.winner
        val prevWk = wkF?.winner
        val api = apiF?.ensure { ep -> probeApi(ep, profile) } ?: ""
        val wk = wkF?.ensure { url -> prober.ok("$url/health") } ?: ""
        val s = _status.value
        _status.value = s.copy(
            apiWinner = apiF?.winner ?: gw.primaryApi,
            watchkitWinner = wkF?.winner ?: gw.primaryWatchkit,
            apiResults = apiF?.lastResult?.toMap() ?: emptyMap(),
            watchkitResults = wkF?.lastResult?.toMap() ?: emptyMap(),
            probing = false,
        )
        val winnerNow = apiF?.winner
        if (winnerNow != null && winnerNow != prevApi)
            Log.i(TAG, "endpoint winner: ${winnerNow.substringAfter("://").substringBefore('/')} · ${apiF?.describe()}")
        if ((winnerNow != null && winnerNow != prevApi) || (wk.isNotEmpty() && wk != prevWk))
            onWinner(gw, apiF?.effective().orEmpty(), wkF?.effective().orEmpty())
        return api // "" = no machine / every probe dead (a stale winner stands inside it)
    }

    /**
     * The link moved (`net/NetworkMonitor` fired this): dethrone both winners — the network that
     * taught them is gone — and re-pick the whole route list FOR THE NEW LINK, right now, before the
     * user taps anything (SPEC §11.6). Demand-driven failover recovers inside the first ask after a
     * walk-out; this makes the watch never notice the change at all.
     *
     * Returns the API endpoint now in use ("" when no gateway is configured). Both machines are
     * invalidated, not just the API one: images/mermaid/whisper/sessions ride watchkit and would
     * otherwise keep dialing the dead interface the ask no longer uses.
     */
    suspend fun onNetworkChanged(reason: String): String = mutex.withLock {
        val gw = settings.selectedGateway() ?: return@withLock ""
        bind(gw)
        apiF?.invalidate()
        wkF?.invalidate()
        Log.i(TAG, "re-routing for '${gw.label}' ($reason)…")
        ensureLocked(force = true)
    }

    /** Profiled health first, bare `/health` next — either 2xx means the endpoint is alive. */
    private suspend fun probeApi(endpoint: String, profile: String): Boolean {
        val base = endpoint.trim().trimEnd('/')
        if (dev.hermes.wearos.domain.ProfileLogic.isValidProfile(profile)) {
            if (prober.ok("$base/p/$profile/v1/health")) return true
        }
        return prober.ok("$base/health")
    }

    /**
     * Mid-session failure hook (SPEC §11 "advance to next endpoint"): a request to [usedBaseUrl]
     * just died with an I/O error. Dethrone it, re-probe the rest ONCE, return the NEW base
     * (with /p/agent) or "" when nothing better exists (caller keeps its old base + retries).
     *
     * "Nothing better exists" is decided by [Failover.fallbackPointer], deliberately NOT by
     * [Failover.effective]: the old code fell back to the PRIMARY endpoint, and when that primary is
     * a tailnet hostname the watch cannot resolve, a plain LAN hiccup got the mirror rewritten to an
     * address guaranteed to fail DNS — the retry then surfaced as "Unable to resolve host
     * …tailca7ee1.ts.net", which is the sentence the owner read as "couldn't connect over tailnet"
     * while sitting on Wi-Fi (SPEC §11.5). Silence here is honest; a pointer to a dead host is not.
     */
    suspend fun advanceFrom(usedBaseUrl: String): String = mutex.withLock {
        val gw = settings.selectedGateway() ?: return@withLock ""
        bind(gw)
        val used = dev.hermes.wearos.domain.GatewayModel.normalizeEndpoint(usedBaseUrl)
        val f = apiF ?: return@withLock ""
        f.reportNetworkFailure(used)
        val profile = settings.profileSync()
        val next = f.resolve { ep -> probeApi(ep, profile) } ?: f.fallbackPointer()
        _status.value = _status.value.copy(apiWinner = f.winner ?: gw.primaryApi, apiResults = f.lastResult.toMap())
        if (!next.isNullOrEmpty() && next != used) {
            Log.i(TAG, "endpoint failover advanced: ${used.substringAfter("://")} -> ${next.substringAfter("://")} (${f.describe()})")
            val wk = wkF?.effective().orEmpty()
            onWinner(gw, next, wk)
            dev.hermes.wearos.domain.GatewayModel.apiBase(next, profile)
        } else {
            // Nothing answered AND nothing better than what the caller already holds: say so, because
            // this is the state where the chat's next line becomes "no route to gateway".
            Log.w(TAG, "endpoint failover found no route (used ${used.substringAfter("://").ifEmpty { "?" }}, ${f.lastResult.size} probed)")
            ""
        }
    }
}

/** Default prober: 2.5 s hard leash (owner spec) — a dead tailnet URL must fail FAST. */
class HttpProber : Prober {
    private val client = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .callTimeout(2800, TimeUnit.MILLISECONDS)
        .proxySelector(dev.hermes.wearos.net.TailnetRoute.SELECTOR)   // probes ride the tunnel too (SPEC §11.4)
        .build()

    override suspend fun ok(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false // connect refused/timeout/DNS miss — the exact "tailnet not reachable" case
        }
    }
}
