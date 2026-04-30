# rust_webrtc

[![CI](https://github.com/ekkus93/rust_webrtc/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/ekkus93/rust_webrtc/actions/workflows/ci.yml)

`rust_webrtc` is a CLI-only secure TCP tunnel that carries a single TCP stream over a WebRTC data channel while using MQTT only as an untrusted signaling transport.

GitHub Actions runs linting and tests for normal branch and pull request CI. Release artifacts are uploaded only for tagged pushes.

## Build

This project builds with stable Rust and Cargo.

Build the full workspace:

```bash
cargo build --workspace
```

For optimized release binaries:

```bash
cargo build --release --workspace
```

The main executables are:

- `target/debug/p2pctl`
- `target/debug/p2p-offer`
- `target/debug/p2p-answer`

Or, in release mode:

- `target/release/p2pctl`
- `target/release/p2p-offer`
- `target/release/p2p-answer`

## Architecture

The project is split into focused crates:

- `p2p-core`: shared config, IDs, protocol enums, and validation
- `p2p-crypto`: identity files, authorized key parsing, Ed25519/X25519 helpers
- `p2p-signaling`: encrypted and signed MQTT signaling envelopes and inner messages
- `p2p-webrtc`: STUN-only WebRTC wrapper with SDP, ICE, and data-channel hooks
- `p2p-tunnel`: tunnel frame codec and TCP/data-channel bridge
- `p2p-daemon`: offer/answer orchestration, reconnect policy, status output, and logging
- `p2pctl`: key and config management CLI
- `p2p-offer` / `p2p-answer`: runnable daemons

At runtime:

1. The offer node accepts one local TCP client.
2. It creates an encrypted signaling session over MQTT.
3. The answer node validates the sender against `authorized_keys`.
4. Both sides establish a reliable, ordered WebRTC data channel named `tunnel`.
5. Tunnel frames carry one TCP stream with explicit `OPEN`, `DATA`, `CLOSE`, `ERROR`, `PING`, and `PONG` messages.

Ordinary session failures tear down only the active session. The offer daemon then returns to waiting for the next local client, and the answer daemon returns to idle for the next valid offer.

## Trust model

MQTT is **not** trusted. The broker is only a message relay.

In this project, the broker is the MQTT server that both peers connect to in order to exchange signaling messages. It forwards publish/subscribe traffic between clients, but it is not trusted with plaintext tunnel or signaling contents.

- signaling payloads are end-to-end encrypted
- signaling payloads are signed
- sender identity must match both the outer envelope KID and the inner `sp`
- recipient identity must match both the outer recipient KID and the inner `rp`
- replay protection rejects duplicate `(sender_kid, msg_id)` pairs
- signaling publishes always use `retain = false`

## Key workflow

The operator workflow is intentionally SSH-like.

### Local files

```text
~/.config/p2ptunnel/identity
~/.config/p2ptunnel/identity.pub
~/.config/p2ptunnel/authorized_keys
~/.config/p2ptunnel/config.toml
```

### Generate a local identity

```bash
p2pctl keygen offer-home
```

For an answer/server node, generate the answer identity instead:

```bash
p2pctl keygen answer-office
```

This writes:

- `identity` with the private Ed25519 and X25519 keys
- `identity.pub` with the public identity record

The private identity file is written with `0600` permissions.

The identity file is role-specific. The `peer_id` inside `~/.config/p2ptunnel/identity` must match `[node].peer_id` in `~/.config/p2ptunnel/config.toml`, or the daemon will refuse to start. In practice, that means the server should have an `answer-office` identity and answer-side config, while the client should have an `offer-home` identity and offer-side config.

### Exchange and authorize keys

On each side, copy the remote `identity.pub` and add it:

```bash
p2pctl add-authorized-key ./remote.identity.pub
```

You can inspect a public identity fingerprint with:

```bash
p2pctl fingerprint ~/.config/p2ptunnel/identity.pub
```

## Config file

The config format is `p2ptunnel-config-v1`.

`~/.config/p2ptunnel/config.toml` must be a complete config file. `p2pctl check-config` does not accept isolated section snippets such as only `[broker]` or only `[tunnel.answer]`; it expects the top-level `format` field and all required sections.

### Important sections

- `[node]`: local `peer_id` and role (`offer` or `answer`)
- `[paths]`: identity, authorized keys, and local runtime file paths such as the state and log directories
- `[broker]`: the MQTT server connection settings, including broker URL, topic prefix, optional credentials, and TLS requirements
- `[webrtc]`: STUN URLs, trickle ICE, and ICE restart
- `[tunnel]`: bridge behavior for the fixed v1 single-stream protocol
- `[tunnel.offer]`: local listen host/port and remote peer
- `[tunnel.answer]`: target host/port and allowed remote peers
- `[reconnect]`: ICE restart, renegotiation, and backoff settings
- `[security]`: mandatory v1 security requirements such as TLS, encryption, signatures, replay limits, and strict config parsing
- `[logging]`: text/json output, stdout/file logging, and redaction flags
- `[health]`: local status file path

For the public broker examples below, point `broker.tls.ca_file` at your system CA bundle. On Debian and Ubuntu that is usually:

```bash
/etc/ssl/certs/ca-certificates.crt
```

The current implementation requires `broker.tls.ca_file` to point at a real local CA file for `mqtts://` brokers.

### Minimal answer-side config

Use this on the server or remote host, together with an identity generated by `p2pctl keygen answer-office`.

```toml
format = "p2ptunnel-config-v1"

[node]
peer_id = "answer-office"
role = "answer"

[paths]
identity = "~/.config/p2ptunnel/identity"
authorized_keys = "~/.config/p2ptunnel/authorized_keys"
state_dir = "~/.local/state/p2ptunnel"
log_dir = "~/.local/state/p2ptunnel/log"

[broker]
url = "mqtts://broker.emqx.io:8883"
client_id = "answer-office"
topic_prefix = "p2ptunnel"
username = ""
password_file = ""
qos = 1
keepalive_secs = 30
clean_session = false
connect_timeout_secs = 5
session_expiry_secs = 0

[broker.tls]
ca_file = "/etc/ssl/certs/ca-certificates.crt"
client_cert_file = ""
client_key_file = ""
insecure_skip_verify = false

[webrtc]
stun_urls = ["stun:stun.l.google.com:19302"]
enable_trickle_ice = true
enable_ice_restart = true

[tunnel]
stream_id = 1
read_chunk_size = 16384
local_eof_grace_ms = 250
remote_eof_grace_ms = 250

[tunnel.offer]
listen_host = "127.0.0.1"
listen_port = 2222
remote_peer_id = "offer-home"

[tunnel.answer]
target_host = "127.0.0.1"
target_port = 22
allow_remote_peers = ["offer-home"]

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
file_logging = true
stdout_logging = true
log_file = "~/.local/state/p2ptunnel/log/p2ptunnel.log"
redact_secrets = true
redact_sdp = true
redact_candidates = true
log_rotation = "none"

[health]
status_socket = ""
write_status_file = true
status_file = "~/.local/state/p2ptunnel/status.json"
```

### Minimal offer-side config

Use this on the client or laptop, together with an identity generated by `p2pctl keygen offer-home`.

```toml
format = "p2ptunnel-config-v1"

[node]
peer_id = "offer-home"
role = "offer"

[paths]
identity = "~/.config/p2ptunnel/identity"
authorized_keys = "~/.config/p2ptunnel/authorized_keys"
state_dir = "~/.local/state/p2ptunnel"
log_dir = "~/.local/state/p2ptunnel/log"

[broker]
url = "mqtts://broker.emqx.io:8883"
client_id = "offer-home"
topic_prefix = "p2ptunnel"
username = ""
password_file = ""
qos = 1
keepalive_secs = 30
clean_session = false
connect_timeout_secs = 5
session_expiry_secs = 0

[broker.tls]
ca_file = "/etc/ssl/certs/ca-certificates.crt"
client_cert_file = ""
client_key_file = ""
insecure_skip_verify = false

[webrtc]
stun_urls = ["stun:stun.l.google.com:19302"]
enable_trickle_ice = true
enable_ice_restart = true

[tunnel]
stream_id = 1
read_chunk_size = 16384
local_eof_grace_ms = 250
remote_eof_grace_ms = 250

[tunnel.offer]
listen_host = "127.0.0.1"
listen_port = 2222
remote_peer_id = "answer-office"

[tunnel.answer]
target_host = "127.0.0.1"
target_port = 22
allow_remote_peers = ["answer-office"]

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
file_logging = true
stdout_logging = true
log_file = "~/.local/state/p2ptunnel/log/p2ptunnel.log"
redact_secrets = true
redact_sdp = true
redact_candidates = true
log_rotation = "none"

[health]
status_socket = ""
write_status_file = true
status_file = "~/.local/state/p2ptunnel/status.json"
```

The examples above use `broker.emqx.io:8883`, a real public test broker listener with a normal X.509v3 certificate chain that works with the current Rust TLS stack when `broker.tls.ca_file` points at the system CA bundle. The `client_cert_file` and `client_key_file` settings are only for brokers that require mutual TLS client authentication: `client_cert_file` is the client certificate presented to the broker, and `client_key_file` is the matching private key for that certificate. If your broker does not require client certificates, leave both empty as shown above. If your broker does require them, set both together; v1 rejects configs where only one of the two is set. For brokers that require a password, `password_file` should point to a local text file containing only the broker password or token, typically as a single line. In v1, the supported broker auth modes are anonymous or certificate-only (`username = ""`, `password_file = ""`), username-only (`username` set, `password_file = ""`), or username plus password file (`username` set, `password_file` pointing at the password file). In v1, `connect_timeout_secs` must stay `5`, `session_expiry_secs` must stay `0`, TLS server name is derived from the broker URL host, and broker TLS verification cannot be disabled. If you use a public broker, choose a unique `topic_prefix` and treat it as test-only infrastructure.

The current config schema requires both `[tunnel.offer]` and `[tunnel.answer]` blocks to be present even though only the block matching `node.role` is used at runtime. In the opposite-role block, use placeholder values like the examples above.

`paths.state_dir` is the base directory for local runtime artifacts. In the examples above, it is the parent of the log file and status file under `~/.local/state/p2ptunnel`. The daemons create parent directories for `logging.log_file` and `health.status_file` when needed, so you do not usually need to create `~/.local/state/p2ptunnel` manually before running them. Creating it ahead of time with `mkdir -p ~/.local/state/p2ptunnel/log` is still a safe setup step if you want the paths to exist before the first run.

The fixed v1 protocol constants for ICE timing behavior, WebRTC message size, tunnel frame version, and single-stream handling live in code and the spec rather than in the public config file.

The `[security]` section is intentionally fail-closed in v1: required TLS, encryption, signatures, authorized keys, strict unknown-key rejection, and path/identity safety checks must stay enabled rather than being treated as optional tuning knobs.

While an offer-side session is active, additional local TCP clients are accepted and immediately closed with no banner. During an active answer-side session, only a fully allowed peer may receive an encrypted `busy` response; unauthorized or disallowed peers receive no response, and duplicate replays of the same foreign offer are dropped from the active-session dedupe cache before they can trigger repeated `busy` replies or a second full reclassification pass.

Check the effective configuration with:

```bash
p2pctl check-config --config ~/.config/p2ptunnel/config.toml
```

`check-config` also verifies that referenced files already exist, including `identity`, `authorized_keys`, and `broker.tls.ca_file`.

## Running offer and answer

### Always-on home answer daemon

```bash
p2p-answer run --config ~/.config/p2ptunnel/config.toml
```

Optional runtime overrides:

```bash
p2p-answer run \
  --config ~/.config/p2ptunnel/config.toml \
  --broker-url mqtts://broker.emqx.io:8883 \
  --target-host 127.0.0.1 \
  --target-port 22
```

### Laptop offer daemon

```bash
p2p-offer run --config ~/.config/p2ptunnel/config.toml
```

Optional runtime overrides:

```bash
p2p-offer run \
  --config ~/.config/p2ptunnel/config.toml \
  --broker-url mqtts://broker.emqx.io:8883 \
  --listen-port 2222
```

Then point your local client at the offer listener, for example:

```bash
ssh -p 2222 user@127.0.0.1
```

## Operator setup examples

### Always-on home answer daemon

1. Generate an answer identity with `p2pctl keygen answer-office`.
2. Add the offer side's `identity.pub` to `authorized_keys`.
3. Set `node.role = "answer"` and `node.peer_id = "answer-office"`.
4. Set `tunnel.answer.target_host` / `target_port` to the protected service.
5. Start `p2p-answer run` and leave it running.
6. Make sure `~/.config/p2ptunnel/identity` on this machine is the `answer-office` identity, not the offer-side one.

### Laptop offer node

1. Generate an offer identity with `p2pctl keygen offer-home`.
2. Add the answer side's `identity.pub` to `authorized_keys`.
3. Set `node.role = "offer"` and `node.peer_id = "offer-home"`.
4. Point `tunnel.offer.remote_peer_id` at the answer daemon peer ID.
5. Start `p2p-offer run`.
6. Connect your local client to the configured listen port.
7. Make sure `~/.config/p2ptunnel/identity` on this machine is the `offer-home` identity, not the answer-side one.

### Example authorized key exchange

1. On the offer node: `p2pctl keygen offer-home`
2. On the answer node: `p2pctl keygen answer-office`
3. Exchange `identity.pub` files out-of-band.
4. On the offer node: `p2pctl add-authorized-key ./answer.identity.pub`
5. On the answer node: `p2pctl add-authorized-key ./offer.identity.pub`

## Reconnect behavior

The offer side owns recovery.

- detect ICE disconnect/failure
- if the data channel was already open, try same-session ICE restart first
- if ICE fails before the data channel ever opens, skip same-session ICE restart and fall back directly to full renegotiation with a new `session_id`
- if same-session ICE restart fails after transport was already available, fall back to full renegotiation with a new `session_id`
- use exponential backoff with jitter
- do **not** keep the local TCP client open across reconnect in v1

## Security notes

### Why MQTT is untrusted

MQTT can reorder, replay, retain, or expose traffic if treated as trusted. This project prevents that by encrypting and signing every signaling payload and by disabling retained messages for signaling.

### Why both Ed25519 and X25519 are used

- Ed25519 authenticates the sender and protects message integrity.
- X25519 derives shared secrets for end-to-end signaling confidentiality.

Using both keeps authentication and key agreement explicit and separate.

### Why TURN is unsupported in v1

v1 is intentionally conservative:

- STUN-only keeps the network model simpler
- the trust and credential surface is smaller
- the failure model is easier to reason about during early rollout

### Replay protection

Every encrypted signaling message carries a `msg_id`, timestamps are freshness-checked, and replay protection rejects duplicate `(sender_kid, msg_id)` tuples.

## Logging and status

- logs support text or JSON output
- logs can write to stdout, a local file, or both
- secrets are always redacted
- SDP and ICE candidates are redacted by default
- the daemon writes a local `status.json` with peer ID, role, latest-known MQTT transport usability, session ID, and daemon state

The daemon creates `~/.local/state/p2ptunnel/status.json` when status-file writing is enabled, so you do not normally create it by hand. `p2pctl status --config ~/.config/p2ptunnel/config.toml` expects that file to already exist, which usually means you need to start `p2p-offer run` or `p2p-answer run` first and let it write an initial status snapshot.

Read status with:

```bash
p2pctl status --config ~/.config/p2ptunnel/config.toml
```

## Manual validation targets

These are the intended end-to-end checks for operators:

1. localhost broker + two local nodes
2. answer daemon always-on idle wait
3. local SSH-style tunnel through the data channel
4. disconnect and reconnect attempt

## v1 constraints

- CLI only
- no GUI
- no TURN support
- no plaintext signaling
- no unsigned signaling
- one active tunnel session at a time; the offer side immediately closes extra local clients while busy, and the answer side rejects a second allowed peer with encrypted `busy`
- no retained MQTT signaling messages
- no default logging of secrets, SDP, ICE candidates, or decrypted payloads
