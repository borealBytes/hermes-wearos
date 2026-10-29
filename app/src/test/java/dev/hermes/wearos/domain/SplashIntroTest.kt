package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHO SEES THE FILM (domain/SplashIntro). The expensive property here is that exactly ONE launch
 * path plays a 14 s sound-on video and every other path is untouched: warm starts, notification
 * taps, tile taps, `am start`, and the screenshot harnesses all land on the chat as before.
 * Priority order is pinned too — an owner who switched the film off gets an honest `OwnerOff`
 * even on a deep link, so the log says what actually stopped it.
 */
class SplashIntroTest {

    private fun gate(
        first: Boolean = true,
        owner: Boolean = true,
        link: Boolean = false,
        launcher: Boolean = true,
    ) = SplashIntro.decide(first, owner, link, launcher)

    @Test fun `the only launch that plays is a launcher cold start`() {
        assertEquals(SplashIntro.Gate.Play, gate())
    }

    @Test fun `a warm start never replays the film`() {
        // The process already showed it; coming back from Recents must go straight to the chat.
        assertEquals(SplashIntro.Gate.WarmStart, gate(first = false))
        // …and warm-start beats every other reason, including a launcher tap on a live process.
        assertEquals(SplashIntro.Gate.WarmStart, gate(first = false, owner = false, link = true))
    }

    @Test fun `the owner switch wins over a cold launcher start`() {
        assertEquals(SplashIntro.Gate.OwnerOff, gate(owner = false))
    }

    @Test fun `a notification or tile tap goes where it was aimed, not to the film`() {
        assertEquals(SplashIntro.Gate.DeepLink, gate(link = true))
        // Owner-off outranks the deep link (both say no — the log names the first one it hit).
        assertEquals(SplashIntro.Gate.OwnerOff, gate(owner = false, link = true))
    }

    @Test fun `am start and the screenshot harnesses are not launcher launches`() {
        // scripts/setup.sh and the DEMO.md capture path start the activity by explicit component;
        // a film over a screencap is a silent demo breakage.
        assertEquals(SplashIntro.Gate.NotLauncher, gate(launcher = false))
        assertEquals(SplashIntro.Gate.DeepLink, gate(link = true, launcher = false))
    }

    @Test fun `timeouts are set for a watch not a phone`() {
        // The setting wait must stay far below a perceptible launch stall, and the first-frame
        // budget far below the film itself, so a dead decoder is a brief pause — never a lockout.
        assertTrue(SplashIntro.SETTING_BUDGET_MS in 1L..1_000L)
        assertTrue(SplashIntro.FADE_MS in 100..400)
        // The AUDIO grace must resolve INSIDE the frame budget: giving up on the soundtrack is the
        // thing that keeps the picture alive, so a grace that outlasts the budget would cut the film
        // instead of rescuing it.
        assertTrue(SplashIntro.AUDIO_GRACE_MS in 1L..2_000L)
        assertTrue(SplashIntro.AUDIO_GRACE_MS < SplashIntro.FIRST_FRAME_BUDGET_MS)
        // The frame budget is pinned to hardware, not taste: cold starts on an SM-L705U painted
        // their first film frame at 1.9 s / 3.1 s / 4.1 s, so it has to clear the worst of those and
        // still be bounded — a player that never renders must hand the wrist back to the chat.
        assertTrue(SplashIntro.FIRST_FRAME_BUDGET_MS in 4_500L..15_000L)
    }
}
