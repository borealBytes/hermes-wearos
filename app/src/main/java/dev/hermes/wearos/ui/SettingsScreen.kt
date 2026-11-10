package dev.hermes.wearos.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Checkbox
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import dev.hermes.wearos.WearApp
import dev.hermes.wearos.data.AppSettings
import dev.hermes.wearos.domain.FontScale
import dev.hermes.wearos.domain.SttProvider
import dev.hermes.wearos.domain.SttProviders
import dev.hermes.wearos.domain.VoskInstallState
import dev.hermes.wearos.voice.SystemStt
import dev.hermes.wearos.domain.TtsConfig
import dev.hermes.wearos.domain.Lane
import dev.hermes.wearos.domain.OrbDial
import dev.hermes.wearos.domain.Gateway
import dev.hermes.wearos.domain.GatewayModel
import dev.hermes.wearos.domain.NetworkWatch
import dev.hermes.wearos.domain.PairingCode
import dev.hermes.wearos.domain.RouteReport
import dev.hermes.wearos.domain.TailnetLogic
import dev.hermes.wearos.domain.TailnetPhase
import dev.hermes.wearos.health.BodySnapshot
import dev.hermes.wearos.health.BodyTools
import dev.hermes.wearos.location.LocationTool
import dev.hermes.wearos.net.HermesApi
import dev.hermes.wearos.net.PairingClient
import dev.hermes.wearos.net.Watchkit
import dev.hermes.wearos.voice.SpeakAloud
import dev.hermes.wearos.voice.VoskStt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * On-device settings (SPEC §4): GATEWAYS (multi-gateway epic — registry rows, endpoint winner
 * dots, add/edit/delete), connection mirror (base URL + token + Test), voice-out master switch,
 * STT model + TTS engine status, app version. Everything saves immediately through AppSettings;
 * right edge-swipe returns to chat.
 *
 * IME RULE (owner epic): NO field ever depends on the Enter key — every single-line field is
 * singleLine + ImeAction.Done wired to the same action as its explicit Save/Send button, and
 * that button is always visible. Enter weirdness (space/newline instead of submit) can never
 * strand input again.
 */
@Composable
fun SettingsScreen(
    context: Context,
    settings: AppSettings,
    speak: SpeakAloud,
) {
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    // TAILNET page state (SPEC §11.4); the engine's live status rides the Graph mirror.
    var tnEnabled by remember { mutableStateOf(true) }
    var tnKey by remember { mutableStateOf("") }
    var tnHost by remember { mutableStateOf("") }
    var tnKeySaved by remember { mutableStateOf(false) }
    var tnHostSaved by remember { mutableStateOf(false) }
    var tnTesting by remember { mutableStateOf(false) }
    var tnTestResult by remember { mutableStateOf("") }
    var tnReenrollArmed by remember { mutableStateOf(false) }
    // PAIR page state (pairing v2, SPEC §4): the funnel handle, the CODE THE GATEWAY ISSUES
    // (we display it; we never mint it), the flow's status line, and pairAttempt++ as the knock
    // trigger. The 256-bit device secret lives in memory for the duration of one knock only.
    var pairHandle by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    var pairStatus by remember { mutableStateOf("type your tailnet handle, then knock") }
    var pairBusy by remember { mutableStateOf(false) }
    var pairAttempt by remember { mutableStateOf(0) }
    var pairUnpairArmed by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("…") }
    var testing by remember { mutableStateOf(false) }
    var wkResult by remember { mutableStateOf("") }
    var wkTesting by remember { mutableStateOf(false) }
    var gwEditor by remember { mutableStateOf<GwDraft?>(null) }
    // Agent-keys editor (SPEC §12.2): which agent's key is being typed, and the draft.
    var keyEdit by remember { mutableStateOf<String?>(null) }
    var keyDraft by remember { mutableStateOf("") }
    var gwNote by remember { mutableStateOf("") }

    // CUSTOM SPLASH MEDIA (owner 2026-10-29: *"let the user choose custom image/video for splash
    // screen useage from image/video on their watch"*). SAF picker; the pick is CLASSIFIED by the
    // resolver's MIME type — a video lands in the film slot, an image in the still slot — so each
    // mode keeps its own custom media and the chooser decides which one plays. Persistable read
    // grant or it is not stored at all: a pick that silently falls back to built-in every launch
    // is worse than an honest toast now.
    val splashPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val cr = context.contentResolver
            val granted = runCatching {
                cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            if (granted) {
                val isVideo = cr.getType(uri).orEmpty().startsWith("video")
                scope.launch { settings.setSplashCustom(isVideo, uri.toString()) }
                Toast.makeText(context, if (isVideo) "Custom film set" else "Custom still set",
                    Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "That app can't keep the share", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val voiceOut by speak.enabled.collectAsState()
    val ttsState by speak.state.collectAsState()
    // Voice-out config rows (owner 2026-10-29): flows mirror what SpeakAloud persists on pick.
    val ttsRate by settings.ttsRate.collectAsState()
    // PER-AGENT REPLY VOLUME (owner 2026-10-29): the slider edits the ACTIVE AGENT's override;
    // the flow re-seeds it whenever the agent or the level changes (dial/mute write the same map).
    val replyVolFor by settings.ttsVolumeFor.collectAsState()
    val agentNow by settings.profile.collectAsState()
    val ttsVoice by settings.ttsVoice.collectAsState()
    var voicesOpen by remember { mutableStateOf(false) }
    // Engine/local-network cascade pickers (owner 2026-10-29): engine pkg + filtered voice lists
    // for preferred AND fallback.
    var enginesOpen by remember { mutableStateOf(false) }
    var fbOpen by remember { mutableStateOf(false) }
    var voiceSrc by remember { mutableStateOf(dev.hermes.wearos.domain.VoiceCatalog.ANY) }
    var fbSrc by remember { mutableStateOf(dev.hermes.wearos.domain.VoiceCatalog.ANY) }
    val ttsEngine by settings.ttsEngine.collectAsState()
    // issue #7: voice-out source — system cascade vs gateway (Wear Kit cloned voices)
    val ttsSource by settings.ttsSource.collectAsState()
    var gwProbe by remember { mutableStateOf(0) }
    val ttsFallback by settings.ttsFallback.collectAsState()
    val markdownOn by settings.markdownEnabled.collectAsState()
    val bodyTools by settings.bodyTools.collectAsState()
    val bodyStatus by BodyTools.status.collectAsState()
    val pushState by WearApp.Graph.pusher.state.collectAsState()
    val gateways by WearApp.Graph.gatewayList.collectAsState()
    val selectedGwId by WearApp.Graph.gatewaySelectedId.collectAsState()
    // Settings tree (owner 2026-10-29: the flat screen became a "forever scroll"): root is a
    // hub of nav rows; every section lives on its own page. Sections below are UNCHANGED logic,
    // just wrapped in `if (page == …)` — same state, same prefs, same behavior.
    var page by remember { mutableStateOf(SettingsPage.Root) }
    androidx.activity.compose.BackHandler(enabled = page != SettingsPage.Root) { page = page.up() }
    // PAIR GATE (SPEC §4): first launch (empty registry) and engine edges into NEEDS_AUTH request
    // the Pair screen — this edge lands the page. Cleared on pickup (Graph.pairApplyPickup).
    val pairNow by WearApp.Graph.pairRequested.collectAsState()
    LaunchedEffect(pairNow) { if (pairNow) page = SettingsPage.Pair }
    // KNOCK the funnel ONCE: begin → show the gateway's code → poll with backoff → ack (client
    // pair() owns the whole loop; a 429 backs off, never burns the code). The device secret is
    // generated fresh per knock and never leaves this process except inside the POST/poll body.
    LaunchedEffect(pairAttempt) {
        if (pairAttempt == 0) return@LaunchedEffect
        val h = PairingCode.normalizeHandle(pairHandle)
        if (h.isEmpty()) { pairStatus = "⚠️ set your tailnet handle first"; return@LaunchedEffect }
        settings.setTailnetHandle(h)
        pairBusy = true
        pairCode = ""
        pairStatus = "knocking on $h.ts.net…"
        val client = PairingClient()
        val secret = client.newSecret()
        val out = withContext(Dispatchers.IO) {
            client.pair(h, secret, android.os.Build.MODEL,
                onCode = { code -> pairCode = code; pairStatus = "type $code on the PC — it approves there" })
        }
        pairBusy = false
        pairStatus = when (out) {
            is PairingClient.Outcome.Completed -> {
                WearApp.Graph.pairApplyPickup(out.packet, secret, out.deviceToken)
                "paired ✓ — keys land over the tunnel"
            }
            is PairingClient.Outcome.Refused -> "⚠️ ${out.why}"
            PairingClient.Outcome.ServerTooOld -> "⚠️ gateway plugin predates pairing v2 — update the plugin"
            PairingClient.Outcome.Gone -> "code gone — knock again"
            PairingClient.Outcome.Malformed -> "⚠️ the door answered unreadably — knock again"
            PairingClient.Outcome.RateLimited -> "too many tries (429) — knock again soon"
            PairingClient.Outcome.Pending -> "still waiting on the PC — knock again any time"
            is PairingClient.Outcome.Failed -> "⚠️ ${out.why}"
            else -> pairStatus
        }
    }
    val gwStatus by WearApp.Graph.endpoints.status.collectAsState()
    val netInfo by WearApp.Graph.network.net.collectAsState() // the link truth (§11.6)
    var sttPresent by remember { mutableStateOf(VoskStt.isModelPresent(context)) }
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
    }

    val saveConnection: () -> Unit = {
        scope.launch {
            settings.setBaseUrl(baseUrl)
            settings.setToken(token)
            saved = true
            WearApp.Graph.refreshMirrorFromSettings() // base/token folds land in the list mirror
        }
    }

    LaunchedEffect(Unit) {
        baseUrl = settings.baseUrl()
        token = settings.token()
        settings.loadBodyTools()
        tnEnabled = settings.tailnetEnabled()
        tnKey = settings.tailnetKey()
        tnHost = settings.tailnetHostname()
        pairHandle = settings.tailnetHandle()
    }

    /** Host of the active gateway endpoint — the peer the tailnet phase is judged against. */
    fun settingsGwHost(): String =
        runCatching { android.net.Uri.parse(baseUrl).host.orEmpty() }.getOrDefault("")

    val settingsScroll = androidx.compose.foundation.rememberScrollState()
    val edgeScope = rememberCoroutineScope()
    // BATCH-5 #18 (owner: *"it's not working on the agent screen… let me check the settings
    // screens… it's not working there… voice out has a really long list… not working there
    // either"*): the gesture lived ON the scrolled Column, whose layout node measures as tall
    // as its CONTENT — the ring geometry then computed a ~480×900 ellipse on a 480 px face, so
    // only touches near the TOP rim ever counted, and they drifted further off the deeper the
    // page scrolled (positions ride the node origin, which scrolls away). Chat/Sessions kept
    // working because a ScalingLazyColumn node IS viewport-sized. The gesture now rides a
    // viewport-sized wrapper: ring = the real round face, every page, every scroll position.
    Box(
        modifier = Modifier
            .fillMaxSize()
            // BATCH-6 #29 (owner: "the main settings screen… does not work with the bezel
            //  scrolling"): it DID circle — at the chat's reading gear, ~50 px a real-finger
            //  stroke, invisible on a button list. Dense surfaces circle at 2f, like Sessions.
            .edgeCircleScroll(speed = 2f) { total -> edgeScope.launch { settingsScroll.scrollBy(total) } },
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // BATCH-4 #6, owner-corrected: NO viewport band (roundSafe removed) and no pre-scroll
            // padding either — the scroll strip is the WHOLE face, exactly like chat ("cutting off
            // stuff at the top and the bottom of the screen, which is dumb… like you did on the
            // chat window initially"). The buffer is CONTENT padding after the scroll: think of a
            // web page with extra blank lines at the end — the text flows through the rim zone
            // while you read, and the LAST line comes to rest in the meat of the circle.
            .padding(horizontal = 20.dp)
            .verticalScroll(settingsScroll)
            // BATCH-7: 12.dp was fine at the owner's 1.81× text scale, but at default scale the
            // page title lands dead-center under OneUI's persistent clock pill (About proved it).
            // Clear the pill band on EVERY page, at every font scale.
            .padding(top = 34.dp, bottom = circleSafeBottomDp(20f, 12f).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(page.title, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)

        if (page != SettingsPage.Root) {
            Column(
                modifier = Modifier.fillMaxWidth().clickable { page = page.up() },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { Text(if (page.up() == SettingsPage.Root) "‹  all settings" else "‹  " + page.up().title.lowercase(), style = MaterialTheme.typography.caption2) }
            Spacer(Modifier.height(6.dp))
        }
        if (page == SettingsPage.Root) {
            val selGw = gateways.firstOrNull { it.id == selectedGwId }
            SettingNavRow("🌐", "Gateways", "${gateways.size} saved" + (if (selGw != null) " · ✓ ${selGw.label}" else "")) { page = SettingsPage.Gateways }
            SettingNavRow("🗣️", "Voice out", (if (voiceOut) "on" else "off") + " · ${String.format(java.util.Locale.US, "%.2f", ttsRate)}×" + (if (ttsSource == "gateway") " · 🌐" else "")) {
                page = SettingsPage.Voice
                Thread { dev.hermes.wearos.voice.GatewayTts.refresh(); gwProbe++ }.start()
            }
            // #13: voice-IN gets its own page (was buried in Voice out); subtitle names the
            // provider the orb will use, straight from the same in-memory truth the mic reads.
            SettingNavRow("🎤", "Voice in", SttProviders.navSummary(SttProviders.fromId(SttProviders.current))) { page = SettingsPage.VoiceIn }
            val mediaStereo by settings.mediaStereo.collectAsState()
            SettingNavRow("🎬", "Media", "A/V scaling · " + (if (mediaStereo) "stereo" else "mono") + " audio") { page = SettingsPage.Media }
            SettingNavRow("🎛️", "Display", "markdown · splash · orb") { page = SettingsPage.Display }
            SettingNavRow("⌚", "Watch", "body tools ${bodyTools.size} on · notifications") { page = SettingsPage.Watch }
            SettingNavRow("ℹ️", "About", "v$version · stt model") { page = SettingsPage.About }
            Spacer(Modifier.height(8.dp))
        }

        if (page == SettingsPage.Gateways) {
        // ─── GATEWAYS (owner multi-gateway epic, SPEC §11) ────────────────────────────────
        FieldLabel("Gateways")

        // WHAT THE RADIO ACTUALLY HAS (SPEC §11.6, owner: "make it flawless for discovering what's
        // available network wise"). One line, always true: the link carrying us, whether anything
        // validated it, and — the part that took a bug report to find out — whether a tailnet entry
        // in this gateway's list has ANY chance of resolving here. A `.ts.net` name is answered by
        // MagicDNS, which needs a tailnet interface or its resolver on this device; the phone's
        // tailnet does not lend itself to the watch. So instead of letting the owner infer that from
        // a DNS error, the Settings screen says it out loud, and the walk-out test reads off the
        // wrist. No SSID, no address literals: the privacy rule (§ repo policy) still holds.
        Text(
            NetworkWatch.describe(
                netInfo,
                gateways.firstOrNull { it.id == selectedGwId }?.apiEndpoints.orEmpty(),
                // Probe evidence counts only for the gateway it was gathered against (§11.2 dots rule).
                if (gwStatus.gatewayId == selectedGwId) gwStatus.apiResults else emptyMap(),
            ),
            style = MaterialTheme.typography.caption3,
            fontSize = 10.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
        )
        gateways.forEach { gw ->
            GatewayRow(
                gw = gw,
                selected = gw.id == selectedGwId,
                isProbed = gw.id == gwStatus.gatewayId,
                winner = gwStatus.apiWinner,
                results = gwStatus.apiResults,
                busy = gwStatus.probing,
                onSelect = {
                    if (gw.id != selectedGwId) {
                        gwNote = "probing ${gw.label}…"
                        WearApp.Graph.selectGateway(gw.id) { ok, label, err ->
                            // switchAgent's callback lands on a worker thread — toast gate (2a32c65).
                            WearApp.Graph.runOnUi {
                                gwNote = if (ok) "✓ $label" else "⚠️ $err"
                                android.widget.Toast.makeText(
                                    context, if (ok) "gateway → $label" else "gateway failed: $err",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                },
                onEdit = { gwEditor = GwDraft.of(gw) },
                canDelete = gateways.size > 1,
                onDelete = {
                    WearApp.Graph.deleteGateway(gw.id) { ok ->
                        WearApp.Graph.runOnUi { gwNote = if (ok) "deleted ${gw.label}" else "delete failed" }
                    }
                },
            )
        }
        if (gwNote.isNotEmpty()) {
            Text(gwNote, style = MaterialTheme.typography.caption3, fontSize = 11.sp,
                color = if (gwNote.startsWith("⚠")) Color(0xFFE57373) else Color(0xFF81C784))
        }
        if (gwEditor == null) {
            Button(
                onClick = { gwEditor = GwDraft() },
                modifier = Modifier.size(width = 100.dp, height = 32.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.4f)),
            ) { Text("＋ add", style = MaterialTheme.typography.button) }
        }
        gwEditor?.let { draft ->
            GatewayEditor(
                draft = draft,
                onChange = { gwEditor = it },
                onSave = { d ->
                    val api = (listOf(d.apiA, d.apiB) + d.extraApis).map { it.trim() }
                        .filter { it.isNotEmpty() }.distinct()
                    if (api.isEmpty()) { gwNote = "⚠️ needs an API URL"; return@GatewayEditor }
                    val wk = (listOf(d.wkA.ifBlank { GatewayModel.derivedWatchkit(api.first()) }) + d.extraWks)
                        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    val gwId = d.id.ifEmpty { GatewayModel.hostId(api.first()) }
                    val gw = Gateway(
                        id = gwId,
                        label = d.label.trim().ifEmpty { GatewayModel.hostId(api.first()) },
                        apiEndpoints = api.distinct(),
                        watchkitEndpoints = wk.distinct(),
                        token = d.token.trim(),
                        // Editing URLs must never erase the per-profile keys learned from the box
                        // (SPEC §12.2) — the editor does not show them, so carry them over.
                        agentKeys = gateways.firstOrNull { it.id == gwId }?.agentKeys.orEmpty(),
                    )
                    WearApp.Graph.saveGateway(gw) { err ->
                        WearApp.Graph.runOnUi {
                            if (err.isEmpty()) {
                                gwEditor = null
                                gwNote = "✓ saved ${gw.label}"
                            } else gwNote = "⚠️ $err"
                        }
                    }
                },
                onCancel = { gwEditor = null },
            )
        }

        }
        if (page == SettingsPage.AgentKeys) {
        // ─── AGENT KEYS (SPEC §12.2; owner bug: "switching agents gives errors on some agents") ─
        // One Hermes box multiplexes every agent, but `API_SERVER_KEY` resolves PER PROFILE and
        // fails closed for a named profile without one — while the unauthenticated `/v1/health`
        // keeps answering 200. So the Test button lies and every real call 401s. This section is
        // the honest answer: show which agents actually have a key, LEARN them from watchkit (it
        // reads profiles/<p>/.env on the gateway box), and allow pasting one by hand — typing 64
        // hex chars with a watch keyboard is the fallback, not the workflow.
        FieldLabel("Agent keys")
        val agentKeys by settings.agentKeys.collectAsState()
        val agentNames by WearApp.Graph.agents.agents.collectAsState()
        val keyAgents = (agentNames + agentKeys.keys).distinct().sorted()
        Text(
            "one box · one key per agent · health 200 ≠ calls work",
            style = MaterialTheme.typography.caption3,
            fontSize = 10.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
        )
        var keyNote by remember { mutableStateOf("") }
        keyAgents.forEach { a ->
            val has = agentKeys[a]?.isNotBlank() == true
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClickLabel = "key for $a") { keyEdit = a; keyDraft = agentKeys[a].orEmpty() }
                    .padding(vertical = 3.dp, horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    a,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.caption1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (has) "✓ key" else "— no key",
                    style = MaterialTheme.typography.caption3,
                    color = if (has) Color(0xFF81C784) else Color(0xFFE57373),
                )
            }
        }
        if (keyAgents.isEmpty()) {
            Text(
                "no agents seen yet — open the agent picker once (dial 10:30) and come back",
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "⟳ learn from watchkit",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colors.surface.copy(alpha = 0.4f))
                    .clickable(onClickLabel = "learn agent keys") {
                        scope.launch {
                            keyNote = "…"
                            keyNote = try {
                                val n = settings.refreshAgentKeys()
                                if (n > 0) "✓ $n keys learned" else "watchkit returned no keys"
                            } catch (e: Exception) {
                                "⚠️ " + RouteReport.explain(e).take(60)
                            }
                        }
                    }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
            if (keyNote.isNotEmpty()) {
                Text(
                    keyNote,
                    style = MaterialTheme.typography.caption3,
                    fontSize = 10.sp,
                    color = if (keyNote.startsWith("⚠")) Color(0xFFE57373) else Color(0xFF81C784),
                )
            }
        }
        keyEdit?.let { agent ->
            FieldLabel("key for $agent")
            EditLine(
                value = keyDraft,
                onChange = { keyDraft = it },
                placeholder = "paste ${agent}'s API_SERVER_KEY",
                password = true,
                onDone = {
                    scope.launch {
                        settings.setAgentKey(agent, keyDraft)
                        keyNote = if (keyDraft.isBlank()) "cleared ${agent}" else "✓ saved for $agent"
                        keyEdit = null
                    }
                },
            )
        }

        }
        if (page == SettingsPage.Gateways) {
        FieldLabel("Base URL (active)")
        EditLine(
            value = baseUrl,
            onChange = { baseUrl = it; saved = false },
            placeholder = "http://host:8642/p/wear",
            onDone = saveConnection,
        )
        FieldLabel("Token")
        EditLine(
            value = token,
            onChange = { token = it; saved = false },
            placeholder = "••••••",
            password = true,
            onDone = saveConnection,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = saveConnection,
                modifier = Modifier.size(width = 72.dp, height = 32.dp),
            ) { Text(if (saved) "✓" else "Save", style = MaterialTheme.typography.button) }
            Button(
                onClick = {
                    testing = true; testResult = "…"
                    scope.launch {
                        testResult = try {
                            val a = HermesApi(settings.baseUrl(), settings.token())
                            withContext(Dispatchers.IO) { a.health() }
                        } catch (e: Exception) {
                            "⚠️ " + RouteReport.explain(e)
                        }
                        testing = false
                    }
                },
                modifier = Modifier.size(width = 72.dp, height = 32.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.4f)),
            ) { Text(if (testing) "…" else "Test", style = MaterialTheme.typography.button) }
        }
        if (testResult.isNotEmpty() && !testing) {
            Text(
                if (testResult.startsWith("ok") || testResult.startsWith("OK") || testResult.startsWith("✅"))
                    "✅ $testResult" else testResult,
                style = MaterialTheme.typography.caption2,
                color = if (testResult.startsWith("⚠️")) Color(0xFFE57373) else Color(0xFF81C784),
                fontSize = 11.sp,
            )
        }

        // Display (SPEC §4 "Display" node, §5 F3): renders Hermes replies through
        // ui/md/WatchMarkdown instead of raw text. Default ON; StateFlow so the chat re-renders live.
            Spacer(Modifier.height(2.dp))
            SettingNavRow("🔑", "Agent keys", "learned from the selected gateway") { page = SettingsPage.AgentKeys }
            val tnSt by WearApp.Graph.tailnetStatus.collectAsState()
            val tnPhase = TailnetLogic.phase(tnSt, tnEnabled, settingsGwHost())
            SettingNavRow("🕸️", "Tailnet", TailnetLogic.describe(tnPhase, tnSt, settingsGwHost())) { page = SettingsPage.Tailnet }
            // PAIR (SPEC §4 / S5): like Tailnet, it hangs UNDER Gateways — its whole job is
            // filling this registry (key + endpoints + token) from the plugin's claim funnel.
            SettingNavRow("🤝", "Pair", "PIN up · gateway claim from your tailnet") { page = SettingsPage.Pair }
        }
        if (page == SettingsPage.Tailnet) {
        // ─── TAILNET (SPEC §11.4): the watch's own node is THE transport ─────────────────
        // Phase truth comes from the engine (parsed in domain/TailnetState); this page owns
        // only the enrollment inputs. IME rule holds: every EditLine saves on Done AND on
        // the Save button — nothing here depends on Enter.
        FieldLabel("Status")
        val tnSt by WearApp.Graph.tailnetStatus.collectAsState()
        val tnGw = settingsGwHost()
        val tnPhase = TailnetLogic.phase(tnSt, tnEnabled, tnGw)
        Text(
            TailnetLogic.describe(tnPhase, tnSt, tnGw),
            style = MaterialTheme.typography.caption1,
            color = when (tnPhase) {
                TailnetPhase.ACTIVE -> Color(0xFF81C784)
                TailnetPhase.NEEDS_AUTH, TailnetPhase.DEGRADED, TailnetPhase.STOPPED -> Color(0xFFE57373)
                else -> Color(0xFFB0BEC5)
            },
        )
        if (tnPhase == TailnetPhase.ACTIVE && tnSt.self != null) {
            Text(
                "node ${tnSt.self?.dnsName} · via 127.0.0.1:${tnSt.socksPort}",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
            )
        }
        // DEBUGGABLE BUILDS ONLY ("tailnet only, always"): release force-ON (AppSettings ignores
        // the pref on non-debuggable APKs), so the OFF switch — the plain-sockets escape hatch —
        // is dev input by definition and hiding it is the honest UI for that.
        if (WearApp.Graph.isDebugBuild) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Use tailnet", style = MaterialTheme.typography.caption1)
                Switch(
                    checked = tnEnabled,
                    onCheckedChange = { v -> tnEnabled = v; WearApp.Graph.tailnetSetEnabled(v) },
                )
            }
        }
        FieldLabel("Node name")
        EditLine(
            value = tnHost,
            onChange = { tnHost = it; tnHostSaved = false },
            placeholder = "hermes-wearos",
            onDone = { scope.launch { settings.setTailnetHostname(tnHost); tnHostSaved = true } },
        )
        Text(
            "Shows in the tailnet admin · applied at next enrollment",
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
        )
        FieldLabel("Auth key")
        EditLine(
            value = tnKey,
            onChange = { tnKey = it; tnKeySaved = false },
            placeholder = "tskey-auth-… (admin ▸ Keys)",
            password = true,
            onDone = {
                scope.launch {
                    settings.setTailnetKey(tnKey); tnKeySaved = true
                    WearApp.Graph.tailnetStart() // fresh key → land it (enroll if not in yet)
                }
            },
        )
        // Two rows, not three buttons on one: the ring's ~170dp content width fits two
        // buttons comfortably and clips a third (caught on screenshot, 2026-11-02 — the
        // Gateways page's Save+Test row is the precedent this copies).
        Button(
            onClick = {
                scope.launch {
                    settings.setTailnetKey(tnKey); tnKeySaved = true
                    WearApp.Graph.tailnetStart()
                }
            },
            modifier = Modifier.size(width = 72.dp, height = 32.dp),
        ) { Text(if (tnKeySaved) "✓" else "Save", style = MaterialTheme.typography.button) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Two-tap confirm (the ring has no dialog real estate worth burning here): first
            // tap arms, second enrolls fresh — and a fresh enrollment means a NEW node row in
            // the admin console, so it must not be a fatality.
            Button(
                onClick = {
                    if (tnReenrollArmed) { WearApp.Graph.tailnetReenroll(); tnReenrollArmed = false }
                    else tnReenrollArmed = true
                },
                modifier = Modifier.size(width = 84.dp, height = 32.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.4f)),
            ) { Text(if (tnReenrollArmed) "Sure?" else "Re-enroll", style = MaterialTheme.typography.button) }
            // THE honest test: a gateway health call that rides whatever the app rides right
            // now — through the tunnel when it's up (same client, same ProxySelector).
            Button(
                onClick = {
                    tnTesting = true; tnTestResult = "…"
                    scope.launch {
                        tnTestResult = try {
                            val a = dev.hermes.wearos.net.HermesApi(settings.baseUrl(), settings.token())
                            withContext(Dispatchers.IO) { a.health() }
                        } catch (e: Exception) {
                            "⚠️ " + RouteReport.explain(e)
                        }
                        tnTesting = false
                    }
                },
                modifier = Modifier.size(width = 72.dp, height = 32.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.4f)),
            ) { Text(if (tnTesting) "…" else "Test", style = MaterialTheme.typography.button) }
        }
        if (tnTestResult.isNotEmpty() && !tnTesting) {
            Text(
                if (tnTestResult.startsWith("ok") || tnTestResult.startsWith("OK") || tnTestResult.startsWith("✅"))
                    "✅ $tnTestResult" else tnTestResult,
                style = MaterialTheme.typography.caption2,
                color = if (tnTestResult.startsWith("⚠️")) Color(0xFFE57373) else Color(0xFF81C784),
                fontSize = 11.sp,
            )
        }
        Text(
            "Every ask rides the tunnel — no LAN, no phone, and no switch to turn it off: " +
                "the tunnel is THE transport. Node state lives on this watch; a Re-enroll " +
                "spends a key and leaves the old node for pruning in the admin." +
                if (WearApp.Graph.isDebugBuild) " The switch above is the dev escape hatch (off = plain sockets)." else "",
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
        )
        }
        if (page == SettingsPage.Pair) {
        // ─── PAIR (SPEC §4 onboarding, PAIRING v2): the bootstrap door ───────────────────
        // The GATEWAY issues the one-shot code (the watch never chooses it); the watch knocks
        // the plugin's funnel over plain HTTPS (the ONLY no-tunnel client, net/PairingClient)
        // and polls by its 256-bit device secret — the code rides no URL. Approval happens on
        // a login surface (CLI/TUI/Desktop), never in a chat the model can write into. The
        // pickup carries the authkey + endpoints; the KEYS arrive later over the private lane
        // (Graph.pairFinishSession). IME rule holds: the handle saves on Done AND on Save.
        FieldLabel("Tailnet handle")
        EditLine(
            value = pairHandle,
            onChange = { pairHandle = it },
            placeholder = "your-tailnet-name",
            onDone = { scope.launch { settings.setTailnetHandle(pairHandle) } },
        )
        Button(
            onClick = { scope.launch { settings.setTailnetHandle(pairHandle) } },
            modifier = Modifier.size(width = 72.dp, height = 32.dp),
        ) { Text("Save", style = MaterialTheme.typography.button) }
        FieldLabel("Your code (gateway-issued, 5 min)")
        Text(
            if (pairCode.isNotEmpty()) pairCode else "——————",
            style = MaterialTheme.typography.title2.copy(letterSpacing = 8.sp),
            color = MaterialTheme.colors.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (pairBusy && pairCode.isEmpty()) "…" else pairStatus,
            style = MaterialTheme.typography.caption2,
            color = when {
                pairStatus.startsWith("⚠️") || pairStatus.startsWith("code gone") -> Color(0xFFE57373)
                pairStatus.startsWith("paired") -> Color(0xFF81C784)
                else -> MaterialTheme.colors.onBackground.copy(alpha = 0.7f)
            },
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (!pairBusy) pairAttempt++ },
                modifier = Modifier.size(width = 84.dp, height = 32.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.85f)),
            ) { Text(if (pairBusy) "…" else if (pairCode.isEmpty()) "Knock" else "Retry", style = MaterialTheme.typography.button) }
            if (dev.hermes.wearos.net.PrivateLane.configured) {
                Button(
                    onClick = {
                        if (pairUnpairArmed) { WearApp.Graph.unpair(); pairUnpairArmed = false
                            pairStatus = "unpaired — the gateway revoked this watch" }
                        else { pairUnpairArmed = true; pairStatus = "tap Unpair again to revoke" }
                    },
                    modifier = Modifier.size(width = 84.dp, height = 32.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF5C2B2B)),
                ) { Text(if (pairUnpairArmed) "Sure?" else "Unpair", style = MaterialTheme.typography.button) }
            }
        }
        Text(
            "On the PC: hermes watch pair (or the Wear Kit pane) — the pairing window opens there, " +
                "one code per open. The code is typed on the PC, never spoken to an agent; the " +
                "authkey and keys never appear in a log, and the gateway key arrives only over " +
                "this watch's private tailnet lane.",
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
        )
        }
        if (page == SettingsPage.Display) {
        FieldLabel("Display")
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Markdown rendering", style = MaterialTheme.typography.caption1)
            Switch(
                checked = markdownOn,
                onCheckedChange = { v -> scope.launch { settings.setMarkdownRendering(v) } },
            )
        }
        // THE LAUNCH SPLASH (domain/SplashIntro owns when and what it may show; owner
        // 2026-10-29: *"I only want static or video, video by default… let the user choose
        // custom image/video… from their watch"*). This chooser REPLACES the old Launch-video
        // switch — its OFF predated the chooser and maps to STATIC on this build's first boot
        // (migration in SplashIntro.choiceOf, tested). Applies to the NEXT cold start: the gate
        // is decided exactly once per process.
        val splashMode by settings.splashMode.collectAsState()
        val customStill by settings.splashImageUri.collectAsState()
        val customFilm by settings.splashVideoUri.collectAsState()
        val videoMode = splashMode != "static"
        // STACKED, not inline: the round screen clipped a label+2-chip row at the bezel —
        // label row first, then the two chips centered with room to read.
        Text("Launch splash", style = MaterialTheme.typography.caption1)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SplashChoiceChip("Video", videoMode) { scope.launch { settings.setSplashMode("video") } }
            Spacer(Modifier.size(8.dp))
            SplashChoiceChip("Static", !videoMode) { scope.launch { settings.setSplashMode("static") } }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val custom = if (videoMode) customFilm else customStill
            Button(
                onClick = { splashPick.launch(arrayOf(if (videoMode) "video/*" else "image/*")) },
                modifier = Modifier.size(width = 92.dp, height = 32.dp),
            ) { Text(if (custom.isNotEmpty()) "Swap media" else "Pick media", style = MaterialTheme.typography.button) }
            if (custom.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = { scope.launch { settings.setSplashCustom(videoMode, null) } },
                    modifier = Modifier.size(width = 44.dp, height = 32.dp),
                ) { Text("↺", style = MaterialTheme.typography.button) }
            }
        }
        Text(
            if ((if (videoMode) customFilm else customStill).isNotEmpty()) "Media: custom ✓ (next launch)"
            else "Media: built-in",
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
        )
        // PINCH FONT-SCALE (Layout D epic): read-out + rescue reset. The two-finger pinch IN THE
        // MESSAGE AREA is the adjust UI (double-tap empty transcript = reset too); this row makes
        // the persisted value visible and recoverable from Settings.
        val fontScale by settings.fontScale.collectAsState()
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Pinch text", style = MaterialTheme.typography.caption1)
            Text(
                "×%.2f".format(fontScale),
                style = MaterialTheme.typography.caption2,
                color = if (FontScale.isDefault(fontScale)) MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
                    else MaterialTheme.colors.primary,
            )
            if (!FontScale.isDefault(fontScale)) {
                Text(
                    "reset",
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colors.surface.copy(alpha = 0.4f))
                        .clickable(onClickLabel = "reset text size") {
                            scope.launch { settings.setFontScale(FontScale.DEFAULT) }
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Text(
            "pinch the chat to resize text",
            style = MaterialTheme.typography.caption3,
            fontSize = 10.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
        )

        // THE ORB DIAL (SPEC §12.1, owner batch 3): "the orb should be the only thing on the
        // screen — drag it nine ways". Every one of the nine directions is a lane and every lane
        // is remappable HERE. Tapping a row advances that direction to the next action (the Lane
        // list cycles; `off` blanks a direction so an accidental drag does nothing instead of
        // opening something). The chat reads the StateFlow mirror, so the very next flick obeys —
        // no restart, and the ring icons change with it.
        FieldLabel("Orb dial — drag 8 ways")
        val dialSlots by settings.orbSlots.collectAsState()
        Text(
            "tap a direction to change it",
            style = MaterialTheme.typography.caption3,
            fontSize = 10.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
        )
        dialSlots.forEachIndexed { i, lane ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClickLabel = "change ${OrbDial.clockLabel(i)}") {
                        val all = Lane.entries
                        scope.launch { settings.setOrbSlot(i, all[(lane.ordinal + 1) % all.size]) }
                    }
                    .padding(vertical = 3.dp, horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    OrbDial.clockLabel(i),
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.primary,
                    modifier = Modifier.width(46.dp),
                )
                Text(lane.glyph, style = MaterialTheme.typography.caption1)
                Text(
                    lane.label,
                    style = MaterialTheme.typography.caption1,
                    color = if (lane == Lane.None) MaterialTheme.colors.onBackground.copy(alpha = 0.4f)
                        else MaterialTheme.colors.onBackground,
                )
            }
        }
        var catalogNote by remember { mutableStateOf("") }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "refresh commands",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colors.surface.copy(alpha = 0.4f))
                    .clickable(onClickLabel = "refresh command catalog from watchkit") {
                        scope.launch {
                            catalogNote = "…"
                            catalogNote = try {
                                val n = settings.refreshSlashCatalog(settings.profileSync())
                                if (n > 0) "$n commands" else "gateway sent none back"
                            } catch (e: Exception) {
                                "watchkit unreachable: ${e.message?.take(40) ?: e.javaClass.simpleName}"
                            }
                        }
                    }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
            Text(
                "⌘ 6:00 · steer/btw land here" + (if (catalogNote.isEmpty()) "" else " · $catalogNote"),
                style = MaterialTheme.typography.caption3,
                fontSize = 9.5.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
            )
        }

        // watchkit helper daemon (server/watchkit, tailnet :42651): health probe like the Hermes Test.
        }
        if (page == SettingsPage.Gateways) {
        FieldLabel("Watchkit")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    wkTesting = true; wkResult = "…"
                    scope.launch {
                        val url = settings.mermaidBaseUrl()
                        Watchkit.setConfig(url, settings.watchkitToken())
                        wkResult = try {
                            Watchkit.health(url)
                        } catch (e: Exception) {
                            "⚠️ " + RouteReport.explain(e)
                        }
                        wkTesting = false
                    }
                },
                modifier = Modifier.size(width = 72.dp, height = 32.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.4f)),
            ) { Text(if (wkTesting) "…" else "Test", style = MaterialTheme.typography.button) }
        }
        if (wkResult.isNotEmpty() && !wkTesting) {
            Text(
                if (wkResult.startsWith("ok")) "✅ $wkResult" else wkResult,
                style = MaterialTheme.typography.caption2,
                color = if (wkResult.startsWith("⚠️")) Color(0xFFE57373) else Color(0xFF81C784),
                fontSize = 11.sp,
            )
        }

        }
        if (page == SettingsPage.Watch) {
        // W1-W4 body tools (SPEC §5): per-tool switches, all default OFF (owner-locked),
        // a Push-now button, and the fail-soft Health Services status lines.
        FieldLabel("Body tools")
        BodyToolToggle("Heart rate", BodySnapshot.TOOL_HR, bodyTools, settings)
        BodyToolToggle("Steps & activity", BodySnapshot.TOOL_STEPS, bodyTools, settings)
        BodyToolToggle("Sleep", BodySnapshot.TOOL_SLEEP, bodyTools, settings)
        BodyToolToggle("Blood oxygen", BodySnapshot.TOOL_SPO2, bodyTools, settings)
        // Owner ask B: the watch knows where it is (one-shot fix, fail-soft, default OFF).
        BodyToolToggle("Location", BodySnapshot.TOOL_LOC, bodyTools, settings)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { WearApp.Graph.pusher.pushNow("settings") },
                modifier = Modifier.size(width = 72.dp, height = 32.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.4f)),
            ) { Text(if (pushState.inFlight) "…" else "Push now", style = MaterialTheme.typography.button) }
        }
        val pushLine = when {
            pushState.lastTs == 0L -> "no snapshot pushed yet (15-min cadence)"
            else -> {
                val agoMin = ((System.currentTimeMillis() - pushState.lastTs) / 60_000).toInt()
                "${pushState.result} · ${agoMin} min ago"
            }
        }
        StatusLine("last push", pushLine)
        StatusLine(
            "health services",
            bodyStatus[BodySnapshot.TOOL_HR]?.take(64)
                ?: (if (bodyStatus.isEmpty()) "probing…" else "—"),
        )
        StatusLine(
            "location",
            (if (LocationTool.hasPermission(context)) "" else "perms missing · ") +
                LocationTool.status.collectAsState().value.take(64),
        )

        }
        if (page == SettingsPage.Voice) {
        // ─── VOICE-OUT CONFIG (owner 2026-10-29: "change voices… and change speed"; the chips
        // became a DRAG SLIDER the same day: "the buttons don't all fit… a slider would do me
        // better"). domain/TtsConfig owns the ↔ mapping; SpeakAloud applies mid-queue live and
        // the release ear-tests the result. Slow (0.5×) left … fast (2.0×) right.
        // The master switch moved HERE from the Watch page (the same-day tree move had stranded
        // a lone 🔊 under body tools).
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🔊 voice out", style = MaterialTheme.typography.body2)
            Switch(checked = voiceOut, onCheckedChange = { speak.setVoiceEnabled(it) })
        }
        // VOICE SOURCE (issue #7, SPEC §19): speak with the OS cascade (Samsung/Google —
        // the engine+voice rows below) or with the WEAR KIT's cloned gateway voices served
        // by the private lane. The probe (GET /voice/cards) runs on entry to this page; the
        // note states the truth — engine missing is a refusal, not a silent dead end, and
        // a lane that fails mid-answer falls the sentence back to the system cascade.
        FieldLabel("voice source")
        val gw = dev.hermes.wearos.voice.GatewayTts
        gwProbe  // the entry probe bumps this volatile counter-backed state → fresh note/cards
        // Equal-size pills, STACKED: two 150dp+ buttons side by side overflow the inscribed
        // square of the round dial and the longer label ellipsizes into looking smaller.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("system" to "system", "gateway" to "gateway 🌐").forEach { (id, label) ->
                val chosen = ttsSource == id
                Button(
                    onClick = { speak.setVoiceSource(id); gwProbe++ },
                    modifier = Modifier.size(width = 168.dp, height = 36.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (chosen) MaterialTheme.colors.primary.copy(alpha = 0.5f)
                        else MaterialTheme.colors.surface.copy(alpha = 0.4f)),
                ) { Text(label, style = MaterialTheme.typography.button, maxLines = 1) }
            }
        }
        if (ttsSource == "gateway") {
            StatusLine("lane", gw.note)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { Thread { gw.refresh(); gwProbe++ }.start() },
                    modifier = Modifier.size(width = 100.dp, height = 30.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.4f)),
                ) { Text("↻ probe", style = MaterialTheme.typography.button) }
            }
            if (gw.cards.isNotEmpty()) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                FieldLabel("voices · ✓ = default")
                gw.cards.forEach { (cid, label) ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label.take(14), style = MaterialTheme.typography.body2,
                            modifier = Modifier.weight(1f))
                        Button(
                            onClick = { Thread { gw.previewCard(cid, agentNow) }.start() },
                            modifier = Modifier.size(width = 48.dp, height = 34.dp),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.4f)),
                        ) { Text("▶", style = MaterialTheme.typography.button) }
                        // DEFAULT checkbox (owner 2026-11-10): the state lives in the canonical
                        // prefs doc (voices.__default__, gateway-canonical, higher-rev-wins) —
                        // the wrist mirrors it and edits it THROUGH the lane, never owns it.
                        Checkbox(
                            checked = gw.defaultCard == cid,
                            onCheckedChange = { on ->
                                if (on) Thread { gw.setAsDefault(cid); gwProbe++ }.start()
                            },
                        )
                    }
                }
            }
        }
        // SPEECH INPUT lives on its own nested page now (batch-4 #13: Settings ▸ Voice in) —
        // the owner asked for the orb's default engine to be settable the same way voice-out
        // is, not buried under it.
        // VOLUME (owner 2026-10-29): above the speed slider — the ear adjusts loudness first.
        // Edits the CURRENT AGENT's override (unset agents ride the global default); the orb
        // dial's double-slider and the mute satellite write the SAME store, so all three agree.
        // EAR-TEST rides the SELECTED source (owner 2026-11-10): on the gateway the sliders
        // preview with the shipped card voice, so what you tune is what you hear. Volume is
        // the same per-agent gain the gateway clips now honor; on the gateway the speed
        // slider IS the one global dial (writes global.speed, higher-rev-wins).
        val gwSource = ttsSource == "gateway"
        var gwSpeed by remember { mutableStateOf(if (gw.laneSpeed > 0f) gw.laneSpeed else ttsRate) }
        fun earTest() {
            if (!voiceOut) return
            if (gwSource && gw.available()) gw.preview(agentNow) else speak.preview()
        }
        VolumeSlider(
            volume = replyVolFor,
            agent = agentNow,
            commit = { speak.setReplyVolume(it) },
            onDone = { earTest() },
        )
        RateSlider(
            rate = if (gwSource) gwSpeed else ttsRate,
            onRate = { v -> if (gwSource) gwSpeed = v else speak.setSpeechRate(v) },
            onDone = {
                if (gwSource) {
                    val v = gwSpeed
                    Thread { gw.setSpeed(v); gwSpeed = gw.laneSpeed.coerceAtLeast(v); gwProbe++ }.start()
                }
                earTest()
            },
        )
        // ENGINE row (owner: "choose google/samsung/(other if exists)") — switching packages
        // rebuilds the live engine; its voices load right after.
        FieldLabel("engine")
        val engines = speak.availableEngines()
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { enginesOpen = !enginesOpen },
                modifier = Modifier.size(width = 140.dp, height = 34.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.4f)),
            ) {
                Text(
                    (if (ttsEngine.isEmpty()) "system default"
                    else engines.firstOrNull { it.pkg == ttsEngine }?.label ?: ttsEngine).take(18) +
                        if (enginesOpen) " ▲" else " ▼",
                    style = MaterialTheme.typography.button, maxLines = 1,
                )
            }
        }
        if (enginesOpen) {
            VoicePickRow("★ system default", "", ttsEngine, onSelect = {
                speak.setEngine(""); enginesOpen = false
            })
            if (engines.size <= 1) {
                StatusLine("engines", "engine not ready yet")
            } else {
                engines.drop(1).forEach { e ->
                    VoicePickRow(e.label, e.pkg, ttsEngine, onSelect = {
                        speak.setEngine(e.pkg); enginesOpen = false
                    })
                }
            }
        }

        // VOICE row with the local/network filter (owner: "then local/network, then it shows
        // the list … limited by the higher level choices") — an unfiltered Google list is 100+
        // rows; 🌐 marks network voices so the ear-test target is findable in two taps.
        FieldLabel("voice")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { voicesOpen = !voicesOpen },
                modifier = Modifier.size(width = 140.dp, height = 34.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.4f)),
            ) {
                Text(
                    (if (ttsVoice.isEmpty()) "engine default" else ttsVoice)
                        .take(18) + if (voicesOpen) " ▲" else " ▼",
                    style = MaterialTheme.typography.button, maxLines = 1,
                )
            }
        }
        if (voicesOpen) {
            VoiceSourceChips(voiceSrc) { voiceSrc = it }
            val all = speak.availableVoices()
            val voices = all.filter { voiceSrc == dev.hermes.wearos.domain.VoiceCatalog.ANY ||
                (voiceSrc == dev.hermes.wearos.domain.VoiceCatalog.LOCAL) == it.local }
            if (all.isEmpty()) {
                StatusLine("voices", "engine not ready yet")
            } else if (voices.isEmpty()) {
                StatusLine("voices", "no $voiceSrc voices on this engine")
            } else {
                VoicePickRow("★ engine default", "", ttsVoice, onSelect = {
                    speak.setVoice("")   // the settings.ttsVoice flow comes back on write
                    if (voiceOut) speak.preview()
                })
                voices.forEach { v ->
                    VoicePickRow(
                        v.name.take(26) + if (!v.local) " 🌐" else "",
                        v.name, ttsVoice, onSelect = {
                            speak.setVoice(v.name)
                            if (voiceOut) speak.preview()
                        },
                    )
                }
            }
        }

        // FALLBACK row (owner: "choose a default and fallback… network first… local fallback"):
        // when the preferred voice ERRORS mid-utterance (the usual network voice off-wifi), the
        // same sentence re-queues on this one — see SpeakAloud.fail.
        FieldLabel("fallback")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { fbOpen = !fbOpen },
                modifier = Modifier.size(width = 140.dp, height = 34.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.4f)),
            ) {
                Text(
                    (if (ttsFallback.isEmpty()) "none" else ttsFallback).take(18) +
                        if (fbOpen) " ▲" else " ▼",
                    style = MaterialTheme.typography.button, maxLines = 1,
                )
            }
        }
        if (fbOpen) {
            VoiceSourceChips(fbSrc) { fbSrc = it }
            val all = speak.availableVoices()
            val voices = all.filter { fbSrc == dev.hermes.wearos.domain.VoiceCatalog.ANY ||
                (fbSrc == dev.hermes.wearos.domain.VoiceCatalog.LOCAL) == it.local }
            VoicePickRow("— none (single try) —", "", ttsFallback, onSelect = {
                speak.setVoiceFallback(""); fbOpen = false
            })
            if (all.isEmpty()) {
                StatusLine("voices", "engine not ready yet")
            } else {
                voices.forEach { v ->
                    VoicePickRow(
                        v.name.take(26) + if (!v.local) " 🌐" else "",
                        v.name, ttsFallback, onSelect = {
                            speak.setVoiceFallback(v.name); fbOpen = false
                        },
                    )
                }
            }
        }

        }
        if (page == SettingsPage.VoiceIn) {
            // SPEECH INPUT PROVIDER (batch-4 #8 picker, #13 page: the orb's default engine,
            // nested like Voice out). HONEST subtitles from domain/SttProviders (pure +
            // JVM-tested); Vosk stays default — airplane mode must keep listening (SPEC §5).
            // The mic path resolves the pick on every startListening (WearApp.Graph.sttNow),
            // so a pick is live immediately, no restart.
            FieldLabel("🎙 speech input")
            val sysAvailable = remember { SystemStt.isAvailable(context) }
            val sysPkg = remember { SystemStt.recognizerPackage(context) }
            var sttProv by remember { mutableStateOf(SttProviders.fromId(SttProviders.current)) }
            // Model presence becomes LIVE state (owner 2026-11-06): the in-app installer flips
            // it without leaving the page, so the row subtitle follows the installer flow.
            val installer = dev.hermes.wearos.WearApp.Graph.voskInstaller
            val installState by installer.state.collectAsState()
            LaunchedEffect(installState) { sttPresent = VoskStt.isModelPresent(context) }
            VoicePickRow(
                "🎙️ Local Vosk · " + SttProviders.subtitle(SttProvider.LOCAL, sttPresent, sysAvailable, sysPkg),
                SttProviders.id(SttProvider.LOCAL), SttProviders.id(sttProv),
                onSelect = {
                    sttProv = SttProvider.LOCAL
                    scope.launch { settings.setSttProvider(SttProviders.ID_LOCAL) }
                },
            )
            VoicePickRow(
                "📡 System · " + SttProviders.subtitle(SttProvider.SYSTEM, sttPresent, sysAvailable, sysPkg),
                SttProviders.id(SttProvider.SYSTEM), SttProviders.id(sttProv),
                onSelect = {
                    sttProv = SttProvider.SYSTEM
                    scope.launch { settings.setSttProvider(SttProviders.ID_SYSTEM) }
                },
            )
            // BATCH-4 #9: the third provider — streaming mic to the gateway engine. The subtitle
            // names the engine watchkit's /health reports (refreshed while this page is open),
            // which is also the #9 "Settings shows it" contract. Auto-fallback = never stranded.
            val gwEngine by dev.hermes.wearos.net.Watchkit.sttEngine.collectAsState()
            val gwEngines by dev.hermes.wearos.net.Watchkit.sttEngines.collectAsState()
            var gwPick by remember { mutableStateOf(SttProviders.gatewayEnginePick) }
            LaunchedEffect(page, sttProv) {
                dev.hermes.wearos.net.Watchkit.refreshSttEngine()
                gwPick = SttProviders.gatewayEnginePick
            }
            // #16: the EFFECTIVE engine (pick when /health says installed-ready, else the
            // watchkit default) — the row name and the streaming preview both tell THIS truth.
            val effEngine = SttProviders.effectiveEngine(gwPick, gwEngine, gwEngines.toList())
            VoicePickRow(
                "🛰 Gateway · " + SttProviders.subtitle(
                    SttProvider.GATEWAY, sttPresent, sysAvailable, sysPkg, effEngine,
                ),
                SttProviders.id(SttProvider.GATEWAY), SttProviders.id(sttProv),
                onSelect = {
                    sttProv = SttProvider.GATEWAY
                    scope.launch { settings.setSttProvider(SttProviders.ID_GATEWAY) }
                },
            )
            // #13: the SAME truth the listening overlay prints up top, previewed here so the
            // wearer learns what each row means before they rely on it mid-sentence.
            val streamsFor: Boolean? = if (sttProv == SttProvider.GATEWAY)
                SttProviders.engineStreams(effEngine, gwEngines.toList())
            else SttProviders.streamsText(sttProv, effEngine)
            streamsFor?.let { streams ->
                Text(
                    SttProviders.streamsLabel(streams) + if (!streams) " (stays 'listening…' while you talk)" else "",
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // #16 ENGINE PICKER (owner: *"we should have the option to select the model …
            // if you click on it, it should let you … select … installed models … Whisper is
            // working okay, I just don't like that it doesn't live transcribe … at least we
            // have two and we're set up to expand"*): the gateway row CLICKS OPEN its installed
            // engines — the list is exactly what watchkit's /health reports, so an engine nobody
            // installed reads "not installed" instead of silently 404-ing mid-sentence.
            if (sttProv == SttProvider.GATEWAY) {
                FieldLabel("🛰 gateway engine")
                VoicePickRow(
                    "★ gateway default" + (gwEngine?.let { " · $it" } ?: " · watchkit offline"),
                    "", gwPick,
                    onSelect = {
                        gwPick = ""
                        scope.launch { settings.setSttGatewayEngine("") }
                    },
                )
                if (gwEngines.isEmpty()) {
                    Text(
                        if (gwEngine == null) "watchkit offline — no engines listed"
                        else "$gwEngine (only one reported)",
                        style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                gwEngines.forEach { e ->
                    VoicePickRow(
                        SttProviders.engineRowLabel(e), e.id.lowercase(), gwPick,
                        onSelect = {
                            gwPick = e.id.lowercase()
                            scope.launch { settings.setSttGatewayEngine(e.id) }
                        },
                    )
                }
            }
            // ── OFFLINE MODEL (owner 2026-11-06: *"install the local Vosk as a check option"*)
            // — the LOCAL row above is a choice; it only WORKS once the ~39 MB model sits in
            // filesDir. It rides in from the wearer's own gateway (GET /stt/vosk-model, bearer
            // + tailnet already established there), Range-resumes across wifi dropouts, and is
            // verified by marker before the tree swaps in. After install the mic works in
            // airplane mode; "remove" frees the space and the offer comes back.
            FieldLabel("🗃 offline model")
            val gwModel by dev.hermes.wearos.net.Watchkit.voskModel.collectAsState()
            Text(
                dev.hermes.wearos.domain.VoskInstall.statusLabel(installState) +
                    if (installState is VoskInstallState.Missing && gwModel != null)
                        " · ${dev.hermes.wearos.domain.VoskInstall.humanBytes(gwModel!!.sizeBytes)} via gateway"
                    else "",
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                when (installState) {
                    is VoskInstallState.Missing, is VoskInstallState.Failed ->
                        SplashChoiceChip("⬇ install", false) { installer.install() }
                    is VoskInstallState.Download ->
                        SplashChoiceChip("cancel", false) { installer.cancel() }
                    VoskInstallState.Ready ->
                        SplashChoiceChip("remove", false) { installer.remove() }
                    VoskInstallState.Unpacking -> {}
                }
            }
        }
        if (page == SettingsPage.Media) {
            // MEDIA EPIC (owner 2026-11-02: "a slider for the video quality … an option for
            // bitrate and stereo because with Bluetooth headphones you might want the stereo"):
            // these knobs ride EVERY watchkit /media URL and the ffmpeg transcode matches them
            // there (server cache keyed per shape — changing a knob re-shapes, never re-junks).
            FieldLabel("🎬 media scaling")
            val mediaQ by settings.mediaQuality.collectAsState()
            val mediaStereo by settings.mediaStereo.collectAsState()
            val mediaKbps by settings.mediaAudioKbps.collectAsState()
            Text(
                "Generated video/audio is shrunk on the gateway before it crosses the wire.",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
            )
            QualitySlider(mediaQ) { v -> scope.launch { settings.setMediaQuality(v) } }
            Text(
                when {
                    mediaQ < 34 -> "Small · ≤360p · ~350 kbps"
                    mediaQ < 67 -> "Balanced · ≤480p · ~700 kbps"
                    else -> "Sharp · ≤540p · ~1.2 Mbps"
                },
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
            )
            Spacer(Modifier.size(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Stereo audio", style = MaterialTheme.typography.caption1)
                Switch(checked = mediaStereo, onCheckedChange = { v -> scope.launch { settings.setMediaStereo(v) } })
            }
            Text(
                "Off = mono for the watch speaker; on for Bluetooth headphones.",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f),
            )
            Spacer(Modifier.size(6.dp))
            Text("Audio bitrate", style = MaterialTheme.typography.caption1)
            // 2×2, not a 4-chip line: the round bezel clipped the wide row (Display-page lesson).
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SplashChoiceChip("32k", mediaKbps == 32) { scope.launch { settings.setMediaAudioKbps(32) } }
                SplashChoiceChip("64k", mediaKbps == 64) { scope.launch { settings.setMediaAudioKbps(64) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SplashChoiceChip("96k", mediaKbps == 96) { scope.launch { settings.setMediaAudioKbps(96) } }
                SplashChoiceChip("128k", mediaKbps == 128) { scope.launch { settings.setMediaAudioKbps(128) } }
            }
        }
        if (page == SettingsPage.Voice) {
            StatusLine("tts engine", ttsState.toString())
        }
        if (page == SettingsPage.About) {
            StatusLine("stt model", if (sttPresent) "present ✅" else "missing · Voice in ▸ install")
            StatusLine("version", "v$version")
            // BATCH-4 #10 (owner's credit line, verbatim): MIT · borealBytes (tappable) ·
            // Superior ByteWorks, LLC · Clayton Young. The org name opens the GitHub on the
            // companion browser; watches with no browser fail soft to a toast (nothing else
            // on the watch can render the page anyway).
            Text(
                "MIT · 🌐 borealBytes",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClickLabel = "open github.com/borealBytes") {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/borealBytes"),
                        )
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "github.com/borealBytes", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            // BATCH-5 #22 (owner: *"we should also have a tappable link to this repo for the main
            // branch just so people can get right there … I like having one go my main borealBytes
            // GitHub profile first though"*): the profile link above stays primary; this second
            // chip opens THE PROJECT itself (root shows the default branch, main).
            Text(
                "🌐 hermes-wearos — this repo",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClickLabel = "open github.com/borealBytes/hermes-wearos") {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/borealBytes/hermes-wearos"),
                        )
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "github.com/borealBytes/hermes-wearos", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            StatusLine("made by", "Superior ByteWorks, LLC")
            StatusLine("author", "Clayton Young")
        }
    }
    } // Box (batch-5 #18 edge-scroll host)
}

/**
 * One gateway row: label + per-endpoint probe dots (winner green ●, failed red ✕, unprobed gray
 * ○) + selected marker; tap the row = SELECT (re-probe + reload chats/agents/sessions through
 * the switchAgent plumbing). ✎ edits, ✕ deletes (kept while ≥2 gateways exist — never strands
 * the watch with zero endpoints). No IconButton (wear-compose 1.4.1).
 */
@Composable
private fun GatewayRow(
    gw: Gateway,
    selected: Boolean,
    isProbed: Boolean,
    winner: String,
    results: Map<String, Boolean>,
    busy: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    canDelete: Boolean,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colors.primary.copy(alpha = 0.22f)
                else MaterialTheme.colors.surface.copy(alpha = 0.25f)
            )
            .clickable(onClickLabel = "select gateway", onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (selected) "◉ ${gw.label}" else gw.label,
                style = MaterialTheme.typography.caption1,
                color = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.onBackground,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                "✎",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClickLabel = "edit gateway", onClick = onEdit)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (canDelete) Text(
                "✕",
                style = MaterialTheme.typography.caption2,
                color = Color(0xFFE57373),
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(onClickLabel = "delete gateway", onClick = onDelete)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        // Endpoint dots — which one WON (owner: "rows of gateways (label + endpoint dots: which won)")
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            gw.apiEndpoints.forEach { ep ->
                val isWinner = isProbed && ep == winner
                val failed = isProbed && results[ep] == false
                Text(
                    if (isWinner) "●" else if (failed) "✕" else "○",
                    style = MaterialTheme.typography.caption3,
                    fontSize = 10.sp,
                    color = when {
                        isWinner -> Color(0xFF81C784)
                        failed -> Color(0xFFE57373)
                        else -> MaterialTheme.colors.onBackground.copy(alpha = 0.45f)
                    },
                )
            }
            Text(
                if (busy && selected) "probing…" else gw.apiEndpoints.firstOrNull()?.hostHint() ?: "",
                style = MaterialTheme.typography.caption3,
                fontSize = 9.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                maxLines = 1,
            )
        }
    }
}

/** Compact host label for a dot row: `hermes.tailne…:8642` / `192.168.1.50:8642`. */
private fun String.hostHint(): String =
    substringAfter("://").let { a -> if (a.length > 22) a.take(10) + "…" + a.takeLast(5) else a }

/** Inline add/edit editor (SPEC §11). EVERY field: singleLine + Done=Save + explicit buttons. */
@Composable
private fun GatewayEditor(
    draft: GwDraft,
    onChange: (GwDraft) -> Unit,
    onSave: (GwDraft) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colors.surface.copy(alpha = 0.35f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(if (draft.id.isEmpty()) "New gateway" else "Edit ${draft.id}",
            style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.primary)
        FieldLabel("Label")
        EditLine(value = draft.label, onChange = { onChange(draft.copy(label = it)) },
            placeholder = "myhost", onDone = { onSave(draft) })
        FieldLabel("API URL")
        EditLine(value = draft.apiA, onChange = { onChange(draft.copy(apiA = it)) },
            placeholder = "http://hermes.tailnet.ts.net:8642", onDone = { onSave(draft) })
        FieldLabel("API backup (optional)")
        EditLine(value = draft.apiB, onChange = { onChange(draft.copy(apiB = it)) },
            placeholder = "http://hermes2.tailnet.ts.net:8642", onDone = { onSave(draft) })
        if (draft.extraApis.isNotEmpty() || draft.extraWks.isNotEmpty()) {
            // Reassurance the hidden endpoints ride along (they used to be silently dropped).
            Text("+${draft.extraApis.size} api / +${draft.extraWks.size} wk kept",
                style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f))
        }
        FieldLabel("Watchkit URL (optional)")
        EditLine(value = draft.wkA, onChange = { onChange(draft.copy(wkA = it)) },
            placeholder = "http://host:42651", onDone = { onSave(draft) })
        FieldLabel("Token")
        EditLine(value = draft.token, onChange = { onChange(draft.copy(token = it)) },
            placeholder = "••••••", password = true, onDone = { onSave(draft) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(draft) },
                modifier = Modifier.size(width = 72.dp, height = 32.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.85f)),
            ) { Text("Save", style = MaterialTheme.typography.button) }
            Button(
                onClick = onCancel,
                modifier = Modifier.size(width = 72.dp, height = 32.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.4f)),
            ) { Text("Cancel", style = MaterialTheme.typography.button) }
        }
    }
}

/** Editor backing state — endpoints as A/B rows (deleting one leaves the other; SPEC §11). */
private data class GwDraft(
    val id: String = "",
    val label: String = "",
    val apiA: String = "",
    val apiB: String = "",
    // The editor shows two API rows, but a record can hold more (tailnet + LANs + …). The rows
    // beyond the visible ones ride along HIDDEN and merge back on save — before this, saving an
    // edit SILENTLY DROPPED every 3rd+ endpoint (owner's 3-green-dot gateway shrank under an edit).
    val extraApis: List<String> = emptyList(),
    val wkA: String = "",
    val extraWks: List<String> = emptyList(),
    val token: String = "",
) {
    companion object {
        fun of(gw: Gateway) = GwDraft(
            id = gw.id,
            label = gw.label,
            apiA = gw.apiEndpoints.getOrElse(0) { "" },
            apiB = gw.apiEndpoints.getOrElse(1) { "" },
            extraApis = gw.apiEndpoints.drop(2),
            wkA = gw.watchkitEndpoints.getOrElse(0) { "" },
            extraWks = gw.watchkitEndpoints.drop(1),
            token = gw.token,
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.caption3,
        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
    )
}

/** One W1-W4 body tool row: name + switch (persisted immediately; StateFlow drives the UI). */
@Composable
private fun BodyToolToggle(label: String, tool: String, tools: Map<String, Boolean>, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.caption1)
        Switch(
            checked = tools[tool] ?: false,
            onCheckedChange = { v -> scope.launch { settings.setBodyTool(tool, v) } },
        )
    }
}

/**
 * IME-RULE field (owner epic): singleLine + ImeAction.Done → the same action as the explicit
 * Save button. The Wear IME's "enter" turning into space/newline can no longer strand input.
 */
@Composable
private fun EditLine(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
    onDone: () -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Done,
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Uri,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colors.surface.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        textStyle = MaterialTheme.typography.caption1.copy(color = MaterialTheme.colors.onBackground),
        cursorBrush = SolidColor(MaterialTheme.colors.primary),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.35f))
            } else inner()
        },
    )
}

@Composable
private fun StatusLine(key: String, value: String) {
    Text(
        "$key: $value",
        style = MaterialTheme.typography.caption3,
        color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
    )
}

/** One option of the Launch-splash chooser: filled when it IS the current mode (owner
 * 2026-10-29). Same Button idiom as every other action row on this screen. */
@Composable
private fun SplashChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(width = 84.dp, height = 34.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.surface,
            contentColor = if (selected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
        ),
    ) { Text(label, style = MaterialTheme.typography.button) }
}

/** One row in the voice-out engine-voice picker; the stored name highlights itself. */
@Composable
private fun VoicePickRow(label: String, name: String, current: String, onSelect: () -> Unit) {
    val selected = name == current
    Button(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.surface.copy(alpha = 0.35f),
            contentColor = if (selected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
        ),
    ) { Text(label, style = MaterialTheme.typography.caption1, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

/**
 * VIDEO-QUALITY SLIDER (media epic, owner 2026-11-02: "a slider for the video quality"): a
 * three-stop track — Small / Balanced / Sharp — mapping onto the watchkit /media preset ladder.
 * Same drag physics as RateSlider (no Slider component exists in wear-compose 1.4.1), commit on
 * release so a drag never re-shapes the stream mid-finger.
 */
@Composable
private fun QualitySlider(q: Int, onCommit: (Int) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var trackW by remember { mutableStateOf(1f) }
    val stops = listOf(0, 50, 100)
    var level by remember(q) { mutableStateOf(q) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "video quality · " + when (level) {
                in 0..33 -> "small"
                in 34..66 -> "balanced"
                else -> "sharp"
            },
            style = MaterialTheme.typography.caption3,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(MaterialTheme.colors.surface.copy(alpha = 0.35f))
                .onSizeChanged { trackW = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun applyAt(x: Float) {
                            val raw = (x / trackW * 100).coerceIn(0f, 100f)
                            level = stops.minByOrNull { kotlin.math.abs(it - raw) } ?: 50
                        }
                        applyAt(down.position.x)
                        down.consume()
                        while (true) {
                            val ch = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: break
                            applyAt(ch.position.x)
                            ch.consume()
                        }
                        onCommit(level)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val knob = with(density) { 26.dp.toPx() }
            Box(
                modifier = Modifier
                    .size(with(density) { 26.dp })
                    .offset { IntOffset(((trackW - knob) * (level / 100f)).roundToInt(), 0) }
                    .clip(CircleShape)
                    .background(MaterialTheme.colors.primary),
            )
        }
        Text(
            "small ←──→ sharp",
            style = MaterialTheme.typography.caption3,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.4f),
        )
    }
}

/**
 * REPLY-SPEED SLIDER (owner 2026-10-29, replacing same-day chip buttons that clipped the round
 * face): a drag-everywhere track — touch anywhere jumps the knob there, keep dragging to fine
 * tune, release to ear-test. Slow (0.5×) sits left, fast (2.0×) right; the mapping is pure
 * (`TtsConfig.rateFromFraction`), so what the knob shows and what the engine gets cannot drift.
 * Deliberately tiny physics: raw gesture loop, no Slider component exists in wear-compose 1.4.1.
 */
@Composable
private fun RateSlider(rate: Float, onRate: (Float) -> Unit, onDone: () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var trackW by remember { mutableStateOf(1f) }
    val frac = TtsConfig.fractionFor(rate)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "reply speed · " + String.format(java.util.Locale.US, "%.2f", rate) + "×",
            style = MaterialTheme.typography.caption3,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(MaterialTheme.colors.surface.copy(alpha = 0.35f))
                .onSizeChanged { trackW = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun applyAt(x: Float) = onRate(TtsConfig.rateFromFraction(x / trackW))
                        applyAt(down.position.x)
                        down.consume()
                        while (true) {
                            val ch = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: break
                            applyAt(ch.position.x)
                            ch.consume()
                        }
                        onDone()
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val knob = with(density) { 26.dp.toPx() }
            Box(
                modifier = Modifier
                    .size(with(density) { 26.dp })
                    .offset { IntOffset(((trackW - knob) * frac).roundToInt(), 0) }
                    .clip(CircleShape)
                    .background(MaterialTheme.colors.primary),
            )
        }
        Text(
            "slow 0.5× ←──→ fast 2.0×",
            style = MaterialTheme.typography.caption3,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.4f),
        )
    }
}

/**
 * REPLY VOLUME slider (owner 2026-10-29): "% of max output" on a 5 % grid, riding ABOVE the speed
 * slider on Voice. The label names the AGENT whose override it edits; the commit lands in that
 * agent's slot (agents without one ride the global). Drag updates the readout only — persistence
 * and the ear-test preview fire on release, like the dial's volume mode.
 */
@Composable
private fun VolumeSlider(volume: Float, agent: String, commit: (Float) -> Unit, onDone: () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var trackW by remember { mutableStateOf(1f) }
    // Local drag state: quantized onto the grid so the knob and the percent always agree.
    var level by remember(volume) { mutableStateOf(TtsConfig.volumePercent(volume) / 100f) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "reply volume · $agent · ${(level * 100).roundToInt()}%",
            style = MaterialTheme.typography.caption3,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(MaterialTheme.colors.surface.copy(alpha = 0.35f))
                .onSizeChanged { trackW = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(agent) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun applyAt(x: Float) {
                            level = TtsConfig.volumePercent(x / trackW) / 100f
                        }
                        applyAt(down.position.x)
                        down.consume()
                        while (true) {
                            val ch = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: break
                            applyAt(ch.position.x)
                            ch.consume()
                        }
                        commit(level)
                        onDone()
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val knob = with(density) { 26.dp.toPx() }
            Box(
                modifier = Modifier
                    .size(with(density) { 26.dp })
                    .offset { IntOffset(((trackW - knob) * level).roundToInt(), 0) }
                    .clip(CircleShape)
                    .background(MaterialTheme.colors.primary),
            )
        }
        Text(
            "quiet 0% ←──→ loud 100% · per-agent",
            style = MaterialTheme.typography.caption3,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.4f),
        )
    }
}

/**
 * local | network | any filter chips above a voice list (owner: "then local/network, then it
 * shows the list"). Wear Button row — round-safe, caption2 tiny.
 */@Composable
private fun VoiceSourceChips(src: String, onSrc: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        listOf(
            dev.hermes.wearos.domain.VoiceCatalog.ANY to "any",
            dev.hermes.wearos.domain.VoiceCatalog.LOCAL to "local",
            dev.hermes.wearos.domain.VoiceCatalog.NETWORK to "🌐 net",
        ).forEach { (k, label) ->
            Button(
                onClick = { onSrc(k) },
                modifier = Modifier.size(width = 62.dp, height = 26.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (src == k) MaterialTheme.colors.primary
                    else MaterialTheme.colors.surface.copy(alpha = 0.35f),
                    contentColor = if (src == k) MaterialTheme.colors.onPrimary
                    else MaterialTheme.colors.onSurface,
                ),
            ) { Text(label, style = MaterialTheme.typography.caption2, maxLines = 1) }
        }
    }
    Spacer(Modifier.height(4.dp))
}

/** Settings tree pages (owner 2026-10-29 anti-forever-scroll restructure). */
enum class SettingsPage(val title: String) {
    Root("Settings"),
    // Agent keys hangs UNDER Gateways (owner 2026-10-29: "shouldn't agent keys be under
    // gateways? … too high up in the tree") — the map is learned from one gateway, so that is
    // its parent. See up() below.
    Gateways("Gateways"),
    AgentKeys("Agent keys"),
    // The watch's own tailnet node (SPEC §11.4): like agent keys, it belongs UNDER Gateways —
    // the tunnel exists to reach them.
    Tailnet("Tailnet"),
    // S5 (SPEC §4): the PIN + funnel bootstrap that FILLS the registry — fresh installs and every
    // NEEDS_AUTH edge route the wrist here ("tailnet only, always": unenrolled = told, not silent).
    Pair("Pair"),
    Display("Display"),
    Watch("Watch"),
    Voice("Voice out"),
    // BATCH-4 #13 (owner: *"there's a settings page for voice out. We need a voice in
    // settings so we can set the default one we want to use on the orb"*): the STT provider
    // picker moved OUT of the voice-out page into its own nested page.
    VoiceIn("Voice in"),
    // MEDIA EPIC (owner 2026-11-02): the A/V scaling knobs (video quality slider + mono/stereo +
    // audio bitrate) the watchkit /media endpoint reshapes generated clips with.
    Media("Media"),
    About("About"),
}

/** Back-target per page: Agent keys, Tailnet, and Pair return to Gateways; everything else to the hub. */
private fun SettingsPage.up(): SettingsPage =
    if (this == SettingsPage.AgentKeys || this == SettingsPage.Tailnet || this == SettingsPage.Pair)
        SettingsPage.Gateways
    else SettingsPage.Root

@Composable
private fun SettingNavRow(emoji: String, title: String, subtitle: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.5f)),
    ) {
        // LEADING chevron (owner 2026-11-02: "that arrow doohickey in the middle of the button
        // makes no sense where it is"). The wear Button content scope does NOT lay children in a
        // row — an explicit Row is the only way the chevron sits beside the text instead of
        // floating over it; Wear list convention puts drill-in chevrons in FRONT of the content.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("›  ", style = MaterialTheme.typography.title3)
            Column(horizontalAlignment = Alignment.Start) {
                Text("$emoji  $title", style = MaterialTheme.typography.button)
                Text(subtitle, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}
