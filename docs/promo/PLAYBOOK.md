# PLAYBOOK — the clean promo pass (run this, then delete it or keep it, owner's call)

Goal: one brand-new capture set (Nov 11 daylight), all fresh build stamps,
clay-synth-only voice store, then squash every superseded media file off the
branch. Budget ≈ 75 min including one retake allowance. Everything below was
proven on this exact machine on Nov 11 ~01:00; commands are copy-paste.

Working dir for all commands:
`/run/media/clay/Data/dev/hermes-wearos-worktrees/hermes-wearos-plugin`
then `source scripts/env.sh` and `export ANDROID_SERIAL=192.168.10.159:35271`
(port rotates — check `adb devices` / the watch's wireless-debugging screen).

## 0 · Preflight (5 min, gates everything)

| check | command | expect |
|---|---|---|
| pane is current | header stamp on Voice-out card | `· 6b7d949f` or fresher (re-pins bump it) |
| gateway current | `systemctl --user show hermes-gateway.service -p MainPID --value` | today's pid; witness lines appear on enroll POSTs |
| one TTS worker | `pgrep -f "air_runner.*--serve" \| wc -l` | `1` |
| watch awake+loud | `adb shell svc power stayon true; adb shell media volume --show --stream 3 --set 15; adb shell dumpsys deviceidle disable` | volume 15 |
| store clean | pane voice list | ONLY `clay-synth` + (optional) one NEW voice enrolled during the shoot; delete test cards with ✕✕ |
| mic rig | `arecord -l` | card 3 `Depstech webcam` present |
| keyboard on glass | Settings ▸ Voice out ▸ ➕ → name field | floating QWERTY (HoneyBoard enabled Nov 10) |
| monitor on for pane crops | any x11grab shot shows the app, not black | screen not DPMS-off |

## 1 · Reusable generated media (DO NOT reshoot these)

- `hermes-plugin/docs/screenshots/05-aurora-player.gif` — the aurora video
  playing on the watch media player. This is the media-playback proof; keep,
  reference directly from PR/README.
- `~/.hermes/plugin-data/wearos/proof/jingle.mp4` + `proof-play.mp4` — audio
  clip + on-watch play proof (chimes.wav is the source). Reuse the GIF frames
  idea: to REGENERATE (only if we want fresh), ask the wear-demo agent
  “send me the aurora video again” — it reuses the watchkit-scaled asset.
- `docs/promo/2026-11-10/demo.mp4` — 104 s walkthrough. REPLACE with the new
  walkthrough (scene 2), then git rm this one.

## 2 · Scene sheet (shoot in this order — same setup serves several cuts)

Every watch scene: `bash ~/.hermes/profiles/weardev/cache/scratch/xshot.sh
$ANDROID_SERIAL <sec> "<question>" /tmp/shots/<name>` (video + webcam-mic audio
welded, faststart). Every pane scene: wake the screen (`xset dpms force on`),
`ffmpeg -f x11grab -video_size 1920x1080 -i :1` cropped to the pane,
OR ask the owner for a device-pixel screenshot (crisper). QA every frame with
vision BEFORE promoting (privacy + circle mask + no stale stamps).

| # | scene | how | file | length |
|---|---|---|---|---|
| S1 | pane: pairing window open, code shown, watches list with the paired Ultra | x11grab still | `pane-pairing.png` | — |
| S2 | watch: voice list — clay-synth checked, one fresh voice | screencap | `watch-voices.png` | — |
| S3 | wrist enrollment: keyboard open → type name → ● record → accept | xshot.sh, 20 s, NO talking off-mic | `watch-enroll.mp4` | 20 s |
| S4 | pane enrollment: ➕ → name → ● → ✓ confirm lands, new row appears, “heard:” note | x11grab video (`-t 25`) + mic | `pane-enroll.mp4` | ~20 s |
| S5 | PTT: ask → thinking → streamed answer → KARAOKE words highlighted (burst screencaps mid-speech to nail the highlight frame) | xshot.sh 15 s | `watch-answer.mp4` + `watch-karaoke.png` | 15 s |
| S6 | media on the wrist: aurora playback | REUSE `05-aurora-player.gif` | — | — |
| S7 | image card / mermaid on watch (one screencap) | broadcast ask with a chart prompt, screencap at ~6 s | `watch-image.png` | — |
| S8 | voice-out card: green default tick, GPU row, speed, karaoke box | screencap-quality still | `pane-voiceout.png` | — |
| S9 | one-sentence closer shot: watch on charger, speaking, orb halo | phone cam from owner's hand (nice depth) or screencap+ffmpeg Ken Burns | `closer.png`/`closer.mp4` | 3 s |

End card (no shoot): ffmpeg drawtext card — “One watch. Your gateway.
Your voice.” + `github.com/borealBytes/hermes-wearos` + Wear OS / Hermes logos
text line.

## 3 · The three deliverables

1. **Walkthrough v2 (target ≤ 120 s, replaces demo.mp4):** S1 → S2 → S5 (first
   answer) → S6 (aurora GIF, play at 1×) → S3 (wrist enroll) → S4 (pane
   enroll, the ✓ lands) → end card. Audio: S3/S4/S5 natural; duck the rest to
   silence. Encode `-crf 30 -r 24 -movflags +faststart` (~3 MB budget).
2. **X cut ≤ 90 s:** same spine, no S1 (pairing is a caption card instead:
   “pair once over your tailnet” 2 s), S6 shown 4 s, end card 3 s.
   Burned captions, no music with rights questions (jingle.mp4 IS ours —
   use it under the end card).
3. **PR hero GIF:** 3 s from S4 at 12 fps, 480 px wide, palette2
   (`ffmpeg -i pane-enroll.mp4 -t 3 -vf "fps=12,scale=480:-1:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse" hero.gif`)
   — the ✓ click is the loop point. GIF ≤ 2.5 MB or fall back to the mp4 chip.

## 4 · Media squash (after the new set is promoted + links swapped)

git rm: `docs/promo/2026-11-10/{plugin-settings.jpg,plugins-list.jpg,
tts-provider.jpg,wearkit-pane-2244.jpg,enroll.png,demo.mp4}`,
`docs/promo/2026-11-11/{watch-live-ask.mp4,watch-ptt-thinking.png,
watch-answer.png,watch-voices-checked.png,watch-voice-list.png,
pane-recorder-live.png,pane-enroll-confirm-row.png,pane-voices-rows.png,
pane-voiceout-card.png}` — EXCEPT any the README/PR still reference after the
swap; new set lives in `docs/promo/2026-11-11/` under the S-names above,
with the rewritten README table. Keep `privacy_pass.py`. Branch media target:
≤ 8 MB total across both dates.

## 5 · Swap-in map (URLs to edit once the new files exist)

- PR #151546 body: media block (6 refs).
- Status comment 6788437327: same refs + demo.mp4 line.
- README.md (plugin) screenshots section + repo-root README hero strip.
- Raw-URL law: links point at branch paths — re-push the path and every
  consumer updates in place; NEVER renumber after posting (rename = new file).

## 6 · Definition of done (all must be true before submission 5 PM EST)

- [ ] every image/video on the PR shows build `6b7d949f`-or-newer (pane) and
      the clay-synth-only store
- [ ] walkthrough v2 + X cut + hero GIF committed compressed (rule 17 spirit:
      only shipped artifacts, sources regenerable via the ffmpeg one-liners
      saved in the README table)
- [ ] privacy vision-QA passed on every frame (no session titles, no names,
      no tailnet hostnames on the watch status line)
- [ ] squash committed; `git ls-tree -r HEAD docs/promo | wc` matches README
      table exactly
- [ ] PR body + comment updated to new URLs; both read back from the API
      (no escape mangling — patch via JSON input, never `--field`)
- [ ] Typeform entry done, links to: X post → repo → demo video
