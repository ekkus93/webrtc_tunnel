# V03_CODE_REVIEW.md

# Rust WebRTC Tunnel v0.3 Code Review — Multi-Session Answer Daemon Hardening

## Purpose

This review covers the current v0.3 development version of the Rust WebRTC tunnel project.

v0.3 is intended to evolve the existing v2 multiplexed forwarding model from:

```text
one answer daemon
one active offer peer session
one WebRTC peer connection
one data channel
many multiplexed TCP streams
```

to:

```text
one answer daemon
many concurrent authorized offer peer sessions
one WebRTC peer connection per active offer peer
one reliable ordered data channel per session
many multiplexed TCP streams per session
```

This review is not asking for another redesign. The current v0.3 architecture is mostly the right direction. The remaining work is a hardening pass around authenticated routing, stale session events, status correctness, replacement semantics, missing tests, and stale documentation.

## High-level assessment

The implementation is a serious v0.3 attempt. It introduces a multi-session answer-side registry, preserves one centralized MQTT signaling loop, spawns per-session answer runtimes, and keeps the v2 multiplexed tunnel runtime session-local.

However, the implementation should not yet be considered complete. The main risks are exactly the kind that appear when moving from one active session to many sessions:

1. Some routing decisions are made using unauthenticated outer-envelope metadata.
2. Stale events from old sessions can mutate or re-key newer session entries.
3. Status output claims to expose stream/session information that is not fully accurate.
4. Same-peer replacement rules are not frozen tightly enough.
5. The integration test coverage is weaker than `V03_TODO.md` currently claims.
6. Canonical documentation still contains stale single-session assumptions.

## What is good

### 1. The v0.3 product direction is correct

The product model is right:

```text
one answer daemon
many offer peers
one active session per peer
many logical streams inside each session
```

This is the right next step after v2 multiplexed forwarding.

### 2. The config and wire format are mostly preserved

The implementation does not appear to require a new MQTT signaling wire format, tunnel frame format, or public config shape. That matches the v0.3 spec goal.

This is good because v0.3 should be primarily a daemon/runtime architecture change, not a crypto or protocol rewrite.

### 3. The answer daemon now has a real session registry

The code introduces the right general data model:

```rust
sessions_by_id: HashMap<SessionId, AnswerSessionHandle>
session_by_peer: HashMap<PeerId, SessionId>
```

That is the correct base shape for multiple simultaneous answer-side sessions.

### 4. One MQTT transport loop is preserved

The answer daemon keeps a single MQTT signaling transport loop rather than creating one MQTT client per session.

That is good. MQTT transport state, broker reconnect behavior, and local status should remain process-level concerns.

### 5. Session runtimes are separated

Each answer-side session gets its own runtime/task and owns its own WebRTC/data-channel/multiplexed tunnel lifecycle.

That is the right direction for failure isolation.

### 6. The multiplexed tunnel remains session-local

Each session owns its own data channel and multiplexed stream runtime. Stream IDs remain unique within a session, not globally across all sessions.

That matches the v0.3 spec.

### 7. There is meaningful concurrent-peer test coverage

There is evidence of integration-style coverage for multiple offer peers talking to one answer daemon, and at least some failure isolation coverage.

That is the right kind of testing for v0.3.

## Major issues

## P0 — Active-session routing uses unauthenticated outer-envelope metadata

### Problem

The answer daemon currently has a fast path that attempts to identify the sending peer from the outer signaling envelope before full authentication/decryption/validation, then routes the raw MQTT payload to that peer's active session queue.

The problematic pattern is conceptually:

```rust
if let Some(sender) = OuterEnvelope::decode(&payload)
    .ok()
    .and_then(|envelope| authorized_keys.get_by_kid(&envelope.sender_kid).cloned())
{
    if let Some(session_id) = session_by_peer.get(&sender.peer_id).copied() {
        if let Some(handle) = sessions_by_id.get(&session_id) {
            handle.inbound.send(payload).await;
            return;
        }
    }
}
```

This is unsafe design for a multi-session answer daemon.

The outer envelope contains routing metadata, but until the signature is verified and the inner payload is decrypted/validated, the daemon must not let that metadata choose the owning session.

### Why this matters

A malicious or buggy MQTT publisher can forge an outer `sender_kid` belonging to an active authorized peer. The session task will eventually reject the payload after verification fails, but the global answer loop has already routed attacker-chosen traffic into a particular session queue.

That creates avoidable risks:

- session queue flooding,
- unfair load against one active session,
- incorrect coupling between unauthenticated metadata and session ownership,
- violation of the spec's "authenticate/decrypt/validate before routing" rule.

### Required fix

Do not route based only on `OuterEnvelope.sender_kid`.

The answer daemon must:

1. Decode the payload enough to perform normal signaling validation.
2. Verify the Ed25519 signature.
3. Decrypt the inner signaling message.
4. Verify inner sender/recipient identity binding.
5. Extract authenticated `sender_peer_id` and `session_id`.
6. Route by authenticated `session_id`.

If the current `SignalCodec` API cannot support this cleanly, add a safe helper such as:

```rust
pub struct DecodedSignal {
    pub sender_peer_id: PeerId,
    pub recipient_peer_id: PeerId,
    pub session_id: SessionId,
    pub msg_id: MsgId,
    pub body: SignalBody,
    pub ack_required: bool,
}

impl SignalCodec {
    pub fn decode_without_expected_session(
        &self,
        payload: &[u8],
        replay_cache: &mut ReplayCache,
    ) -> Result<DecodedSignal, SignalError>;
}
```

Then route the authenticated `DecodedSignal` to a session.

### Acceptance rule

No answer-side session routing may depend on unauthenticated outer-envelope fields.

---

## P0 — Stale `Status` events can mutate the wrong session entry

### Problem

The answer-side session event handler has logic that updates a session by `status.session_id` if present, but if not present, it falls back to `remote_peer_id` and can remove/reinsert a session under a different `session_id`.

Conceptually:

```rust
if let Some(handle) = sessions_by_id.get_mut(&status.session_id) {
    handle.status = status;
} else if let Some(old_session_id) = session_by_peer.get(&status.remote_peer_id).copied()
    && let Some(mut handle) = sessions_by_id.remove(&old_session_id)
{
    session_by_peer.insert(status.remote_peer_id.clone(), status.session_id);
    handle.status = status.clone();
    sessions_by_id.insert(status.session_id, handle);
}
```

This is dangerous in a multi-session runtime.

### Why this matters

A stale status event from an old closed/replaced session can arrive after a newer session for the same peer has already been admitted. The fallback-by-peer logic can remove the newer session from the registry and reinsert its handle under the stale session ID.

That violates the v0.3 spec's callback binding requirement:

```text
stale callbacks from session A must not alter session B
stale data-channel events from a closed session must be ignored
teardown completion from one session must not clear global state for another session
```

### Required fix

Generic `Status` events must not re-key the session registry.

Rules:

- If `status.session_id` exists in `sessions_by_id`, update that session's status.
- If `status.session_id` does not exist, ignore the status event as stale.
- Do not fall back to `remote_peer_id` for registry mutation in a generic status path.

If a real session replacement must re-key registry state, use an explicit event:

```rust
enum AnswerSessionEvent {
    SessionStatus(SessionStatusSnapshot),
    SessionEnded {
        session_id: SessionId,
        remote_peer_id: PeerId,
        reason: SessionEndReason,
    },
    SessionReplaced {
        old_session_id: SessionId,
        new_session_id: SessionId,
        remote_peer_id: PeerId,
        generation: u64,
        status: SessionStatusSnapshot,
    },
}
```

Even better: include a session generation token so stale tasks cannot mutate the current registry entry.

### Acceptance rule

A stale event for an unknown `session_id` must not mutate any active session.

---

## P1 — Session status is not actually stream-aware

### Problem

The status model includes fields like:

```rust
active_stream_count
open_forward_ids
```

But the implementation appears to populate them as:

```rust
active_stream_count: 0,
open_forward_ids: config.forwards.iter().map(|forward| forward.id.clone()).collect(),
```

That is not real session stream status.

### Why this matters

Operator status output can say a session has zero active streams even when the session is carrying traffic. It can also show `open_forward_ids` that are merely configured, not actually open.

That is misleading for debugging concurrent v0.3 behavior.

### Required fix

Choose one of two paths.

#### Preferred: make status real

Expose stream runtime status from the multiplexed tunnel:

```rust
pub struct TunnelRuntimeStatus {
    pub active_stream_count: usize,
    pub open_forward_ids: Vec<String>,
}
```

Each answer session should update its session status from the actual multiplexed runtime.

#### Acceptable v0.3 simplification: rename fields honestly

If real stream runtime status is too invasive for this pass, rename fields:

```rust
configured_forward_ids
```

and remove or omit `active_stream_count`.

Do not keep fields that look live but are hardcoded.

### Acceptance rule

Status fields must mean what their names say.

---

## P1 — Daemon-level state can report `idle` while sessions are active

### Problem

When multiple sessions are active, daemon status can report something equivalent to:

```json
{
  "current_state": "idle",
  "active_session_count": 2
}
```

This is confusing.

### Required fix

Add or use a daemon-level state that distinguishes "serving with active sessions" from "no active sessions."

Recommended states:

```rust
enum DaemonState {
    Starting,
    Serving,
    Active,
    Backoff,
    Stopping,
    Error,
}
```

If changing the enum is too broad, set `current_state` to an existing non-idle value when `active_session_count > 0`.

### Acceptance rule

If `active_session_count > 0`, status must not report a state that operators will understand as "idle/no active sessions."

---

## P1 — Same-peer replacement semantics are too vague

### Problem

The spec says same-peer replacement/reconnect remains allowed, but it does not freeze exactly which same-peer second offers are valid.

This creates ambiguity between:

- duplicate retransmission,
- pending-session replacement,
- active-session reconnect,
- unrelated second active session.

### Required policy

Freeze v0.3 as:

1. **Duplicate/retransmitted message for existing session**  
   Normal replay/dedupe path.

2. **Same-peer new offer while existing session is pending / not yet data-channel active**  
   Replacement is allowed.

3. **Same-peer new unrelated offer while existing session is active**  
   Reject with encrypted `busy`.

4. **Same-peer reconnect/renegotiation for existing active session**  
   Allowed only if it follows existing session-local reconnect semantics.

5. **Same-peer different `session_id` with no valid replacement context**  
   Reject with encrypted `busy`.

### Required code changes

Rename any broad helper like:

```rust
maybe_replace_pending_answer_session_via_events
```

to something explicit:

```rust
maybe_replace_pending_same_peer_session
```

Add comments describing that replacement is only for pending/not-yet-active sessions unless explicitly part of the reconnect flow.

### Acceptance rule

A same-peer unrelated second active session must never be admitted.

---

## P1 — Canonical docs still contain stale single-session language

### Problem

The canonical docs still contain language such as:

```text
one active peer tunnel session at a time
answer daemon returns to idle after one session
offer closes session when all streams close
```

This conflicts with v0.3.

### Required fix

Update canonical docs:

- `README.md`
- `docs/SPECS.md`
- `docs/V03_SPEC.md` if needed

Historical review files may keep old text if clearly historical, but canonical docs must not contradict current behavior.

### Acceptance rule

Current docs must clearly say:

```text
one answer daemon may serve multiple simultaneous authorized offer peers
one active session per peer_id
many streams per session
session failures are isolated by session
```

---

## P1 — Test coverage is overstated

### Problem

`V03_TODO.md` marks many integration and regression tasks complete, but static review did not find convincing coverage for all of them.

The most important missing/weak tests appear to be:

- same-peer second unrelated offer receives encrypted `busy`,
- same-peer pending replacement does not affect peer B,
- stale status/callback event from old session cannot mutate newer session,
- duplicate/replay handling with multiple active sessions,
- per-forward allowlists enforced across simultaneous sessions,
- unauthorized/disallowed peers receive no useful response under load,
- session-local ACK timeout/remote close/remote error/reconnect failure while another peer remains active.

### Required fix

Add the tests or mark the TODO as partial.

Do not mark v0.3 complete unless these cases are either covered or explicitly deferred.

---

## Additional notes

### MSRV / edition compatibility

The workspace declares:

```toml
rust-version = "1.85"
edition = "2024"
```

The code may use `if let` chains or other syntax that should be verified against the declared MSRV. Ensure CI uses Rust 1.85 or later as declared.

### Do not weaken crypto or replay behavior

Do not solve routing by exposing unauthenticated session IDs or by trusting cleartext topic/envelope metadata. The broker remains untrusted.

### Do not add new config knobs unless real

No public config fields are required for this hardening pass. Keep the global answer session capacity as an internal constant unless a real operator need arises.

## Recommended fix order

1. Fix authenticated routing before session dispatch.
2. Fix stale `Status` event registry mutation.
3. Clarify and enforce same-peer replacement policy.
4. Fix status correctness.
5. Fix daemon-level state naming/reporting.
6. Update canonical docs.
7. Add missing integration/regression tests.
8. Run full workspace formatting, clippy, and tests.

## Bottom line

The v0.3 implementation is promising and the architecture is mostly right, but it is not fully safe yet as a multi-session answer daemon.

The two most important fixes are:

1. **route only after authentication/decryption/session validation**
2. **prevent stale session events from mutating active registry entries**

Those are the core correctness hazards introduced by moving from one session to many sessions.
