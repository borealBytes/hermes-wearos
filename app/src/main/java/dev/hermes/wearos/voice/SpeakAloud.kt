package dev.hermes.wearos.voice

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.os.SystemClock
import android.util.Log
import dev.hermes.wearos.data.AppSettings
import dev.hermes.wearos.domain.KaraokeCursor
import dev.hermes.wearos.domain.TtsConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speak-aloud over Android TTS (M2). Answers stream in as SSE deltas, so we sentence-chunk the
 * growing buffer (split on [.!?\n], min chunk 40 chars) and queue each finished sentence once;
 * `finish()` flushes the remainder of the settled answer. Volume is boosted to max on STREAM_MUSIC
 * while a queue is live and restored when it drains.
 *
 * Must be constructed/`init()`ed on the main thread (TTS posts its engine callback to that looper).
 */
class SpeakAloud(context: Context, private val settings: AppSettings, private val scope: CoroutineScope) {

    enum class State { None, Initializing, Ready, MissingEngine }

    companion object {
        private const val TAG = "HermesWear"
        private const val MIN_CHUNK = 40
        private const val SENTENCE_ENDS = ".!?\n"
    }

    private val appContext = context.applicationContext
    private val audio: AudioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    init { GatewayTts.bind(appContext) }

    private val _state = MutableStateFlow(State.None)
    val state: StateFlow<State> = _state

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    /** True while TTS utterances are in flight — the orb wears its STOP face on this (2026-10-29). */
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private var tts: TextToSpeech? = null
    private var utterSeq = 0
    private val pending = AtomicInteger(0)

    /**
     * Voice-out config (owner 2026-10-29): engine voice NAME ("" = engine default) + rate + the
     * ENGINE package ("" = system default; the watch carries Google AND Samsung engines) + a
     * fallback voice name tried when the preferred one errors mid-utterance.
     */
    @Volatile private var rate = TtsConfig.DEFAULT_RATE
    @Volatile private var voiceName = ""
    @Volatile private var fallbackName = ""
    @Volatile private var engineName = ""
    /** Voice-out SOURCE (issue #7): "system" = the OS TTS cascade; "gateway" = Wear Kit cloned
     *  voices via the private lane (GatewayTts). The system engine stays initialized either
     *  way: the gateway is a fallback-away, never a dead end — a lane that refuses mid-answer
     *  re-queues the rest of the answer on the local cascade, in order. */
    @Volatile private var source = "system"
    /** Engine package the LIVE tts was built with ("" = system default); null until init runs. */
    @Volatile private var activeEnginePkg: String? = null
    /** Voice name currently set on the engine — the cascade snaps back to preferred with it. */
    @Volatile private var appliedVoice = ""

    /** Reply-volume scale for THIS app's speech only (mute-lane double slider): a per-utterance
     *  KEY_PARAM_VOLUME multiplier over the boosted stream. 1 = exactly yesterday's behavior. */
    @Volatile private var volume = TtsConfig.DEFAULT_VOLUME

    /**
     * Engine voices for the Settings picker — empty until the TTS callback lands (state Ready).
     * `Voice.isPresent()/isDefault()` are @hide on the public SDK (compileSdk rejects them), so
     * availability filtering degrades to "keep everything" and the built-in flag rides on best
     * effort; `setVoice(Voice)` + name matching are public and carry the real work. `local`
     * classifies transport (tier where the ROM exposes it, else the `-local`/`-network` name
     * convention — domain/VoiceCatalog) so the picker can filter google-vs-samsung cascades.
     */
    data class VoiceOption(val name: String, val quality: Int, val builtin: Boolean, val local: Boolean)

    /** One installed TTS engine; pkg "" is the synthetic "system default" head entry. */
    data class EngineOption(val pkg: String, val label: String, val current: Boolean)

    fun availableVoices(): List<VoiceOption> {
        val t = tts ?: return emptyList()
        if (_state.value != State.Ready) return emptyList()
        return runCatching { t.voices.orEmpty() }.getOrNull()
            ?.map {
                VoiceOption(
                    it.name, it.quality, hiddenFlag(it, "isDefault"),
                    dev.hermes.wearos.domain.VoiceCatalog.classify(it.name, networkTier(it)) ==
                        dev.hermes.wearos.domain.VoiceCatalog.LOCAL,
                )
            }
            ?.sortedWith(compareByDescending<VoiceOption> { it.name == voiceName }
                .thenByDescending { it.quality }
                .thenBy { it.name })
            ?: emptyList()
    }

    fun availableEngines(): List<EngineOption> {
        val t = tts ?: return emptyList()
        if (_state.value != State.Ready) return emptyList()
        val installed = runCatching { t.engines.orEmpty() }.getOrNull() ?: emptyList()
        val head = EngineOption("", "system default", engineName.isEmpty())
        return listOf(head) + installed.map {
            EngineOption(
                it.name,
                dev.hermes.wearos.domain.VoiceCatalog.friendlyEngine(it.label ?: "", it.name),
                it.name == engineName,
            )
        }
    }

    /** Label of the engine the user chose (for the picker's collapsed row). */
    fun currentEngineLabel(): String =
        if (engineName.isEmpty()) "system default"
        else runCatching {
            tts?.engines.orEmpty()?.firstOrNull { it.name == engineName }?.let {
                dev.hermes.wearos.domain.VoiceCatalog.friendlyEngine(it.label ?: "", it.name)
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: engineName

    /** Voice.NETWORK_CONNECTION_TIER_*: NEVER=1 IF_NECESSARY=2 REQUIRED=3; 0 = unknown/absent. */
    private fun networkTier(v: Voice): Int = runCatching {
        Voice::class.java.getMethod("getNetworkConnectionTier").invoke(v) as? Int
    }.getOrNull() ?: 0

    private fun hiddenFlag(v: Voice, getter: String): Boolean = runCatching {
        Voice::class.java.getMethod(getter).invoke(v) as? Boolean ?: false
    }.getOrDefault(false)

    /** Pull persisted voice+rate+engine+fallback and apply to a live engine (cold start + Settings). */
    suspend fun loadVoiceConfig() {
        // Load through the AppSettings loaders so the SettingsScreen StateFlows see the truth too.
        settings.loadTtsRate()
        settings.loadTtsVoice()
        settings.loadTtsVolume()
        settings.loadTtsEngine()
        settings.loadTtsFallback()
        settings.loadTtsSource()
        source = settings.ttsSourceResolved()
        rate = settings.ttsRateValue()
        voiceName = settings.ttsVoiceValue()
        settings.loadTtsVolume()
        volume = settings.ttsVolumeForSync()
        fallbackName = settings.ttsFallbackValue()
        val wantEngine = settings.ttsEngineValue()
        if (tts == null) {
            engineName = wantEngine // cold boot: init() will build with it
        } else if (wantEngine != (activeEnginePkg ?: "")) {
            engineName = wantEngine
            restartEngine() // the live engine was built for another package — rebuild on the pick
            return
        }
        tts?.let { applyRate(it); applyVoiceTo(it, voiceName) }
    }

    /** Settings ▸ Voice-out: reply speed (clamped in AppSettings/TtsConfig). Applies mid-queue. */
    fun setSpeechRate(v: Float) {
        rate = TtsConfig.clampRate(v)
        tts?.let { applyRate(it) }
        scope.launch { settings.setTtsRate(rate) }
    }

    /** Settings ▸ Voice-out: engine voice by name ("" restores the engine's own default). */
    fun setVoice(name: String) {
        voiceName = name.trim()
        tts?.let { applyVoiceTo(it, voiceName) }
        scope.launch { settings.setTtsVoice(voiceName) }
    }

    /** Settings ▸ Voice-out: FALLBACK voice by name — second try when preferred errors. */
    fun setVoiceFallback(name: String) {
        fallbackName = name.trim()
        scope.launch { settings.setTtsFallback(fallbackName) }
    }

    /** Settings ▸ Voice-out: TTS engine package ("" = system default). A real switch rebuilds. */
    fun setEngine(pkg: String) {
        val p = pkg.trim()
        if (p == engineName) return
        engineName = p
        scope.launch { settings.setTtsEngine(p) }
        Log.i(TAG, "tts engine → '${p.ifEmpty { "system default" }}' (engine restart)")
        restartEngine()
    }

    /** Settings ▸ Voice-out: WHERE the voice comes from — "system" (OS cascade) or "gateway"
     *  (Wear Kit cloned voices over the private lane). Switching cuts any live gateway queue;
     *  the picker probes GET /voice/cards before offering gateway (honesty: no engine = no
     *  silent dead-end, the note says why). */
    fun setVoiceSource(v: String) {
        val s = if (v.trim().lowercase() == "gateway") "gateway" else "system"
        if (s == source) return
        source = s
        if (s == "system") GatewayTts.clear()   // leaving gateway: cut what it was speaking
        scope.launch { settings.setTtsSource(s) }
        Log.i(TAG, "voice-out source → $s")
    }

    fun voiceSource(): String = source

    /** Tear the live engine down and re-init on the preferred package. Always lands on main:
     *  TextToSpeech binds its init callback to the creating looper (Application scope is not main). */
    private fun restartEngine() {
        mainHandler.post {
            try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { }
            tts = null
            appliedVoice = ""
            utterances.clear()
            pending.set(0)
            _speaking.value = false
            _speakingOffset.value = null
            restoreVolume()
            init()
        }
    }

    /** Say a short sample with current settings — the picker's ear-test button. */
    fun preview() = speakFinal(TtsConfig.PREVIEW_TEXT)

    /** Current reply volume FOR THE ACTIVE AGENT (the dial's volume mode seeds from this). */
    fun replyVolume(): Float = settings.ttsVolumeForSync()

    /**
     * The mute-lane double slider / Voice-page slider commit: scale THIS app's replies for the
     * CURRENT AGENT (per-agent override, owner 2026-10-29 — the global slider stays as the
     * default for agents that never got one).
     */
    fun setReplyVolume(v: Float) {
        volume = TtsConfig.clampVolume(v)
        // BATCH-5 #19: dialing to 0 MID-utterance cuts the live queue — the rest of the answer
        // would be queued-silent anyway, and the orb drops its STOP face the moment you mute.
        if (TtsConfig.muted(volume)) stop()
        scope.launch { settings.setTtsVolumeFor(settings.profileSync(), volume) }
    }

    private fun applyRate(t: TextToSpeech) = runCatching { t.setSpeechRate(rate) }
        .onFailure { Log.w(TAG, "tts rate $rate failed: ${it.message}") }

    /**
     * Name → the engine's `voices` entry → `setVoice`. Matching by NAME (not the stored Voice
     * object) survives engine updates; a vanished voice falls back to the engine default voice
     * rather than throwing (voices come and go with engine updates — SPEC voice-out). "" =
     * engine's own default. [appliedVoice] tracks what the engine globally holds so the
     * fallback cascade can snap back to the preferred voice.
     */
    private fun applyVoiceTo(t: TextToSpeech, want: String) {
        val voices = runCatching { t.voices.orEmpty() }.getOrNull() ?: return
        val exact = voices.firstOrNull { it.name == want }
        val match = exact ?: voices.firstOrNull { hiddenFlag(it, "isDefault") }
        if (want.isNotEmpty() && exact == null) {
            Log.i(TAG, "tts voice '$want' gone from engine; default voice in use")
        }
        if (match != null) runCatching { t.setVoice(match) }
            .onFailure { Log.w(TAG, "tts setVoice('${match.name}') failed: ${it.message}") }
        appliedVoice = exact?.name ?: ""
        Log.i(TAG, "tts voice = ${match?.name ?: "(engine default)"}")
    }

    /** Saved STREAM_MUSIC volume while a queue is boosted; -1 = not boosted. */
    @Volatile private var savedVolume = -1

    /** Contiguous prefix of the in-flight stream already queued for speech (for finish() flush). */
    private val spokenPrefix = StringBuilder()
    private var scanFrom = 0

    /** utteranceId → (text, chain-step, stream-offset) so an engine error can retry the SAME
     *  sentence next, and the CHAT can center the line being spoken (karaoke follow, 2026-10-29).
     *  offset < 0 = standalone speech (notices/previews) — nothing to follow. */
    private data class Utt(val text: String, val step: Int, val offset: Int)
    private val utterances = java.util.concurrent.ConcurrentHashMap<String, Utt>()

    /** Char offset into the ACTIVE chat's live stream of the sentence currently being spoken —
     *  the chat screen anchors it ~⅓ down while voice is on (null = not speaking standalone/none). */
    private val _speakingOffset = MutableStateFlow<Int?>(null)
    val speakingOffset: StateFlow<Int?> = _speakingOffset

    /** Preferred → fallback (blank-safe, deduped) — domain/VoiceCatalog owns the rule. */
    private fun voiceChain(): List<String> =
        dev.hermes.wearos.domain.VoiceCatalog.chain(voiceName, fallbackName)

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            // Karaoke: the moment a sentence LEAVES the queue, tell the chat where it is — and
            // start the IN-UTTERANCE cursor (owner day-two fix: un-punctuated replies like
            // numbered lists ride as ONE utterance, so onStart alone pins line 1 for a minute).
            val rec = utteranceId?.let { utterances[it] } ?: return
            if (rec.offset < 0) return
            _speakingOffset.value = rec.offset
            startCursor(utteranceId!!, rec)
        }
        // (No onProgress override: absent from the compileSdk's UtteranceProgressListener, and
        //  the calibrated ticker below is engine-independent anyway.)
        override fun onDone(utteranceId: String?) {
            // Self-calibration: this utterance just told us the voice's REAL seconds-per-char —
            // the next estimate rides that (rate setting, voice, and pauses all converge in).
            val started = utteranceId?.let { uttStartMs.remove(it) }
            val rec = utteranceId?.let { utterances[it] }
            if (started != null && rec != null) {
                measuredCps = KaraokeCursor.calibrate(measuredCps, rec.text.length, SystemClock.elapsedRealtime() - started)
            }
            stopCursor()
            utterances.remove(utteranceId); utteranceSettled()
        }
        @Deprecated("kept for API 30") override fun onError(utteranceId: String?) = fail(utteranceId)
        override fun onError(utteranceId: String?, errorCode: Int) = fail(utteranceId)
    }

    // ── KARAOKE CURSOR plumbing (domain/KaraokeCursor has the rules + the why) ────────────────
    private var cursorJob: kotlinx.coroutines.Job? = null
    private val uttStartMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var measuredCps: Float? = null

    private fun startCursor(id: String, rec: Utt) {
        stopCursor()
        uttStartMs[id] = SystemClock.elapsedRealtime()
        cursorJob = scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(KaraokeCursor.TICK_MS)
                val started = uttStartMs[id] ?: break
                val est = KaraokeCursor.estimateOffset(
                    rec.text.length,
                    SystemClock.elapsedRealtime() - started,
                    measuredCps ?: KaraokeCursor.SEED_CHARS_PER_SEC,
                )
                _speakingOffset.value = rec.offset + est
            }
        }
    }

    private fun stopCursor() {
        cursorJob?.cancel()
        cursorJob = null
    }

    /**
     * The fallback cascade (owner: *"choose network first … then a local fallback"*): a failed
     * utterance is requeued verbatim on the next chain voice — nothing of it was audible, and
     * QUEUE_ADD keeps stream order — until the chain runs out. Queue-drain snaps the engine's
     * global voice back to preferred.
     */
    private fun fail(id: String?) {
        val rec = id?.let { utterances.remove(it) }
        val t = tts
        var retried = false
        if (rec != null && t != null) {
            val chain = voiceChain()
            if (rec.step + 1 < chain.size) {
                Log.i(TAG, "tts voice '${chain[rec.step]}' errored → retry on '${chain[rec.step + 1]}'")
                speakOne(rec.text, queueAdd = true, step = rec.step + 1, offset = rec.offset)
                retried = true
            }
        }
        if (!retried && rec != null) Log.w(TAG, "tts utterance failed, voice chain exhausted (id=$id)")
        utteranceSettled()
    }

    fun init() {
        _state.value = State.Initializing
        val pkg = engineName.ifEmpty { null }
        activeEnginePkg = engineName
        tts = TextToSpeech(appContext, { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts
                if (t != null) {
                    t.setOnUtteranceProgressListener(progress)
                    val avail = try { t.isLanguageAvailable(Locale.US) } catch (e: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
                    if (avail >= TextToSpeech.LANG_AVAILABLE) t.language = Locale.US
                    applyRate(t)
                    applyVoiceTo(t, voiceName)
                    _state.value = State.Ready
                } else _state.value = State.MissingEngine
            } else {
                _state.value = State.MissingEngine
            }
            Log.i(TAG, "tts init status=$status pkg=${pkg ?: "system"} state=${_state.value}")
        }, pkg)
    }

    /** Load the persisted master-mute + voice/rate config (call from a coroutine). */
    suspend fun loadEnabled() { _enabled.value = settings.voiceOutEnabled() }

    fun setVoiceEnabled(v: Boolean) {
        _enabled.value = v
        if (!v) stop()
        scope.launch { settings.setVoiceOut(v) }
    }

    /**
     * Feed the growing streaming answer (ChatUiState.live). Speaks every completed sentence
     * (>= MIN_CHUNK chars) that hasn't been spoken yet. A shorter-than-consumed buffer means a
     * new run started, so offsets reset.
     *
     * BATCH-5 #19: at 0 % reply volume every sentence is CONSUMED but never QUEUED — mute means
     * silent AND still (no `_speaking`, so the orb keeps its face and the karaoke cursor sleeps).
     */
    fun feed(textSoFar: String) {
        if (!_enabled.value) return
        // gateway source streams through the lane — it needs no system-TTS Ready handshake
        if (source != "gateway" && _state.value != State.Ready) return
        val mute = dev.hermes.wearos.domain.TtsConfig.muted(settings.ttsVolumeForSync())
        if (textSoFar.length < scanFrom) resetTracking()
        var segStart = scanFrom
        var i = scanFrom
        while (i < textSoFar.length) {
            if (textSoFar[i] in SENTENCE_ENDS && i + 1 - segStart >= MIN_CHUNK) {
                if (!mute) speak(textSoFar.substring(segStart, i + 1), queueAdd = true, offset = segStart)
                consumeTo(textSoFar, i + 1)
                segStart = scanFrom
            }
            i++
        }
        // Whitespace between sentences: keep the consumed prefix contiguous, never spoken.
        while (scanFrom < textSoFar.length && textSoFar[scanFrom] == ' ') consumeTo(textSoFar, scanFrom + 1)
    }

    /** Advance the consumed prefix to [to]; prefix always equals stream[0..scanFrom). */
    private fun consumeTo(stream: String, to: Int) {
        spokenPrefix.append(stream, scanFrom, to)
        scanFrom = to
    }

    /** Final settled text arrived: flush whatever the stream never reached. */
    fun finish(fullText: String) {
        if (!_enabled.value || _state.value != State.Ready || fullText.isBlank()) { resetTracking(); return }
        if (dev.hermes.wearos.domain.TtsConfig.muted(settings.ttsVolumeForSync())) { resetTracking(); return }
        val prefix = spokenPrefix.toString()
        if (fullText.startsWith(prefix)) {
            val rest = fullText.drop(prefix.length).trim()
            if (rest.isNotEmpty()) speak(rest, queueAdd = prefix.isNotEmpty(), offset = prefix.length)
        } else {
            // Terminal output diverged from the stream (rare): stop and read the settled answer.
            stop()
            speak(fullText, queueAdd = false, offset = 0)
        }
        resetTracking()
    }

    /** Speak a standalone text (dev `speak` broadcast, notices). Mute is mute: silent. */
    fun speakFinal(text: String) {
        if (!_enabled.value || _state.value != State.Ready) return
        if (dev.hermes.wearos.domain.TtsConfig.muted(settings.ttsVolumeForSync())) return
        stop()
        speak(text, queueAdd = false, offset = -1)
    }

    fun stop() {
        resetTracking()
        stopCursor()
        utterances.clear()
        _speaking.value = false
        _speakingOffset.value = null
        try { tts?.stop() } catch (e: Exception) { /* engine gone */ }
        GatewayTts.clear()   // orb-STOP covers the gateway queue too (SPEC §12.11 mute heritage)
        pending.set(0)
        restoreVolume()
    }

    fun shutdown() {
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { }
        tts = null
        stopCursor()
        utterances.clear()
        _speaking.value = false
        _speakingOffset.value = null
        restoreVolume()
    }

    private fun speak(text: String, queueAdd: Boolean, offset: Int) = speakOne(text, queueAdd, step = 0, offset = offset)

    /** Speak one utterance at [step] of the voice chain; registers it for fallback retry + karaoke follow. */
    private fun speakOne(text: String, queueAdd: Boolean, step: Int, offset: Int = -1) {
        // GATEWAY SOURCE (issue #7): cloned voices through the lane. The pending count is held
        // across the network+playback; if the lane refuses mid-answer the sentence re-queues
        // on the LOCAL cascade (QUEUE_ADD keeps order), and the settle fires exactly once.
        if (source == "gateway" && GatewayTts.available()) {
            volume = settings.ttsVolumeForSync()
            boostVolume()
            pending.incrementAndGet()
            _speaking.value = true
            GatewayTts.enqueue(text, settings.profileSync(),
                onDone = { utteranceSettled() },
                onFail = { speakLocal(text, queueAdd = true, step, offset) })
            return
        }
        speakLocal(text, queueAdd, step, offset)
    }

    private fun speakLocal(text: String, queueAdd: Boolean, step: Int, offset: Int = -1) {
        val t = tts ?: return
        if (_state.value != State.Ready) return
        // PER-UTTERANCE agent resolution (owner 2026-10-29): the agent that OWNS this reply is
        // active right now, so its override (or the global default) rides this exact sentence.
        volume = settings.ttsVolumeForSync()
        boostVolume()
        pending.incrementAndGet()
        _speaking.value = true
        val mode = if (queueAdd) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        // Chain step > 0 means the preferred voice failed: hop the ENGINE-global voice to the
        // fallback before queueing (the drain-snap in utteranceSettled puts it back).
        val want = voiceChain().getOrElse(step) { "" }
        if (want != appliedVoice) applyVoiceTo(t, want)
        // Reply volume rides PER UTTERANCE (KEY_PARAM_VOLUME) — app-scoped by construction, the
        // system stream stays untouched for everything else on the watch. BATCH-6 #24: `volume`
        // is a slider POSITION; the amplitude sent to the engine is the dB-sweep taper of it
        // (TtsConfig.gainFromPosition — 50 % position ≈ 3 % amplitude, the ear's law, not the
        // ruler's).
        val amp = TtsConfig.gainFromPosition(volume)
        val params = if (amp < 0.999f) android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, amp)
        } else null
        val id = "hermes-${++utterSeq}"
        utterances[id] = Utt(text, step, offset)
        t.speak(text, mode, params, id)
    }

    private fun utteranceSettled() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            _speaking.value = false
            _speakingOffset.value = null
            restoreVolume()
            // Chain drained: put the engine's global voice back on the preferred one.
            val t = tts
            if (t != null) {
                val head = voiceChain().firstOrNull().orEmpty()
                if (head != appliedVoice) applyVoiceTo(t, head)
            }
        }
    }

    private fun resetTracking() { spokenPrefix.setLength(0); scanFrom = 0 }

    private fun boostVolume() {
        if (savedVolume >= 0) return
        try {
            savedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        } catch (e: Exception) { savedVolume = -1 }
    }

    private fun restoreVolume() {
        val v = savedVolume
        if (v < 0) return
        savedVolume = -1
        try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) } catch (e: Exception) { }
    }
}
