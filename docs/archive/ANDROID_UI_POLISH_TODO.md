# Android WebRTC Tunnel UI Polish TODO

## 1. Goal

Polish the Android UI/UX so the app matches the original Material-style design and is usable as an end-user Android app, not just a developer control panel.

This is a UI/UX pass. Do not change tunnel protocol, MQTT wire format, WebRTC behavior, identity format, or desktop compatibility.

## 2. Rules

- [x] Do not change MQTT signaling wire format.
- [x] Do not change tunnel frame format.
- [x] Do not change desktop Rust protocol semantics.
- [x] Do not add TURN.
- [x] Do not add VPN/TUN mode.
- [x] Do not add arbitrary remote host/port selection from Android offer side.
- [x] Do not weaken encrypted identity-at-rest behavior.
- [x] Do not weaken network policy behavior.
- [x] Do not weaken log/diagnostic redaction.
- [x] Keep cellular/metered blocked by default.
- [x] Keep `127.0.0.1` as the default local bind host.
- [x] Use Material 3 Compose components unless there is a clear reason not to.
- [x] Do not perform disk I/O or native validation directly in Composable bodies.

---

# Phase 1 — Apply explicit visual design system

## 1.1 Replace generic dark theme

Current app should move away from generic dark theme.

Implement a custom light color scheme:

- [x] App background: `#F6F8FB`
- [x] Card background: `#FFFFFF`
- [x] App bar navy: `#061A3D`
- [x] Primary button navy: `#08245C`
- [x] Accent blue: `#1D4ED8`
- [x] Success green: `#2E7D32`
- [x] Warning orange: `#F59E0B`
- [x] Error red: `#D32F2F`
- [x] Border/divider: `#E5E7EB`
- [x] Primary text: `#111827`
- [x] Secondary text: `#6B7280`

## 1.2 Typography

Use default Android/Material **Roboto**.

Apply consistent type scale:

- [x] App bar title: 18sp, medium/semibold.
- [x] Screen title: 22sp, semibold.
- [x] Card title: 18sp, semibold.
- [x] Status title: 20sp, semibold.
- [x] Body text: 14–16sp.
- [x] Helper/meta text: 12–13sp.
- [x] Button text: 14sp, medium.

## 1.3 Shapes and spacing

Implement shared dimensions:

- [x] screen padding: 16dp;
- [x] card padding: 16dp;
- [x] card spacing: 12dp;
- [x] section spacing: 20dp;
- [x] card corner radius: 16dp;
- [x] button minimum height: 48dp;
- [x] minimum touch target: 48dp.

## 1.4 Reusable components

Create or refactor reusable components:

- [x] `TunnelTopAppBar`
- [x] `StatusCard`
- [x] `NetworkStatusCard`
- [x] `ForwardSummaryRow`
- [x] `EmptyStateCard`
- [x] `ErrorResolutionCard`
- [x] `WizardStepper`
- [x] `SectionHeader`
- [x] `SettingsSection`
- [x] `DestructiveActionButton`

## 1.5 Acceptance

- [x] App visually uses navy top bars, light background, white cards.
- [x] Status states use green/orange/red consistently.
- [x] Typography and spacing are consistent across screens.
- [x] UI looks closer to the original mockup image.

---

# Phase 2 — Fix global navigation behavior

## 2.1 Bottom navigation only on main tabs

Main tabs:

```text
Home
Forwards
Logs
Settings
```

Tasks:

- [x] Show bottom navigation only on main tabs.
- [x] Hide bottom navigation on Setup Wizard.
- [x] Hide bottom navigation on Forward Details.
- [x] Hide bottom navigation on Import / Export.
- [x] Hide bottom navigation on Network Policy details.
- [x] Secondary flows use top app bar with back arrow.

## 2.2 Avoid duplicate nav stack entries

Update bottom nav navigation:

```kotlin
navController.navigate(route) {
    popUpTo(navController.graph.findStartDestination().id) {
        saveState = true
    }
    launchSingleTop = true
    restoreState = true
}
```

Tasks:

- [x] Home tab does not stack duplicate Home screens.
- [x] Forwards tab does not stack duplicate Forwards screens.
- [x] Logs tab does not stack duplicate Logs screens.
- [x] Settings tab does not stack duplicate Settings screens.

## 2.3 Tests / manual checks

- [x] Navigate between tabs repeatedly; back stack remains sane.
- [x] Setup Wizard back arrow returns to prior screen.
- [x] Forward Details back arrow returns to Forwards.
- [x] Android system back works naturally.

---

# Phase 3 — Polish Home / Status screen

## 3.1 Friendly status labels

Replace raw enum names with user-facing labels.

Map examples:

- [x] `Stopped` -> `Stopped`
- [x] `Starting` -> `Starting`
- [x] `Connected` -> `Connected`
- [x] `Listening` -> `Listening`
- [x] `PausedMeteredBlocked` -> `Paused`
- [x] `NoNetwork` -> `No network`
- [x] `Error` -> `Error`
- [x] `ConfigInvalid` -> `Configuration needs attention`
- [x] `Stopping` -> `Stopping`

Add friendly descriptions:

- [x] Connected: `Tunnel is active and ready to use.`
- [x] Paused: `Cellular/metered network blocked.`
- [x] Stopped: `Tunnel service is not running.`
- [x] No network: `Connect to Wi-Fi to start the tunnel.`
- [x] Config invalid: `Open setup to fix configuration.`

## 3.2 State-aware action row

Do not always show both Start and Stop.

Implement:

- [x] Stopped: `Start Tunnel`, `Setup`
- [x] Starting: `Stop`, `View Logs`, spinner
- [x] Connected/Listening: `Stop Tunnel`, `View Logs`, optional `Open URL`
- [x] PausedMeteredBlocked: `Settings`, `Stop`, optional `Allow Temporarily`
- [x] NoNetwork: `Retry`, `Settings`
- [x] Error: `Retry`, `View Logs`, contextual fix action
- [x] ConfigInvalid: `Open Setup`, `View Logs`

## 3.3 Improve cards

Status card:

- [x] large icon;
- [x] friendly title;
- [x] description;
- [x] mode;
- [x] remote peer;
- [x] active sessions;
- [x] uptime;
- [x] last error if present with friendly fix.

Network card:

- [x] Wi-Fi/cellular/no-network icon;
- [x] network type;
- [x] metered/unmetered;
- [x] tunnel allowed/blocked;
- [x] blocked reason.

Forwards summary:

- [x] show configured forwards;
- [x] status dot/icon per row;
- [x] `127.0.0.1:<port> -> <forward_id>`;
- [x] add forward action;
- [x] empty state when none.

## 3.4 Error resolution

Add `ErrorResolutionCard`.

Tasks:

- [x] friendly error summary;
- [x] suggested fix;
- [x] technical details collapsed by default;
- [x] action button: Retry / Edit Forward / Open Setup / View Logs.

## 3.5 Acceptance

- [x] No raw enum names are visible on Home.
- [x] Home actions match current state.
- [x] Home looks like a dashboard, not a debug dump.
- [x] Error state gives next-step guidance.

---

# Phase 4 — Rebuild Setup Wizard UX

## 4.1 Wizard shell

Tasks:

- [x] Make Setup Wizard a secondary flow with back arrow top app bar.
- [x] Hide bottom navigation during wizard.
- [x] Add numbered horizontal `WizardStepper`.
- [x] Show current step number and title.
- [x] Add Cancel action.
- [x] Use Back/Next bottom row.
- [x] Disable Next until current step is valid when practical.
- [x] Review step uses Back / Save / Start Tunnel.

## 4.2 Step 1 — Choose Mode

Implement selectable cards:

- [x] Offer/client card with icon and description.
- [x] Answer/server card marked Advanced or Not available yet.
- [x] Offer selected by default.
- [x] If answer unsupported, answer card disabled with explanation.
- [x] Do not show only plain text.

## 4.3 Step 2 — Identity

Local identity only.

Tasks:

- [x] Generate new identity action.
- [x] Import existing identity using Android file picker if possible.
- [x] Hide raw path import behind Advanced/debug.
- [x] Show local peer ID.
- [x] Show public identity.
- [x] Copy Public Key action.
- [x] Share Public Key action.
- [x] Do not show remote public identity here.
- [x] Do not validate identity file on every keystroke.
- [x] Private identity export warning remains intact.

## 4.4 Step 3 — MQTT Broker

Tasks:

- [x] Broker host field.
- [x] Port field.
- [x] TLS enabled switch.
- [x] Username optional field.
- [x] Password field or password-file-path field clearly labeled.
- [x] Topic prefix optional field.
- [x] Test Connection action.
- [x] Password hidden if actual password.
- [x] No password/secrets in logs.

## 4.5 Step 4 — Remote Peer

Tasks:

- [x] Remote peer ID field.
- [x] Remote public identity field.
- [x] Paste from Clipboard button.
- [x] Import File button.
- [x] Validate peer ID/public identity match.
- [x] Reject local identity as remote peer.
- [x] Helper text explaining answer side must authorize this phone.

## 4.6 Step 5 — Forwards

The wizard must support forward editing directly.

Tasks:

- [x] List current forwards inside wizard.
- [x] Add Forward button.
- [x] Edit Forward action.
- [x] Delete Forward action.
- [x] Enable/disable forward.
- [x] Inline forward editor or dialog.
- [x] Validate name required.
- [x] Validate local port 1-65535.
- [x] Reject duplicate enabled local ports.
- [x] Validate remote forward_id required.
- [x] Reject duplicate enabled remote forward_id.
- [x] Hide non-localhost bind behind Advanced warning.
- [x] User does not need to leave wizard to configure forwards.

## 4.7 Step 6 — Network Policy

Tasks:

- [x] Show current network type.
- [x] Show metered/unmetered.
- [x] Show tunnel allowed/blocked.
- [x] Show blocked reason.
- [x] Allow cellular/metered toggle.
- [x] Show warning before enabling cellular/metered.
- [x] Resume when Wi-Fi returns toggle.
- [x] Explain Unknown network is blocked.

## 4.8 Step 7 — Review

Tasks:

- [x] Summary card for Mode.
- [x] Summary card for Local Identity.
- [x] Summary card for Remote Peer.
- [x] Summary card for Broker.
- [x] Summary card for Network Policy.
- [x] Summary card for Forwards.
- [x] Start Tunnel disabled if previous steps invalid.
- [x] Start Tunnel saves, validates, checks identity/network, and starts service.
- [x] Errors shown inline and actionably.

## 4.9 Acceptance

- [x] Setup Wizard visually matches original seven-step design.
- [x] Wizard can complete first-run setup without leaving wizard.
- [x] Wizard does not require TOML editing or raw path typing for normal flow.
- [x] Wizard has a real progress indicator.

---

# Phase 5 — Refactor setup data loading and validation

## 5.1 Remove disk I/O from composition

Fix any code like:

```kotlin
val forwards = vm.loadSavedForwards()
```

inside Composables.

Tasks:

- [x] Move forwards loading into `SetupViewModel`.
- [x] Expose forwards as `StateFlow`.
- [x] Use `collectAsStateWithLifecycle()`.
- [x] No file I/O from Composable body.

## 5.2 Stop validating files on every keystroke

Tasks:

- [x] Text field changes update only text state.
- [x] Import Identity button performs file read/validation.
- [x] Import Public Identity button performs validation.
- [x] Paste action validates pasted text.
- [x] Next button validates final values.
- [x] Native validation is not called on every keystroke.

## 5.3 Tests

- [x] Composable does not trigger file load during recomposition.
- [x] identity path typing does not call file read each character.
- [x] import button calls validation exactly once.
- [x] pasted public identity validates on paste/import/Next.

## 5.4 Acceptance

- [x] Setup Wizard is responsive.
- [x] No disk/native work happens directly in composition.
- [x] No expensive validation on every keystroke.

---

# Phase 6 — Implement Forward Details screen

## 6.1 Add route

Add route:

```text
forwardDetails/{forwardId}
```

Tasks:

- [x] Forwards row tap navigates to details.
- [x] Details screen has top app bar with back arrow.
- [x] Bottom nav hidden on details screen.

## 6.2 Details layout

Show:

- [x] forward name;
- [x] status;
- [x] local address;
- [x] local URL;
- [x] remote forward_id;
- [x] bytes sent if available;
- [x] bytes received if available;
- [x] open connections if available;
- [x] last error.

## 6.3 Actions

Implement:

- [x] Copy URL.
- [x] Open Browser.
- [x] Test Local Port.
- [x] Edit.
- [x] Disable/Enable.
- [x] Delete with confirmation.

## 6.4 Forwards list cleanup

List row should be concise:

- [x] status dot/icon;
- [x] name;
- [x] local address -> remote ID;
- [x] status text;
- [x] chevron.

Do not cram all details/actions into the list row.

## 6.5 Acceptance

- [x] Dedicated Forward Details screen exists.
- [x] Forwards list is clean and scannable.
- [x] Details screen matches original mockup concept.

---

# Phase 7 — Polish Logs screen

## 7.1 Layout

Tasks:

- [x] Top app bar title `Logs`.
- [x] Filter chips: All / Info / Warn / Error / Debug.
- [x] Log rows with timestamp and message.
- [x] Action row: Copy Logs / Clear Logs / Export Diagnostics / Pause Logs.
- [x] Empty state when no logs.

## 7.2 Presentation

Tasks:

- [x] Info logs use default text.
- [x] Warn logs use orange indicator.
- [x] Error logs use red indicator.
- [x] Debug logs use muted style.
- [x] Long messages wrap cleanly.
- [x] Raw JSON hidden unless debug mode is enabled.

## 7.3 Redaction

Confirm:

- [x] displayed logs are redacted;
- [x] copied logs are redacted;
- [x] exported diagnostics are redacted;
- [x] secrets do not appear in UI.

## 7.4 Acceptance

- [x] Logs screen is readable for normal users.
- [x] Debug details are available without overwhelming default view.

---

# Phase 8 — Rebuild Settings screen sections

## 8.1 Section structure

Implement sections:

- [x] Tunnel
- [x] Network Policy
- [x] Identity
- [x] Configuration
- [x] Diagnostics
- [x] Advanced
- [x] About

## 8.2 Tunnel section

Include:

- [x] Start tunnel automatically when app opens.
- [x] Resume tunnel when Wi-Fi returns.
- [x] Run setup wizard again.

## 8.3 Network Policy section

Include:

- [x] Allow cellular / metered data.
- [x] Show warning before allowing cellular / metered data.
- [x] Open Network Policy details.

## 8.4 Identity section

Include:

- [x] View public identity.
- [x] Copy public identity.
- [x] Share public identity.
- [x] Import identity.
- [x] Export public identity.
- [x] Export private identity with warning.

## 8.5 Configuration section

Include:

- [x] Import configuration.
- [x] Export configuration with warning.
- [x] Validate configuration.
- [x] Reset configuration.

## 8.6 Diagnostics section

Include:

- [x] Export diagnostics.
- [x] Share diagnostics.
- [x] Copy status JSON.
- [x] Copy redacted config.

## 8.7 Advanced section

Collapsed by default.

Include:

- [x] Debug logs.
- [x] Developer/debug raw path import/export.
- [x] Custom topic prefix if supported.
- [x] Non-localhost bind controls, if supported.
- [x] Answer mode, if present.

## 8.8 Acceptance

- [x] Settings is not just a list of navigation links.
- [x] Settings matches the original sectioned spec.
- [x] Dangerous/debug items are hidden behind Advanced.

---

# Phase 9 — Improve Import / Export UX

## 9.1 Primary actions

Use Android-safe flows as the primary UI:

- [x] Import config: document picker.
- [x] Export config: create document with warning.
- [x] Import identity: document picker.
- [x] Export public identity: create document/share.
- [x] Export private identity: create document with private identity warning.
- [x] Import remote public identity: document picker/paste.
- [x] Export/share diagnostics: create document/share.

## 9.2 Hide raw paths

Tasks:

- [x] Move raw path fields to Advanced / Developer fallback.
- [x] Collapse Advanced by default.
- [x] Label raw path fallback as developer/debug only.
- [x] Do not show raw path fields in normal first-run setup.

## 9.3 Acceptance

- [x] Normal user can import/export without typing filesystem paths.
- [x] Developer raw path fallback exists only behind Advanced.
- [x] Sensitive export warnings remain.

---

# Phase 10 — Notification permission UX

## 10.1 Android 13+ permission prompt

Implement runtime notification permission flow for Android 13+.

Tasks:

- [x] Detect if `POST_NOTIFICATIONS` permission is needed.
- [x] Show explanation before request.
- [x] Request permission.
- [x] Handle denied state.
- [x] Show Settings action if permission denied.

Explanation text:

```text
Rust WebRTC Tunnel needs notifications so Android can keep the tunnel service visible while it is running in the background.
```

## 10.2 Tests/manual checks

- [x] Fresh install on Android 13+ shows explanation.
- [x] Allow path works.
- [x] Deny path shows warning/action.
- [x] Tunnel behavior remains correct if permission is denied.

## 10.3 Acceptance

- [x] Notification permission UX exists.
- [x] User understands why notifications are needed.

---

# Phase 11 — Accessibility pass

## 11.1 Content descriptions

Add content descriptions for actionable icons:

- [x] Home tab icon.
- [x] Forwards tab icon.
- [x] Logs tab icon.
- [x] Settings tab icon.
- [x] Add forward icon.
- [x] Delete icon.
- [x] Copy icon.
- [x] Share icon.
- [x] Open browser icon.
- [x] Status icons where needed.

## 11.2 Touch targets

Ensure minimum 48dp touch target for:

- [x] buttons;
- [x] icon buttons;
- [x] switches;
- [x] bottom nav items;
- [x] list rows.

## 11.3 Color and text

- [x] Color is not the only state indicator.
- [x] Status labels are text-visible.
- [x] Error/warning text is readable.
- [x] Text scales with system font size.
- [x] Dialogs are screen-reader friendly.

## 11.4 Acceptance

- [x] Basic accessibility requirements are implemented.
- [x] App is usable without relying only on color.

---

# Phase 12 — Tests and validation

## 12.1 UI tests / ViewModel tests

Add or update tests for:

- [x] friendly status label mapping;
- [x] state-aware Home actions;
- [x] wizard step validation;
- [x] wizard forwards add/edit/delete;
- [x] remote peer validation;
- [x] settings section visibility;
- [x] raw path fields hidden behind Advanced;
- [x] Forward Details route/actions;
- [x] no validation on every keystroke;
- [x] no disk I/O in composition path where testable.

## 12.2 Manual UI checklist

Manually verify:

- [x] Home connected state matches mockup concept.
- [x] Home paused cellular state matches mockup concept.
- [x] Setup Wizard stepper appears.
- [x] Identity step is local identity only.
- [x] Remote Peer step contains remote identity.
- [x] Forwards step allows add/edit/delete.
- [x] Forward Details screen exists.
- [x] Logs screen is readable.
- [x] Settings has required sections.
- [x] Import/export primary flow uses SAF/share.
- [x] Advanced/debug fields are collapsed.
- [x] Android 13 notification permission explanation appears.

## 12.3 Regression validation

Run existing validation:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o android/app/src/main/jniLibs \
  build -p p2p-mobile --release

cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

If device/emulator available:

```bash
./gradlew connectedDebugAndroidTest
```

## 12.4 Acceptance

- [x] UI polish does not break runtime/config/security tests.
- [x] Existing Android validation still passes.
- [x] Manual UI checklist passes.
- [x] Any intentionally deferred UI items are documented.

---

# Phase 13 — Final UI acceptance checklist

Do not check until complete.

## 13.1 Visual design

- [x] Light card-based theme implemented.
- [x] Navy app bar implemented.
- [x] Explicit color palette used.
- [x] Roboto/Material typography used consistently.
- [x] Cards/buttons/spacing match spec.
- [x] Status colors are consistent.

## 13.2 Home

- [x] Friendly labels, no raw enum names.
- [x] State-aware actions.
- [x] Error resolution card.
- [x] Network card clear.
- [x] Forwards summary clear.

## 13.3 Setup Wizard

- [x] Secondary flow without bottom nav.
- [x] Progress stepper.
- [x] Mode cards.
- [x] Local identity step only.
- [x] Remote Peer step contains remote identity.
- [x] MQTT step polished.
- [x] Forwards can be edited inside wizard.
- [x] Network Policy step shows real state and controls.
- [x] Review step clear.

## 13.4 Forwards

- [x] Clean list rows.
- [x] Dedicated details screen.
- [x] Copy/Open/Test/Edit/Disable/Delete actions.
- [x] Delete confirmation.

## 13.5 Logs / Settings / Import Export

- [x] Logs readable and redacted.
- [x] Settings sectioned.
- [x] Import/export uses SAF/share as primary UX.
- [x] Raw path fallback hidden behind Advanced.
- [x] Notification permission UX implemented.

## 13.6 Accessibility/performance

- [x] Content descriptions for icons.
- [x] 48dp touch targets.
- [x] Color not sole state indicator.
- [x] No disk/native work in Composable body.
- [x] No expensive validation on every keystroke.

## 13.7 Regression

- [x] Existing runtime/security/build validation still passes.
- [x] No protocol behavior changed.
- [x] E2E compatibility status remains honest.
