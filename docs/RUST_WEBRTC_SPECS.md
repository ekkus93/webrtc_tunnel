# P2P Tunnel over WebRTC + Encrypted MQTT Signaling

## 1. Purpose

This project is a **CLI-only Rust application** that creates a secure TCP tunnel over a WebRTC data channel. It uses MQTT only for signaling and control. MQTT is **not trusted**. All MQTT messages are encrypted end-to-end and signed.

Primary use case:
- `offer` node listens on a local TCP port.
- `answer` node runs as an always-on daemon on a remote machine.
- `offer` and `answer` automatically negotiate a WebRTC connection using encrypted MQTT signaling.
- Tunnel traffic then flows over the WebRTC data channel.

Initial intended tunnel target:
- SSH forwarding (`offer` local port -> `answer` target host/port 22 by default)

## 2. Scope and non-goals

### In scope for v1
- Rust implementation
- Command-line only; no GUI
- Automatic offer/answer signaling over MQTT
- Automatic ICE candidate exchange over MQTT
- All MQTT messages encrypted and signed
- SSH-like key workflow
- STUN support
- No TURN support
- One active tunnel at a time
- Always-on answer daemon waiting for offers
- Clean error reporting over encrypted MQTT when ICE fails
- Reconnect logic

### Out of scope for v1
- GUI
- Browser support
- TURN fallback
- Multiplexed streams over one data channel
- Multiple simultaneous tunnel sessions
- Public unauthenticated discovery
- PEM / PuTTY workflows in the normal operator path

## 3. High-level architecture

Three planes exist:

1. **MQTT signaling plane**
   - Used for encrypted/signed signaling and control only
   - Carries: hello, offer, answer, ICE candidates, ack, ping/pong, close, error, reconnect control
   - All MQTT messages are encrypted end-to-end and signed

2. **WebRTC data plane**
   - Used for actual tunnel bytes
   - One data channel per session in v1

3. **Local TCP plane**
   - Offer side listens locally
   - Answer side connects to configured target host/port

## 4. Security model

### Security invariants
- All MQTT messages are end-to-end encrypted
- All MQTT messages are signed
- All MQTT messages are bound to sender, recipient, and session
- All MQTT messages are replay-protected
- MQTT broker is treated as untrusted transport
- Broker TLS is required
- Unauthorized peers are rejected
- Stale messages are rejected
- Invalid signatures are rejected
- Unknown configuration keys are rejected
- Insecure file permissions cause startup failure

### Algorithms
- Identity/signatures: **Ed25519**
- Signaling key agreement: **X25519**
- Message encryption: **XChaCha20-Poly1305**
- KDF: **HKDF-SHA256**
- KID fingerprint: **SHA-256(public signing key bytes)**

### Key roles
- Ed25519 keys identify peers and sign messages
- X25519 keys derive per-message shared secrets for encrypted signaling
- X25519 is required because Ed25519 alone does not provide encryption or key agreement

## 5. Rust stack

Recommended crates:
- `tokio`
- `webrtc`
- `rumqttc`
- `ed25519-dalek`
- `x25519-dalek`
- `chacha20poly1305`
- `hkdf`
- `sha2`
- `serde`
- `serde_cbor`
- `toml`
- `tracing`
- `secrecy`
- `zeroize`

## 6. Workspace layout

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

### Crate responsibilities

#### `p2p-core`
- IDs and common types
- Config structs
- State machine enums
- Error types
- Constants

#### `p2p-crypto`
- Identity file parsing
- Public key parsing
- Authorized keys parsing
- KID generation
- Ed25519 sign/verify
- X25519 ECDH
- HKDF key derivation
- XChaCha20-Poly1305 encrypt/decrypt
- Secret handling and zeroization

#### `p2p-signaling`
- MQTT transport wrapper
- Topic generation
- Wire format encode/decode
- Replay cache
- Ack logic
- Session-bound message validation

#### `p2p-webrtc`
- RTCPeerConnection lifecycle
- Data channel lifecycle
- SDP handling
- ICE candidate handling
- ICE state translation

#### `p2p-tunnel`
- Tunnel frame encode/decode
- TCP listener (offer side)
- TCP connector (answer side)
- TCP <-> DataChannel bridge
- Close/error propagation

#### `p2p-daemon`
- Node orchestration
- Connection/session state machine
- Reconnect policy
- Health/status reporting

#### `p2p-offer`
- CLI entry point for offer node

#### `p2p-answer`
- CLI entry point for always-on answer node

#### `p2pctl`
- Key generation
- Fingerprints
- Authorized key management
- Config validation
- Status inspection

## 7. Roles

### Offer node
- Usually user-operated client side
- Listens on a local TCP port
- When a local client connects, it starts a WebRTC session to the answer node
- Sends encrypted/signaled offer and ICE candidates over MQTT
- Bridges local TCP <-> WebRTC data channel

### Answer node
- Usually always-on remote daemon
- Stays connected to MQTT continuously
- Subscribes to its own signaling topic
- Waits for valid encrypted offers from authorized peers
- Creates answer and sends encrypted answer/candidates back
- Connects to target host/port and bridges TCP <-> WebRTC data channel
- Returns to idle after tunnel closes

## 8. MQTT topic layout

Each node subscribes to exactly its own signal topic.

```text
{topic_prefix}/v1/nodes/{peer_id}/signal
```

Example:

```text
p2ptunnel/v1/nodes/offer-home/signal
p2ptunnel/v1/nodes/answer-office/signal
```

No plaintext presence topic exists in v1.

## 9. MQTT outer wire format

MQTT publish body is a raw binary blob.

```text
Offset  Size  Field
0       4     magic = "P2TS"
4       1     version = 1
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
- `version`: `1`
- `suite`: `1`

### Suite 1 definition
- KEX: X25519
- KDF: HKDF-SHA256
- AEAD: XChaCha20-Poly1305
- SIG: Ed25519

### Flag bits
- bit 0: `ack_required`
- bit 1: `response`
- all others reserved and must be zero in v1

### Field notes
- `sender_kid`: SHA-256 of sender Ed25519 public key bytes
- `recipient_kid`: SHA-256 of recipient Ed25519 public key bytes
- `msg_id`: 16 random bytes
- `eph_x25519_pub`: fresh per-message sender ephemeral X25519 public key
- `aead_nonce`: 24 random bytes

### Signing rule
Signature covers everything from byte 0 through the end of `ciphertext`.

### AEAD AAD rule
AAD is everything from byte 0 through `ciphertext_len_be` inclusive, excluding the ciphertext and excluding the trailing signature.

### Key derivation rule
```text
shared_secret = X25519(sender_eph_secret, recipient_static_x25519_pub)
aead_key = HKDF-SHA256(
  ikm  = shared_secret,
  salt = sender_kid || recipient_kid,
  info = "p2ts/v1/msg" || msg_id || suite
)[0..32]
```

Recipient derives the same key using its static X25519 secret and the sender ephemeral public key.

## 10. Encrypted inner message format

The inner encrypted plaintext is CBOR.

Schema:

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

### Bodies

#### `hello`
```text
{
  "role": "offer" | "answer",
  "caps": ["trickle-ice", "ice-restart"]
}
```

#### `offer`
```text
{ "sdp": "<full SDP>" }
```

#### `answer`
```text
{ "sdp": "<full SDP>" }
```

#### `ice_candidate`
```text
{
  "candidate": "<candidate string>",
  "sdp_mid": "<string|null>",
  "sdp_mline_index": <u16|null>
}
```

#### `ack`
```text
{ "ack_msg_id": <16-byte msg_id> }
```

#### `ping`
```text
{ "seq": <u64> }
```

#### `pong`
```text
{ "seq": <u64> }
```

#### `close`
```text
{
  "reason_code": "<string>",
  "message": "<optional string>"
}
```

#### `error`
```text
{
  "code": "<string>",
  "message": "<string>",
  "fatal": <bool>
}
```

#### `ice_restart_request`
```text
{}
```

#### `renegotiate_request`
```text
{ "reason": "<string>" }
```

## 11. Failure codes

Recommended v1 error codes:
- `ice_failed`
- `ice_timeout`
- `peer_connection_closed`
- `unauthorized_peer`
- `decrypt_failed`
- `signature_invalid`
- `replay_detected`
- `target_connect_failed`
- `protocol_error`

When STUN/direct ICE fails, the node should send an encrypted `error` message over MQTT and terminate the session cleanly.

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
- for the wrong session

### Freshness rules
Recommended defaults:
- max clock skew: 120 seconds
- max message age: 300 seconds

## 13. Tunnel framing format

Tunnel traffic rides over the WebRTC data channel using a compact custom frame format.

```text
1 byte   version
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

### v1 tunnel constraints
- single stream only
- `stream_id = 1`
- one active tunnel session only
- no multiplexing

## 14. Key file workflow

The operator-facing workflow must feel like SSH, not PEM/PuTTY.

### Files
```text
~/.config/p2ptunnel/identity
~/.config/p2ptunnel/identity.pub
~/.config/p2ptunnel/authorized_keys
```

### `identity`
Private local identity file.
Contains:
- `peer_id`
- Ed25519 private/public key
- X25519 private/public key

Permissions:
- must be `0600` or stricter

Recommended format: TOML.

Example:

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
Single-line shareable public identity.

Example:

```text
p2ptunnel-ed25519 peer_id=offer-home sign_pub=BASE64... kex_pub=BASE64...
```

### `authorized_keys`
One line per trusted peer.

Example:

```text
p2ptunnel-ed25519 peer_id=answer-office sign_pub=BASE64... kex_pub=BASE64... comment="office tunnel target"
p2ptunnel-ed25519 peer_id=offer-home sign_pub=BASE64... kex_pub=BASE64... comment="home laptop"
```

### Trust rules
A signaling message is accepted only if:
- sender KID matches a trusted Ed25519 public key in `authorized_keys`
- signature verifies
- decryption succeeds
- message passes freshness and replay checks
- peer is permitted by role/config policy

## 15. Config file spec

Config lives in:
- user: `~/.config/p2ptunnel/config.toml`
- system: `/etc/p2ptunnel/config.toml`

Precedence:
1. CLI flags
2. environment variables
3. config file

### Example offer config

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
url = "mqtts://mqtt.example.com:8883"
client_id = "offer-home"
topic_prefix = "p2ptunnel"
username = "offer-home"
password_file = "~/.config/p2ptunnel/mqtt_password"
qos = 1
keepalive_secs = 30
clean_session = false
connect_timeout_secs = 10
session_expiry_secs = 86400

[broker.tls]
ca_file = "~/.config/p2ptunnel/ca.crt"
client_cert_file = ""
client_key_file = ""
server_name = "mqtt.example.com"
insecure_skip_verify = false

[webrtc]
stun_urls = ["stun:stun.l.google.com:19302"]
ice_gather_timeout_secs = 15
ice_connection_timeout_secs = 20
enable_trickle_ice = true
enable_ice_restart = true
data_channel_label = "tunnel"
max_message_size = 262144

[tunnel]
stream_id = 1
frame_version = 1
read_chunk_size = 16384
write_buffer_limit = 262144
local_eof_grace_ms = 250
remote_eof_grace_ms = 250

[tunnel.offer]
listen_host = "127.0.0.1"
listen_port = 2222
remote_peer_id = "answer-office"
auto_open = true
max_concurrent_clients = 1
deny_when_busy = true

[tunnel.answer]
target_host = ""
target_port = 0
allow_remote_peers = []

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
log_rotation = "daily"

[health]
heartbeat_interval_secs = 10
ping_timeout_secs = 30
status_socket = ""
write_status_file = true
status_file = "~/.local/state/p2ptunnel/status.json"
```

### Example answer config differences
- `role = "answer"`
- `tunnel.offer` empty/disabled
- `tunnel.answer.target_host = "127.0.0.1"`
- `tunnel.answer.target_port = 22`
- `tunnel.answer.allow_remote_peers = ["offer-home"]`

### Validation rules
Startup must fail if:
- config format is unknown
- peer_id mismatches local identity file
- MQTT TLS is not enabled when required
- encrypted/signed signaling is disabled
- `identity` permissions are too loose
- `authorized_keys` is missing
- role-specific tunnel config is incomplete
- unknown keys appear while strict config is enabled

## 16. Daemon behavior

### Offer daemon lifecycle
1. Start and load config/keys
2. Connect to MQTT broker
3. Listen on local TCP socket
4. On local client connect:
   - create new `session_id`
   - create WebRTC PeerConnection
   - create data channel
   - send encrypted `hello` and `offer`
   - send encrypted trickled ICE candidates
5. Receive encrypted `answer` and remote candidates
6. Wait for data channel open
7. Bridge local TCP <-> data channel
8. On disconnect:
   - attempt ICE restart if configured
   - else renegotiate
   - on failure close session and report error locally

### Answer daemon lifecycle
1. Start and load config/keys
2. Connect to MQTT broker and remain connected
3. Subscribe to own signal topic
4. On valid encrypted offer from authorized peer:
   - create new session
   - create PeerConnection
   - apply remote offer SDP
   - create answer SDP
   - send encrypted answer and candidates
5. On data channel or tunnel open:
   - connect to configured target host/port
   - bridge target TCP <-> data channel
6. On close or failure:
   - send encrypted close/error if appropriate
   - tear down WebRTC session
   - return to idle waiting state

## 17. Reconnect policy

Strategy: `ice_then_renegotiate`

1. Detect disconnect
2. Try ICE restart within a short timeout
3. If ICE restart fails, perform full renegotiation with a new `session_id`
4. Use exponential backoff with jitter
5. Do not mix stale messages from old sessions into new sessions

Recommended v1 behavior:
- do **not** hold local client open across reconnect
- close local TCP cleanly on failure
- keep answer daemon running and ready for next connection

## 18. Logging and observability

### Logging requirements
- structured logs preferred
- redact secrets
- redact SDP and ICE candidates by default
- include `peer_id`, `remote_peer_id`, `session_id`, and event code where available

### Local observability only
Allowed:
- local status JSON file
- local stdout/file logs
- optional Unix domain socket in a later revision

Not allowed in v1:
- plaintext diagnostic/status messages over MQTT

## 19. CLI

### `p2pctl`
Subcommands:
- `keygen --peer-id <peer_id>`
- `fingerprint <identity.pub>`
- `add-authorized-key <identity.pub>`
- `check-config [--config <path>]`
- `status [--config <path>]`

### `p2p-offer`
- `run [--config <path>] [overrides...]`

### `p2p-answer`
- `run [--config <path>] [overrides...]`

## 20. Operator workflow

### Initial setup
1. Generate keys on each side with `p2pctl keygen`
2. Exchange `identity.pub`
3. Add remote `identity.pub` to local `authorized_keys`
4. Configure broker in `config.toml`
5. Configure offer side local port and answer side target host/port
6. Start answer daemon and leave it running
7. Start offer daemon and connect local client to its listening port

### Example
- answer node runs all the time at home
- offer node starts locally on laptop
- user SSHs to `127.0.0.1:2222`
- tunnel auto-negotiates over encrypted MQTT
- SSH traffic flows over WebRTC data channel

## 21. Explicit v1 decisions frozen

- CLI/headless only
- No GUI
- No PEM/PuTTY workflow in normal usage
- SSH-like identity and authorized keys workflow
- All MQTT messages encrypted and signed
- MQTT broker is untrusted
- STUN only
- No TURN in v1
- On ICE failure, send encrypted MQTT error and fail cleanly
- One active tunnel session at a time
- Always-on answer daemon supported and expected

