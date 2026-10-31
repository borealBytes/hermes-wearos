package dev.hermes.wearos.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/**
 * Agent switcher dialog (owner ask #1): gateway profiles, one tap to switch, current one marked.
 * Full-bleed black dialog (round-safe: 20+ dp padding, scrollable list) — lists can outgrow the
 * circle, so vertical scroll is a must. Empty-list state explains the fallback. No IconButtons
 * anywhere: wear Buttons + Text only.
 */
@Composable
fun AgentPickerDialog(
    current: String,
    agents: List<String>,
    note: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    gateway: String = "",  // selected gateway label (SPEC §11) — small header line
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val edgeDlgScroll = androidx.compose.foundation.rememberScrollState()
        val edgeScope = rememberCoroutineScope()
        // BATCH-5 #18: the gesture rides a viewport-sized wrapper, NOT the scrolled Column —
        // the column node is content-tall, so the ring ellipse was computed against the wrong
        // box and edge circles died below the top rim (see SettingsScreen for the full why).
        Box(
            modifier = Modifier
                .fillMaxSize()
                // BATCH-6 #29: button lists circle at 2f (see SettingsScreen).
                .edgeCircleScroll(speed = 2f) { total -> edgeScope.launch {
                    // APPLY TELEMETRY (#28): the stroke lands here; if it does nothing visible,
                    // the used/max numbers say whether the scroll range was zero (content fits)
                    // or the ScrollState refused the injection — no guessing from screenshots.
                    val used = edgeDlgScroll.scrollBy(total)
                    android.util.Log.d(
                        "HermesWear",
                        "agents edge apply: want=${total.toInt()} used=${used.toInt()} max=${edgeDlgScroll.maxValue} at=${edgeDlgScroll.value}",
                    )
                } },
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // Owner correction (batch-4 #6): the old 30/18 band sat OUTSIDE the scroll and
                // shrank the viewport — content visibly cut off at top and bottom. Whole face
                // scrolls; the buffer is blank CONTENT lines at the ends (top so the title clears
                // the clock at rest, bottom = the circle-safe rim line).
                .verticalScroll(edgeDlgScroll)
                .padding(top = 34.dp, bottom = circleSafeBottomDp(12f, 10f).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Agents", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
            if (gateway.isNotEmpty()) {
                // "which gateway am I picking agents ON" (owner gateway epic, SPEC §11) — small.
                Text(
                    "on $gateway",
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (note.isNotEmpty()) {
                Text(
                    note,
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                )
            }
            agents.forEach { name ->
                val isCurrent = name == current
                Button(
                    onClick = { onPick(name) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .size(width = 140.dp, height = 38.dp),
                    colors = if (isCurrent) ButtonDefaults.buttonColors()
                    else ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.35f),
                    ),
                ) {
                    Text(
                        if (isCurrent) "● $name" else name,
                        style = MaterialTheme.typography.button,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.size(width = 90.dp, height = 32.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.35f),
                ),
            ) { Text("close", style = MaterialTheme.typography.button) }
        }
        } // Box (batch-5 #18 edge-scroll host)
    }
}
