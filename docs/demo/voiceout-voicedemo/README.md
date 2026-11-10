# voice-out voiced demo (2026-11-09)

`wearos-voice-demo.mp4` — 46 s, pane mock (Xvfb+chromium) driven by the REAL product
API: POST /api/plugins/wearos/voice action=soundtest on the wearos-gw container;
caption timing from `align-words.json` (Parakeet word stamps on the returned audio).
`narration.opus` — the same 44.3 s pitch, 24 k mono Opus (24 kHz codec rumble
tamed with a 55 Hz high-pass + limiter in the clip mix).

Ear test (host Parakeet STT): 130/133 content words = 97.7%, RMS varies
10.3 dB across 1 s windows, 77% voiced — real speech, not buzz.

Known ceiling: single-pass synth caps at the model 2048-token KV window
(~120 chars); the PRODUCT path chunks per sentence, this demo stitched
per-sentence soundtests with 300 ms gaps. mirror-proof raw path must chunk too
(follow-up).

sha manifest: mirror-sha256.txt
