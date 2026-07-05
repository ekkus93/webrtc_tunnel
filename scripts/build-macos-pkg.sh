#!/usr/bin/env bash
#
# Builds an (unsigned) macOS installer package for p2p-offer/p2p-answer/
# p2pctl using pkgbuild. This is P2/"possible future work" packaging
# scaffolding: it has NOT been run or verified on a real Mac by whoever wrote
# it in this pass (no macOS host was available) — it must be exercised for
# real on macOS (a real Mac or the project's macOS CI runner) before being
# relied on. See docs/MACOS_PACKAGING.md for the full design and what
# remains manual (code signing, notarization, account creation).
#
# Usage: scripts/build-macos-pkg.sh [output-dir]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
OUT_DIR="${1:-$ROOT/target/macos-pkg}"

log() { printf '\033[1;34m[build-macos-pkg]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[build-macos-pkg FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(uname -s)" = "Darwin" ] || fail "this script builds a macOS .pkg and only runs on macOS (detected $(uname -s))"
command -v pkgbuild >/dev/null 2>&1 || fail "pkgbuild not found (part of Xcode Command Line Tools)"

VERSION="$(sed -n 's/^version = "\(.*\)"/\1/p' "$ROOT/Cargo.toml" | head -1)"
[ -n "$VERSION" ] || fail "could not determine package version from $ROOT/Cargo.toml"
IDENTIFIER="com.p2ptunnel.pkg"

for bin in p2p-offer p2p-answer p2pctl; do
  if [ ! -x "$ROOT/target/release/$bin" ]; then
    log "building release binaries (missing $bin)"
    ( cd "$ROOT" && cargo build --release -p p2p-offer -p p2p-answer -p p2pctl )
    break
  fi
done

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

log "assembling payload in $STAGE"
install -d -m 0755 "$STAGE/usr/local/bin"
install -d -m 0755 "$STAGE/Library/LaunchDaemons"

install -m 0755 "$ROOT/target/release/p2p-offer" "$STAGE/usr/local/bin/p2p-offer"
install -m 0755 "$ROOT/target/release/p2p-answer" "$STAGE/usr/local/bin/p2p-answer"
install -m 0755 "$ROOT/target/release/p2pctl" "$STAGE/usr/local/bin/p2pctl"

install -m 0644 "$ROOT/packaging/launchd/com.p2ptunnel.offer.plist" \
  "$STAGE/Library/LaunchDaemons/com.p2ptunnel.offer.plist"
install -m 0644 "$ROOT/packaging/launchd/com.p2ptunnel.answer.plist" \
  "$STAGE/Library/LaunchDaemons/com.p2ptunnel.answer.plist"

if command -v plutil >/dev/null 2>&1; then
  plutil -lint "$STAGE/Library/LaunchDaemons/com.p2ptunnel.offer.plist"
  plutil -lint "$STAGE/Library/LaunchDaemons/com.p2ptunnel.answer.plist"
fi

mkdir -p "$OUT_DIR"
PKG_PATH="$OUT_DIR/p2ptunnel-${VERSION}.pkg"
log "building (unsigned) $PKG_PATH"
pkgbuild \
  --root "$STAGE" \
  --identifier "$IDENTIFIER" \
  --version "$VERSION" \
  --scripts "$ROOT/packaging/macos/scripts" \
  --install-location / \
  "$PKG_PATH"

log "PASS — built unsigned $PKG_PATH"
log "NOTE: this package is unsigned. Real distribution requires:"
log "  productsign --sign \"Developer ID Installer: ...\" \"$PKG_PATH\" signed.pkg"
log "  xcrun notarytool submit signed.pkg --wait ..."
log "See docs/MACOS_PACKAGING.md."
