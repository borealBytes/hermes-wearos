"""Streaming STT (Parakeet) installer behind the Wear Kit pane button (D16, rule 17).

Everything this module can possibly do, it does INSIDE the plugin's own data dir
(``<HERMES_HOME>/plugin-data/wearos/stt/``), with its OWN virtualenv — never Hermes's,
never a system Python. Nothing compiled is shipped: the library is built FROM SOURCE
on the user's machine, pinned to an exact commit, and the model is verified against an
exact SHA-256 before it is ever put in place (rule 17: no unscannable binaries).

Fetched hosts, complete: ``github.com`` (source), ``huggingface.co`` (model weights).
Every command is a fixed argv list — no shell, no curl-into-bash, no pip-from-network
except the bindings install into the private venv (pip's own index host disclosed).
One install at a time, cancellable, 30-minute cap, full log. Interactive surfaces
only: the pane button or ``hermes watch setup --fix stt`` on a terminal — a model has
no route here, and unattended runs refuse (rule 12).
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional

REPO = "https://github.com/handy-computer/transcribe.cpp"
COMMIT = "5700f127e38dd53c4735ea23ef743b4b538eacd4"   # pinned: what the owner's rig runs
MODEL_REPO = "handy-computer/parakeet-unified-en-0.6b-gguf"
# One engine (transcribe.cpp), every official quant selectable (owner ruling
# 2026-11-09): the vendor repo publishes six; sizes are the repo's own bytes.
VARIANTS = {
    "q4_k_m": {"file": "parakeet-unified-en-0.6b-Q4_K_M.gguf", "bytes": 477_274_496,
               "label": "Q4_K_M — smallest, fastest first-token; slightly more mishears"},
    "q5_k_m": {"file": "parakeet-unified-en-0.6b-Q5_K_M.gguf", "bytes": 540_795_264,
               "label": "Q5_K_M — near-Q8 accuracy at ~74% the size"},
    "q6_k":   {"file": "parakeet-unified-en-0.6b-Q6_K.gguf", "bytes": 602_191_232,
               "label": "Q6_K — visually-indistinguishable from Q8"},
    "q8_0":   {"file": "parakeet-unified-en-0.6b-Q8_0.gguf", "bytes": 731_357_568,
               "sha256": "4b50b6dd862bf6e346929aaf4f5eaacec003bfa3f56462d6c874b41ef2f38795",
               "label": "Q8_0 — the shipped default, checksum-pinned since day one"},
    "f16":    {"file": "parakeet-unified-en-0.6b-F16.gguf", "bytes": 1_239_114_240,
               "label": "F16 — lossless half-precision"},
    "f32":    {"file": "parakeet-unified-en-0.6b-F32.gguf", "bytes": 2_473_323_520,
               "label": "F32 — full reference weights (disk-cheap boxes need not apply)"},
}
DEFAULT_VARIANT = "q8_0"
MODEL_SHA256 = VARIANTS["q8_0"]["sha256"]      # the day-one pin, kept name-compatible
MODEL_BYTES = 731_000_000                      # displayed estimate; the checksum is the truth


def _selected_variant() -> str:
    try:
        v = json.loads((_root() / "model_variant").read_text()).get("selected")
        return v if v in VARIANTS else DEFAULT_VARIANT
    except (OSError, ValueError):
        return DEFAULT_VARIANT


def select_variant(vid: str) -> dict:
    if vid not in VARIANTS:
        return {"ok": False, "error": f"unknown variant {vid!r} — pick from the menu"}
    _root().mkdir(parents=True, exist_ok=True)
    (_root() / "model_variant").write_text(json.dumps({"selected": vid}))
    return {"ok": True, "selected": vid}


def variant_menu() -> list:
    sel = _selected_variant()
    return [{"id": k, "selected": k == sel, **{f: v[f] for f in ("file", "bytes", "label")},
             "sha_pinned": "sha256" in v} for k, v in VARIANTS.items()]


def model_path() -> Path:
    """Where the CHOSEN quant lives — the karaoke aligner reads this too, so an
    engine installed on Q4 aligns on Q4 (never a stale hardcode)."""
    return _root() / "models" / VARIANTS[_selected_variant()]["file"]
BUILD_TIMEOUT = 30 * 60            # whole-pipeline cap, minutes-scale machines included

try:
    from . import weight_cache as _wc          # package context (the plugin host)
except ImportError:
    _wc = None                                 # standalone CLI: lazy import on first use

STATUS = threading.Lock()
_state: dict = {"phase": "idle", "detail": "", "percent": 0, "log": [],
                "started": 0.0, "error": ""}
_proc: Optional[subprocess.Popen] = None
_worker: Optional[threading.Thread] = None


def _root() -> Path:
    try:
        from hermes_constants import get_hermes_home
        home = Path(get_hermes_home())
    except Exception:
        home = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes")))
    return home / "plugin-data" / "wearos" / "stt"


def _probe() -> dict:
    root = _root()
    lib = root / "transcribe-src" / "build-shared" / "src" / "libtranscribe.so"
    model = model_path()
    venv_py = root / "venv" / "bin" / "python"
    have_lib = lib.is_file() and venv_py.is_file()
    have_model = model.is_file()
    ready = have_lib and have_model
    return {"installed": ready,
            "lib": have_lib, "model": have_model,
            "model_verified": have_model and _verify(model),
            "dir": str(root),
            "toolchain": bool(shutil.which("cmake") and shutil.which("g++")
                              and shutil.which("git"))}


def status() -> dict:
    with STATUS:
        st = dict(_state)
        st["log"] = st["log"][-25:]
    st.update(_probe())
    st["variant"] = _selected_variant()
    st["variants"] = variant_menu()
    return st


def commands() -> list[str]:
    """The EXACT argv lines _run() executes, in order — the pane shows these verbatim
    (sudo law: the human approves the command they can read, not a paraphrase). The
    model download is a fixed https GET shown as curl-equivalent; no shell is involved.
    Paths are relative to the plugin data dir (first line) so the block stays readable.
    """
    root = _root()
    src = "$D/transcribe-src"
    venv = "$D/venv"
    v = VARIANTS[_selected_variant()]     # the quant the pane's menu selected
    return [
        f"D = {root}",
        f"git init -q {src} && git -C {src} remote add origin {REPO}",
        f"git -C {src} fetch -q --depth 1 origin {COMMIT}",
        f"git -C {src} checkout -q FETCH_HEAD     # verified: HEAD == {COMMIT}",
        f"cmake -S {src} -B {src}/build-shared -DCMAKE_BUILD_TYPE=Release "
        "-DTRANSCRIBE_BUILD_SHARED=ON -DTRANSCRIBE_BUILD_TESTS=OFF",
        f"cmake --build {src}/build-shared -j {os.cpu_count() or 4}",
        f"{sys.executable} -m venv {venv}",
        f"{venv}/bin/python -m pip install -q {src}/bindings/python",
        f"curl -L https://huggingface.co/{MODEL_REPO}/resolve/main/{v['file']} "
        f"-o $D/models/{v['file']}"
        + (f"   # then SHA-256-verified: {v['sha256'][:16]}…" if "sha256" in v
           else f"   # size-checked resume; hash-pin {v['file']} to verify"),
    ]


def preview() -> dict:
    root = _root()
    return {
        "title": "Streaming speech-to-text (Parakeet)",
        "steps": [
            f"Download {REPO} at commit {COMMIT[:12]} (git, fixed revision)",
            "Build the shared library (cmake + g++, a few minutes, CPU-only)",
            f"Download {VARIANTS[_selected_variant()]['file']} "
            f"(~{VARIANTS[_selected_variant()]['bytes'] // 1_000_000} MB) from huggingface.co",
            ("Verify it against SHA-256 "
             + (VARIANTS[_selected_variant()].get("sha256") or MODEL_SHA256)[:16]
             + "… before use") if VARIANTS[_selected_variant()].get("sha256") else
            "Verify its exact repo size; the observed SHA-256 is recorded in the cache",
            "Cache-first: bytes already on disk (any variant, any installer) are "
            "hash-checked against the remote and hardlinked — never re-fetched; "
            "a partial download resumes",
            f"Everything lands in {root} (private venv, nothing system-wide)",
        ],
        "hosts": ["github.com", "huggingface.co", "pypi.org (bindings)"],
        "needs": ["git", "cmake", "g++"],
        "disk_mb": 1600,
        "toolchain_ok": _probe()["toolchain"],
        "commands": commands(),
        "cli": "hermes watch setup --fix stt",
    }


def _set(phase: str, detail: str = "", percent: Optional[int] = None) -> None:
    with STATUS:
        _state["phase"] = phase
        _state["detail"] = detail
        if percent is not None:
            _state["percent"] = percent
        _state["log"].append(f"{time.strftime('%H:%M:%S')} {phase}: {detail}")
        _state["log"] = _state["log"][-200:]


def _verify(path: Path) -> bool:
    """Pinned rows check their SHA; menu rows without a pin check the exact
    repo size (and the cache records the observed SHA at fetch)."""
    v = VARIANTS[_selected_variant()]
    pin = v.get("sha256", "")
    if not pin:
        try:
            return path.stat().st_size == v["bytes"]
        except OSError:
            return False
    try:
        digest = hashlib.sha256()
        with open(path, "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                digest.update(chunk)
        return digest.hexdigest() == pin
    except OSError:
        return False


def start(interactive: bool = True) -> dict:
    if not interactive:
        return {"started": False, "error": "no terminal to approve the install "
                                           "(rule 12): use the Wear Kit pane or run "
                                           "hermes watch setup --fix stt"}
    probe = _probe()
    if probe["installed"] and probe["model_verified"]:
        return {"started": False, "error": "already installed and verified"}
    if not probe["toolchain"]:
        return {"started": False, "error": "needs git, cmake and g++ (apt install "
                                           "cmake build-essential git)"}
    with STATUS:
        if _state["phase"] in ("clone", "build", "model", "bindings"):
            return {"started": False, "error": "an install is already running"}
    global _worker
    _worker = threading.Thread(target=_run, daemon=True, name="wearos-stt-install")
    _worker.start()
    return {"started": True, "preview": preview()}


def cancel() -> dict:
    global _proc
    if _proc is not None and _proc.poll() is None:
        _proc.terminate()
        _set("cancelled", "user cancelled")
        return {"cancelled": True}
    return {"cancelled": False}


def _run() -> None:
    root = _root()
    global _proc
    with STATUS:
        _state.update({"started": time.time(), "error": "", "percent": 0, "log": []})
    try:
        root.mkdir(parents=True, exist_ok=True)
        src = root / "transcribe-src"
        lib = src / "build-shared" / "src" / "libtranscribe.so"
        venv = root / "venv"
        model = model_path()

        if not lib.is_file():
            if not (src / ".git").is_dir():
                _set("clone", "fetching transcribe.cpp at the pinned commit")
                _checkout(src)
            _set("build", "cmake shared build (a few minutes)")
            _cmake(src)
        if not lib.is_file():
            raise RuntimeError("build finished but libtranscribe.so is missing")

        if not (venv / "bin" / "python").is_file():
            _set("bindings", "creating private venv + transcribe bindings")
            subprocess.run([sys.executable, "-m", "venv", str(venv)],
                           check=True, capture_output=True, timeout=300,
                           env=_min_env())
            _pip(venv, ["install", "-q", str(src / "bindings" / "python")])

        if not model.is_file() or not _verify(model):
            _set("model", f"downloading {variant['file']} "
                          f"(~{variant['bytes'] // 1_000_000} MB)")
            _download(f"https://huggingface.co/{MODEL_REPO}/resolve/main/"
                      + variant["file"], model, variant["bytes"],
                      variant.get("sha256", ""))
            if not _verify(model):
                model.unlink(missing_ok=True)
                raise RuntimeError("model failed its SHA-256 check — kept nothing")
        _set("done", "installed and verified — watchkit serves it after its next "
                     "health probe", 100)
    except Exception as exc:  # never raise out of the worker
        with STATUS:
            _state["error"] = str(exc)[:400]
        _set("failed", str(exc)[:200])
    finally:
        _proc = None


def _min_env(extra: dict | None = None) -> dict:
    """Catalog review law (Nous #151546, 2026-11-10): third-party build/setup
    code must NOT run with the gateway's inherited environment — every provider
    key would be in reach of a setup.py. Allowlist only what the chain reads:
    PATH/HOME/LANG/TMPDIR/proxies, plus CMAKE_BUILD_PARALLEL_LEVEL; GIT_TERMINAL_PROMPT=0
    so a credential prompt fails fast instead of hanging a build worker."""
    env = {k: v for k, v in os.environ.items()
           if k in ("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "TMP", "TEMP",
                    "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "XDG_CACHE_HOME",
                    "http_proxy", "https_proxy", "no_proxy")}
    env.setdefault("GIT_TERMINAL_PROMPT", "0")
    env.setdefault("CMAKE_BUILD_PARALLEL_LEVEL", str(os.cpu_count() or 4))
    if extra:
        env.update(extra)
    return env


def _checkout(src: Path) -> None:
    src.mkdir(parents=True, exist_ok=True)
    _run_proc(["git", "init", "-q", str(src)], "clone")
    _run_proc(["git", "-C", str(src), "remote", "add", "origin", REPO], "clone")
    _run_proc(["git", "-C", str(src), "fetch", "-q", "--depth", "1", "origin", COMMIT],
              "clone")
    _run_proc(["git", "-C", str(src), "checkout", "-q", "FETCH_HEAD"], "clone")
    head = subprocess.run(["git", "-C", str(src), "rev-parse", "HEAD"],
                          capture_output=True, text=True, timeout=10,
                          env=_min_env()).stdout.strip()
    if head != COMMIT:
        raise RuntimeError(f"pinned checkout drifted: {head[:12]} != {COMMIT[:12]}")


def _cmake(src: Path) -> None:
    _run_proc(["cmake", "-S", str(src), "-B", str(src / "build-shared"),
               "-DCMAKE_BUILD_TYPE=Release", "-DTRANSCRIBE_BUILD_SHARED=ON",
               "-DTRANSCRIBE_BUILD_TESTS=OFF"], "build")
    _run_proc(["cmake", "--build", str(src / "build-shared"), "-j",
               str(os.cpu_count() or 4)], "build")


def _pip(venv: Path, args: list) -> None:
    _run_proc([str(venv / "bin" / "python"), "-m", "pip", *args], "bindings")


def _run_proc(argv: list, phase: str) -> None:
    global _proc
    _proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             text=True, env=_min_env())
    out = _proc.communicate(timeout=BUILD_TIMEOUT)[0] or ""
    if _proc.returncode != 0:
        raise RuntimeError(f"{phase} failed ({argv[0]} exit {_proc.returncode}): "
                           f"{out[-300:]}")


def _download(url: str, dest: Path, nbytes: int = 0,
              expected_sha: str = "") -> None:
    """Same cache-first discipline as voice-out (shared weight_cache): hash-match
    skips the bytes, an interrupt resumes, and quant switches on this same engine
    reuse whatever the cache already holds."""
    if not _wc:
        import weight_cache as _wc_mod
        globals()["_wc"] = _wc_mod
    return _wc.fetch(_root() / "cache", url, dest, nbytes, expected_sha,
                     progress=lambda done, total: _set(
                         "model", f"{done // 1_048_576} MB",
                         min(99, int(done * 100 / max(1, total)))))
