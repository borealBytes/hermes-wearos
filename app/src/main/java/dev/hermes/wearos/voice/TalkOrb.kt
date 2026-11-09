package dev.hermes.wearos.voice

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.wear.compose.material.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hermes.wearos.R
import dev.hermes.wearos.domain.OrbDial
import dev.hermes.wearos.domain.OrbGesture
import kotlinx.coroutines.withTimeoutOrNull

// LAYOUT D recolor (owner-approved): the disc is the launcher-dark #0D1117 with the white Hermes
// glyph instead of olive/gold; pressed BRIGHTENS the slate so the hold is visible on the dark disc.
private val OrbIdle = Color(0xFF0D1117)
private val OrbPressed = Color(0xFF3A4A66)
private val OrbLive = Color(0xFFB3261E)
private val OrbStop = Color(0xFF7F1D1D)
private val OrbStopRing = Color(0xFFFF5252)

/**
 * The TALK orb — and since batch 3, the ONLY control on the chat screen: every satellite button
 * became a drag direction (SPEC §12.1). "talk doesn't need to be a full circle; talk button maybe
 * can actually be the hermes icon" — a circular button rendering the HERMES MASCOT glyph
 * (ic_hermes_orb, a tintable white mark; NO "TALK" text anymore) on the launcher-dark disc.
 * LAYOUT D: the diameter is a PARAMETER (the cluster feeds the computed circle); the default keeps
 * the old 68 dp look for anything else that embeds it.
 *  - idle: the glyph (slightly softened alpha).
 *  - pressed: glyph brightened to full white + lighter base (visual feedback for the hold).
 *  - listening: red base + PULSE RING (expanding/fading circle), glyph kept.
 *  - running (a reply streams): red ring + a STOP square over the dimmed glyph — tap/hold-release
 *    stops the run (ask #3 semantics UNCHANGED).
 * Tap = hands-free listening (VAD auto-send); a stationary HOLD now blooms the dial IN PLACE in
 * every state (§12.1 batch 6+7: hold-to-talk is retired — the hold belongs to the dial); a DRAG out
 * of the disc reports a vector stream ([onDialArm]/[onDialMove]/[onDialEnd]) and the caller decides
 * the lane with `OrbDial` — because the ring lives in the parent (it must be drawn around the orb,
 * and the contextual rise moves the orb itself). The up-drag that used to open the keyboard is now
 * simply the 12:00 lane, so `onDragUp` is gone. Not a Material `Button`: wear Buttons swallow
 * click-only semantics and the hold/drag lane needs the raw pointer stream.
 */
@Composable
fun TalkOrb(
    listening: Boolean,
    modifier: Modifier = Modifier,
    running: Boolean = false,
    /** TTS queue live (owner 2026-10-29): the orb ALSO wears the STOP face while it SPEAKS —
     *  tap = mute (the answer already landed); hold = the dial pops out in place. */
    speaking: Boolean = false,
    diameter: Dp = 68.dp,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    /** 480→viewport scale (`OrbDial.scaleFor`) so the dial gate is proportional on every face. */
    dialScale: Float = 1f,
    /** The finger left the disc in some direction — show the ring. */
    onDialArm: () -> Unit = {},
    /** Live drag vector (viewport px, from the DOWN point) — aim the ring with this. */
    onDialMove: (dxPx: Float, dyPx: Float) -> Unit = { _, _ -> },
    /** Released. The caller cancels when `OrbDial.isCancel` says the finger came home. */
    onDialEnd: (dxPx: Float, dyPx: Float) -> Unit = { _, _ -> },
) {
    var pressed by remember { mutableStateOf(false) }
    // The detector reads NO orb state (batch 7): a stationary hold arms the dial in EVERY state,
    // so there is nothing for a mid-hold flip to race against — and pointerInput keys only on
    // dialScale, so thinking→idle (queue drains while the finger sits) can never restart the
    // gesture mid-stream and freeze the ring open (the owner 2026-10-29 state-machine bug).
    // STOP face = thinking OR speaking (same red ⏹ everywhere: tap always means "stop what the
    // orb is doing" — cancel the answer while running, mute while speaking).
    val stopped = running || speaking
    val bg = when {
        stopped -> OrbStop
        listening -> OrbLive
        pressed -> OrbPressed
        else -> OrbIdle
    }
    val pulse = rememberInfiniteTransition(label = "orbpulse")
    val t by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Restart), label = "t")

    Box(contentAlignment = Alignment.Center, modifier = modifier.size(diameter)) {
        // Pulse ring (listening, or speaking alone): an expanding, fading red circle OUTSIDE the
        // orb — drawn first so the orb always covers its center. While a reply is being READ
        // ALOUD the pulse carries across the room ("silent-but-speaking is obvious", SPEC §12.11).
        if ((listening && !running) || (speaking && !listening && !running)) {
            Box(
                modifier = Modifier
                    .size(diameter)
                    .graphicsLayer {
                        val s = 1f + 0.45f * t
                        scaleX = s; scaleY = s
                        alpha = 1f - t
                    }
                    .border(2.dp, OrbStopRing.copy(alpha = 0.9f), CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .size(diameter)
                .clip(CircleShape)
                .then(
                    if (stopped) Modifier.border(androidx.compose.foundation.BorderStroke(3.dp, OrbStopRing), CircleShape).padding(3.dp)
                    else Modifier
                )
                .clip(CircleShape)
                .background(bg)
                .pointerInput(dialScale) {
                    val minUp = OrbGesture.MIN_UP_DP.dp
                    val maxDrift = OrbGesture.MAX_DRIFT_DP.dp
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        val tracker = OrbGesture.Tracker(
                            down.position.x, down.position.y, down.uptimeMillis,
                            viewConfiguration.longPressTimeoutMillis, minUp.toPx(), maxDrift.toPx(),
                            OrbDial.ACTIVATE_REF * dialScale,
                        )
                        // The dial stream, shared by BOTH entries: the drag gate AND the
                        // hold-pop (which arms in every state). Feeds moves until the finger
                        // leaves. (Extension receiver: awaitPointerEvent is a @RestrictsSuspension
                        // member.)
                        val feedDialUntilUp: suspend androidx.compose.ui.input.pointer.AwaitPointerEventScope.() -> Unit = {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.pressed }
                                if (ch == null) break
                                tracker.move(ch.position.x, ch.position.y, ch.uptimeMillis)
                                ev.changes.forEach { if (it.pressed) it.consume() }
                                onDialMove(tracker.dx, tracker.dy)
                            }
                        }
                        // THE RACE: drag gate vs hold timeout — whoever crosses first owns the
                        // gesture (a slow slide is PTT, a flick is a lane, and the finger that
                        // lifts before either is a tap).
                        val earlyUp = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.pressed }
                                if (ch == null) return@withTimeoutOrNull true // up/cancel, no gate
                                // This artifact's PointerEvent has no event timestamp — the
                                // per-change uptimeMillis is the time source (1.7-era API).
                                tracker.move(ch.position.x, ch.position.y, ch.uptimeMillis)
                                if (tracker.dragArmed || tracker.dragUpCrossed) {
                                    // Claim the stream: the transcript under the orb must not scroll.
                                    ev.changes.forEach { if (it.pressed) it.consume() }
                                    return@withTimeoutOrNull false
                                }
                            }
                        }
                        when {
                            earlyUp == false -> {
                                // DIAL: feed the ring until the finger leaves, then let go = commit.
                                // No mic ever started here.
                                onDialArm()
                                onDialMove(tracker.dx, tracker.dy)
                                this.feedDialUntilUp()
                                pressed = false
                                onDialEnd(tracker.dx, tracker.dy)
                            }
                            earlyUp == true -> {
                                pressed = false
                                onTap()
                            }
                            else -> {
                                // HOLD-POPS-THE-DIAL IN EVERY STATE (owner batch 6 doctrine,
                                // SPEC §12.1: "hold-to-talk retired — the hold belongs to the
                                // dial"; batch 7 on real glass: the state gate that made the pop
                                // thinking/speaking-only was the regression — a stationary hold on
                                // the IDLE orb went to the no-op lane and the dial never bloomed).
                                // The run is NOT stopped by the hold (short tap does that);
                                // release-at-home = cancel. Aim after the bloom — the vector is
                                // live post-hold (OrbGesture batch 7) — and release on a wedge to
                                // fire that lane, spin included.
                                onHoldStart()
                                onDialArm()
                                onDialMove(tracker.dx, tracker.dy)
                                this.feedDialUntilUp()
                                pressed = false
                                onDialEnd(tracker.dx, tracker.dy)
                                onHoldEnd()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // The hermes mark: white tintable glyph. Idle/pressed = bright white (pressed FULL +
            // lighter base); listening = white on red; running = dimmed under the STOP square.
            Image(
                painter = painterResource(R.drawable.ic_hermes_orb),
                contentDescription = when {
                    running -> "stop"
                    speaking -> "mute"
                    else -> "talk with hermes"
                },
                contentScale = ContentScale.Fit,
                colorFilter = when {
                    stopped -> ColorFilter.tint(Color.White.copy(alpha = 0.25f))
                    listening -> ColorFilter.tint(Color.White.copy(alpha = 0.55f))
                    pressed -> ColorFilter.tint(Color.White)
                    else -> ColorFilter.tint(Color.White.copy(alpha = 0.92f))
                },
                modifier = Modifier.size(diameter * 0.76f),
            )
            // STOP overlay glyph (thinking): white rounded square = "tap cancels the answer".
            // While it SPEAKS the answer already landed, so the honest glyph is a MUTE speaker
            // (owner 2026-10-29: *"by the time you're speaking you've already completed returning
            // information … it should be a mute button on the orb"*) — same tap-to-act disc,
            // same red face, but it promises exactly what the tap does.
            if (running) {
                Box(
                    modifier = Modifier
                        .size(diameter * 0.32f)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.White),
                )
            } else if (speaking) {
                Text("🔇", fontSize = (diameter.value * 0.34f).sp)
            }
        }
    }
}
