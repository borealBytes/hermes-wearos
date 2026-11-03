package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bezel-volume bus (SPEC §12.10): the player binds, the bezel steers the PLAYER; nobody
 * bound, the keys pass through to the system. Math on DialVolume's 5 % grid.
 */
class RotaryVolumeTest {

    @Test fun `unbound bus reports inactive and consumes nothing`() {
        RotaryVolume.unbind { true }
        assertFalse(RotaryVolume.active)
        assertFalse(RotaryVolume.rotate(1))
    }

    @Test fun `bound handler takes the key and sees detent sign`() {
        val seen = mutableListOf<Int>()
        val h: (Int) -> Boolean = { seen += it; true }
        RotaryVolume.bind(h)
        try {
            assertTrue(RotaryVolume.active)
            assertTrue(RotaryVolume.rotate(1))
            assertTrue(RotaryVolume.rotate(-3))
        } finally {
            RotaryVolume.unbind(h)
        }
        assertEquals(listOf(1, -3), seen)
        assertFalse(RotaryVolume.active)
    }

    @Test fun `stepping clamps at both rails on the 5 percent grid`() {
        assertEquals(0.55f, RotaryVolume.step(0.5f, 1), 0.001f)
        assertEquals(0.45f, RotaryVolume.step(0.5f, -1), 0.001f)
        assertEquals(1f, RotaryVolume.step(0.98f, 5), 0.001f)
        assertEquals(0f, RotaryVolume.step(0.02f, -5), 0.001f)
        // Off-grid inputs land on the grid (DialVolume.quantize's rule).
        assertEquals(0.5f, RotaryVolume.step(0.52f, 0), 0.001f)
    }

    @Test fun `a second bind replaces the first and unbind only drops its own handler`() {
        val a: (Int) -> Boolean = { true }
        val b: (Int) -> Boolean = { false }
        RotaryVolume.bind(a)
        RotaryVolume.bind(b)
        try {
            assertFalse(RotaryVolume.rotate(1)) // b replaced a
            RotaryVolume.unbind(a)              // unbinding the OLD handler must not free the bus
            assertTrue(RotaryVolume.active)
        } finally {
            RotaryVolume.unbind(b)
        }
    }
}
