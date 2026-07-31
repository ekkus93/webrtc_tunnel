#!/usr/bin/env bash
set -eu

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tests/e2e/lib/probe_evidence.sh
. "$HERE/lib/probe_evidence.sh"

fail_test() {
  printf '[probe-evidence-test FAIL] %s\n' "$*" >&2
  exit 1
}

FIXTURE_DIR="$(mktemp -d)"
trap 'rm -rf "$FIXTURE_DIR"' EXIT

healthy="$FIXTURE_DIR/healthy.log"
missing="$FIXTURE_DIR/missing.log"
printf '%s\n' \
  'INFO data channel open' \
  'INFO received tunnel PING; sending PONG' > "$healthy"
printf '%s\n' \
  'INFO data channel open' \
  'INFO marker forwarded' > "$missing"

verify_probe_evidence "$healthy" >/dev/null \
  || fail_test "healthy PING/PONG fixture was rejected"

missing_output="$FIXTURE_DIR/missing.out"
if verify_probe_evidence "$missing" >"$missing_output" 2>&1; then
  fail_test "missing PING/PONG fixture was accepted"
fi
grep -Fq 'no data-plane probe PING/PONG' "$missing_output" \
  || fail_test "missing-evidence diagnostic was not emitted"

absent_output="$FIXTURE_DIR/absent.out"
if verify_probe_evidence "$FIXTURE_DIR/absent.log" >"$absent_output" 2>&1; then
  fail_test "absent answer log was accepted"
fi
grep -Fq 'log file missing' "$absent_output" \
  || fail_test "absent-log diagnostic was not emitted"

printf '[probe-evidence-test] PASS\n'
