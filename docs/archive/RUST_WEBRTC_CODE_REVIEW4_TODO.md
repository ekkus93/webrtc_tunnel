# Rust WebRTC Tunnel Code Review 4 TODO

## Goal

Address the remaining issues from the latest review, with emphasis on:

1. accurate MQTT connectivity/status reporting
2. replay/dedup hardening for active busy-offer handling
3. clearer fatal-vs-recoverable runtime error policy
4. stronger daemon lifecycle/integration test coverage

This TODO is written to be explicit and implementation-oriented for GitHub Copilot.

---

## P0 — Fix inaccurate `mqtt_connected` status reporting

- [x] Status: complete

### Task P0.1 — Identify all status write sites

- [x] Find every place where `DaemonStatus` is constructed or written.
- [x] Enumerate where `mqtt_connected` is currently set.
- [x] Document which of those writes happen:
  - [x] at startup
  - [x] during idle steady state
  - [x] during session startup
  - [x] during session teardown
  - [x] during transport failure/recovery

### Task P0.2 — Add explicit daemon MQTT connectivity state

Implement explicit runtime state for MQTT connectivity instead of inferring it ad hoc.

Requirements:
- [x] Add a small connectivity-state variable/field owned by the daemon runtime.
- [x] It must represent real current transport state, not a guessed value.
- [x] It must be updateable from:
  - [x] successful connection/setup
  - [x] transport poll failure/disconnect
  - [x] successful reconnect/recovery
  - [x] permanent fatal shutdown paths that still reach status-writing code paths

### Task P0.3 — Use real connectivity state in `DaemonStatus`

- [x] Stop hardcoding or defaulting `mqtt_connected = true` in status writes.
- [x] Make all status writes use the tracked runtime connectivity state.
- [x] Ensure session-state updates do not accidentally overwrite connectivity state with a stale optimistic value.

### Task P0.4 — Update status on recoverable transport errors

On recoverable MQTT/signaling transport errors:
- [x] mark `mqtt_connected = false`
- [x] write updated status if possible
- [x] log the transition
- [x] then proceed with recovery/backoff behavior

On successful recovery/reconnect:
- [x] mark `mqtt_connected = true`
- [x] write updated status
- [x] log recovery

### Task P0.5 — Add tests for status accuracy

Add tests that validate:
- [x] startup/healthy state writes `mqtt_connected = true`
- [x] recoverable transport failure updates status to `false`
- [x] recovery updates status back to `true`
- [x] status-write failures remain recoverable and do not kill the daemon

---

## P1 — Harden active busy-offer replay/dedup behavior

- [x] Status: complete

### Task P1.1 — Locate the active busy-offer classification path

- [x] Identify the function(s) responsible for classifying and responding to incoming offers during an already-active answer session.
- [x] Confirm where replay/dedup data is currently sourced.
- [x] Confirm whether a fresh replay cache/dedupe structure is created per call.

### Task P1.2 — Add persistent dedupe state for active busy offers

Implement a small persistent dedupe cache for this path.

Requirements:
- [x] Scope may be per active answer session or per daemon, but must persist across repeated calls while the session remains active.
- [x] Key should include enough information to suppress duplicate handling, such as:
  - [x] sender KID
  - [x] message ID
  - [x] optionally session ID if appropriate
- [x] The cache must only suppress exact duplicates/replays, not legitimate new offers from distinct peers/messages.

### Task P1.3 — Suppress repeated `busy` responses for duplicates

Behavior to enforce:
- [x] first allowed peer offer during active session may receive encrypted `busy`
- [x] repeated duplicate/replayed copies of that same offer must **not** produce repeated `busy` replies
- [x] unauthorized or disallowed peers must continue to receive **no response**

### Task P1.4 — Add tests for busy-offer dedupe

Add tests that prove:
- [x] allowed peer receives one `busy` for a legitimate first foreign offer during active session
- [x] exact duplicate/replayed copies do not trigger additional `busy` replies
- [x] unauthorized peer receives no response
- [x] disallowed-but-authorized peer receives no response

---

## P2 — Freeze and implement fatal-vs-recoverable runtime policy

- [x] Status: complete

### Task P2.1 — Enumerate current fatal error paths

Audit the daemon code and identify where errors can bubble out and terminate the process.

Create a list of current fatal paths for:
- [x] startup/config failures
- [x] identity/authorized-key loading failures
- [x] transport setup failures
- [x] runtime transport turbulence
- [x] accept-loop failures
- [x] session failures
- [x] status write failures

### Task P2.2 — Classify errors into fatal vs recoverable

Freeze and implement the following policy.

#### Fatal
These should terminate the daemon:
- [x] invalid config
- [x] invalid/missing identity files
- [x] invalid/missing authorized keys
- [x] TLS/security misconfiguration
- [x] cryptographic initialization failure
- [x] startup bind failure that prevents entering service
- [x] other startup/init failures that prevent the daemon from functioning at all

#### Recoverable
These should not kill the daemon:
- [x] individual session failures
- [x] ICE failure for one session
- [x] ACK timeout for one session
- [x] target-connect failure for one session
- [x] remote error/close for one session
- [x] transient signaling transport poll/read errors
- [x] transient signaling publish failures
- [x] local status file write failures
- [x] ordinary accept-loop turbulence if service can continue

### Task P2.3 — Wrap recoverable runtime failures consistently

- [x] Replace remaining top-level `?` propagation for recoverable runtime conditions with explicit recovery handling where round-4 scope touched runtime transport/state reporting.
- [x] Ensure recoverable paths:
  - [x] log the error
  - [x] clean up any current session state
  - [x] update status if relevant
  - [x] optionally back off
  - [x] return to idle/waiting state

### Task P2.4 — Keep fatal paths explicit and obvious

Do **not** silently recover from:
- [x] broken identity/security setup
- [x] invalid config
- [x] impossible startup conditions

- [x] Fatal startup/security failures should still fail fast and loudly.

### Task P2.5 — Add tests for recoverable runtime behavior

Add tests that validate:
- [x] session failure does not kill daemon
- [x] recoverable signaling transport failure does not kill daemon
- [x] status write failure does not kill daemon
- [x] daemon returns to steady state after cleanup

---

## P3 — Add higher-level lifecycle/integration tests

- [x] Status: complete

### Task P3.1 — Add top-level daemon behavior tests

Add tests around top-level daemon orchestration, not just helper components.

Target scenarios:
- [x] answer daemon survives a failed session and returns to waiting
- [x] offer daemon survives a failed session and returns to waiting for the next local client
- [x] active offer-side session rejects extra local clients while busy

### Task P3.2 — Add status transition tests

Add tests for:
- [x] healthy startup status
- [x] disconnect status
- [x] reconnect status
- [x] session active/inactive transitions
- [x] status write failure remains recoverable

### Task P3.3 — Add busy-offer policy tests

Add higher-level tests covering:
- [x] active answer session + allowed peer foreign offer => one `busy`
- [x] replayed duplicate => no repeated `busy`
- [x] unauthorized peer => no response
- [x] authorized-but-disallowed peer => no response

### Task P3.4 — Add runtime turbulence tests

Add tests for:
- [x] transient signaling transport poll failure
- [x] transient signaling publish failure
- [x] cleanup then return to steady state

- [x] These do not need to be full network integration tests; controlled fakes/mocks for transport are acceptable if they truly exercise top-level orchestration.

---

## P4 — General cleanup and documentation alignment

- [x] Status: complete

### Task P4.1 — Audit remaining config/runtime alignment

- [x] Re-check all public config fields.
- [x] For each field, confirm one of the following is true:
  - [x] it meaningfully affects runtime behavior, or
  - [x] it is explicitly unsupported and rejected, or
  - [x] it should be removed

### Task P4.2 — Update docs/spec if runtime policy changed

If the implementation clarifies or changes any runtime behavior, update the docs/spec accordingly.

Specifically ensure the docs match actual behavior for:
- [x] daemon recoverability
- [x] busy local client handling
- [x] active busy-offer response policy
- [x] status semantics

### Task P4.3 — Improve log messages around recovery

Ensure logs clearly distinguish:
- [x] fatal startup/security failure
- [x] recoverable runtime failure
- [x] session failure with daemon survival
- [x] transport disconnect/recovery
- [x] status write failure

- [x] This will make debugging much easier.

---

## Suggested implementation order

1. **P0 — Fix `mqtt_connected` status reporting**
2. **P1 — Harden active busy-offer dedupe/replay behavior**
3. **P2 — Freeze and enforce fatal vs recoverable runtime policy**
4. **P3 — Add higher-level lifecycle/integration tests**
5. **P4 — Final cleanup/docs/logging pass**

---

## Expected outcome

After this TODO is completed, the codebase should have:

- more trustworthy local health/status reporting
- cleaner behavior for duplicate/replayed busy offers
- clearer and more robust daemon survival semantics
- better test coverage for the actual remaining runtime risk areas

That would make the project materially closer to production-readiness.
