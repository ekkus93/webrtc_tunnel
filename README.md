# rust_webrtc

[![CI](https://github.com/ekkus93/rust_webrtc/actions/workflows/ci.yml/badge.svg)](https://github.com/ekkus93/rust_webrtc/actions/workflows/ci.yml)

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

This writes:

- `identity` with the private Ed25519 and X25519 keys
- `identity.pub` with the public identity record

The private identity file is written with `0600` permissions.

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

### Important sections

- `[node]`: local `peer_id` and role (`offer` or `answer`)
- `[paths]`: identity, authorized keys, state, and log paths
- `[broker]`: MQTT broker URL, topic prefix, optional credentials, and TLS requirements
- `[webrtc]`: STUN URLs, trickle ICE, and ICE restart
- `[tunnel]`: bridge behavior for the fixed v1 single-stream protocol
- `[tunnel.offer]`: local listen host/port and remote peer
- `[tunnel.answer]`: target host/port and allowed remote peers
- `[reconnect]`: ICE restart, renegotiation, and backoff settings
- `[logging]`: text/json output, stdout/file logging, and redaction flags
- `[health]`: local status file path

### Example broker config

```toml
[broker]
url = "mqtts://broker.example.com:8883"
client_id = "offer-home"
topic_prefix = "p2ptunnel"
username = "offer-home"
password_file = "~/.config/p2ptunnel/broker-password"
qos = 1
keepalive_secs = 30
clean_session = true
connect_timeout_secs = 5
session_expiry_secs = 0

[broker.tls]
ca_file = "~/.config/p2ptunnel/ca.pem"
client_cert_file = "~/.config/p2ptunnel/client.crt"
client_key_file = "~/.config/p2ptunnel/client.key"
insecure_skip_verify = false
```

Set `username = ""` and `password_file = ""` for anonymous or certificate-only broker auth, or keep `username` set and leave `password_file = ""` for username-only auth. In v1, `connect_timeout_secs` must stay `5`, `session_expiry_secs` must stay `0`, TLS server name is derived from the broker URL host, and broker TLS verification cannot be disabled.

The fixed v1 protocol constants for ICE timing behavior, WebRTC message size, tunnel frame version, and single-stream handling live in code and the spec rather than in the public config file.

While an offer-side session is active, additional local TCP clients are accepted and immediately closed with no banner. During an active answer-side session, only a fully allowed peer may receive an encrypted `busy` response; unauthorized or disallowed peers receive no response, and duplicate replays of the same foreign offer are dropped from the active-session dedupe cache before they can trigger repeated `busy` replies or a second full reclassification pass.

### Example offer-side tunnel config

```toml
[tunnel.offer]
listen_host = "127.0.0.1"
listen_port = 2222
remote_peer_id = "answer-office"
```

### Example answer-side tunnel config

```toml
[tunnel.answer]
target_host = "127.0.0.1"
target_port = 22
allow_remote_peers = ["offer-home"]
```

Check the effective configuration with:

```bash
p2pctl check-config --config ~/.config/p2ptunnel/config.toml
```

## Running offer and answer

### Always-on home answer daemon

```bash
p2p-answer run --config ~/.config/p2ptunnel/config.toml
```

Optional runtime overrides:

```bash
p2p-answer run \
  --config ~/.config/p2ptunnel/config.toml \
  --broker-url mqtts://broker.example.com:8883 \
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
  --broker-url mqtts://broker.example.com:8883 \
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
3. Set `node.role = "answer"`.
4. Set `tunnel.answer.target_host` / `target_port` to the protected service.
5. Start `p2p-answer run` and leave it running.

### Laptop offer node

1. Generate an offer identity with `p2pctl keygen offer-home`.
2. Add the answer side's `identity.pub` to `authorized_keys`.
3. Set `node.role = "offer"`.
4. Point `tunnel.offer.remote_peer_id` at the answer daemon peer ID.
5. Start `p2p-offer run`.
6. Connect your local client to the configured listen port.

### Example authorized key exchange

1. On the offer node: `p2pctl keygen offer-home`
2. On the answer node: `p2pctl keygen answer-office`
3. Exchange `identity.pub` files out-of-band.
4. On the offer node: `p2pctl add-authorized-key ./answer.identity.pub`
5. On the answer node: `p2pctl add-authorized-key ./offer.identity.pub`

## Reconnect behavior

The offer side owns recovery.

- detect ICE disconnect/failure
- try ICE restart first
- if ICE restart fails, fall back to full renegotiation with a new `session_id`
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
- one active tunnel session at a time on the answer side
- no retained MQTT signaling messages
- no default logging of secrets, SDP, ICE candidates, or decrypted payloads
