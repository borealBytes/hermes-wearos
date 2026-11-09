# HANDOFF — Wear Kit, voice-out milestone (updated at every slice boundary)

**As of:** 2026-11-09 (late) · **From:** weardev desk (slices 2a–2c shipped) · **To:** next session of weardev
**You pick up here.** If this file disagrees with your memory, this file loses to the branch's `docs/SPEC.md` §19, which loses only to a fresher `git fetch`.

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
