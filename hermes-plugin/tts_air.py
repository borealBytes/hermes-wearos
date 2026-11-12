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
Paragraph chunking to the model's KV ceiling lives HERE (synth), not at
the route layer — every caller gets it, including the watch lane.
"""

from __future__ import annotations

import hashlib
import itertools
import json
import select
import os
import shutil
import signal
import threading
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
                             # skip). TOFU on first fetch.
OUTPUT_RATE = 24_000                                   # neucodec is 50 Hz → 24 kHz audio
BUILD_TIMEOUT = 60 * 60                                # torch wheel + llama-cpp build
SYNTH_TIMEOUT = 600                                    # a whole paragraph on weak CPUs; a cold first run (model+mmap+codec warm) can exceed 180s after install
CODEC_REPO = "neuphonic/neucodec"                      # PyTorch codec (encode+decode)
# Revision pins (catalog review round 2, Nous #151546): the codec is NEVER
# mirrored (2026-11-09 measurement), so the repo the loader pulls is recorded
# at an exact revision for audit; the kind:"port" plumbing, when it lands,
# downloads at PORT_REV — no floating `main`.
CODEC_REV = "a940021324df13a07f44185f0a17c6dac3698179"     # neuphonic/neucodec @ 2026-11-11
PORT_REV = "11873db8f834e8dc5b132d64b421fc4890ec45fe"      # bezzam/neucodec-hf  @ 2026-11-11

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
        # Pinned per catalog review round 2 (Nous #151546): cross-verified —
        # HF LFS oid matches this box's observed-sha256 from the day-one fetch.
        "sha256": "589adb5df73ee56ef68cd2f3056d8c8b938bd6440f81221d99332bb51640b7ad",
        "codec": {"kind": "repo", "repo": CODEC_REPO},
    },
    "community-q4": {
        "label": "Community Q4_K_M — mradermacher re-quant (no account, ungated; "
                 "half the RAM — the watch-size quant)",
        "repo": "mradermacher/neutts-air-GGUF", "file": "neutts-air.Q4_K_M.gguf",
        "bytes": 596_267_936, "gated": False,
        "sha256": "9e8ae3aefb85c617f6eabeec774619c59d402ed3f8096c28b842d0abdb6c98a1",
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
        "sha256": "ca552530b7e81a0756f71886fe32081195a653721e5a5b2f5d4ec0468d140ddb",
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


def validate_token(token: str) -> dict:
    """One-shot HF whoami (stdlib urllib, 10 s): the pane paste box and CLI
    --token refuse an invalid/dead token AT PASTE TIME instead of learning at
    download time. Returns {ok, user|reason}. Never logs the secret."""
    import urllib.error
    import urllib.request
    req = urllib.request.Request(
        "https://huggingface.co/api/whoami-v2",
        headers={"Authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return {"ok": True, "user": str(json.load(r).get("name") or "?")}
    except urllib.error.HTTPError as e:
        if e.code in (401, 403, 404):
            return {"ok": False, "reason": "rejected by huggingface.co "
                    "(token not found, expired, or deleted)"}
        return {"ok": True, "user": "?"}          # unexpected status: don't
    except OSError:                              # block a token we can't prove
        return {"ok": True, "user": "?"}          # dead from here (offline?)
    return {"ok": True, "user": "?"}


def store_token(token: str) -> dict:
    """Validated, then written 0600 (rule 11 file fallback route)."""
    token = token.strip()
    v = validate_token(token)
    if not v["ok"]:
        return {"ok": False, "error": v["reason"]}
    _root().mkdir(parents=True, exist_ok=True)
    tf = _root() / "hf_token"
    tf.write_text(token)
    os.chmod(tf, 0o600)                          # no window before 0600
    return {"ok": True, "user": v["user"]}


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
        _ensure_venv(venv, _set)
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
                              f"/resolve/{s['codec'].get('rev', PORT_REV)}/{f}",
                              cdir / f,
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


def _min_env(extra: "dict | None" = None) -> dict:
    """Catalog review law (Nous #151546, 2026-11-10): never hand the inherited
    gateway environment (every provider key) to third-party build/setup code.
    Allowlist the basics, drop the rest. Mirrors stt_install._min_env."""
    env = {k: v for k, v in os.environ.items()
           if k in ("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "TMP", "TEMP",
                    "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "XDG_CACHE_HOME",
                    "http_proxy", "https_proxy", "no_proxy")}
    env.setdefault("GIT_TERMINAL_PROMPT", "0")
    if extra:
        env.update(extra)
    return env


def _run_proc(argv: list, phase: str) -> None:
    global _proc
    _proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             text=True, env=_min_env())
    out = _proc.communicate(timeout=BUILD_TIMEOUT)[0] or ""
    if _proc.returncode != 0:
        raise RuntimeError(f"{phase} failed ({argv[0]} exit {_proc.returncode}): "
                           f"{out[-300:]}")


# ---------------------------------------------------------------- venv + device
#
# SPEC 19.4(m) — the engine speaks on CPU by default and CAN speak on any one
# GPU the pane names. Two private venvs live side by side: 'venv' (CPU, the
# floor every install gets) and 'venv_cu' (same pins, llama-cpp rebuilt with
# the CUDA backend — a consent action, never automatic, rule 12). The device
# choice persists in tts/device.json. A GPU worker is spawned with
# CUDA_VISIBLE_DEVICES=<picked index>, so inside the worker 'cuda' is exactly
# the chosen card and an unlucky mapping can NEVER reach GPU0 or another card.

def _ensure_venv(venv: Path, setf) -> None:
    """Create + fill one private venv with the pinned runtime (idempotent)."""
    if not (venv / "bin" / "python").is_file():
        setf("venv", "creating the private venv")
        subprocess.run([_venv_python(), "-m", "venv", str(venv)],
                       check=True, capture_output=True, timeout=300,
                       env=_min_env())
    if not (venv / ".neutts-installed").is_file():
        setf("runtime", "pip install (torch wheel + llama-cpp, minutes)")
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


_CUDA_BUILD = threading.Lock()
_CUDA_STATE = {"phase": "idle", "detail": ""}
LLAMA_PIN = "llama-cpp-python==0.3.36"          # the version PKG_SPEC resolved to
# Rule 17: the upstream CUDA release wheel for THAT EXACT pin, hash-pinned.
# 1.8 GB 'full' build — every arch incl. sm_89 — so the first GPU enable is a
# download, not a twenty-minute nvcc tour. Source build remains the fallback
# (verification decides; a bad wheel can never strand speech). Measured
# 2026-11-10: local glibc 2.43 >= wheel's manylinux_2_35, driver 580.
LLAMA_CUDA_WHEEL_URL = (
    "https://github.com/abetlen/llama-cpp-python/releases/download/"
    "v0.3.36-cu124/llama_cpp_python-0.3.36-py3-none-manylinux_2_35_x86_64.whl")
LLAMA_CUDA_WHEEL_SHA = "3d53f94464d8d69028d993033d6120439c994108c04375a46205bd1935bc30ad"

def _cuda_state_write() -> None:
    try:
        (_root() / "cuda_build.json").write_text(json.dumps(_CUDA_STATE))
    except OSError:
        pass


def _gpu_compute_cap(idx: int) -> str:
    """sm arch for the picked card ('8.9' -> '89') so nvcc compiles ONE target:
    minutes, not the ten-architecture tour."""
    try:
        r = subprocess.run(["nvidia-smi", f"-i", str(idx),
                            "--query-gpu=compute_cap",
                            "--format=csv,noheader"],
                           capture_output=True, text=True, timeout=15)
        cap = (r.stdout or "").strip().splitlines()[0].strip()
        if cap.replace(".", "").isdigit():
            return cap.replace(".", "")
    except (OSError, subprocess.TimeoutExpired, IndexError):
        pass
    return "89"                                  # Ada floor; honest fallback


def cuda_build_state() -> dict:
    try:
        return dict(json.loads((_root() / "cuda_build.json").read_text()),
                    venv_cu=(_root() / "venv_cu" / ".cuda-installed").is_file())
    except (OSError, ValueError):
        return {"phase": "idle", "detail": "",
                "venv_cu": (_root() / "venv_cu" / ".cuda-installed").is_file()}


def build_cuda_gpu(idx: int) -> dict:
    """One-time CUDA variant of the runtime (consent = the device pick).
    Same pins as the CPU venv; llama-cpp is REBUILT from the identical pinned
    source with GGML_CUDA on, for the picked card's compute capability only
    (rule 17: pinned source, no floating binaries)."""
    with _CUDA_BUILD:
        st = cuda_build_state()
        if st["phase"] == "building":
            return st
        _CUDA_STATE.update(phase="building",
                           detail="creating the CUDA venv (reuses the wheel cache)")
        _cuda_state_write()
    def _verify_cuda(venv: Path) -> None:
        v = subprocess.run(
            [str(venv / "bin" / "python"), "-c",
             "import torch, glob, os, llama_cpp; "
             "assert torch.cuda.is_available(), 'torch sees no CUDA'; "
             "assert any('cuda' in os.path.basename(x) for x in glob.glob("
             "os.path.dirname(llama_cpp.__file__) + '/**/*.so*', recursive=True)), "
             "'llama_cpp has no CUDA backend'; print('ok')"],
            env=dict(_runner_env("cpu"), CUDA_VISIBLE_DEVICES=str(idx)),
            capture_output=True, text=True, timeout=120)
        if "ok" not in (v.stdout or ""):
            raise RuntimeError("CUDA verification failed: "
                               + ((v.stdout or "") + (v.stderr or ""))[-400:])

    def _install_cuda_wheel(venv: Path) -> None:
        """Hash-pinned release wheel first (rule 17: verified download, not a
        floating binary); the cache dir may already hold it from a past run."""
        import hashlib
        _CUDA_STATE.update(phase="building",
                           detail="fetching the CUDA llama-cpp release wheel")
        _cuda_state_write()
        dest = _root() / "wheel_cache" / LLAMA_CUDA_WHEEL_URL.rsplit("/", 1)[-1]
        dest.parent.mkdir(parents=True, exist_ok=True)
        if not (dest.is_file() and
                hashlib.sha256(dest.read_bytes()).hexdigest() == LLAMA_CUDA_WHEEL_SHA):
            _download(LLAMA_CUDA_WHEEL_URL, dest, expected_sha=LLAMA_CUDA_WHEEL_SHA)
        _CUDA_STATE.update(phase="building",
                           detail="installing the CUDA llama-cpp wheel")
        _cuda_state_write()
        subprocess.run([str(venv / "bin" / "python"), "-m", "pip", "install",
                        "--no-input", "-q", "--no-deps", "--force-reinstall",
                        str(dest)],
                       check=True, capture_output=True, timeout=600,
                       env=_runner_env("cpu"))
        _verify_cuda(venv)

    def _run():
        try:
            venv = _root() / "venv_cu"
            _ensure_venv(venv, lambda ph, det, p=0: _CUDA_STATE.update(
                phase=ph, detail=det) or _cuda_state_write())
            if not (venv / ".cuda-installed").is_file():
                try:
                    _install_cuda_wheel(venv)
                except Exception as wheel_exc:
                    # fallback: build the same pinned source with CUDA, for the
                    # picked card's compute capability only
                    _CUDA_STATE.update(
                        phase="building",
                        detail=f"wheel unusable ({str(wheel_exc)[:120]}) — building "
                               f"llama-cpp with CUDA (arch {_gpu_compute_cap(idx)}, "
                               "minutes, CPU-heavy once)")
                    _cuda_state_write()
                    env = dict(_runner_env("cpu"),
                               CMAKE_ARGS=f"-DGGML_CUDA=on "
                                          f"-DCMAKE_CUDA_ARCHITECTURES={_gpu_compute_cap(idx)}",
                               CMAKE_BUILD_PARALLEL_LEVEL="16")
                    for extra in ("cmake", "ninja"):
                        subprocess.run([str(venv / "bin" / "python"), "-m", "pip",
                                        "install", "--no-input", "-q", extra],
                                       check=True, capture_output=True, timeout=600,
                                       env=_min_env())
                    r = subprocess.run(
                        [str(venv / "bin" / "python"), "-m", "pip", "install",
                         "--no-input", "-q", "--no-deps", "--no-binary", ":all:",
                         "--force-reinstall", LLAMA_PIN],
                        env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                        text=True, timeout=BUILD_TIMEOUT)
                    if r.returncode != 0:
                        raise RuntimeError("CUDA llama-cpp build failed: "
                                           + (r.stdout or "")[-400:])
                    _verify_cuda(venv)
                (venv / ".cuda-installed").write_text(LLAMA_PIN)
            _CUDA_STATE.update(phase="done",
                               detail=f"GPU {idx} ready — the next speak respawns "
                                      "the engine on the card")
        except Exception as exc:
            _CUDA_STATE.update(phase="failed", detail=str(exc)[:400])
        _cuda_state_write()
    threading.Thread(target=_run, daemon=True).start()
    return cuda_build_state()


def get_device() -> str:
    try:
        return str(json.loads((_root() / "device.json").read_text())
                   .get("device") or "cpu")
    except (OSError, ValueError):
        return "cpu"


def effective_device(device: Optional[str] = None) -> str:
    """What the NEXT worker will really run on. Owner doctrine 2026-11-10:
    a GPU pick means GPU — while the one-time CUDA build runs (or if it has
    not started) the answer is 'pending-gpu', NOT a silent CPU downgrade.
    A CPU worker must only ever exist because CPU was the chosen backend."""
    dev = device if device is not None else get_device()
    if dev.startswith("gpu:"):
        if (_root() / "venv_cu" / ".cuda-installed").is_file():
            return "cuda"
        return "pending-gpu"
    return "cpu"


def devices() -> list:
    """Every place the engine can speak: CPU + all GPUs nvidia-smi reveals,
    with the memory/util truth of the moment so the owner picks right. No
    nvidia-smi (or no CUDA venv) is reflected in each row's ready flag —
    the dropdown lists reality, never an aspiration."""
    cur = get_device()
    out = [{"id": "cpu", "label": "CPU (bounded threads, background nice)",
            "ready": True, "selected": cur == "cpu"}]
    st = cuda_build_state()
    try:
        r = subprocess.run(
            ["nvidia-smi",
             "--query-gpu=index,name,memory.used,memory.total,utilization.gpu",
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=10)
        rows = (r.stdout or "").splitlines() if r.returncode == 0 else []
    except (OSError, subprocess.TimeoutExpired):
        rows = []
    for line in rows:
        f = [x.strip() for x in line.split(",")]
        if len(f) < 5:
            continue
        try:
            idx, name = int(f[0]), f[1]
            used, total, util = int(f[2]), int(f[3]), int(f[4])
        except ValueError:
            continue
        row = {"id": f"gpu:{idx}", "label": f"GPU {idx} · {name}",
               "free_mb": max(0, total - used), "util": util,
               "selected": cur == f"gpu:{idx}"}
        if st["phase"] == "building":
            row["ready"] = False
            row["note"] = "building the CUDA engine once…"
        elif st["venv_cu"]:
            row["ready"] = True
        else:
            row["ready"] = False
            row["note"] = "select to build the CUDA engine (one time, minutes)"
        if used > total // 3:
            row["note"] = (row.get("note", "") +
                           f" · already carrying {used} MB (busy elsewhere)").strip(" ·")
        out.append(row)
    return out


def set_device(dev: str) -> dict:
    """Persist the choice; a GPU pick IS the consent (rule 12) to run the
    one-time CUDA build. The live worker is left alone: every host process
    rebinds lazily on its next request when the effective device changes."""
    if dev != "cpu" and not (dev.startswith("gpu:") and dev[4:].isdigit()):
        return {"ok": False, "error": "device must be 'cpu' or 'gpu:<index>'"}
    _root().mkdir(parents=True, exist_ok=True)
    (_root() / "device.json").write_text(json.dumps({"device": dev}))
    if dev.startswith("gpu:"):
        build_cuda_gpu(int(dev.split(":")[1]))
    return {"ok": True, "device": dev, "build": cuda_build_state(),
            "effective": effective_device(dev)}


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
"""NeuTTS Air runner — the ONLY code that touches torch/llama-cpp.

Serves ONE warm model per gateway (owner ruling 2026-11-10: the spawn-per-
sentence era piled ten 3.4 GB pythons onto boot and turned every dial move
into a minutes-long wait). `--serve` reads one JSON request per stdin line
{cmd?, text_file, ref_wav, ref_text, out, seed, temperature, top_k} and
answers {"ok": true|false, error?} per line; a crash still cannot take the
gateway down because the parent respawns and retries once. Without --serve
the OLD fixed-argv one-shot contract stands (tests, dev). Reference codes
are cached per ref wav (upstream's own pattern) — the second sentence on a
card skips encode_reference entirely.

Generation runs at the RAW-TOKEN level on purpose (measured 2026-11-09):
llama_cpp_python 0.3.36 (what neutts' unpinned `llama` extra resolves to today)
strips CONTROL tokens from the rendered completion text, so upstream's
regex-on-text sees "no valid speech tokens" and dies even though the model is
speaking. We iterate .generate() ids and resolve names from the GGUF vocab
ourselves — immune to the renderer.
"""
import argparse, json, os, re, select, sys, time, wave

SPEECH = re.compile(r"^<\\|speech_(\\d+)\\|>$")
# RESIDENT by owner ruling 2026-11-10: the model stays loaded — reply latency
# beats RAM. 0/None = never exit on idle; set WEARKIT_TTS_IDLE_S=900 to restore
# the old reaper on small boxes.
IDLE_EXIT_S = int(os.environ.get("WEARKIT_TTS_IDLE_S", "0") or 0)
_TTS = None
_NAMES = None
_CODES = {}


def _tts(model, codec, seed, device="cpu"):
    global _TTS
    if _TTS is None:
        import torch
        from neutts import NeuTTS
        # language is REQUIRED when the backbone is a local GGUF (neutts infers it
        # only from Neuphonic repo ids; measured 2026-11-09). en-us = the kit pin.
        # The codec is the OFFICIAL gated repo id, never a dir: neutts AND neucodec
        # both hard-assert the two repo-id strings (local dirs assert) — except the
        # kit's pinned community port dir, which loads through the same path.
        # device: 'cpu' or 'cuda' — CUDA_VISIBLE_DEVICES is set by the parent, so
        # 'cuda' here is ALWAYS exactly the one GPU the pane selected (SPEC 19.4m:
        # a GPU0-running sglang can never be touched by accident).
        _TTS = NeuTTS(backbone_repo=model, language="en-us", backbone_device=device,
                      codec_repo=codec, codec_device=device, seed=seed)
    return _TTS


def _names(tts):
    global _NAMES
    if _NAMES is None:
        from gguf import GGUFReader
        _NAMES = {i: (t.decode() if isinstance(t, bytes) else str(t))
                  for i, t in enumerate(GGUFReader(tts.backbone.model_path)
                                         .fields["tokenizer.ggml.tokens"].contents())}
    return _NAMES


def synth_one(tts, text, ref_wav, ref_text, out, seed, temperature, top_k):
    import torch
    codes_path = ref_wav + ".codes.pt"
    if codes_path in _CODES:
        ref_codes = _CODES[codes_path]
    elif os.path.isfile(codes_path):
        ref_codes = torch.load(codes_path, weights_only=True)
        _CODES[codes_path] = ref_codes
    else:
        ref_codes = tts.encode_reference(ref_wav)
        torch.save(ref_codes, codes_path)
        _CODES[codes_path] = ref_codes
    bb = tts.backbone
    prompt = tts._ggml_prompt(ref_codes, ref_text, text)
    ids = bb.tokenize(prompt.encode(), add_bos=True, special=True)
    end_id = bb.tokenize(b"<|SPEECH_GENERATION_END|>",
                         add_bos=False, special=True)[0]
    try:
        bb.seed(seed)  # llama_cpp 0.3.x: per-call sampler reseed
    except Exception:
        pass
    names = _names(tts)
    pieces, ended = [], False
    # repeat_penalty 1.1 = the llama.cpp eval default the vendor's __call__ path
    # gets for free; our raw loop must pass it explicitly (speech token streams
    # loop without it — measured 2026-11-09: rp absent → single-token buzz).
    for t in bb.generate(ids, temp=temperature, top_k=top_k,
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
        raise RuntimeError("model produced no speech tokens (temp %s, seed %s)"
                           % (temperature, seed))
    # neucodec decode needs a floor of latent frames — a short reply like
    # "you're welcome" crashed it (measured 2026-11-10: conv padding 1024 vs
    # input 640). Hold the last token to the floor: the pad decodes to tail
    # ambience, and the seam/trim law upstream already tolerates it.
    while len(pieces) < 64:
        pieces.append(pieces[-1])
    wav = tts._decode("".join(pieces))
    if getattr(tts, "watermarker", None) is not None:
        wav = tts.watermarker.apply_watermark(wav, sample_rate=tts.sample_rate)
    pcm = (wav * 32767).clip(-32768, 32767).astype("int16")
    if int(abs(pcm).max(initial=0)) < 128:
        # Honesty guard (measured 2026-11-10: serve-mode renders could return
        # ok with an all-zero decode). Silence is a FAILURE here, so the
        # parent falls back to the audible system tier instead of caching and
        # speaking nothing.
        raise RuntimeError("engine decoded digital silence")
    with wave.open(out, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(tts.sample_rate)
        w.writeframes(pcm.tobytes())
    return ended


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--codec", default="neuphonic/neucodec")
    ap.add_argument("--serve", action="store_true")
    ap.add_argument("--text-file")
    ap.add_argument("--ref-wav")
    ap.add_argument("--ref-text")
    ap.add_argument("--out")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--device", default="cpu", choices=["cpu", "cuda"])
    ap.add_argument("--state", default="", help="busy/idle marker file")
    ap.add_argument("--temperature", type=float, default=0.7)
    ap.add_argument("--top-k", type=int, default=50)
    a = ap.parse_args()
    if a.serve:
        # The ONLY thing allowed on the original stdout is one JSON line per
        # request — torch/neutts/GGUF load chatter is real-world noise on fd 1
        # (measured 2026-11-10: a banner line ate the first readline and the
        # parent died on JSONDecodeError). Save the protocol fd, park fd 1 on
        # /dev/null, speak only through the saved handle.
        import io as _io
        _proto = _io.open(os.dup(1), "w", buffering=1)
        _null = os.open(os.devnull, os.O_WRONLY)
        os.dup2(_null, 1)
        tts = _tts(a.model, a.codec, a.seed, a.device)
        while True:
            r, _, _ = select.select([sys.stdin], [], [], IDLE_EXIT_S or None)
            if not r:
                return 0                  # reaper only (IDLE_EXIT_S>0): parent respawns
            line = sys.stdin.readline()
            if not line:
                return 0
            try:
                req = json.loads(line)
            except ValueError:
                continue
            if req.get("cmd") == "quit":
                return 0
            # busy/idle marker (sibling-safe reaping): a worker mid-synth is
            # NEVER reaped by another process's spawn guard — the 21:16
            # ping-pong killed the owner's stream mid-draw.
            _st = open(a.state, "w") if a.state else None
            def _mark(state, _f=_st):
                if _f:
                    _f.seek(0); _f.truncate()
                    _f.write(json.dumps({"pid": os.getpid(), "state": state,
                                         "t": time.time()}))
                    _f.flush()
            _mark("busy")
            try:
                text = open(req["text_file"], encoding="utf-8").read()
                ref_text = open(req["ref_text"], encoding="utf-8").read()
                synth_one(tts, text, req["ref_wav"], ref_text, req["out"],
                          int(req.get("seed", a.seed)),
                          float(req.get("temperature", a.temperature)),
                          int(req.get("top_k", a.top_k)))
                _proto.write(json.dumps({"ok": True}) + "\\n")
            except Exception as exc:
                _proto.write(json.dumps({"ok": False,
                                         "error": str(exc)[:300]}) + "\\n")
            _mark("idle")
            if _st:
                _st.close()
            _proto.flush()
    if not (a.text_file and a.ref_wav and a.ref_text and a.out):
        raise SystemExit("one-shot mode needs --text-file/--ref-wav/--ref-text/--out")
    text = open(a.text_file, encoding="utf-8").read()
    ref_text = open(a.ref_text, encoding="utf-8").read()
    synth_one(_tts(a.model, a.codec, a.seed, a.device), text, a.ref_wav, ref_text, a.out,
              a.seed, a.temperature, a.top_k)


main()
'''


def write_runner() -> Path:
    p = _root() / "air_runner.py"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(RUNNER_SRC)
    return p


class ProtocolRefusal(RuntimeError):
    """The warm worker answered honestly ('no, that draw decoded to silence').
    Healthy worker — the retry loop must NOT respawn it (CPU law, owner
    2026-11-10: the reload storm is what spiked the 7950X to load 24)."""


_WORKER = None
_WORKER_DEV = None    # device the live worker was spawned on (pane flips rebind)
_QUEUED = 0        # in-flight/queued requests — the pane's live "what is happening" number
_LAST_OK = 0.0     # epoch of the last successful render (warm-truth for the chip)
_SHUTTING_DOWN = False


def _shutdown_worker() -> None:
    """Resident workers must die with the gateway, not linger as orphans holding
    3.4 GB (and must never block a graceful stop: terminate-then-kill, 2 s grace).
    Registered via atexit — SIGTERM under systemd lands here, and the boot-warm
    thread checks the flag so a shutdown can't be re-ignited by a late warm."""
    global _SHUTTING_DOWN, _WORKER
    _SHUTTING_DOWN = True
    w = _WORKER
    _WORKER = None
    if w and w.poll() is None:
        try:
            w.terminate()
            try:
                w.wait(2)                      # polite exit first...
            except Exception:
                w.kill()                       # ...then the axe (2 s grace max)
        except OSError:
            pass


import atexit as _atexit            # noqa: E402  (next to the lifecycle state)
_atexit.register(_shutdown_worker)


def _reap_stray_workers(keep: "set | None" = None) -> None:
    """ONE air_runner on the machine, full stop (owner law 2026-11-11: the 4070
    cannot afford a second ~4.4 GB copy — VRAM hit 97% with two twins and a
    third spawn could not load). Not per-process, not per-gateway-parent —
    SYSTEM-WIDE: every `air_runner.py --serve` that is not explicitly kept is
    an exit. Covers every twin ever measured: init-parented orphans from killed
    gates, sibling children of this gateway (two module instances, or a
    lockless double-spawn race — live-proven 23:12), and a dashboard that
    warmed its own while the gateway warmed another. The killed owner lazily
    respawns on its NEXT speak; the survivor pays the model load once. Cheap
    /proc scan, stdlib only (rule 10)."""
    keep = keep or set()
    _foreign_busy_at[0] = 0.0
    busy = _worker_state_busy()
    try:
        pids = [int(p) for p in os.listdir("/proc") if p.isdigit()]
    except OSError:
        return                       # non-linux: nothing to do
    for pid in pids:
        try:
            with open("/proc/%d/cmdline" % pid, "rb") as f:
                cmd = f.read().replace(b"\0", b" ").decode("utf-8", "replace")
            if "air_runner.py" not in cmd or "--serve" not in cmd:
                continue
            if pid in keep or pid == os.getpid():
                continue
            if busy is not None and pid == busy:
                # mid-synth on another surface (the 21:16 ping-pong killed the
                # owner's stream): never reaped. Spawn guard defers instead.
                _foreign_busy_at[0] = time.time()
                continue
            os.kill(pid, signal.SIGKILL)
        except (OSError, ValueError):
            continue                 # raced/permission — best effort


_foreign_busy_at = [0.0]             # ts of last seen foreign mid-synth worker


def _worker_state_busy() -> "int | None":
    """pid of the last announced worker while it claims BUSY and its marker
    is fresher than one synth could take; None = nothing safe to protect."""
    try:
        st = json.loads((_root() / "worker.state").read_text())
        if (st.get("state") == "busy"
                and time.time() - float(st.get("t", 0)) < SYNTH_TIMEOUT):
            return int(st.get("pid", -1))
    except (OSError, ValueError):
        pass
    return None


_WORKERS_ALL: list = []              # every worker this process ever spawned
_SPAWN_LOCK = threading.RLock()      # check-then-spawn is ONE step (twin law)


def _spawn_guard(w, want: str, seed):
    """Check-then-spawn as ONE step (owner law 2026-11-11: never two TTS
    workers — VRAM cannot afford it). Boot prewarm and the first watch request
    once both read _WORKER=None a quarter-second apart and each loaded a full
    ~4.4 GB model; the lock plus the system-wide reaper below makes that
    structurally impossible: inside the lock the keep-set is the only thing
    that survives, and a same-device live worker is adopted, never doubled."""
    global _WORKER, _WORKER_DEV
    with _SPAWN_LOCK:
        w = _WORKER if _WORKER is not None else w   # re-read under the lock
        if w is not None and _WORKER_DEV != want and w.poll() is None:
            try:                           # lazy rebind: the pane flipped
                w.terminate()              # cpu->gpu; next speak boots there
                w.wait(timeout=5)
            except Exception:
                w.kill()
            w = _WORKER = None
        if w is not None and w.poll() is None and _WORKER_DEV == want:
            _reap_stray_workers({w.pid})   # adopt the live one; sweep every twin
            return w
        if w is None or w.poll() is not None:
            write_runner()
            _reap_stray_workers()          # system-wide: no twins, no orphans
            if _foreign_busy_at[0] and time.time() - _foreign_busy_at[0] < 60:
                # A sibling surface (dashboard speaking vs this lane) is
                # MID-SYNTH and cannot be reaped without clipping its audio.
                # DEFER rather than twin on the 4070: honest retry (the speak
                # attempt loop re-enters; its marker expires or flips idle).
                return None
            _WORKER = w = subprocess.Popen(
                _serve_argv(seed), stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL, text=True, env=_runner_env(),
                # background class: the engine must never outrank the
                # gateway, sglang, or the owner's foreground work
                preexec_fn=(lambda: os.nice(10) if os.name != "nt" else None))
            _WORKER_DEV = want
            _WORKERS_ALL.append(w)
            _reap_extra_tracked(w)         # one worker per process, full stop
        return w


def _reap_extra_tracked(keep) -> None:
    """At most ONE live worker per process: anything we spawned and no longer
    track as THE worker is an exit — its model memory is speakable by nobody."""
    global _WORKERS_ALL
    alive = []
    for w in list(_WORKERS_ALL):
        if w.poll() is not None:
            continue                 # dead handle: drop
        if w is keep:
            alive.append(w)
            continue
        try:
            w.kill()                 # live but untracked: exit
        except OSError:
            pass
    _WORKERS_ALL = alive


def engine_state() -> dict:
    """Live worker/queue state for the pane (issue #7 honesty: a spinner must
    say WHICH wait it is — model loading, queued, or speaking)."""
    w = _WORKER
    alive = bool(w and w.poll() is None)
    if alive and _LAST_OK:                 # resident model, at least one render done
        phase = "warm"
    elif alive:
        phase = "loading"          # worker exists, first render not back yet
    else:
        phase = "idle"
    st = {"phase": phase, "queued": _QUEUED, "last_ok": _LAST_OK,
          "device": get_device(), "effective": effective_device()}
    bld = cuda_build_state()
    if bld["phase"] in ("building", "failed"):
        st["build"] = bld                # the pane names the wait, not a spinner
    if _WORKER_DEV is not None and _WORKER_DEV != st["effective"] and not alive:
        st["phase"] = "rebinding"        # device flipped: next speak boots there
    return st


# ONE model, ONE synthesis at a time, IN ARRIVAL ORDER (owner ruling
# 2026-11-10: "one TTS engine, FIFO queue in front of it — one agent speaks
# at a time on the watch"). threading.Lock has no fairness promise, so the
# gate is an explicit ticket line: whoever asked first talks first.
_TICK = itertools.count()
_READY = threading.Condition()
_NEXT = 0


def _runner_env(device: Optional[str] = None) -> dict:
    # Catalog review law (Nous #151546): NOTHING in this chain inherits the
    # gateway environment — a pinned wheel's setup.py and the vendored neutts
    # runtime must not see provider keys. Allowlist, then add what we require.
    env = {k: v for k, v in os.environ.items()
           if k in ("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "TMP", "TEMP",
                    "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "XDG_CACHE_HOME",
                    "http_proxy", "https_proxy", "no_proxy", "USER", "SHELL",
                    "TERM", "TZ")}
    env.update(HF_HOME=str(_root() / "cache"),
               HF_HUB_DISABLE_TELEMETRY="1",
               TMPDIR=str(_root()),
               LD_LIBRARY_PATH=str(_root() / "cache"),
               GIT_TERMINAL_PROMPT="0",
               # ONE worker, a bounded slice of the box (owner CPU-spike ruling
               # 2026-11-10): llama.cpp/torch default to ALL 32 threads and the
               # engine shares this host with sglang + the gateway itself.
               OMP_NUM_THREADS="8", MKL_NUM_THREADS="8")
    dev = device if device is not None else get_device()
    if dev.startswith("gpu:"):
        # THE GPU0 SHIELD: the worker sees exactly the picked card as its
        # cuda:0. An owner who picks GPU 1 cannot touch sglang's GPU 0 —
        # not by convention, by visibility.
        env["CUDA_VISIBLE_DEVICES"] = dev.split(":")[1]
    for _k in ("PYTHONPATH", "PYTHONHOME", "CONDA_PREFIX", "CONDA_DEFAULT_ENV"):
        env.pop(_k, None)   # host interpreter paths must not shadow the venv
    tok = _hf_token()
    if tok:
        env["HF_TOKEN"] = tok
    return env


def _serve_argv(seed=None, device: Optional[str] = None) -> list:
    _, s = _source()
    eff = effective_device(device)
    py = _root() / ("venv_cu" if eff == "cuda" else "venv") / "bin" / "python"
    argv = [str(py),
            str(_root() / "air_runner.py"),
            "--serve", "--device", eff,
            "--model", str(_root() / "model" / s["file"]),
            "--codec", str(_root() / "codec") if s["codec"]["kind"] == "port"
                       else s["codec"]["repo"],
            "--state", str(_root() / "worker.state")]
    if seed is not None:
        # neutts 1.4.1 seeds the sampler ONLY at construction, so a rotated
        # draw across a respawn needs the seed in argv, not just in the request.
        argv += ["--seed", str(int(seed))]
    return argv


def _worker_request(req: dict) -> None:
    """One line in, one line out against the warm model. Respawn-once keeps the
    crash-isolation law (an engine fault cannot wedge the lane or take the
    gateway): death/timeout/refusal → SIGKILL → fresh worker → up to 3 attempts
    total, rotating the sampler seed after each failure (degenerate generations
    are per-draw, not per-worker)."""
    global _WORKER, _NEXT, _QUEUED, _LAST_OK, _WORKER_DEV
    if _SHUTTING_DOWN:
        raise RuntimeError("engine shutting down")   # never re-ignite during stop
    ticket = next(_TICK)
    with _READY:
        _QUEUED += 1
        while ticket != _NEXT:
            _READY.wait()
        try:
            for attempt in (1, 2, 3):
                w = _WORKER
                want = effective_device()          # device may have changed in the pane
                if want == "pending-gpu":
                    # GPU is the chosen backend but its worker build has not
                    # finished — REFUSE loudly (owner doctrine 2026-11-10: a
                    # GPU pin never quietly runs on CPU). No worker spawns;
                    # the pane's Build row carries the fix.
                    raise RuntimeError(
                        "GPU selected but its engine build is not ready — "
                        "Voice-out ▸ GPU row shows build progress")
                w = _spawn_guard(w, want, req.get("seed"))
                if w is None:
                    # deferred: sibling surface mid-synth (marker-guarded) —
                    # wait; the attempt loop retries honestly, and the third
                    # give-up stays in the honest RuntimeError shape.
                    time.sleep(3)
                    if attempt == 3:
                        raise RuntimeError(
                            "engine busy: another surface is speaking")
                    continue
                try:
                    w.stdin.write(json.dumps(req) + "\n")
                    w.stdin.flush()
                    rd, _, _ = select.select([w.stdout], [], [], SYNTH_TIMEOUT)
                    if not rd:
                        raise TimeoutError("engine busy over %s s" % SYNTH_TIMEOUT)
                    line = w.stdout.readline()
                    if not line:
                        raise OSError("worker died mid-request")
                    res = json.loads(line)
                    if not res.get("ok"):
                        # ENGINE'S OWN REFUSAL (e.g. silence guard): the worker
                        # is healthy — respawning it would pay a full model
                        # reload (30-90 s at all-core, the owner's CPU-spike
                        # complaint 2026-11-10). Keep it; the rotated draw
                        # below rides the same warm model.
                        raise ProtocolRefusal(res.get("error") or "runner refused")
                    _LAST_OK = time.time()
                    return
                except Exception as exc:
                    if not isinstance(exc, ProtocolRefusal):
                        try:
                            w.kill()
                        except OSError:
                            pass
                        _WORKER = None
                    if attempt == 3:
                        raise RuntimeError("engine failed: " + str(exc)[:400]) from exc
                    # Rotate BOTH knobs for the retry. The sampler seed alone
                    # proved insufficient (measured 2026-11-10: one test line
                    # drew digital silence 3/3 across argv-level seed changes —
                    # neutts 1.4.1 has no per-call backbone seed hook, only a
                    # constructor arg). temperature rides the per-request
                    # generate() call and therefore ALWAYS changes the draw;
                    # the decode is not all-zero-safe, so token diversity is
                    # the recovery lever. (f) class bug: some generations
                    # decode to zeros; a fresh, hotter draw is the cheap fix.
                    req["seed"] = (int(req.get("seed", 42)) + 7919) % (2 ** 31)
                    req["temperature"] = round(
                        float(req.get("temperature", 0.7)) + 0.15, 2)
        finally:
            _QUEUED = max(0, _QUEUED - 1)
            _NEXT += 1
            _READY.notify_all()


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
    eff = effective_device()
    argv = [str(_root() / ("venv_cu" if eff == "cuda" else "venv") / "bin" / "python"),
            str(_root() / "air_runner.py"),
            "--device", eff,
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


def _sentences(text: str, limit: int = 120) -> list:
    """Sentence chunks <=limit chars (the model's 2048-token KV window caps a
    single generation at ~120 chars of speech — measured: full-text passes die
    at llama_decode with the context-ceiling warning). Greedy pack on sentence
    ends; a single monster sentence passes through alone."""
    import re
    chunks = []
    for para in text.split("\n\n"):
        buf = ""
        for sent in re.split(r"(?<=[.!?]) +", para.strip().replace("\n", " ")):
            if not sent:
                continue
            if buf and len(buf) + 1 + len(sent) > limit:
                chunks.append(buf)
                buf = sent
            else:
                buf = (buf + " " + sent).strip()
        if buf:
            chunks.append(buf)
    return chunks


def synth(text: str, card: Optional[dict], speed: float) -> tuple:
    """voiceout.register_synth target: (wav_bytes, rate) out. Raises with a NAMED
    cause — never returns silence pretending to be speech. Text over the KV
    ceiling is chunked per sentence and stitched with the demo-proven 30 ms
    prosodic pause — the aligner runs on the STITCHED wav (voiceout.synthesize),
    so word stamps stay monotonic across the seams."""
    probe = _probe()
    if not probe["installed"]:
        raise RuntimeError("NeuTTS Air not installed (pane ▸ Voice-out ▸ install)")
    if card is None:
        raise RuntimeError("no voice card bound — record one or fetch the seed pack; "
                           "zero-shot cloning needs reference audio")
    voiceout = _voiceout()
    cid = str(card.get("card_id") or "")
    if not voiceout._CARD_KEY_RE.fullmatch(cid):
        raise RuntimeError(f"card id {cid!r} is not a safe filename")
    ref_wav = voiceout._root() / voiceout.CARDS_DIR / f"{cid}.wav"
    if not ref_wav.is_file():
        raise RuntimeError(f"card {card['card_id']} has no reference wav on disk")
    ref_text = card.get("transcript") or ""
    if not ref_text:
        raise RuntimeError(f"card {card['card_id']} has no stored transcript — "
                           "zero-shot clone would be guessing the reference words")
    out = _root() / f"synth-{os.getpid()}-{id(threading.current_thread())}.wav"
    # UNIQUE per call: two synths on the fixed name raced (killed client's
    # server-side thread keeps writing after the POST dies — measured 500 via
    # take-6). Siblings (.txt/.ref.txt from _synth_argv) are removed with it.
    rate, pcm = 0, b""
    try:
        for i, chunk in enumerate(_sentences(text)):
            argv = _synth_argv(chunk, ref_wav, ref_text, out)
            def arg(flag):
                return argv[argv.index(flag) + 1]
            # Seed varies by DRAW TEXT (not a fixed 42): a hand test 2026-11-10
            # proved (seed 42, temp 0.7) decodes to zeros for this owner's card
            # while 77/123 speak — a fixed first draw turns a sampler basin into
            # a guaranteed 3/3 failure for whichever line starts there. crc32
            # keeps each sentence's draw stable across caches and restarts.
            import zlib
            _worker_request({"text_file": arg("--text-file"),
                             "ref_wav": arg("--ref-wav"),
                             "ref_text": arg("--ref-text"),
                             "out": arg("--out"),
                             "seed": zlib.crc32(chunk.encode()) % 65536})
            r, p = _pcm_from_wav(out.read_bytes())
            if i:
                pcm += b"\x00\x00" * (r * 30 // 1000)   # 30 ms seam gap (demo-proven)
            rate, pcm = r, pcm + p
    finally:
        for sib in (out, out.with_suffix(".txt"), out.with_suffix(".ref.txt")):
            sib.unlink(missing_ok=True)   # leaves no litter (rule 17 neighbours)
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
    _boot_warm()
    return True


def _boot_warm() -> None:
    """RESIDENT doctrine (owner 2026-11-10): load the model at gateway boot, not
    at first speak — and never unload it (the reaper is off by default). Voice
    switching is FREE: the card is a per-request reference (zero-shot), so the
    loaded model serves every voice without a reload; only a MODEL swap re-loads.
    Fire-and-forget on the first configured card; no cards = nothing to clone,
    the worker arrives with the first real synth."""
    def _warm():
        try:
            cards = (_voiceout().read_prefs().get("cards") or {})
            if not cards:
                return
            cid, meta = next(iter(cards.items()))
            synth("Ready.", dict(meta, card_id=cid), 1.0)
        except Exception:
            pass   # silent: the first real speak retries the warm
    threading.Thread(target=_warm, daemon=True, name="wearos-tts-bootwarm").start()


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
            v = store_token(a.token)              # whoami-validated, stored 0600
            if not v.get("ok"):
                raise SystemExit(f"--token: {v.get('error')}")
            print(f"token stored (HF account: {v['user']})")
        r = start(interactive=True, source=a.source, for_slot=a.slot)
        print(r)
        if r.get("started"):
            while _worker and _worker.is_alive():
                with STATUS:
                    print(f"  {_state['phase']}: {_state['detail']}")
                time.sleep(5)
            with STATUS:
                print("final:", _state["phase"], _state.get("error", ""))
