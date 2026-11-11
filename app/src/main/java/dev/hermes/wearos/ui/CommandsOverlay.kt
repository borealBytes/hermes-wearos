package dev.hermes.wearos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.hermes.wearos.domain.CommandEntry
import dev.hermes.wearos.domain.CommandGroup
import dev.hermes.wearos.domain.CommandKind
import dev.hermes.wearos.domain.SlashCommands

/**
 * THE SLASH OVERLAY (SPEC §12.3, dial 6:00) — owner: "drag it straight down … pop up with an
 * overlay of all the slash commands that I can use … make it scrollable … remember the ones I use
 * most". Sections, in order: RECENTS (usage-ranked, `CommandUsage.topRecents`), COMMANDS, SKILLS
 * (the agent's live list), WATCH (client actions). A row carries its invocation, so a chosen entry
 * can prefill args instead of firing blind.
 *
 * Capability gating is HONEST: a row whose `requires` gate the gateway doesn't advertise is dimmed
 * with the reason, never hidden — so `/steer` still shows on a gateway that lacks `run_steer` and
 * says it cannot run here (the owner needs to see why, not find a missing command).
 *
 * Voice-or-type: the filter box is the shared `VoiceOrTypeField`, so the query can be dictated with
 * Vosk or typed, and `honored = Prompt` rows send the typed text as a prompt (that is the honest
 * bucket for CLI-only commands).
 */
data class CommandOverlayState(
    val entries: List<CommandEntry>,
    /** Usage-ranked names (overlay RECENTS section). */
    val recents: List<String> = emptyList(),
    /** Capability gates from `/v1/capabilities` — null value means "unknown", which shows enabled. */
    val capabilities: Map<String, Boolean> = emptyMap(),
)

@Composable
fun CommandsOverlay(
    state: CommandOverlayState,
    /** A draft the wrist already typed (`/st…`) or a dictated query — seeds the filter. */
    prefill: String? = null,
    onPick: (CommandEntry) -> Unit,
    onPrompt: (String) -> Unit,
    onDismiss: () -> Unit,
    voiceOn: Boolean = false,
    onVoiceStart: () -> Unit = {},
    onVoiceStop: () -> Unit = {},
    modifier: Modifier = Modifier,
) {

    // #24 TRACE: self-name on open — the WindowManager addView line gets a witness.
    androidx.compose.runtime.LaunchedEffect(Unit) { android.util.Log.i("HermesWear", "sheet-open CommandsOverlay") }
    var query by remember { mutableStateOf(prefill?.removePrefix("/") ?: "") }
    androidx.compose.runtime.LaunchedEffect(prefill) {
        prefill?.let { query = it.removePrefix("/") }
    }
    val now = remember { System.currentTimeMillis() }
    val byName = state.entries.associateBy { it.name }
    val recentRows = state.recents.mapNotNull { byName[it] }
    val filtered = SlashCommands.matches(query, state.entries)
    val sections = remember(filtered, recentRows, query, state.capabilities) {
        buildList {
            if (query.isBlank() && recentRows.isNotEmpty()) {
                add("RECENTS" to recentRows)
            }
            val rest = filtered.filter { !(query.isBlank() && it in recentRows) }
            val commands = rest.filter { it.group == CommandGroup.Commands || it.group == CommandGroup.Recents }
            val skillsAll = rest.filter { it.group == CommandGroup.Skills }
            val skills = SlashCommands.skillSection(skillsAll, query)
            val client = rest.filter { it.group == CommandGroup.Client }
            if (commands.isNotEmpty()) add("COMMANDS" to commands)
            if (skills.isNotEmpty()) {
                // The count lives in the TITLE: an agent can carry 180+ skills and a 480-face watch
                // does not show a 200-row menu — typing narrows to the one you mean, which is how a
                // skill gets invoked anyway (SPEC §12.3).
                val more = skillsAll.size - skills.size
                add((if (more > 0) "SKILLS · +$more more — type to filter" else "SKILLS · this agent") to skills)
            }
            if (client.isNotEmpty()) add("WATCH" to client)
        }
    }
    val fm = LocalFontScale.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            .padding(top = 8.dp, bottom = 6.dp, start = 8.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "⌘ commands",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.caption2.copy(fontSize = (12 * fm).sp),
                color = MaterialTheme.colors.primary,
                maxLines = 1,
            )
            Text(
                "✕",
                modifier = Modifier.clickable(onClick = onDismiss).padding(4.dp),
                style = MaterialTheme.typography.caption1,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
        VoiceOrTypeField(
            value = query,
            onValueChange = { query = it },
            placeholder = "search or /text as prompt",
            onSend = { text ->
                val t = text.trim()
                if (t.isEmpty()) return@VoiceOrTypeField
                val hit = SlashCommands.matches(t, state.entries).firstOrNull()
                if (hit != null && t.removePrefix("/").trim().equals(hit.name, ignoreCase = true)) onPick(hit)
                else onPrompt(t)
            },
            voiceOn = voiceOn,
            onVoiceStart = onVoiceStart,
            onVoiceStop = onVoiceStop,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            // BATCH-4 #6: the sheet's last command must scroll clear of the rim band too.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 4.dp, bottom = 26.dp, start = 10.dp, end = 10.dp,
            ),
        ) {
            sections.forEach { (title, rows) ->
                item {
                    Text(
                        title,
                        modifier = Modifier.padding(top = 4.dp, start = 2.dp),
                        style = MaterialTheme.typography.caption3.copy(fontSize = (10 * fm).sp),
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                    )
                }
                items(rows, key = { it.name }) { entry ->
                    CommandRow(
                        entry = entry,
                        enabled = entry.requires == null || state.capabilities[entry.requires] != false,
                        onClick = { onPick(entry) },
                    )
                }
            }
            if (sections.isEmpty()) {
                item {
                    Text(
                        "nothing matches — send it as a prompt?",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                    )
                }
            }
            item { Text("", style = MaterialTheme.typography.caption3) } // last-row clearance for the chip row
        }
    }
}

@Composable
private fun CommandRow(entry: CommandEntry, enabled: Boolean, onClick: () -> Unit) {
    val fm = LocalFontScale.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colors.surface.copy(alpha = if (enabled) 0.55f else 0.25f),
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                buildString {
                    append("/").append(entry.name)
                    if (entry.argsHint.isNotEmpty()) {
                        append(" "); append(entry.argsHint)
                    }
                },
                style = MaterialTheme.typography.body2.copy(fontSize = (13.5f * fm).sp),
                color = if (enabled) MaterialTheme.colors.onBackground else MaterialTheme.colors.onBackground.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val note = when {
                entry.requires != null && !enabled -> "${entry.requires} not available here"
                entry.help.isNotBlank() -> entry.help
                entry.source.isNotBlank() -> entry.source
                else -> null
            }
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.caption3.copy(fontSize = (9.5f * fm).sp),
                    color = if (entry.requires != null && !enabled) MaterialTheme.colors.error.copy(alpha = 0.85f)
                        else MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // How the watch honors it — the honesty marker the owner can learn from (SPEC §12.3).
        Text(
            when (entry.honored) {
                CommandKind.Steer -> "inject"
                CommandKind.Side -> "fork"
                CommandKind.Fork -> "branch"
                CommandKind.Stop -> "stop"
                CommandKind.Model -> "model"
                CommandKind.Skill -> "skill"
                CommandKind.Client -> "local"
                CommandKind.Status -> "local"
                CommandKind.Prompt -> "prompt"
                CommandKind.None -> ""
            },
            style = MaterialTheme.typography.caption3.copy(fontSize = (9 * fm).sp),
            color = MaterialTheme.colors.primary.copy(alpha = 0.8f),
        )
    }
}
