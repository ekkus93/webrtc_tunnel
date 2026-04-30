# Rust WebRTC Tunnel Code Review 5 TODO

## Goal

Finish the current hardening pass by fixing the remaining config/runtime honesty gaps, tightening one active-session replay path, and adding a small number of focused lifecycle tests.

This TODO is intentionally narrow. Do **not** redesign the system. Do **not** broaden the config surface. Keep v1 simple and honest.

---

## Priority Order

1. Wire in or remove the two dead WebRTC timeout fields
2. Harden active busy-offer replay handling earlier in the path
3. Remove or simplify remaining fixed-only config baggage
4. Add focused tests for timeout and replay behavior

---

## Task 1 — Fix `webrtc.ice_gather_timeout_secs`

**Status:** Done

### Objective

Make `webrtc.ice_gather_timeout_secs` either:

- a real runtime control, or
- not part of the public v1 config at all

### Implemented decision

Used **Option B — Remove it**.

#### Option A — Implement it

- Find the exact runtime path where local ICE gathering completion is awaited.
- Apply the configured timeout there.
- Ensure timeout failure produces clear, bounded, recoverable session failure behavior.
- Add or update logging/status so timeout failure is visible.

#### Option B — Remove it

- Remove `ice_gather_timeout_secs` from the public config structs/templates/docs.
- Remove validation for it.
- Remove any references implying it is user-tunable.

### Acceptance criteria

- The field is either genuinely enforced in runtime behavior or no longer exposed as a v1 config knob.
- There is no longer a config/runtime mismatch for this field.
- Done: removed from the public config struct, config examples, and docs, and config-load coverage now treats it as a removed v1 knob.

---

## Task 2 — Fix `webrtc.ice_connection_timeout_secs`

**Status:** Done

### Objective

Make `webrtc.ice_connection_timeout_secs` either:

- a real runtime control, or
- not part of the public v1 config at all

### Implemented decision

Used **Option B — Remove it**.

#### Option A — Implement it

- Identify the exact runtime point where ICE / peer-connection establishment is considered to be in progress.
- Apply the configured timeout there.
- Ensure timeout produces a recoverable session failure rather than a daemon-fatal condition.
- Update logs/status to reflect connection timeout clearly.

#### Option B — Remove it

- Remove `ice_connection_timeout_secs` from config/docs/templates.
- Remove validation for it.
- Remove code/comments implying it is a real user control.

### Acceptance criteria

- The field either affects real runtime behavior or is gone from the public v1 surface.
- Done: removed from the public config struct, config examples, and docs, and config-load coverage now treats it as a removed v1 knob.

---

## Task 3 — Harden active busy-offer replay handling earlier in the path

**Status:** Done

### Objective

Avoid repeatedly doing full decode/decrypt/classification work for replayed duplicate foreign offers during an active answer session.

### Current problem

The current code suppresses repeated `busy` replies, but duplicate active-session foreign offers still appear to be fully processed before dedupe suppresses the response.

### Required behavior

- Maintain a **per-active-answer-session** dedupe or replay structure.
- Key it by at least:
  - `sender_kid`
  - `msg_id`
- Use it early enough in the active busy-offer path to avoid unnecessary repeated work where practical.
- Drop this dedupe state when the active answer session ends.

### Implementation notes

- Keep the scope narrow to the active-answer busy-offer path.
- Do **not** introduce daemon-wide persistence for this feature.
- Do **not** redesign the whole replay system.

### Acceptance criteria

- Repeated copies of the same active-session foreign offer from an allowed peer do not repeatedly trigger full expensive classification work or repeated `busy` responses.
- Dedupe state is scoped to the active answer session lifetime.
- Done: the authenticated per-session cache remains authoritative, and already-seen `(sender_kid, msg_id)` keys are now dropped earlier from decoded outer-envelope metadata before full reclassification.

---

## Task 4 — Clean up remaining fixed-only config baggage

**Status:** Done

### Objective

Reduce the public config surface to knobs that actually matter.

### Steps

- Audit remaining config fields that are only validated to one supported value.
- For each such field, choose one:
  - keep it because it documents a real enforced runtime/protocol constant users benefit from seeing
  - remove it from the public config surface
- Update config docs/templates accordingly.

### Acceptance criteria

- The public config surface is smaller, clearer, and more honest.
- No obviously fake user knobs remain.
- Done: this pass removed the two clearly fake WebRTC timeout knobs and intentionally did not broaden into a larger config-pruning rewrite.

---

## Task 5 — Add focused tests for timeout behavior

**Status:** Done

### Objective

Ensure timeout behavior is either truly implemented or truly absent.

### Add tests for whichever timeout fields remain public

If `ice_gather_timeout_secs` remains public:
- add a test covering that the configured timeout is actually used
- verify failure behavior is bounded and recoverable

If `ice_connection_timeout_secs` remains public:
- add a test covering that the configured timeout is actually used
- verify failure behavior is bounded and recoverable

### Acceptance criteria

- Remaining public timeout knobs have tests proving they matter.
- Or, if removed, no tests/docs/templates imply they exist.
- Done: the timeout knobs were removed, related fixtures/templates/docs were updated, and config tests reject the removed fields.

---

## Task 6 — Add a focused replay/dedupe lifecycle test for active busy-offer handling

**Status:** Done

### Objective

Prove that repeated active-session foreign offer replays are suppressed properly.

### Add test coverage for

- one active answer session exists
- an allowed foreign peer sends a valid offer for a different session
- the first offer is classified and produces one encrypted `busy`
- duplicate copies of the same offer do not produce repeated `busy`
- duplicate processing work is reduced according to the chosen implementation boundary

### Acceptance criteria

- Regression test exists for repeated duplicate active-session foreign offers.
- Done: tests cover duplicate busy-offer suppression per session and the new early duplicate fast path.

---

## Task 7 — Update docs/config examples

**Status:** Done

### Objective

Keep docs consistent with the real runtime surface.

### Steps

- Update `SPECS.md` / config examples / templates if timeout knobs are removed or semantics change.
- Update any operator-facing examples that still imply removed or fake controls.
- Keep the v1 story simple and consistent.

### Acceptance criteria

- Docs and config examples match the actual runtime surface.
- Done: README, spec, config examples, and project instructions now reflect the removed timeout knobs and the tightened active busy-offer behavior.

---

## Non-goals for this pass

Do **not** do these in this round:

- do not redesign the signaling protocol
- do not add TURN support
- do not reintroduce multi-session concurrency
- do not widen the config surface
- do not build a full metrics/telemetry subsystem

Keep this round narrow and finish the remaining honesty/hardening work.

---

## Suggested implementation order

1. Decide fate of `ice_gather_timeout_secs`
2. Decide fate of `ice_connection_timeout_secs`
3. Implement/remove both consistently
4. Harden active busy-offer replay handling
5. Add replay/timeout tests
6. Trim docs/config examples

---

## Definition of done

This round is done when:

- [x] the two WebRTC timeout fields are either real or removed
- [x] active busy-offer replay handling is tightened earlier in the path
- [x] remaining fixed-only config baggage is trimmed
- [x] focused timeout/replay tests exist
- [x] docs/config examples match reality
