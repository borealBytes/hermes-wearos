#!/usr/bin/env bash
# Record a voice-clone CARD with the gateway mic — the scripted 17-word prompt
# the plugin measures ref_rate against. Output lands in ~/voice-samples/ and is
# ready for the /voice/* demo (mounted at /opt/wearos-in/clay.wav).
#   usage: scripts/record-card.sh [seconds]     (default 15, Enter stops early)
set -eu
SECS=${1:-15}
OUTDIR="$HOME/voice-samples"; mkdir -p "$OUTDIR"
TS=$(date +%Y%m%d-%H%M%S); W="$OUTDIR/clay-$TS.wav"; TXT="$OUTDIR/clay-$TS.txt"

PROMPT="Your cron digest is ready: three farm alerts, no incidents, sprayer window opens Thursday at six ten."
printf '%s\n' "$PROMPT" > "$TXT"

echo "READY — when you press Enter, say this in your normal pace:"
echo "  \"$PROMPT\""
echo "(recording caps at ${SECS}s; press Enter again when done)"
read -r _

if command -v pw-record >/dev/null; then
  REC=(pw-record --channels 1 --rate 16000 "$W")
elif command -v arecord >/dev/null; then
  REC=(arecord -q -f S16_LE -c 1 -r 16000 -t wav "$W")
else
  echo "no pw-record/arecord found" >&2; exit 1
fi
"${REC[@]}" & RP=$!
( sleep "$SECS"; kill "$RP" 2>/dev/null ) &
read -r _ 2>/dev/null || true
kill "$RP" 2>/dev/null || true; wait "$RP" 2>/dev/null || true

[ -s "$W" ] || { echo "nothing recorded" >&2; exit 1; }
python3 - "$W" <<'PY'
import sys, wave
with wave.open(sys.argv[1]) as w:
    d = w.getnframes() / w.getframerate()
print(f"length: {d:.1f}s  (card floor is 3s, ceiling 60s)")
sys.exit(0 if 3.0 <= d <= 60.0 else 1)
PY
chmod 644 "$W" "$TXT"
echo "WAV : $W"
echo "TXT : $TXT"
echo "demo: docker run ... -v $W:/opt/wearos-in/clay.wav:ro -v $TXT:/opt/wearos-in/clay.txt:ro"
