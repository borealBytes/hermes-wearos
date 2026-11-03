package dev.hermes.wearos.voice

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log

/**
 * STREAMING MIC ENCODER (owner 2026-11-02: "why'd you send the audio as a wave? default to the
 * best current one"). [dev.hermes.wearos.voice.GatewayStt] used to push raw 16 kHz mono PCM16 —
 * 256 kbit/s of wave — through the tailnet. This picks the best encoder the device's MediaCodec
 * list actually carries:
 *
 *  1. **Opus 24 kbit/s** (VoIP application) — the best current speech codec, ~10x lighter than
 *     the wave and kinder to whisper/parakeet than any 24k competitor;
 *  2. **AAC-LC 32 kbit/s** — the every-Android-device fallback (this Wear emulator image ships
 *     an Opus *decoder* but no Opus *encoder*; real phones/watches usually have both);
 *  3. **null** → raw PCM, exactly the legacy wire (watchkit sniffs the format from the first
 *     bytes, so all three lanes coexist — an old watch and a new server, or vice versa).
 *
 * Synchronous by design: [encode] is called from the capture loop with ~100 ms PCM chunks; it
 * slices encoder-sized frames (Opus 20 ms = 640 B, AAC 1024 samples = 2048 B), feeds, and
 * immediately drains, returning wire frames (Ogg pages / ADTS frames) to send in order. Output
 * order is preserved by the caller — for Opus the Ogg header page must lead the stream.
 */
class MicCodec private constructor(
    private val codec: MediaCodec,
    private val kind: Kind,
    private val inFrameBytes: Int,
) {
    enum class Kind { OPUS, AAC }

    private val info = MediaCodec.BufferInfo()
    private var acc = ByteArray(0)
    private var ogg: OggOpusMuxer? = null
    private var oggHeadSent = false
    private var aacProfile = 1 // AAC-LC; re-derived from the CSD when the encoder provides one
    private var inputStarved = 0

    val wireName: String get() = if (kind == Kind.OPUS) "opus" else "aac"

    /** Feed one capture chunk; returns wire frames to send NOW, in order (possibly empty). */
    fun encode(pcm: ByteArray): List<ByteArray> = run(pcm, eos = false)

    /** Final call: flush the encoder and return whatever is left (send before {"end":true}). */
    fun flush(): List<ByteArray> = run(ByteArray(0), eos = true)

    private fun run(pcm: ByteArray, eos: Boolean): List<ByteArray> {
        val out = ArrayList<ByteArray>(4)
        acc += pcm
        var o = 0
        while (acc.size - o >= inFrameBytes) {
            if (!feed(acc, o)) break // input starved: keep the remainder for the next call
            o += inFrameBytes
            drain(out, timeoutUs = 0L, eosSeen = null)
        }
        if (o > 0) acc = acc.copyOfRange(o, acc.size)
        if (eos && o == acc.size) {
            queueEos()
            drain(out, timeoutUs = 5_000L, eosSeen = BooleanArray(1))
        }
        if (kind == Kind.OPUS) {
            oggHeaderIfNeeded(out)
            ogg!!.flushPage()?.let { out += it }
        }
        return out
    }

    private fun feed(data: ByteArray, off: Int): Boolean {
        var idx = codec.dequeueInputBuffer(0)
        var tries = 0
        while (idx < 0 && tries++ < 8) { // drain first, then retry — never block the mic loop
            drain(ArrayList(), timeoutUs = 2_000L, eosSeen = null)
            idx = codec.dequeueInputBuffer(2_000)
        }
        if (idx < 0) {
            if (inputStarved++ == 0) Log.w(TAG, "mic codec input starved — buffering")
            return false
        }
        inputStarved = 0
        codec.getInputBuffer(idx)?.let { b ->
            b.position(0); b.put(data, off, inFrameBytes)
            codec.queueInputBuffer(idx, 0, inFrameBytes, fedUs, 0)
            fedUs += inFrameBytes.toLong() / 2 * 1_000_000L / CodecMuxers.SAMPLE_RATE // mono PCM16 → µs
        }
        return true
    }

    private fun queueEos() {
        val idx = codec.dequeueInputBuffer(20_000)
        if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    private fun drain(out: MutableList<ByteArray>, timeoutUs: Long, eosSeen: BooleanArray?) {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // The CSD arrives as a CODEC_CONFIG buffer (onCsd); the format event itself
                    // carries nothing the wire needs.
                }
                idx >= 0 -> {
                    val raw = ByteArray(info.size).also { codec.getOutputBuffer(idx)?.get(it) }
                    when {
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> onCsd(raw)
                        info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 -> eosSeen?.set(0, true)
                        info.size > 0 -> onPacket(raw, out)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (eosSeen?.get(0) == true) return
                }
                else -> return // INFO_OUTPUT_BUFFERS_CHANGED etc. — the deprecated getters work anyway
            }
        }
    }

    private fun onCsd(csd: ByteArray) {
        when (kind) {
            Kind.AAC -> if (csd.size >= 2) {
                // AudioSpecificConfig: 5-bit audioObjecttype — ADTS profile field = objectType - 1.
                val objType = (csd[0].toInt() ushr 3) and 0x1F
                if (objType in 1..4) aacProfile = objType - 1
            }
            Kind.OPUS -> {
                val preskip = when {
                    csd.size >= 12 && String(csd, 0, 8, Charsets.US_ASCII) == "OpusHead" ->
                        (csd[10].toInt() and 0xFF) or (csd[11].toInt() and 0xFF shl 8)
                    csd.size == 2 -> (csd[0].toInt() and 0xFF) or (csd[1].toInt() and 0xFF shl 8)
                    else -> 0
                }
                if (ogg == null) ogg = OggOpusMuxer(preskip)
            }
        }
    }

    private fun onPacket(bytes: ByteArray, out: MutableList<ByteArray>) {
        when (kind) {
            Kind.OPUS -> {
                // An encoder with no CSD owes us the standard 312-sample preskip at best; 0 only
                // costs whisper a few ms of silence, never a wrong word.
                if (ogg == null) ogg = OggOpusMuxer(preskip = 0)
                ogg!!.packet(bytes)
            }
            Kind.AAC -> out += Adts.frame(bytes, profile = aacProfile)
        }
    }

    private fun oggHeaderIfNeeded(out: MutableList<ByteArray>) {
        if (kind != Kind.OPUS || oggHeadSent) return
        oggHeadSent = true
        if (ogg == null) ogg = OggOpusMuxer(preskip = 0)
        out += ogg!!.headerPages()
    }

    fun release() { runCatching { codec.stop() }; runCatching { codec.release() } }

    private var fedUs = 0L

    companion object {
        private const val TAG = "HermesWear"
        private const val OPUS_BITRATE = 24_000
        private const val AAC_BITRATE = 32_000

        /** Best available encoder, or null = raw PCM wire. Never throws; logs the choice. */
        fun create(): MicCodec? = tryOpus() ?: tryAac()
            .alsoIfNull { Log.i(TAG, "mic codec: none found — streaming raw PCM") }

        private fun tryOpus(): MicCodec? {
            var c: MediaCodec? = null
            return runCatching {
                c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, CodecMuxers.SAMPLE_RATE, 1)
                f.setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BITRATE)
                f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 640) // 20 ms @ 16k mono
                // No SDK constant for the Opus application hint (it is a raw format key); 2048 =
                // OPUS_APPLICATION_VOIP — tuned for speech, which is exactly what a mic lane is.
                runCatching { f.setInteger("opus-application", 2048) }
                c!!.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                c!!.start()
                Log.i(TAG, "mic codec: opus ${OPUS_BITRATE / 1000}k (10x lighter than the wave)")
                MicCodec(c!!, Kind.OPUS, 640)
            }.onFailure { runCatching { c?.release() } }.getOrNull()
        }

        private fun tryAac(): MicCodec? {
            var c: MediaCodec? = null
            return runCatching {
                c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, CodecMuxers.SAMPLE_RATE, 1)
                f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                f.setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
                f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2048) // 1024 samples @ 16k mono
                runCatching { f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC) }
                c!!.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                c!!.start()
                Log.i(TAG, "mic codec: aac-lc ${AAC_BITRATE / 1000}k")
                MicCodec(c!!, Kind.AAC, 2048)
            }.onFailure { runCatching { c?.release() } }.getOrNull()
        }

        private inline fun <T> T?.alsoIfNull(block: () -> Unit): T? { if (this == null) block(); return this }
    }
}
