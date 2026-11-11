# HANDOFF — Wear Kit, voice-out milestone (updated at every slice boundary)

**As of:** 2026-11-09 (later) · **From:** weardev desk (slices 2a–3 shipped; TTS bring-up deep-dive below) · **To:** next session of weardev
**You pick up here.** If this file disagrees with your memory, this file loses to the branch's `docs/SPEC.md` §19, which loses only to a fresher `git fetch`.

## 2026-11-11 ~00:50 — pane enrollment SHIPPED (and the three-alarm ghost that hid it)
- Pane now enrolls too: ➕ add a voice → name → ● record (system mic, live seconds, auto-stop at 12 s) → ▶ play / ✓ confirm / ↺ redo → same POST /voice card_create as the wrist → automatic Parakeet transcription at creation + engine-rise backfill sweep (voiceout.transcribe_pending) — no card stays deaf, no syllabic guess survives contact with a listening engine.
- MediaRecorder RETIRED: this Electron build never fires its stop→onstop; the pane taps raw PCM (ScriptProcessor) and assembles WAV synchronously — no codec, no async blob decode. 3 s floor enforced twice (stop + gateway re-proves).
- THE GHOST: ✓ confirm 'did nothing' for four deploy rounds because the handler's first line referenced `_voice` (atom is `$voice`) — a ReferenceError inside an onClick is swallowed by the framework, ESM --check passes, pytest passes (routes tested directly, no browser in the loop). The gateway's own witness log (every card_create logs label/sizes/outcome) proved 'zero POSTs since restart' and named the failure class. Doctrine: server-side witness beats remote UI guessing; click-handler throws are invisible.
- UX per owner: buttons LABEL themselves ('✓ confirm', not a lone ✓ next to real checkboxes); failures print their reason in the note line (no .catch(()=>{})); mic-open + first timer tick land WITH the click; takes travel gzip once the gateway reports a revision (old gateway keeps raw — pane never outpaces its backend).
- TTS: singleton spawn guard + system-wide reaper (twin workers ate the GPU twice); default checkbox is theme-proof (hard green accent + '● default' text chip, optimistic tick); deleting the default hands the tick to the newest survivor; health rows speak human; sibling-lane poll answers 403 not a traceback.
- Gateway restart: owner-driven, one bounce at 00:46 loaded all of the above; air_runner single worker on cuda. Pin 2927b004.

## 2026-11-10 ~23:00 — ➕ new voice enrollment ON THE WATCH (SPEC §19.5)
- PROMO VIDEO shipped: OBS 23:05 capture (104 s, 720p) → crf30/24fps → 2.7 MB, privacy mosaic on BOTH session-list layouts (main-app intro t<6 box y200..655, wear-kit pane y52..248) — QA'd clean at 3/40/95 s. Repo: docs/promo/2026-11-10/demo.mp4; PR #151546 comment 6786789643. Recipe: split=3 crop→area down ×16→neighbor up→overlay enable windows, +faststart so it progressive-plays from raw URL.
- Owner ruling: a screen under the voices list — name, ONE scripted take, hear it back, retry or accept. Built: VoiceEnroll.kt (full-bleed Dialog, wear-M2, live mic meter via MicLevel, 12 s cap), GatewayTts.enroll() → lane POST /voice/card_create (already existed and was tested), CodecMuxers.wav() PCM→RIFF (JVM-tested), voiceout.status() now serves `prompt` = SCRIPTED_PROMPT (single source; test_voice_api pins prompt-in-snapshot + transcript auto-fill law). JVM 523/523; python 149 pass / 1 known fixture drift.
- HARDWARE-PROVEN 23:07 on SM-L705U: Settings ▸ Voice out ▸ ➕ → name → READY (server prompt rendered verbatim) → ● record (live meter, real 8.6 s room-noise take) → ✓ accept → card `watch-voice-76bb94ae` appeared in the watch list — full lane round-trip incl. gateway validation + ref_rate measure. Junk card deleted (yaml rev 102; clay-synth untouched). Enroll shot on PR #151546 (comment 6786740540); mirror re-pinned a968ac3 (tree-identical). PR comment 6786357548 corrected per owner: core PR ships reply text as ONE chunk at turn end — the 'end-of-sentence speech starts earlier' claim is not live; core PR's value there is voice-identity plumbing for bot mode/chat rooms.
- Gotcha: `input swipe` on this watch must stay inside 480x480 — y>480 swipes run but no-op silently (wasted a dozen 'scrolls').
- Deploy note: voiceout.py changed → gateway restart needed for the live lane (detached systemd-run trick); plugin dir + dashboard refreshed.
- Hardware proof pending owner's voice: open Settings ▸ Voice out (gateway source) → ➕ new voice → record → ✓ → new card appears in the list (mine: clay-synth row + fresh card).

## 2026-11-10 ~22:45 — boot-crash fix + promo screenshots on the catalog PR
- **APK boot-crash (owner: "starts the video then dies")**: `TailnetService.kt:98` NPE — `runCatching{URI(base).host}.getOrDefault("")` passes a null RESULT (getOrDefault guards exceptions only; URI.host is null for opaque bases) into phase()'s non-null gatewayHost; the service killed the process at first status emit after the 22:29 restart. Fix = `getOrNull() ?: ""` (`b612274`). Hardware-proven: boot clean, tunnel ACTIVE, endpoints re-picked to the completed http://<host>:8642 (bare-host minimal input works on glass).
- **Mirror**: no-op for this fix (Kotlin-only; mirror carries hermes-plugin/).
- **Catalog PR #151546**: marked ready-for-review (gh pr ready — the REST /ready endpoint 404s on this token); promo comment posted (https://github.com/NousResearch/hermes-agent/pull/151546#issuecomment-6786346400) with 3 privacy-passed screenshots + voice-out/voice-cloning/HF-token narrative. Shots live in repo `docs/promo/2026-11-10/` (jpg + privacy_pass.py reproducible). Privacy pass: unchecked agent names mosaic'd, my-agent rows (default/wear/wear-demo/weardev/weather) kept crisp with amber ring on the clay-synth row; settings shot mosaic'd Applies-to chips + secret fields, amber ring on the HF-token row; plugins shot mosaic'd foreign sidebar rows. Uploads to api.github.com assets = 404 on our token (no upload scope) → raw.githubusercontent from our own repo instead.
- Watch: APK 22:38 build (boot fix + minimal-input normalize + mirror-law), paired sm-l705u, adb 192.168.10.159:35271.
- Next: owner reopens desktop app → new pane (wear kit host row + tooltips); demo clip optional; hackathon video <90s; Typeform.

## 2026-11-10 (night) boundary — PR #8 MERGED; fresh-install ceremony done; enable law reaches the watch (SPEC §19.4(o))

- **PR #8 merged** into `feat/hermes-plugin` @ `cc92c61` (ledger `28e8ead` on top); worktree `hermes-wearos-plugin` is the new floor. Mirror `borealBytes/hermes-wearos-plugin` pr-8 = content-identical; pane pin refreshed via `plugins install --force --ref` (pipeline in this file's Deploy traps).
- **Fresh-install proof:** uninstall → `setup.sh --skip-build --config watchos.config.json --agent-keys` → tailnet REUSABLE key broadcast → ACTIVE → pair window → `-e pair_knock` → logcat code → `POST /pair/claim` → pickup ≤5 s. Gotchas learned: API token rotated mid-ceremony ⇒ claim 500 (journal names the 401 instantly); `watchos.config.json` hostname does NOT ride the key-broadcast (node enrolled as `hermes-wearos`) — fix in-app; a self-restart while this session hosts tool calls drains only after the turn yields (never panic at `deactivating`).
- **Pane settings honesty:** every secret description now names its admin URL + exact scopes; the APK line warns REUSABLE/non-ephemeral (the trap that ate the evening). Same links ride the Pair card as anchors.
- **The (o) batch (owner: "swipe between chat/sessions/agents… should only show the enabled ones"):** watchkit `/agents` filters by the plugin grant map (mtime-hot, `pairing_store` pin, HERMES_HOME NOT trusted — a named-profile shell poisoned the first deploy); MainActivity `SwipeForward` restores the ring's forward leg for fingers (left-drag advances; right-drag yields to edge-back). JVM tests + install proven on the Ultra: picker shows exactly the pane's ticks.
- **Still open tonight:** owner eyeball of swipe paging + checkbox toggle on the live watch; <90 s X video; catalog PR blurb; Typeform. Box hygiene owed: 27 orphaned desktop-ssh connectors, `linux-gpu-fallback.json` SwiftShader pin, `mcp-stderr.log` 415 MB truncate.
- **No-flash enable (2d70053):** `/enable` now splices the mutated row into the cache synchronously (`refresh_inventory_row`) — the pane's next fetch answers the click; fan-out runs behind. Electron lesson: manual cp of `~/.hermes/desktop-plugins/wearos/plugin.js` does NOT hot-reload a running desktop app (only install events do) — after cp, the owner must quit & reopen the Hermes desktop, not just the pane tab. This explains the 'flashes' surviving two earlier deploys.


## 2026-11-10 (late) boundary — GPU slice + twin fix live; DESKTOP READ-ALOUD NOW STREAMS (contest ruling)

- **Contest-compliance ruling (owner: "can we rewrite stuff in core?"):** the admission rule ('Public surfaces only') forbids the *plugin* from patching core at runtime — it does not forbid contributing to core. The Wear Kit entry is our plugin repo at a pinned SHA and works on vanilla core (the desktop lag is graceful, not a break). The streaming fix is therefore shipped as a **separate hermes-agent PR**, never as a monkey-patch inside the plugin. Full reasoning: SPEC §19.4(n).
- **Streaming read-aloud (owner: "start as soon as we have it"):** hermes-agent branch `feat/desktop-read-aloud-stream` @ `318996aa25` — pending replies open a `SpeechStreamSession`, wakes append the tail, `finish()` at seal; server SentenceChunker + our `streams_pcm` provider synthesize sentence one mid-generation. 285 composer-hook tests green (2 new); eslint clean. Packaged desktop app on this box rebuilt from the commit (`wear-desktop-rebuild3`, exit 0 — check `/home/clay/.hermes/profiles/weardev/cache/scratch/desktop_build.log`); owner restarts the Hermes desktop app to pick it up.
- **Voice-in 🎤 card (owner: "this needs a nice Voice-in thing like Voice-out has"):** the STT strip and its installed-branch twin are now ONE card mirroring Voice-out's anatomy — header + right-side chip (`✓ model ready — Q8_0` / `⏳ <phase>% …` / `⚠ checksum re-verify pending` / `not installed`), no silent gap when installed-but-unverified (the old branch rendered nothing). Quant selector, disclosure copy, consent card, progress+Cancel all behaviorally identical.
- **Deploy trap found (owner: "don't see the voice-in header yet"):** the desktop app loads the PUBLISHED half `~/.hermes/desktop-plugins/wearos/plugin.js`, refreshed by reconcile only on install events — copying to `~/.hermes/plugins/wearos/desktop/` + restarting the dashboard is NOT enough for the pane. Pane deploys must copy BOTH paths; owner reopens the pane, no restart.
- **Pane-gate lesson (15:50):** the app evaluates `plugin.js` as ESM (`plugin_validate_desktop.py` header, `runtime-loader.ts`), so `node --check file.js` is a FALSE GREEN — CJS grammar swallows line-gutter damage (`N|import …`) and the app then dies at the first import with exactly `Unexpected token '{'`. The 1fd33bf voice-in rewrite wrote read_file's `N|` output back as file content; fixed in 1e225ee, mirror re-synced (pr-8@44a7a4ac) and the install re-pinned via `hermes -p default plugins install borealBytes/hermes-wearos-plugin --force --ref <40-sha>` (pin lives in `~/.hermes/plugins/.install-metadata.json`). From now on gate pane JS with `node --input-type=module --check < file`.
- **Demo caution:** a future `hermes update` restores the vanilla renderer until the core PR merges — reapply `318996aa25` or rebuild before filming the X video.

## 2026-11-10 (evening) boundary — RESIDENT ENGINE shipped; WearKit provider live; SENTENCE-BY-SENTENCE both lanes; ON-WATCH UI PARKED by owner

- **Resident engine (owner: "keep the model loaded")** shipped: reaper off by default (`WEARKIT_TTS_IDLE_S=900` opt-in), boot-warm thread loads at gateway start, voice switching free (zero-shot per-request refs — only a MODEL-weights swap reloads). README discloses ~3.4 GB RSS + nice(10)/8-thread cap. SPEC §19.4(j).
- **Provider live for the owner:** `tts.provider: wearkit-gateway` set in `~/.hermes/config.yaml` (backup `config.yaml.bak-wearkit-tts`); the Voice-out card's selection resolves via `voices.__default__` (provider `_resolve_voice` chain: settings voice > agent binding > `__default__` > single card — never refuses for a missing 'desktop' binding). Provider slice commit added `_resolve_voice` + honest rate-drift raise.
- **Sentence-by-sentence BOTH lanes (owner CPU shot load 39: "not waiting for the full damn pass"):** `voiceout._sentences` cuts long replies (≤160 chars stays one clip, <24-char fragments merge forward); each sentence renders as its own cache-keyed short draw, stitched with 80 ms breaths, karaoke timelines shifted. Per-sentence cache = repeated sentences replay from disk forever. `tts_provider` declares `streams_pcm`+`stream_sample_rate=24000` so the desktop speaker's SentenceChunker starts playback at sentence one. Engine lifecycle: atexit terminate→kill grace + `_SHUTTING_DOWN` re-ignite guard (resident workers die with the gateway). SPEC §19.4(k). 137 pytest green (+10).
- **ON-WATCH TTS PARKED (owner, twice: "remove on-watch TTS… and the watch model version… just change the interface so we don't show those buttons"):** `WATCH_TTS_UI = false` at top of desktop/plugin.js hides the 'watch' quant dropdown, swaps wrist copy for gateway wording, hides the recorder note. Card STAYS (gateway install/audition/dial/cards live there). All watch-side code merged and untouched — flip the constant to restore. SPEC §19.4(l).
- **Ops notes learned:** never `systemctl restart` the gateway from inside a gateway-hosted tool call (the bounce kills the turn; the drain also waits on in-flight tools — that was the 'stuck deactivating'); plugin submodules import `try: from . import X` first; dashboard AND gateway must restart for provider/schema changes to surface. WORKER COUNT TRUTH: gateway and dashboard are separate processes and BOTH host the plugin — steady state is one worker each (~6.8 GB total); a stuck drain left THREE workers on the box (94 GB RAM used). tts_air now sweeps init-parented strays before any spawn and enforces one live worker per process (SPEC §19.4(k)). Future slice: pane routes proxy to the gateway's lane voice surface so exactly ONE worker exists (watch-side Compose slider parked anyway).
- **Next:** owner dictation test on Community Q4 (Settings reopen needed once for the cached schema); <90 s X video, catalog PR, Typeform (Nov 11 5 PM EST). Open: pair payload :8642-vs-lane-port bug; mcp-stderr.log 415 MB truncate; watch-side Compose slider (parked lane anyway now).

## 2026-11-10 (afternoon) boundary — PACING AT PLAYBACK; pane status honesty; fresh APK on the Ultra

- **Owner ruling implemented:** gateway renders natural 1x, cache key speed-free (v2); the dial is pure player state (watch `PlaybackParameters(speed,1f)` pitch-preserved, pane `playbackRate`+`preservesPitch`). No re-warm on dial moves. SPEC §19.4(g).
- **Pane:** speed slider drag-fixed (was busy-gated per change event → one-notch steps); optimistic echo + 250 ms debounced silent commit. ▶ shows LIVE progress ('loading voice model ≈2 min, once' / 'queued: N (FIFO)' / 'synthesizing') from `tts_air.engine_state()` via GET /voice `air.engine`, ends at 'done ✓'. Engine chip on the card header.
- **Engine:** retry attempts fixed to 3 (the tuple had silently stayed (1,2)); `_QUEUED/_LAST_OK` feed engine_state; silence guard + self-healing cache from (f) intact. 121 pytest green, JS node-check clean.
- **CPU discipline (owner htop shot, load 24):** refusals keep the warm worker (ProtocolRefusal — no SIGKILL+reload on an honest 'no'), retries rotate temperature (0.7→0.85→1.0) alongside the argv-level seed, workers capped OMP/MKL=8 + nice(10). The seed-alone rotation had been a lie (neutts seeds at construction only; respawn replayed the same stream — why one line drew silence 3/3). Proof: that line rendered audible 5.26 s peak 25721 T 42 s. SPEC §19.4(h). Note: the htop spike also carried non-desk load (sglang scheduler 99%, pm-runtime worker, the research desk's `plugins list` probes) — the TTS worker is now the politest thing on the box.
- **WearKit Gateway provider (owner screenshot, Settings ▸ Voice):** tts_provider.py registers `wearkit-gateway` ('WearKit Gateway') into core's TTS registry — it now sits in the provider dropdown (live-confirmed in /api/config/schema) and routes ALL Hermes speech through voiceout → the same one warm worker/FIFO/cache the watch uses. Voice = the pane's bound card. 6 new tests; 127 green. Restart BOTH hermes-dashboard and hermes-gateway user units after touching the provider (the dropdown reads the :9119 process's registry). SPEC §19.4(i).
- **Hardware:** APK 12:07 build (chunked-push md5 7e7e8631…, pm install Success) — watch audition + replies now apply the dial at playback. synth-cache purged (v1 keys gone). Gateway bounced post-sync (both roots + desktop-plugins bundle).
- **UNAPPLIED:** AGENTS.md §8 tts_air line still says 'stdlib time-scale' (protected-file approval timed out — say 'yes to the AGENTS.md edit' or edit by hand).
- **Next:** owner taps ▶ (pane first, then watch row) → expect first call ≈2 min with live note, then instant 'cached'; then <90 s X video, catalog PR, Typeform (Nov 11 5 PM EST). Pair payload :8642-vs-lane-port bug still open.

## 2026-11-10 boundary — REAL WATCH ON THE LIVE GATEWAY; host engine proves itself

- **Watch paired for real (production path, owner's Ultra SM-L705U):** DevAskReceiver broadcasts replace UI automation entirely — `-e pair_knock <handle>` (handle = `{node}.{tailnet}` FQDN stem; the app appends `.ts.net`), code lands in logcat, approve via `wearos.approve_pair(code)`. Pairing v2 minted the one-use tailnet key **itself** (needs the `tailscale_api_token` secret visible to the approving scope — now in `~/.hermes/.env`; lowercase name, rule 11). Node self-enrolled as `weardev-watch.tailb12aa5.ts.net`; two agent runs executed over the lane (error=false).
- **PORT TRUTH (branch bug, open):** the pair payload writes `gw=…:8642` but the private API actually binds the derived tailnet lane (`watchkit_port+3`, here `100.69.144.33:42654`; funnel door at +2 loopback). The app must use the lane port for `$baseUrl/v1/runs` — on-device workaround was `set_base http://tuxdesktop.tailb12aa5.ts.net:42654` (NO `/p/` path: profile rides the token; the `/p/weardev` path is the watchkit companion only). FIX: pair payload + `set_base` guidance must carry the API's bound port.
- **Host engine PROVEN:** installed community-q8 (6.7 GB cache + hf_token copied from the demo container via `docker exec … tar`), voiceout CARDS+prefs same trick (the `clay-real` owner-recorded card; the seed-pack GitHub release is NOT cut yet — `SEED_SHA256=PENDING`, slice-6). First cold gateway synth: 271 s → 4.12 s of real speech at 24 kHz (RMS-verified); `SYNTH_TIMEOUT` 180→600 shipped `4914b8a` (cold-after-install runs must be allowed to warm; 120 tests green).
- **Standalone-probe trap:** any scratch script must pin `env HERMES_HOME=/home/clay/.hermes` — the session env pins it to the weardev profile root and `get_hermes_home()` precedence hides it (`installed: false` was a probe lie, not the engine).
- **Next:** scrcpy screen+device-audio capture of a spoken reply on the Ultra (watch is paired, engine warm); then <90 s X cut, catalog PR, Typeform (Nov 11 5 PM EST). scrcpy v5.0.1 at `~/.hermes/profiles/weardev/cache/scratch/scrcpy-linux-x86_64-v5.0.1/` (device-audio capture proven).

## 2026-11-09 latest boundary — THE DEMO SEQUENCE IS IN CODE (`docker/wearos-demo/demo-recording.sh`)

- **One command = a commit-ready demo pack:** `docker/wearos-demo/demo-recording.sh [--text FILE] [--name DIR] [--container wearos-gw] [--min-coverage 95] [--from STAGE]`. Stages: sync (plugin -> container + **s6 service reload** — NEVER container restart: cont-init re-bakes the stale image plugin on boot, measured twice) / record (**one** product `soundtest` call, full text) / eartest (raw Parakeet rows vs script, difflib ORDERED CHAR-STREAM coverage @95% — tokenizer-proof and slur-sensitive; word-greedy walks proved brittle to one stray short token (measured). Runs on HOST with HERMES_HOME pinned to the STT engine's root) / shots (Xvfb+chromium+pane_proxy, mock `?autoplay` — no xdotool coordinates) / clip (`wearos-clip.sh`: 55 Hz high-pass + limiter + showwaves + caption window) / pack (opus/ogg/webm + `align-words.json` + `SHA256SUMS`). Nothing auto-commits.
- **Product changes this forced:** (1) `tts_air.synth` now sentence-chunks at the 2048-token KV ceiling (~120 chars/gen; monster sentences pass through alone) with the demo-proven 30 ms seam — the subagent's stitch.py was demo-side compensation for a product gap; (2) voice engines register at PLUGIN LOAD, not only watch-lane bind — a dashboard on a host without tailscaled had a dead soundtest (rig-measured). Tests: `_sentences` order/limit/passthrough, suite 120/1 skipped. (3) synth temps are UNIQUE per call — two synths on the fixed `synth.wav` name raced to a 500 (killed client's server-side thread keeps writing; take-6 measured), and `.txt`/`.ref.txt` siblings are cleaned in `finally` (the tmp-litter follow-up below is now handled for synth).
- **Shots stage handshake (all measured across takes 8–15):** the capture waits for the pane_proxy `PROXIED POST .../voice -> 200` log line, NOT pixels — a frame going stable during the (long, ~8 min full-text) synth lies. pane_proxy timeout is 1200s (180 was a measured cliff); the mock's `?autoplay&text=` hook injects the REAL narration and clicks at +2.5s; the stage cleans X99 locks and strays on entry and SIGKILLs chromium children on exit (orphans poisoned the next take). Stage-idempotent: shots/clip ship their own files, so `--from shots` never serves a stale mock.
- **Follow-up noted, not yet done:** `torchinductor_*` dirs still land in the tts root (TMPDIR sweep at synth start); `mirror-proof.sh` raw path should call the product `synth()`; fresh-container first run needs the STT install (~10 min) before eartest.

## 2026-11-09 LATER boundary — weight cache, quant menus, raw-token runner, codec ruling

- **Shipped since the slice-3 notes below:** shared `weight_cache.py` (hash-matched resume/hardlink reuse; backbone+STT+codec all ride it, `8cd12bc`); STT 6-variant quant menu; voice TTS 3-source menu (`official-q8`/`community-q8`/`community-q4`, default `community-q8`); `_profile_home` honors `HERMES_HOME` (sandbox/relocated installs); upstream merges through the STT sudo-gate card (UNION resolution in plugin.js).
- **Engine findings (all measured, all in the code's comments):** (1) neutts needs **python<3.14** + **torchao==0.11.0** (import smoke gates installs, `install --repair`); (2) local GGUF backbones REQUIRE `language="en-us"`; (3) **llama_cpp_python 0.3.36** (neutts' unpinned `llama` extra) strips control tokens from rendered text → vendor `infer()` finds "no speech tokens" on EVERY community GGUF; the RUNNER now generates at **raw token level** against the GGUF vocab (tokens exist: 65,536 speech specials, ids 43690+); (4) raw loops must pass **repeat_penalty=1.1** (default-off → single-token buzz); (5) **the codec has NO mirror**: bezzam/neucodec-hf is the PAPER'S model (flat acoustic_* keys, 517-key w2v-Bert frontend, no pytorch_model.bin — roundtrip probe: every frame → ONE code) and both loaders assert the gated repo-id STRINGS. → official `neuphonic/neucodec` + free-account token is the only route; test pins it until a roundtrip-proven port exists.
- **BLOCKER for the demo video:** needs the owner's HF read token (`hf_…`) for `neuphonic/neucodec` (gated: auto — sign the two repo pages with the free account). Then: rerun `docker/wearos-demo/mirror-proof.sh` in the `wearos-gw` container (HF_TOKEN + HF_HOME env) → narration.wav → ear test → pane+watch capture → inline here.
- **Demo container:** `wearos-gw` (fresh- installs the branch plugin from `/opt/data/plugin-src`; dashboard :19119, gateway :18642, token `weardemo`). Host scratch: `~/.hermes/profiles/weardev/cache/scratch/wearos-demo/` (mirror/, runner probes, logs).
- **Test recipe now:** `TMPDIR=/tmp PYTHONPATH=tests /tmp/wear-pytest-venv/bin/python -m pytest tests/` (venv carries ruamel.yaml; suite **119 passed, 1 skipped** at this commit).

## Where things stand (verified, not claimed)

- **Repo:** `borealBytes/hermes-wearos` (monorepo — plugin code edits happen here; `hermes-wearos-plugin` mirror is CI-only, zero human commits).
- **This worktree:** branch `feat/voiceout`; base merged through PR #6 head `e24bd5a` + `feat/hermes-plugin` `a417853`-era tip. PR #8 stays draft until slices 3–7 land.
- **Slice 1 SHIPPED** (`voiceout.py` + 8 tests — see the 2026-11-09 research-desk notes below the line).
- **Slice 2a SHIPPED** `hermes-plugin/tts_air.py` (468 lines): NeuTTS Air pinned adapter — private venv install (`stt_install.py` pattern), size-gated gated-HF fetch (802,658,528 B; consent+token named on 401/403), subprocess runner, stdlib linear time-scale, `MODEL_SHA256="PENDING"` (first token-carrying install records it), `register_if_ready()`. Tests 11/11 (`test_tts_air.py`, stdlib unittest — run `python3 tests/test_tts_air.py`).
- **Slice 2b SHIPPED** `hermes-plugin/align_parakeet.py` (252 lines): word timestamps from the Parakeet/CTA build `stt_install.py` already produces (`timestamps="word"`, pin `5700f127`); recognized rows re-blended onto the SCRIPT, dropped words get proportional slots flagged `"align":"estimated"`; absent engine → `align:"none"` (reported, never silent). Tests 8/8.
- **Slice 2c SHIPPED** `/voice/*` on the private lane: `private_api.py` gained the transport (node-law gate, `_read_body(cap)`, `_handle_voice`, PUT verb, raw-audio streaming), `__init__.py` gained `_lane_voice` (prefs GET/PUT with rev-merge · cards snapshot · card_wav · card_create/delete · synthesize→b64) and `_voiceout_register_engines()` at lane start. Identity = WHOIS-verified node **bound to a paired device**; no bearer anywhere on this surface. Tests 10/10 (`test_voice_api.py`, real HTTP + fake whois).
- **Full pytest suite on this box: 112 passed** (was 2 red — fixed by RUNNER hygiene, not code: `TMPDIR=/tmp`, Hermes-managed interpreter, keep inherited `PYTHONPATH`). Recipe in SPEC §19.4; also: do NOT add `hermes-plugin/tests/__init__.py` (double-conftest breaks the module-identity test).
- **Slice 2d (proof infra) SHIPPED:** `docker/wearos-demo/` — image `hermes-wearos:voiceout` layered on the owner's `hermes-film:dev` (upstream hermes-agent + Xvfb/ffmpeg film box). Bakes THIS branch's plugin (cont-init seeds it into `/opt/data/plugins/wearos`), pytest+pylibs, `install-tts.sh` (gated HF install via `HF_TOKEN`/`HF_TOKEN_FILE`), and `demo_lane.py` (pairs a device, drives EVERY `/voice/*` route over real HTTP with a fake whois + fake Tailscale API, writes artifacts to `/opt/wearos-out/`). Proven in-container: suite green; prefs rev-law replay; card create (measured ref_rate) + verbatim wav round trip; synthesize refuses honestly with no engine. `scripts/record-card.sh` on the host records the 17-word scripted prompt for a real-voice card.
- **REAL VOICE PROVEN (2026-11-09):** the owner's own recording (take 4, final pass, webcam-mic 16 kHz mono) enrolled through the API inside the fresh container → card `clay-real-b553254d…`, measured 213.4 wpm / 4.78 s voiced. This is the **seed-pack default voice** (founder voice ships; users are encouraged to record their own or anyone's — SPEC §19 enrollment ruling). First human contact exposed the slice-1 VAD gate bug: −9 dB shattered speech (567 wpm reading); `_voiced_seconds` is now −18 dB hysteresis + ±1-frame hangover + ≤300 ms bridging, regression-tested (`test_vad_bridges_conversation_not_coughs`). Suite 112/112. Artifacts regenerated in `docs/demo/voiceout-slice2/` — `card.opus` is Clay's voice, click-playable.
- **Slice 2e SHIPPED — source menu, community-default** (owner: "drop-down of every one … make the community ones the default, the most popular; a model for the gateway and a model for the watch — watch needs the smaller 4-bit"): `tts_air.SOURCES` + `DEFAULT_SLOTS {gateway: community-q8, watch: community-q4}` (mradermacher re-quant, most-downloaded, ungated; watch row = Q4_K_M), `GET /voice/tts_sources` + `POST /voice/tts_install {source, for, token→0600}` on the lane, `python3 tts_air.py sources|install --source X --for gateway|watch` on the CLI. Suite **117/117**. Slice 3's pane binds straight to these routes.

## Your next slice (3): desktop pane ▸ Voice-out

Rule 18: sidebar pane, mock first, real source. Art: `docs/mocks/voiceout/` (issue #7 attachments). `desktop/plugin.js` is a SHARED file — fetch/merge both upstream branches before touching, classify conflicts per CONTRIBUTING. The pane already has the gated-install pattern (STT engine section) to copy: `tts_air.install()/status()` drive the Install button + progress; card list = `GET /voice/cards` mirror (or direct `voiceout.status()` in-pane); prefs edits PUT with `rev+1`.

Then: 4 = watch prefs client (replay-on-reconnect, rides the rev law), 5 = karaoke orb player (ring never animates; tap = stop), 6 = recorder UI (3 s floor message comes from the gateway — show it verbatim), 7 = PR hygiene: MEDIA proof on #8, SPEC flips, HANDOFF update.

## Read first (in this order)

1. This file → 2. `AGENTS.md` §0 rules + §8 map (branch copy) → 3. `CONTRIBUTING.md` (wear-demo rule, MEDIA-on-PR rule, build policy, spec-and-sync) → 4. `docs/SPEC.md` §11 (networking), §12.8/§12.11 (voice semantics your UI must honor), §14 (voice-out) → 5. issue #7 body (`gh issue view 7 -R borealBytes/hermes-wearos --json body`) → 6. `hermes-plugin/voiceout.py` (the seams: `register_engine`, `synthesize`, `cards`).

## Hard-won gotchas (do not re-earn)

- Plugin python is **stdlib-only** (rule 10); tests must run without network.
- RIFF chunks: WAV fmt fields are payload-relative (`i+10/i+12` for channels), not chunk-id-relative — burned a slice-1 round-trip.
- The scripted enrollment prompt is **17 words** (assert `ref_rate == words/speech_span*60`, don't re-derive by eye).
- GitHub issue image attach that works: `requests` multipart POST `/issues/7/comments`, field `upload_file`, header `X-GitHub-Api-Version: 2022-11-28`.
- Host mic reality (owner's box): the USB-Audio "Microphone" pipewire node records **digital silence** (empty jack, hardware mute — pw-record by node-id OR name both give peak 0). The working path is `arecord` on the DEFAULT device (webcam mic). Peak ~25–27k/32.7k is close enough to clip that Air will taste distortion — back off a hand-width.
- Issue #7's images serve from branch `docs/voiceout-mocks` — re-push that branch to update art in place.
- The desktop pane is a **SIDEBAR** (real `desktop/plugin.js` is the ground truth — mock to the source, rule 18).
- PTT halo animates (`Brush.Sweep`, 1 rev/1.6 s): the karaoke timeline ring must stay **steady**; orb = stop (mute heritage); global speed rides the existing TTS-speed feature; track #431410, played #C0392B, stroke concentric with orb (arc flags: `large-arc=0` under 180°).
- Gradle lives under `/run/media`; run builds from a worktree, never the /home checkout (rule 14); AVDs must live in the repo's gitignored `.android-home/` (rule 15).
- Slice 2–7 todo: pane (4) → prefs client (5) → karaoke orb player (6) → recorder UI (7) → tests+docs PR hygiene (8).

## How to run me (the desk)

`weardev` (alias) or `hermes -p weardev` on the desktop, opened at `/run/media/clay/Data/dev/hermes-wearos-worktrees` (this folder's AGENTS.md loads from cwd). The owner drives by demo and review: ship small, show screenshots on the PR, state guesses as guesses, bottom line first.
 2026-11-09 hackathon compliance pass: `hermes plugins validate` run against the branch (passed; the sole sudo_usage caution is a PRINTED suggestion, documented in README). HF token rerouted to the declared `config_schema` secret `hf_token` (rule 11 — no bare env reads, no vendor-cache reads in-host); README gained the voice-out disclosure section (rule 13); version 0.3.0 with voice-out description + voice/tts tags. Demo proof packs will land at docs/demo/voiceout-slice3/proof/ (desktop + watch + install + card, all captured in Docker).
 2026-11-09b weight-cache + STT quant menu: shared weight_cache.py (hash-matched hardlink reuse, Range resume, SHA pins) now backs BOTH installers — stt_install gained the vendor's full quant menu (q4_k_m..f32, default q8_0 SHA-pinned, pane dropdown + POST /stt select) and align_parakeet follows the selection via stt_install.model_path(). 128/128 + validator clean. LIVE-GATEWAY RULING (owner): demo/dev NEVER rides the owner's live gateway — the rig is a fresh hermes-wearos:voiceout container install (plugins install flow, seeded wear-demo agent); the ~/.hermes/plugins/wearos dev-sync was rolled back to PR #6's 0.2.0 floor.
 2026-11-10c lane-race + player-thread recovery (post-OOM-storm): (1) The wearos plugin looked "disabled" — really the upgrade's leftover `wearos.bak-0.2.0/` dir collided in discovery AND `.install-metadata.json` pinned the dir to the 0.2.0 revision while dev-sync wrote 0.3.0 files over it → integrity-failed → hidden. Fixed by parking the bak outside the scan and `hermes plugins install <mirror> --force --ref <pr-8 head>`. Do NOT leave sibling dirs in ~/.hermes/plugins/. (2) lane_alive now recognizes the lane by its route table (401/403/410 pass; bare 404 only passes if /voice is wired) and a bind lost to a non-lane squatter is loud + retried 5 s × 15 min (1cf5ad1). The 08:48 incident: hermes-webui (bare daemon, no unit) took :42653/54 mid-storm and answered 404 to everything — every watch voice-out 404'd while the lane "looked healthy". (3) MediaPlayerHolder:3639c4f — ExoPlayer is main-thread-owned (media3 verifyApplicationThread killed the worker-thread setMediaItem); download stays on worker, player build/prepare/stop/release post to the main Looper. Rebuild + reinstall to prove on hardware. (4) parakeet.sh done at 09:38 — watchkit spawns engines per call, so the pre-install daemon serves the new weights without restart (:42651 health shows both engines ready, streaming:true). STT round trip re-proven on the live daemon: 'Voice in, voice out, okay on the central processor…' (near-verbatim, CPU). (5) One restart only, detached (`python subprocess.Popen(systemctl…) start_new_session`) — never `systemctl restart` from inside the gateway (guard + self-kill). Voice defaults (owner): gateway Parakeet voice-IN + gateway tier voice-OUT, CPU-pinned; GPU dropdown future.

## 2026-11-10b boundary (weardev)
- Voice-out probe SocketTimeout ROOT CAUSE: the endpoint registry held only
  LAN/loopback literals; the private lane binds tailnet-only. Fixed on-device
  (tux FQDN entry, winner confirmed in logcat, literals pruned) + AGENTS.md
  troubleshooting row + voice-source pills now stacked equal-size (029b6f9,
  APK on watch 10:30:38).
- Wear Kit pane: plugin enabled and BOTH halves detected (agent + desktop).
  The owner's desktop window predates the 09:53 gateway restart; Electron
  scans plugins at launch, so the tab returns after a desktop restart.
- ▶ preview fetched (209 KB via tailnet, logcat) but was SILENT: ExoPlayer defaults
  playWhenReady=false and the holder was prepare-only since 185764a — byte-count
  logs hid it until an ear tested it. Fixed ed66c86 (p.play() after prepare), APK
  on watch 10:40:25. Watch DND/volume ruled out first.
- Voice curation per owner: clay-synth-eff920cd is the ONLY card (created via
  voiceout.create_card re-bake; rev 12); clay-real + desk-demo deleted. Shipped
  APK 10:48:27: DEFAULT checkbox on voice rows (writes voices.__default__ over
  the lane, rev+1 read-modify-write), gateway-source ear-test (volume = ExoPlayer
  gain on lane clips, speed slider writes global.speed), prepare-only fix ed66c86.

## 2026-11-10d boundary (weardev)
- OWNER RULING (audition semantics): the ▶ on a voice row is the ONLY preview — it rides
  the real pipeline and respects reply volume + dial speed; the sliders commit SILENTLY
  (no auto-ear-test on release, like before). SettingsScreen onDone hooks stripped to
  commit-only; previewCard stays.
- ▶ was SILENT for the owner because a cold engine takes ~5-6 min per fresh cache key
  (per-gateway-restart model reload) and the watch POST gave up at 60 s. Three fixes:
  (a) previewCard now plays the card reference INSTANTLY on tap and swaps in the pipeline
  clip when it lands (tap always answers, cold engine never equals silence), read timeout
  raised to 600 s on the audition call only; (b) voiceout._warm_audition(): daemon thread
  caches every card's OWN transcript (exactly the ▶ request) at boot, on card create, and
  whenever global.speed moves (set_speed + merge_prefs paths) — first tap hits cache;
  (c) proof it works: the audition clip for clay-synth is already in synth-cache
  (3b3b6c8e, 170 KB, 10:55) — post-restart taps answer instantly.
- WEAR KIT TAB MISSING (desktop, next to Sessions/Bots): ROOT CAUSE is NOT Electron —
  the dashboard systemd unit (hermes-dashboard.service, :9119) serves /api/panes and
  discovers dashboard plugins ONCE at boot. It started 08:51, before wearos 0.3.0
  existed. `systemctl --user restart hermes-dashboard` picks the pane up; no desktop
  relaunch needed. Manifest now carries label "Wear Kit", icon Watch, tab
  {/wearos, after:bots}. Earlier "needs desktop relaunch" note (2026-11-10b) was wrong.

## 2026-11-10e boundary (weardev) — ONE engine, FIFO queue
- OWNER RULING (htop screenshot): "one TTS engine loaded on the gateway, FIFO queue in
  front of it — spawning per sentence is wasteful; one agent speaks at a time."
  Implemented: air_runner gained --serve (loads torch+backbone+codec ONCE, one JSON
  request per stdin line, {"ok":…} per stdout line, 900 s idle reaper — parent respawns
  on demand). tts_air._worker_request is the gate: explicit ticket queue (_TICK/_NEXT +
  Condition) because threading.Lock has no fairness promise; respawn-once preserves the
  crash-isolation law (death/timeout/refusal → SIGKILL → fresh worker → one retry → error).
  Boot/audition storm of ten concurrent 3.4 GB pythons is gone (measured 11:06 htop).
- WHY ▶ STILL SOUNDED FLAT: at 1.63 dial every synth cache key is cold (keys include the
  dial), the pipeline clip needed minutes, and the watch POST timed out at 60 s and played
  the REFERENCE wav (by design) — which carries no dial speed. Fixes shipped together:
  ref-first audition (instant, swaps to the scaled clip when it lands, 600 s patience on
  that call), boot/card-create/dial-change audition prewarm (voiceout._warm_audition),
  and the warm worker (second+ sentences in seconds, not 40 s reloads).
- STALE PANES ROOT GOTCHA: dashboard discovery scans PROFILE-scope plugin dirs FIRST and
  first-wins on the name — the weardev profile carried an old wearos copy (manifest without
  label/tab) so /api/panes served the stale entry even after the launch-scope dir was
  synced. Sync BOTH roots on every dev bump: ~/.hermes/plugins/wearos AND
  ~/.hermes/profiles/weardev/plugins/wearos, then restart hermes-dashboard (the desktop
  renderer only refetches /api/panes at window load → reload the desktop once).

- **2026-11-10 (f): silence is a failure; opt-in pane half.** A serve-mode render cached
  1.25 s of DIGITAL SILENCE and every ▶ swapped the audible reference for it (owner: 'play
  button isn't working'), so the engine now refuses its own zeros: runner-side peak<128 =>
  RuntimeError('engine decoded digital silence'); voiceout._cache_get purges zero-peak entries
  on read (self-heal); a failed request retries once with a rotated sampler seed (certain
  lines drew degenerate generations; the shipped card line measured 4/4 audible at 29 s/warm
  call). Lesson: `ok:true` with silent audio is a lying contract — honesty law extends to the
  bytes, not just the UI. Separately, the Wear Kit pane ships OFF by design: the desktop half
  of a unified package carries .hermes-package.json => runtime-loader defaultEnabled:false
  (GHSA-mcfc-hp25-cjv7); enabling is the owner's toggle at Capabilities > Plugins (decision
  key hermes.desktop.pluginDecisions.v2, Electron localStorage — not file-flippable from here).

## 2026-11-10 ~13:45 — b233ed3: pane ▶ 500 RESOLVED (pre-restart notes)

Root cause chain of the red 'synth request failed: 500': the soundtest route
POSTed agent='desktop' but the voices map only carried '__default__' →
refusal raised through the route as a bare HTTP 500. And even once bound,
the fixed first draw (seed 42, temp 0.7) sits in a silence basin for the
owner's card (hand test: 77/123 speak, 42 zeros) so the old +1 retry walked
the basin and died 3/3.

Fixes (all in b233ed3, deployed to both plugin roots, dashboard restarted
13:39:45):
* unbound agents fall back to the GLOBAL card — the dropdown's selection;
* soundtest answers {"ok": false, "error": ...} JSON, pane shows the red
  note, never a 500;
* synth seeds = crc32(draw text); silence retries jump seed +7919.

CPU note: the 13:41-13:45 all-core pin was (a) my probe chains re-loading the
model (each ~90 s at all-core, nice 10) and (b) a gateway-child storm of
'hermes -p <profile> plugins list --json' cold starts (12+ concurrent, all
~30 profiles) that predates and is independent of the wear kit. Orphan
worker from the interrupted probe was reaped. Load already falling
(59→41).

GATEWAY: restarted by the owner ~13:55 — now on b233ed3 (crc32 seeds, binding
fallback, sentence streaming). ▶ on a changed line speaks on the FIRST draw.

GPU EXECUTION SHIPPED (owner: "drop-down for CPU and GPUs… it's GPU 1 that we
use, never, ever, ever GPU 0"): the answer to "can this model run on GPU" is
YES, but the pinned llama-cpp wheel is CPU-only, so GPU = a second private
venv (tts/venv_cu, llama-cpp rebuilt -DGGML_CUDA=on). The Voice-out card grew
a 'speak on' dropdown: CPU + EVERY GPU (nvidia-smi: name, index, free GB).
Picking a GPU IS the consent → one background build; the engine stays honestly
on CPU until .cuda-installed, then the resident worker rebinds lazily on the
next speak. THE GPU0 SHIELD: the worker env always carries
CUDA_VISIBLE_DEVICES=<picked index> — sglang's GPU0 is not even visible to the
engine. tts/device.json persists the pick. SPEC 19.4(m). Tests:
tests/test_tts_air_device.py (7). Suite: 145 passed, 1 skipped, 3 subtests.
ON THIS BOX: GPU1 = RTX 4070 (idle). DONE 14:34: the CUDA variant installed
from the hash-pinned release wheel (see SPEC 19.4m measured note) — marker
`venv_cu/.cuda-installed` on disk, verification probe passed. Old CPU workers
killed (the gateway's old code had DOUBLE-spawned — 273% + 24% twins). DONE
14:48 — HARDWARE PROOF (owner + weardev both heard it): gateway restarted,
resident worker now `venv_cu --device cuda` holding 4.3 GB on GPU 1 (GPU 0
untouched at 90 GB/100% — the shield held); the Watch Ultra (app 0.9.0 = the
12:07 f1fc974 build, pairing kept across -r install) spoke a live reply over
the tailnet lane — Android AudioTrack started at 24000 Hz mono, USAGE_MEDIA.
Watch gate learned: Settings ▸ Voice out ▸ source 'gateway 🌐' (default is
'system'; voice_out itself defaults ON). 'voice suppressed — answer settled
off-screen' is the honest no-chime law, not a bug.
## 2026-11-10 ~16:30 — /status timeout + CLI-storm fix + pane step reorder
- Symptom: pane showed no paired watch/agents though pairing.json healthy. Cause: `/status` took 68–84 s (sequential 27-profile `hermes plugins list` fan-out); pane timeout -> retry -> overlapping scans pinned the box (load 28.7).
- Fix: `_profile_inventory` parallelized (8 workers); new `_inventory_serving()` serves newest cache whatever age, refreshes on ONE background thread; status path uses it. Test: stale-serves-with-zero-CLI-calls + single-refresh.
- Pane (owner ruling): steps reordered — 1. Install the APK, 2. Pair a watch, 3. Enable Wear Kit; paired-watches card follows; empty-state is a pointer line.
- Env note: test_plugin_api.py only runs where fastapi importable (was silently skipped locally); there test_voice_post_select_install_soundtest fails PRE-EXISTING: real token rejected by huggingface.co — follow-up (was masked by earlier gutter bug).
- Follow-up same hour (owner UI ruling): enable⇒grant auto (grants route kept for CLI), "watch may use" checkbox REMOVED, per-row voice-override dropdowns (writes canonical voices[agent]; blank = Voice-out card = the global), Enable-all box. SPEC §19 amendment (n). Tests: 156 (10 api incl. auto-grant + revoke asserts, deselect env-broken soundtest).
- Panes (owner, 16:42): health lights are ✅/❌ emoji (fail keeps inline reason); Paired-watch rows drop the agent name (one watch = one pairing; agents are the enable list); ceremony: both stale device records revoked, fresh pair as wear-demo.
- Lane key fallback (2026-11-10 16:50): _read_profile_key falls through to the canonical FILE secret scope when the ambient scope of the running (default) profile is empty — systemd gateway boots carry no API_SERVER_KEY env; file route is the rule-11 truth. Live proof pending re-knock after deploy.
- Pair card: button toggles open/close (POST /pair/window action=close), countdown as bare "575s" chip (owner 16:45: no dead-end open-window label).
- Inventory TTL 300s + invalidate-on-mutation (CPU noise 16:52: 10s TTL re-ran 26 cold CLIs per poll while pane open). Tests race-hardened (mut-argv filter).
- Pair card absorbs Paired-watches (owner 16:55): one box for the pairing concern; no number on Pair; enable step renumbered 2.
- enable self-heal (owner 17:05 Where
Agent
flashes): named profile without the plugin → auto-install from install-metadata source+revision, retry enable once. wear profile installed/enabled/granted live.
- invalidate keeps serving stale (17:10 flash fix): pane refetch must never pay the fan-out sync; pane overlays the clicked state until server confirms; enable-all reports per-profile failures.
## 2026-11-10 ~17:25 — PR #8 MERGED into feat/hermes-plugin (merge commit cc92c61). Develop from this worktree / branch onward; hermes-wearos-voiceout stays as the voice-out slice archive. Mirror: catalog main lags until feat/hermes-plugin->main; live box pinned pr-8@faf30a3f (identical content). Voice-out soundtest verified ok:true on GPU 1 — the owner 503 was an armed restart drain window, self-resolving.

## 2026-11-10 ~22:15 — fling fix on glass + tailnet address row (weardev)
- SwipeForward hardened: 40 dp travel OR 14 dp + <=140 ms timestamp fling.
  HARDWARE-PROVEN on SM-L705U (build 21:50, installed 21:58): 70 px/80 ms
  flicks walk Chat->Sessions->Agents (screenshots /tmp/hw/g0-g3.png).
  Caveat: first flick after cold launch may be dropped; subsequent ones register.
- Tailnet address row (ee23cd2): /status carries `tailnet` = derived from
  `tailscale status --json` Self.DNSName -> http://tuxdesktop.tailb12aa5.ts.net:8642
  (60 s cache, honest empties). Pane prints it under the Pair card with copy button.
  Owner must bounce the desktop app once for the pane JS to reload.
- Install gotcha found: `split -d` => `part-NN` (hyphen); the reassembly glob must be
  `part-*`. Three "MD5 MISMATCH" scares were this, not the link.
- Test harness canonical now: scratch/wear-pytest-venv (3.14) + overlay
  ~/.hermes/tools/python-3.14.9*/lib/python3.14/site-packages -> 146 pass, 1 skip,
  1 pre-existing fail (sibling_lane fixture drift; stashed HEAD reproduces it).
- Mirror re-synced clean (77bfbdf) after the flatten half-apply; dashboard restored.

## Enrollment keyboard: saga closed (23:40, 23:39 APK)
- Root cause was the WATCH, not the app: HoneyBoard (the registered IME) is package-DISABLED
  since the companion work and Gboard was absent from `enabled_input_methods` — IMMS had
  nothing to bind (mCurMethod=null for every window). Fixed on device via
  `ime enable + ime set ...LatinIME` (disclosed; reversible in watch Settings).
- Name field is now VoiceOrTypeField (the app's one keyboard widget), parameterized with
  imeAction: enrollment asks Done, search keeps the glass (owner ruling); the page also has
  an explicit next button. Enrollment lives in the ACTIVITY window, not a Dialog — the
  OneUI Wear IME never attaches to dialog windows (the kept fix from the migration).
- Proven: tapping the field raises the floating Gboard (kbd11/kbd_done shots).
- Freeform law shipped: READY reads "talk 3–12 s / say anything"; pace = syllable nuclei
  from the envelope (`rate_source: syllabic-est`); scripted lane kept for seed pack + pane.
- Display-name law shipped: invisible immutable card uuid; ✎ rename on the pane propagates
  (selected voices, dropdowns, watch list) through rev-bumped prefs.
