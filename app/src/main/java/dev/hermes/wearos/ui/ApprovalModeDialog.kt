package dev.hermes.wearos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import dev.hermes.wearos.domain.ApprovalMode

/**
 * APPROVALS SHEET (owner 2026-10-29: *"we have manual, smart, and off for approvals … change the
 * approval mode by chat session … up to the user on the watch to choose how much interaction they
 * want"*). Four rows — inherit (the agent's default, the safe rest state), manual, smart, off —
 * each stating what it DOES, because the wrist decides from this sentence alone. ● marks the
 * chat's current pick; a tap persists it to this chat and closes (domain/ApprovalMode owns the
 * semantics, SPEC §12.8). Same round-safe dialog shape as the model picker.
 */
@Composable
fun ApprovalModeDialog(
    current: ApprovalMode,
    chatLabel: String,
    agent: String,
    onPick: (ApprovalMode) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .verticalScroll(rememberScrollState())
                // BATCH-4 #6: rim clearance as CONTENT padding after the scroll (see AgentPicker).
                .padding(top = 30.dp, bottom = 18.dp)
                .padding(top = 0.dp, bottom = (circleSafeBottomDp(12f, 10f) - 18f).coerceAtLeast(0f).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Approvals", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
            Text(
                (if (chatLabel.isBlank()) agent else chatLabel) + " · this chat only",
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ApprovalMode.entries.forEach { mode ->
                val selected = mode == current
                Button(
                    onClick = { onPick(mode) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (selected) MaterialTheme.colors.primary
                            else MaterialTheme.colors.surface.copy(alpha = 0.4f),
                        contentColor = if (selected) MaterialTheme.colors.onPrimary
                            else MaterialTheme.colors.onSurface,
                    ),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            (if (selected) "● " else "") + mode.label,
                            style = MaterialTheme.typography.button,
                        )
                        Text(
                            mode.hint,
                            style = MaterialTheme.typography.caption3,
                            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
