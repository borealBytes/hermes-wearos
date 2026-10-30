package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bottom-anchor row-count math (walkthrough: transcript must STAY at the bottom across send /
 * stream / stop / approval / chat switch / overlay dismiss). The index ChatScreen scrolls to must
 * always be the LAST rendered row of ChatScreen's ScalingLazyColumn.
 */
class ChatAnchorTest {
    // Baseline note (2026-10-29 bottom-anchor): item 0 is the dynamic top spacer,

    @Test fun `bare chat is spacer plus header row`() {
        assertEquals(1, ChatAnchor.bottomIndex(messageCount = 0))
    }

    @Test fun `empty hint sits between header and the (missing) messages`() {
        assertEquals(2, ChatAnchor.bottomIndex(messageCount = 0, showEmptyHint = true))
    }

    @Test fun `messages push the anchor down one row each`() {
        assertEquals(5, ChatAnchor.bottomIndex(messageCount = 4))
    }

    @Test fun `approval live and status rows each add one`() {
        assertEquals(6, ChatAnchor.bottomIndex(messageCount = 4, hasApproval = true))
        assertEquals(6, ChatAnchor.bottomIndex(messageCount = 4, hasLive = true))
        assertEquals(6, ChatAnchor.bottomIndex(messageCount = 4, hasStatus = true))
        assertEquals(8, ChatAnchor.bottomIndex(messageCount = 4, hasApproval = true, hasLive = true, hasStatus = true))
    }

    @Test fun `voice send settle sequence walks the anchor to the bottom every time`() {
        // idle → user asks → stream deltas → stop marker (the exact "scrolls to the top" complaint)
        assertEquals(3, ChatAnchor.bottomIndex(2))                                        // 2 msgs
        assertEquals(4, ChatAnchor.bottomIndex(3))                                        // user msg lands
        assertTrue(ChatAnchor.bottomIndex(3, hasLive = true) > ChatAnchor.bottomIndex(3)) // live row
        assertEquals(5, ChatAnchor.bottomIndex(4, hasStatus = false))                     // stop adds "— stopped —"
    }

    @Test fun `empty hint only shows on a truly idle empty chat`() {
        assertTrue(ChatAnchor.showEmptyHint(0, "", false))
        assertFalse(ChatAnchor.showEmptyHint(0, "partial answer", false))  // streaming already
        assertFalse(ChatAnchor.showEmptyHint(0, "", true))                 // approval pending
        assertFalse(ChatAnchor.showEmptyHint(1, "", false))                // has history
    }

    @Test fun `negative message counts can't underflow the index`() {
        assertEquals(1, ChatAnchor.bottomIndex(-5))
    }


    // ── #14 FOCUS HOLD: the thinking-hold render is fixed chrome — the ticker chases these
    // exact rows, never message-count math (drift here is what made the first pass fail).
    @Test fun `hold layout pins question and status rows`() {
        assertEquals(2, ChatAnchor.HOLD_Q_INDEX)      // spacer · caption · QUESTION
        assertEquals(3, ChatAnchor.HOLD_STATUS_INDEX) // · STATUS (permanent during a hold)
        assertEquals(3, ChatAnchor.holdBottomIndex())
    }

    @Test fun `single-message chat agrees with the hold constants`() {
        // one question + status: full bottomIndex and the hold math must say the same row
        // spacer · caption · question · status — same bottom row by both counts
        assertEquals(ChatAnchor.holdBottomIndex(), ChatAnchor.bottomIndex(messageCount = 1, hasStatus = true))
    }
}
