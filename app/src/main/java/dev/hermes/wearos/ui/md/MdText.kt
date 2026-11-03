package dev.hermes.wearos.ui.md

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.hermes.wearos.net.Watchkit
import dev.hermes.wearos.ui.scaled

/**
 * Renders [MdBlock]s with Wear Compose MaterialTheme typography ONLY (title1-3 / body1-2 /
 * caption1-3 / button — no title4+/subtitle; those don't exist in wear-compose 1.x anyway).
 *
 * Expand/collapse state is `remember`ed per block key (caller passes a stable `msgId to index`
 * pair) so it survives recomposition and streaming re-parses but never leaks across blocks.
 */
@Composable
fun MdBlockView(block: MdBlock, blockKey: Any, modifier: Modifier = Modifier) {
    when (block) {
        is MdBlock.Heading -> Text(
            block.spans.annotated(),
            modifier = modifier.fillMaxWidth().padding(top = 4.dp),
            style = when (block.level) {
                1 -> MaterialTheme.typography.title1
                2 -> MaterialTheme.typography.title2
                3 -> MaterialTheme.typography.title3
                else -> MaterialTheme.typography.body1 // h4+ shares body1 bold: no title4 on Wear
            }.scaled(),
            fontWeight = FontWeight.Bold,
        )

        is MdBlock.Paragraph -> MdBodyText(
            block.spans.annotated(),
            modifier = modifier.fillMaxWidth(),
            style = MaterialTheme.typography.body2.scaled(),
        )

        is MdBlock.BulletItem -> MdBodyText(
            block.spans.annotated("•  "),
            modifier = modifier.fillMaxWidth().padding(start = (block.indent * 8).dp),
            style = MaterialTheme.typography.body2.scaled(),
        )

        is MdBlock.NumberedItem -> MdBodyText(
            block.spans.annotated("${block.n}.  "),
            modifier = modifier.fillMaxWidth(),
            style = MaterialTheme.typography.body2.scaled(),
        )

        // Blockquote: caption2 + indent + a leading "│ " bar (cheaper than a canvas line on a round screen).
        is MdBlock.Quote -> MdBodyText(
            block.spans.annotated("│ "),
            modifier = modifier.fillMaxWidth().padding(start = 6.dp),
            style = MaterialTheme.typography.caption2.scaled().copy(
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.75f),
            ),
        )

        is MdBlock.CodeBlock -> CodeCard(block.lang, block.code, blockKey, modifier)

        is MdBlock.MermaidFence -> MermaidBlockView(block.code, blockKey, modifier)

        is MdBlock.Image -> ImageBlock(block.alt, block.target, blockKey, modifier)

        is MdBlock.Media -> MediaBlock(block, blockKey, modifier)

        is MdBlock.ActivityRun -> ActivityRunCard(block.steps, running = false, blockKey, modifier)

        is MdBlock.Table -> if (block.gridable) {
            // True mini-grid for exactly-2 labelled columns: header + hairline + rows (F3 note).
            Column(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    block.header.forEach { cell ->
                        Text(
                            WatchMarkdown.parseInline(cell).annotated(),
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.caption1.scaled(),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(
                    Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 4.dp)
                        .background(MaterialTheme.colors.onBackground.copy(alpha = 0.28f)),
                )
                block.rows.forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { cell ->
                            Text(
                                WatchMarkdown.parseInline(cell).annotated(),
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.caption2.scaled(),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        } else Column(modifier.fillMaxWidth()) {
            block.lines.forEach {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.caption2.scaled(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Thin rule: Box+background because wear-compose 1.x has no Divider and
        // foundation HorizontalDivider only exists from 1.8 (we pin 1.7.6).
        MdBlock.HrLine -> Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .height(1.dp)
                .background(MaterialTheme.colors.onBackground.copy(alpha = 0.3f)),
        )
    }
}

/** Whole markdown answer as a column of blocks — one ScalingLazyColumn row per message. */
@Composable
fun MdMessage(text: String, msgKey: Any, modifier: Modifier = Modifier) {
    val blocks = remember(text) { WatchMarkdown.parse(text) }
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        blocks.forEachIndexed { i, b -> MdBlockView(b, msgKey to i) }
    }
}

/** Collapsed code: `‹lang› code · tap` + first-line hint; tap → full monospace, h-scroll. */
/**
 * THE COLLAPSIBLE TOOL-ACTIVITY CARD (owner 2026-11-02: "collapsable box like hermes desktop — a
 * little window that pops up, I click it, it collapses"). Header = count + live state; tap the
 * header to collapse/expand; a step's bounded result (newer gateways carry one) is per-row.
 * Fed LIVE (from ChatSession's ledger, running=true, starts expanded) and from the persisted
 * ```hermes-steps fence after settle (starts collapsed — the answer owns the stage, the theatre
 * is one tap away, exactly the desktop posture).
 */
@Composable
fun ActivityRunCard(
    steps: List<MdBlock.ActStep>,
    running: Boolean,
    blockKey: Any,
    modifier: Modifier,
    expandedDefault: Boolean = false,
) {
    var expanded by remember(blockKey) { mutableStateOf(expandedDefault) }
    val nErrors = steps.count { it.error }
    val head = when {
        running -> "⚙ ${steps.size} step${if (steps.size == 1) "" else "s"} · running…"
        nErrors > 0 -> "⚙ ${steps.size} step${if (steps.size == 1) "" else "s"} · $nErrors failed"
        else -> "⚙ ${steps.size} step${if (steps.size == 1) "" else "s"} · done ✓"
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colors.surface.copy(alpha = 0.4f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            head + (if (expanded) "  ‹collapse›" else "  ‹expand›"),
            style = MaterialTheme.typography.caption3.scaled(),
            color = if (running) Color(0xFFF5C518) else MaterialTheme.colors.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!expanded) {
            // Collapsed still shows the LATEST move — the glance answer to "what is it doing".
            val last = steps.lastOrNull()
            if (last != null) Text(
                "${stepIcon(last.tool)} ${last.preview.ifBlank { last.tool }}",
                style = MaterialTheme.typography.caption3.copy(fontFamily = FontFamily.Monospace).scaled(),
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                steps.forEachIndexed { i, s ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = if (i == 0) 2.dp else 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${stepIcon(s.tool)} ${s.tool}",
                            style = MaterialTheme.typography.caption3.scaled(),
                            fontWeight = FontWeight.Bold,
                            color = when {
                                s.error -> Color(0xFFFF8A80)
                                s.running -> Color(0xFFF5C518)
                                else -> MaterialTheme.colors.onBackground
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            when {
                                s.running -> "▸"
                                s.error -> "⚠"
                                s.durS > 0 -> "%.1fs".format(s.durS)
                                else -> "✓"
                            },
                            style = MaterialTheme.typography.caption3.scaled(),
                            color = if (s.error) Color(0xFFFF8A80) else MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                        )
                    }
                    if (s.preview.isNotBlank()) Text(
                        s.preview,
                        style = MaterialTheme.typography.caption3.copy(fontFamily = FontFamily.Monospace).scaled(),
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (s.result.isNotBlank()) Text(
                        s.result,
                        style = MaterialTheme.typography.caption3.copy(fontFamily = FontFamily.Monospace).scaled(),
                        color = if (s.error) Color(0xFFFF8A80) else MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Tool → one-glyph family (monochrome: survives any watch font; mirrors the desktop's </>-style tag). */
internal fun stepIcon(tool: String): String = when {
    tool.contains("terminal") || tool.contains("shell") || tool.contains("exec") -> "⌨"
    tool.contains("edit") || tool.contains("write") || tool.contains("patch") -> "✎"
    tool.contains("read") || tool.contains("list") || tool.contains("glob") -> "≡"
    tool.contains("search") || tool.contains("grep") || tool.contains("browser") || tool.contains("web") -> "⌕"
    tool.contains("delegate") || tool.contains("agent") -> "⇢"
    else -> "⚙"
}

@Composable
private fun CodeCard(lang: String, code: String, blockKey: Any, modifier: Modifier) {    var expanded by remember(blockKey) { mutableStateOf(false) }
    val label = lang.ifBlank { "code" }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colors.surface.copy(alpha = 0.4f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            if (expanded) "‹$label› code · tap to collapse" else "‹$label› code · tap",
            style = MaterialTheme.typography.caption3.scaled(),
            color = MaterialTheme.colors.primary,
        )
        if (expanded) {
            Text(
                code,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 150.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
                style = MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace).scaled(),
            )
        } else {
            val hint = code.lineSequence().firstOrNull { it.isNotBlank() }
            if (hint != null) Text(
                hint,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.caption3.copy(fontFamily = FontFamily.Monospace).scaled(),
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * F4 watchkit render: first composition POSTs the fence to watchkit /render/mermaid and shows the
 * returned PNG (server-side mmdc; see server/watchkit). Re-keys on config change so a pushed token
 * (Settings Test / adb set_watchkit) auto-retries failed blocks. States: spinner-line while the
 * request is in flight → image card on success → the original placeholder card on ANY error
 * (401/404/timeout/undecodable), tap-for-source behavior kept. Decodes via LruCache(8) in Watchkit.
 */
@Composable
private fun MermaidBlockView(code: String, blockKey: Any, modifier: Modifier) {
    val cfg by Watchkit.config.collectAsState()
    var img by remember(code, cfg) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(code, cfg) { mutableStateOf(false) }
    LaunchedEffect(code, cfg) {
        failed = false
        img = try { Watchkit.renderBitmap(code, width = 460) } catch (e: Exception) { failed = true; null }
    }

    val bitmap = img
    if (bitmap != null) {
        // Pinch-zoom (walkthrough): tapping the rendered diagram opens the full-screen viewer.
        // (mmdc renders on a white canvas — kept white here, rounded like CodeCard.)
        var zoomed by remember(blockKey) { mutableStateOf(false) }
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .clickable(onClickLabel = "zoom diagram") { zoomed = true },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "mermaid diagram",
                contentScale = ContentScale.Fit,
                modifier = Modifier.widthIn(max = 460.dp),
            )
        }
        if (zoomed) ZoomableMediaViewer(bitmap, caption = "mermaid", onDismiss = { zoomed = false })
        return
    }

    if (!failed) {
        Row(
            modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                indicatorColor = MaterialTheme.colors.primary,
                trackColor = MaterialTheme.colors.surface,
            )
            Text(
                "drawing diagram…",
                style = MaterialTheme.typography.caption3.scaled(),
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
            )
        }
        return
    }

    // Fallback = the pre-watchkit placeholder card; tap still toggles the source.
    var showSource by remember(blockKey) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colors.surface.copy(alpha = 0.4f))
            .clickable { showSource = !showSource }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            "📊 diagram (watchkit offline)",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.caption2.scaled(),
            color = MaterialTheme.colors.primary,
        )
        Text(
            if (showSource) "source · tap to hide" else "tap for source",
            style = MaterialTheme.typography.caption3.scaled(),
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
        )
        if (showSource) Text(
            code,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 150.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
            style = MaterialTheme.typography.caption3.copy(fontFamily = FontFamily.Monospace).scaled(),
        )
    }
}

/**
 * MEDIA EPIC inline card (owner 2026-11-02: "small, inline, you could click play — video
 * fullscreen on tap, hardware back returns to the session"). Videos show the watchkit
 * poster frame (ffmpeg one-JPEG grab) when one can be made, else a 🎬 glyph card; audio is
 * always the 🎵 glyph card. Tap opens [MediaPlayDialog] — a Dialog, so the transcript stays
 * composed underneath and back lands exactly where the finger was. Poster fetch re-keys on
 * watchkit config + media prefs like ImageBlock re-keys on config, so pushed tokens and
 * Settings ▸ Media changes refresh cards live.
 */
@Composable
private fun MediaBlock(block: MdBlock.Media, blockKey: Any, modifier: Modifier) {
    val cfg by Watchkit.config.collectAsState()
    val prefs by Watchkit.mediaPrefs.collectAsState()
    val local = Watchkit.mediaNeedsAuth(block.target)
    var poster by remember(block.target, cfg, prefs) { mutableStateOf<ImageBitmap?>(null) }
    var play by remember(blockKey) { mutableStateOf(false) }
    LaunchedEffect(block.target, cfg, prefs) {
        poster = if (block.video && local) try { Watchkit.fetchPoster(block.target) } catch (e: Exception) { null } else null
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colors.surface.copy(alpha = 0.35f))
            .clickable(onClickLabel = if (block.video) "play video" else "play audio") { play = true }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val bmp = poster
        if (bmp != null) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    bitmap = bmp,
                    contentDescription = block.label.ifBlank { "video" },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(8.dp)),
                )
                Text("▶", style = MaterialTheme.typography.title1, color = Color.White)
            }
        } else {
            Text(if (block.video) "🎬" else "🎵", style = MaterialTheme.typography.title2)
        }
        Text(
            block.label.ifBlank { if (block.video) "video" else "audio" },
            style = MaterialTheme.typography.caption2.scaled(),
            color = MaterialTheme.colors.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (block.video) "tap to play · fullscreen" else "tap to listen",
            style = MaterialTheme.typography.caption3.scaled(),
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
        )
    }
    if (play) MediaPlayDialog(block.target, block.video, block.label, onDismiss = { play = false })
}

/**
 * F6 inline image (OWNER BATCH 2 — FULL RES): the target is fetched ONCE at full resolution
 * (watchkit /img serves original bytes), then scaled ON THE WATCH: the card gets a bitmap decoded
 * down to ~512 px via BitmapFactory inSampleSize (crisp-but-small), while a tap opens the
 * pinch-zoom viewer on the FULL bitmap (decoded lazily on first zoom, sharing the one fetch —
 * see Watchkit.fetchImageCard / fetchImageFull, byte-aware LruCache). Re-keys on config change
 * like MermaidBlockView, so a pushed token auto-retries failed blocks. States: caption
 * placeholder → rounded bitmap card → on ANY error (403/404/offline/undecodable) a caption card
 * with alt + short error, tap reveals the target text.
 */
@Composable
private fun ImageBlock(alt: String, target: String, blockKey: Any, modifier: Modifier) {
    val cfg by Watchkit.config.collectAsState()
    var img by remember(target, cfg) { mutableStateOf<ImageBitmap?>(null) }
    var err by remember(target, cfg) { mutableStateOf<String?>(null) }
    LaunchedEffect(target, cfg) {
        err = null
        img = try {
            Watchkit.fetchImageCard(target)
        } catch (e: Exception) {
            err = (e.message ?: "fetch failed").take(90); null
        }
    }

    val bitmap = img
    if (bitmap != null) {
        // Pinch-zoom: tap opens the full-screen viewer; the FIRST zoom decodes the full-res
        // bitmap from the shared bytes (the card bitmap shows while it loads, then swaps —
        // deep zoom stays sharp because the pixels are the original ones).
        var zoomed by remember(blockKey) { mutableStateOf(false) }
        // GIF EPIC (owner 2026-11-02: "animated GIFs are not rendering"): the original container
        // is fetched from the SAME cache the card used (no extra bytes on the wire) and sniffed:
        // an animated GIF/WebP renders through AnimatedDrawablePainter — inline AND in the viewer,
        // playing, stopped when scrolled away. Stills keep the sampled card bitmap (that's the
        // inSampleSize memory story; a full-size Drawable inline would defeat it).
        var bytes by remember(target, cfg) { mutableStateOf<ByteArray?>(null) }
        LaunchedEffect(target, cfg) {
            bytes = runCatching { Watchkit.fetchImageSourceBytes(target) }.getOrNull()
        }
        val rawBytes = bytes
        val animated = rawBytes != null && looksAnimated(rawBytes)
        val frame = if (animated) rememberAnimatedImage(rawBytes) else AnimatedImage(null, false)
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colors.surface.copy(alpha = 0.25f))
                .clickable(onClickLabel = "zoom image") { zoomed = true },
            contentAlignment = Alignment.Center,
        ) {
            if (frame.painter != null)
                Image(
                    painter = frame.painter,
                    contentDescription = alt.ifBlank { "image" },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.widthIn(max = 440.dp),
                )
            else
                Image(
                    bitmap = bitmap,
                    contentDescription = alt.ifBlank { "image" },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.widthIn(max = 440.dp),
                )
            if (animated) Text(
                "GIF",
                style = MaterialTheme.typography.caption3,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (zoomed) {
            // Full-res viewer from the same bytes: the animated painter when there is a film,
            // the BitmapFactory full decode otherwise (identical sharpness to the old path).
            var full by remember(blockKey) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(Unit) {
                if (!animated) full = try { Watchkit.fetchImageFull(target) } catch (e: Exception) { null }
            }
            val viewerPainter = if (frame.painter != null) frame.painter
                else full?.let { androidx.compose.ui.graphics.painter.BitmapPainter(it) }
            ZoomableMediaViewer(
                viewerPainter ?: androidx.compose.ui.graphics.painter.BitmapPainter(bitmap),
                caption = alt, onDismiss = { zoomed = false },
            )
        }
        return
    }

    if (err == null) {
        Text(
            if (alt.isBlank()) "loading image…" else "loading image… · $alt",
            modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.caption3.scaled(),
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
        )
        return
    }

    // Error card: alt + short reason; tap toggles the raw target (like mermaid's tap-for-source).
    var showTarget by remember(blockKey) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colors.surface.copy(alpha = 0.4f))
            .clickable { showTarget = !showTarget }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            alt.ifBlank { "image" },
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.caption2.scaled(),
            color = MaterialTheme.colors.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            err + " · tap " + if (showTarget) "to hide target" else "for target",
            style = MaterialTheme.typography.caption3.scaled(),
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
        )
        if (showTarget) Text(
            target,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 100.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
            style = MaterialTheme.typography.caption3.copy(fontFamily = FontFamily.Monospace).scaled(),
        )
    }
}

private fun List<MdSpan>.annotated(prefix: String = ""): AnnotatedString =
    buildAnnotatedString {
        if (prefix.isNotEmpty()) append(prefix)
        appendSpans(this@annotated)
    }

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendSpans(spans: List<MdSpan>) {
    for (s in spans) when (s) {
        is MdSpan.Plain -> append(s.text)
        is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendSpans(s.children) }
        is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendSpans(s.children) }
        is MdSpan.InlineCode -> withStyle(
            SpanStyle(fontFamily = FontFamily.Monospace, background = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f)),
        ) { append(s.text) }
        // Clickable link: blue+underline for the eye, a string annotation for the finger
        // (MdBodyText reads the tag back off the tap offset). Nesting keeps inner styles.
        is MdSpan.Link -> {
            val start = length
            withStyle(SpanStyle(color = LinkBlue, textDecoration = TextDecoration.Underline)) { appendSpans(s.children) }
            addStringAnnotation("hermes.url", s.url, start, length)
        }
    }
}

private val LinkBlue = Color(0xFF8AB4F8)

/**
 * Body text with LINKS (owner: "URL links, they're not clickable … open whatever browser is
 * default on my watch"). Wraps foundation [ClickableText]: the tap offset resolves to a
 * "hermes.url" annotation (±1 — a tap right at the end of a link must still hit it), fired as an
 * ACTION_VIEW so the watch's default browser/handler chooses. No handler (many Wear builds ship
 * none) → honest toast, never a crash.
 */
@Composable
private fun MdBodyText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.body2.scaled(),
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // FOUNDATION Text trap (OWNER BUG: replies went black-on-black): ClickableText lives in
    // foundation, which never consults LocalContentColor like material Text does — and Wear's
    // typography styles leave color Unspecified, so every unspanned char fell back to BLACK.
    // Resolve the theme content color explicitly; spans (links) still override per-range.
    val base = style.copy(
        color = style.color.takeOrElse { androidx.wear.compose.material.LocalContentColor.current },
    )
    ClickableText(
        text = text,
        modifier = modifier,
        style = base,
        onClick = { offset ->
            text.getStringAnnotations("hermes.url", (offset - 1).coerceAtLeast(0), offset)
                .firstOrNull()?.item?.let { url ->
                    try {
                        ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(
                            ctx, "no app opens ${url.take(36)}", android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
        },
    )
}
