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
