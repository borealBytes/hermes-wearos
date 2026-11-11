#!/usr/bin/env python3
"""demo_eartest.py — the honesty gate. Transcribes the returned wav with the
PRODUCT aligner internals: the raw Parakeet rows (BEFORE script-blending — the
blend would hide misses) scored as IN-ORDER coverage against the script, and
the shipped karaoke rows (align.align()) written to <out_json> for the clip.
Buzz scores low even when words are recognizable: order is the claim.
Exit 0 iff coverage >= min-coverage.
Usage: demo_eartest.py WAV SCRIPT OUT_JSON MIN_COV [align_parakeet.py path]
Runs on whatever machine holds the STT engine (host or container) — the
aligner CODE is the plugin file itself, which is what makes the gate honest."""
import importlib.util, json, re, subprocess, sys
from difflib import SequenceMatcher
from pathlib import Path

wav, script, out_json, min_cov = sys.argv[1:5]
path = sys.argv[5] if len(sys.argv) > 5 else str(
    Path(__file__).resolve().parents[2] / "hermes-plugin" / "align_parakeet.py")
spec = importlib.util.spec_from_file_location("align_parakeet", path)
assert spec and spec.loader, f"no aligner module at {path}"
ap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ap)

# raw recognition: same argv align() builds, minus the blend
stt = ap._stt_root()
venv_py, model = stt / "venv" / "bin" / "python", ap._model_path()
lib = stt / "transcribe-src" / "build-shared" / "src" / "libtranscribe.so"
tmp = stt / "eartest_in.wav"
tmp.write_bytes(open(wav, "rb").read())
try:
    proc = subprocess.run([str(venv_py), str(ap.write_runner()), str(tmp),
                           str(model), str(lib)],
                          capture_output=True, text=True, timeout=900)
    assert proc.returncode == 0, (proc.stderr or "")[-400:]
    raw = json.loads(proc.stdout).get("words", [])
finally:
    tmp.unlink(missing_ok=True)

# ORDERED char-stream coverage (difflib): tokenizer-proof (audio "wearos" vs
# script "wear OS" costs nothing; "you self host" slurring to "uself host"
# costs only the broken span). Word-level greedy walks proved brittle to one
# stray short word 48 tokens downstream (measured). Buzz still scores low:
# difflib blocks are in-order in BOTH streams — the claim is preserved.
want_chars = re.sub(r"[^a-z0-9]+", "", open(script).read().lower())
said_chars = re.sub(r"[^a-z0-9]+", "", " ".join(r["text"] for r in raw).lower())
sm = SequenceMatcher(None, want_chars, said_chars, autojunk=False)
cov = sum(b.size for b in sm.get_matching_blocks())
pct = 100.0 * cov / max(1, len(want_chars))
# word-level report for human eyes: difflib on words, misses = script words in
# replace/delete regions (same alignment engine as the gate, so no greedy-run
# artifacts — an early stray token can't blacken the whole list).
_said_w = [w for r in raw for w in re.sub(r"[^a-z0-9']+", " ", r["text"].lower()).split()]
_want_w = re.sub(r"[^a-z0-9']+", " ", open(script).read().lower()).split()
_wm = SequenceMatcher(None, _want_w, _said_w, autojunk=False)
miss = [w for tag, i1, i2, _, _ in _wm.get_opcodes()
        if tag in ("replace", "delete") for w in _want_w[i1:i2]]

# shipped karaoke rows (product path, blend + flags) for the clip captions
import wave
w = wave.open(wav); rows = ap.align(w.readframes(w.getnframes()), w.getframerate(),
                                    open(script).read())
w.close()
json.dump(rows, open(out_json, "w"))
print(f"eartest: {cov}/{len(want_chars)} chars in order = {pct:.1f}% "
      f"({'PASS' if pct >= float(min_cov) else 'FAIL'} at {min_cov}%), "
      f"karaoke rows: {len(rows)}, word misses: {miss[:8] or 'none'}")
sys.exit(0 if pct >= float(min_cov) else 5)
