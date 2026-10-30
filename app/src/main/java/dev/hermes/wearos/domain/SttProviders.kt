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
}
