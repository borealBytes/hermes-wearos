package dev.hermes.wearos.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * THE STT SEAM (batch-4 #8). MainActivity's listening loop only ever sees this shape —
 * `start(scope): Flow<SttEvent>` + `stop()` — so the provider picker (Settings ▸ Voice) can
 * swap engines without the mic flow knowing which one it got. Events stay Vosk's sealed class
 * (rename would have churned every call site for zero meaning).
 *
 * stop() contract (both engines honour it): flush/close so the collector sees a FINAL for what
 * was heard, then the flow COMPLETES — MainActivity's endListening depends on that to clear the
 * listening overlay instead of hanging on an open collection.
 */
typealias SttEvent = VoskEvent

interface SttSource {
    fun start(scope: CoroutineScope): Flow<SttEvent>
    fun stop()
}

/**
 * SYSTEM recognizer (batch-4 #8): android.speech.SpeechRecognizer — Google on Pixel/OnePlus,
 * Bixby/Weamp on Galaxy Watch, whatever the vendor ships elsewhere. UNLIKE Vosk this is
 * networked speech recognition on every stack we ship against, which is why the picker labels
 * it OFF-DEVICE (domain/SttProviders.subtitle), and why LOCAL stays the default.
 *
 * Engine quirks encoded here:
 *  • must be created/used on the MAIN thread (callbackFlow inherits the collector's Main scope);
 *  • silence ends with ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT — that is NOT an error worth a
 *    toast, it just closes the session (Vosk's silence behaviour, mirrored);
 *  • stop() means stopListening(): the engine flushes its FINAL (onResults) and we close THEN,
 *    never on our own timer — killing the recognizer early truncates the last word.
 */
class SystemStt(private val context: Context) : SttSource {

    companion object {
        /**
         * Package that ANSWERS recognition (the vendor auto-detect input for the picker).
         * The activity action alone finds NOTHING on real watches (measured SM-L705U — only
         * the service-interface action is declared); try all three keys the framework itself
         * uses, first hit wins. Manifest <queries> makes them visible on Android 11+.
         */
        private val RECOGNIZER_ACTIONS = listOf(
            "android.speech.RecognitionService",            // what vendors actually declare
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH,       // activity-side action
            "android.speech.SpeechRecognition",             // isRecognitionAvailable's key
        )

        fun recognizerPackage(context: Context): String? {
            for (action in RECOGNIZER_ACTIONS) {
                val pkg = runCatching {
                    context.packageManager.queryIntentServices(Intent(action), 0)
                        .firstOrNull()?.serviceInfo?.packageName
                }.getOrNull()
                if (pkg != null) return pkg
            }
            return null
        }

        fun isAvailable(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context)
    }

    @Volatile private var stopping = false
    private var recognizer: SpeechRecognizer? = null

    override fun start(scope: CoroutineScope): Flow<SttEvent> = callbackFlow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            trySend(VoskEvent.Error("no-permission"))
            close()
            return@callbackFlow
        }
        stopping = false
        val rec = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = rec
        val best = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                // The engine's own level readout drives the #13 "hearing you" meter.
                dev.hermes.wearos.voice.SttLevel.publish(dev.hermes.wearos.domain.MicLevel.fromDb(rmsdB))
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                val silent = error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                if (!silent) trySend(VoskEvent.Error("system stt error $error"))
                close() // NO_MATCH after stop() = nothing left to flush
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?.let { trySend(VoskEvent.Partial(it)) }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) trySend(VoskEvent.Final(text))
                close()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        rec.setRecognitionListener(best)
        // The Intent overload (SDK 34+): every extra is a plain RecognizerIntent key — the
        // engine builds its session from exactly these, same as the legacy Bundle form.
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        rec.startListening(intent)
        awaitClose { runCatching { rec.destroy() }; recognizer = null; SttLevel.reset() }
    }

    /** Flush-and-close: the engine delivers its FINAL via onResults; the flow ends there. */
    override fun stop() {
        stopping = true
        runCatching { recognizer?.stopListening() }
    }
}
