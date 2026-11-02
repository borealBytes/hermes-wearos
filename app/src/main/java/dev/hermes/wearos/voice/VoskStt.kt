package dev.hermes.wearos.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed class VoskEvent {
    data class Partial(val text: String) : VoskEvent()
    data class Final(val text: String) : VoskEvent()
    data class Error(val message: String) : VoskEvent()
}

/**
 * Offline STT (M2): Vosk `vosk-model-small-en-us` in filesDir (pushed by scripts/get-vosk-model.sh),
 * AudioRecord 16 kHz PCM16 mono, pull-API (`acceptWaveForm` + result polling — the Maven AAR has no
 * callback/stream API). One session at a time (mutex); model + recognizer cached across sessions.
 */
class VoskStt(private val context: Context) : SttSource {

    companion object {
        private const val TAG = "HermesWear"
        const val MODEL_DIR = "vosk-model-small-en-us"
        private const val SAMPLE_RATE = 16_000
        private const val BUFFER_SHORTS = 3600

        fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR)

        /** small-en-us-0.15 keeps the acoustic model at am/final.mdl; larger models use root final.mdl. */
        fun isModelPresent(context: Context): Boolean {
            val d = modelDir(context)
            return File(d, "final.mdl").exists() || File(d, "am-final.mdl").exists() ||
                File(d, "am/final.mdl").exists()
        }

        private val json = Json { ignoreUnknownKeys = true }

        private fun field(jsonStr: String, key: String): String = try {
            json.parseToJsonElement(jsonStr).jsonObject[key]?.jsonPrimitive?.content ?: ""
        } catch (e: Exception) { "" }
    }

    private val mutex = Mutex()
    @Volatile private var stopping = false
    private var model: Model? = null
    private var recognizer: Recognizer? = null

    /**
     * Listen until [stop] (or cancellation). Emits Partial per frame, Final on endpoint detection
     * and/or on stop (flush), Error for model-missing / busy / no-permission / mic failures.
     * [scope] is used to warm the model load concurrently.
     */
    override fun start(scope: CoroutineScope): Flow<VoskEvent> = flow {
        // sync.Mutex.tryLock() *throws* when another coroutine holds it: treat as busy.
        val locked = try { mutex.tryLock(); true } catch (e: IllegalMonitorStateException) { false }
        if (!locked) { emit(VoskEvent.Error("busy")); return@flow }
        try {
            if (!isModelPresent(context)) { emit(VoskEvent.Error("model-missing")); return@flow }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
            ) { emit(VoskEvent.Error("no-permission")); return@flow }
            stopping = false
            val rec = ensureRecognizer() ?: run { emit(VoskEvent.Error("model-load-failed")); return@flow }

            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, BUFFER_SHORTS * 2),
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release(); emit(VoskEvent.Error("mic-unavailable")); return@flow
            }
            val buf = ShortArray(BUFFER_SHORTS)
            Log.i(TAG, "stt listening (model=${modelDir(context).name})")
            record.startRecording()
            try {
                while (currentCoroutineContext().isActive && !stopping) {
                    val n = record.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    SttLevel.publish(dev.hermes.wearos.domain.MicLevel.fromShorts(buf, n)) // #13 meter
                    if (rec.acceptWaveForm(buf, n)) {
                        val text = field(rec.result, "text")
                        if (text.isNotBlank()) emit(VoskEvent.Final(text))
                        rec.reset()
                    } else {
                        val p = field(rec.partialResult, "partial")
                        if (p.isNotBlank()) emit(VoskEvent.Partial(p))
                    }
                }
                // Released mid-utterance: flush what the endpointer hadn't settled.
                val flushed = field(rec.finalResult, "text")
                if (flushed.isNotBlank()) emit(VoskEvent.Final(flushed))
                rec.reset()
            } finally {
                try { record.stop() } catch (e: Exception) { }
                record.release()
                SttLevel.reset()
                Log.i(TAG, "stt stopped")
            }
        } finally {
            mutex.unlock()
        }
    }
        .onStart { scope.launch(Dispatchers.IO) { runCatching { ensureModel() } } }
        .flowOn(Dispatchers.IO)

    /** Release native session handles; model/recognizer are reused, released with the process. */
    override fun stop() { stopping = true }

    /**
     * BATCH-4 #9: the same recognizer pipeline fed from OUTSIDE — raw 16 kHz mono PCM16
     * frames instead of the private AudioRecord. This is what makes GatewayStt's
     * auto-fallback honest: the mic was already open for the WebSocket attempt, so the
     * buffered frames replay HERE (nothing spoken is lost) and Vosk behaves exactly as if
     * [start] had owned the capture. Mirrors [start]'s loop on purpose (endpoint finals +
     * stop flush + reset); busy-safe on the same mutex; honors [stopped] per frame.
     */
    internal fun eventsFromFrames(frames: Flow<ByteArray>, stopped: () -> Boolean): Flow<VoskEvent> = flow {
        val locked = try { mutex.tryLock(); true } catch (e: IllegalMonitorStateException) { false }
        if (!locked) { emit(VoskEvent.Error("busy")); return@flow }
        try {
            if (!isModelPresent(context)) { emit(VoskEvent.Error("model-missing")); return@flow }
            val rec = ensureRecognizer() ?: run { emit(VoskEvent.Error("model-load-failed")); return@flow }
            Log.i(TAG, "stt listening (gateway-fallback feed, model=${modelDir(context).name})")
            frames.collect { bytes ->
                if (stopped()) return@collect
                val n = bytes.size / 2
                if (n <= 0) return@collect
                val buf = ShortArray(n)
                java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().get(buf)
                if (rec.acceptWaveForm(buf, n)) {
                    val text = field(rec.result, "text")
                    if (text.isNotBlank()) emit(VoskEvent.Final(text))
                    rec.reset()
                } else {
                    val p = field(rec.partialResult, "partial")
                    if (p.isNotBlank()) emit(VoskEvent.Partial(p))
                }
            }
            val flushed = field(rec.finalResult, "text")
            if (flushed.isNotBlank()) emit(VoskEvent.Final(flushed))
            rec.reset()
            Log.i(TAG, "stt stopped (gateway-fallback feed)")
        } finally {
            mutex.unlock()
        }
    }
        .flowOn(Dispatchers.IO)

    private suspend fun ensureRecognizer(): Recognizer? = withContext(Dispatchers.IO) {
        val m = ensureModel() ?: return@withContext null
        recognizer ?: Recognizer(m, SAMPLE_RATE.toFloat()).also { recognizer = it }
    }

    @Synchronized private fun ensureModel(): Model? {
        model?.let { return it }
        return try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            Model(modelDir(context).absolutePath).also {
                model = it
                Log.i(TAG, "vosk model loaded")
            }
        } catch (e: Exception) {
            Log.e(TAG, "vosk model load failed", e)
            null
        }
    }
}
