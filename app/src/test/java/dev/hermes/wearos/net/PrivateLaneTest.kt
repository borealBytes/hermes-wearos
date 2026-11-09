package dev.hermes.wearos.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRIVATE LANE v2 (SPEC §11.4, D8/D13/D14/D17): the pure wire layer — session-body law (the
 * pairing secret rides ONLY when it is a real 64-hex; after binding the call stays {"v":2}),
 * the events/outbox parser (cron fold counts included), and the status contract the watch maps
 * to honest states (401/410 = not paired, 403 = refused with the server's WHY — a missing
 * grant must read as exactly that, never as an empty key).
 */
class PrivateLaneTest {

    @Test
    fun `session body carries the secret only when it is a valid 64-hex`() {
        val s = "ab".repeat(32)
        assertEquals("{\"v\":2,\"secret\":\"$s\"}", PrivateLane.sessionBody(s))
        assertEquals("{\"v\":2}", PrivateLane.sessionBody(""))
        assertEquals("{\"v\":2}", PrivateLane.sessionBody("nope"))          // not hex
        assertEquals("{\"v\":2}", PrivateLane.sessionBody("ab".repeat(31)))  // too short
    }

    @Test
    fun `session payload parses keys + ports, watchkit token nullable`() {
        val body = """{"api_key":"k1","watchkit_token":null,"gateway_fqdn":"t.ts.net",""" +
            """"gateway_port":8642,"watchkit_port":42651}"""
        val s = PrivateLane.parseSession(body)!!
        assertEquals("k1", s.apiKey)
        assertNull(s.watchkitToken) // nullable BY CONTRACT (v2 pane can run keyless watchkit)
        assertEquals(42651, s.watchkitPort)
    }

    @Test
    fun `events payload parses rows fold counts and the next cursor`() {
        val body = """{"since":12,"events":[{"id":9,"kind":"cron","text":"done","agent":"wear",""" +
            """"title":"Nightly sweep","fold":3},{"id":12,"kind":"msg","text":"hi","agent":"research"}]}"""
        val ev = PrivateLane.parseEvents(body)!!
        assertEquals(12L, ev.since)
        assertEquals(2, ev.events.size)
        assertEquals(3, ev.events[0].fold)
        assertEquals("msg", ev.events[1].kind)
    }

    @Test
    fun `empty events list is still a valid page`() {
        val ev = PrivateLane.parseEvents("""{"since":7,"events":[]}""")!!
        assertEquals(7L, ev.since)
        assertTrue(ev.events.isEmpty())
    }

    @Test
    fun `status contract maps 200 shapes 401 410 403 and keeps the error WHY`() {
        assertTrue(PrivateLane.outcomeOf(200, """{"api_key":"k","gateway_fqdn":"t.ts.net"}""")
            is PrivateLane.Outcome.Session)
        assertTrue(PrivateLane.outcomeOf(200, """{"since":1,"events":[]}""")
            is PrivateLane.Outcome.Events)
        assertTrue(PrivateLane.outcomeOf(200, """{"remaining":0}""") is PrivateLane.Outcome.Acked)
        assertTrue(PrivateLane.outcomeOf(401, "") is PrivateLane.Outcome.Unpaired)
        assertTrue(PrivateLane.outcomeOf(410, "") is PrivateLane.Outcome.Gone)
        val r = PrivateLane.outcomeOf(
            403, """{"error":"agent 'research' has no key grant yet — approve it under Gateways ▸ Agents"}""")
        assertTrue(r is PrivateLane.Outcome.Refused)
        assertTrue((r as PrivateLane.Outcome.Refused).why.contains("no key grant")) // D17 truth preserved
        assertTrue(PrivateLane.outcomeOf(500, "") is PrivateLane.Outcome.Failed)
    }

    @Test
    fun `configured needs all three pieces — a half-paired watch never dials`() {
        PrivateLane.clearConfig()
        assertFalse(PrivateLane.configured)
        PrivateLane.setConfig("http://t.ts.net:42654", "wtk-1", "wear")
        assertTrue(PrivateLane.configured)
        PrivateLane.setConfig("http://t.ts.net:42654", "", "wear")
        assertFalse(PrivateLane.configured) // no token = no lane
        PrivateLane.clearConfig()
    }
}
