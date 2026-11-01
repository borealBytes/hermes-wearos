import os
DEMO = os.environ.get("DEMO_TMP", "/tmp/opencode")
#!/usr/bin/env python3
"""Build harbor canvas + circle mask + caption overlays for the vertical demo."""
from PIL import Image, ImageDraw, ImageFilter, ImageFont
import os

OUT = DEMO + "/vid/canvas"
os.makedirs(OUT, exist_ok=True)
W, H = 1080, 1920
COBALT = (60, 99, 221)      # #3C63DD
INKBG = (11, 11, 20, 205)   # #0B0B14 @80%
PAPER = (232, 232, 242)     # #E8E8F2
# Art/font locations: env-overridable, default to the folder-local devkit next to the repo.
_DEVKIT = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))), "devkit", "demo")
FONT_DIR = os.environ.get("DEMO_FONTS", os.path.join(_DEVKIT, "fonts"))

def font(name, size):
    return ImageFont.truetype(os.path.join(FONT_DIR, name), size)

def tracked(d, xy, text, fnt, fill, track=6):
    x, y = xy
    for ch in text:
        d.text((x, y), ch, font=fnt, fill=fill)
        x += d.textlength(ch, font=fnt) + track
    return x - track

def tracked_w(d, text, fnt, track=6):
    return sum(d.textlength(c, font=fnt) + track for c in text) - track

# ---- 1) base canvas: draft-1 harbor cover-cropped, dimmed, vignetted ----
art = Image.open(os.environ.get("DEMO_ART", os.path.join(_DEVKIT, "art", "hero-source.png"))).convert("RGB")
s = max(W / art.width, H / art.height)
art = art.resize((int(art.width * s), int(art.height * s)), Image.LANCZOS)
l, t = (art.width - W) // 2, (art.height - H) // 2
base = art.crop((l, t, l + W, t + H))
base = base.point(lambda p: int(p * 0.82))
vig = Image.new("L", (W, H), 0)
dv = ImageDraw.Draw(vig)
dv.ellipse((-W // 2, -H // 2, W * 3 // 2, H * 3 // 2), fill=255)
vig = vig.filter(ImageFilter.GaussianBlur(220)).point(lambda p: 255 - min(90, (255 - p) // 2))
dark = Image.new("RGB", (W, H), (0, 0, 0))
base = Image.composite(base, dark, vig).convert("RGBA")
# ---- version bug (owner rule): every film frame carries the release version + the test
# agent it was captured on; bg.png is under every beat, so one stamp persists whole film+GIF.
VER_FILE = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "VERSION")
with open(VER_FILE) as _fh:
    DEMO_VERSION = _fh.read().strip()
bug = ImageDraw.Draw(base)
bug_fnt = font("RulesCmp-Medium.ttf", 28)
bug_txt = f"v{DEMO_VERSION} · wear-demo"
bug_xy = (W - tracked_w(bug, bug_txt, bug_fnt, track=4) - 56, H - 64)
bug.text(bug_xy, bug_txt, font=bug_fnt, fill=(232, 232, 242, 130))
base.save(f"{OUT}/bg.png")

# ---- 2) circle hole mask for the watch clip (640 box at (220,400)) ----
CX, CY, D = 160, 280, 760          # circle = 70% of canvas width (owner: bigger)
R = D // 2
ccx, ccy = CX + R, CY + R
mask = Image.new("RGBA", (D, D), (0, 0, 0, 0))
dm = ImageDraw.Draw(mask)
dm.ellipse((0, 0, D - 1, D - 1), fill=(255, 255, 255, 255))
mask.save(f"{OUT}/circle.png")

# ---- 2a) splash face: owner-provided art, circle-masked, fills any beat with no capture
SPLASH = os.environ.get("DEMO_SPLASH", os.path.join(_DEVKIT, "art", "hermes-watch-splash.png"))
sp = Image.open(SPLASH).convert("RGB")
side = (D - 8) // 2 * 2          # sit inside the white edge ring
sp = sp.resize((side, side), Image.LANCZOS)
m = Image.new("L", (side, side), 0)
ImageDraw.Draw(m).ellipse((0, 0, side - 1, side - 1), fill=255)
sp = sp.convert("RGBA"); sp.putalpha(m)
sp.save(f"{OUT}/splash_circle.png")

# ---- 2b) white screen-edge ring (2px-ish, reads as the watch glass rim) ----
ring = Image.new("RGBA", (W, H), (0, 0, 0, 0))
dr = ImageDraw.Draw(ring)
dr.ellipse((CX - 1, CY - 1, CX + D + 1, CY + D + 1), outline=(235, 238, 248, 255), width=3)
dr.ellipse((CX - 4, CY - 4, CX + D + 4, CY + D + 4), outline=(235, 238, 248, 70), width=2)
ring.save(f"{OUT}/ring.png")

# ---- 3) chrome overlay: cobalt ring + faint guide line (no pill here) ----
chrome = Image.new("RGBA", (W, H), (0, 0, 0, 0))
dc = ImageDraw.Draw(chrome)
dc.ellipse((CX - 8, CY - 8, CX + D + 8, CY + D + 8), outline=COBALT + (230,), width=6)
dc.ellipse((CX - 18, CY - 18, CX + D + 18, CY + D + 18), outline=COBALT + (70,), width=3)

# ---- 4) per-beat caption pills ----
BEATS = [
    "HERMES WEAR",
    "ORB DIAL - EIGHT LANES AROUND THE GLANCE",
    "PUSH TO TALK - ON-WATCH SPEECH-TO-TEXT",
    "STREAMED ANSWERS + LIVE TOOL CALLS",
    "TABLES - MERMAID - IMAGES, INLINE",
    "STOP ANY RUN MID-FLIGHT",
    "TAP A CARD - ZOOM AND PAN",
    "SWAP MODELS PER CHAT",
    "GATED COMMANDS APPROVE ON THE WATCH",
    "SEARCH EVERY SESSION FROM THE WRIST",
    "ARCHIVE AND UNARCHIVE - NOTHING LOST",
    "APPROVALS REACH YOU OFF THE WRIST",
    "DEEP-LINK STRAIGHT BACK INTO THE CHAT",
    "FONT SCALE - YOUR EYES, YOUR SIZE",
    "ONE GLANCE FROM THE LAUNCHER",
    "YOUR GATEWAY, ON YOUR WRIST",
]
fpill = font("RulesCnd-Light.ttf", 44)
fsmall = font("RulesCmp-Medium.ttf", 30)
probe = ImageDraw.Draw(Image.new("RGBA", (1, 1)))
for i, cap in enumerate(BEATS):
    ov = chrome.copy()
    dd = ImageDraw.Draw(ov)
    if i in (0, len(BEATS) - 1):
        # hero/end: wordmark centered, no watch circle
        wtxt = "HERMES WEAR" if i == 0 else "HERMES WEAR"
        w = tracked_w(probe, wtxt, fpill)
        tracked(dd, ((W - w) / 2, 1210 if i == 0 else 1210), wtxt, fpill, PAPER, track=10)
        sub = "VOICE-FIRST WEAR OS CLIENT FOR HERMES" if i == 0 else "HERMES-WEAROS - OPEN SOURCE"
        ws = tracked_w(probe, sub, fsmall)
        tracked(dd, ((W - ws) / 2, 1290 if i == 0 else 1290), sub, fsmall, (150, 158, 190), track=4)
    else:
        w = tracked_w(probe, cap, fpill) + 72
        x0 = (W - w) / 2
        y0, hh = 1150, 112
        dd.rounded_rectangle((x0, y0, x0 + w, y0 + hh), radius=34, fill=INKBG, outline=COBALT + (200,), width=3)
        tracked(dd, (x0 + 36 - 6, y0 + (hh - 46) / 2 - 4), cap, fpill, PAPER)
    ov.save(f"{OUT}/ov_{i:02d}.png")
print("canvas files done:", len(BEATS))
