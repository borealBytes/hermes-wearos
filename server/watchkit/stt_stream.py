#!/usr/bin/env python3
"""watchkit STREAMING stt adapter — batch-4 #9.

Invoked by watchkit.mjs per streamed session:

    .venv/bin/python stt_stream.py --engine whisper|parakeet

stdin  = raw 16 kHz mono PCM16-LE mic frames (closed by the daemon on the watch's {"end":true})
stdout = JSONL engine protocol:  {"partial":"…"}* then exactly one {"final":"…"},
         or one {"error":"…"} (exit non-zero). stderr = human log.

Engines:
  whisper   — faster-whisper CPU (same model as whisper_transcribe.py). Buffered by nature:
              the final lands after the wearer stops. NO partials — the watch UI is built to
              tolerate that (it keeps showing the local mic level, not fake text).
  parakeet  — NeMo streaming ASR (Unified-EN-6B or the installed streaming model): partials
              WHILE talking. Needs `nemo_toolkit[asr]` in the watchkit venv; honest error if not.
"""
import argparse
import json
import os
import sys
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = os.path.join(HERE, ".models")
os.environ.setdefault("HF_HOME", os.path.join(MODELS, "hf"))

RATE, WIDTH, CHANNELS = 16_000, 2, 1


def emit(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def read_pcm():
    return sys.stdin.buffer.read()


def run_whisper():
    pcm = read_pcm()
    if not pcm:
        emit({"final": ""})
        return
    tmp = os.path.join(MODELS, f"stream-{os.getpid()}.wav")
    with wave.open(tmp, "wb") as w:
        w.setnchannels(CHANNELS)
        w.setsampwidth(WIDTH)
        w.setframerate(RATE)
        w.writeframes(pcm)
    try:
        from faster_whisper import WhisperModel  # heavy import after stdin drained

        model = WhisperModel("base", device="cpu", compute_type="int8", download_root=MODELS)
        segments, _info = model.transcribe(tmp, beam_size=1)
        text = " ".join(s.text.strip() for s in segments).strip()
        emit({"final": text})
    finally:
        try:
            os.remove(tmp)
        except OSError:
            pass


def run_parakeet():
    """NeMo decode per growing window = honest partials while the wearer talks."""
    win = int(RATE * 0.4) * WIDTH
    acc, since_flush, flushed_chars = bytearray(), 0, 0
    name = os.environ.get("PARAKEET_MODEL", "nvidia/parakeet-tdt-0.6b-v3")
    try:
        from nemo.collections.asr.models import ASRModel

        model = (
            ASRModel.restore_from(name)
            if name.endswith(".nemo")
            else ASRModel.from_pretrained(name)
        )
    except Exception as e:  # ImportError or model-naming drift — be honest either way
        emit({"error": f"parakeet unavailable: {str(e)[:160]}"})
        sys.exit(3)
    try:
        while True:
            chunk = sys.stdin.buffer.read(win)
            if not chunk:
                break
            acc += chunk
            since_flush += len(chunk)
            if since_flush >= win * 2:
                partial = _decode(model, bytes(acc))
                if len(partial) > flushed_chars:
                    emit({"partial": partial})
                    flushed_chars = len(partial)
                since_flush = 0
        emit({"final": _decode(model, bytes(acc))})
    finally:
        del acc


def _decode(model, pcm):
    import tempfile

    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
        path = f.name
    try:
        with wave.open(path, "wb") as w:
            w.setnchannels(CHANNELS)
            w.setsampwidth(WIDTH)
            w.setframerate(RATE)
            w.writeframes(pcm)
        transcripts = model.transcribe([path])
        texts = transcripts[0].text if hasattr(transcripts[0], "text") else transcripts
        if isinstance(texts, (list, tuple)):
            texts = texts[0] if texts else ""
        return str(texts).strip()
    finally:
        try:
            os.remove(path)
        except OSError:
            pass


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine", choices=["whisper", "parakeet"], default="whisper")
    args = ap.parse_args()
    try:
        (run_whisper if args.engine == "whisper" else run_parakeet)()
    except BrokenPipeError:
        pass  # the watch hung up mid-utterance: nothing left to tell
    except Exception as e:
        emit({"error": f"{type(e).__name__}: {str(e)[:180]}"})
        sys.exit(1)
