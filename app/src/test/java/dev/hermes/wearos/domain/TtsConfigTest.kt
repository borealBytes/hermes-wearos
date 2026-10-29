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
}
