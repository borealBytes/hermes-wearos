#!/usr/bin/env python3
"""demo_eartest.py — the honesty gate. Transcribes the returned wav with the
PRODUCT aligner internals: the raw Parakeet rows (BEFORE script-blending — the
blend would hide misses) scored as IN-ORDER coverage against the script, and
the shipped karaoke rows (align.align()) written to <out_json> for the clip.
Buzz scores low even when words are recognizable: order is the claim.
Exit 0 iff coverage >= min-coverage."""
import importlib.util, json, re, subprocess, sys

wav, script, out_json, min_cov = sys.argv[1:5]
path = "/opt/data/plugins/wearos/align_parakeet.py"
spec = importlib.util.spec_from_file_location("align_parakeet", path)
assert spec and spec.loader, f"plugin not synced into the container? no {path}"
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

said = [w for w in (re.sub(r"[^a-z0-9']", "", r["text"].lower()) for r in raw) if w]
want = re.findall(r"[a-z0-9']+", open(script).read().lower())
it = iter(said)
cov = sum(1 for w in want if w in it)                    # in-order subsequence
pct = 100.0 * cov / max(1, len(want))

# shipped karaoke rows (product path, blend + flags) for the clip captions
import wave
w = wave.open(wav); rows = ap.align(w.readframes(w.getnframes()), w.getframerate(),
                                    open(script).read())
w.close()
json.dump(rows, open(out_json, "w"))
print(f"eartest: {cov}/{len(want)} words in sequence = {pct:.1f}% "
      f"({'PASS' if pct >= float(min_cov) else 'FAIL'} at {min_cov}%), "
      f"karaoke rows: {len(rows)}")
sys.exit(0 if pct >= float(min_cov) else 5)
