package dev.hermes.wearos.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TAILNET phase rules (SPEC §11.4) pinned as pure timing-free fixtures — same school as
 * FailoverTest: the Go engine's status JSON is the fixture source of truth (these strings
 * are verbatim-shaped from tailnet/go's StatusJSON), and the owner doctrine under test is
 * "the tunnel is the transport": Running-but-gateway-offline is DEGRADED, never ACTIVE.
 */
class TailnetStateTest {

    private val runningFixture = """
        {"backendState":"Running","socksPort":38851,"authURL":"",
         "self":{"hostname":"hermes-wearos-pilot","dnsName":"hermes-wearos-pilot.tailca7ee1.ts.net",
                 "ips":["100.116.61.37","fd7a:115c:a1e0::a730:3d26"],"online":true},
         "peers":[{"hostname":"labhost","dnsName":"labhost.tailca7ee1.ts.net",
                   "ip":"100.99.1.42","online":true,"relay":"ord"},
                  {"hostname":"oneplus-12","dnsName":"","ip":"100.92.121.67","online":true}]}
    """.trimIndent()

    @Test fun `parse round-trips the engine status`() = runBlocking {
        val s = TailnetLogic.parse(runningFixture)
        assertEquals("Running", s.backendState)
        assertEquals(38851, s.socksPort)
        assertEquals("hermes-wearos-pilot", s.self?.hostname)
        assertEquals(2, s.peers?.size)
        assertEquals("ord", s.peers?.first()?.relay)
    }

    @Test fun `parse survives stopped status and bad json`() = runBlocking {
        assertEquals("stopped", TailnetLogic.parse("""{"backendState":"stopped","socksPort":0,"authURL":""}""").backendState)
        // null peers (fresh node) must not blow up the non-null list default
        assertEquals("stopped", TailnetLogic.parse("""{"backendState":"stopped","socksPort":0,"authURL":"","peers":null}""").backendState)
        val bad = TailnetLogic.parse("not json at all")
        assertEquals("stopped", bad.backendState)
        assertTrue(bad.error.startsWith("bad status json"))
    }

    @Test fun `gateway peer matches dnsName case-insensitively and by tailnet ip`() = runBlocking {
        val s = TailnetLogic.parse(runningFixture)
        assertEquals("labhost.tailca7ee1.ts.net", TailnetLogic.gatewayPeer(s, "LabHost.tailca7ee1.ts.net")?.dnsName)
        assertEquals("labhost", TailnetLogic.gatewayPeer(s, "100.99.1.42")?.hostname)
        assertNull(TailnetLogic.gatewayPeer(s, ""))
        assertNull(TailnetLogic.gatewayPeer(s, "somewhere.else.ts.net"))
    }

    @Test fun `phase matrix - the tunnel is the transport`() = runBlocking {
        val up = TailnetLogic.parse(runningFixture)
        // DISABLED overrides every engine truth; STOPPED covers both stopped and engine errors.
        assertEquals(TailnetPhase.DISABLED, TailnetLogic.phase(up, enabled = false, gatewayHost = "labhost.tailca7ee1.ts.net"))
        assertEquals(TailnetPhase.STOPPED, TailnetLogic.phase(TailnetStatus(), enabled = true, gatewayHost = "x"))
        assertEquals(TailnetPhase.STOPPED, TailnetLogic.phase(TailnetStatus(backendState = "error"), enabled = true, gatewayHost = "x"))
        // NeedsLogin with a login link is NEEDS_AUTH (authkey path is the design; URL the last resort).
        assertEquals(TailnetPhase.NEEDS_AUTH, TailnetLogic.phase(
            TailnetStatus(backendState = "NeedsLogin", authURL = "https://login.tailscale.com/a/xx"), enabled = true, gatewayHost = "x"))
        // Anything else non-Running is STARTING — and Running without the SOCKS port yet, too.
        assertEquals(TailnetPhase.STARTING, TailnetLogic.phase(TailnetStatus(backendState = "Starting"), enabled = true, gatewayHost = "x"))
        assertEquals(TailnetPhase.STARTING, TailnetLogic.phase(up.copy(socksPort = 0), enabled = true, gatewayHost = "labhost.tailca7ee1.ts.net"))
        // ACTIVE: node up + gateway peer online (or no gateway configured to judge).
        assertEquals(TailnetPhase.ACTIVE, TailnetLogic.phase(up, enabled = true, gatewayHost = "labhost.tailca7ee1.ts.net"))
        assertEquals(TailnetPhase.ACTIVE, TailnetLogic.phase(up, enabled = true, gatewayHost = ""))
        // DEGRADED: Running is NOT enough — the gateway peer must answer (owner doctrine).
        val gwOffline = up.copy(peers = up.peers?.map { if (it.hostname == "labhost") it.copy(online = false) else it })
        assertEquals(TailnetPhase.DEGRADED, TailnetLogic.phase(gwOffline!!, enabled = true, gatewayHost = "labhost.tailca7ee1.ts.net"))
        assertEquals(TailnetPhase.DEGRADED, TailnetLogic.phase(up.copy(peers = null), enabled = true, gatewayHost = "labhost.tailca7ee1.ts.net"))
    }

    @Test fun `describe gives the wrist its honest one-liner`() = runBlocking {
        val up = TailnetLogic.parse(runningFixture)
        val active = TailnetLogic.phase(up, enabled = true, gatewayHost = "labhost.tailca7ee1.ts.net")
        assertEquals(
            "tailnet up · hermes-wearos-pilot.tailca7ee1.ts.net · gateway 100.99.1.42 online",
            TailnetLogic.describe(active, up, "labhost.tailca7ee1.ts.net"),
        )
        assertEquals("tailnet off", TailnetLogic.describe(TailnetPhase.DISABLED, up, "x"))
        val noPeers = up.copy(peers = null)
        val neverSeen = TailnetLogic.phase(noPeers, enabled = true, gatewayHost = "gw.tailca7ee1.ts.net")
        assertEquals("tailnet up · gateway gw.tailca7ee1.ts.net not on the tailnet",
            TailnetLogic.describe(neverSeen, noPeers, "gw.tailca7ee1.ts.net"))
    }
}
