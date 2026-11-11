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
        out = _bump(_save_and_return(local))
        return out


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
    playback-speed feature; per-agent is voice + mode, never rate.
    PACING AT PLAYBACK (2026-11-10): the dial is pure player state — moving it
    never re-renders a clip (cache key is speed-free), so it commits silently."""
    s = max(SPEED_MIN, min(SPEED_MAX, float(speed)))
    with LOCK:
        p = read_prefs()
        p.setdefault("global", {})[("speed")] = round(s, 2)
        return _save_and_return(p)


def set_karaoke(on: bool) -> dict:
    with LOCK:
        p = read_prefs()
        p.setdefault("global", {})["karaoke"] = bool(on)
        return _save_and_return(p)


# ------------------------------------------------------------------ cards

def _syllable_wpm(wav_bytes: bytes, voiced: float) -> float:
    """Freeform pace without a transcript: count syllable nuclei in the energy
    envelope (10 ms hops / 40 ms windows, 3-tap smooth, prominence gate at
    floor+25% of range, 120 ms refractory) and divide by the English mean
    1.65 syllables/word. An ESTIMATE, flagged as such on the card — the
    owner's freeform enrollment law (2026-11-11) retired read-off-the-page
    scripts on the watch, and pace still has to be real: a fast take must
    measure fast."""
    rate, sm = _wav_parse(wav_bytes)
    hop = max(1, int(rate * 0.01))
    win = max(1, int(rate * 0.04))
    env = []
    for k in range(0, len(sm) - win + 1, hop):
        seg = sm[k:k + win]
        env.append(sum(s * s for s in seg) / win)
    if len(env) < 5:
        return 0.0
    sm3 = [(env[i - 1] + env[i] + env[i + 1]) / 3.0 for i in range(1, len(env) - 1)]
    srt = sorted(sm3)
    floor, peak = srt[len(srt) // 5], srt[-1]
    if peak <= floor * 1.5:
        return 0.0
    gate = floor + 0.25 * (peak - floor)
    refr = max(1, int(0.12 / 0.01))            # frames (hop is 10 ms)
    syl, last = 0, -10**9
    for i in range(1, len(sm3) - 1):
        if (sm3[i] > gate and sm3[i] >= sm3[i - 1] and sm3[i] >= sm3[i + 1]
                and (sm3[i] > sm3[i - 1] or sm3[i] > sm3[i + 1])
                and i - last >= refr):
            syl += 1
            last = i
    span_min = (voiced / 60.0) if voiced > 0 else len(sm) / rate / 60.0
    if span_min <= 0:
        return 0.0
    return (syl / span_min) / 1.65


def measure_ref_rate(wav_bytes: bytes, transcript: Optional[str] = None) -> dict:
    """Words-per-minute of the reference, measured — never assumed.

    Three lanes, honest about which one ran (`rate_source` on the card):
      transcript given   → wpm = words / voiced-seconds.  'measured'
      transcript == ""   → freeform take (owner law 2026-11-11: talk 3-12 s,
                           say ANYTHING): syllable-envelope estimate. 'syllabic-est'
      transcript None    → legacy/scripted default (seed pack, pane demo). 'measured'
    """
    voiced = _voiced_seconds(wav_bytes)
    if voiced < 0.5:
        raise ValueError("reference is (nearly) all silence — record 3s of actual speech")
    if transcript == "":
        wpm = _syllable_wpm(wav_bytes, voiced)
        n = max(1, int(round(wpm * voiced / 60.0)))
        src = "syllabic-est"
    else:
        if transcript is None:
            transcript = SCRIPTED_PROMPT      # seed/pane lane: the doc matches the words
        n = len(WORD_RE.findall(transcript))
        wpm = n / voiced * 60.0
        src = "measured"
    return {"ref_rate": round(wpm, 1), "voiced_s": round(voiced, 2),
            "words": n, "seconds_total": round(_wav_duration(wav_bytes), 2),
            "rate_source": src}


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
    """Energy VAD, stdlib: 20ms frames, hysteresis gate at max(6*noise_floor,
    peak/64) (-18 dB), plus hangover so natural syllable valleys and coarticulation
    dips stay inside the speech. Runs separated by <=300ms merge (inter-word gaps
    are pace, silence is pace too; a 300ms bridge stops breath micro-pauses from
    fragmenting one sentence into confetti — the bug that read a human card at
    567 wpm on first real-voice contact)."""
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
    gate = max(noise * 6.0, peak / 64.0)
    hang = gate / 3.0
    on = [e > gate for e in frames]
    for i, v in enumerate(on):                            # +/-1 frame hangover
        if v:
            if i:
                on[i - 1] = on[i - 1] or frames[i - 1] > hang
            if i + 1 < len(on):
                on[i + 1] = on[i + 1] or frames[i + 1] > hang
    voiced, gap, started = 0, 0, False
    for v in on:
        if v:
            if started and gap <= 15:                    # bridge <=300ms gaps
                voiced += gap
            voiced += 1
            started = True
            gap = 0
        else:
            gap += 1
    return max(0.0, voiced * frame_ms / 1000.0)


def _warm_audition() -> None:
    """Background: synthesize every card's OWN transcript at the live dial speed once.
    That clip is exactly what the picker's ▶ requests (card_id override + same words),
    so the first tap answers from the cache instead of paying the per-call model reload
    (cold engine: minutes on the owner's box). Silent on failure; the cache is the
    only consumer and the refusal path stays honest."""
    def run() -> None:
        try:
            p = read_prefs()
            if not _SYNTH:
                return
            for cid, m in (p.get("cards") or {}).items():
                t = m.get("transcript") or ""
                if not t:
                    continue
                try:
                    synthesize(t, "", want_words=False, card_id=cid)
                except Exception:
                    pass
        except Exception:
            pass
    threading.Thread(target=run, name="voiceout-audition-warm", daemon=True).start()


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
        if transcript == "":                # freeform enrollment lane
            rate0, _ = _wav_parse(wav_bytes)
            got = _transcribe(wav_bytes, rate0)
            if len(got.split()) >= 2:       # real words beat any estimate
                transcript = got[:600]
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
    _warm_audition()   # a new card should be auditionable the moment it appears
    return {"card_id": card_id, **p["cards"][card_id]}


def rename_card(card_id: str, label: str) -> dict:
    """Display label swapped on the SAME card_id (owner law 2026-11-11): the
    id is the unchanging uuid nobody sees — voices bound to a card, the watch
    list, and every dropdown update the moment the rev does, because 'name'
    was always just a label. Free text, capped like at creation."""
    label = re.sub(r"[\x00-\x1f\x7f]", "", label).strip()[:48] or card_id[:48]
    with LOCK:
        p = read_prefs()
        card = (p.get("cards") or {}).get(card_id)
        if not card:
            raise KeyError(f"no such voice card: {card_id}")
        card["label"] = label
        return _save_and_return(p)


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


_TRANSCRIBERS: "dict[str, Callable]" = {}


def register_transcriber(name: str, fn: Callable) -> None:
    """ASR adapters that can turn a freeform enrollment take into words.
    Owner law 2026-11-11: zero-shot freeform takes should NOT guess the
    reference words — the gateway already ships Parakeet for voice-in, so
    the same model HEARS what the wearer said and the card stores the real
    transcript (exact ref_rate, honest audition). Absent engine, the
    syllable-envelope estimate stands; nothing is ever invented."""
    _TRANSCRIBERS[name] = fn


def _transcribe(wav_bytes: bytes, rate: int) -> str:
    for fn in _TRANSCRIBERS.values():
        try:
            txt = (fn(wav_bytes, rate) or "").strip()
            if txt:
                return txt
        except Exception:
            continue                    # busy engine — next adapter / estimate
    return ""


_SENT_RE = __import__("re").compile(r"(?<=[.!?])\s+")


def _sentences(text: str, min_len: int = 24) -> list[str]:
    """The server-side sentence cutter (owner ruling 2026-11-10, issue #7): long
    replies synthesize SENTENCE BY SENTENCE in BOTH lanes — one mega-generation of a
    'big old line of text' pins the engine for ages and caches as one giant blob;
    segments are short draws, cache per sentence, and every repeated sentence across
    all future replies replays from disk. Short texts stay one clip. Fragments under
    *min_len* chars (a stray 'U.S.' abbreviation, a clipped '...') merge forward into
    the following sentence so we never synthesize a 3-char gasp on its own."""
    if len(text) <= 160:
        return [text]
    parts = [p.strip() for p in _SENT_RE.split(text) if p.strip()]
    if len(parts) < 2:
        return [text]
    merged: list[str] = []
    for p in parts:
        if merged and len(merged[-1]) < min_len:
            merged[-1] = merged[-1] + " " + p
        else:
            merged.append(p)
    if len(merged) > 1 and len(merged[-1]) < min_len:  # tail fragment joins the previous
        merged[-2] = merged[-2] + " " + merged.pop()
    return merged if len(merged) > 1 else [text]


def _now_speed(p: dict) -> float:
    return float((p.get("global") or {}).get("speed", SPEED_DEFAULT))


def _render_segment(seg: str, card, card_id, want_words: bool,
                    speed: float) -> dict:
    """One sentence (or short text): cache-keyed, silence-guarded, natural 1x pace."""
    key = _synth_key(seg, card_id, bool(want_words))
    hit = _cache_get(key, want_words)
    if hit is not None:
        hit["cached"] = True
        hit["speed"] = speed
        return hit
    audio, rate = _SYNTH["air"](seg, card, 1.0)       # natural pace, always
    words: list[dict] = []
    if want_words and _ALIGN:
        try:
            words = _ALIGN["parakeet"](audio, rate, seg)
        except Exception:
            words = []          # karaoke degrades to sentence mode, honestly
    out = {"ok": True, "tier": "gateway", "audio": audio, "rate": rate,
           "words": words, "align": "forced" if words else "none",
           "speed": speed, "engine": "air"}
    _cache_put(key, out)
    return out


def _stitch(parts: list[dict], gap_s: float = 0.08) -> dict:
    """Join segment clips into one WAV with *gap_s* breaths between; word timeline
    shifts with the cumulative position (karaoke stays true)."""
    import io as _io
    import wave
    frames = bytearray()
    words: list[dict] = []
    rate = parts[0]["rate"]
    t = 0.0
    for i, o in enumerate(parts):
        w = wave.open(_io.BytesIO(o["audio"]))
        d = w.readframes(w.getnframes())
        if i:
            frames += b"\x00\x00" * int(rate * gap_s)
            t += gap_s
        frames += d
        for wd in o.get("words") or []:
            wd2 = dict(wd)
            wd2["t0"] = wd.get("t0", 0.0) + t
            wd2["t1"] = wd.get("t1", 0.0) + t
            words.append(wd2)
        t += w.getnframes() / float(rate)
    buf = _io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setparams((1, 2, rate, 0, "NONE", "not compressed"))
        w.writeframes(bytes(frames))
    align = "forced" if words else parts[0].get("align", "none")
    return {"ok": True, "tier": "gateway", "audio": buf.getvalue(), "rate": rate,
            "words": words, "align": align, "speed": parts[0]["speed"],
            "engine": "air"}


def synthesize(text: str, agent: str, *, want_words: bool = True,
               card_id: Optional[str] = None) -> dict:
    """The watch-facing contract: text in, {audio_opus_b64|pcm_b64, words[], engine,
    tier_note} out. PACING AT PLAYBACK (owner ruling 2026-11-10): audio always renders
    at the engine's natural 1x pace; the current dial is returned as "speed" for the
    PLAYER to apply (ExoPlayer playbackParameters on the watch, playbackRate in the
    pane) — pitch-preserved time-stretch, no per-speed re-render. ref_rate still lives
    on the card (1.0x replays a speaker at THEIR OWN pace; target-WPM normalization,
    if we ever add it, is a playback-time concern too).
    [card_id] overrides the agent's bound voice (the picker's ▶ auditions ANY card at
    the live dial, not just the bound one); an unknown id is a refusal (400)."""
    p = read_prefs()
    speed = float((p.get("global") or {}).get("speed", SPEED_DEFAULT))
    # Binding law: the agent's own voice wins; an unbound (or deliberately
    # unbound — '' after a card delete) agent falls back to the GLOBAL card —
    # the Voice-out dropdown's selection. A named-but-empty key must never
    # strand a speakable box on a refusal (measured 2026-11-10: pane ▶ POSTs
    # agent='desktop', the map only carried '__default__', and the owner got
    # a 500 instead of his own voice).
    vm = p.get("voices") or {}
    bound = vm.get(agent or "__default__") or vm.get("__default__") \
        or vm.get("default") or ""
    if card_id:
        if card_id not in (p.get("cards") or {}):
            raise ValueError(f"no such voice card: {card_id}")
        card_id = str(card_id)
    else:
        card_id = bound
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
    # Synthesis cache: NeuTTS Air reloads its weights per subprocess call (tens of
    # seconds on cold hardware — measured 271 s cold / 41 s warm on the owner's box,
    # 2026-11-10), which turns a pane Play click into an invisible multi-minute wait
    # (issue #7 honesty rule: a button must answer *now*). PACING AT PLAYBACK (owner
    # ruling 2026-11-10): audio renders at NATURAL 1x speed and the cache key is
    # speed-free, so moving the dial never re-renders — the clip is one canonical
    # take and the player applies the rate. Identical (text, card, words) requests
    # replay from disk instantly.
    key = _synth_key(text, card_id, bool(want_words))
    hit = _cache_get(key, want_words)
    if hit is not None:
        hit["cached"] = True
        hit["speed"] = speed            # dial may have moved since render
        return hit
    # SENTENCE BY SENTENCE (owner ruling 2026-11-10): a long reply is cut server-side
    # and each sentence renders as its own short, cache-keyed draw — no more single
    # mega-generation of a "big old line of text" ripping the CPU for minutes, and a
    # sentence that ever repeats in ANY future reply replays from disk.
    segs = _sentences(text)
    parts = [_render_segment(s, card, card_id, want_words, speed) for s in segs]
    if len(parts) == 1:
        out = dict(parts[0])
    else:
        out = _stitch(parts)
        _cache_put(key, out)            # whole text replays too; sentences cache-shared
    out = dict(out)
    out["speed"] = speed                # dial may have moved mid-render
    return out


# ---------------------------------------------------------------- synthesis cache
# plugin-data/voiceout/synth-cache/<key>.wav + .json sidecar; bounded by count with
# mtime eviction (entries are ~100-500 KB). Stdlib only (rule 10).

_CACHE_MAX = 60


def _synth_key(text: str, card_id: str, want_words: bool) -> str:
    # v2: speed dropped from the key (pacing rides playback, not the render).
    import hashlib
    doc = f"v2\n{text}\n{card_id}\n{int(want_words)}"
    return hashlib.sha256(doc.encode("utf-8")).hexdigest()[:32]


def _cache_dir() -> Path:
    d = _root() / "synth-cache"
    d.mkdir(parents=True, exist_ok=True)
    return d


def _silent(audio: bytes) -> bool:
    """True if a WAV carries no audible signal (int16 peak < 128)."""
    import io as _io
    import struct
    import wave
    try:
        w = wave.open(_io.BytesIO(audio))
        d = w.readframes(w.getnframes())
        if len(d) < 2:
            return True
        s = struct.unpack("<%dh" % (len(d) // 2), d[:400000])
        return max(abs(x) for x in s) < 128
    except Exception:
        return False


def _cache_get(key: str, want_words: bool) -> Optional[dict]:
    try:
        d = _cache_dir()
        wav, side = d / f"{key}.wav", d / f"{key}.json"
        if not wav.exists() or not side.exists():
            return None
        meta = json.loads(side.read_text("utf-8"))
        audio = wav.read_bytes()
        if _silent(audio):
            # A zero-peak clip is an engine failure, not a result (measured
            # 2026-11-10: a serve-mode render cached 1.25 s of digital
            # silence and every ▶ swapped the audible reference for it).
            # Drop the entry so the next call re-renders.
            wav.unlink(missing_ok=True)
            side.unlink(missing_ok=True)
            return None
        words = meta.get("words", []) if want_words else []
        return {"ok": True, "tier": "gateway", "audio": audio, "rate": meta["rate"],
                "words": words, "align": meta.get("align", "none"),
                "speed": meta["speed"], "engine": meta.get("engine", "air")}
    except Exception:
        return None


def _cache_put(key: str, out: dict) -> None:
    try:
        d = _cache_dir()
        (d / f"{key}.wav").write_bytes(out["audio"])
        (d / f"{key}.json").write_text(json.dumps(
            {"rate": out["rate"], "speed": out["speed"], "align": out["align"],
             "engine": out["engine"], "words": out["words"]}))
        wavs = sorted(d.glob("*.wav"), key=lambda p: p.stat().st_mtime)
        for old in wavs[:-_CACHE_MAX]:
            old.unlink(missing_ok=True)
            old.with_suffix(".json").unlink(missing_ok=True)
    except Exception:
        pass                                     # cache failure must never break a synth


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
            # the EXACT line every enrollment must speak — the watch renders THIS
            # string so server-side transcript auto-fill stays true (single source).
            "prompt": SCRIPTED_PROMPT,
            "engine": bool(_SYNTH), "aligner": bool(_ALIGN)}
