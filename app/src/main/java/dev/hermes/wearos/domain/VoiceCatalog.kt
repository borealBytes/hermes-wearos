package dev.hermes.wearos.domain

/**
 * Pure logic behind the ENGINE → local/network → voice cascade (owner 2026-10-29: *"are there
 * google voices for tts or just samsung? would be cool to have both… choose google/samsung/(other
 * if exists), then local/network, then it shows the list… also default and FALLBACK so someone
 * could choose network first and a local fallback"*).
 *
 * Engines come from the OS (`TextToSpeech.engines` — the watch has at least Google + Samsung).
 * A voice's transport is `Voice.getNetworkConnectionTier()` where the ROM exposes it (API 33);
 * otherwise the name convention carries it (Google voices end `-local`/`-network`). The chain
 * rule is here because it must be testable: preferred first, fallback second, nothing forced.
 */
object VoiceCatalog {

    const val ANY = "any"
    const val LOCAL = "local"
    const val NETWORK = "network"

    /**
     * Classify one voice. [tier] is `Voice.networkConnectionTier` (NEVER=1, IF_NECESSARY=2,
     * REQUIRED=3) or 0 when unknown/absent. An unclassifiable voice is treated as LOCAL — an
     * offline voice under the wrong filter just works; a network voice listed under "local"
     * is the friendlier failure mode.
     */
    fun classify(name: String, tier: Int): String = when (tier) {
        3 -> NETWORK
        1 -> LOCAL
        else -> when {
            name.contains("-network") || name.contains("-remote") || name.contains("online") -> NETWORK
            name.contains("-local") || name.contains("offline") -> LOCAL
            else -> LOCAL
        }
    }

    /**
     * The per-utterance speak chain: [preferred, fallback] minus blanks/dupes; both blank means
     * "engine default". No hidden default is appended — the engine's own voice is what a blank
     * preferred already means, and the owner's explicit "no fallback" must mean ONE attempt.
     */
    fun chain(preferred: String, fallback: String): List<String> =
        listOf(preferred.trim(), fallback.trim()).filter { it.isNotEmpty() }.distinct()
            .ifEmpty { listOf("") }

    /** Short vendor label for the engine row (Google/Samsung by pkg hint, else raw label). */
    fun friendlyEngine(label: String, pkg: String): String {
        val hay = (label + " " + pkg).lowercase()
        return when {
            "google" in hay -> "Google"
            "samsung" in hay || "bixby" in hay || "sround" in hay -> "Samsung"
            label.isNotBlank() -> label
            else -> pkg
        }
    }
}
