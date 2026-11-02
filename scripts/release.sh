#!/usr/bin/env bash
# Bump the single-source version (repo-root VERSION) — standard semver.
#   scripts/release.sh 0.7.0
# Checks the shape, shows every surface that will pick it up, and prints the tag command.
# Tagging happens on the MERGE commit on main (not the branch), so release.sh never tags itself:
#   git tag -a v$(cat VERSION) -m "release $(cat VERSION)" && git push origin --tags
set -euo pipefail
cd "$(dirname "$0")/.."
V="${1:-}"
[ -n "$V" ] || { echo "usage: scripts/release.sh <major.minor.patch>"; exit 2; }
echo "$V" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "not semver: $V"; exit 2; }
OLD=$(scripts/version.sh)
echo "$V" > VERSION
echo "VERSION: $OLD -> $V"
cat <<EOF
Picked up automatically, no other edits:
  • app versionName/versionCode      gradle reads VERSION ($( [ -f app/build.gradle.kts ] && echo 'major*10000+minor*100+patch'))
  • watchkit /health + MCP version   server/watchkit/watchkit.mjs reads VERSION
  • in-app About + Settings diag     reads packageManager.versionName
  • demo pipeline                    canvas bug "v$V · wear-demo", demo-vertical-v$V-raw.mp4,
                                     demo-walkthrough-v$V.gif (+ container metadata)
After merge to main:
  git tag -a v$V -m "release $V" && git push origin --tags
Then re-cut media for the release (CONTRIBUTING MEDIA RULE) — every film/GIF a viewer sees
must carry this version.
EOF
