# MULTIPLEXED_FORWARDING_SPEC.md

# Secure Rust WebRTC Tunnel — Multiplexed Forwarding Specification

## 1. Purpose

This document specifies the implemented multiplexed tunnel upgrade for the Rust WebRTC/MQTT port-forwarding application.

The original v1 implementation was effectively a single TCP stream / single configured forward model. That worked reasonably for SSH, but it was too limited for general-purpose forwarding. The v2 design supports:

- multiple configured local forwards,
- one local listener per configured forward on the offer/client side,
- one WebRTC peer connection per authorized peer session,
- one reliable ordered WebRTC data channel,
- many simultaneous logical TCP streams multiplexed over that data channel,
- stream-level open/data/close/error handling,
- answer-side ownership of target host/port mappings,
- per-forward authorization,
- no arbitrary target host/port selection by the remote peer.

This is a protocol/runtime refactor. Do not treat it as a small patch to the old single-stream bridge.

---

## 2. Non-goals

The following are not part of this pass:

1. Do not add a GUI.
2. Do not add TURN support.
3. Do not add app-layer encryption for forwarded TCP payloads.
4. Do not allow the offer/client side to choose arbitrary target host/port values.
5. Do not implement SOCKS, dynamic forwarding, or transparent proxying.
6. Do not support multiple WebRTC data channels for forwarding.
7. Do not preserve live TCP streams across WebRTC session failure.
8. Do not implement cross-peer multi-tenancy beyond the configured authorized peers and per-forward allowlists.
9. Do not add HTTP-specific behavior.
10. Do not add custom compression.

---

## 3. Security model

### 3.1 MQTT signaling remains encrypted and signed

The existing signaling security model remains mandatory:

- all MQTT signaling messages must be end-to-end encrypted,
- all MQTT signaling messages must be signed,
- messages must be bound to sender/recipient/session,
- messages must pass replay checks,
- unauthorized or disallowed peers must not receive useful protocol responses.

This spec changes the data tunnel behavior only. It does not weaken MQTT signaling security.

### 3.2 Forwarded TCP data encryption

Forwarded TCP data will be carried inside the WebRTC data channel.

WebRTC data channels are protected by WebRTC's DTLS/SCTP stack. Therefore, for this pass:

- do not add a second custom AEAD layer inside tunnel `DATA` frames,
- do not invent separate data-session keys,
- do not encrypt each tunnel frame manually,
- rely on WebRTC's data-channel encryption for TCP payload confidentiality and integrity.

Rationale:

- the WebRTC data channel is already encrypted,
- the MQTT signaling path is encrypted and signed,
- the WebRTC negotiation is protected from broker tampering,
- adding custom frame encryption would add nonce/key/fragmentation complexity and create more places to make mistakes.

### 3.3 Answer side owns target mappings

The offer/client side must never send:

- `target_host`
- `target_port`
- arbitrary destination address
- arbitrary destination port

The offer side may only send:

- `forward_id`

The answer/host side maps `forward_id` to a locally configured `target_host:target_port`.

This prevents a compromised or misconfigured client from reaching arbitrary services on the answer-side host or network.

### 3.4 Per-forward authorization

Each forward rule may have its own allowlist.

A peer must satisfy all of the following:

1. Its key must be present in `authorized_keys`.
2. It must be allowed by the answer daemon's general policy.
3. It must be present in the specific forward rule's `allow_remote_peers`, unless that rule explicitly allows all authorized peers.

If any check fails, the answer side must reject the stream without exposing target details.

---

## 4. Product model

The v2 product model is:

```text
one authorized peer session
one WebRTC peer connection
one reliable ordered WebRTC data channel
many configured forwards
many simultaneous logical TCP streams
```

Example:

```text
forward "ssh":
  offer listens on 127.0.0.1:2223
  answer connects to 127.0.0.1:22

forward "web-ui":
  offer listens on 127.0.0.1:8080
  answer connects to 127.0.0.1:8080

forward "postgres":
  offer listens on 127.0.0.1:15432
  answer connects to 127.0.0.1:5432
```

Multiple local TCP clients may be connected at the same time. Each accepted local TCP connection becomes one logical `stream_id` over the existing WebRTC data channel.

---

## 5. Configuration model

### 5.1 Remove old single-forward config

Remove the old single-forward config shape:

```toml
[tunnel.offer]
listen_host = "127.0.0.1"
listen_port = 2223
remote_peer_id = "answer-office"

[tunnel.answer]
target_host = "127.0.0.1"
target_port = 22
allow_remote_peers = ["offer-home"]
```

Replace it with a list of forwarding rules.

### 5.2 New forward rule config

Use this v2 shape:

```toml
[peer]
remote_peer_id = "answer-office"

[[forwards]]
id = "ssh"
listen_host = "127.0.0.1"
listen_port = 2223
target_host = "127.0.0.1"
target_port = 22
allow_remote_peers = ["offer-home"]

[[forwards]]
id = "web-ui"
listen_host = "127.0.0.1"
listen_port = 8080
target_host = "127.0.0.1"
target_port = 8080
allow_remote_peers = ["offer-home"]

[[forwards]]
id = "postgres"
listen_host = "127.0.0.1"
listen_port = 15432
target_host = "127.0.0.1"
target_port = 5432
allow_remote_peers = ["offer-home"]
```

### 5.3 Field definitions

#### `[peer].remote_peer_id`

Required on the offer side.

Defines the answer peer that the offer daemon connects to.

Rules:

- must refer to a peer present in `authorized_keys`,
- must not be empty for role `offer`,
- may be ignored for role `answer`.

#### `[[forwards]].id`

Required.

A stable logical name for the forward.

Rules:

- must be unique,
- must be non-empty,
- allowed characters: ASCII letters, digits, dash, underscore, dot,
- recommended max length: 64 characters,
- must not contain whitespace,
- must not contain `/`, `\`, `:`, or control characters.

Examples:

```text
ssh
web-ui
postgres
grafana
dev-api
```

#### `[[forwards]].listen_host`

Used by the offer side.

The local address to listen on.

Recommended default:

```toml
listen_host = "127.0.0.1"
```

Rules:

- required for role `offer`,
- may be empty or ignored for role `answer`,
- should default to loopback for safety,
- binding to `0.0.0.0` should be allowed only if explicitly configured.

#### `[[forwards]].listen_port`

Used by the offer side.

The local TCP port to listen on.

Rules:

- required for role `offer`,
- must be between 1 and 65535,
- may be ignored for role `answer`,
- all `(listen_host, listen_port)` pairs must be unique.

#### `[[forwards]].target_host`

Used by the answer side.

The host/IP the answer side connects to after receiving `OPEN`.

Rules:

- required for role `answer`,
- may be empty or ignored for role `offer`,
- must never be supplied by the remote peer at runtime.

#### `[[forwards]].target_port`

Used by the answer side.

The TCP port the answer side connects to after receiving `OPEN`.

Rules:

- required for role `answer`,
- must be between 1 and 65535,
- may be ignored for role `offer`,
- must never be supplied by the remote peer at runtime.

#### `[[forwards]].allow_remote_peers`

Used by the answer side.

List of peer IDs allowed to use this forward.

Rules:

- required for role `answer`,
- entries must exist in `authorized_keys`,
- if empty, reject all peers for that forward,
- do not interpret empty list as "allow everyone",
- if a future "all authorized peers" behavior is wanted, use an explicit sentinel such as `["*"]`, but do not implement that sentinel in this pass unless intentionally specified.

For v2, require explicit peer IDs.

### 5.4 Config validation rules

At startup, validate:

1. `forwards` must not be empty.
2. Every forward ID must be unique.
3. Every forward ID must be syntactically valid.
4. For role `offer`:
   - `[peer].remote_peer_id` must be non-empty,
   - `remote_peer_id` must exist in `authorized_keys`,
   - each forward must have valid `listen_host`,
   - each forward must have valid `listen_port`,
   - all listen sockets must be unique.
5. For role `answer`:
   - each forward must have valid `target_host`,
   - each forward must have valid `target_port`,
   - each forward must have non-empty `allow_remote_peers`,
   - every `allow_remote_peers` entry must exist in `authorized_keys`.
6. Old single-forward config fields must be rejected if present.
7. Do not silently ignore unknown forward fields.

### 5.5 Example offer config

```toml
format = "p2ptunnel-config-v2"

[node]
peer_id = "laptop"
role = "offer"

[peer]
remote_peer_id = "home-server"

[broker]
url = "mqtts://mqtt.example.com:8883"
client_id = "laptop"
topic_prefix = "p2ptunnel"
username = "laptop"
password_file = "~/.config/p2ptunnel/mqtt_password"
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

[paths]
identity = "~/.config/p2ptunnel/identity"
authorized_keys = "~/.config/p2ptunnel/authorized_keys"
state_dir = "~/.local/state/p2ptunnel"
log_dir = "~/.local/state/p2ptunnel/log"

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

### 5.6 Example answer config

```toml
format = "p2ptunnel-config-v2"

[node]
peer_id = "home-server"
role = "answer"

[paths]
identity = "~/.config/p2ptunnel/identity"
authorized_keys = "~/.config/p2ptunnel/authorized_keys"
state_dir = "~/.local/state/p2ptunnel"
log_dir = "~/.local/state/p2ptunnel/log"

[broker]
url = "mqtts://mqtt.example.com:8883"
client_id = "home-server"
topic_prefix = "p2ptunnel"
username = "home-server"
password_file = "~/.config/p2ptunnel/mqtt_password"
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

[forwards.answer]
target_host = "127.0.0.1"
target_port = 22
allow_remote_peers = ["laptop"]

[[forwards]]
id = "web-ui"

[forwards.answer]
target_host = "127.0.0.1"
target_port = 8080
allow_remote_peers = ["laptop"]

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

---

## 6. Tunnel protocol v2

### 6.1 Frame header

Keep the existing frame header shape if possible:

```text
1 byte   version
1 byte   frame_type
4 bytes  stream_id
4 bytes  payload_len
N bytes  payload
```

Rules:

- `version = 2` for multiplexed forwarding, or keep the existing version only if the codebase does not have an explicit protocol-version bump mechanism.
- `stream_id = 0` is reserved.
- valid data streams use `stream_id >= 1`.
- `payload_len` must be bounded by a sane maximum.
- invalid frame type rejects/tears down the data channel or session according to existing protocol-error policy.

### 6.2 Stream ID allocation

Offer side allocates stream IDs.

Rules:

- answer side must not create unsolicited stream IDs,
- answer side may only respond on a stream ID previously opened by offer,
- offer side allocates monotonically increasing `u32` stream IDs,
- start at `1`,
- skip `0`,
- if stream ID reaches `u32::MAX`, close the WebRTC session and renegotiate a new one rather than wrapping,
- closed stream IDs must not be reused within the same WebRTC session.

### 6.3 Frame types

Use existing frame types if available:

```text
OPEN
DATA
CLOSE
ERROR
PING
PONG
```

All frame types except future session-level control frames are stream-scoped.

For this pass:

- `OPEN` is stream-scoped,
- `DATA` is stream-scoped,
- `CLOSE` is stream-scoped,
- `ERROR` is stream-scoped,
- `PING`/`PONG` may remain session-level if already implemented that way, but if they carry a stream ID, use `stream_id = 0`.

### 6.4 `OPEN` payload

The offer side sends an `OPEN` frame with payload:

```json
{
  "forward_id": "web-ui"
}
```

CBOR is acceptable if the existing code already uses CBOR for protocol payloads. JSON is acceptable for this small internal payload if simpler.

Rules:

- payload must contain exactly one required logical field: `forward_id`,
- `forward_id` must refer to a locally configured forward on the answer side,
- payload must not contain `target_host`,
- payload must not contain `target_port`,
- unknown fields should be rejected for v2.

### 6.5 `OPEN` acknowledgment

The answer side acknowledges a successful target connection by sending `OPEN` back on the same `stream_id`.

Rules:

- offer sends `OPEN(stream_id, { forward_id })`,
- answer validates and connects to target,
- answer sends exactly `OPEN(stream_id)` with an empty payload,
- after ACK, both sides may exchange `DATA`,
- if target connect fails, answer sends `ERROR(stream_id, ...)`.

The offer side rejects any non-empty `OPEN` ACK payload as `protocol_error`.

### 6.6 `DATA`

`DATA(stream_id, bytes)` carries raw TCP bytes for that logical stream.

Rules:

- if `stream_id` is unknown or already closed, ignore the late frame and keep the session alive,
- if the stream is not open yet, ignore the frame and keep the session alive,
- payload may be empty only if existing framing allows it; otherwise reject empty data frames.

### 6.7 `CLOSE`

`CLOSE(stream_id)` closes one logical stream.

Rules:

- `CLOSE` must not close the WebRTC session,
- `CLOSE` must not close other streams,
- use full-close semantics: when local TCP EOF is observed, send `CLOSE(stream_id)`, remove local stream state, and do not wait for the peer to echo `CLOSE`,
- duplicate `CLOSE` on already-closing/closed stream should be harmless.

### 6.8 `ERROR`

`ERROR(stream_id, payload)` reports a stream-level error.

Payload should be structured:

```json
{
  "code": "unknown_forward",
  "message": "forward_id is not configured"
}
```

Recommended error codes:

```text
unknown_forward
forbidden_forward
target_connect_failed
stream_not_found
stream_already_exists
protocol_error
local_io_error
queue_overflow
```

Rules:

- stream-level errors close only that stream,
- session-level protocol corruption may still close the full data channel/session,
- do not include sensitive local details in error messages.

---

## 7. Runtime architecture

### 7.1 Multiplex runtime owner

The old bridge shape was effectively:

```text
one TcpStream <-> one data channel stream id 1
```

The production runtime is now the daemon-used `run_multiplex_offer` and `run_multiplex_answer` path in `p2p-tunnel`. That path owns stream ID allocation, stream state, writer queue and writer failure handling, per-stream task cancellation, forward lookup, frame dispatch, and session teardown. Do not leave a second production-looking multiplex manager beside that path.

### 7.2 Forward table

Create a normalized `ForwardTable`.

Responsibilities:

- lookup forward by `id`,
- validate local listener config on offer side,
- validate target config on answer side,
- validate per-forward peer authorization on answer side,
- produce bind addresses for offer listeners,
- produce target addresses for answer connections.

### 7.3 Offer side runtime

Offer side responsibilities:

1. Establish MQTT signaling and WebRTC session as before.
2. Create one reliable ordered data channel as before.
3. Start one local TCP listener per configured forward.
4. For each accepted local TCP connection:
   - allocate a new `stream_id`,
   - create stream state,
   - send `OPEN(stream_id, { forward_id })`,
   - wait for stream-level `OPEN` ACK or `ERROR`,
   - if ACK succeeds, bridge local TCP bytes as `DATA(stream_id, bytes)`,
   - if local TCP EOF occurs, send `CLOSE(stream_id)`,
   - if stream-level error occurs, close only that local TCP connection.

### 7.4 Answer side runtime

Answer side responsibilities:

1. Establish MQTT signaling and WebRTC session as before.
2. Wait for `OPEN(stream_id, { forward_id })`.
3. Reject if:
   - stream ID is `0`,
   - stream ID is already active,
   - forward ID is unknown,
   - sender peer is not allowed for that forward,
   - payload is malformed.
4. If accepted:
   - connect to locally configured `target_host:target_port`,
   - send `OPEN(stream_id)` ACK,
   - bridge target TCP bytes as `DATA(stream_id, bytes)`.
5. On target EOF:
   - send `CLOSE(stream_id)`.
6. On target connect failure:
   - send `ERROR(stream_id, target_connect_failed)`.
7. Stream failure must not kill unrelated streams.

### 7.5 Listener lifecycle

Offer side should maintain listeners for all configured forwards while the WebRTC session is active.

Implementation choices:

- listeners may be started after data channel open,
- listeners may stop when the WebRTC session is down,
- or listeners may remain bound and reject/hold connections until session availability.

For this pass, prefer the simpler behavior:

- start forward listeners only after data channel is open,
- stop all forward listeners when the WebRTC session fails/closes,
- after reconnect, restart listeners.

If current architecture already binds one listener before session setup, adapt carefully. Do not accept local TCP clients unless the tunnel manager is ready to assign a stream.

### 7.6 Stream state machine

Each logical stream should have an explicit lifecycle:

```text
Opening
Open
LocalClosing
RemoteClosing
Closed
Failed
```

Required behavior:

- `Opening`: `OPEN` sent/received, waiting for ACK or target connect.
- `Open`: both sides may send/receive `DATA`.
- `LocalClosing`: local TCP side closed; `CLOSE` sent.
- `RemoteClosing`: remote `CLOSE` received; local side draining/closing.
- `Closed`: cleanup complete; remove from stream map.
- `Failed`: error occurred; send/receive `ERROR`, cleanup stream.

### 7.7 Stream-level isolation

A failure in one stream must not close:

- the WebRTC peer connection,
- the data channel,
- other active streams,
- other local listeners.

Examples:

- browser connection to `web-ui` closes: only that stream closes.
- `target_connect_failed` for `postgres`: only that stream fails.
- unknown `forward_id`: only that stream fails.
- one slow stream overflows its buffer: only that stream fails.

The full session should close only when:

- WebRTC peer connection fails,
- data channel closes/fails,
- unrecoverable protocol corruption occurs,
- daemon shutdown occurs,
- reconnect policy decides to tear down the session.

---

## 8. Backpressure and buffering

### 8.1 Bounded queues

Every stream must use bounded buffering.

Do not let one slow stream create unbounded memory growth.

Recommended v2 defaults:

```text
per_stream_queue_messages = 64
max_data_payload_size = 16 KiB or existing read_chunk_size
```

If a per-stream outbound queue fills:

1. close that stream,
2. send `ERROR(stream_id, queue_overflow)` if possible,
3. keep other streams alive.

### 8.2 Data channel send backpressure

If the WebRTC data channel exposes buffered amount / low threshold behavior, use it if convenient. If not, use bounded internal queues and a single writer task to serialize frame sends.

Important rule:

- avoid concurrent unsynchronized writes to the data channel from many stream tasks,
- centralize frame sending through a writer queue if the data channel handle is not guaranteed to be safely usable from many tasks.

### 8.3 Fairness

For v2, simple FIFO across a central outbound queue is acceptable.

Do not implement complex fair scheduling unless needed later.

---

## 9. Session and reconnect behavior

### 9.1 WebRTC session failure

If the WebRTC data channel or peer connection fails:

- fail all active streams,
- close all local TCP sockets,
- close all target TCP sockets,
- stop all forward listeners if they were tied to the active session,
- follow existing reconnect/session recovery behavior.

Do not try to preserve live TCP streams across a WebRTC reconnect in this pass.

### 9.2 Stream failure

Stream failure is isolated.

A single stream failure must not cause renegotiation.

### 9.3 Reconnect after full session failure

After session failure, the existing daemon reconnect behavior applies.

Offer side may establish a fresh WebRTC session. New local TCP clients after reconnection get new stream IDs in the new session.

---

## 10. Logging and status

### 10.1 Log fields

Add stream/forward context to logs.

Recommended fields:

```text
session_id
stream_id
forward_id
local_addr
target_addr
remote_peer_id
state
event
```

Do not log raw forwarded data.

### 10.2 Status file

If status file exists, consider adding optional fields:

```json
{
  "active_streams": 3,
  "configured_forwards": ["ssh", "web-ui"],
  "active_forwards": {
    "ssh": 1,
    "web-ui": 2
  }
}
```

This is optional for the multiplexing pass. Do not block the main implementation on richer status.

---

## 11. Compatibility

This is a protocol-breaking tunnel change if the old implementation only supports `ACTIVE_STREAM_ID = 1`.

For v2:

- do not attempt to interoperate with old single-stream tunnel peers unless explicit negotiation already exists,
- bump config format to `p2ptunnel-config-v2` if the config shape changes incompatibly,
- optionally bump tunnel frame protocol version if currently used,
- update documentation and examples.

The simplest safe approach:

- require both peers to run the new multiplexed version.

---

## 12. Required tests

### 12.1 Config tests

Add tests for:

1. valid multiple forwards,
2. duplicate forward IDs rejected,
3. duplicate listen sockets rejected,
4. invalid forward IDs rejected,
5. offer config requires listen host/port,
6. answer config requires target host/port,
7. answer config requires explicit allowlist,
8. allowlist peer must exist in `authorized_keys`,
9. old single-forward fields rejected if present.

### 12.2 Frame codec tests

Add tests for:

1. `stream_id = 0` rejected for stream frames,
2. multiple nonzero stream IDs accepted,
3. `OPEN` with forward ID encodes/decodes correctly,
4. `DATA` routes by stream ID,
5. `CLOSE` routes by stream ID,
6. `ERROR` routes by stream ID,
7. large payload rejected,
8. malformed `OPEN` payload rejected.

### 12.3 Multiplexed tunnel tests

Add tests for:

1. one forward, one stream,
2. one forward, two simultaneous streams,
3. two forwards, one stream each,
4. unknown forward ID returns stream-level error,
5. unauthorized peer cannot open restricted forward,
6. target connect failure closes only that stream,
7. one stream closing does not close another stream,
8. queue overflow closes only that stream,
9. data from stream A never reaches stream B,
10. data from forward `ssh` never routes to forward `web-ui`.

### 12.4 Daemon/session tests

Add tests for:

1. listeners start for all offer-side forwards after data channel open,
2. listener bind failure fails startup or session according to chosen policy,
3. WebRTC session failure closes all streams,
4. WebRTC session failure closes/stops forward listeners,
5. reconnect starts fresh stream ID allocation,
6. browser-like multiple TCP connections to one forwarded port work concurrently.

---

## 13. Migration notes

Old config:

```toml
[tunnel.offer]
listen_host = "127.0.0.1"
listen_port = 2223
remote_peer_id = "home-server"

[tunnel.answer]
target_host = "127.0.0.1"
target_port = 22
allow_remote_peers = ["laptop"]
```

New config:

```toml
[peer]
remote_peer_id = "home-server"

[[forwards]]
id = "ssh"
listen_host = "127.0.0.1"
listen_port = 2223
target_host = "127.0.0.1"
target_port = 22
allow_remote_peers = ["laptop"]
```

If implementing migration tooling, provide:

```bash
p2pctl migrate-config --from config-v1.toml --to config-v2.toml
```

Migration tooling is optional. Clear documentation is sufficient for this pass.

---

## 14. Implementation guidance

This is a large refactor. Implement in phases:

1. config model and validation,
2. frame protocol update,
3. forward table,
4. stream manager,
5. offer-side local listeners,
6. answer-side target connectors,
7. integration into daemon session loops,
8. tests,
9. documentation cleanup.

Do not attempt to change every layer in one untested patch.

---

## 15. Acceptance criteria

The implementation is complete when:

1. Config supports multiple `[[forwards]]`.
2. Old single-forward config is removed or rejected.
3. Offer side starts a listener for each configured forward.
4. One WebRTC data channel can carry multiple simultaneous TCP streams.
5. Each TCP connection gets a unique nonzero `stream_id`.
6. Answer side maps `forward_id` to local configured target.
7. Offer side never sends target host/port.
8. Per-forward allowlists are enforced.
9. Unknown or forbidden forwards produce stream-level errors.
10. One failed stream does not kill other streams.
11. WebRTC/data-channel failure closes all streams and follows session recovery.
12. Tests cover multiple forwards and simultaneous streams.
13. Forwarded data remains protected by WebRTC DTLS.
14. MQTT signaling remains encrypted and signed.
