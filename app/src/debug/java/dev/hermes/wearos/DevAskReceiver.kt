package dev.hermes.wearos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import dev.hermes.wearos.voice.MediaPlayerHolder
import dev.hermes.wearos.voice.VoskStt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Debug builds only: `adb shell am broadcast -n .../DevAskReceiver -e text "..."` (or -e set_token K,
 *  -e set_watchkit K, -e render_test, -e img_test, -e chat_new, -e open_chat <id>, -e chat_dump,
 *  -e body_status, -e body_push). UI-epic additions: -e agents_list, -e agent <name>,
 *  -e sessions_dump, -e resume_session <id> [-e resume_profile <p>], -e stop_mid_run [text], -e loc_status,
 *  -e session_fork <id> / -e session_delete <id> (the Sessions long-press menu, see §12.4 follow-up),
 *  -e session_archive <id> / -e session_unarchive <id> (🗃 hide/restore, desktop semantics),
 *  -e search <q> (types into the Sessions search box — adb input text can't traverse the IME),
 *  -e screen <chat|chats|settings|sessions> (jump the UI to a screen — the ⌘ popup ignores injected
 *  touches, so this is how demo scripts reach Settings and the Chats list),
 *  -e ptt_demo <partial|off> (forces the PTT overlay for screenshots; NEVER -ez — see handler),
 *  -e fontscale <1.4|reset> (persist+apply pinch font-scale without fingers),
 *  -e viewer_demo [scale] (open the media viewer on a generated card — sub-fit zoom proof;
 *  scale like 0.5 opens BELOW fit directly, 0/false closes).
 *  -e model_picker (open the 🧠 model picker on the active chat — the SPEC §12.7 lane without a
 *  flaky synthetic dial release; waits on the gateway catalog round-trip itself).
 *  -e notify_demo plain|approval|both (post the REAL notification cards on command — the deterministic
 *  door for the demo's "off the wrist" act, see docs/DEMO.md).
 *  Gateway-epic additions: -e gw_dump, -e gw_probe, -e gw_select <id>, -e add_gw "label|api|wk",
 *  -e del_gw <id> (the cleanup half of add_gw — deletes and re-lands the selection on the
 *  surviving gateway, so a demo gateway can never be left stranded on the watch).
 *  Dial-epic additions (SPEC §12-13): -e render [list|tables|wide|mermaid|pdf|link|media|all]
 *  (inject a canned answer that exercises the media surfaces — no gateway, no model),
 *  -e slash_catalog <json> (push the 6:00 overlay's command catalog + capability flags),
 *  -e set_agent_key <agent>=<key> / -e agent_keys (per-profile gateway keys, SPEC §12.2). */
class DevAskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val token = intent?.getStringExtra("set_token")
        if (token != null) {
            WearApp.Graph.setToken(token)
            return
        }
        intent?.getStringExtra("set_base")?.let { WearApp.Graph.setBase(it); return }
        intent?.getStringExtra("set_watchkit_base")?.let { WearApp.Graph.setWatchkitBase(it); return }
        intent?.getStringExtra("set_watchkit")?.let { WearApp.Graph.setWatchkitToken(it); return }
        // AGENT KEYS (SPEC §12.2): `-e set_agent_key wear=KEY` stores one agent's gateway key; a
        // bare `-e agent_keys` makes the watch re-learn the whole map from watchkit (which reads
        // profiles/<p>/.env on the gateway box). This is how a multi-agent setup gets configured
        // without typing 64 hex chars on a watch keyboard.
        intent?.getStringExtra("set_agent_key")?.let { spec ->
            val agent = spec.substringBefore('=', "").trim()
            val key = spec.substringAfter('=', "").trim()
            if (agent.isEmpty() || key.isEmpty()) {
                Log.w("HermesWear", "dev: set_agent_key wants agent=key (got '$spec')")
                return
            }
            CoroutineScope(Dispatchers.Main).launch {
                WearApp.Graph.settings.setAgentKey(agent, key)
                Log.i("HermesWear", "dev: agent key stored for $agent (${key.length} chars)")
                Toast.makeText(context, "key set: $agent", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (intent?.hasExtra("agent_keys") == true) {
            CoroutineScope(Dispatchers.Main).launch {
                val n = try { WearApp.Graph.settings.refreshAgentKeys() } catch (e: Exception) { -1 }
                Log.i("HermesWear", if (n >= 0) "dev: agent keys learned ($n known)" else "dev: agent_keys failed")
                Toast.makeText(context, if (n >= 0) "agent keys: $n" else "agent keys: watchkit unreachable", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (intent?.hasExtra("gw_dump") == true) { WearApp.Graph.devGwDump(); return }
        if (intent?.hasExtra("gw_probe") == true) { WearApp.Graph.devGwProbe(); return }
        intent?.getStringExtra("gw_select")?.let { WearApp.Graph.devGwSelect(it); return }
        intent?.getStringExtra("del_gw")?.let { WearApp.Graph.devGwDelete(it); return }
        // -e add_gw "label|https?://api|https?://watchkit" → upsert a gateway reusing the current
        // token + agent-key map (deterministic multi-gateway demo shots, no GBoard wrestling).
        intent?.getStringExtra("add_gw")?.let { spec ->
            WearApp.Graph.devAddGateway(spec); return
        }
        if (intent?.hasExtra("render_test") == true) { WearApp.Graph.devRenderTest(); return }
        if (intent?.hasExtra("img_test") == true) { WearApp.Graph.devImgTest(); return }
        if (intent?.hasExtra("body_status") == true) { WearApp.Graph.devBodyStatus(); return }
        if (intent?.hasExtra("body_push") == true) { WearApp.Graph.devBodyPush(); return }
        if (intent?.hasExtra("loc_status") == true) { WearApp.Graph.devLocStatus(); return }
        if (intent?.hasExtra("agents_list") == true) { WearApp.Graph.devAgentsList(); return }
        if (intent?.hasExtra("sessions_dump") == true) { WearApp.Graph.devSessionsDump(); return }
        intent?.getStringExtra("agent")?.let { WearApp.Graph.devAgent(it); return }
        intent?.getStringExtra("resume_session")?.let {
            WearApp.Graph.devResumeSession(it, intent.getStringExtra("resume_profile").orEmpty()); return
        }
        if (intent?.hasExtra("ptt_demo") == true) {
            // NOTE: `am` silently mis-handles `-ez key bool` (sets pkg=true/false → the receiver
            // never sees it) — always send `-e ptt_demo <text|off>`. Any text shows the overlay
            // with that partial; "off"/"false"/"reset" clears it.
            WearApp.Graph.devPtt.value = intent.getStringExtra("ptt_demo")
                ?.takeIf { it.isNotBlank() && !it.equals("off", true) && !it.equals("false", true) }
            return
        }
        intent?.getStringExtra("splash")?.let { mode ->
            // -e splash video|static → the Display chooser, without touching the wrist UI.
            val v = if (mode.trim().lowercase() == "static") "static" else "video"
            CoroutineScope(Dispatchers.Main).launch { WearApp.Graph.settings.setSplashMode(v) }
            return
        }
        intent?.getStringExtra("splash_uri")?.let { raw ->
            // -e splash_uri content://… → take the persistable grant and store into the slot its
            // MIME type says (video→film, else still) — the exact path Settings' picker runs.
            val uri = android.net.Uri.parse(raw)
            val cr = context?.contentResolver
            // file:// needs no ceremony (app-owned external dir); am broadcast cannot hand out a
            // PERSISTABLE grant, so content:// works here only via app-assumed access.
            val granted = uri.scheme == "file" || runCatching {
                cr?.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.onFailure { android.util.Log.w("HermesWear", "splash_uri grant refused", it) }.isSuccess
            // Classify: resolver MIME first (content://), extension as the file:// fallback.
            val ext = raw.substringAfterLast('.', "").lowercase()
            val isVideo = cr?.getType(uri).orEmpty().startsWith("video") ||
                ext in setOf("mp4", "3gp", "mkv", "webm", "mov", "avi")
            if (granted) {
                CoroutineScope(Dispatchers.Main).launch {
                    WearApp.Graph.settings.setSplashCustom(isVideo, uri.toString())
                }
            }
            return
        }
        if (intent?.hasExtra("clear_splash") == true) {
            CoroutineScope(Dispatchers.Main).launch {
                WearApp.Graph.settings.setSplashCustom(true, null)
                WearApp.Graph.settings.setSplashCustom(false, null)
            }
            return
        }
        intent?.getStringExtra("fontscale")?.let {
            // -e fontscale 1.4 → persist+apply; non-numeric ("reset") → back to 1.0.
            WearApp.Graph.devSetFontScale(it)
            return
        }
        if (intent?.hasExtra("viewer_demo") == true) {
            // -e viewer_demo → open at fit; `-e viewer_demo 0.5` opens BELOW fit (sub-fit proof);
            // 0/false closes.
            val arg = intent.getStringExtra("viewer_demo").orEmpty()
            WearApp.Graph.devViewerScale.value = when {
                arg.isBlank() || arg == "true" -> 1f
                arg.equals("false", true) || arg == "0" -> null
                else -> arg.toFloatOrNull() ?: 1f
            }
            return
        }
        if (intent?.hasExtra("open_editor") == true) {
            // Prefilled prompt editor + system keyboard (owner batch 2 verification hook; now
            // also the drag-up-orb proof for the hunter era): yes/true/1/'' → default prefill,
            // anything else is the literal draft.
            val v = intent.getStringExtra("open_editor").orEmpty()
            WearApp.Graph.devEditor.value =
                if (v.isBlank() || v.lowercase() in setOf("true", "yes", "1")) "edit me on the watch keyboard"
                else v
            return
        }
        if (intent?.hasExtra("stop_mid_run") == true) {
            val what = intent.getStringExtra("stop_mid_run")
                ?.takeIf { it.isNotBlank() && it != "true" }
                ?: "count slowly from one to thirty, one number per second, no tools"
            WearApp.Graph.devStopMidRun(what)
            return
        }
        if (intent?.hasExtra("approve") == true) { WearApp.Graph.session.approve(); return }
        if (intent?.hasExtra("deny") == true) { WearApp.Graph.session.deny(); return }
        if (intent?.hasExtra("chat_new") == true) {
            val meta = WearApp.Graph.session.createChat()
            Log.i("HermesWear", "chat_new id=${meta.id} session=${meta.sessionTitle}")
            return
        }
        // RENDER HARNESS (SPEC §13.3): inject a canned answer that exercises the media surfaces —
        // `-e render list` prints the ids, `-e render tables|wide|mermaid|pdf|link|media|all`
        // injects one. No gateway, no model: the renderer is what's under test.
        intent?.getStringExtra("render")?.let {
            val id = it.trim().ifEmpty { "all" }
            val ids = dev.hermes.wearos.domain.RenderCase.ids()
            if (id == "list") {
                Log.i("HermesWear", "dev: render cases = $ids")
                Toast.makeText(context, "render: $ids", Toast.LENGTH_LONG).show()
                return
            }
            WearApp.Graph.devRender.value = id
            Log.i("HermesWear", "dev: render case '$id' queued")
            return
        }
        // PUSHED COMMAND CATALOG (SPEC §12.3): the 6:00 overlay reads gateway rows + capability
        // flags from this JSON. A real install gets it from watchkit `GET /catalog`; over adb the
        // dev can paste a hand-written file and watch the overlay change without a gateway.
        intent?.getStringExtra("slash_catalog")?.let {
            val raw = it
            CoroutineScope(Dispatchers.Main).launch {
                WearApp.Graph.settings.setSlashCatalogJson(raw)
                val n = dev.hermes.wearos.domain.CommandCatalog.parse(raw).entries.size
                Log.i("HermesWear", "dev: slash catalog pushed ($n rows, ${raw.length} chars)")
                Toast.makeText(context, "commands: $n rows", Toast.LENGTH_SHORT).show()
            }
            return
        }
        // SESSION MANAGEMENT (owner: long-press → fork/delete). The adb twins of the sheet:
        // `-e session_fork <id>` copies the session into the current agent and opens it;
        // `-e session_delete <id>` deletes it for real. The source profile is resolved from the
        // cached Sessions row, so drive it with ids straight out of `-e sessions_dump` / the list.
        intent?.getStringExtra("session_fork")?.let { id ->
            WearApp.Graph.session.manageSessionById(id, fork = true) { ok, msg ->
                Log.i("HermesWear", "dev: session_fork ${if (ok) "ok — $msg" else "FAILED — $msg"}")
            }
            Log.i("HermesWear", "dev: session_fork '$id' queued")
            return
        }
        intent?.getStringExtra("session_archive")?.let { id ->
            WearApp.Graph.session.manageArchiveById(id, archived = true) { ok, msg ->
                Log.i("HermesWear", "dev: session_archive ${if (ok) "ok — $msg" else "FAILED — $msg"}")
            }
            Log.i("HermesWear", "dev: session_archive '$id' queued")
            return
        }
        intent?.getStringExtra("session_unarchive")?.let { id ->
            WearApp.Graph.session.manageArchiveById(id, archived = false) { ok, msg ->
                Log.i("HermesWear", "dev: session_unarchive ${if (ok) "ok — $msg" else "FAILED — $msg"}")
            }
            Log.i("HermesWear", "dev: session_unarchive '$id' queued")
            return
        }
        intent?.getStringExtra("session_delete")?.let { id ->
            WearApp.Graph.session.manageSessionById(id, fork = false) { ok, msg ->
                Log.i("HermesWear", "dev: session_delete ${if (ok) "ok — $msg" else "FAILED — $msg"}")
            }
            Log.i("HermesWear", "dev: session_delete '$id' queued")
            return
        }
        // DIAL PREVIEW: pins the ring open ~8 s for a screenshot (adb can't hold a drag mid-stroke).
        if (intent?.hasExtra("dial_preview") == true) { WearApp.Graph.devDialPreview.value = true; return }
        // MODEL PICKER PIN (`-e model_picker`): opens the 🧠 picker like a lane release would.
        if (intent?.hasExtra("model_picker") == true) { WearApp.Graph.devModelPicker.value = true; return }
        // NOTIFY DEMO (`-e notify_demo plain|approval|both`): posts the REAL notification shapes on
        // command — the settle card and the Approve/Deny approval card (runId "demo-run", so a
        // tapped Approve lands in ApprovalNotificationReceiver and fail-softs) — the deterministic
        // door the demo capture needs instead of racing a live run's timing (§DEMO.md).
        intent?.getStringExtra("notify_demo")?.let { mode ->
            val app = context?.applicationContext ?: return
            // A FRESH id per post (unless "reuse"): a re-posted id keeps its READ flag, and
            // OneUI only fronts the notification drawer when something unread arrived.
            val n = if (mode.startsWith("reuse")) System.currentTimeMillis() / 86_400_000L
                    else System.currentTimeMillis()
            val m = mode.removePrefix("reuse")
            // Optional `-e notify_chat <id>`: aim the card + its "Open session" at a REAL chat
            // (the deep-link beat points at a live session instead of the local demo id).
            val cid = intent?.getStringExtra("notify_chat")?.takeIf { it.isNotBlank() } ?: "demo-$n"
            if (m != "approval") {
                dev.hermes.wearos.notify.Notifs.settle(
                    app, cid, "wear-demo brief",
                    "Harbor tonight: 8°C, wind SW 12 km/h, aurora KP 6 — the plot is in the chat.", false,
                )
            }
            if (m != "plain") {
                dev.hermes.wearos.notify.Notifs.approval(
                    app, cid,
                    dev.hermes.wearos.domain.ApprovalInfo(
                        "demo-run-$n", "demo-req-$n",
                        "Send the buyer reply to Hannah? (draft ready)",
                        listOf("approve", "deny"),
                    ),
                )
            }
            Log.i("HermesWear", "dev: notify_demo '$mode' posted as #$n")
            return
        }
        // SEARCH AS A THUMB TYPES (`-e search haiku`) — adb input text can't traverse the Wear IME.
        intent?.getStringExtra("search")?.let { WearApp.Graph.devSearchQuery.value = it; return }
        // CATCH UP THE OPEN CHAT (`-e catchup go`) — same door the pull-up gesture uses.
        intent?.getStringExtra("catchup")?.let {
            WearApp.Graph.session.catchUpActive(manual = true) { ok, n ->
                Log.i("HermesWear", "dev: catchup ${if (ok) "ok +$n" else "FAILED"}")
            }
            Log.i("HermesWear", "dev: catchup queued")
            return
        }
        intent?.getStringExtra("open_chat")?.let { WearApp.Graph.devOpenChat.value = it; return }
        intent?.getStringExtra("screen")?.let { WearApp.Graph.devScreen.value = it; return }
        if (intent?.hasExtra("chat_dump") == true) { WearApp.Graph.session.dumpChats(); return }
        if (intent?.hasExtra("stt_status") == true && context != null) {
            val model = if (VoskStt.isModelPresent(context)) "present" else "missing"
            Log.i("HermesWear", "stt model=$model tts=${WearApp.Graph.speak.state.value}")
            return
        }
        intent?.getStringExtra("speak")?.let { WearApp.Graph.speak.speakFinal(it); return }
        intent?.getStringExtra("play_url")?.let { MediaPlayerHolder.play(context!!, it); return }
        val text = intent?.getStringExtra("text") ?: return
        WearApp.Graph.session.ask(text)
    }
}
