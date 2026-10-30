package dev.hermes.wearos.domain

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Round-viewport discipline (owner ask #5) as PURE math. Wear Compose 1.4.1 has no
 * `androidx.wear.compose.foundation.Scaffold`/`ROUND_SCAFFOLD_INSETS` yet (verified against the
 * 1.4.1 artifacts), so the app ships the equivalent itself: these insets feed
 * `Modifier.windowInsetsPadding` / ScalingLazyColumn `contentPadding`, and [arcLiftDp] places the
 * orb-bar's side buttons on the safe-circle arc instead of a square bottom line.
 */
object RoundScreen {
    /** Status/clock zone (TimeText) the content must start below. */
    const val CLOCK_INSET_DP = 30f

    /** Horizontal falloff toward the circular edge (owner: "hPadding = 20dp + edge falloff"). */
    const val EDGE_H_DP = 20f

    /** Chin clip / bottom safe area — buttons and last lines must clear this. */
    const val CHIN_INSET_DP = 16f

    /** Extra margin inside the physical circle when computing the button arc. */
    const val ARC_MARGIN_DP = 10f

    /** Inset box for a screen shape; square screens keep a modest guttter instead. */
    data class Insets(val topDp: Float, val bottomDp: Float, val horizontalDp: Float)

    fun insetsFor(round: Boolean): Insets =
        if (round) Insets(CLOCK_INSET_DP, CHIN_INSET_DP, EDGE_H_DP) else Insets(0f, 8f, 8f)

    /**
     * How far a button at horizontal [offsetDp] from screen centre must rise so the whole orb bar
     * follows the safe circle (Now-in-Edge arc placement) rather than a flat bottom line.
     * [radiusDp] = half the smaller screen dimension; safe radius = radius − [marginDp].
     * 0 when the offset sits on the circle's lowest point; caps at the safe radius for extreme x.
     */
    fun arcLiftDp(radiusDp: Float, offsetDp: Float, marginDp: Float = ARC_MARGIN_DP): Float {
        if (radiusDp <= 0f) return 0f
        val safeR = radiusDp - abs(marginDp)
        if (safeR <= 0f) return 0f
        val x = abs(offsetDp)
        if (x >= safeR) return safeR
        return safeR - sqrt(safeR * safeR - x * x)
    }

    /** Half of the smaller screen dimension, in dp (the round screen's radius). */
    fun screenRadiusDp(minDimensionDp: Float): Float = if (minDimensionDp <= 0f) 0f else minDimensionDp / 2f

    /**
     * Y of the **10-o'clock tick** in viewport pixels (owner batch-4 #2/#3: *"the beginning of
     * the speech should start at the 10 o'clock spot… when you submit a request, it's okay for
     * your question to start at that 10 o'clock spot"*). The tick sits 60° counterclockwise from
     * 12 on the face circle ⇒ y = center − r·cos 60° = center − r/2 = h/2 − min(w,h)/4 — about
     * 25 % down on square faces, and UNIVERSAL (the ratio, not a pixel constant, so square and
     * every round vendor size read the same line). Up here the rim is wide open: text starting
     * at the left gutter is nowhere near the circle cut, which is why this is the calm anchor
     * for a submitted question and for the line currently being spoken.
     */
    fun tenOclockY(viewportW: Float, viewportH: Float): Float =
        if (viewportW <= 0f || viewportH <= 0f) 0f
        else viewportH / 2f - min(viewportW, viewportH) / 4f
}
