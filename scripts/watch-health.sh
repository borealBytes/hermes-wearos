#!/usr/bin/env bash
# watch-health.sh — body tools helper (SPEC W1-W8): permission grants + snapshot inspection.
#
#   grant   adb pm grant the Health Services / activity permissions (no in-app permission UI
#           in this slice; unknown permissions on the target image are skipped, not fatal).
#   snap    GET /health/latest from watchkit (the snapshot the MCP tools read).
#   test    GET /health/tool/watch_heart_rate — the exact shape the wear agent receives.
#   push    status + force-push via the debug DevAskReceiver (-e body_push / -e body_status).
#
# Env overrides: ANDROID_SERIAL (device), WATCHKIT_URL (default: example LAN — set yours),
# WATCHKIT_CONFIG (token source). Standalone on purpose: no env.sh needed for the curl verbs.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CONFIG="$ROOT/server/watchkit/config.json"

PKG=dev.hermes.wearos
WATCHKIT_URL="${WATCHKIT_URL:-http://192.168.1.50:42651}"
SERIAL_ARGS=()
if [ -n "${ANDROID_SERIAL:-}" ]; then SERIAL_ARGS=(-s "$ANDROID_SERIAL"); fi

PERMS=(
  android.permission.BODY_SENSORS
  android.permission.BODY_SENSORS_BACKGROUND
  android.permission.ACTIVITY_RECOGNITION
  android.permission.health.READ_HEART_RATE
  android.permission.health.READ_STEPS
  android.permission.health.READ_SLEEP
  android.permission.health.READ_OXYGEN_SATURATION
  android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND
  android.permission.health.HEALTH_RECEIVE_HEALTH_SUMMARY
  # Location tool (owner ask B): signature-free runtime perms; grant loop skips unknown ones.
  android.permission.ACCESS_COARSE_LOCATION
  android.permission.ACCESS_FINE_LOCATION
)

token() { python3 -c "import json;print(json.load(open('$CONFIG'))['token'])" 2>/dev/null || echo "${WATCHKIT_TOKEN:-}"; }

adb_cmd() {
  command -v adb >/dev/null || { echo "adb missing (source scripts/env.sh)" >&2; exit 1; }
  adb "${SERIAL_ARGS[@]}" "$@"
}

case "${1:-}" in
  grant)
    for p in "${PERMS[@]}"; do
      if out=$(adb_cmd shell pm grant "$PKG" "$p" 2>&1); then
        echo "granted  $p"
      else
        # Not-defined-on-this-image / already-granted-by-default: skip, never abort the slice.
        echo "skipped  $p (${out//$'\n'/ } — verify the image supports it)"
      fi
    done
    ;;
  snap)
    curl -sf -m 8 "$WATCHKIT_URL/health/latest" -H "Authorization: Bearer $(token)" && echo || { echo "GET /health/latest failed ($WATCHKIT_URL)"; exit 1; }
    ;;
  test)
    tool="${2:-watch_heart_rate}"
    curl -sf -m 8 "$WATCHKIT_URL/health/tool/$tool" -H "Authorization: Bearer $(token)" && echo || { echo "GET /health/tool/$tool failed ($WATCHKIT_URL)"; exit 1; }
    ;;
  push)
    adb_cmd shell am broadcast -n "$PKG/dev.hermes.wearos.DevAskReceiver" -e body_push 1 >/dev/null
    echo "broadcast body_push sent — inspect with: $0 snap   (logcat -s HermesWear for the result line)"
    ;;
  status)
    adb_cmd shell am broadcast -n "$PKG/dev.hermes.wearos.DevAskReceiver" -e body_status 1 >/dev/null
    echo "broadcast body_status sent — watch logcat -s HermesWear for per-tool probe results"
    ;;
  *)
    echo "usage: $0 grant | snap | test [tool] | push | status" >&2
    echo "  (ANDROID_SERIAL=... $0 grant   |   WATCHKIT_URL=... $0 snap)" >&2
    exit 2
    ;;
esac
