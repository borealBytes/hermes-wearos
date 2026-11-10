#!/usr/bin/env bash
# Gated-install the NeuTTS Air engine into the image's private venv.
# Token: HF_TOKEN env, or HF_TOKEN_FILE pointing at a file (mount your token
# read-only — the adapter also honors ~/.cache/huggingface/token).
# Requires the one-time consent clicks on the neuphonic HF repos (gated: auto).
set -eu
TOK=${HF_TOKEN:-}
if [ -z "$TOK" ] && [ -f "${HF_TOKEN_FILE:-/nonexistent}" ]; then
  TOK=$(tr -d '[:space:]' < "$HF_TOKEN_FILE")
fi
export HF_TOKEN="$TOK"
/opt/hermes/.venv/bin/python - <<'PY'
import time, sys
sys.path.insert(0, "/opt/wearos/plugin")
import tts_air
r = tts_air.start(interactive=False)
print("install started:", r)
while True:
    s = tts_air.status()
    ph = s.get("phase", "?")
    print(f"  {ph} {s.get('percent','')}% {s.get('detail','')}", flush=True)
    if ph in ("ready", "done", "error", "failed", "idle") and s.get("error"):
        print("ERROR:", s["error"]); sys.exit(1)
    if ph in ("ready", "done"):
        break
    time.sleep(5)
print("tts_air.status():", tts_air.status())
PY
