#!/usr/bin/env bash
#
# Validates the repository's launchd LaunchDaemon plists with the native
# `plutil -lint` on macOS. On non-macOS hosts, prints an explicit message that
# native validation was not run here and relies on the platform-independent
# structural tests (crates/p2p-daemon/tests/launchd_plist_tests.rs) instead —
# it never claims native macOS validation succeeded when it did not run.
#
# Usage: scripts/check-launchd-plists.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
PLISTS=(
  "$ROOT/packaging/launchd/com.p2ptunnel.offer.plist"
  "$ROOT/packaging/launchd/com.p2ptunnel.answer.plist"
)

log() { printf '\033[1;34m[check-launchd-plists]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[check-launchd-plists FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

for plist in "${PLISTS[@]}"; do
  [ -f "$plist" ] || fail "plist file not found: $plist"
done

if [ "$(uname -s)" != "Darwin" ]; then
  log "SKIP: not running on macOS; native plutil validation was not performed here."
  log "Structural coverage still comes from: cargo test -p p2p-daemon --test launchd_plist_tests"
  exit 0
fi

if ! command -v plutil >/dev/null 2>&1; then
  fail "running on macOS but plutil is missing; this should not happen on a real macOS host"
fi

status=0
for plist in "${PLISTS[@]}"; do
  log "running: plutil -lint $plist"
  if ! plutil -lint "$plist"; then
    status=1
  fi
done

[ "$status" -eq 0 ] || fail "plutil -lint reported malformed plist(s); see output above"

log "PASS — plutil -lint succeeded for both plists"
