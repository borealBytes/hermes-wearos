package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure halves of the in-app Vosk install (domain/VoskInstall): zip path mapping (the security-
 * relevant one), marker verification, resume math, progress fractions, and the labels the card
 * prints. The OkHttp/unzip driver is Android-side by nature (RandomAccessFile/ZipFile).
 */
class VoskInstallTest {

    @Test fun modelRootStrippedToRelPath() {
        assertEquals(
            "am/final.mdl",
            VoskInstall.mappedRelPath("vosk-model-small-en-us-0.15/am/final.mdl"),
        )
        assertEquals(
            "conf/model.conf",
            VoskInstall.mappedRelPath("vosk-model-small-en-us-0.15/conf/model.conf"),
        )
    }

    @Test fun directoriesJunkAndForeignRootsSkip() {
        assertNull(VoskInstall.mappedRelPath("vosk-model-small-en-us-0.15/"))            // root dir entry
        assertNull(VoskInstall.mappedRelPath("vosk-model-small-en-us-0.15/am/"))         // inner dir
        assertNull(VoskInstall.mappedRelPath("vosk-model-other-0.9/am/final.mdl"))       // wrong root
        assertNull(VoskInstall.mappedRelPath("am/final.mdl"))                             // no root at all
        assertNull(VoskInstall.mappedRelPath("__MACOSX/._final.mdl"))
        assertNull(VoskInstall.mappedRelPath("vosk-model-small-en-us-0.15/./am/.DS_Store"))
    }

    @Test fun traversalNeverEscapesTheModelDir() {
        assertNull(VoskInstall.mappedRelPath("vosk-model-small-en-us-0.15/../../init.appcompat"))
        assertNull(VoskInstall.mappedRelPath("vosk-model-small-en-us-0.15/am/../escape"))
    }

    @Test fun markerVerificationNeedsAnAcousticModel() {
        assertTrue(VoskInstall.hasModelMarker(setOf("am/final.mdl", "conf/model.conf")))
        assertTrue(VoskInstall.hasModelMarker(setOf("final.mdl")))
        assertFalse("conf-only unpack is NOT a model", VoskInstall.hasModelMarker(setOf("conf/model.conf")))
    }

    @Test fun resumeHeaderOnlyWhenWeHaveBytes() {
        assertNull(VoskInstall.rangeFor(0))
        assertEquals("bytes=1048576-", VoskInstall.rangeFor(1048576))
    }

    @Test fun downloadFractionCountsWholeZipOnResume() {
        // Mid-session: server serves the tail (total = remaining); client also holds the head.
        val d = VoskInstallState.Download(done = 5_000_000, total = 20_000_000, resumeFrom = 15_000_000)
        assertEquals(20, d.received / 1_000_000)      // head + tail on disk
        assertEquals(35, d.overall / 1_000_000)        // full zip
        assertEquals(57, d.fraction)                   // 20M/35M
        assertEquals(99, VoskInstallState.Download(25_000_000, 20_000_000, 15_000_000).fraction) // over→clamp
        assertEquals(-1, VoskInstallState.Download(123, 0).fraction) // no Content-Length, no lies
    }

    @Test fun labelsTellTheWearerWhatHappens() {
        assertEquals("not installed", VoskInstall.statusLabel(VoskInstallState.Missing))
        assertEquals("unpacking…", VoskInstall.statusLabel(VoskInstallState.Unpacking))
        assertEquals("installed · works offline", VoskInstall.statusLabel(VoskInstallState.Ready))
        assertEquals("failed: gateway unreachable", VoskInstall.statusLabel(VoskInstallState.Failed("gateway unreachable")))
        assertTrue(
            VoskInstall.statusLabel(VoskInstallState.Download(10_000_000, 30_000_000))
                .startsWith("downloading 33% · 10 MB of 30 MB"),
        )
        assertTrue(VoskInstall.statusLabel(VoskInstallState.Download(10_000_000, 0)).startsWith("downloading"))
    }

    @Test fun sizesAreRoundedToWhatHumansActOn() {
        assertEquals("41 MB", VoskInstall.humanBytes(41_205_931))
        assertEquals("612 KB", VoskInstall.humanBytes(611_999))
        assertEquals("900 B", VoskInstall.humanBytes(900))
    }
}
