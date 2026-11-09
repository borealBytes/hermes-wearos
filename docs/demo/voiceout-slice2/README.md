# voiceout slice 2 — example outputs (generated, not staged)

Produced by the fresh-image proof lane (`docker/wearos-demo/demo_lane.py` in
`hermes-wearos:voiceout`, image layered on `hermes-film:dev`), 2026-11-09.
Regenerate:

```bash
docker build -f docker/wearos-demo/Dockerfile -t hermes-wearos:voiceout .
docker run --rm -u $(id -u):$(id -g) -v $PWD/docs/demo/voiceout-slice2:/opt/wearos-out \
  hermes-wearos:voiceout /opt/hermes/.venv/bin/python /opt/wearos/demo/demo_lane.py
ffmpeg -y -i card.wav -c:a libopus -b:a 24k -ac 1 card.opus && rm card.wav
```

| file | what it proves |
|---|---|
| `prefs.json` | canonical `voiceout.yaml` mirror at `GET /voice/prefs` (rev 1, no bearer — the tailnet socket is the credential) |
| `card.json` | card store entry from `POST /voice/card_create` — **measured** `ref_rate` (316.8 wpm synthetic clip), persisted transcript, sha256 of the clip |
| `card.opus` | the exact bytes `GET /voice/card_wav` streamed back (166 KB wav → 24 kbps mono Opus, 23.8 KB — repo rule: compressed audio only) |
| `synthesize.json` | the honest no-engine refusal: `ok:false` naming NeuTTS Air (never silence) |
| `engines.json` | `tts_air.status()` + `align_parakeet.status()` — expected/observed model size, token presence, toolchain gate |

This clip is the SYNTHETIC demo voice (speech-like envelope), not a human.
Real-voice cards (owner recording via `scripts/record-card.sh`) and Air-synthesized
audio land here once the gated HF weights are installed — same files, `label` says
whose voice it is.
