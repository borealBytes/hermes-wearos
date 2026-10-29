package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** The engine → transport → chain rules the voice picker rides on (owner 2026-10-29). */
class VoiceCatalogTest {

    @Test fun tierIsAuthoritative() {
        assertEquals(VoiceCatalog.NETWORK, VoiceCatalog.classify("en-us-x-sfg", 3))
        assertEquals(VoiceCatalog.LOCAL, VoiceCatalog.classify("en-us-x-sfg", 1))
    }

    @Test fun nameConventionCarriesWhenTierUnknown() {
        assertEquals(VoiceCatalog.NETWORK, VoiceCatalog.classify("en-au-x-aub-network", 0))
        assertEquals(VoiceCatalog.NETWORK, VoiceCatalog.classify("en-us-x-ngc-remote", 2))
        assertEquals(VoiceCatalog.LOCAL, VoiceCatalog.classify("en-au-x-aud-local", 0))
        assertEquals(VoiceCatalog.LOCAL, VoiceCatalog.classify("en-gb-x-rjs", 0)) // no signal → local
        assertEquals(VoiceCatalog.LOCAL, VoiceCatalog.classify("en-gb-x-rjs", 2))  // IF_NECESSARY too
    }

    @Test fun chainPreferredThenFallback() {
        assertEquals(listOf("netA", "locA"), VoiceCatalog.chain("netA", "locA"))
    }

    @Test fun chainDropsBlanksDupesAndEmptyFallsToDefault() {
        assertEquals(listOf("onlyA"), VoiceCatalog.chain("onlyA", ""))
        assertEquals(listOf("sameA"), VoiceCatalog.chain("sameA", "sameA"))
        assertEquals(listOf(""), VoiceCatalog.chain("", ""))            // engine default
        assertEquals(listOf("netA"), VoiceCatalog.chain("  netA  ", "")) // trimmed
        assertEquals(listOf("netA", "locA"), VoiceCatalog.chain("", "netA").let { VoiceCatalog.chain("netA", "locA") })
    }

    @Test fun engineLabels() {
        assertEquals("Google", VoiceCatalog.friendlyEngine("Google Text-to-Speech", "com.google.android.tts"))
        assertEquals("Samsung", VoiceCatalog.friendlyEngine("Samsung TTS", "com.samsung.android.texttospeech"))
        assertEquals("Samsung", VoiceCatalog.friendlyEngine("", "com.samsung.sr.universe"))
        assertEquals("Custom Engine", VoiceCatalog.friendlyEngine("Custom Engine", "com.acme.tts"))
    }
}
