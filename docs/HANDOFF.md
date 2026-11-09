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
- **Full pytest suite on this box: 111 passed** (was 2 red — fixed by RUNNER hygiene, not code: `TMPDIR=/tmp`, Hermes-managed interpreter, keep inherited `PYTHONPATH`). Recipe in SPEC §19.4; also: do NOT add `hermes-plugin/tests/__init__.py` (double-conftest breaks the module-identity test).

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
- Issue #7's images serve from branch `docs/voiceout-mocks` — re-push that branch to update art in place.
- The desktop pane is a **SIDEBAR** (real `desktop/plugin.js` is the ground truth — mock to the source, rule 18).
- PTT halo animates (`Brush.Sweep`, 1 rev/1.6 s): the karaoke timeline ring must stay **steady**; orb = stop (mute heritage); global speed rides the existing TTS-speed feature; track #431410, played #C0392B, stroke concentric with orb (arc flags: `large-arc=0` under 180°).
- Gradle lives under `/run/media`; run builds from a worktree, never the /home checkout (rule 14); AVDs must live in the repo's gitignored `.android-home/` (rule 15).
- Slice 2–7 todo: pane (4) → prefs client (5) → karaoke orb player (6) → recorder UI (7) → tests+docs PR hygiene (8).

## How to run me (the desk)

`weardev` (alias) or `hermes -p weardev` on the desktop, opened at `/run/media/clay/Data/dev/hermes-wearos-worktrees` (this folder's AGENTS.md loads from cwd). The owner drives by demo and review: ship small, show screenshots on the PR, state guesses as guesses, bottom line first.
