# ANDROID_UI_FIX_TODO3.md

# Android WebRTC Tunnel UI Fix TODO 3 — Final UI Cleanup Pass

## 1. Goal

Complete the final small Android UI cleanup pass after `ANDROID_UI_FIX_TODO2.md`.

The app is close. This TODO focuses only on the remaining UI/UX correctness issues:

1. make "Allow Temporarily" truly temporary or rename it,
2. remove duplicate Setup Wizard Review controls,
3. make Home and Forwards scrollable / large-font safe,
4. reduce Logs action crowding,
5. add or verify missing targeted tests,
6. document mockup differences and E2E status honestly.

This is **not** a protocol rewrite and not a new Android architecture pass.

## 2. Non-negotiable rules

- [x] Do not change MQTT signaling wire format.
- [x] Do not change tunnel frame format.
- [x] Do not change desktop Rust protocol semantics.
- [x] Do not add TURN.
- [x] Do not add VPN/TUN mode.
- [x] Do not weaken Android Keystore identity-at-rest behavior.
- [x] Do not weaken cellular/metered blocking behavior.
- [x] Do not weaken log/diagnostic redaction behavior.
- [x] Do not expose arbitrary remote host/port selection from Android offer side.
- [x] Do not persist metered allowance from a UI action labeled temporary.
- [x] Do not mark E2E compatibility complete unless actually run.

---

# Phase 0 — Baseline and scope control

## 0.1 Read relevant docs

Read:

```text
ANDROID_UI_CODE_REVIEW3.md
ANDROID_UI_FIX_TODO2.md
ANDROID_UI_POLISH_TODO.md
ANDROID_WEBRTC_TUNNEL_SPEC.md
ANDROID_FIX_TODO1.md
```

## 0.2 Confirm this pass is narrow

This pass should only touch:

- UI state semantics,
- Compose layout,
- ViewModel action behavior,
- tests,
- documentation/validation notes.

Do not start another large refactor.

## 0.3 Preserve current good behavior

Before changing code, verify the following still exist:

- private identity export warning,
- metered/cellular warning in Settings,
- metered/cellular warning in Setup Wizard,
- `SetupWizardState.canAdvance`,
- Home configured-forwards display,
- Forward Details route,
- Logs redaction,
- Android Keystore identity storage.

---

# Phase 1 — Fix Home "Allow Temporarily" semantics

## 1.1 Locate all temporary metered actions

Find all UI or notification actions labeled like:

```text
Allow Temporarily
Allow This Session
Allow for 15 minutes
```

Likely places:

- Home paused/metered state,
- Network Policy screen,
- notification action,
- service command,
- repository/ViewModel method such as `allowMeteredTemporarily()`.

## 1.2 Decide final v1 wording and behavior

Use one of these exact policies.

### Preferred policy: Allow This Session

Use button label:

```text
Allow This Session
```

Behavior:

- show cellular/metered warning,
- user confirms,
- allow metered only until tunnel stops or app process/service stops,
- do **not** persist `allowMetered = true` in DataStore.

Store temporary allowance in service/repository runtime state, not the persistent preferences.

### Alternative policy: Permanent setting

If implementation persists `allowMetered = true`, button must be renamed:

```text
Allow Metered Data
```

and should preferably route to Settings.

Do not use "temporarily" wording for permanent behavior.

## 1.3 Implement session-scoped temporary allowance

If using preferred policy, implement state such as:

```kotlin
data class MeteredAllowanceState(
    val allowForCurrentSession: Boolean = false,
    val expiresAtMillis: Long? = null
)
```

or a simpler service-level flag:

```kotlin
var allowMeteredForCurrentRun: Boolean
```

Rules:

- default false,
- cleared on tunnel stop,
- cleared on service destroy,
- does not modify DataStore `allowMetered`,
- visible in UI if active.

## 1.4 Make service honor temporary allowance

Network policy should allow metered only if:

```text
persistent allowMetered == true
OR temporary/session allowance == true
```

If temporary allowance expires or tunnel stops, metered becomes blocked again unless persistent `allowMetered` is true.

## 1.5 Warning remains required

Even temporary allowance must show warning first.

Flow:

```text
Tap Allow This Session
  -> show warning
  -> confirm
  -> set temporary allowance
  -> start/resume tunnel
```

## 1.6 Tests

Add tests:

- Home temporary action does not set persistent `allowMetered = true`,
- warning is required before temporary allowance,
- cancelling warning leaves metered blocked,
- confirming warning allows current session,
- stopping tunnel clears temporary allowance,
- persistent Settings allow still works separately.

## 1.7 Acceptance

- [x] No temporary-labeled action persists `allowMetered = true`.
- [x] Temporary allowance is cleared when tunnel stops/service ends.
- [x] Warning is still required.
- [x] UI wording matches behavior.

---

# Phase 2 — Remove duplicate Review-step controls

## 2.1 Inspect Review step

Find Save/Start controls in:

- Setup Wizard shell bottom row,
- `ReviewStepContent`,
- ViewModel event handlers.

## 2.2 Choose one clear layout

Use this model:

```text
Review screen content:
  summary cards only
  no duplicate Save/Start buttons inside content

Fixed bottom action row:
  Back | Save | Start Tunnel
```

If screen width is too narrow, use stacked buttons:

```text
Start Tunnel
Save
Back
```

but still avoid duplicate controls.

## 2.3 Define action semantics

`Save`:

- saves config,
- does not start tunnel,
- shows saved confirmation or returns Home.

`Start Tunnel`:

- saves config,
- validates config,
- checks identity,
- checks network policy,
- starts ForegroundService,
- navigates Home or shows inline error.

Labels must match behavior.

## 2.4 Tests

Add tests:

- Review displays only one Save action,
- Review displays only one Start action,
- Save does not start service,
- Start saves and starts service,
- failed validation shows inline error and does not start service.

## 2.5 Acceptance

- [x] No duplicate Save/Start controls on Review.
- [x] Save and Start labels match behavior.
- [x] Review step is less confusing.

---

# Phase 3 — Finish Home and Forwards scrollability

## 3.1 Audit Home layout

Check whether Home still uses non-scrollable `ScreenSurface` / `Column`.

Home must support:

- small phone screens,
- large system font,
- many forwards,
- error card,
- paused/network card,
- notification permission card.

## 3.2 Make Home scrollable

Use:

```kotlin
LazyColumn
```

or:

```kotlin
Modifier.verticalScroll(rememberScrollState())
```

Requirements:

- status card visible at top,
- action buttons reachable,
- forwards list does not clip,
- error card reachable,
- no nested scroll conflicts.

## 3.3 Audit Forwards layout

Check whether Forwards uses fixed `ScreenSurface` with nested `LazyColumn`.

## 3.4 Make Forwards screen robust

Preferred:

```text
Scaffold
  top app bar
  LazyColumn for list content
```

Avoid nesting `LazyColumn` inside a non-scrollable weighted container unless tested.

## 3.5 Large font / small screen manual check

Manually verify:

- Home with 0 forwards,
- Home with 3+ forwards,
- Home paused metered state,
- Home error state,
- Forwards with many rows,
- Forwards with long names,
- large system font.

## 3.6 Tests

Add Compose tests where practical:

- Home renders with many forwards,
- Forwards renders with many forwards,
- key buttons remain present,
- no obvious clipping in test constraints.

## 3.7 Acceptance

- [x] Home is scrollable or otherwise large-font safe.
- [x] Forwards is scrollable or otherwise large-font safe.
- [x] Main actions remain reachable.
- [x] No content clips on normal phone sizes.

---

# Phase 4 — Tighten Logs layout

## 4.1 Audit Logs action layout

Find actions:

- Pause Logs,
- Clear Logs,
- Copy Logs,
- Export Diagnostics,
- Share Diagnostics.

## 4.2 Avoid three long buttons in one row

Use one of:

### Option A — Primary row plus overflow

```text
Pause Logs | Clear Logs | ⋮
```

Overflow menu:

```text
Copy Logs
Export Diagnostics
Share Diagnostics
```

### Option B — Stacked actions

```text
Pause Logs | Clear Logs
Copy Logs | Export
Share Diagnostics
```

### Option C — FlowRow

Use `FlowRow` if already available and stable in the Compose dependency set.

## 4.3 Keep labels readable

Avoid truncating important labels.

Short labels acceptable:

```text
Copy
Export
Share
```

if section context makes meaning clear.

## 4.4 Tests/manual checks

Verify:

- phone-width layout,
- large font,
- landscape if practical,
- no clipped buttons,
- actions remain accessible.

## 4.5 Acceptance

- [x] Logs actions do not overflow.
- [x] Logs screen remains readable on phone width.
- [x] Copy/export/share still work.
- [x] Redaction remains intact.

---

# Phase 5 — Advanced Settings final polish

## 5.1 Decide acceptable advanced UX

The current Settings may show the Advanced section card while hiding controls until enabled.

Acceptable v1 approaches:

### Preferred

A collapsed expandable card:

```text
Advanced  >
```

Tap expands to show controls.

### Acceptable

A visible Advanced section with a single `Show advanced settings` switch, and no advanced controls visible until enabled.

## 5.2 Make state clear

If using the acceptable switch model:

- label should be clear,
- advanced controls must be hidden when false,
- no dangerous/debug action visible before enabling.

## 5.3 Tests

Add tests:

- advanced controls hidden by default,
- enabling advanced reveals raw/debug controls,
- disabling hides them again.

## 5.4 Acceptance

- [x] Advanced/debug controls are hidden by default.
- [x] User must intentionally reveal advanced controls.
- [x] Settings is not cluttered by raw path/debug options.

---

# Phase 6 — Fix add/edit forward dialog polish

## 6.1 Add explicit mode

Pass explicit mode to the dialog:

```kotlin
enum class ForwardEditorMode {
    Add,
    Edit
}
```

or:

```kotlin
isNew: Boolean
```

Do not infer add/edit from `initial.id == value.id`.

## 6.2 Correct title and button text

Add mode:

```text
Title: Add Forward
Button: Add
```

Edit mode:

```text
Title: Edit Forward
Button: Save
```

## 6.3 Keep inline validation

Ensure existing inline validation remains.

## 6.4 Tests

Add tests:

- add dialog title is Add Forward,
- edit dialog title is Edit Forward,
- invalid add remains open,
- valid add creates forward,
- valid edit updates forward.

## 6.5 Acceptance

- [x] Add/Edit dialog labels are correct.
- [x] No misleading defaults.
- [x] Inline validation still works.

---

# Phase 7 — Improve accessibility/copy polish

## 7.1 Context-specific content descriptions

Update key actions:

```text
Copy llama local URL
Open llama local URL in browser
Delete forward llama
Share diagnostics
Copy public identity
Export private identity
```

Avoid generic:

```text
Copy icon
Delete icon
Share icon
```

## 7.2 Remove or fix no-op clickable controls

Audit for:

```kotlin
onClick = {}
```

or clickable-looking components that do nothing.

Replace with:

- non-clickable label,
- real action,
- disabled state with explanation.

## 7.3 Large font check

Use large font and verify:

- Home,
- Forwards,
- Logs,
- Settings,
- Setup Wizard.

## 7.4 Tests/manual checks

Add tests where practical for content descriptions on important actions.

Manual checks are acceptable for large font.

## 7.5 Acceptance

- [x] Important icons/buttons have action-specific descriptions.
- [x] No no-op clickable-looking controls remain.
- [ ] Large font remains usable.

---

# Phase 8 — Test and validation honesty

## 8.1 Add targeted tests where practical

Add or verify tests for:

- temporary metered allowance does not persist,
- private identity export warning path,
- Settings metered warning,
- Wizard metered warning,
- Home configured forwards in stopped/connected/paused states,
- `SetupWizardState.canAdvance` is state-driven,
- Review has one Save/Start control set,
- Add/Edit forward dialog titles,
- advanced settings hidden by default.

## 8.2 Document manual-only checks

If UI behavior is manually checked instead of automated, document:

- what was checked,
- device/emulator,
- screen size,
- font scale,
- result.

Use:

```text
docs/ANDROID_UI_VALIDATION.md
```

or append to existing validation docs.

## 8.3 Do not over-check TODOs

Only mark checklist items complete if implemented and either tested or manually verified.

## 8.4 Acceptance

- [x] Test coverage matches claims.
- [x] Manual-only checks are documented.
- [x] TODO checkboxes are honest.

---

# Phase 9 — Android↔desktop E2E status

## 9.1 Run E2E if possible

Run the real acceptance test:

```text
desktop p2p-answer
Android p2p-offer
Android browser -> http://127.0.0.1:<port>
remote service responds
```

## 9.2 Document result

Document:

- desktop command,
- Android app version/build,
- Android device/emulator,
- network type,
- forward configuration,
- browser URL,
- success/failure,
- logs/errors.

## 9.3 If not run

If not run, explicitly write:

```text
Manual Android↔desktop browser E2E: NOT RUN
```

Do not mark product acceptance complete.

## 9.4 Acceptance

- [x] E2E run and passed, or not-run status documented honestly.
- [x] Merge readiness notes reflect E2E status.

---

# Phase 10 — Final validation

## 10.1 Rust validation

Run from repo root:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

## 10.2 Android validation

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

## 10.3 Rust Android library build

Run:

```bash
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o android/app/src/main/jniLibs \
  build -p p2p-mobile --release
```

or the configured Gradle task.

## 10.4 Validation notes

Record results in validation docs.

Do not mark validation complete unless commands pass.

## 10.5 Acceptance

- [x] Rust validation passes.
- [x] Android assemble passes.
- [x] Android unit tests pass.
- [x] Android connected tests pass or are documented as not run.
- [x] cargo-ndk Android build passes.
- [x] Failures are documented and checklist remains unchecked.

---

# Phase 11 — Final acceptance checklist

## Temporary metered allowance

- [x] Temporary-labeled action does not persist permanent metered allowance.
- [x] Temporary allowance requires warning.
- [x] Temporary allowance clears on stop/service end.
- [x] UI wording matches actual behavior.

## Review step

- [x] No duplicate Save/Start controls.
- [x] Save only saves.
- [x] Start saves/validates/starts.
- [x] Errors display inline.

## Scrollability/layout

- [x] Home scrolls or is otherwise safe on small phones.
- [x] Forwards scrolls or is otherwise safe on small phones.
- [x] Logs actions do not overflow.
- [x] Wizard actions remain reachable.
- [ ] Large font remains usable.

## Settings/forwards/accessibility

- [x] Advanced controls hidden by default.
- [x] Add/Edit forward dialog labels correct.
- [x] Important content descriptions are context-specific.
- [x] No no-op clickable controls.

## Tests/docs

- [x] Targeted tests added or verified.
- [x] Manual UI checks documented.
- [x] Android↔desktop E2E passed or not-run status documented.
- [x] Validation commands pass or failures documented.

## Regression

- [x] No protocol behavior changed.
- [x] No identity-at-rest behavior weakened.
- [x] No network-policy behavior weakened.
- [x] Logs/diagnostics remain redacted.
