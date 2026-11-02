package dev.hermes.wearos.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE RENDER HARNESS (SPEC §13.3). These cases are how a media surface gets PROVEN over
 * `adb shell am broadcast -e render <case>` + `screencap` instead of asking a model for a table and
 * hoping. The tests keep the fixtures honest: each case must actually contain the construct it
 * claims to exercise, or the screenshot proves nothing.
 */
class RenderCasesTest {

    @Test fun `ids resolve both ways and list for the dev`() {
        assertEquals(RenderCase.Tables, RenderCase.of("tables"))
        assertEquals(RenderCase.Mermaid, RenderCase.of(" MERMAID "))
        assertNull(RenderCase.of("nonsense"))
        assertEquals("tables,wide,mermaid,pdf,link,media,av,all", RenderCase.ids())
    }

    @Test fun `the tables case has a five-column grid and a six-column degradation`() {
        val md = RenderCase.Tables.markdown
        val tables = md.split("\n\n").filter { it.contains("|---") }
        assertEquals(2, tables.size)
        assertEquals(5, tables[0].lineSequence().first().count { it == '|' } - 1)
        assertEquals(6, tables[1].lineSequence().first().count { it == '|' } - 1)
    }

    @Test fun `every case carries the construct it is named for`() {
        assertTrue(RenderCase.Wide.markdown.contains("|---"))
        assertEquals(7, RenderCase.Wide.markdown.lineSequence().first { it.startsWith("| Route") }.count { it == '|' } - 1)
        assertTrue(RenderCase.Mermaid.markdown.contains("```mermaid"))
        assertTrue(RenderCase.Pdf.markdown.contains(".pdf"))
        assertTrue(RenderCase.Link.markdown.contains("[8642 dashboard](http://"))
        assertTrue(RenderCase.Media.markdown.contains("```mermaid"))
        assertTrue(RenderCase.Media.markdown.contains("|---"))
        assertTrue(RenderCase.Media.markdown.contains("![dial mock]"))
        assertTrue(RenderCase.Av.markdown.contains("MEDIA:"))
        assertTrue(RenderCase.Av.markdown.contains(".mp4"))
    }

    @Test fun `all concatenates the five media surfaces for one screenshot`() {
        val all = RenderCase.All.markdown
        listOf("|---", "```mermaid", ".pdf", "](http://", "![Deck cover]").forEach {
            assertTrue("all-case missing $it", all.contains(it))
        }
    }

    @Test fun `cases never leak a raw newline escape into the markdown`() {
        // The transcript renderer splits on real \n; a literal "\\n" inside a fence would render as
        // text and the screenshot would prove nothing about the parser.
        assertNotNull(RenderCase.of("media"))
        RenderCase.entries.forEach { c ->
            assertTrue("${c.id} has a literal backslash-n", !c.markdown.contains("\\n"))
        }
    }
}
