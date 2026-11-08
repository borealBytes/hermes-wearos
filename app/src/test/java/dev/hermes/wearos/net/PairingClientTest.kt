package dev.hermes.wearos.net

import dev.hermes.wearos.domain.PairingCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PAIRING BOOTSTRAP CLIENT (SPEC §4/§11.4, S5): URL building, claim-body encoding, packet
 * parsing, and the outcome mapping of the funnel protocol (202 queued / 204 pending / 200
 * one-shot packet / 410 spent / 429 rate-limited) — plus the knock-then-poll loop driven by a
 * scripted [PairingClient.Http] fake and a fake sleeper (no real delay on the JVM, no socket).
 */
class PairingClientTest {

    // ── pure wire pieces ─────────────────────────────────────────────────────────────────────
    @Test
    fun `claim url is plain HTTPS to the plugin funnel host`() {
        assertEquals("https://lab.ts.net/pair/claim", PairingClient.claimUrl("lab"))
        // Funnel is reached BEFORE any tunnel exists → it must be a PUBLIC host, never the tunnel.
        assertFalse(PairingClient.claimUrl("lab").startsWith("http://"))
    }

    @Test
    fun `claim url rejects blank and garbage handles`() {
        for (h in listOf("", "  ", "bad handle", "!")) {
            var threw = false
            try { PairingClient.claimUrl(h) } catch (e: IllegalArgumentException) { threw = true }
            assertTrue("'$h' must not build a URL", threw)
        }
    }

    @Test
    fun `poll url carries the pin as a query param`() {
        assertEquals("https://lab.ts.net/pair/claim?pin=123456", PairingClient.pollUrl("lab", "123456"))
    }

    @Test
    fun `claim body is the documented shape with the normalized tailnet`() {
        val body = PairingClient.claimBody("246810", "My-Home", "Galaxy Watch Ultra")
        assertTrue(body, body.contains("\"pin\":\"246810\""))
        assertTrue(body, body.contains("\"tailnet\":\"my-home\""))
        assertTrue(body, body.contains("\"hostname\":\"Galaxy Watch Ultra\""))
    }

    @Test
    fun `claim body refuses malformed pins`() {
        for (p in listOf("012345", "12345", "abcdef", "")) {
            var threw = false
            try { PairingClient.claimBody(p, "lab", "w") } catch (e: IllegalArgumentException) { threw = true }
            assertTrue("pin '$p' must be rejected", threw)
        }
    }

    // ── packet parsing ───────────────────────────────────────────────────────────────────────
    private val packetJson = """
        {"authkey":"tskey-auth-SECRETVALUE","tailnet":"lab","gateway_fqdn":"gateway.lab.ts.net",
         "gateway_port":8642,"watchkit_port":42651,"profile":"wear","api_key":"APIKEY",
         "watchkit_token":"WKTOKEN"}
    """.trimIndent()

    @Test
    fun `packet parses every documented field`() {
        val p = PairingClient.parsePacket(packetJson)!!
        assertEquals("tskey-auth-SECRETVALUE", p.authKey)
        assertEquals("lab", p.tailnet)
        assertEquals("gateway.lab.ts.net", p.gatewayFqdn)
        assertEquals(8642, p.gatewayPort)
        assertEquals(42651, p.watchkitPort)
        assertEquals("wear", p.profile)
        assertEquals("APIKEY", p.apiKey)
        assertEquals("WKTOKEN", p.watchkitToken)
    }

    @Test
    fun `unknown packet fields are ignored and a missing gateway host is no packet`() {
        assertNull(PairingClient.parsePacket("""{"authkey":"x","future_field":{"a":1}}""")) // no fqdn
        assertNull(PairingClient.parsePacket("not json"))
        val p = PairingClient.parsePacket("""{"authkey":"x","gateway_fqdn":"g.ts.net","someday":true}""")!!
        assertEquals(0, p.gatewayPort) // absent ports default to 0 — the caller applies the 8642 convention
    }

    @Test
    fun `the redacted line never repeats the authkey or the api key`() {
        val line = PairingClient.parsePacket(packetJson)!!.redacted()
        assertFalse(line, line.contains("SECRETVALUE"))
        assertFalse(line, line.contains("APIKEY"))
        assertFalse(line, line.contains("WKTOKEN"))
        assertTrue(line, line.contains("auth.len=22"))
        assertTrue(line, line.contains("gateway.lab.ts.net"))
    }

    // ── outcome mapping (the status-code contract) ──────────────────────────────────────────
    @Test
    fun `status codes map to the documented outcomes`() {
        assertTrue(PairingClient.outcomeOf(202, "") is PairingClient.Outcome.Queued)
        assertTrue(PairingClient.outcomeOf(204, "") is PairingClient.Outcome.Pending)
        assertTrue(PairingClient.outcomeOf(410, "") is PairingClient.Outcome.Gone)
        assertTrue(PairingClient.outcomeOf(429, "") is PairingClient.Outcome.RateLimited)
        val fail = PairingClient.outcomeOf(500, "boom")
        assertTrue(fail is PairingClient.Outcome.Failed)
        assertEquals("HTTP 500", (fail as PairingClient.Outcome.Failed).why)
        val claimed = PairingClient.outcomeOf(200, packetJson)
        assertTrue(claimed is PairingClient.Outcome.Claimed)
        assertEquals("gateway.lab.ts.net", (claimed as PairingClient.Outcome.Claimed).packet.gatewayFqdn)
        // 200 with garbage is Malformed — terminal (the packet was CONSUMED; re-polling
        // would only turn the truth into a misleading "spent"). Never a crash.
        assertTrue(PairingClient.outcomeOf(200, "{{{") is PairingClient.Outcome.Malformed)
    }

    @Test
    fun `a null watchkit_token parses — the plugin serves explicit null, not absence`() {
        // Measured live 2026-11-06 (first real-watch drill): the packet carried
        // "watchkit_token":null because no watchkit bearer was configured. A non-null
        // field deserialized that null, threw AFTER the one-shot GET, and the retry
        // loop honestly-lyingly reported "that PIN is spent". The contract says
        // nullable; the model must honor it.
        val json = packetJson.replace("\"watchkit_token\":\"WKTOKEN\"", "\"watchkit_token\":null")
        val p = PairingClient.parsePacket(json)!!
        assertNull(p.watchkitToken)
        assertTrue(p.redacted(), p.redacted().contains("wkToken.len=0"))
    }

    // ── the knock→poll loop ──────────────────────────────────────────────────────────────────
    /** Scripted transport: responses in order; records method+url+body per call. */
    private class FakeHttp(vararg responses: Pair<Int, String>) : PairingClient.Http {
        val queue = responses.toMutableList()
        val calls = mutableListOf<Triple<String, String, String?>>()
        override fun exchange(method: String, url: String, bodyJson: String?): PairingClient.Res {
            calls += Triple(method, url, bodyJson)
            val (code, body) = if (queue.isNotEmpty()) queue.removeAt(0) else 204 to ""
            return PairingClient.Res(code, body)
        }
    }

    /** Run claim() against a scripted transport: fake sleep advances the fake clock. */
    private class Harness(vararg responses: Pair<Int, String>) {
        val http = FakeHttp(*responses)
        val client = PairingClient(http)
        val slept = mutableListOf<Long>()
        var t = 0L
        fun run(maxWaitMs: Long = 120_000L): PairingClient.Outcome = runBlocking {
            client.claim(
                "lab", "123456", "Watch",
                maxWaitMs = maxWaitMs,
                sleep = { slept += it; t += it },
                now = { t },
            )
        }
    }

    @Test
    fun `claim posts the knock then returns the packet when the poll answers 200`() {
        val h = Harness(202 to "", 204 to "", 200 to packetJson)
        val out = h.run()
        assertTrue(out is PairingClient.Outcome.Claimed)
        assertEquals("wear", (out as PairingClient.Outcome.Claimed).packet.profile)
    }

    @Test
    fun `claim first call is a POST knock and the rest are GET polls`() {
        val h = Harness(202 to "", 200 to packetJson)
        h.run()
        assertEquals("POST", h.http.calls[0].first)
        assertEquals("/pair/claim", h.http.calls[0].second.substringAfter("ts.net"))
        assertTrue(h.http.calls[0].third!!.contains("\"pin\":\"123456\""))
        assertEquals("GET", h.http.calls[1].first)
        assertEquals("pin=123456", h.http.calls[1].second.substringAfter("?"))
    }

    @Test
    fun `claim returns Gone when the funnel says 410`() {
        val h = Harness(202 to "", 410 to "")
        assertTrue(h.run() is PairingClient.Outcome.Gone)
    }

    @Test
    fun `a 429 while polling does not end the wait and keeps the pin`() {
        val h = Harness(202 to "", 429 to "", 204 to "", 200 to packetJson)
        val out = h.run()
        assertTrue("429 must be polled through, got $out", out is PairingClient.Outcome.Claimed)
        // The 429 poll must NOT have re-POSTed (the knock happens once; the PIN stays as-is).
        assertEquals(1, h.http.calls.count { it.first == "POST" })
        assertEquals(3, h.http.calls.count { it.first == "GET" })
    }

    @Test
    fun `the wait window ends with the last honest word, backoff capped and advancing`() {
        // Endless 204s: claim stops at the deadline and returns Pending — the wearer can knock
        // again with the SAME PIN (nothing was spent).
        val h = Harness(202 to "")
        val out = h.run(maxWaitMs = 30_000L)
        assertTrue(out is PairingClient.Outcome.Pending)
        assertTrue("sleeps must advance the fake clock or the loop never ends", h.slept.isNotEmpty())
        assertTrue("backoff is capped (no unbounded sleeps)", h.slept.max()!! <= 20_000L)
    }

    @Test
    fun `a dead knock surfaces Failed without polling at all`() {
        val h = Harness(404 to "")
        val out = h.run()
        assertTrue(out is PairingClient.Outcome.Failed)
        assertEquals(1, h.http.calls.size)
    }

    @Test
    fun `exchange exceptions become Failed, never crash the caller`() {
        val boom = PairingClient(PairingClient.Http { _, _, _ -> throw java.io.IOException("connect failed") })
        val out = runBlocking { boom.knock("lab", "123456", "w") }
        assertTrue(out is PairingClient.Outcome.Failed)
        assertEquals("connect failed", (out as PairingClient.Outcome.Failed).why)
    }

    @Test
    fun `the PIN the loop claims with survives validation round trip (domain seam)`() {
        val pin = PairingCode.mint()
        val url = PairingClient.pollUrl("lab", pin)
        assertTrue(url.endsWith("?pin=$pin"))
    }
    @Test
    fun `a malformed 200 ends the wait — the consumed PIN is never re-polled`() {
        // The exact failure that ate the first live drill: packet served (200), body
        // unparseable, loop kept polling, next GET said 410, wearer saw "spent" for a
        // claim that had actually SUCCEEDED server-side.
        val h = Harness(202 to "", 204 to "", 200 to "not json")
        val out = h.run()
        assertTrue(out is PairingClient.Outcome.Malformed)
        assertEquals(3, h.http.calls.size) // POST + one pending GET + the 200 — no fourth call
    }
}
