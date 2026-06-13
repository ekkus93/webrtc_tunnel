# Android Status / IO Hardening Follow-up TODO

This TODO implements `ANDROID_STATUS_IO_HARDENING_FOLLOWUP_SPEC.md`.

Target app area: Android app under `android/`, plus `crates/p2p-mobile` only for duplicate-start/runtime-state fixes.

---

## Phase 1 — Fix `Listening` active-state integration

### P1.1 — Add canonical service-state helpers

**Files:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/model/Models.kt`
- or a small existing helper file near service/status mapping

**Tasks:**

- [ ] Add helper for active-or-starting tunnel states.
- [ ] Add helper for running tunnel states if useful.
- [ ] Include at least:
  - [ ] `Starting`
  - [ ] `Connecting`
  - [ ] `Reconnecting`
  - [ ] `Listening`
  - [ ] `Serving`
  - [ ] `Connected`
- [ ] Exclude stopped/error/config-invalid/policy-blocked states unless intentionally handled separately.
- [ ] Add unit tests for the helpers.

### P1.2 — Use helpers in `TunnelForegroundService`

**Files:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt`

**Tasks:**

- [ ] Replace duplicate-start checks that only test `Connected` / `Serving`.
- [ ] Treat `Listening` as already running for Start commands.
- [ ] Treat `Starting`, `Connecting`, and `Reconnecting` as already in-progress for Start commands.
- [ ] Replace network-policy pause checks that only test `Connected` / `Serving`.
- [ ] Ensure policy block pauses a tunnel in `Listening`.
- [ ] Ensure status polling active checks do not accidentally ignore `Listening`.
- [ ] Ensure stop logic handles `Listening` as a stoppable/running state.
- [ ] Avoid scattering ad hoc state lists; use the helper.

**Tests:**

- [ ] Service test: Start while `Listening` does not call native start again.
- [ ] Service test: Start while `Starting` / `Connecting` does not call native start again if practical.
- [ ] Service test: policy block while `Listening` pauses the tunnel.
- [ ] Service test: polling remains correct for `Listening`.

---

## Phase 2 — Fix Rust duplicate-start behavior

### P2.1 — Preserve runtime state on already-running start

**Files:**

- `crates/p2p-mobile/src/runtime.rs`
- Rust tests under `crates/p2p-mobile`

**Tasks:**

- [ ] Locate the duplicate-start branch where `inner.state.active` is already true.
- [ ] Change the branch so it returns a clear already-running error/result without mutating state to `Error`.
- [ ] Do not set `active = false`.
- [ ] Do not clear live runtime metadata.
- [ ] Do not abort the current runtime task.
- [ ] Keep true startup failures on the error path.
- [ ] Ensure the error/result is safe to expose to Kotlin logs/UI.

**Tests:**

- [ ] Rust test: start once, duplicate start returns already-running error/result.
- [ ] Rust test: after duplicate start, status is still active/running.
- [ ] Rust test: duplicate start does not clear `started_at_unix_ms`.
- [ ] Rust test: duplicate start does not clear active sessions/forwards if present or mocked.
- [ ] Rust test: true start failure still maps to Error.

### P2.2 — Android mapping/test for duplicate start

**Files:**

- `TunnelForegroundService` tests
- `TunnelRepository` tests if needed

**Tasks:**

- [ ] Add Android-side regression test for duplicate Start while already active/listening.
- [ ] Confirm no false `Error` status is published.
- [ ] Confirm user-facing message is non-alarming, e.g. `Tunnel already running`.

---

## Phase 3 — Finish main-thread safety audit and migration

### P3.1 — Audit remaining blocking UI-triggered paths

**Files to inspect:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupIdentityController.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupForwardsController.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupStepValidation.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/LogsViewModel.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SettingsViewModel.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ImportExportViewModel.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ImportExportOps.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigRepository.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStore.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/security/IdentityRepository.kt`

**Tasks:**

- [ ] List every UI event handler that reaches disk IO.
- [ ] List every UI event handler that reaches `ContentResolver`.
- [ ] List every UI event handler that reaches encrypted identity import/export.
- [ ] List every UI event handler that reaches native validation/status/log retrieval.
- [ ] Mark each path as already dispatcher-safe or requiring migration.
- [ ] Add comments/tests where ownership of dispatcher switching is non-obvious.

### P3.2 — Move setup identity work off main thread

**Files:**

- `SetupIdentityController.kt`
- `SetupViewModel.kt`
- related tests

**Tasks:**

- [ ] Move stored identity reads off main thread.
- [ ] Move file-path identity import off main thread.
- [ ] Move URI identity import off main thread.
- [ ] Move public identity URI import off main thread.
- [ ] Move native private/public identity validation off main thread.
- [ ] Move identity generation/storage off main thread.
- [ ] Prefer suspend APIs or caller-side `withContext(deps.dispatchers.io)`.
- [ ] Surface errors through existing setup state/message flow.

**Tests:**

- [ ] Test identity import success path with test dispatcher.
- [ ] Test identity import failure path with busy cleanup.
- [ ] Test identity generation/storage failure path if practical.

### P3.3 — Move setup forwards and setup validation off main thread

**Files:**

- `SetupForwardsController.kt`
- `SetupStepValidation.kt`
- `SetupViewModel.kt`
- `ForwardsRepository.kt`
- related tests

**Tasks:**

- [ ] Stop using `ForwardsConfigStore` directly from setup mutation paths if practical.
- [ ] Use `ForwardsRepository` for setup forward load/upsert/delete where possible.
- [ ] Move saved-forward loads off main thread.
- [ ] Move forward upsert/delete off main thread.
- [ ] Move setup validation that reads persisted forwards off main thread.
- [ ] Move public identity validation off main thread.
- [ ] Ensure setup state remains consistent after async operations complete.

**Tests:**

- [ ] Test setup forward load uses repository/shared state.
- [ ] Test setup forward upsert updates shared forwards.
- [ ] Test setup validation failure message.
- [ ] Test setup validation does not block main dispatcher if practical.

### P3.4 — Move logs and diagnostics work off main thread

**Files:**

- `LogsViewModel.kt`
- `SettingsViewModel.kt`
- diagnostics/export helper files
- related tests

**Tasks:**

- [ ] Move native log refresh off main thread if bridge call may block.
- [ ] Move diagnostics export file creation off main thread.
- [ ] Move diagnostics URI writes off main thread.
- [ ] Move diagnostics share intent file preparation off main thread.
- [ ] Move redacted config file reads off main thread.
- [ ] Move status JSON generation off main thread if it calls native status or disk.
- [ ] Add busy state for export/share/copy operations where user-visible.

**Tests:**

- [ ] Test diagnostics export success with test dispatcher.
- [ ] Test diagnostics export failure clears busy state.
- [ ] Test redacted config copy/share failure message if practical.
- [ ] Test logs refresh failure does not crash UI.

---

## Phase 4 — Busy-state UX and exception safety

### P4.1 — Make `ForwardsViewModel` busy state exception-safe

**Files:**

- `ForwardsViewModel.kt`
- `ForwardsScreen.kt`
- `ForwardDetailsScreen.kt`
- related tests

**Tasks:**

- [ ] Wrap save-forward busy state in `try/finally`.
- [ ] Wrap delete-forward busy state in `try/finally`.
- [ ] Wrap regenerate/rollback paths so exceptions do not leave `_isBusy = true`.
- [ ] Ensure failure message is shown.
- [ ] Disable or ignore duplicate Save while busy.
- [ ] Disable or ignore duplicate Delete while busy.
- [ ] Disable or ignore conflicting Test action while save/delete is busy if necessary.
- [ ] Collect busy state in the UI.

**Tests:**

- [ ] Save success clears busy.
- [ ] Save failure clears busy.
- [ ] Delete success clears busy.
- [ ] Delete failure clears busy.
- [ ] Duplicate save/delete while busy is ignored or disabled.

### P4.2 — Make import/export busy state visible and complete

**Files:**

- `ImportExportViewModel.kt`
- `ImportExportScreen.kt`
- related tests

**Tasks:**

- [ ] Ensure all import/export operations set busy state.
- [ ] Ensure busy state clears in `finally`.
- [ ] Disable import/export buttons while busy.
- [ ] Disable repeated URI/path import/export triggers while busy.
- [ ] Keep error/success message visible near the action.

**Tests:**

- [ ] Import success clears busy.
- [ ] Import failure clears busy.
- [ ] Export success clears busy.
- [ ] Export failure clears busy.
- [ ] Duplicate tap while busy is ignored or disabled.

### P4.3 — Add busy handling for settings/logs diagnostics

**Files:**

- `SettingsViewModel.kt`
- `SettingsScreen.kt`
- `LogsViewModel.kt`
- `LogsScreen.kt`
- related tests

**Tasks:**

- [ ] Add busy state for settings config validation if not complete.
- [ ] Add busy state for diagnostics copy/share/export if operations can take time.
- [ ] Disable duplicate diagnostics actions while busy.
- [ ] Ensure failures are visible and redacted.
- [ ] Ensure busy state clears in `finally`.

**Tests:**

- [ ] Config validation success/failure busy cleanup.
- [ ] Diagnostics export success/failure busy cleanup.
- [ ] Copy/share diagnostics failure is visible and redacted.

---

## Phase 5 — Fix forward persistence atomicity and corruption safety

### P5.1 — Remove direct-write fallback

**Files:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStore.kt`
- related tests

**Tasks:**

- [ ] Replace `renameTo()` persistence with `java.nio.file.Files.move`.
- [ ] Prefer `StandardCopyOption.ATOMIC_MOVE` and `REPLACE_EXISTING`.
- [ ] If atomic move is unsupported, fall back to replace move only.
- [ ] Do not call `forwardsFile.writeText(...)` as a fallback.
- [ ] Write temp file in same directory as destination.
- [ ] Clean up temp file on failure.
- [ ] Ensure parent directory creation is safe.

**Tests:**

- [ ] Save/load round trip.
- [ ] Temp file is removed or harmless after failed save.
- [ ] Existing destination is not truncated by failed move.
- [ ] Save failure surfaces as failure, not silent success.

### P5.2 — Stop silently erasing forwards on corrupt JSON

**Files:**

- `ForwardsConfigStore.kt`
- `ForwardsRepository.kt`
- `SetupForwardsController.kt`
- `HomeViewModel.kt`
- `ForwardsViewModel.kt`
- related tests

**Tasks:**

- [ ] Audit all uses of `loadForwards()`.
- [ ] Remove or deprecate `loadForwards()` if it hides errors via `emptyList()`.
- [ ] Prefer `loadForwardsResult()` or repository methods that preserve errors.
- [ ] Ensure mutation paths do not treat corrupt disk state as empty list.
- [ ] Ensure repository preserves last known in-memory forwards on load failure.
- [ ] Surface/log corruption errors.
- [ ] Migrate setup forward operations away from direct store usage where practical.

**Tests:**

- [ ] Corrupt JSON returns failure through explicit API.
- [ ] Repository refresh on corrupt JSON preserves current in-memory list.
- [ ] Save/delete after corrupt JSON does not overwrite existing file with empty list.
- [ ] Setup forward paths do not erase forwards after corrupt load.

---

## Phase 6 — Fix notification state wording

### P6.1 — Add explicit notification title helper

**Files:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/notification/NotificationController.kt`
- notification tests

**Tasks:**

- [ ] Add explicit title mapping for `Stopped`.
- [ ] Add explicit title mapping for `Starting` / `Connecting` / `Reconnecting`.
- [ ] Add explicit title mapping for `Listening`.
- [ ] Add explicit title mapping for `Serving`.
- [ ] Add explicit title mapping for `Connected`.
- [ ] Add explicit title mapping for policy/no-network paused states.
- [ ] Add explicit title mapping for `Stopping`.
- [ ] Add explicit title mapping for `Error` / `ConfigInvalid`.
- [ ] Avoid generic `else -> "WebRTC Tunnel running"`.

**Suggested title mapping:**

- `Stopped` → `WebRTC Tunnel stopped`
- `Starting`, `Connecting`, `Reconnecting` → `WebRTC Tunnel starting`
- `Listening` → `WebRTC Tunnel listening`
- `Serving` → `WebRTC Tunnel serving`
- `Connected` → `WebRTC Tunnel connected`
- `PausedMeteredBlocked`, `NoNetwork` → `WebRTC Tunnel paused`
- `Stopping` → `WebRTC Tunnel stopping`
- `Error`, `ConfigInvalid` → `WebRTC Tunnel error`

### P6.2 — Align notification body/detail text

**Tasks:**

- [ ] Stopped body says stopped, not running.
- [ ] Listening body says waiting/listening, not connected.
- [ ] Connected body only appears for actual connected/session-active state.
- [ ] Policy-blocked body is clear.
- [ ] No-network body is clear.
- [ ] Error body is redacted and actionable enough.

**Tests:**

- [ ] Notification title/body test for stopped.
- [ ] Notification title/body test for starting.
- [ ] Notification title/body test for listening.
- [ ] Notification title/body test for serving.
- [ ] Notification title/body test for connected.
- [ ] Notification title/body test for policy paused.
- [ ] Notification title/body test for no network.
- [ ] Notification title/body test for error/config-invalid.

---

## Phase 7 — Clean dead Settings state

### P7.1 — Remove stale metered-warning Settings state

**Files:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/SettingsScreen.kt`
- related tests if present

**Tasks:**

- [ ] Remove unused `showMeteredWarningDialog` state if present.
- [ ] Remove unused warning dialog composable path if present.
- [ ] Remove stale callbacks related to duplicate metered controls.
- [ ] Keep read-only network policy summary.
- [ ] Keep button to open Network Policy screen.
- [ ] Ensure no duplicate editable network-policy switches remain in Settings.

**Validation:**

- [ ] Static analysis has no unused private declarations from the cleanup.
- [ ] Manual UI check: Settings network section is read-only summary plus navigation button.
- [ ] Manual UI check: Network Policy screen remains canonical editing surface.

---

## Final validation gate

### Android

Run:

```bash
cd android
./gradlew --no-daemon -PskipRustBuild=true lintDebug
./gradlew --no-daemon -PskipRustBuild=true testDebugUnitTest
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
./gradlew --no-daemon check
```

### Rust

Run if `crates/p2p-mobile` or shared Rust runtime code changed:

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test -p p2p-mobile
cargo test --workspace
```

### Manual Android smoke test

- [ ] Install debug APK on physical Android device.
- [ ] Launch app without crash.
- [ ] Confirm notification permission flow on Android 13+.
- [ ] Start offer-mode tunnel with no active session.
- [ ] Confirm Home/notification show `Listening` or equivalent, not `Connected`.
- [ ] Tap Start again while listening.
- [ ] Confirm no false Error state appears.
- [ ] Confirm logs/status show already-running or no-op behavior.
- [ ] Move to policy-blocked network while listening.
- [ ] Confirm tunnel pauses.
- [ ] Return to allowed network and confirm resume behavior if enabled.
- [ ] Stop tunnel and confirm notification says stopped.
- [ ] Confirm uptime disappears after stop.
- [ ] Import identity through URI and confirm UI stays responsive.
- [ ] Export identity/config/diagnostics and confirm duplicate taps are prevented.
- [ ] Edit/delete forwards and confirm Home and Forwards stay in sync.
- [ ] Confirm no corrupt forwards file can silently erase existing forwards in tested paths.
- [ ] Confirm Settings has no duplicate network-policy switches.

---

## Completion checklist

- [ ] P1 `Listening` active-state integration complete.
- [ ] P2 Rust duplicate-start behavior fixed.
- [ ] P3 main-thread safety migration complete.
- [ ] P4 busy-state UX and exception safety complete.
- [ ] P5 forward persistence atomicity/corruption safety complete.
- [ ] P6 notification wording complete.
- [ ] P7 dead Settings state cleanup complete.
- [ ] No suppressions or lint bypasses added.
- [ ] Android lint passes.
- [ ] Android unit tests pass.
- [ ] Android debug APK builds.
- [ ] Android `check` passes.
- [ ] Rust tests pass if Rust changed.
- [ ] Manual smoke test completed or skipped with explicit reason.
