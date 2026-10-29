package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
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
}
