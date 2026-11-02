package dev.hermes.wearos.net

import android.content.Context
import android.util.Log
import dev.hermes.wearos.domain.TailnetLogic
import dev.hermes.wearos.domain.TailnetStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import tailnet.Tailnet

/**
 * The embedded tailnet node (SPEC §11.4). One process-wide owner of the Go engine
 * (gomobile AAR `app/libs/hermes-tailnet.aar`): [startOnce] boots it on a dedicated
 * thread with retry/backoff, polls `statusJSON()` into [status], and mirrors the SOCKS
 * port into [TailnetRoute] — the only way the rest of the app learns about the tunnel.
 *
 * Threading: every `tailnet.Tailnet` native call runs on the ONE engine thread (the Go
 * side is a single global node; cross-thread calls are safe but this keeps ordering
 * obvious). [stop] may be called from any thread — it flips the loop flag and asks the
 * Go side to close; in-flight dials die with it, which is exactly the semantics OkHttp
 * recovers from on its own.
 */
object TailnetEngine {
    private const val TAG = "HermesWear"
    private val Json_ = Json { ignoreUnknownKeys = true }

    private val _status = MutableStateFlow(TailnetStatus())
    val status: StateFlow<TailnetStatus> = _status

    @Volatile private var running = false
    private var loop: Thread? = null
    private var stateDir: String = ""

    /** Idempotent start. `key` enrolls a FRESH node only (tsnet ignores it once enrolled);
     *  `forceLogin` wipes local identity state first (the Re-enroll path). */
    fun startOnce(context: Context, hostname: String, key: String, forceLogin: Boolean = false) {
        val dir = java.io.File(context.filesDir, "tailnet").absolutePath
        // The Go env is read at runtime-init under cgo; set the logpolicy jail
        // escapes from the JVM side too (android.system.Os.setenv) so they exist
        // BEFORE the Go runtime snapshots its environment. (Go Start() re-sets them.)
        runCatching {
            android.system.Os.setenv("TS_LOGS_DIR", java.io.File(dir, "logs").absolutePath, true)
            android.system.Os.setenv("TS_NO_LOGS_NO_SUPPORT", "1", true)
            android.util.Log.i("HermesWear", "tailnet: JVM setenv TS_LOGS_DIR=" + android.system.Os.getenv("TS_LOGS_DIR"))
        }.onFailure { android.util.Log.w("HermesWear", "tailnet: JVM setenv failed: ${it.message}") }
        synchronized(this) {
            if (running) { Log.i(TAG, "tailnet already running (port=${TailnetRoute.socksPort})"); return }
            running = true
            stateDir = dir
            loop = Thread({ run(dir, hostname, key, forceLogin) }, "hermes-tailnet").apply { isDaemon = true; start() }
        }
    }

    fun stop() {
        synchronized(this) { running = false }
        try { Tailnet.stop() } catch (e: Exception) { Log.w(TAG, "tailnet stop: ${e.message}") }
        TailnetRoute.socksPort = 0
        _status.value = TailnetStatus()
    }

    /** Re-enroll with a (new) key: full stop → wipe identity state → start forceLogin.
     *  The old node stays in the tailnet admin until pruned (owner's call there). */
    fun reEnroll(context: Context, hostname: String, key: String) {
        stop()
        startOnce(context, hostname, key, forceLogin = true)
    }

    /** The Go engine's ring buffer (its `tailscale status` equivalent) for Settings/logcat. */
    fun engineLogs(limit: Int = 40): String = try {
        Json_.parseToJsonElement(Tailnet.logsJSON()).jsonArray
            .map { it.jsonPrimitive.contentOrNullSafe() ?: "" }
            .takeLast(limit).joinToString("\n")
    } catch (e: Exception) { "(no engine logs: ${e.message})" }

    private fun run(dir: String, hostname: String, key: String, forceLogin: Boolean) {
        var backoff = 5_000L
        while (running) {
            try {
                val port = Tailnet.start(dir, hostname, key, forceLogin).toInt()
                TailnetRoute.socksPort = port
                Log.i(TAG, "tailnet engine up (socks=127.0.0.1:$port)")
                backoff = 5_000L
                // Reflect the truth until the node is stopped or the service restarts it.
                _status.value = read()
                while (running && _status.value.backendState != "stopped") {
                    Thread.sleep(if (_status.value.backendState == "Running") 15_000 else 5_000)
                    if (!running) break
                    _status.value = read()
                }
            } catch (e: InterruptedException) {
                running = false
            } catch (e: Exception) {
                TailnetRoute.socksPort = 0
                _status.value = TailnetStatus(backendState = "error", error = "start failed: ${e.message}")
                Log.w(TAG, "tailnet start failed: ${e.message}")
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { running = false }
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
        TailnetRoute.socksPort = 0
        Log.i(TAG, "tailnet engine thread done")
    }

    private fun read(): TailnetStatus = try {
        TailnetLogic.parse(Tailnet.statusJSON())
    } catch (e: Exception) {
        TailnetStatus(backendState = "error", error = "status: ${e.message}")
    }

    private fun kotlinx.serialization.json.JsonElement.contentOrNullSafe(): String? =
        runCatching { jsonPrimitive.content }.getOrNull()
}
