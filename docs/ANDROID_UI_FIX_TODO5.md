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

- [ ] Do not change MQTT signaling wire format.
- [ ] Do not change tunnel frame format.
- [ ] Do not change desktop Rust protocol semantics.
- [ ] Do not add TURN.
- [ ] Do not add VPN/TUN mode.
- [ ] Do not weaken Android Keystore identity-at-rest behavior.
- [ ] Do not weaken private identity export warning behavior.
- [ ] Do not weaken cellular/metered warning behavior.
- [ ] Do not persist temporary metered allowance to DataStore.
- [ ] Do not weaken log/diagnostic redaction behavior.
- [ ] Do not reintroduce disk/native work in Composable bodies.
- [ ] Do not reintroduce duplicate Review Save/Start controls.
- [ ] Do not break Setup Wizard Add/Edit Forward mode.
- [ ] Do not mark Android↔desktop E2E complete unless it actually ran and passed.
- [ ] Do not mark large-font validation complete unless it actually ran and passed.

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

- [ ] Setup Wizard Add Forward uses Add mode.
- [ ] Setup Wizard Edit Forward uses Edit mode.
- [ ] Settings public identity is loaded through ViewModel state.
- [ ] Settings composable does not directly read files.
- [ ] Home temporary metered allowance does not persist to DataStore.
- [ ] Temporary metered allowance clears on stop/service destroy.
- [ ] Private identity export warning remains.
- [ ] Settings metered warning remains.
- [ ] Setup Wizard metered warning remains.
- [ ] Review Save/Start controls remain non-duplicated.
- [ ] Home configured-forwards display remains.
- [ ] Logs overflow layout remains.
- [ ] Logs/diagnostics remain redacted.

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

- [ ] Public identity loads when Settings ViewModel is created.
- [ ] Opening Settings does not trigger duplicate public identity reads.
- [ ] Settings composable remains passive and reads `SettingsUiState`.
- [ ] Copy public identity still works.
- [ ] Share public identity still works.
- [ ] Missing identity state still works.
- [ ] Public identity read error state still works.

---

# Phase 2 — Targeted test adjustment

## 2.1 Add or update a small test if practical

Add the smallest useful test that confirms public identity refresh behavior.

Possible tests:

- [ ] `SettingsViewModel` loads public identity during init.
- [ ] `SettingsViewModel` does not require a composable-triggered refresh.
- [ ] Missing public identity remains handled.
- [ ] Read error remains handled.

If the repository fake can count reads, add:

- [ ] public identity is read once during ViewModel startup.

Do not create a large testing refactor.

## 2.2 Keep existing tests

Ensure these existing behaviors remain covered:

- [ ] Setup Wizard Add/Edit Forward mode.
- [ ] Home temporary metered allowance does not persist DataStore.
- [ ] Review Save does not start service.
- [ ] Review Start saves and starts service.
- [ ] Settings public identity state handles missing/error states.

## 2.3 Acceptance

- [ ] Tests cover the tiny cleanup or existing tests still prove behavior.
- [ ] No existing tests regress.
- [ ] Test claims are not overstated.

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
- [ ] Large-font validation remains explicitly documented as NOT RUN.

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
- [ ] E2E remains explicitly documented as NOT RUN.
- [ ] Product acceptance is not claimed unless E2E passed.

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

- [ ] Rust fmt passes.
- [ ] Rust clippy passes.
- [ ] Rust tests pass.
- [ ] Android assemble passes.
- [ ] Android unit tests pass.
- [ ] Android connected tests pass or are documented as not run.
- [ ] cargo-ndk Android library build passes.
- [ ] Validation docs are updated honestly.

---

# Phase 6 — Final acceptance checklist

## Code cleanup

- [ ] Duplicate Settings public identity refresh removed.
- [ ] Settings public identity still loads through ViewModel state.
- [ ] Settings composable remains free of direct file reads.
- [ ] Copy public identity still works.
- [ ] Share public identity still works.
- [ ] Missing/error identity states still work.

## Regression preservation

- [ ] Setup Wizard Add Forward uses Add mode.
- [ ] Setup Wizard Edit Forward uses Edit mode.
- [ ] Review Save/Start controls remain non-duplicated.
- [ ] Home temporary metered allowance remains session-scoped.
- [ ] Temporary metered allowance does not persist to DataStore.
- [ ] Temporary metered allowance clears on stop/service destroy.
- [ ] Private identity export warning remains.
- [ ] Metered/cellular warning flows remain.
- [ ] Logs overflow layout remains.
- [ ] Logs/diagnostics remain redacted.

## Validation

- [ ] Targeted tests pass.
- [ ] Automated validation passes or failures are documented.
- [ ] Large-font validation passed or remains honestly NOT RUN.
- [ ] Android↔desktop browser E2E passed or remains honestly NOT RUN.

## Non-regression

- [ ] No protocol behavior changed.
- [ ] No identity-at-rest behavior weakened.
- [ ] No network-policy behavior weakened.
- [ ] No redaction behavior weakened.

## Final note

After this TODO is complete, no further Android UI cleanup TODO should be needed unless new issues appear during large-font validation or Android↔desktop E2E testing.
