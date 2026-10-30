package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch-4 #13: the "it IS hearing you" meter math. A listener must trust a dot that moves with
 * their voice and sits still in silence — the mapping is pinned here, not on-device.
 */
class MicLevelTest {

    private fun pcm(v: Short, samples: Int): ByteArray {
        val b = ByteArray(samples * 2)
        for (i in 0 until samples) {
            b[i * 2] = (v.toInt() and 0xFF).toByte()
            b[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        return b
    }

    @Test fun `silence reads zero, full-scale reads one`() {
        assertEquals(0f, MicLevel.rmsNormalized(pcm(0, 160), 320), 0.0001f)
        assertEquals(0f, MicLevel.rmsNormalized(pcm(0, 160), 0), 0.0001f)
        assertEquals(1f, MicLevel.rmsNormalized(pcm(Short.MAX_VALUE, 160), 320), 0.001f)
    }

    @Test fun `loud is louder than quiet and the curve compresses for visibility`() {
        val quiet = MicLevel.rmsNormalized(pcm(1000, 160), 320)
        val loud = MicLevel.rmsNormalized(pcm(12000, 160), 320)
        assertTrue("quiet=$quiet loud=$loud", loud > quiet)
        // sqrt compression: speech-level input must NOT be invisible (linear would read 0.03…);
        // at 12000/32768 linear RMS is ~0.37 — the meter must read clearly above that floor.
        assertTrue("meter too flat: $loud", loud > 0.5f)
        assertTrue(quiet > 0.05f)
    }

    @Test fun `odd trailing byte is ignored, shorts reader matches byte reader`() {
        val bytes = pcm(5000, 8)
        assertEquals(
            MicLevel.rmsNormalized(bytes, bytes.size),
            MicLevel.fromShorts(ShortArray(8) { 5000 }, 8),
            0.0001f,
        )
        // 17 bytes = 8 samples + a stray byte: value must not jump on the partial sample.
        assertEquals(
            MicLevel.rmsNormalized(bytes, bytes.size),
            MicLevel.rmsNormalized(bytes + 0xFF.toByte(), bytes.size + 1),
            0.0001f,
        )
    }

    @Test fun `dB window from the system recognizer maps onto the same unit dot`() {
        assertEquals(0f, MicLevel.fromDb(-2f), 0.0001f)
        assertEquals(1f, MicLevel.fromDb(10f), 0.0001f)
        assertEquals(1f, MicLevel.fromDb(25f), 0.0001f) // clamped, never over-driven
        assertEquals(0f, MicLevel.fromDb(-20f), 0.0001f) // clamped below
        assertEquals(0.5f, MicLevel.fromDb(4f), 0.0001f)
    }
}
