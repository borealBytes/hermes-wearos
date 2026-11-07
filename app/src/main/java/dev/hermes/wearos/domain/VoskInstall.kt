package dev.hermes.wearos.domain

/**
 * IN-APP VOSK MODEL INSTALL (owner 2026-11-06: *"install the local Vosk as a check option"*).
 *
 * The offline STT model used to be a script's job (`scripts/get-vosk-model.sh` + adb). This is
 * the pure logic for doing it from Settings ▸ Voice in: the watch streams the zip from the
 * gateway's watchkit (`GET /stt/vosk-model`, gateway-cached, Range-resumable) and unpacks it
 * into filesDir exactly where [dev.hermes.wearos.voice.VoskStt] looks.
 *
 * Pure (no Android types): state, labels, zip path mapping and resume math all JVM-tested.
 */
sealed class VoskInstallState {
    /** Nothing installed; no transfer running. Initial state, and where cancel/failure land. */
    data object Missing : VoskInstallState()

    /**
     * Zip bytes riding in **on this response** (a resumed pull counts only the tail; the head
     * already held rides in [resumeFrom]). [total] is Content-Length of the same response.
     */
    data class Download(
        val done: Long,
        val total: Long,
        val resumeFrom: Long = 0L,
    ) : VoskInstallState() {
        /** Bytes on disk overall (resume head + this session's tail). */
        val received: Long get() = done + resumeFrom
        /** Full zip size the fraction is against (Content-Length of a 206 covers only the tail). */
        val overall: Long get() = total + resumeFrom
        /** 0..99; -1 when the server did not report a length (no fake precision). */
        val fraction: Int
            get() = if (total <= 0L || overall <= 0L) -1
            else ((received.coerceAtMost(overall) * 100) / overall).toInt().coerceAtMost(99)
    }

    /** Zip complete; entries being written + verified. */
    data object Unpacking : VoskInstallState()

    /** Model tree in place and marker-verified. */
    data object Ready : VoskInstallState()

    /** Human reason shown verbatim — the wearer decides whether to retry. */
    data class Failed(val reason: String) : VoskInstallState()
}

/** Pure helpers for the installer states (referenced by UI, dev hook, and JVM tests). */
object VoskInstall {

    /** Canonical model the app supports (mirrors watchkit `VOSK_MODEL` + get-vosk-model.sh). */
    const val MODEL_VERSION = "vosk-model-small-en-us-0.15"
    const val ENDPOINT = "/stt/vosk-model"

    /** Marker files proving an unpacked model is really complete (VoskStt.isModelPresent). */
    private val MARKERS = setOf("final.mdl", "am/final.mdl", "am-final.mdl")

    /**
     * Map a zip entry name to its path under the model dir, or null to skip it.
     * The upstream zip wraps everything in `<MODEL_VERSION>/`; junk (mac resource forks,
     * dirs, dotfiles) is skipped. Guards against `..` traversal — this zip is network input.
     */
    fun mappedRelPath(entryName: String): String? {
        val e = entryName.replace('\\', '/')
        if (e.isBlank() || e.contains("..")) return null
        if (e.startsWith("__MACOSX") || e.substringAfterLast('/').startsWith(".") ||
            e.substringAfterLast('/').endsWith(".DS_Store")
        ) return null
        val cut = if (e.startsWith(MODEL_VERSION + "/")) e.indexOf('/') + 1 else return null
        val rel = e.substring(cut).trimEnd('/')
        if (rel.isEmpty() || e.endsWith("/")) return null
        return rel
    }

    /** True when a mapped entry set contains a model marker (unpack verification). */
    fun hasModelMarker(relPaths: Set<String>): Boolean = MARKERS.any { it in relPaths }

    /** Range header for a `.part` of [existing] bytes; null = full GET. */
    fun rangeFor(existing: Long): String? = if (existing > 0L) "bytes=$existing-" else null

    /** "39 MB" / "612 KB" for labels (never more precision than the wearer can act on). */
    fun humanBytes(b: Long): String = when {
        b >= 1_000_000L -> "${(b + 500_000) / 1_000_000} MB"
        b >= 1_000L -> "${(b + 500) / 1_000} KB"
        else -> "$b B"
    }

    /** One-line label for the settings card + the `vosk_status` dev hook. */
    fun statusLabel(state: VoskInstallState): String = when (state) {
        VoskInstallState.Missing -> "not installed"
        is VoskInstallState.Download ->
            if (state.fraction >= 0)
                "downloading ${state.fraction}% · ${humanBytes(state.received)} of ${humanBytes(state.overall)}"
            else "downloading ${humanBytes(state.received)}…"
        VoskInstallState.Unpacking -> "unpacking…"
        VoskInstallState.Ready -> "installed · works offline"
        is VoskInstallState.Failed -> "failed: ${state.reason}"
    }
}
