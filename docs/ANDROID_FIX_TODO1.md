# ANDROID_FIX_TODO1.md

# Android WebRTC Tunnel Fix TODO 1 — Make the Scaffold Functional

## 1. Goal

Turn the current Android scaffold into a working Android offer-mode WebRTC Tunnel app.

This is a hardening/fix pass, not a redesign.

The target product remains:

```text
Android app in same repo
Kotlin + Jetpack Compose + Material 3
ForegroundService owns tunnel runtime
Kotlin calls shared Rust through JNI
Android offer mode connects to desktop Rust answer
Browser/other apps use 127.0.0.1:<port>
cellular/metered blocked by default
private identity encrypted at rest using Android Keystore
protocol compatible with desktop Rust
```

## 2. Non-negotiable rules

- Do not merge this work to `master` until validation passes.
- Keep Android work on the Android feature branch.
- Do not change MQTT signaling wire format.
- Do not change tunnel frame format.
- Do not change desktop Rust protocol semantics.
- Do not add TURN.
- Do not add VPN/TUN mode.
- Do not add arbitrary remote host/port selection from Android offer side.
- Do not run the tunnel as a hidden background service.
- Do not allow cellular/metered data unless explicitly enabled by the user.
- Do not store private identity plaintext at rest.
- Do not log private keys, MQTT passwords, SDP, ICE candidates, decrypted payloads, or forwarded data.
- Bind local forwards to `127.0.0.1` by default.
- Do not mark TODO items complete unless they are implemented and tested.

---

# Phase 0 — Correct checklist and validation honesty

## 0.1 Correct TODO completion status

Audit `ANDROID_WEBRTC_TUNNEL_TODO.md`.

Uncheck or annotate any items that are not truly complete, especially:

- Android offer can connect to desktop Rust answer,
- browser can use localhost forwarded port from actual UI/config flow,
- private identity encrypted at rest and used by runtime,
- network policy enforced by service,
- setup wizard implemented,
- import/export implemented,
- validation commands passing.

## 0.2 Record current validation state

Run or document inability to run:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
cd android && ./gradlew assembleDebug
cd android && ./gradlew testDebugUnitTest
```

If any command fails, paste the failing command and summary into `docs/memory.md` or a new validation note.

## 0.3 Fix known failing tests before claiming completion

Repo notes indicated:

- Rust workspace tests failing daemon integration tests,
- Android connected test failing with `ForegroundServiceDidNotStartInTimeException`.

Fix or explicitly track these as incomplete.

---

# Phase 1 — Fix Rust/Kotlin JSON schema compatibility

## 1.1 Audit native status JSON

Inspect `crates/p2p-mobile` status output.

Document its exact JSON schema.

Current likely shape:

```json
{
  "state": "running",
  "mode": "offer",
  "config_path": "...",
  "last_error": null,
  "started_at_unix_ms": 123,
  "active": true
}
```

## 1.2 Audit Kotlin status models

Inspect Kotlin models:

```text
TunnelStatus
ServiceState
TunnelMode
ForwardStatus
NetworkStatus
TunnelError
```

Identify every mismatch with Rust status JSON.

## 1.3 Choose schema strategy

Use one of these approaches.

### Preferred: DTO mapping

Add Kotlin DTOs matching Rust native JSON exactly:

```kotlin
@Serializable
data class NativeRuntimeStatusDto(
    val state: String,
    val mode: String? = null,
    val config_path: String? = null,
    val last_error: String? = null,
    val started_at_unix_ms: Long? = null,
    val active: Boolean = false
)
```

Map DTOs into UI models:

```kotlin
NativeRuntimeStatusDto.toTunnelStatus(...)
```

### Alternative: change Rust JSON

Make `p2p-mobile` emit JSON matching Kotlin `TunnelStatus`.

Do this only if it does not pollute Rust with Android UI concerns.

## 1.4 Fix log JSON compatibility

Add DTO for native logs:

```kotlin
@Serializable
data class NativeLogEventDto(
    val unix_ms: Long,
    val level: String,
    val message: String
)
```

Map to Kotlin `LogEvent`.

## 1.5 Surface decode failures

Do not silently swallow status/log decode failures.

If native JSON cannot decode:

- update UI status to error,
- log a redacted error,
- show actionable message in Logs or Home screen.

## 1.6 Tests

Add unit tests:

- native running status JSON decodes,
- native stopped status JSON decodes,
- native error status JSON decodes,
- native log JSON decodes,
- malformed status JSON produces visible error state,
- repository refresh reflects real native running state.

## 1.7 Acceptance

- Home screen reflects real Rust runtime state.
- Logs screen shows real Rust log events.
- No silent stale status after decode failure.

---

# Phase 2 — Fix Android config generation

## 2.1 Audit current default config

Inspect `ConfigRepository.defaultConfigTemplate()`.

Remove desktop paths:

```text
~/.config/p2ptunnel/identity
~/.config/p2ptunnel/authorized_keys
~/.local/state/p2ptunnel
/etc/ssl/certs/ca-certificates.crt
```

## 2.2 Generate Android app-private paths

Use app-private storage:

```text
filesDir/config.toml
filesDir/identity.enc
filesDir/identity.pub
filesDir/authorized_keys
filesDir/state/
cacheDir/runtime/
```

Do not put plaintext private identity in the default config.

## 2.3 Resolve identity path strategy

Because Rust daemon currently expects a plaintext identity path, choose and implement one strategy:

### Preferred strategy

Add `p2p-mobile` API that accepts decrypted identity bytes/material separately from `config.toml`.

### Temporary compatibility strategy

Decrypt `identity.enc` to a short-lived app-private temp file at tunnel start.

Rules for temp file:

- path under `cacheDir` or another app-private runtime directory,
- mode private,
- delete immediately after Rust loads it,
- delete again on stop/error,
- never include in diagnostics,
- never persist across app restarts.

## 2.4 Handle authorized_keys

Create and maintain:

```text
filesDir/authorized_keys
```

Ensure remote peer public identity import writes here if required by the Rust config model.

## 2.5 TLS CA behavior

Do not assume Android has:

```text
/etc/ssl/certs/ca-certificates.crt
```

Choose one:

- omit `ca_file` if Rust TLS stack can use native/root store,
- bundle a CA bundle intentionally,
- allow user-imported CA file,
- document current limitation.

## 2.6 Config validation

Ensure generated Android config validates after required user fields are filled.

## 2.7 Tests

Add tests:

- default config uses app-private paths,
- config contains no `~/.config`,
- config contains no `~/.local`,
- config contains no hardcoded `/etc/ssl/certs` unless intentionally supported,
- config validation handles Android paths,
- missing identity produces clear setup error.

---

# Phase 3 — Wire encrypted identity into runtime startup

## 3.1 Audit IdentityRepository

Confirm it can:

- import identity,
- encrypt to `identity.enc`,
- decrypt when needed,
- write `identity.pub`,
- avoid plaintext at rest.

## 3.2 Implement start-time identity flow

When starting tunnel:

1. Ensure `identity.enc` exists.
2. Decrypt identity using Android Keystore.
3. Provide identity to `p2p-mobile` using the chosen strategy.
4. Start tunnel.
5. Ensure no plaintext identity remains at rest.

## 3.3 Avoid plaintext file where practical

Preferred:

```text
Kotlin passes decrypted identity bytes to Rust JNI.
Rust parses from bytes.
No plaintext identity file.
```

If temp file is used, document as temporary and delete aggressively.

## 3.4 Add private export warning

Implement UI flow before export:

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

## 3.5 Add import identity flow

Implement:

```text
select file
validate identity
encrypt to identity.enc
write identity.pub
discard plaintext
```

## 3.6 Tests

Add tests:

- import writes `identity.enc`,
- no plaintext `identity` file remains,
- start flow reads/decrypts `identity.enc`,
- stop/error cleanup removes temp identity if temp file strategy used,
- export warning must be confirmed before export,
- diagnostics do not include identity bytes.

## 3.7 Acceptance

- Private identity is encrypted at rest and actually used by the running tunnel.
- User can import/generate identity and start tunnel from UI.
- No long-lived plaintext private identity file exists.

---

# Phase 4 — Enforce network policy in ForegroundService

## 4.1 Audit NetworkPolicyManager

Confirm it classifies:

```text
Unmetered Wi-Fi
Metered Wi-Fi
Cellular
No network
Unknown network
```

Unknown must fail safe.

## 4.2 Register network callback

Use `ConnectivityManager.NetworkCallback` or equivalent to observe network changes.

Expose changes as `Flow<NetworkStatus>`.

## 4.3 Gate tunnel startup

Before `TunnelForegroundService` starts Rust runtime:

1. Load DataStore preferences.
2. Read current network state.
3. If metered/cellular/unknown and `allowMetered = false`, do not start.
4. Set service state to paused/blocked.
5. Show paused notification.
6. Show Home UI paused state.

## 4.4 Pause on network changes

If tunnel is running and network becomes disallowed:

1. stop or pause Rust runtime,
2. close local listeners if runtime supports it,
3. update status,
4. show paused notification.

## 4.5 Resume on unmetered return

If `resumeOnUnmetered = true` and tunnel was paused only because of network policy:

1. resume/start tunnel when unmetered network returns,
2. update notification and UI.

## 4.6 Cellular warning dialog

Implement required dialog before enabling metered use:

```text
Cellular / Metered Data Warning

WebRTC Tunnel can use a large amount of data. Browser traffic, API calls, SSH sessions, downloads, streaming, llama-server usage, or other forwarded traffic may consume your mobile data plan quickly.

Your carrier may charge overage fees, throttle your connection, or suspend service depending on your plan.

The app developer is not responsible for carrier charges, throttling, overage fees, or data-plan exhaustion caused by your use of this feature.

Only enable this if you understand the risk and accept responsibility for any data usage or charges.

[Cancel]
[I understand — allow cellular/metered tunnels]
```

## 4.7 Tests

Add tests:

- startup blocked on cellular by default,
- startup blocked on metered Wi-Fi by default,
- startup blocked on unknown network by default,
- explicit allow permits metered,
- running tunnel pauses on switch to cellular,
- paused tunnel resumes on unmetered return when configured,
- warning required before enabling metered.

## 4.8 Acceptance

- The tunnel cannot use cellular/metered data by default.
- Network policy is enforced by service, not only displayed by UI.

---

# Phase 5 — Fix ForegroundService lifecycle

## 5.1 Decide sticky behavior

Choose one:

### Recommended for v1

Use:

```kotlin
START_NOT_STICKY
```

The tunnel is user-controlled and should not silently restart without full policy/config checks.

### Alternative

If using `START_STICKY`, handle null intents safely and call `startForeground()` immediately.

## 5.2 Ensure prompt foreground notification

Every service start path must call `startForeground()` within Android timing requirements.

Do this before long Rust startup operations.

## 5.3 Handle null intents

If `onStartCommand()` receives null intent:

- either stop self safely,
- or start foreground with safe paused/restoring notification,
- then re-run policy/config checks.

Do not leave service alive without foreground notification.

## 5.4 Stop cleanup

On stop:

- stop Rust runtime idempotently,
- unregister network callbacks,
- cancel coroutines,
- update repository/service state,
- stop foreground notification,
- stop self.

## 5.5 Tests

Fix/add connected or Robolectric tests:

- service start posts notification promptly,
- null intent does not crash,
- stop action stops runtime,
- no `ForegroundServiceDidNotStartInTimeException`,
- service can be started, stopped, and started again.

---

# Phase 6 — Fix FFI/JNI safety

## 6.1 Audit all exported Rust FFI functions

Check:

- null handles,
- invalid strings,
- CString creation,
- panics,
- memory ownership,
- double free,
- use after destroy.

## 6.2 Add panic boundaries

No panic may cross FFI.

Wrap exported functions with a helper that catches panics and returns a structured error.

## 6.3 Null handle checks

Use a helper for all handle-based calls:

```rust
fn with_controller<T>(handle: *mut MobileController, f: impl FnOnce(&MobileController) -> T) -> Result<T, MobileError>
```

Do not use:

```rust
unsafe { &*handle }
```

without checking.

## 6.4 CString / NUL handling

Replace:

```rust
CString::new(value).expect(...)
```

with error-returning logic.

## 6.5 Kotlin native library load

Do not swallow `System.loadLibrary()` failure.

Store load result:

```kotlin
private val nativeAvailable: Boolean
private val nativeLoadError: Throwable?
```

If unavailable, return structured errors and show UI diagnostic.

## 6.6 Destroy semantics

After destroy:

- Kotlin must not call native handle methods,
- Rust must tolerate double destroy if feasible,
- status after destroy should return clean error at Kotlin layer.

## 6.7 Tests

Add tests where practical:

- null handle returns error,
- invalid string input returns error,
- stop before start safe,
- double stop safe,
- missing native library produces visible error,
- no panic across FFI for invalid config path.

---

# Phase 7 — Wire Rust Android build into Gradle

## 7.1 Audit current buildRustAndroid task

Confirm:

- uses `cargo ndk`,
- targets `arm64-v8a` and `x86_64`,
- outputs to correct `jniLibs` directories,
- fails clearly if cargo-ndk is missing.

## 7.2 Wire into Gradle lifecycle

Recommended:

```text
preBuild dependsOn buildRustAndroid
```

or:

```text
assembleDebug dependsOn buildRustAndroid
```

If this is too slow, add a check task that fails if native libs are missing.

## 7.3 Native library presence check

Add Gradle check:

```text
android/app/src/main/jniLibs/arm64-v8a/libp2p_mobile.so exists
android/app/src/main/jniLibs/x86_64/libp2p_mobile.so exists
```

before packaging.

## 7.4 Document build flow

Update Android build docs:

```bash
cargo install cargo-ndk
cd android
./gradlew assembleDebug
```

If a separate Rust build step remains required, make it explicit and ensure CI runs it.

## 7.5 Tests/validation

Run:

```bash
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p p2p-mobile --release
cd android && ./gradlew assembleDebug
```

Confirm APK contains `libp2p_mobile.so`.

---

# Phase 8 — Implement real setup wizard

## 8.1 Replace placeholder wizard

Implement functional steps:

```text
Choose Mode
Identity
MQTT Broker
Remote Peer
Forwards
Network Policy
Review
```

## 8.2 Choose Mode step

- Offer mode enabled and default.
- Answer mode disabled or marked Advanced if not complete.

## 8.3 Identity step

Implement:

- generate identity if Rust API exists,
- import identity file,
- show public identity,
- copy/share public identity,
- store private identity encrypted.

## 8.4 MQTT Broker step

Fields:

```text
Broker host
Port
TLS enabled
Username optional
Password optional
Topic prefix optional
```

Validation:

- host required,
- port valid,
- secrets not logged.

## 8.5 Remote Peer step

Fields:

```text
Remote peer ID
Remote public identity
```

Actions:

```text
Paste
Import file
```

Write/import remote public identity to appropriate app-private authorized peer file.

## 8.6 Forwards step

Support add/edit/remove forwards.

Fields:

```text
Name
Local host default 127.0.0.1
Local port
Remote forward_id
Enabled
```

Validation:

- no duplicate enabled local ports,
- no remote target host/port,
- non-localhost requires advanced warning.

## 8.7 Network Policy step

Show actual current network state.

Allow user to keep default blocked metered policy.

If user enables metered, show warning dialog.

## 8.8 Review step

Show final summary:

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
Save
Start Tunnel
Back
```

## 8.9 Tests

Add ViewModel/unit tests:

- cannot proceed with invalid step,
- generated config contains wizard fields,
- forwards validation works,
- metered warning required,
- review summary correct.

---

# Phase 9 — Implement forwards management

## 9.1 Forwards list

Implement:

- list configured forwards,
- listening/stopped/error/disabled/paused states,
- add button,
- edit/delete/disable actions.

## 9.2 Forward details

Implement:

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

## 9.3 Port validation

Validate:

- port 1-65535,
- no duplicate enabled port,
- localhost default,
- advanced warning before non-localhost bind.

## 9.4 Tests

Add tests for:

- add forward,
- edit forward,
- delete forward,
- duplicate port rejected,
- remote host/port cannot be entered,
- copy URL generated correctly.

---

# Phase 10 — Implement import/export and diagnostics

## 10.1 Config import/export

Implement:

- import `config.toml`,
- export `config.toml`,
- validate imported config,
- keep Android preferences separate.

## 10.2 Public identity import/export

Implement:

- copy public identity,
- share public identity,
- import remote public identity,
- paste remote public identity.

## 10.3 Private identity import/export

Implement:

- import private identity -> validate -> encrypt to `identity.enc`,
- export private identity only after warning,
- no private identity in logs.

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

Never include secrets.

## 10.5 Redaction tests

Test diagnostics do not contain:

- private identity,
- MQTT password,
- tokens,
- SDP,
- ICE candidates,
- decrypted payloads,
- raw forwarded data.

---

# Phase 11 — Fix logs and diagnostics

## 11.1 Logs screen

Implement useful log view:

- All / Info / Warn / Error / Debug filters,
- Copy logs,
- Clear logs,
- Export diagnostics.

## 11.2 Redaction

Enforce redaction in both Rust mobile logs and Kotlin diagnostics.

## 11.3 Native logs

Ensure `p2p-mobile` recent logs are decoded and shown.

## 11.4 Tests

Add tests:

- native logs show in Logs screen,
- filter works,
- redacted export does not include secrets.

---

# Phase 12 — Protocol compatibility test

## 12.1 Manual end-to-end test

Run:

1. Start desktop Rust `p2p-answer`.
2. Configure Android app from UI in offer mode.
3. Add forward:
   ```text
   127.0.0.1:8080 -> llama
   ```
4. Start Android tunnel.
5. Open Android browser:
   ```text
   http://127.0.0.1:8080
   ```
6. Confirm remote service responds.

## 12.2 Document exact steps/results

Add to `docs/memory.md` or `docs/ANDROID_VALIDATION.md`:

- desktop command,
- Android config summary,
- network type,
- result,
- errors if any.

## 12.3 Preserve protocol invariants

Verify Android uses same:

- signaling envelope,
- encrypted inner message schema,
- MQTT topic layout,
- identity/public key format,
- authorized key semantics,
- tunnel frame format,
- `OpenPayload { forward_id }`,
- per-forward authorization.

---

# Phase 13 — Validation

## 13.1 Rust validation

Run:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

## 13.2 Android validation

Run:

```bash
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

If connected tests exist and an emulator/device is available:

```bash
./gradlew connectedDebugAndroidTest
```

## 13.3 Android Rust library validation

Run:

```bash
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o android/app/src/main/jniLibs \
  build -p p2p-mobile --release
```

or equivalent Gradle task.

## 13.4 Do not mark complete unless passing

If any command fails:

- leave acceptance item unchecked,
- document failure,
- fix before merge.

---

# Phase 14 — Acceptance checklist

## 14.1 P0 integration

- [x] Rust/Kotlin status JSON compatibility fixed.
- [x] Rust/Kotlin log JSON compatibility fixed.
- [x] Native status decode failures surface visibly.
- [x] Android config uses app-private valid paths.
- [x] `identity.enc` is used by actual tunnel startup.
- [x] No plaintext private identity remains at rest.
- [x] Network policy gates tunnel startup.
- [x] Network policy pauses tunnel on metered/cellular transition.
- [ ] Setup wizard is functional for offer mode.

## 14.2 FFI/service

- [x] No panics cross FFI.
- [x] Null handles do not cause UB/crash.
- [x] Native library load failure is surfaced.
- [x] ForegroundService starts notification promptly.
- [x] Connected foreground-service test passes.
- [x] Stop action releases runtime.

## 14.3 Build

- [x] Native Rust library is built for `arm64-v8a`.
- [x] Native Rust library is built for `x86_64`.
- [x] APK includes `libp2p_mobile.so`.
- [x] `assembleDebug` cannot silently produce unusable APK.

## 14.4 UI

- [x] Home screen shows real runtime status.
- [ ] Setup wizard creates valid offer config.
- [ ] Forwards add/edit/delete works.
- [ ] Forward details works.
- [ ] Network policy UI and warning dialog work.
- [x] Logs show native logs.
- [x] Settings are functional.
- [ ] Import/export works.
- [x] Error states are actionable.

## 14.5 Security

- [x] Private identity encrypted at rest with Android Keystore.
- [ ] Private export requires explicit warning.
- [ ] Diagnostics redact secrets.
- [x] Logs redact secrets.
- [x] Cellular/metered data blocked by default.

## 14.6 Compatibility

- [ ] Android offer connects to desktop Rust answer.
- [ ] Android browser reaches remote service via `127.0.0.1:<port>`.
- [x] Protocol wire formats unchanged.
- [x] Desktop Rust tests still pass.

## 14.7 Validation

- [x] `cargo fmt --check` passes.
- [x] `cargo clippy --workspace --all-targets --all-features -- -D warnings` passes.
- [x] `cargo test --workspace --all-targets` passes.
- [x] `cargo ndk ... build -p p2p-mobile --release` passes.
- [x] `./gradlew assembleDebug` passes.
- [x] `./gradlew testDebugUnitTest` passes.
- [x] connected Android tests pass if present.

---

# Suggested implementation order

1. Correct TODO/checklist honesty.
2. Fix JSON schema compatibility.
3. Fix Android config paths.
4. Wire encrypted identity into startup.
5. Enforce network policy in service.
6. Fix FFI/JNI safety.
7. Fix ForegroundService lifecycle.
8. Wire Rust Android library build into Gradle.
9. Implement functional setup wizard.
10. Implement forwards management.
11. Implement import/export and diagnostics.
12. Fix logs/redaction.
13. Run end-to-end desktop answer + Android offer test.
14. Run full validation.

Do not spend time on UI polish before the native/runtime/config/network/security integration is correct.
