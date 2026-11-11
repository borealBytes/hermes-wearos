package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPIN mode (owner batch 5): slide up, sweep laterally, the ring rotates a slot per detent and
 * the lit icon arrives under the finger; come back to the middle and release commits it — while
 * a plain directional flick keeps working untouched. These are the rules the finger learns by,
 * so they are pinned.
 */
class DialSpinTest {

    private val scale = 1f
    private fun spin() = DialSpin(scale)
    private val step get() = OrbDial.SPIN_STEP_REF

    /** Viewport-px vector pointing at a slot's wedge (same coords the ring draws in). */
    private fun aim(slot: Int, distPx: Float = 100f): Pair<Float, Float> {
        val rad = Math.toRadians((slot * OrbDial.SLOT_DEG).toDouble())
        return Pair((distPx * Math.sin(rad)).toFloat(), (-distPx * Math.cos(rad)).toFloat())
    }

    private fun moveTo(s: DialSpin, x: Float, y: Float) = s.move(x, y)

    @Test fun `aim mode follows the finger angle with no spin engaged`() {
        val s = spin()
        val (x, y) = aim(3)
        moveTo(s, x, y)
        assertEquals(3, s.activeSlot)
        assertFalse(s.spinning)
        assertEquals(0f, s.phaseDeg, 0.001f)
    }

    @Test fun `finger home before any spin cancels`() {
        val s = spin()
        val (x, y) = aim(0)
        moveTo(s, x, y)
        moveTo(s, 0f, 10f) // back inside the activation gate
        assertNull(s.commit())
        assertEquals(-1, s.activeSlot)
    }

    @Test fun `lateral jitter below one detent never engages the spin`() {
        val s = spin()
        moveTo(s, 0f, -100f)
        moveTo(s, step * 0.6f, -100f) // < 1 step of travel
        assertFalse(s.spinning)
        assertEquals(0, s.activeSlot) // still keyboard
    }

    @Test fun `a full detent right walks one slot toward the sweep and lights it`() {
        val s = spin()
        moveTo(s, 0f, -100f)
        moveTo(s, step, -100f)
        assertTrue(s.spinning)
        assertEquals(1, s.activeSlot) // 12:00 -> 1:20 with a rightward push
        // …and the ring rotated THAT icon under the finger:
        val finger = OrbDial.angleOf(step, -100f)
        assertEquals(OrbDial.normDeg180(finger - OrbDial.SLOT_DEG), s.phaseDeg, 0.001f)
    }

    @Test fun `three detents left walk back wrapping past twelve`() {
        val s = spin()
        moveTo(s, 0f, -100f)
        for (i in 1..3) moveTo(s, -step * i, -100f)
        assertEquals(6, s.activeSlot) // 0 - 3 wraps to slot 6 (8:00 — the arc spin EXISTS for)
    }

    @Test fun `a full turn comes home`() {
        val s = spin()
        moveTo(s, 0f, -100f)
        for (i in 1..OrbDial.SLOT_COUNT) moveTo(s, step * i, -100f)
        assertEquals(0, s.activeSlot)
        assertTrue(s.spinning)
    }

    @Test fun `sweeping the other way outside the circle un-steps it`() {
        val s = spin()
        moveTo(s, 0f, -100f)
        moveTo(s, step, -100f)
        moveTo(s, 0f, -100f) // travel undone at full reach → steps back to 0
        assertEquals(0, s.activeSlot)
        assertTrue(s.spinning) // the gesture stays owned; un-stepping is not un-spinning
    }

    @Test fun `come back to the middle and let go — the walk home does NOT un-step`() {
        val s = spin()
        moveTo(s, 0f, -100f)
        moveTo(s, step, -100f) // spin to slot 1
        moveTo(s, step * 0.4f, -60f) // heading in: still outside the freeze circle, small pull
        moveTo(s, 2f, 8f) // over the middle: frozen, selection waits
        assertEquals(1, s.activeSlot) // home is NOT cancel once spinning
        assertEquals(1, s.commit()!!)
    }

    @Test fun `spin only arms from the top arc - a side drag keeps pure aim semantics`() {
        val s = spin()
        val (x, y) = aim(2) // 2:40
        moveTo(s, x, y)
        moveTo(s, x + step * 2, y) // big lateral travel while aiming sideways
        assertFalse(s.spinning)
        assertEquals(2, s.commit()!!)
    }

    @Test fun `changed fires for wedge hops and detents, not for staying put`() {
        val s = spin()
        val (x0, y0) = aim(0)
        moveTo(s, x0, y0)
        assertTrue(s.changed) // -1 -> 0 (armed hop)
        moveTo(s, 1f, -100f)
        assertFalse(s.changed) // same wedge, sub-detent lean
        val (x4, y4) = aim(4)
        moveTo(s, x4, y4)
        assertTrue(s.changed) // wedge hop to 6:00… spin engaged though (68 px of x travel from
        // above? x goes 1 -> 0: no). Either way SOMETHING changed — the caller only ticks on this.
    }

    @Test fun `#23 the owner's curved gear drag passes through the arc but never spins`() {
        // Trace 2026-11-11: gear drags committed slot=3 (model). Finger launches up-right
        // (knuckle curve), sweeping the top arc at TRANSIT radius before flattening east.
        // The old gate counted that lateral transit: the ring stepped a detent mid-drag.
        val s = DialSpin(1f)
        listOf(Pair(30f, -60f), Pair(90f, -80f), Pair(150f, -50f), Pair(190f, -5f)).forEach {
            s.move(it.first, it.second)
        }
        assertFalse("transit through 12:00's arc must not engage spin", s.spinning)
        assertEquals(2, s.commit()) // the wedge he aimed: due east = slot 2 = settings
    }

    @Test fun `the batch-5 flow still spins — slide up deep, dwell, sweep lateral`() {
        val s = DialSpin(1f)
        s.move(0f, -120f)          // slide up, deep
        s.move(0f, -121f)          // dwell
        var x = 0f
        while (!s.spinning && x < 200f) { x += 9f; s.move(x, -120f) } // sweep right
        assertTrue("a deliberate top dwell + lateral sweep must engage", s.spinning)
    }

    @Test fun `reset returns the whole state machine to rest`() {
        val s = spin()
        moveTo(s, 0f, -100f)
        moveTo(s, step, -100f)
        s.reset()
        assertEquals(-1, s.activeSlot)
        assertFalse(s.spinning)
        assertEquals(0f, s.phaseDeg, 0.001f)
        assertNull(s.commit())
    }
}
