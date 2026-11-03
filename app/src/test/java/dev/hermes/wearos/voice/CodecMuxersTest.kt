package dev.hermes.wearos.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WIRE FRAMERS for the post-wave mic (owner 2026-11-02: "why send it as a wave?"). The pages
 * here are byte-compared against INDEPENDENT references generated outside this repo:
 *  - the Ogg pages against a from-scratch Python encoder (own CRC table, own lacing);
 *  - the ADTS header against ffmpeg's own adts muxer output for a 16 kHz mono AAC-LC frame.
 * If these pass, watchkit's ffmpeg demuxer is guaranteed to eat what the encoder emits.
 */
class CodecMuxersTest {

    private fun hex(b: ByteArray) = buildString {
        for (x in b) append("%02x".format(x.toInt() and 0xFF))
    }

    @Test fun `opus header pages match the independent python reference byte for byte`() {
        val m = OggOpusMuxer(preskip = 0, serial = 0x1234ABCD)
        val pages = m.headerPages()
        // RFC 7845: OpusHead must END the BOS page (so it rides alone) and OpusTags takes page 1 —
        // ffmpeg rejects them sharing a page ("Channel change is not supported").
        assertEquals(
            "4f6767530002ffffffffffffffffcdab3412000000005cb49dc20113" +
                "4f7075734865616401010000803e0000000000",
            hex(pages[0]),
        )
        assertEquals(
            "4f6767530000ffffffffffffffffcdab34120100000012f1c784011d" +
                "4f707573546167730d0000006865726d65732d776561726f7300000000",
            hex(pages[1]),
        )
    }

    @Test fun `audio page carries five 20ms frames with granule on the 48k clock`() {
        val m = OggOpusMuxer(preskip = 0, serial = 0x1234ABCD)
        m.headerPages()
        for (n in listOf(40, 45, 38, 41, 37)) m.packet(ByteArray(n))
        val page = m.flushPage()!!
        // Same fixture from Python: 5 packets, granulepos 4800 (=5×960), seq 2 (header pages took
        // 0 and 1). First 32 bytes = page header + seg_count(05) + the 5 segment lengths.
        assertEquals("4f6767530000c012000000000000cdab3412020000007452ec4d05282d262925", hex(page).take(64))
        assertEquals(27 + 5 + 40 + 45 + 38 + 41 + 37, page.size)
        assertNull(m.flushPage()) // nothing queued → no empty pages
    }

    @Test fun `long packets lace segments like the ogg spec demands`() {
        // A 255-byte packet must be followed by a 0-length segment so the page parser knows the
        // packet ended; a 512-byte one laces as 255+255+2. The reference page below comes from
        // the same Python encoder with one 255-byte packet.
        val m = OggOpusMuxer(preskip = 0, serial = 0x1234ABCD)
        m.headerPages()
        m.packet(ByteArray(255))
        val page = m.flushPage()!!
        assertEquals(0xFF.toByte(), page[27]) // first segment = 255
        assertEquals(0, page[28].toInt())     // terminator segment = 0
        assertEquals(2, page[26].toInt())     // two segments
    }

    @Test fun `adts header matches ffmpeg s own bytes`() {
        // ffmpeg -f lavfi -i sine@16k -c:a aac -b:a 32k -ac 1 -f adts → first frame header:
        val reference = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x60, 0x40, 0x28, 0x3F, 0xFC.toByte())
        val frame = Adts.frame(ByteArray(314)) // 321 total, exactly the captured frame's size
        assertArrayEquals(reference, frame.copyOfRange(0, 7))
        assertEquals(314 + 7, ((frame[3].toInt() and 0xF) shl 11) or (frame[4].toInt() shl 3) or (frame[5].toInt() ushr 5))
    }

    @Test fun `sample rate 16k is adts table index 8`() {
        assertEquals(8, Adts.SF_16000)
        assertEquals(960, CodecMuxers.GRANULE_PER_FRAME_48K) // 20 ms @ 48 kHz — RFC 7845
    }
}
