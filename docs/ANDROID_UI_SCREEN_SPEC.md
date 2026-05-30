# Android UI Specification — Rust WebRTC Tunnel

## 1. Purpose

This document specifies the Android UI/UX for the Android version of `rust_webrtc_tunnel`.

The Android app is a control panel for a long-running tunnel service. The tunnel itself runs inside an Android `ForegroundService` so that other Android apps, such as Chrome, Firefox, Termux, JuiceSSH, or API clients, can connect to local forwarded ports while the tunnel app UI is not visible.

The Android app must remain protocol-compatible with the desktop Rust version.

## 2. Product model

```text
Android app Activity
  - configuration
  - status display
  - start/stop controls
  - logs
  - settings

Android ForegroundService
  - owns Rust tunnel runtime
  - owns local TCP listeners
  - owns MQTT/WebRTC connections
  - owns persistent notification
  - keeps running while other apps are foregrounded

Other Android apps
  - connect to 127.0.0.1:<local_port>
  - use forwarded services through the tunnel
```

Example:

```text
Android browser:
  http://127.0.0.1:8080

Android rust_webrtc_tunnel offer service:
  127.0.0.1:8080 -> WebRTC tunnel -> remote answer forward_id "llama"

Remote answer side:
  forward_id "llama" -> 127.0.0.1:8080 llama-server
```

## 3. Non-negotiable UX and product rules

1. The tunnel must not use cellular or metered networks unless the user explicitly allows it.
2. Cellular/metered usage is disabled by default.
3. The tunnel must run as a `ForegroundService` while active.
4. The user must always have a visible persistent notification while the tunnel is running or paused by network policy.
5. The Android UI must not require manual TOML editing for basic setup.
6. The Android app must preserve desktop protocol compatibility.
7. The Android offer side must request only `forward_id`; it must not choose remote target host/port.
8. The answer side owns target mappings.
9. Secrets, private keys, SDP, ICE candidates, decrypted payloads, and MQTT credentials must not be shown in normal logs.
10. Bind forwarded local ports to `127.0.0.1` by default.
11. Binding to `0.0.0.0` must be hidden behind an advanced warning, if supported at all.
12. Offer mode is the default first-class Android workflow.
13. Answer mode is advanced.

## 4. Design language

Use standard Android Material-style UI.

Recommended visual style:

- dark navy app bar
- white/light content cards
- green for connected/allowed
- orange/yellow for paused/warning
- red for error/destructive actions
- blue/navy primary actions
- rounded cards
- clear status icons
- bottom navigation for main screens
- wizard progress indicator for setup flow

Primary tabs:

```text
Home
Forwards
Logs
Settings
```

Setup wizard is a separate flow launched from first-run or Settings.

## 5. Global navigation model

### 5.1 First-run flow

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

### 5.2 Normal use flow

```text
Home / Status
  -> Start Tunnel
  -> ForegroundService starts
  -> Notification appears
  -> User switches to browser/client app
  -> Browser connects to localhost port
```

### 5.3 Main navigation tabs

```text
Home
Forwards
Logs
Settings
```

### 5.4 Secondary screens

```text
Forward Details
Setup Wizard
Network Policy
Identity Management
Remote Peer Management
MQTT Broker Settings
Import / Export
Diagnostics Export
```

## 6. Global app states

The UI must distinguish these states clearly.

| State | Meaning | Primary UI |
|---|---|---|
| `Stopped` | ForegroundService is not running | Start Tunnel button |
| `Starting` | Service is starting Rust runtime | Spinner, Stop button |
| `Serving` | Answer daemon is ready to accept sessions | Serving badge |
| `Listening` | Offer side local listeners are open | Local URLs visible |
| `Connecting` | MQTT/WebRTC negotiation in progress | Connecting indicator |
| `Connected` | Tunnel is usable | Green connected card |
| `Reconnecting` | Session dropped; reconnecting | Yellow reconnecting card |
| `PausedMeteredBlocked` | Cellular/metered network blocked | Orange paused card |
| `NoNetwork` | No usable network | Warning card |
| `Error` | User action required | Red error card |
| `Stopping` | Service is shutting down | Spinner |
| `ConfigInvalid` | Config cannot start | Error with fix action |

## 7. ForegroundService notification UX

### 7.1 Running notification

Title:

```text
Rust WebRTC Tunnel running
```

Body examples:

```text
Connected · 2 forwards active
Listening · 2 local ports
Serving · 2 active sessions
Reconnecting to home-server
```

Actions:

```text
Stop
Open
```

Optional later:

```text
Pause
Copy URL
```

### 7.2 Paused by cellular/metered policy

Title:

```text
Rust WebRTC Tunnel paused
```

Body:

```text
Cellular/metered network blocked
```

Actions:

```text
Settings
Allow temporarily
Stop
```

### 7.3 Error notification

Title:

```text
Rust WebRTC Tunnel error
```

Body examples:

```text
MQTT connection failed
Config invalid
Local port 8080 already in use
```

Actions:

```text
Open
Stop
```

## 8. Screen 1 — Home / Status

### 8.1 Purpose

The Home screen is the main dashboard. It must answer:

1. Is the tunnel running?
2. Is it connected or paused?
3. What network is being used?
4. What local ports are available?
5. What should the user do next?

### 8.2 Layout

Top app bar:

```text
Rust WebRTC Tunnel
menu icon
overflow menu
```

Main cards:

```text
Status card
Network card
Forwards summary card
Action row
Bottom navigation
```

### 8.3 Connected state example

Status card:

```text
Connected
Tunnel is active and ready to use

Mode: Offer (Client)
Remote peer: home-server
Active sessions: 1
Uptime: 00:12:34
```

Network card:

```text
Wi-Fi (Unmetered)
Tunnel allowed
```

Forwards card:

```text
Forwards (2)

llama
127.0.0.1:8080 -> llama

ssh
127.0.0.1:2223 -> ssh
```

Actions:

```text
Stop Tunnel
View Logs
```

Optional convenience action:

```text
Open Browser
Copy Local URL
```

### 8.4 Paused cellular/metered state

Status card:

```text
Paused
Cellular/metered network blocked

Mode: Offer (Client)
Remote peer: home-server
Active sessions: 0
Uptime: -
```

Network card:

```text
Cellular (Metered)
Tunnel blocked by policy
```

Actions:

```text
Start Tunnel
Settings
Allow Temporarily
```

If `Allow Temporarily` is tapped, show the cellular warning dialog.

### 8.5 Stopped state

Status card:

```text
Stopped
Tunnel service is not running

Mode: Offer (Client)
Remote peer: home-server
```

Actions:

```text
Start Tunnel
Setup
```

### 8.6 Error state

Status card:

```text
Error
Local port 8080 is already in use

Suggested fix:
Change the local port or stop the app using it.
```

Actions:

```text
Edit Forward
Retry
View Logs
```

### 8.7 Home screen data requirements

The screen consumes:

```kotlin
TunnelStatus(
    serviceState,
    mode,
    localPeerId,
    remotePeerId,
    mqttConnected,
    sessionCount,
    sessionCapacity,
    uptimeSeconds,
    networkStatus,
    forwards,
    lastError
)
```

Forward summary:

```kotlin
ForwardSummary(
    id,
    displayName,
    localHost,
    localPort,
    remoteForwardId,
    enabled,
    listenState,
    lastError
)
```

## 9. Screen 2 — Setup Wizard

The setup wizard should be used on first launch and when the user chooses "Run Setup Again" from Settings.

### 9.1 Wizard structure

Steps:

```text
1. Choose Mode
2. Identity
3. MQTT Broker
4. Remote Peer
5. Forwards
6. Network Policy
7. Review
```

Each wizard step has:

```text
Back
Next
Cancel
progress indicator
```

The Next button is disabled until the current step is valid.

## 10. Setup Step 1 — Choose Mode

### 10.1 Purpose

Choose whether this Android device is an offer/client or answer/server.

Default:

```text
Offer / Client
```

### 10.2 UI content

Title:

```text
Choose Mode
```

Subtitle:

```text
How do you want to use this phone?
```

Options:

```text
Use this phone as a client (Offer side)
This phone will connect to a remote answer peer and request forwarded services.
```

```text
Use this phone as a server (Answer side) — Advanced
This phone will wait for incoming connections and provide access to local services.
```

### 10.3 Validation

- One mode must be selected.
- Offer mode selected by default.
- Answer mode should display an "Advanced" label.

### 10.4 Notes

For v1 Android, offer mode should be fully supported first. Answer mode may be hidden behind advanced settings if not implemented.

## 11. Setup Step 2 — Identity

### 11.1 Purpose

Create or import the Android device identity.

### 11.2 UI content

Title:

```text
Identity
```

Subtitle:

```text
Your identity is how other peers recognize this phone.
```

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

Copy Public Key
Share Public Key
```

### 11.3 Fields

```text
Peer ID
Public identity text
Identity created/imported status
```

### 11.4 Validation

- Peer ID must be non-empty.
- Peer ID must be unique within this app config.
- Imported identity must parse successfully.
- Private identity must never be displayed after import except in explicit export flow.

### 11.5 Security UX

When exporting private identity:

```text
Private Identity Export Warning

Anyone with this file can impersonate this phone in your tunnel network.

Only export it if you understand the risk.

[Cancel]
[Export Private Identity]
```

## 12. Setup Step 3 — MQTT Broker

### 12.1 Purpose

Configure the MQTT broker used for encrypted signaling.

### 12.2 UI fields

```text
Broker host
Port
TLS enabled
Username optional
Password optional
Topic prefix optional
```

Optional advanced fields:

```text
CA certificate
Client certificate
Client key
Keepalive seconds
```

### 12.3 Actions

```text
Test Connection
```

### 12.4 Validation

- Host required.
- Port must be 1-65535.
- TLS should be enabled by default.
- Password field hidden by default.
- Test connection must not log password.
- The app must not send unencrypted tunnel signaling payloads.

### 12.5 Errors

Examples:

```text
Could not resolve broker host.
TLS handshake failed.
Authentication failed.
Connection timed out.
```

## 13. Setup Step 4 — Remote Peer

### 13.1 Purpose

Configure the remote peer identity.

For offer mode, this is the answer peer.

### 13.2 UI fields

```text
Remote peer ID
Remote public identity
```

Actions:

```text
Paste from Clipboard
Scan QR Code
Import File
```

### 13.3 Validation

- Remote peer ID required.
- Public identity must parse successfully.
- Remote public identity must match expected peer ID if the identity format embeds peer ID.
- Do not allow the local identity to be used as remote peer.

### 13.4 UX note

Show helper text:

```text
Paste the answer side public identity here. The answer side must also authorize this phone's public identity.
```

## 14. Setup Step 5 — Forwards

### 14.1 Purpose

Configure local ports on Android that map to remote forward IDs.

### 14.2 Offer-side forward fields

```text
Name
Local host
Local port
Remote forward_id
Enabled
```

Default local host:

```text
127.0.0.1
```

Example:

```text
Name: llama
Local host: 127.0.0.1
Local port: 8080
Remote forward_id: llama
```

Another example:

```text
Name: ssh
Local host: 127.0.0.1
Local port: 2223
Remote forward_id: ssh
```

### 14.3 Validation

- Name required.
- Local host defaults to `127.0.0.1`.
- Local port must be 1-65535.
- Local port must not duplicate another enabled forward.
- Remote `forward_id` required.
- Remote `forward_id` must not include remote host/port.
- Do not allow local host `0.0.0.0` unless advanced mode and explicit warning.

### 14.4 Add Forward flow

```text
Tap Add Forward
Enter fields
Tap Save
Validate port availability if possible
Return to Forwards step
```

### 14.5 Advanced bind warning

If user tries to bind to anything other than `127.0.0.1`:

```text
Local Network Exposure Warning

Binding to this address may allow other devices on your network to connect to this forwarded port.

Use 127.0.0.1 unless you specifically need network exposure.

[Cancel]
[I understand]
```

## 15. Setup Step 6 — Network Policy

### 15.1 Purpose

Prevent accidental cellular/metered data usage.

### 15.2 Default policy

```text
Allow cellular / metered data: OFF
```

### 15.3 UI content

Current network card:

```text
Current network
Wi-Fi (Unmetered)
Tunnel allowed: Yes
```

or:

```text
Current network
Cellular (Metered)
Tunnel allowed: No
```

Settings:

```text
Allow cellular / metered data
Pause tunnel when cellular/metered network is detected
Resume tunnel when unmetered Wi-Fi returns
Show warning before allowing cellular/metered data
```

Defaults:

```text
allow_metered = false
pause_on_metered = true
resume_on_unmetered = true
show_metered_warning = true
```

### 15.4 Cellular warning dialog

Required exact UX concept:

```text
Cellular / Metered Data Warning

Rust WebRTC Tunnel can use a large amount of data. Browser traffic, API calls, SSH sessions, downloads, streaming, llama-server usage, or other forwarded traffic may consume your mobile data plan quickly.

Your carrier may charge overage fees, throttle your connection, or suspend service depending on your plan.

The app developer is not responsible for carrier charges, throttling, overage fees, or data-plan exhaustion caused by your use of this feature.

Only enable this if you understand the risk and accept responsibility for any data usage or charges.

[Cancel]
[I understand — allow cellular/metered tunnels]
```

### 15.5 Temporary allow options

Optional but recommended:

```text
Allow for 15 minutes
Allow until tunnel stops
Always allow
Cancel
```

### 15.6 Runtime behavior

The service must check network policy:

- before starting tunnel runtime
- before MQTT connect
- before WebRTC negotiation
- before opening local listeners
- on every network change
- before accepting new local clients, if practical

When network changes from Wi-Fi to cellular and metered use is disabled:

```text
pause tunnel
close or stop accepting local listeners
disconnect MQTT/WebRTC if needed
show paused notification
show Home screen paused state
```

When unmetered Wi-Fi returns:

```text
resume automatically if resume_on_unmetered = true
otherwise show Resume action
```

## 16. Setup Step 7 — Review

### 16.1 Purpose

Show final summary before starting.

### 16.2 UI content

```text
Mode: Offer
Local identity: android-phone
Remote peer: home-server
Broker: mqtt.home-server.com:8883 TLS
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

### 16.3 Validation

All previous steps must be valid before Start is enabled.

## 17. Screen 3 — Forwards List

### 17.1 Purpose

Show configured forwards and their current listening status.

### 17.2 Layout

Top app bar:

```text
Forwards
Add icon
```

List rows:

```text
llama
127.0.0.1:8080 -> llama
Status: Listening
```

```text
ssh
127.0.0.1:2223 -> ssh
Status: Listening
```

### 17.3 Row states

| State | UI |
|---|---|
| Enabled + Listening | Green dot, "Listening" |
| Enabled + Stopped | Gray dot, "Stopped" |
| Enabled + Error | Red dot, error text |
| Disabled | Gray row, "Disabled" |
| Blocked by network policy | Orange dot, "Paused" |

### 17.4 Actions

```text
Tap row -> Forward Details
Add Forward
Edit Forward
Disable Forward
Delete Forward
```

Delete requires confirmation.

## 18. Screen 4 — Forward Details

### 18.1 Purpose

Show detailed information for one forward.

### 18.2 UI content

```text
Forward: llama
Status: Listening

Local address: 127.0.0.1:8080
Remote forward_id: llama
Local URL: http://127.0.0.1:8080

Bytes sent: optional later
Bytes received: optional later
Open connections: optional later
Last error: None
```

### 18.3 Actions

```text
Copy URL
Open Browser
Test Local Port
Edit
Disable Forward
Delete Forward
```

### 18.4 Test Local Port behavior

The app may attempt a local TCP connection to `127.0.0.1:<port>`.

Possible results:

```text
Port is accepting connections.
Port is not listening.
Connection failed.
Tunnel is paused by network policy.
```

Do not send arbitrary protocol data during this test unless user opts in.

## 19. Screen 5 — Logs

### 19.1 Purpose

Show user-readable tunnel events and optional debug logs.

### 19.2 Default log level

Default view should show user-safe info/warn/error messages only.

Examples:

```text
12:01 Tunnel service started
12:01 Network OK: Wi-Fi unmetered
12:01 MQTT connected
12:01 WebRTC session connected to home-server
12:02 Listening on 127.0.0.1:8080
12:05 Local client connected to llama
12:06 Local client disconnected from llama
12:10 Tunnel paused: cellular/metered network blocked
```

### 19.3 Filters

```text
All
Info
Warn
Error
Debug
```

Debug may require advanced toggle.

### 19.4 Actions

```text
Copy Logs
Export Diagnostics
Clear Logs
Pause Logs
```

### 19.5 Redaction rules

Logs must redact:

- private identity
- private key material
- MQTT password
- tokens
- full SDP
- full ICE candidates
- decrypted signaling payloads
- raw forwarded data

Safe to show:

- local peer ID
- remote peer ID
- session ID, if not sensitive
- forward ID
- local port
- high-level connection state
- redacted error categories

## 20. Screen 6 — Settings

### 20.1 Purpose

Central app preferences and import/export tools.

### 20.2 Sections

```text
Tunnel
Network Policy
Identity
Configuration
Diagnostics
Advanced
About
```

### 20.3 Tunnel settings

```text
Start tunnel automatically when app opens
Resume tunnel when Wi-Fi returns
Stop tunnel on cellular/metered network
```

### 20.4 Network policy settings

```text
Allow cellular / metered data
Show warning before allowing cellular/metered data
Temporary cellular allowance
```

### 20.5 Identity settings

```text
View public identity
Copy public identity
Share public identity
Import identity
Export public identity
Export private identity
Regenerate identity
```

Private export requires warning.

### 20.6 Configuration settings

```text
Import configuration
Export configuration
Reset configuration
Run setup wizard again
```

### 20.7 Diagnostics settings

```text
Export diagnostics
Copy status JSON
Copy redacted config
```

### 20.8 Advanced settings

Advanced settings should be hidden behind an explicit "Advanced" section.

Possible advanced settings:

```text
Answer mode
Bind local forwards to non-localhost address
Custom MQTT topic prefix
Custom keepalive
Debug logs
```

## 21. Screen 7 — Network Policy Details

This can be a dedicated screen or part of Settings.

### 21.1 Purpose

Explain and configure cellular/metered behavior.

### 21.2 UI content

```text
Current network
Wi-Fi / Cellular / Metered / Unknown

Tunnel allowed
Yes / No

Allow cellular / metered data
Off by default

Pause on cellular/metered
On by default

Resume when Wi-Fi returns
On by default
```

### 21.3 Network classification

Use Android network capabilities to classify:

```text
Unmetered Wi-Fi
Metered Wi-Fi
Cellular
No network
Unknown network
```

Fail safe on unknown:

```text
Unknown network
Tunnel blocked until network policy is confirmed
```

## 22. Screen 8 — Import / Export

### 22.1 Purpose

Move config and identities between desktop Rust and Android.

### 22.2 Import options

```text
Import config file
Import identity
Import authorized peer/public identity
Paste public identity
Scan QR code
```

### 22.3 Export options

```text
Export config
Export public identity
Export diagnostics
Export private identity
```

Private identity export must require warning.

### 22.4 Compatibility

Imported/exported config should remain compatible with desktop Rust where practical.

Android-only preferences should be stored separately from protocol config:

```text
Android app preferences:
  allow_metered
  pause_on_metered
  resume_on_unmetered
  notification options

Protocol config:
  unchanged
```

## 23. Screen 9 — Error Resolution

This may be a reusable dialog/card system rather than a separate screen.

### 23.1 Common errors

| Error | User message | Suggested action |
|---|---|---|
| Local port in use | Port 8080 is already in use | Edit forward port |
| MQTT auth failed | Broker rejected username/password | Edit broker settings |
| TLS failed | Broker TLS validation failed | Check host/certificate |
| Remote peer not authorized | Remote rejected this identity | Copy public key to answer authorized_keys |
| Forward denied | Remote answer does not allow this forward | Check answer allow_remote_peers |
| Metered blocked | Cellular/metered network blocked | Connect to Wi-Fi or allow temporarily |
| No network | No network connection | Connect to Wi-Fi |
| WebRTC failed | Could not establish WebRTC session | Check STUN/network/firewall |
| Config invalid | Configuration cannot start | Open setup wizard |

### 23.2 Error display rules

- Show human-readable summary first.
- Provide a clear action button.
- Put technical details behind "Show details."
- Never show secrets.

## 24. Android permissions and prompts

### 24.1 Required permissions

Likely required:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

For Android 14+, foreground service type/permissions must be selected during implementation.

### 24.2 Optional permissions

Possibly later:

```xml
RECEIVE_BOOT_COMPLETED
```

Only if implementing start-on-boot.

### 24.3 Notification permission UX

On Android 13+, notification permission is runtime. The app should explain:

```text
Rust WebRTC Tunnel needs notifications so Android can keep the tunnel service running while you use other apps.
```

## 25. Accessibility requirements

- Minimum touch target: 48dp.
- Text should scale with system font size.
- All icons require content descriptions.
- Color must not be the only indicator of state.
- Status cards must include text labels such as Connected, Paused, Error.
- Warning dialogs must be screen-reader friendly.
- Buttons must have clear verbs: Start Tunnel, Stop Tunnel, Copy Public Key.

## 26. Security requirements

### 26.1 Identity

- Private identity stored in app-private storage.
- Do not display private key in normal UI.
- Private export requires explicit warning.
- Public identity can be copied/shared.

### 26.2 Logs

- Redact secrets.
- Redact raw SDP/ICE by default.
- Redact MQTT credentials.
- Redact decrypted signaling payloads.
- Never log forwarded data.

### 26.3 Local listeners

- Bind to `127.0.0.1` by default.
- Warn before non-localhost bind.
- Show all listening ports clearly.

### 26.4 Network policy

- Metered/cellular blocked by default.
- User must explicitly opt in.
- Warn about data charges and bandwidth exhaustion.
- Pause on network changes.

## 27. Data model for UI

### 27.1 App preferences

```kotlin
data class AndroidAppPreferences(
    val allowMetered: Boolean = false,
    val pauseOnMetered: Boolean = true,
    val resumeOnUnmetered: Boolean = true,
    val showMeteredWarning: Boolean = true,
    val startTunnelWhenAppOpens: Boolean = false,
    val debugLogsEnabled: Boolean = false
)
```

### 27.2 Tunnel status

```kotlin
data class TunnelStatus(
    val serviceState: ServiceState,
    val mode: TunnelMode,
    val localPeerId: String,
    val remotePeerId: String?,
    val mqttConnected: Boolean,
    val activeSessionCount: Int,
    val sessionCapacity: Int?,
    val uptimeSeconds: Long?,
    val networkStatus: NetworkStatus,
    val forwards: List<ForwardStatus>,
    val lastError: TunnelError?
)
```

### 27.3 Forward config

```kotlin
data class ForwardConfig(
    val id: String,
    val name: String,
    val localHost: String = "127.0.0.1",
    val localPort: Int,
    val remoteForwardId: String,
    val enabled: Boolean = true
)
```

### 27.4 Forward status

```kotlin
data class ForwardStatus(
    val id: String,
    val name: String,
    val localHost: String,
    val localPort: Int,
    val remoteForwardId: String,
    val enabled: Boolean,
    val listenState: ListenState,
    val lastError: String?
)
```

### 27.5 Network status

```kotlin
data class NetworkStatus(
    val networkType: NetworkType,
    val isMetered: Boolean,
    val tunnelAllowed: Boolean,
    val blockReason: String?
)
```

## 28. Rust FFI integration expectations

The UI should talk to a Kotlin service layer. The Kotlin service layer talks to Rust FFI.

Suggested minimal operations:

```kotlin
interface TunnelController {
    fun start(configPath: String): Result<Unit>
    fun stop(): Result<Unit>
    fun getStatusJson(): String
    fun getRecentLogs(): List<LogEvent>
    fun validateConfig(configPath: String): ValidationResult
}
```

Rust mobile FFI should expose stable operations such as:

```rust
p2ptunnel_start_offer(config_path)
p2ptunnel_start_answer(config_path)
p2ptunnel_stop(handle)
p2ptunnel_status_json(handle)
p2ptunnel_recent_logs(handle)
p2ptunnel_free_string(ptr)
```

The UI must not directly manipulate protocol internals.

## 29. v1 implementation priority

Build in this order:

1. Home / Status screen
2. ForegroundService notification
3. Setup Wizard for offer mode
4. Network Policy screen and enforcement
5. Forwards list and details
6. Logs screen
7. Settings screen
8. Import/export
9. Answer mode advanced support
10. QR code import/export later

## 30. v1 out of scope

Do not implement in v1 unless explicitly chosen later:

- VPN/TUN whole-device tunneling
- TURN support
- browser extension
- multiple visual themes
- cloud account system
- remote config sync
- arbitrary remote host/port selection from Android offer side
- hidden background service without persistent notification
- cellular/metered use without explicit opt-in

## 31. Acceptance checklist

The Android UI/UX is acceptable when:

- [ ] First-run wizard can create/import identity.
- [ ] User can configure MQTT broker.
- [ ] User can configure remote peer.
- [ ] User can configure at least one offer-side forward.
- [ ] User can start tunnel as a ForegroundService.
- [ ] User can switch to browser and use `http://127.0.0.1:<port>`.
- [ ] Persistent notification is shown while tunnel is running.
- [ ] User can stop tunnel from notification or app.
- [ ] Cellular/metered use is blocked by default.
- [ ] User receives strong warning before enabling cellular/metered use.
- [ ] Network changes are handled.
- [ ] Home screen clearly shows connected/paused/error states.
- [ ] Logs are useful and redacted.
- [ ] `p2pctl`/desktop compatibility is preserved at protocol/config level.
- [ ] No secrets are displayed in normal UI.
- [ ] Accessibility basics are implemented.
