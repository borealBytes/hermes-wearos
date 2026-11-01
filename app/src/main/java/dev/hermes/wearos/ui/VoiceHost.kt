package dev.hermes.wearos.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import dev.hermes.wearos.WearApp
import dev.hermes.wearos.domain.AttentionPolicy
import dev.hermes.wearos.domain.ChatMessage
import dev.hermes.wearos.domain.ChatSession
import dev.hermes.wearos.voice.SpeakAloud

/**
 * Headless bridge between ChatUiState and SpeakAloud (keeps ChatSession UI/voice-agnostic):
 * progressive sentence speech from state.live, full-answer flush when a Hermes message settles,
 * reset on new chat.
 */
@Composable
fun VoiceHost(session: ChatSession, speak: SpeakAloud) {
    val state by session.state.collectAsState()
    val ttsState by speak.state.collectAsState()
    val lastSpokenId = remember { mutableLongStateOf(-1L) }
    val lastGen = remember { mutableLongStateOf(-1L) }
    val lastUserId = remember { mutableLongStateOf(-1L) }

    LaunchedEffect(Unit) {
        Log.i("HermesWear", "voice host up tts=$ttsState voiceOut=${speak.enabled.value}")
    }
    LaunchedEffect(ttsState) {
        Log.i("HermesWear", "tts state -> $ttsState")
    }
    // Chat switch / offline transcript load: swallow the loaded history (never re-speak it).
    LaunchedEffect(state.gen) {
        if (state.gen != lastGen.longValue) {
            lastGen.longValue = state.gen
            lastSpokenId.longValue = state.messages.lastOrNull { it.role == ChatMessage.Role.Hermes }?.id ?: -1L
            speak.stop()
        }
    }
    // Progressive: speak each completed sentence as the answer streams in.
    // BATCH-5 #20 (hardware, 2026-10-30): the newest USER message ENDS the previous answer's
    // speech — a new ask means the wearer moved on, and the voice must follow the ask (the old
    // queue kept reading an already-replaced reply across two fresh questions — watch logcat:
    // hermes-11 of the PREVIOUS answer still starting mid-hold — "talking from the orb" unasked).
    // BATCH-6 #31 THE ATTENTION POLICY (domain/AttentionPolicy, owner requirement): voice is
    // ONLY for the chat LIVE IN FRONT OF THE WEARER — app foreground AND this chat on the chat
    // screen. An answer from the watch face / another screen / another chat belongs to SYSTEM
    // NOTIFICATIONS (that half already reads the same brain in ChatSession) — never to the TTS
    // engine. These are snapshot-backed Graph signals: leaving recomposes this host, so the
    // gate re-evaluates the instant the wearer walks away — mid-answer included.
    val liveOnScreen = AttentionPolicy.shouldSpeak(
        WearApp.Graph.isForeground, WearApp.Graph.viewingChatId, state.activeChatId,
    )
    LaunchedEffect(state.live, state.busy, liveOnScreen) {
        val u = state.messages.lastOrNull { it.role == ChatMessage.Role.User }
        if (u != null && u.id != lastUserId.longValue) speak.stop() // new ask cuts old speech
        if (!liveOnScreen) {
            // Walking away cuts the queue (owner: the ONLY time it should talk is with the
            // session live on screen). The skipped material is NOT replayed on return —
            // the wearer reads it; the notification already carried the landing.
            speak.stop()
            return@LaunchedEffect
        }
        if (state.busy && state.live.isNotEmpty()) speak.feed(state.live)
    }
    // Settled: flush the remainder exactly once per Hermes message.
    LaunchedEffect(state.messages) {
        val u = state.messages.lastOrNull { it.role == ChatMessage.Role.User }
        val uid = u?.id ?: -1L
        if (uid != lastUserId.longValue) {
            lastUserId.longValue = uid
            speak.stop()
        }
        if (state.messages.isEmpty()) {
            speak.stop(); lastSpokenId.longValue = -1L
            return@LaunchedEffect
        }
        val last = state.messages.lastOrNull { it.role == ChatMessage.Role.Hermes }
        if (last != null && last.id != lastSpokenId.longValue) {
            lastSpokenId.longValue = last.id
            // #31: an answer that settled off-screen is the NOTIFICATION's news, not speech's.
            if (liveOnScreen) speak.finish(last.text)
            else android.util.Log.i("HermesWear", "voice suppressed — answer settled off-screen")
        }
    }
}
