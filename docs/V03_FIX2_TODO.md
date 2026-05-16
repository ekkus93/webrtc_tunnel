# V03_FIX2_TODO.md

# Rust WebRTC Tunnel v0.3 Fix 2 TODO — Routing, Status, and Canonical Docs

## Goal

Finish the remaining v0.3 patch-level issues after the multi-session answer-daemon hardening pass.

This is **not** a redesign.

The current v0.3 architecture remains:

```text
one p2p-answer daemon
many concurrent authorized p2p-offer peers
one active WebRTC session per peer_id
one reliable ordered data channel per session
many multiplexed logical TCP streams per session
```

This TODO focuses only on:

1. unknown-session non-offer routing,
2. answer daemon steady-state status,
3. canonical docs cleanup,
4. focused tests,
5. local validation.

---

## Non-negotiable rules

- Do not change the MQTT signaling wire format.
- Do not change the tunnel frame format.
- Do not change the public config shape.
- Do not add TURN.
- Do not add arbitrary target selection by the offer side.
- Do not weaken encrypted/signed signaling.
- Do not trust MQTT broker metadata.
- Do not route messages by unauthenticated outer-envelope fields.
- Do not allow more than one active unrelated session per `peer_id`.
- Do not let one session failure tear down unrelated sessions.
- Do not start another broad architecture refactor.

---

# Task 0 — Baseline review

## 0.1 Read the relevant files

Read:

- `docs/V03_SPEC.md`
- `docs/V03_TODO.md`
- `docs/V03_CODE_REVIEW.md`
- `docs/V03_FIX_TODO.md`
- `docs/V03_CODE_REVIEW2.md`
- this TODO file

Focus on the latest remaining issues:

- unknown-session non-offer routing,
- answer daemon `Serving` status,
- stale canonical single-session docs.

## 0.2 Confirm current v0.3 architecture remains intact

Before changing code, verify the current implementation still has:

- frame v2 tunnel format,
- encrypted/signed MQTT signaling,
- centralized answer-side MQTT loop,
- authenticated decode before routing,
- answer-side session registry,
- one active unrelated session per peer ID,
- per-session multiplexed tunnel runtime,
- per-forward answer-side authorization,
- generation-based stale event protection.

Do not proceed if any of those have regressed.

---

# Task 1 — Fix unknown-session non-offer routing

## 1.1 Locate answer-side routing function

Find the answer daemon function that receives decoded/authenticated signaling and routes it.

Likely area:

```text
crates/p2p-daemon
handle_answer_daemon_payload
answer daemon session registry / routing code
```

Identify current behavior for:

- known `session_id`,
- unknown `session_id` + `Offer`,
- unknown `session_id` + non-`Offer`,
- same-peer fallback.

## 1.2 Freeze the routing policy

Implement this exact policy:

```text
If authenticated session_id exists:
    route only to that exact session.

If authenticated session_id does not exist and body is Offer:
    evaluate new-session admission, same-peer pending replacement, same-peer active busy, and capacity rules.

If authenticated session_id does not exist and body is not Offer:
    ignore/reject at daemon routing layer.
    do not route by peer fallback.
    do not send normal ACK.
```

The same-peer fallback is allowed **only** for `Offer` messages that need admission/busy/replacement classification.

## 1.3 Prevent ACKing unknown-session non-offers

Ensure unknown-session non-offers do not receive a normal ACK as if they were accepted into an existing session.

If the existing decode path automatically emits ACKs too early, move ACK emission later or add a condition so these messages are dropped without accepted-message ACK behavior.

Rules:

- ACK known-session messages according to existing protocol policy.
- ACK accepted new-session offers according to existing protocol policy.
- Do not ACK unknown-session non-offers as accepted.
- Do not route unknown-session non-offers to session tasks.

## 1.4 Preserve replay behavior

Do not break replay suppression.

Rules:

- duplicate known-session messages remain deduped,
- replayed old-session messages do not create or mutate state,
- unknown-session non-offers are safe to ignore even if replayed,
- new-session offers still use existing replay/admission logic.

## 1.5 Tests

Add tests proving:

- authenticated unknown-session non-offer from an active peer is ignored or rejected at daemon layer,
- authenticated unknown-session non-offer is not routed into the active peer session,
- authenticated unknown-session non-offer does not receive a normal accepted-message ACK,
- known-session non-offer still routes correctly,
- unknown-session `Offer` still enters admission/same-peer policy handling,
- forged outer-envelope metadata still cannot choose a session queue.

---

# Task 2 — Fix answer daemon steady-state status

## 2.1 Locate answer registry status writer

Find the code that writes answer-daemon registry status.

Likely area:

```text
write_answer_registry_status
DaemonStatus
DaemonState
steady_state_for_role
```

## 2.2 Freeze answer daemon status policy

For `NodeRole::Answer`, a healthy daemon should report:

```text
DaemonState::Serving
```

when it is connected/running and able to accept sessions, whether there are:

- zero active sessions,
- one active session,
- multiple active sessions.

Do not report `Idle` for a healthy answer daemon simply because `active_session_count = 0`.

## 2.3 Implement status change

Update status creation so that answer daemon registry status uses:

```rust
DaemonState::Serving
```

for the healthy answer service state.

Offer-side behavior may remain unchanged if `WaitingForLocalClient` or another state is appropriate.

## 2.4 Update `p2pctl status` wording if needed

Ensure `p2pctl status` renders `Serving` clearly.

Expected output should be understandable for:

```text
answer daemon serving, 0 sessions
answer daemon serving, 1 session
answer daemon serving, N sessions
```

## 2.5 Tests

Add tests proving:

- answer status with zero sessions reports `Serving`,
- answer status with one session reports `Serving`,
- answer status with multiple sessions reports `Serving`,
- `active_session_count` remains accurate,
- `p2pctl status` output for zero sessions is not misleading.

---

# Task 3 — Clean canonical docs

## 3.1 Locate stale canonical docs

Search canonical docs for stale single-session language.

At minimum search:

```text
One active peer tunnel session at a time
Multiple simultaneous WebRTC peer sessions
single active session
one session at a time
answer daemon returns to idle
all streams close
```

Focus especially on:

```text
docs/SPECS.md
docs/V03_SPEC.md
README.md
```

Historical review docs may keep old wording if clearly historical.

## 3.2 Update `docs/SPECS.md`

Ensure this file no longer presents v2 single-session rules as current behavior.

It should clearly state current v0.3 behavior:

```text
One p2p-answer daemon may serve multiple simultaneous authorized p2p-offer peers.
Each peer_id may have at most one active unrelated session.
Each active peer session has one WebRTC peer connection and one reliable ordered data channel.
Each session has its own multiplexed logical TCP stream space.
Stream IDs are unique within a session, not globally.
Per-forward authorization remains enforced on the answer side.
Unknown-session non-offer signaling is ignored/rejected and is not routed by peer fallback.
```

## 3.3 Preserve history without confusing current behavior

If old v2 behavior is kept, put it under a clearly marked section such as:

```markdown
## Historical v2 behavior
```

Do not leave old behavior as unqualified current rules.

## 3.4 Update status docs

Document that answer daemon status uses:

```text
Serving
```

for healthy service state with zero or more active sessions.

Document that:

```text
active_session_count
session_capacity
sessions[]
configured_forward_ids
```

are the current status fields.

## 3.5 Docs stale-string guard

Add a test or validation check for canonical docs.

The test should fail if `docs/SPECS.md` contains unqualified current-behavior claims like:

```text
One active peer tunnel session at a time
Multiple simultaneous WebRTC peer sessions are out of scope
```

If those phrases appear in a historical section, the test should either allow that context explicitly or avoid searching historical sections.

---

# Task 4 — TODO/status honesty

## 4.1 Keep old TODOs as historical records

Do not rewrite old TODO history extensively.

It is acceptable to add a note such as:

```markdown
Note: This file records an earlier implementation pass. Follow-up hardening is tracked in V03_FIX_TODO.md and V03_FIX2_TODO.md.
```

## 4.2 Make active TODO completion accurate

Do not mark this TODO complete until:

- routing behavior is fixed,
- status behavior is fixed,
- canonical docs are fixed,
- required tests pass,
- validation commands are run.

## 4.3 If validation cannot be run

If any validation command cannot be run, document:

- command attempted,
- reason it could not run,
- whether the task remains incomplete.

Do not mark validation complete without running it.

---

# Task 5 — Validation

Run:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

Also run targeted tests while developing:

```bash
cargo test -p p2p-daemon
cargo test -p p2pctl
```

If any command fails, fix the issue before marking this TODO complete.

---

# Task 6 — Acceptance checklist

Mark this TODO complete only when all items below are true.

## Routing

- [x] Known-session signaling routes by authenticated `session_id`.
- [x] Unknown-session `Offer` enters new-session / same-peer / capacity policy.
- [x] Unknown-session non-`Offer` is ignored/rejected at daemon routing layer.
- [x] Unknown-session non-`Offer` is not routed by peer fallback.
- [x] Unknown-session non-`Offer` does not receive normal accepted-message ACK.
- [x] Forged outer-envelope metadata cannot choose a session queue.
- [x] Replay handling remains correct.

## Status

- [x] Healthy answer daemon with zero sessions reports `Serving`.
- [x] Healthy answer daemon with one session reports `Serving`.
- [x] Healthy answer daemon with many sessions reports `Serving`.
- [x] `active_session_count` is accurate.
- [x] `p2pctl status` displays zero/one/many answer sessions clearly.

## Docs

- [x] `docs/SPECS.md` no longer presents single-session v2 behavior as current.
- [x] v0.3 multi-session answer behavior is documented.
- [x] Unknown-session non-offer routing policy is documented.
- [x] Answer `Serving` status semantics are documented.
- [x] Canonical docs stale-string guard exists or equivalent doc validation was performed.

## Validation

- [x] `cargo fmt --check` passes.
- [x] `cargo clippy --workspace --all-targets --all-features -- -D warnings` passes.
- [x] `cargo test --workspace --all-targets` passes.

---

# Suggested implementation order

1. Fix unknown-session non-offer routing.
2. Add routing tests.
3. Fix answer daemon `Serving` status.
4. Add status tests.
5. Clean canonical docs.
6. Add stale-doc guard if feasible.
7. Run fmt, clippy, and full tests.

Do not change config, tunnel frame format, signaling wire format, TURN policy, or encryption policy.
