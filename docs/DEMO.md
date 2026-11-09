# DEMO.md — demo media production ledger (LIVING DOC)

> Purpose: the single source of truth for the README/demo rework. It is the
> [SPEC](SPEC.md)'s sibling for **media**, and it is kept current as features
> land — anything that changes what the demo can show updates §2 and §3 first.
> Every feature must appear **twice**: as a video beat AND as a screenshot.
> Assembly is not done until every §2 row is `shot ✓` AND every §3 row is
> `refreshed`.

## 0. Principles (owner-set)

- **wear-demo only.** Every on-screen conversation is the `wear-demo` agent /
  its seeded sessions. Owner's other sessions and their titles never appear.
  Agent names and session titles are fine to show *as long as they're ours*.
- **More is better.** Feature completeness > runtime. Film target ≤ ~2:30 by
  keeping beats tight, never by dropping features.
- **Silent master.** Video is delivered without audio; the owner scores it with
  a separate Hermes agent *after* approving the picture.
- **Truth before polish.** Every screenshot/GIF keyframe passes a vision review
  against its exact caption claim (§8) before it lands in `docs/screens/`.
- **Owner identity is pixelated.** No username/home-path/coordinates in ANY
  shipped pixel or text (owner, 2026-11-01). OCR-scan every PNG *and every GIF
  frame* (fades included — tesseract goes blind on dissolves, finish the sweep
  with the vision endpoint) before shipping; docs text writes paths as
  `/run/media/<user>/…`. Reusable tool: `pixelate.py` (block-pixel + blur,
  alpha-preserving — plain `cv2.imread` strips the circle mask's alpha).
  **This applies to hostnames and tailnet names too** — and not only in pixels:
  repo text (code comments, tests, docs) carries neutral stand-ins (a fake
  tailnet, `labhost`, `/home/user`) so a public clone can't rebuild the owner's
  network. The batch-9 privacy sweep found the last real ones; scrub new ones
  the same day they appear.
- **Shipped stills are TRANSPARENT CIRCLES.** The corners never existed on the
  round glass, so committed `docs/screens/*` carry alpha: outside the inscribed
  circle is TRANSPARENT (never a black-cornered square — on the README's white
  page that reads as a black plate; the batch-9 regression this bullet exists
  for). `circle-shot.sh` (capture AND `--mask`) writes this mask — canvas stays
  square 1:1 with the panel. Motion artifacts mask EVERY frame.
  `scripts/demo-check.sh` asserts corner alpha == 0 on every still; a square
  fails the gate. The tall poster GIFs in `docs/demo` are harbor art and exempt.
- **Screenshots show what WORKS.** A model-picked toast must name a model that
  actually answers (claude-fable-5.1 400s on this gateway — never on screen);
  verify the pick by making it answer (`luna-online` pattern) before capturing.
  A browser shot loads a REAL page (this repo's GitHub),
  never `example.com`. Notification shots show the notification EXPANDED.
  Settings shots scroll until the interesting rows are IN FRAME (Voice-in must
  show every audio path incl. the whisper engine/model rows).
- **GIF pacing is readable, not flashy.** Stills hold ~1.5 s (hero/end ~2.5 s);
  motion where motion is real — the splash intro is cut from the SHIPPED asset
  (`app/src/main/assets/hermes-watch-intro.mp4`), circle-cropped with
  transparent background, ~125 ms/frame — never a still pretending to move
  (owner blesses this "cheating": same pixels, honest motion).
- **One generation of media in history.** After each media round: purge +
  force-push (§9).
- **Every artifact carries the version** (owner, 2026-11-01). The single source of truth is
  repo-root `VERSION` (standard semver, `scripts/version.sh`). Film/GIF filenames embed it
  (`demo-vertical-v0.6.0-raw.mp4`, `demo-walkthrough-v0.6.0.gif`), the canvas burns a permanent
  `v<VER> · wear-demo` bug into every frame, and ffmpeg container metadata repeats it — so a
  YouTube upload or a downloaded GIF is never ambiguous about what it shows. The app
  (`versionName`/About/Settings) and watchkit (`/health`) read the SAME file; bump with
  `scripts/release.sh <x.y.z>`, never by hand.

## 1. Production flow

```mermaid
flowchart TB
    subgraph P0 [Phase 0 · code — DONE 39f770b]
        IC[orb-spirit notif icon + orb largeIcon] --> SL[⚙ settings lane at 3:00]
        SL --> ND[notify_demo hook · fresh-id posts]
    end
    subgraph P2 [Phase 2 · brand kit]
        FT[Nous fonts: Rules + Aeonik Fono] --> DS[design system:\nart-fill canvas · floating orb circle\nfading caption pills]
        AR[art gen: gateway image agent\n3–4 prompts → owner picks] --> DS
    end
    subgraph P3 [Phase 3 · capture — wear-demo staged]
        SH[34 screenshots · docs/screens/]
        VR[screencap video · acts A1–A7\nin ONE watch session]
    end
    subgraph P45 [Phase 4–5 · assemble + proof]
        ASM[ffmpeg silent master\n1080×1920] --> V[vision review\n1 claim per pass]
        SH --> V
        V -->|fail = reshoot| SH
    end
    subgraph P67 [Phase 6–7 · publish]
        RD[README per §3 checklist] --> PU[media purge +\nforce-with-lease]
    end
    P0 --> P3
    DS --> ASM
    VR --> RD
```

Acts (story order, one continuous watch session):

| Act | Story beat | Beats (S-ids) |
|---|---|---|
| A0 | **It starts like a film** — launcher tap → the small icon HOLDS, then the splash film opens on its moving lid-opening frame (modes: none/orb/built-in/custom) | S35a, S35b, S36 |
| A1 | **Morning brief** — wrist raise → PTT ask fires the `aurora` + daily-weather skill → streaming markdown → **3-day weather plot** lands inline | S01→S06→S07→S03 |
| A2 | **Look at the art** — tap the plot → viewer, pinch zoom, pan; mermaid diagram of the day | S04, S05 |
| A3 | **Talking back** — 🧠 model pick mid-chat, edit & resend, stop mid-stream, full long reply lands whole, tap a link → browser; the answer is SPOKEN and the glass follows the sentence (karaoke), volume on the dB taper | S25, S26, S16, S08, S29, S28, S39, S37, S38 |
| A4 | **Approve right here** — approval card inside the open session → tap Approve, run continues | S33, S34 |
| A5 | **Finding things** — sessions list, search `harbor`, long-press sheet → fork, archive chip, unarchive | S17→S21→S22→S23→S24 |
| A6 | **Off the wrist** — app backgrounded: settle card + approval notification (Approve from the shade), deep-link resume, **the DESKTOP WATCH**: answers + approval parks from desktop/API sessions buzz the wrist (never voice off-screen — THE ATTENTION POLICY), agent picker, gateway failover, agent-keys map | S31, S32, S32b, S09, S44a, S44b, S45, S18, S11, S42 |
| A7 | **Make it yours** — ⚙ lane → Settings, font scale both ends, body tools, commands/skills (aurora listed), keyboard, edge-circle scrolling on every list (bezel AND swipe), dial cluster close on launcher | S30, S10→S19, S13→S14→S15, S12, S27, S41, S00 |

## 2. Feature ledger (feature ⇔ screenshot ⇔ beat ⇔ status)

Status values: `todo` → `captured` → `shot ✓` (vision-reviewed). All screenshots
live in `docs/screens/` and all re-shoots keep the existing filename.

| # | Feature (SPEC) | Screenshot | Video beat | Status |
|---|---|---|---|---|
| 1 | Orb dial — 8 lanes, ⚙ settings default (F12, §12.1) | `01-orb-cluster.png` | S01, S27-close | shot ✓ |
| 2 | Launcher icon = orb spirit | `13-launcher-icon.png` | S00 (open + close) | shot ✓ |
| 3 | Voice PTT → STT (on-watch Vosk) | `06-ptt-overlay.png` | S06 | shot ✓ |
| 4 | Skill call + streaming markdown (aurora/weather brief) | `02-chat-markdown.png` | S02→S07 | shot ✓ |
| 5 | Markdown table (batch-10 re-shot: FOUR labelled columns, center-aligned cells — `Slip/Depth/Tide/Wind` harbor fixture; the six-column degradation rides below) | `02b-chat-table.png` | S07b (2 s) · `-e render tables` + rim-scroll to center the grid | shot ✓ |
| 6 | Image card — **3-day weather plot** inline | `03-image-card.png` | S03 | shot ✓ |
| 7 | Image viewer: pinch-zoom + pan | `04-viewer-zoom.png` | S04 | shot ✓ |
| 8 | Mermaid via watchkit render (batch-10 re-shot: the WHOLE voice round-trip — `graph LR`, wrist subgraph, classDef colors, approval diamond — not the 3-node sketch) | `05-mermaid.png` | S05 · `-e render mermaid` | shot ✓ |
| 9 | ⌘ commands/skills overlay (`/compact` + SKILLS rows visible) | `27-commands-overlay.png` | S27 | shot ✓ |
| 10 | Model picker stage 1 (providers) | `25-model-picker.png` NEW | S25 | shot ✓ |
| 11 | Model picked — 🧠 chip + replies from it | `26-model-picked.png` NEW | S26 · capture: gpt-6-luna pick TOAST (`model-26c`), answer proven: `Reply with exactly: luna-online` → `finish run=… error=false len=11`; restored to gateway default after | shot ✓ |
| 12 | Edit & resend | `16-edit-resend.png` | S16 | shot ✓ |
| 13 | Stop a running answer | `07-stop-running.png` | S08 | shot ✓ |
| 14 | Full long reply (no truncation, 6 k chars) | `29-full-reply.png` NEW | S29 | shot ✓ |
| 15 | Links clickable → browser opens | `28-browser-opened.png` NEW | S28 · capture: ask via `-e text` (editor swipes = handwriting junk), blue-cluster CENTROID tap, GitHub `borealBytes/hermes-wearos` page rendered on the watch | shot ✓ |
| 16 | Approval card **in-session** — flanks shot (owner 2026-11-07 re-shoot): mid-stream park (long-count `-e text` + `-e approval_demo 1` — flanks are gated on run-live AND chat-at-bottom, MainActivity:1699, so the streaming tail pushes the card off-glass by itself; a post-finish swipe trips the card-rescue re-center instead). ✅ left / ❌ right centroids proven by pixel analysis | `33-approval-card.png` re-shot | S33 | shot ✓ (2026-11-07) |
| 17 | Sessions list (wear-demo rows; batch-9 re-shot — the chip row now includes the 💻 computer lane filter) | `08-sessions.png` | S17 | shot ✓ |
| 18 | Session search `harbor` | `21-session-search.png` | S21 | shot ✓ |
| 19 | Long-press sheet + fork | `22-session-sheet.png` | S22 | shot ✓ |
| 20 | Archive (🗃 chip) | `23-archived-chip.png` | S23 | shot ✓ |
| 21 | Unarchive ♻️ | `24-unarchive.png` | S24 | shot ✓ |
| 22 | Settle notification (orb largeIcon, spirit stencil in bar) | `31-notify-plain.png` NEW | S31 | shot ✓ |
| 23 | Approval notification — Approve/Deny actions | `32-notify-approval.png` NEW | S32 (+S32b expanded) · capture: EXPANDED in place via `notify_demo approval` + one card tap: **Approve / Deny / Open session** pills all visible (OneUI does NOT cap at Open+Clear — earlier claim was wrong); demo run denied via the receiver afterwards | shot ✓ |
| 24 | Open-session deep link from notification | `09-resumed.png` | S09 | shot ✓ |
| 25 | Agent picker (wear-demo + names OK) | `17-agent-picker.png` | S18 | shot ✓ |
| 26 | Gateways: ● winner, failover (batch-9 re-shot: transport line `wifi(wlan0) · validated`, two rows, hostname pixelated per §0; the Tailnet section is its own row 56) | `11-gateways.png` | S11 | shot ✓ |
| 27 | ⚙ settings lane opens Settings | `30-settings-lane.png` NEW | S30 | shot ✓ |
| 28 | Settings ▸ Home | `18-settings-home.png` | S19 | shot ✓ |
| 29 | Settings ▸ Display | `19-settings-display.png` | S13→S14 | shot ✓ |
| 30 | ~~Settings ▸ Connection~~ — **merged into Gateways (row 26)** in the v0.6 registry work; legacy `20-settings-connection.png` retired (it leaked the raw token) | — | — | n/a |
| 31 | Settings ▸ tail (about) | `15-settings-tail.png` | S15 · batch-9 re-shot (the About row stamps `v0.7.0` — same rationale as row 49) | shot ✓ |
| 32 | Font scale min / max (both ends) | `14a-fontscale-min.png`, `14b-fontscale-max.png` | S14 (both) | shot ✓ |
| 33 | Body tools (HR/steps) permission + read | `10-settings-body.png` | S12 | shot ✓ |
| 34 | Wear keyboard (dictation fallback) | `12-keyboard.png` | S27b | shot ✓ |
| 35 | 🔊 voice-out mute lane (0 dB floor = speaks NOTHING, live queue cut) | (shown in 01) | S01 (release) | shot ✓ |
| 36 | Splash intro film: held launcher icon → reveal on a MOVING film frame (`revealIsMoving`, one cut); tap-anywhere skips into the app the moment it's ready | `35-splash-film.gif` (was `.png`) | S35a, S35b · capture: real 240×240 transparent circle GIF cut from the SHIPPED asset (t≈1→10.5: lid opens → eclipse light → Hermes rise, 10 fps, 95 frames, ~3.4 MB); README img + ledger now say `.gif` | shot ✓ |
| 37 | Splash modes in Settings (none / orb / built-in film / custom media) | `36-splash-picker.png` NEW | S36 | shot ✓ |
| 38 | Voice-out settings: engine picker, voice + fallback, speech rate, preview | `37-voice-output.png` NEW | S37 | shot ✓ |
| 39 | Reply volume = **dB-taper double sliders** (mute-lane pair + settings mirror, −60 dB floor) | `38-volume-db.png` NEW | S38 | shot ✓ |
| 40 | Karaoke follow — glass scrolls to the sentence being spoken (teleprompter yields to taps) | `39-karaoke-follow.png` NEW | S39 | shot ✓ |
| 41 | STT source: on-watch Vosk ⇄ OFF-DEVICE vendor (Google/Samsung) ⇄ Gateway whisper/parakeet engines | `40-stt-source.png` NEW | S40 · capture: Voice-in scrolled with the gateway row CLICKED OPEN: engine picker shows `★ gateway default · parakeet` (selected), `whisper · text streaming ✗ · final when you stop`, `parakeet · text streaming ✓` (watchkit v0.6.0 from this worktree; Voice in restored to Local Vosk after) | shot ✓ |
| 42 | Edge-circle scrolling on EVERY list (Settings pages, Agents, Model, Approvals, Chats — 2f) AND normal swipes still scroll | `41-edge-circle-pair.png` NEW (before/after pair in one frame) | S41 | shot ✓ |
| 43 | Gateway registry: multi-gateway save/select/delete, ● winner dot, failover badge | `42-gateways-multi.png` | S42 · batch-9 re-shot with harbor-lab restaged via `-e add_gw 'label|api|wk'` (delete after), hostname pixelated | shot ✓ |
| 44 | Agent keys per profile ✓/— (SPEC §12.2 one-login) + ⟳ learn — batch 9 added the 60 s self-sweep (no pixels change; README caption says it) | `43-agent-keys.png` | S42b | shot ✓ |
| 45 | DESKTOP WATCH — approval park from a DESKTOP/API session buzzes the wrist with the parked command (beacon-derived); **Open adopts the session** and finishes it with ✅/❌ in chat (watch-started runs keep inline Approve/Deny) | `44-notify-desktop-approval.png` NEW | S44a | shot ✓ |
| 46 | DESKTOP WATCH — answer finished off the wrist → "Answer ready on the desktop" notify | `45-notify-desktop-settled.png` NEW | S44b | shot ✓ |
| 47 | THE ATTENTION POLICY — live on screen: inline + voice, no notify; anywhere else: notify + silent (§16.3) | `46-attention-pair.png` NEW (live vs off-screen pair) | S45 | shot ✓ |
| 48 | THE POCKET LIFELINE — the off-screen buzz survives Samsung's cached-process freeze (WorkManager chain, shared cursor, no dupes at handoff) | no own shot: proven through `44-notify-desktop-approval.png` + `45-notify-desktop-settled.png`, both captured app-BACKGROUNDED (settle card additionally survived 4 min screen-off darkness) | rides S44a/S44b | rides 44/45 |
| 49 | Settings ▸ About page (version, STT model presence, push status — the diagnostics page itself, not just the tail row) | `47-about-screen.png` | S47 · batch-9 re-shot (v0.7.0 version line; About is the owner-blessed identity-whitelist page) | shot ✓ |

| 50 | MEDIA EPIC — generated video/audio play in chat (cards inline, tap→fullscreen media3 player; watchkit `/media` pre-shrink) (SPEC §17, §12.14) · batch-10 re-shot: the video card is now the comfy **aurora gif** rendered via the gateway agent — it ANIMATES inline (GIF chip), audio sting below · **2026-11-07 re-capture done: `49-video-player.gif` was a real 30-frame screenrecord (10 fps, per-frame circle mask, magenta-key transparency idx 255 disposal 2; motion diff ~27/19/19 RGB mean-abs; ⟲5-rewind trick — a finished clip shows the controller reliably, and PinchPlayerView swallows keyevents while the controller is hidden; `-e volume -8` fired mid-rec lands the 🔊 60 % readout on two frames). 2026-11-08 OWNER-REQUESTED re-shot: replaced with a clip that is genuinely generated — the comfy **MiniMax H3** aurora render (`/run/media/clay/Data/comfy/projects/aurora/outputs/6734e3af_000.mp4`, 864×480@24, 6.58 s) served by watchkit `/media` and asked of the watch agent by absolute path. RECIPE that worked (take 5): open the card chat → `screenrecord --time-limit 16` → tap the ▶ chip (uiautomator-measured) → **let Media3 auto-hide the chrome** (~3.5 s into real playback; the y-centre white-pixel fraction is the gate: 0.78 with the glyph, <0.07 without — eyeballing lies and a mid-chrome tap at y≈55 hit the pause toggle in take 4; a *finished* clip re-shows the controller, the 11-07 trap) → `-e volume -5` mid-play for the 🔊 50 % cameo → take frames 45..64 of a 9-fps extraction of the WHOLE take (seek-free: `-ss` before `-i` plus a `-t` input cap duplicated frames and gave me a still GIF twice), FULL-circle crop `r = min/2 − 2`, one 254-colour palette with the magenta key pinned at idx 254 OUT of the content indices, key applied post-dither from the circle mask, 240 px, 20 frames @110 ms, disposal 2. ⚠ VERIFY MOTION BY DECODING THE FILE (`ffmpeg -i out.gif f%03d.png` + per-frame mean-abs ≈8): PIL's own GIF frame diffs read 0.0 on a file ffmpeg proves animates, and a shared palette with `dither=NONE` silently flattened 2–5/255 aurora gradients into identical frames. ⚠ `screenrecord` returns 480×288 or 480×480 depending on surface state — measure the frame before masking. `-e play_url` drives the AUDIO-only ExoPlayer stub (no player UI); fullscreen video must be reached by tapping a media card. Source take kept at `/tmp/opencode/aurora-rec5.mp4`** | `48-media-cards.png`, `49-video-player.gif`, `52-audio-player.png` | S48a, S48b, S48c · media card from `/p/comfyui` agent (aurora mp4→gif via repo ffmpeg), tap ▶ chip (uiautomator-measured) | shot ✓ (2026-11-08, generated-clip re-shot) |
| 51 | Animated GIFs decode ON THE WRIST (ImageDecoder→AnimatedImageDrawable via public Drawable.Callback; watchkit `/img` passes gif verbatim) (SPEC §12.14) | `50-gif-card.gif` (screen-recorded, circle-masked — the splash-film precedent for motion artifacts) | S50 | shot ✓ |
| 52 | Pinch-to-zoom VIDEO — PinchPlayerView intercepts the 2nd finger, MediaZoom parity clamp, double-tap fit↔2.5× | `51-video-pinched.png` (capture: `-e media_zoom 2.4` fires the double-tap's own `animateScale` — adb can't inject two fingers) | S51 | shot ✓ |
| 53 | BEZEL VOLUME — player binds the rotary while on screen; 5 % detents, haptic tick, fading 🔊 readout; unbind = system volume again | readout visible in `49-video-player.gif` (50 %, driven by `-e volume -5` through the exact key path the bezel fires) | rides S48b | shot ✓ |
| 54 | FLAWLESS HAND-OFF — desktop chat adopted on the wrist, gateway-first backfill, one-column promote, NO fork (SPEC §12.13) | `53-handoff-adopted.png` (capture: seeded `desk-echo-probe-7` (source=local) → `-e resume_session` → ask answered from desktop history `ORCHID`; one row) | S53 | shot ✓ |
| 55 | Tool-activity card — collapsible per-run ledger persisted in the transcript (SPEC §12.12) | `54-tool-activity.png` (capture: real gated `uname -r` terminal run on wear-demo) | S54 | shot ✓ |
| 56 | Tailnet status page — node FQDN + gateway peer + phase + Test, owner identity pixelated (SPEC §11.4) | `55-tailnet-status.png` (capture: emulator's own node; `tailca7ee1`/peer-IP boxes scanned then pixelated on the SQUARE before masking) | S55 | shot ✓ |
| 57 | Approval LEVELS sheet (batch-10 new): per-chat Inherit / Manual / Smart with their honest hints — the dial's 10:40 lane opens it (SPEC §12.9) | `56-approval-modes.png` (capture: `-e approvals 1` dev door, zero code) | S56 | shot ✓ |

`working ✓` = the exact shot shape already captured once during Phase 0 (see
`/tmp/opencode/demo/notify-*.png`) — re-shot in context under the brand rules.

## 3. README change checklist (assembly gate: every row `refreshed`)

| README row (current) | Becomes | Caption refresh | Status |
|---|---|---|---|
| hero `dial-orb-vertical-v2.mp4` | **RETIRED** → one combined film at top (inline `![](…mp4)` embed); current generation: **versioned** `demo-vertical-v<VER>-raw.mp4` → owner scores keeping the stem (`demo-vertical-v<VER>.mp4`) → YouTube master + README link swap | NEW caption: full feature story, silent note | refreshed |
| `orb-dial.gif` | **RETIRED** (dial act lives in the film) | — | refreshed |
| `01-orb-cluster.png` | re-shot (owner: empty-session minimalism — new chat, Ask Hermes + popped orb cluster, no chat content) | yes — 3:00 row ⚙ in shot | refreshed |
| `02-chat-markdown.png` | re-shot | keep claim | refreshed |
| `02b-chat-table.png` | re-shot (batch-10: harbor 4-col grid, centered cells) | claim REFRESHED (Slip/Depth/Tide/Wind + degrade note) | refreshed |
| `03-image-card.png` | re-shot | YES → weather-plot wording | refreshed |
| `04-viewer-zoom.png` | re-shot | keep claim | refreshed |
| `05-mermaid.png` | re-shot | keep claim | refreshed |
| `06-ptt-overlay.png` | re-shot | keep claim | refreshed |
| `07-stop-running.png` | re-shot | keep claim | refreshed |
| `08-sessions.png` | re-shot | wear-demo rows only | refreshed |
| `09-resumed.png` | re-shot | keep claim | refreshed |
| `10-settings-body.png` | re-shot | keep claim | refreshed |
| `11-gateways.png` | re-shot | keep claim | refreshed |
| `12-keyboard.png` | re-shot | keep claim | refreshed |
| `13-launcher-icon.png` | re-shot | keep claim | refreshed |
| `14a/14b-fontscale` | re-shot | keep claim | refreshed |
| `15/18/19/20-settings-*` | re-shot | keep claims | refreshed |
| `16-edit-resend.png` | re-shot | keep claim | refreshed |
| `17-agent-picker.png` | re-shot | names check | refreshed |
| `21/22/23/24` sessions | re-shot | keep claims | refreshed |
| `27-commands-overlay.png` | re-shot | aurora note | refreshed |
| — (new, 2026-11-08) | pane v3: desktop/plugin.js data plane moved from the (nonexistent — measured) `host.callTool` to `ctx.rest` against NEW `dashboard/{manifest.json,plugin_api.py}` (`GET /status`, `POST /enable`, `GET /pin` at `/api/plugins/wearos/*`); errors now surface in the pane instead of a silent empty checklist; step 1 carries the open-source-forever line. `plugin.yaml` description rewritten to lead with "pairs the Hermes Wear OS watch app" + repo link; `requires_hermes` floor raised to >=0.23.0 (measured). Hot-mount proven by core's own test — backend answers without app restart; Rescan only refreshes JS. Gotchas pinned by tests: sys.modules can alias the plugin file under a bare `__init__` (prefer the `_CTX`-bound instance), and `_door_listening()` probes the loopback port for real — tests must fake it on a live gateway box |
| — (new, 2026-11 round) | `35-splash-film.gif` (REAL looping intro film, cut from the shipped asset — superseded `.png`), `36-splash-picker.png`, `37-voice-output.png`, `38-volume-db.png`, `39-karaoke-follow.png`, `40-stt-source.png`, `41-edge-circle-pair.png`, `42-gateways-multi.png`, `43-agent-keys.png`, `44-notify-desktop-approval.png`, `45-notify-desktop-settled.png`, `46-attention-pair.png`, `47-about-screen.png` | NEW README rows in feature order — splash-first hero, voice suite, desktop watch, attention policy, About | refreshed |
| hero film | **v0.8 cut built (2026-11-07): `demo-vertical-v0.8.0-raw.mp4`** — 24 beats / 141.3 s / 1080×1920@30, silent, `v0.8.0 · wear-demo` bug, every motion window ffprobe-fitted to the real takes (r-media 4.7 s!); batch-10 pixels throughout (4-col table, LR mermaid, real gated card + modes sheet, aurora media, GIF-card MOTION take); the attention PAIR ships as its right half (no two-watch frame); beats lost to budget: 04-viewer (lives inside r-media's pinch), 26-picked, 31-plain; karaoke still (take still pending). Silent master at `/tmp/opencode/vid/` (never committed) — owner scores + uploads, then README YouTube link swap | pills re-worded to pixels | refreshed (owner upload pending) |
| `demo-walkthrough.gif` | RE-CUT from the new film's keyframes; committed as **`demo-walkthrough-v0.6.0.gif`** (version in the name — README links the current one; old GIF purged) | — | refreshed |
| `21-session-search.png` | re-shot (batch-10, post world-prune) | claim REFRESHED: query `harbor`, `Search · 14`, watch-tagged brief + [Harbor]-bracketed rows; IME up at type time (query lands via `-e search` for the shot) | refreshed |
| `11-gateways.png` | re-shot (from the r7d film raw — live registry); 42 shows the same registry a beat later | alt re-worded to pixels | refreshed |
| `17-agent-picker.png` | re-shot | agent-switch speed note (batch 5); ● marks the live agent | refreshed |
| — (new, batch 9 / v0.7.0) | `48-media-cards.png`, `49-video-player.png`, `50-gif-card.gif` (MOTION artifact, screen-recorded), `51-video-pinched.png`, `52-audio-player.png`, `53-handoff-adopted.png`, `54-tool-activity.png`, `55-tailnet-status.png`; re-shots of `08`/`11`/`42`/`47` | NEW README rows in the feature grid + handoff lead-in + tailnet img in §Networking + `wifi(wlan0)`/pixelation alt refresh | refreshed |
| hero GIF | re-cut as **`demo-walkthrough-v0.8.0.gif`** (v0.7.0 GIF purged per §9): splash film FIRST (shipped-asset frames) → 16 captioned v0.8 feature beats (incl. 02b grid, 56 modes, aurora 48, animated 50) → end card; 42 s loop, 5.06 MB, `v0.8.0 · wear-demo` bug; README hero img swapped to it (35-splash-film.gif stays in the feature grid). Silent master for the v0.8 tour video rides the wave-4 trio | alt lists the beats | refreshed |
| weights section | numbers re-measured (106 MB APK incl. the gomobile tailnet node) | table matches `unzip -l` of the shipped debug APK | refreshed |
| — (new, 2026-11-08 / catalog-PR wave) | `49-video-player.gif` **replaced with a real generated clip** (comfy MiniMax H3 aurora, take-5 recipe in row 50), plus the plugin-side gallery `hermes-plugin/docs/screenshots/{05-aurora-player.gif, 06-pin-screen.png, 07-stop-running.png, 12-keyboard.png, 66-wearkit-pane.png}` — the mirror repo now carries its own visuals, plugin surfaces first | plugin README gallery re-ordered (pane → PIN → aurora → orb → keyboard → the wrist set); monorepo README gains the pane image + Play-Store/sidecar-trajectory copy | shot ✓ (2026-11-08) |
| `06-pin-screen.png` (plugin gallery) | **the PIN hero**: captured on the `hermeswear` AVD with app state wiped and a generic tailnet handle (`acme-corp`), fresh APK from main — the Pair page needs a build ≥ `adb8b59`; the Pair list rests at this scroll offset (PIN y≈422), so the masked hero IS a genuine viewport, no compositing | 6-digit PIN is the pairing UX; sweep-clean (`--psm 11`) | shot ✓ (2026-11-08) |
| `67-wearkit-pane.png` (monorepo wall; the mirror ships it as `docs/screenshots/66-wearkit-pane.png`) | desktop pane screenshot with the owner's private agent rows **blurred** (merge / upwork / redridge-treasurer) and a transient 502 banner stitched out; `borealBytes/hermes-wearos` on card 1 kept — public org citation | (none; blur disclosure inline) | shot ✓ (re-shoot when the pane grows the install verbs) |

**v0.8 plugin-era re-shoot wave (2026-11-06/07):** every screen re-captured against the live
watch + fresh wear-demo world (two Harbor briefs + aurora + mooring seeded; desk noise archived
via the feature itself). Per-family commits: `bb8086d` sessions (08/09/21/22/23/24), `95fd3c3`
chat + voice (02b/03/04/05/06/07/12/16/25/26/27/29/33/34/37/38/40 + the voice-in quartet
56-59, now gated), `ed4b79e` settings (01/10/11/14a/14b/15/18/19/36/42/43/47/55), `186af52`
media family on /tmp fixtures + notifications (48/49/51/52/53 + 31/32 + 30), `4799c9b` README
restructure + 41 pair + `tools/demo/pair.py`. Notes: the `av` render fixture moved to
`/tmp/clip.mp4` + `/tmp/tone.opus` (the paths §6 stages — the old machine-local path 403'd
through img_roots); **35 needs no re-shoot** (film asset unchanged since its gif cut — cold
launch re-recorded as proof it still plays); 33 ships the REAL gated card (classic terminal…
chip, run approved via `-e approve` for the frame; the `approval_demo` inject is the simplified
narrative variant); tailnet pages redact the FQDN + peer-IP bands with OCR-measured boxes
(55/31; re-scan zero hits); `17` now pins the ●-marked blue wear-demo pill.

**Close-out state (2026-11-07, blitz tip `5d9562a`):** every agent-doable deliverable shipped —
39/50/44/45/46 and the final `21` all PASS on the pruned world (the sglang lane drained
mid-wave; 33/34 re-shot from a real gated run). What remains is OWNER-ONLY, in order:
(1) score + upload `/tmp/opencode/vid/demo-vertical-v0.8.0-raw.mp4` (silent, 24 beats,
141.3 s), then swap the README hero-film line to the v0.8 tour link; (2) the Tailscale-console
pair shot — the camoufox tab parked on the login screen for the owner's manual noVNC login
(`/tmp/opencode/rs/console-login-needed.png`); (3) the Wear Kit PANE v2 shot — needs the
owner's **Rescan** click in the Desktop app (plugin state untouched since Nov 6 13:16 = no
rescan yet). The karaoke MOTION take is NOT a loose end: the watch stays muted by owner
doctrine, so beat 14 of the film (and shot 39) ship as stills for good. Docs VM intentionally
left RUNNING (`capture.sh --only-a` re-runnable without spending a key; `run.sh --destroy`
when done). Old-media purge per §9: the v0.6 /tmp master is deleted; v0.7's is kept until the
v0.8 upload lands, then the v0.7 README link retires too.

**Batch-10 wave (2026-11-07, the owner-rework round):** five asks — approvals-levels shot,
richer + centered mermaid, approval card shows the parked command, 4-column centered table,
video media as gifs, all vision-verified; hero GIF re-cut with splash-first feature walk.
Code: `WatchMarkdown.gridable` widened to 2–4 labelled cols (5+ still degrade), grid cells
center-aligned, `tables` case → harbor Slip/Depth/Tide/Wind, `mermaid` case → `graph LR` with
the wrist subgraph + classDef colors (TD was too tall for the round glass), `av` case →
`MEDIA:/tmp/opencode/aurora-vid.gif` (comfy agent render via the gateway — the card animates
inline), `ApprovalCard` question now a 3-line mono chip. **The re-shoot found a real bug:**
newer session-authority gateways 400 the watch's approval POST unless the body is exactly
`{request_id, execution_generation, choice}` — fixed end-to-end (ApprovalInfo.gen + status
poll normalizes `pending_controls[]` + one retry-from-snapshot on 400). Shots: `02b` PASS
(t02bJ — grid dead-center; first two frames were splash-swallowed and one re-mask read a
stale square: masks must diff 0 against their input), `05` PASS, `48` PASS (aurora + audio
sting), `56` PASS (NEW — `-e approvals 1`, zero code), `33` PASS — re-shot from a REAL
gated run (batch10i, fresh chat, card scrolled center: yellow heading + mono chip with the
exact parked command + wrench status + live stop orb; the ✅/❌ flanks hide while the card
is fully on-glass — the app's own bottom-only rule), `34` PASS — that same run's settled
answer after the wrist approve: parked → answered with the gen triple → `finish run
error=false`, the fixed POST accepted FIRST TRY (retry-from-snapshot held in reserve,
normalizer unit-proven). Docs-VM:
plugin/config shots re-plumbed to the DESKTOP-IN-WEB ticket lane (`webapp_open` in
capture.sh) — the Wear Kit PANE itself is desktop-APP-only, now stated in that README. Hero:
`demo-walkthrough-v0.8.0.gif` (splash first → 16 beats → end card; v0.7.0 purged per §9).
World hygiene: 13 probe-junk sessions archived through the feature itself (the `run_e23ae…`
raw-id chat went the same way — 14 total); the archive story rows untouched. Final `21`
re-shot on the pruned world in the SAME wave: `Search · 14` on `harbor`, watch-tag +
[Harbor] brackets legible — PASS.

## 4. Capture recipes (hard-won; read before touching the watch)

**Device hardening (every capture session, in order):**

```bash
export ANDROID_SERIAL=<watch ip:port>     # port rotates on EVERY reconnect — ping first
adb shell svc power stayon true
adb shell dumpsys deviceidle disable
adb shell settings put system screen_off_timeout 600000
adb shell settings put secure bedtime_mode_on 0        # else night = artwork overlay + dead input
adb shell settings put global enable_display_offload 0 # else screencap returns STALE AOD frames
```

**Gesture truth on this Galaxy Watch (OneUI Wear):**

| Want | Gesture |
|---|---|
| Notification list | from the WATCH FACE: swipe **left→right** (tiles panel ≠ notifications; `cmd statusbar expand-notifications` only opens tiles) |
| Notification actions | tap the card once → expands inline (Approve/Deny); posting must be FRESH-UNREAD (`notify_demo` posts a new id each time) or OneUI won't front the drawer |
| Anything clickable | uiautomator dump bounds = ground truth; scaled-screenshot guesses miss |
| A markdown link inside chat text | uiautomator lies for scaled/scrolling compose text — instead run **blue-pixel forensics on the screencap itself** (screencap is 480×480 = device pixels, 1:1 with `input tap`): LinkBlue `0xFF8AB4F8` glyph clusters + their underline runs mark the exact link box; tap its center. Tapping your OWN message chip opens the Edit & resend sheet (useful for S16, annoying mid-link-hunt). |

**watchkit mermaid broke overnight = npx auto-update pulled a mermaid-cli whose puppeteer wants a newer `chrome-headless-shell` than `.cache/puppeteer` holds (an empty stub dir masks the link). Fix without duplicating storage: symlink the version from `~/.cache/puppeteer/chrome-headless-shell/` into `server/watchkit/.cache/puppeteer/chrome-headless-shell/` and re-POST `/render/mermaid`.**

**Screenshot hygiene (every shot):**

- docs/screens ships the **circle-masked** generation (owner directive: round-first — the mask is
  exactly what the round watch physically shows; square originals live in `tmp-shots/screens-square/`
  for re-shoots and forensics). Re-shoots: capture square → redact → mask → gate.
- NEVER reuse a filename between live captures (this round re-learned it the hard way: a later
  capture overwrote `C-20-connection.png`, silently mis-filing a Display-section frame as the
  Settings-home candidate — the vision gate caught what the eye skipped). — harness/viewer caches make a
  stale render look fresh (`live-` unique suffixes until accepted).
- Pixel-forensics before perception: region means, button-color counts, two
  shots 3 s apart (identical md5 + content that should animate = frozen frame). Weight claims
  (bold/italic) verify as ink-density ratios across lines, not vibes.
- Synthetic dial-lane releases do NOT reliably arm under `adb input` (swipe AND motionevent
  sequences both get eaten as list scrolls or register as orb taps) — dial beats either get owner
  live verification or lean on lane-mapping unit tests; drive screens with the `screen`/`open_chat`
  hooks meanwhile. Beware the `screen`-hook StateFlow dedup: resend the SAME value = no-op —
  step through a second value first (`chats` then `sessions`).
- Settings token/key fields render RAW (no PasswordVisualTransformation exists yet) — any
  connection-editor shot must blur field values or the live `API_SERVER_KEY` ships in the README.
- OneUI search traps: entering Sessions auto-focuses search (IME toolbar shifts every hit target
  +60px); the toolbar ✕ pops to home; `screen` injections are ignored while the Agents modal is
  up; tap rows by uiautomator bounds only.
- **screenrecord discipline**: NEVER `pkill screenrecord` (moov never written → whole take is
  garbage; let `--time-limit` expire, then wait ~5 s, then pull). Keep `svc power stayon true` +
  `dumpsys deviceidle disable` — a screen timeout mid-take truncates the recording too (a 100 s
  take died at 60 s when the display slept; stay-on only holds while tethered/charging).
- **Watch stop-orb**: during an active run the red circle + white square at bottom-center is the
  STOP button — tapping it to dismiss "an overlay" silently cancels the run. While it's up the
  chat's lower controls can also swallow taps (in-session Approve taps missed twice; the
  `-e approve` hook resolves via the same path the button uses — fine for film, honest in log).
- Blur redaction must be measured, not guessed: locate value text rows by brightness-band scan
  (rows with >8 bright px in the field box), blur THAT strip ±6 px. Two rounds shipped with
  hand-placed bands that covered Save/Test while leaving the token readable (owner caught it).
- Vision review (sglang endpoint, T=0, ONE claim per pass, scan `content` +
  `reasoning_content`) against the exact README caption → `shot ✓`.

**Video:** `screenrecord` on Wear freezes when the compositor idles (display
offload) — offload is disabled above; still drive a small UI animation per take.
Assemble traps: `-ss` AFTER all `-i`; drawtext text without `:` or `'`; escape
commas inside `between(t\,a\,b)`; `pkill` zombie ffmpeg before retry.

**Batch-9 recipes (2026-11-03, the v0.7.0 media round):**

- Staged media lives on the GATEWAY box (`/tmp/anim.gif`, `/tmp/clip.mp4` H.264+AAC, `/tmp/tone.opus`)
  — `/tmp` is a watchkit img/media root already; ask wear-demo to emit the exact `MEDIA:` line(s).
- **Video pinch still:** `-e media_zoom <s>` fires the player's own `animateScale` (adb can't inject
  two fingers); play first (tap center), then zoom, then capture — a paused frame carries the big ▶ glyph.
- **Bezel-volume still:** `-e volume <±N>` drives the same lane the physical bezel fires; the 🔊
  readout lives 1.1 s — fire the broadcast and screencap in the same breath. Player volume starts at
  1.0 (ceiling), so go NEGATIVE to land a readable percentage like 60 %.
- **Motion artifacts:** `screenrecord --time-limit 3`, pull, crop the card box (measure rows by
  saturation, don't eyeball — one crop missed and shipped the prompt bubble), circle-mask every
  frame, one GIF @10 fps, `disposal=2` — 0.3 MB for 26 frames.
- **Approval-flanks shot (2026-11-07):** the ✅/❌ orbs require `approval pending AND chat at
  bottom` (MainActivity flanks gate) — park the approval MID-STREAM (`-e text` a slow count, then
  `-e approval_demo 1` ~1 s in); the streaming tail scrolls the card off-glass by itself. A
  post-finish swipe instead trips the card-rescue re-center and you shoot the card again.
- **Video-player shot:** reach fullscreen by TAPPING the media card's ▶ chip (uiautomator-measured
  coords) — `-e play_url` is the audio-only ExoPlayer stub, no player UI. While the controller is
  hidden, PinchPlayerView swallows adb keyevents: start the rec off a ⟲5-rewind (chrome shows
  ~3.5 s at playback end). If the gateway-side fixture expired, the watchkit `.cache/media`
  pre-shrink of the same clip is the recovery source.
- **Multi-frame cells (owner 2026-11-07):** a pair/composite image carries ONLY circle-masked
  watch frames on a transparent ground — NO burned-in labels ("the text belongs in the README
  cell, not the image"), and every frame inside a composite gets the same circle mask as a solo
  shot, or on a dark table it reads square. GIFs included: transparency is per-frame
  (magenta-key → palette index → `transparency=`/`disposal=2`).
- **Hand-off beat:** seed the desktop-starter row straight into the wear-demo state.db (source
  `local`), adopt with `-e resume_session`, ask something only the seeded history answers.
- **Identity scan on the SHIPPED squares** before masking: tesseract `--psm 11` + `--psm 6` sweep
  for hostname/tailnet-id/IP; blur boxes measured from cyan/gray text-band scans (psm-7 crops drift).
- `-e add_gw` wants the full `label|api|watchkit` spec (bare URL = `add_gw BAD spec`).

**Batch-10 recipes (2026-11-07, the owner-rework round):**

- **Aurora gif fixture:** rendered by the GATEWAY's comfy agent (owner doctrine: never the raw
  CLI) — `POST :8642/p/comfyui/v1/chat/completions` with the bearer from `~/.hermes/.env`; ask
  for a short aurora-borealis timelapse saved under `/tmp/opencode/`, then mp4→gif with the repo
  ffmpeg (`fps=12,scale=360:-2,palettegen/paletteuse`). The `av` render case points at
  `/tmp/opencode/aurora-vid.gif` (gif ∈ IMG_EXT → the card ANIMATES inline, shot-50 lane).
- **Approval 400 fix (real bug, found by a re-shoot):** newer session-authority gateways demand
  the EXACT body triple `{request_id, execution_generation, choice}` on
  `POST /v1/runs/{id}/approval` and the run-status snapshot exposes them under
  `pending_controls[]` (`prompt_id`, `execution_generation`) — the watch now threads the gen
  through `ApprovalInfo` and, on a 400, retries ONCE from a fresh status snapshot
  (`approval retry-from-snapshot ok` in logcat = the proof line for shot 34).
- **Splash swallows dev broadcasts** in the first seconds after launch: an
  `-e render …` fired within ~4 s of `monkey … LAUNCHER 1` paints its answer UNDER the splash
  and the screencap captures splash artwork instead (bit two captures this wave). Launch →
  wait for a settled pid → then broadcast → capture.
- **Table/mermaid framing:** after `-e render`, edge-swipe the list to push the fixture
  DEAD-CENTER (round glass buries top-edge content under the clock); re-gate every mask
  against the SQUARE it was cut from (`sampled diff px: 0`) — a stale square = stale shot.
- **Approval-mode sheet (56):** zero code — `-e approvals 1` opens ApprovalModeDialog directly.

**Batch-7 recipes (2026-11-01, the honesty round):**

- **Model toast (row 11):** model picker → provider → pick ONE that answers
  (this box: gpt-6-luna under the ChatGPT/Codex provider); toast lands on the
  chat screen — capture THERE; then send `Reply with exactly: <token>` and
  confirm the reply in logcat (`finish run=… error=false len=…`) BEFORE trusting
  the shot. Restore model to gateway default afterwards.
- **Browser row (15):** deliver the ask with `-e text` (NOT the editor — adb swipes
  inside the editor are parsed as HANDWRITING and silently type junk like "CT"/"VG");
  ask wear-demo for exactly one markdown link (this repo's GitHub page). uiautomator
  reports bogus bounds inside Compose text — locate the link by BLUE-PIXEL FORENSICS
  (saturated-blue HSV cluster in the frame) and tap the CLUSTER CENTROID — the node
  center can ride off the chip's right edge and land on the user-message sheet
  (✎ Edit & resend) instead of the link. Then the browser page renders on the
  watch — capture it directly.
- **Expanded approval (23):** `-e notify_demo approval` → swipe-down shade →
  tap the card ONCE (OneUI expands in place: Open + Clear) → screencap. Deny
  the demo run afterwards: `am broadcast -n dev.hermes.wearos/.notify.ApprovalNotificationReceiver -a dev.hermes.wearos.DENY`.
- **Voice-in scroll (41):** Settings ▸ Voice in, then swipe the list up until
  the GATEWAY engine rows (whisper model choice incl.) are visible; capture
  with the local rows still partly in frame.
- **About page (49):** Settings ▸ ℹ️ About; nothing to scroll — but re-read the
  version line against root `VERSION` before shipping.
- **Privacy pixelator:** OCR every artifact (tesseract tsv, word boxes);
  pixelate hits (block ≥ height/3 + blur, ALPHA-PRESERVING via
  `IMREAD_UNCHANGED` + write back the BGRA); for GIFs extract with
  `-fps_mode passthrough`, mask hit frames AND the ±fade frames around each
  hit (confirm fades with the vision endpoint — OCR lies there), remux via
  palettegen/paletteuse, re-extract and re-OCR the final GIF = ZERO hits.
- **Walkthrough GIF (no master needed):** compose from `docs/screens/` stills +
  caption pills (`canvas_build.py` fonts/patterns) + intro-motion frames cut
  from the shipped asset; ~1500 ms still holds, ~2500 ms hero/end, ~125 ms
  motion frames; burn the `v<VER> · wear-demo` bug.
- **One screen per watch frame (owner, 2026-11-02):** `*-pair.png` artifacts are README
  side-by-side layouts — NEVER place one inside the GIF's watch circle (two tiny watches
  read as broken). `gif_build` splits pairs at build (`pair_half`, cached) and captions each
  half as its own beat.
- **Caption typography (owner round 2, 2026-11-02):** every pill is centered
  by measuring the text with the SAME tracking it draws with (measure-track-6
  + draw-track-2 = off-center box, clipped text — the exact bug that shipped);
  long captions SHRINK the font until the pill fits the canvas, never clip.
  Wordmark frames ride the TOP band (measured via textbbox, sub below with a
  gap) — and NO frame is without the watch circle: the open plays the intro
  film inside the circle, the close holds its final frame there. Pixel-check
  the emitted canvas PNGs (pill bbox centered ±4 px, ring never under text).
- **Master mp4:** `DEMO_MASTER=1 python3 tools/demo/gif_build.py` renders the
  same beats at 1080×1920@24 → `/tmp/opencode/vid/demo-vertical-v<VER>-raw.mp4`
  (silent, h264, faststart) for the owner to score + upload. NEVER committed.

## 5. Design system (Nous Hermes-brand)

- Fonts (downloaded from nousresearch.com to `/tmp/opencode/demo/fonts/`):
  `RulesVariable.woff2`, `RulesGothicCnd-Light.woff2` (title caps),
  `RulesGothicCmp-Medium.woff2`, body `AeonikFonoProTRIAL-Regular.woff2`.
  Frames-only use (TRIAL license).
- Palette: cobalt `#0000f2` (accents/CTAs), off-white `#f2f2f2` (text),
  near-black `#191919`→midnight `#19192f` (grounds).
- Canvas: 1080×1920. Generated art fills the FULL canvas (no black bars); the
  phone-shaped capture floats over it; the watch circle sits on the art, framed
  by a 2px cobalt ring.
- Captions: transparent-overlay pill — `#0B0B14` at ~72% alpha, 1px `#0000f2`
  edge, RulesGothicCnd caps — **fades in/out with the spoken beat** (alpha
  ramps), never a hard black band.
- Art: gateway image agent (same pipeline as the self-portrait), engraved
  silver-on-midnight style. Prompt drafts (owner picks one, or remixes):
  1. Harbor at night in engraved silver, aurora ribbon overhead, a pocket watch
     floating in the tide showing three hands.
  2. Winged spirit rising out of an open pocket watch over dark water, star
     rays — matches the owner's watch-face taste.
  3. Lighthouse whose beam is a blinking stream cursor, night sea, fine-line
     copperplate.
  4. Orb spirit between constellations that trace a chat bubble, silver
     engraving on midnight.

## 6. wear-demo staging (before the camera rolls)

- Gateway: `wear-demo` profile (key already in the watch's agent map).
- Seed lived-in history (via API inserts, timestamps spread over days):
  harbor briefs with plots, an aurora forecast, a mooring-fee thread with
  Hannah drafts, one long essay reply, one mermaid, one archived pair.
- Search beat queries `harbor` → matches ONLY seeded rows.
- Fork/archive/unarchive beats act on seeded rows only.
- Old wear-demo chatter cleared with the archive feature itself (dogfood).
- Approval beats ride a REAL gated tool run in wear-demo (in-session card S33
  and backgrounded S32 are two separate runs).
- **Desktop-watch beats (S44a/S44b):** drive them from the API lane on the gateway box — a
  curl'd ask at `wear-demo` (finished-answer pulse) and a gated tool parked via the `waiting
  for user approval` beacon — while the watch sits backgrounded; the buzz + card prove the
  away-watch path end to end. This is also the ATTENTION POLICY beat (S45): same off-screen
  run → notify + silent; then open the chat on the wrist and let another answer land → inline
  + speech-path, zero notify. Capture both halves as `46-attention-pair.png`.
- Voice beats (S37/S38/S39): voice-out ON with the owner-blessed dB volume, engine/voice as
  configured, karaoke visibly scrolling with the spoken sentence; the STT source row shows
  `Gateway · parakeet` (owner's daily pick) with the on-watch Vosk fallback visible.
- Splash act (S35a/S35b): record the cold-launch burst (held-icon frames → MOVING film frame);
  the built-in film is the hero (owner media stays local).
- Model-pick beat: pick → confirm chip → restore default before A4.

## 7. Assembly (Phase 4)

Pipeline (scripts live in `/tmp/opencode/` — copy to `tools/demo/` before they burn in a reboot):
`canvas_build.py` (harbor canvas + masks + pills) → `assemble.py` (beat cut list → segments →
concat) → final medium/crf24 re-encode → `gif_build.py` (walkthrough keyframes → palette GIF).

**Versioned artifacts (owner rule 2026-11-01):** all three scripts read repo-root `VERSION`.
`assemble.py` emits `vid/demo-vertical-v<VER>-raw.mp4` (+ metadata `comment=hermes-wearos v<VER>`);
the owner scores that file and keeps the stem (`demo-vertical-v<VER>.mp4`); `gif_build.py` then
prefers the SCORED film for keyframes, falling back to the raw — so the GIF always matches the
video the owner actually published — and commits `vid/demo-walkthrough-v<VER>.gif`. The canvas
bottom-right bug (`v<VER> · wear-demo`) rides every frame of both.

**Owner-approved design (2026-10-27, keep unless overridden):**
- Art canvas: agent self-portrait harbor plate (`comfyui state demo-art/draft-1`), cover-cropped
  1080×1920, dimmed to 0.82, vignette. Cobalt (#3C63DD) ring + caption pills in
  RulesGothicCnd-Light caps ( Nous brand fonts, converted woff2→ttf via fontTools, kept in
  `../devkit/demo/fonts/` — trial fonts, never commit).
- Watch circle = **760 px (70 % of canvas width)** at (160, 280) — owner: "640 was too small".
  Clip is a plain 480×480 screencap → alpha-masked to the circle; mask is TRANSPARENT outside
  (not white) with a ~2 px white screen-edge ring + faint halo so it reads as watch glass on any
  background.
- Caption pills y≈1150, cobalt outline, ink #0B0B14 @ 80 %.
- **The circle is NEVER empty** (owner, 2026-10-27): any beat without a capture fills the watch
  circle with the splash art (`tools/demo/art/hermes-watch-splash.png`, circle-masked in
  `canvas_build.py` → `splash_circle.png`, overlaid at 164:284 so the white edge ring survives).
  Hero and end cards run on it.
- **Beat arcs are LONG on purpose** (owner: "more demo per step — show the best parts"): every
  feature beat shows the whole arc — action → stream → result. "Long" means LONG ON CONTENT,
  not long in wall-clock: place the window on the motion (timeline forensics, §4) so every
  second shows something moving — 6–24 s per beat, silent master 3:37. Dead air reads broken;
  an arc packed with motion reads like proof even at 6 s.
- Hero/end cards: wordmark over the canvas + empty circle ("your watch, waiting").

**ffmpeg traps this pipeline hits (all fixed in the scripts — do not regress):**
- Watch screenrecord raws are ~5 fps VFR with sparse keyframes: input-side `-ss` is KEYFRAME-COARSE
  and silently shifts beats — use `trim=start:end,setpts=PTS-STARTPTS` in the graph (exact).
- PNG overlays need `-loop 1` (repeatlast alone makes them one frame → hero collapses); cap the
  loop with input `-t` AND output `-frames:v` or the muxer never closes (the classic hang).
- Encoding: CPU `libx264 -preset ultrafast` per segment + `medium -crf 24` final is fine
  (~1 min for 3:37). GPU: ffmpeg here needs driver ≥ 610 for NVENC API 13.1 (box has 580 + a
  4070 AND a Blackwell PRO 6000 — after a driver upgrade flip the codec line in `assemble.py`
  to `h264_nvenc -preset p4 -rc vbr -cq 23` and the final encode drops to seconds).
- GIF: extract keyframes with OUTPUT-side `-ss` only; input-side `-ss` lands on the wrong keyframe.

## 8. Verification gates (all must pass before push)

1. **Coverage ledger:** mechanical check — every §2 row `shot ✓`; every feature
   has BOTH a screenshot that exists and a beat present in the assembled film's
   cut list (script compares §2 ⇄ `docs/screens/` ⇄ manifest).
2. **Vision review:** every README `<img>` and every film keyframe against its
   exact claim, fresh filename, T=0, single claim.
3. **Privacy pass:** film + shots contain only wear-demo content (§0 rule 1);
   reviewer prompt includes the seeded-title whitelist; anything else = fail.
   Plus the ID scan: OCR every shipped PNG and EVERY GIF frame (fades checked
   by vision) for username/home-path/coordinates — zero hits or it doesn't ship;
   docs text keeps `/run/media/<user>/…`.
4. **Captions:** every README row `refreshed` in §3, text matches pixels.
5. Tests green (`gradle :app:testDebugUnitTest`).

## 9. History invariant (after every media round)

**Tracked media = `docs/screens/*.png` + `docs/demo/demo-walkthrough-v<VER>.gif` (version in
the filename; the old version's GIF is purged with the round, never kept side by side). The mp4
master is NOT tracked** (`docs/demo/*.mp4` gitignored — videos make this repo heavy; it
regenerates via `tools/demo/` and lives locally, published on YouTube — README links, never
embeds, the mp4).

For tracked media: commit new → `git filter-branch --index-filter "git rm -r --cached
--ignore-unmatch <paths>"` over `origin/main..HEAD` (NOTE: the tree must be CLEAN or
filter-branch silently no-ops; and `[ "$x" != "$KEEP*" ]` never globs inside `[ ]` — exact
strings only) → restore from `refs/original` on any botched pass → single
`media: current generation` commit → `git gc --prune=now` → `git push --force-with-lease`.
Branch keeps exactly ONE generation of every tracked media path.

## 10. Feature changelog (append when a feature lands)

- **2026-11-03 (batch-9: the v0.7.0 media round)** — every artifact regenerated for THIS branch only
  (owner: "leave old features that didn't change, remove/update what did"). NEW: media-epic cards +
  fullscreen player (48/49/52), the first MOTION artifact since the splash — a real screen-recorded
  animated-GIF card (50-gif-card.gif), pinched video (51, via the new `-e media_zoom` hook), the
  60 % bezel-readout frame, hand-off adopted-with-history (53), tool-activity card (54), and the
  Tailnet status page the owner asked to show (55, FQDN + peer IP pixelated on the square before
  masking). RE-SHOT (changed surfaces): 08 (💻 computer chip), 11/42 (transport row + pixelated
  hostname; harbor-lab restaged and deleted after), 47 (v0.7.0 line), 15 (v0.7.0 stamp on the
  About row). Vision gate re-run across every changed claim: 61/61 PASS (two claim-wording
  fixes fell out of it: the transport chips are plain skip chips; reply text is white). RETIRED wording everywhere:
  "LAN or Tailscale" is dead — the embedded node IS the transport (§11.4); weights re-measured
  (106 MB incl. the 44 MB gomobile node across ABIs); voice table says the mic rides as Opus.
  Captured on the Wear OS 5 emulator (the physical watch's port slept mid-round — honest note in
  the README license line; the volume key path is the bezel's own). GIF re-cut
  `demo-walkthrough-v0.7.0.gif`; silent master `demo-vertical-v0.7.0-raw.mp4` — owner scored and uploaded
  it as the v0.7.0 YouTube Short the README hero now links.
  `tools/demo/pixelate.py` persisted (the round-1 tool had been living in /tmp). Post-review fixups rode the same
  commit train: the batch-9 stills first shipped as black-corner squares — re-masked to
  transparent circles (new §0 bullet + the `demo-check.sh` corner-alpha audit make it permanent),
  and a full privacy sweep pixelated the Settings-home hostname (row 18 re-shot: harbor-lab
  restaged, the real hostname mosaic-blurred on the square, deleted after) AND scrubbed every real
  hostname / tailnet name / peer IP / home-path out of ALL repo text — code comments, test
  fixtures and docs now carry neutral stand-ins (`tailca7ee1`, `labhost`, `100.99.1.42`,
  `/home/user`) so the public clone cannot rebuild the owner's network.

- **2026-11-02 (batch-8: GIF typography round, owner screenshot review)** — the owner read the
  walkthrough GIF frame-by-frame and the canvas port lost its geometry: caption pills measured
  width at track-6 while drawing at track-2 (box too wide, text drifting left, long captions
  clipped off-canvas), and the wordmark frames collided (1080 y-positions ported blind) AND had
  no watch face ("I don't want any screen where we don't have the watch face part on it"). SHIPPED:
  gif_build pills now measure with the tracking they draw and shrink the font until they fit;
  wordmark rides the top band, measured apart; the OPEN beat plays the intro film INSIDE the
  watch circle under the title (per owner: "put that text at the very top… let it play at the
  very beginning"), the CLOSE holds the film's final frame in the circle — no circle-less frames
  exist anymore; 36-splash-picker got its own caption beat. `canvas_build` got the same exact-
  centering formula so the family shares the rule. `DEMO_MASTER=1` renders the same beats at
  1080×1920@24 → silent h264 master for the owner's YouTube pass (never committed). Re-shot
  `42-gateways-multi` on the fixed build (its old frame still wore the clock-pill collision);
  staging harbor-lab with `-e add_gw` exposed a REAL bug — the dev upsert never refreshed the
  `gatewayList` mirror the Settings UI renders (contract says re-read after every mutation) —
  fixed in WearApp. 42 re-gated PASS; every emitted canvas frame pixel-checked (pill centered
  ±4 px, top text clear of the ring); privacy re-swept 0 leaks over 821 frames. Watch nudge
  trap: at default scale 14 px swipes inside rows read as CLICKS (hit a gateway ✕ twice) —
  nudge scrollbars from dead space, ≥40 px. Second owner pass: the README pair-comparisons
  (41/46) had been dropped into the GIF's circle as two tiny watches — `gif_build` now splits
  pairs at build and captions each half as its own beat (four single-screen beats), rule in §4.

- **2026-11-01 (batch-7: honesty round)** — owner's eye found what the gates missed. SHIPPED CHANGES:
  `/home/<user>` pixelated out of `09`+`14a` stills and every v0.6.0 hero-GIF frame (fade frames caught by
  the vision endpoint after OCR went blind on dissolves; mask must be ALPHA-PRESERVING — plain imread
  silently squares the circle); SPEC text anonymized; §8 privacy gate extended (OCR every PNG + EVERY GIF
  frame, fades by vision; `Clayton` whitelisted — it's a credit, not a leak). Honest re-shoots: `26` =
  **gpt-6-luna** (an answerable model — fable-5.1 400s here), `28` = this repo's REAL GitHub page (ask via
  `-e text`; the editor parses adb swipes as HANDWRITING — it typed "CT"/"VG" into drafts; tap the blue
  cluster CENTROID, node bounds lie), `32` = EXPANDED approval card — Approve/Deny/Open session ALL render
  (the "OneUI caps at Open+Clear" ledger note was wrong), `40` = gateway engine picker (whisper/parakeet
  rows; needed the v0.6.0 watchkit — the boot-restored systemd daemon was the old main-branch copy), `47`
  = About page (new row; its title sat under the clock pill at default scale → SettingsScreen top padding
  12→34dp shipped), `35-splash-film` = real transparent GIF cut from the shipped asset. The walkthrough GIF
  no longer needs the master mp4: `gif_build.py` composes committed circle-masked stills + asset-motion
  intro on the film's canvas, 1.5 s holds — 59.5 s legible loop, vision-gated 48/48. Dev hooks: `-e dev_lane`
  (adb can't hold a Compose drag — lane destinations are now deterministic). README: OpenCode paragraph
  says learned-from ≠ reused (self-patch anecdote deleted per owner), splash caption gains tap-to-skip +
  the film's provenance (owner's first fully-local AI video; Qwen Image + MiniMax, audio included), voice
  ladder rebuilt around the real three-way picker incl. parakeet.sh one-shot setup, body tools say which
  signals TRULY work (SpO2/sleep: wired, waiting, quiet — RE is the plan), and a size-and-aging section
  with the watch's own numbers (60 MB APK debug, 68 MB optional model, 25 KB cache that never grows).
  Dokploy was never real — ignore every past mention.

- **2026-11-01 (v0.6.0 media round)** — full re-shoot of every §2 row against the v0.6.0 build;
  film re-cut from seven fresh `screenrecord` takes (`vid/raw/r[0-8]*`, logcat marks per take via
  `rec.sh`/`rec-stop.sh`) into the 20-beat `demo-vertical-v0.6.0-raw.mp4` (149 s). Honest capture
  notes: `06-ptt-overlay.png` renders the shipped overlay via `-e ptt_demo` (the film's PTT beat is
  a REAL Vosk partial); `21-session-search.png` query is `harbors ` (IME up doubles as row 34's
  keyboard shot); `35-splash-film.png` is a film frame from the built-in intro movie; the desktop-
  watch pair came from farm-advisor (owner's desktop agent) parking a real `uptime` approval while
  the watch was backgrounded. Assemble trap re-confirmed: `screenrecord` raws are VFR — all frame
  checks and cuts must use OUTPUT-side seeks (`-i` then `-ss`, or `trim`); input-side `-ss` lands
  on the wrong second and invents content (an "approval card" frame that never existed at t=18).
  Vision-gate round 1 (16 fails) forced honest remaps: `26-model-picked` = the pick TOAST frame
  (model-26e), `27-commands-overlay` = the SKILLS frame (cmd-27b — no aurora-filter frame ever
  existed), `33/34` = pending card vs post-approve streaming (the wedges hide during a live tool),
  `34` approved = wedge-less stop-orb frame, `35-splash-film` = a REAL film frame (opened watch
  over clouds, from r0), `30-settings-lane` = `dial_preview` over the empty new-session home,
  `40-stt-source` re-shot after switching Voice in back to Local Vosk (owner default), `42` = the
  clean r7d registry frame (its 20 s twin was a black screen), `14a/b` re-shot as chat text at
  ×0.75/×1.6 (the first bank showed the Display-page multiplier label, not chat). `28` is the
  example.com placeholder page (Arabic + Chinese below the English) — captions rewritten to pixels.
- **2026-10-27 (film round + owner-feedback re-cut)** — silent master `demo-vertical.mp4`
  (3:35, 1080×1920, 20 segments) + `demo-walkthrough.gif` (20 keyframes, 16.4 s loop) shipped;
  old `hermes-wear-vertical.*` / `orb-dial.gif` retired. First cut FAILED owner review: black
  watch spans, dead-static stretches, stale pills. Fix = forensics, not guessing:
  `timeline.py` (per-second luma+motion over every raw) + a contact sheet of every segment's
  55% frame — every window then placed on measured evidence (white mermaid content is luma 164
  at a1b 15–33; sheet scrim at a5b 59–64; expanded approval card at a6 63–74 with the tap at
  71…). Two hard traps learned: watch raws can be mostly SCREEN-OFF (watch not charging →
  ambient at ~15 s kills `screenrecord` mid-take — every new-take attempt that day died at
  13–15 s), and text-only screens measure dark (~luma 6–13) so luma thresholds must never
  cancel a beat that a real frame-check proves is good.
- **Later-gen watch raws became burst-VFR junk** (a8f: 31 frames in 22 s, delivered in 3 clusters
  at pts 0-2.6 / 14.9-15.7 / 22.5): `trim` windows land in EMPTY spans (black segs) and output
  `-ss` silently clamps to the next burst, faking "content at every time". Check
  `ffprobe -show_entries frame=pts_time` before trusting any new raw. When the footage is only
  stills, choreograph honestly: pose stills + slow zoompan push-in reads editorial, a dead
  recording reads broken (that is what the STOP beat is now). `zoompan d=` multiplies frames —
  always cap with `-frames:v`.
- **Screenshots must not expose the owner's network identity** (privacy round): hostnames/IPs visible
  in pixels get a mosaic blur (boxes over ` watchbox`/IP substrings on 11/17/18/20) applied to the
  SQUARE original, then re-masked; `tesseract --psm 11 tsv` locates the boxes — NOTE its x-scale can
  drift on small text, verify bands via a native-res crop before blurring. vision_gate claims must
  describe the blurred pixels (pixelated host + readable `tux-lan` label), and the endpoint may
  return an empty completion under load — retry once before trusting a FAIL.
- **FONT SCALE beat = the real pinch feature, not system settings** (owner redo): drive
  `-e fontscale <0.75..1.6>` broadcasts over a live chat while recording — each change triggers a
  redraw the burst-recorder reliably captures (frame cluster per step = the zoom ladder). Content
  must be a SHOWCASE: ask the model for a heading + bold + 2-column table (2-col renders as the
  real grid; 5+ cols degrades to list rows) + bullets, scroll to TOP before recording. The
  delivered beat = 7 hard-cut zoom stages (the cuts ARE the pinch motion) + push-in finale. Design locked with owner: 760 px watch circle (70 % width) with
  transparent-outside mask + white glass-edge ring, harbor canvas, cobalt caption pills in
  RulesGothicCnd; windows locked to measured motion/luma timelines (see above). Pipeline persisted
  to `tools/demo/` (canvas_build/assemble/gif_build, `DEMO_TMP` workdir env; fonts stay in devkit —
  trial license). Encode traps + the NVENC driver-610 requirement recorded in §7. Screenshots
  re-issued as circle-masked PNGs (square originals in `tmp-shots/screens-square/`); redaction
  bands re-measured (§4 rule); README gained the round-first note. Film keyframes vision-gated
  (hero/stream/approval/deep-link: PASS). GPU note: box has a 4070 + RTX PRO 6000 Blackwell, but
  ffmpeg's NVENC needs driver ≥ 610 (has 580) — after upgrade switch the codec line in
  `tools/demo/assemble.py` to `h264_nvenc`.

- **2026-10-27 (shots round)** — all 36 screenshots re-shot under wear-demo on `240a957` and
  vision-gated. Findings: (a) **app-level fork 404s** — the Sessions-sheet fork POSTs
  `/p/<profile>/api/sessions/<id>/fork` but the gateway answers `session not found` even for real
  store sessions, while the identical curl with `-d '{}'` + JSON content-type succeeds (likely the
  app's `request()` sends its `{}` body without the JSON content-type and/or a stale base — file a
  follow-up); the fork beat is proven via curl (`Mooring fees — draft for Hannah #2`, deleted
  after). (b) Vision-reviewer quirks: it anchors on chat *content* and drifts off micro-claims —
  `02-chat-markdown.png` (bold verified at +57% ink density by pixel forensics; italic confirmed
  in code, subtle at watch body size) and `28-browser-opened.png` (the site genuinely serves its
  desktop layout to the watch) were accepted after the reviewer's own descriptions confirmed every
  claim element; captions adjusted where pixels disagreed (16 sheet, 18/20 editors, 09 landing,
  17/30 scope). (c) Junk-chat hygiene: the long-press sheet's delete hit `gateway session deleted
  …(0 msgs)` for chats whose gateway twin kept serving — verify purges against the gateway list,
  not just the watch UI.

- **2026-10-26 `cbbaf4d`** — white reply text restored (foundation ClickableText never consults
  the theme's content color — MdBodyText now resolves it; the links round had shipped black-on-
  black body copy, so **every chat-reply screenshot must be from a build ≥ cbbaf4d**); notifications
  gained an **Open session** action that lands the notice's own chat, switching agents first when
  needed (`notify_demo -e notify_chat <id>` aims the demo card at any session — the A6 beat uses it).
- **2026-10-26 `39f770b`** — orb-spirit notification icon (+ color largeIcon),
  ⚙ settings lane at 3:00, `notify_demo` hook → ledger rows 22, 23 + new S30–S34.
- **2026-11-01 (THE POCKET LIFELINE, row 48)** — the capture round caught #25's dirty secret:
  backgrounded with a quiet process, the away-watch loop dies with Samsung's cached-process
  freeze within a minute (the morning's buzzes only survived because adb traffic kept the app
  warm). #32 hands the off-screen sweep to a chained one-time WorkManager hop (~55 s,
  CONNECTED) armed on stop / cancelled on resume, sharing ONE SweepCursor with the foreground
  loop (no double-buzz; 4 new pins). Wrist-proven: unattended off-wrist settle buzz, 3 sweeps
  through 4 minutes of screen-off darkness (old build: zero). Rows 45/46's shots were captured
  THROUGH this path — the desktop watch pixels are honest pixels now.
- **2026-11-01 (One UI shade hides approval actions)** — the Approve/Deny ACTIONS still post (verified `actions=3` via `dumpsys notification --noredact`) but this watch's One UI notification UI renders only the Open + Clear chips on the expanded card; the stacked-approvals sheet captured 10-27 came from the older AOSP-style shade. The action INTENTS (broadcast `dev.hermes.wearos.APPROVE` with extra `run`) are the shipped answer path — test them via adb rather than chasing the retired sheet.
- **2026-11-01 (standard versioning)** — repo-root `VERSION` (semver, now `0.6.0`) is the single
  source for app versionName/versionCode, watchkit `/health`+MCP version, in-app About, and the
  demo pipeline: versioned film/GIF filenames, container metadata, and a permanent `v<VER> ·
  wear-demo` canvas bug on every frame — the owner uploads scored videos to YouTube and needs the
  version visible. Bump via `scripts/release.sh`; tag merge commits on main `v<VER>`.
- **2026-11-01 (media realign for batch 5+6, rows 36–47)** — the ledger grew for everything
  shipped after the 10-27 round: splash intro film (one cut, `revealIsMoving`), the full voice
  suite (engine/voice/rate settings, dB-taper volume, karaoke follow, 0-dB silence), STT sources
  (on-watch Vosk ⇄ Gateway · parakeet), edge-circle scrolling on every list (2f) with swipes
  intact, multi-gateway registry + agent-keys screen, and the DESKTOP WATCH pair (beacon-derived
  approval parks + finished answers) under THE ATTENTION POLICY (SPEC §16.3: voice only for the
  chat live on screen — the buzz/voice split gets its own film beat S45 and pair shot). ALL §2/§3
  rows for new AND changed features flipped to `todo` — a full fresh capture round, not a delta
  patch (owner directive: "complete, fresh redo"). CONTRIBUTING gained THE MEDIA RULE: refreshing
  this ledger + pixels is the last step before every PR push.
- (next feature updates §2 + README §3 rows in the SAME commit)

- **2026-10-27 (publish round)** — owner's audio-scored master replaced the silent cut; mp4s are no
  longer tracked at all (`.gitignore docs/demo/*.mp4`; canonical home = YouTube, link wired into the
  README hero). The GIF IS the video link (click the strip → tour). Loop slowed to ~31 s
  (1.4 s/frame floor) after owner: "flashes through too damn fast to read". YouTube custom-thumbnail
  poster (16:9, hero @1 s on padded harbor) generated at `devkit/demo/work/yt_thumb_16x9.png`
  (owner copy in ~/Pictures). §9 invariant reworded: tracked media = screenshots + GIF only.
- **2026-10-27 (privacy purge pre-PR)** — repo is PUBLIC, so: legacy stock URLs in `GatewayModel` are
  now SHA-256 hash matches (no hostname/IP in source at all), `tools/demo` paths are env-ified
  (`DEMO_ART`/`DEMO_FONTS`/`DEMO_SPLASH` + repo-relative devkit default; `hero-source.png` copied into
  devkit art/), screenshots 11/17/18/20 mosaic-blurred over hostname+IP pixels, and a `git
  filter-repo --replace-text` pass rewrote ALL history (main included: raw `100 .99.88.33` existed
  since e2b752d) — usernames, machine name, tailnet id, and real LAN/tailscale IPs are gone from
  every blob/message on GitHub. GIF/video frames intentionally keep original pixels (owner OK).
- **2026-10-27 (lat/lng blur)** — diagnostics shot showed the owner's REAL GPS fix (values live on,
  untracked — do NOT record them here; that's the whole point) — tesseract `\d\.\d{3,}` sweep missed
  it: the `lat=` prefix breaks the token regex, scan for `lat[=: ]?-?[0-9]+\.[0-9]+` instead. Values mosaic-blurred (labels `location: fix ok ·
  lat=` kept), re-masked, claim re-gated PASS. README caption rewritten (no "redacted here" prose —
  pixels just are). History: all prior generations of this PNG replaced with the blurred bytes via
  filter-repo blob-callback.
