"""NeuTTS Air — the gateway voice-out engine behind the pane button (issue #7, slice 2).

Same discipline as ``stt_install.py`` (rule 17): nothing binary is committed. The
runtime installs FROM PINNED SOURCE into this plugin's own data dir with its own
private venv — never Hermes's interpreter, never system Python — and the 802 MB GGUF
model is fetched, size-verified, and (once ``MODEL_SHA256`` is pinned) SHA-256 checked
before it is ever loaded. Inference runs in a SUBPROCESS, so an engine crash can never
take the gateway down (voiceout.py's comment is the contract this file implements).

Licensing/hosts (rule 13): the model is Apache-2.0 (HF repos
``neuphonic/neutts-air-q8-gguf`` + the neucodec decoder), the runtime is
``neuphonic/neutts`` at the commit pinned below. All three Neuphonic HF repos are
``gated: auto`` — a one-time consent click plus a read token (``HF_TOKEN``) is
required to download; the pane names that cause instead of failing anonymously.
Fetched hosts, complete: ``github.com`` (runtime at the pin), ``pypi.org`` (deps,
incl. the CPU torch wheel), ``huggingface.co`` (model + codec weights).

Cloning is what the card is for: the reference wav + its TRANSCRIPT drive
``encode_reference``/``infer``. Zero-shot needs the exact words spoken in the ref —
a card without a persisted transcript is refused with a reason, never guessed at.

Speed: ``voiceout.synthesize`` hands us the GLOBAL speed (0.5–2.0); 1.0 replays the
speaker at THEIR measured pace, which is what a clone of their own reference audio
naturally does. Off-1.0 is applied here as a stdlib time-scale (linear resample —
pitch follows at extremes; WSOLA is a documented polish item, SPEC §19 amendment log).
Chunking paragraphs to the model's ~30 s context window happens at the /voice route
layer (slice 2c), not here.
"""

from __future__ import annotations

import hashlib
import os
import shutil
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional

REPO = "https://github.com/neuphonic/neutts"
COMMIT = "ac69851f28fc63a487917e7c2e27f0d75c759cba"    # pinned main @ 2026-11-09
PKG_SPEC = f"neutts[all] @ git+{REPO}@{COMMIT}"        # exact source, no floating version
MODEL_REPO = "neuphonic/neutts-air-q8-gguf"
MODEL_FILE = "neutts-air-Q8_0.gguf"
MODEL_URL = f"https://huggingface.co/{MODEL_REPO}/resolve/main/{MODEL_FILE}"
MODEL_BYTES = 802_658_528                              # HF API truth, 2026-11-09
MODEL_SHA256 = "PENDING"     # gated: auto — anonymous fetch 401s. Pinned on the first
                             # token-carrying install; until then install() size-gates
                             # and status() reports verified_pending (never a silent
                             # skip). Same pattern voiceout.SEED_SHA256 uses.
OUTPUT_RATE = 24_000                                   # neucodec is 50 Hz → 24 kHz audio
BUILD_TIMEOUT = 60 * 60                                # torch wheel + llama-cpp build
SYNTH_TIMEOUT = 180                                    # a whole paragraph on weak CPUs
CODEC_REPO = "neuphonic/neucodec"                      # PyTorch codec (encode+decode)

STATUS = threading.Lock()
_state: dict = {"phase": "idle", "detail": "", "percent": 0, "log": [],
                "started": 0.0, "error": ""}
_proc: Optional[subprocess.Popen] = None
_worker: Optional[threading.Thread] = None


def _voiceout():
    """Import the sibling module under BOTH loaders: package-relative in the
    plugin, top-level in the standalone test convention (voiceout.py uses the
    same dance for hermes_constants)."""
    try:
        from . import voiceout
        return voiceout
    except ImportError:
        import voiceout  # type: ignore[no-redef]
        return voiceout


def _root() -> Path:
    try:
        from hermes_constants import get_hermes_home
        home = Path(get_hermes_home())
    except Exception:
        home = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes")))
    return home / "plugin-data" / "wearos" / "tts"


def _hf_token() -> str:
    """Read token, in precedence order, never logged. Empty string = not available."""
    tok = (os.environ.get("HF_TOKEN") or "").strip()
    if tok:
        return tok
    f = Path.home() / ".cache" / "huggingface" / "token"
    try:
        if f.is_file():
            return f.read_text().strip()
    except OSError:
        pass
    local = _root() / "hf_token"                    # pane-side paste box writes this
    try:
        if local.is_file():
            return local.read_text().strip()
    except OSError:
        pass
    return ""


def _size_ok(path: Path) -> bool:
    try:
        return path.stat().st_size == MODEL_BYTES
    except OSError:
        return False


def _verify(path: Path) -> bool:
    try:
        digest = hashlib.sha256()
        with open(path, "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                digest.update(chunk)
        return digest.hexdigest() == MODEL_SHA256
    except OSError:
        return False


def _probe() -> dict:
    root = _root()
    venv_py = root / "venv" / "bin" / "python"
    model = root / "model" / MODEL_FILE
    have_venv = venv_py.is_file() and (root / "venv" / ".neutts-installed").is_file()
    have_model = model.is_file() and _size_ok(model)
    pinned = MODEL_SHA256 != "PENDING"
    verified = have_model and (pinned and _verify(model))
    return {"installed": have_venv and have_model,
            "venv": have_venv, "model": have_model,
            "model_verified": verified,
            "verified_pending": have_model and not pinned,
            "model_size_expected": MODEL_BYTES,
            "dir": str(root),
            "hf_token": bool(_hf_token()),
            "toolchain": bool(shutil.which("cmake") and shutil.which("g++")
                              and shutil.which("git"))}


def status() -> dict:
    with STATUS:
        st = dict(_state)
        st["log"] = st["log"][-25:]
    st.update(_probe())
    return st


def preview() -> dict:
    root = _root()
    return {
        "title": "Voice-out engine (NeuTTS Air, Apache-2.0)",
        "steps": [
            f"pip-install {REPO} at commit {COMMIT[:12]} into a private venv "
            "(pulls the CPU torch wheel; pypi.org)",
            f"Download {MODEL_FILE} (~{MODEL_BYTES // 1_000_000} MB) from "
            "huggingface.co — gated: auto, needs your one-time consent + read token",
            f"Size-check against {MODEL_BYTES} bytes" + (
                f"; SHA-256 check against {MODEL_SHA256[:16]}…" if pinned_hint() else
                " (checksum pin lands with the first verified fetch)"),
            f"Everything lands in {root} (private venv + cache, nothing system-wide)",
        ],
        "hosts": ["github.com", "pypi.org", "huggingface.co"],
        "needs": ["git", "cmake", "g++", "HF read token (HF_TOKEN or the token box)"],
        "disk_mb": 4600,
        "toolchain_ok": _probe()["toolchain"],
    }


def pinned_hint() -> bool:
    return MODEL_SHA256 != "PENDING"


def start(interactive: bool = True) -> dict:
    if not interactive:
        return {"started": False, "error": "no terminal to approve the install "
                                           "(rule 12): use the Wear Kit pane or run "
                                           "hermes watch setup --fix tts"}
    probe = _probe()
    if probe["installed"] and (probe["model_verified"] or probe["verified_pending"]):
        return {"started": False, "error": "already installed"}
    if not probe["toolchain"]:
        return {"started": False, "error": "needs git, cmake and g++ (apt install "
                                           "cmake build-essential git)"}
    if not probe["hf_token"]:
        return {"started": False,
                "error": "the model lives behind HF consent (gated: auto): accept the "
                         "terms at https://huggingface.co/" + MODEL_REPO + " and set "
                         "HF_TOKEN or paste a read token into the pane box"}
    with STATUS:
        if _state["phase"] in ("venv", "runtime", "model"):
            return {"started": False, "error": "an install is already running"}
    global _worker
    _worker = threading.Thread(target=_run, daemon=True, name="wearos-tts-install")
    _worker.start()
    return {"started": True, "preview": preview()}


def cancel() -> dict:
    global _proc
    if _proc is not None and _proc.poll() is None:
        _proc.terminate()
        _set("cancelled", "user cancelled")
        return {"cancelled": True}
    return {"cancelled": False}


def _set(phase: str, detail: str = "", percent: Optional[int] = None) -> None:
    with STATUS:
        _state["phase"] = phase
        _state["detail"] = detail
        if percent is not None:
            _state["percent"] = percent
        _state["log"].append(f"{time.strftime('%H:%M:%S')} {phase}: {detail}")
        _state["log"] = _state["log"][-200:]


def _run() -> None:
    root = _root()
    global _proc
    with STATUS:
        _state.update({"started": time.time(), "error": "", "percent": 0, "log": []})
    try:
        root.mkdir(parents=True, exist_ok=True)
        venv = root / "venv"
        model = root / "model" / MODEL_FILE
        if not (venv / "bin" / "python").is_file():
            _set("venv", "creating the private venv")
            subprocess.run([sys.executable, "-m", "venv", str(venv)],
                           check=True, capture_output=True, timeout=300)
        if not (venv / ".neutts-installed").is_file():
            _set("runtime", "pip install (torch CPU wheel + llama-cpp build, minutes)")
            _run_proc([str(venv / "bin" / "python"), "-m", "pip", "install",
                       "--no-input", "-q", PKG_SPEC], "runtime")
            (venv / ".neutts-installed").write_text(PKG_SPEC)
        if not _size_ok(model):
            _set("model", f"downloading {MODEL_FILE} (~803 MB)")
            _download(MODEL_URL, model)
            if not _size_ok(model):
                model.unlink(missing_ok=True)
                raise RuntimeError("model size mismatch after download — kept nothing")
            if not pinned_hint():
                (model.parent / (MODEL_FILE + ".observed-sha256")).write_text(
                    _sha_of(model))                     # the pin lands from here
            elif not _verify(model):
                model.unlink(missing_ok=True)
                raise RuntimeError("model failed its SHA-256 check — kept nothing")
        write_runner()
        _set("done", "installed — first synth warms the codec weights (same token)", 100)
    except Exception as exc:                        # never raise out of the worker
        with STATUS:
            _state["error"] = str(exc)[:400]
        _set("failed", str(exc)[:200])
    finally:
        _proc = None


def _sha_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _run_proc(argv: list, phase: str) -> None:
    global _proc
    _proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             text=True)
    out = _proc.communicate(timeout=BUILD_TIMEOUT)[0] or ""
    if _proc.returncode != 0:
        raise RuntimeError(f"{phase} failed ({argv[0]} exit {_proc.returncode}): "
                           f"{out[-300:]}")


def _download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    headers = {"User-Agent": "hermes-wearkit/0.2"}
    tok = _hf_token()
    if tok:
        headers["Authorization"] = "Bearer " + tok
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp, open(tmp, "wb") as fh:
            total = int(resp.headers.get("Content-Length") or MODEL_BYTES)
            done = 0
            while True:
                chunk = resp.read(1 << 20)
                if not chunk:
                    break
                fh.write(chunk)
                done += len(chunk)
                _set("model", f"{done // 1_048_576} MB",
                     min(99, int(done * 100 / max(1, total))))
    except urllib.error.HTTPError as exc:
        tmp.unlink(missing_ok=True)
        if exc.code in (401, 403):
            raise RuntimeError(f"huggingface.co said {exc.code} — consent not given or "
                               "token lacks read access for " + MODEL_REPO) from None
        raise
    os.replace(tmp, dest)


# ------------------------------------------------------------------ synthesis

RUNNER_SRC = '''\
"""NeuTTS Air runner — the ONLY code that touches torch/llama-cpp (fixed argv).

Spawns fresh per synth (voiceout.py: an engine crash must not take the gateway),
writes a 24 kHz mono PCM16 wav to --out. Reference codes are cached beside the
card wav (upstream's own pattern) so repeat synths skip encode_reference.
"""
import argparse, os, struct, sys, wave

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--text-file", required=True)
    ap.add_argument("--ref-wav", required=True)
    ap.add_argument("--ref-text", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--temperature", type=float, default=0.7)
    ap.add_argument("--top-k", type=int, default=50)
    a = ap.parse_args()
    text = open(a.text_file, encoding="utf-8").read()
    codes_path = a.ref_wav + ".codes.pt"
    import torch
    from neutts import NeuTTS
    tts = NeuTTS(backbone_repo=a.model, backbone_device="cpu",
                 codec_repo="neuphonic/neucodec", codec_device="cpu", seed=a.seed)
    if os.path.isfile(codes_path):
        ref_codes = torch.load(codes_path, weights_only=True)
    else:
        ref_codes = tts.encode_reference(a.ref_wav)
        torch.save(ref_codes, codes_path)
    wav = tts.infer(text, ref_codes, a.ref_text,
                    temperature=a.temperature, top_k=a.top_k)
    pcm = (wav * 32767).clip(-32768, 32767).astype("int16")
    with wave.open(a.out, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(tts.sample_rate)
        w.writeframes(pcm.tobytes())

main()
'''


def write_runner() -> Path:
    p = _root() / "air_runner.py"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(RUNNER_SRC)
    return p


def _synth_argv(text: str, ref_wav: Path, ref_text: str, out_wav: Path) -> list:
    """Fixed argv, no shell; text/ref ride FILES, so nothing user-controlled is a
    flag boundary and no quoting trap exists."""
    tf = out_wav.with_suffix(".txt")
    tf.parent.mkdir(parents=True, exist_ok=True)
    tf.write_text(text, encoding="utf-8")
    rf = out_wav.with_suffix(".ref.txt")
    rf.write_text(ref_text, encoding="utf-8")
    return [str(_root() / "venv" / "bin" / "python"),
            str(_root() / "air_runner.py"),
            "--model", str(_root() / "model" / MODEL_FILE),
            "--text-file", str(tf), "--ref-wav", str(ref_wav),
            "--ref-text", str(rf), "--out", str(out_wav)]


def _time_scale(pcm16: bytes, factor: float) -> bytes:
    """16-bit mono linear resample: factor>1 speeds up (shorter). Linear
    interpolation — documented pitch-follow at extremes until WSOLA (SPEC §19)."""
    if abs(factor - 1.0) < 1e-6:
        return pcm16
    n = len(pcm16) // 2
    if n < 2:
        return pcm16
    samples = struct.unpack(f"<{n}h", pcm16[:n * 2])
    out_n = max(2, int(n / factor))
    out = bytearray()
    for i in range(out_n):
        # out_n ALREADY encodes the factor — map the index span, don't scale twice
        pos = i * (n - 1) / (out_n - 1)
        j = int(pos)
        if j >= n - 1:
            v = samples[-1]
        else:
            frac = pos - j
            v = int(samples[j] * (1 - frac) + samples[j + 1] * frac)
        out += struct.pack("<h", max(-32768, min(32767, v)))
    return bytes(out)


def synth(text: str, card: Optional[dict], speed: float) -> tuple:
    """voiceout.register_synth target: (wav_bytes, rate) out. Raises with a NAMED
    cause — never returns silence pretending to be speech."""
    probe = _probe()
    if not probe["installed"]:
        raise RuntimeError("NeuTTS Air not installed (pane ▸ Voice-out ▸ install)")
    if card is None:
        raise RuntimeError("no voice card bound — record one or fetch the seed pack; "
                           "zero-shot cloning needs reference audio")
    voiceout = _voiceout()
    ref_wav = voiceout._root() / voiceout.CARDS_DIR / f"{card['card_id']}.wav"
    if not ref_wav.is_file():
        raise RuntimeError(f"card {card['card_id']} has no reference wav on disk")
    ref_text = card.get("transcript") or ""
    if not ref_text:
        raise RuntimeError(f"card {card['card_id']} has no stored transcript — "
                           "zero-shot clone would be guessing the reference words")
    out = _root() / "synth.wav"
    argv = _synth_argv(text, ref_wav, ref_text, out)
    env = dict(os.environ, HF_HOME=str(_root() / "cache"),
               HF_HUB_DISABLE_TELEMETRY="1", TMPDIR=str(_root()))
    tok = _hf_token()
    if tok:
        env["HF_TOKEN"] = tok
    try:
        proc = subprocess.run(argv, capture_output=True, text=True,
                              timeout=SYNTH_TIMEOUT, env=env)
        if proc.returncode != 0:
            raise RuntimeError("engine failed: " + (proc.stderr or "")[-400:])
        raw = out.read_bytes()
    finally:
        out.unlink(missing_ok=True)
    rate, pcm = _pcm_from_wav(raw)
    pcm = _time_scale(pcm, float(speed))
    return _wrap_wav(pcm, rate), rate


def _pcm_from_wav(blob: bytes) -> tuple:
    i = 12
    rate, data = 0, b""
    while i + 8 <= len(blob):
        cid, sz = blob[i:i + 4], struct.unpack("<I", blob[i + 4:i + 8])[0]
        if cid == b"fmt ":
            rate = struct.unpack("<I", blob[i + 12:i + 16])[0]
        elif cid == b"data":
            data = blob[i + 8:i + 8 + sz]
        i += 8 + sz + (sz & 1)
    if not rate or not data:
        raise RuntimeError("engine returned no readable wav")
    return rate, data


def _wrap_wav(pcm: bytes, rate: int) -> bytes:
    hdr = (b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt "
           + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
           + b"data" + struct.pack("<I", len(pcm)))
    return hdr + pcm


def register_if_ready() -> bool:
    """Register the engine into voiceout when (and only when) the probe is green —
    the no-engine refusal in voiceout.synthesize stays honest until then."""
    probe = _probe()
    if not (probe["installed"] and (probe["model_verified"]
                                    or probe["verified_pending"])):
        return False
    _voiceout().register_synth("air", synth)
    write_runner()
    return True
