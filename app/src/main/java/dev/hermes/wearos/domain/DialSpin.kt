package dev.hermes.wearos.domain

import kotlin.math.abs

/**
 * SPIN mode for the dial ring (owner batch 5): *"take the orb, slide it up, then you can go right
 * or left, and those little icons turn around you — you let go where you want to be. But keep
 * the directional swipe too."*
 *
 * So every dial gesture has two halves sharing one stream:
 *
 *  - **AIM** (default): the highlight follows the finger's angle — free re-aiming, sweep past
 *    any wedge and back, the release direction is the lane ([OrbGesture]'s live vector feeds this).
 *  - **SPIN**: while the finger holds the TOP arc, lateral travel accumulates; every
 *    [OrbDial.SPIN_STEP_REF]-px detent walks the highlight one slot toward the sweep and the
 *    whole ring ROTATES ([phaseDeg]) so the chosen icon arrives under the finger. One detent
 *    engages the mode (aim-drag's incidental lean is below a step and never does); from then on
 *    the spin owns the gesture — center is no longer cancel, it is the owner's "come back and let
 *    go" commit spot, and releasing anywhere commits what is lit.
 *
 * Spin arms only from the top arc ([OrbDial.SPIN_UP_ARC_DEG]) because that is the posture the
 * owner described and because the LOW wedges — the crowded, hard-to-aim ones spin exists for —
 * become reachable by rotation without the finger ever leaving the comfort zone. Pure logic:
 * feed [move] with the same dial-center-relative px the ring draws with; nothing here knows
 * Compose.
 */
class DialSpin(private val scale: Float) {

    /** Slot under the highlight, 0..7, or -1 (finger home with no spin = nothing aimed). */
    var activeSlot = -1
        private set

    /** True once a lateral detent took the gesture over. */
    var spinning = false
        private set

    /** Ring rotation in degrees: slot i draws at `i * 45 + phaseDeg` (0 = icons at rest). */
    var phaseDeg = 0f
        private set

    /** Something the caller should give a haptic tick for: wedge hop or detent crossing. */
    var changed = false
        private set

    private var accumPx = 0f
    private var lastDx = Float.NaN
    private var slotAtSpinStart = 0
    private var lastSteps = 0

    fun reset() {
        activeSlot = -1; spinning = false; phaseDeg = 0f; changed = false
        accumPx = 0f; lastDx = Float.NaN; slotAtSpinStart = 0; lastSteps = 0
    }

    /**
     * Feed one live drag vector (px relative to the orb center — same coordinates
     * [OrbDial.laneAt] uses). Call once per pointer move between arm and up.
     */
    fun move(dxPx: Float, dyPx: Float) {
        changed = false
        val home = OrbDial.isCancel(dxPx, dyPx, scale)
        val ang = OrbDial.angleOf(dxPx, dyPx)
        if (lastDx.isNaN()) lastDx = dxPx
        val stepPx = OrbDial.SPIN_STEP_REF * scale
        if (stepPx <= 0f) return

        if (!spinning) {
            if (home) {
                if (activeSlot != -1) changed = true
                activeSlot = -1; phaseDeg = 0f; accumPx = 0f
                lastDx = dxPx
                return
            }
            val aim = OrbDial.slotAt(ang)
            if (aim != activeSlot) changed = true
            activeSlot = aim
            // Accumulate lateral motion ONLY while the finger DWELLS in the top arc — arc AND
            // a real radius (#23, 2026-11-11 gear misfires: every one of the owner's ordinary
            // wedge drags curved its first samples through the ±65° zone, the transit's lateral
            // velocity crossed the step, and the ring engaged ±1 detent under a finger that was
            // just dragging — gear committed model, sessions committed agents). The owner's
            // spin flow is "slide UP, then go left or right": a deep-in-the-arc finger.
            // Passing THROUGH the arc on the way to some other wedge (small radius, transient)
            // is not owed any accumulation.
            val r = kotlin.math.sqrt(dxPx * dxPx + dyPx * dyPx)
            val dwelling = OrbDial.wedgeDist(ang, 0f) <= SPIN_ARM_ARC_DEG &&
                r >= SPIN_ARM_R_REF * scale
            if (dwelling) accumPx += dxPx - lastDx
            else accumPx = 0f
            lastDx = dxPx
            if (abs(accumPx) >= stepPx) {
                spinning = true
                slotAtSpinStart = aim
                lastSteps = truncSteps(accumPx, stepPx)
                activeSlot = wrap(slotAtSpinStart + lastSteps)
                changed = true
                // The ring starts turning the INSTANT the mode engages, not on the next event.
                phaseDeg = OrbDial.normDeg180(ang - activeSlot * OrbDial.SLOT_DEG)
            }
            return
        }

        // SPINNING: lateral travel accumulates — but ONLY outside the commit circle. The owner's
        // flow is "sweep right, come back to the center, let go": if the walk home counted as
        // lateral motion, returning would UNWIND the selection it just made. Inside the circle
        // steps freeze (approaching the commit spot is not an un-step); the lit icon waits there
        // for the release, and fine-stepping is available again the moment the finger works the
        // outer ring.
        val dist = kotlin.math.sqrt(dxPx * dxPx + dyPx * dyPx)
        if (dist >= commitFreezeRef * scale) {
            accumPx += dxPx - lastDx
            val steps = truncSteps(accumPx, stepPx)
            if (steps != lastSteps) {
                lastSteps = steps
                activeSlot = wrap(slotAtSpinStart + steps)
                changed = true
            }
            // Rotate the ring so the lit slot sits under the finger.
            phaseDeg = OrbDial.normDeg180(ang - activeSlot * OrbDial.SLOT_DEG)
        }
        lastDx = dxPx
    }

    /**
     * The lane the release commits — null means cancel (finger went home before ever spinning).
     * While spinning, the lit slot wins regardless of release position: "come back to the center
     * and let go" and "flick off sideways" both mean *the one I'm looking at*.
     */
    fun commit(): Int? = if (spinning) activeSlot else activeSlot.takeIf { it >= 0 }

    /** Lateral-travel freeze radius while spinning (ref px): just outside the cancel gate —
     *  inside it the finger is walking to the commit spot, not un-step. */
    private val commitFreezeRef = 70f

    /** SPIN ENTRY arc (deg) — the TOP WEDGE, not the wide ±65 fine-step arc. An ordinary wedge
     *  drag curves through 40–65° on its way east/west; only a finger that actually slid UP
     *  (deep in 12:00's own wedge) owes the ring lateral accumulation (#23 gear→model proof). */
    private val SPIN_ARM_ARC_DEG = 35f

    /** SPIN ENTRY radius (ref px) — past the activation gate and well under the icon ring:
     *  "up and DWELL", the owner's batch-5 flow. Transit samples sit inside this. */
    private val SPIN_ARM_R_REF = 88f

    private fun truncSteps(accum: Float, stepPx: Float): Int = (accum / stepPx).toInt()

    private fun wrap(slot: Int): Int = ((slot % OrbDial.SLOT_COUNT) + OrbDial.SLOT_COUNT) % OrbDial.SLOT_COUNT
}
