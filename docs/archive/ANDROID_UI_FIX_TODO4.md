# ANDROID_UI_FIX_TODO4.md

# Android WebRTC Tunnel UI Fix TODO 4 — Tiny Final Cleanup

## 1. Goal

Finish the last tiny Android UI cleanup pass.

The app is already close. This TODO only covers the remaining small issues:

1. Fix Setup Wizard Add/Edit Forward dialog mode.
2. Remove Settings composable disk I/O when loading public identity.
3. Add or verify targeted tests for those fixes.
4. Keep large-font validation honest.
5. Keep Android↔desktop E2E status honest.

This is **not** a protocol change, service redesign, UI redesign, or Rust refactor.

## 2. Non-negotiable rules

- [x] Do not change MQTT signaling wire format.
- [x] Do not change tunnel frame format.
- [x] Do not change desktop Rust protocol semantics.
- [x] Do not add TURN.
- [x] Do not add VPN/TUN mode.
- [x] Do not weaken Android Keystore identity-at-rest behavior.
- [x] Do not weaken cellular/metered blocking behavior.
- [x] Do not weaken log/diagnostic redaction behavior.
- [x] Do not persist temporary metered allowance to DataStore.
- [x] Do not reintroduce duplicate Review Save/Start controls.
- [x] Do not reintroduce disk/native work in Composable bodies.
- [x] Do not mark Android↔desktop E2E complete unless it actually ran and passed.

---

# Phase 0 — Baseline guardrails

## 0.1 Read relevant files

Read:

```text
ANDROID_UI_CODE_REVIEW4.md
ANDROID_UI_FIX_TODO3.md
ANDROID_UI_CODE_REVIEW3.md
ANDROID_UI_FIX_TODO2.md
docs/ANDROID_VALIDATION.md
```

## 0.2 Confirm current good behavior remains

Before changing code, confirm the following are still true:

- [x] Home action is labeled `Allow This Session`, not `Allow Temporarily`.
- [x] Home temporary metered allowance does not persist `allowMetered = true`.
- [x] Temporary metered allowance clears on stop/service destroy.
- [x] Settings metered toggle still shows warning before enabling.
- [x] Setup Wizard metered toggle still shows warning before enabling.
- [x] Private identity export still shows warning before export.
- [x] Review step has one Save/Start control set.
- [x] Home is scrollable.
- [x] Forwards screen is scrollable/list-based.
- [x] Logs uses overflow menu for secondary actions.
- [x] Home configured-forwards display still works.
- [x] `SetupWizardState.canAdvance` is ViewModel state.
- [x] Logs/diagnostics remain redacted.

---

# Phase 1 — Fix Setup Wizard Add/Edit Forward dialog mode

## 1.1 Locate Setup Wizard forward editor state

Find the Setup Wizard state used for forward editing.

Likely current pattern:

```kotlin
var editingForward by remember { mutableStateOf<ForwardConfig?>(null) }
```

or equivalent.

## 1.2 Add explicit editor state

Create an explicit state object:

```kotlin
private data class ForwardEditorState(
    val mode: ForwardEditorMode,
    val draft: ForwardConfig,
)
```

If this data class needs to be top-level or private to the file, choose the cleanest Kotlin option.

## 1.3 Wire Add path

When adding a forward:

```kotlin
editingForward = ForwardEditorState(
    mode = ForwardEditorMode.Add,
    draft = defaultNewForward(forwards),
)
```

Expected dialog:

```text
Title: Add Forward
Primary button: Add
```

## 1.4 Wire Edit path

When editing an existing forward:

```kotlin
editingForward = ForwardEditorState(
    mode = ForwardEditorMode.Edit,
    draft = existingForward,
)
```

Expected dialog:

```text
Title: Edit Forward
Primary button: Save
```

## 1.5 Preserve save behavior

The same `vm.upsertForward(...)` path may still be used if it correctly handles both add and edit.

Do not break:

- inline validation,
- duplicate port validation,
- duplicate remote forward ID validation,
- empty name validation,
- empty remote forward ID validation,
- default new forward generation,
- Forward Details edit behavior.

## 1.6 Tests

Add or update tests for:

- [x] Setup Wizard Add Forward opens dialog with `Add Forward`.
- [x] Setup Wizard Add Forward primary button says `Add`.
- [x] Setup Wizard Edit Forward opens dialog with `Edit Forward`.
- [x] Setup Wizard Edit Forward primary button says `Save`.
- [x] Forward Details edit still opens `Edit Forward`.
- [x] Invalid Add/Edit input remains in dialog with inline errors.

## 1.7 Acceptance

- [x] Setup Wizard Add uses Add mode.
- [x] Setup Wizard Edit uses Edit mode.
- [x] Dialog labels match action semantics.
- [x] Existing forward validation still works.

---

# Phase 2 — Remove Settings composable disk I/O

## 2.1 Locate public identity access in Settings

Find any composable code like:

```kotlin
val publicIdentity = vm.publicIdentityOrNull()
```

or any direct call from composition that eventually reads:

```kotlin
IdentityRepository.readPublicIdentity()
File.readText()
File.exists()
```

## 2.2 Add Settings UI state

Add or extend a Settings UI state model:

```kotlin
data class SettingsUiState(
    val publicIdentity: String? = null,
    val publicIdentityLoadError: String? = null,
)
```

If Settings already has a state class, extend it rather than creating unnecessary duplicate state.

## 2.3 Load public identity in ViewModel

Load public identity in the ViewModel or repository layer, not from the composable.

Use an IO-safe path, for example:

```kotlin
fun refreshPublicIdentity() {
    viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                identityRepository.readPublicIdentity().ifBlank { null }
            }
        }

        _uiState.update {
            it.copy(
                publicIdentity = result.getOrNull(),
                publicIdentityLoadError = result.exceptionOrNull()?.message,
            )
        }
    }
}
```

Call it from ViewModel init or an explicit UI event.

## 2.4 Update SettingsScreen

Composable should only read state:

```kotlin
val uiState by vm.uiState.collectAsStateWithLifecycle()
val publicIdentity = uiState.publicIdentity
```

Do not call file-reading methods from the composable.

## 2.5 Update Copy/Share public identity actions

Copy/share actions should use the state value.

If public identity is missing:

- disable action,
- or show `No public identity available`.

Do not read file synchronously on button click unless delegated through ViewModel/repository with coroutine/IO handling.

## 2.6 Tests

Add or update tests for:

- [x] Settings ViewModel loads public identity into state.
- [x] Settings ViewModel handles missing public identity.
- [x] Settings ViewModel handles read errors.
- [x] SettingsScreen no longer calls `publicIdentityOrNull()` during composition.
- [x] Copy/share public identity uses ViewModel state.

Where direct Compose testing is difficult, use unit tests and remove the composable-facing file-read method entirely.

## 2.7 Acceptance

- [x] No disk I/O is triggered directly from `SettingsScreen`.
- [x] Public identity appears through ViewModel state.
- [x] Copy/share public identity still works.
- [x] Missing public identity is handled gracefully.

---

# Phase 3 — Targeted test cleanup

## 3.1 Add focused tests only

Do not create a large test rewrite.

Add the smallest useful tests for:

- [x] Setup Wizard Add/Edit mode.
- [x] Settings public identity state loading.
- [x] Settings missing public identity state.
- [x] Settings read error state, if error path exists.
- [x] Home temporary metered allowance still does not persist DataStore.
- [x] Review Save does not start service.
- [x] Review Start saves and starts service.

## 3.2 Keep existing tests passing

Do not break existing:

- ViewModel tests,
- repository tests,
- service tests,
- Android connected tests,
- Rust tests.

## 3.3 Manual-only items

If large-font usability or visual mockup comparison is manual-only, document it rather than faking a test.

## 3.4 Acceptance

- [x] New tests cover the two code fixes.
- [x] Existing tests still pass.
- [x] Manual-only checks are documented honestly.

---

# Phase 4 — Large-font validation

## 4.1 Run large-font walkthrough if possible

Use a real device or emulator.

Set Android system font/display size high enough to stress the UI.

Minimum screens to check:

- [ ] Home with no forwards.
- [ ] Home with multiple forwards.
- [ ] Home paused/metered state.
- [ ] Forwards list with multiple rows.
- [ ] Forward Details.
- [ ] Logs.
- [ ] Settings.
- [ ] Setup Wizard.
- [ ] Import / Export.
- [ ] Network Policy.

## 4.2 Document result

Append to:

```text
docs/ANDROID_VALIDATION.md
```

Use a heading like:

```markdown
## Android UI Fix TODO 4 Validation — YYYY-MM-DD
```

Include:

```text
Device/emulator:
Android version:
Screen size/orientation:
Font scale:
Screens checked:
Result:
Known issues:
```

## 4.3 If not run

If large-font validation is not run, document:

```text
Manual large-font UI validation: NOT RUN
```

Keep the checklist unchecked.

## 4.4 Acceptance

- [ ] Large-font walkthrough passed and is documented, or
- [x] Large-font walkthrough is explicitly documented as NOT RUN and remains unchecked.

---

# Phase 5 — Android↔desktop E2E honesty

## 5.1 Run if environment is ready

Run the real E2E only if the environment is available:

```text
desktop p2p-answer
Android p2p-offer
Android browser -> http://127.0.0.1:<local_port>
remote service responds
```

## 5.2 Document result

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

## 5.3 If not run

If not run, keep the existing honest status:

```text
Manual Android↔desktop browser E2E: NOT RUN
```

Do not mark product acceptance complete.

## 5.4 Acceptance

- [ ] E2E passed and is documented, or
- [x] E2E remains documented as NOT RUN and product acceptance is not claimed.

---

# Phase 6 — Final validation commands

## 6.1 Rust validation

Run from repo root:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

## 6.2 Android validation

Run:

```bash
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

If emulator/device available:

```bash
./gradlew connectedDebugAndroidTest
```

## 6.3 Rust Android library build

Run:

```bash
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o android/app/src/main/jniLibs \
  build -p p2p-mobile --release
```

or the configured Gradle task.

## 6.4 Update validation docs

Append results to:

```text
docs/ANDROID_VALIDATION.md
```

Do not mark commands as passing unless they pass.

## 6.5 Acceptance

- [x] Rust fmt passes.
- [x] Rust clippy passes.
- [x] Rust tests pass.
- [x] Android assemble passes.
- [x] Android unit tests pass.
- [x] Android connected tests pass or are documented as not run.
- [x] cargo-ndk Android library build passes.
- [x] Validation docs are updated honestly.

---

# Phase 7 — Final acceptance checklist

## Code fixes

- [x] Setup Wizard Add Forward uses Add mode.
- [x] Setup Wizard Edit Forward uses Edit mode.
- [x] Forward editor labels match Add/Edit semantics.
- [x] Settings public identity is loaded through ViewModel state.
- [x] Settings composable does not read files directly.

## Regression preservation

- [x] Home temporary metered allowance does not persist to DataStore.
- [x] Temporary allowance still clears on stop/service destroy.
- [x] Private identity export warning remains.
- [x] Settings metered warning remains.
- [x] Setup Wizard metered warning remains.
- [x] Review Save/Start controls remain non-duplicated.
- [x] Home configured-forwards display remains.
- [x] Logs overflow layout remains.
- [x] Logs/diagnostics remain redacted.

## Tests/docs

- [x] Targeted tests added or verified.
- [x] Validation docs updated.
- [x] Large-font validation passed or remains honestly marked NOT RUN.
- [x] Android↔desktop E2E passed or remains honestly marked NOT RUN.

## Non-regression

- [x] No protocol behavior changed.
- [x] No identity-at-rest behavior weakened.
- [x] No network-policy behavior weakened.
- [x] No redaction behavior weakened.

## Final note

After these items are done, the Android UI cleanup work should be considered complete except for any explicitly documented manual validation or E2E items that remain not run.
