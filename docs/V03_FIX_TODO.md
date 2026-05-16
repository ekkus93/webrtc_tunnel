# V03_FIX_TODO.md

# Rust WebRTC Tunnel v0.3 Fix TODO — Multi-Session Answer Daemon Hardening

## Goal

Finish the v0.3 multi-session answer-daemon implementation by fixing authenticated routing, stale event isolation, status correctness, replacement semantics, stale docs, and missing tests.

This is **not** a redesign.

The v0.3 product model remains:

```text
one p2p-answer daemon
many concurrent authorized p2p-offer peers
one active WebRTC session per peer_id
one reliable ordered data channel per session
many multiplexed logical TCP streams per session
```

## Non-negotiable rules

- Do not change the MQTT signaling wire format unless absolutely required.
- Do not change the tunnel frame format.
- Do not change the public config shape.
- Do not add TURN.
- Do not add arbitrary target selection by the offer side.
- Do not weaken encrypted/signed signaling.
- Do not trust MQTT broker metadata.
- Do not route messages by unauthenticated outer-envelope fields.
- Do not allow one stale session event to mutate another active session.
- Do not allow more than one active unrelated session per `peer_id`.
- Do not let one session failure tear down unrelated sessions.

---

# Task 0 — Baseline verification

## 0.1 Read the relevant docs

Read:

- `docs/V03_SPEC.md`
- `docs/V03_TODO.md`
- `docs/V03_CODE_REVIEW.md`
- this TODO file

Understand the intended model:

- many offer peers,
- one answer daemon,
- one active session per peer ID,
- one multiplexed tunnel runtime per session,
- centralized MQTT loop,
- authenticated routing by session ID,
- session-local failure isolation.

## 0.2 Verify current architecture remains intact

Before making changes, confirm:

- existing v2 tunnel frame format remains unchanged,
- multiplexed stream runtime remains session-local,
- `OpenPayload { forward_id }` remains unchanged,
- answer side still owns target mapping,
- MQTT signaling remains encrypted and signed,
- no TURN support is introduced,
- no custom data encryption is added for tunnel data frames.

Do not proceed if any of these have regressed.

---

# Task 1 — Fix authenticated routing before session dispatch

Status: complete.

## 1.1 Locate current active-session routing fast path

Find code in the answer daemon that:

1. decodes `OuterEnvelope`,
2. reads `sender_kid`,
3. maps it to an authorized peer,
4. routes the raw MQTT payload into an active session queue based on `peer_id`.

This routing is unsafe and must be removed or replaced.

## 1.2 Add a safe authenticated decode/routing step

The answer daemon must authenticate/decrypt/validate incoming MQTT payloads before choosing a session.

Required decoded routing metadata:

```rust
pub struct AuthenticatedSignal {
    pub sender_peer_id: PeerId,
    pub recipient_peer_id: PeerId,
    pub session_id: SessionId,
    pub msg_id: MsgId,
    pub body: SignalBody,
    pub ack_required: bool,
}
```

Exact names may differ.

The important requirement:

```text
session_id and sender_peer_id used for routing must come from authenticated/decrypted/validated signaling data.
```

Do not route by unauthenticated `OuterEnvelope.sender_kid`.

## 1.3 Avoid double-decode pitfalls

If the daemon decodes/authenticates a message before routing, avoid making the session task decode the same raw payload again in a way that breaks replay handling.

Acceptable designs:

### Option A — Decode centrally and pass typed signal to session

The daemon loop decodes the payload and sends `AuthenticatedSignal` to the session task.

Recommended if it fits the current code.

### Option B — Authenticated peek with replay-safe handoff

The daemon loop performs a safe authenticated peek that validates and extracts sender/session metadata, then the session performs final processing without double-counting replay state.

Only use this if central typed dispatch is too invasive.

## 1.4 Replay handling must remain correct

Ensure replay caches are not accidentally bypassed or double-consumed.

Rules:

- duplicate MQTT messages must still be suppressed,
- retransmitted ACK-required messages must still behave correctly,
- stale old-session messages must not create or mutate new session state.

If replay cache ownership needs to move to the daemon-level routing layer, make that explicit.

## 1.5 Route by authenticated session ID

After successful authentication/decryption:

1. If `session_id` exists in `sessions_by_id`, route to that session.
2. If `session_id` does not exist and message type is `offer`, evaluate as new-session candidate.
3. If `session_id` does not exist and message type is not `offer`, ignore/reject per existing protocol rules.
4. If session exists but authenticated sender does not match the session's remote peer, reject/ignore.

## 1.6 Tests

Add tests proving:

- forged outer `sender_kid` cannot route a payload into another peer's active session,
- authenticated known `session_id` routes to the correct session,
- authenticated unknown non-offer is ignored/rejected,
- authenticated new `offer` can create a session,
- stale old-session message does not mutate current session,
- duplicate/replayed message is still handled correctly.

---

# Task 2 — Fix stale session event isolation

Status: complete.

## 2.1 Locate generic status event handling

Find answer daemon event handling that processes per-session status events.

Look for fallback logic like:

```rust
if sessions_by_id.get_mut(&status.session_id).is_none() {
    session_by_peer.get(&status.remote_peer_id)
    ...
}
```

This fallback is unsafe.

## 2.2 Remove fallback-by-peer registry mutation

Generic status events must only update an existing session by exact `session_id`.

Rules:

- If `status.session_id` exists, update that session's status.
- If it does not exist, ignore the event as stale.
- Do not use `remote_peer_id` to re-key `sessions_by_id`.
- Do not remove/reinsert another session from a generic `Status` event.

## 2.3 Add explicit replacement event if needed

If same-peer pending replacement needs to remap registry entries, create an explicit event:

```rust
AnswerSessionEvent::SessionReplaced {
    old_session_id: SessionId,
    new_session_id: SessionId,
    remote_peer_id: PeerId,
    generation: u64,
    status: SessionStatusSnapshot,
}
```

or perform replacement synchronously in the registry owner before spawning the new session.

Do not hide replacement inside a generic `Status` event.

## 2.4 Add session generation if useful

If stale async tasks can still race with newer sessions, add a generation token:

```rust
struct AnswerSessionHandle {
    session_id: SessionId,
    remote_peer_id: PeerId,
    generation: u64,
    ...
}
```

Events should include the generation that produced them. If generation mismatches, ignore as stale.

This is optional if exact `session_id` handling is enough.

## 2.5 Tests

Add tests proving:

- status event for unknown old `session_id` is ignored,
- stale status from old same-peer session cannot re-key current session,
- stale `Ended` event from old session cannot remove current session,
- stale callback from peer A cannot mutate peer B,
- repeated teardown is safe.

---

# Task 3 — Freeze and enforce same-peer replacement semantics

Status: complete.

## 3.1 Document exact v0.3 policy

Freeze this policy in code comments and docs:

1. Duplicate/retransmitted message for existing session follows normal replay/dedupe behavior.
2. Same-peer new offer while existing session is pending/not yet data-channel active may replace that pending session.
3. Same-peer new unrelated offer while existing session is active receives encrypted `busy`.
4. Same-peer reconnect/renegotiation for an active session is allowed only through the existing session-local reconnect flow.
5. Same-peer different `session_id` with no valid replacement context receives encrypted `busy`.

## 3.2 Rename vague replacement helpers

Rename broad/vague helpers such as:

```rust
maybe_replace_pending_answer_session_via_events
```

to a name like:

```rust
maybe_replace_pending_same_peer_session
```

The name should make it obvious that this is pending-session replacement only.

## 3.3 Enforce one active session per peer

Ensure `session_by_peer` prevents unrelated second active sessions for the same `peer_id`.

Do not rely only on test behavior; make the policy explicit in admission code.

## 3.4 Ensure replacement is peer/session scoped

A replacement for peer A must not:

- remove peer B's session,
- pause peer B's session,
- modify peer B's status,
- reset peer B's stream runtime,
- clear daemon-wide session state.

## 3.5 Tests

Add tests proving:

- pending same-peer replacement is allowed,
- active same-peer unrelated offer gets encrypted `busy`,
- same-peer replacement does not affect peer B,
- peer B remains active while peer A replaces a pending session,
- same-peer duplicate/retransmission does not create a second session.

---

# Task 4 — Fix status correctness

Status: complete.

## 4.1 Audit status fields

Audit all status structs and output paths:

- daemon status file,
- `p2pctl status`,
- per-session status snapshots,
- tests that construct or assert status.

Identify fields that are fake or misleading, especially:

- `active_stream_count`,
- `open_forward_ids`,
- `current_state`.

## 4.2 Fix `active_stream_count`

Choose one:

### Option A — Make it real

Expose actual active stream count from the multiplex runtime.

Example:

```rust
pub struct MultiplexRuntimeStatus {
    pub active_stream_count: usize,
    pub open_forward_ids: Vec<String>,
}
```

Each answer session should periodically or eventfully update its `SessionStatusSnapshot`.

### Option B — Remove it for now

If real stream status is too invasive, remove `active_stream_count` from public status until it is real.

Do not hardcode it to `0`.

## 4.3 Fix `open_forward_ids`

Choose one:

### If this means active/open forward IDs

Populate it only with forwards currently used by active streams.

### If this means configured forwards

Rename it:

```rust
configured_forward_ids
```

or:

```rust
available_forward_ids
```

Do not call configured forwards "open" forwards.

## 4.4 Fix daemon-level `current_state`

If `active_session_count > 0`, status must not report a misleading idle/no-session state.

Acceptable fixes:

- add `Serving` / `Active` / `MultiSessionActive`,
- or define `current_state` clearly and adjust display wording in `p2pctl`.

Recommended:

```rust
DaemonState::Serving
```

for answer daemon running with zero or more possible sessions, plus aggregate fields to show active count.

## 4.5 Update `p2pctl status`

Update CLI output to match corrected fields.

Required behavior:

- zero sessions: readable and clear,
- one session: readable and clear,
- multiple sessions: readable and clear,
- stream/forward fields must not mislead operators.

## 4.6 Tests

Add tests proving:

- status with zero sessions is correct,
- status with one session is correct,
- status with multiple sessions is correct,
- `active_session_count` matches session list length,
- status does not say idle/no-session when sessions are active,
- `active_stream_count` is either real or absent,
- forward IDs field name matches semantics.

---

# Task 5 — Fix canonical documentation

Status: complete.

## 5.1 Update canonical specs

Update:

- `docs/SPECS.md`
- `docs/V03_SPEC.md` if needed
- README if needed

Remove or correct stale text saying:

- one active peer tunnel session at a time,
- answer daemon has one global busy session,
- answer daemon returns to idle after one session,
- offer closes session when all streams close,
- v0.3 is single-session.

## 5.2 Document v0.3 runtime model

Canonical docs must clearly state:

```text
one answer daemon may serve multiple simultaneous authorized offer peers
one active session per peer_id
each session has its own WebRTC peer connection and data channel
each session has its own multiplexed stream space
per-forward authorization remains answer-side and session-local
```

## 5.3 Document same-peer policy

Document:

- pending same-peer replacement allowed,
- active same-peer unrelated second session rejected with encrypted `busy`,
- same-peer replacement does not disturb unrelated peers.

## 5.4 Document status semantics

Update docs to describe corrected status fields:

- daemon service state,
- active session count,
- session capacity,
- per-session remote peer,
- per-session state,
- stream/forward status fields with honest names.

## 5.5 Docs stale search

Search current docs for:

```text
one active peer tunnel session
single active session
global busy
single-session
one session at a time
idle after session
```

Historical review docs may retain old language if clearly historical. Canonical docs must not.

---

# Task 6 — Add missing multi-session tests

Status: complete.

## 6.1 Same-peer busy test

Add an integration or high-level unit test:

1. Peer A establishes an active session.
2. Peer A sends a new unrelated offer with a different `session_id`.
3. Answer daemon returns encrypted `busy`.
4. Original peer A session remains active.

## 6.2 Pending replacement isolation test

Add a test:

1. Peer A has a pending/not-yet-active session.
2. Peer B has an active healthy session.
3. Peer A sends valid pending replacement.
4. Peer A's pending session is replaced.
5. Peer B remains active and unaffected.

## 6.3 Stale callback/status test

Add a test:

1. Peer A session old ID exists and is closed/replaced.
2. Peer A new session is active.
3. Old session emits late `Status` or `Ended` event.
4. Registry ignores the stale event.
5. New session remains registered.

## 6.4 Authenticated routing test

Add a test:

1. Peer A and Peer B both active.
2. A forged payload with outer `sender_kid = Peer A` but invalid signature/body is published.
3. Payload is not routed into Peer A's active session queue based only on outer metadata.
4. Peer A session remains healthy.

## 6.5 Replay/duplicate with many sessions

Add tests proving:

- duplicate signaling for session A does not affect session B,
- replayed old session message is ignored,
- replay cache remains correct with multiple sessions.

## 6.6 Per-forward allowlist across sessions

Add test:

1. Peer A and Peer B both have sessions.
2. Forward `ssh` allows Peer A only.
3. Forward `web-ui` allows Peer B only.
4. Peer A can open `ssh` but not `web-ui`.
5. Peer B can open `web-ui` but not `ssh`.
6. Denial in one session does not affect the other.

## 6.7 Failure isolation regression tests

Add tests for:

- session-local ACK timeout while another session remains active,
- session-local remote `close` while another session remains active,
- session-local remote `error` while another session remains active,
- session-local reconnect failure while another session remains active,
- stream-level target connect failure while another session remains active.

---

# Task 7 — Clean up `V03_TODO.md` completion status

Status: complete.

## 7.1 Correct overstated checklist items

If a test or behavior is not implemented, do not mark it complete.

Update `docs/V03_TODO.md` or add a new status note so future readers can tell which v0.3 items are truly done.

## 7.2 Link to this fix TODO

Add a note that the initial v0.3 implementation required this hardening pass.

## 7.3 Keep historical docs historical

Do not edit old review docs just to erase history. But make sure canonical docs and active TODO/status docs do not mislead.

---

# Task 8 — Validation commands

Status: complete.

Run the following locally before marking this complete:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

Also run targeted tests while developing:

```bash
cargo test -p p2p-daemon
cargo test -p p2p-tunnel
cargo test -p p2p-signaling
cargo test -p p2pctl
```

If any command cannot be run, document why and do not mark the task complete.

---

# Task 9 — Acceptance checklist

Mark this fix pass complete only when all items below are true.

## Authenticated routing

- [x] Answer daemon does not route active-session messages by unauthenticated outer `sender_kid`.
- [x] Routing uses authenticated/decrypted `session_id`.
- [x] Forged outer envelope metadata cannot choose a session queue.
- [x] Replay handling remains correct after routing changes.

## Stale event isolation

- [x] Generic status events cannot re-key sessions by `remote_peer_id`.
- [x] Stale status from old session is ignored.
- [x] Stale ended event from old session cannot remove newer session.
- [x] Same-peer replacement uses explicit, safe logic.

## Same-peer policy

- [x] Pending same-peer replacement is allowed.
- [x] Active same-peer unrelated offer gets encrypted `busy`.
- [x] Same-peer replacement does not affect unrelated peers.
- [x] One active unrelated session per `peer_id` is enforced.

## Status

- [x] `active_session_count` is accurate.
- [x] Daemon state is not misleading when sessions are active.
- [x] `active_stream_count` is real or removed.
- [x] Forward ID status field names match semantics.
- [x] `p2pctl status` displays zero/one/many sessions correctly.

## Docs

- [x] Canonical docs no longer claim one active peer tunnel session at a time.
- [x] v0.3 runtime model is documented.
- [x] same-peer replacement policy is documented.
- [x] status semantics are documented.

## Tests

- [x] same-peer active duplicate/unrelated offer busy test exists.
- [x] pending replacement with peer B active test exists.
- [x] stale callback/status event test exists.
- [x] authenticated routing/forged outer metadata test exists.
- [x] replay with multiple sessions test exists.
- [x] per-forward allowlist across sessions test exists.
- [x] failure isolation across sessions tests exist.

## Validation

- [x] `cargo fmt --check` passes.
- [x] `cargo clippy --workspace --all-targets --all-features -- -D warnings` passes.
- [x] `cargo test --workspace --all-targets` passes.

---

# Suggested implementation order

1. Fix authenticated routing before session dispatch.
2. Fix stale status/event registry mutation.
3. Freeze and enforce same-peer replacement semantics.
4. Fix status correctness.
5. Update canonical docs.
6. Add missing multi-session tests.
7. Correct TODO completion status.
8. Run fmt, clippy, and full tests.

Do not start by adding config fields or changing wire formats. The problem is runtime correctness, not protocol design.
