# UNIT_TEST1_TODO.md

# Android Unit Test Expansion TODO 1

## Goal

Add comprehensive Android-side automated test coverage for:

1. `ConfigRepository`
2. `TunnelRepository`
3. `HomeViewModel`
4. `NetworkPolicyManager`
5. `IdentityRepository`
6. `NotificationController`
7. `SetupViewModel` / `SettingsViewModel`
8. `TunnelForegroundService` instrumentation behavior

This TODO is test-focused. Production code changes are allowed only when needed to make behavior testable and must preserve existing runtime behavior.

## Guardrails

- Keep tests deterministic and offline (no live broker/network dependency).
- Prefer JVM unit tests (`src/test`) for logic; use instrumentation tests (`src/androidTest`) only for true Android framework/service lifecycle behavior.
- Avoid broad mocking of core behavior; prefer focused fakes/stubs close to app interfaces.
- Do not weaken security expectations for convenience (permissions, encrypted-at-rest assumptions, fail-closed behavior).
- Keep assertions behavior-focused (state transitions, outputs, side effects), not implementation-detail snapshots.
- Run lint/tests at phase boundaries; fix warnings/errors instead of suppressing.

---

## Phase 0 - Test harness and structure baseline

### 0.1 Inventory current test setup

- [x] Review `android/app/build.gradle.kts` test dependencies.
- [x] Review existing Android tests under:
  - [x] `android/app/src/test/...`
  - [x] `android/app/src/androidTest/...`
- [x] Confirm current runner and instrumentation setup in `AndroidManifest.xml` / Gradle.

### 0.2 Add shared test utilities

- [x] Create JVM test utilities package (example: `android/app/src/test/java/.../testutil`).
- [x] Add reusable fakes:
  - [x] fake `TunnelNativeBridge`
  - [x] fake/in-memory config storage helper
  - [x] fake notification sink wrapper (if needed)
- [x] Add coroutine test helpers where needed (`runTest`, dispatcher setup).
- [x] Add minimal file-system helper for temp file setup/teardown in JVM tests.

### 0.3 Stabilize test naming/layout conventions

- [x] Define naming pattern `<ClassName>Test`.
- [x] Group tests by behavior sections (`given_when_then` style names or equivalent).
- [x] Ensure all new tests are discoverable by `testDebugUnitTest`.

### 0.4 Phase validation

- [x] Run: `cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest`

---

## Phase 1 - `ConfigRepository` unit tests

Target file: `android/app/src/main/java/.../data/ConfigRepository.kt`

### 1.1 Default config behavior

- [x] Test `ensureDefaultConfig()` creates file when missing.
- [x] Test `ensureDefaultConfig()` does not overwrite existing file.
- [x] Test default template content includes required sections/keys for v0.3 format.

### 1.2 Read/write config behavior

- [x] Test `writeConfig()` writes exact content.
- [x] Test `readConfig()` returns current content.
- [x] Test `readConfig()` empty behavior when file absent.
- [x] Test config path points under app-private files dir.

### 1.3 Android-only preferences behavior

- [x] Test default preference values:
  - [x] `allowMetered = false`
  - [x] `pauseOnMetered = true`
  - [x] `resumeOnUnmetered = true`
  - [x] `showMeteredWarning = true`
  - [x] `startTunnelWhenAppOpens = false`
  - [x] `debugLogsEnabled = false`
- [x] Test `savePreferences()` persists all fields.
- [x] Test subsequent reads reflect updates without corruption.

### 1.4 Edge and failure cases

- [x] Test repeated writes are idempotent (latest write wins).
- [x] Test malformed/partial existing preference state still maps to safe defaults.

### 1.5 Phase validation

- [x] Run targeted tests for `ConfigRepositoryTest`.
- [x] Run: `cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest`

---

## Phase 2 - `TunnelRepository` unit tests

Target file: `android/app/src/main/java/.../data/TunnelRepository.kt`

### 2.1 Start/stop path coverage

- [x] Test `start(Offer, ...)` calls `bridge.startOffer(...)`.
- [x] Test `start(Answer, ...)` calls `bridge.startAnswer(...)`.
- [x] Test successful `start()` triggers `refreshStatus()`.
- [x] Test successful `stop()` triggers `refreshStatus()`.

### 2.2 Status decoding behavior

- [x] Test valid status JSON updates exposed `status` flow.
- [x] Test invalid status JSON does not crash and preserves safe behavior.
- [x] Test initial status state is sane before any bridge calls.

### 2.3 Error propagation behavior

- [x] Test start failure propagates `Result.failure`.
- [x] Test stop failure propagates `Result.failure`.
- [x] Test validation pass-through from bridge (`validateConfig`).

### 2.4 Recent logs behavior

- [x] Test valid log JSON returns parsed list.
- [x] Test invalid log JSON returns empty list (existing behavior).

### 2.5 Phase validation

- [x] Run targeted tests for `TunnelRepositoryTest`.
- [x] Run: `cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest`

---

## Phase 3 - `HomeViewModel` unit tests

Target file: `android/app/src/main/java/.../viewmodel/AppViewModels.kt` (`HomeViewModel`)

### 3.1 Service intent wiring tests

- [x] Test `startTunnel(Offer)` dispatches intent action `ACTION_START_OFFER`.
- [x] Test `startTunnel(Answer)` dispatches intent action `ACTION_START_ANSWER`.
- [x] Test `stopTunnel()` dispatches intent action `ACTION_STOP`.
- [x] Test intents target `TunnelForegroundService` class explicitly.

### 3.2 Status passthrough behavior

- [x] Test `status` flow exposure mirrors repository flow.
- [x] Test `refresh()` delegates to repository exactly once per call.

### 3.3 Testability seam tasks (if needed)

- [x] Introduce minimal injectable intent dispatcher abstraction if current code is hard to unit test.
- [x] Keep runtime behavior identical after seam extraction.

### 3.4 Phase validation

- [x] Run targeted tests for `HomeViewModelTest`.
- [x] Run: `cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest`

---

## Phase 4 - `NetworkPolicyManager` unit tests

Target file: `android/app/src/main/java/.../network/NetworkPolicyManager.kt`

### 4.1 Default policy behavior

- [x] Test metered/cellular blocked by default.
- [x] Test unknown/unclassified network treated fail-safe.

### 4.2 Preference-driven policy behavior

- [x] Test explicit opt-in allows metered when configured.
- [x] Test pause/resume flags affect resulting policy state as intended.
- [x] Test warning-required preference is represented correctly.

### 4.3 Network transition handling

- [x] Test transition unmetered -> metered emits expected policy/state.
- [x] Test transition metered -> unmetered emits expected policy/state.
- [x] Test disconnected/no-network emits blocked/no-network state.

### 4.4 Phase validation

- [x] Run targeted tests for `NetworkPolicyManagerTest`.
- [x] Run: `cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest`

---

## Phase 5 - `IdentityRepository` unit tests

Target file: `android/app/src/main/java/.../security/IdentityRepository.kt`

### 5.1 Identity file presence behavior

- [x] Test `hasEncryptedIdentity()` false when no file exists.
- [x] Test `hasEncryptedIdentity()` true after encrypted store.

### 5.2 Encrypted storage behavior

- [x] Test `storeEncryptedIdentity()` writes encrypted payload file.
- [x] Test stored payload is not plaintext private identity bytes.
- [x] Test public identity file is written as expected.

### 5.3 Decryption roundtrip behavior

- [x] Test `readEncryptedIdentity()` returns original private identity bytes after store.
- [x] Test roundtrip with multiple sample payloads (short/long).

### 5.4 Failure/robustness behavior

- [x] Test corrupted encrypted file read fails explicitly.
- [x] Test missing public file does not affect private decrypt path (if current behavior expects this).

### 5.5 Android keystore test strategy subtasks

- [x] Decide per-test approach:
  - [x] Robolectric-backed Android Keystore
  - [x] injectable crypto/key provider seam
- [x] Implement minimal seam only if strictly required for deterministic tests.

### 5.6 Phase validation

- [x] Run targeted tests for `IdentityRepositoryTest`.
- [x] Run: `cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest`

---

## Phase 6 - `NotificationController` unit tests

Target file: `android/app/src/main/java/.../notification/NotificationController.kt`

### 6.1 Channel behavior

- [x] Test `ensureChannels()` creates expected channels IDs/properties.
- [x] Test repeated `ensureChannels()` calls are safe/idempotent.

### 6.2 Notification content behavior

- [x] Test notification built for key service states has expected title/body.
- [x] Test pending intent points to `MainActivity`.
- [x] Test notification action/button wiring for stop/open behaviors (if present).

### 6.3 Permission-gated notify behavior

- [x] Test no-post path when POST_NOTIFICATIONS permission is absent (API 33+ path).
- [x] Test notify path when permission is granted.
- [x] Test failure path logs/handles `notify()` exceptions without crashing.

### 6.4 Phase validation

- [x] Run targeted tests for `NotificationControllerTest`.
- [x] Run: `cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest`

---

## Phase 7 - `SetupViewModel` / `SettingsViewModel` unit tests

Target file: `android/app/src/main/java/.../viewmodel/AppViewModels.kt`

### 7.1 `SetupViewModel` behavior

- [x] Test `validateConfig()` delegates to tunnel repository with current config path.
- [x] Test `saveConfig(contents)` writes contents through config repository.
- [x] Test validation failure result is surfaced unchanged.

### 7.2 `SettingsViewModel` behavior

- [x] Test `validateConfig()` delegates to tunnel repository.
- [x] Test validation success/failure pass-through behavior.

### 7.3 Phase validation

- [x] Run targeted tests for `SetupViewModelTest` and `SettingsViewModelTest`.
- [x] Run: `cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest`

---

## Phase 8 - `TunnelForegroundService` instrumentation tests (`androidTest`)

Target file: `android/app/src/main/java/.../service/TunnelForegroundService.kt`

### 8.1 Instrumentation test scaffolding

- [x] Add instrumentation dependencies/rules needed for service lifecycle tests.
- [x] Add test helper to start app activity and trigger service actions as app UID.
- [x] Ensure tests cleanly stop service and reset app state between cases.

### 8.2 Service start/stop action tests

- [x] Test `ACTION_START_OFFER` starts foreground service and enters foreground state.
- [x] Test `ACTION_START_ANSWER` starts foreground service and enters foreground state.
- [x] Test `ACTION_STOP` stops/tears down service runtime.

### 8.3 Background persistence behavior

- [x] Start service from app UI flow.
- [x] Background activity (home action) in test.
- [x] Assert service remains foreground and active after activity backgrounding.

### 8.4 Notification behavior in service lifecycle

- [x] Assert persistent notification exists while running.
- [x] Assert stop action updates/stops service as expected.

### 8.5 Failure path behavior

- [x] Simulate startup error and assert service reports error state without process crash.
- [x] Assert service status remains queryable after failure.

### 8.6 Phase validation

- [x] Run: `cd android && ./gradlew --no-daemon connectedDebugAndroidTest`
- [x] Re-run: `cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest`

---

## Cross-cutting completion tasks

### C.1 Coverage and regression checks

- [x] Ensure each major Android package has at least one meaningful automated test:
  - [x] `data`
  - [x] `viewmodel`
  - [x] `network`
  - [x] `security`
  - [x] `notification`
  - [x] `service` (instrumentation)
- [x] Add regression tests for any bugs found while writing tests.

### C.2 Documentation updates

- [x] Update `docs/ANDROID_WEBRTC_TUNNEL_TODO.md` test-related checklist/status if scope changed.
- [x] Add a short testing section in Android docs if test commands/layout changed materially.

### C.3 Final validation (required before done)

- [x] Run: `cargo fmt --check`
- [x] Run: `cargo clippy --workspace --all-targets --all-features -- -D warnings`
- [x] Run: `cargo test --workspace --all-targets`
- [x] Run: `cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p p2p-mobile --release`
- [x] Run: `cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest`
- [x] Run instrumentation suite (if emulator/device available): `cd android && ./gradlew --no-daemon connectedDebugAndroidTest`

---

## Acceptance checklist

Mark complete only when all are true:

- [x] `ConfigRepository` tests cover default config, read/write, and preferences.
- [x] `TunnelRepository` tests cover start/stop, status decode, logs decode, and error propagation.
- [x] `HomeViewModel` tests verify foreground-service intent wiring for offer/answer/stop.
- [x] `NetworkPolicyManager` tests verify default block, opt-in, and fail-safe unknown behavior.
- [x] `IdentityRepository` tests verify encrypted-at-rest roundtrip and corruption handling.
- [x] `NotificationController` tests verify channel setup, content, and permission-gated notify.
- [x] `SetupViewModel` and `SettingsViewModel` tests verify validation/save delegation.
- [x] `TunnelForegroundService` instrumentation tests verify start/stop + background persistence.
- [x] Android lint + unit tests pass with no lint errors.
- [x] Workspace fmt/clippy/tests remain green.
