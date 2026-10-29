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
 *  • **launched from the app grid** — the launcher icon is the only intent that means "show me the
 *    app". `am start`/dev broadcasts/screenshot harnesses carry an explicit component with no
 *    LAUNCHER category, and a splash painted over a screencap silently breaks every docs/screens
 *    shot.
 *  • **the owner's choice decides WHICH show, not whether one exists** — VIDEO or STATIC, both
 *    real modes now (the old off-switch's OFF maps to STATIC in [choiceOf]: an owner who silenced
 *    the film still gets a splash, just a still one).
 *
 * No android imports — unit-tested.
 */
object SplashIntro {

    /** Settings ▸ Display ▸ Launch splash (owner: *"video by default"*). */
    enum class Choice { VIDEO, STATIC }

    enum class Gate { PlayVideo, PlayStatic, WarmStart, DeepLink, NotLauncher }

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
    ): Gate = when {
        !firstCompositionOfProcess -> Gate.WarmStart
        deepLink -> Gate.DeepLink
        !fromLauncher -> Gate.NotLauncher
        choice == Choice.VIDEO -> Gate.PlayVideo
        else -> Gate.PlayStatic
    }

    /**
     * Ceiling on "wait for the owner's Display switch" (DataStore read at boot). Waiting beats
     * letting a launch-time race decide whether a film plays; timing out beats hanging the launch
     * on a slow disk, and defaults to the shipped default (VIDEO) for everyone who never opened
     * the chooser.
     */
    const val SETTING_BUDGET_MS = 400L

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
