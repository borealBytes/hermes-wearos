package dev.hermes.wearos.domain

/**
 * THE LAUNCH-SPLASH GATE (owner 2026-10-29, two orders: *"it's only on the very first load of the
 * app… if the user clicks anywhere on the screen it stops playing and loads the rest — and we're
 * loading the rest of the app in the background while the video plays"*; then *"I only want static
 * or video, video by default. let the user choose video or splash screen in the settings. also let
 * the user choose custom image/video for splash screen usage from image/video on their watch if
 * they want so they can customize"*). The film (`assets/hermes-watch-intro.mp4`, 14 s, WITH audio)
 * or a still (the built-in self-portrait, or the owner's own image) replaces the one-frame spirit
 * flash on a launcher cold start, drawn OVER a chat that is already composing itself underneath —
 * the splash never gates composition, and any touch cuts it.
 *
 * Conditions, checked in this order, because each one protects a different failure:
 *
 *  • **first composition of the process** — a warm start out of Recents must not replay the show.
 *    A video you cannot skip is a video you turn off; the tap-to-cut covers within the show, this
 *    covers re-entering the app.
 *  • **not a deep link** — a notification tap after the process died wants THAT CHAT and the tile's
 *    "Ask Hermes" wants the mic up; both cold-start the process, neither wants an intro (§M5).
 *  • **launched from the app grid** — "show me the app" is answered by [launcherShaped]:
 *    anything with ACTION_MAIN (the AOSP launcher's MAIN+LAUNCHER **and** the One UI Watch grid's
 *    MAIN-without-category, proven from watch logcat 2026-10-30) qualifies; the harness declines
 *    via the `dev_no_intro` extra or by being a bare `am start -n` component start (no action),
 *    which keeps every docs/screens shot splash-free.
 *  • **the owner's choice decides WHICH show, not whether one exists** — VIDEO or STATIC, both
 *    real modes now (the old off-switch's OFF maps to STATIC in [choiceOf]: an owner who silenced
 *    the film still gets a splash, just a still one).
 *
 * No android imports — unit-tested.
 */
object SplashIntro {

    /** Settings ▸ Display ▸ Launch splash (owner: *"video by default"*). */
    enum class Choice { VIDEO, STATIC }

    enum class Gate { PlayVideo, PlayStatic, WarmStart, DeepLink, NotLauncher, Onboarding }

    /** The persisted mode string, or the legacy Launch-video switch, mapped to a [Choice]. */
    fun choiceOf(stored: String?, legacyVideoOn: Boolean? = null): Choice =
        when (stored?.lowercase()) {
            "video" -> Choice.VIDEO
            "static" -> Choice.STATIC
            // First boot of the chooser build: honor an explicit legacy OFF (that owner never
            // wanted the film — a still is the closest honest successor), default to the film.
            // Anything else (never touched the switch) is the shipped default: VIDEO.
            else -> if (legacyVideoOn == false) Choice.STATIC else Choice.VIDEO
        }

    fun decide(
        firstCompositionOfProcess: Boolean,
        choice: Choice,
        deepLink: Boolean,
        fromLauncher: Boolean,
        needsPairing: Boolean = false,
    ): Gate = when {
        !firstCompositionOfProcess -> Gate.WarmStart
        deepLink -> Gate.DeepLink
        !fromLauncher -> Gate.NotLauncher
        // #17 rework (owner 2026-11-11, cold-start forensics on the AVD): a fresh install
        // routes to the Pair wizard the moment the empty registry loads — BEHIND the film.
        // The film's overlay had nothing legitimate to hand off to (no chat composes under a
        // wizard), the notification dialog on that same first launch steals the player's
        // focus, and the wearer just watches an icon marinate. Setup launches answer the
        // SETUP question first; the film is the reward for the second launch.
        needsPairing -> Gate.Onboarding
        choice == Choice.VIDEO -> Gate.PlayVideo
        else -> Gate.PlayStatic
    }

    /** The one android constant this domain file needs (Intent.ACTION_MAIN). */
    const val ACTION_MAIN = "android.intent.action.MAIN"

    /**
     * Is this launch "from the app grid"? (owner 2026-10-30: *"I close out all the apps and then
     * I open Hermes… it shows the static image instead of the video"* — the SETTING read
     * `mode=video` and the gate still skipped the film: the One UI Watch launcher starts the
     * activity with `act=android.intent.action.MAIN` and **no CATEGORY_LAUNCHER at all** (watch
     * logcat: `START u0 {act=…MAIN … cmp=dev.hermes.wearos/.MainActivity} … from uid 1000`), so
     * the old require-the-category rule rejected every real drawer launch into the static flash.
     *
     * The rule inverts: a launch is launcher-shaped UNLESS IT PROVES OTHERWISE.
     *  • [devNoIntro] — the explicit harness opt-out (intent extra `dev_no_intro`), for any
     *    script that wants the app with no show, whatever the fingerprint;
     *  • `action == null` with no LAUNCHER category — a bare component start, which is exactly
     *    what `am start -n` (every `scripts` launcher call, verify-chat.sh, docs/screens captures)
     *    produces, and what no real launcher does.
     *
     * Anything carrying ACTION_MAIN plays the owner's chosen mode — AOSP's Wear launcher (MAIN +
     * LAUNCHER), the One UI grid (MAIN alone), monkey (MAIN + CATEGORY_INFO). The gate is pure so
     * vendor intent fingerprints stay unit-testable; deep links never reach this question
     * ([decide] checks them first).
     */
    fun launcherShaped(action: String?, hasLauncherCategory: Boolean, devNoIntro: Boolean): Boolean =
        !devNoIntro && (hasLauncherCategory || action == ACTION_MAIN)

    /**
     * Ceiling on "wait for the owner's Display switch" (DataStore read at boot). Waiting beats
     * letting a launch-time race decide whether a film plays; timing out beats hanging the launch
     * on a slow disk, and defaults to the shipped default (VIDEO) for everyone who never opened
     * the chooser.
     */
    const val SETTING_BUDGET_MS = 400L

    // ── BATCH-6 #23 THE SPLASH HOLD (owner cold-start review: *"I see the icon first in the
    // center — as expected — then the icon from the orb zoomed to full screen — don't expect
    // this — straight to video per launch selection"*) ──────────────────────────────────
    // The zoom WAS the operating system: from Android 12 the system splash exits with an
    // ICON SCALE-OUT animation the moment the app first draws, and our #17 curtain — a twin
    // of that same orb on the same background — then sat visible while the video decoder
    // woke (measured 4.7 s to first frame). Icon → zoom → big orb → film. The fix makes the
    // SYSTEM SPLASH the curtain: MainActivity holds it up (setKeepOnScreenCondition) until
    // the chosen media has actually PAINTED, and replaces the exit with a fast alpha fade.
    // What the owner sees is then exactly the ask: the small launcher icon they already
    // accept, then a fade straight into film (or the still). [shouldHold] is the pure brain;
    // MainActivity installs the holder, the intro gate feeds it, IntroVideo's first-frame
    // callback releases it.

    /** Grace after the settings budget: a gate still unread at budget+slack means the disk is
     *  slow — release the splash and let the app draw (the in-app curtain falls back). */
    const val HOLD_SLACK_MS = 300L

    /** Last-resort ceiling on holding the splash: the intro's own [FIRST_FRAME_BUDGET_MS] is
     *  what actually decides a slow/sick decoder (and every skip path releases the hold through
     *  IntroVideo's done()), so this cap only has to sit ABOVE that deadline with slack — never
     *  below the boot's real first-frame time. Batch-6 #30 evidence: the 8 s cap was tuned to a
     *  4.7 s first frame, but a busy boot measured 11.5 s — the cap then released the splash
     *  onto the full-screen orb CURTAIN for seconds, which the owner read as the vetoed icon
     *  zoom all over again ("it zooms that in all the way, static, before it shows the video").
     *  The launcher icon they accepted is a far better hold than that curtain. */
    const val FILM_HOLD_CAP_MS = 15_000L

    /** BATCH-6 #30 (owner: *"it zooms that in all the way to take up the whole screen, static,
     *  before it shows the video"*): the film's opening shot — a CLOSED pocket watch lying like
     *  a silver ellipse — is still for its first ~2.2 s, so fading the splash onto the decoder's
     *  first frame lands the wearer on a big static orb and reads as the vetoed zoom all over
     *  again. The hold therefore releases on a MOVING frame: the built-in film starts playing
     *  under the splash and the fade cuts in once the lid is opening. The position comes from
     *  the player; owner-picked media and stills release on their first paint as before. */
    const val INTRO_OPEN_MS = 2_200L

    /** Is the overlay showing a frame worth fading the splash away ONTO? (Pure half of #30.) */
    fun revealIsMoving(firstFramePainted: Boolean, positionMs: Long, builtInFilm: Boolean): Boolean =
        firstFramePainted && (!builtInFilm || positionMs >= INTRO_OPEN_MS)

    /**
     * The mutable half of the hold. `SystemClock.elapsedRealtime()` rides in through
     * [holds] — this file stays android-free and the whole machine unit-tested.
     */
    class LaunchHold(val startedAtMs: Long) {
        @Volatile var gate: Gate? = null
        @Volatile var painted: Boolean = false
        fun holds(nowMs: Long): Boolean = shouldHold(gate, painted, nowMs - startedAtMs)
    }

    /** #17 rework (owner spec, verbatim: *\"it should be a second or two of the nouse girl
     *  icon, then go into video right away while the app loads\"*): the icon may NEVER marinate.
     *  The happy path releases EARLIER — at the film's moving-frame reveal (~2.2 s, [#30]) —
     *  this cap only binds when the decoder is starved (focus, a sick codec, a queue under
     *  load): past it the splash fades onto the overlay's own surface (poster, then the film
     *  as it wakes) instead of holding the whole launch hostage. 800 ms above the proven
     *  moving-frame moment: on a healthy boot the reveal always wins the race, and the owner
     *  never sees the difference. */
    const val OWNER_ICON_CAP_MS = 3_000L

    /**
     * Should the SYSTEM splash still be on screen? Pure so the whole state machine is unit-
     * tested: [gate] null = the Display switch has not been read yet (hold to budget+slack —
     * the same bounded wait that always decided the gate, now invisible behind the icon);
     * PlayVideo/PlayStatic = hold until [painted] (the media's first real pixels, not merely
     * "player prepared" — a prepared-but-unpainted film would leak black), now ALSO capped at
     * [OWNER_ICON_CAP_MS] (#17 rework — the icon holds for a second or two, then the app's
     * OWN surface shows whatever it has: a playing film, its poster, or the wizard); every
     * skip verdict and the [FILM_HOLD_CAP_MS] ceiling release immediately. Called only on
     * launches the holder deems show-shaped (launcher-shaped intent, no deep link) —
     * everything else gets the plain OS hand-off.
     */
    fun shouldHold(gate: Gate?, painted: Boolean, elapsedMs: Long): Boolean = when {
        elapsedMs >= FILM_HOLD_CAP_MS -> false
        gate == null -> elapsedMs < SETTING_BUDGET_MS + HOLD_SLACK_MS
        gate == Gate.PlayVideo || gate == Gate.PlayStatic ->
            !painted && elapsedMs < OWNER_ICON_CAP_MS
        else -> false
    }

    /**
     * How long the film waits for audio focus before it stops waiting (the policy itself lives in
     * ui/IntroVideo: ask politely, then play silent). Tuned on hardware: an SM-L705U answered a
     * focus request LATER than the first 700 ms attempt, which silently cost the film its soundtrack,
     * so the ask gets a real window. It got a wider one after a fifth cold start on the same watch
     * logged `no audio focus in 1200ms — playing silent` for a launch that otherwise played
     * perfectly: the owner's intro is silent for that whole boot, and he cannot tell. Invisible
     * either way — the poster is what is on screen while we wait. Must stay inside
     * [FIRST_FRAME_BUDGET_MS], which is the deadline for giving up.
     */
    const val AUDIO_GRACE_MS = 1_800L

    /**
     * Ceiling on "wait for the decoder's first frame". Past this the overlay is dropped and the
     * chat shows instead: a player that never painted (no codec, exhausted decoder, focus denied
     * at prepare) must never strand the watch behind black.
     *
     * Tuned on hardware, not from a phone: first-frame timings on an SM-L705U (Galaxy Watch,
     * 32-bit userspace) measured **1.9 s, 3.1 s and 4.1 s** across cold starts, so the first
     * attempt at 1.5 s cut a film that was about to play perfectly, and 3.5 s still lost one run in
     * four. Waiting costs nothing you can see — the poster is the same face the system splash has
     * been showing — while a premature cut silently kills the launch story. Leave headroom.
     *
     * A later session on the same watch added **2.87 / 2.96 / 2.99 / 3.04 s** in a clean run series
     * and **5.93 s** on a cold start whose audio focus arrived late — media3 gates rendering on
     * focus, so a slow-focus launch pushes the frame out with it, and the 6 s ceiling came within
     * 71 ms of eating a film that played fine. That is why the ceiling is 8 s. And it was STILL
     * eating films: the owner launched the app, watched the poster sit there, and reported *"it
     * showed the static instead of the video"* — a same-session cold start logged a **5.49 s**
     * first frame that WOULD have played. The waiting is invisible (the poster is the same face
     * the system splash shows) while a premature cut silently kills the launch story, so the
     * ceiling is 12 s and the only thing it can cost is patience on a bad boot.
     */
    const val FIRST_FRAME_BUDGET_MS = 12_000L

    /**
     * How long a STATIC splash holds before lifting on its own (tap cuts it sooner). Sized to sit
     * clearly UNDER the video path's own cost — every measured first frame of the film on the
     * SM-L705U was ≥ 1.9 s — so switching to STATIC always makes the launch feel FASTER, never
     * slower; long enough that a face or a logo is actually read, short enough that a launch with
     * nothing to decode never feels like it is waiting for something.
     */
    const val STATIC_HOLD_MS = 1_200L

    /** Longest edge a custom still is decoded down to (watch gallery originals are multi-megapixel). */
    const val SPLASH_DECODE_EDGE_PX = 560

    /** Crossfade out on dismiss — tap, end, or error — matching the spirit flash's hand-off. */
    const val FADE_MS = 220
}
