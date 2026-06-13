# RUST_WEBRTC_CODE_REVIEW3_TODO.md

## Goal

Implement the next hardening pass for the Rust MQTT-signaled WebRTC tunnel project based on the current review.

This pass should focus on:

1. real offer-daemon busy behavior
2. dead config cleanup / runtime alignment
3. active-answer policy consistency
4. daemon operational robustness
5. lifecycle/integration tests

Do **not** redesign the protocol. This is a hardening and cleanup pass.

---

## P0 — Fix Real Offer-Daemon Busy Behavior

- [x] Status: complete

### Objective

Make the actual top-level offer daemon behave according to the product rule for v1:

- only one active local session at a time
- extra local clients are rejected promptly while busy
- do not rely on listener-level behavior that is never exercised by the real daemon loop

### Task 0.1 — Audit the real top-level offer daemon flow

- [x] Inspect the actual control flow of `run_offer_daemon()` and any related listener/session orchestration.
- [x] Document precisely when the daemon is inside `accept()` and when it is inside session handling.
- [x] Confirm whether extra local client connections are currently:
  - [x] accepted and rejected immediately
  - [x] left waiting in backlog
  - [x] or handled in some other way
- [x] Add comments describing the current behavior before changing it.

### Task 0.2 — Choose and implement one real v1 busy model

Implement **one** of the following and make it explicit in code/comments/config/docs:

#### Option A (preferred)
Prompt busy rejection

- [x] Keep accepting local connections even while a session is active.
- [x] If a new local client connects while busy:
  - [x] accept it
  - [x] simply close immediately
  - [x] close the socket promptly
- [x] Ensure this behavior happens at the actual daemon/runtime level, not only inside an isolated listener helper.

#### Option B
Explicitly documented backlog/wait behavior

- If prompt busy rejection is intentionally not supported in v1, remove misleading listener busy machinery.
- Make the daemon obviously single-session and document that additional clients may block or fail at the TCP level.

**Recommendation:** use Option A.

### Task 0.3 — Remove or simplify decorative busy machinery if no longer needed

- [x] Revisit `OfferListener.active_client`, `is_busy()`, and related logic.
- [x] If the busy state can be represented more simply at the daemon/session layer, simplify it.
- [x] Avoid keeping two overlapping models of “busy.”

### Task 0.4 — Add integration tests for real busy behavior

Add tests that exercise the top-level behavior, not just helper structs:

- [x] start offer daemon orchestration
- [x] connect first local client
- [x] while session is active, connect second local client
- [x] assert second client is rejected promptly
- [x] assert first session remains intact

---

## P1 — Clean Up Dead Or Misleading Config Surface

- [x] Status: complete

### Objective

Ensure every public v1 config field is either:

- implemented,
- explicitly rejected,
- or removed.

Do not leave fields in the config surface that imply real runtime control when they do not actually do anything.

### Task 1.1 — Audit config-to-runtime usage

For every config field, determine whether it is:

- [x] actively used in runtime behavior
- [x] only validated but not used
- [x] or ignored

- [x] Create a short internal table while implementing.

### Task 1.2 — Resolve suspicious fields

Review and resolve at least these fields:

- [x] `tunnel.offer.auto_open`
- [x] `tunnel.write_buffer_limit`
- [x] `health.heartbeat_interval_secs`
- [x] `health.ping_timeout_secs`
- [x] `tunnel.frame_version`
- [x] `webrtc.max_message_size`

For each one, do one of the following:

- [x] **Remove it from the v1 public config surface**

### Task 1.3 — Make config validation and docs match reality

- [x] Update config validation so unsupported fields do not linger silently.
- [x] Update example configs and comments.
- [x] Keep the v1 surface small and honest.

### Task 1.4 — Add tests for config/runtime alignment

Add tests that verify:

- [x] unsupported fields are rejected when intended
- [x] implemented fields actually affect runtime state or derived behavior
- [x] default config remains valid

---

## P1 — Make Active-Answer Busy Rejection Follow Full Policy

- [x] Status: complete

### Objective

Ensure answer-side behavior is consistent between:

- idle answer state
- active answer session state

Specifically, busy rejection should not bypass `allow_remote_peers` policy.

### Task 2.1 — Audit active answer “busy offer” handling

- [x] Inspect the code path that detects a new/foreign incoming offer while an answer session is already active.
- [x] Confirm which policy checks are applied before replying with `busy`.
- [x] Compare it directly with the idle-path authorization/policy logic.

### Task 2.2 — Apply consistent policy rules

- [x] Ensure active-session busy handling respects:
  - [x] `authorized_keys`
  - [x] `allow_remote_peers`
  - [x] session validity expectations
- [x] Decide what unauthorized or disallowed peers receive:
  - [x] no response
  - [x] explicit error not used
  - [x] explicit busy only if fully allowed

**Recommendation for v1:**
- If peer is not authorized or not in `allow_remote_peers`, do not send normal `busy`.
- Reserve `busy` for peers that are actually allowed but blocked only by active-session state.

### Task 2.3 — Add tests for policy consistency

Add tests covering:

- [x] allowed peer gets `busy` during active answer session
- [x] unauthorized peer gets rejected appropriately
- [x] authorized but disallowed peer does not get a misleading `busy`

---

## P1 — Review Daemon Survival For Non-Session Operational Errors

- [x] Status: complete

### Objective

Make daemon behavior resilient for ordinary operational failures, not just ordinary session failures.

### Task 3.1 — Audit top-level `?` exits

- [x] Inspect the top-level daemon loops and identify every remaining place where an ordinary operational failure can still tear down the daemon.

Examples to inspect:

- [x] listener accept failures
- [x] transport poll failures
- [x] status file write failures
- [x] bridge task join/propagation behavior
- [x] transient signaling transport failures

### Task 3.2 — Classify failures

Classify each failure as one of:

- [x] fatal startup/config/identity error → process should exit
- [x] recoverable session error → session should end, daemon should continue
- [x] recoverable operational error → log, back off, retry, daemon should continue

### Task 3.3 — Implement daemon-safe handling where appropriate

- [x] Convert recoverable operational errors into log/backoff/retry behavior.
- [x] Avoid allowing ordinary runtime turbulence to kill the daemon.
- [x] Preserve fail-closed behavior for true security/configuration failures.

### Task 3.4 — Add tests for daemon survival

Add tests for cases like:

- [x] session fails, daemon remains alive
- [x] target connect fails, answer daemon returns to waiting
- [x] remote error tears down session but not daemon
- [x] transient operational error is logged and daemon continues if policy says it should

---

## P2 — Simplify Or Remove Misleading Single-Session V1 Surfaces

- [x] Status: complete

### Objective

Reduce code/config complexity that suggests unsupported concurrency or unsupported behavior.

### Task 4.1 — Re-evaluate single-session assumptions across codebase

- [x] Identify code paths that imply multiple simultaneous sessions or richer concurrency than v1 actually supports.
- [x] Simplify where possible.

### Task 4.2 — Remove or document non-real concurrency knobs

If v1 remains truly single-session:

- [x] make that explicit in code comments and config docs
- [x] remove knobs that imply richer behavior unless they are actually implemented

### Task 4.3 — Improve inline documentation

- [x] Add comments where runtime behavior is intentionally simpler than lower-level helper abstractions.
- [x] Explain why v1 is single-session and what “busy” means operationally.

---

## P2 — Strengthen Integration / Lifecycle Test Coverage

- [x] Status: complete

### Objective

Add tests that validate the behavior of the real daemon/session orchestration rather than only unit-level helpers.

### Task 5.1 — Add top-level lifecycle test matrix

Create tests for at least these scenarios:

1. [x] offer session success path
2. [x] answer target-connect failure returns daemon to waiting
3. [x] session error does not kill daemon
4. [x] second local client is rejected promptly while busy
5. [x] active answer session receives new allowed offer and returns `busy`
6. [x] active answer session receives unauthorized/disallowed offer and applies correct policy

### Task 5.2 — Add test helpers/fakes if needed

- [x] Introduce fake signaling transport / fake bridge / fake peer connection pieces if needed.
- [x] Keep tests deterministic.
- [x] Do not rely on real network or broker infrastructure for core daemon lifecycle tests.

### Task 5.3 — Keep tests close to real orchestration

- [x] Prefer testing the real daemon/session orchestration with controlled fakes over testing isolated helper structs only.
- [x] Avoid a situation where component tests pass but the top-level daemon behavior is still wrong.

---

## P3 — Cleanup And Polish

- [x] Status: complete

### Task 6.1 — Improve logs around busy/session-state transitions

- [x] Add clear logs for:
  - [x] entering busy state
  - [x] rejecting extra local clients
  - [x] returning to idle
  - [x] active answer rejecting busy offers
  - [x] daemon recovering from session failure

### Task 6.2 — Keep docs/examples aligned

- [x] Update sample config if fields are removed or rejected.
- [x] Update README / docs comments if busy behavior changes.

### Task 6.3 — Remove obsolete tests and comments

- [x] If listener-only busy behavior is replaced by daemon-level behavior, remove stale tests/comments that no longer reflect the real system.

---

## Suggested Implementation Order

1. **P0:** real offer-daemon busy behavior
2. **P1:** active-answer policy consistency
3. **P1:** daemon survival for non-session operational errors
4. **P1:** dead config cleanup / runtime alignment
5. **P2:** lifecycle/integration tests
6. **P3:** cleanup/logging/docs

---

## Definition Of Done

This hardening pass is complete when all of the following are true:

- [x] the actual offer daemon enforces the intended busy behavior
- [x] v1 config surface matches real runtime behavior
- [x] active answer busy handling respects full allowlist policy
- [x] session failures do not kill the daemon
- [x] ordinary recoverable operational failures do not unnecessarily kill the daemon
- [x] integration tests cover the real daemon/session lifecycle behavior
- [x] docs/comments/examples reflect the true v1 model
