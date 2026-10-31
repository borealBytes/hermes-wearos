package dev.hermes.wearos.domain

import kotlin.math.roundToInt

/**
 * Text-to-speech reply config (owner 2026-10-29: *"settings for that so that you can change
 * voices … and I also want to be able to change speed"*). Pure half of the voice-out feature:
 * the persisted rate is clamped to a range that stays intelligible over the watch speaker
 * (Android's `setSpeechRate` is a multiplier around the engine default; 1.05 was the
 * pre-feature hardcoded value, so the default row keeps today's exact behavior), and the chip
 * row runs the three labels below. Voice selection is engine-supplied (`TextToSpeech.voices`)
 * — the name rides along verbatim; blank means "whatever the engine picked".
 */
object TtsConfig {

    /** Today's shipped rate — DEFAULT so nobody's ear notices the upgrade. */
    const val DEFAULT_RATE = 1.05f

    /** Persisted rate lives in this band; outside it engines garble or crawl. */
    const val MIN_RATE = 0.5f
    const val MAX_RATE = 2.0f

    /** Settings ▸ Voice-out ▸ reply speed chips (label → rate). */
    val SPEED_CHIPS = listOf("slow" to 0.75f, "normal" to DEFAULT_RATE, "fast" to 1.4f)

    fun clampRate(v: Float): Float =
        if (v.isNaN()) DEFAULT_RATE else v.coerceIn(MIN_RATE, MAX_RATE)

    /** Preview line spoken when the owner picks a voice/speed — short, and tests rate live. */
    const val PREVIEW_TEXT = "Hermes online. This is how I will sound."

    /**
     * REPLY VOLUME (the mute-lane double slider, owner 2026-10-29): an app-side utterance scale
     * (TTS KEY_PARAM_VOLUME), NOT the system stream — "the volume of the replies … within the
     * Hermes Watch app only". 1 = today (full stream while speaking); never below 0.
     */
    const val DEFAULT_VOLUME = 1f

    fun clampVolume(v: Float): Float =
        if (v.isNaN()) DEFAULT_VOLUME else v.coerceIn(0f, 1f)

    /**
     * AUDIO TAPER (batch-6 #24, owner 2026-10-30: *"the audio slider for voice out is linear,
     * fix that… audio taper / log-law volume curve — map the slider to dBFS instead of
     * amplitude"*). A linear slider spends half its travel in a range the ear barely notices
     * (the ear answers RATIOS: −10 dB = half as loud, but still 31.6 % amplitude), and the last
     * 20 % does everything (50→100 % is only +6 dB). The slider position x is therefore a
     * position on a dB sweep with a [VOLUME_FLOOR_DB] floor, converted to amplitude — what DAWs
     * and OS mixers do:
     *
     *     gain = 10^((floor_dB · (1 − x)) / 20),  gain = 0 at x = 0 (hard mute, no FP dust)
     *
     * At −60 dB the 5 % first stop is 0.14 % amplitude (−57 dB, genuinely a whisper), 50 % is
     * 3.2 %, 90 % is 50 %. The PERSISTED number (global slider and the per-agent 5 % grid) is
     * now the slider POSITION — the old values re-read as positions (100 stays 100; a stored
     * 40 becomes a gentle 1.6 % until redialed, which the taper's owner will do anyway).
     */
    const val VOLUME_FLOOR_DB = -60f

    /** Slider position (0..1, 0 = mute) → KEY_PARAM_VOLUME amplitude via the dB sweep. */
    fun gainFromPosition(pos: Float): Float {
        val x = clampVolume(pos)
        if (x <= 0f) return 0f // HARD ZERO — the mute flag, never 10^(-inf) floating dust
        if (x >= 0.9999f) return 1f
        return Math.pow(
            10.0, (VOLUME_FLOOR_DB * (1f - x) / 20f).toDouble(),
        ).toFloat().coerceIn(0f, 1f)
    }

    /** Inverse (curve position for an amplitude) — round-trips the table, clamped like the map. */
    fun positionForGain(gain: Float): Float = when {
        gain <= 0f -> 0f
        gain >= 1f -> 1f
        else -> (1f - kotlin.math.log10(gain) / (VOLUME_FLOOR_DB / 20f)).coerceIn(0f, 1f)
    }

    /**
     * MUTE IS MUTE (batch-5 #19, owner 2026-10-30: *"I have it muted, but it still seems like
     * it's talking to me from the orb … if it's muted, the talking shit shouldn't happen"*). At
     * 0 % reply volume the old code still QUEUED every sentence with KEY_PARAM_VOLUME=0 — no
     * sound, but the orb pulsed its STOP/"mute" face and the karaoke cursor marched, so the watch
     * silently lip-synced like it was shouting. 0 % therefore means: speak NOTHING — no queue, no
     * `_speaking`, no orb mouth, no follow. The 5 % grid makes equality the honest test.
     */
    fun muted(volume: Float): Boolean = clampVolume(volume) <= 0f

    /**
     * PER-AGENT REPLY VOLUME (owner 2026-10-29): each agent may ride its own level (the wear
     * agent polite at 40 %, the work agent loud at 100 %); unset agents inherit the global
     * slider. Stored as a 5 % grid so a wrist-sized slider can never land on a phantom value:
     * `tts_volumes` = "wear=40,default=100".
     */
    const val VOLUME_STEP_PCT = 5

    fun volumePercent(v: Float): Int =
        (clampVolume(v) * 100f / VOLUME_STEP_PCT).roundToInt() * VOLUME_STEP_PCT

    fun volumeFromPercent(pct: Int): Float =
        (pct.coerceIn(0, 100) / 100f)

    /** Map → "wear=40,default=100" (sorted for a stable on-disk form, values on the grid). */
    fun encodeVolumes(volumes: Map<String, Int>): String =
        volumes.entries.sortedBy { it.key }
            .joinToString(",") { "${it.key}=${(it.value / VOLUME_STEP_PCT * VOLUME_STEP_PCT).coerceIn(0, 100)}" }

    /** Garbage-tolerant inverse: bad pairs skip, values quantize onto the 5 % grid. */
    fun decodeVolumes(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",").mapNotNull { part ->
            val agent = part.substringBefore('=', "").trim()
            val pct = part.substringAfter('=', "").trim().toIntOrNull()
            if (agent.isEmpty() || pct == null) null
            else agent to (pct / VOLUME_STEP_PCT * VOLUME_STEP_PCT).coerceIn(0, 100)
        }.toMap()
    }

    /** Chip labels resolve known rates and unknown ones stay neutral. */
    fun labelFor(rate: Float): String =
        SPEED_CHIPS.firstOrNull { it.second == clampRate(rate) }?.first ?: "custom"

    /**
     * SLIDER MAPPING (owner 2026-10-29: "can that be a slider you can drag from very slow to
     * fast — the buttons don't all fit"): fraction of track width ↔ rate. 0 = [MIN_RATE]
     * (0.5×, crawl), 1 = [MAX_RATE] (2.0×, chipmunk), the shipped default sits ~37 % along.
     */
    fun rateFromFraction(f: Float): Float =
        clampRate(if (f.isNaN()) DEFAULT_RATE else MIN_RATE + f.coerceIn(0f, 1f) * (MAX_RATE - MIN_RATE))

    fun fractionFor(rate: Float): Float =
        ((clampRate(rate) - MIN_RATE) / (MAX_RATE - MIN_RATE)).coerceIn(0f, 1f)
}
