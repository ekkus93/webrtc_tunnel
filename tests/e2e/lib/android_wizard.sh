#!/usr/bin/env bash
#
# Shared Android setup-wizard automation for the emulator E2E scripts
# (android_smoke.sh and android_tunnel_e2e.sh). Sourced, not executed.
#
# Drives the real app on a running emulator/device through the from-scratch setup
# wizard via uiautomator (screen-size independent). Exposes:
#   - vars: ROOT, ADB, PKG, APK, P2PCTL, KEYHOME, REMOTE_PEER, PUB, PUB_INPUT
#   - fns:  log, fail, android_install_app, android_generate_remote_identity,
#           android_run_wizard_to_listening
#
# Note: intentionally NOT using `pipefail` — the UI-scraping helpers rely on grep
# returning no match (exit 1) without aborting command substitutions under `set -e`.
# shellcheck disable=SC2086  # "cx cy" coordinate strings are intentionally
# word-split into two positional args for `adb input tap`.

P2P_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${ROOT:-$(cd "$P2P_LIB_DIR/../../.." && pwd)}"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PKG="com.phillipchin.webrtctunnel"
ACT=".MainActivity"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
P2PCTL="$ROOT/target/debug/p2pctl"
XML=/tmp/p2p_e2e_ui.xml

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

# Reliably type a long string (e.g. a ~140-char base64 identity): `adb input text`
# silently drops characters on long fast input, so type word-by-word in small chunks and
# emit real spaces via KEYCODE_SPACE (62) rather than the fragile `%s` space-escape.
input_text_reliable() {
  local s="$1" first=1 w i
  for w in $s; do
    [ "$first" = 0 ] && $ADB shell input keyevent 62
    first=0
    i=0
    while [ "$i" -lt "${#w}" ]; do
      $ADB shell input text "${w:$i:8}"
      i=$((i + 8))
      sleep 0.12
    done
  done
}

tap_text() {
  dump
  local xy; xy="$(bounds_of_text "$1")"
  [ -n "$xy" ] || return 1
  tap_xy $xy
}

screen_w() { $ADB shell wm size | sed -E 's/.*: ([0-9]+)x([0-9]+).*/\1/' | tail -1; }
screen_h() { $ADB shell wm size | sed -E 's/.*: ([0-9]+)x([0-9]+).*/\2/' | tail -1; }

# Tap "Next". Long step content (e.g. the generated identity on the Identity step)
# can scroll the bottom nav row off-screen, so if "Next" isn't visible, scroll the
# content up and retry — uiautomator-located, hence screen-size independent.
tap_next() {
  dump
  local xy; xy="$(bounds_of_text "Next")"
  if [ -n "$xy" ]; then tap_xy $xy; return 0; fi
  local w h; w="$(screen_w)"; h="$(screen_h)"
  local _
  for _ in 1 2 3; do
    $ADB shell input swipe "$(( w/2 ))" "$(( h*70/100 ))" "$(( w/2 ))" "$(( h*25/100 ))" 250
    sleep 1
    dump
    xy="$(bounds_of_text "Next")"
    if [ -n "$xy" ]; then tap_xy $xy; return 0; fi
  done
  return 1
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

# Dismiss the soft keyboard. When the IME is shown, BACK (keyevent 4) is consumed by
# the IME to hide itself and does NOT navigate the Activity. Only call right after
# typing (keyboard guaranteed up), else BACK would pop the screen.
hide_kbd() { $ADB shell input keyevent 4 >/dev/null 2>&1 || true; sleep 0.5; }

# ---- build + install the debug APK; reset app state ----
android_install_app() {
  [ -x "$ADB" ] || fail "adb not found at $ADB (set ADB=...)"
  local state; state="$($ADB get-state 2>/dev/null || true)"
  [ "$state" = "device" ] || fail "no emulator/device (adb get-state='$state'). Boot an emulator first."
  [ -x "$P2PCTL" ] || { log "building p2pctl"; ( cd "$ROOT" && cargo build -q -p p2pctl ); }

  if [ ! -f "$APK" ] || [ "${REBUILD:-1}" = "1" ]; then
    log "building debug APK"
    ( cd "$ROOT/android" && ./gradlew --no-daemon -q assembleDebug )
  fi
  log "installing APK"
  $ADB install -r "$APK" >/dev/null
  log "resetting app state"
  $ADB shell pm clear "$PKG" >/dev/null
  $ADB shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
}

# ---- generate a remote peer identity (input-safe: no + or / in base64) ----
# Sets KEYHOME, REMOTE_PEER, PUB, PUB_INPUT.
android_generate_remote_identity() {
  REMOTE_PEER="${1:-answer-peer}"
  log "generating remote peer identity ($REMOTE_PEER)"
  KEYHOME="$(mktemp -d)"
  PUB=""
  local _
  for _ in $(seq 1 50); do
    HOME="$KEYHOME" "$P2PCTL" keygen "$REMOTE_PEER" --force >/dev/null 2>&1
    local cand; cand="$(cat "$KEYHOME/.config/p2ptunnel/identity.pub")"
    if ! printf '%s' "$cand" | grep -q '[+/]'; then PUB="$cand"; break; fi
  done
  [ -n "$PUB" ] || fail "could not generate input-safe peer identity"
  PUB_INPUT="${PUB// /%s}"   # spaces -> %s for `adb input text`
}

# ---- launch the app, drive the 7-step wizard, Start, wait for Listening ----
# Args: BROKER_HOST BROKER_PORT  (uses REMOTE_PEER + PUB_INPUT globals)
android_run_wizard_to_listening() {
  local broker_host="$1" broker_port="$2"

  log "launching app"
  $ADB shell am start -n "$PKG/$ACT" >/dev/null
  sleep 3
  dump
  local W H
  W="$(screen_w)"; H="$(screen_h)"
  if ! bounds_of_text "Settings" >/dev/null 2>&1; then
    wait_for_text "Settings" 15 || fail "home never rendered"
  fi
  # Settings tab: locate it via uiautomator (the bottom nav sits above any system
  # gesture area, so a hardcoded % of screen height misses on tall/physical devices).
  tap_text "Settings" || fail "could not find Settings nav tab"
  sleep 1
  tap_text "Run setup wizard again" || fail "could not open setup wizard"
  wait_for_text "Step 1 of 7: Mode" 15 || fail "wizard did not open at Mode step"

  log "wizard: Mode"
  tap_next; sleep 2
  wait_for_text "Step 2 of 7: Identity" 15 || fail "did not reach Identity step"

  log "wizard: Identity (generate)"
  tap_text "Generate identity" || fail "no Generate identity button"
  wait_for_text "Identity generated" 20 || fail "identity was not generated"
  tap_next; sleep 2
  wait_for_text "Step 3 of 7: Broker" 15 || fail "did not reach Broker step"

  log "wizard: Broker ($broker_host:$broker_port)"
  dump
  local HOSTXY; HOSTXY="$(editext_center 1)"; [ -n "$HOSTXY" ] || fail "broker host field not found"
  tap_xy $HOSTXY; sleep 1
  # The broker host field is pre-filled with a default (broker.emqx.io), so move to the
  # end and backspace it clear before typing — otherwise the value concatenates.
  # shellcheck disable=SC2046  # intentional word-splitting of the repeated keycodes
  $ADB shell input keyevent 123 $(printf '67 %.0s' $(seq 1 40)); sleep 1
  $ADB shell input text "$broker_host"; sleep 1
  if [ "$broker_port" != "8883" ]; then
    dump; local PORTXY; PORTXY="$(editext_center 2)"; tap_xy $PORTXY
    $ADB shell input text "$broker_port"
  fi
  hide_kbd; sleep 1
  tap_next; sleep 2
  wait_for_text "Step 4 of 7: Remote Peer" 15 || fail "did not reach Remote Peer step"

  log "wizard: Remote Peer"
  dump
  local PIDXY; PIDXY="$(editext_center 1)"; tap_xy $PIDXY; sleep 1
  $ADB shell input text "$REMOTE_PEER"; hide_kbd; sleep 1
  dump
  local PUBXY; PUBXY="$(editext_center 2)"; tap_xy $PUBXY; sleep 1
  # PUB has real spaces; input_text_reliable handles spaces + avoids long-string char drops
  # (a single `input text` of the ~140-char identity corrupts it -> base64 padding errors).
  input_text_reliable "$PUB"; hide_kbd; sleep 1
  # Validation is optional for advancing (the step gates only on both fields being
  # non-blank; the identity is also validated at save). Tap it best-effort — there is
  # no on-Peer-step success banner to wait for (the "validated" text is on Review).
  tap_text "Validate remote identity" || true
  sleep 1
  tap_next; sleep 2
  wait_for_text "Step 5 of 7: Forwards" 15 || fail "did not reach Forwards step (default forward expected)"

  log "wizard: Forwards + Network Policy (defaults)"
  tap_next; sleep 2
  wait_for_text "Step 7 of 7: Review" 20 || { tap_next; sleep 2; wait_for_text "Step 7 of 7: Review" 15 || fail "did not reach Review"; }

  log "wizard: Review -> Start Tunnel"
  $ADB shell input swipe "$(( W/2 ))" "$(( H*78/100 ))" "$(( W/2 ))" "$(( H*25/100 ))" 300; sleep 1
  tap_text "Start Tunnel" || fail "could not find Start Tunnel on review"

  log "waiting for Listening"
  wait_for_text "Listening" 60 || { dump; fail "tunnel did not reach Listening"; }
  log "offer is Listening"
}
