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
- [x] Added `ForwardListenState { Listening, #[default] Stopped, Error }`
      (`#[serde(rename_all = "snake_case")]`) to `status.rs`.
- [x] Added `ForwardRuntimeStatus { id, listen_state, last_error: Option<String> }`
      with `listening`/`error` constructors.
- [x] Added `pub forwards: Vec<ForwardRuntimeStatus>` to `DaemonStatus` plus a
      `with_forward_statuses` builder; `with_sessions` defaults it empty (keeps the
      `new`/`with_sessions` signatures stable across ~all call sites).
- [x] Maintained `forward_statuses: Vec<ForwardRuntimeStatus>` in
      `DaemonRuntimeState` (dropped its `Copy` derive); attached via
      `with_forward_statuses` in `write_daemon_status`/`write_answer_status`.
- [x] Populated at `bind_offer_listeners`:
  - [x] bind success → `Listening`.
  - [x] bind failure → `Error` + reason (soft-fail per forward, D1).
  - [x] stop → daemon ends; statuses cleared by controller (mobile resets on stop).
- [x] Answer role: out of scope (offer-only); documented (D4).
- [x] Forward fields carry no secret material (bind errors are OS-level reasons).

**Tests:**
- [x] `DaemonStatus` serializes `forwards` as an array; empty by default
      (`daemon_status_forwards_default_empty_and_attachable`).
- [x] Updated schema test to include `forwards` while keeping the
      `open_forward_ids`/secret-absence checks.
- [x] `bind_offer_listeners_soft_fails_individual_forward`: one forward `Listening`,
      one `Error` (port occupied), daemon does not abort.

**Acceptance:**
- [x] `Listening` means a local TCP listener is actually bound — never task-spawn.
- [x] One forward can be `Error` while others are `Listening` (soft-fail).

---

## Phase 3 — Surface per-forward status to Android

**Files:** `crates/p2p-mobile/src/{runtime.rs,lib.rs}`,
`android/.../model/Models.kt`, `android/.../data/TunnelRepository.kt`,
`android/.../ui/screens.kt`.

**Mobile tasks:**
- [x] Added `AndroidForwardRuntimeStatus { id, local_host, local_port, listen_state,
      last_error }` and `forwards: Vec<...>` to `AndroidRuntimeStatus` (snake_case).
      Scope note: the Rust config has no `name`/`enabled`/`remote_forward_id`, so the
      native struct carries id + config-derived host/port + runtime state; the UI
      already supplies display name/id from its own config by joining on `id`.
- [x] Joined `DaemonStatus.forwards` (id → listen_state/last_error) with the offer
      forwards captured at start (`id → host/port`) in `snapshot_status`; cleared on
      stop / when inactive.

**Kotlin tasks:**
- [x] Added `NativeRuntimeForwardStatusDto { id, local_host, local_port,
      listen_state, last_error }` (defaulted fields).
- [x] Added `val forwards: List<NativeRuntimeForwardStatusDto> = emptyList()` to
      `NativeRuntimeStatusDto` (backward compatible).
- [x] Mapped native forward DTOs → `TunnelStatus.forwards` in `toTunnelStatus()`
      (name/remoteForwardId fall back to id; the UI shows config values).
- [x] Tolerant `mapNativeListenState` (listening/stopped/error/disabled/paused →
      enum; unknown → `Stopped`, or `Error` when `last_error` present).
- [x] Redact `last_error` via `SensitiveDataRedactor.redactText` (redactStatus does
      not recurse into forwards).

**UI tasks:**
- [x] No change needed: the Home/Forwards/Details screens already join
      `status.forwards` by id and fall back to `Configured`/`Disabled` only when no
      runtime entry exists; chips now render real state via `forwardStatusChipColors`.
- [x] Phase B policy-pause safeguard still holds (forwards ride along the same copy).

**Tests:**
- [x] Kotlin: decode JSON **with** `forwards` → populated (`...MapsForwardRuntimeStatus`);
      **without** → empty list (`...WithoutForwardsLeavesEmptyList`).
- [x] Kotlin: unknown `listen_state` falls back (`...ForwardUnknownListenStateFallsBack`).
- [x] Rust: `AndroidRuntimeStatus.forwards` populated when active / empty when
      inactive (snapshot overlay tests); status JSON secret-safe.

**Acceptance:**
- [x] Running tunnel shows real per-forward `Listening`/`Error`/`Stopped`.
- [x] Disabled forwards still show `Disabled` from config (UI fallback by id).
- [x] Older native JSON without `forwards` still decodes.

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

- [x] `cargo test -p p2p-daemon` passes (78 lib tests).
- [x] `cargo test -p p2p-mobile` passes (19 + 6 tests).
- [x] Full `cargo test` passes; `cargo fmt --check` clean.
- [x] `lintDebug`, `testDebugUnitTest`, `assembleDebug` pass.

### Secret-safety spot checks
```bash
# Status JSON / runtime status must not carry secret material.
grep -RnE "identity|private|password|token|secret" crates/p2p-daemon/src/status.rs
```
- [x] No secret values are placed into `DaemonStatus`/`ForwardRuntimeStatus`/
      `AndroidForwardRuntimeStatus`/`last_error` (grep matches are pre-existing
      identity plumbing + tests, not leaked values; status test asserts no
      `private` in serialized output).

### Manual QA (offer mode)
> Partially run on the `Medium_Phone_API_36.0` x86_64 emulator (`emulator-5554`).
> The freshly cargo-ndk-built `libp2p_mobile.so` (with all P5 changes) was installed
> and exercised. Items needing a live MQTT broker + remote peer + provisioned
> identity cannot be reached here (the offer daemon connects MQTT and reads the
> identity before binding listeners), so the `Listening`/connected transitions
> remain covered only by the headless tests below.
- [x] App + native `.so` load and run on-device (no `UnsatisfiedLinkError`/crash);
      Home/Forwards/Logs render; forward shows `Configured` fallback (no runtime
      entry) via the contrast-safe chip.
- [x] Start with no provisioned identity → Home shows an **honest `Error`**, not a
      fabricated `Connected` (validates the Phase 1 honesty fix on-device).
- [x] Redaction verified on-device: the identity path is `***REDACTED***` in both
      logcat and the UI error card.
- [x] P4 on-device: Forward Details → Test Local Port reports the **configured**
      host and actual `host:port` ("Local port test failed for 127.0.0.1:8080 …
      ECONNREFUSED"), with the message rendered directly under the button.
- [x] UIUX2 surfaces confirmed on-device: Forward Details shows "Remote forward ID"
      (not `forward_id`) and both Copy URL + Open Browser (canOpenBrowser for the
      http-like port); Logs shows the empty-state **inside** the `LazyColumn` with
      filter/action controls pinned; Settings shows Network Policy as read-only
      "Cellular / metered: Blocked" (no duplicate switch) and no duplicate
      import/export; Home shows "Remote peer: Not configured".
- [x] Theme finding: the app is **light-only** (`Theme.kt` uses only
      `lightColorScheme`, no `isSystemInDarkTheme`), so dark-mode chip contrast is
      not an actual concern; light-mode chip readability verified.
- [ ] Real MQTT/connected state — needs a reachable broker (covered headlessly by
      `snapshot_status_overlays_daemon_status_when_active`).
- [ ] Forward `Listening` after bind / per-forward `Error` while others listen —
      needs broker+peer (covered headlessly by
      `bind_offer_listeners_soft_fails_individual_forward`).
- [ ] Stop → forwards `Stopped`/cleared — needs a running tunnel first.

---

## Definition of done

- [x] Daemon delivers `DaemonStatus` to `AndroidTunnelController` over a watch
      channel (offer path; answer disabled on Android — D4).
- [x] Mobile status no longer fabricates `mqttConnected`/`activeSessionCount`/state.
- [x] Per-forward `Listening`/`Stopped`/`Error` sourced from real offer binds and
      surfaced to the UI; disabled forwards derive `Disabled` from config.
- [x] Older native JSON without `forwards` still decodes on Kotlin.
- [x] All Rust + Android tests, lint, and debug build pass.
- [x] No secret material in status JSON, status file, logs, or UI.
- [x] UIUX2 Phase A/B behavior preserved.
- [x] CLI behavior unchanged except the additive, backward-compatible `forwards`
      status-file field.
- [x] On-device smoke + honesty QA on the x86_64 emulator (native lib loads, honest
      `Error` not fake `Connected`, redaction in UI/logcat).
- [ ] Full on-device tunnel QA (live `Listening`/connected) — needs broker + peer +
      provisioned identity; covered headlessly by Rust/Kotlin tests in the meantime.
