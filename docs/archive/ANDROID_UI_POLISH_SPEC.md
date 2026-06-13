# Android WebRTC Tunnel UI Polish Spec

## 1. Purpose

This spec defines the UI/UX polish pass for the Android `webrtc_tunnel` app.

The app already has a mostly functional control-panel implementation. This pass should make it match the intended Material-style Android product more closely:

```text
dark navy app bars
light card-based content
clear green/orange/red tunnel states
friendly user-facing labels
first-run setup wizard that works without leaving the wizard
dedicated forward details flow
structured Settings screen
Android-safe import/export as primary UX
developer/debug controls hidden behind Advanced
accessible, readable, touch-friendly UI
```

This is a UI/UX polish and usability pass. Do not change WebRTC, MQTT, tunnel frame format, identity format, or desktop protocol semantics.

## 2. Product UX goal

The Android app is a user-facing control panel for a long-running foreground tunnel service.

The UI must answer these questions quickly:

1. Is the tunnel running?
2. Is it connected, paused, stopped, or in error?
3. What network is being used?
4. Is cellular/metered data blocked or allowed?
5. What local URLs can other Android apps use?
6. What should the user do next?

The target user should be able to complete first-run setup without editing TOML, without typing raw file paths, and without leaving the setup wizard.

## 3. Non-goals

Do not implement or redesign:

- TURN;
- VPN/TUN mode;
- arbitrary remote host/port selection from Android offer side;
- desktop protocol semantics;
- tunnel frame format;
- MQTT signaling wire format;
- new identity format;
- cloud account system;
- hidden background service without persistent notification;
- cellular/metered use without explicit opt-in.

## 4. Visual design system

### 4.1 Overall style

Use a light Material 3 card-based UI with a dark navy app bar.

Avoid a generic all-dark app. The mockup style is:

```text
dark navy top bars
light gray app background
white cards
green connected state
orange paused/warning state
red error/destructive state
navy primary buttons
blue links and secondary actions
```

### 4.2 Color palette

Use these exact color tokens unless there is a strong Android/Material reason to slightly adjust contrast.

```kotlin
object TunnelColors {
    val Navy900 = Color(0xFF061A3D)      // app bar / top bar
    val Navy800 = Color(0xFF071B3A)      // secondary app bar surface
    val Navy700 = Color(0xFF08245C)      // primary buttons
    val Blue600 = Color(0xFF1D4ED8)      // links, active step, focus
    val Blue100 = Color(0xFFDBEAFE)      // selected card background tint

    val Success700 = Color(0xFF2E7D32)   // connected / allowed
    val Success100 = Color(0xFFE8F5E9)   // success background tint

    val Warning600 = Color(0xFFF59E0B)   // paused / warning
    val Warning100 = Color(0xFFFFF7E6)   // warning background tint

    val Error700 = Color(0xFFD32F2F)     // error / destructive
    val Error100 = Color(0xFFFFEBEE)     // error background tint

    val Background = Color(0xFFF6F8FB)   // app screen background
    val Surface = Color(0xFFFFFFFF)      // cards
    val Border = Color(0xFFE5E7EB)       // dividers/card borders

    val TextPrimary = Color(0xFF111827)
    val TextSecondary = Color(0xFF6B7280)
    val TextMuted = Color(0xFF9CA3AF)
    val TextOnNavy = Color(0xFFFFFFFF)
}
```

### 4.3 Material theme requirements

Use a custom `lightColorScheme`, not a global dark theme.

Required mapping:

```kotlin
private val TunnelLightColorScheme = lightColorScheme(
    primary = TunnelColors.Navy700,
    onPrimary = TunnelColors.TextOnNavy,
    primaryContainer = TunnelColors.Blue100,
    onPrimaryContainer = TunnelColors.Navy900,
    secondary = TunnelColors.Blue600,
    background = TunnelColors.Background,
    onBackground = TunnelColors.TextPrimary,
    surface = TunnelColors.Surface,
    onSurface = TunnelColors.TextPrimary,
    error = TunnelColors.Error700,
    onError = Color.White,
)
```

Use dark navy explicitly for top bars even under the light theme.

### 4.4 Typography

Use Android/Material default **Roboto**. Do not add custom font files.

Recommended typography:

```text
App bar title:        18sp, Medium/SemiBold
Screen title:         22sp, SemiBold
Card title:           18sp, SemiBold
Card subtitle/body:   14sp–16sp, Regular
Field label:          14sp, Medium
Helper text:          12sp–13sp, Regular
Status title:         20sp, SemiBold
Status description:   14sp, Regular
Button text:          14sp, Medium
Metadata text:        12sp–13sp, Regular
```

Use Material typography names consistently:

```kotlin
titleLarge    // screen titles
titleMedium   // card titles
bodyLarge     // important body text
bodyMedium    // normal body text
bodySmall     // helper/meta text
labelLarge    // buttons
labelMedium   // chips/status labels
```

### 4.5 Shape and spacing

Use consistent dimensions:

```kotlin
object TunnelDimens {
    val ScreenPadding = 16.dp
    val CardPadding = 16.dp
    val CardSpacing = 12.dp
    val SectionSpacing = 20.dp
    val InlineSpacing = 8.dp
    val CardCornerRadius = 16.dp
    val ButtonCornerRadius = 8.dp
    val ChipCornerRadius = 999.dp
    val MinTouchTarget = 48.dp
}
```

Cards:

```text
background: white
corner radius: 16dp
border: 1dp #E5E7EB or subtle shadow
padding: 16dp
spacing between cards: 12dp
```

Buttons:

```text
primary: navy background, white text
secondary: outlined navy/blue
destructive: red outline or red filled for final destructive confirmation
minimum height: 48dp
```

### 4.6 Icons

Use Material icons consistently:

```text
Connected: CheckCircle
Paused/blocked: PauseCircle or Warning
Stopped: RadioButtonUnchecked or StopCircle
Starting/Connecting: Sync / CircularProgressIndicator
Error: Error
Network Wi-Fi: Wifi
Cellular: SignalCellularAlt
No network: SignalWifiOff
Forward/listener: Lan or DeviceHub
Logs: Article/ListAlt
Settings: Settings
Copy: ContentCopy
Share: Share
Open browser: OpenInBrowser
Delete: Delete
```

Every icon must have a content description unless it is purely decorative and accompanied by equivalent visible text.

### 4.7 Status colors

Use both color and text. Color must never be the only state indicator.

```text
Connected / Listening / Allowed:
  icon = green
  title = Connected / Listening / Tunnel allowed

Paused / Metered blocked / Reconnecting:
  icon = orange
  title = Paused / Reconnecting / Blocked by policy

Stopped:
  icon = gray/navy
  title = Stopped

Error / Config invalid:
  icon = red
  title = Error / Config invalid
```

## 5. Navigation model

### 5.1 Main tabs

Main tabs:

```text
Home
Forwards
Logs
Settings
```

Use bottom navigation only on main tabs.

### 5.2 Secondary flows

Secondary flows should not show bottom navigation:

```text
Setup Wizard
Forward Details
Network Policy Details
Import / Export
Identity Management
Diagnostics Export
Advanced Settings
```

Secondary flows should use a top app bar with a back arrow.

### 5.3 Navigation behavior

Bottom nav item taps must not stack duplicate destinations.

Use:

```kotlin
navController.navigate(route) {
    popUpTo(navController.graph.findStartDestination().id) {
        saveState = true
    }
    launchSingleTop = true
    restoreState = true
}
```

## 6. Common reusable components

### 6.1 TunnelTopAppBar

A reusable top bar:

```text
background: Navy900
title: white
navigation icon: white
overflow/actions: white
height: Material default
```

### 6.2 StatusCard

Inputs:

```kotlin
title: String
description: String
state: StatusVisualState
metadataRows: List<Pair<String, String>>
primaryAction: Action?
secondaryAction: Action?
```

Visual:

```text
large status icon at left
title + description
metadata key/value rows
optional action row
```

### 6.3 NetworkStatusCard

Shows:

```text
network icon
network type
metered/unmetered
tunnel allowed/blocked
blocked reason if blocked
```

### 6.4 ForwardSummaryRow

Shows:

```text
colored status dot/icon
forward name
local address -> remote forward_id
status label
chevron
```

### 6.5 EmptyStateCard

Use when no forwards/logs/config:

```text
icon
title
description
primary action
```

### 6.6 ErrorResolutionCard

For actionable errors:

```text
friendly summary
suggested fix
technical details collapsed behind "Show details"
actions such as Retry, Edit Forward, View Logs, Open Setup
```

### 6.7 WizardStepper

Horizontal numbered stepper:

```text
1 2 3 4 5 6 7
active step = blue filled circle
completed step = blue check or filled
future step = gray outline
labels optional on small screens
```

Must match the mockup concept.

## 7. Home / Status screen

### 7.1 Purpose

The Home screen is the dashboard. It must clearly show:

- current service/tunnel state;
- current network policy result;
- configured forwards and their current state;
- the right next action.

### 7.2 Layout

```text
Top app bar: Rust WebRTC Tunnel
Content:
  Status card
  Network card
  Forwards summary card
  State-specific action row
Bottom nav
```

### 7.3 Friendly state labels

Do not show enum names.

Map internal states to user-facing labels:

```text
ServiceState.Stopped -> Stopped
ServiceState.Starting -> Starting
ServiceState.Connected -> Connected
ServiceState.Listening -> Listening
ServiceState.PausedMeteredBlocked -> Paused
ServiceState.NoNetwork -> No network
ServiceState.Error -> Error
ServiceState.ConfigInvalid -> Configuration needs attention
ServiceState.Stopping -> Stopping
```

Descriptions:

```text
Connected: Tunnel is active and ready to use.
Listening: Local ports are open and waiting for tunnel traffic.
Paused: Cellular/metered network blocked.
Stopped: Tunnel service is not running.
No network: Connect to Wi-Fi to start the tunnel.
Error: Action required before the tunnel can start.
Config invalid: Open setup to fix configuration.
```

### 7.4 State-aware actions

Do not show Start and Stop at the same time unless there is a deliberate reason.

Required action mapping:

```text
Stopped:
  primary = Start Tunnel
  secondary = Setup

Starting:
  primary = Stop
  secondary = View Logs
  show spinner

Connected / Listening:
  primary = Stop Tunnel
  secondary = View Logs
  optional = Open first local URL

PausedMeteredBlocked:
  primary = Settings
  secondary = Stop
  optional = Allow Temporarily, if implemented

NoNetwork:
  primary = Retry
  secondary = Settings

Error:
  primary = Retry
  secondary = View Logs
  contextual action = Edit Forward / Open Setup if known

ConfigInvalid:
  primary = Open Setup
  secondary = View Logs
```

### 7.5 Forwards summary

If forwards exist:

```text
Forwards (2)
+ icon to add forward

llama
127.0.0.1:8080 -> llama
Status: Listening

ssh
127.0.0.1:2223 -> ssh
Status: Listening
```

If no forwards:

```text
No forwards configured
Add a local port so browser or other apps can use the tunnel.
[Add Forward]
```

### 7.6 Error handling

Errors should be user friendly.

Bad:

```text
PausedMeteredBlocked
ConfigInvalid
java.io.FileNotFoundException...
```

Good:

```text
Paused
Cellular/metered network blocked.

Config needs attention
The MQTT broker host is missing.
[Open Setup]
```

Technical details should be behind “Show details.”

## 8. Setup Wizard

### 8.1 Overall structure

The setup wizard must be a secondary flow, not a bottom-nav tab.

Top bar:

```text
Back arrow
Setup Wizard
overflow menu optional
```

No bottom navigation.

Each step:

```text
WizardStepper
Step title
Step subtitle
Main content card(s)
Bottom action row: Back / Next
Review step: Back / Save / Start Tunnel
Cancel action in app bar overflow or bottom row
```

### 8.2 Step validation

Next button should be disabled until the current step is valid when practical.

If validation requires an action, show inline error text.

Do not wait until Review to reveal obvious missing fields.

### 8.3 Step 1 — Choose Mode

Use two large selectable cards:

```text
Use this phone as a client (Offer side)
This phone will connect to a remote answer peer and request forwarded services.

Use this phone as a server (Answer side) — Advanced
This phone will wait for incoming connections and provide access to local services.
```

For Android v1:

```text
Offer side selected by default.
Answer side disabled or shown as Advanced / Not available yet.
```

Do not show only a plain text statement unless answer mode is fully removed from the UI.

### 8.4 Step 2 — Identity

Local identity only.

Content:

```text
Generate new identity
Import existing identity

Your identity
Peer ID: android-phone / derived peer ID

Public identity
<public identity text>

Actions:
Copy Public Key
Share Public Key
```

Important:

- do not put remote public identity on this step;
- do not validate identity file on every keystroke;
- use file picker for import when possible;
- if debug raw path remains, hide it under Advanced.

### 8.5 Step 3 — MQTT Broker

Fields:

```text
Broker host
Port
TLS enabled
Username optional
Password optional or password file path clearly labeled
Topic prefix optional
```

Actions:

```text
Test Connection
```

UX details:

- TLS enabled by default;
- password should be visually hidden if it is an actual password;
- if it is a password file path, label it “Password file path, optional”;
- test connection must not log or show the password.

### 8.6 Step 4 — Remote Peer

Remote peer only.

Fields/actions:

```text
Remote peer ID
Remote public identity
Paste from Clipboard
Import File
```

Helper text:

```text
Paste the answer side public identity here. The answer side must also authorize this phone's public identity.
```

Validation:

- remote peer ID required;
- remote public identity parseable;
- remote public identity peer ID matches remote peer ID;
- local identity cannot be used as the remote peer.

### 8.7 Step 5 — Forwards

The user must be able to add/edit/delete forwards directly inside the wizard.

Do not force the user to leave setup and use the Forwards tab.

Forward editor fields:

```text
Name
Local host
Local port
Remote forward_id
Enabled
```

Defaults:

```text
Local host = 127.0.0.1
Enabled = true
```

Validation:

- name required;
- local port 1-65535;
- duplicate enabled local port rejected;
- remote forward_id required;
- duplicate enabled remote forward_id rejected;
- no remote host/port fields;
- non-localhost bind requires advanced warning.

### 8.8 Step 6 — Network Policy

Show current network and controls.

Content:

```text
Current network
Wi-Fi (Unmetered)
Tunnel allowed: Yes

Allow cellular / metered data
Resume tunnel when Wi-Fi returns
```

Required behavior:

- cellular/metered disabled by default;
- unknown network blocked;
- metered enable shows strong warning;
- UI should match service policy.

### 8.9 Step 7 — Review

Show summary:

```text
Mode: Offer
Local identity: <peer id>
Remote peer: <peer id>
Broker: host:port TLS
Network policy: Cellular/metered blocked
Forwards:
  127.0.0.1:8080 -> llama
  127.0.0.1:2223 -> ssh
```

Actions:

```text
Back
Save
Start Tunnel
```

Start Tunnel must:

- save config;
- validate config;
- check identity;
- check network policy;
- start ForegroundService if allowed;
- show actionable error if blocked.

## 9. Forwards List screen

### 9.1 Layout

```text
Top app bar: Forwards, Add icon
Content:
  forward rows/cards
  empty state if none
Bottom nav
```

Each row:

```text
status dot/icon
name
local address -> remote forward_id
Status: Listening / Stopped / Error / Disabled / Paused
chevron
```

### 9.2 Row behavior

Tap row -> Forward Details screen.

Quick actions may be in overflow, but primary pattern should be list -> details.

### 9.3 Add/Edit

Use a dialog or secondary screen with:

```text
Name
Local host
Local port
Remote forward_id
Enabled
```

Hide advanced local host options unless advanced mode is enabled.

## 10. Forward Details screen

### 10.1 Required dedicated screen

Implement a dedicated Forward Details route/screen.

Top bar:

```text
Back arrow
Forward Details
```

No bottom nav.

Content:

```text
Forward: llama
Status: Listening

Local address: 127.0.0.1:8080
Remote forward_id: llama
Local URL: http://127.0.0.1:8080

Bytes sent: optional if available
Bytes received: optional if available
Open connections: optional if available
Last error: None
```

Actions:

```text
Copy URL
Open Browser
Test Local Port
Edit
Disable / Enable
Delete
```

Destructive actions should require confirmation.

## 11. Logs screen

### 11.1 Layout

```text
Top app bar: Logs, search/filter optional
Filter chips: All / Info / Warn / Error / Debug
Log list
Action row: Copy Logs / Clear Logs / Export Diagnostics / Pause Logs
Bottom nav
```

### 11.2 Log presentation

Use readable rows:

```text
12:01:15  Tunnel service started
12:01:16  Network OK: Wi-Fi unmetered
12:01:17  MQTT connected
```

Color by level subtly:

```text
Info: default text
Warn: orange icon/text
Error: red icon/text
Debug: muted gray
```

Do not show raw JSON unless debug mode is enabled.

### 11.3 Redaction

Logs displayed, copied, or exported must be redacted before reaching UI state.

## 12. Settings screen

### 12.1 Required sections

Settings should be structured into sections:

```text
Tunnel
Network Policy
Identity
Configuration
Diagnostics
Advanced
About
```

### 12.2 Tunnel section

```text
Start tunnel automatically when app opens
Resume tunnel when Wi-Fi returns
Run setup wizard again
```

### 12.3 Network Policy section

```text
Allow cellular / metered data
Show warning before allowing cellular / metered data
Open Network Policy details
```

### 12.4 Identity section

```text
View public identity
Copy public identity
Share public identity
Import identity
Export public identity
Export private identity
```

Private export requires warning.

### 12.5 Configuration section

```text
Import configuration
Export configuration
Validate configuration
Reset configuration
```

Raw config export requires warning.

### 12.6 Diagnostics section

```text
Export diagnostics
Share diagnostics
Copy status JSON
Copy redacted config
```

### 12.7 Advanced section

Collapsed by default.

Contains:

```text
Debug logs
Developer/debug raw path import/export
Custom topic prefix
Non-localhost bind controls
Answer mode, if present
```

## 13. Import / Export UX

Primary UX must use Android-safe flows:

```text
Import: ACTION_OPEN_DOCUMENT
Export: ACTION_CREATE_DOCUMENT
Share: Android share sheet
```

Raw absolute file paths are developer/debug fallback only and must be hidden behind Advanced.

Do not show raw path fields as the main import/export UI.

## 14. Notification permission UX

On Android 13+, request notification permission with explanation.

Message:

```text
Rust WebRTC Tunnel needs notifications so Android can keep the tunnel service visible while it is running in the background.
```

Actions:

```text
Allow Notifications
Not Now
```

If denied, show a clear warning that foreground-service status visibility may be limited.

## 15. Accessibility requirements

Required:

- minimum touch target 48dp;
- all actionable icons have content descriptions;
- color is not the only state indicator;
- status cards include text labels;
- form fields have clear labels and helper text;
- warning dialogs are screen-reader friendly;
- text scales with system font size;
- buttons use clear verbs;
- error details can be expanded/collapsed;
- bottom nav icons have content descriptions or clear labels.

## 16. Performance and Compose correctness

### 16.1 No disk/native work in composition

Do not call file I/O or native validation directly from Composable body.

Bad:

```kotlin
val forwards = vm.loadSavedForwards()
```

inside a Composable.

Good:

```kotlin
val forwards by vm.forwards.collectAsStateWithLifecycle()
```

ViewModel loads asynchronously.

### 16.2 No validation on every keystroke for file paths

Do not read files or call native validation on every text-field change.

Use:

```text
text field changes local UI state
Import button performs file read/validation
Next button validates final text values
```

### 16.3 Use lifecycle-aware collection

Use:

```kotlin
collectAsStateWithLifecycle()
```

for ViewModel flows in Composables.

## 17. Acceptance criteria

The UI polish pass is acceptable when:

- app uses the explicit light card-based navy/white palette;
- main top app bar is navy;
- Home has friendly state labels and state-specific actions;
- Setup Wizard has real progress stepper;
- Setup Wizard can add/edit forwards without leaving wizard;
- Remote public identity is on Remote Peer step;
- Forwards row opens a dedicated Forward Details screen;
- Settings is sectioned as specified;
- raw path import/export is hidden behind Advanced;
- notification permission UX exists on Android 13+;
- no disk/native work happens inside Composable bodies;
- identity file validation is not triggered on every keystroke;
- icons/content descriptions/accessibility basics are implemented;
- no protocol/runtime/security behavior regresses.
