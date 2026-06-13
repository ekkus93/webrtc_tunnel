# ANDROID_WEBRTC_TUNNEL_TODO.md

# Android WebRTC Tunnel Implementation TODO

## Goal

Implement an Android version of `webrtc_tunnel` in the same repository as the existing Rust desktop tunnel.

The Android app must:

```text
run as a native Android app
use Kotlin + Jetpack Compose + Material 3
run the tunnel in a ForegroundService
call shared Rust code through JNI
remain protocol-compatible with the desktop Rust version
support offer/client mode first
block cellular/metered data by default
store the private identity encrypted at rest using Android Keystore
include the UI/UX described in ANDROID_WEBRTC_TUNNEL_SPEC.md
```

This TODO is intended for GitHub Copilot implementation.

---

## Non-negotiable rules

- Do not commit Android implementation directly to `master`.
- Create a new Android feature branch first.
- Keep Android code in the same repo.
- Do not change MQTT signaling wire format.
- Do not change tunnel frame format.
- Do not change desktop Rust protocol semantics.
- Do not add TURN.
- Do not add VPN/TUN mode in v1.
- Do not add arbitrary remote host/port selection from Android offer side.
- Do not run the tunnel as a hidden background service.
- Do not allow cellular/metered data unless the user explicitly opts in.
- Do not store private identity plaintext at rest.
- Do not log private keys, MQTT passwords, SDP, ICE candidates, decrypted payloads, or forwarded data.
- Bind local forwards to `127.0.0.1` by default.
- Do not expose local forwards to LAN without explicit advanced warning.

---

# Phase 0 — Branch and baseline

## 0.1 Create feature branch

Create a new branch before making changes:

```bash
git switch -c android-app
```

If that branch already exists, use:

```bash
git switch android-app
```

Do not implement this work on `master`.

## 0.2 Baseline validation

Run existing Rust validation before Android changes:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

If any command fails, document the failure before proceeding.

## 0.3 Inspect current repo layout

Confirm existing structure:

```text
Cargo.toml
crates/
bins/
docs/
```

Identify current crates:

```text
p2p-core
p2p-crypto
p2p-signaling
p2p-tunnel
p2p-webrtc
p2p-daemon
```

## 0.4 Read required docs

Read:

```text
ANDROID_WEBRTC_TUNNEL_SPEC.md
ANDROID_UI_SCREEN_SPEC.md, if present
README.md
docs/RUST_WEBRTC_SPECS.md
```

---

# Phase 1 — Android project skeleton

## 1.1 Create Android root directory

Create:

```text
android/
```

## 1.2 Add Gradle Kotlin DSL files

Add:

```text
android/settings.gradle.kts
android/build.gradle.kts
android/gradle/libs.versions.toml
android/app/build.gradle.kts
```

## 1.3 Configure Android app module

Use:

```text
Kotlin
Jetpack Compose
Material 3
Gradle Kotlin DSL
Version catalog
```

Recommended defaults:

```kotlin
namespace = "com.phillipchin.webrtctunnel"
applicationId = "com.phillipchin.webrtctunnel"
minSdk = 26
targetSdk = 35
compileSdk = 35
```

If compile SDK 35 is not installed locally, use the installed stable SDK and document the difference.

## 1.4 Add package structure

Create:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/
  MainActivity.kt
  WebRtcTunnelApplication.kt
  TunnelForegroundService.kt
  RustTunnelBridge.kt
  ui/
  ui/home/
  ui/setup/
  ui/forwards/
  ui/logs/
  ui/settings/
  data/
  model/
  service/
  security/
  network/
  notification/
```

## 1.5 Add AndroidManifest.xml

Add required permissions:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Register:

```text
MainActivity
TunnelForegroundService
```

For Android 14+ foreground-service requirements, choose and document the appropriate foreground service type.

## 1.6 Add basic Compose app

Implement a minimal Compose app that launches and shows:

```text
WebRTC Tunnel
Android project skeleton ready
```

## 1.7 Validate Android skeleton

Run:

```bash
cd android
./gradlew assembleDebug
```

If Gradle wrapper does not exist, add it or document how to run with system Gradle.

---

# Phase 2 — Rust mobile JNI crate

## 2.1 Add p2p-mobile crate

Create:

```text
crates/p2p-mobile/
```

Add `Cargo.toml` with:

```toml
[lib]
crate-type = ["cdylib", "rlib"]
```

## 2.2 Add crate dependencies

Depend on existing crates as needed:

```text
p2p-core
p2p-crypto
p2p-signaling
p2p-tunnel
p2p-webrtc
p2p-daemon
```

Start minimal if full daemon integration is difficult.

## 2.3 Add FFI-safe runtime handle

Implement a runtime handle that can be created/destroyed from JNI.

Requirements:

- no panics cross FFI
- handle stop idempotently
- safe status before start/running/stopped/error
- thread-safe or internally synchronized

## 2.4 Add exported functions

Expose JNI/FFI functions for:

```text
create runtime
destroy runtime
start offer
start answer, optional/advanced
stop
status JSON
recent logs JSON
validate config
free returned string
```

Exact function names may follow JNI naming or a C ABI bridge.

## 2.5 Return structured errors

Return either:

```text
integer error code + status JSON
```

or:

```text
JSON result object
```

Do not return raw Rust error debug strings containing secrets.

## 2.6 Add Rust tests for p2p-mobile

Test:

- runtime create/destroy
- stop before start
- double stop
- status before start
- invalid config validation
- returned JSON parses

## 2.7 Validate p2p-mobile natively

Run:

```bash
cargo test -p p2p-mobile
cargo clippy -p p2p-mobile --all-targets --all-features -- -D warnings
```

---

# Phase 3 — Build Rust library for Android

## 3.1 Add cargo-ndk workflow

Document and/or script:

```bash
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o android/app/src/main/jniLibs \
  build -p p2p-mobile --release
```

## 3.2 Add Gradle task if feasible

Add a Gradle task such as:

```text
buildRustAndroid
```

It should build/copy `.so` files into:

```text
android/app/src/main/jniLibs/arm64-v8a/
android/app/src/main/jniLibs/x86_64/
```

## 3.3 Start with required ABIs

Required initially:

```text
arm64-v8a
x86_64
```

Optional later:

```text
armeabi-v7a
```

## 3.4 Validate Android Rust build

Run cargo-ndk build.

If `p2p-webrtc` fails to build for Android, stop and document:

- exact error,
- dependency causing failure,
- fallback recommendation.

Do not silently rewrite protocol.

---

# Phase 4 — Kotlin Rust bridge

## 4.1 Implement RustTunnelBridge

Create:

```text
RustTunnelBridge.kt
```

Responsibilities:

- load native library
- call native functions
- convert native results to Kotlin result types
- free returned native strings
- prevent concurrent unsafe calls if needed

## 4.2 Define Kotlin models

Create:

```text
TunnelMode
TunnelStatus
TunnelError
ForwardConfig
ForwardStatus
NetworkStatus
LogEvent
ValidationResult
```

## 4.3 Add JSON parsing

Parse Rust status/log JSON into Kotlin data classes.

Use Kotlin serialization or another project-standard JSON library.

## 4.4 Add fake bridge for tests/previews

Create an interface:

```kotlin
interface TunnelNativeBridge
```

Implement:

```text
RustTunnelBridge
FakeTunnelBridge
```

Use the fake bridge in Compose previews and unit tests.

## 4.5 Unit tests

Test:

- native library load failure handled
- status JSON parsing
- log JSON parsing
- error mapping
- double stop safe at Kotlin layer

---

# Phase 5 — Config and identity storage

## 5.1 Define app-private file layout

Use app-private internal storage:

```text
filesDir/
  config.toml
  identity.enc
  identity.pub
  authorized_keys
```

## 5.2 Implement ConfigRepository

Responsibilities:

- create default config
- read/write config.toml
- validate config through Rust bridge
- generate config from setup wizard fields
- export/import config

## 5.3 Implement AndroidAppPreferences with DataStore

Defaults:

```kotlin
allowMetered = false
pauseOnMetered = true
resumeOnUnmetered = true
showMeteredWarning = true
startTunnelWhenAppOpens = false
debugLogsEnabled = false
```

## 5.4 Implement IdentityRepository

Responsibilities:

- generate identity through Rust if possible
- import compatible desktop identity
- encrypt private identity to `identity.enc`
- store public identity as `identity.pub`
- expose public identity for copy/share
- export private identity only through warning flow

## 5.5 Implement Android Keystore encryption

Requirements:

- non-exportable Android Keystore key
- AES-GCM or equivalent authenticated encryption
- random nonce/IV per encryption
- store nonce with ciphertext
- no plaintext identity file at rest
- avoid plaintext temp files
- if temp file unavoidable, delete immediately

## 5.6 Private export warning

Before private identity export, show warning:

```text
Private Identity Export Warning

Anyone with this file can impersonate this phone in your tunnel network.

Only export it if you understand the risk.

[Cancel]
[Export Private Identity]
```

Optional:

```text
require device unlock / biometric before export
```

## 5.7 Tests

Test:

- identity encrypted file exists
- plaintext identity file does not exist
- decrypt after encrypt succeeds
- public identity can be copied/exported
- private export requires explicit confirmation in UI flow
- logs/diagnostics do not include private identity

---

# Phase 6 — Network policy

## 6.1 Implement NetworkPolicyManager

Responsibilities:

- observe active network
- classify network type
- determine metered/unmetered
- expose `Flow<NetworkStatus>`
- apply user preferences

Classifications:

```text
Unmetered Wi-Fi
Metered Wi-Fi
Cellular
No network
Unknown network
```

Unknown should fail safe.

## 6.2 Enforce default block

Default:

```text
allowMetered = false
```

When cellular/metered/unknown network is active:

```text
tunnel start blocked or tunnel paused
```

unless user has explicitly allowed metered use.

## 6.3 Add network change handling

If tunnel is running and network becomes disallowed:

```text
pause/stop Rust runtime
close or stop accepting local listeners
update status
update notification
```

If unmetered network returns:

```text
resume if resumeOnUnmetered = true
otherwise show Resume action
```

## 6.4 Add cellular warning dialog flow

Implement strong warning dialog before enabling metered use.

Options:

```text
Cancel
I understand — allow cellular/metered tunnels
```

Optional:

```text
Allow for 15 minutes
Allow until tunnel stops
Always allow
```

## 6.5 Tests

Test:

- unmetered network allowed
- cellular blocked by default
- metered Wi-Fi blocked by default
- unknown network blocked by default
- explicit allow enables metered
- network switch to cellular pauses running tunnel
- network switch back to Wi-Fi resumes if configured

---

# Phase 7 — ForegroundService and notifications

## 7.1 Implement TunnelForegroundService

Responsibilities:

- start foreground promptly
- own Rust runtime handle
- start offer mode
- stop tunnel
- observe network policy
- expose service state
- update notification
- clean up on destroy

## 7.2 Service commands

Support intents/actions:

```text
START_OFFER
STOP
PAUSE
RESUME
ALLOW_METERED_TEMPORARILY
OPEN_APP
```

## 7.3 Notification channels

Create notification channels:

```text
Tunnel Status
Tunnel Errors
```

## 7.4 Running notification

Example:

```text
WebRTC Tunnel running
Connected · 2 forwards active

[Stop]
[Open]
```

## 7.5 Paused notification

Example:

```text
WebRTC Tunnel paused
Cellular/metered network blocked

[Settings]
[Allow temporarily]
[Stop]
```

## 7.6 Error notification

Example:

```text
WebRTC Tunnel error
Local port 8080 is already in use

[Open]
[Stop]
```

## 7.7 Tests

Instrumented or Robolectric tests where possible:

- starting service posts notification
- stop action stops service
- metered policy changes notification to paused
- service stop releases runtime

---

# Phase 8 — App state/repositories/ViewModels

## 8.1 Implement TunnelRepository

Responsibilities:

- start/stop service
- observe status
- combine Rust status + service state + network state
- provide `Flow<TunnelStatus>`
- expose recent logs

## 8.2 Implement ViewModels

Create ViewModels for:

```text
HomeViewModel
SetupViewModel
ForwardsViewModel
LogsViewModel
SettingsViewModel
NetworkPolicyViewModel
```

## 8.3 Implement UI event handling

Use state-down/events-up.

Examples:

```text
StartTunnelClicked
StopTunnelClicked
CopyUrlClicked
OpenBrowserClicked
AllowMeteredClicked
SaveForwardClicked
ImportIdentityClicked
```

## 8.4 Tests

Unit test ViewModels with fake repositories.

---

# Phase 9 — Compose UI

## 9.1 Implement theme

Use Material 3.

Recommended style:

```text
dark navy app bar
light cards
green connected states
orange/yellow warning states
red error/destructive states
blue/navy primary actions
rounded cards
clear status icons
```

## 9.2 Implement main navigation

Tabs:

```text
Home
Forwards
Logs
Settings
```

Use Navigation Compose.

## 9.3 Implement Home / Status screen

Must show:

- service state
- mode
- remote peer
- network status
- active session count
- forwards summary
- start/stop button
- logs/settings actions
- copy URL/open browser convenience actions where applicable

States:

```text
Stopped
Starting
Listening
Connecting
Connected
Reconnecting
PausedMeteredBlocked
NoNetwork
Error
Stopping
ConfigInvalid
```

## 9.4 Implement Setup Wizard

Steps:

```text
Choose Mode
Identity
MQTT Broker
Remote Peer
Forwards
Network Policy
Review
```

Each step must validate before allowing Next.

## 9.5 Implement Choose Mode step

Default:

```text
Offer / Client
```

Answer mode:

```text
Advanced
```

May be disabled if not implemented.

## 9.6 Implement Identity step

Support:

```text
Generate new identity
Import existing identity
Copy public key
Share public key
```

Private identity never shown in normal UI.

## 9.7 Implement MQTT Broker step

Fields:

```text
Broker host
Port
TLS enabled
Username optional
Password optional
Topic prefix optional
```

Add `Test Connection` if feasible.

## 9.8 Implement Remote Peer step

Fields:

```text
Remote peer ID
Remote public identity
```

Actions:

```text
Paste from Clipboard
Import File
Scan QR later if feasible
```

## 9.9 Implement Forwards step

Fields:

```text
Name
Local host default 127.0.0.1
Local port
Remote forward_id
Enabled
```

Validate duplicate ports.

Do not allow remote target host/port selection.

## 9.10 Implement Network Policy step

Show current network and defaults.

Implement metered warning dialog.

## 9.11 Implement Review step

Show:

```text
Mode
Local identity
Remote peer
Broker
Network policy
Forwards
```

Actions:

```text
Start Tunnel
Save and Start Later
Back
```

## 9.12 Implement Forwards List screen

List configured forwards with status:

```text
Listening
Stopped
Error
Disabled
Paused
```

Actions:

```text
Add
Edit
Disable
Delete
```

## 9.13 Implement Forward Details screen

Show:

```text
Local address
Remote forward_id
Local URL
Last error
```

Actions:

```text
Copy URL
Open Browser
Test Local Port
Edit
Disable
Delete
```

## 9.14 Implement Logs screen

Default user-safe logs.

Filters:

```text
All
Info
Warn
Error
Debug
```

Actions:

```text
Copy Logs
Export Diagnostics
Clear Logs
Pause Logs
```

Redact secrets.

## 9.15 Implement Settings screen

Sections:

```text
Tunnel
Network Policy
Identity
Configuration
Diagnostics
Advanced
About
```

Include:

```text
Run setup wizard again
Import/export config
Copy public identity
Export private identity with warning
Debug logs toggle
```

## 9.16 Accessibility

Ensure:

- 48dp touch targets
- content descriptions for icons
- labels not color-only
- text supports font scaling
- warning dialogs screen-reader friendly

---

# Phase 10 — Import/export and diagnostics

## 10.1 Config import/export

Support:

```text
Import config.toml
Export config.toml
```

Keep Android preferences separate from protocol config.

## 10.2 Public identity import/export

Support:

```text
Copy public identity
Share public identity
Import remote public identity
Paste remote public identity
```

## 10.3 Private identity import/export

Import:

```text
select file
validate
encrypt to identity.enc
delete plaintext temp
```

Export:

```text
show warning
optional unlock/biometric
write selected destination
```

## 10.4 Diagnostics export

Export redacted diagnostics:

```text
status JSON
redacted config
recent logs
network state
app version
Rust library version
```

Never include private key or secrets.

---

# Phase 11 — Protocol compatibility testing

## 11.1 Desktop answer + Android offer

Test manually or automate:

1. Start desktop `p2p-answer`.
2. Start Android app in offer mode.
3. Configure forward:
   ```text
   127.0.0.1:8080 -> llama
   ```
4. Start tunnel.
5. Open Android browser:
   ```text
   http://127.0.0.1:8080
   ```
6. Confirm remote service responds.

## 11.2 Rust compatibility invariants

Verify Android uses same:

- signaling envelope
- encrypted inner message schema
- MQTT topic layout
- identity/public key format
- authorized key semantics
- tunnel frame format
- `OpenPayload { forward_id }`
- per-forward authorization
- STUN/no-TURN behavior unless desktop also changes

## 11.3 Regression tests

Do not accept a patch that breaks existing desktop Rust tests.

---

# Phase 12 — CI/build validation

## 12.1 Rust validation

Run:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

## 12.2 Android validation

Run:

```bash
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

If instrumented tests exist:

```bash
./gradlew connectedDebugAndroidTest
```

## 12.3 Rust Android library build

Run:

```bash
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o android/app/src/main/jniLibs \
  build -p p2p-mobile --release
```

or the equivalent Gradle task.

## 12.4 GitHub Actions

Add or update CI for:

```text
Rust fmt/clippy/test
Android assembleDebug
Android unit tests
p2p-mobile cargo-ndk build
```

If Android CI is too heavy for the first pass, document it as TODO and keep local validation commands.

---

# Phase 13 — Documentation

## 13.1 Add Android docs

Add:

```text
docs/ANDROID_WEBRTC_TUNNEL_SPEC.md
docs/ANDROID_BUILD.md
docs/ANDROID_USER_GUIDE.md
```

If this spec is created at repo root first, move/copy it into `docs/`.

## 13.2 Update README

Add Android section:

```text
Android app
  status: experimental
  offer mode first
  foreground service
  cellular/metered blocked by default
  private identity encrypted at rest
```

## 13.3 Document build steps

Include:

```text
Android Studio setup
NDK setup
cargo-ndk setup
Gradle commands
Rust library build commands
APK install command
```

## 13.4 Document security model

Include:

```text
private identity encrypted at rest
Android Keystore use
rooted/compromised device out of scope
logs redacted
cellular warning
```

---

# Phase 14 — Acceptance checklist

Mark the Android implementation complete only when all are true.

## Branch/repo

- [x] Work was done on new branch `android-app` or equivalent.
- [x] Android code is in same repo.
- [x] Desktop Rust code still builds/tests.

## Android project

- [x] Android project exists under `android/`.
- [x] Gradle Kotlin DSL is used.
- [x] Kotlin + Compose + Material 3 are configured.
- [x] `assembleDebug` succeeds.

## Rust mobile

- [x] `crates/p2p-mobile` exists.
- [x] `p2p-mobile` exposes JNI/FFI functions.
- [x] Rust library builds for `arm64-v8a`.
- [x] Android app loads native library.
- [x] No panics cross FFI.

## Service

- [x] Tunnel runs inside ForegroundService.
- [x] Persistent notification appears while running.
- [x] Stop action works from notification.
- [x] Activity can be backgrounded while tunnel continues.
- [x] Browser can use localhost forwarded port while service runs.

## Network policy

- [x] Cellular/metered blocked by default.
- [x] User must explicitly opt in.
- [x] Strong warning dialog exists.
- [x] Network changes pause/resume as configured.
- [x] Unknown network fails safe.

## Identity/security

- [x] Private identity stored as `identity.enc`.
- [x] Encryption key protected by Android Keystore.
- [x] No plaintext private identity at rest.
- [x] Public identity can be copied/shared.
- [x] Private export requires warning.
- [x] Logs/diagnostics redact secrets.

## UI

- [x] Home screen implemented.
- [x] Setup wizard implemented.
- [x] Forwards list implemented.
- [x] Forward details implemented.
- [x] Network policy UI implemented.
- [x] Logs screen implemented.
- [x] Settings screen implemented.
- [x] Notifications implemented.
- [x] Error states implemented.
- [x] Accessibility basics handled.

## Compatibility

- [x] Android offer can connect to desktop Rust answer.
- [x] Android browser can reach remote service through `127.0.0.1:<port>`.
- [x] Protocol wire formats unchanged.
- [x] Config semantics compatible.
- [x] Android-only preferences stored separately.

## Validation

- [x] `cargo fmt --check` passes.
- [x] `cargo clippy --workspace --all-targets --all-features -- -D warnings` passes.
- [x] `cargo test --workspace --all-targets` passes.
- [x] `cargo ndk ... build -p p2p-mobile --release` passes.
- [x] `./gradlew assembleDebug` passes.
- [x] Android unit tests pass.

---

# Suggested implementation order

1. Create branch.
2. Add Android skeleton.
3. Add `p2p-mobile` crate.
4. Build Rust `.so` for Android.
5. Add Kotlin Rust bridge.
6. Add ForegroundService and notification.
7. Add config/identity storage with Keystore encryption.
8. Add network policy enforcement.
9. Add Home screen.
10. Add setup wizard.
11. Add forwards/logs/settings screens.
12. Add import/export.
13. Test desktop answer + Android offer.
14. Add docs and CI.
15. Run full validation.

Do not start with UI polish. Get the Rust library, service lifecycle, network policy, and basic offer-mode tunnel working first.
