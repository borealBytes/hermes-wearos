package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PAIRING PIN + handle math (SPEC §4, S5): the mint shape (6 digits, crypto-random, no leading
 * zero), the 5-min TTL edge, and the funnel-handle normalization the Pair screen / AppSettings /
 * PairingClient all share. Pure, so the protocol's inputs are pinned on the JVM, not a wrist.
 */
class PairingCodeTest {

    // ── mint ─────────────────────────────────────────────────────────────────────────────────
    @Test
    fun `mint is exactly six digits with no leading zero for any rng value`() {
        for (raw in listOf(0, 1, 42, 899_999, 900_000, 900_001, -1, -900_000, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val pin = PairingCode.mint { raw }
            assertTrue("$raw → $pin", PairingCode.isPin(pin))
        }
        assertEquals("100000", PairingCode.mint { 0 })
        assertEquals("999999", PairingCode.mint { 899_999 })
        assertEquals("100001", PairingCode.mint { 900_001 }) // wraps mod 900k, never 7 digits
    }

    @Test
    fun `the default rng only ever yields valid pins and varies`() {
        val pins = (1..500).map { PairingCode.mint() }
        assertTrue(pins.all { PairingCode.isPin(it) })
        assertNotEquals("a crypto rng that never varies is broken", 1, pins.distinct().size)
    }

    // ── isPin ────────────────────────────────────────────────────────────────────────────────
    @Test
    fun `isPin accepts 6 digits and rejects everything shorter longer or leading-zero`() {
        assertTrue(PairingCode.isPin("123456"))
        assertTrue(PairingCode.isPin("999999"))
        assertFalse(PairingCode.isPin("012345")) // leading zero never mints, so never valid
        assertFalse(PairingCode.isPin("12345"))
        assertFalse(PairingCode.isPin("1234567"))
        assertFalse(PairingCode.isPin("12a456"))
        assertFalse(PairingCode.isPin(""))
    }

    // ── TTL ──────────────────────────────────────────────────────────────────────────────────
    @Test
    fun `a pin dies at mint plus 5 minutes exactly`() {
        assertFalse(PairingCode.isExpired(1000L, 1000L + PairingCode.TTL_MS - 1))
        assertTrue(PairingCode.isExpired(1000L, 1000L + PairingCode.TTL_MS))
        assertTrue(PairingCode.isExpired(1000L, 1000L + PairingCode.TTL_MS + 1))
        assertEquals(300_000L, PairingCode.TTL_MS) // SPEC §4: 5-min
    }

    // ── handle normalization ─────────────────────────────────────────────────────────────────
    @Test
    fun `handles normalize from everything a human might paste`() {
        assertEquals("my-home", PairingCode.normalizeHandle("  My-Home "))
        assertEquals("lab", PairingCode.normalizeHandle("lab.ts.net"))
        assertEquals("lab", PairingCode.normalizeHandle("pair.lab.ts.net"))
        assertEquals("lab", PairingCode.normalizeHandle("https://pair.Lab.TS.NET/pair/claim"))
        assertEquals("lab", PairingCode.normalizeHandle("http://pair.lab/"))
        assertEquals("tail1b2c3d4", PairingCode.normalizeHandle("tail1b2c3d4.ts.net"))
        assertEquals("a.b", PairingCode.normalizeHandle("a.b")) // dotted tailnet names stay legal
    }

    @Test
    fun `garbage handles collapse to empty — they feed a hostname, not a prayer`() {
        assertEquals("", PairingCode.normalizeHandle(""))
        assertEquals("", PairingCode.normalizeHandle("   "))
        assertEquals("", PairingCode.normalizeHandle("bad handle")) // space
        assertEquals("", PairingCode.normalizeHandle("-lab"))       // leading dash
        assertEquals("lab", PairingCode.normalizeHandle("lab-"))    // trailing dash trims (pasted text)
        assertEquals("", PairingCode.normalizeHandle("lab!"))
        assertEquals("", PairingCode.normalizeHandle(".ts.net"))
    }
}
