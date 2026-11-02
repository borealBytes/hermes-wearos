#!/usr/bin/env bash
# watchkit — mermaid render + whisper STT helper daemon (tailnet :42651).
# Usage: scripts/watchkit.sh setup|run|start|stop|status|logs
# Standalone on purpose: no env.sh — the daemon is plain node + a folder-local venv.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WK="$ROOT/server/watchkit"
CONFIG="$WK/config.json"
PIDFILE="$WK/.watchkit.pid"

# logs land in the sibling devkit when it exists, else /tmp/opencode
if [ -d "$ROOT/../devkit" ]; then LOGFILE="$(cd "$ROOT/.." && pwd)/devkit/watchkit.log"; else LOGFILE=/tmp/opencode/watchkit.log; fi
HOST=$(python3 - "$CONFIG" <<'EOF' 2>/dev/null || true
import json,sys; print(json.load(open(sys.argv[1])).get("host","127.0.0.1"))
EOF
)
HOST="${HOST:-127.0.0.1}"
PORT=42651
URL="http://$HOST:$PORT"
# keep npx + chromium downloads folder-local (same dirs watchkit.mjs uses)
export PUPPETEER_CACHE_DIR="$WK/.cache/puppeteer"
export npm_config_cache="$WK/.cache/npm"

token() { python3 -c "import json;print(json.load(open('$CONFIG'))['token'])"; }

running_pid() {
  [ -f "$PIDFILE" ] || return 1
  local pid; pid=$(cat "$PIDFILE" 2>/dev/null) || return 1
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && echo "$pid"
}

case "${1:-}" in
  setup)
    command -v node >/dev/null || { echo "node missing (need v18+)"; exit 1; }
    if [ ! -f "$CONFIG" ]; then
      umask 077
      python3 - "$CONFIG" <<'EOF'
import json, secrets, sys
json.dump({"host": "0.0.0.0", "port": 42651, "token": secrets.token_hex(32)}, open(sys.argv[1], "w"))
EOF
      chmod 600 "$CONFIG"
      echo "created $CONFIG (chmod 600, random token)"
    fi
    bash "$SCRIPT_DIR/whisper-env.sh"
    echo "prefetching whisper model 'base' into $WK/.models …"
    "$WK/.venv/bin/python" - "$WK/.models" <<'EOF'
import os, sys, time
os.environ.setdefault("HF_HOME", os.path.join(sys.argv[1], "hf"))
t0 = time.time()
from faster_whisper import WhisperModel
WhisperModel("base", device="cpu", compute_type="int8", download_root=sys.argv[1])
print(f"model ready in {time.time()-t0:.1f}s")
EOF
    echo "warming mermaid-cli (npx + chromium download on first run)…"
    mkdir -p "$WK/.cache"
    npx -y @mermaid-js/mermaid-cli --version
    echo "setup done."
    ;;
  run)
    exec node "$WK/watchkit.mjs"
    ;;
  start)
    if pid=$(running_pid); then echo "already running (pid $pid) — scripts/watchkit.sh stop first"; exit 1; fi
    [ -f "$CONFIG" ] || { echo "no $CONFIG — run scripts/watchkit.sh setup"; exit 1; }
    mkdir -p "$(dirname "$LOGFILE")"
    nohup node "$WK/watchkit.mjs" >>"$LOGFILE" 2>&1 &
    echo $! >"$PIDFILE"
    sleep 1
    if running_pid >/dev/null; then echo "started pid $(cat "$PIDFILE") → $URL (log: $LOGFILE)"; else echo "died on start — see $LOGFILE"; rm -f "$PIDFILE"; exit 1; fi
    ;;
  stop)
    if pid=$(running_pid); then kill "$pid" && rm -f "$PIDFILE" && echo "stopped pid $pid"; else echo "not running"; rm -f "$PIDFILE"; fi
    ;;
  status)
    if pid=$(running_pid); then echo "running pid $pid (pidfile $PIDFILE)"; else echo "not running (no live pid in $PIDFILE)"; fi
    curl -s -m 5 "$URL/health" && echo || echo "health: NO REPLY at $URL"
    ;;
  logs)
    tail -n 40 "$LOGFILE"
    ;;
  *)
    echo "usage: $0 setup|run|start|stop|status|logs" >&2
    exit 2
    ;;
esac
