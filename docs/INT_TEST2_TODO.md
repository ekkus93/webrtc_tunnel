# INT_TEST2_TODO.md

# Rust WebRTC Tunnel Integration Test TODO 2

## Goal

Add a second round of integration coverage for v0.3 multi-peer answer-daemon behavior under combined runtime pressure: simultaneous reconnects, answer restarts with multiple peers, MQTT/signaling turbulence while data streams stay active, same-peer replacement isolation, status-file churn, and malformed authenticated traffic during active load.

This TODO is test-first and behavior-preserving. Do not change protocol behavior, signaling wire format, tunnel frame format, public config shape, or operator-facing semantics unless an integration test exposes a real bug that must be fixed.

## Guardrails

- Prefer `crates/p2p-daemon/tests/two_node_daemon.rs` and the existing in-memory signaling transport mesh.
- Do not use live MQTT brokers, public STUN servers, wall-clock sleeps as synchronization, or nondeterministic external services.
- Keep every async wait bounded with explicit timeouts.
- Preserve encrypted and signed signaling in all real signaling paths.
- Treat MQTT as untrusted transport; never rely on broker ordering or plaintext metadata for correctness.
- Keep answer-side reconnect behavior passive: the offer side owns fresh offers, ICE restart, and renegotiation.
- Keep failures session-local unless the test intentionally stops a daemon.
- Keep tests CI-suitable; split broad scenarios into focused tests if needed.
- After each phase, run targeted integration tests plus relevant crate lint/tests.
- Before marking this TODO complete, run:

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

---

# Task 1 - Audit and extend the integration harness for combined-failure tests

## 1.1 Review current harness surfaces

- [x] Review `crates/p2p-daemon/tests/two_node_daemon.rs`.
- [x] Identify reusable helpers for:
  - [x] in-memory transport mesh creation,
  - [x] route-scoped publish/poll/drop/delay/duplicate faults,
  - [x] signaling trace recording and decode assertions,
  - [x] multi-peer identity/config setup,
  - [x] offer test hooks and ICE-state injection,
  - [x] daemon task startup/shutdown,
  - [x] target TCP listener setup,
  - [x] repeated local TCP client round trips,
  - [x] parse-tolerant status-file waits.

## 1.2 Add missing helper seams only if needed

- [x] Add helper(s) for starting one answer daemon and N offer daemons with distinct identities.
- [x] Add helper(s) for restarting an answer daemon with the same identity/config and fresh transport route.
- [x] Add helper(s) for waiting on signaling trace predicates:
  - [x] replacement `Offer`,
  - [x] `Answer`,
  - [x] `IceCandidate`,
  - [x] `Error`,
  - [x] absence of answer-originated `Offer`,
  - [x] absence of answer-originated `IceRestartRequest`,
  - [x] absence of answer-originated `RenegotiateRequest`.
- [x] Add helper(s) for repeatedly reading status while tolerating partial writes.
- [x] Ensure helper changes are test-only and do not alter production behavior.

## 1.3 Validate harness-only changes

- [x] Run focused helper tests if new unit-style helper tests are added.
- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.
- [x] Run `cargo clippy -p p2p-daemon --test two_node_daemon --all-features -- -D warnings`.

---

# Task 2 - Simultaneous reconnect pressure across multiple offer peers

## 2.1 Define reconnect-pressure scenario

- [x] Use one answer daemon and two authorized offer daemons.
- [x] Establish both peer sessions and prove bytes bridge for each peer.
- [x] Inject offer-side ICE/signaling failure for both peers close together.
- [x] Keep the answer daemon running throughout the test.
- [x] Use deterministic test hooks instead of uncontrolled network failure.

## 2.2 Exercise concurrent recovery

- [x] Trigger reconnect/replacement behavior for Peer A.
- [x] Trigger reconnect/replacement behavior for Peer B before Peer A fully settles.
- [x] Assert each offer side publishes recovery signaling for only its own session.
- [x] Assert the answer side routes each replacement by authenticated peer/session.
- [x] Assert each peer ends with at most one active session.
- [x] Assert unrelated peer session state is not removed or re-keyed.

## 2.3 Assert answer-side passive recovery policy

- [x] Decode signaling trace during the reconnect window.
- [x] Assert answer side never publishes a fresh `Offer`.
- [x] Assert answer side never publishes `IceRestartRequest`.
- [x] Assert answer side never publishes `RenegotiateRequest`.
- [x] Assert answer-originated messages are limited to allowed response types such as `Ack`, `Answer`, `IceCandidate`, `Error`, or `Close`.

## 2.4 Assert post-recovery usability

- [x] Open a new local TCP client for Peer A after recovery.
- [x] Open a new local TCP client for Peer B after recovery.
- [x] Assert both clients bridge distinct payloads correctly.
- [x] Assert answer status reports two active sessions.
- [x] Assert each offer status returns to an active/usable session state.

## 2.5 Validation

- [x] Run the focused simultaneous reconnect test.
- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.
- [x] Run `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

---

# Task 3 - Multi-peer answer daemon restart and fresh-session recovery

## 3.1 Define restart scenario

- [x] Use one answer daemon and two authorized offer daemons.
- [x] Establish two active sessions.
- [x] Prove bytes bridge for both peers before restart.
- [x] Abort the answer daemon task intentionally.
- [x] Restart answer daemon with:
  - [x] same answer identity,
  - [x] same authorized keys,
  - [x] same config semantics,
  - [x] clean in-memory transport route.

## 3.2 Assert old sessions fail cleanly

- [x] Assert old answer-side session registry is gone after restart.
- [x] Assert stale old-session messages do not mutate the restarted answer daemon.
- [x] Assert old sessions do not create duplicate active entries.
- [x] Assert offer-side failures are recoverable and do not terminate offer daemons.
- [x] Assert no plaintext signaling/status message is published during restart.

## 3.3 Re-establish fresh sessions from both offer peers

- [x] Trigger a new local client or recovery path from Peer A.
- [x] Trigger a new local client or recovery path from Peer B.
- [x] Assert each peer establishes one fresh post-restart session.
- [x] Assert fresh session IDs differ from old session IDs.
- [x] Assert post-restart bytes bridge for both peers.
- [x] Assert answer status reports both fresh sessions.

Note: the multi-peer restart regression restarts the offer daemons on fresh local listener ports after intentionally aborting the old daemon tasks. This avoids a port-release race in the test harness while still preserving the same peer identities, authorized keys, answer identity, and config semantics.

## 3.4 Assert answer remains passive after restart

- [x] Decode post-restart signaling trace.
- [x] Assert answer side does not initiate `Offer`.
- [x] Assert answer side does not initiate `IceRestartRequest`.
- [x] Assert answer side does not initiate `RenegotiateRequest`.
- [x] Assert offer side drives all fresh-session establishment.

## 3.5 Validation

- [x] Run the focused multi-peer answer restart test.
- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.
- [x] Run `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

---

# Task 4 - MQTT/signaling turbulence while active data streams remain healthy

## 4.1 Define data-vs-signaling isolation scenario

- [x] Establish one or two active WebRTC data-channel sessions.
- [x] Open long-lived local TCP streams that continue exchanging data.
- [x] Inject recoverable signaling transport failures while streams are open:
  - [x] answer-side poll failure,
  - [x] offer-side poll failure,
  - [x] route-scoped publish failure,
  - [x] delayed signaling delivery.
- [x] Avoid forcing WebRTC data-channel failure unless explicitly part of the case.

## 4.2 Assert active data streams are not disrupted by signaling-only turbulence

- [x] Send data before the signaling fault.
- [x] Send data during the signaling fault.
- [x] Send data after signaling recovery.
- [x] Assert each payload is echoed/bridged correctly.
- [x] Assert stream IDs and forward IDs do not cross between peers.
- [x] Assert no active stream is closed solely because MQTT polling had a transient failure.

## 4.3 Assert local status reflects latest-known signaling usability

- [x] Assert affected daemon status flips `mqtt_connected = false` after recoverable signaling failure.
- [x] Assert unaffected peer/session data flow can continue while status is false.
- [x] Assert `mqtt_connected = true` after successful signaling activity resumes.
- [x] Assert current daemon/session state remains honest and does not claim a stream count it cannot know.

## 4.4 Assert recovery does not create duplicate sessions

- [x] Inspect signaling trace after recovery.
- [x] Assert no duplicate active session is created for the same peer.
- [x] Assert delayed/duplicate signaling does not mutate another peer session.
- [x] Assert replay handling remains session-bound.

## 4.5 Validation

- [x] Run the focused signaling-turbulence-with-streams test.
- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.
- [x] Run `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

---

# Task 5 - Same-peer replacement while unrelated peers are active

## 5.1 Define same-peer replacement scenario

- [x] Establish Peer A session.
- [x] Establish Peer B session.
- [x] Put Peer A into a pending/replacement path using a deterministic offer-side hook or injected failure.
- [x] Keep Peer B actively moving data while Peer A replaces.
- [x] Ensure Peer A and Peer B use distinct identities and `peer_id` values.

## 5.2 Assert same-peer replacement is isolated

- [x] Assert Peer A replacement updates only Peer A's session entry.
- [x] Assert Peer B session ID remains unchanged.
- [x] Assert Peer B data channel remains usable.
- [x] Assert Peer B stream traffic succeeds during Peer A replacement.
- [x] Assert status never drops Peer B because of Peer A replacement.

## 5.3 Assert same-peer busy policy remains scoped

- [x] Attempt a forbidden second unrelated session from Peer A while Peer A already has an active tunnel.
- [x] Assert Peer A receives the expected encrypted `busy`/error behavior when fully authorized and allowed.
- [x] Assert Peer B is unaffected.
- [x] Assert unauthorized or disallowed peers still receive no response.

## 5.4 Assert stale Peer A messages cannot affect replacement

- [x] Inject stale Peer A messages from the old session ID after replacement.
- [x] Assert stale messages do not mutate the new Peer A session.
- [x] Assert stale messages do not mutate Peer B.
- [x] Assert no normal accepted-message ACK is emitted for rejected stale non-offers.

## 5.5 Validation

- [x] Run focused same-peer replacement isolation tests.
- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.
- [x] Run `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

---

# Task 6 - Status-file consistency under heavy multi-peer churn

## 6.1 Define bounded churn model

- [x] Use at least two offer peers.
- [x] Use at least two configured forwards where practical.
- [x] Cycle through a bounded set of events:
  - [x] successful stream open/close,
  - [x] target connect failure,
  - [x] remote close,
  - [x] recoverable signaling poll failure,
  - [x] session replacement/reconnect.
- [x] Keep iteration count small enough for CI.

## 6.2 Continuously sample status files

- [x] Spawn a reader task for answer status.
- [x] Spawn reader task(s) for offer status where useful.
- [x] Read status repeatedly during churn.
- [x] Treat partial writes as retryable in the reader helper.
- [x] Record every successfully parsed status sample.

## 6.3 Assert status invariants across samples

- [x] Assert every completed read is valid JSON.
- [x] Assert `role` and `peer_id` remain stable.
- [x] Assert `active_session_count == sessions.len()` for answer status.
- [x] Assert `active_session_id` is null for zero or multiple answer sessions.
- [x] Assert `active_session_id` is populated only for exactly one answer session.
- [x] Assert `session_capacity` remains present.
- [x] Assert `configured_forwards` remains present.
- [x] Assert session entries include `configured_forward_ids`.
- [x] Assert removed/misleading fields remain absent:
  - [x] `active_stream_count`,
  - [x] `open_forward_ids`.

## 6.4 Assert final steady state

- [x] Wait for churn operations to finish.
- [x] Assert answer daemon is still `serving`.
- [x] Assert surviving sessions remain usable.
- [x] Assert failed sessions are cleaned from status.
- [x] Assert `mqtt_connected` reflects the latest observed signaling result.

## 6.5 Validation

- [x] Run the focused status churn test.
- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.
- [x] Run `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

---

# Task 7 - Malformed authenticated signaling during active multi-peer load

## 7.1 Define malformed-message cases

- [x] Establish two valid peer sessions and keep both usable.
- [x] Create authenticated/encrypted payloads from an authorized peer with:
  - [x] unknown-session non-offer,
  - [x] known-session wrong sender,
  - [x] stale old-session message,
  - [x] duplicate `msg_id` for different session,
  - [x] malformed but decryptable inner payload if a safe test seam exists,
  - [x] unexpected message type for current state.
- [x] Avoid plaintext or unsigned shortcuts.

## 7.2 Inject malformed traffic under load

- [x] Keep Peer A and Peer B moving valid stream data.
- [x] Inject malformed authenticated signaling from Peer A.
- [x] Inject malformed authenticated signaling from a third authorized-but-not-active peer if useful.
- [x] Inject replayed payloads while valid traffic continues.
- [x] Ensure injection uses the same broker/topic-style transport path as normal payloads.

## 7.3 Assert session-local rejection

- [x] Assert malformed traffic does not route to the wrong active session.
- [x] Assert malformed traffic does not create a new session unless it is a valid `Offer`.
- [x] Assert unknown-session non-offers do not receive normal accepted-message ACKs.
- [x] Assert duplicate same-session ack-required payloads may be re-ACKed as designed.
- [x] Assert duplicate different-session payloads are rejected without normal ACK.
- [x] Assert valid Peer B stream traffic continues.

## 7.4 Assert logging/status/security behavior

- [x] Assert no plaintext diagnostic or status message is published over signaling transport.
- [x] Assert status remains parseable after malformed traffic.
- [x] Assert answer daemon remains alive and `serving`.
- [x] Assert only affected session state changes, if any.

## 7.5 Validation

- [x] Run focused malformed-authenticated-signaling tests.
- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.
- [x] Run `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

---

# Task 8 - Route-scoped replay, delay, and duplicate stress

## 8.1 Define route-scoped replay matrix

- [x] Use two active peers against one answer daemon.
- [x] For Peer A route, inject:
  - [x] duplicate delivery,
  - [x] delayed delivery,
  - [x] drop then retransmit,
  - [x] reused `msg_id` across different session IDs.
- [x] For Peer B route, keep normal traffic flowing.

## 8.2 Assert route isolation

- [x] Assert Peer A duplicate/delay behavior is handled by replay/ACK policy.
- [x] Assert Peer B does not see Peer A payloads.
- [x] Assert Peer B session status does not change because of Peer A route faults.
- [x] Assert answer registry still maps each peer to the correct session.

## 8.3 Assert retransmit behavior remains byte-identical where applicable

- [x] Capture retransmitted payload bytes for ack-required signaling when retries are triggered.
- [x] Assert retransmits are byte-identical except MQTT delivery metadata outside the encrypted payload.
- [x] Assert duplicate retransmits are matched by `(sender_kid, msg_id)`.
- [x] Assert valid retransmits do not create duplicate sessions.

Note: the integration stress test covers route-scoped drop/duplicate recovery and peer isolation. Exact byte-identical ACK retry behavior remains pinned by the lower-level daemon/signaling ACK tests, where the retry tracker can be exercised deterministically without changing session timing.

## 8.4 Validation

- [x] Run focused route-scoped replay tests.
- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.
- [x] Run `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

---

# Task 9 - Final checklist and validation

## 9.1 Update this TODO as tests land

- [x] Mark each completed task and subtask.
- [x] If a test exposes a real bug, add a short note under the relevant task describing the bug and fix.
- [x] If a proposed integration test is replaced by a better equivalent, document the replacement.
- [x] Keep this file as the implementation checklist until all items are complete.

## 9.2 Targeted validation

- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.
- [x] Run `cargo test -p p2p-daemon`.
- [x] Run `cargo test -p p2p-signaling`.
- [x] Run `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

## 9.3 Full workspace validation

- [x] Run `cargo fmt --all --check`.
- [x] Run `cargo clippy --workspace --all-targets --all-features -- -D warnings`.
- [x] Run `cargo test --workspace --all-targets`.

---

# Acceptance checklist

Mark this TODO complete only when all items are true:

- [x] Simultaneous multi-peer reconnect pressure is integration-tested.
- [x] Multi-peer answer restart and fresh-session recovery are integration-tested.
- [x] Signaling turbulence during active data streams is integration-tested.
- [x] Same-peer replacement is proven not to affect unrelated peers.
- [x] Status files remain parseable and semantically honest during churn.
- [x] Malformed authenticated signaling is rejected without cross-session damage under load.
- [x] Route-scoped replay/delay/duplicate behavior is covered.
- [x] Answer side remains passive during recovery scenarios.
- [x] No plaintext MQTT diagnostics/status messages are introduced.
- [x] Targeted integration tests pass.
- [x] Full workspace fmt, clippy, and tests pass.
