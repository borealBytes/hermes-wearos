import os
DEMO = os.environ.get("DEMO_TMP", "/tmp/opencode")
#!/usr/bin/env python3
"""demo-walkthrough.gif — one representative canvas frame per beat, timed flipbook."""
import subprocess, os, glob

VID = DEMO + "/vid"
SEG = f"{VID}/segs"
G = f"{VID}/gif"
os.makedirs(G, exist_ok=True)

# Versioned media (owner rule): the GIF name + container metadata carry the repo version.
# Source preference: the OWNER'S SCORED film for this version (music added, same stem) ->
# versioned raw from assemble.py -> legacy un-versioned file.
with open(os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "VERSION")) as _fh:
    VER = _fh.read().strip()
_cands = [f"{VID}/demo-vertical-v{VER}.mp4", f"{VID}/demo-vertical-v{VER}-raw.mp4", f"{VID}/demo-vertical.mp4"]
FILM = next((c for c in _cands if os.path.exists(c)), _cands[0])
OUT_GIF = f"{VID}/demo-walkthrough-v{VER}.gif"

durs = []
for f in sorted(glob.glob(f"{SEG}/seg_*.mp4")):
    d = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                        "-of", "csv=p=0", f], capture_output=True, text=True).stdout.strip()
    durs.append(float(d))
assert len(durs) >= 15, durs

# one keyframe at the 60% point of each beat (post-reveal moments)
times, t = [], 0.0
for d in durs:
    times.append(min(t + d * 0.55, 999))
    t += d

frames = []
for i, tt in enumerate(times):
    src = f"{G}/f{i:02d}.png"
    tt = min(tt, sum(durs) - 0.35)
    subprocess.run(["ffmpeg", "-v", "error", "-y",
                    "-i", FILM, "-ss", f"{tt:.2f}", "-frames:v", "1", src], check=True)
    # scale to 480 wide (half canvas), sharpen lightly
    out = f"{G}/s{i:02d}.png"
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src,
                    "-vf", "scale=480:854:flags=lanczos,unsharp=3:3:0.4", out], check=True)
    frames.append(out)

# per-frame hold time: hero/end linger, feature frames 850ms
durations = []
for i in range(19):
    durations.append(1600 if i in (0, 18) else 850)

with open(f"{G}/concat.txt", "w") as fh:
    for f, ms in zip(frames, durations):
        fh.write(f"file '{f}'\nduration {ms/1000:.3f}\n")
    fh.write(f"file '{frames[-1]}'\n")

pal = f"{G}/palette.png"
subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", f"{G}/concat.txt",
                "-vf", "fps=12,scale=480:854:flags=lanczos:force_original_aspect_ratio=decrease,"
                       "palettegen=max_colors=128:stats_mode=diff", pal], check=True)
out = OUT_GIF
subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", f"{G}/concat.txt",
                "-i", pal, "-lavfi",
                "fps=12,scale=480:854:flags=lanczos [x];[x][1:v]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle",
                "-loop", "0", "-metadata", f"comment=hermes-wearos v{VER} (wear-demo capture)", out], check=True)
sz = os.path.getsize(out) / 1e6
print(f"walkthrough gif: {sz:.1f} MB, {len(frames)} frames, {sum(durations)/1000:.1f}s loop")
