# Unit Test Coverage Gaps — TODO (Batch 1)

## 0. Instructions for Claude Code

This TODO tracks a coverage audit of the webrtc_tunnel workspace (Rust
crates/bins under `crates/`, `bins/`, and the Android app under `android/`).
It was produced by surveying which non-trivial source files have no
corresponding unit tests, or have tests that only cover the happy path.
**No test-writing has started yet** — this is planning only.

Read first, per task, whichever of these applies:

```text
crates/p2p-core/src/config/paths.rs
crates/p2p-core/src/config/forward.rs
crates/p2p-crypto/src/public_identity.rs
crates/p2p-crypto/src/authorized_keys.rs
crates/p2p-crypto/src/identity.rs
crates/p2p-signaling/src/transport/codec.rs
crates/p2p-signaling/src/transport/mqtt.rs
crates/p2p-webrtc/src/data_channel.rs
crates/p2p-daemon/src/predicates.rs
bins/p2p-offer/src/main.rs
bins/p2p-answer/src/main.rs
bins/p2pctl/src/main.rs
android/app/src/main/java/com/phillipchin/webrtctunnel/data/SensitiveDataRedactor.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupIdentityController.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/LogsViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupStepValidation.kt
```

### Non-negotiable rules (per project CLAUDE.md)

- Work directly on `master`. Do not create a feature branch unless
  explicitly told to. Only commit/push when explicitly asked.
- No `Co-Authored-By:`/AI-attribution trailers in commit messages.
- **Never suppress a linter to make it pass** — no `#[allow(...)]`,
  `@Suppress`, ktlint-disable comments, baseline files, or lowered rule
  severities. Fix the real issue or ask.
- This workspace **forbids `unsafe` code** (`unsafe_code = "forbid"` in the
  root `Cargo.toml`). Any test needing process-wide env-var mutation
  (`std::env::set_var`/`remove_var`, unsafe as of Rust 1.82) must use a
  safe workaround (e.g. spawn a child process with `Command::env`, which
  only sets the child's environment) — see
  `crates/p2p-daemon/src/notify.rs`'s test module for a worked example of
  this exact pattern.
- Run before every commit:
  - Rust: `cargo fmt --all --check`, then
    `cargo clippy --workspace --all-targets --all-features -- -D warnings`,
    then `cargo test --workspace --all-targets --all-features`.
  - Android: `cd android && ./gradlew ktlintCheck detekt lintDebug
    testDebugUnitTest` (or `./gradlew check`).
- Prefer one focused commit per task (matching the pattern used for the
  service-lifecycle work in `docs/WEBRTC_TUNNEL_SERVICE_LIFECYCLE_TODO.md`).
- Do not weaken production code to make it more testable in ways that
  change real behavior (e.g. don't loosen a validation check just to
  simplify a test). If a function is genuinely hard to unit test as
  structured, prefer restructuring it behind a clean internal seam over
  skipping the test.

### Priority definitions

```text
P0 = untested code that parses untrusted input, enforces a security
     control, or handles secret material — a bug here is a real
     vulnerability or a silent secret leak.
P1 = untested code with meaningful blast radius (crypto onboarding UX,
     protocol state machines, CLI data-integrity operations) but lower
     or more contained risk than P0.
P2 = minor/edge-case gaps — low blast radius, worth closing for
     completeness but not urgent.
```

---

# P0 tasks

## P0-001 — `SensitiveDataRedactor` regex suite (Android)

### Files

Create:

```text
android/app/src/test/java/com/phillipchin/webrtctunnel/data/SensitiveDataRedactorTest.kt
```

Read:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/SensitiveDataRedactor.kt
```

### Goal

This class redacts secret material (private key PEM/TOML blocks, broker
passwords, bearer/auth tokens, SDP bodies, decrypted tunnel payload bytes,
identity file paths) out of text before it is exported/shared as
diagnostics. **It currently has zero tests.** A single regex ordering bug
or overly-narrow pattern means real secrets ship in exported diagnostics
with nothing to catch it.

### Required test cases

For each redaction rule the class implements (read the file first to get
the exact list — do not guess at rule names), add at least:

- [ ] A **positive** case: input containing the real secret shape is fully
      redacted (assert the secret substring is *absent* from the output,
      not just that *some* redaction happened).
- [ ] A **boundary** case: secret-like text embedded inside otherwise
      benign surrounding text (e.g. a private key block in the middle of a
      multi-line log dump) — assert only the secret span is redacted and
      surrounding text survives.
- [ ] A **near-miss** case designed to catch an overly broad regex: text
      that looks similar to the secret pattern but isn't one (e.g. a
      hex-looking string that isn't actually a key) — assert it is *not*
      redacted if it shouldn't be, or *is* redacted if the class
      intentionally over-redacts for safety (confirm which behavior is
      intended by reading the code, then test that intent explicitly).
- [ ] Multiple distinct secrets of different rule types in the same input
      blob — assert all are redacted, not just the first match (a common
      bug class with global-vs-first-match regex usage).
- [ ] Empty string / no-secrets input — output equals input unchanged.
- [ ] Idempotency: redacting already-redacted output doesn't corrupt it
      further (running the function twice gives the same result as once).

### Acceptance criteria

- [ ] Every distinct redaction rule in the class has at least one positive
      test.
- [ ] At least one test proves multiple secrets in one input are all
      caught.
- [ ] `./gradlew testDebugUnitTest` passes.
- [ ] `./gradlew ktlintCheck detekt` clean on the new file.

---

## P0-002 — `PublicIdentity::parse` malformed-input tests (p2p-crypto)

### Files

Modify:

```text
crates/p2p-crypto/src/public_identity.rs
```

or add a dedicated integration test file:

```text
crates/p2p-crypto/tests/public_identity_parsing.rs
```

(Check whether `crates/p2p-crypto/tests/identity_parsing.rs` already
covers `IdentityFile` rather than `PublicIdentity` — if so, this is a
separate concern and a new file is appropriate; do not conflate the two.)

### Goal

`PublicIdentity::parse` (and whatever `AuthorizedKeys::parse` calls per
line) is the parser for **untrusted, user-supplied `authorized_keys`
content** — a file a peer's operator edits by hand or pastes from another
device. It currently only has round-trip coverage (`generate → render →
parse` on well-formed input) plus one duplicate-key test. There is no
coverage of a hand-corrupted or truncated file, which is the realistic
threat/failure model for this parser.

### Required test cases

Read the file to enumerate the exact fields/tokens expected (format
marker, peer_id, signing key, kex key, any version/algorithm tags), then
add a malformed-input test for each of these failure classes:

- [ ] Missing format marker / wrong marker string.
- [ ] Missing a required field (each required field, one at a time —
      table-driven if the test harness supports it).
- [ ] Invalid base64 in a key field (non-base64 characters).
- [ ] Valid base64 but wrong decoded byte length for a key field (too
      short and too long).
- [ ] Unknown/unsupported algorithm tag, if the format encodes one.
- [ ] Empty input.
- [ ] Only whitespace/comments, no actual identity line.
- [ ] Trailing garbage after an otherwise-valid entry.
- [ ] Extremely long line (defend against unbounded-allocation concerns —
      assert it errors cleanly rather than hangs/panics; skip if the
      parser is already bounded by line-based reading with a sane limit,
      but confirm that explicitly rather than assuming).

Every case must assert a specific `Err(...)` variant is returned (or a
documented panic-free failure path) — not just "it errors," but the
*correct* error, so a future refactor that silently changes behavior
(e.g. from a clear "bad base64" error to a generic "parse failed") is
caught by the test.

### Acceptance criteria

- [ ] Each failure class above has its own test function (clear names,
      e.g. `parse_rejects_truncated_signing_key`).
- [ ] No test causes a panic — the parser must return `Result::Err` for
      all malformed input, never `unwrap`/index-panic internally. If a
      test reveals a panic, that is itself a bug to fix as part of this
      task (fix the parser, not just document the crash).
- [ ] `cargo test -p p2p-crypto` passes.
- [ ] `cargo clippy -p p2p-crypto --all-targets --all-features -- -D warnings`
      clean.

---

## P0-003 — `validate_non_world_writable` positive-enforcement test (p2p-core)

### Files

Modify:

```text
crates/p2p-core/src/config/paths.rs
```

(add an in-file `#[cfg(test)] mod tests` block if one doesn't already
exist near this function, or extend the existing one).

### Goal

`validate_non_world_writable` is the actual security control behind the
`refuse_world_writable_paths` config flag. The existing test only proves
the *bypass* path (flag disabled → no check runs). There is no test that
the check actually **catches** a world-writable file/directory when the
flag is enabled — meaning a broken permission-bit mask (e.g. checking the
wrong octal bit, or an off-by-one in bit shifting) would currently pass
CI silently.

### Required test cases

Using `tempfile`/`std::fs` with explicit `set_permissions`:

- [ ] A file with mode `0o644` (not world-writable) passes when the flag
      is enabled.
- [ ] A file with mode `0o646` / `0o666` (world-writable) is rejected when
      the flag is enabled, with the expected error variant.
- [ ] A directory in the path chain that is world-writable is rejected
      (if the function walks ancestor directories — confirm by reading the
      code first; only write this case if applicable).
- [ ] World-writable-but-flag-disabled still passes (this restores/keeps
      the existing bypass-path test — don't drop it).
- [ ] Path whose immediate parent directory doesn't exist yet (confirm and
      test whatever the function's documented behavior is for this case —
      error vs. skip-check — don't assume; read the code).
- [ ] Group-writable-but-not-world-writable (mode `0o664`) is **not**
      rejected (proves the check targets the world bit specifically, not
      group).

### Acceptance criteria

- [ ] A world-writable path is demonstrably rejected by an enabled test
      (this is the core gap being closed).
- [ ] The group-vs-world bit distinction is explicitly tested (prevents a
      regression that's too strict or too loose by one bit).
- [ ] `cargo test -p p2p-core` passes.
- [ ] Tests clean up temp files/dirs (use `tempfile::tempdir()`, not
      hand-rolled paths in `/tmp`, consistent with existing test
      conventions in the workspace).

---

# P1 tasks

## P1-001 — `SetupIdentityController` generate/import coverage (Android)

### Files

Modify:

```text
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/SetupViewModelTest.kt
```

or create a dedicated:

```text
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/SetupIdentityControllerTest.kt
```

(prefer a dedicated file if `SetupIdentityController` is a standalone
class with its own constructor/dependencies distinct from
`SetupViewModel` — check before deciding; follow whichever pattern the
existing `viewmodel/` test files use for controller classes embedded in a
larger ViewModel).

### Goal

`generateIdentity()`, `importIdentityFromUri()`, and
`importPublicIdentityFromUri()` — the actual crypto onboarding paths a
new user hits on first run — have no test coverage today. Only
`importIdentityFromPath()` and `validateRemotePublicIdentity()` get a
single happy-path call each, from `SetupViewModelTest`.

### Required test cases

- [ ] `generateIdentity()` success path: assert the resulting identity is
      persisted/exposed correctly and the busy flag clears.
- [ ] `generateIdentity()` while already busy (re-entrancy guard via
      `launchBusy`) — assert a second concurrent call is rejected/ignored
      rather than corrupting state or running twice.
- [ ] `importIdentityFromUri()` success path with a well-formed identity
      TOML behind a fake `Uri`/`ContentResolver`.
- [ ] `importIdentityFromUri()` with an unreadable URI (resolver throws /
      returns null stream) — assert a clear error surfaces, no crash.
- [ ] `importIdentityFromUri()` with malformed identity content behind a
      valid URI — assert the parse error is surfaced, not swallowed.
- [ ] `importPublicIdentityFromUri()` success and the same
      unreadable/malformed failure modes as above.
- [ ] Busy-guard reentrancy for the import functions, same as
      `generateIdentity()`.

### Acceptance criteria

- [ ] Every public entry point on the identity-onboarding path has at
      least one success and one failure test.
- [ ] Busy-guard reentrancy is explicitly tested for at least one of the
      three functions (ideally all three, but don't skip this on all of
      them).
- [ ] `./gradlew testDebugUnitTest` passes.

---

## P1-002 — `p2pctl add_authorized_key` duplicate-detection test

### Files

Modify:

```text
bins/p2pctl/src/main.rs
```

(extend the existing `#[cfg(test)] mod tests` block — `write_identity_files`
and status-rendering tests already live there; follow that style).

### Goal

`add_authorized_key()` reads the existing `authorized_keys` file and is
expected to reject/handle a peer that's already present, to prevent
silent duplication or conflicting entries for the same peer_id. This
logic has no test today.

### Required test cases

- [ ] Adding a new peer to an existing non-empty `authorized_keys` file
      appends correctly and leaves prior entries untouched.
- [ ] Adding a peer whose `peer_id` already exists — assert the documented
      behavior (reject with a clear error, or replace — read the code
      first to determine which is intended, then test that exact
      behavior; do not assume).
- [ ] Adding to a missing/empty file — assert it creates the file
      correctly rather than erroring.
- [ ] Adding a malformed public-identity input (reuse malformed-input
      shapes from P0-002 if `add_authorized_key` calls the same parser) —
      assert a clear error, no partial/corrupt file write.

### Acceptance criteria

- [ ] Duplicate-peer behavior is pinned down by a test matching the
      code's actual documented/intended semantics.
- [ ] No test leaves a partially-written `authorized_keys` file on disk
      after a failure case (assert the file is either fully updated or
      untouched — no half-written state).
- [ ] `cargo test -p p2pctl` passes.

---

## P1-003 — `LogsViewModel` test suite (Android)

### Files

Create:

```text
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/LogsViewModelTest.kt
```

### Goal

`exportDiagnostics()`/`exportDiagnosticsToUri()` have no tests: the
busy-guard preventing concurrent exports, success/failure message
branching, and the URI `runCatching`/`openOutputStream` error path are
all unverified.

### Required test cases

- [ ] `exportDiagnostics()` success path — assert the expected
      success message/state.
- [ ] `exportDiagnosticsToUri()` success path with a fake writable `Uri`.
- [ ] `exportDiagnosticsToUri()` when `openOutputStream` throws/returns
      null — assert a clear failure message, no crash.
- [ ] Concurrent export re-entrancy: calling export again while one is
      already in flight is rejected/ignored, matching whatever guard
      pattern `SetupIdentityController`'s `launchBusy` uses (check for a
      shared helper — if `LogsViewModel` has its own separate guard
      implementation, test that one specifically).
- [ ] `filteredLogs` (referenced in the class's nested lambdas per the
      build output) — if this is a meaningful filter (level/search-term),
      add a test for at least one non-trivial filter case; skip if it's a
      trivial passthrough (confirm by reading first).

### Acceptance criteria

- [ ] Both export entry points have a success and a failure test.
- [ ] Re-entrancy guard is tested.
- [ ] `./gradlew testDebugUnitTest` passes.

---

## P1-004 — `DataChannelHandle` state-machine tests (p2p-webrtc)

### Files

Modify:

```text
crates/p2p-webrtc/src/data_channel.rs
```

(add a `#[cfg(test)] mod tests` block if none exists).

### Goal

This file has no tests at all today. `wait_for_open`'s
loop-until-open-or-closed-with-timeout logic, and the open/close/message
event dispatch into the internal `mpsc` channel (including
drop-behavior when the channel is full or the receiver is gone), are real
state-transition logic that end-to-end daemon tests only exercise
indirectly (a bug here could be masked by a retry elsewhere in the
stack).

### Required test cases

Read the file first to determine what's actually mockable without a real
`RTCDataChannel` — the goal is unit-level coverage of the Rust-side
dispatch logic, not re-testing the underlying WebRTC library:

- [ ] `wait_for_open` returns promptly once an "open" event is dispatched.
- [ ] `wait_for_open` returns/errors appropriately if a "close" event
      arrives before "open" (channel never opened).
- [ ] `wait_for_open` respects its timeout when neither event arrives.
- [ ] Message dispatch delivers payloads to the `mpsc` receiver in order.
- [ ] Behavior when the receiver has been dropped (sender-side send
      failure) — assert it doesn't panic.
- [ ] Behavior when the internal buffer/channel is at capacity (if
      bounded) — assert the documented backpressure/drop behavior.

If the type genuinely cannot be constructed without a live WebRTC
connection (no seam exists to inject test events), do not force a test
by weakening the type's API — instead, add a thin internal constructor
or event-injection seam gated `#[cfg(test)]` (`pub(crate)`, not `pub`),
matching the pattern `IceStateInjectorForTests` already uses elsewhere in
this crate for the same problem.

### Acceptance criteria

- [ ] At least the timeout and ordering behaviors above are covered
      without relying on a real WebRTC connection.
- [ ] Any new test-only seam is `#[cfg(test)]`/`pub(crate)`-scoped, not
      exposed in the public API.
- [ ] `cargo test -p p2p-webrtc` passes.

---

## P1-005 — `authorized_keys` duplicate signing-key rejection test (p2p-crypto)

### Files

Modify:

```text
crates/p2p-crypto/src/authorized_keys.rs
```

### Goal

`seen_signing_keys` tracks signing keys across entries to reject a
duplicate *signing key* under a *different* `peer_id` (key confusion /
impersonation-adjacent bug class). Only duplicate-`peer_id` rejection is
currently tested; the signing-key-reuse path is not.

### Required test cases

- [ ] Two entries with different `peer_id`s but the same signing key —
      assert parsing rejects this with the expected error.
- [ ] Two entries with the same `peer_id` and same signing key (the
      already-tested case) — keep/confirm this still passes.
- [ ] Two entries with different `peer_id`s and different signing keys —
      both accepted normally (sanity/negative-control case).

### Acceptance criteria

- [ ] Signing-key reuse across distinct peer_ids is proven to be rejected.
- [ ] `cargo test -p p2p-crypto` passes.

---

## P1-006 — Identity file parsing error-path tests (p2p-crypto)

### Files

Modify:

```text
crates/p2p-crypto/src/identity.rs
```

or extend:

```text
crates/p2p-crypto/tests/identity_parsing.rs
```

### Goal

`IdentityFile` parsing (`from_toml`/`from_file`) only has a
mismatched-key-pair test today. Unsupported format version and unknown
signing/kex algorithm tags aren't directly tested.

### Required test cases

- [ ] Unsupported/missing format marker in the identity TOML.
- [ ] Unknown `sign.alg` value.
- [ ] Unknown `kex.alg` value.
- [ ] Truncated/invalid base64 in a key field (mirror the shapes from
      P0-002 for consistency).
- [ ] Existing mismatched-key-pair test is retained (don't regress it
      while adding these).

### Acceptance criteria

- [ ] Each unsupported/malformed case above has its own test with a
      specific expected error.
- [ ] `cargo test -p p2p-crypto` passes.

---

# P2 tasks

## P2-001 — `SignalCodec` minor branch coverage

### Files

Modify:

```text
crates/p2p-signaling/src/transport/tests/codec.rs
```

### Goal

Two branches are unexercised: `message.version != 1` (protocol version
mismatch) and recipient-peer-id mismatch (message addressed to a
different peer than the decoding node).

### Required test cases

- [ ] Decode a message with an unsupported/future version number —
      assert the expected rejection.
- [ ] Decode a message addressed (encrypted/signed) to a different
      recipient peer_id than the one decoding it — assert rejection.

### Acceptance criteria

- [ ] Both branches have an explicit test.
- [ ] `cargo test -p p2p-signaling` passes.

---

## P2-002 — MQTT transport option-building edge cases

### Files

Modify:

```text
crates/p2p-signaling/src/transport/tests/mqtt_options.rs
```

### Goal

`build_mqtt_options`/`build_tls_transport` are well covered for the
TLS/auth-required branches, but a few option-building edges are
untested (low risk since config-level validation also enforces some of
these, but worth closing for completeness).

### Required test cases

- [ ] Invalid QoS byte value (`qos_from_u8` out-of-range) is rejected.
- [ ] `require_mqtt_tls = false` produces a plain (non-TLS) transport
      configuration.
- [ ] Non-`mqtts://` URL scheme handling matches documented behavior.
- [ ] `insecure_skip_verify = true` is rejected at this layer too (defense
      in depth alongside the config-level check, if this layer
      independently enforces it — confirm by reading the code first).

### Acceptance criteria

- [ ] Each case above has an explicit test.
- [ ] `cargo test -p p2p-signaling` passes.

---

## P2-003 — `SetupStepValidation` edge cases (Android)

### Files

Modify:

```text
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/SetupViewModelTest.kt
```

or create a dedicated:

```text
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/SetupStepValidationTest.kt
```

### Goal

Broker-port-range validation (`1..65535`) and the "remote identity cannot
equal local identity" collision check are not directly tested (only
indirectly touched through broader `SetupViewModelTest` flows for other
validation rules).

### Required test cases

- [ ] Broker port `0` is rejected.
- [ ] Broker port `65536` (out of `u16` range as entered/parsed) is
      rejected.
- [ ] Broker port `1` and `65535` (the exact boundaries) are accepted.
- [ ] Remote peer identity equal to the local identity is rejected with
      the expected validation message.
- [ ] Remote peer identity different from local identity passes this
      specific check (sanity/negative control).

### Acceptance criteria

- [ ] Both validation rules have explicit boundary tests.
- [ ] `./gradlew testDebugUnitTest` passes.

---

## P2-004 — `p2p-offer`/`p2p-answer` `load_config`/`default_config_dir` tests

### Files

Modify:

```text
bins/p2p-offer/src/main.rs
bins/p2p-answer/src/main.rs
```

(add a `#[cfg(test)] mod tests` block to each — currently neither binary
has any tests).

### Goal

Small blast radius (CLI startup only) but currently zero coverage of the
config-path resolution logic: explicit `--config` path vs. the
`$HOME/.config/p2ptunnel/config.toml` default, and the missing-`HOME`
error path.

### Required test cases

Per binary:

- [ ] Explicit config path is used as-is when provided.
- [ ] Missing `--config` falls back to `$HOME/.config/p2ptunnel/config.toml`
      (use a scoped env-var override for the test — do not use
      `std::env::set_var` directly per the unsafe-code ban; if the
      function reads `HOME` via `std::env::var`, prefer refactoring
      `default_config_dir` to accept an injectable home-directory
      parameter for testability, defaulting to reading the real env var
      only at the call site in `main`/`run`).
- [ ] `HOME` unset (or the injected equivalent) produces the documented
      error, not a panic.

### Acceptance criteria

- [ ] Both binaries have these three cases covered.
- [ ] No test uses `std::env::set_var`/`remove_var` (forbidden `unsafe`
      code in this workspace) — use dependency injection or a child
      process instead, per the pattern in
      `crates/p2p-daemon/src/notify.rs`.
- [ ] `cargo test -p p2p-offer -p p2p-answer` passes.

---

## P2-005 — `p2pctl` `fingerprint`/`check_config` CLI wiring tests

### Files

Modify:

```text
bins/p2pctl/src/main.rs
```

### Goal

Thin CLI wiring, low risk, but currently untested; worth a basic smoke
test per subcommand for regression safety as the CLI evolves.

### Required test cases

- [ ] `fingerprint()` on a valid public identity file produces the
      expected fingerprint string format.
- [ ] `fingerprint()` on a missing/invalid file produces a clear error.
- [ ] `check_config()` on a valid config file succeeds.
- [ ] `check_config()` on an invalid/missing config file produces a clear
      error (reuse existing config-validation fixtures from
      `p2p-core`'s test suite if convenient, rather than duplicating
      sample configs).

### Acceptance criteria

- [ ] Each subcommand has a success and a failure test.
- [ ] `cargo test -p p2pctl` passes.

---

## P2-006 — `can_attempt_same_session_ice_restart` direct test (p2p-daemon)

### Files

Modify:

```text
crates/p2p-daemon/src/predicates.rs
```

### Goal

This predicate is exercised indirectly through integration tests but has
no direct unit test asserting its truth table against `ActiveSession`
state combinations.

### Required test cases

- [ ] Table-driven test covering each relevant `ActiveSession` state
      combination the predicate branches on (read the function to
      enumerate them — likely bridge state and/or session state
      variants), asserting the expected `true`/`false` result for each.

### Acceptance criteria

- [ ] The predicate's full decision table is covered by direct tests
      (not just indirectly via integration tests).
- [ ] `cargo test -p p2p-daemon --lib predicates::` passes.

---

## P2-007 — `ForwardTable::offer_listeners()` direct assertion (p2p-core)

### Files

Modify:

```text
crates/p2p-core/src/config/forward.rs
```

### Goal

The dedup/sort behavior of `offer_listeners()` is only exercised as a
side effect of other tests (its output is consumed, not directly
asserted). Add a direct test of its contract.

### Required test cases

- [ ] Multiple forwards with offer configs produce listeners in the
      expected (documented) order.
- [ ] A forward with no `[forwards.offer]` block is excluded from the
      result.
- [ ] Duplicate listen host/port across two forwards — assert the
      function's documented behavior (error vs. dedup vs. pass-through —
      confirm by reading the code; don't assume).

### Acceptance criteria

- [ ] `offer_listeners()`'s return value is asserted directly, not just
      used as an input to something else.
- [ ] `cargo test -p p2p-core` passes.

---

# Final completion checklist

## P0

- [x] P0-001: `SensitiveDataRedactor` test suite added and passing.
- [x] P0-002: `PublicIdentity::parse` malformed-input tests added and
      passing; any panics found are fixed, not just documented.
- [x] P0-003: `validate_non_world_writable` positive-enforcement test
      added and passing.

## P1

- [x] P1-001: `SetupIdentityController` generate/import tests added.
- [x] P1-002: `p2pctl add_authorized_key` duplicate-detection test added.
- [x] P1-003: `LogsViewModel` test suite added.
- [x] P1-004: `DataChannelHandle` state-machine tests added.
- [x] P1-005: `authorized_keys` duplicate signing-key test added.
- [x] P1-006: Identity file parsing error-path tests added.

## P2

- [ ] P2-001: `SignalCodec` version/recipient-mismatch tests added.
- [ ] P2-002: MQTT transport option-building edge-case tests added.
- [ ] P2-003: `SetupStepValidation` boundary tests added.
- [ ] P2-004: `p2p-offer`/`p2p-answer` config-path resolution tests added.
- [ ] P2-005: `p2pctl` `fingerprint`/`check_config` smoke tests added.
- [ ] P2-006: `can_attempt_same_session_ice_restart` direct test added.
- [ ] P2-007: `ForwardTable::offer_listeners()` direct test added.

## Regression gate (run once, at the end, in addition to per-task runs)

- [ ] `cargo fmt --all --check`
- [ ] `cargo clippy --workspace --all-targets --all-features -- -D warnings`
- [ ] `cargo clippy --workspace --release --all-features -- -D warnings`
- [ ] `cargo test --workspace --all-targets --all-features`
- [ ] `cd android && ./gradlew check` (ktlint + detekt + Android lint +
      unit tests)
- [ ] No `#[allow(...)]`/`@Suppress`/baseline additions introduced anywhere
      in this batch of work.

# Definition of done

Every file listed in this document's "Instructions" section has
meaningfully improved test coverage for the specific gaps described, no
new lint suppressions were added anywhere in the workspace, and the full
regression gate above is green.
