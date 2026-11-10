#!/usr/bin/env bash
# Mirror-tier proof: install the pinned neutts runtime, pull an ungated Q8_0 GGUF
# + HF-port codec, and synthesize the sales narration from the owner's card.
# Everything lands in /opt/wearos-out2 as durable artifacts + a sha manifest.
set -euo pipefail
OUT=/opt/wearos-out2; W=$OUT/work; mkdir -p "$W/codec" "$OUT"   # work lives UNDER the mount: retries never re-download
export TMPDIR=/tmp HF_HUB_DISABLE_TELEMETRY=1 PIP_DISABLE_PIP_VERSION_CHECK=1

echo "== toolchain =="
apt-get update -qq >/dev/null 2>&1 || true
apt-get install -y -qq espeak-ng cmake g++ >/dev/null 2>&1 || command -v espeakng
command -v espeak-ng && command -v cmake && command -v g++

echo "== backbone (mirror, ungated) =="
sz() { stat -c%s "$1" 2>/dev/null || echo 0; }
[ "$(sz "$W/model.gguf")" = "802659296" ] || curl -sSL -C - -o "$W/model.gguf" \
  "https://huggingface.co/mradermacher/neutts-air-GGUF/resolve/main/neutts-air.Q8_0.gguf"   # the shipping DEFAULT row
ls -la "$W/model.gguf"

echo "== codec: OFFICIAL gated repo, warmed after the venv exists (token check) =="
# The bezzam HF-layout 'port' is the paper's model (flat acoustic_* keys, no
# pytorch_model.bin): the roundtrip probe encoded every frame of real speech to
# ONE code. And BOTH loaders (neutts._load_codec, neucodec._from_pretrained)
# hard-assert the gated repo-id STRINGS — there is no local-dir bypass at all.
# Measured 2026-11-09; the codec row in the pane is honest about the token.
[ -n "$HF_TOKEN" ] || echo "MIRROR-PROOF needs HF_TOKEN (free account, one-time consent on the Neuphonic repos)"

echo "== venv + pinned runtime =="
PYV="$(command -v python3.13 || command -v python3.12 || command -v python3.11)"   # neutts pin: >=3.10,<3.14
[ -n "$PYV" ] || { echo "no py 3.10-3.13 in image"; exit 1; }
"$PYV" -m venv "$W/venv"
"$W/venv/bin/pip" install -q --upgrade pip wheel
"$W/venv/bin/pip" install -q "neutts[all] @ git+https://github.com/neuphonic/neutts@ac69851f28fc63a487917e7c2e27f0d75c759cba" 2>&1 | tail -3
# measured 2026-11-09: today's fresh resolve pairs torchao 0.12+ with torchtune
# 0.6.1, which lost torchao.dtypes.nf4tensor -> the codec decoder import dies.
# 0.11.0 imports clean (neucodec's >=0.12 metadata is satisfied-in-fact, proven
# by the synth below, not by pip's resolver).
"$W/venv/bin/pip" install -q "torchao==0.11.0" 2>&1 | tail -1
"$W/venv/bin/python" -c "import torchtune; from torchao.dtypes.nf4tensor import NF4Tensor; from neutts import NeuTTS; print('IMPORT-CHAIN-OK')"

echo "== codec warm (official gated bin; token + consent from the HF account) =="
[ -n "$HF_TOKEN" ] || { echo "HF_TOKEN required for the gated codec"; exit 2; }
"$W/venv/bin/python" -c "
from huggingface_hub import hf_hub_download
import os
print('cached:', hf_hub_download('neuphonic/neucodec', 'pytorch_model.bin'))"

echo "== synth via the PRODUCT RUNNER (dogfood: RUNNER_SRC from tts_air.py) =="
HERE="$(cd "$(dirname "$0")" && pwd)"
AIR="$HERE/../../hermes-plugin/tts_air.py"
[ -f "$AIR" ] || { echo "tts_air.py not found next to this rig (repo must be mounted)"; exit 1; }
"$W/venv/bin/python" - "$AIR" "$W/runner.py" <<'PY'
import importlib.util, sys
spec = importlib.util.spec_from_file_location("tts_air", sys.argv[1])
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
open(sys.argv[2], "w").write(m.RUNNER_SRC)
PY
# NOTE the measured facts this rig encodes (2026-11-09): (1) llama_cpp_python
# 0.3.36 — what neutts' UNPINNED llama extra resolves to — strips control
# tokens from rendered completion text, so the vendor's infer() sees "no valid
# speech tokens" on EVERY GGUF; the runner generates at raw-token level against
# the GGUF vocab instead. (2) The vendor path silently runs WITHOUT llama.cpp's
# repeat_penalty default -> a single-token buzz; the runner passes 1.1.
# (3) The codec CANNOT be mirrored today: the bezzam HF-layout 'port' is the
# paper's model (wrong keys; roundtrip -> one code), and both loaders assert the
# gated repo-id strings, so the official pytorch bin + free-account token is
# the only correct route (measured 2026-11-09; see the SOURCES note in tts_air.py).
"$W/venv/bin/python" "$W/runner.py" \
  --model "$W/model.gguf" --codec neuphonic/neucodec \
  --ref-wav /opt/wearos-in/clay.wav \
  --ref-text "Your cron digest is ready. Three farm alerts, no incidents, sprayer window opens Thursday at six ten." \
  --text-file /opt/wearos-in/narration.txt \
  --out /opt/wearos-out2/narration.wav --temperature 1.0 --top-k 50
python3 - "$OUT/narration.wav" <<'PY'
import sys, wave
w = wave.open(sys.argv[1])
print("narration:", w.getnframes() / w.getframerate(), "s @", w.getframerate())
PY

echo "== sha manifest =="
( cd "$W" && sha256sum model.gguf codec/* ) > "$OUT/mirror-sha256.txt"
cp -f "$W/model.gguf" "$OUT/" 2>/dev/null || true
cat "$OUT/mirror-sha256.txt"
test -f "$OUT/narration.wav" && echo "NARRATION_OK"
chown -R "$(stat -c '%u:%g' "$OUT")" "$OUT" 2>/dev/null || true
