# ANDROID_UI_FIX_TODO2.md

# Android WebRTC Tunnel UI Fix TODO 2 — Polish Hardening Pass

## 1. Goal

Finish the Android UI polish pass by fixing the remaining safety, correctness, usability, and mockup-alignment issues found after reviewing the latest Android app.

This is a **UI/UX hardening pass**, not a protocol rewrite.

Do not change:

- MQTT signaling wire format
- tunnel frame format
- desktop Rust protocol semantics
- TURN policy
- VPN/TUN support
- offer-side `forward_id` behavior
- encrypted identity-at-rest design
- network-policy safety behavior
- log/diagnostic redaction requirements

## 2. Non-negotiable rules

- [x] Do not change tunnel protocol behavior.
- [x] Do not change desktop compatibility.
- [x] Do not weaken Android Keystore encrypted private identity storage.
- [x] Do not allow cellular/metered use without explicit warning and confirmation.
- [x] Do not export private identity without explicit warning and confirmation.
- [x] Do not do disk I/O directly from Composable bodies.
- [x] Do not do native validation directly from Composable bodies.
- [x] Do not perform expensive validation on every keystroke.
- [x] Keep `127.0.0.1` as default local bind host.
- [x] Use Material 3 Compose components.
- [x] Keep long screens usable on normal phone sizes.
- [x] Keep accessibility basics intact.

---

# Phase 0 — Baseline and scope control

## 0.1 Read relevant docs

Read:

```text
ANDROID_UI_POLISH_TODO.md
ANDROID_UI_CODE_REVIEW2.md
ANDROID_WEBRTC_TUNNEL_SPEC.md
ANDROID_FIX_TODO1.md
```

Also inspect the supplied mockup image:

```text
android_screens.png
```

## 0.2 Confirm this is UI polish only

Before making changes, confirm no changes are needed to:

- MQTT signaling wire format
- tunnel frame format
- Rust desktop compatibility
- TURN/VPN support
- answer-side target mapping
- Android encrypted identity storage model

## 0.3 Update TODO honesty

If prior TODO files mark incomplete behavior as complete, add a note rather than silently pretending everything is done.

Do not mark this TODO complete until all acceptance items pass.

---

# Phase 1 — Fix private identity export warning bypass

## 1.1 Locate primary private export UI

Inspect:

```text
ImportExportScreen
Settings identity export actions
IdentityRepository export functions
```

Find any path that exports private identity.

## 1.2 Require warning before SAF CreateDocument

The normal Android document-picker export path must show the private identity warning **before** launching `CreateDocument`.

Correct flow:

```text
User taps Export private identity
  -> show warning dialog
  -> user confirms
  -> launch SAF CreateDocument
  -> write private identity to selected URI
```

Do not launch `CreateDocument` before confirmation.

## 1.3 Require warning before raw-path export

If raw path export remains in Advanced / Developer tools, it must also show the same warning.

## 1.4 Fix misleading function names

If a function named like this returns decrypted plaintext:

```kotlin
readEncryptedIdentity()
```

rename it to something explicit:

```kotlin
readPrivateIdentityPlaintext()
decryptPrivateIdentityForRuntime()
```

Add KDoc:

```text
Returns plaintext private identity bytes. Never log, persist, or include in diagnostics.
```

## 1.5 Tests

Add tests for:

- primary SAF private export shows warning first,
- cancelling warning does not launch export,
- confirming warning launches export flow,
- raw path private export also requires warning,
- exported diagnostics never include private identity,
- plaintext private identity helper is not used by logs/diagnostics.

## 1.6 Acceptance

- [x] No private identity export path bypasses warning.
- [x] User must explicitly confirm risk before export.
- [x] Function names make plaintext identity handling obvious.
- [x] Tests cover the warning flow.

---

# Phase 2 — Fix cellular/metered warning bypasses

## 2.1 Centralize metered-warning behavior

Create a shared helper/component/state flow for enabling metered data.

All UI paths must use it:

- Settings toggle
- Network Policy screen toggle
- Setup Wizard Network Policy step
- Home "Allow Temporarily" action, if present
- Notification action, if implemented

## 2.2 Settings warning

In Settings:

```text
Toggle OFF:
  save immediately

Toggle ON:
  show warning
  confirm -> save allowMetered = true
  cancel -> keep allowMetered = false
```

## 2.3 Setup Wizard warning

In the wizard Network Policy step:

```text
Toggle ON:
  show warning
  confirm -> set input.allowMetered = true
  cancel -> keep false
```

Do not mutate wizard state to `allowMetered = true` before confirmation.

## 2.4 Home Allow Temporarily warning

If Home has an "Allow Temporarily" action, it must show the warning before enabling temporary metered use.

## 2.5 Notification Allow Temporarily warning

If the notification has an "Allow temporarily" action, it should open the app warning flow or use a safe explicit confirmation path.

Do not silently enable metered use from a notification action unless an explicit warning has already been accepted for that temporary period.

## 2.6 Tests

Add tests for:

- Settings ON requires warning,
- Settings cancel keeps blocked,
- Settings confirm enables,
- Wizard ON requires warning,
- Wizard cancel keeps blocked,
- Wizard confirm enables,
- Home temporary allow requires warning,
- no direct state mutation enables metered without confirmation.

## 2.7 Acceptance

- [x] Every metered/cellular enable path shows warning first.
- [x] Cancelling warning keeps metered blocked.
- [x] Confirming warning is required before enabling.
- [x] Cellular/metered remains blocked by default.

---

# Phase 3 — Remove disk/native work from composition

## 3.1 Locate composition-time validation

Find code like:

```kotlin
val canAdvance = remember(state) { vm.canAdvanceFromCurrentStep() }
```

or any composable call that triggers:

- repository file reads
- identity file checks
- native bridge calls
- config validation
- public identity validation
- `loadForwards()`

## 3.2 Move canAdvance into ViewModel state

Add to wizard state:

```kotlin
data class SetupWizardState(
    ...
    val canAdvance: Boolean = false,
    val stepValidation: StepValidationState = StepValidationState.Unknown
)
```

The composable should only read:

```kotlin
state.canAdvance
```

## 3.3 Validate on explicit events

Perform validation only on:

- Import Identity
- Paste Remote Public Identity
- Import Remote Public Identity
- Next button
- Save
- Start Tunnel
- Debounced ViewModel validation, if needed

Do not validate on every keystroke.

## 3.4 Load forwards in ViewModel

Move forwards loading to ViewModel initialization or explicit refresh.

Expose:

```kotlin
val forwards: StateFlow<List<ForwardConfig>>
```

or include forwards in `SetupWizardState`.

## 3.5 Tests

Add tests:

- recomposition does not call repository file read,
- recomposition does not call native validation,
- typing identity path does not read files per character,
- typing remote identity does not call native validation per character,
- import button validates exactly once,
- Next triggers validation exactly once.

## 3.6 Acceptance

- [x] No disk I/O from Composable bodies.
- [x] No native validation from Composable bodies.
- [x] No expensive validation on every keystroke.
- [x] `canAdvance` comes from ViewModel state.

---

# Phase 4 — Fix Home forwards summary

## 4.1 Audit Home forwards source

Find where Home reads forwards.

If it reads only:

```kotlin
status.forwards
```

that is insufficient unless native status actually includes configured forwards.

## 4.2 Combine configured forwards with runtime status

Create Home UI state that combines:

```text
Tunnel runtime status
ConfigRepository configured forwards
Network status
Service state
```

Recommended:

```kotlin
data class HomeUiState(
    val tunnelStatus: TunnelStatus,
    val configuredForwards: List<ForwardConfig>,
    val forwardRuntimeStates: Map<String, ForwardRuntimeState>,
    val networkStatus: NetworkStatus,
    ...
)
```

## 4.3 Display configured forwards in all states

Home should show configured forwards when:

- stopped,
- starting,
- listening,
- connected,
- paused,
- error,
- no network.

Only show empty state if no forwards are configured.

## 4.4 Tests

Add tests:

- Home shows configured forwards when stopped,
- Home shows configured forwards when connected,
- Home shows configured forwards when paused,
- Home empty state appears only with no configured forwards,
- Home forward rows display `127.0.0.1:<port> -> <forward_id>`.

## 4.5 Acceptance

- [x] Home forwards summary matches configured forwards.
- [x] Home no longer depends solely on native `status.forwards`.
- [x] Mockup-style `llama` / `ssh` rows can appear correctly.

---

# Phase 5 — Make long screens scrollable

## 5.1 Audit screen containers

Find screens using fixed non-scrollable `Column` with large content.

Likely screens:

- Setup Wizard
- Settings
- Import / Export
- Network Policy
- Logs
- Forward Details
- Broker step
- Forwards step
- Review step

## 5.2 Add scrollable screen surface

Implement a reusable scrollable surface:

```kotlin
@Composable
fun ScrollableScreenSurface(
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit
)
```

or use:

```kotlin
Modifier.verticalScroll(rememberScrollState())
```

where appropriate.

## 5.3 Preserve bottom action rows

Wizard Back/Next row should remain accessible.

Preferred:

- scrollable content area
- fixed bottom action row

Do not bury Next/Back below inaccessible content.

## 5.4 Test large font / small screen

Manual test:

- small phone viewport,
- large system font,
- Settings screen,
- Setup Broker step,
- Forwards step with multiple forwards,
- Import / Export screen.

## 5.5 Acceptance

- [x] No long screen clips content on normal phones.
- [x] Setup Wizard remains usable on small screens.
- [x] Buttons remain reachable.
- [x] Large font does not make screens unusable.

---

# Phase 6 — Fix Logs screen layout

## 6.1 Replace overcrowded action row

Do not show five full buttons in one horizontal row.

Use one:

- FlowRow
- two action rows
- overflow menu
- primary/secondary split

Recommended layout:

```text
[Pause Logs] [Clear Logs]
[Copy Logs] [Export Diagnostics]
[Share Diagnostics]
```

or:

```text
[Pause Logs] [Clear Logs] [⋮]
```

## 6.2 Debug log behavior

If debug logs are disabled:

- either hide debug rows,
- or show a single muted placeholder explaining debug logs are hidden.

Do not clutter the log list with many repeated "Debug event hidden" rows.

## 6.3 Redaction at copy/export time

Ensure logs are redacted when:

- displayed,
- copied,
- exported,
- shared.

Do not rely only on earlier log ingestion redaction.

## 6.4 Tests

Add tests:

- action row does not overflow in normal width,
- debug logs hidden when debug disabled,
- copied logs are redacted,
- exported logs are redacted.

## 6.5 Acceptance

- [x] Logs screen is usable on phone width.
- [x] Logs actions do not overflow.
- [x] Debug logs do not overwhelm default view.
- [x] Copy/export/share remain redacted.

---

# Phase 7 — Fix Setup Wizard mockup alignment

## 7.1 Replace WizardStepper

Implement compact circular stepper:

```text
①──②──③──④──⑤──⑥──⑦
Step 3 of 7: MQTT Broker
```

Requirements:

- active step highlighted navy/blue,
- completed steps distinct,
- future steps muted,
- fits phone width,
- accessible labels.

## 7.2 Move TLS switch out of Advanced

MQTT Broker step should show TLS as a normal field.

Advanced can include:

- password file path,
- custom CA path if supported,
- keepalive,
- raw debug fields.

## 7.3 Remove Answer mode button from Remote Peer

Remote Peer step should contain only:

- remote peer ID,
- remote public identity,
- paste/import actions,
- helper text.

Remove unrelated "Answer mode disabled" button.

## 7.4 Fix Review step actions

Use one clear model.

Preferred bottom row:

```text
Back | Save | Start Tunnel
```

or:

```text
Back | Start Tunnel
```

with "Save and start later" inside content.

Requirements:

- Save only saves.
- Start Tunnel saves, validates, checks network/identity, and starts service.
- Labels must match behavior.

## 7.5 Tests

Add tests:

- stepper displays seven circles,
- Broker step has visible TLS switch,
- Remote Peer step has no Answer mode control,
- Review Start calls start flow,
- Save action does not claim to start if it does not.

## 7.6 Acceptance

- [x] Setup Wizard visually closer to mockup.
- [x] TLS is not hidden.
- [x] Remote Peer step is focused.
- [x] Review actions are not confusing.

---

# Phase 8 — Fix Settings and Import/Export polish

## 8.1 Collapse Advanced section by default

Settings Advanced section should be collapsed until opened.

Do not show debug/raw path controls by default.

## 8.2 Raw path fallback remains developer-only

Raw path import/export should be under Advanced / Developer.

Label clearly:

```text
Developer raw path fallback
```

## 8.3 Settings metered toggle uses shared warning

Do not directly save `allowMetered = true`.

Use shared warning flow from Phase 2.

## 8.4 Settings private export uses warning

If Settings directly exports or navigates to export private identity, ensure warning appears before export.

## 8.5 Tests

Add tests:

- Advanced collapsed by default,
- expanding Advanced reveals debug/raw path controls,
- Settings metered ON shows warning,
- Settings private export path shows warning.

## 8.6 Acceptance

- [x] Settings is less cluttered.
- [x] Dangerous/debug tools are hidden behind Advanced.
- [x] Warning paths cannot be bypassed.

---

# Phase 9 — Fix Forward Details and forward editor polish

## 9.1 Navigate back after delete

After deleting a forward from details:

```text
delete confirmed
  -> delete forward
  -> navigate back to Forwards list
```

Do not leave user on "Forward not found."

## 9.2 Inline validation in editor

Show validation errors before closing dialog.

Validation:

- name required,
- local port 1-65535,
- duplicate enabled local port rejected,
- remote forward_id required,
- duplicate enabled remote forward_id rejected if that policy remains,
- remote host/port not available.

## 9.3 Safer add-forward defaults

Avoid pre-filling remote forward ID with misleading values like `"ssh"` unless adding from a template.

Recommended empty new forward:

```text
name = ""
localHost = "127.0.0.1"
localPort = first suggested free port, or blank
remoteForwardId = ""
enabled = true
```

## 9.4 Tests

Add tests:

- delete from details navigates back,
- invalid forward stays in dialog with inline error,
- duplicate port rejected inline,
- required remote forward ID enforced,
- add-forward default does not create misleading remote ID.

## 9.5 Acceptance

- [x] Forward Details deletion UX is clean.
- [x] Forward editor validates inline.
- [x] Add Forward does not create misleading defaults.

---

# Phase 10 — Accessibility and copy polish

## 10.1 Improve content descriptions

Replace generic descriptions:

```text
Copy icon
Delete icon
Share icon
```

with action-specific descriptions:

```text
Copy public key
Delete forward llama
Share diagnostics
Open http://127.0.0.1:8080 in browser
```

## 10.2 Remove no-op clickable-looking controls

If something looks clickable, it should do something.

Example to audit:

```kotlin
AssistChip(onClick = {})
```

Replace with non-clickable label or implement action.

## 10.3 Maintain 48dp targets

Ensure:

- icon buttons,
- switches,
- tab items,
- forward rows,
- wizard cards,
- action buttons

meet minimum touch target.

## 10.4 Large font check

Manual check with large system font.

Fix:

- clipped text,
- overflowing action rows,
- unreachable buttons.

## 10.5 Tests/manual checks

Add Compose UI tests where practical:

- important icons have content descriptions,
- no critical action lacks accessible label,
- bottom nav labels visible,
- warning dialogs readable.

## 10.6 Acceptance

- [x] Content descriptions are action-specific.
- [x] Color is not the only state indicator.
- [x] No no-op clickable controls.
- [x] Large text remains usable.

---

# Phase 11 — Final validation

## 11.1 Android unit tests

Run:

```bash
cd android
./gradlew testDebugUnitTest
```

## 11.2 Android build

Run:

```bash
cd android
./gradlew assembleDebug
```

## 11.3 Rust validation

Run from repo root:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

## 11.4 Rust Android build

Run:

```bash
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o android/app/src/main/jniLibs \
  build -p p2p-mobile --release
```

or the configured Gradle task.

## 11.5 Connected tests

If emulator/device is available:

```bash
cd android
./gradlew connectedDebugAndroidTest
```

Status: completed successfully on `emulator-5554`.

## 11.6 Manual mockup comparison

Compare against `android_screens.png`:

- Home connected
- Home paused cellular
- Setup Mode
- Setup Identity
- Setup MQTT Broker
- Setup Remote Peer
- Setup Forwards
- Setup Network Policy
- Forwards List
- Forward Details
- Logs
- Settings

Status: completed in this pass.

Intentional differences documented:

- Wizard stepper uses Material-themed numbered markers and connectors rather than literal unicode circled numerals.
- Setup includes explicit "Answer mode not available on Android v1" messaging in the Mode step for scope clarity.

Document any intentional differences.

## 11.7 Acceptance

- [x] Unit tests pass.
- [x] Android build passes.
- [x] Rust tests still pass.
- [x] Android Rust library still builds.
- [x] Manual mockup comparison passes or deviations documented.
- [x] No P0/P1 issues remain.

---

# Phase 12 — Final acceptance checklist

## Security / warning UX

- [x] Private identity export warning cannot be bypassed.
- [x] Settings metered toggle warning cannot be bypassed.
- [x] Wizard metered toggle warning cannot be bypassed.
- [x] Home/notification temporary metered allow warning cannot be bypassed, if present.

## Composition correctness

- [x] No disk I/O from composable bodies.
- [x] No native validation from composable bodies.
- [x] No expensive validation on every keystroke.
- [x] Wizard `canAdvance` comes from ViewModel state.

## Home

- [x] Home shows configured forwards in all relevant states.
- [x] Uptime formatted as `HH:MM:SS`.
- [x] App bar title uses `WebRTC Tunnel`.
- [x] State-aware actions remain correct.

## Scrollability

- [x] Setup Wizard steps scroll correctly.
- [x] Settings scrolls correctly.
- [x] Import / Export scrolls correctly.
- [x] Logs action layout does not overflow.
- [x] Large font remains usable.

## Setup Wizard

- [x] Circular stepper implemented.
- [x] TLS switch visible in Broker step.
- [x] Remote Peer step contains no Answer mode control.
- [x] Review step has clear Save/Start behavior.
- [x] Wizard visually aligns with mockup.

## Settings / Import Export

- [x] Advanced collapsed by default.
- [x] Raw path tools hidden behind Advanced.
- [x] Private export warning path fixed.
- [x] Metered warning path fixed.

## Forwards

- [x] Forward Details delete navigates back.
- [x] Forward editor validates inline.
- [x] Add Forward defaults are not misleading.
- [x] List rows remain compact.

## Accessibility

- [x] Action-specific content descriptions.
- [x] 48dp touch targets.
- [x] No no-op clickable controls.
- [x] Color is not sole state indicator.
- [x] Warning dialogs screen-reader friendly.

## Regression

- [x] No protocol behavior changed.
- [x] No identity-at-rest behavior weakened.
- [x] No network-policy behavior weakened.
- [x] Logs/diagnostics remain redacted.
- [x] Validation commands pass or failures documented.
