package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHO SEES WHAT AT LAUNCH (domain/SplashIntro). The expensive property here is that exactly ONE
 * launch path plays a 14 s sound-on video (or holds the still) and every other path is untouched:
 * warm starts, notification taps, tile taps, `am start`, and the screenshot harnesses all land on
 * the chat as before. Priority order is pinned too, because the log names the FIRST reason the
 * gate answered no — it has to be the one that actually stopped it.
 */
class SplashIntroTest {

    private fun gate(
        first: Boolean = true,
        choice: SplashIntro.Choice = SplashIntro.Choice.VIDEO,
        link: Boolean = false,
        launcher: Boolean = true,
    ) = SplashIntro.decide(first, choice, link, launcher)

    @Test fun `the only launch that plays is a launcher cold start`() {
        assertEquals(SplashIntro.Gate.PlayVideo, gate())
        assertEquals(SplashIntro.Gate.PlayStatic, gate(choice = SplashIntro.Choice.STATIC))
    }

    @Test fun `a warm start never replays the show`() {
        // The process already showed it; coming back from Recents must go straight to the chat.
        assertEquals(SplashIntro.Gate.WarmStart, gate(first = false))
        // …and warm-start beats every other reason, including a launcher tap on a live process.
        assertEquals(SplashIntro.Gate.WarmStart, gate(first = false, link = true))
    }

    @Test fun `a notification or tile tap goes where it was aimed, not to the splash`() {
        assertEquals(SplashIntro.Gate.DeepLink, gate(link = true))
        assertEquals(
            SplashIntro.Gate.DeepLink,
            gate(choice = SplashIntro.Choice.STATIC, link = true),
        )
    }

    @Test fun `am start and the screenshot harnesses are not launcher launches`() {
        // scripts/setup.sh and the DEMO.md capture path start the activity by explicit component;
        // a splash over a screencap is a silent demo breakage.
        assertEquals(SplashIntro.Gate.NotLauncher, gate(launcher = false))
        assertEquals(SplashIntro.Gate.DeepLink, gate(link = true, launcher = false))
    }

    // ── CHOOSER (owner 2026-10-29: "I only want static or video, video by default") ──
    @Test fun `the stored mode decides the show, and junk defaults to the shipped one`() {
        assertEquals(SplashIntro.Choice.VIDEO, SplashIntro.choiceOf("video"))
        assertEquals(SplashIntro.Choice.STATIC, SplashIntro.choiceOf("static"))
        assertEquals(SplashIntro.Choice.STATIC, SplashIntro.choiceOf("STATIC")) // case-insensitive
        assertEquals(SplashIntro.Choice.VIDEO, SplashIntro.choiceOf("banana")) // never throw at boot
        assertEquals(SplashIntro.Choice.VIDEO, SplashIntro.choiceOf(null))
    }

    @Test fun `a legacy Launch-video OFF migrates to STATIC, everything else to the film`() {
        // The OFF owner never wanted the FILM; a still is the closest honest successor. Never
        // touched (null) or ON both land on the shipped default: video.
        assertEquals(SplashIntro.Choice.STATIC, SplashIntro.choiceOf(null, legacyVideoOn = false))
        assertEquals(SplashIntro.Choice.VIDEO, SplashIntro.choiceOf(null, legacyVideoOn = true))
        assertEquals(SplashIntro.Choice.VIDEO, SplashIntro.choiceOf(null, legacyVideoOn = null))
        // A real stored choice always outranks the legacy mirror.
        assertEquals(SplashIntro.Choice.VIDEO, SplashIntro.choiceOf("video", legacyVideoOn = false))
    }

    @Test fun `timeouts are set for a watch not a phone`() {
        // The setting wait must stay far below a perceptible launch stall, and the first-frame
        // budget far below the film itself, so a dead decoder is a brief pause — never a lockout.
        assertTrue(SplashIntro.SETTING_BUDGET_MS in 1L..1_000L)
        assertTrue(SplashIntro.FADE_MS in 100..400)
        // The AUDIO grace must resolve INSIDE the frame budget: giving up on the soundtrack is the
        // thing that keeps the picture alive, so a grace that outlasts the budget would cut the film
        // instead of rescuing it.
        assertTrue(SplashIntro.AUDIO_GRACE_MS in 1_200L..2_000L)
        assertTrue(SplashIntro.AUDIO_GRACE_MS < SplashIntro.FIRST_FRAME_BUDGET_MS)
        // The frame budget is pinned to hardware, not taste: cold starts on an SM-L705U painted
        // their first film frame at 1.9 s / 3.1 s / 4.1 s clean and 5.93 s on a late-focus boot,
        // so it has to clear the worst of those and still be bounded — a player that never renders
        // must hand the wrist back to the chat.
        assertTrue(SplashIntro.FIRST_FRAME_BUDGET_MS in 6_500L..15_000L)
    }

    @Test fun `a static hold sits under every video first frame, so static is always the quick path`() {
        // The claim the UI makes ("static" = the splash screen, not a show) breaks the moment the
        // still outstays the film's own worst measured wait-for-first-frame on this hardware.
        assertTrue(SplashIntro.STATIC_HOLD_MS in 400L..2_000L)
        // (First-frame floor measured 1.9 s on the SM-L705U — the static hold stays a third under.)
        assertTrue(SplashIntro.STATIC_HOLD_MS < 1_900L)
    }
}
