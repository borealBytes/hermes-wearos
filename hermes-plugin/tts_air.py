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
import json
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

# ---------------------------------------------------------------- source menu
# Owner ruling 2026-11-09: every weights source we know of ships as a CHOICE —
# official first, ungated mirrors / community re-quants after, so users can pick
# their own trust tradeoff (consent+token for Neuphonic truth, or a hash-pinned
# mirror with no account). Every row is provenance-honest in the pane, and every
# download — mirror or official — is size-gated and sha-recorded (rule 17).
# Community re-quants differ from the official file by a few hundred bytes of
# GGUF metadata (their own quantization of the same Apache-2.0 weights), so each
# row pins its OWN byte count; SHAs record on first verified fetch like MODEL_SHA256.
# THE CODEC IS NEVER MIRRORED (measured 2026-11-09): the only HF-layout port
# (bezzam/neucodec-hf) is the PAPER'S model — flat acoustic_* keys + a 517-key
# w2v-Bert semantic frontend, missing the pytorch_model.bin neutts' loader is
# written against (loader roundtrip: every audio frame encodes to ONE code).
# Both the neutts AND the neucodec loaders hard-assert the gated repo ids, so
# the official pytorch bin is the only fit until a verified port appears; the
# kind:"port" plumbing stays for that day.
SOURCES = {
    "community-q8": {
        "label": "Community Q8_0 — mradermacher re-quant (no account, ungated; "
                 "the most-downloaded source on the Hub)",
        "repo": "mradermacher/neutts-air-GGUF", "file": "neutts-air.Q8_0.gguf",
        "bytes": 802_659_296, "gated": False,
        "codec": {"kind": "repo", "repo": CODEC_REPO},
    },
    "community-q4": {
        "label": "Community Q4_K_M — mradermacher re-quant (no account, ungated; "
                 "half the RAM — the watch-size quant)",
        "repo": "mradermacher/neutts-air-GGUF", "file": "neutts-air.Q4_K_M.gguf",
        "bytes": 596_267_936, "gated": False,
        "codec": {"kind": "repo", "repo": CODEC_REPO},
    },
    "official-q8": {
        "label": "Official Q8_0 — Neuphonic's own file (HF consent + token, one-time)",
        "repo": "neuphonic/neutts-air-q8-gguf", "file": "neutts-air-Q8_0.gguf",
        "bytes": 802_658_528, "gated": True,
        "codec": {"kind": "repo", "repo": CODEC_REPO},   # gated too; token rides
    },
    "official-q4": {
        "label": "Official Q4_0 — Neuphonic (watch-size, HF consent + token)",
        "repo": "neuphonic/neutts-air-q4-gguf", "file": "neutts-air-Q4_0.gguf",
        "bytes": 0,        # gated: anonymous HEAD 401s; size gates on first token'd fetch
        "gated": True,
        "codec": {"kind": "repo", "repo": CODEC_REPO},
    },
    "alt-q8-drinking": {
        "label": "Alt Q8_0 — MrDrinking re-quant (no account, ungated)",
        "repo": "MrDrinking/neutts-air-Q8_0-GGUF", "file": "neutts-air-q8_0.gguf",
        "bytes": 802_658_880, "gated": False,
        "codec": {"kind": "repo", "repo": CODEC_REPO},
    },
}
# Owner ruling 2026-11-09, second pass: the DEFAULT is the best community quant
# (mradermacher — most-downloaded, ungated, no consent dance), and the slots are
# per-device: the gateway runs its own best-quality pick, the watch pulls a
# smaller quant (Q4). "You get to choose the best" on the box, the wrist gets
# the one that fits. Watch-side install lands with slice 5; the choice is
# canonical prefs from here.
DEFAULT_SLOTS = {"gateway": "community-q8", "watch": "community-q4"}
SLOTS = ("gateway", "watch")

try:
    from . import weight_cache as _wc          # package context (the plugin host)
except ImportError:
    _wc = None                             # standalone CLI: lazy import on first use

STATUS = threading.Lock()
_SECRETS = None      # register() injects: fn(key)->str|None reading declared secrets
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
    """The HF read token, never logged. Rule 11: through the plugin's declared
    secret route first (config_schema secret hf_token → profile-scoped), then the
    pane/CLI-written plugin-data file, then a documented bare-env fallback that
    only the STANDALONE CLI path (no host) may hit. The huggingface-cli cache
    file is deliberately NOT read — that is another tool's login store."""
    if _SECRETS is not None:
        try:
            tok = str(_SECRETS("hf_token") or "").strip()
            if tok:
                return tok
        except Exception:
            pass
    local = _root() / "hf_token"                    # pane-side paste box / CLI --token writes this
    try:
        if local.is_file():
            return local.read_text().strip()
    except OSError:
        pass
    if _SECRETS is None:                            # standalone CLI, no host route exists
        return (os.environ.get("HF_TOKEN") or "").strip()
    return ""


def _size_ok(path: Path, nbytes: int = MODEL_BYTES) -> bool:
    try:
        return (nbytes > 0 and path.stat().st_size == nbytes) or \
               (nbytes == 0 and path.is_file())      # size-unknown gated rows: presence only
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


def _slots() -> dict:
    """Per-device model slots {gateway: id, watch: id}, DEFAULT_SLOTS unless
    source.json says otherwise (a pane dropdown choice or --source/--for flag).
    One engine physically runs (the gateway's); the watch slot is the quant the
    watch client will pull when on-watch inference lands (slice 5)."""
    try:
        raw = json.loads((_root() / "source.json").read_text())
    except (OSError, ValueError):
        raw = {}
    if "source" in raw:                       # pre-slots format: one pick, both slots
        legacy = raw["source"]
        raw = {"gateway": legacy, "watch": legacy}
    return {k: (raw.get(k) if raw.get(k) in SOURCES else DEFAULT_SLOTS[k])
            for k in SLOTS}


def _source_id(slot: str = "gateway") -> str:
    return _slots()[slot if slot in SLOTS else "gateway"]


def _source(slot: str = "gateway") -> tuple:
    sid = _source_id(slot)
    s = SOURCES[sid]
    return sid, dict(s, url=f"https://huggingface.co/{s['repo']}/resolve/main/{s['file']}")


def select_source(source_id: str, for_slot: str = "both") -> dict:
    """Persist a dropdown choice. for_slot: gateway | watch | both."""
    if source_id not in SOURCES:
        return {"ok": False, "error": f"unknown source {source_id!r} — choose one of: "
                + ", ".join(SOURCES)}
    if for_slot not in SLOTS + ("both",):
        return {"ok": False, "error": f"unknown device {for_slot!r} — gateway | watch | both"}
    slots = _slots()
    targets = SLOTS if for_slot == "both" else (for_slot,)
    for t in targets:
        slots[t] = source_id
    _root().mkdir(parents=True, exist_ok=True)
    (_root() / "source.json").write_text(json.dumps(slots))
    return {"ok": True, "source": source_id, "slots": slots}


def sources_menu() -> list:
    """The pane dropdown: every known weights row + honest state, per-slot."""
    root, slots = _root(), _slots()
    menu = []
    for k, s in SOURCES.items():
        have = _size_ok(root / "model" / s["file"], s["bytes"])
        menu.append({"id": k, "label": s["label"], "bytes": s["bytes"],
                     "gated": s["gated"], "present": have, "repo": s["repo"],
                     "url": f"https://huggingface.co/{s['repo']}",
                     "selected_for": [t for t in SLOTS if slots[t] == k]})
    return menu


def _probe() -> dict:
    root = _root()
    sid, s = _source()
    venv_py = root / "venv" / "bin" / "python"
    model = root / "model" / s["file"]
    have_venv = venv_py.is_file() and (root / "venv" / ".neutts-installed").is_file()
    have_model = _size_ok(model, s["bytes"])
    pin = s.get("sha256", "PENDING")
    recorded = root / "model" / (s["file"] + ".observed-sha256")
    if pin == "PENDING" and recorded.is_file():
        pin = recorded.read_text().strip()
    verified = have_model and pin != "PENDING" and _verify_at(model, pin)
    return {"installed": have_venv and have_model,
            "venv": have_venv, "model": have_model,
            "source": sid, "sources": sources_menu(),
            "model_verified": verified,
            "verified_pending": have_model and pin == "PENDING",
            "model_size_expected": s["bytes"],
            "dir": str(root),
            "hf_token": bool(_hf_token()),
            "toolchain": bool(shutil.which("cmake") and shutil.which("g++")
                              and shutil.which("git"))}


def _verify_at(path: Path, sha: str) -> bool:
    return sha == "PENDING" or _sha_of(path) == sha


def status() -> dict:
    with STATUS:
        st = dict(_state)
        st["log"] = st["log"][-25:]
    st.update(_probe())
    return st


def preview() -> dict:
    root = _root()
    sid, s = _source()                        # the preview speaks of the CHOSEN row
    gated = bool(s.get("gated"))
    codec = s.get("codec") or {}
    codec_port = codec.get("kind") == "port"
    mb = (s.get("bytes") or 0) // 1_000_000
    steps = [
        f"pip-install {REPO} at commit {COMMIT[:12]} into a private venv "
        "(venv pinned to a Python 3.10-3.13 found on PATH — the neutts pin; "
        "a gateway interpreter outside that range is never used; pulls the "
        "CPU torch wheel; pypi.org)",
        f"Download {s['file']} (~{mb} MB) from huggingface.co/{s['repo']}"
        + (" — gated: auto, needs your one-time consent + read token" if gated
           else " — ungated: no account, no consent click"),
        ("NeuCodec via the ungated HF port (bezzam/neucodec-hf, ~2.5 GB)"
         if codec_port else
         "NeuCodec from neuphonic/neucodec (same consent + token)"),
        f"Size-check against {s.get('bytes') or 'the first-fetch record'} bytes" + (
            f"; SHA-256 check against {s.get('sha256', MODEL_SHA256)[:16]}…"
            if s.get("sha256", MODEL_SHA256) != "PENDING" else
            " (checksum pin lands with the first verified fetch)"),
        f"Everything lands in {root} (private venv + weights cache, nothing system-wide)",
        "Re-install and slot switches reuse the cache: bytes already on disk are "
        "hash-checked against the remote (HEAD size + etag) and hardlinked, never "
        "re-fetched; a half download RESUMES with a Range request",
    ]
    try:
        _venv_python()
        py_ok = True
    except RuntimeError:
        py_ok = False
    needs = ["git", "cmake", "g++"] \
        + ([] if py_ok else ["python 3.10-3.13 (the engine pin; this box's interpreter is out of range)"]) \
        + (["HF read token (HF_TOKEN or the token box)"] if gated else [])
    return {
        "title": f"Voice-out engine — {sid} (NeuTTS Air, Apache-2.0)",
        "steps": steps,
        "hosts": ["github.com", "pypi.org", "huggingface.co"],
        "needs": needs,
        "disk_mb": 4600 if codec_port else 3800,
        "toolchain_ok": _probe()["toolchain"],
    }


def pinned_hint() -> bool:
    return MODEL_SHA256 != "PENDING"


def start(interactive: bool = True, source: Optional[str] = None,
          for_slot: str = "gateway") -> dict:
    if not interactive:
        return {"started": False, "error": "no terminal to approve the install "
                                           "(rule 12): use the Wear Kit pane or run "
                                           "hermes watch setup --fix tts"}
    if source:
        picked = select_source(source, for_slot)
        if not picked.get("ok"):
            return {"started": False, "error": picked["error"]}
    if for_slot == "watch":       # the watch quant is a prefs value; its files ride
        return {"started": False, "slots": _slots(),                     # to the
                "note": "watch quant recorded — the watch client pulls it over "
                        "the lane when on-watch inference lands (slice 5)"}
    probe = _probe()
    if probe["installed"] and (probe["model_verified"] or probe["verified_pending"]):
        return {"started": False, "error": "already installed"}
    if not probe["toolchain"]:
        return {"started": False, "error": "needs git, cmake and g++ (apt install "
                                           "cmake build-essential git)"}
    if SOURCES[probe["source"]]["gated"] and not probe["hf_token"]:
        return {"started": False,
                "error": "the model lives behind HF consent (gated: auto): accept the "
                         "terms at https://huggingface.co/" + SOURCES[probe["source"]]["repo"]
                         + " and set HF_TOKEN or paste a read token into the pane box "
                         "(or pick an ungated mirror source — see the sources list)"}
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


def _venv_python() -> str:
    """neutts pins >=3.10,<3.14 upstream (measured 2026-11-09: 3.14.9 rejects the
    wheel), and the gateway's own interpreter may be outside that range — the venv
    therefore pins a supported interpreter found on PATH, and fails LOUD by name
    when there is none. Never a silent fall onto the host interpreter."""
    import shutil as _sh
    for name in ("python3.13", "python3.12", "python3.11", "python3.10"):
        p = _sh.which(name)
        if p:
            return p
    if sys.version_info.major == 3 and 10 <= sys.version_info.minor < 14:
        return sys.executable
    raise RuntimeError(
        "the voice-out runtime needs Python 3.10-3.13; none found on PATH "
        f"(this gateway runs {sys.version.split()[0]}) — install python3.13 "
        "and press install again")


def _run() -> None:
    root = _root()
    global _proc
    with STATUS:
        _state.update({"started": time.time(), "error": "", "percent": 0, "log": []})
    try:
        root.mkdir(parents=True, exist_ok=True)
        venv = root / "venv"
        sid, s = _source()
        model = root / "model" / s["file"]
        if not (venv / "bin" / "python").is_file():
            _set("venv", "creating the private venv")
            subprocess.run([_venv_python(), "-m", "venv", str(venv)],
                           check=True, capture_output=True, timeout=300)
        if not (venv / ".neutts-installed").is_file():
            _set("runtime", "pip install (torch CPU wheel + llama-cpp build, minutes)")
            _run_proc([str(venv / "bin" / "python"), "-m", "pip", "install",
                       "--no-input", "-q", PKG_SPEC], "runtime")
            # measured 2026-11-09: fresh resolves pair torchao>=0.12 with
            # torchtune 0.6.1, which lost torchao.dtypes.nf4tensor — the codec
            # decoder import dies at the FIRST SYNTH, the worst possible place.
            # Pin the importable version and PROVE the chain imports before we
            # call it installed (fail at install, never at the sound test).
            _run_proc([str(venv / "bin" / "python"), "-m", "pip", "install",
                       "--no-input", "-q", "torchao==0.11.0"], "runtime")
            _run_proc([str(venv / "bin" / "python"), "-c",
                       "import torchtune; "
                       "from torchao.dtypes.nf4tensor import NF4Tensor; "
                       "from neutts import NeuTTS"], "runtime")
            (venv / ".neutts-installed").write_text(PKG_SPEC)
        if not _size_ok(model, s["bytes"]):
            _set("model", f"downloading {s['file']} "
                          f"(~{max(1, s['bytes'] // 1_000_000)} MB)")
            _download(s["url"], model, s["bytes"],
                      s.get("sha256", MODEL_SHA256))
            if not _size_ok(model, s["bytes"]):
                model.unlink(missing_ok=True)
                raise RuntimeError("model size mismatch after download — kept nothing")
            observed = _sha_of(model)
            (model.parent / (s["file"] + ".observed-sha256")).write_text(observed)
            if s.get("sha256", "PENDING") != "PENDING" and observed != s["sha256"]:
                model.unlink(missing_ok=True)
                raise RuntimeError("model failed its SHA-256 check — kept nothing")
        if s["codec"]["kind"] == "port":
            cdir = root / "codec"
            cbig = s["codec"]["bytes"]
            if not _size_ok(cdir / "model.safetensors", cbig):
                _set("codec", "downloading the ungated NeuCodec port (~2.5 GB)")
                for f in s["codec"]["files"]:
                    _download(f"https://huggingface.co/{s['codec']['repo']}"
                              f"/resolve/main/{f}", cdir / f,
                              cbig if f.endswith(".safetensors") else 0)
            if not _size_ok(cdir / "model.safetensors", cbig):
                raise RuntimeError("codec port download size mismatch — kept nothing")
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


def _download(url: str, dest: Path, nbytes: int = 0,
              expected_sha: str = "") -> None:
    """Cache-first fetch through the kit's shared weight_cache (owner ruling
    2026-11-09): a hash-matched cache entry is hardlinked, never re-fetched;
    a half download resumes with Range. Progress reports ride _set so the pane
    dial keeps ticking."""
    if not _wc:
        # standalone-CLI degrade: the module set may be incomplete offline
        import weight_cache as _wc_mod
        globals()["_wc"] = _wc_mod
    return _wc.fetch(
        _root() / "cache", url, dest, nbytes, expected_sha, token=_hf_token(),
        progress=lambda done, total: _set(
            "model", f"{done // 1_048_576} MB",
            min(99, int(done * 100 / max(1, total)))),
        denied=lambda code: (f"huggingface.co said {code} — consent not given or "
                             f"token lacks read access for {MODEL_REPO}"),
    )


# ------------------------------------------------------------------ synthesis

RUNNER_SRC = '''\
"""NeuTTS Air runner — the ONLY code that touches torch/llama-cpp (fixed argv).

Spawns fresh per synth (voiceout.py: an engine crash must not take the gateway),
writes a 24 kHz mono PCM16 wav to --out. Reference codes are cached beside the
card wav (upstream's own pattern) so repeat synths skip encode_reference.

Generation runs at the RAW-TOKEN level on purpose (measured 2026-11-09):
llama_cpp_python 0.3.36 (what neutts' unpinned `llama` extra resolves to today)
strips CONTROL tokens from the rendered completion text, so upstream's
regex-on-text sees "no valid speech tokens" and dies even though the model is
speaking. We iterate .generate() ids and resolve names from the GGUF vocab
ourselves — immune to the renderer. Same reason we accept a local codec DIR:
neutts 1.4.1 allowlists the gated repo ids; the ungated HF-layout port loads
through the same torchtune path (its own _from_pretrained minus the download).
"""
import argparse, os, re, struct, sys, wave

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--text-file", required=True)
    ap.add_argument("--ref-wav", required=True)
    ap.add_argument("--ref-text", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--codec", default="neuphonic/neucodec")
    ap.add_argument("--temperature", type=float, default=0.7)
    ap.add_argument("--top-k", type=int, default=50)
    a = ap.parse_args()
    text = open(a.text_file, encoding="utf-8").read()
    codes_path = a.ref_wav + ".codes.pt"
    import torch
    from neutts import NeuTTS
    # language is REQUIRED when the backbone is a local GGUF (neutts infers it
    # only from Neuphonic repo ids; measured 2026-11-09). en-us = the kit's pin.
    # The codec is the OFFICIAL gated repo id, never a dir: neutts AND neucodec
    # both hard-assert the two repo-id strings (local dirs assert), and the
    # community HF 'port' is the paper's model, not this package's (measured:
    # every audio frame -> ONE code). hf_hub_download picks up HF_TOKEN/HF_HOME
    # from the env voiceout.synth sets. The ungated community mirrors cover the
    # BACKBONE; the codec token is the one honest requirement (rule 13).
    tts = NeuTTS(backbone_repo=a.model, language="en-us", backbone_device="cpu",
                 codec_repo=a.codec, codec_device="cpu", seed=a.seed)
    if os.path.isfile(codes_path):
        ref_codes = torch.load(codes_path, weights_only=True)
    else:
        ref_codes = tts.encode_reference(a.ref_wav)
        torch.save(ref_codes, codes_path)

    # raw-token generation (see module docstring)
    bb = tts.backbone
    prompt = tts._ggml_prompt(ref_codes, a.ref_text, text)
    ids = bb.tokenize(prompt.encode(), add_bos=True, special=True)
    end_id = bb.tokenize(b"<|SPEECH_GENERATION_END|>",
                         add_bos=False, special=True)[0]
    try:
        bb.seed(a.seed)  # llama_cpp 0.3.x: per-call sampler reseed
    except Exception:
        pass
    from gguf import GGUFReader
    names = {}
    for i, t in enumerate(GGUFReader(bb.model_path)
                          .fields["tokenizer.ggml.tokens"].contents()):
        names[i] = t.decode() if isinstance(t, bytes) else str(t)
    SPEECH = re.compile(r"^<\\|speech_(\\d+)\\|>$")
    pieces, ended = [], False
    # repeat_penalty 1.1 = the llama.cpp eval default the vendor's __call__ path
    # gets for free; our raw loop must pass it explicitly (speech token streams
    # loop without it — measured 2026-11-09: rp absent → single-token buzz).
    for t in bb.generate(ids, temp=a.temperature, top_k=a.top_k,
                         repeat_penalty=1.1, reset=True):
        if t == -1:
            continue
        t = int(t)
        if t == end_id:
            ended = True
            break
        nm = names.get(t, "")
        if SPEECH.match(nm):
            pieces.append(nm)
        elif not nm.startswith("<|"):
            pieces.append(nm)
        if len(pieces) >= tts.max_context:
            break  # same token budget the vendor's infer() passes to llama
    if not pieces:
        raise SystemExit("runner: model produced no speech tokens (temp "
                         f"{a.temperature}, seed {a.seed})")
    if not ended:
        # truncated at the context ceiling: decode what we have, say so
        print("runner: generation hit the context ceiling without an END token",
              file=sys.stderr)
    wav = tts._decode("".join(pieces))
    if getattr(tts, "watermarker", None) is not None:
        wav = tts.watermarker.apply_watermark(wav, sample_rate=tts.sample_rate)
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
    _, s = _source()
    tf = out_wav.with_suffix(".txt")
    tf.parent.mkdir(parents=True, exist_ok=True)
    tf.write_text(text, encoding="utf-8")
    rf = out_wav.with_suffix(".ref.txt")
    rf.write_text(ref_text, encoding="utf-8")
    codec = s["codec"]
    argv = [str(_root() / "venv" / "bin" / "python"),
            str(_root() / "air_runner.py"),
            "--model", str(_root() / "model" / s["file"]),
            "--codec", str(_root() / "codec") if codec["kind"] == "port"
                       else codec["repo"],
            "--text-file", str(tf), "--ref-wav", str(ref_wav),
            "--ref-text", str(rf), "--out", str(out_wav)]
    return argv


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


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser(description="Hermes Wear Kit voice-out engine")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("status")
    p = sub.add_parser("sources")
    i = sub.add_parser("install")
    i.add_argument("--source", default=None, help="source id from `sources`")
    i.add_argument("--for", dest="slot", default="gateway", choices=list(SLOTS),
                   help="which device's model slot this picks (default gateway; "
                        "watch records the quant only)")
    i.add_argument("--token", default=None,
                   help="HF read token (validated, stored 0600; gated sources only)")
    a = ap.parse_args()
    if a.cmd == "status":
        print(json.dumps(status(), indent=2))
    elif a.cmd == "sources":
        print("#  GW = the gateway runs this · WT = the watch will pull this")
        for row in sources_menu():
            marks = ("+" if "gateway" in row["selected_for"] else " ") \
                  + ("+" if "watch" in row["selected_for"] else " ")
            state = "present" if row["present"] else ("gated" if row["gated"] else "ready")
            size = f"{row['bytes'] / 1_000_000:.0f} MB" if row["bytes"] else "size-pending"
            print(f"{marks} {row['id']:26} {size:>11}  [{state}]  {row['label']}")
    else:
        if a.token:
            (_root()).mkdir(parents=True, exist_ok=True)
            tf = _root() / "hf_token"
            tf.write_text(a.token.strip())
            os.chmod(tf, 0o600)
        r = start(interactive=True, source=a.source, for_slot=a.slot)
        print(r)
        if r.get("started"):
            while _worker and _worker.is_alive():
                with STATUS:
                    print(f"  {_state['phase']}: {_state['detail']}")
                time.sleep(5)
            with STATUS:
                print("final:", _state["phase"], _state.get("error", ""))
