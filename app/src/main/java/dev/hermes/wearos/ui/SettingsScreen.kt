package dev.hermes.wearos.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import dev.hermes.wearos.WearApp
import dev.hermes.wearos.data.AppSettings
import dev.hermes.wearos.domain.FontScale
import dev.hermes.wearos.domain.Lane
import dev.hermes.wearos.domain.OrbDial
import dev.hermes.wearos.domain.Gateway
import dev.hermes.wearos.domain.GatewayModel
import dev.hermes.wearos.domain.NetworkWatch
import dev.hermes.wearos.domain.RouteReport
import dev.hermes.wearos.health.BodySnapshot
import dev.hermes.wearos.health.BodyTools
import dev.hermes.wearos.location.LocationTool
import dev.hermes.wearos.net.HermesApi
import dev.hermes.wearos.net.Watchkit
import dev.hermes.wearos.voice.SpeakAloud
import dev.hermes.wearos.voice.VoskStt
import kotlinx.coroutines.Dispatchers
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
    var testResult by remember { mutableStateOf("…") }
    var testing by remember { mutableStateOf(false) }
    var wkResult by remember { mutableStateOf("") }
    var wkTesting by remember { mutableStateOf(false) }
    var gwEditor by remember { mutableStateOf<GwDraft?>(null) }
    // Agent-keys editor (SPEC §12.2): which agent's key is being typed, and the draft.
    var keyEdit by remember { mutableStateOf<String?>(null) }
    var keyDraft by remember { mutableStateOf("") }
    var gwNote by remember { mutableStateOf("") }

    val voiceOut by speak.enabled.collectAsState()
    val ttsState by speak.state.collectAsState()
    val markdownOn by settings.markdownEnabled.collectAsState()
    val bodyTools by settings.bodyTools.collectAsState()
    val bodyStatus by BodyTools.status.collectAsState()
    val pushState by WearApp.Graph.pusher.state.collectAsState()
    val gateways by WearApp.Graph.gatewayList.collectAsState()
    val selectedGwId by WearApp.Graph.gatewaySelectedId.collectAsState()
    val gwStatus by WearApp.Graph.endpoints.status.collectAsState()
    val netInfo by WearApp.Graph.network.net.collectAsState() // the link truth (§11.6)
    val sttPresent = remember { VoskStt.isModelPresent(context) }
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
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Round discipline (ask #5): plain-scroll settings column gets the safe-circle insets
            // (clock top / chin bottom / 20 dp edges) — an SLC-less screen needs it explicitly.
            .roundSafe()
            .padding(horizontal = 4.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)

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
                    val api = listOf(d.apiA, d.apiB).map { it.trim() }.filter { it.isNotEmpty() }
                    if (api.isEmpty()) { gwNote = "⚠️ needs an API URL"; return@GatewayEditor }
                    val wk = listOf(d.wkA.ifBlank { GatewayModel.derivedWatchkit(api.first()) })
                        .map { it.trim() }.filter { it.isNotEmpty() }
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
        // THE LAUNCH FILM (domain/SplashIntro owns when it may show): plays the 14 s intro on a
        // cold start, tap anywhere to skip it. OFF restores the one-frame spirit flash.
        val introOn by settings.introVideoEnabled.collectAsState()
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Launch video", style = MaterialTheme.typography.caption1)
            Switch(
                checked = introOn,
                onCheckedChange = { v -> scope.launch { settings.setIntroVideo(v) } },
            )
        }
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
        // screen — drag it eight ways". Every one of the eight directions is a lane and every lane
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

        // watchkit helper daemon (server/watchkit, tailnet :8651): health probe like the Hermes Test.
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🔊", style = MaterialTheme.typography.body2)
            Switch(checked = voiceOut, onCheckedChange = { speak.setVoiceEnabled(it) })
        }

        StatusLine("stt model", if (sttPresent) "present ✅" else "missing – get-vosk-model.sh")
        StatusLine("tts engine", ttsState.toString())
        StatusLine("version", "v$version")
    }
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
            placeholder = "http://192.168.1.50:8642", onDone = { onSave(draft) })
        FieldLabel("Watchkit URL (optional)")
        EditLine(value = draft.wkA, onChange = { onChange(draft.copy(wkA = it)) },
            placeholder = "http://host:8651", onDone = { onSave(draft) })
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
    val wkA: String = "",
    val token: String = "",
) {
    companion object {
        fun of(gw: Gateway) = GwDraft(
            id = gw.id,
            label = gw.label,
            apiA = gw.apiEndpoints.getOrElse(0) { "" },
            apiB = gw.apiEndpoints.getOrElse(1) { "" },
            wkA = gw.watchkitEndpoints.getOrElse(0) { "" },
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
