package dev.hermes.wearos.domain

/**
 * THE BEZEL VOLUME BUS (owner 2026-11-02: "for the video player and audio player … enable bezel
 * scrolling for the volume up and down — if we're playing music on the wrist that is how we turn
 * it up"). On Galaxy Watch the rotating bezel arrives at the foreground app as plain
 * KEYCODE_VOLUME_UP/DOWN, which by default drags the SYSTEM stream. While a player owns the
 * screen it binds [bind] and the bezel steers the PLAYER instead (ExoPlayer's `volume`, 0..1,
 * the same 5 % grid the dial-volume orb uses — [DialVolume.quantize]); the key is consumed, so
 * the system HUD never flashes. Unbind (player closed) and the bezel is the system volume again,
 * byte for byte what it was before.
 *
 * Pure: no Android imports, testable; MainActivity is the caller.
 */
object RotaryVolume {

    /** One detent = one 5 % step, matching DialVolume's haptic grid. */
    val STEP = DialVolume.quantize(0.05f)

    private var bound: ((steps: Int) -> Boolean)? = null

    /** True while a player owns the bezel (MainActivity consumes volume keys only then). */
    val active: Boolean get() = bound != null

    /** The handler returns true = "I took the key" (system volume untouched). */
    fun bind(handler: (steps: Int) -> Boolean) { bound = handler }

    fun unbind(handler: (steps: Int) -> Boolean) { if (bound === handler) bound = null }

    /** +1 = UP detents, -1 = DOWN. True = consumed. */
    fun rotate(steps: Int): Boolean = bound?.invoke(steps) ?: false

    /** The step math, exposed for tests and callers: quantized clamp of `v + steps·STEP`. */
    fun step(v: Float, steps: Int): Float = DialVolume.quantize(v + steps * STEP)
}
