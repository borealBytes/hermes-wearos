#!/usr/bin/env bash
# demo-recording.sh — THE DEMO SEQUENCE, IN CODE (owner ruling 2026-11-09).
# One command turns a text file into a committed-ready demo pack.
#
#   sync     fresh worktree hermes-plugin -> demo container (plugin-src AND
#            plugins/wearos), rig files -> /opt/wearos-demo3
#   record   ONE product call: POST /voice {action:soundtest, text: FULL pitch}
#            (tts_air.synth sentence-chunks internally — no demo-side stitch)
#   eartest  product aligner transcribes the RESULT; in-order word coverage vs
#            the script must pass --min-coverage or we ABORT before video.
#            (Self-heals: installs the STT Q8_0 engine in-container on first use.)
#   shots    Xvfb+chromium over pane_proxy.py; mock loads ?autoplay so the
#            soundtest fires WITHOUT xdotool coordinate roulette
#   clip     wearos-clip.sh muxes shots + narration + waveform -> mp4
#   pack     ogg/webm/opus + align json + SHA256SUMS -> docs/demo/<name>/
#
# Usage: docker/wearos-demo/demo-recording.sh [--container wearos-gw]
#        [--name voiceout-voicedemo] [--text FILE] [--token hf_...]
#        [--min-coverage 95] [--from record]
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; REPO="$(cd "$HERE/../.." && pwd)"
C=wearos-gw; NAME=voiceout-voicedemo; MIN_COV=95; FROM=sync; TOKEN=""
TEXT="$REPO/docs/demo/voiceout-voicedemo/narration.txt"
while [ $# -gt 0 ]; do case "$1" in
  --container) C=$2; shift 2;; --name) NAME=$2; shift 2;; --text) TEXT=$2; shift 2;;
  --token) TOKEN=*** shift 2;; --min-coverage) MIN_COV=$2; shift 2;;
  --from) FROM=$2; shift 2;; *) echo "unknown $1" >&2; exit 2;; esac; done
[ -f "$TEXT" ] || { echo "no text file $TEXT" >&2; exit 2; }
W=/opt/wearos-demo3; OUT="$(mktemp -d /tmp/demo-$NAME-XXXXXX)"
RUN=0; stage() { [ "$1" = "$FROM" ] && RUN=1; [ "$RUN" = 1 ]; }
cleanup() { rm -rf "$OUT"; }; trap cleanup EXIT
dash() { docker exec "$C" printenv HERMES_DASHBOARD_SESSION_TOKEN; }

if stage sync; then echo "== SYNC =="
  docker exec "$C" rm -rf /opt/data/plugin-src /opt/data/plugins/wearos
  docker cp "$REPO/hermes-plugin" "$C:/opt/data/plugin-src"
  docker cp "$REPO/hermes-plugin" "$C:/opt/data/plugins/wearos"
  docker exec "$C" bash -c "mkdir -p $W/pane-mock; chmod -R go-w /opt/data"
  for f in demo_synth.py demo_eartest.py wearos-clip.sh pane_proxy.py; do
    docker cp "$HERE/$f" "$C:$W/$f"; done
  docker cp "$HERE/pane-mock/pane.html" "$C:$W/pane-mock/pane.html"
  docker cp "$TEXT" "$C:$W/narration.txt"
  echo "-- restarting $C so the loaded plugin IS the synced code"
  docker restart "$C" >/dev/null
  for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
    sleep 5
    docker exec "$C" curl -s -o /dev/null -w "%{http_code}" \
      -H "Authorization: Bearer $(dash)" \
      http://127.0.0.1:9119/api/plugins/wearos/voice 2>/dev/null | grep -q "200\|40\|50" && break
  done
  echo "-- container back up"
fi

if stage record; then echo "== RECORD (product soundtest, full text) =="
  T="${TOKEN:-$(dash)}"
  docker exec -e T="$T" "$C" python3 "$W/demo_synth.py" "$T" "$W/narration.wav"
  docker cp "$C:$W/narration.wav" "$OUT/narration.wav"
fi

if stage eartest; then echo "== EAR TEST =="
  docker exec "$C" test -x /opt/data/plugin-data/wearos/stt/venv/bin/python \
    || docker exec "$C" python3 /opt/data/plugins/wearos/stt_install.py install
  docker cp "$C:$W/narration.wav" "$C:$W/check.wav"
  docker exec "$C" python3 "$W/demo_eartest.py" "$W/check.wav" "$W/narration.txt" \
    /tmp/align-words.json "$MIN_COV" || { echo "eartest gate FAILED — no video ships"; exit 5; }
fi

if stage shots; then echo "== SHOTS =="
  docker exec -e T="$(dash)" "$C" bash -c "
    Xvfb :99 -screen 0 1280x720x24 & XP=\$!; sleep 1
    python3 $W/pane_proxy.py \$T $W/pane-mock & PP=\$!; sleep 0.7
    export DISPLAY=:99
    chromium --app='http://127.0.0.1:9188/mock/pane.html' --window-size=1280,720 \
      --no-sandbox --disable-gpu >/dev/null 2>&1 & CP=\$!
    sleep 2.5; import -window root /tmp/pane-before.png
    sleep 8;      import -window root /tmp/pane-play.png
    kill \$XP \$PP \$CP 2>/dev/null; true"
  docker cp "$C:/tmp/pane-before.png" "$OUT/pane-before.png"
  docker cp "$C:/tmp/pane-play.png" "$OUT/pane-play.png"
  cmp -s "$OUT/pane-before.png" "$OUT/pane-play.png" \
    && { echo "shots: before==after — soundtest did not render (check /tmp/proxy.log)"; exit 6; } || true
fi

if stage clip; then echo "== CLIP =="
  for f in narration.wav narration.txt pane-before.png pane-play.png; do
    docker cp "$OUT/$f" "$C:$W/$f"; done
  docker exec "$C" bash "$W/wearos-clip.sh"
  docker cp "$C:$W/wearos-voice-demo.mp4" "$OUT/wearos-voice-demo.mp4"
fi

if stage pack; then echo "== PACK -> docs/demo/$NAME/ =="
  D="$REPO/docs/demo/$NAME"; mkdir -p "$D"
  cp -f "$OUT/narration.wav" "$OUT/wearos-voice-demo.mp4" \
        "$OUT/pane-before.png" "$OUT/pane-play.png" "$D/"
  ffmpeg -v error -y -i "$D/narration.wav" -c:a libopus -b:a 24k -ac 1 -ar 24000 "$D/narration.opus"
  ffmpeg -v error -y -i "$D/narration.wav" -c:a libvorbis -q:a 3 -ac 1 "$D/narration.ogg"
  docker cp "$C:/tmp/align-words.json" "$D/align-words.json"
  ( cd "$D" && sha256sum narration.opus narration.ogg narration.webm \
      wearos-voice-demo.mp4 pane-play.png align-words.json 2>/dev/null ) > "$D/SHA256SUMS"
  ls -la "$D"
fi
echo "DEMO-RECORDING DONE ($NAME). Nothing auto-committed: review, then git add docs/demo/$NAME."
