package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gateway registry (SPEC §11/§11.4): seed defaults (tailnet-FQDN-ONLY — the LAN fallback was
 * retired with the tunnel doctrine), legacy-key migration (fresh install stays EMPTY so the
 * Pair screen opens), CRUD with selection fixups, JSON round-trip, and the base-URL/token folds.
 * Pure — mirrors exactly what AppSettings persists.
 */
class GatewayModelTest {

    @Test
    fun `seed is tailnet-FQDN only — no LAN default ships`() {
        val g = GatewayModel.seed("tok")
        assertEquals(GatewayModel.SEED_ID, g.id)
        assertEquals("hermes", g.label)
        assertEquals(listOf("http://hermes.tailnet.ts.net:8642"), g.apiEndpoints)
        assertEquals(listOf("http://hermes.tailnet.ts.net:42651"), g.watchkitEndpoints)
        assertEquals("tok", g.token)
    }

    @Test
    fun `normalizeEndpoint strips profile segment trailing slash and whitespace`() {
        assertEquals("http://h:8642", GatewayModel.normalizeEndpoint("http://h:8642/p/wear"))
        assertEquals("http://h:8642", GatewayModel.normalizeEndpoint("  http://h:8642/p/wear/  "))
        assertEquals("http://h:8642", GatewayModel.normalizeEndpoint("http://h:8642/"))
        assertEquals("http://h:8642", GatewayModel.normalizeEndpoint("http://h:8642"))
        assertEquals("", GatewayModel.normalizeEndpoint("   "))
        // A path that isn't a profile segment stays intact (never mangle unknown routes).
        assertEquals("http://h:8642/api", GatewayModel.normalizeEndpoint("http://h:8642/api"))
    }

    @Test
    fun `bare host input completes scheme and gateway port`() {
        // MINIMAL-INPUT LAW (owner 2026-11-10): the pane shows the host alone; the
        // watch must accept typing exactly that.
        assertEquals("http://a.b:8642", GatewayModel.normalizeEndpoint("a.b"))
        assertEquals("http://t.tailb12aa5.ts.net:8642",
                     GatewayModel.normalizeEndpoint("t.tailb12aa5.ts.net"))
        assertEquals("lonejunk", GatewayModel.normalizeEndpoint("lonejunk"))  // not host-like
        // an explicit port rides untouched…
        assertEquals("http://h:9000", GatewayModel.normalizeEndpoint("h:9000"))
        // …and an explicit scheme is honoured exactly (port stays as typed, even absent).
        assertEquals("https://h", GatewayModel.normalizeEndpoint("https://h"))
        assertEquals("http://a.b:8642", GatewayModel.normalizeEndpoint("a.b/p/wear"))
    }

    @Test
    fun `apiBase re-derives the profile segment`() {
        assertEquals("http://h:8642/p/wear", GatewayModel.apiBase("http://h:8642/p/wear", "wear"))
        assertEquals("http://h:8642/p/default", GatewayModel.apiBase("http://h:8642/", "default"))
        // Invalid profile → endpoint stands (caller keeps old behavior; no hardcoding).
        assertEquals("http://h:8642", GatewayModel.apiBase("http://h:8642", ""))
        assertEquals("", GatewayModel.apiBase("", "wear"))
    }

    @Test
    fun `encode decode roundtrip and garbage decodes empty`() {
        val list = listOf(GatewayModel.seed("k"), Gateway("vps", "vps", listOf("http://v:8642"), listOf("http://v:8651"), "t"))
        val back = GatewayModel.decode(GatewayModel.encode(list))
        assertEquals(list, back)
        assertTrue(GatewayModel.decode("").isEmpty())
        assertTrue(GatewayModel.decode("{not json").isEmpty())
        assertTrue(GatewayModel.decode("[]").isEmpty())
    }

    @Test
    fun `migrate fresh install leaves the registry EMPTY for the Pair screen`() {
        // "tailnet only, always": an unenrolled watch must be TOLD (Pair screen), not shipped a
        // silent placeholder — the empty list is the caller's null path (ensureGateways → Pair).
        val (list, selected) = GatewayModel.migrate("", "", "")
        assertTrue(list.isEmpty())
        assertEquals("", selected)
    }

    @Test
    fun `migrate stock default base url keeps the tailnet-only list and migrates token`() {
        val (list, selected) = GatewayModel.migrate("http://hermes.tailnet.ts.net:8642/p/wear", "SECRET", "http://hermes.tailnet.ts.net:42651")
        assertEquals(GatewayModel.SEED_ID, selected)
        val g = list.single()
        assertEquals(GatewayModel.DEFAULT_API_ENDPOINTS, g.apiEndpoints) // tailnet ONLY
        assertEquals(GatewayModel.DEFAULT_WATCHKIT_ENDPOINTS, g.watchkitEndpoints)
        assertEquals("SECRET", g.token)
    }

    @Test
    fun `pre-publication stock urls still migrate as untouched`() {
        // Pre-publication builds shipped the author's own box as the stock default; watches
        // installed back then must STILL migrate (not be treated as custom hosts). The old
        // stock hosts live here base64-encoded so no machine name or address is greppable;
        // GatewayModel matches them by SHA-256.
        fun legacy(b64: String) = String(java.util.Base64.getDecoder().decode(b64))
        val legacyIp = legacy("aHR0cDovLzEwMC42OS4xNDQuMzM=")
        val (list, selected) = GatewayModel.migrate("$legacyIp:8642/p/wear", "SECRET", "$legacyIp:8651")
        assertEquals(GatewayModel.SEED_ID, selected)
        assertEquals(GatewayModel.DEFAULT_API_ENDPOINTS, list.single().apiEndpoints)
        assertEquals("SECRET", list.single().token)
        val legacyIdentity = legacy("aHR0cDovL3R1eGRlc2t0b3AudGFpbGIxMmFhNS50cy5uZXQ=")
        val legacyLan = legacy("aHR0cDovLzE5Mi4xNjguMTAuNjI=")
        for (u in listOf("$legacyIdentity:8642/p/wear", "$legacyLan:8642/p/wear")) {
            val (l2, s2) = GatewayModel.migrate(u, "K", "")
            assertEquals(GatewayModel.SEED_ID, s2)
            assertEquals(GatewayModel.DEFAULT_API_ENDPOINTS, l2.single().apiEndpoints)
        }
    }

    @Test
    fun `the retired LAN placeholder still migrates as untouched (hashed)`() {
        // Builds installed BEFORE the LAN default was deleted carry it in their mirror; the
        // plaintext constant is gone but the SHA-256 stays, so those installs still migrate
        // (seed, tailnet-only) rather than being mislabeled as a custom host.
        val (list, selected) = GatewayModel.migrate("http://192.168.1.50:8642/p/wear", "K", "http://192.168.1.50:42651")
        assertEquals(GatewayModel.SEED_ID, selected)
        assertEquals(GatewayModel.DEFAULT_API_ENDPOINTS, list.single().apiEndpoints)
        assertEquals(GatewayModel.DEFAULT_WATCHKIT_ENDPOINTS, list.single().watchkitEndpoints)
    }

    @Test
    fun `a user-added LAN literal is a real custom host — it leads, tailnet follows`() {
        // DELETE of the LAN DEFAULT never meant DELETE of LAN ENDPOINTS: any address the owner
        // typed stays (dialed THROUGH the tunnel) and keeps the "what worked today" ordering.
        val (list, selected) = GatewayModel.migrate("http://192.168.10.20:8642/p/wear", "K", "")
        assertEquals("192-168-10-20", selected)
        assertEquals(
            listOf("http://192.168.10.20:8642", GatewayModel.TAILNET_API),
            list.single().apiEndpoints,
        )
    }

    @Test
    fun `migrate custom host keeps its endpoint first and derives watchkit`() {
        val (list, selected) = GatewayModel.migrate("http://vps.example.com:8642/p/wear", "VK", "")
        val g = list.single()
        assertEquals(selected, g.id)
        assertEquals("vps-example-com", g.id)
        assertEquals(
            listOf("http://vps.example.com:8642", GatewayModel.TAILNET_API),
            g.apiEndpoints,
        )
        assertTrue(g.watchkitEndpoints.first().startsWith("http://vps.example.com:42651"))
        assertEquals("VK", g.token)
    }

    @Test
    fun `migrate custom watchkit url leads the watchkit order and dedupes`() {
        val (list, _) = GatewayModel.migrate("http://vps.example.com:8642/p/wear", "", "http://vps.example.com:8651")
        val g = list.single()
        assertEquals("http://vps.example.com:8651", g.watchkitEndpoints.first())
        assertEquals(g.watchkitEndpoints.size, g.watchkitEndpoints.distinct().size)
    }

    @Test
    fun `migrate keeps custom watchkit url when api is stock`() {
        val (list, _) = GatewayModel.migrate("http://192.168.1.50:8642/p/wear", "", "http://other.box:9999")
        val g = list.single()
        assertEquals(GatewayModel.DEFAULT_API_ENDPOINTS, g.apiEndpoints)
        assertEquals("http://other.box:9999", g.watchkitEndpoints.first())
    }

    @Test
    fun `upsert replaces by id and appends new`() {
        val seed = listOf(GatewayModel.seed("a"))
        val edited = GatewayModel.upsert(seed, GatewayModel.SEED_ID.let { Gateway(it, "renamed", listOf("http://new:8642"), listOf(), "b") })
        assertEquals(1, edited.size)
        assertEquals("renamed", edited[0].label)
        assertEquals("b", edited[0].token)
        val two = GatewayModel.upsert(edited, Gateway("vps", "vps", listOf("http://v:8642"), listOf(), ""))
        assertEquals(2, two.size)
        assertEquals(listOf(GatewayModel.SEED_ID, "vps"), two.map { it.id })
    }

    @Test
    fun `upsert sanitizes endpoints and defaults label from host`() {
        val list = GatewayModel.upsert(emptyList(), Gateway("g", "  ", listOf(" http://h:8642/p/wear ", "http://h:8642", "  "), listOf(), "  "))
        val g = list.single()
        assertEquals(listOf("http://h:8642"), g.apiEndpoints) // normalize + dedupe + drop blanks
        assertEquals("g", g.label) // blank label falls back to id
    }

    @Test
    fun `upsert rejects gateway without api endpoint`() {
        var threw = false
        try { GatewayModel.upsert(emptyList(), Gateway("g", "g", emptyList(), emptyList(), "")) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `remove + selectedAfterRemove fixup`() {
        val a = GatewayModel.seed("")
        val b = Gateway("vps", "vps", listOf("http://v:8642"), listOf(), "")
        val list = listOf(a, b)
        assertEquals("vps", GatewayModel.selectedAfterRemove(a.id, list, a.id)) // next survivor
        assertEquals(a.id, GatewayModel.selectedAfterRemove(a.id, list, b.id)) // untouched
        assertEquals("", GatewayModel.selectedAfterRemove(b.id, listOf(b), b.id)) // empties out
        assertEquals(listOf(b), GatewayModel.remove(list, a.id))
    }

    @Test
    fun `foldBaseUrl prepends unknown endpoint but never reorders known ones`() {
        val seed = listOf(GatewayModel.seed(""))
        // Failover winner / agent switch re-sets base_url with the endpoint already on the list:
        // order MUST stand (the re-set is mechanical, not an owner edit).
        val foldedKnown = GatewayModel.foldBaseUrl(seed, GatewayModel.SEED_ID, "http://hermes.tailnet.ts.net:8642/p/wear")
        assertEquals(GatewayModel.DEFAULT_API_ENDPOINTS, foldedKnown.single().apiEndpoints)
        // User types a brand-new host (tailnet or a LAN literal riding the tunnel): it PREPENDS.
        val foldedNew = GatewayModel.foldBaseUrl(seed, GatewayModel.SEED_ID, "http://new.host:8642/p/wear")
        assertEquals(
            listOf("http://new.host:8642", GatewayModel.TAILNET_API),
            foldedNew.single().apiEndpoints,
        )
        // No selection / junk URL: list stands.
        assertEquals(seed, GatewayModel.foldBaseUrl(seed, "ghost", "http://x:1/p/wear"))
        assertEquals(seed, GatewayModel.foldBaseUrl(seed, GatewayModel.SEED_ID, ""))
    }

    @Test
    fun `foldToken lands on the selected gateway only`() {
        val list = listOf(GatewayModel.seed("old"), Gateway("vps", "vps", listOf("http://v:8642"), listOf(), "keep"))
        val folded = GatewayModel.foldToken(list, GatewayModel.SEED_ID, " new ")
        assertEquals("new", folded[0].token)
        assertEquals("keep", folded[1].token)
    }

    @Test
    fun `derivedWatchkit and hostId handle ports hosts and junk`() {
        assertEquals("http://a.b:42651", GatewayModel.derivedWatchkit("http://a.b:8642"))
        assertEquals("http://a.b:42651", GatewayModel.derivedWatchkit("http://a.b:8642/p/wear"))
        assertEquals("http://10.0.0.9:42651", GatewayModel.derivedWatchkit("http://10.0.0.9"))
        assertEquals("", GatewayModel.derivedWatchkit("nonsense"))
        assertEquals("vps-example-com", GatewayModel.hostId("http://vps.example.com:8642/p/wear"))
        assertEquals("gateway", GatewayModel.hostId("://"))
    }

    @Test
    fun `primary accessors survive empty lists`() {
        val g = Gateway("x", "x", emptyList(), emptyList(), "")
        assertEquals("", g.primaryApi)
        assertEquals("", g.primaryWatchkit)
        assertFalse(GatewayModel.decode(GatewayModel.encode(listOf(GatewayModel.seed("t")))).isEmpty())
    }

    // ── foldWatchkit + registry pruning guards (owner bug 2026-10-29) ───────

    @Test
    fun `foldWatchkit mirrors foldBaseUrl and leaves sibling gateways untouched`() {
        val a = Gateway("a", "A", listOf("http://a:8642"), listOf("http://a:8651"), "t")
        val b = Gateway("b", "B", listOf("http://b:8642"), listOf("http://b:8651"), "t")
        val list = listOf(a, b)
        // Unknown wk prepends onto the SELECTED gateway only…
        val folded = GatewayModel.foldWatchkit(list, "a", "http://tail.a.ts.net:8651")
        assertEquals(listOf("http://tail.a.ts.net:8651", "http://a:8651"), folded[0].watchkitEndpoints)
        assertEquals(b, folded[1])
        // …a known one keeps its position (failover re-sets watchkit_url constantly), …
        assertEquals(list, GatewayModel.foldWatchkit(list, "a", "http://a:8651/"))
        // …and junk/ghost-selection folds never rewrite anything.
        assertEquals(list, GatewayModel.foldWatchkit(list, "ghost", "http://x:8651"))
        assertEquals(list, GatewayModel.foldWatchkit(list, "a", "  "))
    }

    @Test
    fun `upsert onto a blip-read list is the only safe prune, and folds keep their siblings`() {
        // The wipe shape the owner hit: registry held two gateways, one save wrote back a list of
        // ONE. Upsert/remove are whole-list operations by design — the safety lives in AppSettings
        // (gateways() returns the last good decode when a non-blank blob fails to parse), so the
        // model must simply never invent or drop entries on its own.
        val list = listOf(Gateway("a", "A", listOf("http://a:8642"), listOf(), "t"), Gateway("b", "B", listOf("http://b:8642"), listOf(), "t"))
        assertEquals(listOf("a", "b"), GatewayModel.upsert(list, list[0].copy(label = "A2")).map { it.id })
        assertEquals(listOf("a", "b"), GatewayModel.foldToken(list, "a", "NEW").map { it.id })
        assertEquals(listOf("a", "b"), GatewayModel.foldBaseUrl(list, "a", "http://c:8642").map { it.id })
        assertEquals(listOf("a"), GatewayModel.foldBaseUrl(list, "a", "http://a:8642").let { GatewayModel.remove(it, "b") }.map { it.id })
    }

    // ── per-agent keys (SPEC §12.2: the multi-agent 401 fix) ────────────────

    private fun gw(token: String, keys: Map<String, String> = emptyMap()) =
        Gateway("g", "lab", listOf("http://h:8642"), listOf("http://h:8651"), token, keys)

    @Test
    fun `an agent's own key wins, the gateway token is the fallback`() {
        val g = gw("GWKEY", mapOf("wear" to "WEARKEY", "research" to ""))
        assertEquals("WEARKEY", g.keyFor("wear"))
        // A blank stored value is treated as "no key", so the fallback can still work.
        assertEquals("GWKEY", g.keyFor("research"))
        assertEquals("GWKEY", g.keyFor("brand-new-agent"))
    }

    @Test
    fun `the resolve order is agent key then gateway token then mirror token`() {
        val g = gw("GWKEY", mapOf("wear" to "WEARKEY"))
        assertEquals("WEARKEY", GatewayModel.tokenFor(g, "wear", "MIRROR"))
        assertEquals("GWKEY", GatewayModel.tokenFor(g, "other", "MIRROR"))
        assertEquals("MIRROR", GatewayModel.tokenFor(null, "wear", "MIRROR"))
        // A gateway with no token of its own must not shadow a mirror that works (single-gateway
        // installs keep behaving exactly as they do today).
        assertEquals("MIRROR", GatewayModel.tokenFor(gw("", mapOf("x" to " ")), "wear", "MIRROR "))
    }

    @Test
    fun `agent keys survive the registry round trip`() {
        val list = listOf(gw("T", mapOf("wear" to "A", "elvtr" to "B")))
        val back = GatewayModel.decode(GatewayModel.encode(list))
        assertEquals(mapOf("wear" to "A", "elvtr" to "B"), back.single().agentKeys)
    }

    @Test
    fun `an older watch's stored blob still decodes without agent keys`() {
        // Rollback safety: the field is additive and `ignoreUnknownKeys` covers the other way.
        val legacy = GatewayModel.encode(listOf(gw("T")))
        assertEquals(emptyMap<String, String>(), GatewayModel.decode(legacy).single().agentKeys)
        assertEquals(
            "WEARKEY",
            GatewayModel.decode(legacy.replace("\"token\":\"T\"", "\"token\":\"T\",\"agentKeys\":{\"wear\":\"WEARKEY\"}"))
                .single().keyFor("wear"),
        )
    }
}
