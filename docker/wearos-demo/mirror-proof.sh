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

echo "== codec (HF-port mirror, ungated) =="
for F in config.json model.safetensors preprocessor_config.json; do
  want=292; case "$F" in model.safetensors) want=2519855456 ;; config.json) want=2964 ;; esac
  [ "$(sz "$W/codec/$F")" = "$want" ] || curl -sSL -C - -o "$W/codec/$F" "https://huggingface.co/bezzam/neucodec-hf/resolve/main/$F"
done
ls -la "$W/codec"

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

echo "== synth: sales narration, owner voice =="
"$W/venv/bin/python" - "$W" <<'PY'
import sys, wave, numpy as np
W = sys.argv[1]
import torch
from neutts import NeuTTS
tts = NeuTTS(backbone_repo=W + "/model.gguf", language="en-us", backbone_device="cpu",
             codec_repo=W + "/codec", codec_device="cpu", seed=42)
ref_codes = tts.encode_reference("/opt/wearos-in/clay.wav")
torch.save(ref_codes, "/opt/wearos-in/clay.wav.codes.pt")
text = open("/opt/wearos-in/narration.txt").read().strip().replace("\n\n", " ")
wav = tts.infer(text, ref_codes,
                "Your cron digest is ready: three farm alerts, no incidents, sprayer window opens Thursday at six ten.",
                temperature=0.7, top_k=50)
pcm = (wav * 32767).clip(-32768, 32767).astype("int16")
with wave.open("/opt/wearos-out2/narration.wav", "wb") as f:
    f.setnchannels(1); f.setsampwidth(2); f.setframerate(tts.sample_rate)
    f.writeframes(pcm.tobytes())
print("narration:", len(pcm) / tts.sample_rate, "s @", tts.sample_rate)
PY

echo "== sha manifest =="
( cd "$W" && sha256sum model.gguf codec/* ) > "$OUT/mirror-sha256.txt"
cp -f "$W/model.gguf" "$OUT/" 2>/dev/null || true
cat "$OUT/mirror-sha256.txt"
test -f "$OUT/narration.wav" && echo "NARRATION_OK"
chown -R "$(stat -c '%u:%g' "$OUT")" "$OUT" 2>/dev/null || true
