# P2P Tunnel over WebRTC + Encrypted MQTT Signaling

## 1. Purpose

This project is a **CLI-only Rust application** that creates a secure TCP tunnel over a WebRTC data channel. MQTT is used only for signaling and control, and the MQTT broker is **not trusted**. All MQTT signaling messages are encrypted end-to-end and signed.

The current v0.3 tunnel supports:

- multiple configured forwards,
- one local listener per configured offer-side forward,
- one always-on `p2p-answer` daemon serving multiple simultaneous authorized `p2p-offer` peers,
- at most one active WebRTC peer session per authenticated `peer_id`,
- one reliable ordered WebRTC data channel named `tunnel` per peer session,
- many simultaneous logical TCP streams multiplexed over that data channel,
- answer-side ownership of target host/port mappings,
- per-forward authorization.

The offer side sends only a `forward_id` for each logical stream. It never chooses an arbitrary answer-side target host or port.

## 2. Scope and non-goals

### In scope for current v0.3

- Rust implementation
- Command-line/headless operation only
- SSH-like key workflow with local identity, public identity, and `authorized_keys`
- Automatic offer/answer signaling over MQTT
- Automatic ICE candidate exchange over MQTT
- End-to-end encrypted and signed MQTT signaling
- Broker TLS
- STUN support
- No TURN support
- Multiple simultaneous authorized offer peer sessions per answer daemon
- At most one active session per authenticated `peer_id`
- Multiple logical TCP streams inside each active peer session
- Always-on answer daemon serving while waiting for offers
- Stream-level target connection and error handling
- Reconnect/recovery driven by the offer side

### Out of scope for current v0.3

- GUI
- Browser support
- TURN fallback
- Multiple unrelated sessions for the same authenticated `peer_id`
- Multiple WebRTC data channels for forwarding
- SOCKS, dynamic forwarding, or transparent proxying
- Arbitrary target selection by the offer side
- Public unauthenticated discovery
- PEM/PuTTY-centric normal operator workflow
- Preserving live TCP streams across WebRTC session failure
- Custom app-layer encryption for forwarded TCP payloads

## 3. High-level architecture

Three planes exist:

1. **MQTT signaling plane**
   - Used for encrypted/signed signaling and control only.
   - Carries: `hello`, `offer`, `answer`, ICE candidates, end-of-candidates, `ack`, `ping`, `pong`, `close`, `error`, and reconnect control.
   - All MQTT publish payloads are binary encrypted/signed envelopes.
   - Signaling publishes are never retained.

2. **WebRTC data plane**
   - Used for forwarded TCP bytes.
   - One reliable ordered data channel per peer session.
   - Data channel label is fixed to `tunnel`.
   - Forwarded TCP data relies on WebRTC DTLS/SCTP encryption; do not add another per-frame AEAD layer.

3. **Local TCP plane**
   - Offer side binds one local TCP listener per configured offer forward.
   - Answer side connects to the target configured for the requested `forward_id`.
   - Each accepted TCP connection becomes one logical stream with a nonzero `stream_id`.

## 4. Security model

### Security invariants

- MQTT broker is treated as untrusted transport.
- Broker TLS is required.
- All MQTT signaling messages are end-to-end encrypted.
- All MQTT signaling messages are signed.
- Messages are bound to sender, recipient, and session.
- Messages are replay-protected.
- Unauthorized peers are rejected.
- Stale, future-skewed, duplicate, undecryptable, unsigned, or invalid-signature messages are rejected.
- Unknown configuration keys are rejected when strict config is enabled.
- Insecure identity file permissions cause startup failure.
- Secrets are redacted in logs.
- SDP and ICE candidates are redacted by default.

### Algorithms

- Identity/signatures: **Ed25519**
- Signaling key agreement: **X25519**
- Message encryption: **XChaCha20-Poly1305**
- KDF: **HKDF-SHA256**
- KID fingerprint: **SHA-256(public signing key bytes)**

### Trust rules

A signaling message is accepted only if:

- outer `sender_kid` maps to a trusted public identity in `authorized_keys`,
- outer `recipient_kid` matches the local identity,
- signature verifies,
- decryption succeeds,
- inner `sp` matches the sender identity from `authorized_keys`,
- inner `rp` matches the local peer ID,
- message passes timestamp and replay checks,
- message session matches the expected active session when applicable,
- the peer is permitted by role/config policy.

Answer-side signaling routing is based on authenticated/decrypted message contents, not broker metadata. If an authenticated `session_id` matches an existing session, the message routes only to that session. If the `session_id` is unknown and the message is an `offer`, the answer daemon evaluates new-session, same-peer replacement, same-peer busy, and capacity policy. If the `session_id` is unknown and the message is not an `offer`, the daemon ignores or rejects it at the routing layer and does not route it by peer fallback.

Any mismatch is a hard protocol error: reject, log locally, and do not process further.

## 5. Rust stack

Core crates used by the implementation include:

- `tokio`
- `webrtc`
- `rumqttc`
- `ed25519-dalek`
- `x25519-dalek`
- `chacha20poly1305`
- `hkdf`
- `sha2`
- `serde`
- `serde_json`
- `serde_cbor`
- `toml`
- `tracing`
- `secrecy`
- `zeroize`

## 6. Workspace layout and crate responsibilities

```text
p2p-tunnel/
  Cargo.toml
  crates/
    p2p-core/
    p2p-crypto/
    p2p-signaling/
    p2p-webrtc/
    p2p-tunnel/
    p2p-daemon/
  bins/
    p2p-offer/
    p2p-answer/
    p2pctl/
```

### `p2p-core`

- IDs and common typed wrappers
- Config structs and validation
- Protocol constants and enums
- Shared error/config types

### `p2p-crypto`

- Identity file parsing
- Public identity parsing
- Authorized keys parsing
- KID generation
- Ed25519 sign/verify
- X25519 ECDH
- HKDF key derivation
- XChaCha20-Poly1305 encrypt/decrypt
- Secret handling and zeroization

### `p2p-signaling`

- MQTT transport wrapper
- Signal topic generation
- Encrypted/signed envelope encode/decode
- Inner message encode/decode
- Replay cache
- ACK/retry tracking
- Session-bound validation

### `p2p-webrtc`

- RTCPeerConnection lifecycle
- Data channel lifecycle
- SDP handling
- ICE candidate handling
- ICE state translation

### `p2p-tunnel`

- Tunnel frame encode/decode
- Offer-side TCP listeners
- Multiplexed stream state and stream ID allocation
- TCP/data-channel bridge tasks
- Stream-level close/error propagation

### `p2p-daemon`

- Offer/answer orchestration
- Session state machine
- Reconnect/recovery policy
- Busy-session policy
- Local status output
- Logging setup/redaction

### `p2p-offer`, `p2p-answer`, `p2pctl`

- `p2p-offer`: offer daemon entry point
- `p2p-answer`: always-on answer daemon entry point
- `p2pctl`: key generation, fingerprints, authorized key management, config validation, status inspection

## 7. Roles

### Offer node

- Usually the user-operated client side.
- Binds one local TCP listener per configured forward at startup.
- The first accepted local TCP client triggers WebRTC negotiation.
- Additional local clients during negotiation go into a bounded pending queue; overflow closes the new client immediately with no plaintext banner.
- After the data channel opens, each accepted local TCP client opens a new logical stream over the active data channel.
- Sends encrypted/signaled offer and ICE candidates over MQTT.
- Sends only `forward_id` in tunnel `OPEN` frames.
- Owns reconnect and renegotiation in v0.2.

### Answer node

- Usually an always-on remote daemon.
- Stays connected to MQTT continuously.
- Subscribes to its own signaling topic.
- Waits for valid encrypted offers from authorized peers.
- Creates answer and sends encrypted answer/candidates back.
- On `OPEN(stream_id, { forward_id })`, checks per-forward authorization and connects to the configured target host/port.
- May serve multiple simultaneous authorized offer peers, with one active WebRTC session per remote `peer_id`.
- Returns to service after a session close/failure without tearing down unrelated sessions.
- Does not initiate a fresh session on its own.

## 8. MQTT topic layout

Each node subscribes to exactly its own signal topic:

```text
{topic_prefix}/v1/nodes/{peer_id}/signal
```

Examples:

```text
p2ptunnel/v1/nodes/offer-home/signal
p2ptunnel/v1/nodes/answer-office/signal
```

The topic namespace remains `v1` for compatibility with the current MQTT routing scheme. It does not imply the config or tunnel frame version. No plaintext presence topic exists.

## 9. MQTT outer wire format

MQTT publish body is a raw binary blob:

```text
Offset  Size  Field
0       4     magic = "P2TS"
4       1     version = 2
5       1     suite = 1
6       1     flags
7       32    sender_kid
39      32    recipient_kid
71      16    msg_id
87      32    eph_x25519_pub
119     24    aead_nonce
143     4     ciphertext_len_be
147     N     ciphertext
147+N   64    signature_ed25519
```

### Wire format constants

- `magic`: ASCII `P2TS`
- `version`: `2`
- `suite`: `1`

### Suite 1 definition

- KEX: X25519
- KDF: HKDF-SHA256
- AEAD: XChaCha20-Poly1305
- SIG: Ed25519

### Flag bits

- bit 0: `ack_required`
- bit 1: `response`
- all others reserved and must be zero

### Field notes

- `sender_kid`: SHA-256 of sender Ed25519 public key bytes
- `recipient_kid`: SHA-256 of recipient Ed25519 public key bytes
- `msg_id`: 16 random bytes
- `eph_x25519_pub`: fresh per-message sender ephemeral X25519 public key
- `aead_nonce`: 24 random bytes

### Signing rule

Signature covers everything from byte 0 through the end of `ciphertext`.

### AEAD AAD rule

AAD is everything from byte 0 through `ciphertext_len_be` inclusive, excluding ciphertext and excluding the trailing signature.

### Key derivation rule

```text
shared_secret = X25519(sender_eph_secret, recipient_static_x25519_pub)
aead_key = HKDF-SHA256(
  ikm  = shared_secret,
  salt = sender_kid || recipient_kid,
  info = "p2ts/v1/msg" || msg_id || suite
)[0..32]
```

The HKDF info string is intentionally unchanged for compatibility with the current signaling crypto suite.

## 10. Encrypted inner message format

The encrypted plaintext is CBOR. The current inner message schema version remains `1`:

```text
{
  "v": 1,
  "t": <u8 message type>,
  "sid": <16-byte session_id>,
  "sp": <sender_peer_id string>,
  "rp": <recipient_peer_id string>,
  "ts": <unix_time_ms u64>,
  "body": <type-specific object>
}
```

### Message types

- `1`: `hello`
- `2`: `offer`
- `3`: `answer`
- `4`: `ice_candidate`
- `5`: `ack`
- `6`: `ping`
- `7`: `pong`
- `8`: `close`
- `9`: `error`
- `10`: `ice_restart_request`
- `11`: `renegotiate_request`
- `12`: `end_of_candidates`

### ACK behavior

These message types require ACK:

- `offer`
- `answer`
- `ice_candidate`
- `error`
- `close`
- `ice_restart_request`
- `renegotiate_request`

These do not require ACK:

- `ack`
- `ping`
- `pong`
- `hello`
- `end_of_candidates`

Retry timeout is 2 seconds. Max retries is 3. Retransmits must be byte-identical except for MQTT delivery metadata outside the encrypted payload. Duplicate retransmits are matched by `(sender_kid, msg_id)`.

### `hello`

`hello` is optional. It is a capability hint only and is not required before `offer`; a peer may send `offer` as the first message of a new session.

### `offer` and `answer`

```text
{ "sdp": "<full SDP>" }
```

### `ice_candidate`

```text
{
  "candidate": "<candidate string>",
  "sdp_mid": "<string|null>",
  "sdp_mline_index": <u16|null>
}
```

### `ack`

```text
{ "ack_msg_id": <16-byte msg_id> }
```

### `ping` / `pong`

```text
{ "seq": <u64> }
```

### `close`

```text
{
  "reason_code": "<string>",
  "message": "<optional string>"
}
```

### `error`

```text
{
  "code": "<string>",
  "message": "<string>",
  "fatal": <bool>
}
```

### `end_of_candidates`

```text
{}
```

## 11. Failure and error codes

Signaling/session error codes include:

- `ice_failed`
- `ice_timeout`
- `peer_connection_closed`
- `unauthorized_peer`
- `decrypt_failed`
- `signature_invalid`
- `replay_detected`
- `target_connect_failed`
- `protocol_error`
- `busy`

Tunnel stream-level error codes include:

- `unknown_forward`
- `forbidden_forward`
- `target_connect_failed`
- `stream_not_found`
- `stream_already_exists`
- `protocol_error`
- `local_io_error`
- `queue_overflow`

Authorized session-level peers may receive stream-level `unknown_forward`, `forbidden_forward`, and `target_connect_failed` errors. Unauthorized or disallowed peers receive no useful protocol response.

## 12. Session IDs and replay protection

### Session IDs

- 16 random bytes
- New session ID for each fresh negotiation
- Same session ID across candidate exchange within one negotiation
- New session ID on full renegotiation

### Replay cache

Replay cache key:

```text
(sender_kid, msg_id)
```

Reject messages that are:

- duplicate
- too old
- too far in the future
- for the wrong active session

Recommended defaults:

- max clock skew: 120 seconds
- max message age: 300 seconds

## 13. Tunnel framing format

Tunnel traffic rides over the WebRTC data channel using a compact frame format:

```text
1 byte   version = 2
1 byte   type
4 bytes  stream_id
4 bytes  payload_len
N bytes  payload
```

### Frame types

- `0`: `OPEN`
- `1`: `DATA`
- `2`: `CLOSE`
- `3`: `ERROR`
- `4`: `PING`
- `5`: `PONG`

### Stream ID rules

- `stream_id = 0` is reserved for session-level `PING` and `PONG`.
- `OPEN`, `DATA`, `CLOSE`, and `ERROR` require `stream_id >= 1`.
- The offer side allocates stream IDs.
- Stream IDs start at `1`, increment monotonically, and are not reused within a WebRTC session.
- Duplicate `CLOSE` or stream-level `ERROR` for an already closed stream is harmless.

### `OPEN`

Offer-side stream open:

```json
{ "forward_id": "ssh" }
```

Rules:

- `forward_id` is required and non-empty.
- No target host, target port, or arbitrary destination address is present.
- Unknown JSON fields are rejected.
- The answer side maps `forward_id` to its local config.

Successful answer-side stream ACK:

```text
OPEN(stream_id) with empty payload
```

### `DATA`

`DATA(stream_id, bytes)` carries raw TCP bytes for the logical stream. Payload data is protected by WebRTC DTLS/SCTP; do not add custom per-frame encryption.

### `CLOSE`

`CLOSE(stream_id)` closes only that logical stream.

### `ERROR`

`ERROR(stream_id, payload)` uses JSON:

```json
{ "code": "target_connect_failed", "message": "target connect failed" }
```

Unknown fields are rejected. Error handling is stream-local unless the data channel/session itself fails.

## 14. Key file workflow

The operator-facing workflow must feel like SSH, not PEM/PuTTY.

### Files

```text
~/.config/p2ptunnel/identity
~/.config/p2ptunnel/identity.pub
~/.config/p2ptunnel/authorized_keys
```

### `identity`

Private local identity file. It contains:

- `peer_id`
- Ed25519 private/public key
- X25519 private/public key

Permissions must be `0600` or stricter.

Current format:

```toml
format = "p2ptunnel-identity-v1"
peer_id = "offer-home"

[sign]
alg = "ed25519"
private = "BASE64_32_BYTE_SECRET"
public  = "BASE64_32_BYTE_PUBLIC"

[kex]
alg = "x25519"
private = "BASE64_32_BYTE_SECRET"
public  = "BASE64_32_BYTE_PUBLIC"
```

### `identity.pub`

Single-line shareable public identity:

```text
p2ptunnel-ed25519 peer_id=offer-home sign_pub=BASE64... kex_pub=BASE64...
```

### `authorized_keys`

One trusted peer identity per line:

```text
p2ptunnel-ed25519 peer_id=answer-office sign_pub=BASE64... kex_pub=BASE64... comment="office answer"
p2ptunnel-ed25519 peer_id=offer-home sign_pub=BASE64... kex_pub=BASE64... comment="home laptop"
```

## 15. Config file spec

Config lives in:

- user: `~/.config/p2ptunnel/config.toml`
- system: `/etc/p2ptunnel/config.toml`

Precedence:

1. CLI flags
2. environment variables
3. config file

The config format is:

```toml
format = "p2ptunnel-config-v3"
```

### Example offer config

```toml
format = "p2ptunnel-config-v3"

[node]
peer_id = "offer-home"
role = "offer"

[peer]
remote_peer_id = "answer-office"

[paths]
identity = "~/.config/p2ptunnel/identity"
authorized_keys = "~/.config/p2ptunnel/authorized_keys"
state_dir = "~/.local/state/p2ptunnel"
log_dir = "~/.local/state/p2ptunnel/log"

[broker]
url = "mqtts://mqtt.example.com:8883"
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
ca_file = "~/.config/p2ptunnel/ca.crt"
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
id = "ssh"

[forwards.offer]
listen_host = "127.0.0.1"
listen_port = 2223

[[forwards]]
id = "web-ui"

[forwards.offer]
listen_host = "127.0.0.1"
listen_port = 8080

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

### Example answer config differences

```toml
format = "p2ptunnel-config-v3"

[node]
peer_id = "answer-office"
role = "answer"

[[forwards]]
id = "ssh"

[forwards.answer]
target_host = "127.0.0.1"
target_port = 22
allow_remote_peers = ["offer-home"]

[[forwards]]
id = "web-ui"

[forwards.answer]
target_host = "127.0.0.1"
target_port = 8080
allow_remote_peers = ["offer-home"]
```

### Config rules

- `format` must be `p2ptunnel-config-v3`.
- `[peer].remote_peer_id` is required for role `offer` and must exist in `authorized_keys`.
- `[[forwards]].id` is required, unique, non-empty, max 64 characters, and limited to ASCII letters, digits, dash, underscore, and dot.
- Forward IDs must not contain whitespace, `/`, `\`, `:`, or control characters.
- Offer role requires `[forwards.offer].listen_host` and nonzero `listen_port`.
- Offer role rejects duplicate `(listen_host, listen_port)` pairs.
- Answer role requires `[forwards.answer].target_host`, nonzero `target_port`, and non-empty `allow_remote_peers`.
- `allow_remote_peers` must contain explicit peer IDs only; wildcard/sentinel values are rejected.
- Each peer in `allow_remote_peers` must exist in `authorized_keys`.
- Old `[tunnel.offer]` and `[tunnel.answer]` single-forward config is rejected by strict parsing.
- `broker.username` / `broker.password_file` support anonymous or certificate-only (`""` / `""`), username-only (`"user"` / `""`), or username plus password file.
- TLS server-name behavior is derived from the broker URL host; there is no separate public `server_name` override.
- `broker.connect_timeout_secs` must stay `5`.
- `broker.session_expiry_secs` must stay `0`.
- `reconnect.hold_local_client_during_reconnect` must stay `false`.
- `reconnect.local_client_hold_secs` must stay `0`.
- `logging.log_rotation` must remain `"none"`.
- `health.status_socket` must remain empty.

## 16. Daemon behavior

### Offer daemon lifecycle

1. Load config/keys and validate role/config/authorized peers.
2. Connect to MQTT broker and subscribe to own signal topic.
3. Bind all configured offer forward listeners at startup.
4. Wait for a local TCP client.
5. On first local client:
   - create new `session_id`,
   - create WebRTC PeerConnection,
   - create data channel `tunnel`,
   - optionally send encrypted `hello`,
   - send encrypted `offer`,
   - send encrypted trickled ICE candidates and end-of-candidates signal.
6. Receive encrypted `answer` and remote candidates.
7. Wait for data channel open.
8. Send `OPEN(stream_id, { forward_id })` for each accepted local TCP client.
9. On stream ACK, bridge that TCP client over the logical stream.
10. When all streams close, keep the session and data channel available for future local clients from that peer until explicit close or failure.

### Answer daemon lifecycle

1. Load config/keys and validate role/config/authorized peers.
2. Connect to MQTT broker and subscribe to own signal topic.
3. Remain connected and serving while waiting for valid offers.
4. On valid encrypted offer from an authorized and allowed peer:
   - create new session,
   - create PeerConnection,
   - apply remote offer SDP,
   - create answer SDP,
   - send encrypted answer and candidates.
5. On incoming data channel, run the multiplexed answer dispatcher.
6. On `OPEN`, enforce `forward_id` and peer authorization.
7. Connect to the configured target for that forward.
8. Send empty `OPEN` ACK on success, or stream-level `ERROR` on failure.
9. On one session's close or failure, tear down that session only and continue serving other or future peer sessions.

### Busy/session policy

- One active peer tunnel session per remote `peer_id`; multiple authorized offer peers may be served concurrently.
- Multiple TCP streams are allowed within each active session.
- During offer negotiation, extra local clients enter a bounded pending queue; overflow closes the new client with no banner.
- During a pending answer-side session that has not opened the tunnel yet, a replacement offer from the same authorized peer may replace the pending session.
- During an active answer session, a second offer from a fully allowed peer is rejected with encrypted `error` code `busy`.
- Same-peer replacement is scoped to that peer and must not disturb unrelated active peers.
- Unauthorized or disallowed active-answer peers receive no response.
- Busy-offer dedupe is per active answer session and keyed by at least `(sender_kid, msg_id)`.

## 17. Reconnect policy

Strategy: `ice_then_renegotiate`.

- The offer side owns recovery.
- The answer side never initiates a fresh session on its own.
- If a tunnel is already active, ICE failure ends the current session and daemon recovery returns to the steady state.
- While negotiation is still pending for a local client, the offer side may try same-session ICE restart only when the data channel is already open.
- If same-session ICE restart is unavailable or fails during negotiation, the offer side falls back to renegotiation with a new offer.
- During pending answer-side negotiation, a replacement offer from the same authorized peer may replace the pending session instead of being treated as a busy conflict.
- Exponential backoff with jitter is used.
- Stale messages from old sessions must not mutate a newer session.
- Live TCP streams are not preserved across WebRTC session failure.

## 18. Logging and observability

### Logging requirements

- Structured logs are preferred.
- Redact secrets.
- Redact SDP and ICE candidates by default.
- Include `peer_id`, `remote_peer_id`, `session_id`, `stream_id`, `forward_id`, and event code where available.
- Do not log raw TCP payload data.

### Local status

The daemon writes local status JSON when enabled. Current fields include:

- `peer_id`
- `role`
- `mqtt_connected`
- `active_session_id`
- `current_state`
- `active_session_count`
- `session_capacity`
- `sessions`
- `configured_forwards`

Each entry in `sessions` includes the session ID, remote peer ID, session state, data-channel-open flag, and honestly named configured forward IDs. The answer daemon may report multiple concurrent sessions, one per active authorized offer peer. For a healthy answer daemon, daemon-level `current_state` reports `serving` with zero or more active sessions; per-session states carry the individual lifecycle detail.

`mqtt_connected` is a best-effort latest-known signaling transport usability flag. Recoverable poll/publish failures should flip it to `false` before retry/backoff; later successful transport activity should flip it back to `true`.

Plaintext diagnostic/status messages over MQTT are not allowed.

## 19. CLI

### `p2pctl`

Subcommands:

- `keygen <peer_id>`
- `fingerprint <identity.pub>`
- `add-authorized-key <identity.pub>`
- `check-config [--config <path>]`
- `status [--config <path>]`

### `p2p-offer`

```text
p2p-offer run [--config <path>] [--broker-url <url>]
```

Offer listen ports are configured per forward in `[[forwards]]`; v0.2 does not accept first-forward-only listen override flags.

### `p2p-answer`

```text
p2p-answer run [--config <path>] [--broker-url <url>]
```

Answer targets are configured per forward in `[[forwards]]`; v0.2 does not accept first-forward-only target override flags.

## 20. Operator workflow

### Initial setup

1. Generate keys on each side with `p2pctl keygen`.
2. Exchange `identity.pub`.
3. Add remote `identity.pub` to local `authorized_keys`.
4. Configure broker in `config.toml`.
5. Configure `[peer].remote_peer_id` on the offer side.
6. Configure matching `[[forwards]]` IDs on both sides.
7. Start the answer daemon and leave it running.
8. Start the offer daemon and connect local clients to configured listen ports.

### Example

- answer node runs all the time at home,
- offer node starts locally on laptop,
- user SSHs to `127.0.0.1:2223`,
- browser opens `127.0.0.1:8080`,
- both forwards share one WebRTC data channel as separate logical streams,
- tunnel auto-negotiates over encrypted MQTT,
- forwarded TCP data flows over WebRTC DTLS/SCTP.

## 21. Core decisions frozen for the current CLI product

- CLI/headless only.
- No GUI.
- No PEM/PuTTY workflow in normal usage.
- SSH-like identity and authorized keys workflow.
- MQTT signaling only; no plaintext signaling.
- All MQTT signaling messages encrypted and signed.
- MQTT broker is untrusted.
- Broker TLS is required.
- STUN only.
- No TURN support.
- One active peer tunnel session per authenticated `peer_id`.
- Multiple authorized offer peers may be served concurrently by one answer daemon.
- Multiple logical streams may run within each active peer session.
- One reliable ordered WebRTC data channel labeled `tunnel` per peer session.
- Offer side sends only `forward_id`; answer side owns target mapping.
- `allow_remote_peers` uses explicit peer IDs only.
- Stream errors are isolated whenever possible.
- No custom app-layer encryption for forwarded TCP data.
- Always-on answer daemon supported and expected.
