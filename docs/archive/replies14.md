# replies14.md

# Responses to Copilot Questions for ANDROID_UI_CODE_REVIEW5 / ANDROID_UI_FIX_TODO5

The uploaded `responses14.md` says there are no blocking questions; only one optional confirmation about whether to add a read-count assertion for duplicate Settings public identity refresh.

---

## 1. Duplicate refresh removal

Confirmed. Remove this from `SettingsScreen`:

```kotlin
LaunchedEffect(Unit) {
    vm.refreshPublicIdentity()
}
```

Keep the `SettingsViewModel.init { refreshPublicIdentity() }` call.

That is the correct fix. The ViewModel should own loading its own state, and the composable should remain passive.

---

## 2. Read-count assertion

Yes, add the read-count assertion.

The existing test proving “state is populated after init” is useful, but it does not directly prove the duplicate refresh was removed. A read-count assertion is low-cost and directly protects against this regression.

Recommended test intent:

```text
SettingsViewModel reads public identity exactly once during startup.
```

A simple pattern is fine:

```kotlin
var readCount = 0

val viewModel = SettingsViewModel(
    ...,
    loadPublicIdentity = {
        readCount += 1
        "public-identity"
    },
)

advanceUntilIdle()

assertEquals("public-identity", viewModel.uiState.value.publicIdentity)
assertEquals(1, readCount)
```

Keep the test focused. Do not turn this into a broader test refactor.

---

## 3. Large-font validation

Agreed. In a CLI/headless environment, large-font walkthrough cannot be run.

Document it as:

```text
Manual large-font UI validation: NOT RUN

Reason:
This pass ran in a CLI/headless environment without an interactive Android UI walkthrough.
```

Leave the large-font checklist items unchecked.

---

## 4. Android↔desktop browser E2E

Agreed. If there is no provisioned desktop `p2p-answer`, MQTT setup, Android device/emulator network path, and browser test window, leave it as:

```text
Manual Android↔desktop browser E2E: NOT RUN

Reason:
No dedicated desktop answer-side service / full E2E environment was provisioned for this pass.
```

Do not claim product acceptance.

---

## 5. Final instruction

Proceed with:

1. remove duplicate `LaunchedEffect(Unit)` refresh,
2. add the read-count assertion,
3. document large-font validation as `NOT RUN`,
4. document Android↔desktop E2E as `NOT RUN`,
5. keep the rest of TODO5 narrow.

No protocol, service, UI architecture, identity, network-policy, or redaction changes are needed.
