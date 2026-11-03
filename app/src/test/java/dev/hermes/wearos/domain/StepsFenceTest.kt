package dev.hermes.wearos.domain

import dev.hermes.wearos.ui.md.MdBlock
import dev.hermes.wearos.ui.md.WatchMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SETTLE FOLD round trip (owner 2026-11-02: collapsible tool-activity box): ChatSession
 * encodes the live ledger into a ```hermes-steps fence, the transcript persists it, and
 * WatchMarkdown must read EXACTLY that back — field hygiene (pipes/newlines) included.
 */
class StepsFenceTest {

    @Test fun `encode parses back to identical rows`() {
        val steps = listOf(
            ToolStep("c1", "terminal", "ls -la /etc", done = true, durS = 0.21),
            ToolStep("c2", "write_file", "/tmp/x.txt", done = true, error = true, durS = 1.5, result = "403 forbidden"),
        )
        val fence = StepsFence.encode(steps)
        assertTrue(fence.startsWith("```" + StepsFence.LANG))
        val parsed = WatchMarkdown.parse(fence).single()
        assertTrue(parsed is MdBlock.ActivityRun)
        val rows = (parsed as MdBlock.ActivityRun).steps
        assertEquals(2, rows.size)
        assertEquals("terminal", rows[0].tool)
        assertEquals(0.21, rows[0].durS, 1e-6)
        assertEquals("ls -la /etc", rows[0].preview)
        assertEquals("", rows[0].result)
        assertTrue(rows[1].error)
        assertEquals("403 forbidden", rows[1].result)
    }

    @Test fun `pipes and newlines can never break a row`() {
        val steps = listOf(ToolStep("c1", "terminal", "grep -n 'a|b' f ⏎ second line", done = true, durS = 0.0))
        val rows = (WatchMarkdown.parse(StepsFence.encode(steps)).single() as MdBlock.ActivityRun).steps
        assertEquals("grep -n 'a/b' f ⏎ second line", rows.single().preview) // '|' rewritten, no new row
    }

    @Test fun `a running row survives the round trip as running`() {
        val steps = listOf(ToolStep("c1", "terminal", "sleep 30")) // not done
        val rows = (WatchMarkdown.parse(StepsFence.encode(steps)).single() as MdBlock.ActivityRun).steps
        assertTrue(rows.single().running)
    }

    @Test fun `junk fence bodies parse, never throw`() {
        val body = "not-a-real-step\nbroken|line"
        val rows = WatchMarkdown.parseStepsFence(body)
        assertEquals(2, rows.size) // blank skipped; short rows fill from the right, durS=0
        assertEquals(0.0, rows[1].durS, 1e-6)
    }
}
