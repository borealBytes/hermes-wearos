package dev.hermes.wearos.voice

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.hermes.wearos.net.TailnetRoute
import java.io.File
import java.net.URI
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Media3 ExoPlayer singleton: plays gateway audio clips (voice-out wavs, notify audio).
 *
 * TRANSPORT (SPEC §11.4, fixed 2026-11-10): media rides the SAME tailnet path as every other
 * call. Plain ExoPlayer sockets cannot reach tailnet-only endpoints (the lane binds tailnet-
 * only; ufw rejects LAN) — proven on the owner's Galaxy Watch Ultra. So play() first downloads
 * the body through the app's own OkHttp stack (TailnetRoute.SELECTOR routes it through the Go
 * SOCKS5 shim when the node is up, direct otherwise), writes it to the app cache, and hands
 * ExoPlayer a local file. Clips are small TTS wavs; no streaming/range support is needed.
 * Absolute http(s) URLs AND gateway-relative paths both work: a relative "/voice/..." resolves
 * against the API endpoint, so the voice surface (private lane, WHOIS-gated) is addressable
 * without baking its host into payloads.
 *
 * THREADING (fixed 2026-11-10, same day as the lane race): media3 enforces the creating
 * application thread (verifyApplicationThread) — the player is built and touched ONLY on the
 * main Looper; downloads run on workers. Callers needing "this clip FINISHED" (the gateway
 * voice queue, GatewayTts) pass onEnded — it fires once on natural end, on stop(), or on an
 * error; a clip replaced by a foreign play never fires it (the caller's own timeout guards
 * that rare crossing).
 */
object MediaPlayerHolder {
    private const val TAG = "HermesWear"
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var player: ExoPlayer? = null
    @Volatile private var onEnded: (() -> Unit)? = null
    private val http by lazy {
        OkHttpClient.Builder().proxySelector(TailnetRoute.SELECTOR).build()
    }

    private val playbackListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                val cb = onEnded; onEnded = null
                cb?.let { main.post(it) }
            }
        }
    }

    /** Download (tailnet-routed) then play. [onEnded] optional — see class doc. */
    fun play(context: Context, url: String, onEnded: (() -> Unit)? = null, gain: Float = 1f,
             speed: Float = 1f) {
        val app = context.applicationContext
        Thread {
            try {
                val abs = resolve(context, url) ?: error("cannot resolve media url: $url")
                val req = Request.Builder().url(abs).get().build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "media fetch $abs -> ${resp.code}")
                        onEnded?.let { main.post(it) }
                        return@use
                    }
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (bytes.isEmpty()) {
                        onEnded?.let { main.post(it) }; error("empty body from $abs")
                    }
                    playBytes(app, bytes, resp.header("Content-Type") ?: "audio/wav",
                        onEnded, gain, speed)
                    Log.i(TAG, "media play $abs (${bytes.size} bytes via ${if (TailnetRoute.socksPort > 0) "tailnet" else "direct"})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "media play failed: $url", e)
                onEnded?.let { main.post(it) }
            }
        }.start()
    }

    /**
     * Write decoded audio bytes to the app cache and play them on the main-thread player.
     * [onEnded] (optional) fires once when the clip finishes, is stopped, or could not start.
     */
    fun playBytes(context: Context, bytes: ByteArray, contentType: String,
                  onEnded: (() -> Unit)? = null, gain: Float = 1f, speed: Float = 1f) {
        val app = context.applicationContext
        val ext = when {
            contentType.contains("ogg") || contentType.contains("opus") -> ".ogg"
            contentType.contains("mp4") || contentType.contains("aac") ||
                contentType.contains("mpeg") || contentType.contains("mp3") -> ".m4a"
            else -> ".wav"
        }
        try {
            val dir = File(app.cacheDir, "media").apply { mkdirs() }
            // sweep clips older than 5 min — never the file a player may still be reading
            dir.listFiles()?.forEach {
                if (it.name.startsWith("media-") &&
                    System.currentTimeMillis() - it.lastModified() > 300_000) it.delete()
            }
            val tmp = File.createTempFile("media-", ext, dir)
            tmp.outputStream().use { it.write(bytes) }
            main.post {
                try {
                    val p = player ?: ExoPlayer.Builder(app).build().also {
                        it.addListener(playbackListener); player = it
                    }
                    this.onEnded = onEnded
                    // REPLY VOLUME (owner 2026-11-10): gateway clips honor the same per-agent
                    // reply-volume the system path uses — one slider shapes every voice.
                    p.volume = gain.coerceIn(0f, 1f)
                    // PACING AT PLAYBACK (owner ruling 2026-11-10): the gateway renders one
                    // natural 1x take; the ONE global dial rides the player, pitch-preserved
                    // (ExoPlayer's own time-stretcher), so moving the dial never re-renders.
                    if (kotlin.math.abs(speed - 1f) > 0.001f)
                        p.setPlaybackParameters(
                            androidx.media3.common.PlaybackParameters(speed.coerceIn(0.5f, 2.5f), 1f))
                    p.setMediaItem(MediaItem.fromUri(tmp.toURI().toString()))
                    p.prepare()
                    p.play()   // ExoPlayer defaults playWhenReady=FALSE — prepare alone
                               // fetches and buffers into silence (the ear-level bug of 2026-11-10:
                               // this holder was always prepare-only, and the tailnet byte-proof in
                               // logcat hid it until someone actually listened).
                } catch (e: Exception) {
                    Log.e(TAG, "player prepare failed", e)
                    this.onEnded = null
                    onEnded?.let { main.post(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "media write failed", e)
            onEnded?.let { main.post(it) }
        }
    }

    /** Absolute URLs pass through; relative ones resolve against the DEFAULT api endpoint. */
    private fun resolve(context: Context, url: String): String? {
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) return url
        if (!url.startsWith("/")) return null
        val base = dev.hermes.wearos.WearApp.Graph.endpoints.currentApi()
        if (base.isEmpty()) return null
        return URI(if (base.endsWith("/")) base.dropLast(1) else base).resolve(url)?.toString()
    }

    /** Cuts the live clip (its onEnded fires via the IDLE transition). */
    fun stop() { main.post { player?.stop() } }

    fun release() { main.post {
        try { player?.removeListener(playbackListener); player?.release() } catch (_: Exception) { }
        player = null; onEnded = null
    } }
}
