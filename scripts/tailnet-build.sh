#!/usr/bin/env bash
# Build the embedded-tailnet AAR (SPEC §11.4 lane (a)): gomobile-bind
# tailnet/go into app/libs/hermes-tailnet.aar for exactly the ABIs the app
# ships — android/arm (Galaxy Watch Ultra is 32-bit userspace; see the
# abiFilters warning in app/build.gradle.kts) and android/amd64 (the Wear
# emulator). The AAR is COMMITTED; rerun this only when tailnet/go changes.
#
# Needs (folder-local, devkit convention): Go (devkit/go1.25 or $GOROOT) and
# an NDK r27 in $ANDROID_HOME/ndk. gomobile itself is installed into
# devkit/go-home/bin on first run.
#
# -tags=version_android is NOT optional: without it tailscale assumes a full
# Linux and dies at Start with "route ip+net: netlinkrib: permission denied" —
# SELinux untrusted_app cannot read netlink routes (caught on the emulator,
# 2026-11-02). The official tailscale Android client builds with the same tag.
#
#   scripts/tailnet-build.sh            # → app/libs/hermes-tailnet.aar
#   scripts/tailnet-build.sh --smoke    # also runs the host-side smoketest
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=env.sh
source "$SCRIPT_DIR/env.sh"

GO="${GO:-$DEVKIT/go1.25/bin/go}"
[ -x "$GO" ] || { echo "no Go at $GO — put a toolchain in $DEVKIT (see header)"; exit 1; }
export GOROOT="$DEVKIT/go1.25" GOPATH="$DEVKIT/go-home" GOCACHE="$DEVKIT/go-cache"
[ -d "$GOROOT" ] || { GOROOT="$($GO env GOROOT)"; export GOROOT; }

NDK_DIR="${NDK_DIR:-$(ls -d "$ANDROID_HOME"/ndk/27.* 2>/dev/null | sort -V | tail -1 || true)}"
[ -n "$NDK_DIR" ] || { echo "no NDK r27 under $ANDROID_HOME/ndk — sdkmanager 'ndk;27.2.12479018'"; exit 1; }
export ANDROID_NDK_HOME="$NDK_DIR"

# gomobile on PATH from GOPATH/bin; install pinned-quiet on first run.
# gomobile itself execs `go`, so the toolchain bin must be on PATH too.
export PATH="$GOROOT/bin:$GOPATH/bin:$PATH"
if ! command -v gomobile >/dev/null; then
  echo "installing gomobile → $GOPATH/bin"
  "$GO" install golang.org/x/mobile/cmd/gomobile@latest
fi
# gomobile init installs/refreshes gobind + the NDK toolchain probe; cheap and
# idempotent — run it EVERY time so a half-bootstrapped install can't hide.
command -v gobind >/dev/null && [ -d "$DEVKIT/android/bind" ] || gomobile init

cd "$ROOT/tailnet/go"
"$GO" vet ./...
if [ "${1:-}" = "--smoke" ]; then
  "$GO" build -o /tmp/opencode/tailnet-smoketest ./cmd/smoketest
  echo "smoketest built: /tmp/opencode/tailnet-smoketest (run it with TS_AUTHKEY=…)"
fi

mkdir -p "$ROOT/app/libs"
# strip via ldflags (Go symbols are the bulk of the AAR size).
"$GO" env >/dev/null   # fail fast on a bad GOROOT
CGO_ENABLED=1 gomobile bind -v -trimpath \
  -tags=version_android \
  -ldflags="-s -w" \
  -target=android/arm,android/amd64 \
  -androidapi=30 \
  -o "$ROOT/app/libs/hermes-tailnet.aar" \
  .
ls -la "$ROOT/app/libs/hermes-tailnet.aar"
echo "OK — Java/Kotlin side imports dev.hermes.tailnet.*"
