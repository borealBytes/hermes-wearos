package dev.hermes.wearos.domain

/**
 * THE LAUNCH-VIDEO GATE (owner 2026-10-29: *"it's only on the very first load of the app… if the
 * user clicks anywhere on the screen it stops playing and loads the rest — and we're loading the
 * rest of the app in the background while the video plays"*). `res/raw/hermes_watch_intro.mp4`
 * (14 s, WITH audio) replaces the one-frame spirit flash on a cold start, drawn OVER a chat that
 * is already composing itself underneath — the film never gates composition, and any touch cuts it.
 *
 * Four conditions, checked in this order, because each one protects a different failure:
 *
 *  • **first composition of the process** — a warm start out of Recents must not replay a 14 s
 *    film (that is what the caller's `AtomicBoolean` answers). A video you cannot skip is a video
 *    you turn off; the tap-to-cut covers within the film, this covers re-entering the app.
 *  • **the owner left it on** — Settings ▸ Display ▸ Launch video. Any watch that makes sound on
 *    every launch gets silenced fast, so the opt-out is the next thing checked.
 *  • **not a deep link** — a notification tap after the process died wants THAT CHAT and the tile's
 *    "Ask Hermes" wants the mic up; both cold-start the process, neither wants an intro (§M5).
 *  • **launched from the app grid** — the launcher icon is the only intent that means "show me the
 *    app". `am start`/dev broadcasts/screenshot harnesses carry an explicit component with no
 *    LAUNCHER category, and a film painted over a screencap silently breaks every docs/screens shot.
 *
 * No android imports — unit-tested.
 */
object SplashIntro {

    enum class Gate { Play, WarmStart, OwnerOff, DeepLink, NotLauncher }

    /**
     * Ceiling on "wait for the owner's Display switch" (DataStore read at boot). Waiting beats
     * letting a launch-time race decide whether a 14 s film plays; timing out beats hanging the
     * launch on a slow disk, and defaults to ON — the shipped default for everyone who never
     * opened the switch.
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
     * 71 ms of eating a film that played fine. That is why the ceiling is 8 s.
     */
    const val FIRST_FRAME_BUDGET_MS = 8_000L

    /** Crossfade out on dismiss — tap, end, or error — matching the spirit flash's hand-off. */
    const val FADE_MS = 220

    fun decide(
        firstCompositionOfProcess: Boolean,
        ownerEnabled: Boolean,
        deepLink: Boolean,
        fromLauncher: Boolean,
    ): Gate = when {
        !firstCompositionOfProcess -> Gate.WarmStart
        !ownerEnabled -> Gate.OwnerOff
        deepLink -> Gate.DeepLink
        !fromLauncher -> Gate.NotLauncher
        else -> Gate.Play
    }
}
