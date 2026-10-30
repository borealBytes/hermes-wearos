package dev.hermes.wearos.domain

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * EDGE-CIRCLE SCROLLING (batch-4 #7, owner: *"finger circling the screen edge CW/CCW scrolls"*)
 * as PURE math. Wear's own `edgeRotate` landed after compose-foundation 1.4.1 (verified against
 * the shipped jar: the rotary package has behaviors, no touch-edge API), so the app ships the
 * gesture itself: a finger that rides the OUTER RING of the face and travels around the center
 * scrolls the list — clockwise travels down, counter-clockwise up — on round AND square screens
 * (on a square the ring is the inscribed circle, so corner starts are simply not edge gestures).
 *
 * Why a dead-band: the raw angle jitters ±3° while the finger slides, and a 1:1 mapping makes
 * the list crawl when the finger pauses mid-arc. [angleStepDeg] is the arc that buys one scroll
 * step; [maxStepPerTickPx] caps one step so a fast whoop around the face cannot skip a screen.
 * Pixels-per-radian scales with the face (a full circle ≈ 2π rad ≈ 2× the ring circumference ×
 * [SCROLL_GAIN] / 2 … see [scrollPxFor]).
 */
object EdgeScroll {

    /** Fraction of the face radius where the edge ring begins (inside it, touches are normal). */
    const val RING_INNER_RATIO = 0.80f

    /** Degrees of travel that buy one scroll step. */
    const val ANGLE_STEP_DEG = 7f

    /** Angular velocity (deg per pointer event) that declares CIRCLE, not drag. A straight drag
     *  anywhere on the face — even sliding along the curved rim — drifts under ~2°/event at
     *  normal speeds while a circle runs 8–15°/event, so the two never trade places. */
    const val LOCK_DELTA_DEG = 2.5f

    /** One step moves at most this many px (the list gets a further cap from its own layout). */
    const val MAX_STEP_PX = 60f

    /** Ring circumference × this = pixels a FULL 360° circle scrolls. Tuned on the 480 face:
     *  one full circle ≈ a little over one screenful — reading scrolls, not teleporting. */
    const val SCROLL_GAIN = 0.65f

    /** Is (x, y) inside the edge ring of a w×h face? (r measured from the face CENTER). */
    fun inRing(x: Float, y: Float, w: Float, h: Float): Boolean {
        if (w <= 0f || h <= 0f) return false
        val r = min(w, h) / 2f
        val d = hypot(x - w / 2f, y - h / 2f)
        return d >= r * RING_INNER_RATIO
    }

    /** Signed angle (degrees, -180..180) of (x, y) around the face center. Screen y is down, so
     *  TRAVELLING CLOCKWISE ON THE GLASS (top → right → bottom) INCREASES this angle — verified
     *  on-device with synthesized rim sweeps (a mislabelled test arc once tempted an inversion
     *  here: top→right really is atan2 −90 → 0). */
    fun angleDeg(x: Float, y: Float, w: Float, h: Float): Float =
        Math.toDegrees(atan2((y - h / 2f).toDouble(), (x - w / 2f).toDouble())).toFloat()

    /**
     * Pixels to scroll for the travel from [prevDeg] to [newDeg]: accumulated whole
     * [ANGLE_STEP_DEG] ticks (CW positive — the finger's direction of travel is preserved across
     * the 180° seam via [angleDeltaDeg]), capped to [MAX_STEP_PX] per call. 0 when no full tick
     * has been crossed (the dead-band: jitter and pauses cost nothing).
     */
    fun scrollPxFor(
        prevDeg: Float,
        newDeg: Float,
        remainderDeg: Float,
        w: Float,
        h: Float,
    ): EdgeStep {
        val d = angleDeltaDeg(prevDeg, newDeg)
        val total = remainderDeg + d
        val ticks = (total / ANGLE_STEP_DEG).toInt()   // truncation keeps the remainder
        val rest = total - ticks * ANGLE_STEP_DEG
        val ringCircumference = Math.PI.toFloat() * min(w, h)
        val pxPerTick = ringCircumference * SCROLL_GAIN / (360f / ANGLE_STEP_DEG)
        val px = (ticks * pxPerTick).coerceIn(-MAX_STEP_PX, MAX_STEP_PX)
        return EdgeStep(px, rest)
    }

    /** A step's outcome: scroll by [px], and carry the leftover [remainderDeg] to the next tick. */
    data class EdgeStep(val px: Float, val remainderDeg: Float)

    /** Shortest signed angular path prev→new, preserving sign (CW +, CCW −), across the seam. */
    fun angleDeltaDeg(prevDeg: Float, newDeg: Float): Float {
        var d = newDeg - prevDeg
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }
}
