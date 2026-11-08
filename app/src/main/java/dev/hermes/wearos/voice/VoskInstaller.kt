package dev.hermes.wearos.voice

import android.content.Context
import android.util.Log
import dev.hermes.wearos.domain.VoskInstall
import dev.hermes.wearos.domain.VoskInstallState
import dev.hermes.wearos.net.HermesApi
import dev.hermes.wearos.net.Watchkit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * Drives [VoskInstallState]: streams the model zip from the wearer's own gateway (bearer +
 * tailnet ride `Watchkit.config`; Range resume survives a wifi dropout mid-download), unpacks
 * it into `files/vosk-model-small-en-us` — the exact tree scripts/get-vosk-model.sh produced —
 * verifies the acoustic-model marker, and swaps atomically (temp dir + rename), so a cancelled
 * install never leaves a half model behind where [VoskStt.isModelPresent] would say "present".
 *
 * One transfer at a time; a second install() is a no-op while one runs. Errors land as
 * [VoskInstallState.Failed] with the reason verbatim — the retry is a tap away, nothing else.
 */
class VoskInstaller(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<VoskInstallState>(
        if (VoskStt.isModelPresent(context)) VoskInstallState.Ready else VoskInstallState.Missing
    )
    val state = _state.asStateFlow()

    @Volatile private var running: Job? = null
    @Volatile private var cancelRequested = false

    val isBusy: Boolean get() = running?.isActive == true

    /** Kick the offline pull. No-op while busy or when the model is already in place. */
    fun install() {
        if (isBusy || _state.value is VoskInstallState.Ready) return
        cancelRequested = false
        running = scope.launch(Dispatchers.IO) {
            _state.value = VoskInstallState.Download(0, 0)
            try {
                val part = File(context.cacheDir, "vosk-model.zip.part")
                val have = if (part.exists()) part.length() else 0L
                val ok = transfer(part, have)
                if (cancelRequested) { _state.value = VoskInstallState.Missing; return@launch }
                if (!ok) return@launch // transfer already set Failed
                _state.value = VoskInstallState.Unpacking
                if (unzipAndSwap(part)) {
                    part.delete()
                    _state.value = VoskInstallState.Ready
                    Log.i(TAG, "vosk model installed (${VoskInstall.MODEL_VERSION})")
                } else {
                    _state.value = VoskInstallState.Failed("model unpack failed")
                }
            } catch (e: Exception) {
                Log.w(TAG, "vosk install failed: ${e.message}")
                _state.value = VoskInstallState.Failed(shortReason(e))
            }
        }
    }

    fun cancel() {
        cancelRequested = true
        running?.cancel()
        if (_state.value !is VoskInstallState.Missing) _state.value = VoskInstallState.Missing
    }

    /** Free the ~47 MB: delete the unpacked tree (keep the .part zip for a fast re-install). */
    fun remove() {
        if (isBusy) return
        scope.launch(Dispatchers.IO) {
            VoskStt.modelDir(context).deleteRecursively()
            withContext(Dispatchers.Main) { _state.value = VoskInstallState.Missing }
        }
    }

    /** Re-sync after e.g. get-vosk-model.sh touched the tree out from under us. */
    fun refresh() {
        if (isBusy) return
        _state.value = if (VoskStt.isModelPresent(context)) VoskInstallState.Ready else VoskInstallState.Missing
    }

    // ── transport ────────────────────────────────────────────────────────────
    /** Returns true when `part` holds the complete zip; sets state (Failed/Download) itself. */
    private fun transfer(part: File, resumeFrom: Long): Boolean {
        val cfg = Watchkit.config.value
        if (cfg.baseUrl.isBlank() || cfg.token.isBlank()) {
            _state.value = VoskInstallState.Failed("gateway not configured")
            return false
        }
        val req = Request.Builder()
            .url(cfg.baseUrl + VoskInstall.ENDPOINT)
            .header("Authorization", "Bearer ${cfg.token}")
            .apply { VoskInstall.rangeFor(resumeFrom)?.let { header("Range", it) } }
            .get().build()
        HermesApi.DEFAULT.newCall(req).execute().use { res ->
            when {
                res.code == 404 -> {
                    _state.value = VoskInstallState.Failed("gateway too old — update watchkit")
                    return false
                }
                !res.isSuccessful -> {
                    _state.value = VoskInstallState.Failed(
                        (if (res.code == 502) "gateway could not fetch model" else "gateway http ${res.code}")
                    )
                    return false
                }
                else -> {
                    // A 200 answer to our Range means the server ignored/resumed-less — restart clean.
                    val r0 = if (res.code == 206) resumeFrom else 0L
                    var written = if (res.code == 206) resumeFrom else { part.delete(); 0L }
                    val total = res.body?.contentLength() ?: -1L
                    _state.value = VoskInstallState.Download(written - r0, total.coerceAtLeast(0), resumeFrom = r0)
                    val body = res.body ?: return false.also { _state.value = VoskInstallState.Failed("empty body") }
                    RandomAccessFile(part, "rw").use { raf ->
                        if (res.code != 206) raf.setLength(0)
                        raf.seek(written)
                        val buf = ByteArray(64 * 1024)
                        val input = body.byteStream()
                        var lastPublish = 0L
                        while (true) {
                            if (cancelRequested) return false.also { _state.value = VoskInstallState.Missing }
                            val n = input.read(buf)
                            if (n <= 0) break
                            raf.write(buf, 0, n)
                            written += n
                            // ~256 KB granularity: progress recompositions on a watch cost battery.
                            if (written - lastPublish > 256 * 1024) {
                                lastPublish = written
                                _state.value = VoskInstallState.Download(written - r0, total.coerceAtLeast(0), resumeFrom = r0)
                            }
                        }
                    }
                    val expected = if (total > 0) total + (if (res.code == 206) resumeFrom else 0L) else -1L
                    if (expected in 0 until written) {
                        _state.value = VoskInstallState.Failed("gateway sent extra bytes")
                        return false
                    }
                    return true
                }
            }
        }
    }

    // ── unpack ───────────────────────────────────────────────────────────────
    /** Zip → `filesDir/.vosk-install-tmp/…` → marker verify → atomic rename into place. */
    private fun unzipAndSwap(zip: File): Boolean {
        val tmp = File(context.filesDir, ".vosk-install-tmp")
        tmp.deleteRecursively(); tmp.mkdirs()
        val seen = HashSet<String>()
        try {
            ZipFile(zip).use { z ->
                val entries = z.entries()
                while (entries.hasMoreElements()) {
                    val en = entries.nextElement()
                    val rel = VoskInstall.mappedRelPath(en.name) ?: continue
                    val out = File(tmp, rel)
                    // Belt over the mapper's `..` guard: resolved path must stay under tmp.
                    if (!out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) continue
                    out.parentFile?.mkdirs()
                    z.getInputStream(en).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    seen += rel
                }
            }
            if (!VoskInstall.hasModelMarker(seen)) return false
            val dst = VoskStt.modelDir(context)
            dst.deleteRecursively()
            return tmp.renameTo(dst) || run {
                // Cross-directory rename can fail (should not, same volume) — copy fallback.
                dst.mkdirs()
                tmp.copyRecursively(dst, overwrite = true)
                tmp.deleteRecursively()
                VoskStt.isModelPresent(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "vosk unpack: ${e.message}")
            return false
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun shortReason(e: Exception): String {
        val m = e.message?.take(80) ?: e.javaClass.simpleName
        return when {
            m.contains("Failed to connect") || m.contains("connect timed out") -> "gateway unreachable"
            m.contains("SocketTimeout") || m.contains("timeout") -> "download timed out"
            else -> m
        }
    }

    private companion object { const val TAG = "HermesWear" }
}
