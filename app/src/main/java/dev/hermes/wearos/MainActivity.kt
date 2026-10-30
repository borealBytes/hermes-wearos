package dev.hermes.wearos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import dev.hermes.wearos.ui.AgentPickerDialog
import dev.hermes.wearos.ui.ApprovalModeDialog
import dev.hermes.wearos.ui.ModelPickerDialog
import dev.hermes.wearos.ui.ChatScreen
import dev.hermes.wearos.ui.ChatsScreen
import dev.hermes.wearos.ui.LocalFontScale
import dev.hermes.wearos.ui.PromptActionSheet
import dev.hermes.wearos.ui.PromptEditorOverlay
import dev.hermes.wearos.ui.PttOverlay
import dev.hermes.wearos.ui.SessionActionSheet
import dev.hermes.wearos.ui.SessionDeleteSheet
import dev.hermes.wearos.ui.SessionsScreen
import dev.hermes.wearos.ui.SettingsScreen
import dev.hermes.wearos.ui.VoiceHost
import dev.hermes.wearos.ui.md.ZoomableMediaViewer
import dev.hermes.wearos.domain.BackRing
import dev.hermes.wearos.domain.BottomLayout
import dev.hermes.wearos.domain.ChatMessage
import dev.hermes.wearos.domain.DialSpin
import dev.hermes.wearos.domain.DialVolume
import dev.hermes.wearos.domain.FontScale
import dev.hermes.wearos.domain.SlashCommands
import dev.hermes.wearos.domain.Lane
import dev.hermes.wearos.domain.OrbDial
import dev.hermes.wearos.domain.RenderCase
import dev.hermes.wearos.domain.ProfileLogic
import dev.hermes.wearos.domain.PromptEdit
import dev.hermes.wearos.domain.Vad
import dev.hermes.wearos.domain.CommandCatalog
import dev.hermes.wearos.domain.CommandEntry
import dev.hermes.wearos.domain.CommandKind
import dev.hermes.wearos.domain.CommandUsage
import dev.hermes.wearos.domain.SplashIntro
import dev.hermes.wearos.ui.CommandsOverlay
import dev.hermes.wearos.ui.IntroVideo
import dev.hermes.wearos.ui.CommandOverlayState
import dev.hermes.wearos.ui.OrbDialOverlay
import dev.hermes.wearos.net.GatewaySessions
import dev.hermes.wearos.voice.SpeakAloud
import dev.hermes.wearos.voice.TalkOrb
import dev.hermes.wearos.voice.VoskEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** Watch-local navigation (SPEC §3/§4): chat ⇄ chats-list ⇄ settings ⇄ gateway-sessions. No nav library. */
enum class Screen { Chat, Chats, Settings, Sessions }

/** Deep-link targets (M5): notification tap (open_chat), tile tap (talk), adb parity. */
private data class DeepLink(val openChat: String? = null, val talk: Boolean = false, val nonce: Long = 0L)

class MainActivity : ComponentActivity() {
    private companion object { const val TAG = "HermesWear" }

    private var navNonce = 0L
    private val deepLink = MutableStateFlow<DeepLink?>(null)

    /** M5 U1: POST_NOTIFICATIONS ask on first launch (API 33+). Denied is fine: app still works. */
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Log.w(TAG, "notifs denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        parseDeepLink(intent)
        setContent {
            MaterialTheme {
                val session = WearApp.Graph.session
                val speak = WearApp.Graph.speak
                val settings = WearApp.Graph.settings
                val agents = WearApp.Graph.agents
                val state by session.state.collectAsState()
                val chats by session.chats().collectAsState()
                val activeChatId by session.activeChat.collectAsState()
                val ttsState by speak.state.collectAsState()
                val voiceOut by speak.enabled.collectAsState()
                // The orb's STOP face while the answer is being READ ALOUD (owner 2026-10-29).
                val speakingNow by speak.speaking.collectAsState()
                // Where the spoken sentence sits in the live stream — karaoke follow (SPEC §12.11).
                val speakingOffsetNow by speak.speakingOffset.collectAsState()
                // Mute-lane double slider anchor + picker mirror (owner 2026-10-29).
                val replyVol by settings.ttsVolumeFor.collectAsState()
                val markdownOn by settings.markdownEnabled.collectAsState()
                val profile by settings.profile.collectAsState()
                val agentList by agents.agents.collectAsState()
                val agentsRefreshing by agents.refreshing.collectAsState()
                val gatewaySessions by session.gatewaySessions.collectAsState()
                val archivedSessions by session.archivedSessions.collectAsState()
                val sessionsMeta by session.sessionsMeta.collectAsState()
                val devPtt by WearApp.Graph.devPtt.collectAsState()
                val devEditor by WearApp.Graph.devEditor.collectAsState()
                val gatewayLabel by WearApp.Graph.gatewayLabel.collectAsState()

                var screen by remember { mutableStateOf(Screen.Chat) }
                var composing by remember { mutableStateOf(false) }
                var draft by remember { mutableStateOf("") }
                var showAgents by remember { mutableStateOf(false) }
                var showModels by remember { mutableStateOf(false) }
                var approvalsOpen by remember { mutableStateOf(false) }
                var sessionFilter by remember { mutableStateOf(GatewaySessions.FILTER_ALL) }
                // 🗃 chip auto-exits when its last row was unarchived (an empty chip would strand
                // the user on an empty list they can see is empty).
                androidx.compose.runtime.LaunchedEffect(archivedSessions.isEmpty()) {
                    if (archivedSessions.isEmpty() && sessionFilter == GatewaySessions.FILTER_ARCHIVED)
                        sessionFilter = GatewaySessions.FILTER_ALL
                }
                // OWNER BATCH 2 (asks #5/#6): long-pressed prompt → sheet → full-screen editor.
                var promptSheet by remember { mutableStateOf<dev.hermes.wearos.domain.ChatMessage?>(null) }
                var editor by remember { mutableStateOf<EditorReq?>(null) }
                // SESSION LONG-PRESS (owner: "hold a session → fork or delete, double check"):
                // the row arrives here; the sheets render at scaffold level like PromptActionSheet.
                var manageSheet by remember { mutableStateOf<GatewaySessions.Info?>(null) }
                var confirmDelete by remember { mutableStateOf<GatewaySessions.Info?>(null) }

                // `-e open_editor "…"` (debug) opens the prompt editor prefilled — the system
                // keyboard proof for owner batch 2 over adb; cleared once consumed.
                LaunchedEffect(devEditor) {
                    devEditor?.let {
                        editor = EditorReq(text = it, original = "", hint = "type or swipe…")
                        WearApp.Graph.devEditor.value = null
                    }
                }

                // --- M2 voice: hold-to-talk + tap hands-free STT state (owner ask #4 / follow-up A) ---
                val context = LocalContext.current
                val uiScope = androidx.compose.runtime.rememberCoroutineScope()
                val listening = remember { mutableStateOf(false) }
                val handsFree = remember { mutableStateOf(false) }
                val vad = remember { mutableStateOf(Vad.start(0L)) }
                val partial = remember { mutableStateOf("") }
                // Readable-overlay support: finalized segments of THIS listening session (oldest
                // first) — PttOverlay stacks these under the live partial (SttFeed).
                val sttHistory = remember { androidx.compose.runtime.snapshots.SnapshotStateList<String>() }
                val sttStatus = remember { mutableStateOf("") }
                val sttJob = remember { mutableStateOf<Job?>(null) }

                val voiceSink = remember { mutableStateOf<((String) -> Unit)?>(null) }
                // THE ORB'S CANCEL FLAG (owner batch 6: *"hit the orb mid-speaking so you could
                // cancel"*). A second tap while the wearer is still talking swallows the flushed
                // Final instead of sending half a sentence.
                val voiceCancel = remember { mutableStateOf(false) }

                fun endListening() {
                    // Both engines get the stop: the pick cannot change mid-session, but an
                    // idle sibling stop is free (flag set / null-recognizer no-op).
                    WearApp.Graph.stt.stop(); WearApp.Graph.sttSystem.stop(); WearApp.Graph.sttGateway.stop()
                    voiceSink.value = null
                }

                fun cancelListening() {
                    voiceCancel.value = true
                    WearApp.Graph.stt.stop(); WearApp.Graph.sttSystem.stop(); WearApp.Graph.sttGateway.stop()
                    voiceSink.value = null
                }



                val startListening = remember {
                    { hands: Boolean ->
                        partial.value = ""; sttStatus.value = ""; sttHistory.clear()
                        voiceCancel.value = false
                        handsFree.value = hands
                        vad.value = Vad.start(System.currentTimeMillis())
                        listening.value = true
                        sttJob.value = uiScope.launch {
                            WearApp.Graph.sttNow().start(lifecycleScope).collect { ev ->
                                when (ev) {
                                    is VoskEvent.Partial -> {
                                        val sink = voiceSink.value
                                        if (sink != null) sink(ev.text)
                                        else {
                                            partial.value = ev.text
                                            if (handsFree.value) vad.value = Vad.onPartial(vad.value, ev.text, System.currentTimeMillis())
                                        }
                                    }
                                    is VoskEvent.Final -> {
                                        partial.value = ""
                                        if (voiceCancel.value) {
                                            // Mid-speech orb tap: the flushed tail is DISCARDED —
                                            // a half-spoken sentence is not what the wearer meant.
                                            voiceCancel.value = false
                                            return@collect
                                        }
                                        if (ev.text.isBlank()) return@collect
                                        val sink = voiceSink.value
                                        if (sink != null) sink(ev.text) // a query, not a prompt
                                        else {
                                            sttHistory.add(ev.text) // stays visible while the mic runs on
                                            session.ask(ev.text)
                                        }
                                    }
                                    is VoskEvent.Error -> sttStatus.value = when (ev.message) {
                                        "model-missing" -> "vosk model missing – run get-vosk-model.sh"
                                        "no-permission" -> "mic permission denied"
                                        else -> "stt: ${ev.message}"
                                    }
                                }
                            }
                            listening.value = false
                        }
                    }
                }
                val micGranted = remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                    )
                }
                val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    micGranted.value = granted
                    if (granted) startListening(true) else sttStatus.value = "mic permission denied"
                }
                val startListeningGate: (Boolean) -> Unit = { hands ->
                    if (micGranted.value) startListening(hands)
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }

                // A field/overlay takes the mic: same gate, but the text goes to the sink.
                fun startVoiceInto(sink: (String) -> Unit) {
                    voiceSink.value = sink
                    startListeningGate(false)
                }

                // Hands-free watchdog (follow-up A): ≥1 word + ~1.2 s silence → finalize & send.
                LaunchedEffect(listening.value, handsFree.value) {
                    if (!listening.value || !handsFree.value) return@LaunchedEffect
                    while (listening.value && handsFree.value) {
                        delay(250)
                        if (Vad.shouldFinalize(vad.value, System.currentTimeMillis())) {
                            Log.i(TAG, "vad: silence ${System.currentTimeMillis() - vad.value.lastWordAtMs}ms after ${vad.value.words} words → send")
                            endListening()
                            break
                        }
                    }
                }

                // THE ORB DIAL map (SPEC §12.1) — live so a Settings ▸ Interface remap lands on the
                // dial instantly. Index 0 = 12:00, clockwise.
                val dialSlots by settings.orbSlots.collectAsState()
                // Skill rows arrive INSIDE the pushed catalog (watchkit reads /v1/skills where the
                // watch cannot, SPEC §12.3), so the watch never fetches them separately.

                // SLASH OVERLAY (SPEC §12.3): six-key recents mirror (on-watch memory) + the
                // gateway-pushed command list (Settings ▸ Interface ▸ Command catalog JSON / the
                // `slash_catalog` dev hook). Null = never opened → the built-in catalog ships.
                val slash by settings.commandUsage.collectAsState()
                val catalogJson by settings.slashCatalogJson.collectAsState()
                var commandsOpen by remember { mutableStateOf(false) }
                var slashPrefill by remember { mutableStateOf<String?>(null) }
                val commandEntries = remember(catalogJson) {
                    CommandCatalog.merge(SlashCommands.BUILTIN, CommandCatalog.parse(catalogJson).entries)
                }

                // M5 deep-links: notification tap opens that chat; "Ask Hermes" tile tap jumps
                // straight into hands-free listening (compose box when the mic isn't granted).
                val link by deepLink.collectAsState()
                LaunchedEffect(link) {
                    val l = link ?: return@LaunchedEffect
                    l.openChat?.let { session.openFromNotification(it); screen = Screen.Chat }
                    if (l.talk) {
                        draft = ""; composing = false; speak.stop()
                        if (micGranted.value) startListening(true) else composing = true
                    }
                }

                // DEV RENDER HARNESS (SPEC §13.3): `-e render <case>` injects the canned answer and
                // shows the chat, so one adb command + one screencap proves a media surface.
                val devRender by WearApp.Graph.devRender.collectAsState()
                LaunchedEffect(devRender) {
                    val id = devRender ?: return@LaunchedEffect
                    WearApp.Graph.devRender.value = null
                    val case = dev.hermes.wearos.domain.RenderCase.of(id)
                    session.injectAnswer(case?.markdown ?: "unknown case '$id' — try `-e render list`")
                    screen = Screen.Chat
                }

                // SESSION SEARCH (SPEC §12.4): debounced query → ChatSession.searchSessions; the
                // 300 ms pause is what turns a dictated sentence (partials arrive per word) into
                // one search, not ten, and keeps typing from hammering watchkit.
                var sessQuery by remember { mutableStateOf("") }
                val searchHits by session.searchHits.collectAsState()
                // `-e search <q>`: lands a typed query exactly where the thumb-types path does,
                // opens Sessions, and clears itself so the box can be driven twice in one run.
                val devSearch by WearApp.Graph.devSearchQuery.collectAsState()
                androidx.compose.runtime.LaunchedEffect(devSearch) {
                    devSearch?.let {
                        screen = Screen.Sessions
                        sessQuery = it
                        WearApp.Graph.devSearchQuery.value = null
                    }
                }
                // `-e open_chat <id>`: open the chat AND show it (screen switch included).
                val devOpen by WearApp.Graph.devOpenChat.collectAsState()
                androidx.compose.runtime.LaunchedEffect(devOpen) {
                    devOpen?.let {
                        session.openChat(it)
                        screen = Screen.Chat
                        WearApp.Graph.devOpenChat.value = null
                    }
                }
                // `-e screen <chat|chats|settings|sessions>`: deterministic navigation for demos.
                val devScr by WearApp.Graph.devScreen.collectAsState()
                androidx.compose.runtime.LaunchedEffect(devScr) {
                    devScr?.let {
                        screen = when (it.lowercase()) {
                            "chats" -> Screen.Chats
                            "settings" -> Screen.Settings
                            "sessions" -> Screen.Sessions
                            else -> Screen.Chat
                        }
                        WearApp.Graph.devScreen.value = null
                    }
                }
                LaunchedEffect(sessQuery) {
                    if (sessQuery.isBlank()) session.searchSessions("")
                    else {
                        kotlinx.coroutines.delay(300)
                        session.searchSessions(sessQuery)
                    }
                }

                // Opening the sessions fast-path refreshes the gateway list (ask #2).
                LaunchedEffect(screen) {
                    if (screen == Screen.Sessions) session.refreshGatewaySessions()
                }

                // Notification routing (owner 2026-10-29): publish what the wrist is looking at.
                // Only the front Chat screen showing THAT chat suppresses its system
                // notifications; every other surface posts "" so events always go Wear OS native.
                val activeChat by session.activeChat.collectAsState()
                LaunchedEffect(screen, activeChat) {
                    WearApp.Graph.viewingChatId = if (screen == Screen.Chat) activeChat else ""
                }
                // PAUSE clears the published view (WearApp gate); coming BACK must re-publish it
                // or wrist-up-over-the-same-chat would notify when it shouldn't.
                val screenNow = rememberUpdatedState(screen)
                val activeChatNow = rememberUpdatedState(activeChat)
                val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                    val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
                        if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME) WearApp.Graph.viewingChatId =
                            if (screenNow.value == Screen.Chat) activeChatNow.value else ""
                    }
                    lifecycleOwner.lifecycle.addObserver(obs)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
                }

                val voiceStatus = when {
                    sttStatus.value.isNotEmpty() -> sttStatus.value
                    ttsState == SpeakAloud.State.MissingEngine -> "tts: no engine on device"
                    else -> ""
                }

                val openChatAndBack: (String) -> Unit = { id ->
                    session.openChat(id); screen = Screen.Chat
                }

                // SLASH OVERLAY (SPEC §12.3): catalog = built-ins ⊕ watchkit's pushed rows ⊕ this
                // agent's skills; recents = the on-watch usage map. The Runs API does not dispatch
                // slashes, so `honored` says what the WATCH will do — never a fake round trip.
                val overlayState = CommandOverlayState(
                    entries = commandEntries,
                    recents = CommandUsage.topRecents(slash, System.currentTimeMillis()),
                    capabilities = CommandCatalog.parse(catalogJson).capabilities,
                )
                fun openCommands(prefill: String?) {
                    slashPrefill = prefill
                    commandsOpen = true
                    uiScope.launch { settings.refreshSlashCatalog(profile) }
                }
                fun runCommand(e: CommandEntry) {
                    uiScope.launch { settings.recordCommand(e.name) }
                    when (e.honored) {
                        CommandKind.Steer -> {
                            commandsOpen = false
                            endListening()
                            editor = EditorReq(text = "/steer ", original = "", hint = "guidance for the run in flight")
                        }
                        CommandKind.Stop -> { commandsOpen = false; session.stopActiveRun() }
                        CommandKind.Side -> {
                            commandsOpen = false
                            endListening()
                            editor = EditorReq(text = "/btw ", original = "", hint = "side thread — main chat stays clean")
                        }
                        CommandKind.Fork -> { commandsOpen = false; session.forkActive() }
                        CommandKind.Model -> { commandsOpen = false; session.ask("/model") }
                        CommandKind.Skill -> { commandsOpen = false; session.ask(e.invocation) }
                        CommandKind.Status -> { commandsOpen = false; session.ask("gateway health?") }
                        CommandKind.Client -> {
                            commandsOpen = false
                            when (e.name) {
                                "sessions" -> screen = Screen.Sessions
                                "chats" -> screen = Screen.Chats
                                "agents" -> { showAgents = true; uiScope.launch { agents.refresh() } }
                                "settings" -> screen = Screen.Settings
                                "new" -> { session.createChat(); screen = Screen.Chat }
                                else -> session.ask(e.invocation)
                            }
                        }
                        else -> {
                            commandsOpen = false
                            editor = EditorReq(text = e.invocation, original = "", hint = "sends as a prompt")
                        }
                    }
                }
                fun submitMaybeSlash(text: String) {
                    val t = text.trim()
                    val cmd = if (t.startsWith("/")) t.substringAfter("/").substringBefore(" ").lowercase() else null
                    val entry = cmd?.let { c -> commandEntries.firstOrNull { it.name == c } }
                    if (entry == null) { session.ask(t); return }
                    runCommand(entry)
                }

                // THE DIAL ROUTER (SPEC §12.1): nine directions, every one remappable in
                // Settings ▸ Interface, all of them lands here. Lanes the branch-2 voice work owns
                // say so plainly instead of silently doing nothing.
                fun handleLane(lane: Lane) {
                    when (lane) {
                        Lane.Keyboard -> {
                            // The drag-up that already shipped, now slot 0 of the dial.
                            endListening()
                            editor = EditorReq(text = draft, original = "", hint = "type or swipe…")
                        }
                        Lane.Mute -> speak.setVoiceEnabled(!voiceOut)
                        // ⚙ 3:00 — the owner's "settings is a little tough to get to": one drag
                        // straight to Settings (record_hi lives on as a remap lane, §12.1).
                        Lane.Settings -> screen = Screen.Settings
                        Lane.RecordHi -> sttStatus.value = "record→pc STT: branch 2 (§13.4)"
                        // 🧠 the model lane (owner: notes nobody used → "make that the model
                        // selector"). Always lands on the chat first: the pick is per-chat.
                        Lane.Model -> {
                            screen = Screen.Chat
                            showModels = true
                            session.refreshModels()
                        }
                        Lane.Commands -> openCommands(null)
                        Lane.Chats -> screen = Screen.Chats
                        Lane.Sessions -> screen = Screen.Sessions
                        // ✅ per-chat approvals (owner 2026-10-29): lands on the chat, sheet opens —
                        // same contract as the model lane (the pick belongs to the chat).
                        Lane.Approvals -> {
                            screen = Screen.Chat
                            approvalsOpen = true
                        }
                        Lane.Agents -> {
                            showAgents = true
                            uiScope.launch { agents.refresh() }
                        }
                        Lane.None -> Unit
                    }
                }


                // LAUNCH FILM (gate + rationale in domain/SplashIntro): decided EXACTLY ONCE per
                // process, and only after the owner's Display switch has actually been read — a
                // launch-time race against DataStore must not be what makes a 14 s film play. The
                // wait is bounded (SplashIntro.SETTING_BUDGET_MS), so a slow disk falls back to the
                // shipped default (ON) instead of holding the launch. The chat composes under the
                // film either way: the intro decorates a warming app, it never gates it.
                var introGate by remember { mutableStateOf<SplashIntro.Gate?>(null) }
                // Which media the chosen mode shows (null = built-in art). Resolved with the
                // gate, ONCE: a mid-launch Settings change must not swap the media under a film
                // that already started.
                var introSource by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    withTimeoutOrNull(SplashIntro.SETTING_BUDGET_MS) {
                        settings.introSettingsLoaded.first { it }
                    }
                    val mode = SplashIntro.choiceOf(settings.splashMode.value)
                    // A stored custom pick counts only while its persistable grant lives; a dead
                    // URI falls back to built-in silently rather than failing the launch.
                    val customRaw =
                        if (mode == SplashIntro.Choice.VIDEO) settings.splashVideoUri.value
                        else settings.splashImageUri.value
                    introSource = customRaw.takeIf { settings.splashGrantLive(it) }
                    val fromLauncher = intent?.isLauncherLaunch() == true
                    Log.i(
                        TAG, "intro gate: mode=${settings.splashMode.value} custom=$customRaw " +
                            "source=${introSource ?: "built-in"} launcher=$fromLauncher " +
                            "deepLink=${deepLink.value != null}",
                    )
                    introGate = SplashIntro.decide(
                        firstCompositionOfProcess = !IntroClaimed.getAndSet(true),
                        choice = mode,
                        deepLink = deepLink.value != null, // notification/tile tap (§M5)
                        fromLauncher = fromLauncher,
                    ).also {
                        if (it != SplashIntro.Gate.PlayVideo && it != SplashIntro.Gate.PlayStatic) {
                            Log.i(TAG, "intro skipped: $it")
                        }
                    }
                }

                // NULL until the gate answers — HermesScaffold waits for the answer before it
                // claims either splash, so a DataStore race can never pick the launch story.
                HermesScaffold(
                    intro = introGate?.let {
                        it == SplashIntro.Gate.PlayVideo || it == SplashIntro.Gate.PlayStatic
                    },
                    introKind = if (introGate == SplashIntro.Gate.PlayStatic) {
                        SplashIntro.Choice.STATIC
                    } else {
                        SplashIntro.Choice.VIDEO
                    },
                    introSource = introSource,
                ) {
                    VoiceHost(session, speak)
                    when (screen) {
                        Screen.Chat -> ChatBody(
                            state = state, voiceOut = voiceOut, voiceStatus = voiceStatus,
                            composing = composing, draft = draft,
                            markdown = markdownOn,
                            onDraftChange = { draft = it },
                            onSend = {
                                // A draft that is ONE slash command opens the overlay instead of
                                // asking — typing "/st" then send is the keyboard-native way in.
                                if (SlashCommands.looksLikeSlashDraft(draft)) {
                                    openCommands(draft.trim().removePrefix("/").takeIf { it.isNotBlank() })
                                    draft = ""; composing = false
                                } else {
                                    session.ask(draft); draft = ""; composing = false
                                }
                            },
                            listening = listening.value || devPtt != null,
                            speaking = speakingNow,
                            speakingOffset = speakingOffsetNow,
                            pttPartial = partial.value.ifBlank { devPtt ?: "" },
                            pttHistory = if (devPtt != null) emptyList() else sttHistory.toList(),
                            handsFree = handsFree.value,
                            micGranted = micGranted.value,
                            onApprove = { session.respondApproval(it) },
                            onOpenChats = { screen = Screen.Chats },
                            onOpenSessions = { screen = Screen.Sessions },
                            // MANUAL DOOR (auto-sync is the primary path): keep dragging up at the
                            // very bottom and the chat re-pulls its gateway session; toast reports.
                            onPullUpAtBottom = {
                                session.catchUpActive(manual = true) { ok, n ->
                                    WearApp.Graph.runOnUi {
                                        Toast.makeText(
                                            context,
                                            when {
                                                !ok -> "⟳ catch-up failed — offline?"
                                                n == 0 -> "⟳ up to date"
                                                else -> "⟳ caught up · $n new"
                                            },
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            onOpenAgents = {
                                showAgents = true
                                uiScope.launch { agents.refresh() } // dialog shows cache instantly, refreshes live
                            },
                            onKeyboard = {
                                // Overlay chip (batch 2): drop the mic, open the FULL-SCREEN editor —
                                // the system keyboard rides on a real focused field (ask #6).
                                endListening()
                                editor = EditorReq(text = draft, original = "", hint = "type or swipe…")
                            },
                            onEditPrompt = { msg -> promptSheet = msg },
                            editor = editor,
                            onEditorDismiss = { editor = null },
                            onEditorSend = { original, text ->
                                val editId = editor?.editOf ?: 0L
                                editor = null
                                PromptEdit.rewrap(original, text)?.let {
                                    speak.stop()
                                    when {
                                        // An EDITED user turn replaces itself (SPEC §12.5), a fresh
                                        // slash draft routes through the command catalog, and only
                                        // then does plain text become a plain ask.
                                        editId > 0L -> session.editAndResend(editId, it)
                                        it.startsWith("/") -> submitMaybeSlash(it)
                                        else -> session.ask(it)
                                    }
                                }
                            },
                            onOrbTap = {
                                when {
                                    // Tap while listening: mid-speech = CANCEL the whole capture
                                    // (owner: *"hit the orb mid-speaking so you could cancel"*);
                                    // after a pause = finalize & send (same as the VAD auto-stop).
                                    listening.value -> {
                                        if (voiceSink.value == null &&
                                            Vad.speakingNow(vad.value, System.currentTimeMillis())
                                        ) cancelListening() else endListening()
                                    }
                                    // Tap while a reply streams = STOP the run (ask #3) — and flush
                                    // the voice queue with it, or a cancelled answer would keep
                                    // talking (owner 2026-10-29 state machine).
                                    state.busy -> {
                                        speak.stop()
                                        session.stopActiveRun()
                                    }
                                    // Tap while it reads aloud = MUTE only — the answer already
                                    // landed; stop speech and view it (orb returns to the mic).
                                    speakingNow -> speak.stop()
                                    // Compose box open: send (or close when empty).
                                    composing && draft.isNotBlank() -> {
                                        speak.stop()
                                        session.ask(draft); draft = ""; composing = false
                                    }
                                    composing -> composing = false
                                    // Follow-up A: tap starts HANDS-FREE listening (VAD auto-send).
                                    else -> { draft = ""; speak.stop(); startListeningGate(true) }
                                }
                            },
                            onHoldStart = {
                                // HOLD BELONGS TO THE DIAL (owner batch 6: long-press-to-listen is
                                // retired — it fought the hold+drag selector). A bare hold with no
                                // drag now means nothing; the dial arms on drag, recording starts
                                // on TAP. TalkOrb still reports hold so callers that want it (none
                                // today) can react; the dial path is onDialArm/onDialMove/onDialEnd.
                            },
                            onHoldEnd = { /* dial semantics only — nothing to flush on bare hold */ },
                            // PINCH FONT-SCALE: multiplicative 0.05-step apply (persisted). Same-value
                            // events are dropped — a pinch only hits DataStore when a step changes.
                            onFontScalePinch = { factor ->
                                val next = FontScale.apply(settings.fontScale.value, factor)
                                if (next != settings.fontScale.value) uiScope.launch { settings.setFontScale(next) }
                            },
                            onFontScaleReset = { uiScope.launch { settings.setFontScale(FontScale.DEFAULT) } },
                            slots = dialSlots,
                            onDial = { lane -> handleLane(lane) },
                            replyVolume = replyVol,
                            onReplyVolume = { v ->
                                speak.setReplyVolume(v)   // persists via AppSettings mirror
                                if (voiceOut) speak.preview()
                            },
                            commands = DialCommands(
                                open = commandsOpen,
                                state = overlayState,
                                prefill = slashPrefill,
                                voiceOn = listening.value && voiceSink.value != null,
                                onVoiceStart = { startVoiceInto { t -> slashPrefill = t } },
                                onVoiceStop = { endListening() },
                                onPick = { e -> runCommand(e) },
                                onPrompt = { t -> commandsOpen = false; submitMaybeSlash(t) },
                                onDismiss = { commandsOpen = false },
                            ),
                        )
                        Screen.Chats -> ExitOnSwipeBack(onBack = { screen = Screen.Chat }) {
                            ChatsScreen(
                                chats = chats.filter { it.profile == profile },
                                agent = profile,
                                activeId = activeChatId,
                                onOpen = openChatAndBack,
                                onCreate = { session.createChat(); screen = Screen.Chat },
                                onRename = { id, label -> session.renameChat(id, label) },
                                onOpenSettings = { screen = Screen.Settings },
                            )
                        }
                        Screen.Sessions -> ExitOnSwipeBack(onBack = { screen = Screen.Chat }) {
                            SessionsScreen(
                                agent = profile,
                                sessions = gatewaySessions,
                                archived = archivedSessions,
                                meta = sessionsMeta,
                                filter = sessionFilter,
                                onFilter = { sessionFilter = it },
                                // Pinned NEW SESSION row: fresh chat for the current agent, straight into it.
                                onNewSession = { session.createChat(); screen = Screen.Chat },
                                onOpen = { id, title, prof ->
                                    screen = Screen.Chat
                                    if (prof.isNotEmpty() && prof != profile) {
                                        // ADOPTING A FOREIGN-PROFILE SESSION (desktop chat): the agent must
                                        // swap first — switchAgent's callback lands on a worker thread, so
                                        // every UI touch here goes through Graph.runOnUi (Toast-crash guard).
                                        session.switchAgent(prof) { ok, _, err ->
                                            if (ok) {
                                                session.resumeGatewaySession(id, title, prof)
                                                WearApp.Graph.runOnUi {
                                                    Toast.makeText(context, "agent → $prof", Toast.LENGTH_SHORT).show()
                                                }
                                            } else WearApp.Graph.runOnUi {
                                                Toast.makeText(context, "switch failed: $err", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } else {
                                        session.resumeGatewaySession(id, title)
                                    }
                                },
                                onRefresh = { session.refreshGatewaySessions() },
                                query = sessQuery,
                                onQueryChange = { sessQuery = it },
                                hits = searchHits,
                                voiceOn = listening.value && voiceSink.value != null,
                                onVoiceStart = { startVoiceInto { t -> sessQuery = t } },
                                onVoiceStop = { endListening() },
                                onOpenLocal = { id -> session.openChat(id); screen = Screen.Chat },
                                onManage = { manageSheet = it },
                            )
                        }
                        Screen.Settings -> ExitOnSwipeBack(onBack = { screen = Screen.Chat }) {
                            SettingsScreen(context = context, settings = settings, speak = speak)
                        }
                    }
                }

                // Dev hook `-e viewer_demo [scale]`: the MEDIA VIEWER over a generated test card —
                // the sub-fit zoom-out proof (d3) over adb without a live gateway image.
                val devViewerScale by WearApp.Graph.devViewerScale.collectAsState()
                // `-e model_picker`: open the 🧠 model picker exactly like a lane release would.
                val devModelPicker by WearApp.Graph.devModelPicker.collectAsState()
                val devApprovals by WearApp.Graph.devApprovals.collectAsState()
                val devAgents by WearApp.Graph.devAgents.collectAsState()
                LaunchedEffect(devAgents) {
                    if (devAgents) {
                        WearApp.Graph.devAgents.value = false
                        screen = Screen.Chat
                        showAgents = true; uiScope.launch { agents.refresh() }
                    }
                }
                LaunchedEffect(devApprovals) {
                    if (devApprovals) {
                        WearApp.Graph.devApprovals.value = false
                        screen = Screen.Chat
                        approvalsOpen = true
                    }
                }
                LaunchedEffect(devModelPicker) {
                    if (devModelPicker) {
                        WearApp.Graph.devModelPicker.value = false
                        screen = Screen.Chat
                        showModels = true
                        session.refreshModels()
                    }
                }
                // THE BACK BUTTON NEVER QUITS (owner batch 5: "hitting the back button from the
                // keyboard exits Hermes — it needs to go back to the previous screen ALWAYS"):
                // whatever is open on top closes first, topmost layer wins. Composed AFTER the
                // screen BackHandlers below, so while an overlay is up this one is the enabled
                // winner; with nothing open it disables and Sessions/Settings keep their
                // → Chat handler, and Chat keeps the plain system default (leaving is the exit).
                val overlayOpen = editor != null || promptSheet != null || manageSheet != null ||
                    confirmDelete != null || showAgents || showModels || approvalsOpen || commandsOpen || devViewerScale != null
                androidx.activity.compose.BackHandler(enabled = overlayOpen) {
                    when {
                        editor != null -> editor = null
                        promptSheet != null -> promptSheet = null
                        confirmDelete != null -> confirmDelete = null
                        manageSheet != null -> manageSheet = null
                        // Model picker closes onto the chat (same contract as Agents).
                        showModels -> { showModels = false; screen = Screen.Chat }
                        approvalsOpen -> { approvalsOpen = false; screen = Screen.Chat }
                        // Agents closes ONTO CHAT (back ring, batch 6): from the ring it is the
                        // Agents→Chat leg; from a dial open it simply goes home to the chat.
                        showAgents -> { showAgents = false; screen = Screen.Chat }
                        commandsOpen -> commandsOpen = false
                        devViewerScale != null -> WearApp.Graph.devViewerScale.value = null
                    }
                }
                // THE RING (owner batch 6: *"chat to sessions to agents, then back to chat again —
                // the app shouldn't really quit unless I hit the home button"*). With nothing open,
                // physical back walks Chat → Sessions → Agents → Chat; the app never finishes from
                // here (Chats/Settings rejoin at Chat). Composed last so it outranks the screens'
                // own handlers; edge-swipe keeps its return-to-Chat meaning untouched.
                androidx.activity.compose.BackHandler(enabled = !overlayOpen) {
                    when (BackRing.action(screen)) {
                        BackRing.Action.ToSessions -> screen = Screen.Sessions
                        BackRing.Action.ToAgents -> {
                            showAgents = true
                            uiScope.launch { agents.refresh() }
                        }
                        BackRing.Action.ToChat -> screen = Screen.Chat
                    }
                }
                devViewerScale?.let { startScale ->
                    ZoomableMediaViewer(
                        bitmap = remember { devMediaTestCard() },
                        caption = "dev card — sub-fit: drag to pan the clipped corners",
                        initialScale = startScale,
                        onDismiss = { WearApp.Graph.devViewerScale.value = null },
                    )
                }

                if (showAgents) {
                    AgentPickerDialog(
                        current = profile,
                        agents = ProfileLogic.displayAgents(agentList, profile),
                        gateway = gatewayLabel,
                        note = when {
                            agentsRefreshing -> "checking gateway…"
                            agentList.isEmpty() -> "offline list · offers wear + default"
                            else -> ""
                        },
                        onPick = { name ->
                            showAgents = false
                            screen = Screen.Chat // the ring's Agents→Chat leg, same on pick
                            if (name != profile) {
                                session.switchAgent(name) { ok, _, err ->
                                    // REGRESSION (2a32c65 walkthrough): this callback fires from ChatSession's
                                    // Graph scope = Dispatchers.Default; Toast here crashed the app
                                    // ("Can't toast on a thread that has not called Looper.prepare()").
                                    // ALWAYS hop via Graph.runOnUi — see the gate's comment in WearApp.Graph.
                                    WearApp.Graph.runOnUi {
                                        Toast.makeText(
                                            context,
                                            if (ok) "agent → $name" else "switch failed: $err",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                        },
                        onDismiss = { showAgents = false; screen = Screen.Chat },
                    )
                }
                if (showModels) {
                    val modelState by session.modelState.collectAsState()
                    ModelPickerDialog(
                        picked = modelState.picked,
                        defaultModel = modelState.catalog?.defaultModel ?: "",
                        groups = modelState.catalog?.groups ?: emptyList(),
                        loading = modelState.loading,
                        error = modelState.error,
                        agent = profile,
                        onPick = { m ->
                            session.pickModel(m)
                            showModels = false
                            WearApp.Graph.runOnUi {
                                Toast.makeText(
                                    context,
                                    if (m.isEmpty()) "🌐 gateway default — this chat"
                                    else "🧠 ${m.substringAfterLast('/')} — this chat",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onDismiss = { showModels = false; screen = Screen.Chat },
                    )
                }
                if (approvalsOpen) {
                    val uiState by session.state.collectAsState()
                    ApprovalModeDialog(
                        current = session.activeChatApprovalMode(),
                        chatLabel = uiState.activeChatLabel,
                        agent = profile,
                        onPick = { m ->
                            session.setChatApprovalMode(m)
                            approvalsOpen = false
                            WearApp.Graph.runOnUi {
                                Toast.makeText(
                                    context,
                                    "✅ approvals: ${m.label} — this chat",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onDismiss = { approvalsOpen = false; screen = Screen.Chat },
                    )
                }
                promptSheet?.let { msg ->
                    PromptActionSheet(
                        preview = msg.text,
                        onEdit = {
                            promptSheet = null
                            editor = EditorReq(text = msg.text, original = msg.text, hint = "✎ edit & resend", editOf = msg.id)
                        },
                        onCopy = {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("prompt", msg.text))
                            promptSheet = null
                        },
                        onDismiss = { promptSheet = null },
                    )
                }
                manageSheet?.let { row ->
                    SessionActionSheet(
                        title = row.title.ifBlank { row.id },
                        profileTag = row.profile,
                        onFork = {
                            manageSheet = null
                            session.forkBrowserSession(row) { ok, msg ->
                                // Worker-thread callback (Toast-crash guard, see switchAgent above);
                                // success ALSO jumps into the fresh fork chat — the whole point.
                                WearApp.Graph.runOnUi {
                                    Toast.makeText(
                                        context,
                                        if (ok) "⑂ $msg" else "fork failed: $msg",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    if (ok) screen = Screen.Chat
                                }
                            }
                        },
                        onDelete = { manageSheet = null; confirmDelete = row },
                        onArchive = {
                            manageSheet = null
                            session.archiveBrowserSession(row, archived = !row.archived) { ok, msg ->
                                WearApp.Graph.runOnUi {
                                    Toast.makeText(
                                        context,
                                        if (ok) "🗃 $msg" else "archive failed: $msg",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        isArchived = row.archived,
                        onDismiss = { manageSheet = null },
                    )
                }
                confirmDelete?.let { row ->
                    SessionDeleteSheet(
                        title = row.title.ifBlank { row.id },
                        profileTag = row.profile,
                        onConfirm = {
                            confirmDelete = null
                            session.deleteBrowserSession(row) { ok, msg ->
                                WearApp.Graph.runOnUi {
                                    Toast.makeText(
                                        context,
                                        if (ok) "🗑 session deleted" else "delete failed: $msg",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        onDismiss = { confirmDelete = null },
                    )
                }
            }
        }
    }

    /** singleTask relaunch: notification/tile taps land here while we are alive. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseDeepLink(intent)
    }

    /** Foregrounding our chat acknowledges it (M5: cancel notification + attention). */
    override fun onResume() {
        super.onResume()
        WearApp.Graph.clearAttention(WearApp.Graph.session.activeChat.value)
    }

    private fun parseDeepLink(intent: Intent?) {
        val openChat = intent?.getStringExtra(dev.hermes.wearos.notify.Notifs.EXTRA_OPEN_CHAT)
        val talk = if (intent?.hasExtra("talk") == true) try {
            intent.getBooleanExtra("talk", false)
        } catch (e: Exception) { // adb --es talk true arrives as a String
            intent.getStringExtra("talk")?.toBoolean() == true
        } else false
        if (openChat != null || talk) {
            navNonce++
            deepLink.value = DeepLink(openChat, talk, navNonce)
        }
    }
}

/** Right edge-swipe exit (Chats/Sessions/Settings → Chat). The PHYSICAL back button follows the
 *  ring (BackRing) instead — swipe is the "just get me back to the chat" gesture. */
@Composable
private fun ExitOnSwipeBack(onBack: () -> Unit, content: @Composable () -> Unit) {
    SwipeToDismissBox(
        onDismissed = onBack,
        modifier = Modifier.fillMaxSize(),
    ) { isBackgroundVisible ->
        if (isBackgroundVisible) {
            Text(
                "‹ chats",
                style = MaterialTheme.typography.button,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxSize().padding(top = 40.dp),
            )
        } else {
            content()
        }
    }
}

@Composable
private fun ChatBody(
    state: dev.hermes.wearos.domain.ChatUiState,
    voiceOut: Boolean,
    voiceStatus: String,
    composing: Boolean,
    draft: String,
    markdown: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    listening: Boolean,
    /** TTS queue live → the STOP face + hold-pops-dial state machine (owner 2026-10-29). */
    speaking: Boolean = false,
    /** Char offset into `state.live` of the sentence being READ ALOUD (karaoke follow, null = silent). */
    speakingOffset: Int? = null,
    pttPartial: String,
    pttHistory: List<String>,
    handsFree: Boolean,
    micGranted: Boolean,
    onApprove: (String) -> Unit,
    onOpenChats: () -> Unit,
    onOpenSessions: () -> Unit,
    /** Pull-up-at-the-bottom of the transcript — re-pull this chat's gateway session (manual door;
     *  entry into the chat already auto-catches-up). */
    onPullUpAtBottom: () -> Unit = {},
    onOpenAgents: () -> Unit,
    onKeyboard: () -> Unit,
    onEditPrompt: (dev.hermes.wearos.domain.ChatMessage) -> Unit,
    editor: EditorReq?,
    onEditorDismiss: () -> Unit,
    onEditorSend: (original: String, text: String) -> Unit,
    onOrbTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onFontScalePinch: (Float) -> Unit,
    onFontScaleReset: () -> Unit,
    /** THE ORB DIAL (SPEC §12.1): the eight lanes as the owner mapped them (Settings ▸ Interface). */
    slots: List<Lane>,
    onDial: (Lane) -> Unit,
    /** Mute-lane double slider (owner 2026-10-29): current reply volume + commit on release. */
    replyVolume: Float,
    onReplyVolume: (Float) -> Unit,
    /** The 6:00 slash overlay, when open (SPEC §12.3). */
    commands: DialCommands,
) {
    // LAYOUT D (OWNER-APPROVED mockup varD.png): the chat fills the round screen and the bottom
    // bar is no longer a Row — it is the computed FOUR-CIRCLE CLUSTER (domain/BottomLayout):
    // talk orb grounded off the bottom edge, AGENT LEFT / SESSIONS RIGHT with labels 8–10px ABOVE
    // them, and the MUTE satellite riding the right arc (THE single mute affordance — the old
    // below-orb pill is gone). While the mic owns the screen (hold OR hands-free tap, ask #4) the
    // transcript is REPLACED by the full-screen live overlay — STT text never starts at the top.
    //
    // NO hardcoded px in the UI: the Box is measured once (onSizeChanged), BottomLayout returns
    // centers+radii in real pixels, and the composition converts px → dp via LocalDensity.
    //
    // The list state is HOISTED here (remembered at ChatBody level, not inside ChatScreen): the
    // PTT overlay and the transcript are an if/else pair, so ChatScreen LEAVES composition while
    // listening. A per-call remember would reset the scroll to the top when the overlay dismisses
    // — the walkthrough's "after stt talk button done, it scrolls to the top of the convo".
    val chatListState = androidx.wear.compose.foundation.lazy.rememberScalingLazyListState()
    // TAIL-FOLLOW flag (owner 2026-10-29, domain/ChatFollow): survives the PTT overlay exactly
    // like chatListState — reading scroll-back, pressing the orb, and cancelling must NOT snap
    // the view to the bottom; a new ask re-engages on its own.
    val chatFollow = remember { mutableStateOf(true) }
    // Karaoke's "we moved it" stamp (guard rationale in ChatScreen's parameter docs).
    val chatScrollStamp = remember { mutableStateOf(0L) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val vpW = viewport.width.toFloat()
    val vpH = viewport.height.toFloat()
    val cluster = remember(viewport) { BottomLayout.geometry(vpW, vpH) }
    // CIRCLE-SAFE BOTTOM (batch-4 #1): the larger of the orb-crown rule and the BEZEL-RIM rule —
    // the newest line's bottom-LEFT corner must sit inside the physical circle, or the round face
    // shaves it (owner: *"the bottom left of the text gets cut off from the circle screen face"*).
    // Text left edge = the SLC gutter (~8 dp, measured from the owner's 45-lines screenshot);
    // square faces fall through to the old crown rule unchanged.
    val roundShape = dev.hermes.wearos.ui.isRoundScreen()
    val clusterPadPx = remember(viewport, roundShape) {
        BottomLayout.circleSafeBottomPad(
            viewportW = vpW, viewportH = vpH, round = roundShape,
            contentLeftPx = with(density) { 8.dp.toPx() },
            orbTopPx = cluster.talk.topY(),
        )
    }
    val hasViewport = viewport.width > 0 && viewport.height > 0
    // Transcript/overlay clearance = the cluster's top edge; pre-measure first frame keeps the
    // old 74–78 dp bar clearance.
    val barPadDp = if (hasViewport) with(density) { clusterPadPx.toDp() } else 78.dp
    // KARAOKE FOLLOW (owner 2026-10-29: *"while it's speaking the line that is being spoken is
    // in the middle of the screen. That way you can follow along"*): while the answer is being
    // READ ALOUD and tail-follow is engaged, the spoken line rides the 10-o'clock tick height
    // (batch-4 #2; was ~⅓ down — the rim shaves text that low on round faces). The
    // char→pixel ratio SELF-CALIBRATES from the live item's measured height (no font math, no
    // assumption about markdown sizes); scrolling away disengages exactly like the tail-pin
    // (same chatFollow escape hatch — no yanking); a line already comfortably on screen is
    // never nudged; and the tap-to-mute orb simply stops the feed, freeing the scroll.
    // SETTLED FOLLOW (owner 2026-10-29 night: *"when you're counting and stuff for the audio
    // test I am not seeing the line that you're saying be at the center"*): the voice lags the
    // stream, so most reading happens AFTER the answer settles — and the live item then vanishes
    // into the transcript, which the first version read as "nothing to follow". It now follows
    // the settled last-Hermes row too (same char offsets — the settled text IS the live text),
    // and the ticker in SpeakAloud walks the cursor THROUGH un-punctuated replies (number lists
    // ride the TTS queue as one utterance; onStart alone would pin line 1 for a minute).
    LaunchedEffect(speakingOffset, state.messages.size, state.live.length) {
        val off = speakingOffset ?: return@LaunchedEffect
        if (!speaking || !chatFollow.value || vpH <= 0f) return@LaunchedEffect
        if (off < 0) return@LaunchedEffect
        val liveLen = state.live.length
        val settling = liveLen <= 0
        val lastMsg = state.messages.lastOrNull()
        if (settling && (lastMsg == null || lastMsg.role != ChatMessage.Role.Hermes)) return@LaunchedEffect
        val textLen = if (settling) lastMsg!!.text.length else liveLen
        if (textLen <= 0) return@LaunchedEffect
        val tailIdx = 2 + state.messages.size + (if (state.approval != null) 1 else 0)
        // Last row of the transcript sits at 2+size-1 regardless of a trailing approval card.
        val wantIdx = if (settling) 1 + state.messages.size else tailIdx
        val item = chatListState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == wantIdx } ?: return@LaunchedEffect
        val yOfSpoken = off.toFloat() / textLen * item.size
        // THE 10 O'CLOCK ANCHOR (batch-4 #2, owner: *"the beginning of the speech should start
        // at the 10 o'clock spot"*): the spoken line rides RoundScreen.tenOclockY (~25 % down,
        // rim wide open up there → left edge never clipped). The no-nudge band runs from just
        // under the clock (~12 %) to the 8:20 rim∩orb line (~60 %): anything already inside is
        // comfortably readable, anything outside is lifted back to the tick.
        val anchorY = dev.hermes.wearos.domain.RoundScreen.tenOclockY(vpW, vpH)
        val onScreen = item.offset + yOfSpoken
        if (onScreen in (anchorY - vpH * 0.12f)..(anchorY + vpH * 0.35f)) return@LaunchedEffect
        // INSTANT, not animateScrollToItem: the follow-disengage detector reads a RISE of
        // isScrollInProgress as the user grabbing the list, and wear-compose raises it for
        // ANIMATED scrolls too — an animated karaoke nudge disengaged tail-follow on its first
        // jump and froze the view (owner: "I am not seeing the line that you're saying be at the
        // center"). Even instant jumps raise it on this wear-compose, so we also STAMP the jump:
        // edges settling within the guard window are ours (ChatScreen skips them). The 180 ms
        // ticker + the ~12–60 % no-nudge band keep the jumps rare and small.
        chatScrollStamp.value = android.os.SystemClock.elapsedRealtime()
        chatListState.scrollToItem(wantIdx, (yOfSpoken - anchorY).roundToInt().coerceAtLeast(0))
    }
    Box(modifier = Modifier.fillMaxSize().onSizeChanged { viewport = it }) {
        if (listening) {
            PttOverlay(
                partial = pttPartial,
                handsFree = handsFree,
                onKeyboard = onKeyboard,
                history = pttHistory,
                modifier = Modifier.padding(bottom = barPadDp),
            )
        } else {
            androidx.compose.foundation.layout.Column(
                // LAYOUT D: the transcript runs the FULL height — the SLC contentPadding (bottom =
                // cluster top) is what keeps the last message clear of the cluster, so ChatAnchor
                // keeps working unchanged. PINCH FONT-SCALE lives on this container: two fingers
                // scale the markdown typography; a single-finger drag is NEVER consumed here, so
                // the SLC keeps scrolling exactly as before (raw-gesture pattern from MediaViewer).
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val ev = awaitPointerEvent()
                                if (ev.changes.count { it.pressed } >= 2) {
                                    val zoom = ev.calculateZoom()
                                    if (zoom != 1f) {
                                        android.util.Log.d("HermesWear", "pinch font-scale z=$zoom")
                                        onFontScalePinch(zoom)
                                        ev.changes.forEach { if (it.pressed) it.consume() }
                                    }
                                }
                            } while (ev.changes.any { it.pressed })
                        }
                    }
                    // HIDDEN RESET: double-tap an empty stretch of the transcript → 1.0× (documented
                    // in SPEC §4; no toast, the type just snaps back and re-anchors). RAW detector —
                    // detectTapGestures bails when a child consumes, and the SLC taps-to-center
                    // consumes every tap inside the transcript; this loop ignores consumption.
                    .rawDoubleTap {
                        android.util.Log.d("HermesWear", "transcript double-tap → font-scale reset")
                        onFontScaleReset()
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (composing) {
                    // IME RULE (owner epic): no field depends on Enter. singleLine + Send action
                    // AND an explicit SEND button — a misbehaving IME that turns Enter into a
                    // space can never strand the draft again.
                    // Round discipline: the plain Column above the chat needs its own top inset
                    // (the clock zone), otherwise the field rides under TimeText on a round face.
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(top = dev.hermes.wearos.ui.roundInsets().topDp.dp + 4.dp)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BasicTextField(
                            value = draft,
                            onValueChange = onDraftChange,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (draft.isNotBlank()) onSend() }),
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.body2.copy(color = MaterialTheme.colors.onBackground),
                            cursorBrush = SolidColor(MaterialTheme.colors.primary),
                        )
                        Text(
                            "send ➤",
                            style = MaterialTheme.typography.caption1,
                            color = if (draft.isNotBlank()) MaterialTheme.colors.primary
                                else MaterialTheme.colors.onBackground.copy(alpha = 0.35f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(MaterialTheme.colors.surface.copy(alpha = 0.45f))
                                .clickable(onClickLabel = "send message") {
                                    if (draft.isNotBlank()) onSend()
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                ChatScreen(
                    state = state,
                    onApprove = onApprove,
                    voiceEnabled = voiceOut,
                    voiceStatus = voiceStatus,
                    chatLabel = state.activeChatLabel,
                    onOpenChats = onOpenChats,
                    markdown = markdown,
                    onEditPrompt = onEditPrompt,
                    listState = chatListState,
                    follow = chatFollow,
                    programmaticScrollAt = chatScrollStamp,
                    contentPadBottomDp = if (hasViewport) barPadDp else null,
                    viewportW = vpW,
                    onPullUpAtBottom = onPullUpAtBottom,
                )
            }
        }
        // LAYOUT D: the bottom cluster (four computed circles + labels) — always on top of the
        // transcript, exactly on the BottomLayout geometry for the measured viewport.
        if (hasViewport) {
            OrbCluster(
                cluster = cluster,
                viewportW = vpW,
                viewportH = vpH,
                listening = listening,
                speaking = speaking,
                running = state.busy,
                voiceOut = voiceOut,
                slots = slots,
                onDial = onDial,
                onTap = onOrbTap,
                onHoldStart = onHoldStart,
                onHoldEnd = onHoldEnd,
                replyVolume = replyVolume,
                onReplyVolume = onReplyVolume,
                approval = state.approval,
                chatAtBottom = chatFollow.value,
                onApproveChoice = onApprove,
            )
        }
        // Full-screen prompt editor (asks #5/#6) LAST in the Box = topmost: covers chat + orb bar,
        // the window resizes for the system keyboard (adjustResize) so the field stays visible.
        editor?.let { req ->
            PromptEditorOverlay(
                original = req.original,
                hint = req.hint,
                prefill = req.text,
                onDismiss = onEditorDismiss,
                onSend = { text -> onEditorSend(req.original, text) },
            )
        }
        // THE SLASH OVERLAY (dial 6:00) — above everything, mic included.
        if (commands.open) {
            CommandsOverlay(
                state = commands.state,
                prefill = commands.prefill,
                voiceOn = commands.voiceOn,
                onVoiceStart = commands.onVoiceStart,
                onVoiceStop = commands.onVoiceStop,
                onPick = commands.onPick,
                onPrompt = commands.onPrompt,
                onDismiss = commands.onDismiss,
            )
        }
    }
}

/**
 * Everything the 6:00 overlay needs, bundled so ChatBody's signature stays readable: the merged
 * catalog + recents, the prefill (a typed `/st…` draft or a dictated query), and the three ways an
 * entry leaves (pick / prompt / dismiss).
 */
private data class DialCommands(
    val open: Boolean,
    val state: CommandOverlayState,
    val prefill: String?,
    val voiceOn: Boolean,
    val onVoiceStart: () -> Unit,
    val onVoiceStop: () -> Unit,
    val onPick: (CommandEntry) -> Unit,
    val onPrompt: (String) -> Unit,
    val onDismiss: () -> Unit,
)

/** Request to open the full-screen editor: prefill, the text it edits ("" = fresh prompt), hint. */
/** The compose/edit sheet. `editOf` = transcript message id when this is an EDITED user turn
 *  (drives fork-on-edit, SPEC §12.5); 0 = a fresh prompt. */
private data class EditorReq(val text: String, val original: String, val hint: String, val editOf: Long = 0L)

/**
 * THE ORB, ALONE (owner batch 3: "get all but the orb button off of the main screen"). The
 * AGENT/SESSIONS/MUTE satellites are GONE from the chat screen — each one is now a drag direction
 * on the disc (SPEC §12.1), so this composable is the orb, its dial ring, and the contextual rise
 * that makes the crowded lower arc reachable:
 *
 *  - [TalkOrb] owns the raw pointer stream (tap = record / stop / cancel, drag = vectors out;
 *    the bare hold is inert — long-press belongs to the dial, owner batch 6).
 *  - [OrbDial] decides which of the eight wedges a vector means, whether the release cancels, and
 *    whether the disc should rise; the ring icons live FARTHER out than the activation gate, so a
 *    learner always drags toward something visible (see ui/OrbDialOverlay).
 *  - The rise animates the orb's own center (110 ms) and the ring follows the animated center, so
 *    the icons never detach from the disc they belong to. Leaving the lower arc settles it back.
 *
 * Geometry still comes pre-computed from domain/BottomLayout (which keeps the satellite circles for
 * the mockup + its regression tests); this file only converts px → dp.
 */
@Composable
private fun OrbCluster(
    cluster: BottomLayout.Cluster,
    viewportW: Float,
    viewportH: Float,
    listening: Boolean,
    /** TTS queue live → the STOP face + hold-pops-dial state machine (owner 2026-10-29). */
    speaking: Boolean = false,
    running: Boolean,
    voiceOut: Boolean,
    slots: List<Lane>,
    onDial: (Lane) -> Unit,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    /** THE MUTE-LANE DOUBLE SLIDER (owner 2026-10-29): current reply volume seeds the dwell mode,
     *  the release commits the new level (domain/DialVolume owns the math). */
    replyVolume: Float,
    onReplyVolume: (Float) -> Unit,
    /** BATCH-4 #5 (owner: *"OK should be to the LEFT of the orb, NO to the RIGHT — but only when
     *  I'm looking at the bottom of the chat, not permanently hanging there"*): a live approval
     *  while the transcript rests at its tail paints ✅/❌ on the old agent/sessions spots. */
    approval: dev.hermes.wearos.domain.ApprovalInfo? = null,
    chatAtBottom: Boolean = false,
    onApproveChoice: (String) -> Unit = {},
) {
    val density = LocalDensity.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scale = OrbDial.scaleFor(minOf(viewportW, viewportH))
    var armed by remember { mutableStateOf(false) }
    var activeSlot by remember { mutableStateOf(-1) }
    // VOLUME MODE (the double slider's second half): dwelled on the mute lane, lateral travel
    // now scales TTS reply volume instead of walking lanes; the release commits, no mute toggle.
    var volumeMode by remember { mutableStateOf(false) }
    var volumeLevel by remember { mutableStateOf(1f) }
    var volumeDial by remember { mutableStateOf<DialVolume?>(null) }
    var spinEngaged by remember { mutableStateOf(false) }
    // SPIN MODE state (domain/DialSpin): the ring's rotation phase, animated so detents read as
    // icons physically travelling around the disc (owner: "those little icons turn around you").
    val spin = remember(scale) { DialSpin(scale) }
    var spinPhase by remember { mutableStateOf(0f) }
    val spinPhaseAnim by androidx.compose.animation.core.animateFloatAsState(
        spinPhase,
        androidx.compose.animation.core.tween(130),
        label = "dialspinphase",
    )
    // `-e dial_preview`: pin the ring for screenshots (see WearApp.Graph.devDialPreview — why
    // adb cannot catch a live drag). Auto-clears; nothing user-facing depends on it.
    val dialPreview by WearApp.Graph.devDialPreview.collectAsState()
    androidx.compose.runtime.LaunchedEffect(dialPreview) {
        if (dialPreview) {
            kotlinx.coroutines.delay(8_000)
            WearApp.Graph.devDialPreview.value = false
        }
    }
    var risen by remember { mutableStateOf(false) }
    val orbCy by androidx.compose.animation.core.animateFloatAsState(
        if (risen) OrbDial.risenCenterY(cluster.talk.cy, viewportH) else cluster.talk.cy,
        androidx.compose.animation.core.tween(110),
        label = "orbRise",
    )
    // MUTE DWELL → VOLUME MODE (owner: "pause on mute for maybe two seconds, then … swipe back
    // and forth to change the volume"). Restarted by every aim change, so the timer only ever
    // completes with the finger still parked on MUTE and no spin engaged.
    androidx.compose.runtime.LaunchedEffect(armed, activeSlot, spinEngaged, volumeMode) {
        if (!armed || volumeMode || spinEngaged) return@LaunchedEffect
        if (activeSlot < 0 || OrbDial.slotFor(slots, activeSlot) != Lane.Mute) return@LaunchedEffect
        kotlinx.coroutines.delay(DialVolume.DWELL_MS)
        volumeDial = DialVolume(scale, replyVolume)
        volumeLevel = replyVolume
        volumeMode = true
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        Log.i("HermesWear", "dial volume mode armed (mute dwell, start=$replyVolume)")
    }
    // RING FIRST so the disc paints over its own center (the caption inside the overlay is the
    // one label that survives every direction, including the crowded lower arc).
    OrbDialOverlay(
        slots = slots,
        orbCxPx = cluster.talk.cx,
        orbCyPx = orbCy,
        scale = scale,
        activeSlot = if (armed) activeSlot else -1,
        visible = armed || dialPreview,
        phaseDeg = spinPhaseAnim,
        voiceOut = voiceOut,
        volumeBadge = if (volumeMode) "🔊 ${DialVolume.pct(volumeLevel)}%" else null,
    )
    TalkOrb(
        listening = listening,
        speaking = speaking,
        running = running,
        diameter = with(density) { (cluster.talk.r * 2f).toDp() },
        modifier = Modifier.offset {
            IntOffset(
                (cluster.talk.cx - cluster.talk.r).roundToInt(),
                (orbCy - cluster.talk.r).roundToInt(),
            )
        },
        onTap = onTap,
        onHoldStart = onHoldStart,
        onHoldEnd = onHoldEnd,
        dialScale = scale,
        onDialArm = {
            armed = true; activeSlot = -1; risen = false; spinPhase = 0f; spin.reset()
            volumeMode = false; volumeDial = null; spinEngaged = false
        },
        onDialMove = { dx, dy ->
            // VOLUME MODE owns the stream once armed: lateral travel sets reply volume and the
            // spin never sees a move (the lit lane stays mute; the ring holds still).
            if (volumeMode) {
                val d = volumeDial
                if (d != null) {
                    d.move(dx)
                    if (d.changed) {
                        volumeLevel = d.level
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                }
                return@TalkOrb
            }
            // AIM is the default (highlight follows the live angle — free re-aim, owner batch 5);
            // a lateral sweep from the top arc engages SPIN (domain/DialSpin): detents walk the
            // lit slot and the ring physically rotates it under the finger.
            spin.move(dx, dy)
            if (spin.changed) {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            }
            spinEngaged = spin.spinning
            activeSlot = spin.activeSlot
            spinPhase = spin.phaseDeg
            risen = if (spin.spinning) false else OrbDial.needsRise(dx, dy, scale)
        },
        onDialEnd = { _, _ ->
            armed = false; activeSlot = -1; risen = false; spinPhase = 0f
            if (volumeMode) {
                // The double slider commits its level — the mute toggle is consumed by the dwell.
                volumeMode = false
                val v = volumeLevel
                volumeDial = null
                Log.i("HermesWear", "dial volume committed v=$v")
                onReplyVolume(v)
            } else {
                val slot = spin.commit() // spun gesture: the LIT slot commits, wherever the finger rose
                if (slot != null) {
                    val lane = OrbDial.slotFor(slots, slot)
                    if (lane != Lane.None) {
                        Log.d("HermesWear", "dial lane=${lane.id} slot=$slot spin=${spin.spinning}")
                        onDial(lane)
                    }
                }
            }
            spinEngaged = false
        },
    )
    // THE APPROVAL FLANKS (batch-4 #5, owner: *"✅ OK to the LEFT of the orb, ❌ NO to the RIGHT,
    // but only when I'm at the bottom of the chat"*): shown ONLY while a live approval waits AND
    // the transcript rests at its tail (chatFollow) — they never dangle by the orb while the user
    // reads history, so there is no permanent mis-click bait. This also fixes the reported bug
    // (buttons invisible when the approval lands mid-scroll): scrolling to the bottom re-engages
    // follow and the flanks appear instantly; the in-list card still carries the question text.
    val appr = approval
    if (appr != null && chatAtBottom) {
        ApprovalFlank(cluster.agent, Color(0xFF1B5E20), "✅") {
            onApproveChoice(dev.hermes.wearos.domain.ApprovalChoices.approveName(appr.choices))
        }
        ApprovalFlank(cluster.sessions, Color(0xFF7F1D1D), "❌") {
            onApproveChoice(dev.hermes.wearos.domain.ApprovalChoices.denyName(appr.choices))
        }
    }
}

/** One round ✅/❌ action riding a BottomLayout spot's CENTER at 1.45× the spot radius — big
 *  enough for a thumb at the rim, centered so the geometry stays BottomLayout-owned. */
@Composable
private fun ApprovalFlank(
    spot: BottomLayout.Spot,
    bg: Color,
    label: String,
    onClick: () -> Unit,
) {
    val r = spot.r * 1.45f
    Box(
        modifier = Modifier
            .size(with(LocalDensity.current) { (r * 2f).toDp() })
            .offset {
                IntOffset((spot.cx - r).roundToInt(), (spot.cy - r).roundToInt())
            }
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.title3,
            fontSize = 26.sp,
        )
    }
}

/** Offset a child so it EXACTLY covers a BottomLayout circle (top-left = center − r, in px). */
private fun Modifier.onSpot(s: BottomLayout.Spot): Modifier = offset {
    IntOffset((s.cx - s.r).roundToInt(), (s.cy - s.r).roundToInt())
}

/**
 * Consumption-agnostic double-tap (transcript font-scale reset). The SLC tap-to-center consumes
 * single taps inside the transcript, which kills foundation `detectTapGestures` (it aborts on a
 * consumed stream). This raw loop reads only raw up-events + system timings — it never consumes,
 * so scrolling and message clicks are untouched. Two ups, same place (< doubleTapSlop), within
 * doubleTapTimeout → onDoubleTap.
 */
private fun Modifier.rawDoubleTap(onDoubleTap: () -> Unit): Modifier = pointerInput(Unit) {
    var lastUpTime = 0L
    var lastUpX = Float.NaN
    var lastUpY = Float.NaN
    val dtMs = viewConfiguration.doubleTapTimeoutMillis
    val slop = 28.dp.toPx() // generous: reset is a deliberate gesture (PointerInputScope: Density)
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var settled = false
        while (!settled) {
            val ev = awaitPointerEvent()
            val up = ev.changes.firstOrNull { !it.pressed && it.previousPressed }
            if (up != null) {
                settled = true
                val now = System.nanoTime() / 1_000_000L
                val x = up.position.x
                val y = up.position.y
                val dx = x - lastUpX
                val dy = y - lastUpY
                if (lastUpX.isFinite() && now - lastUpTime <= dtMs &&
                    kotlin.math.sqrt(dx * dx + dy * dy) <= slop
                ) {
                    lastUpX = Float.NaN // no triple-tap repeat
                    onDoubleTap()
                } else {
                    lastUpTime = now; lastUpX = x; lastUpY = y
                }
            }
        }
    }
}

/**
 * Dev proof card for `-e viewer_demo`: 720×720 card — border + corner dots (the round viewport
 * CLIPS these at fit) + asymmetric blocks so a BELOW-FIT zoom + pan is unmistakable in a
 * screenshot. android.graphics only (the Compose ui-graphics `applyCanvas` extension is not in
 * this Android artifact set).
 */
internal fun devMediaTestCard(): ImageBitmap {
    val side = 720
    val bmp = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    fun paint(hex: String, stroke: Float = 0f) = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor(hex)
        isAntiAlias = true
        if (stroke > 0f) { style = android.graphics.Paint.Style.STROKE; strokeWidth = stroke }
    }
    canvas.drawColor(android.graphics.Color.parseColor("#0D1117"))
    canvas.drawRect(24f, 448f, 424f, 672f, paint("#E57373")) // big BL block — asymmetric on purpose
    canvas.drawRect(456f, 24f, 696f, 224f, paint("#81C784")) // small TR block
    canvas.drawRect(240f, 240f, 480f, 480f, paint("#64B5F6")) // center block
    canvas.drawRect(5f, 5f, side - 5f, side - 5f, paint("#FFFFFF", 10f)) // the clipped edge border
    listOf(
        Triple(40f, 40f, "#FFD54F"), Triple(680f, 40f, "#BA68C8"),
        Triple(40f, 680f, "#4DD0E1"), Triple(680f, 680f, "#FF8A65"),
    ).forEach { (x, y, c) -> canvas.drawCircle(x, y, 34f, paint(c)) } // corner dots = clip proof
    return bmp.asImageBitmap()
}

// Splash-spirit machinery (see HermesScaffold): process-wide one-shot, clock off the main thread.
private val SpiritClaimed = java.util.concurrent.atomic.AtomicBoolean(false)

// THE FILM'S ONE-SHOT (domain/SplashIntro answers WHY; this answers HOW ONCE): claimed on the
// first composition of the PROCESS, so a warm start out of Recents never replays 14 s of film.
private val IntroClaimed = java.util.concurrent.atomic.AtomicBoolean(false)

/**
 * Only "show me the app" launches get the film (domain/SplashIntro.launcherShaped has the FULL
 * story, incl. the One UI Watch fingerprint: MAIN with NO category). The harness keeps itself
 * splash-free the same way it always did — `am start -n` is a bare component start (no action) —
 * plus an explicit `--ez dev_no_intro 1` whenever a caller wants silence regardless of shape
 * (docs/DEMO.md, scripts/verify-chat.sh).
 */
private fun Intent.isLauncherLaunch(): Boolean = SplashIntro.launcherShaped(
    action = action,
    hasLauncherCategory = hasCategory(Intent.CATEGORY_LAUNCHER),
    devNoIntro = getBooleanExtra("dev_no_intro", false),
)
internal val spiritVisible = androidx.compose.runtime.mutableStateOf(false)
private fun spiritBoom() {
    if (!SpiritClaimed.getAndSet(true)) {
        spiritVisible.value = true
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            delay(450)
            spiritVisible.value = false
        }
    }
}

@Composable
private fun HermesScaffold(
    intro: Boolean? = null,
    introKind: SplashIntro.Choice = SplashIntro.Choice.VIDEO,
    introSource: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // PINCH FONT-SCALE: the persisted transcript type multiplier is PUBLISHED here, once, over
    // every screen (MdText + message styles consume via LocalFontScale). Cards never read it.
    val fontScale by WearApp.Graph.settings.fontScale.collectAsState()
    CompositionLocalProvider(LocalFontScale provides fontScale) {
        // SPLASH SPIRIT (owner 2026-10-26): her self-portrait — engraved by Comfy in the Hermes
        // art style — flashes at launch, one quick "boom," then dissolves into the chat. The show
        // is claimed ONCE per process (AtomicBoolean) and timed on a Default-dispatcher scope, so
        // composition churn during warm-up can neither re-trigger nor strand it; the previous
        // remember+LaunchedEffect version restarted its clock on every recomposition and the
        // remember+LaunchedEffect version restarted its clock on every recomposition and the
        // portrait hung on screen for seconds. The system splash (windowSplashScreenAnimatedIcon)
        // already shows her face-circle while the process starts — this overlay owns the
        // full-bleed flash and the hand-off to the chat, and nothing after that.
        //
        // THE FILM (ui/IntroVideo) REPLACES this flash when it plays — same trigger, first draw of
        // a fresh process — so the two never stack. Switch off, cut by a tap, or skipped by the
        // gate, and the launch is exactly the one that shipped before it.
        //
        // `intro` stays NULL until the gate answers, which is a beat AFTER first composition (it
        // waits, bounded, for the owner's Display switch — SplashIntro.SETTING_BUDGET_MS). So the
        // curtain goes up on the FIRST FRAME regardless of the answer, and the answer only ever
        // takes it down. Two measured reasons: waiting to be told yes before painting anything
        // leaked a frame of chat between the system splash and the film (SM-L705U), and an
        // `introShown` seeded from `intro` latched that null and never woke up at all (emulator —
        // the film was decided, claimed, and then quietly never painted).
        var curtainUp by remember { mutableStateOf(true) }
        LaunchedEffect(intro) { if (intro == false) spiritBoom() } // off/dev launch: the pre-film flash
        val spiritA by animateFloatAsState(
            if (spiritVisible.value) 1f else 0f,
            androidx.compose.animation.core.tween(220), label = "spirit",
        )
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                timeText = { TimeText() },
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    content = content,
                )
            }
            if (spiritA > 0.004f) {
                Image(
                    painter = painterResource(R.drawable.splash_spirit),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(spiritA),
                )
            }
            // THE FILM, topmost: it covers the transcript it is warming up behind, and any touch on
            // it is a skip (the overlay consumes that touch). Until the gate answers this is the
            // POSTER only — her face, the same image the system splash just showed — so the launch
            // reads face→face→film instead of face→a frame of chat→film. `intro == false` (owner
            // switch off, warm start, am start, notification tap) takes the exit fade, and because
            // spiritBoom fires on that same beat with the same artwork, it reads as one dissolve.
            AnimatedVisibility(
                visible = intro != false && curtainUp,
                enter = fadeIn(androidx.compose.animation.core.tween(120)),
                exit = fadeOut(androidx.compose.animation.core.tween(SplashIntro.FADE_MS)),
            ) {
                IntroVideo(
                    onDone = { curtainUp = false }, playing = intro,
                    kind = introKind, sourceUri = introSource,
                )
            }
        }
    }
}
