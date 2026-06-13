# INT_TEST1_TODO.md

# Rust WebRTC Tunnel Integration Test TODO 1

## Goal

Add integration coverage for multi-session v0.3 runtime behavior that is difficult to prove with unit tests alone: transport turbulence, daemon restart/recovery, long-lived stream churn, same-peer connection pressure, malformed authenticated signaling, and status-file stability under concurrent activity.

This TODO is test-first and behavior-preserving. Do not change protocol behavior, tunnel frame format, config shape, or operator-facing semantics unless a test exposes a real bug that must be fixed.

## Guardrails

- Prefer the existing in-memory signaling transport mesh in `crates/p2p-daemon/tests/two_node_daemon.rs`.
- Avoid live MQTT brokers, public STUN servers, sleeps as synchronization, and nondeterministic network dependencies.
- Keep tests bounded and deterministic; use timeouts around all async waits.
- Preserve encrypted/signed signaling even in tests.
- Do not introduce plaintext MQTT diagnostics.
- Keep failures session-local unless the tested scenario intentionally stops a whole daemon.
- If a scenario is too broad for one test, split it into smaller focused tests.
- After each phase, run targeted integration tests and the relevant crate tests.
- Before marking this TODO complete, run:

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

---

# Task 1 - Expand the in-memory integration harness

## 1.1 Audit existing integration harness

- [x] Review `crates/p2p-daemon/tests/two_node_daemon.rs`.
- [x] Identify reusable helpers for:
  - [x] identity generation,
  - [x] config creation,
  - [x] in-memory transport mesh,
  - [x] signaling trace decoding,
  - [x] status-file waits,
  - [x] local TCP client round trips,
  - [x] target TCP listeners.

## 1.2 Add transport fault injection knobs

- [x] Extend `InMemoryTransport` or add a wrapper that can deterministically inject:
  - [x] publish failure for a selected sender/recipient route,
  - [x] poll failure for a selected daemon,
  - [x] delayed delivery for a selected route,
  - [x] dropped delivery for a selected route,
  - [x] duplicate delivery for a selected route.
- [x] Ensure failures are scoped by peer/route so one active peer can be disturbed without disturbing another.
- [x] Ensure injected failures can be one-shot or counted, not unbounded by default.
- [x] Record all publish attempts in `TransportTrace`, including failed attempts where useful.

## 1.3 Add daemon task lifecycle helpers

- [x] Add helper(s) to start:
  - [x] one answer daemon,
  - [x] one or more offer daemons,
  - [x] optional offer test hooks,
  - [x] per-peer status paths,
  - [x] per-forward target listeners.
- [x] Add helper(s) to cleanly abort spawned daemons and target tasks.
- [x] Ensure cleanup removes temporary status files.
- [x] Keep helpers local to integration tests unless they are broadly useful.

## 1.4 Add status wait helpers

- [x] Add a helper that waits until a status file is parseable JSON and matches a predicate.
- [x] Add predicates for:
  - [x] `mqtt_connected`,
  - [x] active session count,
  - [x] presence/absence of a remote peer,
  - [x] daemon `current_state`,
  - [x] configured forward IDs.
- [x] Make reads tolerant of partial writes.
- [x] Keep all waits bounded by explicit timeouts.

## 1.5 Validation

- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.
- [x] Run `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

---

# Task 2 - Broker/transport turbulence with active sessions

## 2.1 Define scenarios

- [x] One active offer/answer session with transient answer-side poll failure.
- [x] Two active offer peers where only one route sees publish failures.
- [x] Publish failure during ACK/answer/ICE traffic for one peer.
- [x] Recovery after a later successful transport operation.

## 2.2 Test active answer poll failure status

- [x] Establish one active session.
- [x] Inject a recoverable answer-side poll failure.
- [x] Assert local answer status flips `mqtt_connected = false`.
- [x] Allow a later successful poll or publish.
- [x] Assert `mqtt_connected = true` after successful transport activity.
- [x] Assert the daemon process remains alive.

## 2.3 Test route-scoped publish failure isolation

- [x] Establish two active peer sessions.
- [x] Inject publish failure only for Peer A route.
- [x] Trigger signaling activity or a session event for Peer A.
- [x] Assert Peer A observes the expected failure/cleanup behavior.
- [x] Assert Peer B remains active and can still bridge bytes.
- [x] Assert daemon-level status remains accurate for the surviving session(s).

## 2.4 Test delayed and duplicate delivery under active multi-session

- [x] Establish two active sessions.
- [x] Delay one answer-to-offer payload for Peer A.
- [x] Duplicate one payload for Peer B.
- [x] Assert both sessions either complete or tolerate duplicates according to existing retry/replay policy.
- [x] Assert no duplicate creates an extra session or mutates the wrong peer.

## 2.5 Validation

- [x] Run focused transport-turbulence tests individually.
- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.

---

# Task 3 - Answer daemon restart and offer-side recovery

## 3.1 Define restart model for tests

- [x] Confirm whether the in-memory harness can stop and recreate the answer daemon with the same identity/config.
- [x] Confirm offer-side recovery should be driven only by the offer daemon.
- [x] Decide whether the test should restart the answer daemon on the same in-memory route or create a fresh route.

## 3.2 Single-peer answer restart test

- [x] Establish one offer/answer session and prove bytes bridge.
- [x] Abort the answer daemon task.
- [x] Assert the offer side detects session failure.
- [x] Restart the answer daemon with the same identity and authorized keys.
- [x] Trigger a new local client or reconnect path from the offer side.
- [x] Assert a new session is established.
- [x] Assert bytes bridge after restart.
- [x] Assert answer side did not initiate reconnect signaling.

## 3.3 Multi-peer answer restart test

- [x] Establish two offer peers against one answer daemon.
- [x] Abort the answer daemon task.
- [x] Restart the answer daemon.
- [x] Trigger recovery/new local clients from both offer peers.
- [x] Assert each peer establishes at most one fresh session.
- [x] Assert stale old-session messages are ignored.
- [x] Assert status reports the post-restart sessions accurately.

## 3.4 Negative assertions

- [x] Assert no plaintext MQTT diagnostic/status messages are published during restart.
- [x] Assert no retained old session mutates the restarted answer daemon registry.
- [x] Assert answer side still does not publish `offer`, `ice_restart_request`, or `renegotiate_request`.

## 3.5 Validation

- [x] Run focused restart tests individually.
- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.

---

# Task 4 - Long-lived multi-stream soak-style integration test

## 4.1 Define bounded soak parameters

- [x] Use two offer peers.
- [x] Use at least two forwards.
- [x] Reuse established WebRTC sessions across repeated stream open/close cycles.
- [x] Keep iteration counts small enough for normal CI but large enough to catch cleanup drift.
- [x] Suggested starting point:
  - [x] 2 peers,
  - [x] 2 forwards,
  - [x] 5 stream cycles per peer/forward.

## 4.2 Build target services

- [x] Start target listeners that can accept multiple sequential connections.
- [x] Echo distinct payloads so streams cannot be confused.
- [x] Track accepted connection counts per target.

## 4.3 Exercise repeated stream churn

- [x] Establish both peer sessions.
- [x] For each cycle:
  - [x] open a local TCP client for Peer A forward 1,
  - [x] open a local TCP client for Peer A forward 2,
  - [x] open a local TCP client for Peer B forward 1 or 2 as allowed,
  - [x] send unique payloads,
  - [x] read expected responses,
  - [x] close clients cleanly.
- [x] Include at least one cycle with concurrent clients from both peers.

## 4.4 Assert persistent-session behavior

- [x] Assert offer sessions stay usable after zero active streams.
- [x] Assert stream IDs do not collide or reuse within a session unexpectedly.
- [x] Assert target services see the expected number of connections.
- [x] Assert status remains parseable and reports active sessions during churn.

## 4.5 Validation

- [x] Run the focused soak-style test.
- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.

---

# Task 5 - Concurrent same-peer connection pressure while another peer is active

## 5.1 Define scenarios

- [x] Peer A receives multiple local clients during initial negotiation.
- [x] Peer B already has an active healthy session.
- [x] Peer A clients should queue or be handled according to existing offer-side pending-client policy.
- [x] Peer B must remain unaffected.

## 5.2 Pending-client queue pressure

- [x] Start Peer B and establish an active session.
- [x] Start Peer A and intentionally delay answer-to-offer signaling during negotiation.
- [x] Open multiple local TCP clients to Peer A while negotiation is pending.
- [x] Assert clients are queued up to the configured/internal pending behavior.
- [x] Assert excess clients, if any, are closed according to existing policy.

## 5.3 Post-open handling

- [x] Release the delayed signaling so Peer A session opens.
- [x] Assert queued Peer A clients complete or fail according to documented behavior.
- [x] Assert Peer A can accept additional clients after session open.
- [x] Assert Peer B continues to bridge bytes before, during, and after Peer A pressure.

## 5.4 Same-peer unrelated session pressure

- [x] While Peer A has an active session, trigger a new unrelated same-peer offer if the harness can do so without bypassing protocol.
- [x] Assert answer returns encrypted `busy`.
- [x] Assert Peer A's existing session remains active.
- [x] Assert Peer B remains active.

## 5.5 Validation

- [x] Run focused connection-pressure tests individually.
- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.

---

# Task 6 - Malformed encrypted-but-authenticated signaling during active multi-session

## 6.1 Define malformed cases

Construct payloads that are encrypted, signed, authorized, and recipient-correct, but invalid after inner message validation or session policy:

- [x] known sender with unknown non-offer session,
- [x] known sender with another peer's active `session_id`,
- [x] old/stale session message after replacement,
- [x] invalid message type for current state,
- [x] duplicate `msg_id` across different sessions,
- [x] malformed body that fails protocol validation after decrypt.

## 6.2 Multi-session setup

- [x] Establish Peer A and Peer B active sessions.
- [x] Keep both sessions able to bridge bytes before malformed payload injection.
- [x] Capture session IDs and peer IDs for constructing payloads.

## 6.3 Inject malformed authenticated payloads

- [x] Publish each malformed payload to the answer daemon.
- [x] Assert the payload is rejected or ignored according to protocol rules.
- [x] Assert no wrong session receives the message.
- [x] Assert no new session is created unless the payload is a valid new offer.
- [x] Assert any encrypted error response is sent only when policy allows it.

## 6.4 Assert session-local survival

- [x] After each malformed payload, verify Peer A can still bridge bytes if Peer A was not the failed session.
- [x] Verify Peer B can still bridge bytes.
- [x] Assert status remains accurate and parseable.
- [x] Assert logs/status do not leak decrypted sensitive payloads.

## 6.5 Validation

- [x] Run focused malformed-signaling tests.
- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.

---

# Task 7 - Status-file stability under concurrent churn

## 7.1 Define churn sources

- [x] Multiple peer sessions active.
- [x] Multiple streams opening and closing.
- [x] One session failing while another remains active.
- [x] Repeated status reads during writes.

## 7.2 Add status reader task

- [x] Spawn a task that repeatedly reads the answer status file while churn is running.
- [x] Treat missing file before first write as acceptable.
- [x] Treat partial writes as retryable only if the production writer can expose them; otherwise assert all visible complete reads parse.
- [x] Record every successfully parsed status snapshot.

## 7.3 Run churn while reading status

- [x] Start two offer peers and one answer daemon.
- [x] Open/close streams repeatedly.
- [x] Trigger one stream-level failure or session-level cleanup.
- [x] Continue reading status throughout the churn.

## 7.4 Assert status semantics

- [x] Every parsed status has:
  - [x] `active_session_count == sessions.len()`,
  - [x] no `active_stream_count`,
  - [x] no `open_forward_ids`,
  - [x] valid `current_state`,
  - [x] configured forward IDs where expected.
- [x] When sessions are active, status does not claim a misleading no-session state.
- [x] After cleanup, status eventually reflects the surviving or idle state accurately.

## 7.5 Validation

- [x] Run focused status-churn test.
- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.

---

# Task 8 - Checklist and documentation cleanup

## 8.1 Update this TODO as tests land

- [x] Mark completed tasks and subtasks.
- [x] If an integration scenario is replaced by a better equivalent, document that replacement.
- [x] If a test exposes a real bug, add a short note describing the bug and fix.

## 8.2 Keep canonical docs aligned

- [x] Update canonical docs only if integration tests clarify or change documented runtime behavior.
- [x] Do not rewrite historical review docs except for clearly marked follow-up notes.

No canonical runtime semantics changed. The restart coverage recreates the in-memory answer route and starts a fresh offer-side daemon with the same identities to avoid relying on detached test tasks after abort. The malformed-signaling coverage uses representative encrypted/signed invalid messages constructible through the public signaling codec and verifies active peer isolation after each injection.

## 8.3 Final validation

- [x] Run `cargo fmt --all --check`.
- [x] Run `cargo clippy --workspace --all-targets --all-features -- -D warnings`.
- [x] Run `cargo test --workspace --all-targets`.

---

# Acceptance checklist

Mark this TODO complete only when all items are true:

- [x] In-memory integration harness supports deterministic route-scoped transport turbulence.
- [x] Active-session MQTT/publish/poll turbulence is covered.
- [x] Answer daemon restart and offer-side recovery are covered.
- [x] Long-lived multi-stream session reuse/churn is covered.
- [x] Concurrent same-peer connection pressure with another peer active is covered.
- [x] Malformed encrypted-but-authenticated signaling during multi-session activity is covered.
- [x] Status-file stability under concurrent churn is covered.
- [x] Tests assert no cross-session teardown or status mutation for unrelated peers.
- [x] Tests assert no plaintext MQTT diagnostics are introduced.
- [x] Targeted integration tests pass.
- [x] Full workspace fmt, clippy, and tests pass.
