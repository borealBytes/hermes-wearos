package dev.hermes.wearos.domain

/**
 * WHO OWNS THE CHAT VIEWPORT while an answer streams (owner 2026-10-29: *"anchor the default while
 * you're using it… you should be able to manually scroll any way you want. But I wanted to keep it
 * so that the text that's coming out is just above the orb, so that way you can see as much scroll
 * back as possible when you're waiting on a reply or approval buttons. By default, unless you've
 * scrolled"*).
 *
 * The rule is two signals, and nothing else:
 *
 *  • **Tail-follow is the default state.** Newest line rests at the list's end (just above the orb
 *    bar), so everything older sits ABOVE it — readable by scrolling back — instead of being
 *    shoved off the top. That replaces the batch-7 "hold the question mid-circle" pin, which was
 *    the thing physically pushing the scroll-back out of sight.
 *  • **A user scroll settles the question.** Rest anywhere but the end ⇒ manual mode: the stream
 *    may never yank the transcript away again. Rest AT the end ⇒ the app owns the tail again —
 *    reading your own scroll-back and ending at the bottom IS the re-engage gesture.
 *  • **The owner acting re-engages**: a new ask, or switching chats. You asked, you watch the
 *    answer.
 *
 * No compose/android imports — the ChatScreen wires the signals (a scroll-settle is detected as a
 * RISE-then-fall of `isScrollInProgress`; every scroll WE make is an instantaneous `scrollToItem`,
 * which never raises it, so any edge is by construction the user's).
 */
object ChatFollow {

    /**
     * The user's scroll came to rest. "At the end" means NOTHING is left below the viewport —
     * [canScrollForward] false. That also covers a chat shorter than the circle: there was never
     * anywhere to go, so the viewport still belongs to the tail.
     */
    fun afterUserScroll(canScrollForward: Boolean): Boolean = !canScrollForward

    /** A fresh ask or a chat switch: the app takes the tail back, wherever reading had drifted. */
    fun reengaged(): Boolean = true
}
