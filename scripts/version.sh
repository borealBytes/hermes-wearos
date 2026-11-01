#!/usr/bin/env bash
# The single source of truth for the app/watchkit/demo-media version (standard semver).
# Every consumer reads THIS file: app/build.gradle.kts (versionName/versionCode),
# server/watchkit/watchkit.mjs (/health + MCP version), tools/demo/* (output filenames,
# burned canvas bug), scripts/release.sh. Bump here, nowhere else.
#
# Release cadence (owner rule): bump on every PR that ships user-visible features.
# Tag the merge commit on main as v<version> (git tag -a v0.6.0 -m "release 0.6.0").
# Demo media ALWAYS carries the version: film/GIF filenames embed it and the canvas
# bug shows it on-screen, so a YouTube upload is never ambiguous.
set -euo pipefail
cd "$(dirname "$0")/.."
tr -d '[:space:]' < VERSION
