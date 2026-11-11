package dev.hermes.wearos.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.hermes.wearos.domain.MicLevel
import dev.hermes.wearos.voice.CodecMuxers
import dev.hermes.wearos.voice.GatewayTts
import dev.hermes.wearos.voice.MediaPlayerHolder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * ➕ NEW VOICE — zero-shot enrollment on the wrist (SPEC §19.5, owner ruling
 * 2026-11-10: "name it, record the line once, hear it back, retry or accept").
 * ONE take, not three: the wearer judges the clip they just heard, and the
 * gateway's encoder re-uses the best window when it builds the reference.
 *
 * Honesty rules carried from the voice-out doctrine:
 *  - the script is the GATEWAY's string (GatewayTts.prompt, served by the very
 *    /voice/cards snapshot the list already reads) — the transcript stored on the
 *    card is that same server-side constant, so the watch never claims words it
 *    didn't serve;
 *  - no mic permission / busy mic / short take: the screen STATES the refusal;
 *  - the lane's validation errors surface verbatim — a button never answers silence.
 */

private enum class Step { NAME, READY, REC, REVIEW, SAVE }

private const val TAG = "HermesWear"
private const val RATE = 16000          // the lane's canonical ref rate (PCM16 mono)
private const val MAX_SECONDS = 12      // the prompt reads in ~6–8 s; 12 caps a wander
private const val MIN_SECONDS = 3.0f    // pre-checked here, PROVEN again gateway-side

/** Full-bleed dialog: enrollment is its own screen (owner asked for exactly that). */
@Composable
fun VoiceEnrollDialog(onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colors.background)
                .padding(bottom = 4.dp),
        ) { VoiceEnroll(onClose) }
    }
}

@Composable
fun VoiceEnroll(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val ime = LocalSoftwareKeyboardController.current
    val recThread = remember { AtomicReference<Thread?>(null) }
    var step by remember { mutableStateOf(Step.NAME) }
    var name by remember { mutableStateOf("") }
    var pcm by remember { mutableStateOf<ByteArray?>(null) }
    var level by remember { mutableFloatStateOf(0f) }
    var secs by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf("") }

    fun start() {
        ime?.hide()
        error = ""
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            error = "no mic permission — allow it in System settings"
            return
        }
        step = Step.REC
        val t = Thread {
            val minBuf = try {
                AudioRecord.getMinBufferSize(
                    RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            } catch (e: Exception) { 0 }
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, 2 * RATE))     // ≥1 s buffer — no gaps on a busy UI
            } catch (e: Exception) { null }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { rec?.release() }
                error = "mic busy (a talk in flight?) — try again"
                step = Step.READY
            } else {
                val out = ByteArrayOutputStream(64 * 1024)
                val buf = ShortArray(1600)       // 100 ms chunks
                rec.startRecording()
                val t0 = System.nanoTime()
                while (!Thread.currentThread().isInterrupted &&
                    (System.nanoTime() - t0) / 1_000_000_000L < MAX_SECONDS) {
                    val n = try { rec.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
                    if (n > 0) {
                        for (i in 0 until n) {
                            out.write(buf[i].toInt() and 0xFF)
                            out.write((buf[i].toInt() shr 8) and 0xFF)
                        }
                        level = MicLevel.fromShorts(buf, n)
                        secs = (((System.nanoTime() - t0) / 1_000_000L)) / 1000f
                    } else if (n != 0) break
                }
                runCatching { rec.stop() }
                rec.release()
                level = 0f
                if (Thread.currentThread().isInterrupted && out.size() < 3200) {
                    error = "too short — held the line?"; step = Step.READY
                } else {
                    pcm = out.toByteArray()
                    step = Step.REVIEW
                }
            }
        }
        recThread.set(t.also { it.isDaemon = true; it.start() })
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (step) {
            Step.NAME -> {
                Text("name this voice", style = MaterialTheme.typography.body2)
                // THE standard keyboard path of this app: VoiceOrTypeField is the
                // widget sessions/chat use and whose IME demonstrably rises on the
                // owner's watch (batch-6 #27 fixed its ladder). A hand-rolled box
                // with a hand-run ladder got focus but never the IME window here —
                // one input widget per app, one keyboard behavior.
                VoiceOrTypeField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    placeholder = "voice name",
                    onSend = { step = Step.READY },
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    showKeyboardHint = false,
                )
                Button(onClick = { ime?.hide(); step = Step.READY },
                    modifier = Modifier.size(width = 140.dp, height = 36.dp)) {
                    Text("next", style = MaterialTheme.typography.button)
                }
            }
            Step.READY -> {
                // Freeform law (owner 2026-11-11): NO script on the watch — talk
                // 3-12 s about anything. Pace comes from the syllable envelope
                // (rate_source 'syllabic-est' on the card), so a fast take
                // legitimately measures fast without anyone reading a page.
                Text("talk 3–$MAX_SECONDS s", style = MaterialTheme.typography.body2)
                Text("say anything — this take becomes your voice",
                    style = MaterialTheme.typography.caption1)
                Text("you'll hear it back before it's kept",
                    style = MaterialTheme.typography.caption1)
                if (error.isNotEmpty()) Text(error, style = MaterialTheme.typography.caption1,
                    color = Color(0xFFE0836F))
                Button(onClick = { start() },
                    modifier = Modifier.size(width = 140.dp, height = 44.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFB0563F))) {
                    Text("● record", style = MaterialTheme.typography.button)
                }
            }
            Step.REC -> {
                Text("recording — talk freely", style = MaterialTheme.typography.body2)
                // Live meter, not a spinner (owner doctrine: prove the mic is hearing).
                Box(Modifier.fillMaxWidth().size(8.dp)
                    .background(MaterialTheme.colors.surface, RoundedCornerShape(4.dp))) {
                    Box(Modifier.fillMaxWidth(level.coerceIn(0.02f, 1f)).size(8.dp)
                        .background(Color(0xFFE0836F), RoundedCornerShape(4.dp)))
                }
                Text("%.1f s of %d".format(secs, MAX_SECONDS),
                    style = MaterialTheme.typography.body2)
                Button(onClick = { recThread.get()?.interrupt() },
                    modifier = Modifier.size(width = 120.dp, height = 36.dp)) {
                    Text("■ stop", style = MaterialTheme.typography.button)
                }
            }
            Step.REVIEW -> {
                val take = pcm ?: ByteArray(0)
                val dur = take.size.toDouble() / (RATE * 2)
                Text("take: %.1f s".format(dur), style = MaterialTheme.typography.body2)
                if (error.isNotEmpty()) Text(error, style = MaterialTheme.typography.caption1,
                    color = Color(0xFFE0836F))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = {
                        MediaPlayerHolder.playBytes(ctx, CodecMuxers.wav(take), "audio/wav")
                    }, modifier = Modifier.size(width = 64.dp, height = 40.dp)) { Text("▶") }
                    Button(onClick = { start() },
                        modifier = Modifier.size(width = 64.dp, height = 40.dp)) { Text("↻") }
                    Button(
                        onClick = {
                            if (dur < MIN_SECONDS) error = "too short — talk at least 3 s"
                            else {
                                error = ""
                                step = Step.SAVE
                                Thread {
                                    val err = GatewayTts.enroll(
                                        name.ifBlank { "watch voice" }, CodecMuxers.wav(take))
                                    if (err != null) {
                                        Log.w(TAG, "enrollment refused: $err")
                                        error = err; step = Step.REVIEW
                                    } else onClose()
                                }.start()
                            }
                        },
                        modifier = Modifier.size(width = 64.dp, height = 40.dp),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF4A7A5C)),
                    ) { Text("✓") }
                }
                Button(onClick = onClose,
                    modifier = Modifier.size(width = 90.dp, height = 28.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.4f))) {
                    Text("cancel", style = MaterialTheme.typography.button)
                }
            }
            Step.SAVE -> Text("cloning on the gateway…",
                style = MaterialTheme.typography.body2)
        }
    }
}
