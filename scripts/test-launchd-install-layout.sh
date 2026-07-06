#!/usr/bin/env bash
#
# Package/install smoke test for the launchd/macOS packaging (P1-011): runs
# the real scripts/install-launchd-services.sh end-to-end (not just plist
# syntax, which check-launchd-plists.sh already covers) with only the
# genuinely OS-privileged/side-effecting bits faked — `launchctl` (so it
# never actually loads a system daemon) and `install`'s -o/-g ownership
# arguments (so this does not depend on a real `_p2ptunnel` account
# existing on the CI runner). `dscl`/`sudo` are also faked so both the
# "service account missing" failure path and the `require_service_traverse`/
# `validate_role_config_as_service_user` control flow can be driven
# deterministically. Everything else (directory creation, mode bits,
# `plutil -lint`) is real.
#
# Only runs on macOS; SKIPs elsewhere (mirrors check-launchd-plists.sh).
# Must run as root (the real script requires it too).
#
# Usage: sudo scripts/test-launchd-install-layout.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

log() { printf '\033[1;34m[test-launchd-install-layout]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[test-launchd-install-layout FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

if [ "$(uname -s)" != "Darwin" ]; then
  log "SKIP: this smoke test only runs on macOS (detected $(uname -s))."
  log "Structural coverage still comes from: cargo test -p p2p-daemon --test launchd_plist_tests"
  exit 0
fi
[ "$(id -u)" -eq 0 ] || fail "must run as root (e.g. via sudo)"

WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

STUB_BIN="$WORK/stub-bin"
STATE_DIR="$WORK/state"
mkdir -p "$STUB_BIN" "$STATE_DIR"
DSCL_STATE="$STATE_DIR/dscl-users-groups-present"
LAUNCHCTL_LOG="$STATE_DIR/launchctl.log"
echo "1" > "$DSCL_STATE" # 1 = service account present, 0 = missing
: > "$LAUNCHCTL_LOG"

# Fakes only the service-account existence check; every other `dscl`
# invocation (there are none elsewhere in the script) would be an error.
cat > "$STUB_BIN/dscl" <<STUB_EOF
#!/bin/sh
if [ "\$(cat "$DSCL_STATE")" = "1" ]; then
  exit 0
fi
exit 1
STUB_EOF

# Strips \`-u <user>\` and runs the remaining command as-is (already root
# here). This exercises the script's own control flow (does it call
# \`sudo -u \$SERVICE_USER ...\` at all, does it correctly propagate that
# command's exit code) without needing a real _p2ptunnel account.
cat > "$STUB_BIN/sudo" <<'STUB_EOF'
#!/bin/sh
if [ "$1" = "-u" ]; then
  shift 2
fi
exec "$@"
STUB_EOF

# Never actually loads a system daemon; just records that it was asked to.
cat > "$STUB_BIN/launchctl" <<STUB_EOF
#!/bin/sh
echo "\$@" >> "$LAUNCHCTL_LOG"
exit 0
STUB_EOF

# Forwards to the real /usr/bin/install but drops -o/-g VALUE pairs, so
# directory creation/mode bits are exercised for real without depending on
# a real _p2ptunnel user/group existing on the runner.
cat > "$STUB_BIN/install" <<'STUB_EOF'
#!/bin/bash
args=()
while [ $# -gt 0 ]; do
  case "$1" in
    -o|-g) shift 2 ;;
    *) args+=("$1"); shift ;;
  esac
done
exec /usr/bin/install "${args[@]}"
STUB_EOF

chmod +x "$STUB_BIN"/*
export PATH="$STUB_BIN:$PATH"

APP_SUPPORT_ROOT="/Library/Application Support/P2PTunnel"
LOG_DIR="/Library/Logs/P2PTunnel"
LAUNCHD_DIR="/Library/LaunchDaemons"
BIN_DIR="/usr/local/bin"

cleanup_layout() {
  rm -rf "$APP_SUPPORT_ROOT" "$LOG_DIR"
  rm -f "$LAUNCHD_DIR/com.p2ptunnel.offer.plist" "$LAUNCHD_DIR/com.p2ptunnel.answer.plist"
  rm -f "$BIN_DIR/p2p-offer" "$BIN_DIR/p2p-answer" "$BIN_DIR/p2pctl"
}
trap 'cleanup_layout; cleanup' EXIT
cleanup_layout

# Trivial stand-ins: this test verifies the install *layout* (paths,
# permissions, control flow), not p2pctl's own config validation (covered
# extensively elsewhere), so a fake p2pctl that succeeds only for a config
# file containing "valid" is sufficient and keeps this hermetic.
install -d -m 0755 "$BIN_DIR"
printf '#!/bin/sh\nexit 0\n' > "$BIN_DIR/p2p-offer"
printf '#!/bin/sh\nexit 0\n' > "$BIN_DIR/p2p-answer"
cat > "$BIN_DIR/p2pctl" <<'PCTL_EOF'
#!/bin/sh
if [ "$1" = "check-config" ]; then
  shift
  config=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --config) config="$2"; shift 2 ;;
      *) shift ;;
    esac
  done
  grep -q valid "$config" 2>/dev/null && exit 0 || exit 1
fi
exit 1
PCTL_EOF
chmod +x "$BIN_DIR/p2p-offer" "$BIN_DIR/p2p-answer" "$BIN_DIR/p2pctl"

# --- Scenario 1: missing service account must fail before touching layout ---
echo "0" > "$DSCL_STATE"
if "$ROOT/scripts/install-launchd-services.sh" >/tmp/install-1.log 2>&1; then
  cat /tmp/install-1.log
  fail "script should have failed with the service account missing"
fi
grep -q "does not exist" /tmp/install-1.log || { cat /tmp/install-1.log; fail "expected a clear missing-service-account message"; }
[ ! -e "$APP_SUPPORT_ROOT" ] || fail "script must not create any layout before the account check passes"
log "PASS: missing service account fails clearly before creating any layout"

# --- Scenario 2: first install creates directories and validates plists ---
echo "1" > "$DSCL_STATE"
"$ROOT/scripts/install-launchd-services.sh" >/tmp/install-2.log 2>&1 || { cat /tmp/install-2.log; fail "first install should succeed"; }
for role in offer answer; do
  dir="$APP_SUPPORT_ROOT/$role"
  [ -d "$dir" ] || fail "expected '$dir' to be created"
  mode="$(perl -e 'printf "%o\n", (stat(shift))[2] & 07777' "$dir")"
  [ "$mode" = "750" ] || fail "expected '$dir' mode 750, got $mode"
done
[ -d "$LOG_DIR" ] || fail "expected '$LOG_DIR' to be created"
for role in offer answer; do
  plist="$LAUNCHD_DIR/com.p2ptunnel.$role.plist"
  [ -f "$plist" ] || fail "expected '$plist' to be installed"
  plutil -lint "$plist" >/dev/null || fail "installed plist '$plist' failed plutil -lint"
done
grep -q . "$LAUNCHCTL_LOG" && fail "first install without --enable must not call launchctl"
log "PASS: first install creates 0750 directories, installs plists that pass plutil -lint, and does not load anything"

# --- Scenario 3: re-running without --enable validates existing dirs, doesn't recreate ---
"$ROOT/scripts/install-launchd-services.sh" >/tmp/install-3.log 2>&1 || { cat /tmp/install-3.log; fail "idempotent re-run should succeed"; }
grep -q "already exists; validating" /tmp/install-3.log || { cat /tmp/install-3.log; fail "expected the re-run to validate the existing directories instead of recreating them"; }
log "PASS: re-running validates existing directories instead of silently recreating them"

# --- Scenario 4: --enable with one missing config must not bootstrap either role ---
echo "valid offer config" > "$APP_SUPPORT_ROOT/offer/config.toml"
: > "$LAUNCHCTL_LOG"
if "$ROOT/scripts/install-launchd-services.sh" --enable >/tmp/install-4.log 2>&1; then
  cat /tmp/install-4.log
  fail "--enable should fail when the answer config is missing"
fi
[ ! -s "$LAUNCHCTL_LOG" ] || fail "--enable must not bootstrap either role when any config fails validation (half-enabled pair)"
log "PASS: --enable refuses to bootstrap either role when one config is missing/invalid"

# --- Scenario 5: --enable with both valid configs bootstraps both roles ---
echo "valid answer config" > "$APP_SUPPORT_ROOT/answer/config.toml"
: > "$LAUNCHCTL_LOG"
"$ROOT/scripts/install-launchd-services.sh" --enable >/tmp/install-5.log 2>&1 || { cat /tmp/install-5.log; fail "--enable should succeed with two valid configs"; }
grep -q "bootstrap system .*com.p2ptunnel.offer.plist" "$LAUNCHCTL_LOG" || fail "expected launchctl bootstrap for the offer role"
grep -q "bootstrap system .*com.p2ptunnel.answer.plist" "$LAUNCHCTL_LOG" || fail "expected launchctl bootstrap for the answer role"
log "PASS: --enable bootstraps both roles once both configs validate"

log "PASS — launchd install-layout smoke test complete"
