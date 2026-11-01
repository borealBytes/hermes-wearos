package dev.hermes.wearos

import android.app.Application
import android.os.Bundle
import android.util.Log
import dev.hermes.wearos.data.AgentsRepo
import dev.hermes.wearos.data.AppSettings
import dev.hermes.wearos.data.ChatStore
import dev.hermes.wearos.domain.AwayWatch
import dev.hermes.wearos.domain.SweepCursor
import dev.hermes.wearos.domain.ChatSession
import dev.hermes.wearos.domain.ProfileLogic
import dev.hermes.wearos.health.BodySnapshot
import dev.hermes.wearos.health.BodyTools
import dev.hermes.wearos.location.LocationTool
import dev.hermes.wearos.notify.Attention
import dev.hermes.wearos.notify.AwayWatchWorker
import dev.hermes.wearos.notify.Notifs
import dev.hermes.wearos.net.HermesApi
import dev.hermes.wearos.net.EndpointResolver
import dev.hermes.wearos.net.NetworkMonitor
import dev.hermes.wearos.net.SnapshotPusher
import dev.hermes.wearos.net.Watchkit
import dev.hermes.wearos.voice.SpeakAloud
import dev.hermes.wearos.domain.SttProvider
import dev.hermes.wearos.domain.SttProviders
import dev.hermes.wearos.voice.GatewayStt
import dev.hermes.wearos.voice.SttSource
import dev.hermes.wearos.voice.SystemStt
import dev.hermes.wearos.voice.VoskStt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

class WearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }

    /** App-wide singletons; M5: foreground gate for notifications + attention/complication push. */
    object Graph {
        private const val TAG = "HermesWear"
        private val scope = CoroutineScope(SupervisorJob())
        lateinit var app: Application
            private set
        lateinit var settings: AppSettings
            private set
        lateinit var store: ChatStore
            private set
        lateinit var session: ChatSession
            private set
        lateinit var speak: SpeakAloud
            private set
        lateinit var pusher: SnapshotPusher
            private set
        lateinit var stt: VoskStt
            private set
        /** Batch-4 #8: the SYSTEM recognizer behind the same SttSource seam (picker switch). */
        lateinit var sttSystem: SystemStt
            private set
        /** Batch-4 #9: streamed-mic STT on the gateway engine, with the local auto-fallback. */
        lateinit var sttGateway: GatewayStt
            private set

        /** Which engine the mic flow gets NOW (batch-4 #8/#9 picker; offline-safe default).
         *  GATEWAY needs no availability gate — voice/GatewayStt falls back locally by design. */
        fun sttNow(): SttSource = when (SttProviders.fromId(SttProviders.current)) {
            SttProvider.SYSTEM -> if (SystemStt.isAvailable(app)) sttSystem else stt
            SttProvider.GATEWAY -> sttGateway
            SttProvider.LOCAL -> stt
        }
        lateinit var agents: AgentsRepo
            private set

        /** Gateway endpoint failover (SPEC §11): winner tracking + probe triggers. */
        lateinit var endpoints: EndpointResolver
            private set
        fun hasEndpoints(): Boolean = ::endpoints.isInitialized

        /** The default route's link truth + the change hook that re-picks [endpoints] (SPEC §11.6). */
        lateinit var network: NetworkMonitor
            private set

        /** Label of the selected gateway (agent-dialog header + Settings badge). */
        private val _gatewayLabel = MutableStateFlow("")
        val gatewayLabel: kotlinx.coroutines.flow.StateFlow<String> = _gatewayLabel

        /** Gateway list mirror for the Settings UI (re-read after every registry mutation). */
        private val _gatewayList = MutableStateFlow<List<dev.hermes.wearos.domain.Gateway>>(emptyList())
        val gatewayList: kotlinx.coroutines.flow.StateFlow<List<dev.hermes.wearos.domain.Gateway>> = _gatewayList
        private val _gatewaySelectedId = MutableStateFlow("")
        val gatewaySelectedId: kotlinx.coroutines.flow.StateFlow<String> = _gatewaySelectedId

        private suspend fun refreshGatewayMirror() {
            _gatewayList.value = settings.gateways()
            _gatewaySelectedId.value = settings.selectedGatewayId()
            _gatewayLabel.value = settings.selectedGateway()?.label ?: ""
        }

        /** Re-read the UI mirrors after Settings edited base/token directly (they fold into the list). */
        fun refreshMirrorFromSettings() {
            if (!::settings.isInitialized) return
            scope.launch { refreshGatewayMirror() }
        }

        /** Dev hook (`-e ptt_demo "text"`): forces the PTT overlay with a fake partial for shots. */
        val devPtt = MutableStateFlow<String?>(null)

        /**
         * Dev hook (`-e fontscale <v>`): persist + apply the pinch font-scale over adb — the
         * screenshot path for d2/d5 (a two-finger pinch is not reachable via adb on the watch).
         * Non-numeric values reset to 1.0 (same as the double-tap transcript reset).
         */
        fun devSetFontScale(v: String) {
            scope.launch { settings.setFontScale(v.toFloatOrNull() ?: dev.hermes.wearos.domain.FontScale.DEFAULT) }
        }

        /**
         * Dev hook (`-e viewer_demo [scale]`): opens the MEDIA VIEWER on a generated test card —
         * the sub-fit zoom-out proof (d3) over adb; the optional float opens it BELOW fit directly
         * (single-pointer adb input can't pinch). null = closed (tap / ✕ closes it like the real
         * viewer).
         */
        val devViewerScale = MutableStateFlow<Float?>(null)

        /**
         * Dev hook `-e dial_preview`: pins the DIAL RING open (armed, no finger) so a screenshot
         * can show the 8 slots — `adb input swipe` injects one interpolated MOVE at stroke END,
         * so an adb-driven drag never keeps the ring up long enough to capture (a real finger
         * streams continuously). true = ring visible; the cluster auto-clears it after ~8 s.
         */
        val devDialPreview = MutableStateFlow(false)

        /**
         * Dev hook `-e model_picker`: opens the 🧠 MODEL PICKER on the active chat — same reason
         * dial_preview exists: a synthetic dial release is flaky to script, and the picker needs
         * gateway round-trips a screenshot pass wants to wait on.
         */
        val devModelPicker = MutableStateFlow(false)

        /** Dev hook `-e agents 1`: open the agent picker like the agents lane does (batch-4 #6
         *  dialog-padding proof without hunting the chip under SLC centering). */
        /**
         * BATCH-6 #23: the live SPLASH HOLD (domain/SplashIntro) — non-null only while a
         * show-shaped cold launch is holding the system splash; the intro gate publishes the
         * verdict here, IntroVideo publishes `painted`, the keep-condition reads it. Null
         * everywhere else, so every other launch pays one field read.
         */
        @Volatile var launchHold: dev.hermes.wearos.domain.SplashIntro.LaunchHold? = null
        val devAgents = MutableStateFlow(false)

        /** Dev hook `-e approvals`: opens the per-chat approvals sheet like the ✅ lane does. */
        val devApprovals = MutableStateFlow(false)

        /** Dev hook `-e orb_slot "3|approvals"`: remap one dial direction (lane picker driver). */
        fun devOrbSlot(spec: String) = scope.launch {
            val bits = spec.split('|').map { it.trim() }
            val slot = bits.getOrNull(0)?.toIntOrNull()
            val lane = bits.getOrNull(1)?.let { dev.hermes.wearos.domain.Lane.fromId(it) }
            if (slot == null || lane == null) { Log.i(TAG, "orb_slot BAD spec '$spec'"); return@launch }
            settings.setOrbSlot(slot, lane)
            Log.i(TAG, "orb_slot $slot -> ${lane.id}")
        }

        /** Dev hook `-e search <q>`: types a query into the Sessions search the way a thumb
         *  would (adb `input text` can't traverse the Wear IME), then one-shot clears itself. */
        val devSearchQuery = MutableStateFlow<String?>(null)

        /** Dev hook `-e open_chat <id>`: opens that chat AND lands the UI on it (the screen
         *  switch is what `-e open_chat` alone never did — the catch-up proof needs it visible). */
        val devOpenChat = MutableStateFlow<String?>(null)

        /** Dev hook `-e screen <chat|chats|settings|sessions>`: jump the UI to a screen. The ⌘
         *  overlay popup swallows injected touches (adb taps never focus its search field), so
         *  demo drives need this to reach Settings/Chats deterministically. */
        val devScreen = MutableStateFlow<String?>(null)

        /**
         * Dev hook (`-e open_editor "text"`): opens the prompt editor prefilled — the system
         * keyboard proof for owner batch 2 (works over adb on the physical watch, no tap needed).
         * MainActivity consumes (nulls it back after opening).
         */
        val devEditor = MutableStateFlow<String?>(null)

        /**
         * DEV RENDER HARNESS (SPEC §13.3): `-e render <case>` → the case id, consumed by the chat
         * screen, which injects the canned answer (`RenderCase`) straight into the transcript.
         * Proves the 5-column grid / viewer / mermaid / PDF / link surfaces over adb + screencap
         * without asking a model five times. `-e render list` echoes the ids in logcat.
         */
        val devRender = MutableStateFlow<String?>(null)

        /**
         * MAIN-THREAD GATE for every UI callback (walkthrough crash, 2a32c65):
         * `java.lang.NullPointerException: Can't toast on a thread that has not called
         * Looper.prepare()` at MainActivity switchAgent toast — because Graph's scope is
         * `CoroutineScope(SupervisorJob())` with NO dispatcher, `scope.launch { … }` in
         * ChatSession (switchAgent / refreshGatewaySessions / resumeGatewaySession …) runs on
         * Dispatchers.Default, and callbacks fired from there land on a worker thread where
         * Toast.show() is fatal. REGRESSION GUARD: any callback handed to session/agents that
         * touches Toast/labels/navigation MUST wrap its body in `Graph.runOnUi { … }`.
         * (Audit 2026-10-24: switchAgent.onDone is the only UI-touching callback — the other
         * callback consumers are Log.i (thread-safe) and goAsync.finish (thread-safe).)
         */
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        fun runOnUi(block: () -> Unit) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
            else mainHandler.post(block)
        }

        /**
         * True while any activity is RESUMED (ActivityLifecycleCallbacks counter). Used to gate
         * notifications: a settled answer or approval request that the user can already see on
         * screen must not buzz. tile/complication/receiver processes read it as false — correct,
         * nothing is visible there.
         */
        private val _isForeground = androidx.compose.runtime.mutableStateOf(false)
        var isForeground: Boolean
            get() = _isForeground.value
            private set(value) { _isForeground.value = value }

        /**
         * What the wrist is actually looking at (owner 2026-10-29 notification rule): a run's
         * event suppresses the system notification ONLY while ITS chat is the front chat screen.
         * Another chat open, Sessions/Settings/Chats screens, the watch face, another app, or
         * screen-off all route to standard Wear OS notifications (approve/open actions wired).
         * Published by MainActivity; empty whenever nothing chat-shaped is on screen.
         */
        private val _viewingChatId = androidx.compose.runtime.mutableStateOf("")
        var viewingChatId: String
            get() = _viewingChatId.value
            set(value) { _viewingChatId.value = value }
        private var resumedActivities = 0

        /** PAUSE-CLEANED (owner 2026-10-29 night: *"wait thirty seconds, then send a haiku and
         *  immediately went to the watchface while it was thinking. i did not get a notification"*
         *  — the run settled while the wrist was away and the gate suppressed it): PAUSE, not
         *  STOP, is the moment the chat stops being visible on a watch. A dropped wrist / screen
         *  off pauses the activity but may not stop it for minutes (Samsung ambient), and HOME can
         *  keep the task resumed under the launcher. So the instant anything pauses, the chat is
         *  no longer watched; onActivityResumed puts it back (any surface re-publishes its own
         *  value via MainActivity's effect the moment it's composed). */
        private fun clearViewing() { viewingChatId = "" }

        fun init(app: Application) {
            if (!::settings.isInitialized) {
                this.app = app
                Notifs.ensureChannels(app)
                app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(activity: android.app.Activity) {
                        resumedActivities++
                        isForeground = true
                        // The 20 s loop owns the sweep again; the work chain stands down
                        // mid-handoff — the shared cursor makes overlap harmless anyway.
                        AwayWatchWorker.cancel(app)
                    }

                    override fun onActivityStopped(activity: android.app.Activity) {
                        resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
                        isForeground = resumedActivities > 0
                        clearViewing()
                        // #32: the pocket case. When the last activity stops, a cached watch app
                        // loses its coroutines to the OEM freezer — hand the desktop-watch sweep
                        // to WorkManager, which the system wakes even for frozen apps.
                        if (resumedActivities == 0) AwayWatchWorker.schedule(app)
                    }

                    override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: Bundle?) {}
                    override fun onActivityStarted(activity: android.app.Activity) {}
                    override fun onActivityPaused(activity: android.app.Activity) { clearViewing() }
                    override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: Bundle) {}
                    override fun onActivityDestroyed(activity: android.app.Activity) {}
                })
                settings = AppSettings(app)
                store = ChatStore(settings, app, scope + SupervisorJob())
                session = ChatSession(settings, store, scope + SupervisorJob(), app)
                // W8 body tools: Health Services probe/collection + watchkit snapshot cadence.
                BodyTools.init(app, scope + SupervisorJob())
                pusher = SnapshotPusher(settings, scope + SupervisorJob(), app)
                pusher.start()
                // M2 voice: TTS must be created on the main thread (Application.onCreate is main).
                speak = SpeakAloud(app, settings, scope + SupervisorJob())
                speak.init()
                scope.launch { speak.loadEnabled() }
                // Voice-out config (owner 2026-10-29): engine voice + reply speed. Load AFTER
                // init posts (callback lands first on this looper) — loadVoiceConfig re-applies
                // anyway, so order only affects a few-ms window on cold start.
                scope.launch { speak.loadVoiceConfig() }
                scope.launch { settings.loadMarkdownRendering() } // Display ▸ markdown (F3 default ON)
                scope.launch { settings.loadIntroVideo() } // Display ▸ launch video (SplashIntro gate)
                scope.launch { settings.loadFontScale() } // pinch font-scale (Layout D epic)
                scope.launch { settings.loadOrbSlots() } // the dial map (SPEC §12.1) — chat reads the mirror
                scope.launch { settings.loadAgentKeys() } // per-agent gateway keys (SPEC §12.2)
                scope.launch { settings.loadCommandUsage() } // slash-overlay recents (SPEC §12.3)
                scope.launch { settings.loadSlashCatalog() } // pushed command catalog, if any
                scope.launch { settings.loadBodyTools() } // W1-W4: body switches (all default OFF)
                scope.launch { Watchkit.setConfig(settings.mermaidBaseUrl(), settings.watchkitToken()) } // F4/watchkit
                agents = AgentsRepo(settings)
                // GATEWAY SYSTEM (owner epic, SPEC §11): registry seed/migration from the legacy
                // keys, then endpoint failover for the selected gateway. The winner callback
                // rewrites the legacy mirror keys + Watchkit config so every existing consumer
                // (ChatSession/pusher/sessions/agents) rides the winner with zero changes.
                endpoints = EndpointResolver(settings) { gw, api, wk ->
                    settings.syncMirror(api, wk, gw.token)
                    Watchkit.setConfig(wk.ifEmpty { AppSettings.DEFAULT_WATCHKIT_URL }, settings.watchkitToken())
                }
                // ROUTE FOLLOWED THE LINK (SPEC §11.6): without this, the app discovered a network
                // change by failing one ask — a winner learned on home Wi-Fi stayed "fresh" for
                // minutes while the watch was already somewhere that address means nothing.
                // The monitor owns the ConnectivityManager callback (application scope, never an
                // Activity: the change we care about happens in the pocket, screen off).
                network = NetworkMonitor(app, onChange = { reason ->
                    val api = endpoints.onNetworkChanged(reason)
                    Log.i(TAG, "route after network change: ${api.ifEmpty { "none — nothing answered" }}")
                    // Refresh what visibly lies when a route goes stale: the agent list and the
                    // gateway's session list (both were the reason a stale endpoint felt like a bug).
                    runCatching { agents.refresh() }
                    runCatching { session.refreshGatewaySessions() }
                    runCatching { refreshGatewayMirror() }
                })
                // DESKTOP WATCH (batch-6 #25, owner: "notifications or approvals from sessions
                // started in the desktop … the ONLY time I don't get one is the session that is
                // actively the top thing on my watch"). Desk runs have no watch-side stream, so
                // the wrist POLLS watchkit's inbox (approval parks via the gateway's activity
                // beacon + session pulses from the state stores) and domain/AwayWatch.diff turns
                // sweeps into buzzes. Silence set: the chat top-of-screen on the chat page (the
                // owner's ONE allowed silence) and chats whose runs the watch itself drives —
                // those already notify approvals/settles through the watch-run paths.
                scope.launch {
                    while (true) {
                        delay(AwayWatch.POLL_MS) // first tick also lets the endpoint config land
                        awaySweep()
                    }
                }
                // Queued AFTER Watchkit.setConfig so the first /agents fetch carries the real token.
                scope.launch {
                    val gw = settings.ensureGateways() // one-time seed/migrate (never loses old keys)
                    settings.loadProfile()
                    if (gw != null) { endpoints.bind(gw); endpoints.ensure(false) } // background probe: tailnet-first
                    refreshGatewayMirror()
                    agents.loadCache()
                    agents.refresh()
                }
                stt = VoskStt(app)
                sttSystem = SystemStt(app)
                sttGateway = GatewayStt(app, stt)
                scope.launch { settings.loadSttProvider(); settings.loadSttGatewayEngine() } // seed the #8 picker mirror + #16 engine pick
                session.resume() // loads registry (migrates legacy keys), restores active chat + in-flight run
                network.start() // LAST: its hook reaches into every other Graph member
                // Attention bit → complication prefs + requestUpdateAll (single hook, SPEC §5 U4).
                scope.launch { store.chats.collect { chats -> Attention.publish(app, chats.any { it.pendingAttention }) } }
            }
        }

        /**
         * ONE away-watch sweep: fetch watchkit's inbox and let the SHARED cursor (#32) decide
         * what buzzes. Called by the in-app 20 s loop AND by AwayWatchWorker when the process is
         * cached (Samsung freezes/stops cached apps — a bare coroutine dies the moment
         * onActivityStopped strands it, wrist-verified 2026-11-01: polls stopped within a minute
         * of HOME once nothing kept the process warm). Same cursor both ways ⇒ zero dupes.
         */
        suspend fun awaySweep() {
            if (!::store.isInitialized) return
            val body = Watchkit.approvalsWaiting() ?: return
            // #31 follow-up: keys MUST be gateway session ids (AwayWatch.watchSilence)
            // — local shortIds never matched watchkit's session-keyed pulses, so the
            // viewing chat buzzed while it was live on the glass.
            val mine = AwayWatch.watchSilence(store.chats.value, isForeground, viewingChatId)
            for (a in awayCursor.sweep(AwayWatch.parse(body), mine)) {
                when (a.kind) {
                    AwayWatch.Kind.Approval -> {
                        Log.i(TAG, "away-watch: approval parked ${a.profile}/${a.chatId} ${a.tool}")
                        Notifs.approvalRemote(app, a.chatId, a.title, a.tool, a.arguments)
                    }
                    AwayWatch.Kind.Finished -> {
                        Log.i(TAG, "away-watch: answer finished ${a.profile}/${a.chatId} msgs=${a.messageCount}")
                        Notifs.settle(app, a.chatId, a.title, "Answer ready on the desktop \u00b7 open to read", false)
                    }
                }
            }
        }

        private val awayCursor = SweepCursor()

        /** Chat opened/foregrounded: clear its flag + notifications (M5 U1/U4 "cleared on open"). */        fun clearAttention(chatId: String) {
            if (chatId.isEmpty() || !::store.isInitialized) return
            store.get(chatId)?.let { meta ->
                if (meta.pendingAttention) {
                    android.util.Log.i("HermesWear", "attention cleared chat=$chatId")
                    store.update(chatId) { it.copy(pendingAttention = false) }
                }
                Notifs.cancelChat(app, chatId, meta.lastRunId)
            }
        }

        /** Dev-only (adb): store the gateway token before the pairing flow ships (S5). */
        fun setToken(token: String) {
            scope.launch { settings.setToken(token) }
        }

        /** Dev-only (adb): point the app at a LAN endpoint (🏠 path) before mDNS discovery ships. */
        fun setBase(url: String) {
            scope.launch { settings.setBaseUrl(url) }
        }

        // ─── GATEWAY OPS (SPEC §11) ────────────────────────────────────────────────────────
        // Row tap = SELECT: re-probe the new gateway's endpoint list (force), reload chats for
        // the current agent through the SAME switchAgent plumbing (its onDone lands on a worker
        // thread — toasting callers must use Graph.runOnUi, the 2a32c65 crash gate).

        fun selectGateway(id: String, onDone: (ok: Boolean, label: String, error: String) -> Unit = { _, _, _ -> }) {
            scope.launch {
                val gw = settings.gateways().firstOrNull { it.id == id }
                if (gw == null) { onDone(false, "", "no gateway '$id'"); return@launch }
                settings.setSelectedGatewayId(id)
                endpoints.rebind(gw)
                endpoints.ensure(force = true)          // re-probes tailnet/LAN order for THIS gateway
                settings.syncMirror(endpoints.currentApi(), endpoints.currentWatchkit(), gw.token)
                settings.loadProfile()                  // rebuild /p/ segment on the fresh mirror
                _gatewayLabel.value = gw.label
                session.switchAgent(settings.savedAgent()) { ok, base, err ->
                    Log.i(TAG, "gateway selected id=$id label='${gw.label}' ok=$ok base=$base err=$err")
                }
                agents.refresh()
                session.refreshGatewaySessions()
                refreshGatewayMirror()
                onDone(true, gw.label, "")
            }
        }

        /** Upsert from the Settings editor; re-probes immediately when the EDITED gateway is selected. */
        fun saveGateway(gw: dev.hermes.wearos.domain.Gateway, onDone: (error: String) -> Unit = {}) {
            scope.launch {
                try {
                    val wasSelected = settings.selectedGatewayId() == gw.id
                    settings.saveGateway(gw, select = gw.id == settings.selectedGatewayId())
                    val sel = settings.selectedGateway()
                    if (wasSelected && sel != null) {
                        endpoints.rebind(sel)
                        endpoints.ensure(force = true)
                        settings.syncMirror(endpoints.currentApi(), endpoints.currentWatchkit(), sel.token)
                        settings.loadProfile()
                    }
                    refreshGatewayMirror()
                    agents.refresh()
                    Log.i(TAG, "gateway saved id=${gw.id} label='${gw.label}' api=${gw.apiEndpoints}")
                    onDone("")
                } catch (e: Exception) {
                    Log.w(TAG, "gateway save failed: ${e.message}")
                    onDone(e.message ?: "save failed")
                }
            }
        }

        fun deleteGateway(id: String, onDone: (ok: Boolean) -> Unit = {}) {
            scope.launch {
                val sel = settings.deleteGateway(id)
                if (sel != null) {
                    endpoints.rebind(sel)
                    endpoints.ensure(force = true)
                    settings.syncMirror(endpoints.currentApi(), endpoints.currentWatchkit(), sel.token)
                    settings.loadProfile()
                }
                refreshGatewayMirror()
                agents.refresh()
                Log.i(TAG, "gateway deleted id=$id remaining=${_gatewayList.value.size}")
                onDone(sel != null || _gatewayList.value.isEmpty())
            }
        }

        /** Dev-only (adb `-e gw_dump`): registry + winner state in logcat. */
        fun devGwDump() {
            scope.launch {
                val list = settings.gateways()
                Log.i(TAG, "gw_dump selected=${settings.selectedGatewayId()} n=${list.size}")
                list.forEach {
                    Log.i(TAG, "gw_dump gateway id=${it.id} label='${it.label}' api=${it.apiEndpoints} wk=${it.watchkitEndpoints} token.len=${it.token.length}")
                }
                if (hasEndpoints()) {
                    val s = endpoints.status.value
                    Log.i(TAG, "gw_dump winner api=${s.apiWinner} wk=${s.watchkitWinner} results=${s.apiResults}")
                }
                Log.i(TAG, "gw_dump mirror base=${settings.baseUrl()} wk=${settings.mermaidBaseUrl()} token.len=${settings.token().length}")
            }
        }

        /** Dev-only (adb `-e gw_select <id>`): select + forced re-probe (screenshot-free proof). */
        fun devGwSelect(id: String) = selectGateway(id) { ok, label, err ->
            Log.i(TAG, "gw_select ok=$ok id=$id label='$label' err=$err")
        }

        /** Dev-only (adb `-e del_gw <id>`): delete a gateway, then land the selection where the
         *  registry points next and re-probe it — the deterministic cleanup for multi-gateway demo
         *  shots (§11) (and the only way to clear a gateway the watch cannot route to without
         *  wrestling the IME). */
        fun devGwDelete(id: String) = scope.launch {
            val remaining = settings.deleteGateway(id)
            if (remaining != null) {
                endpoints.rebind(remaining)
                endpoints.ensure(force = true)
                settings.syncMirror(endpoints.currentApi(), endpoints.currentWatchkit(), remaining.token)
                settings.loadProfile()
                _gatewayLabel.value = remaining.label
                session.switchAgent(settings.savedAgent()) { ok, base, err ->
                    Log.i(TAG, "gateway after delete id=${remaining.id} ok=$ok base=$base err=$err")
                }
            }
            Log.i(TAG, "del_gw id=$id -> selected ${remaining?.id ?: "(none)"}")
        }

        /** Dev-only (adb `-e add_gw "label|api|watchkit"`): upsert a gateway carrying the current
         *  token + per-agent keys so a second endpoint is immediately usable (demo shots, §11). */
        fun devAddGateway(spec: String) = scope.launch {
            val bits = spec.split('|').map { it.trim() }
            if (bits.size < 3) { Log.i(TAG, "add_gw BAD spec '$spec'"); return@launch }
            val base = settings.gateways().firstOrNull()
            val gw = dev.hermes.wearos.domain.Gateway(
                id = bits[0].lowercase().replace(Regex("[^a-z0-9]"), ""),
                label = bits[0],
                apiEndpoints = listOf(bits[1]),
                watchkitEndpoints = listOf(bits[2]),
                token = settings.token(),
                agentKeys = base?.agentKeys.orEmpty(),
            )
            settings.saveGateway(gw, select = false)
            Log.i(TAG, "add_gw ok '${gw.label}' api=${bits[1]}")
        }

        /** Dev-only (adb `-e gw_probe`): force the failover probe round; winner goes to logcat. */
        fun devGwProbe() {
            scope.launch {
                val api = endpoints.ensure(force = true)
                Log.i(TAG, "gw_probe done api=$api winner=${endpoints.status.value.apiWinner} wk=${endpoints.status.value.watchkitWinner}")
            }
        }

        fun setWatchkitBase(url: String) {
            scope.launch {
                settings.setMermaidBaseUrl(url)
                // Mirror-only writes left the gateway record's wk list frozen (SPEC §11): a URL
                // added via dev hook or Settings never became a failover candidate. Fold it in.
                settings.foldWatchkitIntoSelected(url)
            }
        }

        /** Dev-only (adb `-e set_watchkit`): store watchkit token + push config so renders retry. */
        fun setWatchkitToken(token: String) {
            scope.launch {
                settings.setWatchkitToken(token)
                Watchkit.setConfig(settings.mermaidBaseUrl(), token)
                android.util.Log.i("HermesWear", "watchkit token set (len=${token.length}) url=${settings.mermaidBaseUrl()}")
            }
        }

        /** Dev-only (adb `-e render_test`): one watchkit render round-trip, result in logcat. */
        fun devRenderTest() {
            Thread {
                try {
                    val bytes = kotlinx.coroutines.runBlocking { Watchkit.renderBytes("digraph{a->b}") }
                    android.util.Log.i("HermesWear", "render ok bytes=${bytes.size}")
                } catch (e: Exception) {
                    android.util.Log.e("HermesWear", "render err ${e.message}")
                }
            }.start()
        }

        /** Dev-only (adb `-e img_test`): one watchkit /img round-trip — FULL-RES path (no width). */
        fun devImgTest() {
            Thread {
                try {
                    val bytes = kotlinx.coroutines.runBlocking {
                        Watchkit.fetchImageBytes("/home/user/.hermes/wear-img-test-full.png", width = 0)
                    }
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw IllegalStateException("png decode failed")
                    android.util.Log.i("HermesWear", "img_test ok bytes=${bytes.size} px=${bmp.width}x${bmp.height} (full-res)")
                } catch (e: Exception) {
                    android.util.Log.e("HermesWear", "img_test err ${e.message}")
                }
            }.start()
        }

        /** Dev-only (adb `-e body_status`): Health Services probe; per-tool results in logcat. */
        fun devBodyStatus() {
            Thread {
                val st = kotlinx.coroutines.runBlocking { BodyTools.probe() }
                android.util.Log.i("HermesWear", "body_status ── health services ──")
                st.forEach { (k, v) -> android.util.Log.i("HermesWear", "body_status $k -> $v") }
                val snap = kotlinx.coroutines.runBlocking {
                    BodySnapshot.buildJson(
                        System.currentTimeMillis(), chatActive = false,
                        BodySnapshot.Toggles(true, true, true, true, true), BodyTools.values(),
                    )
                }
                android.util.Log.i("HermesWear", "body_status snapshot(if all on) $snap")
            }.start()
        }

        /** Dev-only (adb `-e body_push`): force one snapshot push to watchkit. */
        fun devBodyPush() = pusher.pushNow("adb")

        /** Dev-only (adb `-e agents_list`): refresh + log the agent list (switcher source of truth). */
        fun devAgentsList() {
            scope.launch {
                val list = agents.refresh()
                android.util.Log.i(
                    "HermesWear",
                    "agents_list n=${list.size} fallback=${agents.usingFallback()} current=${settings.profileSync()} list=$list",
                )
            }
        }

        /** Dev-only (adb `-e agent <name>`): switch gateway agent (base-URL /p/ swap + chat list). */
        fun devAgent(name: String) = session.switchAgent(name) { ok, base, err ->
            android.util.Log.i("HermesWear", "agent_switch ok=$ok name=$name base=$base err=$err")
        }

        /** Dev-only (adb `-e sessions_dump`): gateway session list + watch-ownership markers. */
        fun devSessionsDump() {
            Thread {
                try {
                    val base = kotlinx.coroutines.runBlocking { settings.baseUrl() }
                    val token = kotlinx.coroutines.runBlocking { settings.token() }
                    val list = devHttpSessions(base, token)
                    android.util.Log.i("HermesWear", "sessions_dump base=$base n=${list.size}")
                    list.forEach {
                        android.util.Log.i(
                            "HermesWear",
                            "sessions_dump id=${it.id} watch=${ProfileLogic.isWatchSession(it.id)} msgs=${it.messages} " +
                                "activeMs=${it.lastActiveMs} title='${it.title.take(60)}'",
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("HermesWear", "sessions_dump err ${e.message}")
                }
            }.start()
        }

        private fun devHttpSessions(base: String, token: String) =
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                HermesApi(base, token).listSessions()
            }

        /** Dev-only (adb `-e resume_session <id>`, optional `-e resume_profile <p>`): adopt a
         *  gateway session (M4 resume proof + history-backfill proof — profile mirrors the
         *  Sessions-screen row tag so the backfill probe targets the right state.db). */
        fun devResumeSession(id: String, profile: String = "") {
            android.util.Log.i("HermesWear", "resume_session id=$id profile=$profile")
            session.resumeGatewaySession(id, "", profile)
        }

        /** Dev-only (adb `-e stop_mid_run [text]`): ask, wait 1.5 s, STOP — logcat: stream ceased. */
        fun devStopMidRun(text: String) {
            Thread {
                try {
                    session.ask(text)
                    Thread.sleep(1_500)
                    session.stopActiveRun()
                    android.util.Log.i("HermesWear", "stop_test: stop fired at 1.5s (see 'stop:' lines above)")
                } catch (e: Exception) {
                    android.util.Log.e("HermesWear", "stop_test err ${e.message}")
                }
            }.start()
        }

        /** Dev-only (adb `-e loc_status`): location tool perms + providers + last-fix outcome. */
        fun devLocStatus() {
            Thread {
                val line = LocationTool.statusLine(app)
                android.util.Log.i("HermesWear", "loc_status ── $line")
                val fix = LocationTool.probe(app)
                android.util.Log.i(
                    "HermesWear",
                    "loc_status probe -> ${fix?.let { LocationTool.formatFix(it, System.currentTimeMillis()) } ?: "no fix"} status='${LocationTool.status.value}'",
                )
            }.start()
        }
    }
}
