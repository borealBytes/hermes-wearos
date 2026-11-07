# docs/art — release visual assets (Wear Kit era, v0.8.0)

Generated 2026-11-06 on this machine's ComfyUI (RTX 4070) **through the Hermes
gateway `comfyui` profile** — the sanctioned art lane: one natural-language ask
per deliverable over `POST /p/comfyui/v1/chat/completions` (bearer from
`~/.hermes/profiles/comfyui/.env`), never a raw queue submit. Full prompt→result
log: kept with the release notes; agent-side project
`/run/media/clay/Data/comfy/projects/hermes-wear-kit-art/` and
`/run/media/clay/Data/comfy/ads-project/` (comfy project/1 dirs — fragments,
blueprints, executed workflow JSONs, LOG.md).

Model stack: Qwen-Image-2.1 DiT (Q6_K GGUF) for stills; MiniMax H3 ref2v
(pruned int8 + 4-step turbo LoRA) for video, native 1344×768@24 with diegetic
room tone (mute freely — the edit pass scores it).

## Hero background plates — 1080×1920, for the README vertical hero

Style DNA: engraved silver linework on midnight navy (#191919→#19192f),
sparing cobalt `#0000f2` accents — same family as `../art-selfportrait.webp`
(DEMO.md §5). All three keep a clean dark center disc: the watch circle is
composited at ~90 % of frame width, so nothing but vignette sits under it.
No text, no UI, no numerals.

| file | look |
|---|---|
| `hero-plate-01-dawn-burst.png` | dawn burst rising from the bottom edge, thin arc overhead, cobalt streaks low-left/right |
| `hero-plate-02-astrolabe.png` | "astrolabe border" — star-glint frame of orbital arcs; most symmetric, safest under the circle; **hero pick** |
| `hero-plate-03-orbital-dawn.png` | top-edge dawn with cobalt atmospheric rim, star-trail arcs wrapping sides/bottom |

## Wear Kit emblem — flat vector-style mark, no text anywhere

Winged-helmet messenger motif fused with a circular watch dial (tick indices
only) and radiating signal-wave arcs; silver/white on midnight-navy ground,
cobalt accents; flat fills (bg luminance sd <1/255 — zero raster grain). The
"Hermes Wear Kit" caption is added outside the art wherever it's used.

| file | use |
|---|---|
| `emblem-wear-kit-square.png` | plugin icon / pane (1088×1088; QC'd legible at 48 px) |
| `emblem-wear-kit-banner.png` | README banner (1920×640, emblem dead-center, open margins for the caption) |

## B-roll — cinematic product clips (real, not fake-TV)

Brandless titanium smartwatch, octagonal cushion bezel (Watch Ultra vibe — no
marks), hands/wrists only, dark and shallow-DOF; the lit screen is always a
**soft abstract cobalt bloom** — never readable text or fake UI (UI composites
happen later in the video pass, DEMO.md §7). Each clip 5.17 s, keyframe still
beside it for re-animation or poster frames.

| file | shot |
|---|---|
| `broll-watch-wake.mp4` / `-still.png` | fingertip taps the dark screen → wake into cobalt bloom — hero b-roll |
| `broll-watch-desk.mp4` / `-still.png` | slow push-in on the desk plate, glow breathes cobalt→amber→cobalt |
| `broll-watch-orbit.mp4` / `-still.png` | lateral drift around the wrist, bezel highlight travels |
