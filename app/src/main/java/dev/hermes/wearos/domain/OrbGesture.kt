package dev.hermes.wearos.domain

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ORB GESTURE classification. The orb's gesture lane must tell four intents apart from one raw
 * pointer stream (batch 3 grew the third one into the whole DIAL — SPEC §12.1):
 *
 *  - DRAG_UP: the pointer rose ≥ [MIN_UP_DP] with lateral drift under [MAX_DRIFT_DP] AND the
 *    crossing happened BEFORE the long-press timeout — mic never starts, the 12:00 lane (keyboard
 *    editor) fires instead. Kept byte-identical to the shipped drag-up contract.
 *  - DRAG (new): any OTHER direction past the dial's activation gate before the hold locks. Which
 *    lane it means is `OrbDial.laneAt(vector)` — the dial decides by sector at release, so this
 *    classifier only answers "was this a drag at all".
 *  - HOLD: the long-press timeout wins first. Once hold is locked the VERDICT is final (a slide
 *    that crosses the gate later is not a flick; the classifier never re-arms), but the vector
 *    stays live: since batch 6 the hold BLOOMS THE DIAL IN PLACE and aim/spin ride the stream.
 *  - TAP: released before the timeout without crossing any gate — incl. small upward slides
 *    (<40dp) and the tap-with-slop jitter a real watch touch always has.
 *
 * The TalkOrb detector drives this Tracker per pointer event (drag gate) and fires the hold on
 * its own timeout; [Tracker.verdict] classifies a COMPLETED gesture from the same rules, which
 * is what the table-driven unit tests exercise. The dial's own gate is a VIEWPORT-scaled number
 * (`OrbDial.ACTIVATE_REF * scale`) because the ring is drawn in the same space — so the Tracker
 * takes it as a parameter rather than a dp constant, and the two can never drift apart.
 * Pure: px + ms in, verdict out — no android.
 */
object OrbGesture {
    /** Upward travel (dp) from the DOWN point that arms the 12:00 / keyboard gesture. */
    const val MIN_UP_DP = 40f

    /** Max horizontal deviation (dp) from the down x for the drag to count as "vertical". */
    const val MAX_DRIFT_DP = 30f

    enum class Verdict { TAP, HOLD, DRAG_UP, DRAG }

    /** Incremental classifier fed with (x, y, eventUptimeMillis) moves after the DOWN. */
    class Tracker(
        private val downX: Float,
        private val downY: Float,
        private val downT: Long,
        private val longPressMs: Long,
        private val minUpPx: Float,
        private val maxDriftPx: Float,
        /** Dial gate (viewport px). Defaults to the old up-gate so pre-dial callers are unchanged. */
        private val minDragPx: Float = minUpPx,
    ) {
        /** True from the moment an up-slide crossed the gate before the hold locked. */
        var dragUpCrossed = false
            private set

        /** True once ANY direction crossed the dial gate before the hold locked (SPEC §12.1). */
        var dragArmed = false
            private set

        /** True once the long-press timeout passed without a drag crossing (hold wins). */
        var holdFired = false
            private set

        /**
         * Live drag vector — what `OrbDial.laneAt` aims with. Updated on EVERY move until the
         * pointer lifts: the verdict freezes when the hold wins the race, the vector never does
         * (the hold blooms the dial in place and aim/spin must keep flowing — batch 7).
         */
        var dx = 0f
            private set
        var dy = 0f
            private set

        /**
         * Feed one pointer event (post-DOWN moves; DOWN itself may be replayed — harmless).
         *
         * THE RE-AIM FIX (owner batch 5: "if I go up… I should be able to move my finger over to
         * one of the others — it only allows the direction you started in"): the hold timeout may
         * only claim a gesture that has NOT armed the dial. Once armed, the dial owns the stream
         * until the finger lifts — time passing must never freeze the vector, or the lane locks to
         * whatever direction the finger happened to cross the gate at (~0.4 s in) and a mid-drag
         * change of mind becomes impossible.
         */
        fun move(x: Float, y: Float, tMs: Long) {
            if (holdFired) {
                // HOLD locks the VERDICT, never the vector (owner on real glass, batch 7: the
                // hold bloomed the dial but "it's not spinning" — a frozen dx/dy meant every
                // move after the timeout still reported the arm point). A stationary hold opens
                // the ring IN PLACE and the finger then aims/spins from there, so the stream
                // stays live until the pointer lifts; `verdict` stays HOLD either way.
                dx = x - downX
                dy = y - downY
                return
            }
            dx = x - downX
            dy = y - downY
            if (dragArmed) return // armed = live vector until UP, no timers apply
            if (tMs - downT >= longPressMs) {
                holdFired = true
                return
            }
            if (dx * dx + dy * dy >= minDragPx * minDragPx) dragArmed = true
            if (downY - y >= minUpPx && abs(x - downX) <= maxDriftPx) dragUpCrossed = true
        }

        /**
         * Classification once the pointer lifted at [releaseT]. A DRAG stays a DRAG for any
         * direction; the caller reads [dx]/[dy] for the lane (angle at RELEASE decides, which is
         * what makes re-aiming mean anything). DRAG_UP is kept honest: only while the finger is
         * still in the up-corridor at release.
         */
        fun verdict(releaseT: Long): Verdict = when {
            // dx/dy are DELTAS from DOWN — the corridor is "rose ≥ minUp with drift ≤ maxDrift".
            dragArmed && -dy >= minUpPx && abs(dx) <= maxDriftPx -> Verdict.DRAG_UP
            dragArmed -> Verdict.DRAG
            holdFired || releaseT - downT >= longPressMs -> Verdict.HOLD
            else -> Verdict.TAP
        }
    }
}
