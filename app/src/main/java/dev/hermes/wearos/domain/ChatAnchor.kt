package dev.hermes.wearos.domain

/**
 * Pure row-count math for the chat transcript's BOTTOM anchor (walkthrough ask: "after stt talk
 * button done, it scrolls to the top of convo instead of staying at bottom"). ChatScreen's
 * ScalingLazyColumn rows, in order: header (always) · empty hint (only when idle-empty) · N
 * messages · approval card · live-stream row · status line. [bottomIndex] returns the index of
 * the LAST row — the value the screen scrolls to on send / stream delta / stop / overlay dismiss /
 * agent switch (chat id change). No compose/android imports — unit-tested.
 */
object ChatAnchor {


    // ── #14 FOCUS HOLD (thinking-runway) ──────────────────────────────────────────────────
    // While an answer is awaited (engine busy, newest message is the wearer's question,
    // nothing streamed yet), ChatScreen renders ONLY —
    //   anchor spacer(0) · caption(1) · QUESTION(2) · status(3) —
    // hiding the history above the question. The collapsed list is short content, so the
    // shipped slack rule (spacer = viewport − bottomPad − content, rest at list top) owns
    // the view: question + thinking rest bottom-aligned on the orb crown with the whole
    // upper circle as the reply's runway. The status row is PERMANENT during a hold (blank
    // text between engine/tool updates keeps its measured height — the hold never jitters).
    // History returns, keyed and intact, the moment the first delta answers.
    const val HOLD_Q_INDEX = 2
    const val HOLD_STATUS_INDEX = 3

    /** Bottom row while focused (== HOLD_STATUS_INDEX): the value the hold scrolls against. */
    fun holdBottomIndex(): Int = HOLD_STATUS_INDEX

    fun bottomIndex(
        messageCount: Int,
        hasApproval: Boolean = false,
        hasLive: Boolean = false,
        hasStatus: Boolean = false,
        showEmptyHint: Boolean = false,
    ): Int {
        var rows = 2 // anchor spacer is item 0, ChatHeader/Caption is item 1 (bottom-anchor, 2026-10-29)
        if (showEmptyHint) rows++
        rows += messageCount.coerceAtLeast(0)
        if (hasApproval) rows++
        if (hasLive) rows++
        if (hasStatus) rows++
        return rows - 1
    }

    /** The idle-empty "Ask Hermes" hint condition — kept in one place so the count can't drift. */
    fun showEmptyHint(messageCount: Int, live: String, hasApproval: Boolean): Boolean =
        messageCount == 0 && live.isEmpty() && !hasApproval
}
