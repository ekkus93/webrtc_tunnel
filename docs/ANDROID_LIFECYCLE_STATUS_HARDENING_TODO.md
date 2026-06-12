# Android Lifecycle, Runtime Status, and Release Hardening TODO

This TODO implements `ANDROID_LIFECYCLE_STATUS_HARDENING_SPEC.md`.

Target app area: Android app under `android/` plus Rust mobile bridge under `crates/p2p-mobile` only where runtime status/cleanup changes require it.

---

## Phase 1 — Lifecycle-scoped ViewModels

### P1.1 — Replace manual `AppScreenModels` construction

**Files:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/App.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/AppViewModelFactory.kt`

**Tasks:**

- [ ] Make `AppViewModelFactory` implement `androidx.lifecycle.ViewModelProvider.Factory`.
- [ ] Replace factory methods such as `home()`, `setup()`, `forwards()`, etc. with `create(modelClass)` dispatch.
- [ ] Remove `AppScreenModels`, or stop using it to directly construct viewmodels.
- [ ] Use `androidx.lifecycle.viewmodel.compose.viewModel()` in composable destinations.
- [ ] Decide and document scope for shared models:
  - [ ] `ForwardsViewModel` should be shared between Home and Forwards.
  - [ ] `HomeViewModel` may be shared at app/nav-graph scope.
  - [ ] `LogsViewModel` may be shared at app/nav-graph scope.
  - [ ] `SetupViewModel` scope should be intentional and documented.
  - [ ] `ImportExportViewModel` may be route scoped.
- [ ] Ensure no production UI code directly calls `HomeViewModel(deps)`, `ForwardsViewModel(deps)`, etc.

**Tests / checks:**

- [ ] Rotate/recreate Activity manually or with a test and confirm state/coroutines behave correctly.
- [ ] Existing viewmodel unit tests still compile and pass.
- [ ] Add a regression test if practical for factory creation of each viewmodel class.

---

## Phase 2 — Main-thread safety

### P2.1 — Audit all UI-triggered blocking operations

**Files to inspect:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModel.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SettingsViewModel.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ImportExportViewModel.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupViewModel.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupSaveController.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ImportExportService.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigRepository.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStore.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/security/IdentityRepository.kt`

**Tasks:**

- [ ] Identify all calls from viewmodel/UI event handlers to:
  - [ ] file reads/writes
  - [ ] ContentResolver stream reads/writes
  - [ ] encrypted identity import/export
  - [ ] config rendering or atomic config replacement
  - [ ] native Rust validation/status calls that may block
- [ ] Mark each path as already safe or requiring dispatcher changes.

### P2.2 — Move forward save/delete/regenerate work off main thread

**Files:**

- `ForwardsViewModel.kt`
- related tests

**Tasks:**

- [ ] Wrap save-forward work in `viewModelScope.launch { withContext(deps.ioDispatcher) { ... } }`.
- [ ] Wrap delete-forward work in `viewModelScope.launch { withContext(deps.ioDispatcher) { ... } }`.
- [ ] Wrap active config regeneration/native validation in IO dispatcher.
- [ ] Add busy state for save/delete/test operations, or reuse existing state if present.
- [ ] Disable duplicate save/delete clicks while busy.
- [ ] Keep UI message/error updates on the normal viewmodel state path.

**Tests:**

- [ ] Add or update tests proving save/delete set busy state and complete with message/error.
- [ ] Ensure `testLocalPort()` remains on IO dispatcher.

### P2.3 — Move import/export/config validation off main thread

**Files:**

- `ImportExportViewModel.kt`
- `ImportExportService.kt`
- `SettingsViewModel.kt`
- `SetupSaveController.kt`

**Tasks:**

- [ ] Wrap ContentResolver import/export in IO dispatcher.
- [ ] Wrap encrypted identity export/import in IO dispatcher.
- [ ] Wrap config validate/reset operations in IO dispatcher.
- [ ] Expose visible progress/busy state where operations can take noticeable time.
- [ ] Prevent duplicate import/export taps while busy.

**Tests:**

- [ ] Add tests for import/export busy and error states.
- [ ] Add tests for config validation success/failure feedback.

---

## Phase 3 — Truthful runtime status mapping

### P3.1 — Audit native-to-Kotlin status mapping

**Files:**

- `crates/p2p-mobile/src/runtime.rs`
- any Rust status DTO/schema files under `crates/p2p-mobile`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/model/Models.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/TunnelRepository.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/HomeCards.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/Components.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/notification/NotificationController.kt`

**Tasks:**

- [ ] List every native daemon state currently possible.
- [ ] List every Android `ServiceState` currently possible.
- [ ] Identify states currently collapsed into `Connected` or `Serving`.
- [ ] Decide the conservative user-facing label for each state.
- [ ] Document the mapping in code comments or tests.

### P3.2 — Add/refine Android-facing runtime states

**Tasks:**

- [ ] Add states or labels to distinguish daemon-running-but-not-connected from actually connected.
- [ ] Prefer labels such as `Running`, `Listening`, `Waiting for connection`, or `Waiting for local client` when a tunnel/session is not known to be connected.
- [ ] Reserve `Connected` for actual active session/tunnel state.
- [ ] Reserve `Serving` for answer mode only if that means serving/listening; use a separate label if peer connection is unknown.
- [ ] Preserve explicit `PausedMeteredBlocked`, `BlockedNoNetwork`, and `Error` semantics.

### P3.3 — Update UI and notification labels

**Files:**

- `HomeCards.kt`
- `HomeScreen.kt`
- `Components.kt`
- `NotificationController.kt`
- any UI helper files

**Tasks:**

- [ ] Update Home status card mapping.
- [ ] Update notification title/body mapping.
- [ ] Update color mapping for new states.
- [ ] Ensure forward status labels remain truthful and contrast-safe.

**Tests:**

- [ ] Add Kotlin tests for stopped.
- [ ] Add Kotlin tests for starting.
- [ ] Add Kotlin tests for running/listening but not connected.
- [ ] Add Kotlin tests for connected/session-active.
- [ ] Add Kotlin tests for paused by policy.
- [ ] Add Kotlin tests for error.
- [ ] Add notification label tests for the same representative states.

---

## Phase 4 — Runtime cleanup on stop

### P4.1 — Clear stale status metadata in Rust mobile runtime

**Files:**

- `crates/p2p-mobile/src/runtime.rs`
- Rust tests under `crates/p2p-mobile`

**Tasks:**

- [ ] On clean stop, clear `started_at_unix_ms`.
- [ ] On clean stop, clear stale clean-stop `last_error`.
- [ ] On clean stop, set MQTT connected false.
- [ ] On clean stop, set active/session count zero.
- [ ] On clean stop, reset per-forward runtime statuses to inactive/configured/disabled as appropriate.
- [ ] Remove suspicious no-op logic such as setting `last_error = None` only when already none.
- [ ] Decide whether `config_path` should be preserved for diagnostics or cleared; document the choice.

**Tests:**

- [ ] Rust test: `start -> stop -> status` has no uptime/start timestamp.
- [ ] Rust test: clean stop does not preserve stale error.
- [ ] Rust test: error state preserves relevant error.

### P4.2 — Ignore stale uptime on Android UI

**Files:**

- `TunnelRepository.kt`
- `HomeScreen.kt`
- `HomeCards.kt`

**Tasks:**

- [ ] Show uptime only for active/running states.
- [ ] Do not show uptime for stopped/error states unless explicitly desired and labeled.
- [ ] Ensure local displayed uptime ticker stops and clears on stopped/error/policy-blocked states.

**Tests:**

- [ ] Kotlin test: stopped native status maps to `uptimeSeconds = null` or otherwise hidden.
- [ ] Kotlin test: clean stop clears displayed uptime.

---

## Phase 5 — Notification correctness

### P5.1 — Fix SDK-gated notification permission check

**Files:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/notification/NotificationController.kt`
- notification tests

**Tasks:**

- [ ] Gate `POST_NOTIFICATIONS` runtime permission checks behind `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU`.
- [ ] Ensure API < 33 notification updates are not skipped due to `POST_NOTIFICATIONS`.
- [ ] Ensure API >= 33 denied permission behavior remains safe.
- [ ] Ensure foreground service startup still creates a valid foreground notification.

**Tests:**

- [ ] Test API < 33 notifications allowed path.
- [ ] Test API >= 33 permission denied path.
- [ ] Test API >= 33 permission granted path if feasible.

### P5.2 — Align notification wording with truthful runtime states

**Tasks:**

- [ ] Reuse the same status-label helper as Home where possible.
- [ ] Do not show `connected` unless the state is actually connected.
- [ ] Show clear paused/blocked/error notification body text.

**Tests:**

- [ ] Notification label tests for stopped/starting/running/connected/paused/error.

---

## Phase 6 — Settings cleanup

### P6.1 — Remove duplicate network policy controls from Settings

**Files:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/SettingsScreen.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/ui/NetworkPolicyScreen.kt`
- `SettingsViewModel.kt`

**Tasks:**

- [ ] Keep canonical editable network policy controls in `NetworkPolicyScreen`.
- [ ] Remove duplicate interactive `Resume tunnel when Wi-Fi returns` from `SettingsScreen`.
- [ ] In `SettingsScreen`, show read-only summary of network policy state if useful.
- [ ] Keep an `Open Network Policy` button in Settings.

**Tests:**

- [ ] UI/helper test or screenshot-level manual check: Settings has no duplicate policy switch.

### P6.2 — Remove or implement `Start tunnel when app opens`

**Files:**

- `SettingsScreen.kt`
- `SettingsViewModel.kt`
- preference model/store files
- `MainActivity.kt` or startup owner if implementing

**Preferred task:**

- [ ] Remove the visible setting if behavior is not implemented.
- [ ] Remove unused preference fields if no longer needed, or leave migration-compatible defaults if stored preferences already exist.

**Alternative implementation tasks if choosing to keep it:**

- [ ] Start tunnel exactly once per app launch, not per recomposition.
- [ ] Respect network policy.
- [ ] Require valid config and identity.
- [ ] Avoid starting before notification permission/foreground-service requirements are satisfied.
- [ ] Show clear status/error if auto-start is blocked.
- [ ] Add tests for auto-start guard behavior.

### P6.3 — Make config validation feedback visible

**Files:**

- `SettingsScreen.kt`
- `SettingsViewModel.kt`

**Tasks:**

- [ ] Ensure `Validate config` button displays success or failure near the button.
- [ ] Include actionable error text for invalid config.
- [ ] Disable duplicate validation while busy.

**Tests:**

- [ ] Test successful validation message.
- [ ] Test invalid config message.

---

## Phase 7 — Atomic forward persistence and Home freshness

### P7.1 — Make `forwards.json` writes atomic

**Files:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStore.kt`
- tests for `ForwardsConfigStore`

**Tasks:**

- [ ] Write new JSON to a temp file in the same directory.
- [ ] Flush and close the writer before moving.
- [ ] Atomically replace `forwards.json` with the temp file.
- [ ] Clean up temp file on failure.
- [ ] Do not silently erase existing forwards on corrupt JSON.
- [ ] Log or surface load failure/corruption.

**Tests:**

- [ ] Test normal save/load round trip.
- [ ] Test invalid/corrupt JSON handling.
- [ ] Test temp file cleanup or replacement behavior where practical.

### P7.2 — Make configured forwards observable/shared

**Files:**

- `ForwardsConfigStore.kt` or new `ForwardsRepository.kt`
- `ForwardsViewModel.kt`
- `HomeViewModel.kt`
- `HomeScreen.kt`
- `ForwardsScreen.kt`

**Tasks:**

- [ ] Choose a single source of truth for configured forwards.
- [ ] Prefer adding a `ForwardsRepository` with `StateFlow<List<ForwardConfig>>`.
- [ ] Update Home to observe the same configured-forward source as Forwards.
- [ ] After save/delete, update the shared state.
- [ ] Remove one-off `LaunchedEffect(Unit) { refreshForwards() }` patterns if replaced by observable state.

**Tests:**

- [ ] Test that save updates observer state.
- [ ] Test that delete updates observer state.
- [ ] Test that Home-facing state sees edits without app restart.

---

## Phase 8 — Build workflow hardening

### P8.1 — Add optional Rust build skip for local Kotlin checks

**Files:**

- Android Gradle build files under `android/`
- README/build docs

**Tasks:**

- [ ] Add a Gradle property such as `-PskipRustBuild=true`.
- [ ] Make `testDebugUnitTest -PskipRustBuild=true` skip Rust/cargo-ndk build steps.
- [ ] Make `lintDebug -PskipRustBuild=true` skip Rust/cargo-ndk build steps unless truly required.
- [ ] Keep `assembleDebug` native build/verification enabled by default.
- [ ] Fail packaging clearly if native libraries are missing and skip is not allowed.
- [ ] Document the property and intended use.

**Validation:**

- [ ] `cd android && ./gradlew --no-daemon testDebugUnitTest -PskipRustBuild=true`
- [ ] `cd android && ./gradlew --no-daemon lintDebug -PskipRustBuild=true`
- [ ] `cd android && ./gradlew --no-daemon assembleDebug`

---

## Phase 9 — Release polish

### P9.1 — Replace default launcher icon

**Files:**

- `android/app/src/main/AndroidManifest.xml`
- Android resources under `android/app/src/main/res/`

**Tasks:**

- [ ] Add app-owned launcher icon resources.
- [ ] Add round/adaptive icon resources if appropriate.
- [ ] Update manifest to use app-owned icon resources.
- [ ] Remove usage of `@android:drawable/sym_def_app_icon`.

**Validation:**

- [ ] Install debug APK and confirm non-default launcher icon.
- [ ] `./gradlew --no-daemon lintDebug` has no icon/resource warnings.

---

## Final validation gate

Run after all phases that apply:

```bash
cd android
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
```

If Rust/mobile runtime was changed:

```bash
cargo test -p p2p-mobile
cargo test --workspace
```

If skip-Rust workflow was added:

```bash
cd android
./gradlew --no-daemon testDebugUnitTest -PskipRustBuild=true
./gradlew --no-daemon lintDebug -PskipRustBuild=true
./gradlew --no-daemon assembleDebug
```

Manual Android smoke test:

- [ ] Install debug APK on physical Android device.
- [ ] Launch app and verify no startup crash.
- [ ] Verify notification permission flow on Android 13+.
- [ ] Start tunnel in offer mode with valid config.
- [ ] Confirm foreground notification appears.
- [ ] Confirm Home does not show `Connected` until actual connected/session state exists.
- [ ] Confirm status moves through starting/running/waiting/connected/error truthfully.
- [ ] Edit a forward and confirm Home updates when returning.
- [ ] Delete a forward and confirm Home updates when returning.
- [ ] Stop tunnel and confirm uptime disappears.
- [ ] Confirm clean stop does not show stale errors.
- [ ] Toggle network policy to blocked/metered and confirm polling does not resurrect connected/running state incorrectly.
- [ ] Test notification behavior on Android 12 or lower if available.
- [ ] Confirm app launcher icon is no longer the default Android icon.

## Completion checklist

- [ ] P1 lifecycle-scoped ViewModels complete.
- [ ] P2 main-thread safety complete.
- [ ] P3 truthful runtime status complete.
- [ ] P4 runtime cleanup complete.
- [ ] P5 notification correctness complete.
- [ ] P6 settings cleanup complete.
- [ ] P7 atomic forward persistence/Home freshness complete.
- [ ] P8 build workflow hardening complete.
- [ ] P9 launcher icon polish complete.
- [ ] Kotlin lint passes.
- [ ] Kotlin unit tests pass.
- [ ] Android debug APK builds.
- [ ] Rust/mobile tests pass if Rust touched.
- [ ] Manual smoke test passes on device.
