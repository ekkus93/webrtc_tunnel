#!/usr/bin/env bash
#
# Package/install smoke test for the Debian packaging (P1-011): builds the
# real .deb via build-deb.sh, then verifies what a real install would
# actually run — not just that the staged tree (which build-deb.sh already
# checks) looks right, but that the *built .deb itself*, once extracted,
# still has every unit's ExecStart(Pre)= path resolve to a real executable,
# and that the maintainer scripts (postinst/prerm/postrm) drive systemd and
# the filesystem exactly as intended across install/upgrade/remove/purge.
#
# Part A (extraction/path verification) requires only dpkg-deb/fakeroot and
# runs on any Linux host. Part B (maintainer-script lifecycle) additionally
# needs Docker, since exercising `postinst configure` for real requires
# root (to create the service account and chown config/state/log
# directories) without touching this host's real /etc, /var/lib, /var/log.
#
# Usage: scripts/test-debian-package.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

log() { printf '\033[1;34m[test-debian-package]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[test-debian-package FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

if [ "$(uname -s)" != "Linux" ]; then
  log "SKIP: this smoke test only runs on Linux (detected $(uname -s))."
  exit 0
fi
command -v dpkg-deb >/dev/null 2>&1 || fail "dpkg-deb not found"
command -v fakeroot >/dev/null 2>&1 || fail "fakeroot not found"

WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

DEB_OUT="$WORK/deb-out"
mkdir -p "$DEB_OUT"
log "building .deb via scripts/build-deb.sh"
"$ROOT/scripts/build-deb.sh" "$DEB_OUT" >/dev/null
DEB_PATH="$(find "$DEB_OUT" -maxdepth 1 -name '*.deb' | head -1)"
[ -n "$DEB_PATH" ] || fail "build-deb.sh did not produce a .deb in $DEB_OUT"
log "built $DEB_PATH"

# --- Part A: extraction / path verification -------------------------------
# Re-checks what build-deb.sh's own verify_staged_unit_executables already
# checks pre-build, but against the *actual built .deb* after dpkg-deb has
# had its say — catching any discrepancy dpkg-deb itself might introduce
# (permission bits lost, a file silently excluded, etc.), not just the
# staged tree the build assembled.
EXTRACT_ROOT="$WORK/extracted"
CONTROL_ROOT="$WORK/control"
mkdir -p "$EXTRACT_ROOT" "$CONTROL_ROOT"
dpkg-deb -x "$DEB_PATH" "$EXTRACT_ROOT"
dpkg-deb -e "$DEB_PATH" "$CONTROL_ROOT"

for bin in p2p-offer p2p-answer p2pctl; do
  path="$EXTRACT_ROOT/usr/bin/$bin"
  [ -x "$path" ] || fail "extracted package is missing executable $path"
done
log "PASS: usr/bin/{p2p-offer,p2p-answer,p2pctl} present and executable in the extracted package"

unit_count=0
for unit in "$EXTRACT_ROOT"/lib/systemd/system/p2p-*.service; do
  [ -f "$unit" ] || continue
  unit_count=$((unit_count + 1))
  while IFS= read -r line; do
    command_line="${line#*=}"
    exe="${command_line%% *}"
    case "$exe" in
      /*)
        staged="$EXTRACT_ROOT$exe"
        [ -x "$staged" ] || fail "$(basename "$unit") references $exe, but $staged is absent/not executable in the extracted .deb — the exact class of bug where a unit references a binary the package never actually ships"
        ;;
    esac
  done < <(grep -E '^ExecStart(Pre)?=' "$unit")
done
[ "$unit_count" -ge 2 ] || fail "expected at least 2 packaged systemd units, found $unit_count"
log "PASS: every packaged unit's ExecStart(Pre)= path resolves inside the extracted package ($unit_count units checked)"

# postinst only *mentions* p2pctl in its help text today, but if that ever
# becomes a real invocation, it must resolve to a real packaged executable.
if grep -q 'p2pctl' "$CONTROL_ROOT/postinst" 2>/dev/null; then
  [ -x "$EXTRACT_ROOT/usr/bin/p2pctl" ] || fail "postinst references p2pctl but it is not present/executable in the extracted package"
fi

# --- Part B: maintainer-script upgrade/remove lifecycle -------------------
if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  log "SKIP: docker not available/usable; maintainer-script lifecycle behavior was not exercised here."
  log "Re-run with Docker available (e.g. in CI) to verify postinst/prerm/postrm control flow."
  exit 0
fi

CONTAINER_SCRIPT="$WORK/run-in-container.sh"
cat > "$CONTAINER_SCRIPT" <<'CONTAINER_EOF'
set -euo pipefail

log() { printf '\033[1;34m[in-container]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[in-container FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

STUB_BIN="/scratch/stub-bin"
LOG_FILE="/scratch/systemctl.log"
ACTIVE_FILE="/scratch/active-units"
mkdir -p "$STUB_BIN" /run/systemd/system
: > "$LOG_FILE"
: > "$ACTIVE_FILE"

# Runs on any exit (pass or fail) so the host-side cleanup trap (running as
# an unprivileged user) can always delete these root-owned artifacts,
# instead of only on a fully successful run.
trap 'chmod -R a+rwX /scratch' EXIT

# Real getent/addgroup/adduser/install (all present in this base image and
# genuinely running as root here) exercise postinst's account/directory
# creation for real. Only systemctl is faked, since no real systemd runs in
# a container — everything it would report is controlled by $ACTIVE_FILE.
cat > "$STUB_BIN/systemctl" <<'STUB_EOF'
#!/bin/sh
echo "$@" >> /scratch/systemctl.log
case "$1" in
  is-active)
    unit="$3"
    grep -qx "$unit" /scratch/active-units 2>/dev/null && exit 0 || exit 1
    ;;
  *) exit 0 ;;
esac
STUB_EOF
chmod +x "$STUB_BIN/systemctl"
export PATH="$STUB_BIN:$PATH"

assert_log_contains() {
  grep -qF -- "$1" "$LOG_FILE" || fail "expected log to contain '$1'; log was:\n$(cat "$LOG_FILE")"
}
assert_log_not_contains() {
  grep -qF -- "$1" "$LOG_FILE" && fail "expected log to NOT contain '$1'; log was:\n$(cat "$LOG_FILE")" || true
}

POSTINST=/repo/postinst
PRERM=/repo/prerm
POSTRM=/repo/postrm

# --- Scenario 1: fresh install (postinst configure, nothing active) ---
: > "$LOG_FILE"
sh "$POSTINST" configure "" >/tmp/postinst-1.log 2>&1 || { cat /tmp/postinst-1.log; fail "postinst configure (fresh install) failed"; }
assert_log_contains "daemon-reload"
assert_log_not_contains "try-restart"
getent passwd p2ptunnel >/dev/null || fail "postinst did not create the p2ptunnel service account"
getent group p2ptunnel >/dev/null || fail "postinst did not create the p2ptunnel service group"
for role in offer answer; do
  [ -d "/etc/p2ptunnel/$role" ] || fail "postinst did not create /etc/p2ptunnel/$role"
  [ -d "/var/lib/p2ptunnel-$role" ] || fail "postinst did not create /var/lib/p2ptunnel-$role"
  [ -d "/var/log/p2ptunnel-$role" ] || fail "postinst did not create /var/log/p2ptunnel-$role"
done
log "PASS: fresh install creates the account and role directories, reloads systemd, restarts nothing"

# --- Scenario 2: upgrade (postinst configure again) with offer active ---
echo "p2p-offer.service" > "$ACTIVE_FILE"
: > "$LOG_FILE"
sh "$POSTINST" configure "0.1.0" >/tmp/postinst-2.log 2>&1 || { cat /tmp/postinst-2.log; fail "postinst configure (upgrade) failed"; }
assert_log_contains "daemon-reload"
assert_log_contains "try-restart p2p-offer.service"
assert_log_not_contains "try-restart p2p-answer.service"
log "PASS: upgrade try-restarts only the unit that was actually active"

# --- Scenario 3: prerm upgrade must not stop anything ---
echo "p2p-offer.service
p2p-answer.service" > "$ACTIVE_FILE"
: > "$LOG_FILE"
sh "$PRERM" upgrade "0.2.0" >/tmp/prerm-upgrade.log 2>&1 || { cat /tmp/prerm-upgrade.log; fail "prerm upgrade failed"; }
assert_log_not_contains "stop"
log "PASS: prerm upgrade does not stop any unit"

# --- Scenario 4: prerm remove stops active units ---
: > "$LOG_FILE"
sh "$PRERM" remove >/tmp/prerm-remove.log 2>&1 || { cat /tmp/prerm-remove.log; fail "prerm remove failed"; }
assert_log_contains "stop p2p-offer.service"
assert_log_contains "stop p2p-answer.service"
log "PASS: prerm remove stops both active units"

# --- Scenario 5: postrm remove reloads systemd, preserves config/state ---
: > "$LOG_FILE"
sh "$POSTRM" remove >/tmp/postrm-remove.log 2>&1 || { cat /tmp/postrm-remove.log; fail "postrm remove failed"; }
assert_log_contains "daemon-reload"
[ -d /etc/p2ptunnel/offer ] || fail "postrm remove must not delete config directories"
log "PASS: postrm remove reloads systemd and preserves config/state/logs"

# --- Scenario 6: postrm purge reloads systemd and removes config/state/logs ---
: > "$LOG_FILE"
sh "$POSTRM" purge >/tmp/postrm-purge.log 2>&1 || { cat /tmp/postrm-purge.log; fail "postrm purge failed"; }
assert_log_contains "daemon-reload"
for role in offer answer; do
  [ ! -d "/etc/p2ptunnel/$role" ] || fail "postrm purge did not remove /etc/p2ptunnel/$role"
  [ ! -d "/var/lib/p2ptunnel-$role" ] || fail "postrm purge did not remove /var/lib/p2ptunnel-$role"
  [ ! -d "/var/log/p2ptunnel-$role" ] || fail "postrm purge did not remove /var/log/p2ptunnel-$role"
done
getent passwd p2ptunnel >/dev/null || fail "postrm purge must not remove the service account"
log "PASS: postrm purge removes config/state/logs but keeps the service account"

log "PASS — all maintainer-script lifecycle scenarios verified"
CONTAINER_EOF

CONTROL_DIR="$WORK/control-scripts"
mkdir -p "$CONTROL_DIR"
cp "$ROOT/packaging/debian/postinst" "$ROOT/packaging/debian/prerm" "$ROOT/packaging/debian/postrm" "$CONTROL_DIR/"
chmod +x "$CONTROL_DIR"/*

SCRATCH_DIR="$WORK/scratch"
mkdir -p "$SCRATCH_DIR"

log "running maintainer-script lifecycle scenarios in a throwaway debian:bookworm-slim container"
docker run --rm \
  -v "$CONTROL_DIR:/repo:ro" \
  -v "$SCRATCH_DIR:/scratch" \
  -v "$CONTAINER_SCRIPT:/run-in-container.sh:ro" \
  debian:bookworm-slim \
  bash /run-in-container.sh

log "PASS — Debian package smoke test complete"
