# MULTIPLEXED_FORWARDING_FIX_TODO.md

# Secure Rust WebRTC Tunnel — Multiplexed Forwarding Fix TODO

## Goal

Finish the multiplexed forwarding implementation by fixing lifecycle, failure-propagation, and documentation gaps found after the initial v2 multiplexing refactor.

This is **not** a redesign. The v2 architecture remains:

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

The purpose of this TODO is to harden the current implementation so it is safe as a long-running daemon.

## Non-negotiable rules

- Do not reintroduce the old single-stream tunnel model.
- Do not reintroduce `ACTIVE_STREAM_ID = 1`.
- Do not reintroduce single `listen_port` / `target_port` config.
- Do not add custom app-layer encryption for data frames.
- Do not add TURN support in this pass.
- Do not let the offer side choose arbitrary target host/port.
- Keep stream failures isolated unless the underlying data channel/session fails.
- Treat data-channel writer failure as session-level failure.
- Ensure all stream tasks are cancelled on stream/session shutdown.

## Status

| Item | Status | Notes |
| --- | --- | --- |
| 0.1 Read relevant docs | Done | Review, TODO, responses, and replies were read. |
| 0.2 Stale assumption search | Done | Classified stale docs/comments, first-forward overrides, and runtime ownership gaps. |
| 1.1 Audit stream task spawning | Done | Stream task spawning is centralized in the multiplex runtime. |
| 1.2 Add stream cancellation primitive | Done | Runtime streams now retain task handles. |
| 1.3 Stream close cancels both halves | Done | Stream close stops read/write tasks and removes stream state. |
| 1.4 Session failure cancels streams | Done | Multiplex loop teardown clears stream state and aborts owned tasks. |
| 1.5 Late frames for closed streams | Done | Late/unknown `DATA`, duplicate `CLOSE`, and duplicate `ERROR` are harmless. |
| 1.6 Stream lifecycle tests | Done | Added cancellation and late-frame tests. |
| 2.1 Identify runtime writer path | Done | Daemon path uses the multiplex writer in `run_multiplex_offer` / `run_multiplex_answer`. |
| 2.2 Writer failure notification | Done | Writer send/encode failures are reported through a failure channel. |
| 2.3 Session observes writer failure | Done | Offer and answer multiplex loops select on writer failure. |
| 2.4 Avoid silent writer exit | Done | Unexpected writer encode/send failure produces a session-level error. |
| 2.5 Writer failure tests | Partial | Added writer failure notification coverage; higher-level teardown coverage remains in Task 8. |
| 3.1 Audit answer `OPEN` handling | Done | Inline answer-side `TcpStream::connect` was confirmed and replaced. |
| 3.2 Move target connect to task | Done | Answer `OPEN` now registers `Opening` and spawns target connect. |
| 3.3 Target connect timeout | Done | Added hardcoded internal 10-second target-connect timeout. |
| 3.4 Preserve stream isolation | Done | Dispatcher returns immediately while target connect is pending. |
| 3.5 Target connect tests | Partial | Added non-blocking dispatcher coverage; full timeout/concurrency coverage remains in Task 8. |
| 4.1 Freeze `OPEN` ACK rule | Done | Success ACK remains empty `OPEN(stream_id)`. |
| 4.2 Offer-side ACK handling | Done | Non-empty `OPEN` ACK is rejected as `protocol_error`. |
| 4.3 Answer-side ACK generation | Done | Answer side uses empty `TunnelFrame::open_ack`. |
| 4.4 ACK tests | Done | Added empty, duplicate-empty, and non-empty ACK coverage. |
| 5.1 Remove stale TURN examples | Pending |  |
| 5.2 Remove old flat/dummy forward examples | Pending |  |
| 5.3 Update migration docs | Pending |  |
| 5.4 Update comments | Pending |  |
| 5.5 Parse-test documented configs | Pending |  |
| 6.1 Audit overrides | Pending |  |
| 6.2 Remove first-forward overrides | Pending |  |
| 6.3 Keep safe global overrides | Pending |  |
| 6.4 Forward-scoped overrides | Pending | Not planned for this pass. |
| 6.5 Override tests | Pending |  |
| 7.1 Identify competing abstractions | Pending |  |
| 7.2 Pick runtime owner | Pending |  |
| 7.3 Make ownership explicit | Pending |  |
| 7.4 Remove dead runtime path | Pending |  |
| 7.5 Runtime ownership tests | Pending |  |
| 8.1 Multi-forward concurrent behavior | Pending |  |
| 8.2 Multiple streams on one forward | Pending |  |
| 8.3 Browser-like connection pattern | Pending |  |
| 8.4 Target-connect isolation | Pending |  |
| 8.5 Writer failure behavior | Pending |  |
| 8.6 Session failure cleanup | Pending |  |
| 8.7 Docs/sample parse tests | Pending |  |
| 9.1 Final stale search | Pending |  |
| 9.2 Remove obsolete code/imports | Pending |  |
| 9.3 Update docs index/README links | Pending |  |
| 9.4 Confirm no custom data encryption | Pending |  |
| 10 Acceptance checklist | Pending |  |

---

# Task 0 — Baseline review and repository search

## 0.1 Read the relevant docs

Read:

- `docs/MULTIPLEXED_FORWARDING_SPEC.md`
- `docs/MULTIPLEXED_FORWARDING_TODO.md`
- `docs/MULTIPLEXED_FORWARDING_CODE_REVIEW.md`
- this file

Confirm the final v2 decisions:

- `format = "p2ptunnel-config-v2"`
- tunnel frame `version = 2`
- no TURN
- role-specific `[forwards.offer]` / `[forwards.answer]`
- `stream_id = 0` reserved
- answer-side `OPEN` ACK is empty payload
- answer side owns target mapping

## 0.2 Search for stale single-stream and stale doc assumptions

Search for:

```text
ACTIVE_STREAM_ID
single-stream
single stream
stream_id == 1
stream_id != 1
listen_port
target_port
turn_urls
turn:
turns:
TunnelBridge
```

Classify each occurrence as:

- valid v2 usage,
- stale comment/doc,
- obsolete code,
- test fixture requiring update.

Do not leave stale comments claiming the runtime is single-stream.

---

# Task 1 — Deterministic per-stream task ownership and cancellation

## 1.1 Audit current stream task spawning

Find every place where a stream-level task is spawned, including:

- offer-side local TCP read loop,
- offer-side local TCP write loop,
- answer-side target TCP read loop,
- answer-side target TCP write loop,
- answer-side target-connect task if present,
- any per-stream queue forwarder task.

For each task, document:

- who owns the task handle,
- how it is cancelled,
- what happens if the stream closes,
- what happens if the session fails.

## 1.2 Add stream cancellation primitive

Add a cancellation mechanism for each active stream.

Acceptable approaches:

- `tokio_util::sync::CancellationToken`, or
- stored `JoinHandle`s that are aborted on close, or
- both.

Preferred structure:

```rust
pub struct RuntimeStream {
    pub stream_id: StreamId,
    pub forward_id: String,
    pub state: StreamLifecycle,
    pub cancel: CancellationToken,
    pub tasks: Vec<JoinHandle<()>>,
    ...
}
```

If `tokio-util` is not currently a dependency and adding it is undesirable, use stored `JoinHandle`s and explicit aborts.

## 1.3 Ensure stream close cancels both halves

Update stream close/fail logic so that closing a stream:

- transitions lifecycle to closing/closed/failed,
- cancels/aborts the TCP read task,
- cancels/aborts the TCP write task,
- closes or drops socket halves,
- drains/removes per-stream queues,
- removes the stream from the stream map.

Do not leave a task blocked on `TcpStream::read()` after the stream is logically closed.

## 1.4 Ensure session failure cancels all streams

Update session teardown logic so that WebRTC/data-channel/session failure:

- stops listeners,
- closes all active streams,
- cancels all per-stream tasks,
- closes writer queue,
- waits briefly for tasks to finish or aborts them,
- clears the stream map.

## 1.5 Handle late frames for closed streams

Define and implement behavior for frames arriving after stream close.

Recommended v2 behavior:

- `DATA` for closed/unknown stream: ignore or send `ERROR(stream_not_found)` at most once.
- duplicate `CLOSE`: ignore.
- duplicate `ERROR`: ignore.
- duplicate `OPEN` for active stream: send `ERROR(stream_already_exists)`.

Avoid resurrecting closed streams.

## 1.6 Tests

Add tests proving:

- closing one stream cancels its read/write tasks,
- session failure cancels all stream tasks,
- closing stream A does not close stream B,
- late `DATA` after stream close does not panic,
- duplicate `CLOSE` is harmless,
- duplicate `ERROR` is harmless.

Use test-only task hooks/channels if needed to prove cancellation deterministically.

---

# Task 2 — Central writer failure propagation

## 2.1 Identify the actual runtime writer path

Find the writer path actually used by the daemon/session runtime.

Important: do not only fix a helper abstraction that is not used by `run_multiplex_offer` / `run_multiplex_answer`.

Document:

- where frames enter the central writer queue,
- where frames are encoded,
- where `data_channel.send()` is called,
- how send errors are currently handled.

## 2.2 Add explicit writer failure notification

The writer task must notify the session on send failure.

Add something like:

```rust
pub enum TunnelRuntimeEvent {
    WriterFailed(String),
    ...
}
```

or:

```rust
writer_error_tx: mpsc::Sender<TunnelError>
```

If `data_channel.send()` fails, the writer must send a failure event before exiting.

## 2.3 Session loop must select on writer failure

Update offer and answer session loops to observe writer failure.

Required behavior:

- writer failure is session-level failure,
- all streams are closed/cancelled,
- listeners are stopped,
- daemon returns to normal recovery/waiting behavior.

Do not treat writer failure as a single-stream error.

## 2.4 Avoid silent writer task exit

The writer must not silently return on:

- closed data channel,
- encoding error,
- send error,
- writer queue closed unexpectedly during active session.

All unexpected exits should produce a visible session-level event/log.

## 2.5 Tests

Add tests proving:

- data-channel send failure triggers writer failure event,
- writer failure tears down the session,
- writer failure closes all active streams,
- writer failure stops listeners,
- writer failure does not leave stream tasks running.

If real WebRTC is hard to test, use a mockable writer trait.

---

# Task 3 — Make answer-side target connect non-blocking for the dispatcher

## 3.1 Audit `OPEN` handling on the answer side

Find the handler for:

```text
OPEN(stream_id, OpenPayload { forward_id })
```

Confirm whether `TcpStream::connect()` is performed inline in the frame dispatcher.

## 3.2 Move target connect into per-stream task

The frame dispatcher must not block on target connect.

Recommended behavior:

1. receive `OPEN`,
2. validate stream ID,
3. validate no duplicate active stream,
4. parse `forward_id`,
5. lookup target in `ForwardTable`,
6. verify remote peer is allowed,
7. reserve/register stream in `Opening`,
8. spawn target-connect task,
9. immediately return to dispatcher loop.

The connect task should:

1. attempt target connect,
2. on success, register socket halves / update state,
3. send empty `OPEN(stream_id)` ACK,
4. start target read/write tasks,
5. on failure, send `ERROR(stream_id, target_connect_failed)`,
6. close/remove only that stream.

## 3.3 Add target connect timeout

Add a hardcoded or config-backed timeout for target connect.

For v2, a hardcoded internal default is acceptable, for example:

```text
target_connect_timeout_secs = 10
```

Do not add a new public config knob unless it is fully implemented and tested.

## 3.4 Preserve stream isolation

While stream X is connecting, the dispatcher must still process:

- `DATA` for other open streams,
- `CLOSE` for other streams,
- `ERROR` for other streams,
- `OPEN` for other streams.

## 3.5 Tests

Add tests proving:

- slow target connect for stream A does not block DATA for stream B,
- target connect timeout sends `ERROR(target_connect_failed)` for stream A,
- stream B remains open after stream A connect timeout,
- multiple target connects can be pending concurrently.

---

# Task 4 — Enforce strict `OPEN` ACK semantics

## 4.1 Freeze success ACK rule

The answer-side success ACK is:

```text
OPEN(stream_id) with empty payload
```

No `{ "ok": true }`.
No `OpenPayload`.
No arbitrary data.

## 4.2 Update offer-side ACK handling

On the offer side, when receiving `OPEN(stream_id, payload)`:

- if stream is not in `Opening`, handle as duplicate/unexpected according to policy,
- if payload is empty, transition stream to `Open`,
- if payload is non-empty, treat as protocol error.

Recommended behavior for non-empty ACK payload:

- fail that stream with `protocol_error`,
- optionally send `ERROR(stream_id, protocol_error)`,
- do not mark stream open.

## 4.3 Update answer-side ACK generation

Ensure answer side sends exactly:

```rust
TunnelFrame::open(stream_id, Bytes::new())
```

or equivalent empty payload.

## 4.4 Tests

Add tests for:

- empty `OPEN` ACK succeeds,
- non-empty `OPEN` ACK fails,
- duplicate empty `OPEN` ACK does not corrupt state,
- `OPEN` ACK for unknown stream is ignored or protocol-error according to policy.

---

# Task 5 — Docs and sample config cleanup

## 5.1 Remove stale TURN examples

Remove `turn_urls = []` from:

- `docs/MULTIPLEXED_FORWARDING_SPEC.md`,
- README,
- sample configs,
- any migration docs.

v2 remains STUN-only / no TURN.

## 5.2 Remove old flat/dummy forward examples

Replace any examples like:

```toml
[[forwards]]
id = "ssh"
listen_host = "127.0.0.1"
listen_port = 2223
target_host = ""
target_port = 0
allow_remote_peers = []
```

with role-specific examples.

Offer example:

```toml
[[forwards]]
id = "ssh"

[forwards.offer]
listen_host = "127.0.0.1"
listen_port = 2223
```

Answer example:

```toml
[[forwards]]
id = "ssh"

[forwards.answer]
target_host = "127.0.0.1"
target_port = 22
allow_remote_peers = ["laptop"]
```

## 5.3 Update migration docs

Old v1:

```toml
[tunnel.offer]
listen_host = "127.0.0.1"
listen_port = 2223
remote_peer_id = "answer-office"

[tunnel.answer]
target_host = "127.0.0.1"
target_port = 22
allow_remote_peers = ["laptop"]
```

New v2 offer:

```toml
[peer]
remote_peer_id = "answer-office"

[[forwards]]
id = "ssh"

[forwards.offer]
listen_host = "127.0.0.1"
listen_port = 2223
```

New v2 answer:

```toml
[[forwards]]
id = "ssh"

[forwards.answer]
target_host = "127.0.0.1"
target_port = 22
allow_remote_peers = ["laptop"]
```

## 5.4 Update comments

Remove stale comments saying:

- "single stream",
- "single-use, single-stream",
- "active stream ID 1",
- "SSH-only",
- "STUN/TURN" if TURN is not supported.

## 5.5 Parse-test documented configs

Add tests that parse every sample config in the repo.

At minimum:

- sample offer config,
- sample answer config,
- README offer snippet if extracted to file,
- README answer snippet if extracted to file,
- migration examples if they are maintained as files.

If inline README snippets are not parse-tested, add a note in docs and make sure sample config files are authoritative.

---

# Task 6 — CLI/env override cleanup for v2

## 6.1 Audit all CLI flags and environment overrides

Search for overrides related to:

- listen host,
- listen port,
- target host,
- target port,
- allow remote peers,
- remote peer ID.

Identify any override that mutates only the first configured forward.

## 6.2 Remove first-forward-only overrides

For v2, remove overrides that silently modify only the first forward.

Do not keep behavior like:

```text
--listen-port 2223
```

if it means "change forwards[0].offer.listen_port".

That is misleading in a multi-forward config.

## 6.3 Keep safe global overrides

It is acceptable to keep global overrides such as:

- config path,
- log level,
- broker URL if already supported,
- identity path,
- authorized keys path.

Do not remove useful global daemon flags.

## 6.4 Optional: add explicit forward-scoped override later

Do not implement this unless needed now.

Future possible syntax:

```text
--forward ssh.listen-port=2223
--forward web-ui.listen-host=127.0.0.1
```

But for this pass, removal of misleading single-forward overrides is preferred.

## 6.5 Tests

Add tests proving:

- removed flags are no longer accepted, or
- removed env vars are ignored/rejected with clear error,
- global flags still work,
- v2 config is not silently mutated at `forwards[0]`.

---

# Task 7 — Clarify and consolidate multiplexed runtime ownership

## 7.1 Identify competing abstractions

Find whether the code currently has both:

- a `MultiplexedTunnel` manager type, and
- standalone `run_multiplex_offer` / `run_multiplex_answer` code that duplicates manager responsibilities.

## 7.2 Pick the real runtime owner

Choose one.

Preferred:

- `MultiplexedTunnel` or an equivalent manager owns stream map, writer, cancellation, and dispatch.

Acceptable:

- standalone runtime functions own these responsibilities, but then remove/deprecate unused manager abstraction.

## 7.3 Make ownership explicit

There should be a clear owner for:

- stream ID allocator,
- stream map,
- stream lifecycle,
- stream task cancellation,
- writer queue,
- writer failure receiver,
- forward table lookup,
- frame dispatch,
- session teardown.

Add comments documenting this ownership in the chosen module.

## 7.4 Remove unused/dead runtime path

If a runtime abstraction is unused outside tests, either:

- remove it, or
- move it under `#[cfg(test)]`, or
- clearly mark it as test helper.

Do not leave two production-looking tunnel implementations.

## 7.5 Tests

Ensure all tests exercise the actual runtime path used by the daemon, not only an unused helper abstraction.

---

# Task 8 — Higher-level lifecycle and integration-style tests

## 8.1 Multi-forward concurrent behavior

Add tests proving:

- two configured forwards can be active in one session,
- two local listeners are created,
- each listener opens streams with the correct `forward_id`,
- data for `ssh` does not route to `web-ui`,
- data for `web-ui` does not route to `ssh`.

## 8.2 Multiple simultaneous streams on one forward

Add tests proving:

- two simultaneous local TCP clients to one forward get different stream IDs,
- both streams can carry data concurrently,
- closing stream A does not close stream B.

## 8.3 Browser-like connection pattern

Add a test or simulation with multiple short-lived concurrent streams to the same forward.

Required behavior:

- all streams get unique stream IDs,
- all complete or fail independently,
- no session-level failure occurs due to normal stream churn.

## 8.4 Target-connect isolation

Add tests proving:

- stream A target connect hangs or times out,
- stream B continues to send/receive data,
- stream A receives `target_connect_failed`,
- session remains alive.

## 8.5 Writer failure behavior

Add tests proving:

- writer send failure causes session-level failure,
- all streams close,
- all stream tasks cancel,
- daemon returns to recovery/waiting behavior.

## 8.6 Session failure cleanup

Add tests proving:

- WebRTC/data-channel failure closes all streams,
- all stream tasks are cancelled,
- listeners are stopped,
- stream map is empty after cleanup.

## 8.7 Docs/sample parse tests

Add tests proving sample configs parse and validate.

---

# Task 9 — Final repository cleanup

## 9.1 Run stale search again

After fixes, search again for:

```text
ACTIVE_STREAM_ID
single-stream
single stream
stream_id == 1
stream_id != 1
turn_urls
TunnelBridge
listen_port
target_port
```

Every remaining occurrence must be correct under v2.

## 9.2 Remove obsolete imports and dead code

Remove unused:

- structs,
- functions,
- enum variants,
- tests for deleted behavior,
- config fields,
- CLI flags,
- env var constants.

## 9.3 Update docs index / README links

Make sure README points to the current v2 docs.

## 9.4 Ensure no custom data encryption was added

Forwarded TCP payload data should still rely on WebRTC data-channel DTLS.

Do not add custom AEAD per `DATA` frame in this pass.

---

# Task 10 — Acceptance checklist

Mark the implementation complete only when all items below are true.

## Runtime lifecycle

- [ ] Every stream owns/cancels all per-stream tasks.
- [ ] Stream close cancels read/write halves.
- [ ] Session failure cancels all stream tasks.
- [ ] WebRTC/data-channel failure clears all streams.
- [ ] Late frames for closed streams are harmless.

## Writer

- [ ] All data-channel sends use the central writer path.
- [ ] Writer send failure notifies the session.
- [ ] Writer failure tears down the session.
- [ ] Writer failure cancels all streams.

## Target connect

- [ ] Answer-side target connect does not block dispatcher.
- [ ] Target connect has a timeout.
- [ ] Target connect failure is stream-scoped.
- [ ] One slow target does not block other streams.

## Protocol

- [ ] `OPEN` request payload contains only `forward_id`.
- [ ] Answer-side `OPEN` ACK has empty payload.
- [ ] Offer-side rejects non-empty `OPEN` ACK payload.
- [ ] `stream_id = 0` is rejected for stream frames.
- [ ] Unknown/forbidden forwards produce intended stream-level behavior.

## Config/docs

- [ ] `format = "p2ptunnel-config-v2"` required.
- [ ] Docs use role-specific `[forwards.offer]` / `[forwards.answer]`.
- [ ] No stale `turn_urls = []` examples remain.
- [ ] Old flat/dummy forward examples removed.
- [ ] Sample configs parse.
- [ ] First-forward-only CLI/env overrides removed or replaced.

## Tests

- [ ] Two forwards work concurrently.
- [ ] Multiple streams on one forward work concurrently.
- [ ] Browser-like concurrent streams work.
- [ ] Target connect failure affects only one stream.
- [ ] Writer failure tears down session.
- [ ] Session failure cancels all stream tasks.
- [ ] Docs/sample configs are parse-tested.

---

# Suggested implementation order

Use this order:

1. Audit task spawning and stream ownership.
2. Add cancellation/task ownership to stream state.
3. Implement session-wide stream cancellation.
4. Fix writer failure propagation.
5. Move answer target connect out of dispatcher.
6. Enforce empty `OPEN` ACK payload.
7. Clean docs/sample configs.
8. Remove first-forward-only CLI/env overrides.
9. Consolidate runtime ownership.
10. Add high-level lifecycle tests.
11. Final stale-search cleanup.

Do not start with documentation only. The highest-priority correctness work is task cancellation and writer failure propagation.
