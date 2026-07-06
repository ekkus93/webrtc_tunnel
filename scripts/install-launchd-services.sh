#!/usr/bin/env bash
#
# Installs the p2p-offer/p2p-answer launchd LaunchDaemons following the steps
# in docs/LAUNCHD.md: config/log directories, plist installation (validated
# with plutil before loading), and an optional load. Does NOT bootstrap/load
# the services unless --enable is passed, and never overwrites an existing
# config/identity/authorized_keys.
#
# Usage: sudo scripts/install-launchd-services.sh [--enable]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

SERVICE_USER="_p2ptunnel"
APP_SUPPORT_ROOT="/Library/Application Support/P2PTunnel"
LOG_DIR="/Library/Logs/P2PTunnel"
LAUNCHD_DIR="/Library/LaunchDaemons"

ENABLE=0
for arg in "$@"; do
  case "$arg" in
    --enable) ENABLE=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 1 ;;
  esac
done

log() { printf '\033[1;34m[install-launchd]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[install-launchd FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(uname -s)" = "Darwin" ] || fail "this script installs launchd plists and only runs on macOS (detected $(uname -s))"
command -v launchctl >/dev/null 2>&1 || fail "launchctl not found"
command -v plutil >/dev/null 2>&1 || fail "plutil not found"
[ "$(id -u)" -eq 0 ] || fail "must run as root (e.g. via sudo) to install system LaunchDaemons"

for bin in p2p-offer p2p-answer p2pctl; do
  [ -x "/usr/local/bin/$bin" ] || fail "/usr/local/bin/$bin not found or not executable — build and install it first (see docs/LAUNCHD.md step 1)"
done

if ! dscl . -read "/Users/$SERVICE_USER" >/dev/null 2>&1; then
  fail "service account '$SERVICE_USER' does not exist. Creating it safely (correct UID/GID \
allocation) is currently an administrator prerequisite — see docs/LAUNCHD.md step 2. This \
script does not create it for you."
fi
if ! dscl . -read "/Groups/$SERVICE_USER" >/dev/null 2>&1; then
  fail "service group '$SERVICE_USER' does not exist. The user check above passing is not \
enough — a user record with a broken/missing primary group is still unusable. See \
docs/LAUNCHD.md step 2."
fi

# For an already-existing directory, don't just print "leaving as-is" — actually
# confirm the service account can still traverse/read it. A directory left over
# from a previous install (or hand-created with the wrong owner/group) would
# otherwise silently fail at runtime instead of at install time.
require_service_traverse() {
  path="$1"
  sudo -u "$SERVICE_USER" test -x "$path" \
    || fail "service account $SERVICE_USER cannot traverse existing directory '$path'"
}

# The log directory is not just traversed, it's written to at runtime. A
# directory left over from a previous install (or hand-created read-only,
# e.g. root:wheel 0755) would pass a traverse-only check yet still make the
# service fail to write its own logs at runtime instead of at install time.
# Permission bits alone (test -w) can also lie (ACLs, immutable flags,
# read-only mounts), so probe with a real create+delete as the service user.
require_service_create_delete() {
  path="$1"
  probe="$path/.p2ptunnel-write-probe-$$"
  sudo -u "$SERVICE_USER" sh -c 'umask 077; : > "$1"' sh "$probe" \
    || fail "service account $SERVICE_USER cannot create files in '$path'"
  sudo -u "$SERVICE_USER" rm -f "$probe" \
    || fail "service account $SERVICE_USER cannot remove files from '$path'"
}

for role in offer answer; do
  dir="$APP_SUPPORT_ROOT/$role"
  if [ -d "$dir" ]; then
    log "config directory '$dir' already exists; validating it is still service-readable"
    require_service_traverse "$dir"
  else
    log "creating '$dir'"
    install -d -m 0750 -o root -g "$SERVICE_USER" "$dir"
  fi
done
if [ -d "$LOG_DIR" ]; then
  log "log directory '$LOG_DIR' already exists; validating it is still service-writable"
  require_service_create_delete "$LOG_DIR"
else
  log "creating '$LOG_DIR'"
  install -d -m 0750 -o "$SERVICE_USER" -g "$SERVICE_USER" "$LOG_DIR"
fi

log "NOTE: this script does not create config.toml, identity, or authorized_keys —"
log "      populate '$APP_SUPPORT_ROOT/{offer,answer}/' yourself (see docs/LAUNCHD.md) before loading."

for role in offer answer; do
  plist_src="$ROOT/packaging/launchd/com.p2ptunnel.$role.plist"
  plist_dst="$LAUNCHD_DIR/com.p2ptunnel.$role.plist"
  [ -f "$plist_src" ] || fail "missing $plist_src"

  log "validating $plist_src before install"
  plutil -lint "$plist_src" || fail "plutil -lint rejected $plist_src; not installing a malformed plist"

  log "installing $plist_dst"
  install -o root -g wheel -m 0644 "$plist_src" "$plist_dst"

  log "validating installed copy at $plist_dst"
  plutil -lint "$plist_dst" || fail "plutil -lint rejected the installed copy at $plist_dst"
done

validate_role_config_as_service_user() {
  role="$1"
  config="$APP_SUPPORT_ROOT/$role/config.toml"

  [ -f "$config" ] || fail "missing '$config'; refusing to bootstrap $role"

  log "validating $role config as $SERVICE_USER"
  sudo -u "$SERVICE_USER" \
    /usr/local/bin/p2pctl check-config --config "$config" \
    || fail "$role config failed validation as $SERVICE_USER"
}

if [ "$ENABLE" -eq 1 ]; then
  # Validate both roles' configs as the actual service user before
  # bootstrapping either one — so a bad second config can't leave the pair
  # half-enabled (first role running, second refused) after this script exits.
  for role in offer answer; do
    validate_role_config_as_service_user "$role"
  done

  for role in offer answer; do
    log "bootstrapping com.p2ptunnel.$role"
    launchctl bootstrap system "$LAUNCHD_DIR/com.p2ptunnel.$role.plist"
  done
else
  log "not loading services (pass --enable to bootstrap them once config is in place)"
fi

log "PASS — installation complete. Next steps:"
log "  1. Populate '$APP_SUPPORT_ROOT/offer/{config.toml,identity,authorized_keys}' and the answer equivalent."
log "  2. /usr/local/bin/p2pctl check-config --config '$APP_SUPPORT_ROOT/offer/config.toml'"
log "  3. sudo launchctl bootstrap system $LAUNCHD_DIR/com.p2ptunnel.offer.plist"
log "  4. sudo launchctl bootstrap system $LAUNCHD_DIR/com.p2ptunnel.answer.plist"
