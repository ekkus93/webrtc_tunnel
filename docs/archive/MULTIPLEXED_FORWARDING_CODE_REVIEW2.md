# MULTIPLEXED_FORWARDING_CODE_REVIEW2.md

# Secure Rust WebRTC Tunnel — Multiplexed Forwarding Code Review 2

## Purpose

This review covers the current multiplexed-forwarding implementation after the first v2 hardening pass.

The implementation has made strong progress. The architecture is now genuinely multiplexed:

- one WebRTC peer connection,
- one reliable ordered data channel,
- multiple configured forwards,
- multiple simultaneous logical TCP streams,
- stream IDs used as real multiplexing keys,
- answer side owns target host/port mappings,
- forwarded TCP data relies on WebRTC DTLS,
- MQTT signaling remains encrypted and signed.

This review does **not** recommend redesigning the multiplexed architecture. The remaining issues are narrower and should be handled as a stream lifecycle / failure-isolation pass.

## Overall assessment

The latest code is the best version so far.

Major pieces that now appear substantially implemented:

- `format = "p2ptunnel-config-v2"`.
- Role-specific `[[forwards]]` config.
- Real nonzero stream IDs.
- `stream_id = 0` reserved/rejected for stream frames.
- Structured `OpenPayload { forward_id }`.
- Structured stream-level error payloads.
- Multiple offer-side local listeners.
- Answer-side target lookup by `forward_id`.
- Per-forward `allow_remote_peers`.
- Central data-channel writer path.
- Writer failure notification path.
- Answer-side target connect moved out of the dispatcher and protected by timeout.
- Empty `OPEN(stream_id)` ACK enforcement in code.
- Legacy first-forward CLI overrides removed.
- Legacy first-forward env vars rejected.
- Sample configs parse-tested.

However, several remaining problems are important for a long-running multiplexed TCP daemon.

## Highest-priority remaining issues

### 1. Local TCP EOF can leave local stream state stuck

The current TCP bridge read task sends a `CLOSE(stream_id)` when it reads EOF from the local socket. That part is good.

The problem is that the side that observed EOF does not clearly close/remove its own local runtime stream state. It appears to rely on the remote peer sending a `CLOSE` back.

That is not a safe assumption.

The current remote `CLOSE` handler appears to close its own local stream, but it does not necessarily echo a `CLOSE` back. This can leave the original EOF side with a stale stream entry.

#### Why this matters

This can cause:

- stream map entries that never disappear,
- sessions that do not return to idle after real TCP work is done,
- long-running daemons accumulating stale stream state,
- confusing logs/status because a stream appears alive after its TCP socket ended.

#### Required behavior

For v2, use a **full-close model**, not half-close semantics.

When local TCP read returns EOF:

1. send `CLOSE(stream_id)` to the peer,
2. locally close/remove the stream,
3. cancel/abort that stream's remaining tasks,
4. release queues/resources,
5. do not wait for the peer to echo `CLOSE`.

Do **not** implement half-close / FIN semantics in this pass.

### 2. TCP write errors are swallowed

The TCP write task currently appears to break out when `writer.write_all(&payload).await` fails.

That is not enough.

A TCP write failure is a stream-local failure and must be reported to the runtime so the stream can be closed and cleaned up. It should not silently exit and leave the stream registered.

#### Required behavior

When TCP write fails for one stream:

1. mark that stream failed or closing,
2. send `ERROR(stream_id, local_io_error)` to the peer if the data channel writer is still usable,
3. close/remove only that stream,
4. cancel/abort both halves of that stream,
5. do **not** fail the whole WebRTC session.

### 3. Closed per-stream write queues can become session-level failures

The runtime currently appears to treat some `TrySendError::Closed` cases as `TunnelError::StreamNotFound`, which can bubble up as a session-level error.

That violates the multiplexing requirement that ordinary stream-local failures should not kill the whole session.

#### Required behavior

If the per-stream write queue is closed when handling `DATA(stream_id, ...)`:

- treat it as a stream-local close/failure,
- optionally send `ERROR(stream_id, local_io_error)` if appropriate,
- remove/close that stream,
- return `Ok(())` from the frame handler,
- keep the WebRTC session and other streams alive.

Do not return a session-level error for one closed per-stream queue.

### 4. Stale `OPEN` ACK documentation remains

The code correctly appears to require answer-side success ACKs to be empty:

```text
OPEN(stream_id) with empty payload
```

But `docs/MULTIPLEXED_FORWARDING_SPEC.md` still contains stale wording allowing:

```text
OPEN(stream_id, { "ok": true })
```

That contradicts the frozen protocol rule and should be removed.

#### Required behavior

The spec must say:

- offer sends `OPEN(stream_id, OpenPayload { forward_id })`,
- answer success sends `OPEN(stream_id)` with empty payload,
- answer failure sends `ERROR(stream_id, ErrorPayload { ... })`,
- offer rejects non-empty `OPEN` ACK payload as `protocol_error`.

### 5. Stream task cancellation is better, but not fully deterministic

The current implementation stores task handles and aborts them, which is good. However, cleanup should be made more deterministic where practical.

#### Required behavior

When a stream is closed/failed:

- abort all owned tasks,
- drop/close socket halves and queues,
- remove from stream map,
- avoid leaving read tasks blocked,
- optionally await task completion briefly in cleanup paths where practical.

This does not need a major runtime rewrite, but stream cleanup must be reliable.

## What is good in the current implementation

### Real multiplexing is present

The code is no longer a single-stream tunnel disguised as multiplexing. It has real stream IDs and can run multiple configured forwards over one WebRTC data channel.

### Config v2 is mostly correct

The config now uses the correct v2 model and appears to reject the old single-forward model.

Good:

- `p2ptunnel-config-v2`.
- `[[forwards]]`.
- `[forwards.offer]`.
- `[forwards.answer]`.
- `[peer].remote_peer_id`.
- no single `listen_port` / `target_port` public model.

### Answer-side target ownership is preserved

The offer side sends only `forward_id`. The answer side maps that to locally configured `target_host:target_port`.

This is the correct security boundary.

### Writer failure path exists

The central writer now has a failure notification path, and runtime loops observe it.

That is a major improvement over earlier versions.

### Target connect no longer blocks the dispatcher

Answer-side target connect is now spawned and timeout-protected. The dispatcher can continue processing other streams while one target connect is pending.

### Legacy first-forward overrides were handled correctly

The first-forward-only CLI overrides are gone, and legacy env vars are rejected rather than silently ignored or applied to `forwards[0]`.

## What still needs focused tests

Add or strengthen tests for:

1. local TCP EOF removes local stream state,
2. local EOF sends one `CLOSE` and does not wait for echoed close,
3. TCP write failure closes only that stream,
4. `TrySendError::Closed` on one stream does not kill the session,
5. late `DATA` after close is harmless,
6. stream A failure does not affect stream B,
7. session remains alive after stream-local errors,
8. current spec docs contain only empty `OPEN` ACK semantics.

## Explicit non-goals for this pass

Do **not** redesign the protocol.

Do **not** add:

- TURN support,
- custom app-layer encryption for data frames,
- half-close / FIN semantics,
- new public config knobs,
- v1/v2 compatibility mode,
- arbitrary target host/port requests from offer to answer.

This pass is about finishing lifecycle correctness and failure isolation.

## Acceptance criteria

This review is addressed when:

- local TCP EOF closes/removes the local stream immediately,
- no side depends on remote `CLOSE` echo for cleanup,
- TCP write failure becomes stream-local failure,
- closed per-stream queue handling does not bubble up as session-level failure,
- docs state only empty `OPEN(stream_id)` ACK is valid,
- tests cover stream-local close/failure isolation,
- all existing multiplexed forwarding tests continue to pass.

## Bottom line

The multiplexed implementation is close, but the remaining bugs matter.

For a long-running daemon, stream state must not leak and one stream's TCP failure must not kill the whole session. Fix those lifecycle semantics before adding new features.
