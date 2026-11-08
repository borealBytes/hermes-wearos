#!/usr/bin/env python3
"""Terminal-shot re-renderer for scripts/docs-vm.

Takes captured CLI text (VERBATIM — this script only escapes and themes it) and
renders a GitHub-dark terminal page matching the repo's docs theme
(docs/screens/60-plugin-card.png: bg #0d1117, mono, #c9d1d9 text). The PNG is
produced from this HTML by capture.sh via playwright-cli, so every glyph is born
in clean pixels — PII redaction happens on the TEXT before rendering (capture.sh
seds the tailnet handle), never on the image.

Usage: render_terminal.py --cmd 'hermes watch setup' --text-file out.txt \
       --title 'fresh VM — no credential yet' > page.html
Lines starting with '$ ' (the command echo) are tinted the accent color; "ok":
true/false values go green/red — decoration on verbatim text, like syntax
highlighting. Nothing is reflowed, wrapped differently, or invented.
"""
import argparse
import html
import re
import sys

PAGE = """<!doctype html>
<html><head><meta charset="utf-8"><style>
  html,body{margin:0;background:#0d1117;width:@@WIDTH@@px;}
  .term{font:13px/1.55 "DejaVu Sans Mono",ui-monospace,monospace;color:#c9d1d9;
        padding:0 0 22px 0;width:@@WIDTH@@px;}
  .bar{display:flex;align-items:center;gap:8px;height:38px;padding:0 14px;
       background:#161b22;border:1px solid #21262d;border-bottom:none;
       border-radius:10px 10px 0 0;margin-top:22px;}
  .dot{width:12px;height:12px;border-radius:50%;display:inline-block;}
  .title{flex:1;text-align:center;color:#8b949e;font:13px/1 "DejaVu Sans Mono",monospace;margin-right:44px;}
  .body{background:#0d1117;border:1px solid #21262d;border-radius:0 0 10px 10px;padding:18px 20px;}
  .cmd{color:#7ee787;} .cmd .p{color:#8b949e;}
  .out{white-space:pre;}
  .t{color:#3fb950;} .f{color:#f85149;}
  .fix{color:#8b949e;}
</style></head><body><div class="term">
  <div class="bar"><span class="dot" style="background:#ff5f56"></span><span class="dot" style="background:#ffbd2e"></span><span class="dot" style="background:#27c93f"></span><span class="title">@@TITLE@@</span></div>
  <div class="body"><div class="cmd"><span class="p">$ </span>@@CMD@@</div>
<div class="out">@@BODY@@</div></div>
</div></body></html>
"""

def theme(line: str) -> str:
    e = html.escape(line, quote=False)
    e = re.sub(r'"ok": true', lambda m: f'<span class="t">{m.group(0)}</span>', e)
    e = re.sub(r'"ok": false', lambda m: f'<span class="f">{m.group(0)}</span>', e)
    e = re.sub(r'"✓"', lambda m: f'<span class="t">{m.group(0)}</span>', e)
    e = re.sub(r'^(\s*red light\?.*|  (?:set it|two-section diff|re-run|NOTE:|·|✓|✗).*)$',
               lambda m: f'<span class="fix">{m.group(1)}</span>', e)
    return e

def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cmd", required=True, help="the command line shown after $")
    ap.add_argument("--text-file", required=True, help="verbatim captured output")
    ap.add_argument("--title", default="hermes-docs — zsh", help="fake window title (must be PII-clean)")
    a = ap.parse_args()
    body = open(a.text_file, encoding="utf-8").read().rstrip("\n")
    # sanity: refuse to be the lie detector — body must NOT have been rewritten
    # width fits the longest line (DejaVu mono 14px ≈ 8.43px/char) so no glyph is
    # ever cut; clamped to 2000px (the widest ceremony line)
    longest = max(len(l) for l in body.splitlines() + ["$ " + a.cmd])
    width = max(960, min(2000, int(longest * 7.83) + 44))
    out = (PAGE.replace("@@TITLE@@", html.escape(a.title))
               .replace("@@CMD@@", html.escape(a.cmd))
               .replace("@@WIDTH@@", str(width))
               .replace("@@BODY@@", "\n".join(theme(l) for l in body.splitlines())))
    sys.stdout.write(out)

if __name__ == "__main__":
    main()
