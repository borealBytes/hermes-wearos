#!/usr/bin/env python3
"""Block-pixel + blur REDACTION for shipped pixels (docs/DEMO.md §0: owner identity is pixelated).

Cuts a mosaic+blur over named rectangles of a SQUARE capture; alpha-preserving (the circle-masked
PNGs keep their transparent outside — plain cv2.imread would silently square the circle). Boxes
are given in native panel pixels: x,y,w,h. Apply on the SQUARE original, mask AFTER (ledger rule).

  python3 tools/demo/pixelate.py in.png out.png x,y,w,h [x,y,w,h ...]
"""
import sys
import cv2
import numpy as np


def redact(path_in, path_out, boxes):
    im = cv2.imread(path_in, cv2.IMREAD_UNCHANGED)  # keeps alpha
    has_alpha = im is not None and im.ndim == 3 and im.shape[2] == 4
    rgb = im[..., :3]
    for (x, y, w, h) in boxes:
        x, y, w, h = max(0, x), max(0, y), max(1, w), max(1, h)
        roi = rgb[y:y + h, x:x + w]
        block = max(4, h // 3)  # ledger: block >= height/3
        small = cv2.resize(roi, (max(1, w // block), max(1, h // block)), interpolation=cv2.INTER_AREA)
        big = cv2.resize(small, (w, h), interpolation=cv2.INTER_NEAREST)
        big = cv2.GaussianBlur(big, (0, 0), max(1.5, block / 4))
        rgb[y:y + h, x:x + w] = big
    out = im
    if has_alpha:
        out = np.dstack([rgb, im[..., 3]])
    cv2.imwrite(path_out, out)
    print(f"redacted {len(boxes)} box(es) -> {path_out}")


def parse(spec):
    boxes = []
    for s in spec:
        x, y, w, h = (int(v) for v in s.split(","))
        boxes.append((x, y, w, h))
    return boxes


if __name__ == "__main__":
    if len(sys.argv) < 5:
        print(__doc__)
        sys.exit(2)
    redact(sys.argv[1], sys.argv[2], parse(sys.argv[3:]))
