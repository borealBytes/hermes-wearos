package dev.hermes.wearos.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * LAYOUT D — the edge-hugging bottom orb cluster (owner-approved mockup `/tmp/opencode/varD.png`)
 * as PURE math, unit-tested. The reference frame is the 480×480 watch face (circle center
 * (240,240), r≈237). Signed offsets below are measured from that face CENTER; at runtime the
 * caller feeds the REAL viewport in pixels and gets centers+radii back in pixels — no hardcoded
 * px in the UI (the composition converts px → dp via LocalDensity).
 *
 * Approved geometry (480-ref):
 *  - talk orb       center (240,401) r=64  → 15px off the bottom edge ("grounded but not shaved")
 *  - agent button   center (110,395) r=32
 *  - sessions       center (370,395) r=32
 *  - MUTE satellite center (432,324) r=24  → rides the right arc ≈209.6 from face center, grazing
 *    the round edge (spec band 202–210).
 *
 * Non-square fallback: scale off the MIN dimension, centers on the viewport center, centers then
 * clamped inside the rect and radii clamped so a circle can never exceed the smaller of the two
 * proportions (it always fits the viewport). Degenerate sizes return all-zero spots.
 */
object BottomLayout {
    /** The approved mockup frame every reference constant is stated against. */
    const val REF = 480f

    // 480-ref: (dx, dy) from face center + radius. (240,401) → (0,+161) r 64 etc.
    const val ORB_DX = 0f
    const val ORB_DY = 161f
    const val ORB_R = 64f
    const val SIDE_DX = 130f
    const val SIDE_DY = 155f
    const val SIDE_R = 32f
    const val MUTE_DX = 192f
    const val MUTE_DY = 84f
    const val MUTE_R = 24f

    /** Label baseline sits this many ref-px ABOVE the button circle top (spec: 8–10px). */
    const val LABEL_GAP_REF = 9f

    /** One placed circle: center (cx,cy) + radius, in VIEWPORT PIXELS. */
    data class Spot(val cx: Float, val cy: Float, val r: Float) {
        /** Top edge of the circle — the button/label anchor lines derive from this. */
        fun topY(): Float = cy - r
    }

    data class Cluster(
        val talk: Spot,
        val agent: Spot,
        val sessions: Spot,
        val mute: Spot,
    )

    /** Uniform scale from the 480 reference onto [viewportW]×[viewportH] (min-dimension rule). */
    fun scaleFor(viewportW: Float, viewportH: Float): Float =
        if (viewportW <= 0f || viewportH <= 0f) 0f else min(viewportW, viewportH) / REF

    /**
     * The four approved centers + radii mapped onto the real viewport. Non-square faces keep the
     * proportions via the min dimension; centers are clamped into the rect and every radius is
     * clamped to stay inside it (on a 480 face the clamp never triggers — the mockup is honored
     * exactly).
     */
    fun geometry(viewportW: Float, viewportH: Float): Cluster {
        val s = scaleFor(viewportW, viewportH)
        fun spot(dx: Float, dy: Float, rRef: Float): Spot {
            if (s <= 0f) return Spot(0f, 0f, 0f)
            val x = (viewportW / 2f + dx * s).coerceIn(0f, viewportW)
            val y = (viewportH / 2f + dy * s).coerceIn(0f, viewportH)
            val fits = min(min(x, viewportW - x), min(y, viewportH - y)).coerceAtLeast(0f)
            return Spot(x, y, min(rRef * s, fits))
        }
        return Cluster(
            talk = spot(ORB_DX, ORB_DY, ORB_R),
            agent = spot(-SIDE_DX, SIDE_DY, SIDE_R),
            sessions = spot(SIDE_DX, SIDE_DY, SIDE_R),
            mute = spot(MUTE_DX, MUTE_DY, MUTE_R),
        )
    }

    /** Distance of a cluster center from the face center (the "riding the arc" check). */
    fun distFromCenter(viewportW: Float, viewportH: Float, spot: Spot): Float {
        val dx = spot.cx - viewportW / 2f
        val dy = spot.cy - viewportH / 2f
        return sqrt(dx * dx + dy * dy)
    }

    /** Y of a label's bottom line: [LABEL_GAP_REF] ref-px above the circle top (pixels). */
    fun labelBottomY(spot: Spot, viewportW: Float, viewportH: Float): Float =
        spot.topY() - LABEL_GAP_REF * scaleFor(viewportW, viewportH)

    /**
     * Transcript contentPadding BOTTOM (px) so the last message can scroll clear of the cluster:
     * viewport height minus the cluster's top edge (the talk-orb crown, 337/480 of the height on
     * the square reference). Never negative on extreme rects.
     */
    fun transcriptBottomPad(viewportW: Float, viewportH: Float): Float =
        if (scaleFor(viewportW, viewportH) <= 0f) 0f
        else (viewportH - geometry(viewportW, viewportH).talk.topY()).coerceAtLeast(0f)

    /** Physical-round allowance: the glass rim is a hair inside the mathematical circle. */
    const val RIM_INSET_RATIO = 0.98f

    /**
     * THE CIRCLE-SAFE BOTTOM LINE (batch-4 items 1 + 6, owner: *"the bottom left of the text gets
     * cut off from the circle screen face… have the corner the text comes from start at the line
     * where the top of the orb is… make this universal to work on square and circle screens"*).
     *
     * Returns the contentPadding-bottom in px — the band at the bottom of the viewport that
     * scrolling content must never enter — as the LARGER of two rules:
     *
     *  • **orb crown** (via [orbTopPx]): content rests above the cluster on ANY shape. Pass
     *    [orbTopPx] = viewport height for surfaces without an orb (every plain list, item 6).
     *  • **rim** (round faces only): a text row's bottom-LEFT corner is the first pixel the
     *    bezel shaves, so content may only descend to the lowest y that keeps (contentLeftPx, y)
     *    inside the physical circle: y ≤ cy + √(r² − (x−cx)²), r = min(w,h)/2 · [RIM_INSET_RATIO].
     *    On the 480-ref face with the text gutter at 8 dp this lands ≈ 288/480 — ABOVE the orb
     *    crown at 337 — which is the owner's "8:20 spot" (rim ∩ just-above-orb), measured from
     *    his screenshot: digits at x≈8 px resting at y≈314 were shaved by ~26 px.
     *
     * Square faces get the orb rule untouched (no rim constraint on a rect), so the same call
     * serves Pixel/OnePlus square watches. Degenerate viewports return 0 (nothing to clear).
     */
    fun circleSafeBottomPad(
        viewportW: Float,
        viewportH: Float,
        round: Boolean,
        contentLeftPx: Float,
        orbTopPx: Float = viewportH,
        marginPx: Float = 0f,
    ): Float {
        if (scaleFor(viewportW, viewportH) <= 0f) return 0f
        val orbPad = (viewportH - (orbTopPx - marginPx)).coerceAtLeast(0f)
        if (!round) return orbPad
        val cx = viewportW / 2f
        val cy = viewportH / 2f
        val r = min(viewportW, viewportH) / 2f * RIM_INSET_RATIO
        val dx = abs(contentLeftPx - cx)
        // x outside the rim's span (tiny face, huge gutter): the safe line is the center height.
        val rimLowestY = if (dx >= r) cy else cy + sqrt(r * r - dx * dx)
        val rimPad = (viewportH - (rimLowestY - marginPx)).coerceAtLeast(0f)
        return max(orbPad, rimPad)
    }

    /** Ref-px gap between two cluster circles (negative = overlap); mockup sanity checks use it. */
    fun clearance(a: Spot, b: Spot): Float {
        val dx = a.cx - b.cx
        val dy = a.cy - b.cy
        return sqrt(dx * dx + dy * dy) - a.r - b.r
    }

    /** True when all four spots sit inside the viewport (post-clamp invariant). */
    fun fitsInViewport(w: Float, h: Float, c: Cluster = geometry(w, h)): Boolean =
        listOf(c.talk, c.agent, c.sessions, c.mute).all {
            it.r >= 0f && it.cx - it.r >= -0.01f && it.cx + it.r <= w + 0.01f &&
                it.cy - it.r >= -0.01f && it.cy + it.r <= h + 0.01f
        }
}
