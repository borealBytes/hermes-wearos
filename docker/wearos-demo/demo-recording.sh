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
W=/opt/wearos-demo3; OUT=/tmp/demo-$NAME           # deterministic: crash-safe —
mkdir -p "$OUT"                                    # a re-run reuses fresh stages
RUN=0; stage() { [ "$1" = "$FROM" ] && RUN=1; [ "$RUN" = 1 ]; }
[ -n "$(find "$OUT/narration.wav" -newermt '-30 minutes' 2>/dev/null)" ] \
  && [ "$FROM" = "sync" ] && FROM=eartest   # auto-resume only a FRESH take's wav;
                                           # delete narration.wav to force a re-record
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
  echo "-- syncing plugin and RELOADING SERVICES (never the container: cont-init
  # re-bakes the stale image plugin on every boot — measured twice today)"
  docker exec "$C" rm -rf /opt/data/plugin-src /opt/data/plugins/wearos
  docker cp "$REPO/hermes-plugin" "$C:/opt/data/plugin-src"
  docker cp "$REPO/hermes-plugin" "$C:/opt/data/plugins/wearos"
  docker exec "$C" bash -c "chmod -R go-w /opt/data; true"
  docker exec "$C" sh -c '
    S=$(command -v s6-svc || ls /command/s6-svc /run/s6/bin/s6-svc 2>/dev/null | head -1)
    D=""; for c in /run/service /run/hermes-service; do
      [ -d "$c/dashboard" ] && { D=$c; break; }; done
    if [ -n "$S" ] && [ -x "$S" ] && [ -n "$D" ]; then
      "$S" -t "$D/dashboard"; "$S" -t "$D/main-hermes"
    else   # no s6 CLI: SIGTERM the supervised procs and let them respawn fresh
      pkill -f "dashboard --host" || true; pkill -f "gateway run" || true
    fi'
  OK=""
  for i in 1 2 3 4 5 6 7 8 9 10 11 12; do
    sleep 5
    [ "$(docker exec "$C" curl -s -o /dev/null -w "%{http_code}" \
      -H "Authorization: Bearer $(dash)" \
      http://127.0.0.1:9119/api/plugins/wearos/voice)" = "200" ] && { OK=1; break; }
  done
  [ -n "$OK" ] || { echo "voice route not green after service reload — stale bake?"; exit 4; }
  echo "-- services reloaded with synced plugin"
fi

if stage record; then echo "== RECORD (product soundtest, full text) =="
  T="${TOKEN:-$(dash)}"
  docker exec "$C" python3 "$W/demo_synth.py" "$T" "$W/narration.txt" "$W/narration.wav" \
    || { echo "record FAILED"; exit 3; }
  docker cp "$C:$W/narration.wav" "$OUT/narration.wav"
fi

if stage eartest; then echo "== EAR TEST (host STT engine, product aligner code) =="
  # The aligner module resolves plugin-data under HERMES_HOME — pin it to the
  # root that ACTUALLY holds the STT engine (our session may sit on a profile
  # root with no engine; measured: weardev profile has no stt/, default does).
  for H in "$HOME/.hermes" "$HOME/.hermes/profiles/weardev"; do
    [ -x "$H/plugin-data/wearos/stt/venv/bin/python" ] && STT_HOME=$H && break
  done
  [ -n "${STT_HOME:-}" ] || { echo "no host STT engine found — run the STT install once"; exit 5; }
  env HERMES_HOME="$STT_HOME" python3 "$HERE/demo_eartest.py" "$OUT/narration.wav" \
    "$TEXT" "$OUT/align-words.json" "$MIN_COV" "$REPO/hermes-plugin/align_parakeet.py" \
    || { echo "eartest gate FAILED — no video ships"; exit 5; }
fi

if stage shots; then echo "== SHOTS =="
  docker cp "$HERE/pane_proxy.py" "$C:$W/pane_proxy.py"          # resume-safe: stages
  docker cp "$HERE/pane-mock/pane.html" "$C:$W/pane-mock/pane.html"  # ship their own files
  TEXTQ=$(python3 -c 'import urllib.parse,sys;print(urllib.parse.quote(open(sys.argv[1]).read()))' "$TEXT")
  docker exec -e T="$(dash)" -e Q="$TEXTQ" "$C" bash -c "
    rm -f /tmp/.X99-lock; pkill -f 'Xvf[b] :99' 2>/dev/null; pkill -f 'chromiu[m].*9188' 2>/dev/null
    sleep 1; kill -9 \$(pgrep -f 'chromiu[m].*9188') 2>/dev/null; rm -f /tmp/proxy.log /tmp/pane-stable.png
    Xvfb :99 -screen 0 1280x720x24 & XP=\$!; sleep 1
    python3 $W/pane_proxy.py \$T $W/pane-mock > /tmp/proxy.log 2>&1 & PP=\$!; sleep 0.7
    export DISPLAY=:99
    chromium --app=\"http://127.0.0.1:9188/mock/pane.html?autoplay&text=\$Q\" \
      --window-size=1280,720 --no-sandbox --disable-gpu >/dev/null 2>&1 & CP=\$!
    sleep 3; import -window root /tmp/pane-before.png     # idle: hook clicks at +2.5s post-load
    for i in \$(seq 1 150); do                             # 15 min cap: full text synth ~8 min
      grep -q 'PROXIED POST /api/plugins/wearos/voice -> 200' /tmp/proxy.log && break
      sleep 6
    done
    sleep 4; import -window root /tmp/pane-play.png       # audio arrived; waveform draws sync
    grep -q 'PROXIED POST .*voice -> 200' /tmp/proxy.log \
      || echo "shots: soundtest POST not completed within 15 min — see /tmp/proxy.log"
    kill \$XP \$PP \$CP 2>/dev/null; pkill -f 'chromiu[m].*9188' 2>/dev/null
    sleep 1; kill -9 \$(pgrep -f 'chromiu[m].*9188') 2>/dev/null; true"
  docker cp "$C:/tmp/pane-before.png" "$OUT/pane-before.png"
  docker cp "$C:/tmp/pane-play.png" "$OUT/pane-play.png"
  cmp -s "$OUT/pane-before.png" "$OUT/pane-play.png" \
    && { echo "shots: before==after — soundtest did not render (see /tmp/proxy.log in $C)"; exit 6; } || true
fi

if stage clip; then echo "== CLIP =="
  # stage-idempotent: resume paths skip sync, so ship the clip's inputs here too
  docker cp "$HERE/wearos-clip.sh" "$C:$W/wearos-clip.sh"
  cp -f "$TEXT" "$OUT/narration.txt"
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
  ffmpeg -v error -y -i "$D/narration.wav" -c:a libopus -b:a 24k -ac 1 "$D/narration.webm"
  rm -f "$D/narration.wav"     # compressed-audio rule: raw wav never enters git
  cp -f "$OUT/align-words.json" "$D/align-words.json"
  ( cd "$D" && sha256sum narration.opus narration.ogg narration.webm \
      wearos-voice-demo.mp4 pane-play.png align-words.json 2>/dev/null ) > "$D/SHA256SUMS"
  ls -la "$D"
fi
echo "DEMO-RECORDING DONE ($NAME). Nothing auto-committed: review, then git add docs/demo/$NAME."
