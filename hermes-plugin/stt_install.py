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
MODEL_FILE = "parakeet-unified-en-0.6b-Q8_0.gguf"
MODEL_URL = f"https://huggingface.co/{MODEL_REPO}/resolve/main/{MODEL_FILE}"
MODEL_SHA256 = "4b50b6dd862bf6e346929aaf4f5eaacec003bfa3f56462d6c874b41ef2f38795"
MODEL_BYTES = 731_000_000          # displayed estimate; the checksum is the truth
BUILD_TIMEOUT = 30 * 60            # whole-pipeline cap, minutes-scale machines included

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
    model = root / "models" / MODEL_FILE
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
        f"curl -L {MODEL_URL} -o $D/models/{MODEL_FILE}   # then SHA-256-verified: "
        f"{MODEL_SHA256[:16]}…",
    ]


def preview() -> dict:
    root = _root()
    return {
        "title": "Streaming speech-to-text (Parakeet)",
        "steps": [
            f"Download {REPO} at commit {COMMIT[:12]} (git, fixed revision)",
            "Build the shared library (cmake + g++, a few minutes, CPU-only)",
            f"Download {MODEL_FILE} (~{MODEL_BYTES // 1_000_000} MB) from huggingface.co",
            f"Verify it against SHA-256 {MODEL_SHA256[:16]}… before use",
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
    try:
        digest = hashlib.sha256()
        with open(path, "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                digest.update(chunk)
        return digest.hexdigest() == MODEL_SHA256
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
        model = root / "models" / MODEL_FILE

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
                           check=True, capture_output=True, timeout=300)
            _pip(venv, ["install", "-q", str(src / "bindings" / "python")])

        if not model.is_file() or not _verify(model):
            _set("model", f"downloading {MODEL_FILE} (~731 MB)")
            _download(MODEL_URL, model)
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


def _checkout(src: Path) -> None:
    src.mkdir(parents=True, exist_ok=True)
    _run_proc(["git", "init", "-q", str(src)], "clone")
    _run_proc(["git", "-C", str(src), "remote", "add", "origin", REPO], "clone")
    _run_proc(["git", "-C", str(src), "fetch", "-q", "--depth", "1", "origin", COMMIT],
              "clone")
    _run_proc(["git", "-C", str(src), "checkout", "-q", "FETCH_HEAD"], "clone")
    head = subprocess.run(["git", "-C", str(src), "rev-parse", "HEAD"],
                          capture_output=True, text=True, timeout=10).stdout.strip()
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
                             text=True)
    out = _proc.communicate(timeout=BUILD_TIMEOUT)[0] or ""
    if _proc.returncode != 0:
        raise RuntimeError(f"{phase} failed ({argv[0]} exit {_proc.returncode}): "
                           f"{out[-300:]}")


def _download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": "hermes-wearkit/0.2"})
    with urllib.request.urlopen(req, timeout=60) as resp, open(tmp, "wb") as fh:
        total = int(resp.headers.get("Content-Length") or MODEL_BYTES)
        done = 0
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            fh.write(chunk)
            done += len(chunk)
            _set("model", f"{done // 1_048_576} MB", min(99, int(done * 100 / max(1, total))))
    os.replace(tmp, dest)
