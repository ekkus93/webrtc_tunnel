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

### Objective

Make the actual top-level offer daemon behave according to the product rule for v1:

- only one active local session at a time
- extra local clients are rejected promptly while busy
- do not rely on listener-level behavior that is never exercised by the real daemon loop

### Task 0.1 — Audit the real top-level offer daemon flow

- Inspect the actual control flow of `run_offer_daemon()` and any related listener/session orchestration.
- Document precisely when the daemon is inside `accept()` and when it is inside session handling.
- Confirm whether extra local client connections are currently:
  - accepted and rejected immediately,
  - left waiting in backlog,
  - or handled in some other way.
- Add comments describing the current behavior before changing it.

### Task 0.2 — Choose and implement one real v1 busy model

Implement **one** of the following and make it explicit in code/comments/config/docs:

#### Option A (preferred)
Prompt busy rejection

- Keep accepting local connections even while a session is active.
- If a new local client connects while busy:
  - accept it,
  - write a short busy message or simply close immediately,
  - close the socket promptly.
- Ensure this behavior happens at the actual daemon/runtime level, not only inside an isolated listener helper.

#### Option B
Explicitly documented backlog/wait behavior

- If prompt busy rejection is intentionally not supported in v1, remove misleading listener busy machinery.
- Make the daemon obviously single-session and document that additional clients may block or fail at the TCP level.

**Recommendation:** use Option A.

### Task 0.3 — Remove or simplify decorative busy machinery if no longer needed

- Revisit `OfferListener.active_client`, `is_busy()`, and related logic.
- If the busy state can be represented more simply at the daemon/session layer, simplify it.
- Avoid keeping two overlapping models of “busy.”

### Task 0.4 — Add integration tests for real busy behavior

Add tests that exercise the top-level behavior, not just helper structs:

- start offer daemon
- connect first local client
- while session is active, connect second local client
- assert second client is rejected promptly
- assert first session remains intact

---

## P1 — Clean Up Dead Or Misleading Config Surface

### Objective

Ensure every public v1 config field is either:

- implemented,
- explicitly rejected,
- or removed.

Do not leave fields in the config surface that imply real runtime control when they do not actually do anything.

### Task 1.1 — Audit config-to-runtime usage

For every config field, determine whether it is:

- actively used in runtime behavior,
- only validated but not used,
- or ignored.

Create a short internal table while implementing.

### Task 1.2 — Resolve suspicious fields

Review and resolve at least these fields:

- `tunnel.offer.auto_open`
- `tunnel.write_buffer_limit`
- `health.heartbeat_interval_secs`
- `health.ping_timeout_secs`
- `tunnel.frame_version`
- `webrtc.max_message_size`

For each one, do one of the following:

- **Implement it fully**, or
- **Reject non-default values in config validation**, or
- **Remove it from the v1 public config surface**

### Task 1.3 — Make config validation and docs match reality

- Update config validation so unsupported fields do not linger silently.
- Update example configs and comments.
- Keep the v1 surface small and honest.

### Task 1.4 — Add tests for config/runtime alignment

Add tests that verify:

- unsupported fields are rejected when intended
- implemented fields actually affect runtime state or derived behavior
- default config remains valid

---

## P1 — Make Active-Answer Busy Rejection Follow Full Policy

### Objective

Ensure answer-side behavior is consistent between:

- idle answer state
- active answer session state

Specifically, busy rejection should not bypass `allow_remote_peers` policy.

### Task 2.1 — Audit active answer “busy offer” handling

- Inspect the code path that detects a new/foreign incoming offer while an answer session is already active.
- Confirm which policy checks are applied before replying with `busy`.
- Compare it directly with the idle-path authorization/policy logic.

### Task 2.2 — Apply consistent policy rules

- Ensure active-session busy handling respects:
  - `authorized_keys`
  - `allow_remote_peers`
  - session validity expectations
- Decide what unauthorized or disallowed peers receive:
  - no response,
  - explicit error,
  - or explicit busy only if fully allowed.

**Recommendation for v1:**
- If peer is not authorized or not in `allow_remote_peers`, do not send normal `busy`.
- Reserve `busy` for peers that are actually allowed but blocked only by active-session state.

### Task 2.3 — Add tests for policy consistency

Add tests covering:

- allowed peer gets `busy` during active answer session
- unauthorized peer gets rejected appropriately
- authorized but disallowed peer does not get a misleading `busy`

---

## P1 — Review Daemon Survival For Non-Session Operational Errors

### Objective

Make daemon behavior resilient for ordinary operational failures, not just ordinary session failures.

### Task 3.1 — Audit top-level `?` exits

Inspect the top-level daemon loops and identify every remaining place where an ordinary operational failure can still tear down the daemon.

Examples to inspect:

- listener accept failures
- transport poll failures
- status file write failures
- bridge task join/propagation behavior
- transient signaling transport failures

### Task 3.2 — Classify failures

Classify each failure as one of:

- fatal startup/config/identity error → process should exit
- recoverable session error → session should end, daemon should continue
- recoverable operational error → log, back off, retry, daemon should continue

### Task 3.3 — Implement daemon-safe handling where appropriate

- Convert recoverable operational errors into log/backoff/retry behavior.
- Avoid allowing ordinary runtime turbulence to kill the daemon.
- Preserve fail-closed behavior for true security/configuration failures.

### Task 3.4 — Add tests for daemon survival

Add tests for cases like:

- session fails, daemon remains alive
- target connect fails, answer daemon returns to waiting
- remote error tears down session but not daemon
- transient operational error is logged and daemon continues if policy says it should

---

## P2 — Simplify Or Remove Misleading Single-Session V1 Surfaces

### Objective

Reduce code/config complexity that suggests unsupported concurrency or unsupported behavior.

### Task 4.1 — Re-evaluate single-session assumptions across codebase

- Identify code paths that imply multiple simultaneous sessions or richer concurrency than v1 actually supports.
- Simplify where possible.

### Task 4.2 — Remove or document non-real concurrency knobs

If v1 remains truly single-session:

- make that explicit in code comments and config docs
- remove knobs that imply richer behavior unless they are actually implemented

### Task 4.3 — Improve inline documentation

- Add comments where runtime behavior is intentionally simpler than lower-level helper abstractions.
- Explain why v1 is single-session and what “busy” means operationally.

---

## P2 — Strengthen Integration / Lifecycle Test Coverage

### Objective

Add tests that validate the behavior of the real daemon/session orchestration rather than only unit-level helpers.

### Task 5.1 — Add top-level lifecycle test matrix

Create tests for at least these scenarios:

1. offer session success path
2. answer target-connect failure returns daemon to waiting
3. session error does not kill daemon
4. second local client is rejected promptly while busy
5. active answer session receives new allowed offer and returns `busy`
6. active answer session receives unauthorized/disallowed offer and applies correct policy

### Task 5.2 — Add test helpers/fakes if needed

- Introduce fake signaling transport / fake bridge / fake peer connection pieces if needed.
- Keep tests deterministic.
- Do not rely on real network or broker infrastructure for core daemon lifecycle tests.

### Task 5.3 — Keep tests close to real orchestration

- Prefer testing the real daemon/session orchestration with controlled fakes over testing isolated helper structs only.
- Avoid a situation where component tests pass but the top-level daemon behavior is still wrong.

---

## P3 — Cleanup And Polish

### Task 6.1 — Improve logs around busy/session-state transitions

- Add clear logs for:
  - entering busy state
  - rejecting extra local clients
  - returning to idle
  - active answer rejecting busy offers
  - daemon recovering from session failure

### Task 6.2 — Keep docs/examples aligned

- Update sample config if fields are removed or rejected.
- Update README / docs comments if busy behavior changes.

### Task 6.3 — Remove obsolete tests and comments

- If listener-only busy behavior is replaced by daemon-level behavior, remove stale tests/comments that no longer reflect the real system.

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

- the actual offer daemon enforces the intended busy behavior
- v1 config surface matches real runtime behavior
- active answer busy handling respects full allowlist policy
- session failures do not kill the daemon
- ordinary recoverable operational failures do not unnecessarily kill the daemon
- integration tests cover the real daemon/session lifecycle behavior
- docs/comments/examples reflect the true v1 model
