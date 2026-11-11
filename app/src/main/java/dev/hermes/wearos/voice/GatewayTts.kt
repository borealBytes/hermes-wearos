package dev.hermes.wearos.voice

import android.content.Context
import android.util.Log
import dev.hermes.wearos.domain.VoiceCatalog
import dev.hermes.wearos.net.TailnetRoute
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.IOException

/**
 * Gateway voice-out (issue #7, slices 1+5 client): the Wear Kit's cloned voices speak the
 * answer. Sentences queue to `POST /voice/synthesize` on the PRIVATE LANE (same host as the
 * API endpoint, port 42654 — VoiceCatalog.laneBase; WHOIS-gated, no bearer here, identity is
 * the socket), come back as 24 kHz WAV (or Opus when the gateway can), and play through the
 * SAME MediaPlayerHolder the notification clips ride (ExoPlayer, main-thread-owned).
 *
 * HONESTY (doctrine): an unbound/absent engine is a REFUSAL, not silence — speak() answers
 * false and the caller falls back to the system TTS; the Settings page says exactly why.
 * The queue is serial (sentence order survives); `clear()` is orb-STOP semantics (cut,
 * don't drain) — SpeakAloud.stop() zeroes its pending count, so dropped items leak nothing.
 *
 * The lane prefers compact audio: `accept_opus:true` in the request — the gateway encodes
 * 24 k mono Opus when it can and says so (`opus:true`); a 400 naming that field (older
 * gateway) is caught and replayed without it, never dropped mid-answer.
 */
object GatewayTts {
    private const val TAG = "HermesWear"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** SpeakAloud binds the app context at construction (same lifecycle, no Graph detour). */
    @Volatile private var appContext: Context? = null
    fun bind(context: Context) { appContext = context.applicationContext }

    /** The api endpoint's host on the lane port (SPEC §19); "" when no gateway is set. */
    fun laneBase(): String = VoiceCatalog.laneBase(
        dev.hermes.wearos.WearApp.Graph.endpoints.currentApi())

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().proxySelector(TailnetRoute.SELECTOR)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS).build()
    }

    // ── inventory (Settings page; refresh() writes it, the speak queue never reads it) ──────
    @Volatile var cards: List<Pair<String, String>> = emptyList()   // (card_id, label)
        private set
    @Volatile var engineReady = false
        private set
    @Volatile var note = "not probed yet"
        private set
    /** The prefs doc's canonical voice (voices.__default__) — the page's ✓ checkbox. */
    @Volatile var defaultCard = ""
        private set
    /** The ONE global speed dial as the gateway knows it (0 = not probed yet). */
    @Volatile var laneSpeed = 0f
        private set
    @Volatile private var prefsDoc = JSONObject()   // last GET /voice/prefs, for read-modify-write
    @Volatile private var prefsRev = 0

    /** True when the gateway path can currently take answers: probed, engine, ≥1 card. */
    fun available(): Boolean = engineReady && cards.isNotEmpty()

    /** Re-pull GET /voice/cards AND the prefs mirror. Returns false (truthful note) on failure. */
    fun refresh(): Boolean {
        val base = laneBase()
        if (base.isEmpty()) { note = "no gateway endpoint set"; return false }
        val ok = try {
            val req = Request.Builder().url("$base/voice/cards").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { note = "lane said ${resp.code}"; return false }
                applyStatusJson(resp.body?.string() ?: "{}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "gateway voice probe failed: ${e.message}")
            note = "lane unreachable (${e.javaClass.simpleName})"
            false
        }
        refreshPrefsOkBestEffort()
        return ok
    }

    /** The cards snapshot already carries rev/speed/voices; this keeps the older
     *  prefs-only path available (setAsDefault/setSpeed fall back to it before their
     *  first write) — harmless one-liner, kept separate for clarity. */
    private fun refreshPrefsOkBestEffort() { runCatching { refreshPrefs() } }

    /** GET /voice/prefs — the watch mirrors, never owns; edits go back via [setAsDefault]/[setSpeed]. */
    fun refreshPrefs(): Boolean {
        val base = laneBase()
        if (base.isEmpty()) return false
        return try {
            val req = Request.Builder().url("$base/voice/prefs").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                applyPrefsJson(resp.body?.string() ?: "{}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "voice prefs mirror failed: ${e.message}"); false
        }
    }

    internal fun applyPrefsJson(raw: String): Boolean = try {
        val o = JSONObject(raw)
        prefsDoc = o; prefsRev = o.optInt("rev", 0)
        defaultCard = o.optJSONObject("voices")?.optString("__default__").orEmpty()
        laneSpeed = o.optJSONObject("global")?.optDouble("speed", 0.0)?.toFloat() ?: 0f
        true
    } catch (e: Exception) { false }

    /**
     * Make [cardId] the canonical voice: every existing binding follows it (the doc's own
     * voices keys ride, so no agent is left on a stale card), rev+1 — higher-rev-wins merge
     * does the rest. The gateway's merged doc comes back and reseeds the mirror.
     */
    fun setAsDefault(cardId: String): Boolean {
        if (prefsRev == 0 && !refreshPrefs()) return false
        val voices = JSONObject()
        prefsDoc.optJSONObject("voices")?.keys()?.forEach { k -> voices.put(k, cardId) }
        voices.put("__default__", cardId)
        return putPrefs(voices = voices)
    }

    /** The ONE global dial, now editable from the wrist too (same doc, same law). */
    fun setSpeed(mult: Float): Boolean {
        if (prefsRev == 0 && !refreshPrefs()) return false
        val global = JSONObject(prefsDoc.optJSONObject("global")?.toString() ?: "{}")
        global.put("speed", String.format(java.util.Locale.US, "%.2f",
            mult.coerceIn(0.5f, 2.0f)).toDouble())
        return putPrefs(global = global)
    }

    private fun putPrefs(voices: JSONObject? = null, global: JSONObject? = null): Boolean {
        val base = laneBase()
        if (base.isEmpty()) return false
        return try {
            val body = JSONObject().put("rev", prefsRev + 1)
                .put("voices", voices ?: JSONObject(prefsDoc.optJSONObject("voices")?.toString() ?: "{}"))
                .put("global", global ?: JSONObject(prefsDoc.optJSONObject("global")?.toString() ?: "{}"))
                .put("modes", JSONObject(prefsDoc.optJSONObject("modes")?.toString() ?: "{}"))
            val req = Request.Builder().url("$base/voice/prefs")
                .put(body.toString().toRequestBody(JSON)).build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) { note = "prefs write said ${resp.code}"; return false }
                applyPrefsJson(text)   // the merged canonical doc (gateway bumped rev again)
                prefsDoc = JSONObject(text); prefsRev = JSONObject(text).optInt("rev", prefsRev + 1)
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "voice prefs write failed: ${e.message}")
            note = "prefs write failed (${e.javaClass.simpleName})"
            false
        }
    }

    /** Chunk mode as the gateway knows it (paragraph default; the doc is truth). */
    val chunkMode: String
        get() = prefsDoc.optJSONObject("global")?.optString("chunk", "paragraph")
            ?.ifEmpty { "paragraph" } ?: "paragraph"

    /** Write chunk mode into the canonical doc's global section (read-modify-write,
     *  rev'd by the gateway; offline edit replays via the same PUT as speed). */
    fun setChunk(mode: String): Boolean {
        val g = JSONObject(prefsDoc.optJSONObject("global")?.toString() ?: "{}")
            .put("chunk", mode)
        return putPrefs(global = g)
    }

    /** Ear-test on the gateway voice — the sliders' preview and the picker's ▶ use this. */
    fun preview(agent: String) {
        enqueue(dev.hermes.wearos.domain.TtsConfig.PREVIEW_TEXT, agent,
            onDone = {}, onFail = { note = "gateway refused the preview" })
    }

    /** Parse a /voice/cards body — pure enough to unit-test the honesty rules.
     *  The body is the FULL canonical snapshot (rev, speed, voices, cards), so one
     *  GET mirrors everything the page shows: list, ✓ default, dial value. */
    internal fun applyStatusJson(raw: String): Boolean = try {
        val o = JSONObject(raw)
        engineReady = o.optBoolean("engine", false)
        prefsRev = o.optInt("rev", prefsRev)
        laneSpeed = o.optDouble("speed", laneSpeed.toDouble()).toFloat()
        defaultCard = o.optJSONObject("voices")?.optString("__default__").orEmpty()
        val cs = o.optJSONObject("cards")
        val tmap = mutableMapOf<String, String>()
        cards = buildList {
            if (cs != null) for (k in cs.keys()) {
                val m = cs.optJSONObject(k)
                add(k to (m?.optString("label").orEmpty().ifEmpty { k }))
                if (m != null) tmap[k] = m.optString("transcript").orEmpty()
            }
        }
        transcripts = tmap
        // the enrollment script, single-sourced from the gateway: the watch renders
        // THIS so create_card's server-side transcript auto-fill stays true.
        if (o.has("prompt")) prompt = o.getString("prompt")
        note = when {
            !engineReady -> "engine not installed on the gateway"
            cards.isEmpty() -> "no voice cards yet — record one on the pane"
            else -> "${cards.size} voice${if (cards.size == 1) "" else "s"} ready"
        }
        true
    } catch (e: Exception) {
        note = "bad lane answer (${e.javaClass.simpleName})"
        false
    }

    /** Card id → its reference transcript (the ▶ audition line, served by the snapshot). */
    @Volatile var transcripts: Map<String, String> = emptyMap()
        private set

    /** The one line every enrollment must speak (kept in step with voiceout.SCRIPTED_PROMPT). */
    @Volatile var prompt: String =
        "Your cron digest is ready: three farm alerts, no incidents, sprayer window " +
        "opens Thursday at six ten."
        private set

    /** card_id of the most recent successful enrollment (post-accept audition). */
    @Volatile var lastCardId: String = ""
        private set

    /**
     * ➕ New voice: POST the recorded prompt wav to the lane. The gateway validates
     * (3–60 s, size, silence) and files the card with the scripted transcript — the
     * watch never claims words it didn't serve. Returns null on success, else the
     * human-readable refusal (a button never answers silence).
     */
    fun enroll(label: String, wav: ByteArray): String? {
        val base = laneBase()
        if (base.isEmpty()) return "no gateway endpoint set"
        val err = try {
            val body = JSONObject()
                .put("label", label.trim().ifEmpty { "watch voice" })
                .put("wav_b64", Base64.getEncoder().encodeToString(wav))
                .put("transcript", "")   // freeform take — gateway estimates pace from the envelope
            val req = Request.Builder().url("$base/voice/card_create")
                .post(body.toString().toRequestBody(JSON)).build()
            http.newCall(req).execute().use { resp ->
                val o = JSONObject(resp.body?.string().orEmpty().ifEmpty { "{}" })
                if (!resp.isSuccessful || o.optString("error").isNotEmpty())
                    o.optString("error").ifEmpty { "lane said ${resp.code}" }
                else { lastCardId = o.optString("card_id"); null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "voice enrollment failed: ${e.message}")
            "enrollment failed (${e.javaClass.simpleName})"
        }
        if (err == null) refresh()   // the list shows the new card, not a promise
        return err
    }

    /**
     * ▶ on a voice row: audition THAT card through the real pipeline — global dial speed,
     *  ref_rate-normalized, reply-volume gain. The gateway-side law (owner 2026-11-10):
     *  ONE warm TTS worker, FIFO queue in front of it, so the tap answers in seconds, not
     *  minutes — and the clip is exactly the speed/volume an answer would ride. The flat
     *  reference wav is only the honest fallback when the engine genuinely can't speak
     *  (it speaks at its own pace, so we don't volunteer it while the queue is merely busy).
     */
    fun previewCard(cardId: String, agent: String) {
        val base = laneBase()
        val ctx = appContext
        if (base.isEmpty() || ctx == null) return
        val gain = runCatching { dev.hermes.wearos.WearApp.Graph.settings.ttsVolumeForSync() }
            .getOrDefault(1f)
        val text = transcripts[cardId].orEmpty().ifEmpty {
            dev.hermes.wearos.domain.TtsConfig.PREVIEW_TEXT }
        try {
            val client = http.newBuilder().readTimeout(600, TimeUnit.SECONDS).build()
            val parsed = postChecked(
                JSONObject().put("text", text).put("agent", agent)
                    .put("card_id", cardId).put("words", false).put("accept_opus", true),
                base, client)
            val b64 = parsed.optString("audio_wav_b64")
                .ifEmpty { parsed.optString("audio_opus_b64") }
            if (b64.isEmpty()) error("no audio in answer")
            // PACING AT PLAYBACK: the clip is the natural 1x take; the dial (echoed in
            // the answer by the gateway) rides the player — pitch-preserved.
            MediaPlayerHolder.playBytes(ctx, Base64.getDecoder().decode(b64),
                parsed.optString("content_type").ifEmpty { "audio/wav" },
                gain = gain, speed = parsed.optDouble("speed", laneSpeed.toDouble())
                    .toFloat().takeIf { it > 0f } ?: 1f)
        } catch (e: Exception) {
            Log.i(TAG, "card audition via synth failed (${e.message}) — playing reference wav")
            MediaPlayerHolder.play(ctx, "$base/voice/card_wav?card_id=$cardId", gain = gain)
        }
    }

    // ── the speak queue ──────────────────────────────────────────────────────────────────────
    private class Item(val text: String, val agent: String, val gen: Long,
                       val onDone: () -> Unit, val onFail: () -> Unit)

    private val queue = ArrayBlockingQueue<Item>(64)
    @Volatile private var generation = 0L

    init {
        Executors.newSingleThreadExecutor { r -> Thread(r, "gateway-tts").apply { isDaemon = true } }
            .execute {
                while (true) {
                    val item = queue.take()
                    if (item.gen != generation) continue   // orb-STOPped while queued
                    try {
                        synthesizeAndPlay(item)
                    } catch (e: Exception) {
                        Log.w(TAG, "gateway voice failed mid-answer: ${e.message}")
                        runCatching { item.onFail() }      // local fallback, same order
                    } finally {
                        runCatching { item.onDone() }      // settle the pending count once
                    }
                }
            }
    }

    private fun synthesizeAndPlay(item: Item) {
        val ctx = appContext ?: throw IOException("gateway voice not bound")
        val base = laneBase()
        if (base.isEmpty()) throw IOException("no gateway endpoint set")
        var parsed = try {
            postChecked(JSONObject().put("text", item.text).put("agent", item.agent)
                .put("words", false).put("accept_opus", true), base)
        } catch (e: IOException) {
            // Possibly an older gateway rejecting the unknown accept_opus field: replay bare.
            if ("accept_opus" !in (e.message ?: "")) throw e
            postChecked(JSONObject().put("text", item.text).put("agent", item.agent)
                .put("words", false), base)
        }
        val b64 = parsed.optString("audio_wav_b64")
            .ifEmpty { parsed.optString("audio_opus_b64") }
        if (b64.isEmpty()) throw IOException("no audio in answer")
        // Serial answers need the clip to FINISH before the next sentence POSTs: block this
        // worker until playback ends / is stopped / dies. The cap guards the rare crossing
        // where a notification clip replaces ours without an ENDED callback (a ~30 s answer
        // plus generous slack; longer than any TTS sentence can run).
        val done = java.util.concurrent.CountDownLatch(1)
        val gain = runCatching { dev.hermes.wearos.WearApp.Graph.settings.ttsVolumeForSync() }
            .getOrDefault(1f)
        // PACING AT PLAYBACK (same law as the audition): natural clip, dial on the player.
        MediaPlayerHolder.playBytes(ctx, Base64.getDecoder().decode(b64),
            parsed.optString("content_type").ifEmpty { "audio/wav" },
            onEnded = { done.countDown() }, gain = gain,
            speed = parsed.optDouble("speed", laneSpeed.toDouble())
                .toFloat().takeIf { it > 0f } ?: 1f)
        if (!done.await(120, java.util.concurrent.TimeUnit.SECONDS))
            Log.w(TAG, "gateway voice playback cap hit (clip replaced?)")
    }

    /** POST and unwrap the lane's error shape ({ok:false,error:…} on any status). */
    private fun postChecked(body: JSONObject, base: String,
                            client: okhttp3.OkHttpClient = http): JSONObject {
        val p = post(body, base, client)
        if (p.optString("error").isNotEmpty()) throw IOException(p.getString("error"))
        return p
    }

    private fun post(body: JSONObject, base: String,
                     client: okhttp3.OkHttpClient = http): JSONObject {
        val req = Request.Builder().url("$base/voice/synthesize")
            .post(body.toString().toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            return JSONObject(text.ifEmpty { "{}" })   // error bodies ride {ok:false,error:…}
        }
    }

    /**
     * Queue one sentence. [onDone] fires when the item has played OR was dropped/failed
     * (exactly once) — SpeakAloud decrements its pending count there, keeping the orb STOP
     * face honest. [onFail] fires INSTEAD of silence when the lane could not speak, before
     * onDone — the caller re-queues the text on the local engine (QUEUE_ADD keeps order).
     */
    fun enqueue(text: String, agent: String, onDone: () -> Unit, onFail: () -> Unit) {
        if (!queue.offer(Item(text, agent, generation, onDone, onFail))) onFail()
    }

    /** Orb-STOP: cut the live clip, drop the queue. Dropped items settle WITHOUT onFail (the
     *  wearer chose silence, not the system voice). */
    fun clear() {
        generation++
        queue.clear()
        MediaPlayerHolder.stop()
    }
}
