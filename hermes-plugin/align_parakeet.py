"""Word timestamps for karaoke — reuse the Parakeet/CTA engine voice-in already paid for.

The aligner takes the audio the TTS engine JUST synthesized plus the text we fed
it, and answers "which word at what second" (`voiceout.register_aligner` target).
The engine is transcribe.cpp's unified Parakeet — the SAME pinned build and model
``stt_install.py`` produces for streaming STT (rule: the timestamps that made
voice-in free make karaoke free). Its alignment head (TDT) emits ``Word.text,
t0_ms, t1_ms`` rows — real measured timings, not estimates.

Honesty tiers (issue #7): the aligner is an AVAILABLE capability, never a
guarantee. Parakeet absent → ``register_if_ready()`` returns False and
``voiceout.synthesize`` reports ``align:"none"`` → watch falls to sentence-mode
karaoke and the UI SAYS so. Recognition desync (cloned voices occasionally drop a
word the source text had) → a word we can't match gets a PROPORTIONAL slot
between its neighbours, flagged ``"align":"estimated"`` on those rows only —
never silently retimed as if measured.

Runs in a subprocess like the TTS runner: a native crash in an optional
accelerator must never take the gateway.

Stdlib in the gateway process (rule 10); numpy/transcribe_cpp live only in the
venv the install built.
"""

from __future__ import annotations

import os
import re
import struct
import subprocess
import sys
import threading
from pathlib import Path
from typing import Optional

ALIGN_TIMEOUT = 120                        # a minute of speech aligns « realtime
_WORDS_PER_S_CEILING = 12.0                # sanity: faster than speech can be

STATUS = threading.Lock()
_last_error = ""                           # last runner failure, surfaced via status()

ALIGNER_SRC = '''\
"""Parakeet aligner runner — the ONLY code touching transcribe_cpp/numpy.

Reads a 16-bit PCM wav path, prints JSON {words:[{text,start,end}]} on stdout.
Word rows come from the pinned transcribe.cpp bindings' Word dataclass
(text, t0_ms, t1_ms — verified against commit 5700f127's source).
"""
import json, sys, wave


def main():
    import numpy as np
    import transcribe_cpp
    path = sys.argv[1]
    model_path = sys.argv[2]
    lib = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] else None
    if lib:
        import os
        os.environ.setdefault("TRANSCRIBE_LIBRARY", lib)
    with wave.open(path, "rb") as w:
        assert w.getnchannels() == 1 and w.getsampwidth() == 2, "aligner needs mono 16-bit"
        rate = w.getframerate()
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
    if rate != 16000:  # linear decimate/intersample — TTS hands us 24k
        n = int(round(len(pcm) * 16000 / rate))
        idx = np.linspace(0, len(pcm) - 1, n)
        lo = np.floor(idx).astype(int)
        hi = np.minimum(lo + 1, len(pcm) - 1)
        frac = (idx - lo).astype(np.float32)
        pcm = (pcm[lo] * (1 - frac) + pcm[hi] * frac).astype(np.int16)
    audio = pcm.astype(np.float32) / 32768.0
    rows = []
    with transcribe_cpp.Model(model_path) as model:
        with model.session() as session:
            result = session.run(audio, timestamps="word")
            for wrow in getattr(result, "words", ()) or ():
                rows.append({"text": wrow.text,
                             "start": wrow.t0_ms / 1000.0,
                             "end": wrow.t1_ms / 1000.0})
    json.dump({"words": rows}, sys.stdout)


main()
'''


def _root() -> Path:
    try:
        from hermes_constants import get_hermes_home
        home = Path(get_hermes_home())
    except Exception:
        home = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes")))
    return home / "plugin-data" / "wearos"


def _stt_root() -> Path:
    return _root() / "stt"          # exactly where stt_install.py builds


def _voiceout():
    try:
        from . import voiceout
        return voiceout
    except ImportError:
        import voiceout  # type: ignore[no-redef]
        return voiceout


def engine_ready() -> bool:
    """The stt_install.py probe, read-only: lib built + model present. We do NOT
    import transcribe_cpp here — rule 10 keeps numpy out of the gateway process."""
    root = _stt_root()
    lib = root / "transcribe-src" / "build-shared" / "src" / "libtranscribe.so"
    model = root / "models" / "parakeet-unified-en-0.6b-Q8_0.gguf"
    venv_py = root / "venv" / "bin" / "python"
    return lib.is_file() and model.is_file() and venv_py.is_file()


def write_runner() -> Path:
    p = _stt_root() / "aligner_runner.py"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(ALIGNER_SRC)
    return p


def align(wav_bytes: bytes, rate: int, text: str) -> list:
    """voiceout.register_aligner target: audio in (mono 16-bit PCM16 wav bytes
    + source rate) + the spoken text, out: [{text,start,end}] ascending. Raises
    RuntimeError with a named cause — voiceout turns that into align:"none"."""
    global _last_error
    if not engine_ready():
        raise RuntimeError("Parakeet not installed (Voice-in ▸ streaming STT ▸ install)")
    if wav_bytes[:4] != b"RIFF":
        wav_bytes = _wrap_pcm(wav_bytes, rate)
    venv_py = _stt_root() / "venv" / "bin" / "python"
    lib = _stt_root() / "transcribe-src" / "build-shared" / "src" / "libtranscribe.so"
    model = _stt_root() / "models" / "parakeet-unified-en-0.6b-Q8_0.gguf"
    runner = write_runner()
    tmp = _stt_root() / "align_in.wav"
    tmp.write_bytes(wav_bytes)
    argv = [str(venv_py), str(runner), str(tmp), str(model), str(lib)]
    try:
        proc = subprocess.run(argv, capture_output=True, text=True,
                              timeout=ALIGN_TIMEOUT)
        if proc.returncode != 0:
            _last_error = (proc.stderr or "")[-400:]
            raise RuntimeError("aligner failed: " + _last_error)
        import json
        rows = json.loads(proc.stdout or "{}").get("words", [])
    finally:
        tmp.unlink(missing_ok=True)
    return _fit_to_script(rows, text, len(wav_bytes) // 2 / max(1, rate))


def _wrap_pcm(pcm: bytes, rate: int) -> bytes:
    hdr = (b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt "
           + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
           + b"data" + struct.pack("<I", len(pcm)))
    return hdr + pcm


WORD_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9'.\-]*")


def _fit_to_script(rows: list, text: str, audio_s: float) -> list:
    """Blend measured word rows onto the script we KNOW was spoken.

    transcribe.cpp's alignment head returns recognized words with real timings.
    Our karaoke must follow the SCRIPT (punctuation, casing, dropped fillers):
    walk both streams; recognized timings carry the script words they match;
    unmatched script words get proportional slots between their neighbours,
    flagged "estimated". Never emits a word past the audio end or a negative span.
    """
    script = WORD_RE.findall(text)
    if not script:
        return []
    # normalized lookup for recognized rows (lowercase, strip trailing punct)
    meas = []
    for r in rows:
        w = re.sub(r"[^\w'’\-]", "", str(r.get("text", ""))).lower()
        if w:
            try:
                meas.append((w, float(r["start"]), float(r["end"])))
            except (KeyError, ValueError):
                continue
    if not meas:
        return _proportional(script, audio_s)
    out = []
    mi = 0
    for si, sw in enumerate(script):
        key = sw.lower().strip(".,;:!?'\"()")
        got = None
        if mi < len(meas) and meas[mi][0] == key:
            got = meas[mi]
            mi += 1
        else:                                   # search ahead a few rows (recog reorder)
            for look in range(mi, min(mi + 3, len(meas))):
                if meas[look][0] == key:
                    got = meas[look]
                    mi = look + 1
                    break
        if got:
            out.append({"text": sw, "start": round(max(0.0, got[1]), 3),
                        "end": round(max(got[2], got[1] + 0.02), 3)})
        else:
            lo = out[-1]["end"] if out else 0.0
            hi = meas[mi][1] if mi < len(meas) else audio_s
            hi = max(hi, lo + 0.05)
            mid = (lo + hi) / 2.0
            out.append({"text": sw, "start": round(lo, 3),
                        "end": round(mid, 3), "align": "estimated"})
    # clamp to audio, keep every row a real interval, drop impossible rates
    dur = audio_s if audio_s > 0 else (out[-1]["end"] + 0.5)
    for row in out:
        s = max(0.0, min(row["start"], dur))
        e = max(0.0, min(row["end"], dur))
        if 1.0 / max(0.02, e - s) > _WORDS_PER_S_CEILING:
            e = min(dur, s + 1.0 / _WORDS_PER_S_CEILING)      # faster than speech
        if e <= s:
            s = max(0.0, e - 0.08)                            # tail past audio — pull back
        row["start"], row["end"] = round(s, 3), round(e, 3)
    return out


def _proportional(script: list, audio_s: float) -> list:
    """Last resort: syllable-ish weight by word length, whole audio span."""
    if not script or audio_s <= 0:
        return []
    weights = [max(1, len(w)) for w in script]
    total = sum(weights)
    out = []
    t = 0.0
    for w, wt in zip(script, weights):
        start = t
        t += audio_s * wt / total
        out.append({"text": w, "start": round(start, 3),
                    "end": round(t, 3), "align": "estimated"})
    return out


def status() -> dict:
    with STATUS:
        err = _last_error
    return {"ready": engine_ready(), "last_error": err[:300]}


def register_if_ready() -> bool:
    if not engine_ready():
        return False
    _voiceout().register_aligner("parakeet", align)
    return True
