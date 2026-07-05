#!/usr/bin/env bash
#
# Installs the p2p-offer/p2p-answer systemd services following the steps in
# docs/SYSTEMD.md: service account, config directories, unit files, and a
# daemon-reload. Does NOT enable or start the services unless --enable is
# passed, and never overwrites an existing config/identity/authorized_keys.
#
# Usage: sudo scripts/install-systemd-services.sh [--enable]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

SERVICE_USER="p2ptunnel"
SERVICE_GROUP="p2ptunnel"
BIN_DIR="/usr/local/bin"
CONFIG_ROOT="/etc/p2ptunnel"
UNIT_DIR="/etc/systemd/system"

ENABLE=0
for arg in "$@"; do
  case "$arg" in
    --enable) ENABLE=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 1 ;;
  esac
done

log() { printf '\033[1;34m[install-systemd]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[install-systemd FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(uname -s)" = "Linux" ] || fail "this script installs systemd units and only runs on Linux (detected $(uname -s))"
command -v systemctl >/dev/null 2>&1 || fail "systemctl not found; is this host running systemd?"
[ "$(id -u)" -eq 0 ] || fail "must run as root (e.g. via sudo) to install system services"

for bin in p2p-offer p2p-answer p2pctl; do
  [ -x "$BIN_DIR/$bin" ] || fail "$BIN_DIR/$bin not found or not executable — build and install it first (see docs/SYSTEMD.md step 1)"
done

if ! getent passwd "$SERVICE_USER" >/dev/null 2>&1; then
  log "creating system user/group '$SERVICE_USER'"
  useradd --system --home /nonexistent --shell /usr/sbin/nologin "$SERVICE_USER"
else
  log "service user '$SERVICE_USER' already exists; leaving it as-is"
fi

for role in offer answer; do
  dir="$CONFIG_ROOT/$role"
  if [ -d "$dir" ]; then
    log "config directory $dir already exists; leaving its contents untouched"
  else
    log "creating $dir"
    install -d -m 0750 -o root -g "$SERVICE_GROUP" "$dir"
  fi
done

log "NOTE: this script does not create config.toml, identity, or authorized_keys —"
log "      populate $CONFIG_ROOT/{offer,answer}/ yourself (see docs/SYSTEMD.md) before starting."

for role in offer answer; do
  unit="packaging/systemd/p2p-$role.service"
  [ -f "$ROOT/$unit" ] || fail "missing $ROOT/$unit"
  log "installing $UNIT_DIR/p2p-$role.service"
  install -m 0644 "$ROOT/$unit" "$UNIT_DIR/p2p-$role.service"
done

log "running systemctl daemon-reload"
systemctl daemon-reload

if [ "$ENABLE" -eq 1 ]; then
  for role in offer answer; do
    log "enabling and starting p2p-$role.service"
    systemctl enable --now "p2p-$role.service"
  done
else
  log "not enabling/starting services (pass --enable to do so once config is in place)"
fi

log "PASS — installation complete. Next steps:"
log "  1. Populate $CONFIG_ROOT/offer/{config.toml,identity,authorized_keys} and the answer equivalent."
log "  2. p2pctl check-config --config $CONFIG_ROOT/offer/config.toml"
log "  3. sudo systemctl enable --now p2p-offer.service p2p-answer.service"
