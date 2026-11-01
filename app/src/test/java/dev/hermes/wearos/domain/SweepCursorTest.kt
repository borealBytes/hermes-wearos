package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #32 — SweepCursor is the ONE memory shared by the in-app loop and the WorkManager lifeline.
 * These pins are what make the handoff safe: whoever calls sweep(), the process sees each news
 * item exactly once, and the anti-stampede silence belongs to the CURSOR (first sweep after
 * process start), not to a particular caller.
 */
class SweepCursorTest {

    private fun pulse(id: String, count: Int, role: String = "assistant") =
        "{\"id\":\"$id\",\"profile\":\"wear-demo\",\"title\":\"t\",\"message_count\":$count,\"last_role\":\"$role\"}"

    private fun inbox(pulses: List<String>, parks: List<String> = emptyList()) =
        AwayWatch.parse(
            "{\"object\":\"list\",\"waiting\":[${parks.joinToString()}],\"pulses\":[${pulses.joinToString()}]}"
        )

    private val park =
        "{\"profile\":\"wear-demo\",\"chat_id\":\"s9\",\"title\":\"t\",\"tool\":\"terminal\",\"arguments\":\"{}\",\"parked_at\":1}"

    @Test fun `first sweep seeds silently even when news is already live`() {
        val c = SweepCursor()
        val alerts = c.sweep(inbox(listOf(pulse("a", 12)), listOf(park)), emptySet())
        assertEquals(emptyList<String>(), alerts.map { it.kind })
        assertEquals(12, c.trackedCounts()["a"])
    }

    @Test fun `second sweep buzzes the bump — cursor remembers across callers`() {
        val c = SweepCursor()
        c.sweep(inbox(listOf(pulse("a", 12))), emptySet())          // loop
        val alerts = c.sweep(inbox(listOf(pulse("a", 14))), emptySet()) // worker
        assertEquals(1, alerts.size)
        assertEquals(AwayWatch.Kind.Finished, alerts[0].kind)
    }

    @Test fun `a park survives the handoff — seen once, never twice`() {
        val c = SweepCursor()
        c.sweep(inbox(emptyList(), listOf(park)), emptySet())            // first sweep records
        assertEquals(0, c.sweep(inbox(emptyList(), listOf(park)), emptySet()).size)
        // beacon answered+re-parked same session+tool: fresh key buzzes again
        c.sweep(inbox(emptyList(), emptyList()), emptySet())
        assertEquals(1, c.sweep(inbox(emptyList(), listOf(park)), emptySet()).size)
    }

    @Test fun `suppressed chat stays invisible to a later unsuppressed sweep only for seen counts`() {
        val c = SweepCursor()
        c.sweep(inbox(listOf(pulse("a", 12))), setOf("a"))     // cursor rides along while viewed
        val alerts = c.sweep(inbox(listOf(pulse("a", 15))), emptySet()) // left the chat
        assertEquals(1, alerts.size)                            // NEW bump still buzzes
        c.sweep(inbox(listOf(pulse("b", 40))), emptySet())     // first-seen b: seed
        assertEquals(0, c.sweep(inbox(listOf(pulse("b", 60))), setOf("b")).size) // viewed: silence
    }
}
