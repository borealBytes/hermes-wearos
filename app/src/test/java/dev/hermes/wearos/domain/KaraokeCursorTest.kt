package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-utterance karaoke cursor (owner 2026-10-29: the number-list reply pinned line 1 while
 * the voice was deep in the list — one un-punctuated utterance, one onStart). These are the
 * arithmetic rails under the ticker; the TTS plumbing lives in voice/SpeakAloud.
 */
class KaraokeCursorTest {

    @Test fun `estimate walks chars over time and clamps at both ends`() {
        assertEquals(0, KaraokeCursor.estimateOffset(100, 0L, 16f))
        assertEquals(0, KaraokeCursor.estimateOffset(100, -5L, 16f))
        assertEquals(0, KaraokeCursor.estimateOffset(0, 1000L, 16f))
        assertEquals(0, KaraokeCursor.estimateOffset(100, 1000L, 0f))
        assertEquals(16, KaraokeCursor.estimateOffset(100, 1000L, 16f))
        assertEquals(80, KaraokeCursor.estimateOffset(100, 5000L, 16f))
        assertEquals(100, KaraokeCursor.estimateOffset(100, 50_000L, 16f)) // clamp to end
    }

    @Test fun `calibration seeds then rides an EMA inside sane rails`() {
        // 320 chars read in 16 s = 20 c/s — the seed is replaced wholesale by the first real one.
        assertEquals(20f, KaraokeCursor.calibrate(null, 320, 16_000L)!!, 0.01f)
        // EMA: 16 current + 24 measured → 16*0.6 + 24*0.4 = 19.2
        assertEquals(19.2f, KaraokeCursor.calibrate(16f, 480, 20_000L)!!, 0.01f)
        // Garbage inputs are IGNORED (current passes through): whisper-short text, absurd speed.
        assertEquals(16f, KaraokeCursor.calibrate(16f, 8, 2_000L)!!, 0.01f)
        assertEquals(11.2f, KaraokeCursor.calibrate(16f, 300, 120_000L)!!, 0.01f) // 2.5 c/s → rail 4 → EMA
        // Rails: an insane measurement lands on the rail, not past it.
        assertEquals(KaraokeCursor.MAX_CHARS_PER_SEC, KaraokeCursor.calibrate(null, 500, 500L)!!, 0.01f)
        assertEquals(KaraokeCursor.MIN_CHARS_PER_SEC, KaraokeCursor.calibrate(null, 10, 30_000L)!!, 0.01f)
    }

    @Test fun `engine progress is trusted only when it actually moves`() {
        assertFalse(KaraokeCursor.engineProgressUsable(0f))    // bookend every engine fires
        assertFalse(KaraokeCursor.engineProgressUsable(-0.1f))
        assertFalse(KaraokeCursor.engineProgressUsable(Float.NaN))
        assertTrue(KaraokeCursor.engineProgressUsable(0.01f))
        assertTrue(KaraokeCursor.engineProgressUsable(1f))
    }
}
