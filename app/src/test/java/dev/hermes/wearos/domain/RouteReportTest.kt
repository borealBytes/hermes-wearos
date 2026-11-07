package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing failures translated for a wrist (SPEC §11.5). The strings are the product here: the
 * owner once saw OkHttp's `Unable to resolve host "labhost.tailca7ee1.ts.net" …` on the watch and
 * concluded the TAILNET was broken. Post-§11.4 the sentence has an even sharper meaning: every
 * ask rides the watch's OWN node, so a `.ts.net` DNS miss can only mean the tunnel is not enrolled
 * (or not up) — and the message must say ENROLL (Settings ▸ Pair), never "add a LAN entry". These
 * tests pin the translation, including the parts that must NOT change: real errors keep their own
 * message, and nothing may claim more routes were tried than the probe record actually holds.
 */
class RouteReportTest {

    private val tailnetHost = "labhost.tailca7ee1.ts.net"
    private val dnsOnTailnet = java.net.UnknownHostException(
        "Unable to resolve host \"$tailnetHost\": No address associated with hostname",
    )
    private val dnsOnLan = java.net.UnknownHostException(
        "Unable to resolve host \"box.home.arpa\": No address associated with hostname",
    )

    // ── tailnet classification: the fact the old message hid ────────────────────────────────
    @Test
    fun `MagicDNS names and tailnet CGNAT addresses are tailnet-only`() {
        assertTrue(RouteReport.isTailnetUrl("http://$tailnetHost:8642"))
        assertTrue(RouteReport.isTailnetUrl("hermes.ts.net"))
        assertTrue(RouteReport.isTailnetUrl("http://100.99.1.42:8642")) // 100.64.0.0/10
        assertTrue(RouteReport.isTailnetUrl("http://100.127.9.9:8651"))
    }

    @Test
    fun `lan, private, public and garbage are not tailnet urls`() {
        assertFalse(RouteReport.isTailnetUrl("http://192.168.10.179:8642"))
        assertFalse(RouteReport.isTailnetUrl("http://100.128.1.1:8642")) // just outside /10
        assertFalse(RouteReport.isTailnetUrl("http://100.63.1.1:8642"))
        assertFalse(RouteReport.isTailnetUrl("http://vps.example.com:8642"))
        assertFalse(RouteReport.isTailnetUrl("tsnet.io")) // a .ts.net look-alike suffix must not fit
        assertFalse(RouteReport.isTailnetUrl(""))
        assertFalse(RouteReport.isTailnetUrl("http://"))
        assertFalse(RouteReport.isTailnetUrl("  "))
    }

    @Test
    fun `the host a dns failure names is extracted`() {
        assertEquals(tailnetHost, RouteReport.unresolvedHost(dnsOnTailnet))
        assertEquals("", RouteReport.unresolvedHost(java.net.SocketTimeoutException("connect timed out")))
        assertEquals("", RouteReport.unresolvedHost(null))
    }

    // ── the messages themselves ─────────────────────────────────────────────────────────────
    @Test
    fun `tailnet dns miss says the WATCH tunnel is not enrolled and names the repair door`() {
        val msg = RouteReport.explain(dnsOnTailnet)
        assertTrue(msg, msg.contains("won't resolve on the watch"))
        assertTrue(msg, msg.contains("Settings"))
        // Post-§11.4: the fix is ENROLL/REPAIR of the tunnel (the Pair screen), never a LAN hint.
        assertTrue(msg, msg.contains("enroll"))
        assertTrue(msg, msg.contains("Pair"))
        assertFalse(msg, msg.contains("LAN"))
        // The trap this exists for: it must not blame the tailnet as if the phone's state mattered.
        assertFalse(msg, msg.contains("Unable to resolve host"))
    }

    @Test
    fun `a probe record full of dead tailnet endpoints reaches the same sentence`() {
        // The ask itself may fail with a bare DNS error whose host is the LAN entry (a stale IP),
        // while the failover machine knows the tailnet candidate was probed and dead.
        val tried = mapOf("http://$tailnetHost:8642" to false, "http://10.0.0.5:8642" to false)
        val msg = RouteReport.explain(dnsOnLan, tried)
        assertTrue(msg, msg.contains("won't resolve on the watch"))
    }

    @Test
    fun `non-tailnet dns miss names the host and the real suspicion`() {
        val msg = RouteReport.explain(dnsOnLan)
        assertTrue(msg, msg.contains("box.home.arpa"))
        assertTrue(msg, msg.contains("Wi-Fi"))
        assertTrue(msg, msg.contains("Settings ▸ Gateways"))
    }

    @Test
    fun `timeout and refusal are counted from the probe record, never invented`() {
        val tried = mapOf("http://a:8642" to false, "http://b:8642" to false, "http://c:8642" to false)
        val timeout = RouteReport.explain(java.net.SocketTimeoutException("connect timed out"), tried)
        assertTrue(timeout, timeout.contains("3 endpoints"))
        val one = RouteReport.explain(java.net.SocketTimeoutException("read timed out"), mapOf("http://a:8642" to false))
        assertTrue(one, one.contains("not answering"))
        assertFalse(one, one.contains("endpoints"))
        val refused = RouteReport.explain(java.net.ConnectException("Failed to connect to /192.168.1.50:8642"))
        assertTrue(refused, refused.contains("refused"))
    }

    @Test
    fun `unrelated failures keep their own words`() {
        assertEquals("HTTP 500 body too big", RouteReport.explain(RuntimeException("HTTP 500 body too big")))
        val routeLine = RouteReport.explain(null)
        assertTrue(routeLine, routeLine.isNotBlank() && routeLine.contains("Settings ▸ Gateways"))
    }

    @Test
    fun `every translation fits a wrist line`() {
        val cases = listOf(
            RouteReport.explain(dnsOnTailnet),
            RouteReport.explain(dnsOnLan),
            RouteReport.explain(java.net.SocketTimeoutException("connect timed out"), mapOf("a" to false)),
            RouteReport.explain(java.net.ConnectException("Failed to connect")),
            RouteReport.explain(null),
        )
        for (c in cases) assertTrue("$c", c.length <= 100)
    }
}
