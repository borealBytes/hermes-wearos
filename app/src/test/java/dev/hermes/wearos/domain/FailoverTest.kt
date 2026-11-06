package dev.hermes.wearos.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Endpoint failover state machine (SPEC §11) driven by a FAKE prober + FAKE clock: the exact
 * failover scenario — first endpoint dead until timeout; the backup answers; winner is remembered;
 * primary gets its short-backoff retry; mid-session failures advance the list.
 */
class FailoverTest {

    private val tailnet = "http://hermes.tailnet.ts.net:8642"
    private val backup = "http://hermes2.tailnet.ts.net:8642"
    private val endpoints = listOf(tailnet, backup)

    private class Fake(val name: String) : Exception(name)

    /** JVM shim: run the suspend ensure() with its fake probe right here. */
    private fun Failover.go(force: Boolean = false, probe: suspend (String) -> Boolean): String? =
        kotlinx.coroutines.runBlocking { ensure(force, probe) }

    private fun machine(
        eps: List<String> = endpoints,
        now: () -> Long = { 1_000_000L },
        reprobeMs: Long = Failover.DEFAULT_REPROBE_MS,
        primaryRetryMs: Long = Failover.DEFAULT_PRIMARY_RETRY_MS,
    ) = Failover(eps, reprobeMs, primaryRetryMs, now)

    @Test
    fun `fresh machine points at primary without probing`() {
        var probes = 0
        val f = machine(now = { 0L })
        assertEquals(endpoints, f.probeOrder(0L))
        assertTrue(f.needsProbe(0L))
        val winner = f.go { probes++; false }
        assertEquals(2, probes) // full round, in order
        assertNull(winner)      // all dead: nothing to remember
        assertEquals(tailnet, f.effective()) // primary stands as the pointer anyway
        assertTrue(f.lastResult.values.all { it == false })
    }

    @Test
    fun `primary tailnet dead backup alive - backup wins after one fast failure`() {
        val probed = mutableListOf<String>()
        val f = machine(now = { 0L })
        val winner = f.go { url -> probed += url; url == backup }
        assertEquals(backup, winner)
        assertEquals(listOf(tailnet, backup), probed) // advance order = endpoint order
        assertEquals(backup, f.effective())
        assertTrue(f.describe().contains("primary"))
        assertTrue(f.describe().contains("down"))
    }

    @Test
    fun `winner is sticky - ensure within ttl never touches the network`() {
        var probes = 0
        var now = 0L
        val f = machine(now = { now })
        assertEquals(backup, f.go { probes++; it == backup })
        assertEquals(2, probes)
        now = 10_000L // well inside reprobe + primary-retry windows
        assertEquals(backup, f.go { probes++; true })
        assertEquals("sticky winner must not re-probe", 2, probes)
        assertFalse(f.needsProbe(now))
    }

    @Test
    fun `after backoff primary is retried once - still dead keeps fallback and re-arms backoff`() {
        var now = 0L
        val probed = mutableListOf<String>()
        val f = Failover(endpoints, clock = { now })
        // Home scenario: fallback wins at t=0.
        assertEquals(backup, f.go { url -> probed += url; url == backup })
        assertEquals(listOf(tailnet, backup), probed)
        // Within backoff: no retry churn.
        now = 30_000L
        assertFalse(f.needsProbe(now))
        // Backoff elapsed (60 s): primary gets ONE probe first.
        now = 61_000L
        assertTrue(f.needsProbe(now))
        probed.clear()
        assertEquals(backup, f.go { url -> probed += url; url == backup })
        assertEquals("primary must be retried first", tailnet, probed.first())
        // Still dead: primary re-armed for another window — next cycle probes it once more,
        // and the backup winner is found WITHOUT re-probing the whole list.
        probed.clear()
        now = 122_000L
        assertEquals(backup, f.go { url -> probed += url; url == backup })
        assertEquals(listOf(tailnet, backup), probed)
    }

    @Test
    fun `primary recovery - winner flips back and retry timer clears`() {
        var now = 0L
        var tailnetUp = false
        val f = Failover(endpoints, clock = { now })
        assertEquals(backup, f.go { url -> url == backup })
        now = 61_000L
        tailnetUp = true
        assertEquals(tailnet, f.go { url -> url == tailnet })
        assertEquals(tailnet, f.effective())
        // Healthy primary: no retry pressure until the long revalidation ttl.
        assertFalse(f.needsProbe(now + 1_000))
        assertFalse(f.describe().contains("down"))
    }

    @Test
    fun `mid-session failure on the winner dethrones and next ensure advances on`() {
        val probed = mutableListOf<String>()
        val f = machine(now = { 0L })
        // Winner is tailnet (it answers now)…
        assertEquals(tailnet, f.go { url -> url == tailnet })
        // …then the socket dies mid-chat (SPEC §11 advance-on-timeout).
        f.reportNetworkFailure(tailnet, now = 5_000L)
        assertNull(f.winner)
        val next = f.go { url -> probed += url; url == backup }
        assertEquals(backup, next)
        assertEquals(listOf(tailnet, backup), probed) // ordered advance
    }

    @Test
    fun `non-winner failure does not disturb a healthy winner`() {
        val f = machine(now = { 0L })
        f.onSuccess(backup, now = 0L)
        f.onFailure(tailnet, now = 0L) // the dead primary recorded for the dots
        assertEquals(backup, f.winner)
        assertEquals(mapOf(backup to true, tailnet to false), f.lastResult)
    }

    @Test
    fun `all endpoints dead keeps the previous winner standing`() {
        var now = 0L
        val f = Failover(endpoints, clock = { now })
        assertEquals(backup, f.go { url -> url == backup })
        // Later everything is down (VPN flap, gateway restart storm):
        now = 400_000L // past reprobe ttl
        val kept = f.go { false }
        assertEquals("stale pointer beats a null one", backup, kept)
        assertFalse(f.isWinner(tailnet))
    }

    @Test
    fun `force ensure always re-probes and can move the winner`() {
        var up = backup
        val f = machine(now = { 0L })
        assertEquals(backup, f.go { url -> url == up })
        up = tailnet
        assertEquals(tailnet, f.go(force = true) { url -> url == up })
        assertEquals(tailnet, f.winner)
    }

    @Test
    fun `empty endpoint list is inert`() {
        val f = machine(eps = emptyList(), now = { 0L })
        assertEquals("", f.effective())
        assertEquals(emptyList<String>(), f.probeOrder(0L))
        assertNull(f.go { throw Fake("must not probe") })
        assertFalse(f.needsProbe(0L))
    }

    @Test
    fun `resolve probe exception counts as failure and advances`() {
        val probed = mutableListOf<String>()
        val f = machine(now = { 0L })
        val winner = f.go { url -> probed += url; if (url == tailnet) throw Fake("timeout"); true }
        assertEquals(backup, winner)
        assertEquals(listOf(tailnet, backup), probed)
    }

    @Test
    fun `three endpoints - advance walks the list to the third`() {
        val vps = "http://vps.example.com:8642"
        val f = machine(eps = endpoints + vps, now = { 0L })
        val winner = f.go { url -> url == vps }
        assertEquals(vps, winner)
        // Primary-retry order after backoff keeps list order for the rest:
        assertEquals(listOf(tailnet, backup, vps), f.probeOrder(61_000L))
    }

    // ── lastKnownGood / fallbackPointer: SPEC §11.5, the "couldn't connect over tailnet" bug ──

    @Test
    fun `dethroned winner stays the pointer - a never-answered primary must not outrank it`() {
        val f = machine(now = { 0L })
        assertEquals(backup, f.go { url -> url == backup }) // the primary entry is DNS-dead, backup serves
        f.reportNetworkFailure(backup, now = 5_000L)    // a plain link hiccup dethrones the winner
        assertNull(f.winner)
        // advanceFrom must NOT be handed a never-answered primary: a name the tunnel cannot
        // resolve right now is a guaranteed second failure whose text the wearer reads as
        // "tailnet broken" while the thing that just answered sits unused.
        assertEquals(backup, f.fallbackPointer())
        assertEquals(backup, f.effective())
    }

    @Test
    fun `a machine that has never heard from anyone offers no pointer, only the primary`() {
        val f = machine(now = { 0L })
        assertNull("no success yet → nothing trustworthy", f.fallbackPointer())
        assertEquals(tailnet, f.effective()) // first-contact dial: the owner's chosen default
        assertNull(f.go { false })
        assertNull(f.fallbackPointer())
        assertEquals(tailnet, f.effective()) // still the primary: we never reached anything else
    }

    @Test
    fun `total outage after a good run keeps pointing at what answered`() {
        var now = 0L
        val f = Failover(endpoints, clock = { now })
        assertEquals(backup, f.go { url -> url == backup })
        now = 400_000L
        f.reportNetworkFailure(backup, now)
        assertNull(f.go { false })               // VPN flap: every candidate dead
        assertEquals(backup, f.lastKnownGood)
        assertEquals(backup, f.fallbackPointer())
        assertEquals(backup, f.effective())
    }

    @Test
    fun `routing is per gateway, not per agent - the same machine serves every profile`() {        // The owner's question ("is it a profile-based thing?") answered in code: one Failover is
        // bound per GATEWAY and its winner is the endpoint, not the /p/<profile> suffix — switching
        // agents reuses it, so no profile can pick its own route or poison another's.
        val f = machine(now = { 0L })
        val baseForComfyui = GatewayModel.apiBase(backup, "comfyui")
        val baseForWear = GatewayModel.apiBase(backup, "wear")
        assertEquals(backup, f.go { url -> url == backup })
        assertEquals("$backup/p/comfyui", baseForComfyui)
        assertEquals("$backup/p/wear", baseForWear)
        assertEquals(f.effective(), GatewayModel.normalizeEndpoint(baseForComfyui))
        assertEquals(f.effective(), GatewayModel.normalizeEndpoint(baseForWear))
    }

    // ── invalidate(): the network moved, so the network-learned staleness is meaningless ────

    @Test
    fun `network change dethrones the winner at once but keeps the pointer`() {
        val f = machine(now = { 0L })
        assertEquals(backup, f.go { url -> url == backup }) // learned at home
        f.invalidate(now = 1_000L) // walk out: the tether attaches, no request has failed yet
        assertNull("a winner learned on a dead network must not stay the crown", f.winner)
        assertEquals("…but the thing that answered is still the best pointer", backup, f.lastKnownGood)
        assertEquals(backup, f.effective())
        assertTrue("the next ensure must probe, not ride a 5-minute TTL learned elsewhere", f.needsProbe(1_000L))
    }

    @Test
    fun `a new network gives the primary a fair chance - tailnet may now be reachable`() {
        var now = 0L
        val probed = mutableListOf<String>()
        val f = Failover(endpoints, clock = { now })
        assertEquals(backup, f.go { url -> probed += url; url == backup })
        // Once the link moves and the node re-attaches, the primary tailnet entry is the RIGHT answer (§11.6).
        now = 5_000L
        f.invalidate(now)
        probed.clear()
        assertEquals(tailnet, f.go { url -> probed += url; url == tailnet })
        assertEquals("primary is retried first after a link change, not after the 60 s backoff",
            tailnet, probed.first())
    }

    @Test
    fun `invalidating a machine that never reached anything stays honest`() {
        val f = machine(now = { 0L })
        f.invalidate(now = 0L)
        assertNull(f.winner)
        assertNull(f.lastKnownGood)
        assertNull(f.fallbackPointer())
        assertEquals(tailnet, f.effective()) // the owner's chosen default, untouched
    }
}
