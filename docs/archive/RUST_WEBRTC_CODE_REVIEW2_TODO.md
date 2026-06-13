# Rust WebRTC Tunnel Code Review TODO (Round 2)

## Goal

Apply a focused hardening and cleanup pass to the current Rust workspace based on the latest code review.

This TODO is intentionally explicit so GitHub Copilot can implement it without improvising product behavior. Follow the instructions exactly.

---

## Implementation Priorities

- **P0:** Fix daemon/session lifecycle correctness and misleading busy/client tracking.
- **P1:** Clean up dead or misleading config/runtime behavior.
- **P2:** Improve integration test coverage for lifecycle behavior.
- **P3:** Optional refactors to reduce drift and duplication.

---

# P0 — Daemon lifecycle and session correctness

## Task P0.1 — Make the answer daemon survive per-session failures

- [x] Status: complete

### Problem
The answer daemon is intended to be an always-on service, but the current top-level control flow appears to allow per-session errors to bubble out and terminate the daemon process.

### Required behavior
A failed session must **not** kill the answer daemon.

### Required changes
- [x] Review the top-level answer daemon entrypoint and loop.
- [x] Identify any use of `?` or equivalent propagation from per-session handling that can terminate the process.
- [x] Refactor so the daemon loop catches session-level failures.
- [x] On session failure:
  - [x] log the failure,
  - [x] update local status,
  - [x] clean up session resources,
  - [x] return to idle / waiting state.
- [x] Do **not** silently swallow errors. Log them clearly.
- [x] Do **not** exit the process for expected operational failures.

### Acceptance criteria
- [x] A target connect failure does not terminate the answer daemon.
- [x] A bridge task failure does not terminate the answer daemon.
- [x] An ICE/session failure does not terminate the answer daemon.
- [x] After failure, the answer daemon continues waiting for the next valid offer.

---

## Task P0.2 — Make the offer daemon survive per-session failures

- [x] Status: complete

### Problem
The offer daemon appears to have the same structural problem: a failed session may terminate the process rather than returning to a usable waiting state.

### Required behavior
A failed offer-side session must terminate the current client/session only, not the daemon process.

### Required changes
- [x] Review the top-level offer daemon loop.
- [x] Catch per-session failures at the session boundary.
- [x] Ensure the daemon returns to its waiting/accept loop after cleanup.
- [x] Log failure cause and session identifier.
- [x] Make sure local listener state is restored properly.

### Acceptance criteria
- [x] ICE failure during a session does not exit the offer daemon.
- [x] Remote error does not exit the offer daemon.
- [x] Session teardown returns the daemon to waiting for the next local client.

---

## Task P0.3 — Define and enforce the v1 session failure model

- [x] Status: complete

### Product rule
For v1:
- one active session at a time,
- no attempt to preserve a live dropped TCP stream,
- if the active tunnel fails, fail the local client immediately,
- daemon remains alive for the next client/session.

### Required changes
- [x] Audit reconnect/session code for any logic that suggests live-stream preservation.
- [x] Make the v1 behavior explicit in code comments and tests.
- [x] Ensure current session cleanup is deterministic and not partially retried in the background.

### Acceptance criteria
- [x] Live dropped tunnel closes the local client/session.
- [x] Daemon stays alive.
- [x] Next client/session can proceed normally.

---

# P0 — Busy handling and client bookkeeping

## Task P0.4 — Fix or remove `deny_when_busy`

- [x] Status: complete

### Problem
`deny_when_busy` appears to have no meaningful behavioral effect.

### Product decision for v1
Use the simplest supported behavior:
- exactly one active session at a time,
- reject new incoming offer-side local clients while busy.

### Required changes
Pick one path and implement it fully:

#### Preferred path
- [x] Remove `deny_when_busy` from config and code.
- [x] Hardcode the v1 busy policy.

#### Acceptable alternative
- [x] Alternative not used in v1.

### Acceptance criteria
- [x] Busy state handling is explicit and real.
- [x] No dead config flag remains for this feature.
- [x] Tests prove the behavior.

---

## Task P0.5 — Fix offer-side active-client lifetime tracking

- [x] Status: complete

### Problem
Offer-side active-client tracking appears to be cleared too early, likely when an intermediate wrapper is consumed/dropped rather than when the session actually ends.

### Required behavior
The active-client marker must reflect the true session lifetime.

### Required changes
- [x] Review the offer listener / accepted-client wrapper lifetime design.
- [x] Remove any bookkeeping tied to short-lived wrapper ownership if it does not match actual session lifetime.
- [x] Move active-client clearing to final session teardown.
- [x] Ensure failure paths also clear state exactly once.
- [x] Make double-clear and early-clear impossible.

### Acceptance criteria
- [x] Active-client state is set when a real session starts.
- [x] It remains set for the full session lifetime.
- [x] It is cleared only after session teardown completes.

---

## Task P0.6 — Freeze the v1 concurrency model in code and config

- [x] Status: complete

### Product rule
For v1:
- exactly one active session at a time,
- no multiplexing,
- no client queueing,
- no concurrent local sessions.

### Required changes
- [x] Audit config fields and runtime logic related to concurrency.
- [x] Remove or reject unsupported concurrency-related knobs.
- [x] Update comments/docs to reflect the actual v1 behavior.
- [x] Simplify the listener/session code accordingly.

### Acceptance criteria
- [x] Code and config clearly express “one session at a time.”
- [x] No misleading concurrency behavior remains.

---

# P1 — Config/runtime alignment

## Task P1.1 — Audit config fields against real implemented behavior

- [x] Status: complete

### Problem
Some config fields still exist even though they are not clearly supported or do not provide meaningful v1 behavior.

### Required changes
For every field in the config schema, classify it as one of:
- [x] fully supported,
- [x] supported with restricted v1 semantics,
- [x] unsupported and must be rejected,
- [x] removable from v1.

Then implement one of these actions:
- [x] keep and document,
- [x] keep but validate/restrict,
- [x] reject at startup,
- [x] remove entirely.

### Focus fields
At minimum review:
- [x] `server_name`
- [x] `connect_timeout_secs`
- [x] `session_expiry_secs`
- [x] `log_rotation`
- [x] `status_socket`
- [x] any remaining concurrency/busy fields

### Acceptance criteria
- [x] No config field silently over-promises.
- [x] Unsupported v1 knobs are rejected or removed.

---

## Task P1.2 — Clarify `server_name` semantics

- [x] Status: complete

### Problem
`server_name` currently appears to behave more like a consistency check than a true independent TLS control.

### Required changes
Choose one of these and implement it clearly:

#### Option A
- [x] Not chosen for v1.

#### Option B (preferred for v1 simplicity)
- [x] Remove `server_name` as a public override and derive it from the broker URL / documented rules.

### Acceptance criteria
- [x] Operators cannot misunderstand what `server_name` does.
- [x] Validation and runtime behavior match documentation.

---

# P2 — Testing and validation

## Task P2.1 — Add integration tests for daemon survival after session failure

- [x] Status: complete

### Required tests
Add integration tests that prove:
- [x] answer daemon survives target connect failure,
- [x] answer daemon survives bridge task failure,
- [x] answer daemon survives ICE/session failure,
- [x] offer daemon survives session failure and returns to waiting.

### Test requirements
- [x] Do not use mocks where a simple local test double is possible.
- [x] Use realistic local components where feasible.
- [x] Keep tests deterministic.

### Acceptance criteria
- [x] Failing session does not terminate daemon.
- [x] Test asserts daemon remains usable after failure.

---

## Task P2.2 — Add integration tests for busy handling

- [x] Status: complete

### Required tests
Add tests proving the v1 busy policy:
- [x] one active session blocks/rejects a second incoming local client,
- [x] rejection behavior is consistent,
- [x] post-session cleanup allows a later new client.

### Acceptance criteria
- [x] Busy behavior is deterministic and matches the frozen product rule.

---

## Task P2.3 — Add integration tests for active-client bookkeeping

- [x] Status: complete

### Required tests
Add tests that verify:
- [x] active-client state is set when session begins,
- [x] stays set during the session,
- [x] clears exactly once on normal teardown,
- [x] clears exactly once on failure teardown.

### Acceptance criteria
- [x] No early-clear.
- [x] No stuck-busy state after cleanup.

---

## Task P2.4 — Add integration tests for target-connect failure paths

- [x] Status: complete

### Required tests
Add tests covering:
- [x] answer-side target TCP connect failure,
- [x] tunnel session fails cleanly,
- [x] appropriate error path/log/status behavior occurs,
- [x] daemon remains alive afterwards.

### Acceptance criteria
- [x] Failure is isolated to the session.
- [x] Service returns to waiting.

---

# P3 — Refactor to reduce drift

## Task P3.1 — Reduce duplicated daemon orchestration logic

- [x] Status: complete

### Problem
Offer-side and answer-side session loops still duplicate substantial orchestration behavior.

### Required changes
- [x] Identify repeated logic that can be shared safely without obscuring role-specific behavior.
- [x] Extract helpers for:
  - [x] common session event processing
  - [x] logging/status transitions
  - [x] cleanup paths
  - [x] per-session failure normalization
- [x] Do **not** over-abstract. Keep role-specific logic readable.

### Acceptance criteria
- [x] Reduced duplication.
- [x] No loss of clarity.
- [x] No change to frozen product behavior.

---

## Task P3.2 — Document daemon/session lifecycle explicitly in code

- [x] Status: complete

### Required changes
Add comments or module-level documentation that clearly describe:
- [x] daemon lifetime,
- [x] session lifetime,
- [x] cleanup rules,
- [x] busy policy,
- [x] v1 reconnect/failure model.

### Acceptance criteria
- [x] A future maintainer can understand the runtime model quickly.

---

# Required Non-Goals

Do **not** introduce these in this pass:
- TURN support
- GUI features
- multiplexed streams
- concurrent multi-session support
- live TCP stream preservation across reconnects
- new protocol features unrelated to the review findings

This pass is for **hardening and correctness**, not expanding scope.

---

# Final Acceptance Checklist

Copilot should not consider this work complete until all of the following are true:

- [x] Per-session failures do not kill either daemon.
- [x] Answer daemon reliably returns to idle after failure.
- [x] Offer daemon reliably returns to waiting after failure.
- [x] Busy behavior is explicit and real.
- [x] `deny_when_busy` is either implemented meaningfully or removed.
- [x] Active-client tracking reflects true session lifetime.
- [x] V1 one-session-at-a-time behavior is explicit in code/config.
- [x] Misleading config fields are removed or rejected.
- [x] Daemon/session integration tests cover failure survival.
- [x] Busy handling tests exist.
- [x] Target-connect failure tests exist.
- [x] No new scope is introduced beyond hardening/cleanup.
