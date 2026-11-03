package dev.hermes.wearos.ui.md

import android.graphics.ImageDecoder
import android.graphics.ImageDecoder.Source
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import kotlinx.coroutines.delay

/**
 * ANIMATED IMAGES ON THE WRIST (owner 2026-11-02: "animated GIFs are not rendering on the watch
 * for the media images — make that work"). Every image path so far decoded to a still Bitmap
 * (BitmapFactory takes the FIRST frame of a GIF), so animated GIFs sat frozen. Android's own
 * `ImageDecoder.decodeDrawable` (API 28+, min here is 30) sniffs the container from the bytes and
 * hands back an [AnimatedImageDrawable] for animated GIF/WebP and a plain BitmapDrawable
 * otherwise — one decoder for everything.
 *
 * [AnimatedDrawablePainter] draws any Drawable through Compose and re-draws on every frame
 * invalidation. Both animation hooks newer than API 27 (`setInvalidateCallback`, `advance`) are
 * @hide in the SDK, so this uses the CLASSIC public door instead: AnimatedImageDrawable drives
 * itself off the Choreographer once `start()`ed and calls `invalidateSelf` per frame — a
 * `Drawable.Callback` (public since API 1) catches that and bumps a state the painter reads in
 * `onDraw`, so each frame redraws just this draw, not the tree. Stills never bump it.
 *
 * Playback is bound to the composition: [start] on enter, [stop] on leave — a GIF scrolled off
 * the transcript costs nothing until it scrolls back.
 */
class AnimatedDrawablePainter internal constructor(val drawable: Drawable) : Painter() {
    private var frame by mutableIntStateOf(0)
    private var alphaState by mutableFloatStateOf(1f)

    init {
        drawable.callback = object : Drawable.Callback {
            override fun invalidateDrawable(d: Drawable) { frame++ }
            // AnimatedImageDrawable schedules via its internal ticker, but a non-animating
            // Drawable that DOES use scheduleDrawable (BitmapDrawable animations) must not stall:
            // run the invalidation the cheap way — next recomposition draws it.
            override fun scheduleDrawable(d: Drawable, what: Runnable, whenMs: Long) { frame++ }
            override fun unscheduleDrawable(d: Drawable, what: Runnable) {}
        }
    }

    /** Redraw subscription: routes a poke through the callback path so `frame` is the ONE door. */
    internal fun onFallbackTick() { drawable.invalidateSelf() }

    override val intrinsicSize: Size
        get() = Size(
            drawable.intrinsicWidth.coerceAtLeast(1).toFloat(),
            drawable.intrinsicHeight.coerceAtLeast(1).toFloat(),
        )

    override fun applyAlpha(alpha: Float): Boolean {
        alphaState = alpha
        return true
    }

    override fun DrawScope.onDraw() {
        frame // read = redraw subscription; the value itself means nothing
        val a = alphaState
        drawIntoCanvas { canvas ->
            drawable.alpha = (a * 255).toInt().coerceIn(0, 255)
            drawable.setBounds(0, 0, size.width.toInt(), size.height.toInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }

    companion object {
        // Safety net: if a Drawable animates without ever invalidating (odd driver timing), a
        // slow poll still keeps the picture honest. Callers attach this as a fallback tick.
        const val FALLBACK_TICK_MS = 120L
    }
}

/** What [rememberAnimatedImage] decided about these bytes. */
class AnimatedImage(val painter: Painter?, val animated: Boolean)

/**
 * Cheap container sniff (no decode): any GIF (a one-frame AnimatedImageDrawable is harmless) or
 * an animated WebP (VP8X chunk, animation bit). Pure and JVM-testable; the expensive question
 * ("does it move?") is answered here without allocating a full-size Bitmap for every still photo
 * on the wrist.
 */
fun looksAnimated(bytes: ByteArray): Boolean {
    fun isByte(i: Int, c: Char) = i < bytes.size && bytes[i] == c.code.toByte()
    if (isByte(0, 'G') && isByte(1, 'I') && isByte(2, 'F') && isByte(3, '8')) return true
    // RIFF<size>WEBP … 'VP8X' + flags byte: bit 1 = animation.
    val webpX = isByte(0, 'R') && isByte(1, 'I') && isByte(2, 'F') && isByte(3, 'F') &&
        isByte(8, 'W') && isByte(9, 'E') && isByte(10, 'B') && isByte(11, 'P') &&
        isByte(12, 'V') && isByte(13, 'P') && isByte(14, '8') && isByte(15, 'X')
    return webpX && bytes.size >= 21 && bytes[20].toInt() and 0x02 != 0
}

/**
 * Decode bytes → a painter, animated when the container is (GIF/WebP). painter=null means "not
 * an image" (caller keeps its BitmapFactory fallback). While composed, an animated drawable is
 * started here and stopped on leave; a slow fallback poke runs the whole time it is visible so
 * the frame state advances even if the drawable's own invalidation timing is strange.
 */
@Composable
fun rememberAnimatedImage(bytes: ByteArray?): AnimatedImage {
    val result = remember(bytes) {
        runCatching {
            val src: Source = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
            val d = ImageDecoder.decodeDrawable(src)
            AnimatedImage(AnimatedDrawablePainter(d), d is AnimatedImageDrawable)
        }.getOrDefault(AnimatedImage(null, false))
    }
    DisposableEffect(result) {
        val d = (result.painter as? AnimatedDrawablePainter)?.drawable as? AnimatedImageDrawable
        d?.start()
        onDispose { d?.stop() }
    }
    LaunchedEffect(result) {
        if (!result.animated) return@LaunchedEffect
        while (true) {
            delay(AnimatedDrawablePainter.FALLBACK_TICK_MS)
            (result.painter as? AnimatedDrawablePainter)?.let { it.onFallbackTick() }
        }
    }
    return result
}
