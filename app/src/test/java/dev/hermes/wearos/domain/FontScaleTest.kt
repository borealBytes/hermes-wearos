package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pinch font-scale clamp + quantize math (0.55×–1.6×, 0.05 grid). */
class FontScaleTest {

    @Test fun `clamps to the approved range`() {
        assertEquals(FontScale.MIN, FontScale.clamp(0.1f), 0.0001f)
        assertEquals(FontScale.MAX, FontScale.clamp(9f), 0.0001f)
        assertEquals(1.2f, FontScale.clamp(1.2f), 0.0001f)
        assertEquals(FontScale.DEFAULT, FontScale.clamp(Float.NaN), 0.0001f)
    }

    @Test fun `quantize snaps to the 0_05 grid`() {
        assertEquals(1.15f, FontScale.quantize(1.14f), 0.0001f)
        assertEquals(1.10f, FontScale.quantize(1.124f), 0.0001f)
        assertEquals(1.00f, FontScale.quantize(0.99f), 0.0001f)
        assertEquals(1.05f, FontScale.quantize(1.03f), 0.0001f)
        assertEquals(FontScale.MIN, FontScale.quantize(0.5f), 0.0001f)
        assertEquals(FontScale.MAX, FontScale.quantize(1.62f), 0.0001f)
        assertEquals(FontScale.DEFAULT, FontScale.quantize(Float.NaN), 0.0001f)
        assertEquals(FontScale.DEFAULT, FontScale.quantize(Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test fun `pinch applies zoom factors multiplicatively`() {
        assertEquals(1.2f, FontScale.apply(1f, 1.2f), 0.0001f)
        assertEquals(0.85f, FontScale.apply(1f, 0.849f), 0.0001f)
        // tiny deltas inside the grid dead-zone keep the current step
        assertEquals(1.00f, FontScale.apply(1f, 1.02f), 0.0001f)
    }

    @Test fun `pinch saturates at both ends exactly`() {
        assertEquals(FontScale.MAX, FontScale.apply(1.5f, 2f), 0.0001f)
        assertEquals(FontScale.MIN, FontScale.apply(0.8f, 0.1f), 0.0001f)
        // garbage factors are no-ops, never NaN
        assertEquals(1.05f, FontScale.apply(1.05f, Float.NaN), 0.0001f)
        assertEquals(1.05f, FontScale.apply(1.05f, 0f), 0.0001f)
    }

    @Test fun `isDefault recognizes only the 1_0 neighborhood`() {
        assertTrue(FontScale.isDefault(1f))
        assertTrue(FontScale.isDefault(1.02f)) // quantizes onto 1.00
        assertFalse(FontScale.isDefault(1.05f))
        assertFalse(FontScale.isDefault(0.95f))
        assertFalse(FontScale.isDefault(FontScale.MIN))
    }
}
