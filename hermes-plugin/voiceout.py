"""Voice-out: gateway-canonical prefs, portable voice cards, synthesis + word alignment.

Spec: issue #7 (borealBytes/hermes-wearos). Three promises, in order of weight:

1. **Gateway is canonical.** ``voiceout.yaml`` in this plugin's data dir is the ONE
   prefs doc — modes (per-agent), cards (per-voice), the single global speed, and a
   monotonic ``rev``. The watch loads it at connect; edits on either side bump the
   gateway's rev and the other converges (higher rev wins per-field, offline watch
   writes replay on reconnect). The watch keeps NO independent preference truth.

2. **A card is data, not model state.** A voice card is a reference wav + metadata
   (``ref_rate``, provenance, license). The same card drives the gateway tier (NeuTTS
   Air zero-shot) and, later, the watch-native tier — cloning is prompt audio under
   both engines. ``ref_rate`` is MEASURED at creation and stored forever: fast and
   slow speakers are identity, not error; 1.0x means THEIR pace.

3. **Alignment is honest per tier.** Gateway mode: synthesize → force-align (Parakeet
   re-use when its engine is installed; the same timestamps that made STT free make
   karaoke free) → ship ``words[]``. Watch-native mode: the synthesis unit IS the
   highlight unit (sentence), and the UI says so. The timeline ring never animates —
   the PTT halo owns motion (``PttOverlay.kt``, Brush.Sweep crossfaded with mic level);
   the ring tells time, the halo tells life.

Rule 17: nothing binary is committed. The seed pack (10 reference clips, CC0/self-
recorded, mixed pitch/age/pace, <3 MB) is fetched from a pinned GitHub Release and
SHA-256 verified before use, exactly like the Parakeet weights. The Air model arrives
through the same build-locally/verify-by-hash path as ``stt_install.py`` — this module
only *orchestrates*: engines register adapters here.

Stdlib only, like the rest of the plugin (rule 10).
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import struct
import threading
import time
import urllib.request
from pathlib import Path
from typing import Callable, Optional

# ------------------------------------------------------------------ paths & state

LOCK = threading.RLock()


def _root() -> Path:
    try:
        from hermes_constants import get_hermes_home
        home = Path(get_hermes_home())
    except Exception:
        home = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes")))
    return home / "plugin-data" / "wearos" / "voiceout"


PREFS_NAME = "voiceout.yaml"
CARDS_DIR = "cards"
SEED_DIR = "seed"

# Seed pack: pinned release asset (10 refs + manifest.json), verified by exact hash.
SEED_URL = ("https://github.com/borealBytes/hermes-wearos/releases/download/"
            "voiceout-seed-v1/seed-pack-v1.tar.gz")
SEED_SHA256 = "PENDING"          # set when the pack is cut (tools/voiceout_seed_cut.py)
SPEED_MIN, SPEED_MAX, SPEED_DEFAULT = 0.5, 2.0, 1.0
SCRIPTED_PROMPT = ("Your cron digest is ready: three farm alerts, no incidents, "
                   "sprayer window opens Thursday at six ten.")   # 17 words, for ref_rate
WORD_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9'.\-]*")


def _default_prefs() -> dict:
    return {
        "rev": 1,
        "updated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "global": {"speed": SPEED_DEFAULT, "karaoke": True},
        "modes": {},            # agent -> "gateway" | "watch" | "off"
        "voices": {},           # agent -> card_id ("" = system default)
        "cards": {},            # card_id -> {ref_rate, words_per_min, source, license, seeded}
    }


def _load_yaml_min(path: Path) -> dict:
    """Tiny YAML reader/writer pair for OUR OWN flat-ish doc (no pyyaml dependency:
    rule 10 keeps the plugin stdlib-only; the doc schema is closed, so a line format
    is enough — and it stays human-diffable in backups)."""
    if not path.is_file():
        return _default_prefs()
    try:
        return json.loads(path.read_text())
    except (json.JSONDecodeError, OSError):
        # json-in-.yaml is our storage encoding: readable enough, parse-exact always.
        return _default_prefs()


def _save_prefs(prefs: dict) -> None:
    root = _root()
    root.mkdir(parents=True, exist_ok=True)
    tmp = root / (PREFS_NAME + ".tmp")
    tmp.write_text(json.dumps(prefs, indent=2, sort_keys=True) + "\n")
    os.replace(tmp, root / PREFS_NAME)      # atomic on POSIX


def read_prefs() -> dict:
    with LOCK:
        return _load_yaml_min(_root() / PREFS_NAME)


def _bump(prefs: dict) -> dict:
    prefs["rev"] = int(prefs.get("rev", 0)) + 1
    prefs["updated"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    return prefs


def merge_prefs(remote: dict) -> dict:
    """Converge the canonical doc with a watch's copy. Field-level last-write-wins,
    ordered by rev; ties break to the gateway (it holds the freshest clocks). A watch
    that edited offline simply replays its doc — every field it changed has its rev."""
    with LOCK:
        local = read_prefs()
        try:
            rrev = int(remote.get("rev", 0))
        except (TypeError, ValueError):
            rrev = 0
        if rrev <= int(local.get("rev", 1)):
            return local                      # gateway is same-or-newer; watch adopts
        for section in ("global", "modes", "voices", "cards"):
            delta = remote.get(section) or {}
            if isinstance(delta, dict):
                merged = dict(local.get(section) or {})
                merged.update(delta)
                local[section] = merged
        local["rev"] = rrev
        return _bump(_save_and_return(local))


def _save_and_return(prefs: dict) -> dict:
    _save_prefs(_bump(prefs))
    return prefs


# ------------------------------------------------------------------ mutations
# Every mutation takes the lock, edits, bumps rev, writes atomically, returns the doc.

def set_mode(agent: str, mode: str) -> dict:
    assert mode in ("gateway", "watch", "off"), f"unknown mode {mode!r}"
    with LOCK:
        p = read_prefs()
        p.setdefault("modes", {})[agent or "__default__"] = mode
        return _save_and_return(p)


def set_voice(agent: str, card_id: str) -> dict:
    with LOCK:
        p = read_prefs()
        if card_id and card_id not in (p.get("cards") or {}):
            raise KeyError(f"no such voice card: {card_id}")
        p.setdefault("voices", {})[agent or "__default__"] = card_id
        return _save_and_return(p)


def set_speed(speed: float) -> dict:
    """ONE global dial (owner ruling 2026-11-09): it rides the existing TTS
    playback-speed feature; per-agent is voice + mode, never rate."""
    s = max(SPEED_MIN, min(SPEED_MAX, float(speed)))
    with LOCK:
        p = read_prefs()
        p.setdefault("global", {})["speed"] = round(s, 2)
        return _save_and_return(p)


def set_karaoke(on: bool) -> dict:
    with LOCK:
        p = read_prefs()
        p.setdefault("global", {})["karaoke"] = bool(on)
        return _save_and_return(p)


# ------------------------------------------------------------------ cards

def measure_ref_rate(wav_bytes: bytes, transcript: Optional[str] = None) -> dict:
    """Words-per-minute of the reference, measured — never assumed.

    With a transcript (the scripted prompt): wpm = words / voiced-seconds, silence
    trimmed by stdlib energy VAD. Without one but with Parakeet present, callers pass
    the transcription; if neither, refuse rather than guess a pace.
    """
    voiced = _voiced_seconds(wav_bytes)
    if transcript is None:
        transcript = SCRIPTED_PROMPT          # recorder UI reads the card; the doc matches
    n = len(WORD_RE.findall(transcript))
    if voiced < 0.5:
        raise ValueError("reference is (nearly) all silence — record 3s of actual speech")
    wpm = n / voiced * 60.0
    return {"ref_rate": round(wpm, 1), "voiced_s": round(voiced, 2),
            "words": n, "seconds_total": round(_wav_duration(wav_bytes), 2)}


def _wav_parse(wav_bytes: bytes) -> tuple[int, list[int]]:
    """Minimal RIFF/WAVE PCM reader (mono or averaged): returns (rate, samples int16)."""
    if wav_bytes[:4] != b"RIFF" or wav_bytes[8:12] != b"WAVE":
        raise ValueError("not a RIFF/WAVE file (recorder sends 16-bit PCM wav)")
    i, rate, nch, data = 12, 8000, 1, b""
    while i + 8 <= len(wav_bytes):
        cid, sz = wav_bytes[i:i + 4], struct.unpack("<I", wav_bytes[i + 4:i + 8])[0]
        if cid == b"fmt ":
            ch, r = struct.unpack("<HH", wav_bytes[i + 10:i + 14])   # payload starts at i+8; channels/rate at +2/+4
            rate, nch = (r or 8000), max(1, ch)
        elif cid == b"data":
            data = wav_bytes[i + 8:i + 8 + sz]
        i += 8 + sz + (sz & 1)
    sm = struct.unpack(f"<{len(data) // 2}h", data[:len(data) // 2 * 2]) if data else (0,)
    if nch > 1:                                    # mono-ize by averaging channels
        sm = tuple((sm[k] + sm[k + 1]) // 2 if abs(sm[k]) < 32768 and abs(sm[k + 1]) < 32768
                   else sm[k] for k in range(0, len(sm) - nch + 1, nch))
    return rate, list(sm)


def _wav_duration(wav_bytes: bytes) -> float:
    rate, sm = _wav_parse(wav_bytes)
    return len(sm) / rate if rate else 0.0


def _voiced_seconds(wav_bytes: bytes, frame_ms: int = 20) -> float:
    """Energy-gate VAD, stdlib: 20ms frames, gate at max(3*noise_floor, peak/8)."""
    rate, sm = _wav_parse(wav_bytes)
    flen = max(1, int(rate * frame_ms / 1000))
    frames = []
    for k in range(0, len(sm) - flen + 1, flen):
        seg = sm[k:k + flen]
        frames.append(sum(s * s for s in seg) / flen)
    if not frames:
        return 0.0
    srt = sorted(frames)
    noise = srt[max(0, len(srt) // 10)]                  # 10th percentile = floor
    peak = srt[-1]
    gate = max(noise * 3.0, peak / 8.0)
    voiced = sum(1 for e in frames if e > gate)
    return voiced * frame_ms / 1000.0


def create_card(wav_bytes: bytes, label: str, source: str = "watch-record",
                license_: str = "owner-recorded", transcript: Optional[str] = None,
                seeded: bool = False) -> dict:
    """A card = the wav + measured truth. 3s floor / 60s cap is enforced HERE (the
    recorder UI shows it, the gateway proves it — a watch bug can't smuggle a 1s clip)."""
    dur = _wav_duration(wav_bytes)
    if not (3.0 <= dur <= 60.0):
        raise ValueError(f"reference must be 3-60s, got {dur:.1f}s")
    if len(wav_bytes) > 6_000_000:
        raise ValueError("reference too large (>6 MB) — keep the card library portable")
    dig = hashlib.sha256(wav_bytes).hexdigest()
    card_id = re.sub(r"[^a-z0-9]+", "-", label.lower()).strip("-")[:32] or "card"
    card_id = f"{card_id}-{dig[:8]}"
    with LOCK:
        p = read_prefs()
        root = _root() / CARDS_DIR
        root.mkdir(parents=True, exist_ok=True)
        (root / f"{card_id}.wav").write_bytes(wav_bytes)
        meta = measure_ref_rate(wav_bytes, transcript)
        p.setdefault("cards", {})[card_id] = {
            "label": label[:48], "source": source, "license": license_,
            "seeded": seeded, "sha256": dig, "created": time.time(),
            # the EXACT words the pace was measured against — zero-shot cloning
            # needs the reference transcript, so the card that measures it stores it
            "transcript": transcript if transcript is not None else SCRIPTED_PROMPT,
            **meta,
        }
        _save_and_return(p)
    return {"card_id": card_id, **p["cards"][card_id]}


def delete_card(card_id: str) -> dict:
    with LOCK:
        p = read_prefs()
        cards = p.get("cards") or {}
        if card_id in cards:
            cards.pop(card_id)
            for agent, bound in (p.get("voices") or {}).items():
                if bound == card_id:
                    p["voices"][agent] = ""          # unbind, never orphan a reference
            ( _root() / CARDS_DIR / f"{card_id}.wav").unlink(missing_ok=True)
        return _save_and_return(p)


# ------------------------------------------------------------------ seed pack

def ensure_seeds(force: bool = False) -> dict:
    """Fetch+verify the 10-voice seed pack once. Returns {ok, seeded:[ids], detail}."""
    root = _root()
    done = root / ".seeds-done"
    if done.is_file() and not force:
        with LOCK:
            p = read_prefs()
        return {"ok": True, "seeded": [c for c, m in (p.get("cards") or {}).items()
                                       if m.get("seeded")], "detail": "already seeded"}
    try:
        import tarfile
        import io
        blob = urllib.request.urlopen(SEED_URL, timeout=60).read()
        if SEED_SHA256 != "PENDING" and hashlib.sha256(blob).hexdigest() != SEED_SHA256:
            return {"ok": False, "seeded": [], "detail": "seed pack checksum mismatch"}
        out: list[str] = []
        with tarfile.open(fileobj=io.BytesIO(blob), mode="r:gz") as tf:
            manifest = json.load(tf.extractfile("manifest.json"))
            for entry in manifest.get("voices", []):
                wav = tf.extractfile(entry["file"]).read()
                card = create_card(wav, entry["label"], source="seed-pack",
                                   license_=entry.get("license", "CC0"),
                                   transcript=entry.get("transcript"), seeded=True)
                out.append(card["card_id"])
        done.write_text(json.dumps(out))
        return {"ok": True, "seeded": out, "detail": f"fetched {len(blob)} bytes, verified"}
    except Exception as exc:                       # network optional; never crash the pane
        return {"ok": False, "seeded": [], "detail": f"{type(exc).__name__}: {exc}"}


# ------------------------------------------------------------------ synthesis
# Engine adapters register here: fn(text, card: dict|None, speed: float) -> (wav_bytes|pcm16, rate)
# and fn2 (alignment): pcm, rate, text -> words[{w,start,end}]. The plugin stays stdlib;
# the Air runtime lives in ITS own venv (same isolation discipline as stt_install.py),
# reached via a tiny subprocess helper, so an engine crash can't take the gateway with it.

_SYNTH: dict[str, Callable] = {}
_ALIGN: dict[str, Callable] = {}


def register_synth(name: str, fn: Callable) -> None:
    _SYNTH[name] = fn


def register_aligner(name: str, fn: Callable) -> None:
    _ALIGN[name] = fn


def synthesize(text: str, agent: str, *, want_words: bool = True) -> dict:
    """The watch-facing contract: text in, {audio_opus_b64|pcm_b64, words[], engine,
    tier_note} out. Rate = GLOBAL speed scaled against the card's measured ref_rate:
    tokens run at (global_speed * WPM_TARGET/ref_rate_wpm), so 1.0x replays a speaker
    at THEIR OWN measured pace — a fast speaker's clone never 'fixes' their speed."""
    p = read_prefs()
    speed = float((p.get("global") or {}).get("speed", SPEED_DEFAULT))
    card_id = (p.get("voices") or {}).get(agent or "__default__", "")
    card = (p.get("cards") or {}).get(card_id)
    mode = (p.get("modes") or {}).get(agent or "__default__", "gateway")
    if mode == "off":
        return {"ok": False, "error": "voice-out off for this agent"}
    if mode == "watch":
        # the watch-native tier synthesizes on-device; gateway just ships the contract
        return {"ok": True, "tier": "watch", "text": text,
                "karaoke": "sentence", "speed": speed,
                "card": card and {"card_id": card_id, "ref_rate": card["ref_rate"]}}
    if not _SYNTH:
        return {"ok": False, "error": "no TTS engine registered (install NeuTTS Air)"}
    # engines address the card's wav by id — hand the id to the adapter with the meta
    card = dict(card, card_id=card_id) if card else None
    audio, rate = _SYNTH["air"](text, card, speed)
    words: list[dict] = []
    if want_words and _ALIGN:
        try:
            words = _ALIGN["parakeet"](audio, rate, text)
        except Exception as exc:
            words = []                            # karaoke degrades to sentence mode, honestly
    align = "forced" if words else "none"
    return {"ok": True, "tier": "gateway", "audio": audio, "rate": rate,
            "words": words, "align": align, "speed": speed, "engine": "air"}


def status() -> dict:
    """Pane card feed: what's installed, how many cards, the rev."""
    p = read_prefs()
    cards = p.get("cards") or {}
    return {"rev": p.get("rev", 1),
            "speed": (p.get("global") or {}).get("speed", SPEED_DEFAULT),
            "karaoke": (p.get("global") or {}).get("karaoke", True),
            "modes": p.get("modes") or {}, "voices": p.get("voices") or {},
            "cards": {cid: {k: v for k, v in m.items() if k != "sha256"}
                      for cid, m in cards.items()},
            "engine": bool(_SYNTH), "aligner": bool(_ALIGN)}
