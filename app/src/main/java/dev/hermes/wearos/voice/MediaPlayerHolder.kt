package dev.hermes.wearos.voice

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
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
 */
object MediaPlayerHolder {
    private const val TAG = "HermesWear"
    private var player: ExoPlayer? = null
    private val http by lazy {
        OkHttpClient.Builder().proxySelector(TailnetRoute.SELECTOR).build()
    }

    fun play(context: Context, url: String) {
        Thread {
            try {
                val abs = resolve(context, url) ?: error("cannot resolve media url: $url")
                val req = Request.Builder().url(abs).get().build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "media fetch $abs -> ${resp.code}")
                        return@use
                    }
                    val dir = File(context.cacheDir, "media").apply { mkdirs() }
                    val tmp = File.createTempFile("media-",
                        "-" + abs.substringAfterLast('/').take(24), dir)
                    resp.body?.byteStream()?.use { input -> tmp.outputStream().use(input::copyTo) }
                    if (tmp.length() == 0L) { tmp.delete(); error("empty body from $abs") }
                    val p = player ?: ExoPlayer.Builder(context.applicationContext).build().also { player = it }
                    p.setMediaItem(MediaItem.fromUri(tmp.toURI().toString()))
                    p.prepare()
                    Log.i(TAG, "media play $abs (${tmp.length()} bytes via ${if (TailnetRoute.socksPort > 0) "tailnet" else "direct"})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "media play failed: $url", e)
            }
        }.start()
    }

    /** Absolute URLs pass through; relative ones resolve against the DEFAULT api endpoint. */
    private fun resolve(context: Context, url: String): String? {
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) return url
        if (!url.startsWith("/")) return null
        val base = dev.hermes.wearos.WearApp.Graph.endpoints.currentApi()
        if (base.isEmpty()) return null
        return URI(if (base.endsWith("/")) base.dropLast(1) else base).resolve(url)?.toString()
    }

    fun stop() {
        player?.stop()
    }

    fun release() {
        player?.release()
        player = null
    }
}
