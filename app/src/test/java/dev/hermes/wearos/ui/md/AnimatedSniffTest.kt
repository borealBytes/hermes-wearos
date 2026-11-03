package dev.hermes.wearos.ui.md

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The container sniff (SPEC §12.14 GIF epic): "does it move?" answered from BYTES ONLY, because
 * deciding it the old way (decode → inspect) would allocate a full-size Bitmap for every still
 * photo on the wrist just to learn it was a JPEG.
 */
class AnimatedSniffTest {

    private fun ascii(s: String) = s.toByteArray()
    private fun riffWebp(flags: Byte, chunk: String = "VP8X"): ByteArray {
        val b = ByteArray(24)
        "RIFF".toByteArray().copyInto(b, 0)
        "WEBP".toByteArray().copyInto(b, 8)
        chunk.toByteArray().copyInto(b, 12)
        b[20] = flags
        return b
    }

    @Test fun `gif87a and gif89a headers are treated as films`() {
        assertTrue(looksAnimated(ascii("GIF89a....")))
        assertTrue(looksAnimated(ascii("GIF87a....")))
    }

    @Test fun `webp animates only when the VP8X animation bit is set`() {
        assertTrue(looksAnimated(riffWebp(0b0000_0010)))   // bit 1 = animation
        assertFalse(looksAnimated(riffWebp(0b0000_0100)))  // ICC flag only
        assertFalse(looksAnimated(riffWebp(0b0000_0010, "VP8 "))) // lossy chunk can't animate
    }

    @Test fun `stills and junk never claim animation`() {
        assertFalse(looksAnimated(ascii("‰PNG............")))
        assertFalse(looksAnimated(ascii(" JPEG..")))
        assertFalse(looksAnimated(ByteArray(0)))
        assertFalse(looksAnimated(ascii("GI")))            // short header can't false-positive
        assertFalse(looksAnimated(riffWebp(0b0000_0010).copyOf(20))) // truncated before flags
    }
}
