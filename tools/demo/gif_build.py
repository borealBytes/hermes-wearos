#!/usr/bin/env python3
"""Build docs/demo/demo-walkthrough-v<VER>.gif WITHOUT the master mp4.

Owner pacing rules (2026-11-01 batch): stills linger ~1.5 s (hero/end ~2.5 s) so they can
actually be read, and motion is shown ONLY where it's real — the splash beat plays frames cut
from the SHIPPED intro asset (circle-cropped, transparent bg), and since batch 9 an animated
`docs/screens` GIF artifact plays its own recorded frames the same way. Since the v0.8 hero
re-cut, stills read the shipped SQUARE originals (`tmp-shots/screens-square/`, falling back to
the masked `docs/screens` artifact) and the GIF cuts its own circle with the splash mechanism —
one round, cut fresh, never double-masked. Canvas, fonts, caption pills and the `v<VER> ·
wear-demo` bug reuse the film's design system (see canvas_build.py + docs/DEMO.md §5).

Run:  python3 tools/demo/gif_build.py     (writes to /tmp then into docs/demo/)
"""
import os, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
GIF_TMP = os.environ.get("DEMO_GIF_TMP", "/tmp/opencode/gifbuild")
SCREENS = os.path.join(ROOT, "docs", "screens")
SQUARES = os.path.join(ROOT, "tmp-shots", "screens-square")
ASSET = os.path.join(ROOT, "app", "src", "main", "assets", "hermes-watch-intro.mp4")
_DEVKIT = os.path.join(os.path.dirname(ROOT), "devkit", "demo")
FONT_DIR = os.environ.get("DEMO_FONTS", os.path.join(_DEVKIT, "fonts"))
ART = os.path.join(_DEVKIT, "art", "hero-source.png")
with open(os.path.join(ROOT, "VERSION")) as fh:
    VER = fh.read().strip()

W, H = 480, 854                    # shipped GIF scale (0.4444 of the 1080x1920 film canvas)
FP = 0.4444                        # geometry scale from canvas_build.py
FPS = 12
# DEMO_MASTER=1 → full 1080x1920 @24 fps and the output is the scored-master MP4 instead of the
# GIF (owner rule: mp4 masters are NEVER committed — this one lands in the tmp dir for YouTube).
MASTER = os.environ.get("DEMO_MASTER") == "1"
if MASTER:
    W, H, FP, FPS = 1080, 1920, 1.0, 24
    GIF_TMP = os.environ.get("DEMO_GIF_TMP", "/tmp/opencode/gifbuild-master")
COBALT = (60, 99, 221)
INKBG = (11, 11, 20, 205)
PAPER = (232, 232, 242)

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
    vig = vig.filter(ImageFilter.GaussianBlur(max(2, round(220 * FP)))).point(lambda p: 255 - min(90, (255 - p) // 2))
    base = Image.composite(base, Image.new("RGB", (W, H), (0, 0, 0)), vig)
else:
    base = Image.new("RGB", (W, H), (7, 7, 14))
base = base.convert("RGBA")
bug = ImageDraw.Draw(base)
bug_fnt = font("RulesCmp-Medium.ttf", 28)
bug_txt = f"v{VER} · wear-demo"
bug.text((W - tracked_w(bug, bug_txt, bug_fnt, track=2) - round(56 * FP), H - round(64 * FP)), bug_txt,
         font=bug_fnt, fill=(232, 232, 242, 130))

# ---- chrome: cobalt glass ring + white rim (same proportions as the film) ----
chrome = Image.new("RGBA", (W, H), (0, 0, 0, 0))
dc = ImageDraw.Draw(chrome)
dc.ellipse((CX - 6, CY - 6, CX + D + 6, CY + D + 6), outline=(235, 238, 248, 255), width=2)
dc.ellipse((CX - 8, CY - 8, CX + D + 8, CY + D + 8), outline=COBALT + (230,), width=3)
dc.ellipse((CX - 16, CY - 16, CX + D + 16, CY + D + 16), outline=COBALT + (70,), width=2)

def pill(canvas, cap):
    """Caption pill with EXACT centering: the box width is computed with the same tracking the
    text is drawn with (mixing the two is what shipped off-center pills once), and the font
    shrinks until the whole caption fits the canvas — clipped captions are not a thing."""
    ov = canvas.copy()
    dd = ImageDraw.Draw(ov)
    track, size = 2, 44
    while True:
        fpill = font("RulesCnd-Light.ttf", size)
        tw = tracked_w(dd, cap, fpill, track=track)
        w = tw + 36
        if w <= W - 28 or size <= 18:
            break
        size -= 2
    x0 = round((W - w) / 2)
    y0, hh = round(1150 * FP), round(112 * FP)
    dd.rounded_rectangle((x0, y0, x0 + w, y0 + hh), radius=round(34 * FP),
                         fill=INKBG, outline=COBALT + (200,), width=2)
    tracked(dd, (x0 + (w - tw) / 2, y0 + (hh - fpill.size) / 2 - 2), cap, fpill, PAPER, track=track)
    return ov

def wordmark(canvas, sub):
    """Open/close title text — TOP of the canvas, leaving the watch circle free below it.
    Owner rule (2026-11-02): no frame without the watch face; the film plays in the circle,
    the text rides the top band, measured apart so nothing overlaps (1080-scale y-positions
    ported blind is what collided once)."""
    ov = canvas.copy()
    dd = ImageDraw.Draw(ov)
    fp, fs = font("RulesCnd-Light.ttf", 52), font("RulesCmp-Medium.ttf", 26)
    wtxt = "HERMES WEAR"
    w = tracked_w(dd, wtxt, fp, track=4)
    tracked(dd, ((W - w) / 2, 40), wtxt, fp, PAPER, track=4)
    hb = dd.textbbox((0, 0), wtxt, font=fp)
    y2 = 40 + (hb[3] - hb[1]) + 14
    ws = tracked_w(dd, sub, fs, track=2)
    tracked(dd, ((W - ws) / 2, y2), sub, fs, (150, 158, 190), track=2)
    return ov

def source_path(png):
    """v0.8 hero rule: beats use the shipped SQUARE originals (this round's pixels, pre-mask);
    fall back to the masked `docs/screens` artifact when no square exists — the animated `.gif`
    beat is exactly that case."""
    sq = os.path.join(SQUARES, png)
    return sq if os.path.exists(sq) else os.path.join(SCREENS, png)

def circle_cut(img, side):
    """The GIF cuts its own round: square pixels in, circle-cropped RGBA out — the splash's
    circle_frame mechanism, so opaque corners can never leak onto the canvas and the shipped
    mask is never double-masked."""
    fr = img.resize((side, side), Image.LANCZOS).convert("RGB").convert("RGBA")
    m = Image.new("L", (side, side), 0)
    ImageDraw.Draw(m).ellipse((0, 0, side - 1, side - 1), fill=255)
    fr.putalpha(m)
    return fr

def place_still(canvas, png):
    """Composite a circle-cut still into the watch circle (glass ring on top).
    `name#i` = half i of a pair composite (see pair_half)."""
    ov = canvas.copy()
    path = pair_half(png, int(png.rsplit("#", 1)[1])) if "#" in png else source_path(png)
    ov.alpha_composite(circle_cut(Image.open(path), D - 6), (CX + 3, CY + 3))
    ov.alpha_composite(chrome)
    return ov


HALVES = os.path.join(GIF_TMP, "halves")


def pair_half(png, i):
    """The committed `*-pair.png` artifacts are TWO circle shots side by side (README gallery
    material). Inside the GIF they'd squeeze two watches into one circle — the owner's rule is
    one screen per frame — so gif_build splits them and gives each half its own beat."""
    os.makedirs(HALVES, exist_ok=True)
    out = os.path.join(HALVES, f"v{VER}-{png.replace('/', '_')}_{i}.png")  # versioned: re-shoots re-cut
    if not os.path.exists(out):
        im = Image.open(source_path(png.split("#", 1)[0])).convert("RGBA")
        # pair canvases are 964x544: circles at x 6..475 and 489..958, y 6..475 (chips below
        # belong to the README layout; the GIF's caption pill says what the chip said).
        x0 = (6, 489)[i]
        im.crop((x0, 6, x0 + 470, 476)).save(out)
    return out

# ---- the beat sheet: caption -> stills (square originals, see source_path) — v0.8 hero ----
# Splash-first (below), then one beat per shipped v0.8 feature; the animated `.gif` artifact
# plays its own recorded frames the way the splash does.
BEAT_SHOTS = [
    ("ORB DIAL - DRAG EIGHT WAYS", ["01-orb-cluster.png"]),
    ("ANSWERS STREAM AS MARKDOWN", ["02-chat-markdown.png"]),
    ("MARKDOWN TABLES - REAL GRIDS ON THE WRIST", ["02b-chat-table.png"]),
    ("REPLIES CARRY IMAGES INLINE", ["03-image-card.png"]),
    ("MERMAID DIAGRAMS - RENDERED ON YOUR BOX", ["05-mermaid.png"]),
    ("PUSH TO TALK - SPEECH-TO-TEXT ON THE WRIST", ["06-ptt-overlay.png"]),
    ("SWAP MODELS PER CHAT", ["25-model-picker.png"]),
    ("GATED COMMANDS APPROVE ON THE WRIST", ["33-approval-card.png"]),
    ("APPROVAL MODES - INHERIT, MANUAL, SMART", ["56-approval-modes.png"]),
    ("FINISHED ANSWERS BUZZ THE WRIST", ["31-notify-plain.png"]),
    ("DESKTOP RUNS PARK ON THE WRIST", ["44-notify-desktop-approval.png"]),
    ("GENERATED VIDEO AND AUDIO ON THE WRIST", ["48-media-cards.png"]),
    ("ANIMATED GIFS - DECODED AND PLAYING ON THE WRIST", ["50-gif-card.gif"]),
    ("ABOUT - VERSION AND DIAGNOSTICS", ["47-about-screen.png"]),
    ("THE EMBEDDED TUNNEL - LIVE STATUS", ["55-tailnet-status.png"]),
    ("ONE CONVERSATION - EITHER DEVICE", ["08-sessions.png"]),
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

# ---- intro film frames (circle-cropped) — cut from the SHIPPED asset ----
intro_dir = os.path.join(GIF_TMP, "intro")
if not os.path.isdir(intro_dir) or not os.listdir(intro_dir):
    os.makedirs(intro_dir, exist_ok=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", str(INTRO_S[0]),
                    "-t", f"{INTRO_S[1] - INTRO_S[0]:.1f}", "-i", ASSET,
                    "-vf", f"fps={FPS}", os.path.join(intro_dir, "i%04d.png")], check=True)

def circle_frame(path):
    return circle_cut(Image.open(path), D)

# OPEN: the shipped film plays IN the watch circle, title text rides the top band (owner rule:
# every frame carries the watch face — no text-only cards).
hero = wordmark(base, "VOICE-FIRST WEAR OS CLIENT FOR HERMES")
for p in sorted(os.listdir(intro_dir)):
    ov = hero.copy()
    ov.alpha_composite(circle_frame(os.path.join(intro_dir, p)), (CX, CY))
    ov.alpha_composite(chrome)
    emit(ov, int(1000 / FPS))

# feature beats — a `.gif` shot is an ANIMATED artifact (a real screen recording, circle-masked
# frame by frame in docs/screens): it plays inside the circle at its own cadence, the splash-film
# precedent — motion in the walkthrough only ever comes from captured pixels, never fakery.
for cap, shots in BEAT_SHOTS:
    for png in shots:
        if png.endswith(".gif"):
            bg = pill(base, cap)
            anim = Image.open(source_path(png))
            for i in range(anim.n_frames):
                anim.seek(i)
                ov = bg.copy()
                ov.alpha_composite(anim.convert("RGBA").resize((D - 6, D - 6), Image.LANCZOS), (CX + 3, CY + 3))
                ov.alpha_composite(chrome)
                emit(ov, max(80, anim.info.get("duration", 100)))
        else:
            emit(pill(place_still(base, png), cap), HOLD_STILL_MS)

# END: wordmark over the film's closing frame — the circle never goes dark.
fin = wordmark(base, "HERMES-WEAROS - OPEN SOURCE")
fin.alpha_composite(circle_frame(os.path.join(intro_dir, sorted(os.listdir(intro_dir))[-1])), (CX, CY))
fin.alpha_composite(chrome)
emit(fin, max(HOLD_HERO_MS, 3000))

# ---- concat -> palette -> gif (transparent corners preserved) ----
G = GIF_TMP
with open(f"{G}/concat.txt", "w") as fh:
    for f, ms in zip(frames, durations):
        fh.write(f"file '{f}'\nduration {ms / 1000:.3f}\n")
    fh.write(f"file '{frames[-1]}'\n")

pal = f"{G}/palette.png"
subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", f"{G}/concat.txt",
                "-vf", f"fps={FPS},palettegen=max_colors=160:stats_mode=diff", pal], check=True)
out_tmp = f"{G}/demo-walkthrough-v{VER}.gif" if not MASTER else f"{G}/demo-vertical-v{VER}-raw.mp4"
if MASTER:
    # Scored master: 9:16 h264, corners flattened to black (players hate alpha), loudnorm-free,
    # silent — the owner scores it with a Hermes agent before the YouTube upload. NEVER committed.
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", f"{G}/concat.txt",
                    "-vf", "fps=30,format=yuv420p", "-c:v", "libx264", "-crf", "19", "-preset", "slow",
                    "-movflags", "+faststart", "-metadata",
                    f"comment=hermes-wearos v{VER} (wear-demo capture, silent master)",
                    out_tmp], check=True)
else:
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", f"{G}/concat.txt",
                    "-i", pal, "-lavfi", f"fps={FPS} [x];[x][1:v]paletteuse=dither=bayer:bayer_scale=4",
                    "-loop", "0", "-metadata", f"comment=hermes-wearos v{VER} (wear-demo capture)",
                    out_tmp], check=True)
dst = (f"/tmp/opencode/vid/demo-vertical-v{VER}-raw.mp4" if MASTER
       else os.path.join(ROOT, "docs", "demo", f"demo-walkthrough-v{VER}.gif"))
os.makedirs(os.path.dirname(dst), exist_ok=True)
subprocess.run(["cp", out_tmp, dst], check=True)
print(f"walkthrough gif: {os.path.getsize(dst) / 1e6:.1f} MB, {len(frames)} frames, "
      f"{sum(durations) / 1000:.1f}s loop -> {dst}")
