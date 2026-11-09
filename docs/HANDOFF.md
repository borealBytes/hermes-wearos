# HANDOFF — Wear Kit, voice-out milestone (updated at every slice boundary)

**As of:** 2026-11-09 20:19 EST · **From:** research desk (planning partner) · **To:** weardev desk
**You pick up here.** If this file disagrees with your memory, this file loses to the branch's `docs/SPEC.md` §14, which loses only to a fresher `git fetch`.

## Where things stand (verified, not claimed)

- **Repo:** `borealBytes/hermes-wearos` (monorepo — plugin code edits happen here; `hermes-wearos-plugin` mirror is CI-only, zero human commits).
- **This worktree:** branch `feat/voiceout` @ `2b16c8e` (pushed), base = PR #6 head `fix/nous-review-151546` @ `01ef29d` — checked today: base contains `feat/hermes-plugin`, so the whole plugin line is in your floor.
- **PR #8:** draft, open — voice-out milestone lands as slices here.
- **Issue #7:** the voice-out spec (owner rulings verbatim inside). Planning art: `docs/mocks/voiceout/` (from `docs/voiceout-mocks`).
- **Slice 1 SHIPPED:** `hermes-plugin/voiceout.py` (382 lines) + `hermes-plugin/tests/test_voiceout.py` (8 tests, stdlib, no network — all pass: `python3 hermes-plugin/tests/test_voiceout.py`). Delivered: rev'd gateway-canonical prefs (`voiceout.yaml`, higher-rev-wins merge), card store with **measured** `ref_rate` (VAD speech-span / 17-word scripted prompt), 3 s floor / 60 s ceiling, hash-pinned 10-voice seed pack (nothing binary committed), tier-aware `synthesize()` that **refuses honestly** until an engine registers.
- **Spec discipline SHIPPED:** `docs/SPEC.md` §14 (incl. §14.4 amendment log — append-only, dated, never rewrite), `CONTRIBUTING.md` "The spec-and-sync discipline" (conflict classification table), `AGENTS.md` §8 map. A slice without its SPEC update is not shipped.

## Your next slice (2): engine adapter + aligner + routes

1. **`hermes-plugin/tts_air.py`** — NeuTTS Air adapter (Apache-2.0, GGUF). Follow `stt_install.py`'s pattern exactly: hash-pinned model download at first use, `install()`/`status()` for the pane's gated install button, `register_engine()` into `voiceout.py`. Runner-up if Air stalls: CosyVoice2 (verify license first).
2. **Aligner** — reuse the already-installed Parakeet/CTA **forced-align** capability (`transcribe.py` pattern) to emit `words: [{text, start, end}]`; when unavailable degrade to whole-sentence karaoke (issue #7 tier rule: gateway=words, watch=sentence — degradation is a *reported* mode, never a silent lie).
3. **`hermes-plugin/private_api.py`** — add `/voice/*` routes (GET/PUT prefs, card list/create/delete, synthesize → wav + words.json). **Shared file:** `git fetch origin && git merge origin/fix/nous-review-151546 && git merge origin/feat/hermes-plugin` BEFORE editing; classify any conflict per CONTRIBUTING (upstream-invalidate / interleave / my-mistake); re-run both sides' tests after.
4. Tests for adapter-degraded-mode (no model → refusal with reason) and route auth (Tailscale node, not token); SPEC §14.2 status flips ✅ with amendment-log line; commit with the docs in the SAME commit.

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
