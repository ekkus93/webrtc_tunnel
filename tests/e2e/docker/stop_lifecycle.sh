#!/usr/bin/env bash
#
# Docker stop lifecycle verification (P1-002): proves the existing `exec`
# container launch pattern actually receives `docker stop`'s SIGTERM and shuts
# down gracefully, rather than hanging until the forced-kill timeout.
#
# Brings up a real TLS mosquitto broker + offer + answer as separate containers
# (raw `docker run` on a dedicated network, independent of compose.yaml/run.sh
# so this cannot destabilize the existing tunnel E2E), waits for both daemons to
# reach steady state, then runs `docker stop -t 10` on offer and answer and
# asserts:
#   - neither hits the 10s grace timeout (i.e. stops quickly, not via SIGKILL);
#   - both exit 0;
#   - both logs show the shutdown-request/drain messages;
#   - the mounted status file reports `closed` for each.
#
# Requires: docker, openssl. Host-built release binaries are mounted into
# ubuntu:24.04 (matching host glibc), so no in-Docker workspace build.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
GEN="$HERE/generated-stop-lifecycle"
P2PCTL="$ROOT/target/release/p2pctl"
NET="p2p-stop-lifecycle-net-$$"
MOSQUITTO_IMAGE="eclipse-mosquitto:2"

log() { printf '\033[1;34m[stop-lifecycle]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[stop-lifecycle FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null || fail "docker not found"
command -v openssl >/dev/null || fail "openssl not found"

cleanup() {
  log "tearing down"
  docker rm -f p2p-sl-broker p2p-sl-target p2p-sl-offer p2p-sl-answer >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT

for bin in p2p-offer p2p-answer p2pctl; do
  if [ ! -x "$ROOT/target/release/$bin" ]; then
    log "building release binaries (missing $bin)"
    ( cd "$ROOT" && cargo build --release -p p2p-offer -p p2p-answer -p p2pctl )
    break
  fi
done

rm -rf "$GEN"
mkdir -p "$GEN/certs" "$GEN/state-offer" "$GEN/state-answer"

log "generating CA + broker server cert"
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout "$GEN/certs/ca.key" -out "$GEN/certs/ca.crt" \
  -subj "/CN=p2p-sl-ca" -days 3 -addext "basicConstraints=critical,CA:TRUE" >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes \
  -keyout "$GEN/certs/server.key" -out "$GEN/certs/server.csr" \
  -subj "/CN=broker" >/dev/null 2>&1
printf 'subjectAltName=DNS:broker,DNS:localhost,IP:127.0.0.1\nbasicConstraints=CA:FALSE\n' > "$GEN/certs/san.ext"
openssl x509 -req -in "$GEN/certs/server.csr" \
  -CA "$GEN/certs/ca.crt" -CAkey "$GEN/certs/ca.key" -CAcreateserial \
  -out "$GEN/certs/server.crt" -days 3 -extfile "$GEN/certs/san.ext" >/dev/null 2>&1
cat > "$GEN/certs/mosquitto.conf" <<'EOF'
listener 8883
allow_anonymous true
cafile /mosquitto/certs/ca.crt
certfile /mosquitto/certs/server.crt
keyfile /mosquitto/certs/server.key
require_certificate false
EOF

log "generating peer identities"
HOME="$GEN/h_offer" "$P2PCTL" keygen offer-peer --force >/dev/null
HOME="$GEN/h_answer" "$P2PCTL" keygen answer-peer --force >/dev/null
cp "$GEN/h_offer/.config/p2ptunnel/identity" "$GEN/offer-identity"
cp "$GEN/h_offer/.config/p2ptunnel/identity.pub" "$GEN/offer.pub"
cp "$GEN/h_answer/.config/p2ptunnel/identity" "$GEN/answer-identity"
cp "$GEN/h_answer/.config/p2ptunnel/identity.pub" "$GEN/answer.pub"
cp "$GEN/answer.pub" "$GEN/offer-authorized_keys"
cp "$GEN/offer.pub" "$GEN/answer-authorized_keys"

emit_config() {
  # $1=role  $2=peer_id  $3=remote_peer_id  $4=identity  $5=authorized_keys
  cat <<EOF
format = "p2ptunnel-config-v3"

[node]
peer_id = "$2"
role = "$1"

[peer]
remote_peer_id = "$3"

[paths]
identity = "/e2e/$4"
authorized_keys = "/e2e/$5"
state_dir = "/var/lib/p2p/state"
log_dir = "/var/lib/p2p/state"

[broker]
url = "mqtts://broker:8883"
client_id = "$2"
topic_prefix = "p2ptunnel-sl"
username = ""
password_file = ""
qos = 1
keepalive_secs = 30
clean_session = false
connect_timeout_secs = 5
session_expiry_secs = 0

[broker.tls]
ca_file = "/e2e/certs/ca.crt"
client_cert_file = ""
client_key_file = ""
insecure_skip_verify = false

[webrtc]
stun_urls = []
enable_trickle_ice = false
enable_ice_restart = true
android_ice_mode = "auto"

[tunnel]
read_chunk_size = 16384
local_eof_grace_ms = 250
remote_eof_grace_ms = 250
data_plane_probe_timeout_ms = 5000

[[forwards]]
id = "web"

[forwards.offer]
listen_host = "127.0.0.1"
listen_port = 8080

[forwards.answer]
target_host = "target"
target_port = 80
allow_remote_peers = ["offer-peer"]

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
log_file = "/var/lib/p2p/state/p2ptunnel.log"
redact_secrets = true
redact_sdp = true
redact_candidates = true
log_rotation = "none"

[health]
status_socket = ""
write_status_file = true
status_file = "/var/lib/p2p/state/status.json"
EOF
}

emit_config offer offer-peer answer-peer offer-identity offer-authorized_keys > "$GEN/offer.toml"
emit_config answer answer-peer offer-peer answer-identity answer-authorized_keys > "$GEN/answer.toml"

chmod 600 "$GEN/offer-identity" "$GEN/answer-identity"
chmod 644 "$GEN/certs/ca.crt" "$GEN/certs/server.crt" "$GEN/certs/server.key" \
  "$GEN"/*.toml "$GEN"/*-authorized_keys "$GEN"/*.pub
find "$GEN" -type d -exec chmod 755 {} +

log "starting stack (network, broker, target, answer, offer)"
docker network create "$NET" >/dev/null
docker run -d --name p2p-sl-broker --network "$NET" --network-alias broker \
  -v "$GEN/certs:/mosquitto/certs:ro" \
  -v "$GEN/certs/mosquitto.conf:/mosquitto/config/mosquitto.conf:ro" \
  "$MOSQUITTO_IMAGE" >/dev/null
docker run -d --name p2p-sl-target --network "$NET" --network-alias target \
  nginx:alpine >/dev/null

deadline=$((SECONDS + 20))
until docker exec p2p-sl-broker mosquitto_pub --cafile /mosquitto/certs/ca.crt \
  -h localhost -p 8883 -t healthcheck -m 1 -q 1 >/dev/null 2>&1; do
  [ "$SECONDS" -lt "$deadline" ] || fail "broker never became healthy"
  sleep 1
done

docker run -d --name p2p-sl-answer --network "$NET" \
  -v "$ROOT/target/release:/p2pbin:ro" \
  -v "$GEN:/e2e:ro" \
  -v "$GEN/state-answer:/var/lib/p2p/state" \
  ubuntu:24.04 /bin/sh -c "exec /p2pbin/p2p-answer run --config /e2e/answer.toml" >/dev/null
docker run -d --name p2p-sl-offer --network "$NET" \
  -v "$ROOT/target/release:/p2pbin:ro" \
  -v "$GEN:/e2e:ro" \
  -v "$GEN/state-offer:/var/lib/p2p/state" \
  ubuntu:24.04 /bin/sh -c "exec /p2pbin/p2p-offer run --config /e2e/offer.toml" >/dev/null

wait_for_status_state() {
  local file="$1" expected="$2" label="$3"
  local deadline=$((SECONDS + 40))
  while :; do
    if [ -f "$file" ] && grep -q "\"current_state\": *\"$expected\"" "$file" 2>/dev/null; then
      return 0
    fi
    [ "$SECONDS" -lt "$deadline" ] || fail "$label status never reached '$expected' (file: $file)"
    sleep 1
  done
}

log "waiting for both daemons to reach steady state over the real broker"
wait_for_status_state "$GEN/state-answer/status.json" "serving" "answer"
wait_for_status_state "$GEN/state-offer/status.json" "waiting_for_local_client" "offer"

log "sending docker stop -t 10 to offer and answer"
start_ts=$SECONDS
docker stop -t 10 p2p-sl-offer p2p-sl-answer >/dev/null
elapsed=$((SECONDS - start_ts))
log "docker stop returned after ${elapsed}s"
[ "$elapsed" -lt 10 ] || fail "stop took ${elapsed}s (>= the 10s grace period; likely hit the forced-kill timeout)"

offer_exit="$(docker inspect p2p-sl-offer --format '{{.State.ExitCode}}')"
answer_exit="$(docker inspect p2p-sl-answer --format '{{.State.ExitCode}}')"
[ "$offer_exit" = "0" ] || fail "offer container exited $offer_exit, expected 0"
[ "$answer_exit" = "0" ] || fail "answer container exited $answer_exit, expected 0"

for name in offer answer; do
  logs="$(docker logs "p2p-sl-$name" 2>&1)"
  echo "$logs" | grep -q 'process shutdown requested' \
    || fail "$name logs missing 'process shutdown requested'"
  echo "$logs" | grep -qE 'shutdown requested' \
    || fail "$name logs missing daemon-level shutdown-requested message"
done

wait_for_status_state "$GEN/state-offer/status.json" "closed" "offer (final)"
wait_for_status_state "$GEN/state-answer/status.json" "closed" "answer (final)"

log "PASS — docker stop reached the process-signal adapter; both daemons drained and exited 0"
