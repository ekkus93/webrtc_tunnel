# UNIT_TEST2_TODO.md

# Rust WebRTC Tunnel Unit Test TODO 2

## Goal

Add focused unit-level coverage for the remaining v0.3/FIX2 hardening edges: answer routing policy across all non-offer message types, answer status semantics, `p2pctl status` resilience, canonical documentation guards, in-memory test harness fault helpers, and status/config compatibility.

This TODO is test-first and behavior-preserving. Do not change protocol behavior, signaling wire format, tunnel frame format, public config shape, or operator-facing semantics unless a test exposes a real bug that must be fixed.

## Guardrails

- Prefer small deterministic unit tests over broad integration tests.
- Keep MQTT signaling encrypted/signed in tests that encode real signaling payloads.
- Do not trust or route by broker metadata or unauthenticated outer-envelope fields.
- Do not reintroduce peer fallback for unknown-session non-offers.
- Preserve same-peer `Offer` fallback for admission, replacement, and busy policy.
- Keep answer daemon healthy steady-state status as `serving` with zero or more sessions.
- Do not weaken replay protection or duplicate ACK behavior.
- After each completed section, run the relevant targeted tests.
- Before marking this TODO complete, run full workspace validation:

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

---

# Task 1 - Table-test answer routing for unknown-session non-offers

## 1.1 Locate current answer routing tests

- [x] Review `crates/p2p-daemon/src/lib.rs`.
- [x] Locate `handle_answer_daemon_payload`.
- [x] Locate existing routing tests:
  - [x] known-session routing,
  - [x] unknown-session non-offer ignore,
  - [x] same-peer unknown-session `Offer` policy,
  - [x] forged outer-envelope routing rejection,
  - [x] duplicate signal isolation.

## 1.2 Build a reusable routing test helper

- [x] Add or reuse a helper that creates:
  - [x] answer config,
  - [x] answer identity,
  - [x] authorized offer identity,
  - [x] active answer session handle,
  - [x] `sessions_by_id`,
  - [x] `session_by_peer`,
  - [x] `RecordingTransport`,
  - [x] `ReplayCache`,
  - [x] encoded authenticated signaling payloads.
- [x] Keep the helper local to daemon tests unless broadly useful.
- [x] Ensure helper does not weaken signature/encryption validation.

## 1.3 Table-test every unknown-session non-offer message body

For each message type below, create an authenticated payload from the active peer with a fresh unknown `session_id`:

- [x] `Answer`
- [x] `IceCandidate`
- [x] `Ack`
- [x] `Ping`
- [x] `Pong`
- [x] `Close`
- [x] `Error`
- [x] `IceRestartRequest`
- [x] `RenegotiateRequest`
- [x] `EndOfCandidates`

For each case assert:

- [x] the active session receiver gets no message,
- [x] no new session is inserted,
- [x] `session_by_peer` remains unchanged,
- [x] no normal accepted-message ACK is published,
- [x] active session status remains unchanged.

## 1.4 Preserve same-peer unknown-session `Offer` behavior

- [x] Add a paired table/control case for `Offer`.
- [x] Assert unknown-session `Offer` still enters same-peer session policy handling.
- [x] Assert pending replacement and active busy paths remain covered by existing tests.
- [x] Assert the new non-offer table cannot accidentally block offer admission.

## 1.5 Preserve known-session non-offer behavior

- [x] Add representative known-session tests for:
  - [x] `Ack`,
  - [x] `IceCandidate`,
  - [x] `Close`.
- [x] Assert each routes to the exact authenticated session.
- [x] Assert matching known-session ACK-required messages still receive ACK where appropriate.
- [x] Assert wrong authenticated sender for a known session is still rejected.

## 1.6 Validation

- [x] Run `cargo test -p p2p-daemon answer_daemon`.
- [x] Run `cargo test -p p2p-daemon duplicate_signal_for_one_session_does_not_route_to_another_session`.
- [x] Run `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

---

# Task 2 - Expand replay and ACK routing unit coverage

## 2.1 Audit replay/ACK helpers

- [x] Review:
  - [x] `process_answer_session_signal`,
  - [x] `duplicate_active_session_ack_message`,
  - [x] `maybe_ack_duplicate_active_session_message`,
  - [x] `ReplayStatus::DuplicateSameSession`,
  - [x] `ReplayStatus::DuplicateDifferentSession`.

## 2.2 Test duplicate same-session ACK behavior by message type

- [x] Create duplicate known-session ACK-required payloads for:
  - [x] `Close`,
  - [x] `Error`,
  - [x] `IceCandidate`,
  - [x] `EndOfCandidates`.
- [x] Assert duplicate same-session messages are re-ACKed where protocol requires.
- [x] Assert duplicates do not invoke state mutation twice.

## 2.3 Test duplicate different-session rejection by message type

- [x] Reuse fixed-msg-id test seam if available.
- [x] Create duplicate `msg_id` payloads across different `session_id` values.
- [x] Assert `DuplicateDifferentSession` does not route to the active session.
- [x] Assert no normal accepted-message ACK is emitted for rejected different-session duplicates.

## 2.4 Test non-ACK-required messages remain non-ACKing

- [x] Cover known-session `Ping`.
- [x] Cover known-session `Pong`.
- [x] Assert neither creates a pending ACK nor publishes a normal ACK.
- [x] Assert handler side effects remain limited to the expected ping/pong behavior.

## 2.5 Validation

- [x] Run `cargo test -p p2p-daemon active_session`.
- [x] Run `cargo test -p p2p-signaling`.
- [x] Run daemon/signaling clippy with `-D warnings`.

---

# Task 3 - Expand answer status unit tests

## 3.1 Audit status creation paths

- [x] Review:
  - [x] `steady_state_for_role`,
  - [x] `write_steady_state_status`,
  - [x] `write_answer_registry_status`,
  - [x] `write_answer_status`,
  - [x] transport usable/unusable status helpers.

## 3.2 Test answer serving state with session-count matrix

- [x] Add or expand tests for answer status with:
  - [x] zero sessions,
  - [x] one session,
  - [x] multiple sessions.
- [x] Assert `current_state = "serving"` in all healthy answer cases.
- [x] Assert `active_session_count == sessions.len()`.
- [x] Assert `active_session_id` is null for zero and multiple sessions.
- [x] Assert `active_session_id` is populated only for exactly one session.

## 3.3 Test transport failure/recovery with answer zero sessions

- [x] Start from healthy answer steady state with zero sessions.
- [x] Mark transport unusable.
- [x] Assert:
  - [x] `mqtt_connected = false`,
  - [x] `current_state = "serving"`,
  - [x] `active_session_count = 0`.
- [x] Mark transport usable.
- [x] Assert:
  - [x] `mqtt_connected = true`,
  - [x] `current_state = "serving"`,
  - [x] no misleading session fields appear.

## 3.4 Test recoverable session failures return answer to serving

- [x] Expand or table-test recovery for:
  - [x] target connect failure,
  - [x] remote close,
  - [x] remote error,
  - [x] ICE failure,
  - [x] bridge task failure.
- [x] Assert answer role returns to `serving`, not `idle`.
- [x] Assert `mqtt_connected` preserves latest-known transport usability.

## 3.5 Validation

- [x] Run `cargo test -p p2p-daemon status`.
- [x] Run `cargo test -p p2p-daemon recovery_returns_to_serving`.
- [x] Run `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

---

# Task 4 - Expand `p2pctl status` rendering tests

## 4.1 Audit current rendering helper

- [x] Review `bins/p2pctl/src/main.rs`.
- [x] Confirm `render_status` is the test seam.
- [x] Identify behavior for missing or malformed JSON fields.

## 4.2 Test malformed and partial status fixtures

- [x] Missing `peer_id`.
- [x] Missing `role`.
- [x] Missing `mqtt_connected`.
- [x] Missing `current_state`.
- [x] Missing `active_session_count`.
- [x] Missing `session_capacity`.
- [x] Missing `sessions`.
- [x] `sessions` present but not an array.
- [x] Session entry missing `configured_forward_ids`.

Assert:

- [x] output is stable and human-readable,
- [x] missing scalar fields render as documented defaults,
- [x] missing sessions do not panic,
- [x] removed fields are not invented.

## 4.3 Test answer serving wording

- [x] Add explicit zero-session answer fixture with `state=serving`.
- [x] Assert output makes "serving, zero sessions" understandable.
- [x] Assert one-session and multi-session output still include session details.

## 4.4 Test old status compatibility

- [x] Add fixture representing an older status file without `session_capacity`.
- [x] Add fixture representing old single-session `active_session_id`.
- [x] Assert rendering remains useful and does not panic.

## 4.5 Validation

- [x] Run `cargo test -p p2pctl`.
- [x] Run `cargo clippy -p p2pctl --all-targets --all-features -- -D warnings`.

---

# Task 5 - Expand canonical documentation guard tests

## 5.1 Audit canonical docs

- [x] Review:
  - [x] `README.md`,
  - [x] `docs/SPECS.md`,
  - [x] `docs/V03_SPEC.md`.
- [x] Identify phrases that would contradict current v0.3 behavior if presented as current.

## 5.2 Guard `docs/SPECS.md`

- [x] Keep the existing stale-string guard.
- [x] Expand it to assert current behavior is present:
  - [x] multiple authorized offer peers per answer daemon,
  - [x] at most one active unrelated session per `peer_id`,
  - [x] unknown-session non-offer ignored/rejected,
  - [x] answer `serving` with zero or more sessions.

## 5.3 Guard `README.md`

- [x] Assert README describes current v0.3 multi-peer behavior.
- [x] Assert README does not present "multiple simultaneous WebRTC peer sessions out of scope" as current.
- [x] Assert README does not present a global "one active peer tunnel session at a time" rule as current.

## 5.4 Guard `docs/V03_SPEC.md`

- [x] Assert V03 spec retains multi-session answer behavior.
- [x] Assert V03 spec documents answer `serving` with zero or more sessions.
- [x] Assert V03 spec documents unknown-session non-offer routing policy.

## 5.5 Validation

- [x] Run `cargo test -p p2p-daemon canonical`.
- [x] Run `cargo clippy -p p2p-daemon --all-targets --all-features -- -D warnings`.

---

# Task 6 - Unit-test in-memory transport fault helpers

## 6.1 Audit helper boundaries

- [x] Review `crates/p2p-daemon/tests/two_node_daemon.rs`.
- [x] Identify testable pieces:
  - [x] `TransportTrace`,
  - [x] `TransportFaultControl`,
  - [x] `decrement_fault`,
  - [x] drop/delay/duplicate/publish-failure behavior,
  - [x] deterministic `unused_local_port` helper.

## 6.2 Test fault counter behavior

- [x] Add unit-style tests for one-shot decrement.
- [x] Add tests for counted failures greater than one.
- [x] Assert counters are removed when exhausted.
- [x] Assert unrelated routes are unaffected.

## 6.3 Test trace recording

- [x] Assert successful publish attempts are recorded with `delivered = true`.
- [x] Assert injected publish failures are recorded with `delivered = false`.
- [x] Assert payloads are recorded by recipient for decode assertions.

## 6.4 Test route-scoped fault isolation

- [x] Configure two routes.
- [x] Inject a fault on one route.
- [x] Assert the other route still delivers normally.
- [x] Assert duplicate and drop behavior only affects the selected route.

## 6.5 Test deterministic test-port helper

- [x] Assert repeated calls return distinct ports.
- [x] Assert returned ports can be bound immediately after allocation.
- [x] Avoid relying on global external network state.

## 6.6 Validation

- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.
- [x] Run `cargo clippy -p p2p-daemon --test two_node_daemon --all-features -- -D warnings`.

---

# Task 7 - Add status JSON compatibility tests

## 7.1 Audit status schema

- [x] Review `crates/p2p-daemon/src/status.rs`.
- [x] Review `bins/p2pctl/src/main.rs`.
- [x] Identify stable public status fields.

## 7.2 Test current schema invariants

- [x] Assert current daemon status includes:
  - [x] `peer_id`,
  - [x] `role`,
  - [x] `mqtt_connected`,
  - [x] `current_state`,
  - [x] `active_session_count`,
  - [x] `session_capacity`,
  - [x] `sessions`,
  - [x] `configured_forwards`.
- [x] Assert per-session status includes:
  - [x] `session_id`,
  - [x] `remote_peer_id`,
  - [x] `state`,
  - [x] `data_channel_open`,
  - [x] `configured_forward_ids`.

## 7.3 Test removed/misleading fields remain absent

- [x] Assert daemon status does not emit `active_stream_count`.
- [x] Assert daemon status does not emit `open_forward_ids`.
- [x] Assert session status does not emit `active_stream_count`.
- [x] Assert session status does not emit `open_forward_ids`.

## 7.4 Test status serialization for edge cases

- [x] Zero configured forwards.
- [x] Multiple configured forwards.
- [x] Multiple sessions with different configured forward IDs.
- [x] Disconnected transport with active sessions.

## 7.5 Validation

- [x] Run `cargo test -p p2p-daemon status`.
- [x] Run `cargo test -p p2pctl status`.

---

# Task 8 - Checklist and validation

## 8.1 Update this TODO as tests land

- [x] Mark completed tasks and subtasks.
- [x] If a test exposes a real bug, add a short note describing the bug and fix.
- [x] If a proposed unit test is replaced by a better equivalent, document the replacement.

## 8.2 Final targeted validation

- [x] Run `cargo test -p p2p-daemon`.
- [x] Run `cargo test -p p2p-daemon --test two_node_daemon`.
- [x] Run `cargo test -p p2pctl`.
- [x] Run `cargo test -p p2p-signaling`.

## 8.3 Final workspace validation

- [x] Run `cargo fmt --all --check`.
- [x] Run `cargo clippy --workspace --all-targets --all-features -- -D warnings`.
- [x] Run `cargo test --workspace --all-targets`.

---

# Acceptance checklist

Mark this TODO complete only when all items are true:

- [x] Unknown-session non-offer routing is table-tested across all signaling message types.
- [x] Same-peer unknown-session `Offer` policy remains covered.
- [x] Known-session routing and ACK behavior remain covered.
- [x] Replay duplicate same-session and different-session behavior have focused tests.
- [x] Answer `serving` status semantics are covered for zero, one, and many sessions.
- [x] `p2pctl status` handles missing/partial status fields gracefully.
- [x] Canonical docs are guarded against stale current-behavior claims.
- [x] In-memory transport fault helper behavior is unit-tested.
- [x] Status schema compatibility and removed-field invariants are covered.
- [x] Targeted tests pass.
- [x] Full workspace fmt, clippy, and tests pass.
