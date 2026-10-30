#!/usr/bin/env bash
# parakeet.sh — install watchkit's OWN Parakeet streaming STT engine (batch-4 #16).
#
# Owner 2026-10-30: *"Parakeet Unified EN .6b streaming is what I'm running in Handy. I want
# watchkit to have its own version, not reuse my handy version, cause I want to make this
# generic so others can use it."* This script is that genericity: it clones transcribe.cpp,
# builds the shared lib CPU-only (no CUDA required), installs the Python bindings into the
# watchkit venv, and downloads the Q8_0 GGUF (~731 MB) into server/watchkit/.models/.
#
# Everything stays folder-local (never ~): anyone can run this on any Linux box.
# Idempotent: every step skips what already exists. Run once, then restart the daemon:
#   scripts/parakeet.sh && scripts/watchkit.sh stop && scripts/watchkit.sh start
# Verify:   curl -s localhost:8651/health | jq .stt   → engines[] lists parakeet ready+streaming
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WK="$(cd "$HERE/../server/watchkit" && pwd)"
SRC="$WK/.transcribe-src"
VENV="$WK/.venv"
MODEL="$WK/.models/parakeet-unified-en-0.6b-Q8_0.gguf"
REPO="https://github.com/handy-computer/transcribe.cpp"   # MIT, same engine Handy runs

say() { printf '\033[36mparakeet:\033[0m %s\n' "$*"; }
die() { printf '\033[31mparakeet: %s\033[0m\n' "$*" >&2; exit 1; }

command -v cmake >/dev/null && command -v g++ >/dev/null || die "needs cmake + g++ (apt install cmake build-essential)"
[ -x "$VENV/bin/python" ] || die "watchkit venv missing — run scripts/whisper-env.sh first"

# 1 · transcribe.cpp source (shallow: the build is what matters, not history)
if [ ! -d "$SRC/.git" ]; then
  say "cloning transcribe.cpp → $SRC"
  git clone --depth 1 "$REPO" "$SRC"
else
  say "source present → skip clone"
fi

# 2 · shared lib (bindings auto-discover build-shared/src/libtranscribe.so; CPU build ships —
#     Q8_0 runs ~15–21x realtime per core, GPU is a bonus elsewhere)
LIB="$SRC/build-shared/src/libtranscribe.so"
if [ -f "$LIB" ]; then
  say "shared lib present → skip build"
else
  say "cmake shared build (a few minutes, -j$(nproc))"
  ( cd "$SRC" \
    && cmake -B build-shared -DCMAKE_BUILD_TYPE=Release -DTRANSCRIBE_BUILD_SHARED=ON -DTRANSCRIBE_BUILD_TESTS=OFF >/dev/null \
    && cmake --build build-shared -j"$(nproc)" >/dev/null )
  [ -f "$LIB" ] || die "build finished but $LIB missing — see the cmake output above"
fi

# 3 · python bindings into the watchkit venv (may also pull a transcribe_cpp_native wheel — fine,
#     stt_stream.py's TRANSCRIBE_LIBRARY env keeps the folder-local build authoritative)
if "$VENV/bin/python" -c "import transcribe_cpp" 2>/dev/null; then
  say "bindings import → skip install"
else
  say "pip install bindings"
  "$VENV/bin/pip" install -q "$SRC/bindings/python"
fi

# 4 · watchkit's OWN copy of the model (never reuse Handy's files — owner's genericity rule)
if [ -f "$MODEL" ]; then
  say "model present → skip download"
else
  say "downloading parakeet-unified-en-0.6b-Q8_0.gguf (~731 MB) → $MODEL"
  mkdir -p "$WK/.models"
  "$VENV/bin/python" - <<PYEOF
from huggingface_hub import hf_hub_download
hf_hub_download("handy-computer/parakeet-unified-en-0.6b-gguf",
                "parakeet-unified-en-0.6b-Q8_0.gguf",
                local_dir="$WK/.models")
PYEOF
fi

# 5 · honest readiness report (same probe /health caches)
say "engine check:"
"$VENV/bin/python" "$WK/stt_stream.py" --check || true
say "done — restart the daemon to serve it:  scripts/watchkit.sh stop && scripts/watchkit.sh start"
