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
        refreshPrefs()   // best-effort: the voice list shows ✓ default / dial value when present
        return ok
    }

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

    /** Ear-test on the gateway voice — the sliders' preview and the picker's ▶ use this. */
    fun preview(agent: String) {
        enqueue(dev.hermes.wearos.domain.TtsConfig.PREVIEW_TEXT, agent,
            onDone = {}, onFail = { note = "gateway refused the preview" })
    }

    /** Parse a /voice/cards body — pure enough to unit-test the honesty rules. */
    internal fun applyStatusJson(raw: String): Boolean = try {
        val o = JSONObject(raw)
        engineReady = o.optBoolean("engine", false)
        val cs = o.optJSONObject("cards")
        cards = buildList {
            if (cs != null) for (k in cs.keys()) {
                val m = cs.optJSONObject(k)
                add(k to (m?.optString("label").orEmpty().ifEmpty { k }))
            }
        }
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
        MediaPlayerHolder.playBytes(ctx, Base64.getDecoder().decode(b64),
            parsed.optString("content_type").ifEmpty { "audio/wav" },
            onEnded = { done.countDown() }, gain = gain)
        if (!done.await(120, java.util.concurrent.TimeUnit.SECONDS))
            Log.w(TAG, "gateway voice playback cap hit (clip replaced?)")
    }

    /** POST and unwrap the lane's error shape ({ok:false,error:…} on any status). */
    private fun postChecked(body: JSONObject, base: String): JSONObject {
        val p = post(body, base)
        if (p.optString("error").isNotEmpty()) throw IOException(p.getString("error"))
        return p
    }

    private fun post(body: JSONObject, base: String): JSONObject {
        val req = Request.Builder().url("$base/voice/synthesize")
            .post(body.toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { resp ->
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
