#!/usr/bin/env python3
"""Privacy-pass v2 — remapped from the 2x crop; banners solid-filled; unchecked rows
mosaic'd; checked rows kept crisp (amber ring on weardev/clay-synth = the clone demo)."""
from PIL import Image, ImageDraw

W = "/home/clay/.hermes/profiles/weardev/cache/scratch/shots"
ORIG_TOP = 640  # crop->orig y mapping

def mosaic(im, box, factor=14):
    x1, y1, x2, y2 = [int(v) for v in box]
    crop = im.crop((x1, y1, x2, y2))
    small = crop.resize((max(1, (x2-x1)//factor), max(1, (y2-y1)//factor)), Image.BILINEAR)
    im.paste(small.resize(crop.size, Image.BILINEAR), (x1, y1))

def ring(im, box, color=(245, 166, 35), width=4, radius=10):
    ImageDraw.Draw(im).rounded_rectangle([int(v) for v in box], radius=radius, outline=color, width=width)

# ---- pane.png: rebuild from the ORIGINAL (v1 file was one-row-low)
pane = Image.open(f"{W}/pane.png").convert("RGBA")
pane.paste(Image.new("RGB", (317, 66), (26, 29, 34)), (18, 232))   # banner: flat card-color fill, no bleed
# unchecked name areas (crop coords -> orig /2 + 640); x col 30..300 crop -> 15..150 orig... but keep
# checkbox + name: blur x 30..300 crop => 15..150 orig; widen to also eat long names fully.
unchecked_crop_y = [111, 149, 187, 225, 263, 301, 339, 377, 415, 453, 491, 529, 567, 605,
                    643, 681, 719, 757, 795, 833, 871]
for cy in unchecked_crop_y:
    oy = cy / 2 + ORIG_TOP
    mosaic(pane, (16, int(oy) - 1, 152, int(oy) + 16))
ring(pane, (14, int(985/2 + ORIG_TOP) - 4, 340, int(1015/2 + ORIG_TOP) + 2), width=3)  # weardev row + clay-synth chip
pane.convert("RGB").save(f"{W}/wearkit-pane.jpg", quality=90)

# ---- settings.png (v1 verified clean; re-run identical)
st = Image.open(f"{W}/settings.png").convert("RGBA")
mosaic(st, (228, 78, 1108, 158))
for box in [(940, 421, 1116, 451), (940, 531, 1116, 561), (940, 626, 1116, 656),
            (940, 826, 1116, 856), (940, 896, 1116, 926)]:
    mosaic(st, box, factor=6)
ring(st, (228, 875, 1108, 1025))
st.convert("RGB").save(f"{W}/plugin-settings.jpg", quality=90)

# ---- plugins.png (v1 verified clean; re-run identical)
pl = Image.open(f"{W}/plugins.png").convert("RGBA")
mosaic(pl, (10, 168, 370, 290))
mosaic(pl, (10, 405, 370, 478))
pl.convert("RGB").save(f"{W}/plugins-list.jpg", quality=90)
print("ok")
