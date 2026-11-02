package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch-4 #8: the picker's honesty contract is pure logic — pinned here, not on-device. */
class SttProvidersTest {

    @Test fun `unknown or missing provider id falls back to the offline default`() {
        assertEquals(SttProvider.LOCAL, SttProviders.fromId(null))
        assertEquals(SttProvider.LOCAL, SttProviders.fromId(""))
        assertEquals(SttProvider.LOCAL, SttProviders.fromId("gateway-future"))
        assertEquals(SttProvider.SYSTEM, SttProviders.fromId("system"))
    }

    @Test fun `normalize keeps every real id and collapses junk to the safe default`() {
        assertEquals("local", SttProviders.normalize("local"))
        assertEquals("system", SttProviders.normalize("system"))
        assertEquals("gateway", SttProviders.normalize("gateway")) // the #9 regression this gate exists for
        assertEquals("local", SttProviders.normalize("bogus"))
        assertEquals("local", SttProviders.normalize(null))
    }

    @Test fun `vendor auto-detect names the big stacks, passes the rest through`() {
        assertEquals("Google", SttProviders.vendor("com.google.android.googlequicksearchbox"))
        assertEquals("Samsung", SttProviders.vendor("com.samsung.android.weamp"))
        assertEquals("Microsoft", SttProviders.vendor("com.microsoft.cortana.stt"))
        assertEquals("com.acme.voice", SttProviders.vendor("com.acme.voice"))
        assertEquals("system", SttProviders.vendor(null))
    }

    @Test fun `the system row says OFF-DEVICE with the vendor name`() {
        val sub = SttProviders.subtitle(SttProvider.SYSTEM, true, true, "com.google.android.googlequicksearchbox")
        assertTrue("must be honest about the network: $sub", sub.startsWith("OFF-DEVICE"))
        assertTrue(sub.contains("Google"))
    }

    @Test fun `local row is honest about a missing model too`() {
        assertTrue(SttProviders.subtitle(SttProvider.LOCAL, true, true, null).contains("offline"))
        assertTrue(SttProviders.subtitle(SttProvider.LOCAL, false, true, null).contains("missing"))
    }

    @Test fun `unavailable system recognizer says so instead of naming a vendor`() {
        val sub = SttProviders.subtitle(SttProvider.SYSTEM, true, false, "com.google.android.googlequicksearchbox")
        assertEquals("no recognizer on this watch", sub)
    }

    @Test fun `gateway is the third provider, honestly labelled with the live engine`() {
        assertEquals(SttProvider.GATEWAY, SttProviders.fromId("gateway"))
        val named = SttProviders.subtitle(SttProvider.GATEWAY, true, true, null, "whisper")
        assertTrue(named.contains("whisper")); assertTrue(named.contains("auto-local"))
        val dark = SttProviders.subtitle(SttProvider.GATEWAY, true, true, null, null)
        assertTrue("offline must be honest too: $dark", dark.contains("offline"))
        // Auto-fallback makes it ALWAYS usable — the wearer is never stranded (the contract).
        assertTrue(SttProviders.usable(SttProvider.GATEWAY, false, false))
    }

    @Test fun `usability gates on the engine that was picked, not the other one`() {
        assertTrue(SttProviders.usable(SttProvider.LOCAL, true, false))
        assertFalse(SttProviders.usable(SttProvider.LOCAL, false, true))
        assertTrue(SttProviders.usable(SttProvider.SYSTEM, false, true))
        assertFalse(SttProviders.usable(SttProvider.SYSTEM, true, false))
    }

    // ── batch-4 #13: the listening overlay's provider + streaming honesty ─────────────────────

    @Test fun `overlay names the provider and the live engine, never invents one`() {
        assertEquals("Local Vosk", SttProviders.overlayName(SttProvider.LOCAL))
        assertEquals(
            "System · Google",
            SttProviders.overlayName(SttProvider.SYSTEM, "com.google.android.tts"),
        )
        assertEquals("Gateway · whisper", SttProviders.overlayName(SttProvider.GATEWAY, null, "whisper"))
        // Engine unknown (health never answered) says "engine ?" — an honest question mark.
        assertEquals("Gateway · engine ?", SttProviders.overlayName(SttProvider.GATEWAY))
    }

    @Test fun `streamsText tells the truth per engine — buffered whisper is NOT streaming`() {
        assertTrue(SttProviders.streamsText(SttProvider.LOCAL)!!)
        assertTrue(SttProviders.streamsText(SttProvider.SYSTEM)!!)
        assertEquals(false, SttProviders.streamsText(SttProvider.GATEWAY, "whisper"))
        assertEquals(true, SttProviders.streamsText(SttProvider.GATEWAY, "parakeet"))
        assertEquals(null, SttProviders.streamsText(SttProvider.GATEWAY))
        assertEquals(null, SttProviders.streamsText(SttProvider.GATEWAY, "mystery-engine"))
    }

    @Test fun `streamsLabel pairs the checkbox with what to expect instead`() {
        assertEquals("text streaming ✓", SttProviders.streamsLabel(true))
        assertTrue(SttProviders.streamsLabel(false).contains("✗"))
        assertTrue("buffered must say WHEN the text lands", SttProviders.streamsLabel(false).contains("stop"))
        assertEquals("text streaming ?", SttProviders.streamsLabel(null))
    }

    @Test fun `nav summary names the orb default without probing the environment`() {
        assertTrue(SttProviders.navSummary(SttProvider.LOCAL).contains("Vosk"))
        assertTrue(SttProviders.navSummary(SttProvider.SYSTEM).contains("System"))
        assertTrue(SttProviders.navSummary(SttProvider.GATEWAY).contains("Gateway"))
    }

    // ── batch-4 #16: gateway ENGINE selection (owner: *"clicking … should let you select …
    // installed models … set up to expand in the future"*) ──────────────────────────────────

    private val par = SttProviders.SttEngineInfo("parakeet", streaming = true, ready = true)
    private val whis = SttProviders.SttEngineInfo("whisper", streaming = false, ready = true)
    private val future = SttProviders.SttEngineInfo("cricket", streaming = true, ready = true)
    private val missing = SttProviders.SttEngineInfo("k2", streaming = true, ready = false, missing = "model missing")

    @Test fun `effective engine honors the pick only when health says it is ready`() {
        assertEquals("parakeet", SttProviders.effectiveEngine("parakeet", "whisper", listOf(par, whis)))
        // picked an engine the box never reported ready → the gateway default answers, honestly
        assertEquals("whisper", SttProviders.effectiveEngine("k2", "whisper", listOf(whis, missing)))
        assertEquals("whisper", SttProviders.effectiveEngine("", "whisper", listOf(par)))
        assertEquals(null, SttProviders.effectiveEngine("", null, emptyList()))
        // an uninstalled pick with no health default is still unknown — not a fake name
        assertEquals(null, SttProviders.effectiveEngine("k2", null, listOf(missing)))
    }

    @Test fun `pick matching is case and space proof`() {
        assertTrue(SttProviders.pickMatches("Parakeet ", "parakeet"))
        assertTrue(!SttProviders.pickMatches("", "whisper"))
        assertTrue(!SttProviders.pickMatches("whisper", null))
    }

    @Test fun `engine streaming truth comes from the inventory first, known map second`() {
        val engines = listOf(par, whis, future, missing)
        assertEquals(true, SttProviders.engineStreams("parakeet", engines))
        assertEquals(false, SttProviders.engineStreams("whisper", engines))
        // a FUTURE engine needs no watch update: /health's flag is the truth
        assertEquals(true, SttProviders.engineStreams("cricket", engines))
        // no inventory (offline) falls back to the two engines that predate it…
        assertEquals(true, SttProviders.engineStreams("parakeet", emptyList()))
        assertEquals(false, SttProviders.engineStreams("whisper", emptyList()))
        // …and anything else stays an honest question mark
        assertEquals(null, SttProviders.engineStreams("cricket", emptyList()))
        assertEquals(null, SttProviders.engineStreams(null, engines))
    }

    @Test fun `engine row labels say installed streaming or not installed`() {
        assertTrue(SttProviders.engineRowLabel(par).contains("text streaming ✓"))
        assertTrue(SttProviders.engineRowLabel(whis).contains("final when you stop"))
        assertTrue("uninstalled must say so: ${SttProviders.engineRowLabel(missing)}",
            SttProviders.engineRowLabel(missing).contains("not installed"))
    }
}
