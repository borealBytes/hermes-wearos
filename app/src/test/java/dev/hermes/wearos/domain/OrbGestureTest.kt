package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ORB GESTURE classifier table. Originally the drag-up → keyboard ask; batch 3 grew that one
 * direction into the 8-lane DIAL (SPEC §12.1), so the orb still sees ONE raw pointer stream and
 * must split it: TAP = hands-free toggle, HOLD = stationary press → the dial blooms in place
 * (batch 6/7 — hold-to-talk retired; the VERDICT freezes, the aim vector never does),
 * DRAG_UP = the 12:00 lane (keyboard,
 * byte-identical contract), DRAG = any other direction, whose lane `OrbDial.laneAt` decides.
 *
 * All px+ms: at the emulator's density 2 the dp gates are 40dp→80px up and 30dp→60px drift;
 * longPress timeout pinned at 400ms here (Wear default; the Tracker takes it as a parameter).
 * Down sample lives at (200, 600); a move is (x, y, eventUptimeMillis).
 */
class OrbGestureTest {

    private val longPress = 400L
    private val minUp = 80f    // 40dp @2x
    private val maxDrift = 60f // 30dp @2x
    private val downX = 200f
    private val downY = 600f

    private data class Move(val x: Float, val y: Float, val t: Long)

    private fun verdict(
        moves: List<Move>,
        releaseT: Long,
        downT: Long = 1_000L,
        minDrag: Float = minUp,
    ): OrbGesture.Verdict {
        val tracker = OrbGesture.Tracker(downX, downY, downT, longPress, minUp, maxDrift, minDrag)
        moves.forEach { tracker.move(it.x, it.y, it.t) }
        return tracker.verdict(releaseT)
    }

    @Test fun `static quick release is a TAP`() {
        assertEquals(OrbGesture.Verdict.TAP, verdict(listOf(Move(200f, 598f, 1_080L)), releaseT = 1_090L))
    }

    @Test fun `jittery quick release stays a TAP`() {
        // Watch touches always drift a few px — tap must survive it (that was the MediaViewer bug),
        // and it does even against the dial's tighter production gate (51px below).
        assertEquals(OrbGesture.Verdict.TAP, verdict(listOf(Move(205f, 595f, 1_060L)), releaseT = 1_100L))
        assertEquals(
            OrbGesture.Verdict.TAP,
            verdict(listOf(Move(205f, 595f, 1_060L)), releaseT = 1_100L, minDrag = 51f),
        )
    }

    @Test fun `press held past the timeout with no movement is a HOLD`() {
        assertEquals(OrbGesture.Verdict.HOLD, verdict(emptyList(), releaseT = 1_700L))
        assertEquals(
            OrbGesture.Verdict.HOLD,
            verdict(listOf(Move(200f, 600f, 1_450L)), releaseT = 1_700L),
        )
    }

    @Test fun `upward slide past the gate is a DRAG_UP`() {
        // Rises 80px (exactly the 40dp gate) with 3px of drift at 150ms — long before timeout.
        assertEquals(OrbGesture.Verdict.DRAG_UP, verdict(listOf(Move(203f, 520f, 1_150L)), releaseT = 1_160L))
    }

    @Test fun `DRAG_UP locks the moment it crosses - staying put after does not become HOLD`() {
        assertEquals(
            OrbGesture.Verdict.DRAG_UP,
            verdict(listOf(Move(203f, 520f, 1_150L), Move(200f, 300f, 1_600L)), releaseT = 1_650L),
        )
    }

    @Test fun `HOLD locks first - an upward slide AFTER the timeout stays a HOLD`() {
        // 40px up at 300ms (not enough), then 120px up at 450ms — hold won the race at 400ms,
        // and the VERDICT never re-arms; the live vector (below) is what the bloomed dial aims with.
        assertEquals(
            OrbGesture.Verdict.HOLD,
            verdict(listOf(Move(200f, 560f, 1_300L), Move(200f, 480f, 1_450L)), releaseT = 1_700L),
        )
    }

    @Test fun `a slow drag past the dial gate after the timeout is still a HOLD, not a lane`() {
        // The dial flick is a FLICK. Cross the 51px gate at 450ms (after the 400ms hold lock) and
        // the hold stands — hold-then-slide blooms the ring and aims it, it is not a fresh flick.
        assertEquals(
            OrbGesture.Verdict.HOLD,
            verdict(listOf(Move(200f, 560f, 1_300L), Move(200f, 500f, 1_450L)), releaseT = 1_700L, minDrag = 51f),
        )
    }

    @Test fun `rise under the gate (30dp) is still a TAP`() {
        assertEquals(OrbGesture.Verdict.TAP, verdict(listOf(Move(200f, 530f, 1_150L)), releaseT = 1_160L))
    }

    @Test fun `steep diagonal past the dial gate is a DRAG, never a DRAG_UP`() {
        // Batch 3: drift past 30dp used to make this a TAP. Now ANY direction out of the disc is
        // the dial (this vector points at 1:30) — but it is still NOT the keyboard lane, which
        // keeps its own strict up-plus-tight-drift contract.
        assertEquals(OrbGesture.Verdict.DRAG, verdict(listOf(Move(270f, 500f, 1_150L)), releaseT = 1_150L + 10L))
    }

    @Test fun `drift exactly on the 30dp gate still counts as DRAG_UP`() {
        assertEquals(OrbGesture.Verdict.DRAG_UP, verdict(listOf(Move(260f, 500f, 1_150L)), releaseT = 1_160L))
    }

    @Test fun `downward drag is a lane now, not a TAP`() {
        // Owner batch 3: "if I drag it just straight down … pop up with an overlay of all the slash
        // commands". The 6:00 wedge owns it; OrbDialTest proves WHICH lane the vector means.
        assertEquals(OrbGesture.Verdict.DRAG, verdict(listOf(Move(200f, 700f, 1_150L)), releaseT = 1_160L))
    }

    @Test fun `crossing exactly on the timeout event is a HOLD (hold wins the tie)`() {
        assertEquals(OrbGesture.Verdict.HOLD, verdict(listOf(Move(200f, 500f, 1_400L)), releaseT = 1_420L))
    }

    @Test fun `vector rides the last move so the dial can aim while the finger travels`() {
        val tracker = OrbGesture.Tracker(downX, downY, 1_000L, longPress, minUp, maxDrift, 51f)
        tracker.move(270f, 500f, 1_150L)
        assertEquals(70f, tracker.dx, 0.01f)
        assertEquals(-100f, tracker.dy, 0.01f)
        tracker.move(140f, 500f, 1_250L) // steer left mid-drag: the vector follows, it does not latch
        assertEquals(-60f, tracker.dx, 0.01f)
    }

    @Test fun `HOLD locks the verdict but the vector stays live for the bloomed dial`() {
        // Regression (owner on real glass, batch 7): the hold branch of TalkOrb feeds THIS
        // tracker through feedDialUntilUp. If move() early-returned on holdFired, every
        // onDialMove after the bloom reported the arm point — the ring opened but could neither
        // be aimed nor spun ("it's not letting me select, it's not spinning").
        val tracker = OrbGesture.Tracker(downX, downY, 1_000L, longPress, minUp, maxDrift, 51f)
        tracker.move(200f, 600f, 1_450L) // jitter past the timeout: HOLD locks
        assertEquals(OrbGesture.Verdict.HOLD, tracker.verdict(1_700L))
        tracker.move(320f, 540f, 1_550L) // finger slides toward the 2:00 wedge post-bloom
        assertEquals(120f, tracker.dx, 0.01f)
        assertEquals(-60f, tracker.dy, 0.01f)
        // …and the decision stays frozen: a slow post-hold drag is never re-armed into a flick.
        assertEquals(OrbGesture.Verdict.HOLD, tracker.verdict(1_700L))
    }

    @Test fun `dp gate constants match the owner spec`() {
        assertEquals(40f, OrbGesture.MIN_UP_DP, 0.0001f)
        assertEquals(30f, OrbGesture.MAX_DRIFT_DP, 0.0001f)
    }
}
