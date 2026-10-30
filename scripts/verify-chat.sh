#!/usr/bin/env bash
# End-to-end M1 check against a booted emulator (or a real watch over adb):
# install, launch, ask via DevAskReceiver, capture screens + logcat around the turn.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
cd "$ROOT"
ADB="$ANDROID_HOME/platform-tools/adb"
OUT="$DEVKIT/verify"; mkdir -p "$OUT"
Q="${1:-Reply in two short bullet points: what is 12 times 12, and what day is it?}"

"$GRADLE" --console=plain :app:assembleDebug >/dev/null
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk | tail -1
# Dev-only token push (pairing flow S5 will replace this): read the wear profile key
WEAR_KEY="${WEAR_KEY:-$(grep '^API_SERVER_KEY=' "$HOME/.hermes/profiles/wear/.env" 2>/dev/null | cut -d= -f2)}"
if [ -n "$WEAR_KEY" ]; then
  "$ADB" shell am broadcast -n dev.hermes.wearos/dev.hermes.wearos.DevAskReceiver -e set_token "$WEAR_KEY" >/dev/null
  sleep 1
fi
"$ADB" shell svc power stayon true          # dev: keep the watch screen awake for captures
"$ADB" shell input keyevent KEYCODE_WAKEUP
"$ADB" shell am start --ez dev_no_intro 1 -n dev.hermes.wearos/.MainActivity >/dev/null
sleep 3
"$ADB" exec-out screencap -p > "$OUT/01-launch.png"
"$ADB" logcat -c
"$ADB" shell am broadcast -n dev.hermes.wearos/dev.hermes.wearos.DevAskReceiver -e text "'$Q'" >/dev/null
echo "ask sent; waiting for the stream…"
sleep 12; "$ADB" exec-out screencap -p > "$OUT/02-mid.png"
sleep 25; "$ADB" exec-out screencap -p > "$OUT/03-done.png"
"$ADB" logcat -d > "$OUT/logcat-full.txt" 2>/dev/null || true
"$ADB" logcat -d | grep -E "HermesWear|AndroidRuntime|FATAL" | tail -60 > "$OUT/logcat.txt"
echo "artifacts: $OUT/01-launch.png 02-mid.png 03-done.png logcat.txt logcat-full.txt"
