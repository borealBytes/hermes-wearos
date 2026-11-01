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
    (None,           None, 3.0,  0),
    ("a1b-full",      1.5,  7.0, 1),   # dial pins (lanes glow) - verified 10/27
    ("a1b-full",      4.5,  6.5, 2),   # PTT overlay: red orb + partial-typing bubble
    ("a1-stream",    13.0, 15.0, 3),   # streamed answer + live tool line + red stop orb
    ("a1b-full",     16.0, 16.0, 4),   # rich cards + mermaid (white content 15-33)
    ("a1b-full",     38.0,  7.0, 5),   # run stopped mid-flight: question + stopped stamp
    ("a2-viewer",    19.0, 15.0, 6),   # mermaid tap -> zoomed viewer (white 20-34) -> pan
    ("a3b-model",     2.0,  9.0, 7),   # picker: providers -> claude-fable-5.1 highlighted
    ("a3b-model",    70.0,  4.1, 7),   # claude names itself verbatim
    ("a4b-approval",  8.0, 20.0, 8),   # ⚠ Approve card + command preview + run running
    ("a4b-approval", 46.0, 10.0, 8),   # resolved: final answer text on screen
    ("a5-sessions",   6.0, 14.0, 9),   # search field + IME typing
    ("a5b-unarchive",44.0,  8.0,10),   # sessions + archived chip row
    ("a5b-unarchive",55.0, 12.0,10),   # long-press -> sheet (59-64) -> unarchive tap -> toast
    ("a6-notify",    53.5, 10.0,11),   # drawer -> Hermes approval card fronts
    ("a6-notify",    62.0, 14.0,11),   # expands (Approve/Deny) -> APPROVE TAP ~71
    ("a6c-deeplink",  2.0, 24.0,12),   # settle card -> Open session -> landing
    ("a7b-settings",  2.5, 10.0,13),   # live font-scale resize on real screens
    ("a7b-settings", 13.5,  6.5,14),   # HOME -> launcher grid -> spirit tap -> launch
    (None,           None, 3.5, 15),
]

def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        print("FAIL", " ".join(cmd[3:6]), r.stderr[-400:], flush=True); raise SystemExit(1)

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
        run(["ffmpeg", "-v", "error", "-i", f"{VID}/{src}.mp4",
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
