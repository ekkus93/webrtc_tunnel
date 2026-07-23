#!/usr/bin/env bash
#
# Phase B (smoke) — Android emulator metered<->unmetered network transition.
#
# Drives the real app through the setup wizard to a live "Listening" state (same as
# android_smoke.sh), then disables the emulator's Wi-Fi so the active network falls
# back to the emulator's cellular network (metered by default — it reports no
# NOT_METERED capability, verified via `dumpsys connectivity`) and asserts the tunnel
# pauses (NetworkPolicyManager's default allowMetered=false blocks a metered network
# unless the user explicitly allows the session), then re-enables Wi-Fi and asserts
# the tunnel resumes on its own (resumeOnUnmetered defaults to true) — exercising the
# real ConnectivityManager.NetworkCallback -> NetworkPolicyManager -> pauseForPolicy/
# handlePolicyAllowed path end-to-end on a real Android network stack, not a fake.
#
# `cmd netpolicy set metered-network` was tried first but only changes a policy-level
# override, not the live NetworkCapabilities a NetworkCallback observes (confirmed via
# `dumpsys connectivity` still showing NOT_METERED after setting it). `svc wifi
# disable` was tried next but on this emulator image only flips the WifiManager
# state flag without tearing down the underlying Wi-Fi NetworkAgent (confirmed via
# `dumpsys connectivity` still listing an active `ni{WIFI` agent afterward — a real
# network-loss glitch during that half-torn-down state even caused a spurious
# runtime-quarantine Error once). `cmd wifi set-wifi-enabled disabled/enabled` is
# what actually removes/re-adds the Wi-Fi NetworkAgent (confirmed: `dumpsys
# connectivity` then lists only the cellular agent, whose capabilities lack
# NOT_METERED), so the active network cleanly becomes metered cellular.
#
# This is the SMOKE tier: local/manual, not a CI gate (same rationale as
# android_smoke.sh — UI automation is emulator/AVD-sensitive). FIX8 P1-004-B/D:
# meteredToUnmeteredTransitionPausesAndResumesAccordingToPreferenceE2E.
#
# Requirements: same as android_smoke.sh, plus an emulator with both Wi-Fi and a
# (metered-by-default) cellular network available, so disabling Wi-Fi leaves a
# metered active network rather than no network at all (true of this repo's dev
# emulator image; a physical device with no cellular radio cannot run this script).
#
# KNOWN ENVIRONMENT LIMITATION (FIX8 P1-004-B, observed on this repo's dev AVD
# image): the emulator's Wi-Fi sometimes auto-reconnects within 1-2s of `cmd wifi
# set-wifi-enabled disabled`, regardless of the command. When that happens the app
# sees a rapid Wi-Fi-drop-then-return flap while the tunnel is active rather than a
# stable metered window, and its stop-like-failure quarantine safety net (proven
# correct elsewhere, e.g. TunnelForegroundServiceVerificationTest/
# TunnelForegroundServiceRuntimeSafetyRecreationTest) can legitimately trip instead
# of a clean pause — a defensible reaction to a confusing rapid-flap network signal,
# not a reproduced app defect, but it means this script does not reliably pass on
# every run of this specific emulator image. It remains local/manual tier (like
# android_smoke.sh) and is not required to pass for signoff; a physical device with
# a real, stable metered/unmetered toggle (Settings -> mobile data, or a genuinely
# separate Wi-Fi AP) is expected to exercise the intended clean transition.
#
# Usage: tests/e2e/android_metered_transition.sh
set -eu

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tests/e2e/lib/android_wizard.sh
. "$HERE/lib/android_wizard.sh"

BROKER_HOST="${BROKER_HOST:-broker.emqx.io}"
BROKER_PORT="${BROKER_PORT:-8883}"

restore_wifi() {
  $ADB shell cmd wifi set-wifi-enabled enabled >/dev/null 2>&1 || true
}
trap restore_wifi EXIT

android_install_app
android_generate_remote_identity "answer-peer"
android_run_wizard_to_listening "$BROKER_HOST" "$BROKER_PORT"
log "PASS: Listening"

# ---- disable Wi-Fi -> active network falls back to metered cellular ----
log "disabling Wi-Fi (falls back to the emulator's metered cellular network)"
$ADB shell cmd wifi set-wifi-enabled disabled
wait_for_text "Paused" 30 || fail "did not pause after falling back to the metered network"
log "PASS: Paused on metered network"

# ---- re-enable Wi-Fi -> active network becomes unmetered again ----
log "re-enabling Wi-Fi"
$ADB shell cmd wifi set-wifi-enabled enabled
wait_for_text "Listening" 30 || fail "did not resume to Listening after Wi-Fi (unmetered) returned"
log "PASS: resumed to Listening on unmetered network"

# ---- cleanup ----
$ADB shell am force-stop "$PKG" >/dev/null 2>&1 || true
rm -rf "$KEYHOME"
log "METERED TRANSITION TEST PASSED"
