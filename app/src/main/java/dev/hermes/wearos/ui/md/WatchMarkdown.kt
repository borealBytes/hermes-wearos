package dev.hermes.wearos.ui.md

/**
 * Inline fragments produced by [WatchMarkdown]. Leaf [MdSpan.Plain] always keeps the original
 * characters: anything we cannot parse (unterminated `**`, stray `` ` ``, …) degrades to literal
 * text, never to dropped text. Unicode/emoji pass through untouched.
 */
sealed interface MdSpan {
    data class Plain(val text: String) : MdSpan
    data class Bold(val children: List<MdSpan>) : MdSpan
    data class Italic(val children: List<MdSpan>) : MdSpan
    data class InlineCode(val text: String) : MdSpan

    /**
     * Clickable link (owner: "URL links … should be clickable … open whatever browser is default
     * on my watch"). http(s)/mailto only — a gateway-local `/path` isn't a browser target, and
     * the F6 image path stays image-card territory. [url] is the RAW target; [children] keep the
     * label's inline styling (a bold link label stays bold).
     */
    data class Link(val url: String, val children: List<MdSpan>) : MdSpan
}

/** Block-level nodes (SPEC §5 F3). One node ≈ one composable row on the watch list. */
sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class BulletItem(val spans: List<MdSpan>, val indent: Int) : MdBlock
    data class NumberedItem(val n: Int, val spans: List<MdSpan>) : MdBlock
    data class Quote(val spans: List<MdSpan>) : MdBlock
    data class CodeBlock(val lang: String, val code: String) : MdBlock
    data class MermaidFence(val code: String) : MdBlock

    /**
     * F6 inline image: an image-ONLY line `![alt](target)`. target is an absolute gateway-host
     * path (routed through watchkit /img) or an http(s) URL (fetched direct). Deliberate
     * simplification: a line that MIXES an image with other text stays a plain paragraph —
     * bitmaps inside running text are unreadable at 480px round, and the alt text still says
     * what it was. (Commented per SPEC §5 F6.)
     */
    data class Image(val alt: String, val target: String) : MdBlock

    /**
     * MEDIA EPIC (owner 2026-11-02: "the desktop shows generated video/audio — so should the
     * watch, small inline with play, fullscreen on tap"): same three source conventions as
     * [Image] (`![]()`, `MEDIA:`, bare whole-line paths/URLs), routed by extension. [video]
     * splits the two kinds; [label] is the alt (or the file name for MEDIA:/bare lines).
     * Remote http(s) targets play DIRECT through the watch's own net; gateway-host absolute
     * paths stream through watchkit /media (ffmpeg pre-shrink, owner: "no big-ass video over
     * the wire") — see Watchkit.mediaUrl.
     */
    data class Media(val video: Boolean, val target: String, val label: String) : MdBlock

    /**
     * Markdown tables degrade to pre-formatted "· col: cell" caption lines — the watch is too
     * narrow for a wide grid, so each non-blank cell becomes its own labelled line (F3 note).
     * Structured [header]/[rows] ride along: when the table is exactly two labelled columns
     * ([gridable]) the renderer draws a real mini-grid instead — narrow tables DO fit a round
     * screen, and a visible table beats a clever degradation in the common case.
     */
    data class Table(
        val lines: List<String>,
        val header: List<String> = emptyList(),
        val rows: List<List<String>> = emptyList(),
    ) : MdBlock {
        val gridable: Boolean
            get() = header.size == 2 && header.none { it.isBlank() } &&
                rows.isNotEmpty() && rows.all { it.size == 2 }
    }
    data object HrLine : MdBlock
}

/**
 * Parse-then-render markdown-lite (no third-party renderer: `horologist-markdown` does not exist
 * as a resolvable artifact — 404 on Google maven + Maven Central; heavier libs like markwon pull
 * Android parsing stacks that misbehave on round small screens).
 *
 * Block pass: fences first (``` / ~~~, mermaid split out), then heading / hr / quote / bullet /
 * numbered / table-ish lines; anything else accumulates into paragraphs. Inline pass: **bold**,
 * *italic*, `code` (recursive; bold may contain italic/code).
 *
 * Stream-safe by construction: an unclosed fence consumes the rest of the text as a code block,
 * and unterminated inline markers stay literal — a partially arrived buffer never renders as
 * broken spans or a backtick storm.
 *
 * Pure Kotlin (no Android imports) so app/src/test runs it on the JVM.
 */
object WatchMarkdown {
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val TRAILING_HASHES = Regex("\\s+#+\\s*$")
    private val HR = Regex("^\\s{0,3}(-{3,}|\\*{3,}|_{3,})\\s*$")
    private val QUOTE = Regex("^\\s*>\\s?(.*)$")
    private val BULLET = Regex("^(\\s*)([-*+])\\s+(.*)$")
    private val NUMBERED = Regex("^\\s*(\\d{1,9})[.)]\\s+(.*)$")
    // Image-only line; optional "title"/'title' after the path is dropped. No spaces in target
    // (gateway paths are plain absolute paths); anything fancier stays literal paragraph text.
    private val IMAGE_LINE = Regex("^\\s*!\\[([^\\]]*)\\]\\(\\s*([^()\\s]+)(?:\\s+[\"'][^\"']*[\"'])?\\s*\\)\\s*$")

    // MEDIA EPIC extension sets. The watch never DECODES these containers itself except the
    // ExoPlayer path; watchkit re-muxes to mp4/m4a before anything hits the wire.
    internal const val IMG_EXT = "png|jpe?g|webp|gif|bmp"
    internal const val VID_EXT = "mp4|mov|m4v|mkv|webm|avi|3gp|ts"
    internal const val AUD_EXT = "mp3|m4a|aac|wav|ogg|opus|flac|wma|amr"

    /** `MEDIA:/abs/path` — the gateway/skill attachment line convention (aurora/weather charts,
     *  comfy video/audio renders). Only fires once the extension is fully on screen, so a
     *  mid-stream partial stays text. Routes image/video/audio by extension. */
    private val MEDIA_LINE = Regex(
        "^\\s*MEDIA:\\s*(\\S+\\.(?:$IMG_EXT|$VID_EXT|$AUD_EXT))\\s*$", RegexOption.IGNORE_CASE,
    )

    /** A WHOLE line that is just one absolute path / http(s) URL ending in a media extension
     *  (optionally backticked) — how desktop transcripts often carry generated clips. Mid-prose
     *  paths stay literal: one path per line is the contract, same discipline as IMAGE_LINE. */
    private val BARE_MEDIA_LINE = Regex(
        "^\\s*`?\\s*((?:https?://|/)[^`\\s|]+?\\.(?:$VID_EXT|$AUD_EXT))\\s*`?\\s*$", RegexOption.IGNORE_CASE,
    )

    /** Route a media target to the right block by extension (image stays F6 Image). */
    private fun mediaBlock(label: String, target: String): MdBlock {
        val t = target.substringBefore('#').lowercase()
        val v = Regex("\\.($VID_EXT)$").containsMatchIn(t)
        val a = !v && Regex("\\.($AUD_EXT)$").containsMatchIn(t)
        return if (v || a) MdBlock.Media(video = v, target = target, label = label)
        else MdBlock.Image(label, target)
    }

    fun parse(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").split('\n')
        val out = ArrayList<MdBlock>()
        val para = ArrayList<String>()

        fun flushPara() {
            if (para.isEmpty()) return
            val spans = ArrayList<MdSpan>()
            para.forEachIndexed { idx, l ->
                if (idx > 0) spans += MdSpan.Plain("\n")
                spans += parseInline(l)
            }
            para.clear()
            if (spans.any { it !is MdSpan.Plain || it.text.isNotEmpty() }) out += MdBlock.Paragraph(spans)
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()

            if (t.isEmpty()) { flushPara(); i++; continue }

            // Fenced code first: ```lang … ``` (unclosed fence = everything left, stream-safe).
            if ((t.startsWith("```") || t.startsWith("~~~")) && !isSeparator(t)) {
                flushPara()
                val fc = t[0]
                val flen = t.takeWhile { it == fc }.length
                val info = t.drop(flen).trim()
                val code = StringBuilder()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    val lt = lines[j].trim()
                    if (lt.length >= flen && lt.all { it == fc }) { closed = true; break }
                    code.appendLine(lines[j]); j++
                }
                val body = code.toString().removeSuffix("\n")
                out += if (info.lowercase().startsWith("mermaid")) MdBlock.MermaidFence(body)
                else MdBlock.CodeBlock(info, body)
                i = if (closed) j + 1 else j
                continue
            }

            val hm = HEADING.matchEntire(line)
            if (hm != null) {
                flushPara()
                val txt = hm.groupValues[2].trim().replace(TRAILING_HASHES, "")
                out += MdBlock.Heading(hm.groupValues[1].length, parseInline(txt))
                i++; continue
            }

            if (HR.matches(t)) { flushPara(); out += MdBlock.HrLine; i++; continue }

            if (QUOTE.matches(line)) {
                flushPara()
                val inner = ArrayList<String>()
                var j = i
                while (j < lines.size && QUOTE.matches(lines[j])) {
                    inner += QUOTE.matchEntire(lines[j])!!.groupValues[1]; j++
                }
                out += MdBlock.Quote(parseInline(inner.joinToString("\n")))
                i = j; continue
            }

            val bm = BULLET.matchEntire(line)
            if (bm != null) {
                flushPara()
                val indent = (bm.groupValues[1].length / 2).coerceAtMost(3)
                out += MdBlock.BulletItem(parseInline(bm.groupValues[3]), indent)
                i++; continue
            }

            val nm = NUMBERED.matchEntire(line)
            if (nm != null) {
                flushPara()
                out += MdBlock.NumberedItem(nm.groupValues[1].toInt().coerceAtMost(999), parseInline(nm.groupValues[2]))
                i++; continue
            }

            if (looksLikeTableRow(line)) {
                flushPara()
                val rows = ArrayList<Pair<List<String>, Boolean>>() // cells to isSeparator
                var j = i
                while (j < lines.size && looksLikeTableRow(lines[j])) {
                    rows += cellsOf(lines[j]) to isSeparator(lines[j]); j++
                }
                val data = rows.filter { !it.second }
                val hasHeader = rows.getOrNull(1)?.second == true && data.isNotEmpty()
                val header = if (hasHeader) data.first().first else emptyList()
                val body = if (hasHeader) data.drop(1) else data
                val linesOut = ArrayList<String>()
                body.forEach { (row, _) ->
                    row.forEachIndexed { c, cell ->
                        if (cell.isEmpty()) return@forEachIndexed
                        val label = header.getOrNull(c)?.takeIf { it.isNotEmpty() }
                        linesOut += if (label != null) "· $label: $cell" else "· $cell"
                    }
                }
                // Header-only table (streaming just the first rows): show it unlabeled, not lost.
                if (linesOut.isEmpty() && header.isNotEmpty()) header.forEach { if (it.isNotEmpty()) linesOut += "· $it" }
                if (linesOut.isNotEmpty()) out += MdBlock.Table(linesOut, header, body.map { it.first })
                i = j; continue
            }

            // Image-only line → dedicated block (F6). Checked late so fences/headings/bullets
            // win first; mixed `text ![alt](x)` lines fall through to paragraphs on purpose
            // (see MdBlock.Image) and a stream-partial `![a](/pat` stays plain text too.
            // Media epics: `![t](clip.mp4)` routes to a Media card, not a bitmap fetch.
            val im = IMAGE_LINE.matchEntire(line)
            if (im != null) {
                flushPara()
                out += mediaBlock(im.groupValues[1].trim(), im.groupValues[2].trim())
                i++; continue
            }

            // Gateway attachment convention: a bare `MEDIA:/abs/path` line from a skill (the
            // aurora/weather renders speak this). Same card family as ![](), label = file name.
            val med = MEDIA_LINE.matchEntire(line)
            if (med != null) {
                flushPara()
                val p = med.groupValues[1]
                out += mediaBlock(p.substringAfterLast('/'), p)
                i++; continue
            }

            // Bare whole-line media path/URL (comfy video drops arrive this way in desktop
            // transcripts). Deliberately AFTER the other two so MEDIA:/md syntax wins.
            val bare = BARE_MEDIA_LINE.matchEntire(line)
            if (bare != null) {
                flushPara()
                val p = bare.groupValues[1]
                out += mediaBlock(p.substringAfterLast('/').substringBefore('?'), p)
                i++; continue
            }

            para += line; i++
        }
        flushPara()
        return out
    }

    // ---- table heuristics (see MdBlock.Table doc: deliberate, commented degradation) ----

    private fun looksLikeTableRow(line: String): Boolean {
        val t = line.trim()
        if (!t.contains('|')) return false
        return t.startsWith('|') || t.count { it == '|' } >= 2
    }

    private fun isSeparator(t: String): Boolean =
        t.contains('-') && t.all { it == '-' || it == ':' || it == '|' || it == ' ' }

    private fun cellsOf(line: String): List<String> =
        line.trim().trimStart('|').trimEnd('|').split('|').map { it.trim() }

    // ---- inline pass: **bold**, *italic*, `code`, [label](url), bare https://…; unterminated stays literal ----

    /** Bare-URL autolink scan (owner: URLs must be clickable). Deliberately conservative: no
     *  fragment runs with trailing punctuation (a sentence-ending dot is not part of the link). */
    private val BARE_URL = Regex("""https?://[^\s<>"'\[\]\u4e00-\u9fff，。；！？]+""")
    private val TRAILING_PUNCT = ".,;:!?*"

    private fun isWebUrl(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")

    internal fun parseInline(text: String): List<MdSpan> {
        if (text.isEmpty()) return emptyList()
        val spans = ArrayList<MdSpan>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) { spans += MdSpan.Plain(buf.toString()); buf.setLength(0) }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '`') {
                val close = text.indexOf('`', i + 1)
                if (close > i + 1) {
                    flush(); spans += MdSpan.InlineCode(text.substring(i + 1, close)); i = close + 1; continue
                }
                buf.append(c); i++; continue // lone backtick: literal
            }
            // [label](url) — a malformed/unclosed/odd-scheme link renders as the plain text it is.
            if (c == '[') {
                val close = text.indexOf(']', i + 1)
                val open = if (close > i + 1 && close + 1 < text.length && text[close + 1] == '(') close + 2 else -1
                val end = if (open > 0) text.indexOf(')', open) else -1
                if (end > open) {
                    val label = text.substring(i + 1, close)
                    val url = text.substring(open, end).trim()
                    if (isWebUrl(url)) {
                        flush(); spans += MdSpan.Link(url, parseInline(label)); i = end + 1; continue
                    }
                }
                buf.append(c); i++; continue
            }
            // Bare URL autolink, only at a word boundary (never the tail of "xhttp://…"), and
            // never right after "(" — that is markdown's own [label](url) shape, partial
            // includes: a stream-cut "[label](https…" must stay literal, not half-link.
            if (c == 'h' && (i == 0 || !text[i - 1].let { it.isLetterOrDigit() || it in "/?.=&%_:(" })) {
                val m = BARE_URL.find(text, i)
                if (m != null && m.range.first == i) {
                    var url = m.value
                    while (url.isNotEmpty() && url.last() in TRAILING_PUNCT) url = url.dropLast(1)
                    if (url.isNotEmpty()) {
                        flush(); spans += MdSpan.Link(url, listOf(MdSpan.Plain(url))); i += url.length; continue
                    }
                }
            }
            if (c == '*' && text.startsWith("**", i)) {
                val close = text.indexOf("**", i + 2)
                if (close > i + 2) {
                    flush(); spans += MdSpan.Bold(parseInline(text.substring(i + 2, close))); i = close + 2; continue
                }
                buf.append("**"); i += 2; continue // unclosed: keep the stars
            }
            if (c == '*' && i + 1 < text.length && text[i + 1] != '*' && !text[i + 1].isWhitespace()) {
                val close = text.indexOf('*', i + 1)
                if (close > i + 1 && close + 1 < text.length && text[close + 1] != '*' && !text[close - 1].isWhitespace()) {
                    flush(); spans += MdSpan.Italic(parseInline(text.substring(i + 1, close))); i = close + 1; continue
                }
                buf.append(c); i++; continue
            }
            // Underscore emphasis (models default to _this_ far more than *this*): guarded so
            // snake_case survives — opening `_` must not hug a letter, closing `_` likewise.
            if (c == '_' && !buf.lastOrNull().let { it != null && it.isLetterOrDigit() }) {
                if (text.startsWith("__", i) && !text.startsWith("___", i)) {
                    val close = text.indexOf("__", i + 2)
                    if (close > i + 2) {
                        flush(); spans += MdSpan.Bold(parseInline(text.substring(i + 2, close))); i = close + 2; continue
                    }
                    buf.append("__"); i += 2; continue
                }
                if (i + 1 < text.length && text[i + 1] != '_' && !text[i + 1].isWhitespace()) {
                    val close = text.indexOf('_', i + 1)
                    if (close > i + 1 && !text[close - 1].isWhitespace() &&
                        !(close + 1 < text.length && text[close + 1].isLetterOrDigit())
                    ) {
                        flush(); spans += MdSpan.Italic(parseInline(text.substring(i + 1, close))); i = close + 1; continue
                    }
                }
                buf.append(c); i++; continue
            }
            buf.append(c); i++
        }
        flush()
        // Coalesce neighbouring Plain spans so tests/renderers see minimal structure.
        val merged = ArrayList<MdSpan>(spans.size)
        for (s in spans) {
            val last = merged.lastOrNull()
            if (s is MdSpan.Plain && last is MdSpan.Plain) merged[merged.size - 1] = MdSpan.Plain(last.text + s.text)
            else merged += s
        }
        return merged
    }
}
