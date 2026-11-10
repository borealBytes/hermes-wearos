"""weight_cache.py — the kit's ONE weight-download discipline (owner ruling 2026-11-09).

Big model files are fetched ONCE into a shared per-installer cache keyed by
<org>--<repo>--<file>; a finished entry whose recorded size/SHA agrees with the
remote (HEAD: CDN content-length + x-linked-etag) is HARDLINKED to the runtime
path, never re-fetched; an interrupted download RESUMES via a Range request and
keeps the partial. Both installers use it: stt_install (Parakeet, voice-in) and
tts_air (NeuTTS Air, voice-out) — and any switch of quant/variant on the same
engine rides the same cache. stdlib only (rule 10); no network in unit tests
(the fetcher takes the urlopen it uses).
"""
import hashlib
import json
import os
import urllib.error
import urllib.request
from pathlib import Path
from typing import Callable, Optional

UA = "hermes-wearkit/0.3"


def key_for(url: str) -> str:
    """https://huggingface.co/<org>/<repo>/resolve/main/<file> -> '<org>--<repo>--<file>';
    anything else falls back to the last path segment."""
    parts = url.split("?")[0].split("/")
    try:
        i = parts.index("huggingface.co")
        return "--".join(parts[i + 1:i + 3]) + "--" + parts[-1]
    except ValueError:
        return parts[-1]


def _index_path(home: Path) -> Path:
    return home / "index.json"


def load_index(home: Path) -> dict:
    try:
        return json.loads(_index_path(home).read_text())
    except (OSError, ValueError):
        return {}


def record(home: Path, key: str, meta: dict) -> None:
    idx = load_index(home)
    idx[key] = meta
    home.mkdir(parents=True, exist_ok=True)
    tmp = _index_path(home).with_suffix(".json.part")
    tmp.write_text(json.dumps(idx, indent=1))
    os.replace(tmp, _index_path(home))


def _sha_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _headers(token: str) -> dict:
    h = {"User-Agent": UA}
    if token:
        h["Authorization"] = "Bearer " + token
    return h


def remote_matches(home: Path, url: str, nbytes: int, token: str = "",
                   openner=urllib.request.urlopen) -> bool:
    """True only when the remote answer agrees with what the cache says it holds
    (bytes always; SHA/etag when recorded). Any network doubt answers False —
    re-verify by fetching, never a silent yes."""
    meta = load_index(home).get(key_for(url)) or {}
    try:
        req = urllib.request.Request(url, headers=_headers(token), method="HEAD")
        with openner(req, timeout=30) as r:
            size = int(r.headers.get("Content-Length") or 0)
            etag = (r.headers.get("x-linked-etag") or r.headers.get("etag")
                    or "").strip('"W/')
    except Exception:
        return False
    want = nbytes or int(meta.get("bytes") or 0)
    if want and size != want:
        return False
    if meta.get("sha256") and etag:
        return etag in (meta["sha256"], meta.get("etag"))
    return bool(meta.get("sha256") or meta.get("etag"))


def fetch(home: Path, url: str, dest: Path, nbytes: int = 0,
          expected_sha: str = "", token: str = "",
          progress: Optional[Callable[[int, int], None]] = None,
          denied: Optional[Callable[[int], str]] = None,
          openner=urllib.request.urlopen) -> dict:
    """Cache-first fetch. Returns {"bytes": n, "sha256": hex, "cache_hit": bool}.
    raises RuntimeError (never HTTPError) on consent (401/403 via denied()),
    SHA mismatch (keeps nothing), or a short stream (keeps the partial to resume)."""
    key = key_for(url)
    cache = home / key
    if cache.is_file() and (not nbytes or cache.stat().st_size == nbytes) \
            and remote_matches(home, url, nbytes, token, openner):
        dest.parent.mkdir(parents=True, exist_ok=True)
        if dest.exists():
            dest.unlink()
        try:
            os.link(cache, dest)
        except OSError:
            import shutil as _sh
            _sh.copy2(cache, dest)              # cross-device cache: honest copy
        return {"bytes": cache.stat().st_size,
                "sha256": load_index(home).get(key, {}).get("sha256", ""),
                "cache_hit": True}
    home.mkdir(parents=True, exist_ok=True)
    part = cache.with_suffix(cache.suffix + ".part")
    start = part.stat().st_size if part.is_file() else 0
    headers = _headers(token)
    mode = "ab"
    if start:
        headers["Range"] = f"bytes={start}-"
    req = urllib.request.Request(url, headers=headers)
    done = start
    try:
        with openner(req, timeout=60) as resp:
            crange = resp.headers.get("Content-Range") or ""
            if start and " " in crange:                      # bytes N/TOTAL
                resume_ok = crange.split(" ")[1].split("-")[0] == str(start)
                total = int(crange.rsplit("/", 1)[1])
            else:
                resume_ok = start == 0
                total = int(resp.headers.get("Content-Length") or nbytes or 0)
            if not resume_ok:
                start, done, mode = 0, 0, "wb"   # Range ignored: restart clean
            with open(part, mode) as fh:
                while True:
                    chunk = resp.read(1 << 20)
                    if not chunk:
                        break
                    fh.write(chunk)
                    done += len(chunk)
                    if progress:
                        progress(done, total or nbytes or 1)
            if total and done != total:
                raise RuntimeError(f"stream ended at {done}/{total} bytes — "
                                   "run install again to resume")
    except urllib.error.HTTPError as exc:
        if exc.code in (401, 403) and denied:
            raise RuntimeError(denied(exc.code)) from None
        if exc.code != 416:                     # 416: the partial IS the whole file
            raise
    observed = _sha_of(part)
    if expected_sha and expected_sha not in ("", "PENDING") and observed != expected_sha:
        part.unlink(missing_ok=True)
        raise RuntimeError("download failed its SHA-256 check — kept nothing")
    record(home, key, {"bytes": done, "sha256": observed, "url": url})
    os.replace(part, cache)
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        dest.unlink()
    try:
        os.link(cache, dest)
    except OSError:
        import shutil as _sh
        _sh.copy2(cache, dest)
    return {"bytes": done, "sha256": observed, "cache_hit": False}
