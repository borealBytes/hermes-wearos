package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner ask #5: the safe-circle math (chin/clock insets + the orb-bar arc lift). */
class RoundScreenTest {

    @Test fun `round screens get clock top chin bottom and 20dp edges`() {
        val i = RoundScreen.insetsFor(round = true)
        assertEquals(30f, i.topDp, 0.001f)
        assertEquals(16f, i.bottomDp, 0.001f)
        assertEquals(20f, i.horizontalDp, 0.001f)
    }

    @Test fun `square viewports keep only a modest gutter`() {
        val i = RoundScreen.insetsFor(round = false)
        assertEquals(0f, i.topDp, 0.001f)
        assertTrue(i.horizontalDp in 1f..10f)
    }

    @Test fun `arc lift is zero at screen center and grows off-axis`() {
        // 480x480 round @ density 1 → radius 240dp.
        assertEquals(0f, RoundScreen.arcLiftDp(240f, 0f), 0.001f)
        val near = RoundScreen.arcLiftDp(240f, 40f)
        val far = RoundScreen.arcLiftDp(240f, 80f)
        assertTrue("lift must grow with x ($near vs $far)", far > near)
        assertTrue("small offsets must barely lift ($near)", near < 8f)
    }

    @Test fun `orb-bar side buttons lift a sane amount on the Ultra face`() {
        // Bar layout: 56dp orb + 8 gap + 40 chip → centers at ±56dp; safe circle radius 240-10=230.
        val lift = RoundScreen.arcLiftDp(240f, 56f)
        assertTrue("expected ~7dp lift, got $lift", lift in 4f..12f)
    }

    @Test fun `extreme offsets cap at the safe radius instead of exploding`() {
        val capped = RoundScreen.arcLiftDp(240f, 235f)
        assertTrue(capped > 0f && capped <= 230f)
    }

    @Test fun `degenerate geometry is inert not negative`() {
        assertEquals(0f, RoundScreen.arcLiftDp(0f, 10f), 0.001f)
        assertEquals(0f, RoundScreen.arcLiftDp(5f, 10f), 0.001f) // safe radius ≤ 0
        assertTrue(RoundScreen.arcLiftDp(240f, -56f) > 0f) // mirrored side same lift
        assertEquals(0f, RoundScreen.screenRadiusDp(0f), 0.001f)
        assertEquals(240f, RoundScreen.screenRadiusDp(480f), 0.001f)
    }

    // ── BATCH-4 #2/#3 — the 10-o'clock tick line (karaoke + submitted-question anchor) ──
    @Test fun `the ten oclock tick sits a quarter down and scales with the face`() {
        // 10:00 is 60° counterclockwise from 12: y = cy − r·cos60° = h/2 − min(w,h)/4.
        assertEquals(120f, RoundScreen.tenOclockY(480f, 480f), 0.01f) // 25 % down on 480
        assertEquals(240f, RoundScreen.tenOclockY(960f, 960f), 0.01f) // same ratio, bigger face
        // Non-square: the SMALLER dimension sets the circle (universal across vendors).
        assertEquals(75f, RoundScreen.tenOclockY(400f, 300f), 0.01f)
        // The tick must live BELOW the clock inset (~30 dp) and well ABOVE the 8:20 rim line —
        // that band is the karaoke no-nudge corridor.
        val y = RoundScreen.tenOclockY(480f, 480f)
        assertTrue(y > 30f * 1.5f && y < 288f)
        assertEquals(0f, RoundScreen.tenOclockY(0f, 480f), 0.001f)
    }
}
