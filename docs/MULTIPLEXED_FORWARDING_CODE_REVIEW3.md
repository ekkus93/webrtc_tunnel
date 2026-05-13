# Secure Rust WebRTC Tunnel — Multiplexed Forwarding Code Review 3

## Purpose

This review documents the remaining follow-up work after the Fix 2 multiplexed forwarding pass.

This is **not** a redesign. The current v2 multiplexed architecture should remain intact:

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

The current implementation is substantially improved and appears to have completed the main Fix 2 goals:

- local TCP EOF now cleans up local stream state,
- TCP write failures are stream-local,
- closed per-stream queues no longer become session-level failures,
- stale non-empty `OPEN` ACK docs were cleaned up,
- focused stream lifecycle/failure-isolation tests were added.

The remaining issues are smaller but still worth fixing before calling the v2 multiplexed implementation clean.

---

## High-level assessment

The latest code is in good shape structurally. The core multiplexing design is present and mostly correct:

- frame version `2`,
- nonzero stream IDs,
- `stream_id = 0` reserved/rejected for stream-scoped frames,
- `OpenPayload { forward_id }`,
- answer-side forward lookup and target ownership,
- spawned target-connect path with timeout,
- central writer path,
- stream-local EOF and local I/O failure events,
- stream task ownership/cancellation,
- encrypted/signed MQTT signaling,
- WebRTC DTLS for forwarded TCP data.

The remaining issues are **protocol hardening** and **session-lifetime clarity**, not core architectural defects.

---

## What is good

### 1. Multiplexing is real now

The runtime is no longer a single-stream tunnel with superficial config changes. It now supports multiple logical streams over one data channel.

### 2. Stream-local lifecycle handling is much better

Local EOF, local write failure, closed queues, late `DATA`, duplicate `CLOSE`, and duplicate `ERROR` are handled much more safely than before.

### 3. The answer side still owns target mapping

The offer side sends only `forward_id`; it does not get to choose arbitrary `target_host` or `target_port`. This remains the correct security boundary.

### 4. Writer failure and session failure are better separated from stream failure

Central writer/data-channel failure is treated as session-level, while TCP stream failures are now generally stream-local.

### 5. Tests are now focused on the correct risk areas

The test suite appears to cover several important runtime behaviors, including stream isolation and lifecycle cleanup.

---

## Remaining issues

## Issue 1 — Malformed answer-side `OPEN` request can still become session-level failure

### Problem

On the answer side, incoming `OPEN(stream_id, payload)` is expected to carry:

```rust
OpenPayload { forward_id }
```

If a peer sends a malformed `OPEN` request payload, such as:

- empty payload,
- invalid CBOR/JSON,
- missing `forward_id`,
- wrong shape,
- otherwise unparsable open payload,

the current answer-side path appears able to bubble the parse error out of the frame handler as a session-level failure.

That is too severe for a stream-scoped protocol error.

### Expected v2 behavior

Malformed answer-side `OPEN` request payloads should be treated as **stream-level protocol errors**, not session-level failures.

Required behavior:

```text
Answer receives malformed OPEN(stream_id, payload)
-> send ERROR(stream_id, protocol_error) if possible
-> do not register/open the stream
-> do not tear down the WebRTC/data-channel session
-> leave other streams unaffected
```

### Why this matters

A malformed `OPEN` is scoped to one stream ID. It should not kill unrelated streams or the entire WebRTC session.

This preserves the v2 rule:

```text
stream failures are isolated unless the underlying data channel/session fails
```

---

## Issue 2 — Session lifetime after all streams close is implicit

### Problem

The current runtime appears closer to a persistent-session model:

```text
WebRTC session remains open after all active streams close
future local clients can open new streams over the same data channel
```

That is reasonable and arguably the best behavior for a multiplexed tunnel.

However, the policy should be explicit in the spec, docs, comments, and tests. Avoid leaving ambiguous wording that implies:

- one WebRTC session per TCP connection,
- one session per stream,
- return to daemon idle after every stream closes,
- close/reconnect whenever the stream map becomes empty.

### Recommended policy

Freeze the v2 policy as:

```text
Persistent-session policy:
After the last logical stream closes, the WebRTC peer connection and data channel remain open.
The offer daemon may accept future local clients and open new logical streams over the same existing data channel.
The session closes only on data-channel/WebRTC failure, explicit daemon shutdown, remote session close, or fatal protocol/session error.
```

### Why this policy is best

Persistent sessions are better for multiplexed forwarding because they avoid repeated WebRTC negotiation for common traffic patterns:

- browser-like short-lived connections,
- repeated HTTP requests,
- multiple service connections over time,
- repeated SSH reconnects,
- local tools opening short control connections.

This is the natural model for:

```text
one WebRTC peer connection
one data channel
many logical streams
```

---

## Issue 3 — Documentation should explicitly reflect persistent-session behavior

### Problem

Some historical comments/docs may still imply older behavior, such as:

- single stream,
- one session per accepted local client,
- close session when stream finishes,
- return to waiting after each stream close.

Historical review docs may keep old text if clearly historical, but current docs and code comments should be accurate.

### Required cleanup

Current documentation should explicitly state:

- listeners stay bound on the offer side,
- first local client can trigger session creation,
- once established, the WebRTC session can carry many streams,
- after all streams close, the session remains alive for reuse,
- session failure closes all streams and returns the daemon to waiting/recovery,
- stream failure does not close the session.

---

## Issue 4 — Focused tests should lock this behavior down

The code needs targeted tests for the remaining edge cases.

Required test categories:

1. malformed answer-side `OPEN` does not kill session,
2. malformed answer-side `OPEN` produces `ERROR(stream_id, protocol_error)`,
3. stream B remains open after malformed stream A,
4. after all streams close, session remains alive under the persistent-session policy,
5. a later local client can open a new stream on the same existing session/data channel.

---

## Non-goals

Do **not** use this pass to redesign the multiplexed runtime.

Do not:

- reintroduce the single-stream tunnel,
- add TURN,
- add custom app-layer encryption for `DATA` frames,
- let the offer side choose target host/port,
- add half-close semantics,
- rewrite the whole daemon,
- change the config shape,
- change the key/signaling crypto model.

This is a focused protocol-hardening and documentation/test pass.

---

## Recommended fixes

### Fix 1 — Make malformed answer-side `OPEN` stream-local

When answer-side `OPEN` payload parsing fails:

- catch the error inside the frame handler,
- emit `ERROR(stream_id, protocol_error)`,
- cleanup/remove that attempted stream if needed,
- return `Ok(())`,
- keep the session alive.

### Fix 2 — Freeze persistent-session behavior

Document and test:

```text
zero active streams does not automatically close the WebRTC session
```

If there is code that exits the session solely because `active_stream_count == 0`, remove or adjust it unless it only applies to explicit shutdown/session failure.

### Fix 3 — Update current docs/comments

Update:

- `docs/MULTIPLEXED_FORWARDING_SPEC.md`,
- README if needed,
- current runtime comments,
- current examples if needed.

Make sure current docs match:

```text
persistent session after streams close
empty OPEN ACK only
stream-local malformed OPEN errors
```

### Fix 4 — Add focused tests

Add narrow tests rather than another broad rewrite.

### Fix 5 — Require local verification

Run:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
```

---

## Acceptance criteria

This follow-up is complete when:

- malformed answer-side `OPEN` request payloads produce stream-level `protocol_error`,
- malformed answer-side `OPEN` does not kill the WebRTC/data-channel session,
- existing streams remain alive after a malformed `OPEN` on another stream,
- the v2 session lifetime policy is explicitly documented,
- tests prove the session can remain alive with zero active streams,
- tests prove a later client can open a new stream on the existing session,
- current docs do not imply single-stream or one-session-per-stream behavior,
- formatting, clippy, and full tests pass locally.

---

## Bottom line

The v2 multiplexed forwarding implementation is close. The next pass should be small and focused:

1. make malformed answer-side `OPEN` stream-local,
2. explicitly freeze persistent-session behavior,
3. update docs/comments,
4. add focused tests,
5. run full Rust verification.

Do not redesign the tunnel again.
