package dev.hermes.wearos.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PINCH FONT-SCALE math (owner ask: two-finger pinch in the message area scales TEXT/MARKDOWN
 * type, NOT a visual zoom — image/mermaid cards keep their footprint). Pure clamp+quantize on a
 * 0.05 grid between 0.55× and 1.6×; the gesture feeds raw `calculateZoom()` deltas multiplicatively.
 * Values persist through AppSettings; NaN/garbage always land on a valid step. No compose imports.
 *
 * THE FLOOR (owner 2026-11-02: "I can read very small text — let me zoom out more"): the Watch
 * Ultra is 480×480 @ ~340 dpi → 1 sp ≈ 1 physical px. Transcript body type is ~14 sp, so 0.55×
 * lands at ≈7.7 px — the same bottom the pinch IMAGE viewer already uses (MediaZoom), and the
 * point where letter x-heights (~4 px) stop being legible at wrist distance. Below this it is
 * texture, not text.
 */
object FontScale {
    const val MIN = 0.55f
    const val MAX = 1.6f
    const val STEP = 0.05f
    const val DEFAULT = 1f

    /** Steps per unit (1/STEP) — integer grid keeps quantization drift-free. */
    private const val PER_UNIT = 20f

    fun clamp(v: Float): Float = if (v.isNaN()) DEFAULT else v.coerceIn(MIN, MAX)

    /** Snap to the 0.05 grid, then clamp (a snap past the ends lands exactly on MIN/MAX). */
    fun quantize(v: Float): Float {
        if (v.isNaN() || v.isInfinite()) return DEFAULT
        return clamp(v).let { (it * PER_UNIT).roundToInt() / PER_UNIT }
    }

    /** Pinch apply: current × gesture zoom factor → snapped, clamped scale. */
    fun apply(current: Float, zoomFactor: Float): Float =
        if (zoomFactor.isNaN() || zoomFactor <= 0f) quantize(current)
        else quantize(clamp(current) * zoomFactor)

    /** Double-tap on empty transcript resets to 1.0 (the hidden reset gesture). */
    fun isDefault(v: Float): Boolean = abs(quantize(clamp(v)) - DEFAULT) < STEP / 2f
}
