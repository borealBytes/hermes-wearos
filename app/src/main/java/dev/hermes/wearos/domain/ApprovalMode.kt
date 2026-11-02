package dev.hermes.wearos.domain

/**
 * PER-CHAT APPROVAL MODE (owner 2026-10-29: *"in the desktop app we have manual, smart and off
 * for approvals… this would let us change the approval mode by chat session … it's up to the user
 * on the watch to choose how much interaction they want"*).
 *
 * GATEWAY REALITY (checked against the agent source on the gateway box, 2026-10-29): the server's
 * `approvals.mode` is PROFILE-scoped (`/approvals [manual|smart|off]` writes the profile's
 * config.yaml; `run_approval_mode_command`) and the only per-session lever is `/yolo` (an
 * in-memory bypass set). There is no per-chat manual/smart/off store server-side yet — so the
 * watch enforces the pick CLIENT-SIDE at the moment an approval arrives, which is the exact
 * decision point the owner described ("how much interaction *I* want"):
 *
 *  - [Inherit] — the agent's own default decides when it asks; the watch never auto-answers.
 *  - [Manual]  — every approval is answered by the human (even mid-watch).
 *  - [Smart]   — auto-answer while the human is ABSENT from that chat (keep the simple thing
 *                moving); ask when they're watching it.
 *  - [Off]     — auto-answer every approval for this chat, never wait for the wrist.
 *
 * A server per-session lever, when it lands, replaces [autoApprove]'s watch-side call site only —
 * the persisted id and the picker stay.
 */
enum class ApprovalMode(val id: String, val label: String, val hint: String) {
    Inherit("", "inherit", "what the agent does by default"),
    Manual("manual", "manual", "you answer every approval"),
    Smart("smart", "smart", "auto while you're away, asks when you watch"),
    Off("off", "off", "never wait for you");

    /** Does this mode answer [viewing]-state approval itself? The one decision point. */
    fun autoApprove(viewing: Boolean): Boolean = when (this) {
        Off -> true
        Smart -> !viewing
        else -> false
    }

    companion object {
        /** Unknown/legacy/garbage → [Inherit] (additive field on old chats reads as today's behavior). */
        fun fromId(id: String?): ApprovalMode =
            entries.firstOrNull { it.id.isNotEmpty() && it.id == id?.trim()?.lowercase() } ?: Inherit
    }
}
