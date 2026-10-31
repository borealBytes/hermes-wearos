package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch-6 #25 — the desktop watch. The owner's rule is the spine of every test: the ONLY
 * allowed silence is the session top-of-screen on the chat page; everything else that happens
 * on the desktop must buzz, exactly once.
 */
class AwayWatchTest {

    private fun park(chat: String, tool: String = "terminal", args: String = "rm -rf /tmp/x") =
        AwayWatch.Parked("default", chat, "Desk work", tool, args)

    private fun pulse(chat: String, count: Int, role: String = "assistant") =
        AwayWatch.Pulse("default", chat, "Desk work", count, role)

    private fun inbox(parked: List<AwayWatch.Parked> = emptyList(), pulses: List<AwayWatch.Pulse> = emptyList()) =
        AwayWatch.Inbox(parked, pulses)

    // ── parse: the watchkit beacon body, garbage-tolerantly ──────────────────────
    @Test fun `beacon body parses both families`() {
        val body = """
            {"object":"list",
             "waiting":[{"profile":"default","chat_id":"s1","title":"Fix the build","tool":"terminal","arguments":"rm -rf target","parked_at":1793417646}],
             "pulses":[{"profile":"wear","id":"s2","title":"Haiku","message_count":7,"last_role":"assistant","last_active":1793417000}]}
        """.trimIndent()
        val inx = AwayWatch.parse(body)
        assertEquals(1, inx.parked.size)
        assertEquals("s1", inx.parked[0].chatId)
        assertEquals("rm -rf target", inx.parked[0].arguments)
        assertEquals(1, inx.pulses.size)
        assertEquals(7, inx.pulses[0].messageCount)
    }

    @Test fun `junk bodies are an empty inbox, never a crash`() {
        for (b in listOf(null, "", "not json", "{}", "[]", """{"waiting":42}""")) {
            val inx = AwayWatch.parse(b)
            assertTrue(inx.parked.isEmpty() && inx.pulses.isEmpty())
        }
    }

    @Test fun `rows missing their chat id are skipped, siblings survive`() {
        val body = """{"waiting":[{"profile":"default","chat_id":"","tool":"x"},
                                   {"profile":"default","chat_id":"ok","tool":"y"}],
                       "pulses":[{"id":"","message_count":3}]}"""
        val inx = AwayWatch.parse(body)
        assertEquals(listOf("ok"), inx.parked.map { it.chatId })
        assertTrue(inx.pulses.isEmpty())
    }

    // ── diff: the anti-stampede and dedup rules ─────────────────────────────────
    @Test fun `first sweep seeds silently`() {
        val seen = mutableSetOf<String>()
        val counts = mutableMapOf<String, Int>()
        val alerts = AwayWatch.diff(inbox(listOf(park("s1")), listOf(pulse("s2", 5))), seen, counts, emptySet(), firstSweep = true)
        assertTrue(alerts.isEmpty())
        assertEquals(5, counts["s2"]) // cursor rides along — the next look knows the baseline
        assertTrue(seen.isNotEmpty())
    }

    @Test fun `a finished desktop answer buzzes once, not every poll`() {
        val seen = mutableSetOf<String>()
        val counts = mutableMapOf<String, Int>()
        AwayWatch.diff(inbox(pulses = listOf(pulse("s1", 4))), seen, counts, emptySet(), firstSweep = true)
        val hit = AwayWatch.diff(inbox(pulses = listOf(pulse("s1", 6))), seen, counts, emptySet(), firstSweep = false)
        assertEquals(1, hit.size)
        assertEquals(AwayWatch.Kind.Finished, hit[0].kind)
        // Same count next poll (the beacon keeps ticking for minutes): quiet.
        assertTrue(AwayWatch.diff(inbox(pulses = listOf(pulse("s1", 6))), seen, counts, emptySet(), firstSweep = false).isEmpty())
    }

    @Test fun `only ANSWERS buzz — mid-turn user and tool tails stay quiet`() {
        val seen = mutableSetOf<String>()
        val counts = mutableMapOf<String, Int>()
        AwayWatch.diff(inbox(pulses = listOf(pulse("s1", 4))), seen, counts, emptySet(), firstSweep = true)
        val u = AwayWatch.diff(inbox(pulses = listOf(pulse("s1", 5, role = "user"))), seen, counts, emptySet(), firstSweep = false)
        assertTrue("user typed ≠ answer ready", u.isEmpty())
        val t = AwayWatch.diff(inbox(pulses = listOf(pulse("s1", 6, role = "tool"))), seen, counts, emptySet(), firstSweep = false)
        assertTrue("tool output ≠ answer ready", t.isEmpty())
        // …and the next assistant completion STILL buzzes (the cursor moved past both).
        val a = AwayWatch.diff(inbox(pulses = listOf(pulse("s1", 7))), seen, counts, emptySet(), firstSweep = false)
        assertEquals(1, a.size)
    }

    @Test fun `approval park buzzes once and a re-park after answer buzzes again`() {
        val seen = mutableSetOf<String>()
        val counts = mutableMapOf<String, Int>()
        AwayWatch.diff(inbox(), seen, counts, emptySet(), firstSweep = true)
        val hit = AwayWatch.diff(inbox(parked = listOf(park("s1"))), seen, counts, emptySet(), firstSweep = false)
        assertEquals(AwayWatch.Kind.Approval, hit.single().kind)
        // Same beacon for the next 15 polls: silence.
        repeat(15) {
            assertTrue(AwayWatch.diff(inbox(parked = listOf(park("s1"))), seen, counts, emptySet(), firstSweep = false).isEmpty())
        }
        // Owner answers on the desktop: the beacon ages out of the inbox.
        AwayWatch.diff(inbox(), seen, counts, emptySet(), firstSweep = false)
        // A NEW park (even the same tool) is news again.
        val again = AwayWatch.diff(inbox(parked = listOf(park("s1"))), seen, counts, emptySet(), firstSweep = false)
        assertEquals(1, again.size)
    }

    @Test fun `a new ask inside one live park buzzes separately`() {
        val seen = mutableSetOf<String>()
        val counts = mutableMapOf<String, Int>()
        AwayWatch.diff(inbox(parked = listOf(park("s1", args = "echo a"))), seen, counts, emptySet(), firstSweep = true)
        AwayWatch.diff(inbox(parked = listOf(park("s1", args = "echo a"))), seen, counts, emptySet(), firstSweep = false) // seed+buzz
        val second = AwayWatch.diff(
            inbox(parked = listOf(park("s1", args = "echo a"), park("s1", tool = "write", args = "big file"))),
            seen, counts, emptySet(), firstSweep = false,
        )
        assertEquals(1, second.size)
        assertEquals("write", second[0].tool)
    }

    // ── the owner's silence rule ─────────────────────────────────────────────────
    @Test fun `the viewed chat is silent for BOTH families but its cursors still ride`() {
        val seen = mutableSetOf<String>()
        val counts = mutableMapOf<String, Int>()
        AwayWatch.diff(inbox(pulses = listOf(pulse("s1", 4))), seen, counts, setOf("s1"), firstSweep = true)
        val none = AwayWatch.diff(
            inbox(parked = listOf(park("s1")), pulses = listOf(pulse("s1", 6))),
            seen, counts, setOf("s1"), firstSweep = false,
        )
        assertTrue("watching it live is the ONE allowed silence", none.isEmpty())
        assertEquals(6, counts["s1"]) // NOT retro-buzzed when the wearer navigates away
        val away = AwayWatch.diff(inbox(pulses = listOf(pulse("s1", 8))), seen, counts, emptySet(), firstSweep = false)
        assertEquals("a LATER answer buzzes once the chat is closed", 1, away.size)
    }

    @Test fun `other chats still buzz while one chat is being watched`() {
        val seen = mutableSetOf<String>()
        val counts = mutableMapOf<String, Int>()
        AwayWatch.diff(inbox(pulses = listOf(pulse("s1", 4), pulse("s2", 4))), seen, counts, emptySet(), firstSweep = true)
        val a = AwayWatch.diff(
            inbox(parked = listOf(park("s2")), pulses = listOf(pulse("s1", 6), pulse("s2", 5))),
            seen, counts, setOf("s1"), firstSweep = false,
        )
        assertEquals(setOf("s2"), a.map { it.chatId }.toSet())
        assertEquals(AwayWatch.Kind.Approval, a[0].kind)
    }

    @Test fun `watch-driven chats never double-buzz from the inbox`() {
        // The caller suppresses chats the watch itself runs; the diff must honor it for both
        // families WITHOUT touching their cursors (dedup state stays coherent).
        val seen = mutableSetOf<String>()
        val counts = mutableMapOf<String, Int>()
        AwayWatch.diff(inbox(parked = listOf(park("mine")), pulses = listOf(pulse("mine", 4))), seen, counts, setOf("mine"), firstSweep = true)
        val a = AwayWatch.diff(inbox(parked = listOf(park("mine")), pulses = listOf(pulse("mine", 6))), seen, counts, setOf("mine"), firstSweep = false)
        assertTrue(a.isEmpty())
        assertTrue(seen.isNotEmpty() && counts["mine"] == 6)
    }

    @Test fun `poll cadence is away-patience but network-gentle`() {
        assertTrue(AwayWatch.POLL_MS in 10_000L..60_000L)
    }
}
