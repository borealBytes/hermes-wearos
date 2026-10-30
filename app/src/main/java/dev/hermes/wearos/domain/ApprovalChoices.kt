package dev.hermes.wearos.domain

/**
 * WHICH STRING to send for ✅ / ❌ (batch-4 #5). The gateway's approval prompt carries its own
 * `choices[]` vocabulary ("approve"/"deny", "yes"/"no", vendor variants), and the ✅-left-of-orb
 * flanks, the ❌-right flanks, and the in-list card must resolve it IDENTICALLY — a flank that
 * sends the wrong verb silently runs (or kills) the wrong action. Pure so both UIs share one
 * tested source instead of the duplicated firstOrNull the card used before.
 */
object ApprovalChoices {

    /** The approve verb: the first choice that is NOT a deny. Junk list → the protocol default. */
    fun approveName(choices: List<String>): String =
        choices.firstOrNull { !it.contains("deny", true) && !it.equals("no", true) } ?: "approve"

    /** The deny verb: the first choice that IS one. Junk list → the protocol default. */
    fun denyName(choices: List<String>): String =
        choices.firstOrNull { it.contains("deny", true) || it.equals("no", true) } ?: "deny"
}
