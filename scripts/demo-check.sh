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
sys.exit(1 if final and (missing or pending) else 0)
EOF
