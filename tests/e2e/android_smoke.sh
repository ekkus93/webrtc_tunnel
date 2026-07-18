#!/usr/bin/env bash
#
# Phase B (smoke) — Android emulator setup-wizard smoke test.
#
# Drives the real Android app on a running emulator/device through a from-scratch
# setup wizard against a real MQTT broker, then asserts the offer tunnel reaches a
# live "Listening" state (broker-connected, forward listening; no peer is connected
# in this smoke, so the truthful label is Listening, not Connected) and that Stop
# reverts it.
#
# This is the SMOKE tier: it proves the Android .so/JNI/Kotlin/foreground-service
# stack connects to a real broker over TLS and binds its local forward listener.
# For the full data path (Android offer -> WebRTC -> answer -> target, with real
# bytes) see android_tunnel_e2e.sh.
#
# Requirements: a running emulator/device (`adb`), the Android SDK, a built p2pctl
# (`cargo build -p p2pctl`), and internet access to the broker. Uses a PUBLIC broker
# by default (broker.emqx.io) so no local CA provisioning is needed (the Android app
# trusts public roots via webpki-roots). Override with BROKER_HOST/BROKER_PORT.
#
# Usage: tests/e2e/android_smoke.sh
set -eu

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tests/e2e/lib/android_wizard.sh
. "$HERE/lib/android_wizard.sh"

BROKER_HOST="${BROKER_HOST:-broker.emqx.io}"
BROKER_PORT="${BROKER_PORT:-8883}"

android_install_app
android_generate_remote_identity "answer-peer"
android_run_wizard_to_listening "$BROKER_HOST" "$BROKER_PORT"
log "PASS: Listening"

# ---- Stop -> assert Stopped ----
log "stopping tunnel"
tap_text "Stop Tunnel" || fail "could not find Stop Tunnel"
# Stop shows a "Stop tunnel?" confirmation dialog; confirm it (the dialog button is "Stop").
sleep 1
tap_text "Stop" || true
wait_for_text "Stopped" 30 || fail "did not return to Stopped after Stop"
log "PASS: Stopped after stop"

# ---- cleanup ----
$ADB shell am force-stop "$PKG" >/dev/null 2>&1 || true
rm -rf "$KEYHOME"
log "SMOKE TEST PASSED"
