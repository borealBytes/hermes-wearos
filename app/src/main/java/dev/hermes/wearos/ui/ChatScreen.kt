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
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import kotlin.math.roundToInt
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
    // Batch-4 #3: full viewport width (px) so the 10-o'clock line is the real face's, not the
    // SLC column's. 0 = fall back to the column's own height (square faces are identical).
    viewportW: Float = 0f,
    // Hoisted from MainActivity's ChatBody so the scroll SURVIVES the PTT overlay (if/else pair
    // leaves composition while listening) — walkthrough: "after stt talk button done, it scrolls
    // to the top of convo instead of staying at bottom".
    listState: ScalingLazyListState = rememberScalingLazyListState(),
    // TAIL-FOLLOW (owner 2026-10-29, policy in domain/ChatFollow): hoisted exactly like listState,
    // because the PTT overlay leaves this composition while listening — an un-hoisted flag would
    // reset to following the moment the owner's answer arrives, which is precisely when his
    // scroll position matters most.
    follow: MutableState<Boolean> = remember { mutableStateOf(true) },
    // KARAOKE's "WE MOVED IT" STAMP (owner 2026-10-29 night): the follow-disengage edge detector
    // below reads any RISE-then-fall of isScrollInProgress as the user's gesture — but wear-
    // compose raises that flag for programmatic scrollToItem too (measured: an animated karaoke
    // nudge AND an instant one each froze tail-follow on its first jump). The karaoke effect
    // stamps this timestamp right before its own jump; edges settling within the grace window are
    // ours, not the wrist's. User flings last well past 400 ms, so the guard never eats a real
    // scroll-away except one landing inside that window — and landing at the end re-engages anyway.
    programmaticScrollAt: MutableState<Long> = remember { mutableStateOf(0L) },
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
    //  • BOTTOM-ANCHOR (owner 2026-10-29: *"it starts streaming from the top of the thing
    //    instead of just above the orb"*): when the WHOLE transcript fits the circle, wear's
    //    list end parks the last item at the MIDDLE anchor — which shoves short content off
    //    the TOP. So a top spacer (item 0) eats the exact slack instead and the list rests at
    //    offset 0: the newest line sits JUST ABOVE THE ORB and the reply grows UPWARD (maximum
    //    scroll-back while waiting, same intent as ChatFollow). Content that does not fit
    //    collapses the spacer to 0 and the classic tail-pin owns the view. The measurement is
    //    the layout's own truth — ONE clean frame with every row visible sums the heights —
    //    so no font/markdown math can drift.
    val anchorDensity = androidx.compose.ui.platform.LocalDensity.current
    // Measured chrome for the question-lift geometry (batch-4 #3): the SLC's own top content
    // padding, its item gap, AND where the list actually starts in the window — the Scaffold's
    // clock band offsets it ~90 px, which the first version assumed away (the 10:00 tick then
    // computed perfectly while the question rendered 70 px lower on the glass).
    val listTopPadPx = 0f  // = the topOverrideDp below: the chat deliberately flows full-bleed
    // (its true clearance comes from the bottom-cluster pad + the spacer math, and the whole
    // transcript was tuned that way; the now-truthful round insets would push every row 64 px
    // down and re-wrap the lines — the list-inset retune is batch item 6, per surface).
    val listGapPx = with(anchorDensity) { 6.dp.toPx() }
    var slcTopPx by remember { mutableStateOf(0f) }
    var anchorPadPx by remember { mutableStateOf(0f) }
    var slcH by remember { mutableStateOf(0) }
    val bottomPadPx = with(anchorDensity) { (contentPadBottomDp ?: 82.dp).toPx() } +
        // the transcript must REST clear of the orb crown, not flush against the circle — the
        // round shoulders make a flush rest look like the text slides under the orb.
        with(anchorDensity) { 10.dp.toPx() }
    val lastChatKey = remember { mutableStateOf(state.activeChatId) }
    val lastAskCount = remember { mutableStateOf(state.messages.size) }
    LaunchedEffect(
        state.activeChatId, state.messages.size, state.live.length,
        state.busy, state.approval?.requestId, state.status, typeScale, follow.value, slcH,
    ) {
        val switched = state.activeChatId != lastChatKey.value
        if (switched) lastChatKey.value = state.activeChatId
        val newAsk = state.messages.size > lastAskCount.value &&
            state.messages.lastOrNull()?.role == ChatMessage.Role.User
        lastAskCount.value = state.messages.size
        if (switched || newAsk) follow.value = ChatFollow.reengaged()
        if (!follow.value) return@LaunchedEffect // manual mode: the stream holds its hands off
        // Wait (bounded) for a frame the list has FULLY composed — wear lays the tail lazily,
        // and a chat opened mid-transition reports an empty/stale layoutInfo. Without a whole
        // frame there is no truthful content height to pad against; the classic rocket is the
        // fallback when composition stays lazy (true overflow shows the last item quickly).
        var tries = 0
        var info = listState.layoutInfo
        var vis = info.visibleItemsInfo
        while (tries < 8 && (vis.isEmpty() || vis.last().index < info.totalItemsCount - 1 || slcH <= 0)) {
            withFrameNanos { }
            info = listState.layoutInfo
            vis = info.visibleItemsInfo
            tries++
        }
        val first = vis.firstOrNull()
        // The content height WITHOUT the spacer: valid only when one frame shows every row
        // (zero-height spacer is omitted by wear — hence the first().index±1 bookkeeping).
        val last = vis.lastOrNull()
        val allShown = first != null && last != null &&
            first.index <= 1 && last.index == info.totalItemsCount - 1 &&
            last.index - first.index + 1 == vis.size &&
            vis.size == info.totalItemsCount - (if (first.index == 0) 0 else 1)
        val contentH = if (allShown && slcH > 0)
            vis.sumOf { it.size } - (if (first!!.index == 0) first.size else 0) else -1
        val idleEmpty = state.messages.isEmpty() && state.live.isEmpty()
        val slack = if (contentH > 0f && !idleEmpty && state.approval == null)
            (slcH - bottomPadPx - contentH) else 0f
        anchorPadPx = if (slack > 1f) slack else 0f
        // BATCH-4 #3 (owner: *"when you submit a request, it's okay for your question to start at
        // that 10 o'clock spot… that should be the top corner"*): the beat right after SEND the
        // question is the newest row and nothing answers it yet — retune the spacer so its TOP
        // edge rests exactly on the 10-o'clock tick line (wide-open rim up there, nothing to
        // clip), leaving the whole lower circle as the answer's runway. The first streamed delta
        // flips this back to the orb-crown rule and the reply grows upward from the 8:20 corner,
        // sliding the question back up past the tick exactly as it fills.
        // THE OFFSET MUST BE TRUTHFUL, AND THE FIX MUST BE A FIXED POINT. The first version
        // one-step-corrected from the row's measured offset — which OSCILLATES (a lifted frame
        // measures above the tick and relaxes the pad back; caught on the SM-L705U as the
        // question wobbling between two heights), and lazy wear frames report not-yet-laid rows
        // at offset −1, where lifting adds a viewport of spacer and the chat falls off screen
        // (black transcript mid-stream, also caught on-device). Direct geometry instead: the
        // spacer that lands the question's TOP on the tick is exactly
        // (tick − listTopPad − header − item gap) — all measured terms, none depends on the
        // current spacer. Clamped to [0, slack]: never above the tick, never below the plain
        // orb-crown rest. The state is "newest row is USER and nothing has streamed for it yet",
        // not the newAsk transition — the transition is true only in the one lazy frame the
        // guards must refuse.
        val awaitingAnswer = state.messages.lastOrNull()?.role == ChatMessage.Role.User &&
            state.live.isEmpty()
        // Direct geometry undercounts the header chrome (measured: formula said 120, the row
        // rendered at 258). MEASURE instead: while resting at the list top, the row's container
        // offset is its distance below the viewport top (content padding + spacer + header all
        // folded in), so shrinking the spacer by exactly (rowTop − tick) lands the question's
        // TOP-LEFT on the 10:00 line. Stable at its result (next pass measures tenY, delta 0);
        // re-decides as the header grows mid-composition.
        val qRow = vis.firstOrNull { it.index == state.messages.size + 1 }
        if (awaitingAnswer && slack > 1f && state.approval == null && qRow != null &&
            qRow.offset >= 0 && slcH > 0 && !listState.canScrollBackward
        ) {
            val tenY = dev.hermes.wearos.domain.RoundScreen.tenOclockY(
                if (viewportW > 0f) viewportW else slcH.toFloat(), slcH.toFloat(),
            )
            val qTopGlobal = slcTopPx + qRow.offset
            val lift = (qTopGlobal - tenY).coerceAtLeast(0f)
            if (lift > 1f) anchorPadPx = (anchorPadPx - lift).coerceIn(0f, slack)
            android.util.Log.d(
                "HermesWear", "q-lift pad=${anchorPadPx.toInt()} qTop=${qTopGlobal.toInt()} " +
                    "tenY=${tenY.toInt()} slcTop=${slcTopPx.toInt()} slack=${slack.toInt()}",
            )
        }
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
            listState.scrollToItem((state.messages.size + 1).coerceIn(0, listState.layoutInfo.totalItemsCount - 1), 0)
        } else if (slack > 1f) {
            // SHORT TRANSCRIPT: the spacer fills to exactly the cluster top — rest at the list
            // top so the whole thing (spacer included) is on screen, newest just above the orb.
            listState.scrollToItem(0, 0)
        } else if (idx > 0) {
            // THE tail-pin (owner walkthrough): latest row must be on screen at the list's end.
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
            // THE TAIL PUSH, padding edition (batch-4 #1/#4, owner: *"the bottom left of the
            // text gets cut off from the circle screen face"*): wear-compose clamps a row
            // TALLER than the viewport at an end position it computes from its own lazy layout —
            // scrollToItem into the row is clamped and simply does not move it (measured:
            // push o=1600, bottom 1009 → unchanged). But wear's list END does HONOR
            // contentPadding (settled long rows rest exactly at vpBottom − pad on this device).
            // So the lever is the PADDING: while the tail hangs below the circle-safe line,
            // grow the bottom pad by the hang; when it lifts back above, decay. The answer's
            // last line then streams upward out of the 8:20 corner instead of the bezel edge.
            val tailInfo = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == target }
            val restLine = slcH - bottomPadPx
            if (tailInfo != null && slcH > 0 && restLine > 0f &&
                tailInfo.offset + tailInfo.size > restLine + 2f
            ) {
                // THE GROWING-ROW LAG (batch-4 #1/#4, measured on the SM-L705U): wear computes the
                // clamped END from the row's PREVIOUS layout pass, so a row growing mid-stream
                // rests its newest line BELOW the circle-safe line until the next relayout — the
                // exact band the bezel shaves. A padding lever over-corrects (black transcript:
                // caught mid-oscillation on this build), so settle it the honest way: let the
                // row finish laying out, then re-assert the end — wear HONORS contentPadding at a
                // settled clamp (long settled rows rest exactly on the safe line, measured).
                // 3 × 70 ms covers the text-wrap passes without a visible pause; every streamed
                // chunk re-runs this effect, so the tail walks up out of the 8:20 corner.
                for (i in 1..6) {
                    delay(if (i <= 3) 70 else 150)
                    programmaticScrollAt.value = android.os.SystemClock.elapsedRealtime()
                    listState.scrollToItem(target, 4096)
                    withFrameNanos { }
                    if (!listState.canScrollForward) break
                }
            }
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
                if (scrolling && !now) {
                    val ours = android.os.SystemClock.elapsedRealtime() - programmaticScrollAt.value < 400L
                    if (!ours) follow.value = ChatFollow.afterUserScroll(ahead)
                }
                scrolling = now
            }
    }
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().onSizeChanged { slcH = it.height }
            .onGloballyPositioned { slcTopPx = it.positionInRoot().y }
            .pointerInput(listState) {
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
        contentPadding = roundListPadding(
            bottomExtraDp = 6f,
            bottomOverrideDp = contentPadBottomDp,
            topOverrideDp = 0.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Item 0: the BOTTOM-ANCHOR spacer (see anchorPadPx) — height 0 whenever the tail-pin
        // owns the view, so every later index is exactly one more than the pre-spacer era.
        item {
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(with(anchorDensity) { anchorPadPx.toDp() }),
            )
        }
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
