# MULTIPLEXED_FORWARDING_FIX2_TODO.md

# Secure Rust WebRTC Tunnel — Multiplexed Forwarding Fix 2 TODO

## Goal

Finish the remaining stream lifecycle and failure-isolation work in the v2 multiplexed forwarding implementation.

## Status

| Task | Status | Notes |
| --- | --- | --- |
| 0.1 Read relevant docs/code | Complete | Reviewed review/spec/TODO docs and current multiplex runtime. |
| 0.2 Confirm v2 architecture | Complete | Frame v2, nonzero stream IDs, `OpenPayload { forward_id }`, answer-side target mapping, DTLS data transport, encrypted/signed MQTT signaling, spawned target connect, and central writer path remain intact. |
| 1.1 Locate local TCP read EOF | Complete | EOF handling is in `spawn_tcp_bridge`. |
| 1.2 Adopt full-close semantics | Complete | Local EOF sends `CLOSE`, schedules stream-local cleanup, removes stream state, and does not wait for remote echo. |
| 1.3 Add stream-local cleanup signal | Complete | Added stream runtime events for local EOF and local I/O failure. |
| 1.4 Ensure cleanup is idempotent | Complete | Duplicate cleanup and late close/error/data paths are harmless. |
| 1.5 EOF tests | Complete | Added local EOF cleanup/isolation coverage. |
| 2.1 Locate TCP write task behavior | Complete | Write task is in `spawn_tcp_bridge`. |
| 2.2 Report write failures | Complete | Write failures notify runtime as stream-local `local_io_error`. |
| 2.3 Avoid silent task exit | Complete | Unexpected write failure is reported before task exit. |
| 2.4 Avoid session-level write failure | Complete | Stream-local write failure cleanup does not return session-level failure. |
| 2.5 Write failure tests | Complete | Added local I/O failure isolation coverage. |
| 3.1 Locate `TrySendError::Closed` | Complete | Closed queue handling was in offer/answer `DATA` dispatch. |
| 3.2 Treat closed queues as stream-local | Complete | Closed write queue emits stream-local `local_io_error`, removes only that stream, and returns `Ok(())`. |
| 3.3 Keep `StreamNotFound` internal | Complete | Ordinary late/closed-stream `DATA` no longer bubbles as session failure. |
| 3.4 Closed queue tests | Complete | Added closed queue DATA isolation coverage. |
| 4.1 Review cleanup methods | Complete | Reviewed `RuntimeStream::close`, `abort_all`, and session cleanup. |
| 4.2 Deterministic cleanup | Complete | Cleanup keeps bounded wait for queued close and uses abort-on-drop for teardown. |
| 4.3 Prevent task leaks | Complete | Session cleanup drains stream map and aborts per-stream tasks. |
| 4.4 Cleanup tests | Complete | Added idempotent session cleanup/task abort coverage. |
| 5.1 Update multiplex spec | Pending | Stale non-empty `OPEN` ACK wording still needs doc update. |
| 5.2 Update examples | Pending | Remaining docs/examples need search cleanup. |
| 5.3 Add doc guard | Pending | Add guard test if feasible. |
| 6.1 Local EOF cleanup test | Complete | Covered. |
| 6.2 TCP write failure test | Complete | Covered via stream runtime event isolation. |
| 6.3 Closed queue DATA test | Complete | Covered. |
| 6.4 Late frame tests | Complete | Covered late `DATA`, duplicate `CLOSE`, duplicate `ERROR`, and post-cleanup remote frames. |
| 6.5 Stream failure isolation test | Complete | Covered stream A failure while stream B remains usable. |
| 6.6 Session failure test | Complete | Existing central writer/session cleanup coverage remains in place and cleanup coverage was strengthened. |
| 7.1 Run repository searches | Pending | Final stale search still needed. |
| 7.2 Remove stale current comments | Pending | Pending final search. |
| 7.3 Clean unused code/imports | Pending | Pending final lint/search. |
| 8 Acceptance checklist | Pending | Will be marked after docs, stale search, validation, commit, and push. |

This is **not** a redesign. The multiplexed architecture remains:

```text
one WebRTC peer connection
one reliable ordered data channel
many configured forwards
many simultaneous logical TCP streams
stream_id multiplexing over the data channel
answer side owns target mappings
forwarded data uses WebRTC DTLS
MQTT signaling remains encrypted and signed
```

The current implementation is mostly correct architecturally. This TODO focuses on the remaining correctness issues:

1. local TCP EOF must clean up local stream state,
2. TCP write failures must be stream-local,
3. closed per-stream queues must not become session-level failures,
4. stale `OPEN` ACK documentation must be fixed,
5. tests must prove stream-local failure isolation.

## Non-negotiable rules

- Do not reintroduce the old single-stream tunnel model.
- Do not reintroduce `ACTIVE_STREAM_ID = 1`.
- Do not reintroduce single `listen_port` / `target_port` config.
- Do not add TURN support.
- Do not add custom app-layer encryption for data frames.
- Do not let the offer side choose arbitrary target host/port.
- Do not implement half-close semantics in this pass.
- One stream's local TCP failure must not kill the whole WebRTC session.
- Closed streams must not remain in the stream map.
- Answer-side success ACK is always empty `OPEN(stream_id)`.

---

# Task 0 — Baseline verification

## 0.1 Read the relevant docs and code

Read:

- `docs/MULTIPLEXED_FORWARDING_SPEC.md`
- `docs/MULTIPLEXED_FORWARDING_FIX_TODO.md`
- `docs/MULTIPLEXED_FORWARDING_CODE_REVIEW.md`
- `docs/MULTIPLEXED_FORWARDING_CODE_REVIEW2.md`
- this TODO file

Then inspect the current multiplexed runtime implementation.

Focus on:

- `run_multiplex_offer`
- `run_multiplex_answer`
- frame dispatch handlers
- `spawn_tcp_bridge`
- stream registration/removal
- `RuntimeStream`
- stream task handles
- writer failure path
- close/error handling

## 0.2 Confirm current v2 architecture remains intact

Before making changes, verify:

- frame version is `2`,
- `stream_id = 0` is rejected for stream frames,
- `OpenPayload` contains only `forward_id`,
- answer side maps `forward_id` to configured target,
- forwarded data still relies on WebRTC DTLS,
- MQTT signaling remains encrypted/signed,
- target connect is spawned and timeout-backed,
- central writer path is still used.

Do not proceed if any of those have regressed.

---

# Task 1 — Fix local TCP EOF stream cleanup

## 1.1 Locate local TCP read EOF handling

Find the read loop in the per-stream TCP bridge.

Look for logic equivalent to:

```rust
Ok(0) => {
    send CLOSE(stream_id);
    break;
}
```

This is the main area to fix.

## 1.2 Adopt full-close semantics

For v2, implement a simple full-close model.

When local TCP read returns EOF:

1. send `CLOSE(stream_id)` to the peer,
2. locally close/remove the stream,
3. cancel/abort that stream's write task,
4. drop/close socket halves and queues,
5. do not wait for the peer to echo `CLOSE`.

Do **not** implement half-close semantics in this pass.

## 1.3 Add a stream-local cleanup signal if needed

If the TCP read task cannot directly call `close_stream`, add an internal runtime event.

Example:

```rust
enum StreamRuntimeEvent {
    LocalEof { stream_id: StreamId },
    LocalIoError { stream_id: StreamId, message: String },
}
```

The main runtime loop can then handle the event and perform cleanup.

Alternative acceptable approach:

- give the bridge task access to a cleanup sender/manager handle,
- but avoid shared mutable state races.

## 1.4 Ensure cleanup is idempotent

Calling close/cleanup twice for the same stream must be harmless.

Cases that must be safe:

- local EOF and remote `CLOSE` arrive around the same time,
- local EOF and local write failure happen around the same time,
- stream is already removed when a late frame arrives.

## 1.5 Tests

Add tests proving:

- local TCP EOF sends `CLOSE(stream_id)`,
- local TCP EOF removes the local stream from the stream map,
- local TCP EOF does not require remote `CLOSE` echo,
- duplicate remote `CLOSE` after local EOF is harmless,
- stream A EOF does not close stream B.

---

# Task 2 — Fix TCP write failure handling

## 2.1 Locate TCP write task behavior

Find the per-stream write task handling payloads from the remote peer.

Look for logic equivalent to:

```rust
if writer.write_all(&payload).await.is_err() {
    break;
}
```

This must be replaced with stream-local failure handling.

## 2.2 Report write failures as stream-local runtime events

On `write_all` failure:

1. notify the runtime that `stream_id` failed locally,
2. send `ERROR(stream_id, local_io_error)` to the peer if possible,
3. close/remove only that stream,
4. cancel/abort that stream's other task(s),
5. keep the WebRTC session alive.

Use the same event mechanism as Task 1 if possible.

Example:

```rust
StreamRuntimeEvent::LocalIoError {
    stream_id,
    message: "tcp write failed: ...".to_string(),
}
```

## 2.3 Avoid silent task exit

The write task must not simply break and disappear.

Every unexpected write-side failure should be observable by the stream manager/runtime.

## 2.4 Avoid session-level failure for stream-local write errors

A failed local TCP write is not a WebRTC/data-channel failure.

It must not return a session-level `TunnelError` from the main frame handler unless the central data-channel writer itself fails.

## 2.5 Tests

Add tests proving:

- local TCP write failure sends or schedules `ERROR(stream_id, local_io_error)`,
- local TCP write failure removes only that stream,
- local TCP write failure does not close other streams,
- local TCP write failure does not terminate the multiplexed session.

---

# Task 3 — Fix closed per-stream write queue handling

## 3.1 Locate `TrySendError::Closed` handling

Search for:

```rust
TrySendError::Closed
TunnelError::StreamNotFound
```

in DATA frame handling.

This currently appears too severe when the stream exists but its write queue is closed.

## 3.2 Treat closed write queues as stream-local close/failure

When handling `DATA(stream_id, payload)`:

If the stream is unknown:
- ignore and log at debug/trace level, or
- perform the existing late-frame policy.

If the stream exists but its write queue is closed:
- close/remove that stream,
- optionally send `ERROR(stream_id, local_io_error)` if not already closing,
- return `Ok(())`,
- do not return a session-level error.

## 3.3 Keep `StreamNotFound` for internal API errors only

`TunnelError::StreamNotFound` may still be useful internally, but it must not escape the frame dispatcher in a way that kills the session for ordinary late/closed-stream `DATA`.

## 3.4 Tests

Add tests proving:

- `DATA` for unknown stream is harmless,
- `DATA` for closed stream is harmless,
- `DATA` for a stream with closed write queue does not kill the session,
- closed write queue removes/fails only that stream,
- stream B remains usable after stream A write queue is closed.

---

# Task 4 — Tighten stream cleanup and task ownership

## 4.1 Review `RuntimeStream::close` / `abort_all`

Inspect stream cleanup methods.

Confirm they:

- abort all owned tasks,
- drop/close queues,
- release socket halves,
- transition lifecycle state correctly,
- remove the stream from the map.

## 4.2 Make cleanup deterministic enough for v2

It is acceptable to use `JoinHandle::abort()`.

If practical, after aborting tasks:

- await completion briefly,
- or document that abort-on-drop is the v2 cleanup model.

Do not block indefinitely during session teardown.

## 4.3 Prevent task leaks after session failure

Session teardown must:

- drain the stream map,
- close each stream,
- abort all per-stream tasks,
- close central writer queue,
- return to daemon recovery/waiting behavior.

## 4.4 Tests

Add or strengthen tests proving:

- session failure empties the stream map,
- session failure aborts stream tasks,
- repeated cleanup calls are harmless,
- no stream task continues processing after cleanup signal.

Use test hooks/channels if needed.

---

# Task 5 — Fix stale `OPEN` ACK documentation

## 5.1 Update `docs/MULTIPLEXED_FORWARDING_SPEC.md`

Find any wording that says the answer-side success ACK can be:

```text
OPEN(stream_id, { "ok": true })
```

or any other non-empty payload.

Replace with:

```text
Answer-side success ACK is exactly OPEN(stream_id) with empty payload.
```

## 5.2 Update all examples

Search docs and examples for:

```text
"ok": true
OPEN ACK
open ack
OPEN(stream_id
```

Ensure they all match the frozen rule:

- request: `OPEN(stream_id, OpenPayload { forward_id })`,
- success: `OPEN(stream_id)` empty payload,
- failure: `ERROR(stream_id, ErrorPayload { code, message })`.

## 5.3 Add doc guard test if feasible

If the project has doc/sample validation tests, add a simple check to prevent reintroducing `{ "ok": true }` in the current multiplexing spec.

If that is too awkward, at minimum ensure stale text is removed.

---

# Task 6 — Focused lifecycle/failure-isolation tests

## 6.1 Local EOF cleanup test

Add a test that simulates:

1. stream is open,
2. local TCP read returns EOF,
3. runtime sends `CLOSE(stream_id)`,
4. local stream is removed,
5. no remote echo is required.

Expected result:

- stream map no longer contains `stream_id`,
- session remains alive,
- other streams remain alive.

## 6.2 TCP write failure test

Add a test that simulates:

1. stream A is open,
2. writing to local TCP sink fails,
3. stream A is failed/removed,
4. stream B remains open,
5. session remains alive.

Expected result:

- optional `ERROR(stream_id, local_io_error)` emitted for stream A,
- no session-level failure.

## 6.3 Closed queue DATA test

Add a test that simulates:

1. stream A exists,
2. its per-stream write queue is closed,
3. remote `DATA(stream_id=A)` arrives,
4. handler does not return session-level error,
5. stream A is cleaned up,
6. stream B remains unaffected.

## 6.4 Late frame tests

Add tests for:

- late `DATA` after stream close,
- duplicate `CLOSE`,
- duplicate `ERROR`,
- remote `CLOSE` arriving after local EOF cleanup.

All must be harmless.

## 6.5 Stream failure isolation test

Add a test where:

- stream A fails due to local I/O,
- stream B continues to send/receive data,
- the WebRTC/data-channel writer remains alive.

## 6.6 Session failure test

Ensure existing session failure tests still prove:

- central writer failure tears down the session,
- WebRTC/data-channel failure tears down the session,
- all streams are cleaned up,
- daemon returns to recovery/waiting behavior.

---

# Task 7 — Final stale search and cleanup

## 7.1 Run repository searches

Search for:

```text
ACTIVE_STREAM_ID
stream_id == 1
stream_id != 1
single-stream
single stream
OPEN(stream_id, { "ok": true })
"ok": true
remote_io_error
TunnelBridge
```

Classify remaining occurrences as:

- valid historical review docs,
- valid tests for rejected legacy behavior,
- stale current docs/comments,
- obsolete code.

Do not leave stale current docs/comments.

## 7.2 Remove stale current comments

Current runtime comments must not claim:

- single-stream behavior,
- SSH-only behavior,
- TURN support,
- non-empty `OPEN` ACK support.

Historical review docs may keep old wording if clearly historical.

## 7.3 Clean unused code/imports

Remove any new dead code introduced while fixing stream lifecycle.

---

# Task 8 — Acceptance checklist

Mark this TODO complete only when all items below are true.

## Stream close semantics

- [ ] Local TCP EOF sends `CLOSE(stream_id)`.
- [ ] Local TCP EOF removes local stream state.
- [ ] Local cleanup does not require remote `CLOSE` echo.
- [ ] Duplicate/later remote `CLOSE` is harmless.
- [ ] Closing stream A does not close stream B.

## TCP write failure

- [ ] TCP write failure is observable by the runtime.
- [ ] TCP write failure closes/removes only that stream.
- [ ] TCP write failure optionally sends `ERROR(local_io_error)`.
- [ ] TCP write failure does not kill the session.
- [ ] Other streams continue after one stream's write failure.

## Closed queue / late frame handling

- [ ] `DATA` for unknown stream is harmless.
- [ ] `DATA` for closed stream is harmless.
- [ ] `DATA` for stream with closed write queue is stream-local.
- [ ] `TrySendError::Closed` does not bubble out as session-level failure.
- [ ] Duplicate `CLOSE` and duplicate `ERROR` are harmless.

## Docs/protocol

- [ ] Current spec says only empty `OPEN(stream_id)` ACK is valid.
- [ ] No current docs allow `{ "ok": true }` ACK.
- [ ] Request `OPEN` still carries only `forward_id`.
- [ ] Failure still uses stream-level `ERROR`.

## Tests

- [ ] Local EOF cleanup tested.
- [ ] TCP write failure isolation tested.
- [ ] Closed queue DATA behavior tested.
- [ ] Stream A failure does not affect stream B.
- [ ] Session-level failures still clean all streams.

---

# Suggested implementation order

1. Add stream runtime event for local EOF / local I/O failure if needed.
2. Fix local EOF cleanup.
3. Fix TCP write failure reporting.
4. Fix `TrySendError::Closed` handling.
5. Tighten stream cleanup/idempotency.
6. Fix stale `OPEN` ACK documentation.
7. Add focused tests.
8. Run stale search and cleanup.

Do not start by changing config or protocol shape. The v2 protocol shape is already correct; this pass is about lifecycle correctness.
