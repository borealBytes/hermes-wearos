package dev.hermes.wearos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.hermes.wearos.domain.ChatAnchor
import dev.hermes.wearos.domain.ApprovalInfo
import dev.hermes.wearos.domain.ChatFollow
import dev.hermes.wearos.domain.ChatMessage
import dev.hermes.wearos.domain.ChatUiState
import dev.hermes.wearos.ui.md.MdMessage

@Composable
fun ChatScreen(
    state: ChatUiState,
    onApprove: (String) -> Unit,
    voiceEnabled: Boolean,
    voiceStatus: String = "",
    chatLabel: String = "",
    onOpenChats: () -> Unit = {},
    markdown: Boolean = true,
    // OWNER BATCH 2 (edit old prompts): long-press on a USER message opens the action sheet.
    onEditPrompt: (ChatMessage) -> Unit = {},
    // LAYOUT D: transcript bottom clearance = the bottom-cluster's top edge (px→dp by the caller)
    // so the LAST message can rest clear of the orb cluster; null keeps the chin-based default.
    contentPadBottomDp: Dp? = null,
    // Hoisted from MainActivity's ChatBody so the scroll SURVIVES the PTT overlay (if/else pair
    // leaves composition while listening) — walkthrough: "after stt talk button done, it scrolls
    // to the top of convo instead of staying at bottom".
    listState: ScalingLazyListState = rememberScalingLazyListState(),
    // TAIL-FOLLOW (owner 2026-10-29, policy in domain/ChatFollow): hoisted exactly like listState,
    // because the PTT overlay leaves this composition while listening — an un-hoisted flag would
    // reset to following the moment the owner's answer arrives, which is precisely when his
    // scroll position matters most.
    follow: MutableState<Boolean> = remember { mutableStateOf(true) },
    // PULL-UP-AT-THE-END (owner: *"scroll to the bottom and keep dragging up → refresh that
    // session from the gateway"*). The auto-sync does the everyday work; this is the manual door.
    onPullUpAtBottom: () -> Unit = {},
) {
    // PINCH FONT-SCALE: every type change re-runs the bottom anchor (text height changes shift
    // the tail) — same scroll machinery, toast-free.
    val typeScale = LocalFontScale.current
    // BOTTOM ANCHOR + WHO-OWNS-THE-VIEWPORT (owner 2026-10-29: *"keep the text that's coming out
    // just above the orb, so you can see as much scroll back as possible… by default, unless
    // you've scrolled"*). The policy is domain/ChatFollow; these are its two signals, wired:
    //
    //  • follow == true — the default, re-engaged by every NEW USER ASK and every chat switch —
    //    pushes the list's END on every content change: newest row just above the orb bar, all
    //    scroll-back resting ABOVE it where a swipe reaches it. The batch-7 mid-circle question
    //    pin (`starting`/qRow) is GONE, and it was the disease, not a symptom to keep: pinning
    //    the question at the list's Middle is exactly what shoved the previous rows off the top.
    //  • a USER scroll settles ownership (observer below): rest off the end ⇒ the stream keeps
    //    its hands off the transcript; rest at the end ⇒ the tail belongs to the app again.
    val lastChatKey = remember { mutableStateOf(state.activeChatId) }
    val lastAskCount = remember { mutableStateOf(state.messages.size) }
    LaunchedEffect(
        state.activeChatId, state.messages.size, state.live.length,
        state.busy, state.approval?.requestId, state.status, typeScale, follow.value,
    ) {
        val switched = state.activeChatId != lastChatKey.value
        if (switched) lastChatKey.value = state.activeChatId
        val newAsk = state.messages.size > lastAskCount.value &&
            state.messages.lastOrNull()?.role == ChatMessage.Role.User
        lastAskCount.value = state.messages.size
        if (switched || newAsk) follow.value = ChatFollow.reengaged()
        if (!follow.value) return@LaunchedEffect // manual mode: the stream holds its hands off
        val idx = ChatAnchor.bottomIndex(
            messageCount = state.messages.size,
            hasApproval = state.approval != null,
            hasLive = state.live.isNotEmpty(),
            hasStatus = state.status.isNotEmpty(),
            showEmptyHint = ChatAnchor.showEmptyHint(state.messages.size, state.live, state.approval != null),
        )
        if (state.approval != null) {
            // APPROVAL CENTERS (owner demo 2026-10-26): the card is the actionable row and the
            // list may already sit at its end (lazy tail) — the tail push can't lift a card that
            // was inserted ABOVE the viewport. scrollToItem(idx, 0) lands idx on the list's
            // center anchor = dead-center in the circle, every time (measured on-device).
            listState.scrollToItem(state.messages.size.coerceIn(0, listState.layoutInfo.totalItemsCount - 1), 0)
        } else if (idx > 0) {
            // THE anchor (owner walkthrough): latest row must be on screen at the list's end.
            // wear-compose 1.4.1 ScalingLazyColumn facts measured on-device: scrollToItem(last, 0)
            // lands short (lazy composition hasn't laid the tail out yet), and the true list end
            // = LAST item resting at the Middle anchor (auto-centering padding fills below it).
            // So: push with a viewport-sized offset (clamps to the end) and retry per frame while
            // the list still reports forward scroll available (bounded — never spins forever).
            val target = idx.coerceAtMost(listState.layoutInfo.totalItemsCount - 1)
            var tries = 0
            while (listState.canScrollForward && tries < 8) {
                // Oversized offset → clamps to the true list end (see comment above).
                listState.scrollToItem(target, 4096)
                withFrameNanos { }
                tries++
            }
            if (tries > 1) android.util.Log.d("HermesWear", "anchor idx=$idx tries=$tries (lazy tail)")
        }
    }
    // THE SECOND ChatFollow SIGNAL — who moved the list? Every scroll this screen itself makes is
    // an instantaneous scrollToItem, and those never raise isScrollInProgress; only user drags and
    // the flings they release do (wear-compose 1.4.1 exposes no interactionSource and no scroll
    // listener on ScalingLazyListState — this edge is the public surface that can tell them apart,
    // and it is the same surface that distinguishes a fling settling mid-list from one settling at
    // the end). So RISE-then-fall ⇒ the user's gesture came to rest, and `ahead` says where.
    // Documented limit: bezel/rotary travel without a touch shows no edge and keeps following —
    // fine on the SM-L705U, which has no rotating bezel.
    LaunchedEffect(listState) {
        var scrolling = false
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (now, ahead) ->
                if (scrolling && !now) follow.value = ChatFollow.afterUserScroll(ahead)
                scrolling = now
            }
    }
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().pointerInput(listState) {
            // OBSERVER-ONLY gesture watch (Initial pass, consumes nothing → the list scrolls and
            // the orb keeps its own lane). While the end is reached (no forward scroll left),
            // upward travel accumulates; past the threshold the gateway re-pull fires — once
            // per stroke.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var acc = 0f
                var fired = false
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val change = ev.changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    val up = change.previousPosition.y - change.position.y
                    if (up > 0f) {
                        if (listState.canScrollForward) acc = 0f
                        else if (!fired) {
                            acc += up
                            if (acc > 150f) { fired = true; onPullUpAtBottom() }
                        }
                    }
                }
            }
        },
        // Round discipline (ask #5): first item rests below the clock, last above the orb bar,
        // horizontal gutter tops rows' own padding up to the safe circle; SLC scaling does falloff.
        // LAYOUT D: the chat passes the measured cluster top edge as the bottom clearance.
        contentPadding = roundListPadding(bottomExtraDp = 6f, bottomOverrideDp = contentPadBottomDp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            ChatCaption(voiceEnabled, voiceStatus, chatLabel)
        }
        if (state.messages.isEmpty() && state.live.isEmpty() && state.approval == null) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
                        .clickable(onClick = onOpenChats),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Ask Hermes", style = MaterialTheme.typography.title2, textAlign = TextAlign.Center)
                    Text("tap the orb to talk", style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f))
                }
            }
        }
        items(state.messages, key = { it.id }) { msg -> MessageLine(msg, markdown, onEditPrompt) }
        if (state.approval != null) {
            item { ApprovalCard(state.approval, onApprove) }
        }
        if (state.live.isNotEmpty()) {
            item {
                val live = state.live + (if (state.busy) " ▉" else "")
                if (markdown) {
                    // Partial markdown parsed every delta: unclosed fence → code block, unfinished
                    // markers stay literal, so the buffer never shows a backtick storm (F4).
                    MdMessage(live, msgKey = "live")
                } else Text(
                    live,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.body2.scaled(),
                )
            }
        }
        if (state.status.isNotEmpty()) {
            item {
                Text(
                    state.status,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.caption2.scaled(),
                    color = Color(0xFFF5C518),
                )
            }
        }
    }
}

/**
 * ONE PASSIVE CAPTION LINE (owner batch 3: "would it simplify the interface even more to get all
 * but the orb button off the main screen?"). The mute chip and the chat-name chip are GONE — mute
 * is the dial's 1:30 lane and chats is 7:30 — but the state they carried still has to be READABLE:
 * speak-aloud state (the same glyph the ring shows, so the dial and the caption agree), the chat
 * name, and any voice/TTS warning in the amber the old chip used. No touch target: tapping here
 * scrolls the transcript like any other text, which is what a caption should do.
 */
@Composable
private fun ChatCaption(
    enabled: Boolean,
    status: String,
    chatLabel: String,
) {
    // The caption is item 0 of a Middle-anchored SLC: contentPadding can't keep it below the clock
    // when content is short, so it carries the top inset itself (round ask #5).
    val insets = roundInsets()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = insets.topDp.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildString {
                append(if (enabled) "🔊 " else "🔇 ")
                append(chatLabel.ifEmpty { "·" })
            },
            style = MaterialTheme.typography.caption3,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.62f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (status.isNotEmpty()) {
            Text(
                status,
                style = MaterialTheme.typography.caption3,
                color = Color(0xFFF5C518),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageLine(msg: ChatMessage, markdown: Boolean, onEditPrompt: (ChatMessage) -> Unit) {
    when (msg.role) {
        ChatMessage.Role.User -> {
            // OWNER BATCH 2 (edit old prompts): the user's own messages are the one thing on the
            // watch they can act on — long-press opens the action sheet ("click on my old prompts
            // and hold with an edit option like desktop has"). combinedClickable: tap does nothing
            // special (no resend-to-compose surprise), long-press is the affordance.
            Text(
                msg.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp)
                    .combinedClickable(
                        onClick = { onEditPrompt(msg) },
                        onLongClick = { onEditPrompt(msg) },
                    ),
                style = MaterialTheme.typography.body2.scaled(),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colors.primary,
            )
        }
        ChatMessage.Role.System -> Text(
            msg.text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
            style = MaterialTheme.typography.caption2.scaled(),
            color = Color(0xFFE57373),
        )
        ChatMessage.Role.Hermes -> if (markdown) {
            // Parse-then-render (F3/F4); user messages stay plain bold lines by design.
            MdMessage(msg.text, msgKey = msg.id)
        } else Text(
            msg.text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.body2,
        )
    }
}

@Composable
private fun ApprovalCard(info: ApprovalInfo, onApprove: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("⚠ Approve?", style = MaterialTheme.typography.body1, color = Color(0xFFF5C518))
        Text(info.question, style = MaterialTheme.typography.caption1, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val approveName = info.choices.firstOrNull { !it.contains("deny", true) && !it.equals("no", true) } ?: "approve"
            val denyName = info.choices.firstOrNull { it.contains("deny", true) || it.equals("no", true) } ?: "deny"
            Button(
                onClick = { onApprove(approveName) },
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
            ) { Text("OK") }
            Button(
                onClick = { onApprove(denyName) },
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF7F1D1D)),
            ) { Text("No") }
        }
    }
}
