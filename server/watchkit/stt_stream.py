#!/usr/bin/env python3
"""watchkit STREAMING stt adapter — batch-4 #9, engine selection (owner 2026-10-30).

Invoked by watchkit.mjs per streamed session:

    .venv/bin/python stt_stream.py --engine whisper|parakeet

stdin  = raw 16 kHz mono PCM16-LE mic frames (closed by the daemon on the watch's {"end":true})
stdout = JSONL engine protocol:  {"partial":"…"}* then exactly one {"final":"…"},
         or one {"error":"…"} (exit non-zero). stderr = human log.

`.venv/bin/python stt_stream.py --check` instead prints one JSON line per engine
{engine, ready, streaming, missing} for the watchkit /health report and exits 0.

Engines (the watch's Settings ▸ Voice in ▸ Gateway picker lists exactly these):
  whisper   — faster-whisper CPU (same model as whisper_transcribe.py). Buffered by nature:
              the final lands after the wearer stops. NO partials — the watch UI is built to
              tolerate that (it keeps showing the local mic level, not fake text).
  parakeet  — nvidia/parakeet-unified-en-0.6b via transcribe.cpp GGUF (the model Handy runs,
              watchkit keeps its OWN copy under .models/ — never the owner's Handy files).
              True streaming: stream.text().committed is append-only-stable while feeding, so
              partials are honest. ~15–21x realtime on CPU (Q8_0), punctuation + caps included.
              Setup: scripts/parakeet.sh
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
PARAKEET_GGUF = os.environ.get(
    "PARAKEET_GGUF", os.path.join(MODELS, "parakeet-unified-en-0.6b-Q8_0.gguf")
)
# Prefer the transcribe.cpp shared lib this repo built (bindings auto-discover the dev tree,
# this env override makes it explicit); an installed transcribe_cpp_native wheel also works.
_TC_LIB = os.path.join(HERE, ".transcribe-src", "build-shared", "src", "libtranscribe.so")
if os.path.exists(_TC_LIB):
    os.environ.setdefault("TRANSCRIBE_LIBRARY", _TC_LIB)

ENGINES = ("whisper", "parakeet")
STREAMING_ENGINES = {"parakeet"}  # engines that emit honest {"partial"} while talking


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
    """transcribe.cpp streaming session: feed live PCM, publish committed+tentative as partials.

    The unified parakeet is trained streaming-first: `committed` never revises what it already
    published (watch-friendly: the overlay appends), `tentative` is the volatile suffix the UI
    may restyle. The wearer's `{"end":true}` closes stdin → finalize() flushes the last
    hypothesis → snapshot text is the final."""
    import numpy as np
    import transcribe_cpp

    if not os.path.exists(PARAKEET_GGUF):
        emit({"error": f"parakeet model missing: {PARAKEET_GGUF} — run scripts/parakeet.sh"})
        sys.exit(3)
    model = transcribe_cpp.Model(PARAKEET_GGUF)
    try:
        if not model.capabilities.supports_streaming:
            emit({"error": "parakeet model build does not advertise streaming"})
            sys.exit(3)
        last = ""
        # 3200 samples = 200 ms per feed: matches the watch's frame cadence closely enough
        # while keeping per-feed decode comfortably below realtime on CPU.
        feed_n = 16_000 * 200 // 1000
        int16 = np.empty(0, dtype=np.int16)  # carry for reads that split a sample in half
        with model.session() as session, session.stream() as stream:
            while True:
                raw = sys.stdin.buffer.read(feed_n * WIDTH)
                if not raw:
                    break
                if len(raw) % WIDTH:
                    raw, carry = raw[:-1], raw[-1:]
                else:
                    carry = b""
                buf = np.frombuffer(raw, dtype="<i2")
                if int16.size:
                    buf = np.concatenate((int16, buf))
                    int16 = np.empty(0, dtype=np.int16)
                if buf.size < feed_n:
                    int16 = buf  # not a full feed yet — hold until the next read
                    continue
                stream.feed((buf.astype(np.float32) / 32768.0))
                text = stream.text()
                shown = (text.committed + (" " if text.committed and text.tentative else "") + text.tentative).strip()
                if shown and shown != last:
                    last = shown
                    emit({"partial": shown})
            if int16.size:
                stream.feed(int16.astype(np.float32) / 32768.0)
            stream.finalize()
            text = stream.text()
            final = (text.committed + (" " if text.committed and text.tentative else "") + text.tentative).strip()
            emit({"final": final or last})
    finally:
        model.close()


def check():
    """One JSON line per engine for watchkit /health — never raises, always exit 0."""
    rows = []
    try:
        import faster_whisper  # noqa: F401

        rows.append({"engine": "whisper", "ready": True, "streaming": False, "missing": ""})
    except Exception as e:
        rows.append({"engine": "whisper", "ready": False, "streaming": False,
                     "missing": f"import: {str(e)[:80]}"})
    missing = ""
    ready = streaming = False
    if not os.path.exists(PARAKEET_GGUF):
        missing = f"model missing: {os.path.basename(PARAKEET_GGUF)} — run scripts/parakeet.sh"
    else:
        try:
            import transcribe_cpp

            m = transcribe_cpp.Model(PARAKEET_GGUF)
            streaming = bool(m.capabilities.supports_streaming)
            m.close()
            ready = True
        except Exception as e:
            missing = str(e)[:120]
    rows.append({"engine": "parakeet", "ready": ready, "streaming": streaming, "missing": missing})
    for r in rows:
        sys.stdout.write(json.dumps(r) + "\n")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine", choices=list(ENGINES), default="parakeet")  # owner doctrine: gateway Parakeet is the STT story
    ap.add_argument("--check", action="store_true", help="print engine readiness JSON and exit")
    args = ap.parse_args()
    if args.check:
        check()
        sys.exit(0)
    try:
        (run_whisper if args.engine == "whisper" else run_parakeet)()
    except BrokenPipeError:
        pass  # the watch hung up mid-utterance: nothing left to tell
    except Exception as e:
        emit({"error": f"{type(e).__name__}: {str(e)[:180]}"})
        sys.exit(1)
