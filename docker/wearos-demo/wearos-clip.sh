#!/bin/bash
# wearos-clip.sh — render the hackathon demo clip INSIDE the demo container.
# Inputs (same dir): narration.wav (24 kHz mono PCM16), narration.txt,
#                   pane-before.png, pane-play.png
# Output: wearos-voice-demo.mp4 (1280x720@30, AAC 48k)
set -euo pipefail
cd "$(dirname "$0")"
command -v ffmpeg >/dev/null || { echo "no ffmpeg" >&2; exit 2; }
for f in narration.wav narration.txt pane-before.png pane-play.png; do
  [ -f "$f" ] || { echo "missing $f" >&2; exit 2; }
done

DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 narration.wav)
WS=9.0                                   # waveform/caption window start
TOT=$(python3 - "$DUR" "$WS" <<'PY'
import sys
d, ws = float(sys.argv[1]), float(sys.argv[2])
print(round(max(20.0, ws + d + 1.6), 2))
PY
)
END_FADE=$(python3 -c "print(round(float('$TOT')-0.5,2))")
AF_OUT=$(python3 -c "print(round(float('$DUR')-0.45,2))")

# --- audio pass: 55 Hz high-pass tames the codec's 24k rumble (upsampling to
#     48k would otherwise double its level), gentle lift + limiter + fades
ffmpeg -y -v error -i narration.wav \
  -af "highpass=f=55,aresample=48000,volume=1.6,alimiter=limit=0.95,afade=t=in:st=0:d=0.25,afade=t=out:st=$AF_OUT:d=0.45" \
  -ar 48000 -ac 1 track.wav

# --- waveform strip, PTS shifted so playback aligns with the caption window
ffmpeg -y -v error -i narration.wav -filter_complex \
  "[0:a]showwaves=s=1180x170:r=30:mode=p2p:colors=0x35d0ba,setpts=PTS+$WS/TB,format=yuv420p[w]" \
  -map "[w]" -an -t "$DUR" waveform.mp4

# --- filtergraph builder: this ffmpeg ignores filter-level quoting, so every
#     option value is backslash-escaped instead (fex = expression option)
python3 - "$TOT" "$DUR" "$WS" "$END_FADE" > filter.txt <<'PY'
import sys, re
tot, dur, ws, ef = (float(x) for x in sys.argv[1:5])
text = open("narration.txt").read()
sents = [s.strip().replace("\n", " ") for s in re.split(r"(?<=[.!?]) +", text.replace("\n\n", " ").replace("\n", " ")) if s.strip()]

def esc(t):  # drawtext text: escape graph+filter specials
    t = t.replace("'", "\u2019")
    for ch in ("\\", ":", ",", ";", "[", "]"):
        t = t.replace(ch, "\\" + ch)
    return t

def fex(s):  # alpha/enable expressions: escape separators (no quotes)
    for ch in (",", ";", "[", "]"):
        s = s.replace(ch, "\\" + ch)
    return s

def wrap(t, width=58):
    words, lines, cur = t.split(), [], ""
    for w in words:
        if len(cur) + len(w) + 1 > width and cur:
            lines.append(cur); cur = w
        else:
            cur = (cur + " " + w).strip()
    if cur: lines.append(cur)
    return lines

F  = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"
C  = "0xe6edf3"; CA = "0x35d0ba"; CM = "0x8b949e"
W  = "(w-text_w)/2"
def dt(txt, x, y, size, color, a="1"):
    return (f"drawtext=fontfile={F}:text={esc(esc(txt))}:fontcolor={color}"
            f":fontsize={size}:x={esc(x)}:y={y}:alpha={a}")

g = ["[0:v]drawbox=x=0:y=0:w=1280:h=720:color=0x101418:t=fill"]
g[-1] += "," + dt("HERMES WEAR OS", W, 250, 64, C, fex("if(lt(t,0.2),0,min(1,(t-0.2)/0.3))"))
g[-1] += "," + dt("voice-out \u2014 your voice, your gateway", W, 340, 26, CA, fex("if(lt(t,0.5),0,min(1,(t-0.5)/0.4))"))
cards = [("NEUTTS AIR Q8", "community GGUF \u00b7 CPU \u00b7 57 s cold"),
         ("OFFICIAL NEUCODEC", "gated \u00b7 sha256-pinned \u00b7 mirror-proven"),
         ("CLAY REAL-VOICE CARD", "zero-shot clone \u00b7 6.55 s reference")]
for i, (h, s) in enumerate(cards):
    t0 = 1.3 + i * 0.4
    a = fex(f"if(lt(t,{t0}),0,min(1,(t-{t0})/0.4))")
    y = 420 + i * 62
    g[-1] += "," + dt(h, W, y, 30, C, a)
    g[-1] += "," + dt(s, W, y + 32, 20, CM, a)
g[-1] += "[v0]"   # terminate the first chain, not a new segment

g.append("[1:v]scale=1280:720:force_original_aspect_ratio=increase,crop=1280:720,fade=t=in:st=4.2:d=0.35[vpan1]")
g.append("[2:v]scale=1280:720:force_original_aspect_ratio=increase,crop=1280:720,fade=t=in:st=7.4:d=0.35[vpan2]")
g.append("[v0][vpan1]overlay=0:0:enable=" + fex("between(t,4.2,7.55)") + "[v1]")
g.append("[v1][vpan2]overlay=0:0:enable=" + fex("gte(t,7.4)") + "[v2]")

we = ws + dur
g.append("[v2]drawbox=x=50:y=500:w=1180:h=170:color=0x0b0f14@0.85:t=fill:enable=" + fex(f"between(t,{ws},{we:.2f})") + "[v3]")
g.append("[v3][3:v]overlay=x=50:y=500:enable=" + fex(f"between(t,{ws},{we:.2f})") + "[v4]")

total = sum(len(s) for s in sents)
lab = "v4"; i = 0; cur = ws
for s in sents:
    d = max(1.4, dur * len(s) / total)
    a, b = cur, min(cur + d, we)
    cur = b
    lines = wrap(s)[:2]
    for j, line in enumerate(lines):
        y = (306 + j * 36) if len(lines) > 1 else 322
        g.append(f"[{lab}]" + dt(line, W, y, 25, C,
                   fex(f"if(between(t,{a:.2f},{b-0.25:.2f}),1,0)")) + f"[v5_{i}]")
        lab = f"v5_{i}"; i += 1
g.append(f"[{lab}]fade=t=out:st={ef}:d=0.5[vout]")
print(";".join(g))
PY

ffmpeg -y -v error \
  -f lavfi -i "color=c=0x101418:s=1280x720:r=30:d=$TOT" \
  -loop 1 -i pane-before.png \
  -loop 1 -i pane-play.png \
  -i waveform.mp4 \
  -i track.wav \
  -filter_complex "$(cat filter.txt)" -map "[vout]" -map 4:a -c:a aac -b:a 192k \
  -c:v libx264 -preset medium -crf 20 -pix_fmt yuv420p -r 30 \
  -shortest -movflags +faststart wearos-voice-demo.mp4
echo "wrote wearos-voice-demo.mp4"
ffprobe -v error -show_entries format=duration -of csv=p=0 wearos-voice-demo.mp4
