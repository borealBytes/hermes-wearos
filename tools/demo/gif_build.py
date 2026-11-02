#!/usr/bin/env python3
"""Build docs/demo/demo-walkthrough-v<VER>.gif WITHOUT the master mp4.

Owner pacing rules (2026-11-01 batch): stills linger ~1.5 s (hero/end ~2.5 s) so they can
actually be read, and motion is shown ONLY where it's real — the splash beat plays frames cut
from the SHIPPED intro asset (circle-cropped, transparent bg). Canvas, fonts, caption pills and
the `v<VER> · wear-demo` bug reuse the film's design system (see canvas_build.py + docs/DEMO.md §5).

Run:  python3 tools/demo/gif_build.py     (writes to /tmp then into docs/demo/)
"""
import os, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
GIF_TMP = os.environ.get("DEMO_GIF_TMP", "/tmp/opencode/gifbuild")
SCREENS = os.path.join(ROOT, "docs", "screens")
ASSET = os.path.join(ROOT, "app", "src", "main", "assets", "hermes-watch-intro.mp4")
_DEVKIT = os.path.join(os.path.dirname(ROOT), "devkit", "demo")
FONT_DIR = os.environ.get("DEMO_FONTS", os.path.join(_DEVKIT, "fonts"))
ART = os.path.join(_DEVKIT, "art", "hero-source.png")
with open(os.path.join(ROOT, "VERSION")) as fh:
    VER = fh.read().strip()

W, H = 480, 854                    # shipped GIF scale (0.4444 of the 1080x1920 film canvas)
FP = 0.4444                        # geometry scale from canvas_build.py
COBALT = (60, 99, 221)
INKBG = (11, 11, 20, 205)
PAPER = (232, 232, 242)
FPS = 12

os.makedirs(GIF_TMP, exist_ok=True)
os.makedirs(os.path.join(GIF_TMP, "frames"), exist_ok=True)

from PIL import Image, ImageDraw, ImageFilter, ImageFont

def font(name, size):
    return ImageFont.truetype(os.path.join(FONT_DIR, name), max(1, round(size * FP)))

def tracked(d, xy, text, fnt, fill, track=6):
    x, y = xy
    for ch in text:
        d.text((x, y), ch, font=fnt, fill=fill)
        x += d.textlength(ch, font=fnt) + track
    return x - track

def tracked_w(d, text, fnt, track=6):
    return sum(d.textlength(c, font=fnt) + track for c in text) - track

# ---- base canvas: same harbor cover-crop + vignette + version bug as the film ----
CX, CY, D = round(160 * FP), round(280 * FP), round(760 * FP)   # watch circle box
if os.path.exists(ART):
    art = Image.open(ART).convert("RGB")
    s = max(W / art.width, H / art.height)
    art = art.resize((int(art.width * s), int(art.height * s)), Image.LANCZOS)
    l, t = (art.width - W) // 2, (art.height - H) // 2
    base = art.crop((l, t, l + W, t + H)).point(lambda p: int(p * 0.82))
    vig = Image.new("L", (W, H), 0)
    ImageDraw.Draw(vig).ellipse((-W // 2, -H // 2, W * 3 // 2, H * 3 // 2), fill=255)
    vig = vig.filter(ImageFilter.GaussianBlur(100)).point(lambda p: 255 - min(90, (255 - p) // 2))
    base = Image.composite(base, Image.new("RGB", (W, H), (0, 0, 0)), vig)
else:
    base = Image.new("RGB", (W, H), (7, 7, 14))
base = base.convert("RGBA")
bug = ImageDraw.Draw(base)
bug_fnt = font("RulesCmp-Medium.ttf", 28)
bug_txt = f"v{VER} · wear-demo"
bug.text((W - tracked_w(bug, bug_txt, bug_fnt, track=2) - 24, H - 30), bug_txt,
         font=bug_fnt, fill=(232, 232, 242, 130))

# ---- chrome: cobalt glass ring + white rim (same proportions as the film) ----
chrome = Image.new("RGBA", (W, H), (0, 0, 0, 0))
dc = ImageDraw.Draw(chrome)
dc.ellipse((CX - 6, CY - 6, CX + D + 6, CY + D + 6), outline=(235, 238, 248, 255), width=2)
dc.ellipse((CX - 8, CY - 8, CX + D + 8, CY + D + 8), outline=COBALT + (230,), width=3)
dc.ellipse((CX - 16, CY - 16, CX + D + 16, CY + D + 16), outline=COBALT + (70,), width=2)

def pill(canvas, cap):
    ov = canvas.copy()
    dd = ImageDraw.Draw(ov)
    fpill = font("RulesCnd-Light.ttf", 44)
    w = tracked_w(dd, cap, fpill) + 32
    x0 = (W - w) / 2
    y0, hh = round(1150 * FP), round(112 * FP)
    dd.rounded_rectangle((x0, y0, x0 + w, y0 + hh), radius=round(34 * FP),
                         fill=INKBG, outline=COBALT + (200,), width=2)
    tracked(dd, (x0 + 16 - 3, y0 + (hh - round(46 * FP)) / 2 - 2), cap, fpill, PAPER, track=2)
    return ov

def wordmark(canvas, sub):
    ov = canvas.copy()
    dd = ImageDraw.Draw(ov)
    fpill, fsmall = font("RulesCnd-Light.ttf", 44), font("RulesCmp-Medium.ttf", 30)
    wtxt = "HERMES WEAR"
    w = tracked_w(dd, wtxt, fpill, track=4)
    tracked(dd, ((W - w) / 2, round(1210 * FP)), wtxt, fpill, PAPER, track=4)
    ws = tracked_w(dd, sub, fsmall, track=2)
    tracked(dd, ((W - ws) / 2, round(1290 * FP)), sub, fsmall, (150, 158, 190), track=2)
    return ov

def place_still(canvas, png):
    """Composite a circle-masked docs/screens still into the watch circle (glass ring on top)."""
    ov = canvas.copy()
    still = Image.open(os.path.join(SCREENS, png)).convert("RGBA")
    side = D - 6
    still = still.resize((side, side), Image.LANCZOS)
    ov.alpha_composite(still, (CX + 3, CY + 3))
    ov.alpha_composite(chrome)
    return ov

# ---- the beat sheet: caption -> stills (docs/screens) ----
BEAT_SHOTS = [
    ("LAUNCH INTO A FILM - THE SPLASH INTRO", None),        # motion beat (asset frames)
    ("ORB DIAL - DRAG EIGHT WAYS", ["01-orb-cluster.png"]),
    ("PUSH TO TALK - SPEECH-TO-TEXT ON THE WRIST", ["06-ptt-overlay.png"]),
    ("STREAMED ANSWERS + LIVE TOOL CALLS", ["02-chat-markdown.png"]),
    ("STOP ANY RUN MID-FLIGHT", ["07-stop-running.png"]),
    ("TABLES - MERMAID - IMAGES - INLINE", ["02b-chat-table.png", "05-mermaid.png", "03-image-card.png"]),
    ("TAP A CARD - ZOOM - PAN - FOLLOW LINKS", ["04-viewer-zoom.png", "28-browser-opened.png"]),
    ("SWAP MODELS PER CHAT", ["25-model-picker.png", "26-model-picked.png"]),
    ("GATED COMMANDS APPROVE ON THE WRIST", ["32-notify-approval.png", "33-approval-card.png"]),
    ("APPROVED - THE RUN RESUMES", ["34-approval-approved.png"]),
    ("SEARCH EVERY SESSION FROM THE WRIST", ["21-session-search.png"]),
    ("ARCHIVE AND UNARCHIVE - NOTHING LOST", ["23-archived-chip.png", "24-unarchive.png"]),
    ("DESKTOP RUNS BUZZ THE WRIST", ["44-notify-desktop-approval.png", "45-notify-desktop-settled.png"]),
    ("SPOKEN ON GLASS - NOTIFIED OFF IT", ["31-notify-plain.png", "46-attention-pair.png"]),
    ("VOICE OUT + KARAOKE FOLLOW", ["37-voice-output.png", "39-karaoke-follow.png"]),
    ("THREE WAYS IN - VOSK - VENDOR - GATEWAY", ["40-stt-source.png", "36-splash-picker.png"]),
    ("FONT SCALE - YOUR EYES YOUR SIZE", ["14a-fontscale-min.png", "14b-fontscale-max.png"]),
    ("EDGE-CIRCLE SCROLLING - EVERY LIST", ["41-edge-circle-pair.png"]),
    ("YOUR GATEWAYS - SAVE - SELECT - FAIL OVER", ["42-gateways-multi.png", "47-about-screen.png"]),
]
HOLD_STILL_MS = 1500
HOLD_HERO_MS = 2500
INTRO_S = (1.0, 10.5)   # lid-open -> eclipse light -> Hermes rise, from the shipped asset

frames, durations = [], []

def emit(img, ms):
    p = os.path.join(GIF_TMP, "frames", f"f{len(frames):04d}.png")
    img.save(p)
    frames.append(p)
    durations.append(ms)

# hero
emit(wordmark(base, "VOICE-FIRST WEAR OS CLIENT FOR HERMES"), HOLD_HERO_MS)

# motion beat: intro film frames, circle-cropped, with the splash caption
intro_dir = os.path.join(GIF_TMP, "intro")
if not os.path.isdir(intro_dir) or not os.listdir(intro_dir):
    os.makedirs(intro_dir, exist_ok=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", str(INTRO_S[0]),
                    "-t", f"{INTRO_S[1] - INTRO_S[0]:.1f}", "-i", ASSET,
                    "-vf", f"fps={FPS}", os.path.join(intro_dir, "i%04d.png")], check=True)
cap_intro = pill(base, BEAT_SHOTS[0][1] or BEAT_SHOTS[0][0])
for p in sorted(os.listdir(intro_dir)):
    fr = Image.open(os.path.join(intro_dir, p)).convert("RGBA")
    fr = fr.resize((D, D), Image.LANCZOS).convert("RGB").convert("RGBA")
    m = Image.new("L", (D, D), 0)
    ImageDraw.Draw(m).ellipse((0, 0, D - 1, D - 1), fill=255)
    fr.putalpha(m)
    ov = cap_intro.copy()
    ov.alpha_composite(fr, (CX, CY))
    ov.alpha_composite(chrome)
    emit(ov, int(1000 / FPS))

# feature beats
for cap, shots in BEAT_SHOTS[1:]:
    for png in shots:
        emit(pill(place_still(base, png), cap), HOLD_STILL_MS)

# end
emit(wordmark(base, "HERMES-WEAROS - OPEN SOURCE"), HOLD_HERO_MS)

# ---- concat -> palette -> gif (transparent corners preserved) ----
G = GIF_TMP
with open(f"{G}/concat.txt", "w") as fh:
    for f, ms in zip(frames, durations):
        fh.write(f"file '{f}'\nduration {ms / 1000:.3f}\n")
    fh.write(f"file '{frames[-1]}'\n")

pal = f"{G}/palette.png"
subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", f"{G}/concat.txt",
                "-vf", f"fps={FPS},palettegen=max_colors=160:stats_mode=diff", pal], check=True)
out_tmp = f"{G}/demo-walkthrough-v{VER}.gif"
subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", f"{G}/concat.txt",
                "-i", pal, "-lavfi", f"fps={FPS} [x];[x][1:v]paletteuse=dither=bayer:bayer_scale=4",
                "-loop", "0", "-metadata", f"comment=hermes-wearos v{VER} (wear-demo capture)",
                out_tmp], check=True)
dst = os.path.join(ROOT, "docs", "demo", f"demo-walkthrough-v{VER}.gif")
subprocess.run(["cp", out_tmp, dst], check=True)
print(f"walkthrough gif: {os.path.getsize(dst) / 1e6:.1f} MB, {len(frames)} frames, "
      f"{sum(durations) / 1000:.1f}s loop -> {dst}")
