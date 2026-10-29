package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When does a network event justify re-picking the gateway route — and when is it noise
 * (SPEC §11.6). The demand-driven machine recovered inside the first failed ask; the point of this
 * policy is that the watch never notices the change at all, which only works if the callback that
 * fires TWELVE times per connect (available → capabilities → validated → dns…) does not fire twelve
 * probe rounds, and if a real wifi→tether switch is never swallowed by that same dedupe.
 *
 * Fake clock throughout: debounce and the quiet opening are timing rules, and a test that sleeps is
 * a test that fails on a busy CI box.
 */
class NetworkWatchTest {

    // Interfaces are the identity (see NetInfo.iface): wlan0 is home, bt-pan is the phone's
    // tether, tun0 is what a userspace tailnet looks like.
    private val wifi = NetInfo(
        iface = "wlan0", links = setOf("wifi"), internet = true, validated = true,
        dns = listOf("192.168.10.1"),
    )
    private val tether = wifi.copy(
        iface = "bt-pan", links = setOf("bt"), validated = false,
        dns = listOf("192.168.43.1"),
    )
    private val tailnet = wifi.copy(
        iface = "tun0", vpn = true, dns = listOf(NetInfo.TAILSCALE_RESOLVER),
    )

    private class Clock(var now: Long = 100_000L) : () -> Long {
        override fun invoke() = now
    }

    private fun watch(clock: Clock = Clock()) =
        NetworkWatch(debounceMs = 1_500L, quietStartMs = 4_000L, clock = clock)

    // ── the connect burst must cost ONE round ───────────────────────────────────────────────
    @Test
    fun `cold start - the boot probe owns the first route`() {
        val c = Clock(0L)
        val w = watch(c)
        assertNull("first callback inside the quiet opening must not double-probe", w.onNet(wifi, 10L))
        // …but the identity IS remembered: a later change on the same network stays silent, and a
        // real change fires the moment the quiet opening ends.
        c.now = 5_000L
        assertNull(w.onNet(wifi, 5_000L))
        c.now = 6_000L
        assertEquals("network changed", w.onNet(tether, 6_000L))
    }

    @Test
    fun `first route after the quiet opening is acted on`() {
        val c = Clock(0L)
        val w = watch(c)
        c.now = 9_000L
        assertEquals("first route", w.onNet(wifi, 9_000L))
    }

    @Test
    fun `a capability refinement inside the boot opening is the app refining itself`() {
        // Measured on an SM-L705U: start() primes the readout, and the validated/dns refinement
        // arrives 44 ms later. Treating that as "the watch moved" fired a second probe round on top
        // of the boot probe on every cold start.
        val c = Clock(0L)
        val w = watch(c)
        assertNull(w.onNet(wifi.copy(validated = false, dns = emptyList()), 20L))
        c.now = 64L
        assertNull("refinement of the primed network is not a route change", w.onNet(wifi, 64L))
        c.now = 5_000L
        assertNull("and it stays settled once the opening closes", w.onNet(wifi, 5_000L))
    }

    @Test
    fun `repeated reports of one network never re-probe`() {
        val c = Clock(0L)
        val w = watch(c)
        assertEquals("first route", w.onNet(wifi, 9_000L))
        for (t in 10_000L..60_000L step 1_000L) {
            val same = wifi.copy(validated = t % 2 == 0L) // validation flips as Android probes
            if (same.validated) assertEquals("validated flip is not a route change", null, w.onNet(same, t))
        }
        assertNull(w.onNet(wifi, 61_000L))
    }

    @Test
    fun `new resolver on the same network IS a route change - magicdns arrives without a new link`() {
        // The exact owner scenario: the phone turns on tailnet forwarding for LAN devices while the
        // watch sits on the same Wi-Fi. Same handle, same SSID, new resolver ⇒ the tailnet entry can
        // suddenly resolve, and the app should re-pick rather than wait for an ask to fail.
        val c = Clock(0L)
        val w = watch(c)
        assertEquals("first route", w.onNet(wifi, 9_000L))
        c.now = 20_000L
        assertEquals("network changed", w.onNet(wifi.copy(dns = listOf(NetInfo.TAILSCALE_RESOLVER)), 20_000L))
    }

    // ── flap suppression must not swallow a real change ─────────────────────────────────────
    @Test
    fun `wifi to tether inside the debounce window pays its debt on the next settled report`() {
        val c = Clock(0L)
        val w = watch(c)
        assertEquals("first route", w.onNet(wifi, 9_000L))
        // Radio flap: two networks inside one debounce window (wifi → tether → wifi → tether).
        c.now = 9_800L
        assertNull("inside debounce", w.onNet(tether, 9_800L))
        c.now = 10_200L
        assertNull("still inside debounce, same new identity", w.onNet(tether, 10_200L))
        // The debt is owed to the identity we LAST saw, not to the one we acted on.
        c.now = 11_000L
        assertEquals("network changed", w.onNet(tether, 11_000L))
        c.now = 30_000L
        assertNull("settled: nothing further until something actually moves", w.onNet(tether, 30_000L))
    }

    @Test
    fun `lost link is never debounced and never reported twice`() {
        val c = Clock(0L)
        val w = watch(c)
        assertEquals("first route", w.onNet(wifi, 9_000L))
        c.now = 9_400L // inside the window on purpose: dethroning an endpoint whose link died is urgent
        assertEquals("network lost", w.onNet(NetInfo.DOWN, 9_400L))
        assertNull("the follow-up lost/onLost pair is noise", w.onNet(NetInfo.DOWN, 9_500L))
        c.now = 20_000L
        assertEquals("route restored", w.onNet(wifi, 20_000L))
    }

    @Test
    fun `booting with no radio at all is not a loss`() {
        val w = watch(Clock(0L))
        assertNull(w.onNet(NetInfo.DOWN, 1_000L))
        assertNull(w.onNet(NetInfo.DOWN, 2_000L))
    }

    @Test
    fun `down is down - no handle or no link means nothing to send on`() {
        assertFalse(NetInfo.DOWN.up)
        assertFalse(wifi.copy(links = emptySet()).up)
        assertFalse("a handle with no interface carries nothing", wifi.copy(iface = "", links = emptySet()).up)
        assertTrue("unvalidated is still worth a probe (the health probe is the real validator)",
            wifi.copy(validated = false).up)
    }

    // ── the readout the owner reads on the wrist during a walk test ─────────────────────────
    @Test
    fun `describe names the link, the truth about validation, and nothing personal`() {
        assertEquals("no network", NetworkWatch.describe(NetInfo.DOWN))
        assertEquals("wifi(wlan0) · validated", NetworkWatch.describe(wifi))
        assertEquals("bt(bt-pan) · not validated", NetworkWatch.describe(tether))
        assertEquals("wifi(tun0) · vpn · validated · ts-resolver", NetworkWatch.describe(tailnet))
        for (line in listOf(NetworkWatch.describe(wifi), NetworkWatch.describe(tether))) {
            assertFalse("no address literals: $line", line.contains(":"))
            assertFalse("no SSID: $line", line.contains("192.168"))
        }
    }

    @Test
    fun `a measured-dead tailnet probe is stated as measured`() {
        // The Wear emulator is the counter-example that forced this shape: no tunnel, no Tailscale
        // resolver in its own LinkProperties, yet .ts.net resolves because slirp forwards the HOST's
        // on-tailnet resolver. "Can't resolve" would have been a lie there; a dead probe never is.
        val tailnetUrl = "http://hermes.tailb12aa5.ts.net:8642"
        val lanUrl = "http://192.168.1.50:8642"
        assertEquals(
            "wifi(wlan0) · validated — tailnet entries unreachable from here",
            NetworkWatch.describe(wifi, listOf(tailnetUrl, lanUrl), mapOf(tailnetUrl to false, lanUrl to true)),
        )
        // It ANSWERED: a working route is a working route, whatever the capabilities claim. The
        // emulator lives in this case — it wins on a .ts.net endpoint while having no tailnet of its
        // own, and the hint printed next to that winner said two opposite things.
        assertEquals(
            "wifi(wlan0) · validated",
            NetworkWatch.describe(wifi, listOf(tailnetUrl, lanUrl), mapOf(tailnetUrl to true, lanUrl to true)),
        )
        assertEquals(
            "wifi(wlan0) · validated",
            NetworkWatch.describe(wifi, listOf(lanUrl), mapOf(lanUrl to true)),
        )
    }

    @Test
    fun `without a tunnel or the tailscale resolver the watch simply has no tailnet route`() {
        // The second tier exists because a LAN-FIRST endpoint list never probes the tailnet entry at
        // all (a round stops at the first 2xx), so an evidence-only clause would stay silent forever
        // on the owner's watch — where the claim is true anyway.
        val tailnetUrl = "http://hermes.tailb12aa5.ts.net:8642"
        assertEquals(
            "wifi(wlan0) · validated — no tailnet route on this device",
            NetworkWatch.describe(wifi, listOf(tailnetUrl), emptyMap()),
        )
        // A tunnel, or the MagicDNS resolver, in front of the device: nothing to say.
        assertEquals(
            "wifi(tun0) · vpn · validated · ts-resolver",
            NetworkWatch.describe(tailnet, listOf(tailnetUrl)),
        )
        assertEquals(
            "wifi(wlan0) · validated · ts-resolver",
            NetworkWatch.describe(
                wifi.copy(dns = listOf("192.168.10.1", NetInfo.TAILSCALE_RESOLVER)), listOf(tailnetUrl),
            ),
        )
        // A LAN-only gateway never earns a tailnet clause at all.
        assertEquals("wifi(wlan0) · validated", NetworkWatch.describe(wifi, listOf("http://192.168.1.50:8642")))
    }

    @Test
    fun `magicdns is ready when a tunnel or the tailscale resolver is present`() {
        assertFalse(wifi.magicDnsReady)
        assertTrue(wifi.copy(dns = listOf("192.168.10.1", NetInfo.TAILSCALE_RESOLVER)).magicDnsReady)
        assertTrue(wifi.copy(vpn = true).magicDnsReady)
    }
}
