# replies9.md

# Responses to v0.3 Hardening Clarification Questions

These are the decisions to hand back to Copilot for the `V03_CODE_REVIEW.md` / `V03_FIX_TODO.md` hardening pass.

---

## 1. Status fields

For this v0.3 hardening pass, choose the **honest simplification**.

Do **not** implement real tunnel runtime stream status right now.

Change status as follows:

- remove `active_stream_count`, or set it only if real data exists
- rename `open_forward_ids` to `configured_forward_ids`

Recommended per-session status:

```rust
SessionStatus {
    session_id,
    remote_peer_id,
    state,
    data_channel_open,
    configured_forward_ids,
}
```

Reason: real active-stream reporting requires plumbing live stream metrics out of the multiplexed tunnel runtime into the answer session status path. That is useful later, but it is extra scope. For this pass, the important fix is to stop reporting fake values.

---

## 2. Daemon-level state

Add a new daemon-level state:

```rust
DaemonState::Serving
```

Use it for the answer daemon when the process is healthy and able to accept sessions, regardless of whether it currently has zero, one, or many sessions.

Then status can say:

```text
current_state = Serving
active_session_count = 0
```

or:

```text
current_state = Serving
active_session_count = 3
```

That is cleaner than overloading `Idle`, which becomes misleading once the answer daemon is always available for multiple sessions.

---

## 3. Authenticated routing design

Use **Option A**:

**The answer daemon loop should centrally decode/authenticate/decrypt each incoming signaling payload once, then route a typed authenticated signal to the owning session task.**

Do not route based on unauthenticated outer-envelope metadata.

The answer daemon should produce something like:

```rust
AuthenticatedSignal {
    sender_peer_id,
    sender_kid,
    session_id,
    msg_id,
    body,
    ack_required,
}
```

Then routing is:

```text
decode/authenticate/decrypt/replay-check
extract authenticated session_id and sender_peer_id
if session_id exists -> route to owning session
else if body is Offer -> evaluate new-session admission
else ignore/reject according to protocol
```

Replay ownership rule:

- The daemon-level decode path owns the replay check for inbound answer-side signaling.
- Session tasks should receive already-authenticated, already-replay-checked messages.
- ACK emission should still happen according to existing policy, but it must be based on the authenticated decoded message, not the unauthenticated envelope.

This is the cleanest design. An authenticated “peek” API would be more complex and easier to get wrong.

---

## 4. Stale event protection

Add a per-session generation token.

Exact `session_id` matching is good, but for this hardening pass require both:

```text
session_id matches
generation matches
```

Use something like:

```rust
SessionGeneration(u64)
```

or a random `SessionTaskId`.

Every session-owned event should carry:

```rust
session_id
generation
```

The registry should ignore events if either does not match the currently registered session handle.

Reason: a generation token makes stale callback protection explicit. It also protects against future bugs where a session ID is reused accidentally or a replacement path temporarily reuses peer/session metadata. It is cheap and clarifies ownership.

---

## 5. TODO status handling

Leave `docs/V03_TODO.md` as a historical record of the first v0.3 implementation.

Do **not** go back and partially uncheck it.

Make `docs/V03_FIX_TODO.md` the active corrective checklist.

Reason:

- changing the old TODO muddies the audit trail
- the new fix TODO is the right place to track remaining hardening work
- the old TODO can still show what Copilot believed it completed during the first implementation pass

If desired, add a short note at the top of `V03_TODO.md`:

```markdown
Note: This file records the first v0.3 implementation pass. Follow-up issues found during review are tracked in V03_FIX_TODO.md.
```

---

## Final frozen decisions

1. Status: simplify honestly; use `configured_forward_ids`; do not fake stream counts.
2. Daemon state: add `DaemonState::Serving`.
3. Routing: centrally authenticate/decrypt once in answer daemon loop, then route typed authenticated signals.
4. Stale events: require both `session_id` and generation token.
5. TODOs: keep `V03_TODO.md` historical; use `V03_FIX_TODO.md` as active checklist.
