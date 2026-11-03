package dev.hermes.wearos.domain

/**
 * TOOL ACTIVITY LEDGER (owner 2026-11-02: "I want to see more verbosity for code edits and
 * terminal outputs when running and when done — a collapsable box like hermes desktop; right now
 * things go straight to the answers and I feel like I'm missing what's actually going on").
 *
 * The gateway's run SSE carries the theatre already — `tool.started {tool, preview,
 * tool_call_id}` (preview = the command / file path / "cmd + N more") and `tool.completed
 * {tool, duration, error, tool_call_id}` — the watch just dropped it on the floor (a transient
 * `🔧 terminal…` line). This is the ledger the session keeps DURING a run; on settle it is
 * [encode]d into a ```hermes-steps fence and stored in the transcript right before the answer,
 * so the card survives reloads. WatchMarkdown parses that fence back into an ActivityRun block
 * (ui/md); the live run renders the SAME card straight from this list — no fence round-trip.
 *
 * Fence format (one line per step, '|' fields, no '|' or newline can survive inside a field —
 * [san] rewrites them): `tool|durS|status|preview|result`, status ∈ run|ok|err. The `result`
 * half is what a NEWER gateway's tool.completed.preview carries (bounded 500 chars, redacted);
 * today's gateway sends "" — the field is there the moment the box updates, no protocol change.
 */
data class ToolStep(
    val callId: String,
    val tool: String,
    val preview: String,
    val done: Boolean = false,
    val error: Boolean = false,
    val durS: Double = 0.0,
    val result: String = "",
) {
    val status: String get() = if (!done) "run" else if (error) "err" else "ok"
}

object StepsFence {
    const val LANG = "hermes-steps"

    fun encode(steps: List<ToolStep>): String = buildString {
        append("```").appendLine(LANG)
        for (s in steps) {
            appendLine(
                listOf(san(s.tool), "%.2f".format(s.durS), s.status, san(s.preview), san(s.result))
                    .joinToString("|"),
            )
        }
        appendLine("```")
    }.trim()

    /** Field hygiene: pipes would break the row, newlines would break the "one line per step". */
    fun san(s: String): String =
        s.replace('\n', '⏎').replace('\r', ' ').replace('|', '/').trim().take(200)
}
