package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LAYOUT D mockup contract (owner-approved /tmp/opencode/varD.png): the exact 480-face coords,
 * the mute satellite's arc band, the grounded-but-not-shaved orb, the non-square fallback and
 * the degenerate-size inertness.
 */
class BottomLayoutTest {

    private val g480 = BottomLayout.geometry(480f, 480f)

    @Test fun `talk orb lands on the approved 480-face coords`() {
        assertEquals(240f, g480.talk.cx, 0.01f)
        assertEquals(401f, g480.talk.cy, 0.01f)
        assertEquals(64f, g480.talk.r, 0.01f)
    }

    @Test fun `side buttons land on the approved 480-face coords`() {
        assertEquals(110f, g480.agent.cx, 0.01f)
        assertEquals(395f, g480.agent.cy, 0.01f)
        assertEquals(32f, g480.agent.r, 0.01f)
        assertEquals(370f, g480.sessions.cx, 0.01f)
        assertEquals(395f, g480.sessions.cy, 0.01f)
        assertEquals(32f, g480.sessions.r, 0.01f)
    }

    @Test fun `mute satellite lands on the approved 480-face coords`() {
        assertEquals(432f, g480.mute.cx, 0.01f)
        assertEquals(324f, g480.mute.cy, 0.01f)
        assertEquals(24f, g480.mute.r, 0.01f)
    }

    @Test fun `orb is grounded but not shaved - clears the bottom edge`() {
        val gap = 480f - (g480.talk.cy + g480.talk.r)
        assertTrue("orb bottom must sit 10..20px off the edge, got $gap", gap in 10f..20f)
    }

    @Test fun `mute rides the right arc inside the approved band`() {
        val d = BottomLayout.distFromCenter(480f, 480f, g480.mute)
        assertTrue("center distance must be 202..210 on the 480 face, got $d", d in 202f..210f)
    }

    @Test fun `transcript pad equals the cluster top edge clearance`() {
        // talk crown at 337/480 → the transcript needs the remaining 143 px of bottom padding.
        assertEquals(143f, BottomLayout.transcriptBottomPad(480f, 480f), 0.01f)
        // scales with the face
        assertEquals(286f, BottomLayout.transcriptBottomPad(960f, 960f), 0.01f)
    }

    // ── BATCH-4 #1/#6 — the circle-safe bottom line ("8:20 spot") ──
    @Test fun `round faces lift content for the rim before the orb crown ever binds`() {
        // Owner screenshot forensics: digits at x≈8 px resting on the crown line were shaved.
        // At the 8 dp gutter the rim rule (r = 0.98 · min/2) must out-rule the 143 px crown.
        val pad = BottomLayout.circleSafeBottomPad(
            viewportW = 480f, viewportH = 480f, round = true, contentLeftPx = 12f, orbTopPx = 337f,
        )
        assertTrue("rim must beat the crown (143), got $pad", pad > 143f)
        assertEquals(182.25f, pad, 0.5f) // rim ∩ x=12 on the 480 face → rest bottom ≈ 297.75
    }

    @Test fun `square faces keep the exact pre-batch crown rule (no rim fiction)`() {
        assertEquals(
            BottomLayout.transcriptBottomPad(480f, 480f),
            BottomLayout.circleSafeBottomPad(
                viewportW = 480f, viewportH = 480f, round = false,
                contentLeftPx = 12f, orbTopPx = BottomLayout.geometry(480f, 480f).talk.topY(),
            ),
            0.01f,
        )
    }

    @Test fun `the rim only binds where the text is wide — centered lines fall back to the crown`() {
        // Text starting near the center column clears the rim at ANY legal depth: the pad is the
        // orb rule again, so the helper never over-lifts narrow content on a round face.
        val pad = BottomLayout.circleSafeBottomPad(
            viewportW = 480f, viewportH = 480f, round = true, contentLeftPx = 120f, orbTopPx = 337f,
        )
        assertEquals(143f, pad, 0.5f)
    }

    @Test fun `lists without an orb clear the rim at the same line`() {
        // Item 6: a plain list passes orbTop = height → the rim alone decides.
        val pad = BottomLayout.circleSafeBottomPad(
            viewportW = 480f, viewportH = 480f, round = true, contentLeftPx = 12f,
            orbTopPx = 480f, marginPx = 12f,
        )
        assertEquals(194.25f, pad, 0.5f) // rim 297.75 + 12 px margin, measured from the bottom
    }

    @Test fun `the safe line scales with the face and survives degenerate sizes`() {
        val big = BottomLayout.circleSafeBottomPad(
            viewportW = 960f, viewportH = 960f, round = true, contentLeftPx = 24f, orbTopPx = 674f,
        )
        assertEquals(364.5f, big, 1f) // exact 2× of the 480-face answer
        assertEquals(0f, BottomLayout.circleSafeBottomPad(0f, 0f, true, 12f), 0.01f)
        // x beyond the rim span (absurd gutter): the safe line collapses to the center height.
        val edge = BottomLayout.circleSafeBottomPad(
            viewportW = 480f, viewportH = 480f, round = true, contentLeftPx = 0f, orbTopPx = 480f,
        )
        assertEquals(240f, edge, 0.5f)
    }

    @Test fun `geometry scales uniformly on bigger square faces`() {
        val g = BottomLayout.geometry(960f, 960f)
        assertEquals(480f, g.talk.cx, 0.01f)
        assertEquals(802f, g.talk.cy, 0.01f)
        assertEquals(128f, g.talk.r, 0.01f)
        assertEquals(220f, g.agent.cx, 0.01f)
        assertEquals(790f, g.agent.cy, 0.01f)
        assertEquals(864f, g.mute.cx, 0.01f)
        assertEquals(648f, g.mute.cy, 0.01f)
        assertEquals(48f, g.mute.r, 0.01f)
    }

    @Test fun `non-square viewports scale off the min dimension and stay inside`() {
        val w = 600f; val h = 400f
        val g = BottomLayout.geometry(w, h)
        val s = h / BottomLayout.REF // 0.8333
        assertEquals(w / 2f, g.talk.cx, 0.01f)
        assertEquals(h / 2f + 161f * s, g.talk.cy, 0.01f)
        assertEquals(64f * s, g.talk.r, 0.01f)
        assertEquals(w / 2f - 130f * s, g.agent.cx, 0.01f)
        assertTrue(g.mute.cx + g.mute.r <= w + 0.01f)
        assertTrue(g.talk.cy + g.talk.r <= h + 0.01f)
        assertTrue(BottomLayout.fitsInViewport(w, h))
    }

    @Test fun `sliver viewports clamp instead of overflowing`() {
        // 160 px wide: the mute center would fall OUTSIDE the rect — clamp pulls it in.
        val g = BottomLayout.geometry(160f, 480f)
        assertTrue(BottomLayout.fitsInViewport(160f, 480f, g))
        assertTrue("clamped mute must keep a non-zero radius", g.mute.r > 0f)
        val tall = BottomLayout.geometry(1000f, 300f)
        assertTrue(BottomLayout.fitsInViewport(1000f, 300f, tall))
        // talk orb never crosses the bottom edge, even unclamped-by-x rects
        assertTrue(tall.talk.cy + tall.talk.r <= 300f + 0.01f)
    }

    @Test fun `degenerate sizes are inert not NaN`() {
        val g = BottomLayout.geometry(0f, 0f)
        assertTrue(listOf(g.talk, g.agent, g.sessions, g.mute).all { it.cx == 0f && it.cy == 0f && it.r == 0f })
        assertEquals(0f, BottomLayout.transcriptBottomPad(-5f, 300f), 0.01f)
        val tiny = BottomLayout.geometry(3f, 3f)
        assertTrue(BottomLayout.fitsInViewport(3f, 3f, tiny))
    }

    @Test fun `mockup clearances keep side buttons off the orb and each other`() {
        val orbAgent = BottomLayout.clearance(g480.talk, g480.agent)
        val orbSess = BottomLayout.clearance(g480.talk, g480.sessions)
        val agentMute = BottomLayout.clearance(g480.agent, g480.mute)
        assertTrue("orb/agent overlap $orbAgent", orbAgent > 0f)
        assertTrue("orb/sessions overlap $orbSess", orbSess > 0f)
        assertTrue("agent/mute overlap $agentMute", agentMute > 0f)
    }

    @Test fun `label baseline sits 8-10 ref px above the circle top`() {
        val bottom = BottomLayout.labelBottomY(g480.agent, 480f, 480f)
        val gap = (g480.agent.cy - g480.agent.r) - bottom
        assertTrue("label gap must be 8..10 px on the 480 face, got $gap", gap in 8f..10f)
    }
}
