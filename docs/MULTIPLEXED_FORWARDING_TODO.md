# MULTIPLEXED_FORWARDING_TODO.md

# Secure Rust WebRTC Tunnel — Multiplexed Forwarding TODO

## Goal

Refactor the existing single-stream/single-forward tunnel into a multiplexed forwarding system:

```text
one WebRTC peer connection
one reliable ordered data channel
many configured forwards
many simultaneous logical TCP streams
```

This TODO is intended for GitHub Copilot or another coding agent. Follow it in order. Do not skip validation and tests.

---

## Task 0 — Read the spec and current code

**Status:** Complete.

### 0.1 Read the multiplexing spec

**Status:** Complete.

Read:

- `docs/MULTIPLEXED_FORWARDING_SPEC.md`

Understand these non-negotiable rules:

- one WebRTC data channel,
- many logical streams using `stream_id`,
- `stream_id = 0` reserved,
- offer side allocates stream IDs,
- answer side owns target host/port mappings,
- offer side sends only `forward_id`,
- stream failures are isolated,
- forwarded data uses WebRTC DTLS; do not add custom frame encryption,
- MQTT signaling remains encrypted and signed.

### 0.2 Locate current single-stream assumptions

**Status:** Complete.

Search for and document all occurrences of:

- `ACTIVE_STREAM_ID`
- hardcoded stream ID `1`
- single `listen_port`
- single `target_port`
- `TunnelBridge`
- `run_offer`
- `run_answer`
- `TunnelFrame::open`
- `TunnelFrame::data`
- `TunnelFrame::close`
- any code that rejects stream IDs other than `1`

Do not begin refactoring until the single-stream assumptions are identified.

---

## Task 1 — Update config model to support `[[forwards]]`

**Status:** Complete for the first passing implementation phase; more docs/sample coverage remains in Task 11/13.

### 1.1 Add `ForwardRule`

**Status:** Complete.

Add a core config type similar to:

```rust
pub struct ForwardRule {
    pub id: String,
    pub listen_host: Option<String>,
    pub listen_port: Option<u16>,
    pub target_host: Option<String>,
    pub target_port: Option<u16>,
    pub allow_remote_peers: Vec<String>,
}
```

Use the existing config style and serde conventions in the repository.

Exact representation may differ, but the model must support all required fields.

### 1.2 Add `[peer].remote_peer_id`

**Status:** Complete.

Add config support for:

```toml
[peer]
remote_peer_id = "answer-office"
```

Rules:

- required for role `offer`,
- must exist in `authorized_keys`,
- ignored or optional for role `answer`.

### 1.3 Remove or reject old single-forward config

**Status:** Complete.

Remove or reject:

```toml
[tunnel.offer]
listen_host = ...
listen_port = ...
remote_peer_id = ...

[tunnel.answer]
target_host = ...
target_port = ...
allow_remote_peers = ...
```

If backwards compatibility is too disruptive, produce a clear validation error explaining the new `[[forwards]]` format.

Do not silently accept both old and new models.

### 1.4 Validate forward IDs

**Status:** Complete.

Implement validation rules:

- non-empty,
- unique,
- max length around 64 chars,
- allowed characters: ASCII letters, digits, dash, underscore, dot,
- reject whitespace,
- reject `/`, `\`, `:`, and control characters.

### 1.5 Validate offer-side forward fields

**Status:** Complete.

For role `offer`, each forward must have:

- valid `listen_host`,
- valid `listen_port`.

Also validate:

- no duplicate `(listen_host, listen_port)` pairs,
- `remote_peer_id` exists in `authorized_keys`.

### 1.6 Validate answer-side forward fields

**Status:** Complete.

For role `answer`, each forward must have:

- valid `target_host`,
- valid `target_port`,
- non-empty `allow_remote_peers`.

Also validate:

- every peer in `allow_remote_peers` exists in `authorized_keys`.

### 1.7 Add config tests

**Status:** Complete.

Add tests for:

- valid offer config with two forwards,
- valid answer config with two forwards,
- duplicate forward IDs rejected,
- duplicate listen sockets rejected,
- invalid forward IDs rejected,
- missing offer listen port rejected,
- missing answer target port rejected,
- empty answer allowlist rejected,
- allowlist peer not in `authorized_keys` rejected,
- old single-forward config rejected.

---

## Task 2 — Create a `ForwardTable`

**Status:** Complete for core lookup behavior.

### 2.1 Add normalized forward lookup type

**Status:** Complete.

Create a type such as:

```rust
pub struct ForwardTable {
    by_id: HashMap<String, ForwardRule>,
}
```

Responsibilities:

- lookup by `forward_id`,
- return offer listener bind addresses,
- return answer target addresses,
- check per-forward peer authorization.

### 2.2 Implement offer-side helpers

**Status:** Complete.

Add helpers:

```rust
fn offer_listeners(&self) -> Vec<OfferForwardBind>
```

Each bind should include:

- `forward_id`,
- `listen_host`,
- `listen_port`.

### 2.3 Implement answer-side helpers

**Status:** Complete.

Add helpers:

```rust
fn target_for(&self, forward_id: &str, remote_peer_id: &str) -> Result<TargetAddr, ForwardError>
```

Rules:

- unknown `forward_id` => `unknown_forward`,
- peer not allowed => `forbidden_forward`,
- valid mapping => target host/port.

### 2.4 Add tests

**Status:** Complete for current lookup behavior.

Add tests for:

- lookup valid forward,
- unknown forward,
- forbidden peer,
- allowed peer,
- two forwards with different target ports.

---

## Task 3 — Update tunnel frame model for real stream IDs

**Status:** Complete.

### 3.1 Remove `ACTIVE_STREAM_ID = 1` behavior

**Status:** Complete.

Do not reject all stream IDs except `1`.

Rules:

- `stream_id = 0` reserved,
- `stream_id >= 1` valid for stream-scoped frames.

### 3.2 Update constructors

**Status:** Complete.

Change constructors to accept stream IDs:

```rust
TunnelFrame::open(stream_id, payload)
TunnelFrame::data(stream_id, bytes)
TunnelFrame::close(stream_id)
TunnelFrame::error(stream_id, ...)
```

Do not hardcode `stream_id = 1`.

### 3.3 Add `OpenPayload`

**Status:** Complete.

Add a structured open payload:

```rust
pub struct OpenPayload {
    pub forward_id: String,
}
```

Use CBOR or JSON consistently with existing code style.

Rules:

- `forward_id` required,
- no target host,
- no target port,
- reject unknown fields if the serializer/parser supports it.

### 3.4 Add `ErrorPayload`

**Status:** Complete.

Add or update structured error payload:

```rust
pub struct ErrorPayload {
    pub code: String,
    pub message: String,
}
```

Recommended codes:

- `unknown_forward`
- `forbidden_forward`
- `target_connect_failed`
- `stream_not_found`
- `stream_already_exists`
- `protocol_error`
- `local_io_error`
- `remote_io_error`
- `queue_overflow`

### 3.5 Frame codec validation

**Status:** Complete.

Implement validation:

- reject `stream_id = 0` for stream-scoped `OPEN`, `DATA`, `CLOSE`, `ERROR`,
- enforce payload size limits,
- reject malformed `OPEN` payload,
- reject malformed `ERROR` payload.

### 3.6 Add frame tests

**Status:** Complete for implemented codec behavior; final runtime/protocol coverage continues in Task 13.

Add tests for:

- encode/decode `OPEN` for stream 1 and stream 2,
- encode/decode `DATA` for different stream IDs,
- stream ID 0 rejected for stream frames,
- malformed `OPEN` rejected,
- unknown forward ID is not checked by codec, only by forward table,
- large payload rejected.

---

## Task 4 — Design stream state and stream manager

**Status:** Complete for the first mux runtime implementation.

### 4.1 Add stream state enum

**Status:** Complete.

Add:

```rust
enum StreamLifecycle {
    Opening,
    Open,
    LocalClosing,
    RemoteClosing,
    Closed,
    Failed,
}
```

Names may differ, but states must be explicit.

### 4.2 Add stream state object

**Status:** Complete.

Create a per-stream state object containing:

- `stream_id`,
- `forward_id`,
- lifecycle state,
- local TCP half or target TCP half,
- outbound queue sender/receiver,
- timestamps if useful,
- peer/local metadata for logs.

### 4.3 Add multiplexed tunnel manager

**Status:** Complete for the current runtime shape.

Replace or wrap `TunnelBridge` with something like:

```rust
pub struct MultiplexedTunnel {
    streams: HashMap<StreamId, StreamState>,
    forward_table: ForwardTable,
    outbound_tx: mpsc::Sender<TunnelFrame>,
}
```

Responsibilities:

- allocate stream IDs on offer side,
- register streams,
- route frames by stream ID,
- remove closed streams,
- close all streams on session failure,
- isolate stream errors.

### 4.4 Implement stream ID allocator

**Status:** Complete.

Offer side allocator:

- starts at `1`,
- increments monotonically,
- skips `0`,
- never reuses stream IDs in the same WebRTC session,
- on overflow, fail the session or require renegotiation.

### 4.5 Add stream manager tests

**Status:** Complete.

Add tests for:

- allocate stream IDs 1, 2, 3,
- no reuse after close,
- stream 0 never allocated,
- duplicate stream registration rejected,
- unknown stream lookup returns error,
- closing one stream does not remove another.

---

## Task 5 — Implement central data-channel writer path

**Status:** Complete.

### 5.1 Avoid unsynchronized concurrent sends

**Status:** Complete.

If current data-channel send API is not clearly safe for concurrent multi-task calls, add a central writer task.

Use a bounded channel:

```rust
mpsc::channel<TunnelFrame>(N)
```

All stream tasks send frames to the writer queue.

### 5.2 Writer task

**Status:** Complete.

The writer task:

- receives `TunnelFrame`,
- encodes it,
- sends over the WebRTC data channel,
- handles send errors by notifying the tunnel manager/session.

### 5.3 Backpressure

**Status:** Complete.

Use bounded queues.

Rules:

- if a stream's queue fills, fail that stream with `queue_overflow`,
- if the central writer queue fails, fail the session,
- do not allow unbounded memory growth.

### 5.4 Tests

**Status:** Complete.

Add tests for:

- queue overflow fails only one stream,
- writer sends frames in FIFO order,
- writer failure causes session-level failure.

Use mockable/local abstractions if needed. Do not require real WebRTC for pure unit tests.

---

## Task 6 — Offer side: multiple local listeners

**Status:** Complete.

### 6.1 Start one listener per forward

**Status:** Complete; listeners bind at offer daemon startup per `docs/replies6.md`.

After the WebRTC data channel is open and the multiplexed tunnel manager is ready, start a local TCP listener for every configured forward.

Each listener needs to know:

- `forward_id`,
- bind host,
- bind port,
- sender/channel into tunnel manager.

### 6.2 Accept loop per forward

**Status:** Complete for initial mux flow.

Each listener accept loop:

1. accepts local TCP client,
2. requests a new stream from the tunnel manager,
3. sends `OPEN(stream_id, { forward_id })`,
4. waits for stream-level `OPEN` ACK or `ERROR`,
5. if ACK succeeds, starts local TCP bridge for that stream,
6. if error, closes the local TCP client.

### 6.3 Local TCP read loop

**Status:** Complete for initial mux flow.

For each local TCP stream:

- read chunks using existing `read_chunk_size`,
- send `DATA(stream_id, bytes)`,
- on EOF, send `CLOSE(stream_id)`,
- on read error, send `ERROR(stream_id, local_io_error)` if appropriate.

### 6.4 Local TCP write path

**Status:** Complete for initial mux flow.

When receiving `DATA(stream_id, bytes)` from remote:

- lookup stream,
- write to local TCP socket,
- handle write errors by failing only that stream.

### 6.5 Listener shutdown

**Status:** Complete for current session lifecycle.

On WebRTC session failure or daemon shutdown:

- stop all forward listeners,
- close all active local TCP streams,
- remove all stream state.

### 6.6 Offer-side tests

**Status:** Complete.

Add tests for:

- two listeners started for two forwards,
- connection to forward `ssh` sends `OPEN` with `forward_id = ssh`,
- connection to forward `web-ui` sends `OPEN` with `forward_id = web-ui`,
- two simultaneous connections to one forward get distinct stream IDs,
- local EOF sends `CLOSE` only for that stream.

---

## Task 7 — Answer side: target connection per `OPEN`

**Status:** Complete.

### 7.1 Handle `OPEN`

**Status:** Complete for initial mux flow.

When answer side receives `OPEN(stream_id, OpenPayload { forward_id })`:

1. reject stream ID 0,
2. reject duplicate active stream ID,
3. lookup `forward_id` in local answer-side `ForwardTable`,
4. verify remote peer is allowed for that forward,
5. connect to configured `target_host:target_port`,
6. on success, register stream and send `OPEN(stream_id, empty ACK)`,
7. on failure, send `ERROR(stream_id, target_connect_failed)`.

### 7.2 Target TCP read loop

**Status:** Complete for initial mux flow.

For each target TCP stream:

- read chunks,
- send `DATA(stream_id, bytes)`,
- on EOF send `CLOSE(stream_id)`,
- on error send `ERROR(stream_id, remote_io_error)` if appropriate.

### 7.3 Target TCP write path

**Status:** Complete for initial mux flow.

When answer side receives `DATA(stream_id, bytes)`:

- lookup stream,
- write bytes to target TCP socket,
- on write error, fail only that stream.

### 7.4 Unknown/forbidden forwards

**Status:** Complete for authorized session-level peers.

If `forward_id` is unknown:

- send `ERROR(stream_id, unknown_forward)`,
- do not kill session.

If peer is forbidden:

- send `ERROR(stream_id, forbidden_forward)` only if peer is already authorized at the session level and policy permits error disclosure,
- otherwise silently drop according to existing unauthorized-peer policy.

For normal per-forward denial of an otherwise authorized peer, stream-level `forbidden_forward` is acceptable.

### 7.5 Answer-side tests

**Status:** Complete.

Add tests for:

- valid forward opens target,
- unknown forward returns `unknown_forward`,
- forbidden peer returns `forbidden_forward` or drops according to policy,
- target connect failure returns `target_connect_failed`,
- one target connect failure does not affect another active stream,
- data on stream A writes only to target A.

---

## Task 8 — Route incoming frames by stream ID

**Status:** Complete.

### 8.1 Implement frame dispatcher

**Status:** Complete for initial mux flow.

Incoming data-channel messages must be decoded to `TunnelFrame` and dispatched by `stream_id`.

Rules:

- `OPEN` on answer side creates stream,
- `OPEN` on offer side is ACK for existing opening stream,
- `DATA` requires existing open stream,
- `CLOSE` transitions that stream to closing/closed,
- `ERROR` fails that stream,
- unknown stream ID gets stream-level error or is ignored according to policy.

### 8.2 Offer-side `OPEN` ACK handling

**Status:** Complete.

Offer side should treat remote `OPEN(stream_id)` as ACK.

Rules:

- stream must be in `Opening`,
- transition to `Open`,
- release local TCP bridge to begin forwarding,
- duplicate ACK is ignored or logged.

### 8.3 Stream-level error handling

**Status:** Complete for initial mux flow.

On `ERROR(stream_id)`:

- mark stream failed,
- close local/target TCP socket,
- remove stream from map,
- do not close other streams.

### 8.4 Dispatcher tests

**Status:** Complete.

Add tests for:

- ACK transitions opening stream to open,
- DATA to unknown stream fails correctly,
- CLOSE closes only target stream,
- ERROR fails only target stream,
- duplicate CLOSE harmless,
- duplicate ERROR harmless.

---

## Task 9 — Integrate with daemon session loops

**Status:** Complete.

### 9.1 Replace old `TunnelBridge` calls

**Status:** Complete.

Remove or stop using the old single-stream calls:

- `run_offer`
- `run_answer`
- any one-stream bridge method.

Use the new multiplexed tunnel manager.

### 9.2 Offer session integration

**Status:** Complete for the first passing implementation phase.

After data channel opens:

1. create `MultiplexedTunnel`,
2. start forward listeners,
3. process data-channel frames,
4. process stream events,
5. stop all listeners/streams on session failure.

### 9.3 Answer session integration

**Status:** Complete for the first passing implementation phase.

After data channel opens:

1. create `MultiplexedTunnel`,
2. process incoming `OPEN` frames,
3. create target streams as needed,
4. route data frames,
5. close all streams on session failure.

### 9.4 Session failure behavior

**Status:** Complete for current session recovery behavior.

On WebRTC session failure:

- close all streams,
- close all listeners,
- clear stream map,
- return to existing session recovery behavior.

Do not try to preserve live TCP streams across reconnect.

### 9.5 Daemon tests

**Status:** Complete for current daemon coverage.

Add tests for:

- session failure closes all streams,
- session failure stops listeners,
- recovery starts with fresh stream ID allocation,
- ordinary stream failure does not trigger session reconnect,
- multiple simultaneous streams remain active until their own close/error.

---

## Task 10 — Update status/logging

**Status:** Complete.

### 10.1 Log stream context

**Status:** Complete.

Add logging fields where possible:

- `session_id`,
- `stream_id`,
- `forward_id`,
- `remote_peer_id`,
- `event`.

Do not log raw TCP payload data.

### 10.2 Optional status enhancement

**Status:** Complete for configured forward IDs; active stream counts were left out intentionally.

If simple to implement, add status fields:

```json
{
  "active_streams": 2,
  "configured_forwards": ["ssh", "web-ui"]
}
```

This is optional. Do not block the main multiplexing implementation on rich status.

### 10.3 Tests

**Status:** Complete for configured forward IDs.

If status is enhanced, add tests for:

- active stream count increments/decrements,
- configured forwards listed correctly,
- status after session close has zero active streams.

---

## Task 11 — Update CLI/config examples/docs

**Status:** Complete.

### 11.1 Update sample configs

**Status:** Complete.

Update sample offer and answer configs to use `[[forwards]]`.

Include at least:

- SSH example: `2223 -> 22`,
- web UI example: `8080 -> 8080`.

### 11.2 Update README

**Status:** Complete.

Document:

- multiple forwards,
- one listener per forward,
- answer side owns target mapping,
- per-forward allowlists,
- WebRTC data channel encryption,
- no custom data encryption added,
- no TURN support.

### 11.3 Add migration note

**Status:** Complete.

Document old-to-new config migration.

Old:

```toml
[tunnel.offer]
listen_port = 2223

[tunnel.answer]
target_port = 22
```

New:

```toml
[[forwards]]
id = "ssh"
listen_port = 2223
target_port = 22
```

### 11.4 Tests/docs check

**Status:** Complete.

Ensure all documented config examples parse.

---

## Task 12 — Remove obsolete code

**Status:** Complete.

### 12.1 Remove single-stream constants

**Status:** Complete.

Remove or stop using:

- `ACTIVE_STREAM_ID`,
- hardcoded stream ID `1`,
- code that rejects non-`1` stream IDs.

### 12.2 Remove obsolete bridge paths

**Status:** Complete.

Remove or deprecate old single-stream `TunnelBridge` paths once the multiplexed tunnel manager is integrated.

Do not leave two competing tunnel implementations unless one is explicitly test-only.

### 12.3 Remove obsolete config types

**Status:** Complete for public config types.

Remove old single-forward config fields and validation paths.

### 12.4 Search cleanup

**Status:** Complete.

Run repository-wide searches for:

- `ACTIVE_STREAM_ID`
- `listen_port`
- `target_port`
- `allow_remote_peers`
- `TunnelBridge`
- `stream_id == 1`
- `stream_id != 1`

Verify remaining occurrences are correct under the new model.

---

## Task 13 — Required final tests

**Status:** Complete.

Before considering this complete, add or update tests that prove:

### Config

- multiple forwards parse,
- duplicate forward IDs reject,
- duplicate listeners reject,
- old config rejects,
- per-forward allowlist validates.

### Frame/protocol

- nonzero stream IDs work,
- stream 0 rejects,
- OPEN payload carries forward ID,
- unknown/forbidden forward produces stream-level error.

### Runtime/tunnel

- two simultaneous streams on one forward work,
- two forwards work at the same time,
- failed stream does not kill other stream,
- data isolation by stream ID works,
- target connect failure affects only one stream,
- WebRTC session failure closes all streams.

### Daemon

- offer side starts one listener per forward,
- browser-like multiple connections to `web-ui` work concurrently,
- answer side enforces per-forward allowlists,
- session recovery returns daemon to waiting state.

---

## Task 14 — Acceptance checklist

**Status:** Complete.

Implementation is acceptable only if all items below are true:

- [x] Config uses `[[forwards]]`.
- [x] Old single-forward config is removed or rejected.
- [x] Offer side can bind multiple local ports.
- [x] One WebRTC data channel carries multiple simultaneous logical streams.
- [x] Each TCP connection gets a unique nonzero `stream_id`.
- [x] `stream_id = 0` is reserved/rejected for stream frames.
- [x] Offer side sends only `forward_id` in `OPEN`.
- [x] Answer side maps `forward_id` to local target from config.
- [x] Answer side enforces per-forward allowlists.
- [x] Unknown forward creates stream-level error.
- [x] Forbidden forward creates stream-level denial or silent drop according to policy.
- [x] Target connect failure closes only that stream.
- [x] Local TCP close closes only that stream.
- [x] Stream data is isolated by stream ID.
- [x] One stream failure does not kill other streams.
- [x] WebRTC session failure closes all streams.
- [x] No custom app-layer encryption is added for data frames.
- [x] MQTT signaling remains encrypted and signed.
- [x] Tests cover multiple forwards and simultaneous streams.
- [x] Documentation and sample configs are updated.

---

## Task 15 — Suggested implementation order

**Status:** Complete.

Use this order to reduce breakage:

1. Config model and validation.
2. Forward table.
3. Frame constructors and codec support for arbitrary stream IDs.
4. Open/error payloads.
5. Stream state and stream manager.
6. Central writer queue.
7. Offer-side multi-listener support.
8. Answer-side target-per-open support.
9. Frame dispatcher.
10. Daemon integration.
11. Tests.
12. Documentation cleanup.
13. Remove obsolete single-stream code.

Do not start by rewriting the daemon loops. First make the lower-level config/frame/stream abstractions testable.
