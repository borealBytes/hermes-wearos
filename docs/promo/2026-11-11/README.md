# 2026-11-11 — voice-out milestone, hardware-proven

Capture set from the hour enrollment went live on all three surfaces
(gateway, pane, watch). Pane crops are the app itself (mid-enrollment);
watch frames are a real PTT conversation (mic → Parakeet → agent →
clay-synth reply) and a live tool-step answer, Galaxy Watch Ultra.

| file | shows |
|---|---|
| pane-voiceout-card.png | Voice-out card: green default tick, GPU pick, per-voice rows |
| pane-recorder-live.png | recorder mid-session (live seconds chip) |
| pane-enroll-confirm-row.png | take ready: ▶ play / ✓ confirm / ↺ redo, cursor on confirm |
| pane-voices-rows.png | enrolled voice lands in the list |
| watch-voice-list.png | Settings ▸ Voice out on-wrist: probe, per-voice play + default |
| watch-ptt-thinking.png | PTT sent (blue echo) → thinking, stop-orb |
| watch-answer.png | streamed answer, one sentence |
| watch-voices-checked.png | multi-agent default check state after the pass |
| watch-live-ask.mp4 | 10 s: broadcast ask → tool step (`echo $(12*12)`) → streamed 144 |
| watch-live-demo.mp4 | 12 s WITH SOUND: PTT-style ask → streamed answer → clay-synth voice-out recorded off the watch speaker (webcam mic + adb screenrecord welded) |

Privacy: pane crops show only the pane; watch frames contain no contact
names or personal message bodies (voice labels cartman/politician are
test artifacts — the shipped product carries clay-synth alone).
