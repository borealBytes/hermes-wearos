package dev.hermes.wearos.domain

/**
 * SPEECH-INPUT PROVIDERS (batch-4 #8, owner: Settings ▸ Voice ▸ *"Speech input provider
 * picker: Local Vosk default/offline · System … honestly labeled OFF-DEVICE when networked,
 * auto-detected per vendor"*).
 *
 * Pure labelling only — the engines live in voice/ (VoskStt, SystemStt) and the persisted id
 * lives in AppSettings (`stt_provider`). Default is LOCAL even when the system recognizer is
 * glorious: the owner's watch must keep working in airplane mode (SPEC §5 offline rule).
 */
enum class SttProvider { LOCAL, SYSTEM, GATEWAY }

object SttProviders {
    const val ID_LOCAL = "local"
    const val ID_SYSTEM = "system"
    const val ID_GATEWAY = "gateway"

    /**
     * In-memory truth of the picked provider (AppSettings seeds it at startup; the picker
     * writes both this and prefs). Synchronous by design: the mic path asks "which engine NOW"
     * on every startListening and must never race a flow emission.
     */
    @Volatile
    var current: String = ID_LOCAL

    /** Anything unknown (first run, garbage in prefs) is LOCAL — the offline-safe default. */
    fun fromId(id: String?): SttProvider = when (id) {
        ID_SYSTEM -> SttProvider.SYSTEM
        ID_GATEWAY -> SttProvider.GATEWAY
        else -> SttProvider.LOCAL
    }

    /** Pref-safe canonical id: garbage (or an older build's write) collapses to the default. */
    fun normalize(id: String?): String = id(fromId(id))

    fun id(p: SttProvider): String = when (p) {
        SttProvider.LOCAL -> ID_LOCAL
        SttProvider.SYSTEM -> ID_SYSTEM
        SttProvider.GATEWAY -> ID_GATEWAY
    }

    /**
     * Vendor NAME from the resolved RecognitionService package (auto-detect, owner's
     * "Pixel/OnePlus → Google"): the well-known stacks map to friendly names, anything else
     * shows its package so the wearer knows what they just opted into.
     */
    fun vendor(recognizerPackage: String?): String = when {
        recognizerPackage == null -> "system"
        recognizerPackage.startsWith("com.google.") -> "Google"
        recognizerPackage.startsWith("com.samsung.") -> "Samsung"
        recognizerPackage.contains("microsoft") -> "Microsoft"
        else -> recognizerPackage
    }

    /**
     * Honest picker subtitle (owner's word: HONESTLY). The system recognizer (Google/Samsung)
     * uploads audio — it says OFF-DEVICE even on a vendor stack that caches some locally,
     * because the wearer must never assume airplane-mode speech-to-text here.
     */
    fun subtitle(
        provider: SttProvider,
        localModelPresent: Boolean,
        systemAvailable: Boolean,
        recognizerPackage: String?,
        gatewayEngine: String? = null,
    ): String = when (provider) {
        SttProvider.LOCAL ->
            if (localModelPresent) "offline · on-watch" else "model missing · get-vosk-model.sh"
        SttProvider.SYSTEM ->
            if (systemAvailable) "OFF-DEVICE · ${vendor(recognizerPackage)}"
            else "no recognizer on this watch"
        // #9: names the engine watchkit's /health actually reports (Settings shows it), and
        // says what happens when the gateway cannot answer — it never strands the wearer.
        SttProvider.GATEWAY ->
            if (gatewayEngine != null) "streaming · $gatewayEngine · auto-local"
            else "watchkit offline · auto-local"
    }

    /** Whether picking this provider can actually listen right now (row-disabled reason). */
    fun usable(
        provider: SttProvider,
        localModelPresent: Boolean,
        systemAvailable: Boolean,
    ): Boolean = when (provider) {
        SttProvider.LOCAL -> localModelPresent
        SttProvider.SYSTEM -> systemAvailable
        // GATEWAY is ALWAYS usable: its contract is the local auto-fallback (voice/GatewayStt).
        SttProvider.GATEWAY -> true
    }

    // ── LISTENING-OVERLAY LABELS (batch-4 #13, owner: *"on the screen where you are entering
    // voice … very top small … voice input provider listed, and … if it's streaming the text
    // back … or not … really simple, like maybe a checkbox"*) — pure so the honesty is tested.

    /** Short provider NAME for the overlay's top line; gateway names the live engine. */
    fun overlayName(
        provider: SttProvider,
        recognizerPackage: String? = null,
        gatewayEngine: String? = null,
    ): String = when (provider) {
        SttProvider.LOCAL -> "Local Vosk"
        SttProvider.SYSTEM -> "System · ${vendor(recognizerPackage)}"
        SttProvider.GATEWAY -> "Gateway · " + (gatewayEngine ?: "engine ?")
    }

    /**
     * Can this provider stream PARTIAL text while talking? LOCAL/SYSTEM yes (both emit
     * Partials); GATEWAY depends on the ENGINE — parakeet re-decodes per window (partials),
     * whisper is buffered (final only at stop). Unknown engine = unknown (null), never a lie.
     */
    fun streamsText(provider: SttProvider, gatewayEngine: String? = null): Boolean? =
        when (provider) {
            SttProvider.LOCAL, SttProvider.SYSTEM -> true
            SttProvider.GATEWAY -> when (gatewayEngine) {
                "parakeet" -> true
                "whisper" -> false
                else -> null
            }
        }

    /** Settings ▸ root row subtitle (#13): which engine the orb will use. No env probes —
     *  static per provider so the hub never lies while availability refreshes in the page. */
    fun navSummary(provider: SttProvider): String = when (provider) {
        SttProvider.LOCAL -> "Local Vosk · offline"
        SttProvider.SYSTEM -> "System recognizer"
        SttProvider.GATEWAY -> "Gateway · streaming"
    }

    /** The #13 "checkbox" sentence: ✓ when text streams, ✗ + what to expect instead. */
    fun streamsLabel(streams: Boolean?): String = when (streams) {
        true -> "text streaming ✓"
        false -> "text streaming ✗ · final when you stop"
        null -> "text streaming ?"
    }

    // ── GATEWAY ENGINE SELECTION (owner 2026-10-30: *"it should let you … select … installed
    // models … we have two and we're set up to expand in the future"*) ────────────────────────

    /** One row of watchkit's /health `stt.engines[]` inventory. */
    data class SttEngineInfo(
        val id: String,
        val streaming: Boolean,
        val ready: Boolean,
        val missing: String = "",
    )

    /**
     * In-memory wearer pick for the GATEWAY provider ("" = use watchkit's configured default).
     * Same synchronous-moment contract as [current]: GatewayStt reads it when the mic opens.
     */
    @Volatile
    var gatewayEnginePick: String = ""

    /** Which engine the mic will ACTUALLY talk to right now (overlay/label truth). A pick only
     *  counts when /health says that engine is installed-ready; otherwise the server default. */
    fun effectiveEngine(
        pick: String = gatewayEnginePick,
        healthDefault: String?,
        engines: List<SttEngineInfo> = emptyList(),
    ): String? {
        val p = pick.trim().lowercase()
        if (p.isNotEmpty() && engines.any { it.id.lowercase() == p && it.ready }) return p
        return healthDefault
    }

    /**
     * Streaming truth for a gateway engine. /health's `streaming` flag is authoritative (a new
     * engine needs no watch update); the hardcoded map is only the offline fallback for the
     * two engines that predate the inventory. Unknown = null, never a lie.
     */
    fun engineStreams(id: String?, engines: List<SttEngineInfo> = emptyList()): Boolean? {
        val e = engines.firstOrNull { it.id.equals(id, ignoreCase = true) }
        if (e != null) return e.streaming
        return when (id) { "parakeet" -> true; "whisper" -> false; else -> null }
    }

    /** One engine-pick row label: name + what it will do (installed state honest). */
    fun engineRowLabel(e: SttEngineInfo): String =
        e.id + " · " + if (!e.ready) "not installed" else streamsLabel(engineStreams(e.id))

    /** Whether a pick is currently in effect (row selection marker). */
    fun pickMatches(pick: String, id: String?): Boolean =
        pick.trim().isNotEmpty() && pick.trim().equals(id, ignoreCase = true)
}
