#!/usr/bin/env bash
# Shared fail-closed verification for the Android real-data-path E2E probe contract.
# This file is sourced by android_tunnel_e2e.sh and by its focused shell fixture test.

probe_evidence_present() {
  local answer_log="$1"
  [ -f "$answer_log" ] || return 1
  grep -Fqi "received tunnel PING; sending PONG" "$answer_log"
}

probe_contract_log() {
  if declare -F log >/dev/null 2>&1; then
    log "$*"
  else
    printf '%s\n' "$*"
  fi
}

verify_probe_evidence() {
  local answer_log="$1"
  if probe_evidence_present "$answer_log"; then
    probe_contract_log "verified data-plane probe: answer received PING and replied PONG"
    return 0
  fi

  probe_contract_log "FAIL: marker delivered but no data-plane probe PING/PONG was seen in answer logs"
  if [ -f "$answer_log" ]; then
    grep -iE "ping|pong|data channel|peer connection|error" "$answer_log" \
      | tail -30 | sed 's/^/    [answer] /' >&2 || true
  else
    printf '    [answer] log file missing: %s\n' "$answer_log" >&2
  fi
  return 1
}
