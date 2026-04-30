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

### Problem
`deny_when_busy` appears to have no meaningful behavioral effect.

### Product decision for v1
Use the simplest supported behavior:
- exactly one active session at a time,
- reject new incoming offer-side local clients while busy.

### Required changes
Pick one path and implement it fully:

#### Preferred path
- Remove `deny_when_busy` from config and code.
- Hardcode the v1 busy policy.

#### Acceptable alternative
- Keep `deny_when_busy` only if both branches truly behave differently and are tested.

### Acceptance criteria
- Busy state handling is explicit and real.
- No dead config flag remains for this feature.
- Tests prove the behavior.

---

## Task P0.5 — Fix offer-side active-client lifetime tracking

### Problem
Offer-side active-client tracking appears to be cleared too early, likely when an intermediate wrapper is consumed/dropped rather than when the session actually ends.

### Required behavior
The active-client marker must reflect the true session lifetime.

### Required changes
- Review the offer listener / accepted-client wrapper lifetime design.
- Remove any bookkeeping tied to short-lived wrapper ownership if it does not match actual session lifetime.
- Move active-client clearing to final session teardown.
- Ensure failure paths also clear state exactly once.
- Make double-clear and early-clear impossible.

### Acceptance criteria
- Active-client state is set when a real session starts.
- It remains set for the full session lifetime.
- It is cleared only after session teardown completes.

---

## Task P0.6 — Freeze the v1 concurrency model in code and config

### Product rule
For v1:
- exactly one active session at a time,
- no multiplexing,
- no client queueing,
- no concurrent local sessions.

### Required changes
- Audit config fields and runtime logic related to concurrency.
- Remove or reject unsupported concurrency-related knobs.
- Update comments/docs to reflect the actual v1 behavior.
- Simplify the listener/session code accordingly.

### Acceptance criteria
- Code and config clearly express “one session at a time.”
- No misleading concurrency behavior remains.

---

# P1 — Config/runtime alignment

## Task P1.1 — Audit config fields against real implemented behavior

### Problem
Some config fields still exist even though they are not clearly supported or do not provide meaningful v1 behavior.

### Required changes
For every field in the config schema, classify it as one of:
- fully supported,
- supported with restricted v1 semantics,
- unsupported and must be rejected,
- removable from v1.

Then implement one of these actions:
- keep and document,
- keep but validate/restrict,
- reject at startup,
- remove entirely.

### Focus fields
At minimum review:
- `server_name`
- `connect_timeout_secs`
- `session_expiry_secs`
- `log_rotation`
- `status_socket`
- any remaining concurrency/busy fields

### Acceptance criteria
- No config field silently over-promises.
- Unsupported v1 knobs are rejected or removed.

---

## Task P1.2 — Clarify `server_name` semantics

### Problem
`server_name` currently appears to behave more like a consistency check than a true independent TLS control.

### Required changes
Choose one of these and implement it clearly:

#### Option A
Make `server_name` a real TLS server-name override with explicit semantics.

#### Option B (preferred for v1 simplicity)
Remove `server_name` as a public override and derive it from the broker URL / documented rules.

### Acceptance criteria
- Operators cannot misunderstand what `server_name` does.
- Validation and runtime behavior match documentation.

---

# P2 — Testing and validation

## Task P2.1 — Add integration tests for daemon survival after session failure

### Required tests
Add integration tests that prove:
- answer daemon survives target connect failure,
- answer daemon survives bridge task failure,
- answer daemon survives ICE/session failure,
- offer daemon survives session failure and returns to waiting.

### Test requirements
- Do not use mocks where a simple local test double is possible.
- Use realistic local components where feasible.
- Keep tests deterministic.

### Acceptance criteria
- Failing session does not terminate daemon.
- Test asserts daemon remains usable after failure.

---

## Task P2.2 — Add integration tests for busy handling

### Required tests
Add tests proving the v1 busy policy:
- one active session blocks/rejects a second incoming local client,
- rejection behavior is consistent,
- post-session cleanup allows a later new client.

### Acceptance criteria
- Busy behavior is deterministic and matches the frozen product rule.

---

## Task P2.3 — Add integration tests for active-client bookkeeping

### Required tests
Add tests that verify:
- active-client state is set when session begins,
- stays set during the session,
- clears exactly once on normal teardown,
- clears exactly once on failure teardown.

### Acceptance criteria
- No early-clear.
- No stuck-busy state after cleanup.

---

## Task P2.4 — Add integration tests for target-connect failure paths

### Required tests
Add tests covering:
- answer-side target TCP connect failure,
- tunnel session fails cleanly,
- appropriate error path/log/status behavior occurs,
- daemon remains alive afterwards.

### Acceptance criteria
- Failure is isolated to the session.
- Service returns to waiting.

---

# P3 — Refactor to reduce drift

## Task P3.1 — Reduce duplicated daemon orchestration logic

### Problem
Offer-side and answer-side session loops still duplicate substantial orchestration behavior.

### Required changes
- Identify repeated logic that can be shared safely without obscuring role-specific behavior.
- Extract helpers for:
  - common session event processing,
  - logging/status transitions,
  - cleanup paths,
  - per-session failure normalization.
- Do **not** over-abstract. Keep role-specific logic readable.

### Acceptance criteria
- Reduced duplication.
- No loss of clarity.
- No change to frozen product behavior.

---

## Task P3.2 — Document daemon/session lifecycle explicitly in code

### Required changes
Add comments or module-level documentation that clearly describe:
- daemon lifetime,
- session lifetime,
- cleanup rules,
- busy policy,
- v1 reconnect/failure model.

### Acceptance criteria
- A future maintainer can understand the runtime model quickly.

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

- [ ] Per-session failures do not kill either daemon.
- [ ] Answer daemon reliably returns to idle after failure.
- [ ] Offer daemon reliably returns to waiting after failure.
- [ ] Busy behavior is explicit and real.
- [ ] `deny_when_busy` is either implemented meaningfully or removed.
- [ ] Active-client tracking reflects true session lifetime.
- [ ] V1 one-session-at-a-time behavior is explicit in code/config.
- [ ] Misleading config fields are removed or rejected.
- [ ] Daemon/session integration tests cover failure survival.
- [ ] Busy handling tests exist.
- [ ] Target-connect failure tests exist.
- [ ] No new scope is introduced beyond hardening/cleanup.
