package dev.hermes.wearos.ui.md

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.hermes.wearos.domain.DialVolume
import dev.hermes.wearos.domain.RotaryVolume
import dev.hermes.wearos.net.TailnetRoute
import dev.hermes.wearos.net.Watchkit
import dev.hermes.wearos.ui.scaled
import kotlinx.coroutines.delay

/**
 * FULLSCREEN A/V PLAYER (media epic, owner 2026-11-02: "small inline like images, click play;
 * video fullscreen; hardware back returns to the session just in"). A Dialog keeps the chat
 * composition alive UNDERNEATH — back/BackRing dismisses and the transcript is exactly where it
 * was, no re-open, no scroll loss (verified against the watch's BackRing semantics: a dialog
 * swallows exactly one back).
 *
 * Playback rides media3/ExoPlayer (same stack the intro film uses). Gateway-host paths stream
 * from watchkit /media (ffmpeg pre-shrunk, bearer via request headers) THROUGH the tailnet SOCKS
 * shim — media3 builds its own HttpURLConnections, so the proxy comes from [TailnetRoute.SELECTOR]
 * explicitly; public URLs play direct with no bearer leak.
 */
@Composable
fun MediaPlayDialog(target: String, video: Boolean, label: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Re-key on watchkit config AND media-pref changes: a pushed token or a Settings ▸ Media
    // tweak while the sheet is open swaps the URL (and with it the stream), never a stale one.
    val cfg by Watchkit.config.collectAsState()
    val prefs by Watchkit.mediaPrefs.collectAsState()
    val url = remember(target, cfg, prefs) { Watchkit.mediaUrl(target) }
    val player = remember(url) {
        runCatching {
            val dsf = DefaultHttpDataSource.Factory()
                .setUserAgent("HermesWear/1.0")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
            if (Watchkit.mediaNeedsAuth(target))
                dsf.setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${Watchkit.currentToken()}"))
            // media3 1.4.1 has no Factory.setProxy (added upstream in 1.5) — but libcore's
            // HttpURLConnection asks java.net.ProxySelector.getDefault(), so the app installs
            // TailnetRoute.SELECTOR as the default for the player's lifetime (restored on
            // dispose): gateway hosts reach watchkit THROUGH the tunnel, exactly like OkHttp.
            DefaultRenderersFactory(context.applicationContext)
                .setEnableDecoderFallback(true)
                .let { ExoPlayer.Builder(context.applicationContext, it).build() }
                .apply {
                    setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
                    videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                    setMediaSource(
                        DefaultMediaSourceFactory(context.applicationContext)
                            .setDataSourceFactory(dsf)
                            .createMediaSource(MediaItem.fromUri(url)),
                    )
                    playWhenReady = true
                }
        }.getOrNull()
    }
    // No decoder (or no URL) is an immediate, silent return — never a stuck black sheet.
    LaunchedEffect(player) { if (player == null) onDismiss() }
    var errored by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val p = player ?: return@DisposableEffect onDispose { }
        val priorSelector = java.net.ProxySelector.getDefault()
        java.net.ProxySelector.setDefault(TailnetRoute.SELECTOR)
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { errored = true }
        }
        p.addListener(listener)
        p.prepare()
        onDispose {
            p.removeListener(listener)
            p.release()
            java.net.ProxySelector.setDefault(priorSelector)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // BEZEL VOLUME (owner 2026-11-02: "enable bezel scrolling for the volume up and down —
        // if we're playing music … we need to do that"): while this sheet owns the screen the
        // watch's volume keys (what the rotating bezel delivers on Galaxy Watch) steer the
        // PLAYER, not the system stream — 5 % detents on DialVolume's haptic grid, one tick per
        // step, and a readout that fades after a beat. Close the player and the bezel is the
        // system volume again.
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        var volPct by remember { mutableIntStateOf(-1) }
        var lastTick by remember { mutableLongStateOf(0L) }
        val volumeHandler = remember(player) { handler@{ steps: Int ->
            val p = player ?: return@handler false
            p.volume = RotaryVolume.step(p.volume, steps)
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastTick > 50L) {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                lastTick = now
            }
            volPct = DialVolume.pct(p.volume)
            true
        } }
        DisposableEffect(volumeHandler) {
            RotaryVolume.bind(volumeHandler)
            onDispose { RotaryVolume.unbind(volumeHandler) }
        }
        LaunchedEffect(volPct) {
            if (volPct >= 0) { delay(1_100); volPct = -1 }
        }
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { c ->
                    PinchPlayerView(c).apply {
                        useController = true
                        controllerShowTimeoutMs = 3_500
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { it.player = player },
            )
            if (volPct >= 0) Text(
                "🔊 $volPct%",
                modifier = Modifier.padding(top = 72.dp),
                style = MaterialTheme.typography.body1.scaled(),
                color = Color.White,
            )
            // Audio has no video surface: keep the glyph + name centered so the sheet is never
            // an empty black hole while the player hums along.
            if (!video) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                ) {
                    Text("🎵", style = MaterialTheme.typography.title2)
                    Text(
                        label.ifBlank { "audio" },
                        style = MaterialTheme.typography.caption2.scaled(),
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            if (errored) {
                Text(
                    "⚠ playback failed",
                    modifier = Modifier.padding(bottom = 96.dp),
                    style = MaterialTheme.typography.body2.scaled(),
                    color = Color.White,
                )
            }
        }
    }
}
