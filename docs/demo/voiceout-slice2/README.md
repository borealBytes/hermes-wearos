# voiceout slice 2 — example outputs (generated, not staged)

Produced by the fresh-image proof lane (`docker/wearos-demo/demo_lane.py` in
`hermes-wearos:voiceout`, image layered on `hermes-film:dev`), 2026-11-09.
Regenerate:

```bash
docker build -f docker/wearos-demo/Dockerfile -t hermes-wearos:voiceout .
docker run --rm -u $(id -u):$(id -g) \
  -v <voice.wav>:/opt/wearos-in/clay.wav:ro \
  -v <prompt.txt>:/opt/wearos-in/clay.txt:ro \
  -v $PWD/docs/demo/voiceout-slice2:/opt/wearos-out \
  hermes-wearos:voiceout /opt/hermes/.venv/bin/python /opt/wearos/demo/demo_lane.py
ffmpeg -y -i card.wav -c:a libopus -b:a 24k -ac 1 card.opus && rm card.wav
```

| file | what it proves |
|---|---|
| `prefs.json` | canonical `voiceout.yaml` mirror at `GET /voice/prefs` (rev 1, no bearer — the tailnet socket is the credential) |
| `card.json` | the FOUNDER'S REAL VOICE card from `POST /voice/card_create` — measured `ref_rate` 213.4 wpm / 4.78 s voiced, persisted transcript, sha256 of the clip. The seed-pack default voice |
| `card.opus` | the exact bytes `GET /voice/card_wav` streamed back, re-encoded 24 kbps mono Opus (repo rule: compressed audio only). Click play — this is the voice the watch ships with |
| `synthesize.json` | the honest no-engine refusal: `ok:false` naming NeuTTS Air (never silence) |
| `engines.json` | `tts_air.status()` + `align_parakeet.status()` — expected/observed model size, token presence, toolchain gate |

The card audio is the repo owner (Clay) reading the scripted prompt into a
webcam mic (`arecord` default device, 16 kHz mono), final pass of take 4 —
trimmed to 14.45–21.0 s of `clay-take4-152157.wav` and enrolled through the
API exactly as the pane will do it (slices 3/6). The measured rate exposed a
VAD bug fixed the same day (SPEC §19.4, corrective entry).

**Enrollment is anyone's voice** (SPEC §19, owner ruling 2026-11-09): this
recording ships as the factory default, and the pane's copy invites every user
to replace it — record whoever you want to hear on your wrist.

Real Air-synthesized audio lands here once the gated HF weights are installed
(same files, `label` says whose voice it is).
