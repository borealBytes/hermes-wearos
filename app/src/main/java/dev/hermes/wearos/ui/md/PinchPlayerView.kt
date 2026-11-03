package dev.hermes.wearos.ui.md

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.media3.ui.PlayerView
import dev.hermes.wearos.domain.MediaZoom
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * PINCH-TO-ZOOM VIDEO (owner 2026-11-02: "I want to be able to pinch to zoom in the video screen
 * too"). The image viewer does this with a Compose gesture lane, but a PlayerView is an
 * interop'd child View that consumes every touch for its controller — a Compose `pointerInput`
 * above it would only ever see the crumbs. So the lane lives WHERE the touches are: this subclass
 * intercepts the moment a second finger lands (pinch), intercepts single-finger drags once the
 * picture is zoomed (pan), and otherwise stays out of the controller's way — an untouched tap
 * still shows/hides the transport like always.
 *
 * The transform is the View's own scaleX/translation (GPU, no re-layout), clamped with the same
 * pure math the image viewer uses ([MediaZoom.clampOffset] — same pan bounds, same feel):
 * scale ∈ [1, 3], pivot at center, double-tap toggles fit↔2.5×.
 */
class PinchPlayerView(context: Context) : PlayerView(context) {

    var maxZoom = DEFAULT_MAX_ZOOM
        set(value) { field = max(1f, value) }

    private var pinchBase = 1f
    private var pinchDist0 = 0f
    private var panX0 = 0f
    private var panY0 = 0f
    private var downX = 0f
    private var downY = 0f
    private var pinching = false
    private var panning = false

    private val slopPx = 18f * resources.displayMetrics.density / 2.75f // ~18dp, viewer parity
    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            animateScale(if (scaleX > FIT + 0.01f) FIT else min(2.5f, maxZoom))
            return true
        }
        // onDown false = single taps stay the controller's.
    })

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                pinchBase = scaleX; pinchDist0 = spacing(ev); startPinch()
                return true
            }
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                tapDetector.onTouchEvent(ev)
                if (scaleX > FIT + 0.01f) return true // zoomed: drags pan, taps reach super via UP logic
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pinching && scaleX > FIT + 0.01f &&
                    (abs(ev.x - downX) > slopPx || abs(ev.y - downY) > slopPx)
                ) {
                    panning = true; panX0 = translationX; panY0 = translationY
                    return true
                }
            }
            else -> {}
        }
        return super.onInterceptTouchEvent(ev)
    }

    private fun startPinch() {
        pinching = true; panning = false
        // Hand the controller a cancel so it doesn't sit mid-gesture.
        val up = MotionEvent.obtain(System.currentTimeMillis(), System.currentTimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.onTouchEvent(up); up.recycle()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        tapDetector.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> { pinchBase = scaleX; pinchDist0 = spacing(ev); pinching = true }
            MotionEvent.ACTION_MOVE -> {
                if (pinching && ev.pointerCount >= 2) {
                    val d = spacing(ev)
                    if (pinchDist0 > 10f && d > 0f) applyScale(pinchBase * (d / pinchDist0), ev.x, ev.y)
                } else if (panning) {
                    val p = clampBoth(panX0 + (ev.x - downX), panY0 + (ev.y - downY))
                    translationX = p[0]; translationY = p[1]
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (ev.pointerCount <= 2) { pinching = false; panning = false }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pinching = false; panning = false }
            else -> {}
        }
        return true
    }

    private fun spacing(ev: MotionEvent): Float {
        val dx = ev.getX(0) - ev.getX(1)
        val dy = ev.getY(0) - ev.getY(1)
        return hypot(dx, dy)
    }

    private fun applyScale(target: Float, fx: Float, fy: Float) {
        val s = scaleFor(pinchBase, target / pinchBase, maxZoom)
        // Keep the picture under the fingers honest the easy way: pivot at view center
        // (round face, no corner pivots), clamp pan to the overflow the new scale exposes.
        pivotX = width / 2f; pivotY = height / 2f
        val prev = scaleX
        scaleX = s; scaleY = s
        if (s <= FIT + 0.01f) { translationX = 0f; translationY = 0f }
        else {
            val grow = if (prev > 0.01f) s / prev else 1f
            translationX *= grow; translationY *= grow
            clampOverflow()
        }
    }

    private fun clampOverflow() {
        val reachX = max(0f, (width * (scaleX - 1f)) / 2f)
        val reachY = max(0f, (height * (scaleX - 1f)) / 2f)
        translationX = translationX.coerceIn(-reachX, reachX)
        translationY = translationY.coerceIn(-reachY, reachY)
    }

    /** Public for the `-e media_zoom` dev hook (same door a double-tap uses). */
    internal fun animateScale(to: Float) {
        val s = to.coerceIn(FIT, maxZoom)
        animate().scaleX(s).scaleY(s).setDuration(140).withEndAction {
            if (s <= FIT + 0.01f) { translationX = 0f; translationY = 0f } else clampOverflow()
        }.start()
    }

    /** Companion helpers (unit-tested, JVM): the clamp + overflow math mirrors MediaZoom's rules. */
    companion object {
        const val FIT = 1f
        const val DEFAULT_MAX_ZOOM = 3f

        /** Scale after a pinch ratio: never below fit (a letterboxed video pans over nothing). */
        fun scaleFor(base: Float, ratio: Float, maxZoom: Float): Float =
            (base * ratio).coerceIn(FIT, max(1f, maxZoom))

        /** Pan reach at scale s for a w×h surface: the overflow each axis can show. */
        fun reach(s: Float, px: Int): Float = max(0f, (px * (s - FIT)) / 2f)

        /** True when the picture is zoomed enough that drags should pan. */
        fun panEnabled(s: Float): Boolean = s > FIT + 0.01f
    }

    /** MediaZoom parity: the SAME clampOffset the image viewer uses, applied to this surface. */
    private fun clampBoth(dx: Float, dy: Float): FloatArray =
        MediaZoom.clampOffset(scaleX, dx, dy, width, height)
}
