package dev.hermes.wearos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // Explicit circle clearance: window insets aren't delivered inside Dialog windows,
                // so the safe-circle paddings are spelled out (title out of the clock zone,
                // content above the chin, edges off the round clip). BATCH-4 #6: the RIM
                // clearance rides AFTER verticalScroll as content padding (a pre-scroll band
                // would squash the viewport) — the last chip RESTS on the circle-safe line.
                .padding(top = 30.dp, bottom = 18.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = (circleSafeBottomDp(12f, 10f) - 18f).coerceAtLeast(0f).dp),
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
    }
}
