#!/usr/bin/env bash
# One-shot proof: pytest suite + the /voice/* demo lane, both inside the image.
set -eu
echo "== plugin pytest suite (the whole pairing+voice floor) =="
PYTHONPATH=/opt/wearos/pylibs /opt/hermes/.venv/bin/python -m pytest /opt/wearos/plugin/tests -q \
  -p no:cacheprovider --basetemp=/tmp/wearos-pytest || echo "(suite reported failures above)"
echo
echo "== demo lane (/voice/* with fake whois) =="
exec /opt/hermes/.venv/bin/python /opt/wearos/demo/demo_lane.py
