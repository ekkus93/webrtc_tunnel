#!/usr/bin/env bash
#
# Phase B (smoke) — Android emulator end-to-end smoke test.
#
# Drives the real Android app on a running emulator/device through a from-scratch
# setup wizard against a real MQTT broker, then asserts the offer tunnel reaches a
# live "Listening" state (broker-connected, forward listening; no peer is connected
# in this smoke, so the truthful label is Listening, not Connected) and that Stop
# reverts it.
#
# This is the SMOKE tier: it proves the Android .so/JNI/Kotlin/foreground-service
# stack connects to a real broker over TLS and binds its local forward listener.
# It does NOT push application data through to a remote answer peer — the full
# data-path E2E is blocked by the lack of TURN support in p2p-webrtc (the emulator
# is behind qemu NAT). See docs/archive/DOCKER_TESTS1_TODO.md (B2) for the deferral.
#
# Requirements: a running emulator/device (`adb`), the Android SDK, a built p2pctl
# (`cargo build -p p2pctl`), and internet access to the broker. Uses a PUBLIC broker
# by default (broker.emqx.io) so no local CA provisioning is needed (the Android app
# trusts public roots via webpki-roots). Override with BROKER_HOST/BROKER_PORT.
#
# Usage: tests/e2e/android_smoke.sh
#
# Note: intentionally NOT using `pipefail` — the UI-scraping helpers rely on grep
# returning no match (exit 1) without aborting command substitutions under `set -e`.
# shellcheck disable=SC2086  # "cx cy" coordinate strings are intentionally
# word-split into two positional args for `adb input tap`.
set -eu

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PKG="com.phillipchin.webrtctunnel"
ACT=".MainActivity"
BROKER_HOST="${BROKER_HOST:-broker.emqx.io}"
BROKER_PORT="${BROKER_PORT:-8883}"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
P2PCTL="$ROOT/target/debug/p2pctl"
XML=/tmp/p2p_e2e_ui.xml
REMOTE_PEER="answer-peer"

log() { printf '\033[1;34m[e2e]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[e2e FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

# ---- UI helpers (uiautomator-based, screen-size independent) ----
dump() { $ADB shell uiautomator dump /sdcard/p2p_e2e.xml >/dev/null 2>&1 || true; $ADB shell cat /sdcard/p2p_e2e.xml 2>/dev/null > "$XML" || true; }

# Echo "cx cy" for the center of the first node whose text equals $1, else nothing.
bounds_of_text() {
  tr '>' '\n' < "$XML" | grep -F "text=\"$1\"" \
    | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' | head -1 \
    | awk 'NF==4{printf "%d %d", ($1+$3)/2, ($2+$4)/2}'
}

# Echo "cx cy" for the center of the Nth (1-based) EditText node.
editext_center() {
  tr '>' '\n' < "$XML" | grep 'class="android.widget.EditText"' \
    | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' | sed -n "${1}p" \
    | awk 'NF==4{printf "%d %d", ($1+$3)/2, ($2+$4)/2}'
}

tap_xy() { $ADB shell input tap "$1" "$2"; }

tap_text() {
  dump
  local xy; xy="$(bounds_of_text "$1")"
  [ -n "$xy" ] || return 1
  tap_xy $xy
}

screen_w() { $ADB shell wm size | sed -E 's/.*: ([0-9]+)x([0-9]+).*/\1/' | tail -1; }
screen_h() { $ADB shell wm size | sed -E 's/.*: ([0-9]+)x([0-9]+).*/\2/' | tail -1; }

# Tap "Next"; on the Broker step it is pushed off-screen by "Test TCP reachability",
# so fall back to the rightmost slot of the nav row (~95% width, ~91% height).
tap_next() {
  dump
  local xy; xy="$(bounds_of_text "Next")"
  if [ -n "$xy" ]; then tap_xy $xy; return 0; fi
  local w h; w="$(screen_w)"; h="$(screen_h)"
  tap_xy "$(( w * 95 / 100 ))" "$(( h * 79 / 100 ))"
}

# Wait until a node with text $1 appears, up to $2 seconds.
wait_for_text() {
  local want="$1" timeout="$2" end; end=$(( $(date +%s) + timeout ))
  while [ "$(date +%s)" -lt "$end" ]; do
    dump
    if tr '>' '\n' < "$XML" | grep -qF "text=\"$want\""; then return 0; fi
    sleep 1
  done
  return 1
}

current_step() { dump; tr '>' '\n' < "$XML" | grep -oE 'Step [0-9] of 7: [A-Za-z ]+' | head -1; }

# Dismiss the soft keyboard. When the IME is shown, BACK (keyevent 4) is consumed by
# the IME to hide itself and does NOT navigate the Activity. Only call right after
# typing (keyboard guaranteed up), else BACK would pop the screen.
hide_kbd() { $ADB shell input keyevent 4 >/dev/null 2>&1 || true; sleep 0.5; }

# ---- preconditions ----
[ -x "$ADB" ] || fail "adb not found at $ADB (set ADB=...)"
state="$($ADB get-state 2>/dev/null || true)"
[ "$state" = "device" ] || fail "no emulator/device (adb get-state='$state'). Boot an emulator first."
[ -x "$P2PCTL" ] || { log "building p2pctl"; ( cd "$ROOT" && cargo build -q -p p2pctl ); }

# ---- build + install ----
if [ ! -f "$APK" ] || [ "${REBUILD:-1}" = "1" ]; then
  log "building debug APK"
  ( cd "$ROOT/android" && ./gradlew --no-daemon -q assembleDebug )
fi
log "installing APK"
$ADB install -r "$APK" >/dev/null
log "resetting app state"
$ADB shell pm clear "$PKG" >/dev/null
$ADB shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

# ---- generate a remote peer identity (input-safe: no + or / in base64) ----
log "generating remote peer identity"
KEYHOME="$(mktemp -d)"
PUB=""
for _ in $(seq 1 50); do
  HOME="$KEYHOME" "$P2PCTL" keygen "$REMOTE_PEER" --force >/dev/null 2>&1
  cand="$(cat "$KEYHOME/.config/p2ptunnel/identity.pub")"
  if ! printf '%s' "$cand" | grep -q '[+/]'; then PUB="$cand"; break; fi
done
[ -n "$PUB" ] || fail "could not generate input-safe peer identity"
PUB_INPUT="${PUB// /%s}"   # spaces -> %s for `adb input text`

# ---- launch + open the setup wizard ----
log "launching app"
$ADB shell am start -n "$PKG/$ACT" >/dev/null
sleep 3
# Settings tab -> Run setup wizard again
dump
SET="$(bounds_of_text "Settings")"; [ -n "$SET" ] || { wait_for_text "Settings" 15 || fail "home never rendered"; SET="$(bounds_of_text "Settings")"; }
# Bottom nav "Settings" is the last; tap it via its known bottom position if text match is ambiguous.
W="$(screen_w)"; H="$(screen_h)"
tap_xy "$(( W * 88 / 100 ))" "$(( H * 95 / 100 ))"   # Settings tab
sleep 1
tap_text "Run setup wizard again" || fail "could not open setup wizard"
wait_for_text "Step 1 of 7: Mode" 15 || fail "wizard did not open at Mode step"

# ---- Step 1: Mode -> Next ----
log "wizard: Mode"
tap_next; sleep 2
wait_for_text "Step 2 of 7: Identity" 15 || fail "did not reach Identity step"

# ---- Step 2: Identity -> Generate identity -> Next ----
log "wizard: Identity (generate)"
tap_text "Generate identity" || fail "no Generate identity button"
wait_for_text "Identity generated" 20 || fail "identity was not generated"
tap_next; sleep 2
wait_for_text "Step 3 of 7: Broker" 15 || fail "did not reach Broker step"

# ---- Step 3: Broker host (port 8883 + TLS are defaults) -> Next ----
log "wizard: Broker ($BROKER_HOST:$BROKER_PORT)"
dump
HOSTXY="$(editext_center 1)"; [ -n "$HOSTXY" ] || fail "broker host field not found"
tap_xy $HOSTXY; sleep 1
$ADB shell input text "$BROKER_HOST"; sleep 1
# If non-default port requested, set field 2.
if [ "$BROKER_PORT" != "8883" ]; then
  dump; PORTXY="$(editext_center 2)"; tap_xy $PORTXY
  $ADB shell input text "$BROKER_PORT"
fi
hide_kbd; sleep 1
tap_next; sleep 2
wait_for_text "Step 4 of 7: Remote Peer" 15 || fail "did not reach Remote Peer step"

# ---- Step 4: Remote peer id + public identity -> Validate -> Next ----
log "wizard: Remote Peer"
dump
PIDXY="$(editext_center 1)"; tap_xy $PIDXY; sleep 1
$ADB shell input text "$REMOTE_PEER"; hide_kbd; sleep 1
dump
PUBXY="$(editext_center 2)"; tap_xy $PUBXY; sleep 1
$ADB shell input text "$PUB_INPUT"; hide_kbd; sleep 1
tap_text "Validate remote identity" || fail "no Validate button"
wait_for_text "Remote public identity validated" 15 || fail "remote identity not validated"
tap_next; sleep 2
wait_for_text "Step 5 of 7: Forwards" 15 || fail "did not reach Forwards step (default forward expected)"

# ---- Steps 5 (Forwards, keep default) & 6 (Network Policy) -> Next ----
log "wizard: Forwards + Network Policy (defaults)"
tap_next; sleep 2
wait_for_text "Step 7 of 7: Review" 20 || { tap_next; sleep 2; wait_for_text "Step 7 of 7: Review" 15 || fail "did not reach Review"; }

# ---- Step 7: Review -> scroll to Start Tunnel ----
log "wizard: Review -> Start Tunnel"
$ADB shell input swipe "$(( W/2 ))" "$(( H*78/100 ))" "$(( W/2 ))" "$(( H*25/100 ))" 300; sleep 1
tap_text "Start Tunnel" || fail "could not find Start Tunnel on review"

# ---- assert: Listening (broker-connected, forward listening; no peer in this smoke) ----
# With truthful status mapping, offer mode without an active peer session shows
# "Listening" (status card) rather than "Connected"; Connected is reserved for an
# actual session. The forward chip also reads "Listening".
log "waiting for Listening"
wait_for_text "Listening" 60 || { dump; tr '>' '\n' < "$XML" | grep -oE 'text="[^"]+"' | sed -E 's/text="(.*)"/\1/' | grep -iE "error|mqtt|tls|connect|stopped|listen" | head; fail "tunnel did not reach Listening"; }
log "PASS: Listening"

# ---- Stop -> assert Stopped ----
log "stopping tunnel"
tap_text "Stop Tunnel" || fail "could not find Stop Tunnel"
wait_for_text "Stopped" 30 || fail "did not return to Stopped after Stop"
log "PASS: Stopped after stop"

# ---- cleanup ----
$ADB shell am force-stop "$PKG" >/dev/null 2>&1 || true
rm -rf "$KEYHOME"
log "SMOKE TEST PASSED"
