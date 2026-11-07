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
# action -> stream -> result. Durations tuned per raw's best window (ffprobe-verified 2026-11-07:
# every in+dur lands inside the take). v0.8.0: 24 beats / 141.3 s.
# Stills linger, arcs show action->result. The 964x546 attention PAIR ships as its right half
# (46-attention-off.png, gif_build.pair_half geometry) — no two-watch circle (§4 owner rule).
# Karaoke + desktop-watch lost their motion takes this round: both ship as refreshed stills.
SEGS = [
    (None,               None,  3.0,  0),   # hero card
    ("r0splash-take",     3.0,  9.0,  1),   # cold launch -> splash film -> reveal on moving frame
    ("r1dial",            0.5,  7.0,  2),   # orb tap -> dial ring pins, lanes glow
    ("r3-voice",          1.5,  8.0,  3),   # hold to mic lane -> listening -> live word stream
    ("r2b-chat",          0.5,  7.5,  4),   # streamed answer scroll + tool call on the glass
    ("img-02b-chat-table",0.0,  4.5,  5),   # batch-10 4-col Slip/Depth/Tide/Wind grid (still)
    ("img-05-mermaid",    0.0,  4.5,  6),   # batch-10 graph LR round-trip diagram (still)
    ("img-25-model-picker",0.0, 4.0,  7),   # model picker modal (still; picked-toast beat folded)
    ("r5b-approval",      1.0,  9.0,  8),   # gated command parks the card -> Approve tap -> resumes
    ("img-33-approval-card",0.0,4.5,  9),   # real gated run card in the transcript (still)
    ("img-56-approval-modes",0.0,4.5,10),   # Inherit/Manual/Smart sheet (still, NEW in v0.8)
    ("img-21-session-search",0.0,4.5,11),   # Search - 14 on `harbor` (still, re-shot)
    ("r6e-sessions",      1.0, 10.5, 12),   # long-press row -> sheet -> archive -> filter
    ("img-32-notify-approval",0.0,4.5, 13), # approval notification, actions expanded (still)
    ("img-39-karaoke-follow",0.0,4.5, 14),  # spoken sentence highlighted (still — take pending)
    ("r-media",           0.0,  4.5, 15),   # media cards -> player -> volume -> pinch (4.7 s take)
    ("img-48-media-cards", 0.0, 4.5, 16),   # aurora gif card + audio sting wall (still)
    ("r50-gif",           0.5,  7.0, 17),   # animated GIF card ON THE GLASS (motion take)
    ("r7c-settings",      1.5, 10.5, 18),   # Settings pages + live font-scale drag
    ("r7e-edge",          0.2,  4.3, 19),   # rim (edge-circle) drag scrolls the list
    ("r7d-gateways",      0.5,  8.5, 20),   # gateway registry: winner dot + fallback
    ("img-46-attention-off",0.0,4.5, 21),   # desktop answer + parked approval buzz (pair half)
    ("img-55-tailnet-status",0.0,4.5, 22),  # the embedded tailnet node (still, redacted)
    (None,               None,  3.5, 23),   # end card
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
