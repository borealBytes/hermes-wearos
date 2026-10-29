package dev.hermes.wearos.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.hermes.wearos.R
import dev.hermes.wearos.domain.SplashIntro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "HermesWear"

/**
 * `app/src/main/assets/hermes-watch-intro.mp4` — the 480×480 launch film (rationale in
 * domain/SplashIntro). ASSET, NOT res/raw, and that is a bug fix rather than a style choice:
 * media3's RawResourceDataSource plays ~3 s of THIS file and then throws
 * `RawResourceDataSourceException: Resource not found.` when the mp4 reader reopens the URI at a
 * byte offset (caught on-device — the film painted, ran, died). AssetDataSource serves those
 * reopens, provided the APK stores the asset uncompressed, which it does for `.mp4`.
 */
private const val ASSET_URI = "file:///android_asset/hermes-watch-intro.mp4"

/**
 * THE LAUNCH FILM — [ASSET_URI] (480×480, 14.3 s, WITH its soundtrack) over full-bleed black, drawn
 * ON TOP of a chat that is composing itself underneath. Who may show it is [SplashIntro]'s call;
 * this composable owns the player, and it owns four rules:
 *
 *  • **any touch cuts it.** A tap anywhere stops the film and leaves the app ready, because the
 *    owner's own demo of a 14 s intro is unwatchable if you have to wait it out. The tap is
 *    CONSUMED, so the transcript underneath never receives the same finger as a message tap.
 *  • **`playing` is a question, and the poster is a curtain.** `playing == null` means the gate has
 *    not answered yet: the curtain is up (her self-portrait — the same image the system splash has
 *    been showing) and NO decoder is built. That is what stops a launch from leaking a frame of
 *    chat between the system splash and the film; `playing == false` is the caller taking it down.
 *  • **it never gates composition, and never outstays its welcome.** Everything the app needs to
 *    warm up keeps running under the overlay; if the decoder has not painted one frame inside
 *    [SplashIntro.FIRST_FRAME_BUDGET_MS] (no codec, decoder exhausted), the film is dropped and the
 *    chat shows instead. A black rectangle is never better than the app.
 *  • **the sound is borrowed, not taken.** media3 holds audio focus for us ([AudioAttributes] with
 *    `handleAudioFocus = true`) — which is politeness but also physics: it renders NOTHING until
 *    focus is granted, so after [SplashIntro.AUDIO_GRACE_MS] we give up the track rather than the
 *    picture. A call pauses the film, the screen sleeping pauses it, and a watch that plays audio to
 *    a dark wrist is a bug.
 *
 *  • **STATIC is the same overlay without the decoder** (owner 2026-10-29: *"I only want static or
 *    video"*): the still — the built-in portrait or the owner's own picked image — holds
 *    [SplashIntro.STATIC_HOLD_MS], cuts to any tap, and needs no decoder, no focus, no codec.
 *
 * The player lives and dies with this composition (released on dispose, which is after the
 * caller's fade-out, so the tail can never leave a decoder running).
 */
@Composable
fun IntroVideo(
    onDone: () -> Unit,
    playing: Boolean? = true,
    kind: SplashIntro.Choice = SplashIntro.Choice.VIDEO,
    // Owner-picked media from the watch (Settings ▸ Display ▸ Media): a content URI whose
    // persistable grant the caller has already validated; null/blank = the built-in art.
    sourceUri: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val done by rememberUpdatedState(onDone)
    var framePainted by remember { mutableStateOf(false) }
    val mutedByCut = remember { AtomicBoolean(false) }

    // Cold start on a Galaxy Watch is SLOW (32-bit userspace: the system splash held her face for
    // ~8 s on an SM-L705U before this overlay even composed), so "how long until the film painted"
    // is the number worth keeping in the log — it separates a slow decoder from a slow app, and
    // the budgets in domain/SplashIntro are tuned on it.
    val openedAt = remember { SystemClock.elapsedRealtime() }

    // `playing == null` means the gate has not answered yet: curtain only, no decoder. Building a
    // player here would spin one up on launches that were never going to show the film.
    val custom = sourceUri?.takeIf { it.isNotEmpty() }
    val player = remember(playing, kind, custom) {
        if (playing != true || kind == SplashIntro.Choice.STATIC) null
        else runCatching {
            custom?.let { Log.i(TAG, "intro: film source = custom media") }
            ExoPlayer.Builder(context.applicationContext).build().apply {
                setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
                // The film is square, the panel is round: crop to fill, so the intro reaches every
                // pixel of the bezel exactly like splash_spirit.webp does (ContentScale.Crop).
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                // A content URI straight into media3 — it resolves content schemes natively; a
                // video the watch cannot decode arrives at onPlayerError, which cuts to chat.
                setMediaItem(MediaItem.fromUri(custom ?: ASSET_URI))
                // DELIBERATELY not prepared here: prepare() starts the decoder, and a first frame
                // rendered before the listener below is attached is a first frame nobody heard
                // about — the overlay then self-cuts while the film is visibly playing (exactly
                // what it did on the emulator). prepare/play belong in DisposableEffect, after
                // addListener and after the TextureView has been handed over.
            }
        }.onFailure { Log.w(TAG, "intro: no player — straight to chat", it) }.getOrNull()
    }

    // A player we cannot build is the same outcome as a film that never paints: skip, don't stall.
    // (Only once the gate has ASKED for the film — `playing == null` is the curtain, not a failure.)
    LaunchedEffect(playing, player) { if (playing == true && player == null) done() }

    // CUT: mute on the finger, not on the dispose — the fade-out tail must not keep playing audio.
    val cut = {
        if (mutedByCut.compareAndSet(false, true)) player?.volume = 0f
        done()
    }

    DisposableEffect(player) {
        val p = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                framePainted = true
                Log.i(TAG, "intro: first frame ${SystemClock.elapsedRealtime() - openedAt}ms in")
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) done()
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "intro: playback failed — straight to chat", error)
                cut()
            }
        }
        p.addListener(listener)
        p.prepare()
        p.playWhenReady = true
        onDispose {
            p.removeListener(listener)
            p.volume = 0f
            p.release()
        }
    }

    // A STILL HAS NO END, so it gets one: hold for STATIC_HOLD_MS, then lift (a tap still cuts,
    // and the hold is deliberately shorter than any measured video first frame — switching to
    // static must make the launch feel faster).
    LaunchedEffect(playing, kind) {
        if (playing == true && kind == SplashIntro.Choice.STATIC) {
            delay(SplashIntro.STATIC_HOLD_MS)
            done()
        }
    }

    // POLITE FIRST, THEN UNBLOCKED: media3 will not render a single frame until it holds audio
    // focus, so a wrist that is mid-call (or an audio stack that is slow to answer — measured
    // ~1.6 s on the Wear emulator, which ate the whole first second of the film) would leave a
    // black rectangle where the intro should be. Ask politely for the grace window — that is what
    // makes a call pause the film instead of talking over it — and if no answer comes back, take
    // the PICTURE anyway and give up the sound: handleAudioFocus=false makes media3 drop the
    // request and start rendering. Sound is the nice-to-have; the launch story is the point.
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        delay(SplashIntro.AUDIO_GRACE_MS)
        if (!p.isPlaying) {
            Log.i(TAG, "intro: no audio focus in ${SplashIntro.AUDIO_GRACE_MS}ms — playing silent")
            p.setAudioAttributes(p.audioAttributes, /* handleAudioFocus = */ false)
        }
    }

    // WATCHDOG: no frame inside the budget ⇒ the film is not happening; show the chat.
    LaunchedEffect(player) {
        if (player == null) return@LaunchedEffect
        delay(SplashIntro.FIRST_FRAME_BUDGET_MS)
        // "Made no progress" is the failure worth reacting to; a missed callback is not. A playhead
        // that has moved means frames ARE on the wrist — leave the film alone and never cut it for
        // bookkeeping (that is how the first version of this overlay ate its own first second).
        if (!framePainted && player.currentPosition <= 0L) {
            Log.w(TAG, "intro: no frame in ${SplashIntro.FIRST_FRAME_BUDGET_MS}ms — dropping overlay")
            cut()
        }
    }

    // SCREEN OFF ⇒ PAUSE: the film is a visual, and audio without the picture is just noise.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val p = player ?: return@DisposableEffect onDispose { }
        val pausedForScreen = AtomicBoolean(false)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (p.isPlaying && pausedForScreen.compareAndSet(false, true)) p.pause()
                Lifecycle.Event.ON_START ->
                    if (pausedForScreen.compareAndSet(true, false)) p.playWhenReady = true
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // Tap-anywhere-to-skip. detectTapGestures consumes the down, so the chat under the
            // film cannot also read this finger (a cut that also opened a message would be worse
            // than the intro it replaced).
            .pointerInput(Unit) { detectTapGestures { cut() } },
    ) {
        if (kind == SplashIntro.Choice.STATIC) {
            // THE STILL: the owner's own picked image when it decoded, else the built-in
            // self-portrait. It IS the splash here — there is no hand-off to a film — and the
            // undecodable case silently keeps the built-in rather than showing black.
            val customImg = splashBitmap(custom)
            if (customImg != null) {
                Image(
                    bitmap = customImg, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.splash_spirit),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // POSTER / CURTAIN: the same self-portrait the system splash used. It covers two gaps that
        // would otherwise show the app mid-launch — the beat while the gate answers, and the
        // ~2 s the decoder needs on a watch (measured 1.9 s on an SM-L705U). The hand-off from the
        // OS splash into the film is face→face→film, never face→chat→film.
        if (kind == SplashIntro.Choice.VIDEO && !framePainted) {
            Image(
                painter = painterResource(R.drawable.splash_spirit),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // TEXTUREView, not SurfaceView: a SurfaceView punches a hole through the window for its
        // own layer, so "video hidden until it paints" leaves a black rectangle instead of the
        // poster underneath it — vendor compositors differ on how politely they do it, and a
        // splash whose first visible pixel is black is exactly the regression the poster exists to
        // prevent. A TextureView composites INSIDE the view tree, so the alpha below behaves. At
        // 480×480 the extra texture copy is nothing; correctness on the wrist is not.
        if (playing == true && kind == SplashIntro.Choice.VIDEO) {
            AndroidView(
                factory = { ctx -> TextureView(ctx).also { player?.setVideoTextureView(it) } },
                modifier = Modifier.fillMaxSize().alpha(if (framePainted) 1f else 0f),
            )
        }
    }
}

/**
 * Decode the owner's picked still for the splash, OFF the main thread, downsampled to
 * [SplashIntro.SPLASH_DECODE_EDGE_PX] longest edge (gallery originals are multi-megapixel; the
 * panel is 480). Null while decoding or on ANY failure — the caller falls back to the built-in
 * portrait, so a huge/corrupt/revoked pick degrades the launch instead of breaking it.
 */
@Composable
private fun splashBitmap(uri: String?): ImageBitmap? {
    val context = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bmp = null
        if (uri != null) {
            bmp = withContext(Dispatchers.IO) {
                val decoded = runCatching {
                    val parsed = Uri.parse(uri)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(parsed)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    val edge = maxOf(bounds.outWidth, bounds.outHeight)
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = if (edge > SplashIntro.SPLASH_DECODE_EDGE_PX) {
                            maxOf(1, Integer.highestOneBit(edge / SplashIntro.SPLASH_DECODE_EDGE_PX))
                        } else 1
                    }
                    context.contentResolver.openInputStream(parsed)?.use { ins ->
                        BitmapFactory.decodeStream(ins, null, opts)?.asImageBitmap()
                    }
                }.onFailure { Log.w(TAG, "intro: custom still undecodable — built-in portrait", it) }
                    .getOrNull()
                Log.i(
                    TAG, if (decoded != null) "intro: custom still decoded ${decoded.width}x${decoded.height}"
                    else "intro: custom still resolved null — built-in portrait",
                )
                decoded
            }
        }
    }
    return bmp
}

