#!/usr/bin/env bash
#
# Persistent debugging rig: Android offer -> WebRTC -> dockerized p2p-answer. Unlike
# android_tunnel_e2e.sh it does NOT tear down, and it runs the answer at DEBUG so the
# frame-level logs (received OPEN / target TCP connected / send errors) are visible via
# `docker logs`. Use it to root-cause a stalled data path with full both-sides
# visibility (answer logs + host packet capture).
#
# Env knobs:
#   ANDROID_SERIAL=<serial>  target a specific phone (required when >1 device attached;
#                            e.g. ANDROID_SERIAL=R5CW31AX4FL for the A54).
#   ANSWER_NET=host|bridge   answer network mode (default host). 'bridge' puts the
#                            answer behind Docker NAT (closer to a Dockerized
#                            answer-office); 'host' advertises the host's real address.
#   ANSWER_LEVEL=debug|info  answer log verbosity (default debug).
#   BROKER_HOST / BROKER_PORT  signaling broker (default broker.emqx.io:8883).
#   REBUILD=0                skip the APK rebuild (install the existing debug APK).
#
# Leaves running: the offer app (Listening), the answer container ($ANSWER_CONTAINER),
# a host target http.server, and an adb forward host:18080 -> device:8080.
#   drive a request : curl -s http://127.0.0.1:18080/marker.txt
#   inspect answer  : docker logs --tail 100 $ANSWER_CONTAINER
#   tear down       : tests/e2e/android_tunnel_debug.sh --clean
#
# Notes:
#   - A same-LAN or cellular phone -> local answer connects directly/via cone-NAT
#     hole-punching and SUCCEEDS; it does NOT reproduce a symmetric-NAT remote failure.
#   - To test on cellular: enable Settings -> Network Policy -> Allow metered, then
#     `adb shell svc wifi disable; adb shell svc data enable`, and tap "Allow This
#     Session" on the paused Home screen. (adb-over-USB is unaffected by the radio.)
#   - See tests/e2e/README.md and the memory.md investigation notes for context.
set -eu

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=tests/e2e/lib/android_wizard.sh
. "$HERE/lib/android_wizard.sh"

BROKER_HOST="${BROKER_HOST:-broker.emqx.io}"
BROKER_PORT="${BROKER_PORT:-8883}"
ANSWER_IMAGE="${ANSWER_IMAGE:-ubuntu:24.04}"
ANSWER_CONTAINER="p2p-android-dbg-answer"
ANSWER_LEVEL="${ANSWER_LEVEL:-debug}"
# Answer container network mode:
#   host   - --network host: answer advertises the host's real LAN/srflx address.
#   bridge - default Docker bridge: answer is behind Docker NAT (matches a Dockerized
#            answer-office). Reaches the host target via the docker0 gateway.
ANSWER_NET="${ANSWER_NET:-host}"
CA="/etc/ssl/certs/ca-certificates.crt"
RIGDIR="/tmp/p2p-android-dbg"   # persistent (not auto-removed) so the rig survives

case "$ANSWER_NET" in
  host)   DOCKER_NET_ARGS="--network host"; TARGET_BIND="127.0.0.1"; TARGET_HOST_CFG="127.0.0.1" ;;
  bridge) DOCKER_NET_ARGS="--add-host=host.docker.internal:host-gateway"
          TARGET_BIND="0.0.0.0"
          TARGET_HOST_CFG="$(ip -br addr show docker0 2>/dev/null | grep -oE '172\.[0-9.]+' | head -1)"
          [ -n "$TARGET_HOST_CFG" ] || TARGET_HOST_CFG="172.17.0.1" ;;
  *) fail "ANSWER_NET must be 'host' or 'bridge' (got '$ANSWER_NET')" ;;
esac

if [ "${1:-}" = "--clean" ]; then
  $ADB forward --remove tcp:18080 >/dev/null 2>&1 || true
  docker rm -f "$ANSWER_CONTAINER" >/dev/null 2>&1 || true
  [ -f "$RIGDIR/target.pid" ] && kill "$(cat "$RIGDIR/target.pid")" >/dev/null 2>&1 || true
  $ADB shell am force-stop "$PKG" >/dev/null 2>&1 || true
  rm -rf "$RIGDIR"
  log "cleaned up debug rig"
  exit 0
fi

command -v docker >/dev/null || fail "docker not found"
[ -f "$CA" ] || fail "system CA bundle not found at $CA"
ANSWER_BIN="$ROOT/target/release/p2p-answer"
[ -x "$ANSWER_BIN" ] || { log "building release p2p-answer"; ( cd "$ROOT" && cargo build --release -q -p p2p-answer ); }

rm -rf "$RIGDIR"; mkdir -p "$RIGDIR"; chmod 700 "$RIGDIR"

# ---- configure the phone as a Listening offer over the broker ----
android_install_app
android_generate_remote_identity "answer-peer"
android_run_wizard_to_listening "$BROKER_HOST" "$BROKER_PORT"

APP_PUB="$RIGDIR/app.pub"
$ADB shell run-as "$PKG" cat files/identity.pub > "$APP_PUB" 2>/dev/null || true
[ -s "$APP_PUB" ] || fail "could not read app identity.pub via run-as (debug build?)"
APP_PEER="$(grep -oE 'peer_id=[^ ]+' "$APP_PUB" | head -1 | cut -d= -f2)"
log "app peer id: $APP_PEER"

# ---- target service the answer forwards to ----
MARKER="ANDROID-DBG-$$"
printf '%s\n' "$MARKER" > "$RIGDIR/marker.txt"
TARGET_PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')"
python3 -m http.server "$TARGET_PORT" --bind "$TARGET_BIND" --directory "$RIGDIR" >"$RIGDIR/target.log" 2>&1 &
echo $! > "$RIGDIR/target.pid"
log "target server on 127.0.0.1:$TARGET_PORT (pid $(cat "$RIGDIR/target.pid"))"

# ---- answer assets (DEBUG level so frame logs are visible) ----
cp "$KEYHOME/.config/p2ptunnel/identity" "$RIGDIR/identity"; chmod 600 "$RIGDIR/identity"
cp "$APP_PUB" "$RIGDIR/authorized_keys"
cat > "$RIGDIR/answer.toml" <<EOF
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
[tunnel]
read_chunk_size = 16384
local_eof_grace_ms = 250
remote_eof_grace_ms = 250
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
level = "$ANSWER_LEVEL"
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

docker rm -f "$ANSWER_CONTAINER" >/dev/null 2>&1 || true
log "starting dockerized p2p-answer ($ANSWER_IMAGE, net=$ANSWER_NET, level=$ANSWER_LEVEL)"
# shellcheck disable=SC2086  # DOCKER_NET_ARGS intentionally expands to multiple args
docker run -d --rm --name "$ANSWER_CONTAINER" \
  $DOCKER_NET_ARGS \
  --user "$(id -u):$(id -g)" \
  -v "$ANSWER_BIN":/p2p-answer:ro \
  -v "$RIGDIR":/cfg \
  -v "$CA":"$CA":ro \
  "$ANSWER_IMAGE" /p2p-answer run --config /cfg/answer.toml >/dev/null
sleep 6

$ADB forward tcp:18080 tcp:8080 >/dev/null

cat <<EOF

================= DEBUG RIG UP (persistent) =================
  phone (ANDROID_SERIAL=${ANDROID_SERIAL:-default}) offer: Listening, peer=$APP_PEER
  answer container : $ANSWER_CONTAINER  (peer=$REMOTE_PEER, net=$ANSWER_NET, level=$ANSWER_LEVEL)
  forward id       : llama -> $TARGET_HOST_CFG:$TARGET_PORT (marker: $MARKER)
  adb forward      : host 127.0.0.1:18080 -> device 8080

  drive a request  : curl -s --max-time 8 http://127.0.0.1:18080/marker.txt
  answer logs      : docker logs --tail 100 $ANSWER_CONTAINER
  tear down        : $0 --clean
============================================================
EOF
