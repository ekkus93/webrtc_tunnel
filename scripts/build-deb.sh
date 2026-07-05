#!/usr/bin/env bash
#
# Builds a .deb package for p2p-offer/p2p-answer/p2pctl from the templates in
# packaging/debian/. This assembles the filesystem tree by hand and invokes
# dpkg-deb directly rather than a full debhelper/dh-cargo pipeline — this is
# a P2/"possible future work" packaging pass, not a Debian-archive-quality
# package (no vendored offline build, no lintian-clean guarantee against
# full Debian Policy). Verify with `dpkg -c`/`lintian` before relying on it.
#
# Usage: scripts/build-deb.sh [output-dir]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
OUT_DIR="${1:-$ROOT/target/debian}"

log() { printf '\033[1;34m[build-deb]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[build-deb FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

command -v dpkg-deb >/dev/null 2>&1 || fail "dpkg-deb not found (this only runs on Debian/Ubuntu-family hosts)"
command -v fakeroot >/dev/null 2>&1 || fail "fakeroot not found"

VERSION="$(sed -n 's/^version = "\(.*\)"/\1/p' "$ROOT/Cargo.toml" | head -1)"
[ -n "$VERSION" ] || fail "could not determine package version from $ROOT/Cargo.toml"
ARCH="$(dpkg --print-architecture)"

for bin in p2p-offer p2p-answer p2pctl; do
  if [ ! -x "$ROOT/target/release/$bin" ]; then
    log "building release binaries (missing $bin)"
    ( cd "$ROOT" && cargo build --release -p p2p-offer -p p2p-answer -p p2pctl )
    break
  fi
done

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

log "assembling package tree in $STAGE"
install -d -m 0755 "$STAGE/DEBIAN"
install -d -m 0755 "$STAGE/usr/bin"
install -d -m 0755 "$STAGE/lib/systemd/system"
install -d -m 0755 "$STAGE/usr/share/doc/p2ptunnel"

sed -e "s/__VERSION__/$VERSION/" -e "s/__ARCH__/$ARCH/" \
  "$ROOT/packaging/debian/control" > "$STAGE/DEBIAN/control"
install -m 0755 "$ROOT/packaging/debian/postinst" "$STAGE/DEBIAN/postinst"
install -m 0755 "$ROOT/packaging/debian/prerm" "$STAGE/DEBIAN/prerm"
install -m 0755 "$ROOT/packaging/debian/postrm" "$STAGE/DEBIAN/postrm"

install -m 0755 "$ROOT/target/release/p2p-offer" "$STAGE/usr/bin/p2p-offer"
install -m 0755 "$ROOT/target/release/p2p-answer" "$STAGE/usr/bin/p2p-answer"
install -m 0755 "$ROOT/target/release/p2pctl" "$STAGE/usr/bin/p2pctl"
if command -v strip >/dev/null 2>&1; then
  strip "$STAGE/usr/bin/p2p-offer" "$STAGE/usr/bin/p2p-answer" "$STAGE/usr/bin/p2pctl"
fi

install -m 0644 "$ROOT/packaging/systemd/p2p-offer.service" "$STAGE/lib/systemd/system/p2p-offer.service"
install -m 0644 "$ROOT/packaging/systemd/p2p-answer.service" "$STAGE/lib/systemd/system/p2p-answer.service"

install -m 0644 "$ROOT/README.md" "$STAGE/usr/share/doc/p2ptunnel/README.md"
install -m 0644 "$ROOT/docs/SYSTEMD.md" "$STAGE/usr/share/doc/p2ptunnel/SYSTEMD.md"
install -m 0644 "$ROOT/packaging/debian/copyright" "$STAGE/usr/share/doc/p2ptunnel/copyright"
sed "s/__VERSION__/$VERSION/" "$ROOT/packaging/debian/changelog" \
  | gzip -9n > "$STAGE/usr/share/doc/p2ptunnel/changelog.gz"
chmod 0644 "$STAGE/usr/share/doc/p2ptunnel/changelog.gz"

mkdir -p "$OUT_DIR"
DEB_PATH="$OUT_DIR/p2ptunnel_${VERSION}_${ARCH}.deb"
log "building $DEB_PATH"
fakeroot dpkg-deb --build "$STAGE" "$DEB_PATH" >/dev/null

log "PASS — built $DEB_PATH"
log "contents:"
dpkg -c "$DEB_PATH" | sed 's/^/    /'

if command -v lintian >/dev/null 2>&1; then
  log "lintian report (informational — this package does not target full Debian Policy compliance):"
  lintian "$DEB_PATH" || true
else
  log "lintian not installed; skipping (informational only)"
fi
