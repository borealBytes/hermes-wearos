package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ── LAUNCHER SHAPED (owner 2026-10-30: *"it shows the static image instead of the video"* —
    //    the One UI Watch app grid sends ACTION_MAIN with NO CATEGORY_LAUNCHER; the old
    //    require-the-category rule skipped the film on every real drawer launch. Watch logcat:
    //    `START u0 {act=android.intent.action.MAIN … cmp=dev.hermes.wearos/.MainActivity}` ── ) ──
    private fun shaped(
        action: String? = null,
        category: Boolean = false,
        dev: Boolean = false,
    ) = SplashIntro.launcherShaped(action, category, dev)

    @Test fun `the One UI Watch grid launch plays the film (MAIN without any category)`() {
        // The regression, pinned: MAIN alone IS a human tapping the app icon.
        assertTrue(shaped(action = SplashIntro.ACTION_MAIN))
        // The AOSP Wear launcher still works (MAIN + LAUNCHER).
        assertTrue(shaped(action = SplashIntro.ACTION_MAIN, category = true))
        // …and the category alone (some vendor launcher variants) is equally a launch.
        assertTrue(shaped(category = true))
    }

    @Test fun `a bare component start is the harness, and dev_no_intro outranks any shape`() {
        // `am start -n pkg/.MainActivity` = component only, no action, no category.
        assertFalse(shaped())
        // Explicit opt-out wins even against the truest launcher shape.
        assertFalse(shaped(action = SplashIntro.ACTION_MAIN, dev = true))
        assertFalse(shaped(category = true, dev = true))
        // A stray non-MAIN action with no category (someone's deep plumbing) is not a launch.
        assertFalse(shaped(action = "android.intent.action.VIEW"))
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

    // ── batch-6 #30 THE MOVING RELEASE: no fade onto the film's still opening ──────────
    @Test fun `the built-in film holds the splash past its still closed-watch opening`() {
        // First painted frame at position 0: still orb on screen = the vetoed zoom, stay up.
        assertFalse(SplashIntro.revealIsMoving(firstFramePainted = true, positionMs = 0L, builtInFilm = true))
        assertFalse(SplashIntro.revealIsMoving(true, SplashIntro.INTRO_OPEN_MS - 1, builtInFilm = true))
        // The lid is opening: release into MOTION.
        assertTrue(SplashIntro.revealIsMoving(true, SplashIntro.INTRO_OPEN_MS, builtInFilm = true))
        assertTrue(SplashIntro.revealIsMoving(true, SplashIntro.INTRO_OPEN_MS + 900, builtInFilm = true))
    }

    @Test fun `owner-picked media releases on its very first frame`() {
        // The still-opening skip is OUR film's artwork quirk — never a rule about theirs.
        assertTrue(SplashIntro.revealIsMoving(firstFramePainted = true, positionMs = 0L, builtInFilm = false))
    }

    @Test fun `nothing releases before the first real frame`() {
        for (builtIn in listOf(true, false)) {
            assertFalse(SplashIntro.revealIsMoving(false, 99_999L, builtIn))
        }
    }

    @Test fun `the opening skip stays shorter than the first-frame it waits out`() {
        // 2.2 s of motion-wait must not outlast a slow decoder's first frame by enough to
        // approach the cap — the reveal never becomes a longer hold than #23 ever allowed.
        assertTrue(SplashIntro.INTRO_OPEN_MS < SplashIntro.FILM_HOLD_CAP_MS / 3)
    }

    // ── batch-6 #23 THE SPLASH HOLD: the system icon stays up until the media paints ──
    @Test fun `splash hold covers the media wait and nothing else`() {
        val b = SplashIntro.SETTING_BUDGET_MS
        // Gate unread (slow disk): hold through the settings budget + slack, then release —
        // the plain hand-off beats trapping the icon behind a wedged DataStore read.
        assertTrue(SplashIntro.shouldHold(null, false, 0L))
        assertTrue(SplashIntro.shouldHold(null, false, b))
        assertTrue(SplashIntro.shouldHold(null, false, b + SplashIntro.HOLD_SLACK_MS - 1))
        assertFalse(SplashIntro.shouldHold(null, false, b + SplashIntro.HOLD_SLACK_MS + 1))
        // Film chosen: hold until it PAINTS (first real pixels), not merely "player exists".
        assertTrue(SplashIntro.shouldHold(SplashIntro.Gate.PlayVideo, false, b))
        assertFalse(SplashIntro.shouldHold(SplashIntro.Gate.PlayVideo, true, b))
        // …but the hold is CAPPED: a decoder that never renders hands the glass back.
        assertFalse(SplashIntro.shouldHold(SplashIntro.Gate.PlayVideo, false, SplashIntro.FILM_HOLD_CAP_MS))
        // The still rides the same hold (custom-image decode included).
        assertTrue(SplashIntro.shouldHold(SplashIntro.Gate.PlayStatic, false, b))
        assertFalse(SplashIntro.shouldHold(SplashIntro.Gate.PlayStatic, true, b))
        // Every skip verdict releases immediately — a skipped launch must not stare at an icon.
        assertFalse(SplashIntro.shouldHold(SplashIntro.Gate.WarmStart, false, 10L))
        assertFalse(SplashIntro.shouldHold(SplashIntro.Gate.DeepLink, false, 10L))
        assertFalse(SplashIntro.shouldHold(SplashIntro.Gate.NotLauncher, false, 10L))
    }

    @Test fun `the hold cap clears the worst measured first frame but stays bounded`() {
        // 4.7 s was the measured video-mode first frame on the SM-L705U (batch-5 #17 proof) —
        // the cap must sit comfortably above it yet never approach a "hung launch".
        // #30: the cap must sit ABOVE the intro's own give-up deadline — a cap below the
        // budget released the splash onto the orb curtain during a slow boot (11.5 s
        // measured), the exact "icon zoomed and sat there static" the owner vetoed.
        assertTrue(SplashIntro.FILM_HOLD_CAP_MS >= SplashIntro.FIRST_FRAME_BUDGET_MS)
        assertTrue(SplashIntro.FILM_HOLD_CAP_MS <= 20_000L)
    }

    @Test fun `LaunchHold is a pure clock over shouldHold`() {
        val h = SplashIntro.LaunchHold(1_000L)
        assertTrue(h.holds(1_500L)) // gate null, inside budget
        h.gate = SplashIntro.Gate.PlayVideo
        assertTrue(h.holds(2_000L))
        h.painted = true
        assertFalse(h.holds(2_000L))
    }

    // ── #17 REWORK (2026-11-11, owner cold-start spec: *"a second or two of the icon, then
    // go into video right away"*; reproduced on the AVD: the first-launch permission dialog
    // paused media3 and the wizard routed behind an unhanded-off hold) ──
    @Test fun `a setup launch answers setup — the film waits for the next launch`() {
        // Baseline (flag not set): yesterday's behavior stands — a launcher video launch plays.
        assertEquals(
            SplashIntro.Gate.PlayVideo,
            gate(first = true, choice = SplashIntro.Choice.VIDEO, link = false, launcher = true),
        )
        assertEquals(
            SplashIntro.Gate.Onboarding,
            SplashIntro.decide(true, SplashIntro.Choice.VIDEO, false, true, needsPairing = true),
        )
        // The wizard must never stare at an icon: Onboarding releases immediately.
        assertFalse(SplashIntro.shouldHold(SplashIntro.Gate.Onboarding, false, 10L))
    }

    @Test fun `the icon holds for a second or two — never longer`() {
        val c = SplashIntro.OWNER_ICON_CAP_MS
        // Unpainted film holds right up to the owner's cap…
        assertTrue(SplashIntro.shouldHold(SplashIntro.Gate.PlayVideo, false, c - 1))
        // …and releases onto the app's own surface at it, however sick the decoder is.
        assertFalse(SplashIntro.shouldHold(SplashIntro.Gate.PlayVideo, false, c))
        assertFalse(SplashIntro.shouldHold(SplashIntro.Gate.PlayStatic, false, c))
        // The cap sits ABOVE the happy reveal (moving frame at INTRO_OPEN_MS), so a healthy
        // boot always cuts on the film itself and the cap only ever catches a starved one.
        assertTrue(c > SplashIntro.INTRO_OPEN_MS)
    }
}
