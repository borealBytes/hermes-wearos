package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice-out reply config (owner 2026-10-29: "change voices … and change speed"): the rate the
 * engine gets is always clamped into the intelligible band, the default row reproduces the old
 * hardcoded 1.05 (an upgrade nobody hears), and chip labels resolve back from stored rates.
 */
class TtsConfigTest {

    @Test
    fun `the default rate is the pre-feature hardcoded one`() {
        assertEquals(1.05f, TtsConfig.DEFAULT_RATE, 0.0001f)
        assertEquals("normal", TtsConfig.labelFor(TtsConfig.DEFAULT_RATE))
    }

    @Test
    fun `rates clamp into the intelligible band`() {
        assertEquals(0.5f, TtsConfig.clampRate(0.1f), 0.0001f)
        assertEquals(2.0f, TtsConfig.clampRate(9f), 0.0001f)
        assertEquals(1.2f, TtsConfig.clampRate(1.2f), 0.0001f)
        // Junk persisted from a crash can never silence the watch or send it into chipmunk.
        assertEquals(TtsConfig.DEFAULT_RATE, TtsConfig.clampRate(Float.NaN), 0.0001f)
    }

    @Test
    fun `chip labels resolve known rates and unknown ones stay neutral`() {
        assertEquals("slow", TtsConfig.labelFor(0.75f))
        assertEquals("fast", TtsConfig.labelFor(1.4f))
        assertEquals("custom", TtsConfig.labelFor(1.1f))
        assertEquals("custom", TtsConfig.labelFor(0f))
    }

    // ── per-agent reply volume (owner 2026-10-29) ────────────────────────────────────────────

    @Test fun `volumes quantize onto the 5 percent grid`() {
        assertEquals(40, TtsConfig.volumePercent(0.4f))
        assertEquals(40, TtsConfig.volumePercent(0.42f))   // nearest notch
        assertEquals(35, TtsConfig.volumePercent(0.374f))  // 7.48 steps → 7 → 35
        assertEquals(100, TtsConfig.volumePercent(1f))
        assertEquals(0, TtsConfig.volumePercent(0f))
        assertEquals(100, TtsConfig.volumePercent(0.99f))  // rounds up to full
        assertEquals(100, TtsConfig.volumePercent(2f))     // clamped first
        assertEquals(100, TtsConfig.volumePercent(Float.NaN)) // NaN → DEFAULT_VOLUME (full)
        assertEquals(0.4f, TtsConfig.volumeFromPercent(40), 0.0001f)
        assertEquals(1f, TtsConfig.volumeFromPercent(140), 0.0001f) // clamped both ends
    }

    @Test fun `the agent volume map round-trips and self-heals`() {
        val map = mapOf("wear" to 40, "default" to 100, "work" to 5)
        val raw = TtsConfig.encodeVolumes(map)
        assertEquals("default=100,wear=40,work=5", raw) // sorted: stable on-disk form
        assertEquals(map, TtsConfig.decodeVolumes(raw))
        // Garbage-tolerant: bad pairs skip, off-grid values land on the nearest notch, blanks empty.
        assertEquals(emptyMap<String, Int>(), TtsConfig.decodeVolumes(null))
        assertEquals(emptyMap<String, Int>(), TtsConfig.decodeVolumes(""))
        assertEquals(mapOf("wear" to 45), TtsConfig.decodeVolumes("wear=47,junk,=30,code,,wear=x"))
        assertEquals(0, TtsConfig.decodeVolumes("a=-9")?.get("a")) // negative clamps home
        assertEquals(100, TtsConfig.decodeVolumes("a=140")?.get("a"))
    }

    // ── batch-5 #19: MUTE IS MUTE — 0 % reply volume speaks NOTHING (no queue, no orb mouth) ──
    @Test fun `zero volume is mute, any sound is not`() {
        assertTrue(TtsConfig.muted(0f))
        assertTrue(TtsConfig.muted(-0.5f))          // clamps to 0 → mute
        assertTrue(!TtsConfig.muted(0.05f))         // one 5 % step is audible
        assertTrue(!TtsConfig.muted(1f))
        assertTrue(!TtsConfig.muted(Float.NaN))     // NaN → default (full), never "muted"
    }

    // ── batch-6 #24 AUDIO TAPER: slider = position on a -60 dB sweep, not amplitude ──
    @Test fun `taper lands the owner table`() {
        // Owner's reference table (-60 floor): 5% -> 0.14%, 25% -> 0.56%, 50% -> 3.16%, 90% -> 50.1%.
        assertEquals(0.0014f, TtsConfig.gainFromPosition(0.05f), 0.0002f)
        assertEquals(0.0056f, TtsConfig.gainFromPosition(0.25f), 0.0003f)
        assertEquals(0.0316f, TtsConfig.gainFromPosition(0.50f), 0.0005f)
        assertEquals(0.501f, TtsConfig.gainFromPosition(0.90f), 0.002f)
        assertEquals(1f, TtsConfig.gainFromPosition(1f), 0f)
    }

    @Test fun `taper mutes hard at zero - no floating-point leak`() {
        // The owner's gotcha: "handle x = 0 as a hard zero/mute flag" — the first stop must be
        // THE MUTE, not 10^(-inf) dust that leaks a near-silent-but-not-muted signal.
        assertEquals(0f, TtsConfig.gainFromPosition(0f), 0f)
        assertEquals(0f, TtsConfig.gainFromPosition(-0.4f), 0f)   // clamped
        assertEquals(1f, TtsConfig.gainFromPosition(1.6f), 0f)    // clamped, no overflow
        assertEquals(1f, TtsConfig.gainFromPosition(Float.NaN), 0f) // NaN -> default full
        assertTrue(TtsConfig.muted(0f) && TtsConfig.gainFromPosition(0f) == 0f)
    }

    @Test fun `taper is monotonic and round-trips through position`() {
        var prev = -1f
        for (p in 0..100) {
            val g = TtsConfig.gainFromPosition(p / 100f)
            assertTrue("pos $p not monotonic", g >= prev)
            prev = g
        }
        for (x in listOf(0f, 0.05f, 0.25f, 0.5f, 0.9f, 1f)) {
            assertEquals(x, TtsConfig.positionForGain(TtsConfig.gainFromPosition(x)), 0.002f)
        }
    }

    @Test fun `linear half-travel complaint is exactly what the taper fixes`() {
        // The ear: half as loud is HALF OF TEN = 0.316 amplitude = -10 dB. Linear put 50%
        // amplitude there (only -6 dB above full); the taper parks 50% at 0.0316 (-30 dB),
        // giving the bottom of the slider real travel.
        val g50 = TtsConfig.gainFromPosition(0.5f)
        assertTrue(g50 < 0.05f)
        // …and the TOP stays useful: 90 % is still over half amplitude.
        assertTrue(TtsConfig.gainFromPosition(0.9f) > 0.4f)
    }
}
