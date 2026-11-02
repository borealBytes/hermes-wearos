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
 */
object TailnetRoute {
    @Volatile
    var socksPort: Int = 0

    val SELECTOR: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val p = socksPort
            val host = uri?.host?.lowercase().orEmpty()
            return if (p <= 0 || host == "127.0.0.1" || host == "localhost" || host == "::1")
                listOf(Proxy.NO_PROXY)
            else listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", p)))
        }

        // NOTE: Android's connectFailed takes (URI, SocketAddress, IOException) — libcore
        // differs from OpenJDK's (SocketAddress, SocketAddress, IOException) here.
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
    }
}
