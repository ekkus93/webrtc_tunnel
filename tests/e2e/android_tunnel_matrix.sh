#!/usr/bin/env bash
#
# Android data-plane matrix: runs android_tunnel_e2e.sh across ICE-mode x answer-network
# combinations, plus the black-hole (probe-failure) row, and summarizes results.
#
# Rows (see ANDROID_WEBRTC_EMULATOR_DATA_PLANE_SPEC.md §9.2):
#   auto   x host     - default path, must PASS (bytes delivered + probe PING/PONG)
#   auto   x bridge   - answer behind Docker NAT, must PASS
#   vnet   x host     - forced vnet fallback, must PASS
#   vnet   x bridge   - forced vnet fallback behind NAT, must PASS
#   native x host     - diagnostic; on emulator/Android 11+ this is EXPECTED_FAIL (no
#                       candidates gathered -> fails via the ~30s first-open timeout, NOT
#                       the probe timeout; set EXPECT_NATIVE_ICE_PASS=1 to require a pass)
#   black-hole        - answer drops the probe PING; the offer must fail FAST and deliver
#                       nothing (exercises the probe-failure teardown end-to-end)
#
# Each row is an independent android_tunnel_e2e.sh invocation. REBUILD=0 is forced after
# the first row so the APK/answer are built once.
#
# Usage: tests/e2e/android_tunnel_matrix.sh
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E2E="$HERE/android_tunnel_e2e.sh"
[ -x "$E2E" ] || { echo "missing $E2E" >&2; exit 1; }

EXPECT_NATIVE_ICE_PASS="${EXPECT_NATIVE_ICE_PASS:-0}"

# row: <label> <expectation: pass|expected_fail|tolerant> <env-assignments...>
#   pass          - must deliver; failure is a hard error.
#   expected_fail - must NOT deliver; a pass is flagged UNEXPECTED_PASS (hard error).
#   tolerant      - either outcome is acceptable (diagnostic rows whose result depends on
#                   the device/network, e.g. `native` passes on a physical phone with a
#                   reachable answer + STUN srflx, but fails on an emulator).
run_row() {
  local label="$1" expect="$2"; shift 2
  printf '\n\033[1;36m===== matrix row: %s (expect %s) =====\033[0m\n' "$label" "$expect"
  if env "$@" "$E2E"; then
    case "$expect" in
      pass)     echo "PASS  $label"; RESULTS+=("PASS  $label"); return 0 ;;
      tolerant) echo "PASS  $label (tolerated)"; RESULTS+=("PASS  $label (tolerated)"); return 0 ;;
      *)        echo "UNEXPECTED_PASS  $label"; RESULTS+=("UNEXPECTED_PASS  $label"); return 1 ;;
    esac
  fi
  case "$expect" in
    expected_fail) echo "EXPECTED_FAIL  $label"; RESULTS+=("EXPECTED_FAIL  $label"); return 0 ;;
    tolerant)      echo "EXPECTED_FAIL  $label (tolerated)"; RESULTS+=("EXPECTED_FAIL  $label (tolerated)"); return 0 ;;
    *)             echo "FAIL  $label"; RESULTS+=("FAIL  $label"); return 1 ;;
  esac
}

RESULTS=()
STATUS=0

# Build once on the first row, reuse afterwards.
run_row "auto x host"   pass REBUILD="${REBUILD:-1}" ANDROID_ICE_MODE=auto ANSWER_NET=host   || STATUS=1
run_row "auto x bridge" pass REBUILD=0               ANDROID_ICE_MODE=auto ANSWER_NET=bridge || STATUS=1
run_row "vnet x host"   pass REBUILD=0               ANDROID_ICE_MODE=vnet ANSWER_NET=host   || STATUS=1
run_row "vnet x bridge" pass REBUILD=0               ANDROID_ICE_MODE=vnet ANSWER_NET=bridge || STATUS=1

# native is a diagnostic row: it passes on a physical device with a reachable answer +
# STUN srflx, but fails on an emulator (no candidates gathered). Tolerate either unless
# EXPECT_NATIVE_ICE_PASS=1 demands a pass.
if [ "$EXPECT_NATIVE_ICE_PASS" = "1" ]; then
  run_row "native x host" pass     REBUILD=0 ANDROID_ICE_MODE=native ANSWER_NET=host || STATUS=1
else
  run_row "native x host" tolerant REBUILD=0 ANDROID_ICE_MODE=native ANSWER_NET=host || STATUS=1
fi

run_row "black-hole (auto x host)" pass REBUILD=0 BLACK_HOLE=1 ANDROID_ICE_MODE=auto ANSWER_NET=host || STATUS=1

printf '\n\033[1;36m===== matrix summary =====\033[0m\n'
for line in "${RESULTS[@]}"; do printf '  %s\n' "$line"; done
[ "$STATUS" -eq 0 ] && echo "matrix: ALL ROWS OK" || echo "matrix: ONE OR MORE ROWS FAILED"
exit "$STATUS"
