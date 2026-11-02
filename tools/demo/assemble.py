import os
DEMO = os.environ.get("DEMO_TMP", "/tmp/opencode")
#!/usr/bin/env python3
"""Assemble docs/demo/demo-vertical.mp4 — LONG feature arcs (owner: more demo per beat),
big watch circle (760/1080), harbor canvas, caption pills."""
import subprocess, os, glob

VID = DEMO + "/vid"
C = f"{VID}/canvas"
SEG = f"{VID}/segs"
os.makedirs(SEG, exist_ok=True)
OVL = {"bg": f"{C}/bg.png", "circle": f"{C}/circle.png", "ring": f"{C}/ring.png"}
# Versioned outputs (owner rule): the RAW master carries the repo version in its name AND
# metadata, so after the owner scores it in YouTube ("name-scored" keeps the stem), the
# upload is always traceable to a build. gif_build.py finds it by the same version.
with open(os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "VERSION")) as _fh:
    VER = _fh.read().strip()
RAW = f"{VID}/demo-vertical-v{VER}-raw.mp4"

# (source, in, dur, beat) ; None => canvas-only card. Arcs show the WHOLE feature:
# action -> stream -> result. Durations tuned per raw's best window.
SEGS = [
    (None,               None,  3.0,  0),   # hero card
    ("r0-splash",         4.5,  9.0,  1),   # cold launch -> splash film -> reveal on moving frame (marks +5.3/+12.4)
    ("r1-dial",           3.5,  4.0,  2),   # dial ring pinned, 8 lanes glow (dial_preview)
    ("r1-dial",           7.5,  8.5,  3),   # press-hold red mic orb -> listening -> live STT partial "what"
    ("r2b-chat",          4.0,  8.5,  4),   # streamed answer + red stop orb + scroll
    ("r8-stopcards",     5.0, 14.0,  5),   # 700-word essay streams, tap orb -> "— stopped —" red stamp
    ("img-table-02e",    0.0,  4.5,  6),   # markdown table card in the demo chat (still, slow push)
    ("img-viewer-04e",    0.0,  4.5,  7),   # media viewer at 2.5x zoom (still, slow push)
    ("r4b-pickers",       2.5,  9.0,  8),   # model picker: providers -> model list
    ("r5b-approval",      8.0,  7.9,  9),   # ⚠ Approve card lands over the stream + ✅/❌ wedges
    ("r5c-approve",       1.0,  7.0, 10),   # ✅ tap -> run resumes (gateway cancel -> continue)
    ("img-sess-21d",      0.0,  4.5, 11),   # Search - 2 results for "harbors" (still, slow push)
    ("r6e-sessions",      2.0, 14.0, 12),   # long-press row -> sheet -> archive -> count 11->10
    ("img-shade-44",      0.0,  4.5, 13),   # desktop-session approval buzz card (still)
    ("img-notify-31",     0.0,  4.5, 14),   # off-glass settle card, voice suppressed (still)
    ("r3-voice",         17.0, 10.0, 15),   # voice-out speaks + karaoke follow on the reply
    ("r7c-settings",      6.5, 11.0, 16),   # Display page + live font-scale 1.6 -> 0.75 -> reset
    ("r7e-edge",          1.5,  6.9, 17),   # rim (edge-circle) drags scroll the list + normal swipe
    ("r7d-gateways",      3.0, 13.0, 18),   # multi-gateway registry: winner dot + fallback + badge
    (None,               None,  3.5, 19),   # end card
]

def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        print("FAIL", " ".join(cmd[3:6]), r.stderr[-400:], flush=True); raise SystemExit(1)

# Still-frame beats (owner rule: the circle is never static) — banked square captures get a
# slow Ken-Burns push, pre-rendered once into vid/raw/img-<name>.mp4 and picked up below.
STILL_SRC = DEMO + "/demo/square"
for (src, tin, dur, beat) in SEGS:
    if not (src or "").startswith("img-"):
        continue
    out = f"{VID}/raw/{src}.mp4"
    png = f"{STILL_SRC}/{src[4:]}.png"
    if os.path.exists(out) and os.path.getsize(out) > 10000:
        continue
    frames = str(int(dur * 30))
    run(["ffmpeg", "-v", "error", "-loop", "1", "-framerate", "30", "-t", str(dur), "-i", png,
         "-vf", f"scale=1520:1520,zoompan=z='1+0.10*on/({int(dur*30)})'"
                f":x='(iw-iw/zoom)/2':y='(ih-ih/zoom)/2':d=1:s=760x760:fps=30",
         "-frames:v", frames, "-pix_fmt", "yuv420p", "-y", out])
    print("still", src, flush=True)

for i, (src, tin, dur, beat) in enumerate(SEGS):
    out = f"{SEG}/seg_{i:02d}.mp4"
    frames = str(int(dur * 30))
    if os.path.exists(out) and os.path.getsize(out) > 20000:
        d = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                            "-of", "csv=p=0", out], capture_output=True, text=True).stdout.strip()
        if d and abs(float(d) - dur) < 0.5:
            print(f"seg {i:02d} cached", flush=True); continue
    common = ["-y", "-r", "30", "-frames:v", frames, "-pix_fmt", "yuv420p",
              "-c:v", "libx264", "-preset", "ultrafast", "-crf", "21", out]  # h264_nvenc p4 when driver >= 610
    if src is None:
        # canvas-only beats: the circle is never empty — owner's splash fills the watch face
        run(["ffmpeg", "-v", "error", "-loop", "1", "-framerate", "30", "-t", str(dur), "-i", OVL["bg"],
             "-loop", "1", "-framerate", "30", "-t", str(dur), "-i", f"{C}/splash_circle.png",
             "-loop", "1", "-framerate", "30", "-t", str(dur), "-i", f"{C}/ov_{beat:02d}.png"] +
            ["-filter_complex", "[0:v][1:v]overlay=164:284[a];[a][2:v]overlay=0:0,format=yuv420p[out]",
             "-map", "[out]"] + common)
    else:
        run(["ffmpeg", "-v", "error", "-i", f"{VID}/raw/{src}.mp4",
             "-loop", "1", "-framerate", "30", "-t", str(dur), "-i", OVL["bg"],
             "-loop", "1", "-framerate", "30", "-t", str(dur), "-i", OVL["circle"],
             "-loop", "1", "-framerate", "30", "-t", str(dur), "-i", OVL["ring"],
             "-loop", "1", "-framerate", "30", "-t", str(dur), "-i", f"{C}/ov_{beat:02d}.png",
             "-filter_complex",
             f"[0:v]trim=start={tin}:end={tin+dur},setpts=PTS-STARTPTS,scale=760:760,fps=30,format=yuva420p[v];[v][2:v]alphamerge[vv];"
             "[1:v][vv]overlay=160:280[b];[b][4:v]overlay=0:0[br];[br][3:v]overlay=0:0,format=yuv420p[out]",
             "-map", "[out]"] + common)
    print(f"seg {i:02d} ok", flush=True)

with open(f"{SEG}/list.txt", "w") as f:
    for i in range(len(SEGS)):
        f.write(f"file '{SEG}/seg_{i:02d}.mp4'\n")
run(["ffmpeg", "-v", "error", "-f", "concat", "-safe", "0", "-i", f"{SEG}/list.txt",
     "-c", "copy", "-metadata", f"comment=hermes-wearos v{VER} (wear-demo capture)", RAW])
d = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                    "-of", "csv=p=0", RAW],
                   capture_output=True, text=True).stdout.strip()
print("master:", d, "seconds", flush=True)
