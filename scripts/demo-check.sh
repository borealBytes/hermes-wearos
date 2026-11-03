#!/usr/bin/env bash
# Coverage gate (docs/DEMO.md §8.1): feature ledger ⇄ docs/screens ⇄ statuses.
# Default: report. `--final`: exit 1 unless EVERY §2 row is `shot ✓` and every
# screenshot exists (the assembly-complete state).
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - "$@" <<'EOF'
import re, sys, os
final = "--final" in sys.argv
rows = []
for line in open("docs/DEMO.md", encoding="utf-8"):
    if not re.match(r"\|\s*\d+\s*\|", line):
        continue
    cells = [c.strip() for c in line.strip().strip("|").split("|")]
    if len(cells) != 5:
        continue
    shots = re.findall(r"`([^`]+\.png)`", cells[2])
    rows.append((cells[0], cells[1], shots, cells[4]))
missing, pending = [], []
for n, feat, shots, st in rows:
    for s in shots:
        if not os.path.exists(os.path.join("docs/screens", s)):
            missing.append((n, feat, s))
    if st != "shot ✓" and not st.startswith("rides") and st != "n/a":
        # "n/a" = row retired/merged; "rides …" = proven through another row's shot (§2 notes)
        pending.append((n, feat, st))
print(f"ledger rows: {len(rows)}  |  shots missing from docs/screens: {len(missing)}  |  rows not 'shot ✓': {len(pending)}")
for n, feat, s in missing:
    print(f"  MISSING  #{n} {feat[:44]:<44} {s}")
for n, feat, st in pending:
    if not any(n == m and feat == f for m, f, _ in missing):
        print(f"  {st:<9} #{n} {feat[:44]}")
# Circle invariant (DEMO.md §0): committed SCREEN-SHAPED stills (docs/screens) ship as
# TRANSPARENT circles — the corners never existed on the round glass. An opaque black-corner
# square is the batch-9 relapse this check exists to catch. The tall poster GIFs in docs/demo
# are harbor-canvas art (opaque by design) and are NOT subject to the invariant.
squares = []
import glob
try:
    from PIL import Image
    for pat in ("docs/screens/*.png", "docs/screens/*.gif"):
        for p in glob.glob(pat):
            im = Image.open(p).convert("RGBA")
            if im.getpixel((2, 2))[3] != 0 or im.getpixel((im.width - 3, im.height - 3))[3] != 0:
                squares.append(p)
except ImportError:
    print("  (PIL missing — circle-invariant audit skipped)")
for p in squares:
    print(f"  SQUARE (needs circle mask): {p}")
sys.exit(1 if final and (missing or pending or squares) else 0)
EOF
