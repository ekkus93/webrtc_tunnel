# UNIT_TEST3_TODO.md

# Unit Test Coverage Expansion TODO 3

## Goal

Implement high-value unit-test coverage for five uncovered or under-covered areas:

1. `p2p-signaling` wire codec edge cases (`messages.rs`, `envelope.rs`)
2. `p2p-signaling` replay-cache eviction/pruning behavior (`replay.rs`)
3. `p2p-signaling` ACK byte-identity retry invariants (`ack.rs`)
4. `p2p-mobile` JNI boundary negative/contract behavior (`lib.rs`)
5. Android repository negative path for answer-start failures (`TunnelRepositoryTest`)

This TODO is test-focused. Production changes are allowed only when required to make behavior testable and must preserve runtime behavior and protocol semantics.

---

## Guardrails

- [x] Preserve protocol/security behavior from `docs/SPECS.md`; do not weaken fail-closed checks.
- [x] Keep tests deterministic and offline (no live MQTT broker/network dependency).
- [x] Prefer unit tests in crate-local `#[cfg(test)]` or `crates/*/tests` unless integration behavior is explicitly required.
- [x] Avoid broad mocking; use focused fakes/stubs or existing helpers.
- [x] Keep assertions behavior-focused (protocol invariants, error surfaces, state transitions).
- [x] Do not suppress warnings; keep strict clippy compatibility.
- [x] For JNI tests, avoid assumptions about Android framework presence unless already guaranteed by existing test setup.

---

## Phase 0 - Baseline and harness prep

### 0.1 Inventory current tests and helpers

- [x] Review existing signaling tests:
  - [x] `crates/p2p-signaling/tests/timestamp_and_replay.rs`
  - [x] `crates/p2p-signaling/tests/mock_mqtt_roundtrip.rs`
  - [x] in-file unit tests under `crates/p2p-signaling/src/*.rs`
- [x] Review existing mobile/JNI tests:
  - [x] `crates/p2p-mobile/src/lib.rs` test module
  - [x] `crates/p2p-mobile/src/runtime.rs` tests
- [x] Review Android repository tests:
  - [x] `android/app/src/test/java/com/phillipchin/webrtctunnel/data/TunnelRepositoryTest.kt`

### 0.2 Decide test placement and naming

- [x] Confirm per-target file/test-file placement:
  - [x] `messages.rs`/`envelope.rs`: in-file `#[cfg(test)]` or new `crates/p2p-signaling/tests/*`
  - [x] `replay.rs`: in-file tests near `ReplayCache`
  - [x] `ack.rs`: extend existing in-file tests
  - [x] JNI contract tests: extend `crates/p2p-mobile/src/lib.rs` tests
  - [x] Android answer-negative path: extend `TunnelRepositoryTest.kt`
- [x] Define deterministic test names with scenario intent (e.g., `decode_rejects_unknown_message_type`).

### 0.3 Baseline validation

- [x] Run:
  - [x] `cargo fmt --check`
  - [x] `cargo clippy -p p2p-signaling --all-targets --all-features -- -D warnings`
  - [x] `cargo clippy -p p2p-mobile --all-targets --all-features -- -D warnings`
  - [x] `cargo test -p p2p-signaling --all-targets`
  - [x] `cargo test -p p2p-mobile --all-targets`
  - [x] `cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest`

---

## Phase 1 - `p2p-signaling` wire codec edge cases (`messages.rs`, `envelope.rs`)

### 1.1 Messages decode/encode edge-case matrix

- [x] Enumerate message-type parsing boundaries:
  - [x] unknown/unsupported message-type byte
  - [x] known type with truncated body
  - [x] known type with invalid body encoding
  - [x] known type with extra trailing bytes (if applicable)
- [x] Enumerate wire-header boundaries:
  - [x] payload shorter than required header
  - [x] declared length mismatch (short and long)
  - [x] zero-length body where body is required

### 1.2 Implement message codec tests

- [x] Add test: unknown message type is rejected with explicit error.
- [x] Add test: truncated frame/body fails decode.
- [x] Add test: invalid structured body fails decode (no silent fallback).
- [x] Add test: valid message roundtrip preserves key fields (`msg_id`, session, sender/recipient semantics where encoded).
- [x] Add test: body length mismatch fails deterministically.

### 1.3 Envelope validation edge cases

- [x] Add test: malformed envelope bytes are rejected.
- [x] Add test: missing required envelope fields are rejected.
- [x] Add test: unknown envelope fields are rejected (if `deny_unknown_fields` is expected).
- [x] Add test: invalid signature/auth metadata shape fails before processing.
- [x] Add test: envelope encode/decode roundtrip preserves authenticated metadata fields.

### 1.4 Assertions and error contracts

- [x] Ensure tests assert concrete failure category (not only “is_err”).
- [x] Ensure no test accepts partial decode success with silently dropped fields.

### 1.5 Phase validation

- [x] Run:
  - [x] `cargo test -p p2p-signaling --all-targets`
  - [x] `cargo clippy -p p2p-signaling --all-targets --all-features -- -D warnings`

---

## Phase 2 - `ReplayCache` eviction and pruning behavior (`replay.rs`)

### 2.1 Build replay-cache scenario matrix

- [x] Define scenarios for:
  - [x] duplicate key insertion before TTL expiry
  - [x] key expiry at TTL boundary
  - [x] over-capacity insertion causing eviction
  - [x] stale queue entries where map has newer value for same key
  - [x] monotonic and non-monotonic timestamp inputs (if supported behavior is defined)

### 2.2 Implement replay-cache unit tests

- [x] Add test: duplicate within active window is rejected as replay.
- [x] Add test: expired entry is no longer replay after prune window passes.
- [x] Add test: prune removes oldest eligible entries when cache exceeds max size.
- [x] Add test: prune does not remove newer map value due to stale deque entry.
- [x] Add test: cache size remains bounded after repeated insert/prune cycles.

### 2.3 Boundary and invariants

- [x] Assert exact cache cardinality after each critical operation.
- [x] Assert key-specific behavior (not only aggregate counts).

### 2.4 Phase validation

- [x] Run:
  - [x] `cargo test -p p2p-signaling --all-targets replay`
  - [x] `cargo test -p p2p-signaling --all-targets`
  - [x] `cargo clippy -p p2p-signaling --all-targets --all-features -- -D warnings`

---

## Phase 3 - `AckTracker` byte-identity retry invariants (`ack.rs`)

### 3.1 Extend ACK retry behavior matrix

- [x] Confirm required ACK/non-ACK message types used in tests.
- [x] Define byte-identity invariants:
  - [x] retry payload bytes are identical to initially registered bytes
  - [x] only MQTT transport metadata may differ externally (out of scope for tracker payload)

### 3.2 Implement ACK invariant tests

- [x] Add test: first retry payload equals original payload byte-for-byte.
- [x] Add test: every retry up to retry limit remains byte-identical.
- [x] Add test: retry schedule updates only `sent_at_ms`/`retries`, not payload.
- [x] Add test: acknowledgement removes pending entry so no further retries are returned.
- [x] Add test: entries at retry limit are reported expired and no retransmit emitted.

### 3.3 Negative/regression assertions

- [x] Assert that mutating external source buffer post-register does not alter stored payload (copy semantics).
- [x] Assert mixed pending entries preserve identity independently.

### 3.4 Phase validation

- [x] Run:
  - [x] `cargo test -p p2p-signaling --all-targets ack`
  - [x] `cargo test -p p2p-signaling --all-targets`
  - [x] `cargo clippy -p p2p-signaling --all-targets --all-features -- -D warnings`

---

## Phase 4 - `p2p-mobile` JNI boundary contract tests (`lib.rs`)

### 4.1 JNI surface inventory

- [x] Enumerate exported JNI methods in `crates/p2p-mobile/src/lib.rs`, including:
  - [x] status/log accessors
  - [x] error retrieval (`nativeLastError` path)
  - [x] config/identity validation wrapper(s)
  - [x] identity generation wrapper(s)
  - [x] lifecycle/dispose edge behavior
- [x] Document expected JSON schema/strings for each method (success and failure).

### 4.2 Negative and malformed-input tests

- [x] Add test: invalid JSON input to validation wrapper returns explicit failure object/message.
- [x] Add test: missing required JSON fields returns explicit failure.
- [x] Add test: malformed UTF-8/string conversion failures are surfaced (where testable).
- [x] Add test: method invoked after dispose fails in defined manner (if contract requires).

### 4.3 Contract-shape tests

- [x] Add test: `nativeLastError` output shape/keys are stable for no-error and error cases.
- [x] Add test: status accessor returns parseable expected JSON structure.
- [x] Add test: generate identity wrapper returns expected fields and non-empty values.
- [x] Add test: validation wrapper success and failure outputs include required keys.

### 4.4 Idempotency and state isolation

- [x] Add test: repeated calls to read-only JNI accessors are stable and side-effect free.
- [x] Add test: one failing call does not poison unrelated subsequent success paths.

### 4.5 Phase validation

- [x] Run:
  - [x] `cargo test -p p2p-mobile --all-targets`
  - [x] `cargo clippy -p p2p-mobile --all-targets --all-features -- -D warnings`

---

## Phase 5 - Android `TunnelRepository` answer-start negative path tests

Target file: `android/app/src/test/java/com/phillipchin/webrtctunnel/data/TunnelRepositoryTest.kt`

### 5.1 Extend fake bridge behavior

- [x] Ensure fake bridge can independently fail:
  - [x] `startOffer`
  - [x] `startAnswer`
  - [x] `stop`
  - [x] `status/log` retrieval as needed
- [x] Ensure failure injection is deterministic and per-call configurable.

### 5.2 Add answer-start failure tests

- [x] Add test: `start(Answer, ...)` failure returns `Result.failure`.
- [x] Add test: answer-start failure updates/retains repository status safely (no invalid Running state).
- [x] Add test: answer-start failure does not clear/overwrite previous valid status unexpectedly.
- [x] Add test: answer-start failure propagates actionable exception/message.

### 5.3 Parity and regression coverage

- [x] Add parity test ensuring offer/answer failure behavior is consistent where intended.
- [x] Add test that successful answer-start still refreshes status (to guard against over-fix).

### 5.4 ViewModel propagation spot-check (if needed)

- [x] If repository tests reveal ambiguity, add/adjust `AppViewModelsTest` assertion that answer-start errors surface correctly to UI state (not needed; repository behavior is unambiguous).

### 5.5 Phase validation

- [x] Run:
  - [x] `cd android && ./gradlew --no-daemon testDebugUnitTest --tests \"*TunnelRepositoryTest*\"`
  - [x] `cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest`

---

## Phase 6 - Cross-phase integration and final acceptance

### 6.1 Final targeted suites

- [x] Run:
  - [x] `cargo test -p p2p-signaling --all-targets`
  - [x] `cargo test -p p2p-mobile --all-targets`
  - [x] `cd android && ./gradlew --no-daemon testDebugUnitTest`

### 6.2 Full workspace quality gate

- [x] Run:
  - [x] `cargo fmt --check`
  - [x] `cargo clippy --workspace --all-targets --all-features -- -D warnings`
  - [x] `cargo test --workspace --all-targets`
  - [x] `cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest connectedDebugAndroidTest`

### 6.3 Acceptance checklist

- [x] Wire codec edge cases are covered with explicit decode-failure assertions.
- [x] Replay cache eviction/prune semantics are directly unit-tested.
- [x] ACK retransmit payload identity is enforced by tests.
- [x] JNI boundary negative/contract behavior has deterministic coverage.
- [x] Android answer-start failure path is covered and behavior-safe.
- [x] No new lint warnings/errors introduced.
- [x] No compatibility/security invariants were weakened.
