package dev.hermes.wearos.ui

import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.hermes.wearos.data.ChatMeta

/** Chats tab (SPEC §3): every local chat FOR THE ACTIVE AGENT (owner ask #1 — the list follows
 *  the profile), newest touched first; tap = continue, long-press = rename, "+" on top = new
 *  session, ⚙ at the bottom = settings. Right edge-swipe returns. */
@Composable
fun ChatsScreen(
    chats: List<ChatMeta>,
    agent: String = "",
    activeId: String,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onRename: (id: String, label: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val sorted = chats.sortedByDescending { it.updatedAt }
    var renaming by remember { mutableStateOf<ChatMeta?>(null) }
    var draft by remember { mutableStateOf("") }

    val edgeChatsState = androidx.wear.compose.foundation.lazy.rememberScalingLazyListState()
    val edgeScope = rememberCoroutineScope()
    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth()
            // BATCH-4 #7: edge-circle scrolling on every list surface.
            // BATCH-6 #29: row lists circle at 2f (see SettingsScreen).
            .edgeCircleScroll(speed = 2f) { total -> edgeScope.launch { edgeChatsState.edgeScrollWalk(total) } },
        state = edgeChatsState,
        contentPadding = roundListPadding(bottomExtraDp = 12f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                if (agent.isEmpty()) "Chats" else "Chats · $agent",
                style = MaterialTheme.typography.title3, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
        }
        item {
            Button(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth().size(width = 80.dp, height = 36.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.4f)),
            ) { Text("＋ new", style = MaterialTheme.typography.button) }
        }
        if (renaming != null) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Rename chat", style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.primary)
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true, // IME rule: no field depends on Enter behavior
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            renaming?.let { onRename(it.id, draft) }
                            renaming = null
                        }),
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colors.surface.copy(alpha = 0.5f))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        textStyle = MaterialTheme.typography.body2.copy(color = MaterialTheme.colors.onBackground),
                        cursorBrush = SolidColor(MaterialTheme.colors.primary),
                    )
                    Button(
                        onClick = {
                            renaming?.let { if (draft.isNotBlank()) onRename(it.id, draft) }
                            renaming = null
                        },
                        modifier = Modifier.size(width = 80.dp, height = 32.dp),
                    ) { Text("Save", style = MaterialTheme.typography.button) }
                }
            }
        }
        items(sorted, key = { it.id }) { meta ->
            ChatRow(
                meta = meta,
                active = meta.id == activeId,
                onClick = { if (renaming == null) onOpen(meta.id) },
                onLongClick = {
                    draft = meta.label
                    renaming = meta
                },
            )
        }
        item {
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth().size(width = 80.dp, height = 36.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.4f)),
            ) { Text("⚙ settings", style = MaterialTheme.typography.button) }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(meta: ChatMeta, active: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (active) MaterialTheme.colors.primary.copy(alpha = 0.22f)
                else MaterialTheme.colors.surface.copy(alpha = 0.25f)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (active) Text("●", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.primary)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                meta.label.ifEmpty { meta.sessionTitle },
                style = MaterialTheme.typography.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (active) MaterialTheme.colors.primary else MaterialTheme.colors.onBackground,
            )
            Text(
                relTime(meta.updatedAt),
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
            )
        }
    }
}

/** Tiny relative-time helper for the list ("now", "12m", "3h", "5d"). */
fun relTime(epochMs: Long): String {
    if (epochMs <= 0L) return "new"
    val d = System.currentTimeMillis() - epochMs
    return when {
        d < 60_000 -> "now"
        d < 3_600_000 -> "${d / 60_000}m ago"
        d < 86_400_000 -> "${d / 3_600_000}h ago"
        else -> "${d / 86_400_000}d ago"
    }
}
