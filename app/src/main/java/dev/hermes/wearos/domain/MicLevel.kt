package dev.hermes.wearos.domain

/**
 * MIC LEVEL MATH (batch-4 #13, owner on the gateway/whisper listen: *"it's not giving me the
 * streaming text, so I don't know if it's hearing me or not"*). A buffered engine shows no text
 * until the stop, so the listening overlay needs an honest PROOF OF LIFE: the mic is reading.
 * Pure math (no android imports) so the mapping is JVM-tested; voice/SttLevel is the 2-line
 * in-memory holder the readers update from their frame loops and the overlay collects.
 */
object MicLevel {

    /**
     * Normalized loudness 0..1 from a 16 kHz mono PCM16 LITTLE-ENDIAN buffer of [n] bytes.
     * sqrt-compressed (like an audio meter): talk-normal speech RMS is ~0.02–0.08 linear,
     * which would sit invisible at 0 on a linear bar — √rms puts speech around 0.15–0.3 and
     * peaks near 1, matching what a meter should LOOK like. Odd trailing byte is ignored.
     */
    fun rmsNormalized(pcm16: ByteArray, n: Int): Float {
        val samples = n / 2
        if (samples <= 0) return 0f
        var sum = 0.0
        var i = 0
        while (i < samples * 2) {
            val s = (pcm16[i].toInt() and 0xFF) or (pcm16[i + 1].toInt() shl 8)
            val v = if (s >= 0x8000) s - 0x10000 else s
            sum += v.toDouble() * v
            i += 2
        }
        val rms = kotlin.math.sqrt(sum / samples) / 32768.0
        return kotlin.math.sqrt(rms).toFloat().coerceIn(0f, 1f)
    }

    /** Same meter for the ShortArray reader (Vosk's loop reads shorts, not bytes). */
    fun fromShorts(buf: ShortArray, n: Int): Float {
        if (n <= 0) return 0f
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
        val rms = kotlin.math.sqrt(sum / n) / 32768.0
        return kotlin.math.sqrt(rms).toFloat().coerceIn(0f, 1f)
    }

    /**
     * SpeechRecognizer.onRmsChanged reports dB-ish in roughly [-2, 10] (framework convention);
     * map that window to 0..1 so the same meter dot works on the System provider.
     */
    fun fromDb(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
}
