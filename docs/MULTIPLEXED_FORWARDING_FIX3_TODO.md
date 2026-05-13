# Secure Rust WebRTC Tunnel — Multiplexed Forwarding Fix 3 TODO

## Goal

Finish the remaining protocol-hardening and session-lifetime clarification work in the v2 multiplexed forwarding implementation.

This TODO is a **small follow-up pass**, not a redesign.

The current v2 architecture remains:

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

## Scope

This pass focuses on:

1. malformed answer-side `OPEN` request handling,
2. persistent-session policy after all streams close,
3. docs/comment cleanup,
4. focused tests,
5. local verification commands.

Do not rewrite the multiplexed runtime unless required for these items.

## Status

| Task | Status | Notes |
| --- | --- | --- |
| 0.1 Read current docs/code | Complete | Reviewed Fix 3 review/TODO, Fix 2 docs, replies, and current multiplex runtime. |
| 0.2 Confirm v2 architecture | Complete | Frame v2, nonzero stream IDs, `OpenPayload { forward_id }`, answer-owned targets, DTLS data transport, encrypted/signed MQTT signaling, timeout-backed target connect, central writer, and stream-local EOF/write failure remain intact. |
| 1.1 Locate answer-side `OPEN` handler | Complete | Malformed payload parsing was in `handle_answer_frame`. |
| 1.2 Define malformed `OPEN` behavior | Complete | Malformed answer-side `OPEN` is stream-level `protocol_error`. |
| 1.3 Implement stream-level handling | Complete | Answer handler catches `open_payload()` errors, emits `ERROR(protocol_error)`, and returns `Ok(())`. |
| 1.4 Preserve session and streams | Complete | Existing streams remain usable and future valid `OPEN` requests still work. |
| 1.5 Malformed `OPEN` tests | Complete | Added empty/invalid/missing/extra payload and isolation coverage. |
| 2.1 Decide persistent policy | Complete | `docs/replies8.md` freezes persistent sessions and closed accepted-client channel shutdown. |
| 2.2 Audit zero-stream behavior | Complete | Removed offer runtime zero-stream exit while accepted-client channel is open. |
| 2.3 Reuse session for future clients | Complete | Later accepted local clients can open new streams on the same data channel. |
| 2.4 Persistent-session tests | Complete | Added zero-stream persistence, later client reuse, and stream ID non-reuse coverage. |
| 3.1 Update multiplexing spec | Pending | Spec update remains. |
| 3.2 Update README if needed | Pending | README audit remains. |
| 3.3 Update runtime comments | Pending | Comment stale search remains. |
| 3.4 Add doc guard tests | Pending | Doc guard extension remains. |
| 4.1 Empty malformed `OPEN` test | Complete | Covered. |
| 4.2 Invalid malformed `OPEN` test | Complete | Covered. |
| 4.3 Missing `forward_id` test | Complete | Covered with `{}` payload. |
| 4.4 Stream B isolation test | Complete | Covered. |
| 4.5 Valid `OPEN` after malformed test | Complete | Covered. |
| 5.1 Zero streams does not close session | Complete | Covered. |
| 5.2 Later client opens new stream | Complete | Covered. |
| 5.3 Explicit session failure | Complete | Existing writer failure coverage remains valid. |
| 5.4 Data-channel failure | Complete | Existing data-channel/session failure cleanup coverage remains valid. |
| 6.1 Stale search | Pending | Final stale search remains. |
| 6.2 Formatting/clippy/tests | Pending | Full phase validation remains. |
| 6.3 Update TODO status/checklist | Pending | Final checklist remains. |
| 7 Acceptance checklist | Pending | Will be marked after docs, stale search, validation, commit, and push. |

---

## Non-negotiable rules

- Do not reintroduce the old single-stream tunnel model.
- Do not reintroduce `ACTIVE_STREAM_ID = 1`.
- Do not reintroduce single `listen_port` / `target_port` config.
- Do not add TURN support.
- Do not add custom app-layer encryption for data frames.
- Do not let the offer side choose arbitrary target host/port.
- Do not implement TCP half-close semantics in this pass.
- Do not close the whole session because one stream sends a malformed `OPEN`.
- Answer-side success ACK remains exactly empty `OPEN(stream_id)`.
- Offer-side `OPEN` request payload still contains only `forward_id`.

---

# Task 0 — Baseline verification

## 0.1 Read current docs and code

Read:

- `docs/MULTIPLEXED_FORWARDING_SPEC.md`
- `docs/MULTIPLEXED_FORWARDING_CODE_REVIEW2.md`
- `docs/MULTIPLEXED_FORWARDING_FIX2_TODO.md`
- `docs/MULTIPLEXED_FORWARDING_CODE_REVIEW3.md`
- this TODO file

Inspect the current runtime implementation, especially:

- `run_multiplex_offer`
- `run_multiplex_answer`
- answer-side frame dispatcher
- `OPEN` handling on answer side
- `OpenPayload` parsing
- stream map lifecycle
- stream close / cleanup logic
- zero-active-stream behavior
- local listener / accepted-client path on offer side

## 0.2 Confirm v2 architecture remains intact

Before making changes, verify:

- frame version is `2`,
- `stream_id = 0` is rejected for stream frames,
- `OpenPayload` contains only `forward_id`,
- answer side maps `forward_id` to configured target,
- forwarded data still relies on WebRTC DTLS,
- MQTT signaling remains encrypted/signed,
- target connect is spawned and timeout-backed,
- central writer path is still used,
- local EOF / write failure remain stream-local.

Do not proceed if any of these have regressed.

---

# Task 1 — Make malformed answer-side `OPEN` stream-local

## 1.1 Locate answer-side `OPEN` handler

Find the answer-side handling path for:

```text
OPEN(stream_id, payload)
```

Look for logic equivalent to:

```rust
let open = frame.open_payload()?;
```

or any path where malformed `OpenPayload` parsing can return an error that bubbles out of the frame handler/session loop.

## 1.2 Define malformed `OPEN` behavior

Freeze this behavior:

```text
Malformed answer-side OPEN request:
- is a stream-level protocol error,
- sends ERROR(stream_id, protocol_error) if possible,
- does not register/open the stream,
- does not tear down the WebRTC/data-channel session,
- does not affect other streams.
```

Malformed examples include:

- empty payload,
- invalid CBOR/JSON,
- missing `forward_id`,
- wrong payload type,
- extra fields if strict parsing is supported and enabled,
- otherwise unparsable `OpenPayload`.

## 1.3 Implement stream-level protocol error handling

Update the answer-side handler so malformed `OPEN` payloads do **not** use `?` in a way that causes session-level failure.

Pseudo-flow:

```rust
match frame.open_payload() {
    Ok(open) => {
        // existing valid OPEN handling
    }
    Err(err) => {
        // stream-local protocol error
        send ERROR(stream_id, protocol_error)
        cleanup attempted stream if needed
        return Ok(())
    }
}
```

If `stream_id = 0`, continue to reject according to existing frame validation policy.

## 1.4 Preserve session and other streams

After malformed `OPEN(stream_id=A)`:

- stream A should be failed/closed/ignored,
- stream B should remain usable,
- central writer should remain alive,
- data channel/session should remain alive,
- runtime should continue processing future frames.

## 1.5 Tests

Add tests proving:

- malformed answer-side `OPEN` does not return session-level error,
- malformed answer-side `OPEN` emits `ERROR(stream_id, protocol_error)`,
- malformed answer-side `OPEN` does not create/register an active stream,
- stream B remains open after malformed stream A,
- valid `OPEN` still works after a malformed `OPEN`.

---

# Task 2 — Freeze persistent-session policy

## 2.1 Decide and document the policy

Freeze the v2 policy as:

```text
Persistent-session policy:
After the last logical stream closes, the WebRTC peer connection and data channel remain open.
The offer daemon may accept future local clients and open new logical streams over the same existing data channel.
The session closes only on data-channel/WebRTC failure, explicit daemon shutdown, remote session close, writer/session failure, or fatal protocol/session error.
```

Do not automatically close the WebRTC session merely because the stream map becomes empty.

## 2.2 Audit zero-stream behavior

Search for logic that exits a multiplexed session solely because:

```text
active_stream_count == 0
streams.is_empty()
no active streams
```

Classify each occurrence as:

- valid explicit shutdown/session failure behavior,
- stale old behavior,
- test helper,
- comment/doc.

If code closes the session simply because the last stream closed, remove or adjust it to match persistent-session behavior.

## 2.3 Ensure future local clients can reuse the session

The offer-side runtime should allow this sequence:

```text
client A connects
stream 1 opens
stream 1 closes
stream map becomes empty
WebRTC/data channel remains open
client B connects later
stream 2 opens on same session
```

The stream ID allocator should continue monotonically and should not reuse stream ID `1` within the same session.

## 2.4 Tests

Add tests proving:

- after last stream closes, session/runtime remains alive,
- after stream 1 closes, a later accepted local client opens stream 2 on the same session,
- stream ID is not reused within the persistent session,
- zero active streams does not trigger session-level failure,
- explicit data-channel/writer failure still tears down the session.

---

# Task 3 — Update docs and comments

## 3.1 Update multiplexing spec

Update `docs/MULTIPLEXED_FORWARDING_SPEC.md` to state:

- malformed answer-side `OPEN` requests produce stream-level `protocol_error`,
- malformed answer-side `OPEN` does not kill the session,
- success ACK is exactly empty `OPEN(stream_id)`,
- after all streams close, the session remains alive for reuse,
- stream IDs are not reused within a session.

## 3.2 Update README if needed

If README describes runtime behavior, update it to match persistent-session policy.

README should not imply:

- one WebRTC session per TCP connection,
- one WebRTC session per stream,
- stream closure causes session closure,
- SSH-only behavior,
- TURN support.

## 3.3 Update runtime comments

Search current code comments for stale wording such as:

```text
single stream
single-use
one stream
return to idle after stream close
close session when no streams remain
```

Update current-runtime comments to match v2 persistent-session behavior.

Historical review docs may keep old wording if clearly historical.

## 3.4 Add doc guard tests if practical

If existing tests already scan docs for stale protocol text, extend them to guard against current docs reintroducing:

- `{ "ok": true }` ACK success payload,
- one-session-per-stream wording in current spec,
- automatic close-on-zero-stream wording in current spec.

Keep this practical. Do not over-engineer doc scanning.

---

# Task 4 — Focused tests for stream-local malformed `OPEN`

## 4.1 Malformed empty `OPEN` payload

Test:

```text
answer receives OPEN(stream_id=A, empty payload)
```

Expected:

- no panic,
- no session-level error,
- `ERROR(A, protocol_error)` emitted if writer available,
- stream A not registered/open,
- runtime continues.

## 4.2 Malformed invalid payload bytes

Test invalid encoded bytes.

Expected:

- stream-level `protocol_error`,
- session stays alive.

## 4.3 Missing `forward_id`

Test payload that decodes structurally but lacks valid `forward_id`, if the serialization layer allows such a case.

Expected:

- stream-level `protocol_error`,
- no target connect attempted,
- session stays alive.

## 4.4 Stream B isolation

Test:

```text
stream B is already open
malformed OPEN arrives for stream A
stream B can still receive DATA / remain active
```

Expected:

- stream B unaffected,
- session alive.

## 4.5 Valid `OPEN` after malformed `OPEN`

Test:

```text
malformed OPEN(A)
valid OPEN(B)
```

Expected:

- malformed A fails stream-locally,
- valid B opens normally.

---

# Task 5 — Focused tests for persistent-session policy

## 5.1 Zero streams does not close session

Test:

```text
stream 1 opens
stream 1 closes
active stream count becomes 0
```

Expected:

- runtime/session still alive,
- central writer still alive,
- data channel not closed by runtime solely due to zero streams.

## 5.2 Later client opens a new stream on same session

Test:

```text
stream 1 opens/closes
later accepted client opens stream 2
```

Expected:

- stream 2 opens on the same session,
- no fresh signaling required,
- stream ID is `2` or otherwise greater than previous ID,
- stream ID is not reused.

## 5.3 Explicit session failure still tears down session

Test:

```text
stream map is empty
writer failure occurs
```

Expected:

- session tears down,
- daemon recovery/waiting path is used.

## 5.4 Data-channel failure still closes everything

Test with active streams and zero-stream state if practical.

Expected:

- data-channel/WebRTC failure closes all streams,
- persistent-session policy does not prevent real session failure handling.

---

# Task 6 — Final verification and stale search

## 6.1 Run repository stale search

Search for:

```text
ACTIVE_STREAM_ID
stream_id == 1
stream_id != 1
single-stream
single stream
one stream
one session per stream
OPEN(stream_id, { "ok": true })
"ok": true
close when no streams
no active streams
```

Classify remaining occurrences:

- valid historical review/TODO docs,
- valid tests for rejected behavior,
- stale current docs/comments,
- obsolete code.

Do not leave stale current docs/comments.

## 6.2 Run formatting, clippy, and tests

Run:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
```

Fix any failures.

Do not mark this TODO complete until those commands pass locally.

## 6.3 Update TODO status/checklist

After implementation, update this TODO with real status.

Do not mark a task complete unless:

- code was changed or verified,
- tests were added or verified,
- local Rust checks passed.

---

# Task 7 — Acceptance checklist

Mark this TODO complete only when all items below are true.

## Malformed answer-side `OPEN`

- [ ] Empty answer-side `OPEN` request payload is stream-local `protocol_error`.
- [ ] Invalid answer-side `OPEN` request payload is stream-local `protocol_error`.
- [ ] Malformed answer-side `OPEN` does not kill session.
- [ ] Malformed answer-side `OPEN` does not register an active stream.
- [ ] Stream B remains usable after malformed stream A.
- [ ] Valid `OPEN` still works after malformed `OPEN`.

## Persistent-session policy

- [ ] Current docs explicitly state persistent-session policy.
- [ ] Zero active streams does not automatically close the WebRTC session.
- [ ] Future local client can open a new stream on the same session.
- [ ] Stream IDs are not reused within the same session.
- [ ] Explicit writer/data-channel/session failure still tears down the session.

## Docs/comments

- [ ] Current spec says success ACK is empty `OPEN(stream_id)` only.
- [ ] Current spec documents stream-local malformed `OPEN` handling.
- [ ] Current spec documents persistent session after all streams close.
- [ ] No current docs imply one WebRTC session per TCP stream.
- [ ] No current runtime comments claim single-stream behavior.

## Verification

- [ ] `cargo fmt --check` passes.
- [ ] `cargo clippy --workspace --all-targets -- -D warnings` passes.
- [ ] `cargo test --workspace` passes.

---

# Suggested implementation order

1. Fix answer-side malformed `OPEN` handling.
2. Add malformed `OPEN` tests.
3. Audit and freeze persistent-session behavior.
4. Add persistent-session tests.
5. Update spec/docs/comments.
6. Run stale searches.
7. Run fmt/clippy/tests.
8. Update TODO status.

Do not start by changing config or protocol shape. The v2 protocol shape is already correct; this pass is about hardening malformed stream requests and making session lifetime explicit.
