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

    fun bottomIndex(
        messageCount: Int,
        hasApproval: Boolean = false,
        hasLive: Boolean = false,
        hasStatus: Boolean = false,
        showEmptyHint: Boolean = false,
    ): Int {
        var rows = 1 // ChatHeader is item 0
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
