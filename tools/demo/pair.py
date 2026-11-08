#!/usr/bin/env python3
"""Side-by-side round-frame pair (41/46 style): two masked circles + caption pills."""
import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

left, lcap = sys.argv[1], sys.argv[2]
right, rcap = sys.argv[3], sys.argv[4]
out = sys.argv[5]
DEVKIT = "/run/media/clay/Data/dev/hermes-wearos-worktrees/devkit/demo/fonts"

S = 420            # frame size (v0.7 pair geometry: 964x544)
GAP = 44
PAD_X = 40
CAP_H = 62
BG = (11, 11, 20)
PAPER = (232, 232, 242)
COBALT = (60, 99, 221)

def font(sz):
    return ImageFont.truetype(DEVKIT + "/Aeonik.ttf", sz)

def load(p):
    im = Image.open(p).convert("RGBA")
    im.thumbnail((S, S), Image.LANCZOS)
    # center on square canvas
    c = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    c.paste(im, ((S - im.width) // 2, (S - im.height) // 2))
    return c

W = PAD_X * 2 + S * 2 + GAP
H = CAP_H + S + CAP_H
canvas = Image.new("RGBA", (W, H), BG + (255,))
d = ImageDraw.Draw(canvas)
for i, (path, cap) in enumerate([(left, lcap), (right, rcap)]):
    x = PAD_X + i * (S + GAP)
    fr = load(path)
    canvas.alpha_composite(fr, (x, CAP_H))
    f = font(24)
    tw = d.textlength(cap, font=f)
    cx = x + S // 2
    pill = (cx - tw // 2 - 16, H - CAP_H + 14, cx + tw // 2 + 16, H - 12)
    d.rounded_rectangle(pill, radius=17, fill=(255, 255, 255, 26), outline=COBALT + (255,), width=2)
    d.text((cx - tw // 2, H - CAP_H + 22), cap, font=f, fill=PAPER)
canvas.convert("RGB").save(out)
print("pair ->", out, canvas.size)
