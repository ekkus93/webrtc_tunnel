# MULTIPLEXED_FORWARDING_CODE_REVIEW.md

# Secure Rust WebRTC Tunnel — Multiplexed Forwarding Code Review

## Purpose

This document reviews the current multiplexed forwarding implementation after the first major `[[forwards]]` / multi-stream refactor.

The implementation has made substantial progress. The core architecture is now much closer to the desired model:

```text
one WebRTC peer connection
one reliable ordered data channel
many configured forwards
many simultaneous logical TCP streams
stream_id multiplexing over the data channel
```

However, the implementation should not yet be treated as fully complete or production-hardened. The remaining issues are not a redesign of the multiplexing architecture. They are primarily lifecycle, failure-propagation, cleanup, documentation, and high-level behavior-test issues.

## High-level verdict

The multiplexed architecture is basically right.

The implementation appears to have completed most of the major structural work:

- `format = "p2ptunnel-config-v2"` is present.
- `[[forwards]]` configuration is present.
- Role-specific forward configuration is present.
- The old single `listen_port -> target_port` model is mostly gone from the real runtime model.
- The tunnel frame protocol supports real nonzero `stream_id` values.
- `stream_id = 0` is reserved/rejected for stream-scoped frames.
- `OPEN` carries a structured `forward_id`.
- The answer side owns target host/port mapping.
- Multiple listeners/forwards exist on the offer side.
- Stream-level errors exist.
- The implementation does not add custom app-layer data encryption for forwarded TCP bytes, relying on WebRTC DTLS as intended.
- MQTT signaling remains encrypted and signed.

That is good progress.

The remaining issues are mostly about making the multiplexed runtime safe and trustworthy as a long-running daemon.

## What is good about the current code

### 1. The config model is moving in the right direction

The code now uses the v2 idea of configured forwards rather than a single fixed SSH-ish tunnel.

The intended shape is:

```toml
format = "p2ptunnel-config-v2"

[peer]
remote_peer_id = "home-server"

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
```

and on the answer side:

```toml
format = "p2ptunnel-config-v2"

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
```

This is a much better model than the original SSH-specific `listen_port` / `target_port` pair.

### 2. The answer side owns target mapping

This is one of the most important security properties.

The offer side should send only:

```text
OPEN(stream_id, { forward_id })
```

The answer side must locally map that `forward_id` to:

```text
target_host:target_port
```

The current implementation appears to follow this model. That prevents a remote peer from choosing arbitrary local targets on the answer host.

### 3. Real stream IDs exist

The frame protocol is no longer limited to `ACTIVE_STREAM_ID = 1`.

The implementation uses real stream IDs and can represent:

```text
stream 1 -> ssh
stream 2 -> web-ui
stream 3 -> web-ui
stream 4 -> another simultaneous browser connection
```

This is the core requirement for browser-like or multi-connection services.

### 4. Stream-scoped errors exist

The implementation now has structured stream-level errors such as:

- `unknown_forward`
- `forbidden_forward`
- `target_connect_failed`
- `stream_not_found`
- `stream_already_exists`
- `protocol_error`
- `local_io_error`
- `remote_io_error`
- `queue_overflow`

That is good. A failure in one stream should not kill the entire WebRTC session unless the underlying data channel/session fails.

### 5. The code has meaningful tests

The codebase appears to include tests for:

- config parsing and validation
- forward table lookup
- arbitrary nonzero stream IDs
- stream ID 0 rejection
- `OPEN` payload parsing
- error payload parsing
- multiple stream allocation
- unknown/forbidden forward cases
- multiple forwards
- stream isolation

That is good. The next test gap is less about isolated unit behavior and more about daemon/runtime lifecycle.

## Main remaining issues

## P0 — Per-stream TCP tasks are not deterministically owned/cancelled

### Problem

The multiplexed runtime spawns per-stream TCP read/write tasks. The stream state does not appear to reliably retain all task handles or cancellation tokens for those tasks.

That means stream cleanup may drop the stream state and close one half of the socket while another task remains blocked in a TCP read or write.

In a long-running daemon, this can cause:

- leaked Tokio tasks,
- leaked sockets,
- tasks blocked indefinitely on dead streams,
- stale stream activity after the stream was logically closed,
- unreliable session shutdown.

### Why this matters

Multiplexing creates many streams. A task leak that was tolerable in a single-stream prototype becomes dangerous in a multiplexed daemon.

Every stream lifecycle must be deterministic:

```text
OPEN -> Open -> Closing -> Closed
```

or:

```text
OPEN -> Failed -> Closed
```

When the stream closes, every task associated with that stream must stop.

### Required behavior

Each runtime stream must own or be associated with:

- a cancellation token, or
- explicit read/write task handles, or
- both.

On stream close:

- cancel/abort TCP read task,
- cancel/abort TCP write task,
- close local/target socket halves,
- remove stream from map,
- ensure late frames for that stream are ignored or receive stream-level error according to policy.

On session failure:

- cancel all stream tasks,
- close all TCP sockets,
- clear stream map,
- stop all listeners for that session,
- stop the writer task.

## P0 — Writer failure is not reliably propagated to the session

### Problem

The TODO required a central data-channel writer path. The important property is not just that all sends go through a queue. The important property is that if the writer fails to send to the WebRTC data channel, the session is notified and the whole session fails.

The current implementation appears to have writer abstractions, but the actual runtime path may use a simpler writer that exits without reliably notifying the session loop.

### Why this matters

If `data_channel.send()` fails, the tunnel is no longer usable.

The session must not continue pretending streams are open after the data channel writer has failed.

### Required behavior

The writer task must have an explicit failure path:

```rust
writer_error_tx.send(TunnelSessionError::WriterFailed(...))
```

or equivalent.

The session loop must select on that failure signal:

```rust
tokio::select! {
    Some(err) = writer_error_rx.recv() => {
        fail_session(err);
    }
    ...
}
```

Writer failure is session-level failure, not stream-level failure.

### Required tests

Add tests proving:

- writer send failure notifies session,
- session failure closes all active streams,
- session failure cancels all stream tasks,
- no stream remains active after writer failure.

## P0 — Answer-side target connect can block the frame dispatcher

### Problem

When the answer side receives:

```text
OPEN(stream_id, { forward_id })
```

it appears to perform `TcpStream::connect(target_host, target_port)` inline in the frame handling/dispatcher path.

A slow or hanging connect to one target can stall the processing of all incoming frames on the data channel.

### Example failure

Suppose these streams are active:

```text
stream 1 -> web-ui
stream 2 -> ssh
```

Then the peer sends:

```text
OPEN(stream_id=3, forward_id="bad-service")
```

If `bad-service` target connect hangs inline, then data for streams 1 and 2 may stop being processed until the connect returns.

That violates stream isolation.

### Required behavior

Target connect for one stream must not block the central frame dispatcher.

Acceptable designs:

1. spawn a per-stream target-connect task; or
2. use a short `tokio::time::timeout()` around connect and ensure the dispatcher stays responsive; or
3. both.

Recommended approach:

- dispatcher validates `OPEN`,
- dispatcher reserves/registers stream in `Opening`,
- dispatcher spawns a task to connect target,
- connect task sends result back to stream manager,
- on success, send empty `OPEN(stream_id)` ACK and start target bridge,
- on failure, send `ERROR(stream_id, target_connect_failed)`.

### Required tests

Add tests proving:

- one slow/hung target connect does not block frames for an already-open stream,
- target connect failure only fails that stream,
- other streams remain active after target connect failure.

## P1 — Offer-side `OPEN` ACK should enforce empty payload

### Problem

The protocol decision was:

```text
answer success ACK = OPEN(stream_id) with empty payload
```

The offer side should not accept arbitrary payloads on an `OPEN` ACK.

If the offer side receives:

```text
OPEN(stream_id, non_empty_payload)
```

for an opening stream, that is malformed under v2.

### Required behavior

On offer side:

- `OPEN(stream_id, empty payload)` for an opening stream means ACK/success.
- `OPEN(stream_id, non-empty payload)` is a protocol error.
- The stream should fail, or the session should fail if the implementation treats protocol errors as session-fatal.
- Do not silently accept non-empty payloads.

### Required tests

Add tests for:

- empty `OPEN` ACK transitions stream from `Opening` to `Open`,
- non-empty `OPEN` ACK is rejected,
- duplicate empty ACK is harmless or logged, but does not corrupt state.

## P1 — Stale docs and examples

### Problem

Some docs still appear to contain stale multiplexing examples such as:

```toml
turn_urls = []
```

and old flat forward examples with dummy fields like:

```toml
target_host = ""
target_port = 0
allow_remote_peers = []
```

or:

```toml
listen_host = ""
listen_port = 0
```

Those contradict the final v2 decisions:

- no TURN in v2,
- no `turn_urls` examples,
- role-specific `[forwards.offer]` and `[forwards.answer]`,
- no dummy placeholder fields,
- strict config validation.

### Required behavior

All docs and sample configs must use the final v2 format.

All documented config examples must parse.

### Required cleanup

Update:

- `docs/MULTIPLEXED_FORWARDING_SPEC.md`
- README
- sample offer config
- sample answer config
- migration notes
- any old comments referring to “single-stream” runtime behavior

## P1 — CLI/env overrides are still shaped like the single-forward era

### Problem

Some CLI/environment override code appears to still assume there is a first/default forward and may mutate only the first configured forward.

That was reasonable in the single-forward model, but it is misleading in v2.

### Required decision

For v2, do one of the following:

### Preferred option: remove first-forward-only overrides

Remove old single-forward flags/env overrides such as:

- listen host override,
- listen port override,
- target host override,
- target port override,

if they only affect the first configured forward.

### Acceptable later option: forward-scoped overrides

Add explicit forward-scoped overrides, such as:

```text
--forward ssh.listen-port=2223
--forward web-ui.listen-port=8080
```

But this is not necessary for v2.

### Required behavior

Do not keep override behavior that silently mutates only the first forward.

## P1 — `MultiplexedTunnel` abstraction is not consistently used

### Problem

There appears to be a `MultiplexedTunnel` / manager abstraction, but the runtime may also contain standalone `run_multiplex_offer` / `run_multiplex_answer` logic that duplicates some responsibilities.

This makes the code harder to reason about.

### Why this matters

The implementation should have one obvious owner for:

- stream map,
- stream lifecycle,
- writer queue,
- writer failure,
- stream task cancellation,
- forward lookup,
- frame dispatch.

If multiple modules partially own those concerns, future fixes may be applied to the wrong path.

### Required behavior

Pick one runtime design and make it clear:

- either use `MultiplexedTunnel` as the real runtime manager,
- or remove/deprecate it if `run_multiplex_offer` / `run_multiplex_answer` are the real runtime.

Do not leave two competing implementations unless one is explicitly test-only.

## P2 — More daemon-level integration coverage needed

### Problem

The code has useful unit tests, but the remaining risk is lifecycle/orchestration behavior.

### Required tests

Add higher-level tests proving:

- two configured forwards work at the same time,
- two simultaneous streams on one forward work,
- browser-like multiple connections to one forward work concurrently,
- target connect failure affects only that stream,
- a slow/hung target connect does not block other streams,
- data-channel writer failure tears down the session,
- WebRTC/session failure closes all streams,
- session shutdown cancels per-stream tasks,
- docs/sample configs parse,
- old v1 config rejects clearly.

## Acceptance criteria for the next pass

The next implementation pass is acceptable only if all of these are true:

- [ ] Every stream owns/cancels its associated TCP tasks.
- [ ] Session failure cancels all stream tasks.
- [ ] Writer failure is propagated to the session.
- [ ] Writer failure tears down all streams.
- [ ] Target connect for one stream cannot block the whole frame dispatcher.
- [ ] Target connect failure is stream-scoped.
- [ ] Offer-side `OPEN` ACK requires empty payload.
- [ ] Stale TURN examples are removed.
- [ ] Docs use role-specific `[forwards.offer]` / `[forwards.answer]`.
- [ ] All documented config examples parse.
- [ ] First-forward-only CLI/env overrides are removed or replaced.
- [ ] Runtime has one clear multiplexed manager path.
- [ ] Higher-level lifecycle tests cover multi-forward and multi-stream behavior.

## Bottom line

The multiplexed forwarding refactor is mostly successful at the architecture level.

The remaining work is not a redesign. It is a correctness/hardening pass.

The highest priority items are:

1. deterministic stream task ownership/cancellation,
2. writer failure propagation,
3. non-blocking answer-side target connect,
4. strict `OPEN` ACK validation,
5. stale docs/override cleanup,
6. daemon-level lifecycle tests.

Once those are fixed, the implementation will be much closer to a trustworthy long-running multiplexed port-forwarding daemon.
