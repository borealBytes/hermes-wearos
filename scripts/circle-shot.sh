#!/usr/bin/env bash
# Circle-masked screenshot (owner 2026-10-30): *"screenshots … come in as the square and …
# you need to apply the circle mask first and then look at that circle-masked image, because
# that's what I see on screen"*. A Wear screencap is the full SQUARE panel buffer; the corners
# never exist on the round glass, so an unmasked capture can "prove" pixels the owner never
# saw. Every screenshot used for visual analysis goes through here (SPEC §14).
#
#   scripts/circle-shot.sh out.png                  # capture $SERIAL/ANDROID_SERIAL + mask
#   scripts/circle-shot.sh --mask in.png out.png    # mask an existing square capture
#
# The mask is the inscribed circle (r = min/2, physical glass). Pixels outside become
# TRANSPARENT — the dead corner zone, gone exactly as it is gone on the round glass — and the
# frame is never cropped, so it stays 1:1 with the panel. Shipped stills (docs/screens/*) MUST
# carry alpha: an opaque black-corner square is what the analysis frame looks like on the watch,
# but on the README's white page it reads as a black plate — the bank has always shipped as a
# true circle. scripts/demo-check.sh asserts corner alpha == 0 on every committed still.
set -euo pipefail

mask() { # mask <in> <out>
  python3 - "$1" "$2" <<'PY'
import sys
from PIL import Image, ImageDraw
src, dst = sys.argv[1], sys.argv[2]
im = Image.open(src).convert("RGB")
w, h = im.size
r = min(w, h) // 2
mask = Image.new("L", (w, h), 0)
ImageDraw.Draw(mask).ellipse(((w - r * 2) // 2, (h - r * 2) // 2, (w + r * 2) // 2, (h + r * 2) // 2), fill=255)
im.putalpha(mask)
im.save(dst)
PY
}

if [ "${1:-}" = "--mask" ]; then
  mask "${2:?usage: circle-shot.sh --mask in.png out.png}" "${3:?}"
  exit 0
fi

out="${1:?usage: circle-shot.sh out.png | circle-shot.sh --mask in.png out.png}"
adb_args=()
[ -n "${SERIAL:-}" ] && adb_args=(-s "$SERIAL")
tmp="$(mktemp /tmp/circleshoot.XXXXXX.png)"
trap 'rm -f "$tmp"' EXIT
adb "${adb_args[@]}" exec-out screencap -p > "$tmp"
mask "$tmp" "$out"
echo "circle-masked: $out"
