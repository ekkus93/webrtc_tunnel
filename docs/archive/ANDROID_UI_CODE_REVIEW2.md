# ANDROID_UI_CODE_REVIEW2.md

# Android WebRTC Tunnel UI Code Review 2 — UI Polish Hardening Review

## 1. Review scope

This review covers the Android UI/UX polish pass for the `webrtc_tunnel` Android app.

It reviews the current Android implementation against:

- `ANDROID_UI_POLISH_TODO.md`
- the original `android_screens.png` mockup
- the Android UI/UX requirements from the prior Android specs
- the product safety requirements around metered/cellular data and private identity export

This is a **UI polish hardening review**, not a protocol or Rust tunnel redesign review.

The UI polish pass should not change:

- MQTT signaling wire format
- tunnel frame format
- desktop Rust protocol semantics
- TURN policy
- VPN/TUN support
- offer-side `forward_id` behavior
- encrypted identity-at-rest behavior
- network policy safety behavior
- log/diagnostic redaction behavior

## 2. High-level verdict

The current Android UI is much improved compared with the first scaffold.

The implementation now has:

- a light Material-style visual system
- navy top app bars
- white cards
- reusable UI components
- bottom navigation for main tabs
- secondary screens with back navigation
- a real Setup Wizard skeleton
- Forwards List and Forward Details screens
- Logs screen with filters/actions
- Settings sections
- Import/Export UX using Android document picker flows
- notification permission UX
- better redaction/diagnostic concepts

However, the UI polish pass is not fully complete. Several checklist items appear marked complete even though the implementation still has important issues.

The most important remaining problems are:

1. Private identity export warning can be bypassed through the normal SAF export path.
2. Cellular/metered enablement warning can be bypassed from Settings and Setup Wizard.
3. Setup Wizard still performs disk/native validation from composition via `canAdvanceFromCurrentStep()`.
4. Home forwards summary likely does not show configured forwards because it relies on `status.forwards`.
5. Many long screens are not scrollable and will clip on real phones.
6. Logs actions are too wide for normal phone widths.
7. Review step has confusing Save vs Start behavior.
8. Wizard stepper does not match the circular mockup design.
9. Settings Advanced section is not actually collapsed by default.
10. Some content descriptions and accessibility behavior need another pass.

Do not merge the UI polish pass as complete until these are fixed.

## 3. What is good

### 3.1 Visual design is much closer to the mockup

The app now uses:

- navy app bars
- light app background
- white cards
- rounded corners
- consistent spacing
- success/warning/error colors
- Material 3 components

This is much closer to the mockup and much better than the earlier developer-control-panel look.

### 3.2 Navigation structure is mostly correct

The app now clearly separates:

```text
Main tabs:
  Home
  Forwards
  Logs
  Settings

Secondary flows:
  Setup Wizard
  Forward Details
  Import / Export
  Network Policy
```

Bottom navigation is hidden on secondary flows. That matches the intended UX.

### 3.3 Home screen is much more product-like

Home now has:

- friendly status labels
- status card
- network card
- forwards summary card
- state-aware actions
- error resolution card

This is the correct structure.

### 3.4 Setup Wizard is no longer just a placeholder

The wizard now includes real steps:

```text
Mode
Identity
Broker
Peer
Forwards
Network Policy
Review
```

It has identity, broker, peer, forwards, and policy-related UI.

### 3.5 Forward Details exists

Forward Details includes the main actions:

- Copy URL
- Open Browser
- Test Local Port
- Edit
- Disable/Enable
- Delete

This matches the mockup concept.

### 3.6 Settings and Import/Export are much more complete

The app now has sectioned Settings and SAF-style import/export flows, which are necessary for a real Android app.

### 3.7 Runtime/protocol safety appears preserved

This UI pass does not appear to change the Rust tunnel protocol, MQTT wire format, tunnel frame format, or `forward_id` security model.

## 4. P0 issues

## P0.1 Private identity export warning is bypassed by SAF export

### Problem

The normal Import / Export screen path for exporting private identity launches the Android document creator directly, then calls export with `confirmRisk = true`.

This means the primary export path can write the decrypted private identity without first showing the required warning dialog.

### Why this matters

Private identity export is one of the most sensitive actions in the app.

The UX requirement is:

```text
Private export requires explicit warning before export.
```

The user must see and confirm:

```text
Anyone with this file can impersonate this phone in your tunnel network.
```

### Required fix

The primary "Export private identity" button must open the warning dialog first.

Only after the user confirms should the app launch `CreateDocument`.

Correct flow:

```text
Tap Export private identity
  -> show warning dialog
  -> user confirms
  -> launch SAF CreateDocument
  -> export plaintext private identity to chosen URI
```

Do not call the export function with `confirmRisk = true` until after the warning is confirmed.

### Also fix naming

Rename any misleading function such as:

```kotlin
readEncryptedIdentity()
```

if it returns decrypted plaintext bytes.

Use an explicit name:

```kotlin
readPrivateIdentityPlaintext()
decryptPrivateIdentityForRuntime()
```

Add KDoc:

```text
Returns plaintext private identity bytes. Never log, persist, or include in diagnostics.
```

---

## P0.2 Metered/cellular warning is bypassed in Settings

### Problem

The Settings screen appears to allow this directly:

```text
Allow cellular / metered data = ON
```

without showing the required warning dialog.

### Why this matters

The app must not allow cellular/metered data unless the user explicitly understands the risk. The warning is not optional.

### Required fix

All UI paths that enable metered/cellular use must go through a shared warning flow.

Behavior:

```text
Toggle OFF:
  save immediately

Toggle ON:
  show warning dialog
  if confirmed:
    save allowMetered = true
  if cancelled:
    leave allowMetered = false
```

Use the same warning text across:

- Settings
- Network Policy screen
- Setup Wizard
- Home "Allow Temporarily" action, if present

---

## P0.3 Metered/cellular warning is bypassed in Setup Wizard

### Problem

The Setup Wizard Network Policy step toggles `allowMetered` directly.

### Required fix

The wizard must also show the warning before enabling metered/cellular data.

Do not let `state.input.allowMetered = true` be set without confirmation.

Recommended flow:

```text
User toggles Allow cellular/metered ON
  -> show warning
  -> confirm sets allowMetered = true
  -> cancel leaves allowMetered = false
```

---

## P0.4 Setup Wizard still performs disk/native validation during composition

### Problem

The wizard computes:

```kotlin
val canAdvance = remember(state) { vm.canAdvanceFromCurrentStep() }
```

But `canAdvanceFromCurrentStep()` can call validation code that performs disk or native work, including:

- loading forwards from config storage
- checking identity file state
- validating public identity through repository/native bridge

This violates the UI polish rule:

```text
Do not perform disk I/O or native validation directly in Composable bodies.
```

### Required fix

Move `canAdvance` fully into ViewModel state.

The composable should read:

```kotlin
val canAdvance = state.canAdvance
```

The ViewModel should update `canAdvance` when:

- current step changes
- form input changes
- explicit validation completes
- import/paste actions complete
- forwards are loaded/changed

Do not call file/native validation from a composable or from a function called directly during composition.

### Acceptance

- recomposition does not read disk
- recomposition does not call native validation
- text typing does not call file/native validation per character
- validation occurs on import, paste, Next, Save, or debounced ViewModel events

---

## P0.5 Home forwards summary likely does not show configured forwards

### Problem

The Home screen displays forwards from:

```kotlin
status.forwards
```

But native runtime status may not populate configured forwards. The configured forwards live in the config repository.

Result: the Home screen may show no forwards even though the user configured forwards.

### Required fix

Home state should combine:

```text
Tunnel runtime status
+
configured forwards from ConfigRepository
+
network policy state
```

Recommended:

```kotlin
data class HomeUiState(
    val tunnelStatus: TunnelStatus,
    val configuredForwards: List<ForwardConfig>,
    val networkStatus: NetworkStatus,
    ...
)
```

Display configured forwards with best available runtime status.

### Acceptance

- Home connected screen shows configured forwards.
- Home stopped screen still shows configured forwards.
- Home paused screen still shows configured forwards.
- Empty state appears only when no forwards are configured.

---

## 5. P1 issues

## P1.1 Long screens are not scrollable

Many screens use fixed-height `Column` layouts without vertical scrolling.

Likely affected:

- Setup Wizard steps
- Settings
- Import / Export
- Network Policy
- Logs
- Forward Details on small screens

### Required fix

Use `LazyColumn` or `verticalScroll(rememberScrollState())` for long screens.

Recommended pattern:

```kotlin
@Composable
fun ScrollableScreenSurface(...) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ...
    }
}
```

Use non-scrollable layouts only for screens with guaranteed short content.

---

## P1.2 Logs action row is too wide

The Logs screen has too many actions in one horizontal row.

### Required fix

Use one of:

- `FlowRow`
- two action rows
- overflow menu
- primary actions visible, secondary in menu

Recommended visible actions:

```text
Pause Logs
Clear Logs
```

Secondary actions:

```text
Copy Logs
Export Diagnostics
Share Diagnostics
```

---

## P1.3 Review step Save vs Start behavior is confusing

The Review step appears to show both:

```text
Save / Start Tunnel
Start tunnel now
```

but the "Save / Start Tunnel" action may only save.

### Required fix

Use one clear finishing model.

Preferred:

```text
Back | Save | Start Tunnel
```

Where:

- Save saves config and exits/returns Home.
- Start Tunnel saves, validates, checks network/identity, starts service.

Alternative:

```text
Back | Start Tunnel
```

and provide "Save and start later" inside the Review content.

Do not label a save-only action as "Save / Start Tunnel."

---

## P1.4 Wizard stepper should match the mockup more closely

Current implementation uses seven rectangular labeled boxes. The mockup uses numbered circles connected by a line.

### Required fix

Use a compact circle stepper:

```text
①──②──③──④──⑤──⑥──⑦
Step 3 of 7: MQTT Broker
```

Requirements:

- fits phone width
- active step highlighted navy/blue
- completed steps distinct
- future steps muted
- current step title shown below

---

## P1.5 MQTT TLS switch should be visible

The mockup shows TLS directly on the MQTT Broker step. The current implementation hides TLS in Advanced.

### Required fix

Move TLS enabled switch to the main broker card.

Advanced can contain:

- password file path
- custom CA path if supported
- keepalive
- debug-only fields

TLS itself is not advanced.

---

## P1.6 Remove unrelated Answer mode control from Remote Peer step

Remote Peer step should only configure the remote answer peer identity.

If there is a disabled "Answer mode disabled" button there, remove it.

Answer mode belongs only in:

- Choose Mode step
- Settings Advanced, if needed
- future answer-mode flow

---

## P1.7 Settings Advanced section should be collapsed by default

The TODO required Advanced to be hidden/collapsed by default.

### Required fix

Implement an expandable Advanced section:

```text
Advanced
  collapsed by default
  tap expands
```

Contents only visible when expanded.

Advanced may include:

- debug logs
- raw path fallback
- custom topic prefix
- non-localhost bind
- answer mode placeholder

---

## P1.8 Forward Details should navigate away after delete

After deleting a forward from Forward Details, navigate back to the Forwards list.

Do not leave the user on a now-missing forward details page.

---

## P1.9 Forward edit validation should be inline

The forward editor should show validation errors before closing.

Validation:

- name required
- local port 1-65535
- no duplicate enabled local ports
- remote forward_id required
- no remote host/port fields

Do not close the dialog and only then show a generic message.

---

## P1.10 Home uptime should be formatted

Display uptime as:

```text
00:12:34
```

not raw seconds.

---

## P1.11 Home app bar title should be the app name

The Home screen should use:

```text
WebRTC Tunnel
```

or:

```text
Rust WebRTC Tunnel
```

not just:

```text
Home
```

Since the app/repo is moving toward `webrtc_tunnel`, prefer:

```text
WebRTC Tunnel
```

---

## 6. Accessibility issues

### Problems

Some accessibility issues remain:

- generic icon descriptions
- dense horizontal button rows
- screens that do not scroll with large fonts
- some clickable-looking chips may have no action
- status color is improved but should always have text labels

### Required fix

Improve content descriptions:

```text
Copy public key
Delete forward llama
Open http://127.0.0.1:8080 in browser
Share diagnostics
```

Avoid generic:

```text
Copy icon
Delete icon
```

Ensure:

- 48dp minimum touch targets
- text labels for all states
- screen-reader-friendly warning dialogs
- UI works with large system font

## 7. Comparison with android_screens.png

### Good matches

The current implementation matches the mockup in broad structure:

- navy top bar
- light cards
- Home status/network/forwards cards
- four bottom tabs
- seven-step setup wizard concept
- Forwards list
- Forward Details
- Logs
- Settings

### Remaining differences

Home:
- app bar title should be app name
- configured forwards may not appear
- uptime formatting should match mockup

Setup Wizard:
- stepper should be circular, not rectangular labels
- TLS should be visible
- Remote Peer should not have answer-mode button
- warning flow missing for metered toggle
- Review step confusing

Logs:
- action row too crowded

Settings:
- Advanced should be collapsed
- metered toggle should warn

Import/Export:
- private identity export warning path must be fixed

## 8. Recommended fix order

1. Fix private identity export warning bypass.
2. Fix metered/cellular warning bypass in Settings, Wizard, and Home.
3. Remove disk/native validation from composition.
4. Make Home show configured forwards.
5. Make long screens scrollable.
6. Fix Review step actions.
7. Replace WizardStepper with compact circular stepper.
8. Move TLS switch out of Advanced.
9. Remove Answer mode from Remote Peer step.
10. Collapse Settings Advanced section.
11. Fix Logs action row.
12. Navigate back after Forward Details delete.
13. Improve forward editor inline validation.
14. Add accessibility/content-description pass.
15. Add tests and rerun validation.

## 9. Bottom line

The UI is much closer to the target mockup and is on the right track.

However, the current pass still has real safety and correctness issues. The private identity export warning bypass and cellular/metered warning bypass must be fixed before the UI can be considered safe. The validation-in-composition problem should also be fixed before polishing further, because it can cause subtle performance and correctness bugs.

After these fixes, the app will be much closer to a polished Android product.
