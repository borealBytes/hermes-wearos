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
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
 * Tap = hands-free listening (VAD auto-send), hold = push-to-talk, exactly as before; a DRAG out of
 * the disc now reports a vector stream ([onDialArm]/[onDialMove]/[onDialEnd]) and the caller decides
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
    // State read LIVE inside the gesture loop (rememberUpdatedState), never as a pointerInput
    // key: if thinking/speaking flipped mid-hold (queue drained while the finger sat there),
    // restarting the detector would cancel the gesture WITHOUT onDialEnd and freeze the ring
    // open (owner 2026-10-29 state machine).
    val runningLive by androidx.compose.runtime.rememberUpdatedState(running)
    val speakingLive by androidx.compose.runtime.rememberUpdatedState(speaking)
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
                        // stopped-state hold-pop. Feeds moves until the finger leaves. (Extension
                        // receiver: awaitPointerEvent is a @RestrictsSuspension member.)
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
                            runningLive || speakingLive -> {
                                // HOLD-POPS-THE-DIAL (owner 2026-10-29: *"press and hold should
                                // still pop out the clock scrolly thing … maybe I want the
                                // keyboard, a steer, a slash command"*). While it thinks OR reads
                                // aloud, a stationary hold blooms the ring IN PLACE — the run is
                                // NOT stopped (short tap does that) and is not disturbed on
                                // release-at-home (come-home = cancel). Aim after the bloom and
                                // release on a lane to fire it.
                                onDialArm()
                                onDialMove(tracker.dx, tracker.dy)
                                this.feedDialUntilUp()
                                pressed = false
                                onDialEnd(tracker.dx, tracker.dy)
                            }
                            else -> {
                                onHoldStart()
                                waitForUpOrCancellation()
                                pressed = false
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
                contentDescription = if (stopped) "stop" else "talk with hermes",
                contentScale = ContentScale.Fit,
                colorFilter = when {
                    stopped -> ColorFilter.tint(Color.White.copy(alpha = 0.25f))
                    listening -> ColorFilter.tint(Color.White.copy(alpha = 0.55f))
                    pressed -> ColorFilter.tint(Color.White)
                    else -> ColorFilter.tint(Color.White.copy(alpha = 0.92f))
                },
                modifier = Modifier.size(diameter * 0.76f),
            )
            // STOP overlay glyph (thinking OR speaking): white rounded square — tap semantics per
            // state: cancel the answer while running, mute while speaking (owner 2026-10-29).
            if (stopped) {
                Box(
                    modifier = Modifier
                        .size(diameter * 0.32f)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.White),
                )
            }
        }
    }
}
