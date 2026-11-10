#!/usr/bin/env bash
# s6 cont-init: seed the Wear Kit plugin into the gateway's plugin dir (image is
# the source of truth — re-copied every boot so a container restart picks up the
# baked branch slice, not a stale volume copy).
set -eu
DEST=/opt/data/plugins/wearos
mkdir -p "$DEST"
rsync -a --delete /opt/wearos/plugin/ "$DEST/" 2>/dev/null \
  || { rm -rf "$DEST"; cp -a /opt/wearos/plugin "$DEST"; }
