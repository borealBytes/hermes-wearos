package dev.hermes.wearos.domain

/**
 * DEV RENDER CASES (SPEC §13.3) — the proof harness for the media surfaces. The owner's ask was
 * "test by sending content that exercises it"; asking a live model for a five-column table, a
 * mermaid fence, a PDF and a link is four coin flips. `adb shell am broadcast -n
 * dev.hermes.wearos/.DevAskReceiver -e render <case>` injects one of these canned answers into the
 * live session instead (no gateway, no model, deterministic), so a screenshot proves the renderer.
 *
 * Pure strings + a resolver so the case list is unit-tested and the dev hook stays dumb.
 * `tables` is the branch-1 case (the 5-column grid + the >5 degradation); the others exist so the
 * branch-2 items (viewer/mermaid/pdf/links) have a fixture the day they land.
 */
enum class RenderCase(val id: String, val markdown: String) {
    Tables(
        "tables",
        "Harbor slips, four columns (BATCH-10: the labelled grid centers on the glass):\n\n" +
            "| Slip | Depth | Tide | Wind |\n" +
            "|---|---|---|---|\n" +
            "| B-12 | 14m | rising | SW 12 |\n" +
            "| B-14 | 12m | rising | SW 9 |\n" +
            "| B-16 | 9m | falling | calm |\n\n" +
            "Six columns degrade to readable rows on purpose (SPEC §13.1):\n\n" +
            "| Region | Q1 | Q2 | Q3 | Plan | Owner |\n" +
            "|---|---|---|---|---|---|\n" +
            "| NA | 120 | 138 | 141 | +17% | dana |\n" +
            "| EMEA | 84 | 91 | 103 | +23% | omar |\n\n" +
            "Inline code `wear --orb dial` and a list:\n" +
            "- dial lane 6:00 opens the slash overlay\n" +
            "- `/steer fix the table width` while a run streams\n",
    ),
    Wide(
        "wide",
        "A wide table is the viewer's job, not the inline one's:\n\n" +
            "| Route | Load p50 | Load p95 | Cache | Errors | Cost | Status |\n" +
            "|---|---|---|---|---|---|---|\n" +
            "| /v1/runs | 84ms | 412ms | 91% | 0.2% | \$0.004 | ok |\n" +
            "| /p/wear/v1/health | 3ms | 11ms | 100% | 0% | \$0 | ok |\n" +
            "| /img | 240ms | 1.8s | 62% | 1.1% | \$0.001 | degraded |\n" +
            "| /stt/whisper | 2.1s | 6.4s | 0% | 0.4% | \$0.02 | ok |\n",
    ),
    Mermaid(
        "mermaid",
        "The whole voice round-trip, one diagram (BATCH-10: richer than the 3-node sketch):\n\n" +
            "```mermaid\n" +
            "graph LR\n" +
            "  subgraph WATCH [on the wrist]\n" +
            "    M[mic PTT] --> V[Vosk STT offline]\n" +
            "    V --> T[ask]\n" +
            "  end\n" +
            "  T -->|SSE /v1/runs| G[gateway]\n" +
            "  G --> C{tool?}\n" +
            "  C -->|yes, risky| A[approve on the orb flanks]\n" +
            "  A --> X[terminal / skill]\n" +
            "  C -->|no| R[answer stream]\n" +
            "  X --> R\n" +
            "  R --> S[markdown on the glass]\n" +
            "  class M,V,T wrist;\n" +
            "  classDef wrist fill:#2b3a67,stroke:#7aa2ff,color:#fff;\n" +
            "  classDef dec fill:#3a2b67,stroke:#b39dff,color:#fff;\n" +
            "  class C dec;\n" +
            "```\n",
    ),
    Pdf(
        "pdf",
        "Deck is filed; the numbers table is on page 3, so open it here or download it:\n\n" +
            "![Deck cover](/tmp/opencode/sample.pdf)\n\n" +
            "PDFs open in the on-watch viewer (PdfRenderer at API 36) with a Download action and a\n" +
            "handoff to WearFiles when one is installed.\n",
    ),
    Link(
        "link",
        "Two links to prove the tap lane:\n\n" +
            "- the gateway dashboard: [8642 dashboard](http://127.0.0.1:8642/)\n" +
            "- the Hermes repo: [borealBytes/hermes](https://github.com/borealBytes)\n\n" +
            "A tap fires ACTION_VIEW — the watch's default browser opens it; with no resolver the\n" +
            "watch says so plainly instead of eating the tap. A bare https://openrouter.ai/models\n" +
            "autolinks too.\n",
    ),
    /** Animated GIF card (ledger row 51): the same /img route serving a gif — the watch decodes
     *  it with ImageDecoder and animates in place (SPEC §12.14). /tmp/anim.gif is the staging
     *  fixture docs/DEMO.md §6 copies onto the gateway box. */
    Gif(
        "gif",
        "Mooring cam, compressed to a loop:\n\n" +
            "![mooring cam loop](/tmp/anim.gif)\n\n" +
            "Same /img route as every image card — the gif bytes ride through verbatim and the " +
            "watch animates them on the glass.\n",
    ),
    Media(
        "media",
        "Everything at once, worst case:\n\n" +
            "![dial mock](/tmp/opencode/dial3.png)\n\n" +
            "| Surface | Branch | Status |\n|---|---|---|\n| tables | 1 | inline grid |\n" +
            "| viewer | 2 | pinch |\n| mermaid | 2 | SVG painter |\n\n" +
            "```mermaid\ngraph LR\n  A[dial] --> B[lane]\n```\n\n" +
            "See [the spec](docs/SPEC.md) §13 for the rest.\n",
    ),
    /**
     * MEDIA EPIC fixture (owner 2026-11-02; BATCH-10 rework): the comfy render rides the gif lane
     * — owner 2026-11-07: "video media should be gifs, render in comfyui via hermes agent aurora
     * borealis video" — so the card ANIMATES in the chat (ImageDecoder lane, shot 50's proof)
     * instead of a static poster you must tap open. The audio clip (wav → m4a via watchkit
     * /media) stays. `-e render av` + a tap each = the whole lane in one screenshot.
     */
    Av(
        "av",
        "The comfy aurora render is ready (it animates right here), and the sting is mixed:\n\n" +
            "MEDIA:/tmp/opencode/aurora-vid.gif\n\n" +
            "MEDIA:/tmp/tone.opus\n\n" +
            "Tap a card for fullscreen; back returns here.\n",
    ),
    Steps(
        "steps",
        "Fixed the maturity maps — here's the run trace (tap the ⚙ box to collapse/expand):\n\n" +
            "```hermes-steps\n" +
            "terminal|1.24|ok|python render_maturity_maps.py --year 2023|\n" +
            "read_file|0.03|ok|research/figures/corn_rm_us_canada_2019_2023_mean.png|\n" +
            "edit_file|0.05|ok|render_maturity_maps.py|+6 -2 (colorbar label fix)\n" +
            "terminal|0.41|err|pytest -q tests/test_maps.py|2 failed, 41 passed ⏎ AssertionError: cmap length\n" +
            "edit_file|0.04|ok|render_maturity_maps.py|tick rounding moved below colorbar\n" +
            "terminal|2.10|ok|pytest -q tests/test_maps.py|43 passed in 1.9s\n" +
            "```\n\n" +
            "All green now.\n",
    ),
    All(
        "all",
        Tables.markdown + "\n" + Wide.markdown + "\n" + Mermaid.markdown + "\n" +
            Pdf.markdown + "\n" + Link.markdown,
    );

    companion object {
        fun of(id: String): RenderCase? = entries.firstOrNull { it.id == id.trim().lowercase() }

        /** `-e render list` / a bad id — the ids the dev can actually type. */
        fun ids(): String = entries.joinToString(",") { it.id }
    }
}
