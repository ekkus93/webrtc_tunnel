# replies13.md

# Responses to Android UI Fix TODO 3 Questions

These are the decisions to hand back to GitHub Copilot for the final small Android UI cleanup pass from `ANDROID_UI_CODE_REVIEW3.md` and `ANDROID_UI_FIX_TODO3.md`.

---

## 1. Temporary metered scope

For **Allow This Session**, the allowance should **survive pause/resume**, but it should be cleared on an intentional or terminal stop.

Use this policy:

```text
Allow This Session survives:
- network pause
- metered-blocked pause
- temporary loss of Wi-Fi
- resume after Wi-Fi/cellular state changes
- foreground service remains alive

Allow This Session clears on:
- user taps Stop Tunnel
- service is destroyed
- app process dies
- tunnel runtime is fully stopped
- user disables the temporary allowance manually, if such control exists
```

Do **not** clear it merely because the tunnel pauses due to network policy; otherwise the user may confirm “Allow This Session,” hit a transient network change, and immediately get blocked again.

---

## 2. Temporary allowance source of truth

For v1, the source of truth should live in `TunnelForegroundService` or the service-owned runtime/session controller.

However, the UI still needs observability. So use this split:

```text
Source of truth:
  TunnelForegroundService / session runtime state

UI observability:
  repository exposes read-only status such as:
    temporaryMeteredAllowanceActive: Boolean
    temporaryMeteredAllowanceLabel: "Allowed this session"
```

Do **not** mirror it into persistent DataStore preferences.

Good model:

```kotlin
data class NetworkPolicyUiState(
    val persistentAllowMetered: Boolean,
    val temporaryAllowMeteredForSession: Boolean,
    val effectiveMeteredAllowed: Boolean,
)
```

Where:

```text
effectiveMeteredAllowed =
  persistentAllowMetered || temporaryAllowMeteredForSession
```

---

## 3. Review step Save behavior

After **Save** on the Review step, stay on Review and show confirmation.

Do **not** navigate Home automatically.

Reason: if the user taps **Save**, they may still want to review, edit, or then tap **Start Tunnel**. Navigating away makes the distinction between Save and Start less clear.

Use:

```text
Save:
  saves config
  stays on Review
  shows "Configuration saved" confirmation/snackbar

Start Tunnel:
  saves config
  validates config/identity/network
  starts service
  navigates Home on success
```

The Review content itself should not duplicate Save/Start buttons if the bottom row already has them.

---

## 4. Logs layout pattern

Use an **overflow menu** for secondary actions.

Recommended final layout:

```text
Visible row:
  Pause Logs | Clear Logs | ⋮

Overflow menu:
  Copy Logs
  Export Diagnostics
  Share Diagnostics
```

This is better than trying to keep all actions visible because phone width and large font sizes will remain a problem.

The visible row should prioritize actions a user may need while actively viewing logs. Copy/export/share are still accessible but do not need to consume permanent horizontal space.

---

## 5. Manual validation document target

Use the existing:

```text
docs/ANDROID_VALIDATION.md
```

Append a new dated section for this pass rather than creating another validation file.

Suggested heading:

```markdown
## Android UI Fix TODO 3 Validation — YYYY-MM-DD
```

Include:

```text
Automated validation:
- command
- pass/fail
- notes

Manual UI validation:
- device/emulator
- screen size/orientation
- font scale
- screens checked
- result

Manual Android↔desktop E2E:
- PASSED / FAILED / NOT RUN
- reason if not run
```

This keeps validation history centralized.

---

## 6. Android↔desktop E2E expectation in this pass

Attempt it **if the environment permits**, but do not block this UI cleanup pass on it if there is no dedicated setup window, Android device/emulator network access, MQTT broker, and desktop answer-side service ready.

Use this policy:

```text
If environment is ready:
  run E2E now and document result.

If environment is not ready:
  document "Manual Android↔desktop browser E2E: NOT RUN"
  include the reason.
```

Do **not** mark full product acceptance complete unless E2E actually passes.

Recommended validation note if not run:

```markdown
Manual Android↔desktop browser E2E: NOT RUN

Reason:
No dedicated E2E setup window was available for this UI cleanup pass. This pass was limited to UI semantics, Compose layout, ViewModel behavior, and validation-document cleanup.

Required future E2E:
1. Start desktop p2p-answer.
2. Configure Android p2p-offer from the app UI.
3. Start the tunnel.
4. Open Android browser to http://127.0.0.1:<local_port>.
5. Confirm the remote service responds.
```

---

## Final frozen decisions

1. **Allow This Session** survives pause/resume but clears on stop/service destruction/process death.
2. Temporary allowance source of truth lives in the service/session runtime, not persistent preferences.
3. UI may observe temporary allowance through read-only repository state.
4. Review **Save** stays on Review and shows confirmation.
5. Review **Start Tunnel** saves, validates, starts, then navigates Home on success.
6. Logs should use visible primary actions plus overflow menu.
7. Append validation results to `docs/ANDROID_VALIDATION.md`.
8. Attempt Android↔desktop E2E only if environment is ready; otherwise document `NOT RUN` honestly.
