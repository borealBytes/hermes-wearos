package dev.hermes.wearos.voice

import dev.hermes.wearos.domain.MicLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * THE "IT IS HEARING YOU" METER (batch-4 #13). Every SttSource's mic loop publishes per-read
 * loudness here (math in domain/MicLevel); PttOverlay's top-row dot rides it. In-memory like
 * SttProviders.current — a 10 Hz scalar with one reader needs no persistence and no plumbing
 * through the event flow, and the overlay must show life even when the ENGINE is buffered and
 * sends no text (gateway whisper's exact shape).
 */
object SttLevel {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    fun publish(value: Float) { _level.value = value.coerceIn(0f, 1f) }

    /** Session end (any reason): the meter drops to silence so a stale bar never lingers. */
    fun reset() { _level.value = 0f }

    internal fun fromPcm16(frame: ByteArray, n: Int) = MicLevel.rmsNormalized(frame, n)
}
