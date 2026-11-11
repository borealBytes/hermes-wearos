#!/usr/bin/env python3
"""demo_synth.py — ONE product call, whole pitch text.
POSTs {action:soundtest} with the full narration to the container dashboard;
tts_air.synth chunks per sentence (KV ceiling) and stitches internally, so the
demo pack audio is byte-for-byte what the watch would receive (opus aside).
Usage: demo_synth.py <token> <text-file> <out.wav>"""
import base64, json, sys, urllib.request

token, text_file, out = sys.argv[1:4]
text = open(text_file).read().strip()
body = json.dumps({"action": "soundtest", "text": text, "agent": "default"}).encode()
req = urllib.request.Request("http://127.0.0.1:9119/api/plugins/wearos/voice",
                             data=body, headers={"Content-Type": "application/json",
                                                 "Authorization": f"Bearer {token}"})
d = json.load(urllib.request.urlopen(req, timeout=1800))
wav = base64.b64decode(d["audio_wav_b64"]) if "audio_wav_b64" in d else b""
if not wav:
    print("soundtest returned no audio:", json.dumps(d)[:400], file=sys.stderr)
    sys.exit(3)
open(out, "wb").write(wav)
print(f"recorded {len(wav)/1e6:.2f} MB, {d.get('words', 0) or ''}{len(d.get('words', []))} words, engine={d.get('engine')}, tier={d.get('tier_note', d.get('tier'))}")
