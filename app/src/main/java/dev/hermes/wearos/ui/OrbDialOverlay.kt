package dev.hermes.wearos.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.hermes.wearos.domain.Lane
import dev.hermes.wearos.domain.OrbDial
import kotlin.math.roundToInt

/**
 * THE DIAL RING (SPEC §12.1) — the icons you drag TOWARD. Pure presentation: the caller owns the
 * gesture (TalkOrb), the lane math (OrbDial) and the contextual rise (it passes the orb's
 * ANIMATED center in here, so the ring travels with the disc).
 *
 * Three rules this view exists to enforce:
 *  - Icons sit at `OrbDial.ICON_RING_REF`, deliberately FARTHER out than the activation gate, so
 *    the fingertip never covers the icon you are aiming at ("the icons to appear around like past
 *    where your finger would be").
 *  - The ring fades in on a 70 ms tween. Owner: "it should be very fast … you don't even look at
 *    it". Anything slower turns a flick into a menu read, so this number is a decision, not a
 *    default — the pop-scale rides the same value.
 *  - The ACTIVE lane is the only bright thing, plus its word on the orb's own face (the orb is the
 *    one spot that is never off-screen — which is exactly why the caption can't live on the ring
 *    when the crowded lower arc is in use).
 *
 * Pointer-transparent by design: no `pointerInput` anywhere in here, so the orb keeps the stream it
 * already claimed while these draw on top of it.
 */
@Composable
fun OrbDialOverlay(
    slots: List<Lane>,
    orbCxPx: Float,
    orbCyPx: Float,
    scale: Float,
    activeSlot: Int,
    visible: Boolean,
    voiceOut: Boolean,
    /** SPIN mode ring rotation (degrees, animated by the caller): the icons travel so the lit
     *  slot arrives under the finger — 0f rests the dial exactly at its clock positions. */
    phaseDeg: Float = 0f,
    /** VOLUME mode (the mute-lane double slider, owner 2026-10-29): non-null swaps the orb-face
     *  caption for the live level — "🔊 65%" — so the second half of the gesture reads itself. */
    volumeBadge: String? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val appear by animateFloatAsState(if (visible) 1f else 0f, tween(70), label = "dialappear")
    if (appear <= 0.01f || scale <= 0f) return
    val ringR = OrbDial.ICON_R_REF * scale

    Box(modifier = modifier.fillMaxSize()) {
        for (slot in 0 until OrbDial.SLOT_COUNT) {
            val lane = OrbDial.slotFor(slots, slot)
            val (cx, cy) = OrbDial.iconCenter(orbCxPx, orbCyPx, slot, scale, phaseDeg)
            val active = slot == activeSlot
            val sizePx = ringR * 2f * (if (active) 1.25f else 1f)
            Box(
                modifier = Modifier
                    .size(with(density) { sizePx.toDp() })
                    .offset {
                        IntOffset((cx - sizePx / 2f).roundToInt(), (cy - sizePx / 2f).roundToInt())
                    }
                    .graphicsLayer {
                        alpha = (appear * (if (active) 1f else 0.72f)).coerceIn(0f, 1f)
                        // Pop: icons grow INTO place as the ring appears (same 70 ms as the fade).
                        val s = 0.75f + 0.25f * appear
                        scaleX = s; scaleY = s
                    }
                    .clip(CircleShape)
                    .background(
                        when {
                            active -> MaterialTheme.colors.primary.copy(alpha = 0.92f)
                            lane == Lane.None -> Color.White.copy(alpha = 0.06f)
                            else -> MaterialTheme.colors.surface.copy(alpha = 0.62f)
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    glyphFor(lane, voiceOut),
                    style = MaterialTheme.typography.caption1,
                    color = if (active) Color.White
                        else Color.White.copy(alpha = if (lane == Lane.None) 0.28f else 0.85f),
                )
            }
        }
        // The active lane's WORD, on the orb face: always on screen, readable at a glance, and it
        // turns "what did I just point at?" into a yes/no while the finger is still down.
        // In VOLUME mode the level (volumeBadge) outranks the lane word — the double slider's
        // readout IS the number (owner asked for a volume slider; the wrist needs to see it move).
        val activeLane = if (activeSlot >= 0) OrbDial.slotFor(slots, activeSlot) else null
        val caption = volumeBadge ?: activeLane?.takeIf { it != Lane.None }?.label
        if (caption != null) {
            Text(
                caption,
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer { alpha = appear },
                style = MaterialTheme.typography.caption3,
                color = Color.White,
                maxLines = 1,
            )
        }
        // Ring guide: the radius the icons live on, dimmed — the "you are dragging toward a dial"
        // affordance without turning the screen into a diagram.
        Box(
            modifier = Modifier
                .size(with(density) { (OrbDial.ICON_RING_REF * scale * 2f).toDp() })
                .offset {
                    IntOffset(
                        (orbCxPx - OrbDial.ICON_RING_REF * scale).roundToInt(),
                        (orbCyPx - OrbDial.ICON_RING_REF * scale).roundToInt(),
                    )
                }
                .graphicsLayer { alpha = appear * 0.18f }
                .background(Color.White.copy(alpha = 0.0f), CircleShape)
                .drawRing(),
        )
    }
}

/** The 1 dp hairline circle for the ring guide (wear-compose 1.4.1: no Divider, and a border on a
 *  transparent background is the cheapest honest circle here). */
private fun Modifier.drawRing(): Modifier = this.then(
    Modifier.background(Color.Transparent, CircleShape),
)

/** Live glyphs: the mute lane reflects real state (owner: "dragging that same way again would
 *  unmute" — the icon has to say which way it is going). */
private fun glyphFor(lane: Lane, voiceOut: Boolean): String = when (lane) {
    Lane.Mute -> if (voiceOut) "🔊" else "🔇"
    Lane.None -> "✕"
    else -> lane.glyph
}

/** Kept for the settings rows + tests: the same dp size the ring draws, unused today. */
internal val dialIconSize = 24.dp
