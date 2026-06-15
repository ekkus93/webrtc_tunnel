#!/usr/bin/env bash
#
# Phase B (full data path) — Android emulator offer -> WebRTC -> dockerized answer,
# with REAL bytes through the tunnel.
#
# Pipeline:
#   host curl --(adb forward)--> emulator 127.0.0.1:8080 (offer listener)
#     --WebRTC data channel--> dockerized p2p-answer --> target (127.0.0.1:9099) --> back
#
# Drives the real app through the setup wizard (shared automation in
# lib/android_wizard.sh), then runs p2p-answer in a container (host networking, so it
# advertises a reachable address) that authorizes the app's own generated identity,
# and asserts a unique marker is pulled through the tunnel.
#
# This exercises the path that was previously blocked: webrtc-rs could not gather a
# host ICE candidate on Android (getifaddrs/NETLINK is restricted on Android 11+), so
# the emulator only offered a srflx candidate and no connection formed. The
# SettingEngine interface-fallback in p2p-webrtc fixes that; this test guards it.
#
# Requirements: a running emulator/device (`adb`), Android SDK, docker, curl, python3,
# internet access to the broker, and the host CA bundle. Uses a PUBLIC broker by
# default (broker.emqx.io). Override with BROKER_HOST/BROKER_PORT. Set REBUILD=0 to
# skip the APK rebuild.
#
# Usage: tests/e2e/android_tunnel_e2e.sh
set -eu

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tests/e2e/lib/android_wizard.sh
. "$HERE/lib/android_wizard.sh"

BROKER_HOST="${BROKER_HOST:-broker.emqx.io}"
BROKER_PORT="${BROKER_PORT:-8883}"
ANSWER_IMAGE="${ANSWER_IMAGE:-ubuntu:24.04}"
ANSWER_CONTAINER="p2p-android-e2e-answer"
CA="/etc/ssl/certs/ca-certificates.crt"

# ANSWER_NET: docker networking for the answer (see android_tunnel_debug.sh).
#   host   - --network host: answer advertises the host's real LAN/srflx address.
#   bridge - default Docker bridge (NAT), reaching the host target via docker0 gateway.
ANSWER_NET="${ANSWER_NET:-host}"
case "$ANSWER_NET" in
  host)   DOCKER_NET_ARGS="--network host"; TARGET_BIND="127.0.0.1"; TARGET_HOST_CFG="127.0.0.1" ;;
  bridge) DOCKER_NET_ARGS="--add-host=host.docker.internal:host-gateway"
          TARGET_BIND="0.0.0.0"
          TARGET_HOST_CFG="$(ip -br addr show docker0 2>/dev/null | grep -oE '172\.[0-9.]+' | head -1)"
          [ -n "$TARGET_HOST_CFG" ] || TARGET_HOST_CFG="172.17.0.1" ;;
  *) fail "ANSWER_NET must be 'host' or 'bridge' (got '$ANSWER_NET')" ;;
esac

# ANDROID_ICE_MODE: force the app's android_ice_mode via a debug system property (read by
# the debug build at config-render time). Device-agnostic; survives SELinux run-as-write
# restrictions on physical devices. Empty => leave the app default ("auto").
ANDROID_ICE_MODE="${ANDROID_ICE_MODE:-}"
case "$ANDROID_ICE_MODE" in
  ""|auto|native|vnet) ;;
  *) fail "ANDROID_ICE_MODE must be auto|native|vnet (got '$ANDROID_ICE_MODE')" ;;
esac

# BLACK_HOLE=1: run the answer in debug drop-ping mode (opens the data channel but never
# replies to the tunnel Ping), so the offer's data-plane probe times out. The test then
# asserts fail-fast behavior instead of byte delivery.
BLACK_HOLE="${BLACK_HOLE:-0}"

command -v docker >/dev/null || fail "docker not found"
command -v curl >/dev/null || fail "curl not found"
command -v python3 >/dev/null || fail "python3 not found"
[ -f "$CA" ] || fail "system CA bundle not found at $CA"

ANSWER_BIN="$ROOT/target/release/p2p-answer"
# Always build (cargo is a near-no-op when current). Reusing a stale binary silently breaks
# the run when the config schema changes: an older answer rejects unknown config keys.
log "building release p2p-answer (incremental)"
( cd "$ROOT" && cargo build --release -q -p p2p-answer )
[ -x "$ANSWER_BIN" ] || fail "p2p-answer build did not produce $ANSWER_BIN"

# ---- drive the app to a Listening offer over the broker ----
android_install_app
# Force the ICE mode (if requested) BEFORE the wizard renders/saves the config; the debug
# build reads this property at config-render time.
if [ -n "$ANDROID_ICE_MODE" ]; then
  log "forcing android_ice_mode=$ANDROID_ICE_MODE via debug.p2p.android_ice_mode"
  $ADB shell setprop debug.p2p.android_ice_mode "$ANDROID_ICE_MODE"
fi
android_generate_remote_identity "answer-peer"
android_run_wizard_to_listening "$BROKER_HOST" "$BROKER_PORT"

# ---- verify the generated config picked up the forced ICE mode (reads work via run-as) ----
if [ -n "$ANDROID_ICE_MODE" ]; then
  GEN_CFG="$($ADB shell run-as "$PKG" cat files/config.toml 2>/dev/null || true)"
  printf '%s' "$GEN_CFG" | grep -q "android_ice_mode = \"$ANDROID_ICE_MODE\"" \
    || fail "generated config does not contain android_ice_mode=\"$ANDROID_ICE_MODE\""
  log "verified generated config android_ice_mode=$ANDROID_ICE_MODE"
fi

# ---- read the app's public identity so the answer can authorize it ----
APP_PUB=/tmp/p2p_android_e2e_app.pub
$ADB shell run-as "$PKG" cat files/identity.pub > "$APP_PUB" 2>/dev/null || true
[ -s "$APP_PUB" ] || fail "could not read app identity.pub via run-as (is this a debug build?)"
APP_PEER="$(grep -oE 'peer_id=[^ ]+' "$APP_PUB" | head -1 | cut -d= -f2)"
[ -n "$APP_PEER" ] || APP_PEER="android-phone"
log "app peer id: $APP_PEER"

# ---- target service the answer forwards to (host process; answer reaches it via
#      host-networked container loopback) ----
TARGET_DIR="$(mktemp -d)"; chmod 700 "$TARGET_DIR"
MARKER="ANDROID-TUNNEL-E2E-OK-$$"
printf '%s\n' "$MARKER" > "$TARGET_DIR/marker.txt"
# Pick a free port so repeated runs never collide on a fixed port.
TARGET_PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')"
# Run python directly (no subshell) so $! is the real server PID that cleanup kills.
python3 -m http.server "$TARGET_PORT" --bind "$TARGET_BIND" --directory "$TARGET_DIR" \
  >/tmp/p2p_android_e2e_target.log 2>&1 &
TARGET_PID=$!
log "target server on 127.0.0.1:$TARGET_PORT (pid $TARGET_PID)"

# ---- answer assets (identity from the wizard's remote keypair; authorize the app) ----
ANSWER_DIR="$(mktemp -d)"; chmod 700 "$ANSWER_DIR"
cp "$KEYHOME/.config/p2ptunnel/identity" "$ANSWER_DIR/identity"
chmod 600 "$ANSWER_DIR/identity"
cp "$APP_PUB" "$ANSWER_DIR/authorized_keys"
cat > "$ANSWER_DIR/answer.toml" <<EOF
format = "p2ptunnel-config-v3"
[node]
peer_id = "$REMOTE_PEER"
role = "answer"
[paths]
identity = "/cfg/identity"
authorized_keys = "/cfg/authorized_keys"
state_dir = "/cfg/state"
log_dir = "/cfg/state/log"
[broker]
url = "mqtts://$BROKER_HOST:$BROKER_PORT"
client_id = "$REMOTE_PEER"
topic_prefix = "p2ptunnel"
username = ""
password_file = ""
qos = 1
keepalive_secs = 30
clean_session = false
connect_timeout_secs = 5
session_expiry_secs = 0
[broker.tls]
ca_file = "$CA"
client_cert_file = ""
client_key_file = ""
insecure_skip_verify = false
[webrtc]
stun_urls = ["stun:stun.l.google.com:19302"]
enable_trickle_ice = true
enable_ice_restart = true
android_ice_mode = "auto"
[tunnel]
read_chunk_size = 16384
local_eof_grace_ms = 250
remote_eof_grace_ms = 250
data_plane_probe_timeout_ms = 5000
[[forwards]]
id = "llama"
[forwards.answer]
target_host = "$TARGET_HOST_CFG"
target_port = $TARGET_PORT
allow_remote_peers = ["$APP_PEER"]
[reconnect]
enable_auto_reconnect = true
strategy = "ice_then_renegotiate"
ice_restart_timeout_secs = 8
renegotiate_timeout_secs = 20
backoff_initial_ms = 1000
backoff_max_ms = 30000
backoff_multiplier = 2.0
jitter_ratio = 0.20
max_attempts = 0
hold_local_client_during_reconnect = false
local_client_hold_secs = 0
[security]
require_mqtt_tls = true
require_message_encryption = true
require_message_signatures = true
require_authorized_keys = true
max_clock_skew_secs = 120
max_message_age_secs = 300
replay_cache_size = 10000
reject_unknown_config_keys = true
refuse_world_readable_identity = true
refuse_world_writable_paths = true
[logging]
level = "info"
format = "text"
file_logging = false
stdout_logging = true
log_file = "/cfg/state/log/p2ptunnel.log"
redact_secrets = true
redact_sdp = true
redact_candidates = true
log_rotation = "none"
[health]
status_socket = ""
write_status_file = false
status_file = "/cfg/state/status.json"
EOF

cleanup() {
  $ADB forward --remove tcp:18080 >/dev/null 2>&1 || true
  docker rm -f "$ANSWER_CONTAINER" >/dev/null 2>&1 || true
  kill "$TARGET_PID" >/dev/null 2>&1 || true
  $ADB shell am force-stop "$PKG" >/dev/null 2>&1 || true
  # Clear the forced ICE mode so it does not leak into later runs.
  [ -n "$ANDROID_ICE_MODE" ] && $ADB shell setprop debug.p2p.android_ice_mode '""' >/dev/null 2>&1 || true
  rm -rf "$KEYHOME" "$ANSWER_DIR" "$TARGET_DIR"
}
trap cleanup EXIT

# ---- run the answer in a container (real sockets; network per ANSWER_NET) ----
docker rm -f "$ANSWER_CONTAINER" >/dev/null 2>&1 || true
BLACK_HOLE_ENV=()
if [ "$BLACK_HOLE" = "1" ]; then
  BLACK_HOLE_ENV=(-e P2P_TUNNEL_DEBUG_DROP_PING=1)
  log "BLACK_HOLE mode: answer will drop inbound tunnel PINGs (probe must fail fast)"
fi
log "starting dockerized p2p-answer ($ANSWER_IMAGE, net=$ANSWER_NET, black_hole=$BLACK_HOLE)"
# shellcheck disable=SC2086  # DOCKER_NET_ARGS intentionally expands to multiple args
docker run -d --rm --name "$ANSWER_CONTAINER" \
  $DOCKER_NET_ARGS \
  "${BLACK_HOLE_ENV[@]}" \
  --user "$(id -u):$(id -g)" \
  -v "$ANSWER_BIN":/p2p-answer:ro \
  -v "$ANSWER_DIR":/cfg \
  -v "$CA":"$CA":ro \
  "$ANSWER_IMAGE" /p2p-answer run --config /cfg/answer.toml >/dev/null
sleep 6  # let the answer connect to the broker

# ---- drive a request THROUGH the tunnel ----
$ADB forward tcp:18080 tcp:8080 >/dev/null
ANSWER_LOG=/tmp/p2p_android_e2e_answer_full.log
dump_answer_log() { docker logs "$ANSWER_CONTAINER" > "$ANSWER_LOG" 2>&1 || true; }

if [ "$BLACK_HOLE" = "1" ]; then
  # Black-hole: the data plane must NOT deliver, and must fail FAST (not hang). One bounded
  # request: if the probe gate works, the offer tears the session down and drops the client,
  # so curl returns promptly with no marker rather than hanging.
  log "BLACK_HOLE: issuing a bounded request; expecting fast failure, not delivery…"
  for _ in $(seq 1 8); do
    body="$(curl -s --max-time 10 http://127.0.0.1:18080/marker.txt 2>/dev/null || true)"
    printf '%s' "$body" | grep -q "$MARKER" && fail "BLACK_HOLE: marker was delivered but should not have been"
    sleep 3
  done
  dump_answer_log
  if grep -qi "dropping inbound tunnel PING" "$ANSWER_LOG"; then
    log "PASS: answer black-holed the probe PING and no bytes were delivered (fail-fast)"
    grep -iE "received tunnel PING|dropping inbound tunnel PING" "$ANSWER_LOG" | tail -5 | sed 's/^/    [answer] /' || true
    exit 0
  fi
  log "FAIL: expected the answer to log a dropped PING in BLACK_HOLE mode."
  grep -iE "ping|pong|data channel|peer connection|error" "$ANSWER_LOG" | tail -30 | sed 's/^/    [answer] /' || true
  exit 1
fi

log "requesting through the tunnel (first request triggers WebRTC negotiation)…"
RESULT=""
for _ in $(seq 1 60); do
  body="$(curl -s --max-time 8 http://127.0.0.1:18080/marker.txt 2>/dev/null || true)"
  if printf '%s' "$body" | grep -q "$MARKER"; then RESULT="ok"; break; fi
  sleep 2
done

if [ "$RESULT" = "ok" ]; then
  log "PASS: marker delivered THROUGH the Android offer tunnel to the dockerized answer"
  # The healthy path must have exercised the data-plane probe round trip.
  dump_answer_log
  if grep -qi "received tunnel PING; sending PONG" "$ANSWER_LOG"; then
    log "verified data-plane probe: answer received PING and replied PONG"
  else
    log "WARN: marker delivered but no probe PING/PONG seen in answer logs (level=info?)"
  fi
  exit 0
fi
log "FAIL: marker not delivered through the tunnel within timeout."
dump_answer_log
log "answer data-path events:"
grep -iE "data channel|data_channel|open|forward|target|stream|connect|error|peer connection|ping|pong" \
  "$ANSWER_LOG" | tail -30 | sed 's/^/    [answer] /' || true
log "target server log:"
sed 's/^/    [target] /' /tmp/p2p_android_e2e_target.log 2>/dev/null | tail -10 || true
exit 1
