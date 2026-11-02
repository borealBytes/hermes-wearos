package dev.hermes.wearos.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dev.hermes.wearos.domain.NetInfo
import dev.hermes.wearos.domain.NetworkWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The android shell around [NetworkWatch]: one default-network callback, one line in logcat per
 * real change, one suspend hook that re-picks the gateway route (SPEC §11.6).
 *
 * Registered on the Application graph, never on an Activity: the change we care about (watch leaves
 * home Wi-Fi, attaches to the phone's tether) happens with the app in the background or asleep, and
 * a listener that dies with the screen would miss the only moment it is worth anything.
 *
 * Everything policy-shaped — what counts as a change, how fast, how often — lives in the pure
 * [NetworkWatch] so it is JVM-tested; this class only translates framework objects and must never
 * throw into the callback thread (an exception here is the system killing our connectivity view for
 * the process, silently).
 */
class NetworkMonitor(
    context: Context,
    private val onChange: suspend (reason: String) -> Unit,
    private val watch: NetworkWatch = NetworkWatch(),
) {
    companion object {
        private const val TAG = "HermesWear"

        /** NetworkCapabilities.NET_CAPABILITY_VPN (API 33, @SystemApi — not in our compile SDK). */
        private const val NET_CAPABILITY_VPN = 30
    }

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _net = MutableStateFlow(NetInfo.DOWN)

    /** Latest link truth, for Settings ▸ Gateways (the field-test readout, SPEC §11.6). */
    val net: StateFlow<NetInfo> = _net.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        val cm = cm
        if (cm == null) {
            Log.w(TAG, "network: no ConnectivityManager — route changes unobserved")
            return
        }
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish(network, null)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                publish(network, null, caps)
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) =
                publish(network, lp)
            override fun onLost(network: Network) = publishDown()
        }
        // The DEFAULT network only: a tunnel/backup that never carries our traffic must not be able
        // to trigger re-routes. When a userspace tailnet takes the default route it shows up here
        // with NET_CAPABILITY_VPN, which is precisely the signal we want.
        runCatching { cm.registerDefaultNetworkCallback(cb) }
            .onSuccess { callback = cb; Log.i(TAG, "network: watching the default route") }
            .onFailure { Log.w(TAG, "network: register failed — failover stays demand-driven", it) }
        // Prime the readout: the first Settings paint should already know the link, not wait for a
        // change that may never come on a stationary watch at home.
        runCatching { cm.activeNetwork?.let { publish(it, null) } }
    }

    fun stop() {
        val cb = callback ?: return
        runCatching { cm?.unregisterNetworkCallback(cb) }
            .onFailure { Log.w(TAG, "network: unregister failed", it) }
        callback = null
    }

    private fun publishDown() {
        _net.value = NetInfo.DOWN
        fire(watch.onNet(NetInfo.DOWN))
    }

    private fun publish(network: Network, lp: LinkProperties?, caps: NetworkCapabilities? = null) {
        val info = runCatching { read(network, lp, caps) }.getOrNull() ?: return
        _net.value = info
        fire(watch.onNet(info))
    }

    private fun fire(reason: String?) {
        val why = reason ?: return
        val info = _net.value
        Log.i(TAG, "network change: $why · ${NetworkWatch.describe(info)}")
        scope.launch { runCatching { onChange(why) }.onFailure { Log.w(TAG, "route re-check failed", it) } }
    }

    /**
     * Is the default route a tunnel (the shape a userspace tailnet takes on Android)?
     * `NET_CAPABILITY_VPN` is @SystemApi, so it is not in the public SDK we compile against: the
     * transport-info class name is the supported signal and the numeric capability is only a
     * second opinion (an unknown capability int answers false, never throws).
     */
    private fun isVpn(caps: NetworkCapabilities): Boolean =
        caps.transportInfo?.javaClass?.name?.endsWith("VpnTransportInfo") == true ||
            caps.hasCapability(NET_CAPABILITY_VPN)

    private fun read(network: Network, lp: LinkProperties?, capsIn: NetworkCapabilities?): NetInfo {
        val caps = capsIn ?: cm?.getNetworkCapabilities(network) ?: return NetInfo.DOWN
        val links = mutableSetOf<String>()
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> links += "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> links += "eth"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> links += "cell"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> links += "bt"
            else -> links += "other"
        }
        val props = lp ?: cm?.getLinkProperties(network)
        val dns = props?.dnsServers?.mapNotNull { it.hostAddress }?.filter { it.isNotBlank() }.orEmpty()
        return NetInfo(
            iface = props?.interfaceName?.takeIf { it.isNotBlank() }.orEmpty(),
            links = links,
            vpn = isVpn(caps),
            internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            dns = dns,
        )
    }
}
