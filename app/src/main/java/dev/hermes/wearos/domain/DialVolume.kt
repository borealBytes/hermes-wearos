package dev.hermes.wearos.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * THE MUTE-LANE DOUBLE SLIDER (owner 2026-10-29: *"pause on mute for maybe two seconds, and then
 * from there, it starts letting you swipe back and forth to change the volume of the replies …
 * think of it as a double slider"*).
 *
 * The dial already delivers a live lateral stream while the finger is armed and parked on a lane
 * (that is what SPIN consumes). When the armed lane is MUTE and the finger DWELLS
 * ([DWELL_MS]) without releasing or spinning, the gesture's second half opens: lateral travel
 * maps to the app's REPLY VOLUME (a TTS utterance-volume scale — never the system stream, this
 * is a "within the Hermes app only" control). One full sweep across [FULL_SWEEP_REF] ref-px
 * covers the whole 0→1 range; the value quantizes to the 5 % grid so every step earns one
 * haptic tick and ±5 % is as fine as a wrist gets.
 *
 * Pure: px in, level out; the dwell timer and haptics live in the caller.
 */
class DialVolume(private val scale: Float, startLevel: Float) {

    /** Current reply volume, 0..1, quantized to 5 %. */
    var level = quantize(startLevel)
        private set

    /** True for the move() that stepped to a new level (haptic cue for the caller). */
    var changed = false
        private set

    private val anchor = level
    private var firstDx = Float.NaN

    /** Feed the live drag x-offset (viewport px, dial-relative like everything else here). */
    fun move(dxPx: Float) {
        changed = false
        if (firstDx.isNaN()) { firstDx = dxPx; return }
        val full = FULL_SWEEP_REF * if (scale > 0f) scale else 1f
        val target = quantize(anchor + (dxPx - firstDx) / full)
        if (abs(target - level) >= 0.001f) { level = target; changed = true }
    }

    companion object {
        /** Dwell on the mute lane before the double slider's second half arms. */
        const val DWELL_MS = 2_000L

        /** Lateral travel (ref px, 480-face space) for the full 0→1 volume sweep. */
        const val FULL_SWEEP_REF = 240f

        /** The 5 % grid — one haptic per step, no drift between glance and ear-test. */
        fun quantize(v: Float): Float =
            (if (v.isNaN()) 20f else v.coerceIn(0f, 1f) * 20f).roundToInt() / 20f

        /** The orb-face readout the overlay shows while volume mode is live. */
        fun pct(level: Float): Int = (quantize(level) * 100f).roundToInt()
    }
}
