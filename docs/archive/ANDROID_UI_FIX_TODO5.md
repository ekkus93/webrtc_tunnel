# ANDROID_UI_FIX_TODO5.md

# Android WebRTC Tunnel UI Fix TODO 5 — Final Tiny Cleanup and Validation

## 1. Goal

Finish the last tiny Android UI cleanup after `ANDROID_UI_FIX_TODO4.md`.

This TODO is intentionally small.

The app is now close. Do not start another broad UI pass.

This pass covers:

1. Remove duplicate Settings public identity refresh.
2. Add/adjust a small targeted test if practical.
3. Keep large-font validation honest.
4. Keep Android↔desktop browser E2E status honest.
5. Update validation docs.

## 2. Non-negotiable rules

- [x] Do not change MQTT signaling wire format.
- [x] Do not change tunnel frame format.
- [x] Do not change desktop Rust protocol semantics.
- [x] Do not add TURN.
- [x] Do not add VPN/TUN mode.
- [x] Do not weaken Android Keystore identity-at-rest behavior.
- [x] Do not weaken private identity export warning behavior.
- [x] Do not weaken cellular/metered warning behavior.
- [x] Do not persist temporary metered allowance to DataStore.
- [x] Do not weaken log/diagnostic redaction behavior.
- [x] Do not reintroduce disk/native work in Composable bodies.
- [x] Do not reintroduce duplicate Review Save/Start controls.
- [x] Do not break Setup Wizard Add/Edit Forward mode.
- [x] Do not mark Android↔desktop E2E complete unless it actually ran and passed.
- [x] Do not mark large-font validation complete unless it actually ran and passed.

---

# Phase 0 — Baseline guardrails

## 0.1 Read relevant files

Read:

```text
ANDROID_UI_CODE_REVIEW5.md
ANDROID_UI_FIX_TODO4.md
ANDROID_UI_CODE_REVIEW4.md
docs/ANDROID_VALIDATION.md
```

## 0.2 Confirm current good behavior remains

Before changing code, confirm:

- [x] Setup Wizard Add Forward uses Add mode.
- [x] Setup Wizard Edit Forward uses Edit mode.
- [x] Settings public identity is loaded through ViewModel state.
- [x] Settings composable does not directly read files.
- [x] Home temporary metered allowance does not persist to DataStore.
- [x] Temporary metered allowance clears on stop/service destroy.
- [x] Private identity export warning remains.
- [x] Settings metered warning remains.
- [x] Setup Wizard metered warning remains.
- [x] Review Save/Start controls remain non-duplicated.
- [x] Home configured-forwards display remains.
- [x] Logs overflow layout remains.
- [x] Logs/diagnostics remain redacted.

---

# Phase 1 — Remove duplicate Settings public identity refresh

## 1.1 Locate refresh calls

Find all calls to:

```kotlin
refreshPublicIdentity()
```

Likely places:

```kotlin
SettingsViewModel.init
SettingsScreen.LaunchedEffect(Unit)
manual refresh action, if any
```

## 1.2 Choose one startup refresh source

Use this policy:

```text
Keep SettingsViewModel init refresh.
Remove SettingsScreen LaunchedEffect(Unit) refresh.
```

The ViewModel should own loading its own state.

## 1.3 Remove duplicate composable-triggered refresh

Remove the Settings composable startup effect if it only duplicates ViewModel init:

```kotlin
LaunchedEffect(Unit) {
    vm.refreshPublicIdentity()
}
```

Do not remove a deliberate user-triggered refresh action if one exists.

## 1.4 Preserve explicit refresh if needed

If the app needs a future explicit refresh, implement it as a clear user action:

```text
Refresh Public Identity
```

or as a lifecycle-aware event with a reason.

Do not use an unconditional duplicate composable startup refresh.

## 1.5 Acceptance

- [x] Public identity loads when Settings ViewModel is created.
- [x] Opening Settings does not trigger duplicate public identity reads.
- [x] Settings composable remains passive and reads `SettingsUiState`.
- [x] Copy public identity still works.
- [x] Share public identity still works.
- [x] Missing identity state still works.
- [x] Public identity read error state still works.

---

# Phase 2 — Targeted test adjustment

## 2.1 Add or update a small test if practical

Add the smallest useful test that confirms public identity refresh behavior.

Possible tests:

- [x] `SettingsViewModel` loads public identity during init.
- [x] `SettingsViewModel` does not require a composable-triggered refresh.
- [x] Missing public identity remains handled.
- [x] Read error remains handled.

If the repository fake can count reads, add:

- [x] public identity is read once during ViewModel startup.

Do not create a large testing refactor.

## 2.2 Keep existing tests

Ensure these existing behaviors remain covered:

- [x] Setup Wizard Add/Edit Forward mode.
- [x] Home temporary metered allowance does not persist DataStore.
- [x] Review Save does not start service.
- [x] Review Start saves and starts service.
- [x] Settings public identity state handles missing/error states.

## 2.3 Acceptance

- [x] Tests cover the tiny cleanup or existing tests still prove behavior.
- [x] No existing tests regress.
- [x] Test claims are not overstated.

---

# Phase 3 — Large-font UI validation

## 3.1 Run if possible

If a device/emulator is available, run a large-font walkthrough.

Minimum screens:

- [ ] Home with no forwards.
- [ ] Home with multiple forwards.
- [ ] Home paused/metered state.
- [ ] Forwards list.
- [ ] Forward Details.
- [ ] Logs.
- [ ] Settings.
- [ ] Setup Wizard.
- [ ] Import / Export.
- [ ] Network Policy.

## 3.2 Document result

Append to:

```text
docs/ANDROID_VALIDATION.md
```

Suggested heading:

```markdown
## Android UI Fix TODO 5 Validation — YYYY-MM-DD
```

Include:

```text
Manual large-font UI validation: PASSED / FAILED / NOT RUN
Device/emulator:
Android version:
Screen size/orientation:
Font scale:
Screens checked:
Known issues:
```

## 3.3 If not run

If not run, document:

```text
Manual large-font UI validation: NOT RUN
```

Do not mark it complete.

## 3.4 Acceptance

- [ ] Large-font validation passed and is documented, or
- [x] Large-font validation remains explicitly documented as NOT RUN.

---

# Phase 4 — Android↔desktop browser E2E

## 4.1 Run if environment is available

Run the real product acceptance test only if the environment is ready:

```text
desktop p2p-answer
Android p2p-offer
Android browser -> http://127.0.0.1:<local_port>
remote service responds
```

Required setup:

- desktop answer-side service available,
- MQTT broker available,
- Android device/emulator can reach broker,
- Android app installed,
- configured local forward,
- remote answer side authorizes Android identity.

## 4.2 Document result

Append to:

```text
docs/ANDROID_VALIDATION.md
```

Include:

```text
Manual Android↔desktop browser E2E: PASSED / FAILED / NOT RUN
Desktop command:
Android device/emulator:
MQTT broker:
Forward config:
Browser URL:
Result:
Errors/logs:
```

## 4.3 If not run

If the environment is not ready, keep it honest:

```text
Manual Android↔desktop browser E2E: NOT RUN
```

Do not claim product acceptance.

## 4.4 Acceptance

- [ ] Android↔desktop browser E2E passed and is documented, or
- [x] E2E remains explicitly documented as NOT RUN.
- [x] Product acceptance is not claimed unless E2E passed.

---

# Phase 5 — Automated validation

## 5.1 Rust validation

Run from repo root:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

## 5.2 Android validation

Run:

```bash
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

If device/emulator available:

```bash
./gradlew connectedDebugAndroidTest
```

## 5.3 Rust Android library build

Run:

```bash
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o android/app/src/main/jniLibs \
  build -p p2p-mobile --release
```

or the configured Gradle task.

## 5.4 Update validation docs

Append command results to:

```text
docs/ANDROID_VALIDATION.md
```

Do not mark commands as passing unless they passed.

## 5.5 Acceptance

- [x] Rust fmt passes.
- [x] Rust clippy passes.
- [x] Rust tests pass.
- [x] Android assemble passes.
- [x] Android unit tests pass.
- [x] Android connected tests pass or are documented as not run.
- [x] cargo-ndk Android library build passes.
- [x] Validation docs are updated honestly.

---

# Phase 6 — Final acceptance checklist

## Code cleanup

- [x] Duplicate Settings public identity refresh removed.
- [x] Settings public identity still loads through ViewModel state.
- [x] Settings composable remains free of direct file reads.
- [x] Copy public identity still works.
- [x] Share public identity still works.
- [x] Missing/error identity states still work.

## Regression preservation

- [x] Setup Wizard Add Forward uses Add mode.
- [x] Setup Wizard Edit Forward uses Edit mode.
- [x] Review Save/Start controls remain non-duplicated.
- [x] Home temporary metered allowance remains session-scoped.
- [x] Temporary metered allowance does not persist to DataStore.
- [x] Temporary metered allowance clears on stop/service destroy.
- [x] Private identity export warning remains.
- [x] Metered/cellular warning flows remain.
- [x] Logs overflow layout remains.
- [x] Logs/diagnostics remain redacted.

## Validation

- [x] Targeted tests pass.
- [x] Automated validation passes or failures are documented.
- [x] Large-font validation passed or remains honestly NOT RUN.
- [x] Android↔desktop browser E2E passed or remains honestly NOT RUN.

## Non-regression

- [x] No protocol behavior changed.
- [x] No identity-at-rest behavior weakened.
- [x] No network-policy behavior weakened.
- [x] No redaction behavior weakened.

## Final note

After this TODO is complete, no further Android UI cleanup TODO should be needed unless new issues appear during large-font validation or Android↔desktop E2E testing.
