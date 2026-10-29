package dev.hermes.wearos.domain

/**
 * THE CARDS INSIDE THE KARAOKE CURSOR (owner 2026-10-29, day-two of karaoke follow: *"when you're
 * counting and stuff for the audio test I am not seeing the line that you're saying be at the
 * center of the screen"*). `onStart` alone publishes ONE offset per utterance — fine for
 * punctuated prose (every sentence is its own utterance), useless for a numbered list or a table,
 * which have no `.!?\n` inside them and therefore ride the TTS queue as ONE long utterance: the
 * chat pinned to line 1 while the voice was thirty lines deep. The fix is a cursor INSIDE the
 * utterance, and the engine only sometimes tells us where it is (`onProgress` is engine-honesty
 * optional — Samsung answers, many don't). So the default is a TIME ESTIMATE, self-calibrated:
 * every finished utterance measures its own seconds-per-character and the next estimate rides
 * that (a rolling EMA), so a 1.6× rate, a slow voice, or a long pause all converge in one sentence.
 *
 * No android imports — unit-tested.
 */
object KaraokeCursor {

    /** First-ever estimate before any utterance has been measured (~normal English speech). */
    const val SEED_CHARS_PER_SEC = 16f

    /** Sanity rails for the calibration — clamps stop one weird long-pause utterance from
     *  stranding the cursor at 0.x c/s or 500 c/s. */
    const val MIN_CHARS_PER_SEC = 4f
    const val MAX_CHARS_PER_SEC = 80f

    /** Rolling weights: new measurement gets 40 % — converges in ~4 utterances, still filters
     *  the one answer the voice happened to read slow. */
    const val CALIBRATION_ALPHA = 0.4f

    /** Where the voice probably is: linear chars-since-start, clamped to the utterance. */
    fun estimateOffset(textLen: Int, elapsedMs: Long, charsPerSec: Float): Int {
        if (textLen <= 0 || elapsedMs <= 0L || charsPerSec <= 0f) return 0
        return (elapsedMs / 1000f * charsPerSec).toInt().coerceIn(0, textLen)
    }

    /** Update the speed model from a COMPLETED utterance (length / real seconds). Garbage
     *  measurements (whisper-short texts, absurd durations) are ignored, not averaged in. */
    fun calibrate(current: Float?, textLen: Int, durationMs: Long): Float? {
        if (textLen < 10 || durationMs < 400L) return current
        val measured = textLen / (durationMs / 1000f)
        val clamped = measured.coerceIn(MIN_CHARS_PER_SEC, MAX_CHARS_PER_SEC)
        return when {
            current == null -> clamped
            else -> (current * (1f - CALIBRATION_ALPHA) + clamped * CALIBRATION_ALPHA)
                .coerceIn(MIN_CHARS_PER_SEC, MAX_CHARS_PER_SEC)
        }
    }

    /**
     * Trust the engine's `onProgress` ONLY when it reports real movement — kept for the day a
     * compileSdk exposes the callback again (absent from UtteranceProgressListener in the
     * current platform jar). Engines that never implement it still fire the bookend callbacks
     * (0 at start, sometimes 1 at the end) — honoring a 0 as "the engine knows" would switch the
     * estimator off and re-create the pinned-at-line-1 bug the owner just reported.
     */
    fun engineProgressUsable(progress: Float): Boolean =
        !progress.isNaN() && progress > 0f && progress <= 1f

    /** How often the ticker re-estimates while an utterance plays (ms). Fast enough to feel
     *  attached to the voice, slow enough that the scroll animation is never fighting itself. */
    const val TICK_MS = 180L
}
