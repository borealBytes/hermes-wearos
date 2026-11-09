package dev.hermes.wearos.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PAIRING v2 BOOTSTRAP CLIENT (SPEC §4, review 2026-11-09): URL building, bodies, the status
 * contract (201 issued / 204 pending / 200 ready-token / 410 gone / 429 slow / 426 v1 door /
 * 403+423 refused) and the begin→poll→ack orchestration — all pure-JVM, scripted through the
 * [PairingClient.Http] seam with a fake sleeper. The review law these tests pin: the CODE comes
 * from the GATEWAY (never minted here), the CODE rides NO URL, and the pickup speaks the 256-bit
 * device secret instead.
 */
class PairingClientTest {

    private val secret = "a".repeat(32) + "b".repeat(32) // 64 hex

    // ── pure wire pieces ─────────────────────────────────────────────────────────────────────
    @Test
    fun `door urls are plain HTTPS to the funnel host`() {
        assertEquals("https://lab.ts.net/pair/begin", PairingClient.doorUrl("lab", "begin"))
        assertEquals("https://lab.ts.net/pair/ack", PairingClient.doorUrl("lab", "ack"))
        // The funnel is reached BEFORE any tunnel exists → it must be PUBLIC, never the tunnel.
        assertFalse(PairingClient.doorUrl("lab", "begin").startsWith("http://"))
    }

    @Test
    fun `door url rejects blank and garbage handles`() {
        for (h in listOf("", "  ", "bad handle", "!")) {
            runCatching { PairingClient.doorUrl(h, "begin") }.onSuccess {
                assertTrue("expected reject for '$h'", false)
            }
        }
    }

    @Test
    fun `poll url carries the SECRET and never the code`() {
        val url = PairingClient.pollUrl("lab", secret)
        assertEquals("https://lab.ts.net/pair/poll?secret=$secret", url)
        // The whole review point: no 6-digit code anywhere in a URL, ever.
        assertFalse(url.contains(Regex("[?&]pin=")))
        assertFalse(url.contains(Regex("[?&]code=")))
    }

    @Test
    fun `poll url rejects a non-hex or short secret`() {
        for (s in listOf("", "xyz", "ab".repeat(31), "z".repeat(64), secret.uppercase())) {
            val thrown = runCatching { PairingClient.pollUrl("lab", s) }.isFailure
            assertTrue("expected reject for '$s'", thrown)
        }
    }

    @Test
    fun `begin body is the v2 request with label and tailnet`() {
        val body = PairingClient.beginBody(secret, "Lab", "Galaxy Watch Ultra")
        assertTrue(body.contains("\"v\":2"))
        assertTrue(body.contains("\"secret\":\"$secret\""))
        assertTrue(body.contains("\"tailnet\":\"lab\"")) // handle normalized
        assertTrue(body.contains("\"label\":\"Galaxy Watch Ultra\""))
    }

    @Test
    fun `ack body carries only the secret`() {
        assertEquals("{\"v\":2,\"secret\":\"$secret\"}", PairingClient.ackBody(secret))
    }

    // ── the status contract ──────────────────────────────────────────────────────────────────
    @Test
    fun `201 issues the gateway code`() {
        val o = PairingClient.outcomeOf(201, """{"code":"429183","expires_in":300}""")
        assertEquals(PairingClient.Outcome.Begun("429183", 300), o)
    }

    @Test
    fun `201 with a malformed code is Malformed not a fake Begun`() {
        assertTrue(PairingClient.outcomeOf(201, """{"code":"12345a"}""") is PairingClient.Outcome.Malformed)
        assertTrue(PairingClient.outcomeOf(201, "{}") is PairingClient.Outcome.Malformed)
    }

    @Test
    fun `204 reads as Pending`() {
        assertTrue(PairingClient.outcomeOf(204, "") is PairingClient.Outcome.Pending)
    }

    @Test
    fun `200 ready parses the pickup packet`() {
        val body = """{"state":"ready","authkey":"tskey-x","key_id":"kid1","profile":"wear",""" +
            """"gateway_fqdn":"tux.tail.ts.net","gateway_port":8642,"watchkit_port":42651,"private_port":42654}"""
        val o = PairingClient.outcomeOf(200, body)
        assertTrue(o is PairingClient.Outcome.Ready)
        val p = (o as PairingClient.Outcome.Ready).packet
        assertEquals("tux.tail.ts.net", p.gatewayFqdn)
        assertEquals(42654, p.privatePort)
        assertEquals("wear", p.profile)
        // v2 law: NO api key in the public packet — that arrives later over the private lane.
        assertFalse(body.contains("api_key"))
    }

    @Test
    fun `200 token parses the device token`() {
        val o = PairingClient.outcomeOf(200, """{"token":"wtk-abcdef"}""")
        assertEquals(PairingClient.Outcome.Tokened("wtk-abcdef"), o)
    }

    @Test
    fun `THE LIVE DOOR SPACES ITS COLONS — stdlib json bodies must still parse`() {
        // json.dumps serializes '"state": "ready"' — the original sniff used to miss it
        // and read the pickup as Malformed (caught live 2026-11-09).
        val ready = """{"authkey": "tskey-x", "key_id": "k", "profile": "wear", """ +
            """"gateway_fqdn": "t.ts.net", "gateway_port": 8642, "watchkit_port": 42651, """ +
            """"private_port": 42654, "state": "ready"}"""
        val o = PairingClient.outcomeOf(200, ready)
        assertTrue(o is PairingClient.Outcome.Ready)
        assertEquals("t.ts.net", (o as PairingClient.Outcome.Ready).packet.gatewayFqdn)
        val ack = """{"token": "wtk-fedcba987654", "device": {"hostname": "w"}}"""
        assertTrue(PairingClient.outcomeOf(200, ack) is PairingClient.Outcome.Tokened)
    }

    @Test
    fun `200 without either shape is Malformed`() {
        assertTrue(PairingClient.outcomeOf(200, """{"hello":"world"}""") is PairingClient.Outcome.Malformed)
        assertTrue(PairingClient.outcomeOf(200, "not json") is PairingClient.Outcome.Malformed)
    }

    @Test
    fun `410 429 426 and 403 map to their honest outcomes`() {
        assertTrue(PairingClient.outcomeOf(410, "") is PairingClient.Outcome.Gone)
        assertTrue(PairingClient.outcomeOf(429, "") is PairingClient.Outcome.RateLimited)
        assertTrue(PairingClient.outcomeOf(426, "") is PairingClient.Outcome.ServerTooOld)
        val r = PairingClient.outcomeOf(403, """{"error":"pairing window closed"}""")
        assertTrue(r is PairingClient.Outcome.Refused)
        assertEquals("pairing window closed", (r as PairingClient.Outcome.Refused).why)
        assertTrue(PairingClient.outcomeOf(423, """{"error":"code locked"}""") is PairingClient.Outcome.Refused)
        assertTrue(PairingClient.outcomeOf(500, "") is PairingClient.Outcome.Failed)
    }

    @Test
    fun `newSecret is 64 hex from SecureRandom and unique per call`() {
        val s1 = PairingClient().newSecret()
        val s2 = PairingClient().newSecret()
        assertEquals(64, s1.length)
        assertTrue(s1.matches(Regex("^[0-9a-f]{64}$")))
        assertFalse(s1 == s2)
    }

    // ── orchestration: begin → poll → ack ────────────────────────────────────────────────────
    private class ScriptedHttp(private vararg val replies: PairingClient.Res) : PairingClient.Http {
        val calls = mutableListOf<Triple<String, String, String?>>()
        var i = 0
        override fun exchange(method: String, url: String, bodyJson: String?): PairingClient.Res {
            calls += Triple(method, url, bodyJson)
            return replies[(i++).coerceAtMost(replies.size - 1)]
        }
    }

    @Test
    fun `pair shows the gateway code then collects on approval`() {
        val http = ScriptedHttp(
            PairingClient.Res(201, """{"code":"429183","expires_in":300}"""),
            PairingClient.Res(204, ""),
            PairingClient.Res(
                200,
                """{"state":"ready","authkey":"tskey-x","key_id":"k","profile":"wear",""" +
                    """"gateway_fqdn":"t.ts.net","gateway_port":8642,"watchkit_port":42651,"private_port":42654}""",
            ),
            PairingClient.Res(200, """{"token":"wtk-dev1"}"""),
        )
        var shown = ""
        val out = runBlocking {
            PairingClient(http).pair("lab", secret, "watch", maxWaitMs = 60_000,
                sleep = {}, now = { 0L }, onCode = { shown = it })
        }
        assertTrue(out is PairingClient.Outcome.Completed)
        val c = out as PairingClient.Outcome.Completed
        assertEquals("wtk-dev1", c.deviceToken)
        assertEquals("wear", c.packet.profile) // the pickup AND the token, in one hand
        assertEquals("429183", shown) // the UI saw the GATEWAY's code the moment begin answered
        // begin POST carries the secret; every poll GET speaks ONLY the secret (no code in any
        // URL — the review law); the ack is the final POST. One 204 lands between begin and ready.
        assertEquals(listOf("POST", "GET", "GET", "POST"), http.calls.map { it.first })
        assertTrue(http.calls[1].second.endsWith("?secret=$secret"))
        assertTrue(http.calls[2].second.endsWith("?secret=$secret"))
        assertFalse(http.calls[1].second.contains("429183")) // the code rides NO url
    }

    @Test
    fun `pair returns ServerTooOld instead of retrying a v1 door`() {
        val http = ScriptedHttp(
            PairingClient.Res(201, """{"code":"429183","expires_in":300}"""),
            PairingClient.Res(426, ""),
        )
        val out = runBlocking {
            PairingClient(http).pair("lab", secret, "watch", maxWaitMs = 60_000, sleep = {}, now = { 0L })
        }
        assertTrue(out is PairingClient.Outcome.ServerTooOld)
    }

    @Test
    fun `pair survives 429 and keeps polling until approval`() {
        val http = ScriptedHttp(
            PairingClient.Res(201, """{"code":"111111","expires_in":300}"""),
            PairingClient.Res(429, ""),
            PairingClient.Res(204, ""),
            PairingClient.Res(
                200,
                """{"state":"ready","authkey":"tsk","key_id":"k","profile":"wear",""" +
                    """"gateway_fqdn":"t.ts.net","gateway_port":8642,"watchkit_port":42651,"private_port":42654}""",
            ),
            PairingClient.Res(200, """{"token":"wtk-2"}"""),
        )
        var clock = 0L
        val out = runBlocking {
            PairingClient(http).pair("lab", secret, "watch", maxWaitMs = 500_000,
                sleep = { clock += it }, now = { clock })
        }
        assertTrue("429 must be polled through, got $out", out is PairingClient.Outcome.Completed)
    }

    @Test
    fun `pair deadline reports the last honest state, never a fake success`() {
        val http = ScriptedHttp(
            PairingClient.Res(201, """{"code":"111111","expires_in":300}"""),
            PairingClient.Res(204, ""),
        )
        var clock = 0L
        val out = runBlocking {
            PairingClient(http).pair("lab", secret, "watch", maxWaitMs = 30_000,
                sleep = { clock += it }, now = { clock })
        }
        assertTrue(out is PairingClient.Outcome.Pending)
    }

    @Test
    fun `pickup packet redaction never leaks the authkey`() {
        val p = PickupPacket(authKey = "tskey-SUPERSECRET", keyId = "k", gatewayFqdn = "t.ts.net", gatewayPort = 8642)
        assertFalse(p.redacted().contains("SUPERSECRET"))
        assertTrue(p.redacted().contains("auth.len=${"tskey-SUPERSECRET".length}"))
    }

    @Test
    fun `parsePickup demands fqdn and authkey`() {
        assertNull(PairingClient.parsePickup("""{"state":"ready","authkey":"a","gateway_fqdn":""}"""))
        assertNull(PairingClient.parsePickup("""{"state":"ready","authkey":"","gateway_fqdn":"t.ts.net"}"""))
    }
}
