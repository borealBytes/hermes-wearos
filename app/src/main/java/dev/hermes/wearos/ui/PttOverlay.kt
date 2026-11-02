package dev.hermes.wearos.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.hermes.wearos.domain.SttFeed

/**
 * Push-to-talk / hands-free full-screen overlay (owner ask #4 → walkthrough READABILITY redesign:
 * "I don't see the live stt, font way too big? it's there just not visible… show at least the last
 * few sentences on screen"). The old title2-centered blob clipped off the round frame; this is a
 * BOTTOM-OF-SAFE-CIRCLE STACK: up to [SttFeed.MAX_LINES] lines, older ones dimmed caption, the
 * freshest in title3, growing upward from just above the orb bar. Mic pulse shrank to a small
 * dot-ring above the stack. Tap targets unchanged (keyboard chip); the orb bar still owns
 * release/send (caller keeps its own bottom padding for it).
 */
@Composable
fun PttOverlay(
    partial: String,
    handsFree: Boolean,
    onKeyboard: () -> Unit,
    history: List<String> = emptyList(),
    // BATCH-4 #13 (owner: *"very top small … voice input provider listed, and … if it's
    // streaming the text back … or not"*): pre-composed by MainActivity from
    // domain/SttProviders.overlayName + streamsLabel — e.g. "🛰 Gateway · whisper ·
    // text streaming ✗ · final when you stop". Honest by construction (pure + tested).
    statusLine: String = "",
    modifier: Modifier = Modifier,
) {
    val pulse = rememberInfiniteTransition(label = "micpulse")
    val t by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Restart), label = "t")
    val lines = SttFeed.lines(partial, history)
    val insets = roundInsets()
    // The PROOF-OF-LIFE dot: rides voice/SttLevel published by whichever engine is listening,
    // so even a buffered gateway engine (no text until stop) visibly proves the mic is reading.
    val level by dev.hermes.wearos.voice.SttLevel.level.collectAsState()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (statusLine.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = insets.topDp.dp, start = 12.dp, end = 12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(4.dp + 7.dp * level)
                        .background(
                            MaterialTheme.colors.primary.copy(alpha = 0.45f + 0.55f * level),
                            CircleShape,
                        ),
                )
                Text(
                    statusLine,
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                // Rest ABOVE the caller's orb-bar clearance; on short screens the stack simply
                // runs out upward — the freshest (last) line is the one that must never clip.
                .padding(bottom = 6.dp),
        ) {
            // Small pulse: 14 dp mic dot + expanding ring ≤ 26 dp (was a 48–64 dp halo stealing frame).
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 2.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(14.dp + (12.dp * t))
                            .border(1.dp, MaterialTheme.colors.primary.copy(alpha = 0.8f - 0.6f * t), CircleShape),
                    )
                    Text("🎤", style = MaterialTheme.typography.caption3)
                }
                Text(
                    if (handsFree) "tap orb again · pause sends" else "listening",
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.45f),
                )
            }
            if (lines.isEmpty()) {
                Text(
                    "listening…",
                    style = MaterialTheme.typography.body1,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = insets.bottomDp.dp / 2f),
                )
            }
            // Oldest → newest. Older lines: dimmed caption; freshest: title3 (owner: "body1,
            // last line body/title3"), anchored lowest so the newest words sit in the widest part
            // of the circle where they're readable; the round falloff only touches old lines.
            lines.forEachIndexed { i, line ->
                val newest = i == lines.lastIndex
                Text(
                    line,
                    style = if (newest) MaterialTheme.typography.title3 else MaterialTheme.typography.caption2,
                    color = if (newest) MaterialTheme.colors.onBackground
                    else MaterialTheme.colors.onBackground.copy(alpha = 0.45f),
                    textAlign = TextAlign.Center,
                    maxLines = if (newest) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                "keyboard",
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.primary,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colors.surface.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clickable(onClick = onKeyboard),
            )
        }
    }
}
