package dev.hermes.wearos.voice

import java.util.Random

/**
 * MIC WIRE CODEC FRAMERS (owner 2026-11-02: "why are we sending the audio as a wave? default to
 * the best current one") — the watch's STT lane used to push RAW 16 kHz mono PCM16 through the
 * tailnet: 256 kbit/s, ~640 KB per 20 s utterance. Now it encodes with the best encoder the
 * watch's MediaCodec list actually carries — Opus first (the best current speech codec), AAC as
 * the every-device fallback — and these two pure helpers put the encoder's raw packets on the
 * wire in stream-friendly containers the server sniffs (watchkit `stt_stream_ws.mjs`):
 *
 *  - [OggOpusMuxer]: raw Opus packets → Ogg pages (OpusHead/OpusTags BOS page first). The server
 *    demuxes with ffmpeg `-f ogg`. Chosen over MP4/WebM muxing because MediaMuxer supports
 *    neither for Opus, and an Ogg page is a self-delimiting flush unit — perfect over a WebSocket.
 *  - [Adts]: raw AAC frames → ADTS (7-byte header per frame). MediaMuxer *can* do MP4/AAC but
 *    MP4 needs its moov atom at session end — wrong shape for live streaming.
 *
 * The server still accepts raw PCM (sniff falls through), so an encoder-less device or an older
 * watch keeps working — format negotiation is the first bytes on the socket, no handshake field.
 */
internal object CodecMuxers {
    /**
     * Standard 44-byte RIFF/WAVE header for mono PCM16LE + the data — pure JVM. The
     * enroll screen wraps its AudioRecord buffer in this before POSTing: the lane's
     * create_card validates THIS header (3–60 s floors are proven gateway-side).
     */
    fun wav(pcm: ByteArray, rate: Int = 16000): ByteArray {
        fun i32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(),
                                      (v shr 16).toByte(), (v shr 24).toByte())
        fun i16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        val data = pcm.size
        return "RIFF".toByteArray() + i32(36 + data) + "WAVE".toByteArray() +
            "fmt ".toByteArray() + i32(16) + i16(1) + i16(1) + i32(rate) +
            i32(rate * 2) + i16(2) + i16(16) + "data".toByteArray() + i32(data) + pcm
    }

    const val SAMPLE_RATE = 16_000
    /** Opus granule positions are always on a 48 kHz clock, whatever the capture rate (RFC 7845). */
    const val GRANULE_PER_FRAME_48K = 960 // one 20 ms Opus frame
}

/** Ogg crc32 (poly 0x04c11db7, init 0, MSB-first, no final xor — the Ogg variant, not zlib). */
internal object OggCrc {
    private val TABLE = IntArray(256) { i ->
        var c = i shl 24
        repeat(8) { c = if (c ushr 31 != 0) (c shl 1) xor 0x04c1_1db7 else c shl 1 }
        c
    }

    fun crc(data: ByteArray, off: Int = 0, len: Int = data.size - off): Int {
        var c = 0
        for (i in off until off + len) c = (c shl 8) xor TABLE[((c ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF]
        return c
    }
}

/**
 * Buffers raw Opus packets and emits complete Ogg pages. One page per capture tick (five 20 ms
 * frames per 100 ms chunk) keeps packetization honest without a page-per-packet tax.
 */
internal class OggOpusMuxer(
    private val preskip: Int,
    private val inputSampleRate: Int = CodecMuxers.SAMPLE_RATE,
    private val serial: Int = Random().nextInt(),
) {
    private var seq = 0
    private var granule = 0L
    private val packetSizes = ArrayList<Int>()
    private val packetBytes = ArrayList<ByteArray>()

    /**
     * BOS pages: OpusHead ALONE on page 0, OpusTags alone on page 1 (RFC 7845 demands the id
     * header ends its page — sharing it makes ffmpeg read the tags as a second OpusHead and
     * reject the stream). Both carry granule -1 (spec: pages with no audio samples).
     */
    fun headerPages(): List<ByteArray> {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 1) + // version, channels: mono
            le16(preskip) + le32(inputSampleRate) /* informational */ + le16(0) /* gain */ +
            byteArrayOf(0) // channel mapping family
        val vendor = "hermes-wearos".toByteArray()
        val tags = "OpusTags".toByteArray() + le32(vendor.size) + vendor + le32(0) // no comments
        return listOf(
            pageOf(listOf(head.size), head, granule = -1, bos = true, seq = seq++, serial = serial),
            pageOf(listOf(tags.size), tags, granule = -1, bos = false, seq = seq++, serial = serial),
        )
    }

    /** One raw Opus packet (MediaCodec output, 20 ms). Queued until [flushPage]. */
    fun packet(bytes: ByteArray) {
        packetSizes += bytes.size
        packetBytes += bytes
    }

    /** Closes the current page (nil if nothing queued). */
    fun flushPage(): ByteArray? {
        if (packetSizes.isEmpty()) return null
        granule += CodecMuxers.GRANULE_PER_FRAME_48K.toLong() * packetSizes.size
        val p = pageOf(packetSizes.toList(), packetBytes.fold(ByteArray(0)) { a, b -> a + b },
            granule = granule, bos = false, seq = seq++, serial = serial)
        packetSizes.clear(); packetBytes.clear()
        return p
    }

    companion object {
        /**
         * Ogg page assembly, exposed for tests: `packetLens` lacing + body bytes + CRC over the
         * whole page with the checksum field zeroed.
         */
        fun pageOf(packetLens: List<Int>, body: ByteArray, granule: Long, bos: Boolean, seq: Int, serial: Int): ByteArray {
            // RFC 7845 segment lacing: floor(n/255) segments of 255 + a final <255 remainder.
            // A size that is an exact multiple of 255 must be followed by a 0-length segment.
            val segs = ArrayList<Int>()
            for (n in packetLens) {
                repeat(n / 255) { segs += 255 }
                segs += n % 255
            }
            val hdr = ByteArray(27 + segs.size)
            hdr[0] = 'O'.code.toByte(); hdr[1] = 'g'.code.toByte(); hdr[2] = 'g'.code.toByte(); hdr[3] = 'S'.code.toByte()
            hdr[4] = 0 // stream version
            hdr[5] = if (bos) 0x02 else 0x00
            putLe64(hdr, 6, granule)
            putLe32(hdr, 14, serial)
            putLe32(hdr, 18, seq)
            // 22..25: checksum (zeroed here), filled below
            hdr[26] = segs.size.toByte()
            segs.forEachIndexed { i, v -> hdr[27 + i] = v.toByte() }
            val page = hdr + body
            val crc = OggCrc.crc(page)
            putLe32(page, 22, crc)
            return page
        }

        private fun putLe32(b: ByteArray, at: Int, v: Int) {
            b[at] = (v and 0xFF).toByte(); b[at + 1] = (v ushr 8 and 0xFF).toByte()
            b[at + 2] = (v ushr 16 and 0xFF).toByte(); b[at + 3] = (v ushr 24 and 0xFF).toByte()
        }

        private fun putLe64(b: ByteArray, at: Int, v: Long) {
            for (i in 0 until 8) b[at + i] = (v ushr (8 * i) and 0xFF).toByte()
        }

        private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v ushr 8 and 0xFF).toByte())
        private fun le32(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), (v ushr 8 and 0xFF).toByte(),
            (v ushr 16 and 0xFF).toByte(), (v ushr 24 and 0xFF).toByte(),
        )
    }
}

/**
 * ADTS wrapper for raw AAC frames (ISO/IEC 14496-3). Byte layout verified against ffmpeg's
 * adts muxer on this box (16 kHz mono AAC-LC frame of 321 B begins FF F1 60 40 28 3F FC):
 * MPEG-2 ID, no CRC, VBR marker 0x1F3F. [profile]/[sfIdx] default to AAC-LC @ 16 kHz.
 */
internal object Adts {
    const val HEADER_LEN = 7
    const val SF_16000 = 8

    fun frame(aac: ByteArray, profile: Int = 1, sfIdx: Int = SF_16000, channels: Int = 1): ByteArray {
        val len = aac.size + HEADER_LEN
        return ByteArray(HEADER_LEN).apply {
            this[0] = 0xFF.toByte()
            this[1] = 0xF1.toByte() // MPEG-2 ID, layer 0, protection_absent
            this[2] = (((profile and 3) shl 6) or ((sfIdx and 15) shl 2) or ((channels ushr 2) and 1)).toByte()
            this[3] = (((channels and 3) shl 6) or ((len ushr 11) and 15)).toByte()
            this[4] = (len ushr 3 and 0xFF).toByte()
            this[5] = (((len and 7) shl 5) or 0x1F).toByte() // 0x1F..0xFC = "buffer fullness unknown" (VBR)
            this[6] = 0xFC.toByte()
        } + aac
    }
}
