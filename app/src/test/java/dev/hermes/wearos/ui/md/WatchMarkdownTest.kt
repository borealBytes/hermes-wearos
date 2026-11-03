package dev.hermes.wearos.ui.md

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM sanity for the markdown-lite parser (SPEC §5 F3/F4): block classification, inline spans,
 * the stream-unsafe cases (unclosed fence, unterminated markers) and the never-lose-text rule.
 */
class WatchMarkdownTest {

    private fun plainSpans(text: String) = listOf(MdSpan.Plain(text))

    // ── clickable links (owner: "URL links … should be clickable") ──────────────────────────

    @Test fun `markdown link parses to a Link span`() {
        val p = WatchMarkdown.parse("see [docs](https://x.dev/a) ok")[0] as MdBlock.Paragraph
        assertEquals(
            listOf(
                MdSpan.Plain("see "),
                MdSpan.Link("https://x.dev/a", listOf(MdSpan.Plain("docs"))),
                MdSpan.Plain(" ok"),
            ),
            p.spans,
        )
    }

    @Test fun `bare URL autolinks and the sentence dot is not part of it`() {
        val p = WatchMarkdown.parse("read https://a.b/c. done")[0] as MdBlock.Paragraph
        assertEquals(
            listOf(
                MdSpan.Plain("read "),
                MdSpan.Link("https://a.b/c", listOf(MdSpan.Plain("https://a.b/c"))),
                MdSpan.Plain(". done"),
            ),
            p.spans,
        )
    }

    @Test fun `link keeps inner styling and code beats links`() {
        val bold = WatchMarkdown.parse("**[hi](https://z)**")[0] as MdBlock.Paragraph
        assertEquals(listOf(MdSpan.Bold(listOf(MdSpan.Link("https://z", listOf(MdSpan.Plain("hi")))))), bold.spans)
        val code = WatchMarkdown.parse("`[a](https://x)`")[0] as MdBlock.Paragraph
        assertEquals(listOf(MdSpan.InlineCode("[a](https://x)")), code.spans)
    }

    @Test fun `unclosed partial and non-web targets stay literal`() {
        // Stream-partial `[docs](https://x` mid-token must render the raw text, never a half-link.
        for (raw in listOf("see [docs](https://x", "[a] b", "[x](not a url)", "[local](/p/x)")) {
            val p = WatchMarkdown.parse(raw)[0] as MdBlock.Paragraph
            assertTrue(raw, p.spans.all { it is MdSpan.Plain })
        }
    }

    @Test fun `autolink never fires mid-word`() {
        val p = WatchMarkdown.parse("xhttp://a.b")[0] as MdBlock.Paragraph
        assertEquals(listOf(MdSpan.Plain("xhttp://a.b")), p.spans)
    }

    @Test fun headingsLevels() {
        val blocks = WatchMarkdown.parse("# One\n## Two\n### Three\n#### Four")
        assertEquals(
            listOf(
                MdBlock.Heading(1, plainSpans("One")),
                MdBlock.Heading(2, plainSpans("Two")),
                MdBlock.Heading(3, plainSpans("Three")),
                MdBlock.Heading(4, plainSpans("Four")),
            ),
            blocks,
        )
    }

    @Test fun inlineBoldItalicCode() {
        val blocks = WatchMarkdown.parse("Some **bold** and *italic* and `inline`.")
        assertEquals(1, blocks.size)
        val p = blocks[0] as MdBlock.Paragraph
        assertEquals(
            listOf(
                MdSpan.Plain("Some "),
                MdSpan.Bold(listOf(MdSpan.Plain("bold"))),
                MdSpan.Plain(" and "),
                MdSpan.Italic(listOf(MdSpan.Plain("italic"))),
                MdSpan.Plain(" and "),
                MdSpan.InlineCode("inline"),
                MdSpan.Plain("."),
            ),
            p.spans,
        )
    }

    @Test fun underscoreEmphasisAndSnakeCase() {
        val p = WatchMarkdown.parse("Peak _aurora NO_ tonight, see __the map__ (file my_var_name here).")[0] as MdBlock.Paragraph
        assertEquals(
            listOf(
                MdSpan.Plain("Peak "),
                MdSpan.Italic(listOf(MdSpan.Plain("aurora NO"))),
                MdSpan.Plain(" tonight, see "),
                MdSpan.Bold(listOf(MdSpan.Plain("the map"))),
                MdSpan.Plain(" (file my_var_name here)."),
            ),
            p.spans,
        )
    }

    @Test fun boldCanNestCode() {
        val p = WatchMarkdown.parse("**run `main()` now**")[0] as MdBlock.Paragraph
        assertEquals(
            listOf(MdSpan.Bold(listOf(
                MdSpan.Plain("run "),
                MdSpan.InlineCode("main()"),
                MdSpan.Plain(" now"),
            ))),
            p.spans,
        )
    }

    @Test fun codeFenceClosedWithLang() {
        val blocks = WatchMarkdown.parse("intro\n\n```kotlin\nfun main() { println(42) }\n```\n\nafter")
        assertTrue(blocks[1] is MdBlock.CodeBlock)
        val cb = blocks[1] as MdBlock.CodeBlock
        assertEquals("kotlin", cb.lang)
        assertEquals("fun main() { println(42) }", cb.code)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(blocks[2] is MdBlock.Paragraph)
    }

    /** Live streaming safety: fence opened but not yet closed ⇒ rest IS the code block. */
    @Test fun unclosedFenceBecomesCodeBlock() {
        val blocks = WatchMarkdown.parse("Here you go:\n\n```python\nprint('hi')\nprin")
        assertEquals(2, blocks.size)
        val cb = blocks[1] as MdBlock.CodeBlock
        assertEquals("python", cb.lang)
        assertEquals("print('hi')\nprin", cb.code)
    }

    @Test fun mermaidFenceSplitOut() {
        val blocks = WatchMarkdown.parse("```mermaid\ngraph TD; A-->B\n```")
        assertEquals(MdBlock.MermaidFence("graph TD; A-->B"), blocks[0])
    }

    // ---- F6 images: image-only lines become Image blocks, mixed lines stay paragraphs ----

    @Test fun imageOnlyLineBecomesImageBlock() {
        val blocks = WatchMarkdown.parse("Look:\n\n![red square](/home/user/.hermes/red.png)\n\nafter")
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Image("red square", "/home/user/.hermes/red.png"), blocks[1])
    }

    @Test fun mediaAttachmentLineBecomesImageBlockNamedByFile() {
        // The gateway/skill convention (`MEDIA:/abs/path`) — how the aurora renders arrive.
        val text = "Maps attached.\n\nMEDIA: /home/user/.hermes/profiles/wear-demo/cache/scratch/aurora/aurora_map.png\n"
        val blocks = WatchMarkdown.parse(text)
        assertEquals(2, blocks.size)
        assertEquals(
            MdBlock.Image("aurora_map.png", "/home/user/.hermes/profiles/wear-demo/cache/scratch/aurora/aurora_map.png"),
            blocks[1],
        )
    }

    @Test fun partialMediaLineStaysParagraph() {
        // Mid-stream the line is still arriving; it must not flash as a broken card.
        assertTrue(WatchMarkdown.parse("MEDIA: /home/user/.hermes/aur")[0] is MdBlock.Paragraph)
    }

    @Test fun imageEmptyAltAndHttpTarget() {
        val b = WatchMarkdown.parse("![](https://example.com/a.webp)")[0] as MdBlock.Image
        assertEquals("", b.alt)
        assertEquals("https://example.com/a.webp", b.target)
    }

    @Test fun imageOptionalTitleIsDropped() {
        val b = WatchMarkdown.parse("![a](/tmp/x.png \"the title\")")[0] as MdBlock.Image
        assertEquals("/tmp/x.png", b.target)
    }

    @Test fun mixedImageLineStaysParagraph() {
        val blocks = WatchMarkdown.parse("here ![alt](/tmp/x.png) inline")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    /** Stream safety: a half-arrived image line is plain text, never a broken block. */
    @Test fun partialImageLineStaysParagraph() {
        val blocks = WatchMarkdown.parse("![red square](/home/user/.hermes/red.pn")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test fun imageInsideFenceIsNotImageBlock() {
        val blocks = WatchMarkdown.parse("```\n![x](/tmp/a.png)\n```")
        assertTrue(blocks[0] is MdBlock.CodeBlock)
    }

    @Test fun bulletsIndentAndNumbered() {
        val blocks = WatchMarkdown.parse("- first\n    - nested\n\n1. one\n2) two")
        assertEquals(MdBlock.BulletItem(plainSpans("first"), 0), blocks[0])
        assertEquals(MdBlock.BulletItem(plainSpans("nested"), 2), blocks[1])
        assertEquals(MdBlock.NumberedItem(1, plainSpans("one")), blocks[2])
        assertEquals(MdBlock.NumberedItem(2, plainSpans("two")), blocks[3])
    }

    @Test fun quoteBlockStripsMarkers() {
        val q = WatchMarkdown.parse("> a **quote**\n> more")[0] as MdBlock.Quote
        assertEquals(
            listOf(MdSpan.Plain("a "), MdSpan.Bold(listOf(MdSpan.Plain("quote"))), MdSpan.Plain("\nmore")),
            q.spans,
        )
    }

    @Test fun hrLines() {
        val blocks = WatchMarkdown.parse("a\n\n---\n\nb")
        assertTrue(blocks[1] is MdBlock.HrLine)
        assertTrue(WatchMarkdown.parse("***") .first() is MdBlock.HrLine)
    }

    @Test fun tableDegradesToLabelledLines() {
        val t = WatchMarkdown.parse("| lang | year |\n|------|------|\n| kotlin | 2011 |")[0] as MdBlock.Table
        assertEquals(listOf("· lang: kotlin", "· year: 2011"), t.lines)
    }

    @Test fun twoColumnLabelledTableIsGridable() {
        val t = WatchMarkdown.parse("| lang | year |\n|------|------|\n| kotlin | 2011 |\n| rust | 2010 |")[0] as MdBlock.Table
        assertTrue(t.gridable)
        assertEquals(listOf("lang", "year"), t.header)
        assertEquals(listOf(listOf("kotlin", "2011"), listOf("rust", "2010")), t.rows)
    }

    @Test fun wideHeaderlessOrStreamingTablesStayDegraded() {
        val wide = WatchMarkdown.parse("| a | b | c |\n|---|---|---|\n| 1 | 2 | 3 |")[0] as MdBlock.Table
        assertFalse(wide.gridable)
        val noHeader = WatchMarkdown.parse("| kotlin | 2011 |")[0] as MdBlock.Table
        assertFalse(noHeader.gridable)
        val streaming = WatchMarkdown.parse("| lang | year |\n|---|---|")[0] as MdBlock.Table
        assertFalse(streaming.gridable) // header-only mid-stream: no rows yet
    }

    /** Never lose text: unterminated markers render as literal characters. */
    @Test fun unparseableInlineStaysPlain() {
        val p = WatchMarkdown.parse("**not closed and `code stuck")[0] as MdBlock.Paragraph
        assertEquals(listOf(MdSpan.Plain("**not closed and `code stuck")), p.spans)
    }

    @Test fun emojiAndUnicodePassThrough() {
        val p = WatchMarkdown.parse("🎉 你好 — **ok** 🚀")[0] as MdBlock.Paragraph
        assertEquals("🎉 你好 — ", p.spans[0].let { (it as MdSpan.Plain).text })
        assertEquals(listOf(MdSpan.Plain("ok")), (p.spans[1] as MdSpan.Bold).children)
        assertTrue((p.spans[2] as MdSpan.Plain).text.contains("🚀"))
    }

    @Test fun plainTextRoundTripsUnchanged() {
        val text = "Just a normal reply, no markdown at all.\nSecond line."
        val blocks = WatchMarkdown.parse(text)
        assertEquals(1, blocks.size)
        assertEquals(text, WatchMarkdown.parse(text).let { (it[0] as MdBlock.Paragraph).spans.joinToString("") { s -> (s as MdSpan.Plain).text } })
    }

    // ---- MEDIA EPIC: video/audio routes to Media blocks, images keep F6 Image ----

    @Test fun mediaLineWithVideoExtensionBecomesVideoCard() {
        val b = WatchMarkdown.parse("MEDIA:/home/user/comfy/out/clip.mp4")[0]
        assertEquals(MdBlock.Media(video = true, target = "/home/user/comfy/out/clip.mp4", label = "clip.mp4"), b)
    }

    @Test fun mediaLineWithAudioExtensionBecomesAudioCard() {
        val b = WatchMarkdown.parse("MEDIA: /srv/podcast/ep7.m4a")[0]
        assertEquals(MdBlock.Media(video = false, target = "/srv/podcast/ep7.m4a", label = "ep7.m4a"), b)
    }

    @Test fun imageSyntaxWithVideoExtensionRoutesToVideoCard() {
        val b = WatchMarkdown.parse("![demo](/p/out/demo-vertical-v0.6.0-song-d.mp4)")[0]
        assertEquals(MdBlock.Media(video = true, target = "/p/out/demo-vertical-v0.6.0-song-d.mp4", label = "demo"), b)
    }

    @Test fun imageSyntaxWithImageExtensionStaysImageBlock() {
        assertTrue(WatchMarkdown.parse("![x](/a/b.png)")[0] is MdBlock.Image)
    }

    @Test fun bareWholeLineMediaPathBecomesCard() {
        val b = WatchMarkdown.parse("/data/out/renders/song-d.mp4")[0]
        assertEquals(MdBlock.Media(video = true, target = "/data/out/renders/song-d.mp4", label = "song-d.mp4"), b)
    }

    @Test fun backtickedBareMediaLineBecomesCard() {
        assertTrue(WatchMarkdown.parse("`/data/x/clip.mp4`")[0] is MdBlock.Media)
    }

    @Test fun mediaPathInsideProseStaysParagraph() {
        // Only whole-line paths become cards — mid-sentence mentions stay text.
        assertTrue(WatchMarkdown.parse("rendered at /data/x/clip.mp4 yesterday")[0] is MdBlock.Paragraph)
    }

    @Test fun remoteMediaUrlBecomesDirectCard() {
        val b = WatchMarkdown.parse("https://example.com/media/talk.mp3")[0]
        assertEquals(MdBlock.Media(video = false, target = "https://example.com/media/talk.mp3", label = "talk.mp3"), b)
    }

    @Test fun partialBareMediaLineMidStreamStaysParagraph() {
        // Stream-safety: the extension has not fully arrived, so nothing is a card yet.
        assertTrue(WatchMarkdown.parse("/data/out/renders/song-d.mp")[0] is MdBlock.Paragraph)
    }
}
