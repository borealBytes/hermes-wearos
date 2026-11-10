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

    @Test fun laneDoors() {
        // profile path and api port fall away; the lane is host+lane-port, always
        assertEquals("http://tuxdesktop.tailb12aa5.ts.net:42654",
            VoiceCatalog.laneBase("http://tuxdesktop.tailb12aa5.ts.net:8642/p/wear"))
        assertEquals("https://gw.example:42654", VoiceCatalog.laneBase("https://gw.example"))
        assertEquals("http://100.69.144.33:42654",
            VoiceCatalog.laneBase("http://100.69.144.33:8642", lanePort = 42654))
        // refusals: nothing to dial
        assertEquals("", VoiceCatalog.laneBase(""))
        assertEquals("", VoiceCatalog.laneBase("tuxdesktop.tailb12aa5.ts.net:8642"))
        assertEquals("", VoiceCatalog.laneBase("ftp://gw.example"))
        assertEquals("", VoiceCatalog.laneBase("not a url at all"))
    }
}
