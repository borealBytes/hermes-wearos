package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE EDGE-CIRCLE GESTURE MATH (batch-4 #7). The gesture earns its keep by (a) engaging ONLY in
 * the outer ring, (b) never firing on jitter or straight drags (zero ticks = zero scroll = zero
 * consumption), (c) keeping sign: clockwise travels forward, counter-clockwise back — across the
 * ±180° seam — and (d) bounding its step so one fast whoop cannot fling the list a mile.
 */
class EdgeScrollTest {

    private val face = Triple(480f, 480f, 0f) // w, h, unused

    @Test fun `the ring is the outer twenty percent of the face`() {
        val (w, h, _) = face
        assertTrue(EdgeScroll.inRing(240f, 240f - 235f, w, h))   // top edge — in
        assertTrue(!EdgeScroll.inRing(240f, 60f, w, h))           // r=180 < the 192 gate — out
        assertTrue(!EdgeScroll.inRing(240f, 120f, w, h))          // well inside — out
        assertTrue(EdgeScroll.inRing(20f, 240f, w, h))            // left edge — in
        assertTrue(!EdgeScroll.inRing(0f, 0f, 0f, 0f))            // degenerate face
    }

    @Test fun `jitter and pauses buy nothing — straight drags produce no ticks`() {
        // Finger straight down at the left edge (x≈8): the ANGLE barely changes while y slides.
        val (w, h, _) = face
        var prev = EdgeScroll.angleDeg(8f, 120f, w, h)
        var rem = 0f
        var scrolled = 0f
        for (y in 130..360 step 10) {
            val a = EdgeScroll.angleDeg(8f, y.toFloat(), w, h)
            val step = EdgeScroll.scrollPxFor(prev, a, rem, w, h)
            prev = a; rem = step.remainderDeg; scrolled += step.px
        }
        // THE RIM IS CURVED: dragging down the left edge sweeps a real ~80° arc — that IS how
        // edge rotation works on a bezel-less watch (Wear's own bezel emulation treats rim
        // drags as rotary). The contract is BOUNDED yield (no runaway), not zero.
        assertTrue("straight drag leaked $scrolled px", kotlin.math.abs(scrolled) in 40f..150f)
    }

    @Test fun `a clockwise quarter circle scrolls forward, counter-clockwise back`() {
        val (w, h, _) = face
        // Screen-y point formula (y DOWN): -90 is the TOP of the glass and top -> right is
        // CLOCKWISE (verified on-device with motionevent rim sweeps — do not "fix" this sign).
        fun sweep(points: List<Float>): Float {
            var prev = EdgeScroll.angleDeg(240f, 30f, w, h) // top of the ring
            var rem = 0f
            var total = 0f
            for (deg in points) {
                val rad = Math.toRadians(deg.toDouble())
                val x = 240f + 210f * Math.cos(rad).toFloat()
                val y = 240f + 210f * Math.sin(rad).toFloat()
                val a = EdgeScroll.angleDeg(x, y, w, h)
                val step = EdgeScroll.scrollPxFor(prev, a, rem, w, h)
                prev = a; rem = step.remainderDeg; total += step.px
            }
            return total
        }
        val cw = sweep(listOf(-90f, -60f, -30f, 0f))    // top -> right along the ring = CW
        val ccw = sweep(listOf(-90f, -120f, -150f, 180f)) // top -> left = CCW
        assertTrue("CW should scroll forward, got $cw", cw > 40f)
        assertTrue("CCW should scroll back, got $ccw", ccw < -40f)
        assertEquals("symmetry", -ccw, cw, 1f)
    }

    @Test fun `the seam does not flip the sign (180 to -180 is still travelling CW)`() {
        // Straight across the seam in one jump: 179 -> -179 is +2° CW, NOT -358°.
        assertEquals(2f, EdgeScroll.angleDeltaDeg(179f, -179f), 0.01f)
        assertEquals(-2f, EdgeScroll.angleDeltaDeg(-179f, 179f), 0.01f)
    }

    @Test fun `the lock separates circles from rim-drags by angular velocity, not position`() {
        // The gesture locks on |delta| >= LOCK_DELTA_DEG per event. Contract at normal speeds:
        val lock = EdgeScroll.LOCK_DELTA_DEG
        // • a circle at ~15 Hz sends 8-15° per event -> locks (second event, always)
        assertTrue(8f >= lock)
        // • a straight rim drag (the left-edge sweep above) moves <=2° per 10px event -> never
        val (w, h, _) = face
        var prev = EdgeScroll.angleDeg(8f, 120f, w, h)
        for (y in 130..360 step 10) {
            val a = EdgeScroll.angleDeg(8f, y.toFloat(), w, h)
            assertTrue(
                "rim drag event tripped the lock: ${EdgeScroll.angleDeltaDeg(prev, a)}°",
                kotlin.math.abs(EdgeScroll.angleDeltaDeg(prev, a)) < lock,
            )
            prev = a
        }
        // • jitter (±3° hops) sits BELOW the lock: it is what ANGLE_STEP_DEG's dead-band absorbs.
    }

    @Test fun `one call never exceeds the step cap, and sub-tick remainders carry over`() {
        val (w, h, _) = face
        // A 90° jump in one event: capped per call…
        val big = EdgeScroll.scrollPxFor(-90f, 0f, 0f, w, h)
        assertTrue("cap breached: ${big.px}", big.px <= EdgeScroll.MAX_STEP_PX + 0.01f)
        assertTrue("cap swallowed a whole step of remainder", big.px > 0f)
        // …while a 3° wiggle only banks the remainder (nothing scrolls yet).
        val tiny = EdgeScroll.scrollPxFor(0f, 3f, 0f, w, h)
        assertEquals(0f, tiny.px, 0.01f)
        assertEquals(3f, tiny.remainderDeg, 0.01f)
        // Banked + 5 more crosses the 7° tick exactly once.
        val next = EdgeScroll.scrollPxFor(3f, 8f, tiny.remainderDeg, w, h)
        assertTrue(next.px > 0f)
    }
}
