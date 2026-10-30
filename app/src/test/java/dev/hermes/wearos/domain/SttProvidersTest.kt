package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch-4 #8: the picker's honesty contract is pure logic — pinned here, not on-device. */
class SttProvidersTest {

    @Test fun `unknown or missing provider id falls back to the offline default`() {
        assertEquals(SttProvider.LOCAL, SttProviders.fromId(null))
        assertEquals(SttProvider.LOCAL, SttProviders.fromId(""))
        assertEquals(SttProvider.LOCAL, SttProviders.fromId("gateway-future"))
        assertEquals(SttProvider.SYSTEM, SttProviders.fromId("system"))
    }

    @Test fun `normalize keeps every real id and collapses junk to the safe default`() {
        assertEquals("local", SttProviders.normalize("local"))
        assertEquals("system", SttProviders.normalize("system"))
        assertEquals("gateway", SttProviders.normalize("gateway")) // the #9 regression this gate exists for
        assertEquals("local", SttProviders.normalize("bogus"))
        assertEquals("local", SttProviders.normalize(null))
    }

    @Test fun `vendor auto-detect names the big stacks, passes the rest through`() {
        assertEquals("Google", SttProviders.vendor("com.google.android.googlequicksearchbox"))
        assertEquals("Samsung", SttProviders.vendor("com.samsung.android.weamp"))
        assertEquals("Microsoft", SttProviders.vendor("com.microsoft.cortana.stt"))
        assertEquals("com.acme.voice", SttProviders.vendor("com.acme.voice"))
        assertEquals("system", SttProviders.vendor(null))
    }

    @Test fun `the system row says OFF-DEVICE with the vendor name`() {
        val sub = SttProviders.subtitle(SttProvider.SYSTEM, true, true, "com.google.android.googlequicksearchbox")
        assertTrue("must be honest about the network: $sub", sub.startsWith("OFF-DEVICE"))
        assertTrue(sub.contains("Google"))
    }

    @Test fun `local row is honest about a missing model too`() {
        assertTrue(SttProviders.subtitle(SttProvider.LOCAL, true, true, null).contains("offline"))
        assertTrue(SttProviders.subtitle(SttProvider.LOCAL, false, true, null).contains("missing"))
    }

    @Test fun `unavailable system recognizer says so instead of naming a vendor`() {
        val sub = SttProviders.subtitle(SttProvider.SYSTEM, true, false, "com.google.android.googlequicksearchbox")
        assertEquals("no recognizer on this watch", sub)
    }

    @Test fun `gateway is the third provider, honestly labelled with the live engine`() {
        assertEquals(SttProvider.GATEWAY, SttProviders.fromId("gateway"))
        val named = SttProviders.subtitle(SttProvider.GATEWAY, true, true, null, "whisper")
        assertTrue(named.contains("whisper")); assertTrue(named.contains("auto-local"))
        val dark = SttProviders.subtitle(SttProvider.GATEWAY, true, true, null, null)
        assertTrue("offline must be honest too: $dark", dark.contains("offline"))
        // Auto-fallback makes it ALWAYS usable — the wearer is never stranded (the contract).
        assertTrue(SttProviders.usable(SttProvider.GATEWAY, false, false))
    }

    @Test fun `usability gates on the engine that was picked, not the other one`() {
        assertTrue(SttProviders.usable(SttProvider.LOCAL, true, false))
        assertFalse(SttProviders.usable(SttProvider.LOCAL, false, true))
        assertTrue(SttProviders.usable(SttProvider.SYSTEM, false, true))
        assertFalse(SttProviders.usable(SttProvider.SYSTEM, true, false))
    }
}
