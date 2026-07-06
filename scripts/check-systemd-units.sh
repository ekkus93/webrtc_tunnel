#!/usr/bin/env bash
#
# Validates the repository's systemd unit files with `systemd-analyze verify`
# where that tool is available (Linux). Not requiring `systemd` to run the
# binaries themselves — this only checks the packaging artifacts under
# packaging/systemd/ are syntactically well-formed.
#
# `systemd-analyze verify` legitimately fails in CI/dev environments where the
# unit's binaries and service account are not actually installed at their
# absolute paths — that is expected and NOT a unit-syntax problem. This script
# filters out only that specific, expected class of complaint and fails loudly
# on anything else, per the project's "do not blanket-ignore all stderr" rule.
#
# Usage: scripts/check-systemd-units.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

# Verified in two separate systemd-analyze invocations, not one combined list:
# the manually-installed (/usr/local/bin) and packaged-deb (/usr/bin) unit
# variants share the same unit basenames (p2p-offer.service/p2p-answer.service),
# and systemd-analyze verify resolves units by name — passing both variants in
# one invocation makes it silently verify only one of the two same-named files
# twice instead of actually checking both.
MANUAL_UNITS=(
  "$ROOT/packaging/systemd/p2p-offer.service"
  "$ROOT/packaging/systemd/p2p-answer.service"
  "$ROOT/packaging/systemd/p2p-offer@.service"
  "$ROOT/packaging/systemd/p2p-answer@.service"
)
DEBIAN_UNITS=(
  "$ROOT/packaging/debian/systemd/p2p-offer.service"
  "$ROOT/packaging/debian/systemd/p2p-answer.service"
)

log() { printf '\033[1;34m[check-systemd-units]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[check-systemd-units FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

for unit in "${MANUAL_UNITS[@]}" "${DEBIAN_UNITS[@]}"; do
  [ -f "$unit" ] || fail "unit file not found: $unit"
done

if ! command -v systemd-analyze >/dev/null 2>&1; then
  log "SKIP: systemd-analyze not found on this host; unit syntax was not natively verified here."
  log "This is expected/optional outside Linux hosts with systemd installed; do not treat as a failure."
  exit 0
fi

# Expected, environment-specific noise: the unit's ExecStart/ExecStartPre binary
# is not installed at its absolute path in this environment. Any other line is
# a real problem (bad syntax, unknown directive, etc.) and fails.
expected_pattern='is not executable: No such file or directory'

verify_group() {
  local label="$1"
  shift
  local units=("$@")
  log "running: systemd-analyze verify ${units[*]}"
  local output
  output="$(systemd-analyze verify "${units[@]}" 2>&1)" && verify_status=0 || verify_status=$?

  local unexpected=""
  if [ -n "$output" ]; then
    while IFS= read -r line; do
      [ -z "$line" ] && continue
      if [[ "$line" != *"$expected_pattern"* ]]; then
        unexpected="${unexpected}${line}"$'\n'
      fi
    done <<<"$output"
  fi

  if [ -n "$unexpected" ]; then
    printf '%s\n' "$output"
    fail "systemd-analyze verify ($label) reported unexpected problems:\n${unexpected}"
  fi

  if [ "$verify_status" -ne 0 ]; then
    log "systemd-analyze verify ($label) exited nonzero, but every reported line was the expected \"$expected_pattern\" (binaries not installed at their absolute path in this environment):"
    printf '%s\n' "$output"
  fi
}

verify_group "manual units" "${MANUAL_UNITS[@]}"
verify_group "debian package units" "${DEBIAN_UNITS[@]}"

log "PASS — unit syntax verified (no unexpected problems)"
