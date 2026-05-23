# UNIT_TEST1_TODO.md

# Rust WebRTC Tunnel Unit Test TODO 1

## Goal

Add focused test coverage for the v0.3 multi-session answer-daemon hardening work and adjacent status/signaling behavior.

This TODO is test-only. Do not change protocol behavior, tunnel frame format, config shape, or public operator semantics while implementing these tests unless a test exposes a real bug that must be fixed.

## Guardrails

- Keep tests deterministic and avoid live network dependencies where possible.
- Prefer unit tests for pure routing/status/replay behavior.
- Use existing in-memory daemon and signaling test harnesses for integration-style coverage.
- Do not weaken encrypted/signed signaling requirements for test convenience.
- Do not reintroduce routing by unauthenticated outer-envelope metadata.
- Preserve session-local failure isolation: one peer/session failure must not tear down unrelated peers.
- After each completed section, run the relevant targeted tests.
- Before marking this TODO complete, run full workspace validation:

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

---

# Task 1 - Add `p2pctl status` rendering tests

## 1.1 Locate current status rendering

- [x] Review `bins/p2pctl/src/main.rs`.
- [x] Identify the `status` command output path.
- [x] Confirm how it reads:
  - [x] `current_state`,
  - [x] `active_session_count`,
  - [x] `session_capacity`,
  - [x] `sessions`,
  - [x] `configured_forward_ids`.

## 1.2 Add a testable rendering seam

- [x] Extract status JSON rendering into a small helper if needed.
- [x] Keep CLI behavior unchanged.
- [x] Ensure the helper accepts parsed status JSON or a string input without reading a real config file.
- [x] Keep error handling explicit for malformed status JSON.

## 1.3 Test zero-session output

- [x] Add a test with `active_session_count = 0`.
- [x] Assert output is readable and includes:
  - [x] peer ID,
  - [x] role,
  - [x] MQTT status,
  - [x] daemon state,
  - [x] `sessions: none`.
- [x] Assert it does not mention removed or misleading fields:
  - [x] `active_stream_count`,
  - [x] `open_forward_ids`.

## 1.4 Test one-session output

- [x] Add a test with one active session.
- [x] Assert output includes:
  - [x] `sessions=1/<capacity>`,
  - [x] session ID,
  - [x] remote peer ID,
  - [x] session state,
  - [x] data-channel-open flag,
  - [x] configured forward IDs.
- [x] Assert daemon-level `state=serving` is displayed when the fixture has active sessions.

## 1.5 Test multi-session output

- [x] Add a test with at least two sessions.
- [x] Assert each session is rendered independently.
- [x] Assert configured forward IDs are shown under the honest `configured_forwards` wording.
- [x] Assert output remains stable regardless of session order in the JSON fixture if ordering is not guaranteed by the CLI.

## 1.6 Validation

- [x] Run `cargo test -p p2pctl`.
- [x] Run `cargo clippy -p p2pctl --all-targets --all-features -- -D warnings`.

---

# Task 2 - Add direct replay-status tests in `p2p-signaling`

## 2.1 Locate replay-status API

- [x] Review `crates/p2p-signaling/src/replay.rs`.
- [x] Review `crates/p2p-signaling/src/transport.rs`.
- [x] Confirm `SignalCodec::decode_with_replay_status` is the intended test target.

## 2.2 Test fresh decode status

- [x] Encode a valid message from an authorized peer.
- [x] Decode it with a new `ReplayCache`.
- [x] Assert `ReplayStatus::Fresh`.
- [x] Assert authenticated sender peer ID and inner session ID match the encoded message.

## 2.3 Test duplicate same-session status

- [x] Decode the same payload twice with the same replay cache.
- [x] Assert the second decode returns `ReplayStatus::DuplicateSameSession`.
- [x] Assert the duplicate payload is still authenticated/decrypted before the duplicate status is returned.
- [x] Assert the legacy `decode` wrapper still maps this case to the existing duplicate protocol error.

## 2.4 Test duplicate different-session status

- [x] Create two valid messages that intentionally reuse the same `msg_id` if an existing test seam supports it.
- [x] If no seam exists, add the smallest test-only helper needed to construct this condition without changing production encoding semantics.
- [x] Decode the first message successfully.
- [x] Decode the second message with the same replay cache.
- [x] Assert `ReplayStatus::DuplicateDifferentSession`.
- [x] Assert the legacy `decode` wrapper still maps this case to the existing protocol error.

## 2.5 Test expected-session mismatch remains hard failure

- [x] Decode a valid message while passing a different `expected_session`.
- [x] Assert a protocol error, not a duplicate status.
- [x] Confirm stale old-session messages cannot be treated as routable duplicates.

## 2.6 Validation

- [x] Run `cargo test -p p2p-signaling`.
- [x] Run `cargo clippy -p p2p-signaling --all-targets --all-features -- -D warnings`.

---

# Task 3 - Add same-peer pending replacement isolation test

## 3.1 Define the scenario

- [x] Peer A has a pending answer-side session that has not reached active data-channel/tunnel state.
- [x] Peer B has an active healthy session.
- [x] Peer A sends a valid replacement offer with a new `session_id`.
- [x] Peer A's pending session is replaced.
- [x] Peer B remains registered, active, and unaffected.

## 3.2 Choose test level

- [x] Prefer a `p2p-daemon` unit test if the registry/event loop can be exercised deterministically.
- [x] Use `crates/p2p-daemon/tests/two_node_daemon.rs` only if end-to-end behavior is needed.
- [x] Avoid sleeps where an event-channel assertion can prove the same behavior.

## 3.3 Build fixtures

- [x] Create identities for answer, peer A, and peer B.
- [x] Configure answer authorized keys for both peers.
- [x] Configure per-forward allowlists that allow both peers where needed.
- [x] Create an existing Peer A pending session.
- [x] Create an existing Peer B active session.

## 3.4 Exercise replacement

- [x] Deliver Peer A's authenticated replacement offer.
- [x] Assert the daemon emits or handles the explicit replacement event.
- [x] Assert `session_by_peer[peer_a]` now maps to the replacement session ID.
- [x] Assert old Peer A session ID is no longer in `sessions_by_id`.

## 3.5 Assert peer B isolation

- [x] Assert Peer B's session ID remains in `sessions_by_id`.
- [x] Assert Peer B's generation is unchanged.
- [x] Assert Peer B's status is unchanged.
- [x] Assert Peer B's inbound channel is not closed or replaced.
- [x] Assert Peer B does not receive Peer A's replacement signal.

## 3.6 Validation

- [x] Run the focused `p2p-daemon` test.
- [x] Run `cargo test -p p2p-daemon`.

---

# Task 4 - Add per-forward allowlist isolation across sessions

## 4.1 Define the scenario

- [x] Peer A and Peer B both establish sessions with the same answer daemon.
- [x] Forward `ssh` allows Peer A only.
- [x] Forward `web-ui` allows Peer B only.
- [x] Peer A can open `ssh`.
- [x] Peer A is denied on `web-ui`.
- [x] Peer B can open `web-ui`.
- [x] Peer B is denied on `ssh`.
- [x] A denial in one session does not affect the other session.

## 4.2 Build integration fixture

- [x] Extend or reuse the in-memory transport mesh in `two_node_daemon.rs`.
- [x] Configure two offer daemons with distinct peer identities.
- [x] Configure two forwards on the answer side with disjoint allowlists.
- [x] Start target TCP listeners for the allowed paths.
- [x] Avoid target listeners for denied paths unless needed to prove no connection is attempted.

## 4.3 Test allowed paths

- [x] Connect Peer A's local client to the `ssh` listener.
- [x] Assert bytes bridge successfully through the `ssh` target.
- [x] Connect Peer B's local client to the `web-ui` listener.
- [x] Assert bytes bridge successfully through the `web-ui` target.

## 4.4 Test denied paths

- [x] Attempt Peer A's denied `web-ui` stream.
- [x] Assert the client observes a stream-local failure or close consistent with existing tunnel behavior.
- [x] Attempt Peer B's denied `ssh` stream.
- [x] Assert the client observes a stream-local failure or close consistent with existing tunnel behavior.
- [x] Assert denied attempts do not produce plaintext MQTT diagnostics.

## 4.5 Assert isolation

- [x] After each denied attempt, prove the other peer's allowed stream still works.
- [x] Assert answer status still reports both sessions if both sessions are expected to remain active.
- [x] Assert target listeners for denied paths are not contacted.

## 4.6 Validation

- [x] Run the focused `two_node_daemon` test.
- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.

---

# Task 5 - Add multi-session failure-isolation variants

## 5.1 Shared fixture

- [x] Build or reuse a helper that starts:
  - [x] one answer daemon,
  - [x] two authorized offer daemons,
  - [x] two independent offer-side clients,
  - [x] two independent answer-side target paths.
- [x] Ensure the helper can inject or trigger a failure in only one session.
- [x] Ensure the helper can prove the unaffected peer still bridges bytes afterward.

## 5.2 ACK timeout isolation

- [x] Inject dropped ACKs or suppress ACK handling for Peer A only.
- [x] Assert Peer A session fails or cleans up as expected.
- [x] Assert Peer B session remains usable.
- [x] Assert the answer daemon remains running and reports accurate status.

## 5.3 Remote close isolation

- [x] Deliver a valid encrypted `close` from Peer A.
- [x] Assert Peer A session cleans up.
- [x] Assert Peer B session remains usable.
- [x] Assert stale Peer A cleanup events cannot remove Peer B.

## 5.4 Remote error isolation

- [x] Deliver a valid encrypted `error` from Peer A.
- [x] Assert Peer A session cleans up according to existing error policy.
- [x] Assert Peer B session remains usable.
- [x] Assert daemon-level status does not collapse to a misleading no-session state while Peer B is still active.

## 5.5 Reconnect failure isolation

- [x] Trigger a reconnect/replacement failure for Peer A.
- [x] Assert failure is contained to Peer A.
- [x] Assert Peer B continues to use its established session.
- [x] Assert answer side does not initiate reconnect signaling.

## 5.6 Target-connect failure isolation expansion

- [x] Keep the existing target-connect failure isolation test.
- [x] Add assertions that the failed session's cleanup does not remove unrelated session registry entries.
- [x] Add assertions that status eventually reflects only the still-active peer if the failed peer session is removed.

## 5.7 Validation

- [x] Run each focused failure-isolation test individually while developing.
- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.
- [x] Run `cargo test -p p2p-daemon`.

---

# Task 6 - Add authenticated-routing edge-case tests

## 6.1 Unknown non-offer handling

- [x] Construct a valid authenticated non-offer message for an unknown `session_id`.
- [x] Deliver it to the answer daemon routing loop.
- [x] Assert no new session is created.
- [x] Assert no existing session receives the message.
- [x] Assert no plaintext diagnostic is published.

## 6.2 Unknown fresh offer admission

- [x] Construct a valid authenticated offer for an unknown `session_id`.
- [x] Deliver it to the answer daemon routing loop.
- [x] Assert a new session is created when the peer is authorized and allowed.
- [x] Assert ACK/answer behavior remains unchanged.

## 6.3 Sender/session owner mismatch

- [x] Create a session owned by Peer A.
- [x] Deliver a valid authenticated Peer B message that uses Peer A's active `session_id`.
- [x] Assert the message is rejected or ignored.
- [x] Assert Peer A's session inbound queue does not receive Peer B's message.
- [x] Assert Peer B cannot mutate Peer A's session status.

## 6.4 Duplicate with multiple sessions

- [x] Create active sessions for Peer A and Peer B.
- [x] Deliver a duplicate ACK-required message for Peer A.
- [x] Assert Peer A gets the duplicate re-ACK behavior.
- [x] Assert Peer B receives no signal and no status mutation.

## 6.5 Validation

- [x] Run `cargo test -p p2p-daemon`.
- [x] Run `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

---

# Task 7 - Add status schema regression tests

## 7.1 Daemon status schema

- [x] Add or extend `p2p-daemon` status tests to assert serialized JSON includes:
  - [x] `active_session_count`,
  - [x] `session_capacity`,
  - [x] `sessions`,
  - [x] `configured_forwards`.
- [x] Assert serialized JSON excludes:
  - [x] `active_stream_count`,
  - [x] `open_forward_ids`.

## 7.2 Session status schema

- [x] Add a session-status fixture with one session.
- [x] Assert per-session JSON includes:
  - [x] `session_id`,
  - [x] `remote_peer_id`,
  - [x] `state`,
  - [x] `data_channel_open`,
  - [x] `configured_forward_ids`.
- [x] Assert per-session JSON excludes fake stream/open-forward fields.

## 7.3 Multi-session aggregate behavior

- [x] Serialize status with two sessions.
- [x] Assert `active_session_count == sessions.len()`.
- [x] Assert `active_session_id` is absent or null when more than one session is active.
- [x] Assert `current_state = serving` for active answer-session registry snapshots.

## 7.4 Validation

- [x] Run `cargo test -p p2p-daemon status`.

---

# Task 8 - Documentation and checklist cleanup

## 8.1 Update this TODO as tests land

- [x] Mark each implemented test task complete.
- [x] If a planned test is replaced by a better equivalent, document the replacement.
- [x] If a test exposes a real bug, add a short note describing the bug and fix.

## 8.2 Avoid stale claims

- [x] Keep canonical docs aligned if status fields or behavior are clarified by tests.
- [x] Do not edit historical review docs except to add clearly marked follow-up notes.

## 8.3 Final validation

- [x] Run `cargo fmt --all --check`.
- [x] Run `cargo clippy --workspace --all-targets --all-features -- -D warnings`.
- [x] Run `cargo test --workspace --all-targets`.

---

# Acceptance checklist

Mark this TODO complete only when all items are true:

- [x] `p2pctl status` has tests for zero, one, and multiple sessions.
- [x] `p2p-signaling` directly tests replay-status outcomes.
- [x] Same-peer pending replacement is tested while an unrelated peer remains active.
- [x] Per-forward allowlist behavior is tested across simultaneous sessions.
- [x] Multi-session failure isolation covers ACK timeout, remote close, remote error, reconnect failure, and target-connect cleanup/status behavior.
- [x] Authenticated routing tests cover unknown non-offer, unknown offer admission, sender/session mismatch, and duplicate handling with multiple sessions.
- [x] Status schema tests prove fake stream/open-forward fields are absent.
- [x] All targeted crate tests pass.
- [x] Full workspace fmt, clippy, and tests pass.
