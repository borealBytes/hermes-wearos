package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mute-lane double slider's second half (owner 2026-10-29): lateral travel after the dwell
 * maps to reply volume on the 5 % grid, clamps at both ends, and a full sweep
 * ([DialVolume.FULL_SWEEP_REF] ref px on the owner's scale-1 face) covers 0→1.
 */
class DialVolumeTest {

    private fun dial(start: Float = 1f) = DialVolume(1f, start)

    @Test
    fun `the first move anchors and moves nothing`() {
        val d = dial(start = 0.5f)
        d.move(100f)
        assertEquals(0.5f, d.level, 0.001f)
        assertFalse(d.changed)
    }

    @Test
    fun `rightward travel raises and leftward lowers on the five-percent grid`() {
        val d = dial(start = 0.5f)
        d.move(120f)                       // anchor
        d.move(120f + DialVolume.FULL_SWEEP_REF / 4f) // +25 % of a full sweep
        assertTrue(d.changed)
        assertEquals(0.75f, d.level, 0.001f)
        d.move(120f - DialVolume.FULL_SWEEP_REF / 4f) // below the anchor by 25 % of a sweep
        assertEquals(0.25f, d.level, 0.001f)
    }

    @Test
    fun `a full sweep covers the whole range and both ends clamp`() {
        val up = dial(start = 0f)
        up.move(0f); up.move(DialVolume.FULL_SWEEP_REF)
        assertEquals(1f, up.level, 0.001f)
        up.move(DialVolume.FULL_SWEEP_REF * 3f)      // overdrag
        assertEquals(1f, up.level, 0.001f)
        val down = dial(start = 1f)
        down.move(0f); down.move(-DialVolume.FULL_SWEEP_REF)
        assertEquals(0f, down.level, 0.001f)
    }

    @Test
    fun `tiny jitters quantize away and never fire the haptic`() {
        val d = dial(start = 0.5f)
        d.move(100f)
        d.move(100f + 3f)                   // 3 px of tremor « one 5 % step
        assertFalse(d.changed)
        assertEquals(0.5f, d.level, 0.001f)
    }

    @Test
    fun `junk start levels quantize into the band and the readout rounds clean`() {
        assertEquals(1f, DialVolume.quantize(Float.NaN), 0.001f)
        assertEquals(0f, DialVolume.quantize(-2f), 0.001f)
        assertEquals(1f, DialVolume.quantize(9f), 0.001f)
        assertEquals(65, DialVolume.pct(0.65f))
        assertEquals(0, DialVolume.pct(0f))
    }

    @Test
    fun `a smaller face scales the sweep with the pixels`() {
        val d = DialVolume(scale = 0.5f, startLevel = 0f)
        d.move(0f)
        d.move(DialVolume.FULL_SWEEP_REF * 0.5f)  // 120 REAL px at half scale = full sweep
        assertEquals(1f, d.level, 0.001f)
    }
}
