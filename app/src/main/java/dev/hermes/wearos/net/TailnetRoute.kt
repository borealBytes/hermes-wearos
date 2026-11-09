package dev.hermes.wearos.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * THE TUNNEL ROUTE (SPEC §11.4): the single seam every OkHttp client installs as its
 * ProxySelector. When the embedded node is up, every non-loopback URI — REST, SSE,
 * WebSocket — connects through the Go SOCKS5 listener on loopback and the hostname is
 * resolved INSIDE the tailnet (MagicDNS truth). Loopback stays direct (dev conveniences,
 * future in-app servers). Port 0 (node down / feature disabled) = direct everywhere,
 * which is the dev escape hatch: `-e tailnet_on off` returns the app to plain sockets.
 *
 * Deliberately no LAN exception: "tailnet only, always" (owner 2026-11-02) — a LAN
 * literal in an endpoint list is reached THROUGH the tunnel like any other address.
 *
 * FAIL-CLOSED (D18): on a RELEASE build [failClosed] is set at boot, and the answer to
 * "no tunnel yet" is NO ROUTE (empty selector list → OkHttp refuses), never a silent
 * plain-socket request — a release watch sends outside the tunnel exactly never. Dev
 * builds keep the direct fallback so `-e tailnet_on off` remains a working escape hatch.
 */
object TailnetRoute {
    @Volatile
    var socksPort: Int = 0

    /** Release truth (WearApp boot): no tunnel = no traffic. Dev leaves it false. */
    @Volatile
    var failClosed: Boolean = false

    val SELECTOR: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val p = socksPort
            val host = uri?.host?.lowercase().orEmpty()
            if (host == "127.0.0.1" || host == "localhost" || host == "::1") return listOf(Proxy.NO_PROXY)
            if (p > 0) return listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", p)))
            // Tunnel down: dev = plain sockets (escape hatch), release = NO route at all.
            return if (failClosed) emptyList() else listOf(Proxy.NO_PROXY)
        }

        // NOTE: Android's connectFailed takes (URI, SocketAddress, IOException) — libcore
        // differs from OpenJDK's (SocketAddress, SocketAddress, IOException) here.
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
    }
}
