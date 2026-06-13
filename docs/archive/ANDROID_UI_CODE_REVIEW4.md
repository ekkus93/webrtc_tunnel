# ANDROID_UI_CODE_REVIEW4.md

# Android WebRTC Tunnel UI Code Review 4 — Tiny Final Cleanup Review

## 1. Review scope

This review covers the latest Android UI state after the `ANDROID_UI_FIX_TODO3.md` cleanup pass.

The app is now very close. This review is intentionally narrow and focuses only on the remaining small issues found in the latest Android app review.

This is **not** a new architecture review and should not trigger protocol, Rust tunnel, MQTT, WebRTC, identity-format, or Android service redesign work.

## 2. Current high-level status

The Android UI is in good shape.

The latest pass fixed or improved:

- Home "Allow This Session" no longer persists permanent `allowMetered = true`.
- Temporary metered allowance is session/runtime state.
- Temporary metered allowance is warning-gated.
- Review-step duplicate Save/Start controls were removed.
- Home is scrollable.
- Forwards is more robust and uses a list layout.
- Logs actions moved to an overflow layout.
- Advanced/debug settings are hidden by default.
- Private identity export warning behavior remains intact.
- Settings and Setup Wizard metered warning behavior remain intact.
- Home configured-forwards display remains intact.
- Validation docs now honestly say manual large-font UI validation and Android↔desktop E2E are not run.

The remaining issues are small but should be fixed before calling the Android UI done.

## 3. Remaining P1 issues

## P1.1 Setup Wizard edit-forward dialog always uses Add mode

### Problem

The Setup Wizard uses the same `editingForward` state for both Add and Edit paths, but it always passes:

```kotlin
mode = ForwardEditorMode.Add
```

to the forward editor dialog.

That means editing an existing forward from the Setup Wizard can show:

```text
Add Forward
Add
```

instead of:

```text
Edit Forward
Save
```

### Why this matters

This is a user-facing correctness bug. The data may still save correctly, but the UI label is wrong and confusing.

### Required fix

Track editor mode explicitly.

Recommended structure:

```kotlin
private data class ForwardEditorState(
    val mode: ForwardEditorMode,
    val draft: ForwardConfig,
)
```

Then:

```kotlin
var editingForward by remember { mutableStateOf<ForwardEditorState?>(null) }

onAdd = {
    editingForward = ForwardEditorState(
        mode = ForwardEditorMode.Add,
        draft = defaultNewForward(forwards),
    )
}

onEdit = {
    editingForward = ForwardEditorState(
        mode = ForwardEditorMode.Edit,
        draft = it,
    )
}
```

Then pass:

```kotlin
mode = editor.mode
initial = editor.draft
```

### Acceptance

- Add from Setup Wizard shows `Add Forward` and `Add`.
- Edit from Setup Wizard shows `Edit Forward` and `Save`.
- Forward Details edit continues to show `Edit Forward` and `Save`.
- Inline validation remains intact.

---

## P1.2 Settings still reads public identity from disk during composition

### Problem

`SettingsScreen` calls something equivalent to:

```kotlin
val publicIdentity = vm.publicIdentityOrNull()
```

directly from the composable.

That method reads from `IdentityRepository.readPublicIdentity()`, which reads a file.

This violates the UI rule that composables should not perform disk I/O during composition.

### Why this matters

Compose functions can recompose often. Disk reads during composition can cause UI jank, nondeterministic behavior, and hard-to-test recomposition side effects.

### Required fix

Move public identity loading into `SettingsViewModel` state.

Recommended model:

```kotlin
data class SettingsUiState(
    val publicIdentity: String? = null,
    val publicIdentityLoadError: String? = null,
)
```

Load it from the ViewModel using IO dispatcher or equivalent repository/state flow:

```kotlin
viewModelScope.launch {
    val identity = withContext(Dispatchers.IO) {
        identityRepository.readPublicIdentity().ifBlank { null }
    }
    _uiState.update { it.copy(publicIdentity = identity) }
}
```

Composable reads only:

```kotlin
val uiState by vm.uiState.collectAsStateWithLifecycle()
val publicIdentity = uiState.publicIdentity
```

### Acceptance

- `SettingsScreen` does not call a function that reads files.
- Public identity is loaded by the ViewModel/repository layer.
- Recomposition does not trigger file reads.
- Settings Copy/Share public identity actions use the state value.

---

## 4. Remaining P2 issues

## P2.1 Large-font UI validation is still not run

The TODO correctly leaves the large-font acceptance item unchecked.

This is honest and acceptable, but it means the final UI pass is not fully signed off.

### Required action

Run or document a manual large-font walkthrough.

Minimum screens:

- Home
- Forwards
- Logs
- Settings
- Setup Wizard
- Forward Details
- Import / Export
- Network Policy

Use a real device or emulator with large system font scale.

### Acceptance

- If run and passed, mark complete with device/font-scale notes.
- If not run, keep unchecked and document `NOT RUN`.

---

## P2.2 Android↔desktop browser E2E is still not run

The validation docs honestly say this is not run.

That is fine for UI cleanup, but product acceptance is not complete until this passes.

### Required action

Keep it documented as `NOT RUN` unless it actually runs.

Do not mark Android↔desktop compatibility fully complete until:

```text
desktop p2p-answer
Android p2p-offer
Android browser -> http://127.0.0.1:<port>
remote service responds
```

has passed.

---

## P2.3 Some UI tests remain manual or thin

The project has useful ViewModel/unit tests, but Compose-level coverage for some warning flows and visual behavior is still limited.

This is acceptable for a tiny cleanup pass if the remaining manual checks are documented, but the TODO should not overstate test coverage.

Recommended additional tests:

- Setup Wizard add/edit forward mode labels.
- Settings public identity is loaded from ViewModel state.
- Settings public identity is not read from disk during composition, where practical.
- Advanced controls hidden by default.
- Home temporary metered allowance does not persist DataStore.
- Review Save does not start service.

## 5. What is good and should not be disturbed

Keep these behaviors intact:

- private identity export warning,
- metered/cellular warning in Settings,
- metered/cellular warning in Setup Wizard,
- Home "Allow This Session" warning,
- temporary metered allowance does not persist to DataStore,
- temporary metered allowance clears on service stop/destroy,
- `SetupWizardState.canAdvance` is state-driven,
- Home configured-forwards display,
- Home and Forwards scrollability,
- Logs overflow menu,
- Forward Details route,
- Android Keystore identity storage,
- log/diagnostic redaction,
- no protocol behavior changes.

## 6. Recommended final patch order

1. Fix Setup Wizard forward editor mode.
2. Move Settings public identity loading out of composition.
3. Add targeted unit/UI tests for those two fixes.
4. Update validation docs with large-font status.
5. Keep Android↔desktop E2E status honest.
6. Run available validation commands.
7. Only then mark the final tiny cleanup TODO complete.

## 7. Bottom line

The Android UI is nearly ready.

The two actual code fixes are small:

```text
Setup Wizard forward editor mode
Settings public identity disk read
```

After those are fixed, the only remaining blockers are validation/documentation items:

```text
large-font manual UI walkthrough
Android↔desktop browser E2E
```

Do not start another broad UI pass unless new issues appear.
