package dev.hermes.wearos.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The viewport-ownership policy (owner 2026-10-29: newest line just above the orb, "by default,
 * unless you've scrolled"). Pure: ChatScreen supplies the signals.
 */
class ChatFollowTest {

    @Test fun `resting away from the end releases the tail to the reader`() {
        assertFalse(ChatFollow.afterUserScroll(canScrollForward = true))
    }

    @Test fun `resting at the end hands the tail back to the app`() {
        assertTrue(ChatFollow.afterUserScroll(canScrollForward = false))
    }

    @Test fun `a chat shorter than the circle was never scrollable, so the tail still owns it`() {
        // Nothing ahead is the same fact as "at the end" — a rubber-band drag in a short chat
        // must not silently disengage follow and let the next delta grow unseen below.
        assertTrue(ChatFollow.afterUserScroll(canScrollForward = false))
    }

    @Test fun `a new ask or a chat switch re-engages regardless of reading position`() {
        assertTrue(ChatFollow.reengaged())
    }
}
