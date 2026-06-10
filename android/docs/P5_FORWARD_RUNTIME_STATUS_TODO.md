# P5 — Real Per-Forward Runtime Status TODO

Implements `P5_FORWARD_RUNTIME_STATUS_SPEC.md`. P5 was deferred from the UIUX2
follow-up because the native runtime could not report per-forward status honestly.
This task wires a real daemon → controller status channel (reusing the existing
`DaemonStatus`), replaces fabricated mobile status fields, and adds honest
per-forward `Listening`/`Stopped`/`Error` state for the offer role.

Work in phases; each phase is independently shippable. Do not leak secrets into
status JSON, the status file, logs, or UI. Do not enable Android answer mode.

---

## P0 — Regression guard & decisions before editing

- [x] Run current tests and record any pre-existing failures:
  - [x] `cargo test -p p2p-daemon` — passed (baseline clean)
  - [x] `cargo test -p p2p-mobile` — passed (baseline clean)
  - [x] `cd android && ./gradlew --no-daemon testDebugUnitTest lintDebug` — passed
- [x] Resolve open decisions from the spec (§ "Open decisions"):
  - [x] **D1 — bind soft-fail vs fatal**: chose soft-fail per forward; daemon-level
    error only if zero forwards bind (implemented in Phase 2).
  - [x] **D2 — status primitive**: `tokio::sync::watch` (latest-value).
  - [x] **D3 — per-forward active-connection count**: deferred (Listening only).
  - [x] **D4 — answer-role**: out of scope; the status channel is wired for the
    **offer** path only (answer mode is disabled on Android), avoiding a risky
    refactor of the large inline answer daemon and its many test callers.
  - [x] **D5 — `DaemonStatus` seed**: channel seeded with a config-derived
    pre-connection status; no `Default` forced on core types.
- [x] Do not hide or suppress new lint/test failures — fix them.

---

## Phase 1 — Daemon → controller status channel (no schema change)

**Files:** `crates/p2p-daemon/src/{lib.rs,status.rs}`,
`crates/p2p-mobile/src/{runtime.rs,lib.rs}`, CLI crate (delegation only).

**Daemon tasks:**
- [x] Sink is `Option<tokio::sync::watch::Sender<DaemonStatus>>` attached to
      `StatusWriter` (lower churn than `RuntimeContext`, which has ~30 construction
      sites). `StatusWriter::with_sink` added; `new` leaves it `None`.
- [x] At `StatusWriter::write` (the choke point reached by all `write_*_status`
      paths), broadcast to the sink before the file write so channel == file and
      observers see updates even when the status file is disabled.
- [x] Add `run_offer_daemon_with_status(config, identity, keys, sink)`; threaded a
      sink param into `run_offer_daemon_inner`. (Answer: see D4 — offer-only.)
- [x] Existing `run_offer_daemon`/`run_answer_daemon` and all transport/test entry
      points delegate with no sink → **CLI unchanged** (full `cargo test` green).

**Mobile tasks:**
- [x] In `AndroidTunnelController::start`: create a `watch::channel` seeded with a
      config-derived pre-connection `DaemonStatus`; store the `Receiver` in
      `RuntimeInner`; pass the `Sender` to `run_offer_daemon_with_status` (offer arm).
- [x] `RuntimeInner::snapshot_status()` merges the latest `DaemonStatus` with
      controller lifecycle state; `status()` uses it; cleared on stop.
- [x] `android_state_from_daemon` maps `DaemonState` → `AndroidRuntimeState`
      (documented in code; covered by a totality test).
- [x] Real `mqtt_connected`, `active_session_count`, `session_capacity` added to
      `AndroidRuntimeStatus` (serialized in the status JSON).

**Kotlin tasks:**
- [x] `NativeRuntimeStatusDto` gains defaulted `mqtt_connected`,
      `active_session_count`, `session_capacity`; `toTunnelStatus()` maps them
      instead of deriving from `active`.

**Tests:**
- [x] Rust: sink receives a `DaemonStatus` clone equal to what was written
      (`write_broadcasts_to_sink_even_when_file_disabled`).
- [x] Rust: `snapshot_status` overlays daemon status when active / quiescent when
      inactive; state mapping totality test.
- [x] Kotlin: measured fields decode and map; JSON without them still decodes.

**Acceptance:**
- [x] Home reflects real MQTT/session/connection state within the poll interval.
- [x] `mqttConnected` is no longer "task spawned".
- [x] CLI status-file behavior unchanged.

---

## Phase 2 — Per-forward runtime state in the daemon (offer role)

**Files:** `crates/p2p-daemon/src/{status.rs,lib.rs}`.

**Tasks:**
- [ ] Add `ForwardListenState { Listening, #[default] Stopped, Error }`
      (`#[serde(rename_all = "snake_case")]`) to `status.rs`.
- [ ] Add `ForwardRuntimeStatus { id, listen_state, last_error: Option<String> }`.
- [ ] Add `pub forwards: Vec<ForwardRuntimeStatus>` to `DaemonStatus`
      (`status.rs:9-20`); update `new`/`with_sessions` constructors.
- [ ] Maintain a `forward_id -> ForwardRuntimeStatus` map in `DaemonRuntimeState`,
      threaded through `RuntimeContext`, included in every built `DaemonStatus`
      (`write_daemon_status`/`write_answer_status`, `lib.rs:2052-2081`).
- [ ] Populate at `bind_offer_listeners` (`lib.rs:1992-2011`):
  - [ ] bind success → `Listening`.
  - [ ] stop/steady-state teardown → `Stopped`.
  - [ ] bind failure → `Error` + redacted reason (**requires D1 soft-fail**).
- [ ] Answer role: report forwards as `Stopped`/omit; document the limitation.
- [ ] Ensure `last_error` and all forward fields are secret-free.

**Tests:**
- [ ] `DaemonStatus` serializes `forwards` as an array; empty by default.
- [ ] Update schema tests (`current_status_schema_exposes_only_stable_public_fields`
      and `open_forward_ids` assertions) to include `forwards` **without** removing
      secret-absence checks.
- [ ] Successful bind → `Listening`; simulated bind failure → `Error` with a
      non-secret message (per D1).

**Acceptance:**
- [ ] `Listening` means a local TCP listener is actually bound — never derived from
      task spawn.
- [ ] One forward can be `Error` while others are `Listening` (per D1).

---

## Phase 3 — Surface per-forward status to Android

**Files:** `crates/p2p-mobile/src/{runtime.rs,lib.rs}`,
`android/.../model/Models.kt`, `android/.../data/TunnelRepository.kt`,
`android/.../ui/screens.kt`.

**Mobile tasks:**
- [ ] Add `AndroidForwardRuntimeStatus` and
      `forwards: Vec<AndroidForwardRuntimeStatus>` to `AndroidRuntimeStatus`,
      snake_case JSON. Include config-derived `name`, `local_host`, `local_port`,
      `remote_forward_id`, `enabled` plus runtime `listen_state`, `last_error`.
- [ ] Derive these from `DaemonStatus.forwards` joined with the loaded config.

**Kotlin tasks:**
- [ ] Add `NativeRuntimeForwardStatusDto` (see spec §3.2).
- [ ] Add `val forwards: List<NativeRuntimeForwardStatusDto> = emptyList()` to
      `NativeRuntimeStatusDto` (defaulted → backward compatible).
- [ ] Map native forward DTOs → `TunnelStatus.forwards` (`ForwardStatus`) in
      `toTunnelStatus()`.
- [ ] Tolerant listen-state mapper (`listening`/`stopped`/`error`/`disabled`/
      `paused` → enum; unknown → `Stopped`, or `Error` if `last_error` present).
- [ ] Redact `last_error` via `SensitiveDataRedactor` before storing.

**UI tasks:**
- [ ] Keep the `Configured`/`Disabled` label only as a fallback for forwards with
      no runtime entry; otherwise render the real state via
      `forwardStatusChipColors()`.
- [ ] Confirm the Phase B policy-pause safeguard still holds with forwards present.

**Tests:**
- [ ] Kotlin: decode JSON **with** `forwards` → populated; **without** → empty list.
- [ ] Kotlin: unknown `listen_state` does not crash.
- [ ] Rust: `p2p-mobile` status JSON includes `forwards`; fresh → `[]`; running →
      populated; secret-safe.

**Acceptance:**
- [ ] Running tunnel shows real per-forward `Listening`/`Error`/`Stopped`.
- [ ] Disabled forwards still show `Disabled` from config.
- [ ] Older native JSON without `forwards` still decodes.

---

## Phase 4 — Validation gate

```bash
cargo test -p p2p-daemon
cargo test -p p2p-mobile
cargo test
cd android
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
```

- [ ] `cargo test -p p2p-daemon` passes.
- [ ] `cargo test -p p2p-mobile` passes.
- [ ] Full `cargo test` passes (or unrelated pre-existing failures documented).
- [ ] `lintDebug`, `testDebugUnitTest`, `assembleDebug` pass.

### Secret-safety spot checks
```bash
# Status JSON / runtime status must not carry secret material.
grep -RnE "identity|private|password|token|secret" crates/p2p-daemon/src/status.rs
```
- [ ] No secret values are placed into `DaemonStatus`/`ForwardRuntimeStatus`/
      `last_error` (matches above are field plumbing/tests only, not leaked values).

### Manual QA (offer mode, physical device if possible)
- [ ] Start tunnel; Home shows real MQTT/connection state, not "task spawned".
- [ ] Each forward shows `Listening` only after its local port is actually bound.
- [ ] Misconfigure one forward's local port to force a bind error (per D1); confirm
      that forward shows `Error` while others show `Listening`.
- [ ] Stop tunnel; forwards transition to `Stopped`/cleared.
- [ ] Export diagnostics; confirm no private identity or secrets.

---

## Definition of done

- [ ] Daemon delivers `DaemonStatus` to `AndroidTunnelController` over a channel.
- [ ] Mobile status no longer fabricates `mqttConnected`/`activeSessionCount`/state.
- [ ] Per-forward `Listening`/`Stopped`/`Error` sourced from real offer binds and
      surfaced to the UI; disabled forwards derive `Disabled` from config.
- [ ] Older native JSON without `forwards` still decodes on Kotlin.
- [ ] All Rust + Android tests, lint, and debug build pass.
- [ ] No secret material in status JSON, status file, logs, or UI.
- [ ] UIUX2 Phase A/B behavior preserved.
- [ ] CLI behavior unchanged except additive, backward-compatible status fields.
