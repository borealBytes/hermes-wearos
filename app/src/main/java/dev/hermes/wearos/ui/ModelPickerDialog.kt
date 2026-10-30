package dev.hermes.wearos.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.hermes.wearos.domain.ModelCatalog

/**
 * MODEL PICKER (owner: notes lane → *"the model selector … it'll just remember that model within
 * that session … only models enabled in the gateway"*). TWO-STAGE on purpose: an enabled catalog
 * here is 7 providers × up to 62 models — one 170-row scroll is a wrist-killer, so the first
 * screen is providers (always a handful), the second the models of one. Top row is always
 * "gateway default" — picking it un-pins the chat (new-session behavior resumes immediately).
 *
 * Same full-bleed black dialog shape as AgentPickerDialog: round-safe paddings, vertical scroll,
 * wear Buttons only, ● marks what this chat will actually run.
 */
@Composable
fun ModelPickerDialog(
    picked: String,                    // "" = gateway default (un-pinned)
    defaultModel: String,              // gateway configured default name for the subtitle row
    groups: List<ModelCatalog.Group>,  // enabled providers (already current-first)
    loading: Boolean,
    error: String,
    agent: String,
    onPick: (String) -> Unit,          // model id, or "" for gateway default
    onDismiss: () -> Unit,
) {
    var openProvider by remember { mutableStateOf<String?>(null) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Bounded full-screen box with the scroll INSIDE: a bare scrollable Column inside a
        // wrap-content dialog window reports its natural (taller-than-the-circle) height and the
        // window then CENTERS it — the title scrolls off before the list even appears.
        // BATCH-5 #18: the edge-circle gesture lives HERE (viewport-sized), never on the
        // scrolled Column — the column node is content-tall, so the ring ellipse was wrong.
        val edgeScope = rememberCoroutineScope()
        val edgeDlgScroll = androidx.compose.foundation.rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .edgeCircleScroll { total -> edgeScope.launch { edgeDlgScroll.scrollBy(total) } },
        ) {
        Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                .verticalScroll(edgeDlgScroll)
                    // Owner correction (batch-4 #6): buffer = blank content lines INSIDE the
                    // scroll (title clears the clock at rest, last row rests on the rim line).
                    .padding(top = 34.dp, bottom = circleSafeBottomDp(12f, 10f).dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            Text("Model", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
            Text(
                "on $agent · this chat only",
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (loading) {
                Text(
                    "checking gateway…",
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
                )
            }
            if (error.isNotEmpty()) {
                Text(
                    error,
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.error,
                    textAlign = TextAlign.Center,
                )
            }
            val provider = openProvider?.let { slug -> groups.firstOrNull { it.provider == slug } }
            if (provider == null) {
                // ── stage 1: default + providers ──
                PickerRow(
                    label = if (defaultModel.isNotBlank()) "default · ${ModelCatalog.shortName(defaultModel)}" else "gateway default",
                    selected = picked.isEmpty(),
                    onClick = { onPick("") },
                )
                groups.forEach { g ->
                    PickerRow(
                        label = "${g.name} · ${g.models.size}",
                        selected = picked in g.models || (picked.isEmpty() && g.isCurrent && defaultModel.isBlank()),
                        onClick = { openProvider = g.provider },
                    )
                }
                if (!loading && groups.isEmpty() && error.isEmpty()) {
                    Text(
                        "no enabled models on this gateway",
                        style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                // ── stage 2: one provider's models ──
                PickerRow(label = "← providers", selected = false, onClick = { openProvider = null })
                provider.models.forEach { m ->
                    PickerRow(label = ModelCatalog.shortName(m), selected = m == picked, onClick = { onPick(m) })
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
}

/** One pill row — same button geometry as AgentPickerDialog's rows. */
@Composable
private fun PickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .size(width = 140.dp, height = 38.dp),
        colors = if (selected) ButtonDefaults.buttonColors()
        else ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.35f)),
    ) {
        Text(
            if (selected) "● $label" else label,
            style = MaterialTheme.typography.button,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}
