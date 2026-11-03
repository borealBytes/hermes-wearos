package dev.hermes.wearos.ui.md

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.hermes.wearos.domain.MediaZoom
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen pinch-zoom viewer for markdown media (walkthrough ask: "tap on an image OR mermaid
 * card → full-screen viewer"). Wear Compose 1.4.1 / foundation 1.7.6-safe: `TransformState` is
 * gone there, so everything runs through ONE raw `awaitEachGesture` lane. The old layout —
 * detectTapGestures + a pinch loop as siblings — was the root cause of the dead ✕/tap-close on
 * the watch: the loop consumed any event with ANY movement (`pan != Offset.Zero`), and a real
 * touch always jitters a pixel or two, so the sibling tap detector (which aborts on consumed
 * events) and the ✕ chip's clickable starved. Owner had to press Back. The lane now owns all
 * four intents directly: tap (<18dp travel, <250ms, paired for double-tap) → fit/close;
 * double-tap → 1×↔2.5×; single-pointer drag past 18dp → pan (only consumed past that slop, so
 * a slow drag never closes); 2+ fingers → pinch. NO IconButton: tap anywhere closes; the ✕ chip
 * is a redundant 52dp target inside the safe circle. Caption small at the bottom. Scope (owner
 * "maybe everything pinch zoom"): images+mermaid now; text keeps crown scroll — crown
 * font-scale noted in SPEC as follow-up.
 *
 * LAYOUT D EPIC: the floor moved BELOW fit (0.3×, pure math in domain/MediaZoom) — the round
 * watch viewport clips the corners of a fit-scaled image, and sub-fit + panning (letterbox slack)
 * is what brings those clipped parts back into view. Double-tap cycle fit↔2.5× unchanged; a tap
 * returns to fit from EITHER side, a second tap dismisses.
 */
private const val FIT_MAX_ZOOM = MediaZoom.FIT_MAX

/** Double-tap pairing bookkeeping for the viewer gesture lane (see ZoomableMediaViewer KDoc). */
private class TapPairing {
    var lastUp = 0L
    var lastX = 0f
    var lastY = 0f
}

@Composable
fun ZoomableMediaViewer(
    bitmap: ImageBitmap,
    caption: String,
    onDismiss: () -> Unit,
    initialScale: Float = MediaZoom.FIT,
) = ZoomableMediaViewer(androidx.compose.ui.graphics.painter.BitmapPainter(bitmap), caption, onDismiss, initialScale)

/**
 * PAINTER-CORED viewer: the GIF epic (owner 2026-11-02: "animated GIFs are not rendering") routes
 * [AnimatedDrawablePainter] here so the film animates at full res, and stills keep the identical
 * pinch/pan/double-tap lane — the gesture code never notices which painter it drives.
 */
@Composable
fun ZoomableMediaViewer(
    painter: androidx.compose.ui.graphics.painter.Painter,
    caption: String,
    onDismiss: () -> Unit,
    // The dev hook (`-e viewer_demo <scale>`) opens the viewer BELOW fit for adb screenshots;
    // production callers pass nothing and land at fit like always.
    initialScale: Float = MediaZoom.FIT,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        var viewSize by remember { mutableStateOf(IntSize.Zero) }
        var scale by remember { mutableFloatStateOf(MediaZoom.clampScale(initialScale)) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        // Pan bounds FOLLOW the scale — above fit: overflow reach; below fit: letterbox slack
        // (the old `(s−1)·w/2` went NEGATIVE under fit and coerceIn would throw).
        fun clamp(s: Float, o: Offset): Offset {
            val p = MediaZoom.clampOffset(
                s, o.x, o.y, viewSize.width, viewSize.height,
            )
            return Offset(p[0], p[1])
        }
        // The double-tap pairing timer needs a real CoroutineScope: in Compose 1.7 PointerInputScope
        // is NOT one (and the awaitEachGesture lane is a restricted scope on top of that), so the
        // pairing bookkeeping lives in a remembered holder and deferred taps launch on the dialog's
        // scope — timers die with the dialog, never firing after close.
        val tapPair = remember { TapPairing() }
        val viewerScope = rememberCoroutineScope()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { viewSize = it }
                // THE ONE GESTURE LANE (see KDoc — the old tap-detector + pinch-loop pair is what
                // killed tap/✕ close on jittery real touches). Tap-close carries a double-tap
                // pairing window: the single action is deferred only by the double-tap timeout,
                // exactly the feel of the old detectTapGestures(onTap, onDoubleTap).
                .pointerInput(Unit) {
                    val tapSlopPx = 18.dp.toPx()
                    val tapMaxMs = 250L
                    val dblMs = viewConfiguration.doubleTapTimeoutMillis
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // CRITICAL: the PointerInputChange returned by awaitFirstDown is a MUTABLE
                        // instance that later events rewrite in place — snapshot the anchor values,
                        // or drift/duration always read ~0 and drags get misclassified as taps.
                        val downPos = Offset(down.position.x, down.position.y)
                        val downT = down.uptimeMillis
                        var multi = false
                        var movedFar = false
                        var lastX = down.position.x
                        var lastY = down.position.y
                        var upX = lastX
                        var upY = lastY
                        var prevDist = -1f
                        // NOTE: this artifact's PointerEvent carries no event timestamp (1.7-era
                        // API) — the per-change uptimeMillis is the only time source, and touch
                        // CANCEL arrives as a plain release here (treated as up: on a touch-only
                        // dial that is the old behavior anyway).
                        var lastT = downT
                        var released = false
                        while (!released) {
                            val ev = awaitPointerEvent()
                            // The lane claims the gesture the moment a finger is down (the old
                            // lane consumed from the first jitter — without that the window LEAKS
                            // ownership mid-swipe: the stream silently stops arriving, proven on
                            // emulator 2026-10-25). Nothing under a full-screen viewer needs the
                            // events; tap vs drag vs pinch is decided internally, not by slop
                            // negotiation with the transcript behind. The ✕ chip (child) still
                            // sees the tap either way.
                            ev.changes.forEach { if (it.pressed) it.consume() }
                            lastT = ev.changes.maxOfOrNull { it.uptimeMillis } ?: lastT
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                released = true
                                ev.changes.firstOrNull()?.let {
                                    upX = it.position.x; upY = it.position.y
                                    // Travel to the RELEASE point counts too — a fast flick can
                                    // deliver few MOVE events, and its up position is proof it
                                    // was not a tap (18dp rule covers the whole gesture).
                                    if (!movedFar && (Offset(upX, upY) - downPos).getDistance() > tapSlopPx) {
                                        movedFar = true
                                        android.util.Log.d("HermesWear", "viewer drag — tap-close disarmed")
                                    }
                                }
                            } else if (pressed.size >= 2) {
                                multi = true
                                val d = (pressed[0].position - pressed[1].position).getDistance()
                                if (prevDist > 0f && d > 0f) {
                                    val s = MediaZoom.clampScale(scale * (d / prevDist))
                                    scale = s
                                    val pan = ev.calculatePan()
                                    offset = if (MediaZoom.panEnabled(s)) clamp(s, offset + pan) else Offset.Zero
                                }
                                prevDist = d
                            } else {
                                val p = pressed[0].position
                                if (!movedFar && (p - downPos).getDistance() > tapSlopPx) {
                                    movedFar = true
                                    android.util.Log.d("HermesWear", "viewer drag — tap-close disarmed")
                                }
                                // Pan whenever scale ≠ fit (IN or BELOW); at fit drags stay inert.
                                if (movedFar) {
                                    val delta = p - Offset(lastX, lastY)
                                    if (MediaZoom.panEnabled(scale)) offset = clamp(scale, offset + delta)
                                }
                                lastX = p.x
                                lastY = p.y
                            }
                        }
                        if (multi || movedFar || lastT - downT >= tapMaxMs) {
                            return@awaitEachGesture
                        }
                        val near = hypot((upX - tapPair.lastX).toDouble(), (upY - tapPair.lastY).toDouble()) <=
                            tapSlopPx * 1.5
                        if (tapPair.lastUp > 0L && lastT - tapPair.lastUp <= dblMs && near) {
                            // Double-tap: clear the pending single action (the timer below finds a
                            // mismatched token and no-ops) and run the zoom cycle.
                            tapPair.lastUp = 0L
                            android.util.Log.d("HermesWear", "viewer doubleTap scale=$scale")
                            if (MediaZoom.atFit(scale)) scale = FIT_MAX_ZOOM
                            else { scale = MediaZoom.FIT; offset = Offset.Zero }
                        } else {
                            tapPair.lastUp = lastT
                            tapPair.lastX = upX
                            tapPair.lastY = upY
                            val mine = lastT
                            viewerScope.launch {
                                delay(dblMs)
                                if (tapPair.lastUp == mine) {
                                    tapPair.lastUp = 0L
                                    android.util.Log.d("HermesWear", "viewer tap scale=$scale")
                                    if (!MediaZoom.atFit(scale)) {
                                        scale = MediaZoom.FIT; offset = Offset.Zero
                                    } else onDismiss()
                                }
                            }
                        }
                    }
                },
        ) {
            Image(
                painter = painter,
                contentDescription = caption.ifBlank { "media" },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    },
            )
            if (caption.isNotBlank()) Text(
                caption,
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 18.dp),
            )
            // ✕ close chip — FIX 2: a 52dp circle (≥48dp touch target; the old glyph+4dp padding
            // measured ~30dp tall on a jittery watch) centered at ~15% of the display height:
            // top-center is INSIDE the round safe circle (the arc dips lowest at the sides, and
            // the clock lives above this line). The gesture lane already closes on a tap ANYWHERE
            // (incl. over this chip — both fire, dismissing twice is idempotent), so the chip is
            // the visible affordance, not the only door.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset {
                        val half = 26.dp.toPx() // half the 52dp circle → center it on the 15% line
                        val y = ((viewSize.height * 0.15f) - half)
                            .roundToInt()
                            .coerceAtLeast(20.dp.toPx().roundToInt())
                        IntOffset(0, y)
                    }
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colors.surface.copy(alpha = 0.45f))
                    .clickable(onClickLabel = "close viewer", onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "✕",
                    style = MaterialTheme.typography.body1,
                    color = MaterialTheme.colors.onBackground,
                )
            }
        }
    }
}
