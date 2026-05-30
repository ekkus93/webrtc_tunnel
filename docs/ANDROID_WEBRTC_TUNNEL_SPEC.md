# ANDROID_WEBRTC_TUNNEL_SPEC.md

# Android WebRTC Tunnel Implementation Specification

## 1. Purpose

This document specifies the Android implementation of `webrtc_tunnel`, the Android-compatible version of the existing Rust `rust_webrtc_tunnel` / `webrtc_tunnel` project.

The Android app must be fully protocol-compatible with the desktop Rust implementation. It should reuse the existing Rust tunnel logic through JNI wherever practical, while providing a native Android user interface, foreground-service lifecycle, safe network-policy behavior, and Android-secure private identity storage.

This specification includes the UI/UX requirements from `ANDROID_UI_SCREEN_SPEC.md` and supersedes that file as the comprehensive Android implementation target.

## 2. Product summary

The Android app is a control panel for a long-running tunnel service.

```text
Android app Activity
  - setup wizard
  - configuration
  - status dashboard
  - forwards management
  - logs
  - settings

Android ForegroundService
  - owns Rust tunnel runtime
  - owns local TCP listeners
  - owns MQTT/WebRTC signaling/session runtime
  - owns persistent notification
  - keeps running while other Android apps are foregrounded

Other Android apps
  - connect to 127.0.0.1:<local_port>
  - use forwarded services through the tunnel
```

Example user flow:

```text
Remote machine:
  p2p-answer has forward_id "llama" -> 127.0.0.1:8080 llama-server

Android phone:
  WebRTC Tunnel offer mode listens on 127.0.0.1:8080

Android browser:
  opens http://127.0.0.1:8080

Traffic:
  Browser -> localhost Android listener -> WebRTC tunnel -> answer side -> llama-server
```

## 3. Repository strategy

Use the same repository as the Rust implementation.

Recommended eventual repo name:

```text
webrtc_tunnel
```

Current repo may still be named:

```text
rust_webrtc_tunnel
```

Do not create a separate GitHub repository for the Android app in v1. Keep Android and Rust code in one monorepo so the protocol, crypto, config semantics, tunnel frame format, and tests stay synchronized.

## 4. Branching requirement

Android work must happen on a new feature branch.

Recommended branch name:

```bash
git switch -c android-app
```

Alternative acceptable names:

```bash
git switch -c android-webrtc-tunnel
git switch -c feature/android-app
```

Do not commit Android implementation work directly to `master`. Merge only after the Android version builds, passes tests, and the user approves it.

## 5. Target architecture

```text
webrtc_tunnel/
  Cargo.toml
  crates/
    p2p-core/
    p2p-crypto/
    p2p-signaling/
    p2p-tunnel/
    p2p-webrtc/
    p2p-daemon/
    p2p-mobile/              # new Rust JNI/mobile wrapper crate

  bins/
    p2p-offer/
    p2p-answer/
    p2pctl/

  android/
    settings.gradle.kts
    build.gradle.kts
    gradle/libs.versions.toml
    app/
      build.gradle.kts
      src/main/AndroidManifest.xml
      src/main/java/com/phillipchin/webrtctunnel/
        MainActivity.kt
        TunnelForegroundService.kt
        RustTunnelBridge.kt
        WebRtcTunnelApplication.kt
        ui/
        data/
        service/
        model/
        security/
        network/
        notification/
```

## 6. Android technical baseline

Use modern native Android tooling.

```text
Language: Kotlin
UI: Jetpack Compose
Design system: Material 3
Build files: Gradle Kotlin DSL (*.gradle.kts)
Dependency management: Gradle version catalog
Architecture: MVVM-ish / unidirectional data flow
Async: Kotlin coroutines + Flow
Preferences: AndroidX DataStore Preferences
Service: Android ForegroundService
Native bridge: Rust cdylib via JNI
Rust Android build: cargo-ndk
```

Recommended SDK settings:

```kotlin
minSdk = 26
targetSdk = 35
compileSdk = 35 // or newer installed stable SDK
```

Start with these ABIs:

```text
arm64-v8a
x86_64
```

Optional later:

```text
armeabi-v7a
```

## 7. Android package and app naming

Default package name:

```text
com.phillipchin.webrtctunnel
```

Default app name:

```text
WebRTC Tunnel
```

Avoid including `rust` in the Android package name because the repo may later be renamed to `webrtc_tunnel`.

## 8. Gradle requirements

Use Gradle Kotlin DSL:

```text
settings.gradle.kts
build.gradle.kts
app/build.gradle.kts
gradle/libs.versions.toml
```

Use an Android version catalog for dependency versions.

Expected Android dependencies:

```text
androidx.core:core-ktx
androidx.activity:activity-compose
androidx.compose.ui:ui
androidx.compose.material3:material3
androidx.compose.ui:ui-tooling-preview
androidx.navigation:navigation-compose
androidx.lifecycle:lifecycle-viewmodel-compose
androidx.lifecycle:lifecycle-runtime-compose
androidx.datastore:datastore-preferences
org.jetbrains.kotlinx:kotlinx-coroutines-android
```

Recommended optional dependency injection:

```text
Hilt
```

If Hilt adds too much initial complexity, manual dependency construction is acceptable for v1. Do not let DI setup block the tunnel.

## 9. Rust mobile crate

Add:

```text
crates/p2p-mobile/
```

`p2p-mobile` is the JNI/FFI wrapper crate. It should expose a narrow, stable Android-facing API and call the existing Rust crates internally.

`Cargo.toml` should include:

```toml
[lib]
crate-type = ["cdylib", "rlib"]
```

The Android app should call only `p2p-mobile`. Kotlin must not depend on internal Rust protocol details.

## 10. Rust code reuse

Reuse these crates directly where possible:

```text
p2p-core
p2p-crypto
p2p-signaling
p2p-tunnel
```

Attempt to reuse:

```text
p2p-webrtc
p2p-daemon
```

If `p2p-webrtc` builds cleanly for Android, use it.

Fallback if Rust WebRTC does not build on Android:

```text
Rust:
  crypto
  signaling
  config
  tunnel frame/multiplexing

Kotlin/native Android:
  WebRTC PeerConnection/DataChannel

Compatibility:
  preserve all wire formats and semantics
```

Do not change the desktop Rust protocol to work around Android build issues unless explicitly approved.

## 11. JNI / FFI API

The mobile Rust API should be intentionally small.

Suggested Kotlin-facing operations:

```kotlin
interface TunnelController {
    fun start(configPath: String, mode: TunnelMode): Result<Unit>
    fun stop(): Result<Unit>
    fun getStatusJson(): String
    fun getRecentLogsJson(maxEvents: Int): String
    fun validateConfig(configPath: String): ValidationResult
}
```

Suggested Rust FFI shape:

```rust
p2ptunnel_create_runtime() -> Handle
p2ptunnel_start_offer(handle, config_path) -> ErrorCode
p2ptunnel_start_answer(handle, config_path) -> ErrorCode
p2ptunnel_stop(handle) -> ErrorCode
p2ptunnel_status_json(handle) -> *mut c_char
p2ptunnel_recent_logs_json(handle, max_events) -> *mut c_char
p2ptunnel_validate_config(config_path) -> *mut c_char
p2ptunnel_free_string(ptr)
p2ptunnel_destroy_runtime(handle)
```

FFI requirements:

1. No Rust panic may cross the FFI boundary.
2. Convert failures to structured error codes or JSON errors.
3. All returned strings must have a matching free function.
4. Avoid passing borrowed Rust pointers to Kotlin.
5. Runtime handle must be thread-safe or guarded so service calls cannot race unsafely.
6. Stop must be idempotent.
7. Status must be safe before start, while running, after stop, and after errors.

## 12. Build Rust for Android

Use `cargo-ndk` to build `p2p-mobile`.

Expected build outputs:

```text
android/app/src/main/jniLibs/arm64-v8a/libp2p_mobile.so
android/app/src/main/jniLibs/x86_64/libp2p_mobile.so
```

Do not commit large generated binaries unless the repo convention accepts that. Prefer a Gradle task that builds/copies them locally.

Recommended workflow:

```bash
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o android/app/src/main/jniLibs \
  build -p p2p-mobile --release
```

Provide a Gradle task wrapper if practical:

```bash
./gradlew buildRustAndroid
```

## 13. Android app architecture

Use layered architecture.

```text
UI layer
  Compose screens
  ViewModels
  UI state data classes

Application/service layer
  TunnelRepository
  TunnelController
  TunnelForegroundService
  NetworkPolicyManager
  NotificationController
  ConfigRepository
  IdentityRepository
  LogRepository

Native bridge layer
  RustTunnelBridge
  JNI calls into libp2p_mobile.so

Rust layer
  p2p-mobile
  existing p2p-* crates
```

Use state-down/events-up UI flow:

```text
TunnelStatus Flow -> ViewModel -> Compose UI
UI events -> ViewModel -> Repository/Service -> Rust bridge
```

## 14. ForegroundService architecture

The tunnel must run in an Android `ForegroundService`.

The Activity is not required to stay visible after the tunnel starts.

Example runtime:

```text
Browser Activity:
  visible foreground app

WebRTC Tunnel:
  ForegroundService running behind browser
  persistent notification visible
  local TCP listener active
  MQTT/WebRTC runtime active
```

Required service behavior:

1. Start from explicit user action.
2. Show persistent notification immediately.
3. Own Rust tunnel runtime handle.
4. Own start/stop lifecycle.
5. Monitor network policy changes.
6. Pause/stop tunnel when metered/cellular policy requires it.
7. Update notification status.
8. Survive Activity recreation.
9. Stop cleanly when user taps Stop.
10. Release Rust runtime and local listeners on stop.

Do not implement the tunnel as a hidden background service.

## 15. Android permissions

Minimum manifest permissions:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

For Android 14+ foreground-service behavior, declare the appropriate foreground service type and matching permission. During implementation, choose the least-wrong foreground service type for a user-visible network tunnel. Document the decision in code comments.

If adding boot start later:

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

Do not add boot behavior in v1 unless explicitly requested.

## 16. Network policy

### 16.1 Default policy

Cellular/metered usage must be disabled by default.

```text
Wi-Fi / unmetered:
  tunnel allowed

Cellular / metered:
  tunnel blocked unless explicitly allowed

Unknown network:
  fail safe; block or require user confirmation

No network:
  tunnel cannot start / pauses
```

Do not simply check Wi-Fi vs cellular. Use Android network metering capabilities.

### 16.2 Android app preferences

Store Android-only network policy in DataStore, not in the cross-platform Rust tunnel config.

Defaults:

```kotlin
allowMetered = false
pauseOnMetered = true
resumeOnUnmetered = true
showMeteredWarning = true
```

### 16.3 Runtime checks

The service must check network policy:

1. before starting Rust runtime,
2. before MQTT connect,
3. before WebRTC negotiation,
4. before opening local listeners,
5. before accepting local client connections if practical,
6. on every network change.

If network switches from unmetered Wi-Fi to cellular/metered while the tunnel is running and `allowMetered = false`:

```text
pause tunnel
close or stop accepting local listeners
disconnect MQTT/WebRTC if required
show paused notification
show paused state in Home UI
```

When unmetered Wi-Fi returns:

```text
resume automatically if resumeOnUnmetered = true
otherwise show Resume action
```

### 16.4 Cellular / metered warning dialog

When enabling cellular/metered use, show a strong warning:

```text
Cellular / Metered Data Warning

WebRTC Tunnel can use a large amount of data. Browser traffic, API calls, SSH sessions, downloads, streaming, llama-server usage, or other forwarded traffic may consume your mobile data plan quickly.

Your carrier may charge overage fees, throttle your connection, or suspend service depending on your plan.

The app developer is not responsible for carrier charges, throttling, overage fees, or data-plan exhaustion caused by your use of this feature.

Only enable this if you understand the risk and accept responsibility for any data usage or charges.

[Cancel]
[I understand — allow cellular/metered tunnels]
```

Optional allowance choices:

```text
Allow for 15 minutes
Allow until tunnel stops
Always allow
Cancel
```

Default remains disabled.

## 17. Identity storage and security

### 17.1 Threat model

Protect against:

```text
ordinary apps
casual file extraction
shared-storage leaks
accidental export
logs/diagnostics leaks
backups where avoidable
```

Out of scope:

```text
fully rooted device
malware with root
compromised OS
hostile kernel/bootloader
runtime memory scraping
```

### 17.2 Private identity storage

The private identity must not be stored as plaintext.

Use:

```text
app-private internal storage:
  identity.enc

Android Keystore:
  non-exportable encryption/wrapping key
```

Recommended implementation:

```text
identity.enc encrypted with AES-GCM
AES key generated/stored/protected by Android Keystore
plaintext identity held only in memory while starting/running tunnel
avoid plaintext temp files
delete plaintext temp files immediately if unavoidable
```

### 17.3 Public files

These may be plaintext in app-private internal storage:

```text
identity.pub
authorized_keys
config.toml
```

### 17.4 Import private identity

Flow:

```text
user selects compatible desktop identity file
app validates identity
app encrypts to identity.enc
app stores public identity separately
app does not keep plaintext copy
```

### 17.5 Export private identity

Export requires strong warning:

```text
Private Identity Export Warning

Anyone with this file can impersonate this phone in your tunnel network.

Only export it if you understand the risk.

[Cancel]
[Export Private Identity]
```

Optional but recommended:

```text
require device unlock / biometric before export
```

### 17.6 Logs and diagnostics

Never include:

```text
private identity
private key material
MQTT password
tokens
full SDP
full ICE candidates
decrypted signaling payloads
raw forwarded data
```

## 18. Config compatibility

The Android app should generate and consume compatible tunnel config.

Android-only preferences must remain separate from the Rust tunnel protocol config.

```text
Protocol config:
  config.toml
  identity.pub
  authorized_keys
  peer/forward/broker settings

Android preferences:
  allow_metered
  pause_on_metered
  resume_on_unmetered
  notification settings
  UI preferences
```

The Android offer side must request only `forward_id`.

It must not allow arbitrary remote target host/port selection.

## 19. v1 feature scope

### 19.1 In scope

1. Android project skeleton in same repo.
2. Kotlin + Compose + Material 3 UI.
3. ForegroundService.
4. Offer-mode client workflow.
5. Rust JNI bridge through `p2p-mobile`.
6. Build Rust library for Android ABIs.
7. Localhost port forwarding using configured forwards.
8. MQTT/WebRTC compatibility with desktop Rust answer.
9. Android network policy with metered/cellular block by default.
10. Android Keystore-encrypted private identity at rest.
11. Setup wizard.
12. Home/status screen.
13. Forwards list/detail screens.
14. Logs screen.
15. Settings screen.
16. Import/export public identity and config.
17. Private identity import/export with encryption/warnings.
18. Persistent notification.
19. Basic tests and CI.

### 19.2 Advanced / optional v1

1. Answer mode hidden behind Advanced.
2. QR code public identity import/export.
3. Temporary cellular allowance.
4. Biometric confirmation before private export.
5. Debug log export.

### 19.3 Out of scope for v1

1. VPN/TUN whole-device tunneling.
2. TURN support.
3. Arbitrary remote host/port selection from Android offer side.
4. Hidden background service with no notification.
5. Cellular/metered use without explicit opt-in.
6. Cloud account system.
7. Remote config sync.
8. Browser extension.
9. Complex traffic analytics.
10. Room database unless later needed.

## 20. UI/UX requirements

The UI requirements from `ANDROID_UI_SCREEN_SPEC.md` are incorporated here.

### 20.1 Main navigation

Primary tabs:

```text
Home
Forwards
Logs
Settings
```

Setup Wizard is separate.

### 20.2 First-run flow

```text
Welcome / Setup Wizard
  -> Choose Mode
  -> Identity
  -> MQTT Broker
  -> Remote Peer
  -> Forwards
  -> Network Policy
  -> Review
  -> Home / Status
```

### 20.3 Normal use flow

```text
Home
  -> Start Tunnel
  -> ForegroundService starts
  -> Notification appears
  -> User switches to browser/SSH/API app
  -> Other app connects to localhost forwarded port
```

## 21. UI global states

The UI must distinguish:

| State | Meaning |
|---|---|
| `Stopped` | Service is not running |
| `Starting` | Service is starting Rust runtime |
| `Serving` | Answer daemon ready to accept sessions |
| `Listening` | Offer local listeners are open |
| `Connecting` | MQTT/WebRTC negotiation in progress |
| `Connected` | Tunnel usable |
| `Reconnecting` | Session dropped; reconnecting |
| `PausedMeteredBlocked` | Cellular/metered policy blocked tunnel |
| `NoNetwork` | No usable network |
| `Error` | User action required |
| `Stopping` | Service shutting down |
| `ConfigInvalid` | Config cannot start |

## 22. Screen: Home / Status

### Purpose

The Home screen answers:

1. Is the tunnel running?
2. Is it connected?
3. Is it blocked by network policy?
4. What local ports are available?
5. What should the user do next?

### Layout

```text
Top app bar
Status card
Network card
Forwards summary card
Action row
Bottom navigation
```

### Connected offer-mode example

```text
Connected
Tunnel is active and ready to use

Mode: Offer (Client)
Remote peer: home-server
Active sessions: 1
Uptime: 00:12:34

Network:
Wi-Fi (Unmetered)
Tunnel allowed

Forwards:
llama
127.0.0.1:8080 -> llama

ssh
127.0.0.1:2223 -> ssh

[Stop Tunnel]
[View Logs]
[Copy URL]
[Open Browser]
```

### Paused metered example

```text
Paused
Cellular/metered network blocked

Network:
Cellular (Metered)
Tunnel blocked by policy

[Settings]
[Allow Temporarily]
[Stop]
```

### Error example

```text
Error
Local port 8080 is already in use

Suggested fix:
Change the local port or stop the app using it.

[Edit Forward]
[Retry]
[View Logs]
```

## 23. Screen: Setup Wizard

Wizard steps:

```text
1. Choose Mode
2. Identity
3. MQTT Broker
4. Remote Peer
5. Forwards
6. Network Policy
7. Review
```

Each step:

```text
Back
Next
Cancel
progress indicator
```

Next disabled until valid.

## 24. Setup Step: Choose Mode

Default:

```text
Offer / Client
```

Options:

```text
Use this phone as a client (Offer side)
Use this phone as a server (Answer side) — Advanced
```

Offer mode must be first-class. Answer mode may be hidden or disabled in v1 if not implemented.

## 25. Setup Step: Identity

Options:

```text
Generate new identity
Import existing identity
```

Generated identity card:

```text
Your identity
android-phone

Public identity
<public key text>

[Copy Public Key]
[Share Public Key]
```

Validation:

1. Peer ID required.
2. Imported identity must parse.
3. Private identity never displayed in normal UI.
4. Private identity stored encrypted at rest.

## 26. Setup Step: MQTT Broker

Fields:

```text
Broker host
Port
TLS enabled
Username optional
Password optional
Topic prefix optional
```

Action:

```text
[Test Connection]
```

Validation:

1. Host required.
2. Port range 1-65535.
3. TLS enabled by default.
4. Credentials redacted in logs.

## 27. Setup Step: Remote Peer

For offer mode, configure answer peer.

Fields:

```text
Remote peer ID
Remote public identity
```

Actions:

```text
Paste from Clipboard
Import File
Scan QR Code later
```

Validation:

1. Remote peer ID required.
2. Public identity parses.
3. Remote peer cannot equal local peer.

Helper text:

```text
Paste the answer side public identity here. The answer side must also authorize this phone's public identity.
```

## 28. Setup Step: Forwards

Offer-side fields:

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
```

Examples:

```text
llama
127.0.0.1:8080 -> llama
```

```text
ssh
127.0.0.1:2223 -> ssh
```

Validation:

1. Name required.
2. Local port 1-65535.
3. No duplicate enabled local ports.
4. Remote `forward_id` required.
5. No remote host/port selection.
6. Warn before non-localhost bind.

## 29. Setup Step: Network Policy

Default:

```text
Allow cellular / metered data: OFF
```

Display current network and whether tunnel is allowed.

Settings:

```text
Allow cellular / metered data
Pause tunnel when cellular/metered network is detected
Resume tunnel when unmetered Wi-Fi returns
Show warning before allowing cellular/metered data
```

Cellular warning dialog required as defined in section 16.4.

## 30. Setup Step: Review

Summary:

```text
Mode: Offer
Local identity: android-phone
Remote peer: home-server
Broker: mqtt.example.com:8883 TLS
Network policy: Cellular/metered blocked
Forwards:
  127.0.0.1:8080 -> llama
  127.0.0.1:2223 -> ssh
```

Actions:

```text
Start Tunnel
Save and Start Later
Back
```

## 31. Screen: Forwards List

List rows:

```text
llama
127.0.0.1:8080 -> llama
Status: Listening
```

States:

| State | UI |
|---|---|
| Enabled + Listening | Green dot |
| Enabled + Stopped | Gray dot |
| Enabled + Error | Red dot |
| Disabled | Gray row |
| Blocked by network policy | Orange dot |

Actions:

```text
Add Forward
Edit Forward
Disable Forward
Delete Forward
```

Delete requires confirmation.

## 32. Screen: Forward Details

Content:

```text
Forward: llama
Status: Listening

Local address: 127.0.0.1:8080
Remote forward_id: llama
Local URL: http://127.0.0.1:8080

Last error: None
```

Actions:

```text
Copy URL
Open Browser
Test Local Port
Edit
Disable Forward
Delete Forward
```

## 33. Screen: Logs

Default log display should be user-safe event logs.

Examples:

```text
12:01 Tunnel service started
12:01 Network OK: Wi-Fi unmetered
12:01 MQTT connected
12:01 WebRTC session connected to home-server
12:02 Listening on 127.0.0.1:8080
12:05 Local client connected to llama
12:10 Tunnel paused: cellular/metered network blocked
```

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

Debug logs may require advanced toggle.

## 34. Screen: Settings

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

Tunnel settings:

```text
Start tunnel automatically when app opens
Resume tunnel when Wi-Fi returns
Stop tunnel on cellular/metered network
```

Identity settings:

```text
View public identity
Copy public identity
Share public identity
Import identity
Export public identity
Export private identity
Regenerate identity
```

Configuration settings:

```text
Import configuration
Export configuration
Reset configuration
Run setup wizard again
```

Diagnostics settings:

```text
Export diagnostics
Copy status JSON
Copy redacted config
```

Advanced:

```text
Answer mode
Bind local forwards to non-localhost address
Custom MQTT topic prefix
Custom keepalive
Debug logs
```

## 35. Screen: Import / Export

Import:

```text
Import config file
Import identity
Import authorized peer/public identity
Paste public identity
Scan QR code later
```

Export:

```text
Export config
Export public identity
Export diagnostics
Export private identity
```

Private identity export requires strong warning and preferably device unlock/biometric.

## 36. Error resolution UX

Common errors:

| Error | User message | Suggested action |
|---|---|---|
| Local port in use | Port is already in use | Edit forward port |
| MQTT auth failed | Broker rejected credentials | Edit broker settings |
| TLS failed | Broker TLS validation failed | Check broker/certificate |
| Remote peer not authorized | Remote rejected this identity | Add phone public key to answer authorized_keys |
| Forward denied | Remote answer denied this forward | Check allow_remote_peers |
| Metered blocked | Cellular/metered blocked | Connect Wi-Fi or allow temporarily |
| No network | No network connection | Connect network |
| WebRTC failed | Could not establish session | Check network/firewall/STUN |
| Config invalid | Configuration cannot start | Open setup wizard |

Display rules:

1. Human-readable summary first.
2. Clear action button.
3. Technical details behind "Show details."
4. Never show secrets.

## 37. Notification UX

Running notification:

```text
WebRTC Tunnel running
Connected · 2 forwards active

[Stop]
[Open]
```

Paused notification:

```text
WebRTC Tunnel paused
Cellular/metered network blocked

[Settings]
[Allow temporarily]
[Stop]
```

Error notification:

```text
WebRTC Tunnel error
Local port 8080 is already in use

[Open]
[Stop]
```

## 38. Accessibility

1. Minimum touch target 48dp.
2. Text scales with system font size.
3. Icons have content descriptions.
4. Color is not the only status indicator.
5. Status cards use labels: Connected, Paused, Error.
6. Warning dialogs are screen-reader friendly.
7. Buttons use clear verbs.

## 39. Testing strategy

### 39.1 Rust tests

Keep existing Rust tests passing:

```bash
cargo test --workspace --all-targets
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo fmt --check
```

### 39.2 Android unit tests

Test:

1. ViewModels.
2. Network policy classification.
3. DataStore preferences.
4. Config generation.
5. Identity import/export validation.
6. Keystore encryption/decryption wrapper where testable.
7. RustTunnelBridge error mapping with fake bridge.

### 39.3 Android instrumented tests

Test:

1. ForegroundService start/stop.
2. Notification appears.
3. Network policy pause/resume.
4. Config files created in app-private storage.
5. Identity encrypted at rest.
6. Compose UI screens render.

### 39.4 Compatibility tests

At minimum, add a manual or automated compatibility test:

```text
Desktop p2p-answer
Android p2p-offer
Android browser -> 127.0.0.1:<port>
Remote service responds
```

Also later:

```text
Android p2p-answer
Desktop p2p-offer
```

if answer mode is implemented.

## 40. CI requirements

Add CI jobs if feasible:

```text
Rust fmt/clippy/test
Android assembleDebug
cargo-ndk Android build
Android unit tests
```

Do not block initial local development if CI is difficult, but document the missing CI tasks.

## 41. Release/testing artifacts

Early testing:

```text
debug APK
```

Later:

```text
signed APK
AAB for Play Store
F-Droid-compatible build if desired
```

## 42. Acceptance checklist

The Android implementation is acceptable when:

- [ ] Work is on a dedicated Android branch.
- [ ] Android project builds with Gradle Kotlin DSL.
- [ ] App uses Kotlin + Jetpack Compose + Material 3.
- [ ] `p2p-mobile` Rust crate builds for `arm64-v8a`.
- [ ] Android app can load JNI library.
- [ ] Android app can start/stop tunnel through ForegroundService.
- [ ] Persistent notification appears while tunnel is running.
- [ ] Offer mode can connect to desktop Rust answer.
- [ ] Android browser can use `http://127.0.0.1:<port>` through the tunnel.
- [ ] Cellular/metered data is blocked by default.
- [ ] User gets strong warning before enabling cellular/metered use.
- [ ] Network changes pause/resume according to policy.
- [ ] Private identity is encrypted at rest using Android Keystore.
- [ ] Private identity export requires explicit warning.
- [ ] Logs and diagnostics redact secrets.
- [ ] Basic UI screens are implemented.
- [ ] Config/key import/export works.
- [ ] Protocol compatibility is preserved.
- [ ] Rust tests still pass.
- [ ] Android build/test commands pass.
