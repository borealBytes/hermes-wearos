package dev.hermes.wearos.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import dev.hermes.wearos.net.Watchkit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GATEWAY STREAMING STT (batch-4 #9, owner: *"the watch streams the mic frames it ALREADY
 * captures … partials streamed back WHILE talking … auto-fallback to local"*).
 *
 * One session = one WebSocket to watchkit `/stt/stream` (bearer like every watchkit call):
 * the mic (same 16 kHz mono PCM16 the Vosk path uses) rides out as ~100 ms binary frames;
 * the engine answers JSONL — `{"partial"}` while talking (parakeet engine) and `{"final"}`
 * after the wearer's stop sends `{"end":true}`.
 *
 * AUTO-FALLBACK is the point of the design, not an error path: frames are BUFFERED from the
 * first read, and if the socket is not OPEN within [FALLBACK_GRACE_MS] — or dies, or answers
 * `{"error"}` before anything was heard — the WHOLE buffer replays into
 * [VoskStt.eventsFromFrames] locally, so nothing spoken is lost and the overlay shows real
 * text either way. A wifi hiccup mid-sentence means Vosk finishes the sentence on-device.
 *
 * Engine honesty: `/health` reports which engine will answer; Settings ▸ Voice shows it in
 * the picker subtitle (whisper is buffered — final only; partials belong to parakeet).
 */
class GatewayStt(
    private val context: Context,
    private val vosk: VoskStt,
) : SttSource {

    companion object {
        private const val TAG = "HermesWear"
        private const val SAMPLE_RATE = 16_000
        /** 100 ms of 16k mono PCM16. */
        private const val FRAME_BYTES = 3200
        /** Connect-or-fallback window. A LAN/tailnet WS opens in tens of ms; this catches dead/off. */
        private const val FALLBACK_GRACE_MS = 2_500L
        /** After {"end":true}, how long the engine may take to deliver its final. */
        private const val FINAL_WAIT_MS = 12_000L
        /** Replay ring: 20 s max (≈640 KB) — a longer press talks to whisper anyway. */
        private const val BUFFER_MAX_BYTES = 20 * SAMPLE_RATE * 2

        /** http→ws / https→wss, watchkit base minus its trailing slash. Pure + tested. */
        fun wsUrl(base: String): String {
            val b = base.trim().trimEnd('/')
            return when {
                b.startsWith("https://") -> "wss://" + b.removePrefix("https://")
                b.startsWith("http://") -> "ws://" + b.removePrefix("http://")
                else -> b // already ws(s):// — pass through
            } + "/stt/stream"
        }
    }

    @Volatile private var stopping = false
    private val client = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()

    override fun start(scope: CoroutineScope): Flow<SttEvent> = channelFlow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            send(VoskEvent.Error("no-permission")); return@channelFlow
        }
        stopping = false
        val cfg = Watchkit.config.value
        val buffer = ArrayDeque<ByteArray>()
        var bufferBytes = 0
        val live = Channel<ByteArray>(Channel.UNLIMITED)
        val open = AtomicBoolean(false)
        val dead = AtomicBoolean(false)
        val fallback = AtomicBoolean(false)
        val answered = AtomicBoolean(false) // any partial/final FROM THE GATEWAY
        val finalSeen = AtomicBoolean(false)
        val settled = CompletableDeferred<Unit>() // fallback replay finished (or never ran)
        val opened = CountDownLatch(1)

        fun engageFallback(reason: String) {
            if (!fallback.compareAndSet(false, true) || answered.get()) return
            Log.i(TAG, "gateway stt → local fallback ($reason)")
            launch {
                try {
                    vosk.eventsFromFrames(
                        frames = flow {
                            buffer.forEach { emit(it) } // replay EVERYTHING — nothing lost
                            while (true) {
                                val f = live.receiveCatching()
                                if (f.isClosed || f.isFailure) break
                                f.getOrNull()?.let { emit(it) }
                            }
                        },
                        stopped = { stopping },
                    ).collect { send(it) }
                } finally {
                    settled.complete(Unit)
                }
            }
        }

        val request = Request.Builder()
            .url(wsUrl(cfg.baseUrl))
            .header("Authorization", "Bearer ${cfg.token}")
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open.set(true); opened.countDown()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val ev = SttStreamProtocol.parse(text) ?: return
                if (ev is VoskEvent.Partial) answered.set(true)
                if (ev is VoskEvent.Final) { answered.set(true); finalSeen.set(true) }
                if (ev is VoskEvent.Error && !answered.get()) { engageFallback("engine: ${ev.message}"); return }
                trySend(ev)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { /* JSONL only */ }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                dead.set(true); opened.countDown()
                Log.i(TAG, "gateway stt ws failed: ${t.message}")
                engageFallback("ws: ${t.message ?: "failed"}")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                dead.set(true); opened.countDown()
            }
        }
        val ws = client.newWebSocket(request, listener)

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME_BYTES * 8),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release(); runCatching { ws.close(1000, "no-mic") }
            send(VoskEvent.Error("mic-unavailable")); return@channelFlow
        }

        launch(Dispatchers.Default) {
            // grace timer runs beside the capture loop; the latch wakes it on open/failure
            if (!opened.await(FALLBACK_GRACE_MS, TimeUnit.MILLISECONDS)) {
                engageFallback("no-open ${FALLBACK_GRACE_MS}ms")
            } else if (!open.get()) {
                engageFallback("ws failed")
            }
        }

        val frame = ByteArray(FRAME_BYTES)
        record.startRecording()
        Log.i(TAG, "gateway stt listening (ws=${wsUrl(cfg.baseUrl)})")
        try {
            while (!stopping) {
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) continue
                val chunk = frame.copyOf(n)
                buffer.addLast(chunk)
                bufferBytes += n
                while (bufferBytes > BUFFER_MAX_BYTES && buffer.size > 1) {
                    bufferBytes -= buffer.removeFirst().size
                }
                if (fallback.get()) live.send(chunk)
                else if (open.get()) ws.send(chunk.toByteString())
                // pre-open and not (yet) falling back: the buffer IS the send queue
            }
        } finally {
            try { record.stop() } catch (_: Exception) { }
            record.release()
        }
        live.close()

        if (!fallback.get() && open.get() && !dead.get()) {
            ws.send("""{"end":true}""")
            withTimeoutOrNull(FINAL_WAIT_MS) { while (!finalSeen.get() && !dead.get()) delay(80) }
            if (!finalSeen.get()) {
                Log.i(TAG, "gateway stt: no final within ${FINAL_WAIT_MS}ms")
                send(VoskEvent.Error("gateway stt: no answer (watchkit up? engine ready?)"))
            }
        }
        if (fallback.get()) withTimeoutOrNull(FINAL_WAIT_MS * 2) { settled.await() }
        runCatching { ws.close(1000, "bye") }
    }.flowOn(Dispatchers.IO)

    override fun stop() { stopping = true }
}

/**
 * watchkit `/stt/stream` JSONL protocol (pure — GatewayStt parses with it, tests pin it):
 * exactly one {final|partial|error} string field per line, final wins over partial, anything
 * else is NOT an event (a future engine must never crash the mic flow).
 */
internal object SttStreamProtocol {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun parse(line: String): VoskEvent? {
        val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(line) }
            .getOrNull() as? kotlinx.serialization.json.JsonObject ?: return null
        fun str(key: String): String? = runCatching {
            (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.getOrNull()
        return when {
            obj.containsKey("final") -> VoskEvent.Final(str("final").orEmpty())
            obj.containsKey("partial") ->
                str("partial")?.takeIf { it.isNotBlank() }?.let { VoskEvent.Partial(it) }
            obj.containsKey("error") -> VoskEvent.Error(str("error").orEmpty())
            else -> null
        }
    }
}
