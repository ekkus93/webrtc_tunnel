# V03_CODE_REVIEW2.md

# Rust WebRTC Tunnel v0.3 Code Review 2 — Focused Patch Review

## Purpose

This review covers the remaining issues found after the v0.3 multi-session answer-daemon hardening pass.

This is **not** a redesign. The v0.3 architecture is mostly implemented and should be preserved:

```text
one p2p-answer daemon
many concurrent authorized p2p-offer peers
one active WebRTC session per peer_id
one reliable ordered data channel per session
many multiplexed logical TCP streams per session
answer side owns target mappings
MQTT signaling remains encrypted and signed
forwarded tunnel data uses WebRTC DTLS
```

The prior hardening pass fixed the most serious issues:

- active-session routing no longer depends only on unauthenticated outer-envelope metadata,
- decoded/authenticated signaling is routed as typed data,
- stale session events now use generation checks,
- the unsafe status-event fallback by `remote_peer_id` was removed,
- same-peer pending replacement is more explicit,
- active same-peer unrelated offers get `busy`,
- fake stream status fields were removed or renamed,
- `p2pctl status` understands multiple sessions.

The remaining issues are narrower and should be fixed as a focused patch.

---

## Overall assessment

The current v0.3 implementation is substantially better than the first multi-session pass. The answer daemon now has a real session registry, per-session task ownership, authenticated decode before most routing, generation-based stale-event protection, and a more honest status model.

However, three remaining problems should be fixed before considering v0.3 clean:

1. **Unknown-session non-offer messages can still be routed by peer fallback into an active session.**
2. **Healthy `p2p-answer` can still report `Idle` when it is serving with zero sessions.**
3. **Canonical docs still contain stale single-session/v2 language that conflicts with v0.3.**

These are patch-level issues, not architecture failures.

---

# 1. Unknown-session non-offer routing

## Problem

The answer daemon now authenticates/decrypts incoming signaling before routing, which is good.

However, after exact `session_id` lookup fails, the daemon can still fall back to routing by authenticated `peer_id` into that peer's currently active session.

That fallback is only correct for a **same-peer new `Offer`** that needs to be classified as:

- duplicate/retransmission,
- pending replacement,
- active unrelated same-peer offer that receives `busy`,
- capacity/busy path.

It is **not** correct for arbitrary non-offer messages with an unknown `session_id`.

Examples of unknown-session non-offers:

- `Answer`
- `IceCandidate`
- `Ack`
- `Close`
- `Error`
- `Ping`
- `Pong`
- reconnect/control messages that are not valid new-session entry points

These should not be routed into an unrelated active session just because the sender has another session active.

## Why this matters

The v0.3 spec requires routing by authenticated `session_id`:

```text
If session_id matches an existing session, route to that session.
If session_id does not match and message type is offer, treat it as a new-session candidate.
If session_id does not match and message type is not a valid new-session entry point, ignore/reject.
```

Routing unknown-session non-offers by peer fallback can cause:

- unrelated active sessions receiving messages for the wrong `session_id`,
- possible ACK emission for messages that should have been ignored,
- confusing session logs,
- weaker stale-session isolation.

This is not as severe as the earlier unauthenticated outer-envelope routing bug, because the message is authenticated. But it is still wrong protocol behavior.

## Required fix

In `handle_answer_daemon_payload()` or the equivalent answer-side routing function:

1. Decode/authenticate/decrypt/replay-check the payload.
2. Extract authenticated `session_id`, `sender_peer_id`, and `SignalBody`.
3. If `session_id` exists in `sessions_by_id`, route to that exact session.
4. Else if the body is `Offer`, evaluate new-session admission / same-peer busy / pending replacement.
5. Else ignore/reject according to existing protocol policy.
6. Do **not** route unknown-session non-offers into an active session by peer fallback.
7. Do **not** send a normal ACK for unknown-session non-offers.

## Recommended behavior

```text
Known session_id:
    route to exact session if authenticated sender matches the session peer.

Unknown session_id + Offer:
    evaluate as new-session candidate or same-peer policy case.

Unknown session_id + non-Offer:
    ignore/reject at daemon layer.
    do not route to any active session.
    do not ACK as if accepted.
```

---

# 2. Answer daemon zero-session state

## Problem

The code added `DaemonState::Serving`, but a healthy answer daemon with zero active sessions can still report the role's steady state, which appears to be `Idle`.

That produces status like:

```text
role = answer
current_state = Idle
active_session_count = 0
```

For v0.3, that is misleading.

An answer daemon is an always-on service. With zero sessions, it is not "idle" in the old single-session sense; it is actively serving and waiting for offers.

## Required fix

For `NodeRole::Answer`, status should report:

```text
current_state = Serving
```

whenever the daemon is healthy and able to accept sessions, regardless of whether `active_session_count` is 0, 1, or many.

Examples:

```text
role = answer
current_state = Serving
active_session_count = 0
```

```text
role = answer
current_state = Serving
active_session_count = 2
```

Offer-side status can keep using states such as `WaitingForLocalClient` if that matches the offer runtime model.

## Why this matters

Operators need status output that matches the product model:

```text
p2p-answer is up and ready to serve authorized offer peers.
```

`Idle` suggests inactivity or old single-session semantics.

---

# 3. Canonical documentation still contains stale language

## Problem

`docs/SPECS.md` still contains v2/single-session language that conflicts with v0.3 behavior, such as:

- "One active peer tunnel session at a time"
- "Multiple simultaneous WebRTC peer sessions" listed as out of scope
- answer daemon returning to idle after one session
- offer closing the session when all streams close
- "one session at a time" language not clearly marked as historical

Some historical review documents may keep old wording, but canonical docs must not contradict the current product.

## Required fix

Update canonical docs so they clearly distinguish:

- v2 behavior, if preserved for historical context,
- current v0.3 behavior,
- future/out-of-scope features.

`docs/SPECS.md` must not contain unqualified claims that the product supports only one active peer tunnel session at a time.

Acceptable approaches:

1. Update the file fully to v0.3.
2. Move old v2 material into a clearly marked "Historical v2 behavior" section.
3. Add an explicit "Current v0.3 behavior" section that supersedes old material.

## Required current v0.3 statements

Canonical docs should say:

```text
One p2p-answer daemon may serve multiple simultaneous authorized p2p-offer peers.
Each peer_id may have at most one active unrelated session.
Each active peer session has one WebRTC peer connection and one reliable ordered data channel.
Each session contains its own multiplexed logical TCP stream space.
Stream IDs are unique within a session, not globally.
Per-forward authorization remains enforced on the answer side.
Unknown-session non-offer signaling is ignored/rejected and is not routed by peer fallback.
```

---

# 4. Test gaps

Add focused tests for the three remaining issues.

## Required tests

### 4.1 Unknown-session non-offer routing

Test:

1. Peer A has an active session.
2. Peer A sends an authenticated non-offer message with a different unknown `session_id`.
3. The answer daemon does not route it into Peer A's active session.
4. The active session remains healthy.
5. The message does not receive a normal accepted-message ACK.

### 4.2 Answer zero-session status

Test:

1. Start / construct answer daemon status with zero active sessions.
2. Assert:
   - `role = answer`
   - `current_state = Serving`
   - `active_session_count = 0`

### 4.3 Canonical doc stale-string guard

Add a lightweight guard test or doc-validation test that ensures canonical docs do not contain unqualified stale phrases such as:

```text
One active peer tunnel session at a time
Multiple simultaneous WebRTC peer sessions are out of scope
```

If the phrase appears in a historical section, it must be clearly marked as historical and must not be presented as current behavior.

---

# 5. Validation

Before marking the patch complete, run:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

If any command cannot be run, document why and do not mark the patch complete.

---

# Bottom line

The v0.3 implementation is close. The biggest architectural hardening work is already done.

This patch should focus on:

1. route unknown-session non-offers correctly,
2. report answer daemon steady state honestly,
3. clean canonical docs,
4. add tests for those exact cases.

Do not change wire formats, tunnel frame formats, config shape, TURN policy, encryption policy, or the one-session-per-peer rule.
