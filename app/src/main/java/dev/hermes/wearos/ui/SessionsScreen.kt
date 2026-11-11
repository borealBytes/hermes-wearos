package dev.hermes.wearos.ui

import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.hermes.wearos.domain.ProfileLogic
import dev.hermes.wearos.domain.SessionHit
import dev.hermes.wearos.domain.SessionsMeta
import dev.hermes.wearos.net.GatewaySessions

/**
 * Gateway sessions fast-path (owner ask #2 → cross-profile upgrade): "continue my computer chats
 * from the watch" — the list carries the CURRENT agent's gateway sessions AND the DESKTOP
 * (`default`) profile's, newest first, filterable by profile chips (all / agents / 💻 computer).
 * The watch's own sessions (ids `<profile>-watch…`) keep the ⌚ marker. **NEW SESSION is pinned as
 * the very first row** (owner asked twice): fresh chat for the current agent, jumps right in.
 * Tapping a foreign-profile row adopts it: agent swap + session resume (crash-safe — the swap's
 * callback toasts via Graph.runOnUi). Offline: the merged cache stays readable with a hint.
 */
@Composable
fun SessionsScreen(
    agent: String,
    sessions: List<GatewaySessions.Info>,
    meta: SessionsMeta,
    filter: String,
    onFilter: (String) -> Unit,
    onNewSession: () -> Unit,
    onOpen: (sessionId: String, title: String, profile: String) -> Unit,
    onRefresh: () -> Unit,
    // SEARCH (SPEC §12.4): the box is always present; `hits` is the merged local+gateway answer
    // owned by ChatSession.searchSessions (voice dictation lands through the same setter the
    // keyboard uses — the search code cannot tell the two input routes apart, by design).
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    hits: List<SessionHit> = emptyList(),
    voiceOn: Boolean = false,
    onVoiceStart: () -> Unit = {},
    onVoiceStop: () -> Unit = {},
    onOpenLocal: (chatId: String) -> Unit = {},
    // LONG-PRESS (owner: "hold a session — fork or delete, delete double-checked"): the sheet +
    // its confirm live in MainActivity (they touch session + screen state); this file only asks.
    onManage: (GatewaySessions.Info) -> Unit = {},
    // ARCHIVED rows (owner batch 5: "archive instead of delete"): their own fetch, their own chip
    // — the chip only exists while there is something to bring back.
    archived: List<GatewaySessions.Info> = emptyList(),
    // CRON RUNS (pairing v2 / D14): every outbox alert opens INTO its run; the ⏰ chip lists
    // those runs (watchkit ?source=cron) — same own-fetch/fail-soft rule as 🗃.
    cron: List<GatewaySessions.Info> = emptyList(),
) {
    val shown = when (filter) {
        GatewaySessions.FILTER_ARCHIVED -> archived
        GatewaySessions.FILTER_CRON -> cron
        else -> GatewaySessions.filterProfile(sessions, filter)
    }
    val chips = buildList {
        add(GatewaySessions.FILTER_ALL)
        addAll(GatewaySessions.profilesIn(sessions))
        if (cron.isNotEmpty()) add(GatewaySessions.FILTER_CRON)
        if (archived.isNotEmpty()) add(GatewaySessions.FILTER_ARCHIVED)
    }
    val edgeSessionState = androidx.wear.compose.foundation.lazy.rememberScalingLazyListState()
    val edgeScope = rememberCoroutineScope()
    val edgeSessionScroll: (Float) -> Unit = { total -> edgeScope.launch { edgeSessionState.edgeScrollWalk(total) } }
    ScalingLazyColumn(
        modifier = Modifier.fillMaxWidth()
            // BATCH-4 #7: edge-circle scrolling on every list surface. BATCH-6 #28 owner:
            // "sessions faster with bezel scroll" — a dense full-width list wants two screens
            // per circle, not the chat's one-screen reading gear.
            .edgeCircleScroll(speed = 2f) { total -> edgeSessionScroll(total) },
        state = edgeSessionState,
        contentPadding = roundListPadding(bottomExtraDp = 16f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── NEW SESSION, pinned at the very top (owner asked twice) ──────────
        item {
            Button(
                onClick = onNewSession,
                modifier = Modifier.fillMaxWidth().size(width = 90.dp, height = 36.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.30f)),
            ) { Text("＋ new session", style = MaterialTheme.typography.button) }
        }
        val searching = query.isNotBlank()
        item {
            VoiceOrTypeField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "search all chats…",
                onSend = onQueryChange,
                voiceOn = voiceOn,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (searching) {
            item {
                Text(
                    "Search · ${hits.size}",
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (hits.isEmpty()) {
                item {
                    Text(
                        "nothing matches in $agent (or offline) — ✕ to browse",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
            items(hits, key = { it.source.name + ":" + it.id }) { h ->
                SearchHitRow(
                    hit = h,
                    onClick = {
                        // A WATCH hit is a local chat: opens instantly, offline included. A
                        // GATEWAY hit takes the adoption path the list rows already use.
                        if (h.watchChatId.isNotEmpty()) onOpenLocal(h.watchChatId)
                        else onOpen(h.id, h.title, h.profile)
                    },
                    onManage = { onManage(GatewaySessions.Info(h.id, h.title, 0L, 0, h.profile)) },
                )
            }
            return@ScalingLazyColumn
        }
        item {
            Text(
                "Sessions · ${shown.size}",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Profile filter chips: all + every profile present (💻 computer = `default`).
        if (chips.size > 1) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    chips.forEach { c ->
                        val sel = c == filter
                        Text(
                            when (c) {
                                GatewaySessions.FILTER_ALL -> "all"
                                GatewaySessions.FILTER_ARCHIVED -> "🗃 archived"
                                GatewaySessions.FILTER_CRON -> "⏰ cron"
                                else -> ProfileLogic.chipLabel(c)
                            },
                            style = MaterialTheme.typography.caption3,
                            color = if (sel) MaterialTheme.colors.primary else MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(
                                    if (sel) MaterialTheme.colors.primary.copy(alpha = 0.25f)
                                    else MaterialTheme.colors.surface.copy(alpha = 0.35f),
                                )
                                .clickable(onClickLabel = "filter sessions") { onFilter(c) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        if (meta.stale) {
            item {
                Text(
                    "cached · ${relTime(meta.fetchedAtMs)} — pull refresh to retry",
                    style = MaterialTheme.typography.caption3,
                    color = Color(0xFFF5C518),
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    when {
                        filter == GatewaySessions.FILTER_ARCHIVED -> "nothing archived 🗃"
                        filter == GatewaySessions.FILTER_CRON -> "no cron runs yet ⏰"
                        meta.stale -> "no cached list yet — refresh once online"
                        else -> "no sessions yet"
                    },
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
        items(shown, key = { it.id }) { s ->
            SessionRow(
                s,
                currentAgent = agent,
                onClick = { onOpen(s.id, s.title, s.profile) },
                onManage = { onManage(s) },
            )
        }
        item {
            Button(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth().size(width = 90.dp, height = 36.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.4f),
                ),
            ) { Text("↻ refresh", style = MaterialTheme.typography.button) }
        }
    }
}

/**
 * One search answer: title, then WHY it matched — the FTS snippet arrives with the hit words in
 * [brackets] from the server, local hits get a preview from SessionSearch.preview — so the wrist
 * recognizes the chat instead of trusting a name. The trailing tag is provenance: ⌚ a chat this
 * watch owns (opens instantly), otherwise which profile's store it lives in.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SearchHitRow(hit: SessionHit, onClick: () -> Unit, onManage: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colors.surface.copy(alpha = 0.25f))
            .combinedClickable(onClick = onClick, onLongClick = onManage)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (hit.source == SessionHit.Source.WATCH) Text("⌚", style = MaterialTheme.typography.caption2)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                hit.title.ifBlank { hit.id },
                style = MaterialTheme.typography.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (hit.source == SessionHit.Source.WATCH) MaterialTheme.colors.primary
                    else MaterialTheme.colors.onBackground,
            )
            val sub = buildString {
                append(hit.snippet.ifBlank { "no preview" })
                append("  ·  ")
                append(
                    if (hit.source == SessionHit.Source.WATCH) "watch"
                    else ProfileLogic.chipLabel(hit.profile),
                )
                if (hit.hits > 1) append(" ×").append(hit.hits)
            }
            Text(
                sub,
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    s: GatewaySessions.Info,
    currentAgent: String,
    onClick: () -> Unit,
    onManage: () -> Unit = {},
) {
    val watchOwn = ProfileLogic.isWatchSession(s.id)
    val foreign = s.profile.isNotEmpty() && s.profile != currentAgent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colors.surface.copy(alpha = 0.25f))
            .combinedClickable(onClickLabel = "open session", onClick = onClick, onLongClick = onManage)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (watchOwn) Text("⌚", style = MaterialTheme.typography.caption2)
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                s.title.ifBlank { s.id },
                style = MaterialTheme.typography.body2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (watchOwn) MaterialTheme.colors.primary else MaterialTheme.colors.onBackground,
            )
            Text(
                buildString {
                    if (foreign) append(ProfileLogic.chipLabel(s.profile)).append(" · ")
                    append(relTime(s.lastActiveMs)).append(" · ").append(s.messages).append(" msg · ")
                    append(s.id.take(18))
                },
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * LONG-PRESS SHEET for a session row (owner: "click and hold on the session itself … fork or
 * delete as options"). Same full-bleed dialog shape as `PromptActionSheet` (wear Buttons only).
 * Fork copies INTO the current agent and opens the branch (see ChatSession.forkBrowserSession) —
 * the button says so, because "fork" of a desktop chat is only interesting if you can keep
 * talking in it.
 */
@Composable
fun SessionActionSheet(
    title: String,
    profileTag: String,
    onFork: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    isArchived: Boolean = false,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(top = 32.dp, bottom = 16.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "“${title.take(48)}${if (title.length > 48) "…" else ""}”",
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            if (profileTag.isNotBlank()) {
                Text(
                    "in $profileTag",
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.4f),
                )
            }
            Button(
                onClick = onFork,
                modifier = Modifier.size(width = 200.dp, height = 38.dp),
            ) { Text("⑂ fork & open", style = MaterialTheme.typography.button) }
            // ARCHIVE is the polite verb (owner batch 5: "archive instead of delete") — hide from
            // the lists, undo lives on the wrist via the 🗃 chip. On an archived row the same
            // button brings it back.
            Button(
                onClick = onArchive,
                modifier = Modifier.size(width = 200.dp, height = 38.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.28f),
                ),
            ) { Text(if (isArchived) "♻️ unarchive" else "🗃 archive", style = MaterialTheme.typography.button) }
            Button(
                onClick = onDelete,
                modifier = Modifier.size(width = 200.dp, height = 30.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.error.copy(alpha = 0.25f),
                ),
            ) { Text("🗑 delete…", style = MaterialTheme.typography.caption2) }
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

/**
 * THE DOUBLE CHECK (owner: "delete should double check"). Says plainly what dies: the session and
 * every message in it, off the gateway box too — no undo, because there is no trash can in this
 * design. Confirm is the far button; a mis-tap hits "keep".
 */
@Composable
fun SessionDeleteSheet(
    title: String,
    profileTag: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(top = 28.dp, bottom = 16.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "delete “${title.take(40)}${if (title.length > 40) "…" else ""}”?",
                style = MaterialTheme.typography.button,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Text(
                "every message goes with it" + (if (profileTag.isNotBlank()) " in $profileTag" else "") +
                    " — no undo",
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Button(
                onClick = onConfirm,
                modifier = Modifier.size(width = 200.dp, height = 38.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.error.copy(alpha = 0.75f),
                ),
            ) { Text("🗑 delete forever", style = MaterialTheme.typography.button) }
            Button(
                onClick = onDismiss,
                modifier = Modifier.size(width = 120.dp, height = 32.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.35f),
                ),
            ) { Text("keep", style = MaterialTheme.typography.button) }
        }
    }
}
