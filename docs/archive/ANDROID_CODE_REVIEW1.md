# ANDROID_CODE_REVIEW1.md

# Android WebRTC Tunnel Code Review 1 — Scaffold Hardening Review

## 1. Review scope

This review covers the Android app implementation added to the `rust_webrtc_tunnel` / future `webrtc_tunnel` repository.

The reviewed implementation appears to be the first Android scaffold for the tunnel app. It includes:

- Android project under `android/`
- Kotlin + Jetpack Compose + Material 3 UI
- Gradle Kotlin DSL
- `TunnelForegroundService`
- `RustTunnelBridge`
- `crates/p2p-mobile`
- Android Keystore identity encryption scaffolding
- DataStore preferences
- network policy scaffolding
- notification scaffolding
- basic UI screens
- Android docs and build scripts

This review compares the implementation against `ANDROID_WEBRTC_TUNNEL_TODO.md` and the Android product requirements previously defined.

## 2. Important review limitation

This review is based on static source inspection.

The review environment did not have a working Rust toolchain and could not run:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

The review environment also could not complete Gradle validation because the Gradle wrapper attempted to download Gradle and network access failed.

Therefore, any build/test claims in this review are based only on the repository contents and any validation notes found in the repo. They are not independently verified by this review environment.

## 3. High-level verdict

The Android implementation is a good first scaffold, but it is not yet a working Android WebRTC Tunnel app.

The project has the right broad shape:

```text
Android app
  -> ForegroundService
  -> Kotlin Rust bridge
  -> p2p-mobile JNI/FFI crate
  -> existing Rust tunnel crates
```

However, multiple critical pieces are currently incomplete, stubbed, or not wired together:

1. Rust `p2p-mobile` status/log JSON does not match the Kotlin models.
2. Android encrypted identity storage is not integrated with Rust daemon startup.
3. Generated config still uses desktop paths and likely does not work on Android.
4. Network policy exists but is not enforced by the service.
5. The setup wizard and much of the UI are placeholders.
6. FFI/JNI code still has panic/null-handle safety risks.
7. Native Rust library build is not reliably wired into normal Android build flow.
8. ForegroundService lifecycle is fragile and appears connected-test failure-prone.
9. The TODO checklist is over-marked as complete despite significant unfinished work.

This should stay on the Android feature branch. Do not merge to `master` until the P0/P1 issues below are fixed and validated.

## 4. What is good

### 4.1 Good repository direction

Keeping Android in the same repo as the Rust tunnel is the correct choice. Android must remain protocol-compatible with desktop Rust, and a monorepo makes it easier to keep protocol, crypto, tunnel frame, config, and test changes synchronized.

### 4.2 Good Android project baseline

The Android project uses the right modern stack:

- Kotlin
- Jetpack Compose
- Material 3
- Gradle Kotlin DSL
- version catalog
- DataStore
- ForegroundService
- notification scaffolding

The SDK choices are also reasonable:

```text
minSdk = 26
targetSdk = 35
compileSdk = 35
```

### 4.3 Good `p2p-mobile` direction

Adding `crates/p2p-mobile` is the correct integration point. Kotlin should not know Rust protocol internals; it should call a small mobile bridge API.

The crate exposes the expected broad operations:

- create runtime
- destroy runtime
- start offer
- start answer
- stop
- status JSON
- recent logs JSON
- validate config
- free returned strings

That is the correct shape.

### 4.4 Good use of ForegroundService concept

The Android tunnel should run as a ForegroundService so other apps can use localhost ports while the browser/SSH client/API client is foregrounded. The implementation has `TunnelForegroundService`, notification actions, and service command concepts.

### 4.5 Good security direction for identity storage

The implementation has Android Keystore encryption scaffolding and stores:

```text
identity.enc
identity.pub
```

That matches the security design:

```text
private identity encrypted at rest
public identity plaintext allowed
app-private internal storage
Android Keystore non-exportable key
```

### 4.6 Good testability seams

The code includes seams such as:

- `TunnelNativeBridge`
- `FakeTunnelBridge`
- `IdentityCrypto`
- `NetworkPolicyManager`
- repository/viewmodel abstractions

Those make tests and Compose previews easier.

## 5. Critical issues

## P0 — Rust/Kotlin status and log JSON schemas do not match

### Problem

`p2p-mobile` returns status JSON shaped roughly like:

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

Kotlin attempts to decode into `TunnelStatus`, which expects a richer UI model such as:

```kotlin
TunnelStatus(
    serviceState,
    mode,
    localPeerId,
    remotePeerId,
    mqttConnected,
    activeSessionCount,
    sessionCapacity,
    uptimeSeconds,
    networkStatus,
    forwards,
    lastError
)
```

Logs have the same kind of mismatch. Rust emits fields like:

```json
{
  "unix_ms": 123,
  "level": "info",
  "message": "..."
}
```

Kotlin expects camelCase fields such as:

```kotlin
unixMs
```

### Impact

The app may start the Rust runtime but fail to display actual running state. `TunnelRepository.refreshStatus()` may silently retain stale UI status or show `Stopped` even when Rust says the runtime is running.

This breaks the Home screen, notifications, logs, and debugging.

### Required fix

Choose one approach and apply consistently:

#### Option A — Make Rust emit the Kotlin UI schema

`p2p-mobile` should emit JSON that exactly matches Kotlin models.

#### Option B — Add Kotlin DTOs matching Rust, then map to UI models

Recommended:

```kotlin
@Serializable
data class NativeRuntimeStatusDto(
    val state: String,
    val mode: String?,
    val config_path: String?,
    val last_error: String?,
    val started_at_unix_ms: Long?,
    val active: Boolean
)
```

Then map:

```kotlin
NativeRuntimeStatusDto -> TunnelStatus
```

Same for logs.

### Acceptance

- Real Rust status JSON decodes successfully.
- Real Rust log JSON decodes successfully.
- Decode failures surface as a visible error, not silent stale state.
- Unit tests cover Rust sample JSON -> Kotlin UI status/log models.

---

## P0 — Encrypted Android identity storage is not wired into Rust startup

### Problem

Android `IdentityRepository` stores the private identity as:

```text
filesDir/identity.enc
```

But Rust `p2p-mobile` / daemon startup still loads the identity from:

```rust
IdentityFile::from_file(&config.paths.identity)
```

That expects a plaintext desktop identity file.

Also, the generated default config still points to desktop-style paths such as:

```toml
identity = "~/.config/p2ptunnel/identity"
authorized_keys = "~/.config/p2ptunnel/authorized_keys"
```

### Impact

The app can encrypt identity storage, but the Rust runtime does not use it. The actual tunnel startup path either:

- cannot find the identity,
- expects a plaintext identity file,
- or requires manual workaround outside the UI.

This means the Android identity storage requirement is not complete.

### Required fix

Implement one of these designs:

#### Preferred design — pass decrypted identity in memory

1. Android decrypts `identity.enc` using Android Keystore.
2. Kotlin passes decrypted identity bytes/string to `p2p-mobile`.
3. `p2p-mobile` starts the daemon using already-loaded identity material.
4. No plaintext identity file is written to disk.

#### Acceptable temporary design — short-lived app-private temp file

1. Android decrypts `identity.enc`.
2. Writes plaintext identity to an app-private temp file.
3. Starts Rust runtime with that temp file path.
4. Deletes the temp file immediately after Rust loads the identity.
5. Documents this as a temporary compatibility bridge.

#### Longer-term design — Rust mobile config object

Refactor `p2p-mobile` so it accepts:

```rust
MobileStartConfig {
    config_toml,
    identity_bytes,
    authorized_keys_bytes,
    android_preferences
}
```

### Acceptance

- `identity.enc` is the only private identity at rest.
- No long-lived plaintext `identity` file is created.
- Rust tunnel startup works using the encrypted Android identity flow.
- Unit/integration tests prove no plaintext identity file remains after start.

---

## P0 — Android config generation uses desktop paths

### Problem

`ConfigRepository.defaultConfigTemplate()` uses desktop paths:

```toml
identity = "~/.config/p2ptunnel/identity"
authorized_keys = "~/.config/p2ptunnel/authorized_keys"
state_dir = "~/.local/state/p2ptunnel"
ca_file = "/etc/ssl/certs/ca-certificates.crt"
```

These are not Android app-private paths and may not exist.

### Impact

The app may generate a config that fails validation or cannot start the tunnel on Android.

### Required fix

Generate Android-specific app-private paths:

```text
filesDir/config.toml
filesDir/identity.enc
filesDir/identity.pub
filesDir/authorized_keys
filesDir/state/
cacheDir/runtime/
```

For TLS CA handling, do not assume:

```text
/etc/ssl/certs/ca-certificates.crt
```

on Android. Either:

1. omit `ca_file` and let the Rust TLS layer use a compatible root store if supported,
2. bundle/export a CA bundle intentionally,
3. or add a documented Android TLS strategy.

### Acceptance

- New Android config points only to app-private valid paths.
- Config validation succeeds with generated config once required user fields are filled.
- No default config points to `~/.config`, `~/.local`, or `/etc/ssl/certs`.

---

## P0 — Network policy is not enforced by ForegroundService

### Problem

`NetworkPolicyManager` exists, but `TunnelForegroundService` starts the tunnel without checking the policy. It does not enforce:

```text
allowMetered = false by default
pauseOnMetered = true
resumeOnUnmetered = true
unknown network fails safe
```

There is also no clearly wired network callback to pause/resume on changes.

### Impact

The app can use cellular/metered data despite the non-negotiable product rule that cellular/metered use must be blocked unless explicitly allowed.

### Required fix

The service must check network policy:

1. before starting Rust runtime,
2. before opening local listeners if possible,
3. before MQTT/WebRTC connection if possible,
4. on every network change.

If metered/cellular/unknown network is disallowed:

```text
do not start tunnel
or pause/stop running tunnel
show paused notification
show paused UI state
```

### Acceptance

- Cellular is blocked by default.
- Metered Wi-Fi is blocked by default.
- Unknown network is blocked by default.
- Explicit allow enables metered use.
- Switching from Wi-Fi to cellular pauses the tunnel.
- Switching back to unmetered Wi-Fi resumes if configured.
- Service tests cover enforcement.

---

## P0 — Setup wizard and core UI workflow are placeholders

### Problem

The app has UI shells, but many screens are placeholders:

- Setup wizard only lists steps.
- Mode selection is not functional.
- Identity step is not functional.
- MQTT broker step is not functional.
- Remote peer step is not functional.
- Forwards setup step is not functional.
- Network policy screen is mostly static.
- Review/start flow is not functional.
- Import/export flows are not implemented.
- Forwards add/edit/delete are not implemented.
- Forward Details is not implemented.

### Impact

The user cannot configure the app from the UI and use it end-to-end.

### Required fix

Implement the real offer-mode setup flow:

```text
Choose Mode
Identity
MQTT Broker
Remote Peer
Forwards
Network Policy
Review
Start Tunnel
```

For v1, answer mode may remain disabled/advanced, but offer mode must work.

### Acceptance

A user can configure an Android offer from the UI, start the tunnel, and open:

```text
http://127.0.0.1:<local_port>
```

in a browser to reach a desktop Rust answer-side service.

---

## P1 — FFI/JNI boundary is not safe enough

### Problem

Some FFI paths can panic or dereference invalid handles.

Examples to audit:

- `CString::new(...).expect(...)`
- `unsafe { &*handle }` without null checks
- native calls after `runtimeHandle = 0`
- panics crossing `extern "system"` JNI boundary

### Impact

The Android app may crash instead of showing structured errors.

### Required fix

1. Wrap all exported FFI functions in panic-catching boundaries.
2. Validate null handles before dereferencing.
3. Convert invalid strings/NUL input to structured errors.
4. Make stop/destroy idempotent.
5. Ensure Kotlin never calls native methods after destroy.
6. Surface native library load failures cleanly.

### Acceptance

- Null handle returns error, not UB/crash.
- Bad string input returns error, not panic.
- Missing `.so` produces visible error, not app crash.
- Unit tests cover invalid handles and native library unavailable behavior where possible.

---

## P1 — Native library build is not reliably integrated with Android build

### Problem

There is a `buildRustAndroid` task, but normal:

```bash
./gradlew assembleDebug
```

does not necessarily depend on it. The uploaded repo does not include built `.so` files.

### Impact

Developers can build an APK missing `libp2p_mobile.so`, leading to runtime crashes.

### Required fix

Choose one:

1. Wire `preBuild.dependsOn("buildRustAndroid")`.
2. Wire only debug/release variants appropriately.
3. Fail the Gradle build if native libraries are missing.
4. Clearly document a mandatory two-step build and make CI enforce it.

Recommended:

```text
assembleDebug depends on cargo-ndk build for local dev
```

### Acceptance

- A normal debug build includes native libraries.
- App startup fails gracefully if native library missing.
- CI builds native Rust before APK.

---

## P1 — ForegroundService lifecycle is fragile

### Problem

The service uses sticky lifecycle behavior, but null/restart intents may not call `startForeground()` promptly. Repo notes indicate an Android connected test failure:

```text
ForegroundServiceDidNotStartInTimeException
```

### Impact

The tunnel service may crash or fail Android foreground-service requirements.

### Required fix

1. Decide whether service should be `START_NOT_STICKY` or handle restarts.
2. If sticky, handle null intents and call `startForeground()` immediately with a safe status.
3. If not sticky, return `START_NOT_STICKY`.
4. Ensure every start path calls `startForeground()` within Android timing requirements.
5. Add tests for service start/null intent/stop behavior.

### Acceptance

- Connected foreground-service test passes.
- No `ForegroundServiceDidNotStartInTimeException`.
- Notification appears promptly.
- Stop action releases Rust runtime.

---

## P1 — Validation status is inaccurate

### Problem

The TODO checklist marks validation complete, but repo notes indicate failing Rust workspace tests and failing Android connected tests.

### Required fix

1. Uncheck or correct incomplete validation items.
2. Fix failing tests.
3. Add actual command output to `docs/memory.md` or validation notes.
4. Do not mark validation complete unless commands pass.

### Acceptance

All required validation commands pass or failures are explicitly documented and TODO items remain unchecked.

---

## 6. Additional issues

### 6.1 `System.loadLibrary()` failure is swallowed

`RustTunnelBridge` should not ignore native library load failure. Store load state and return structured errors.

### 6.2 `validateConfig()` JSON assumptions are unsafe

If Rust returns non-JSON error text, Kotlin decode can fail. Use a stable validation JSON schema.

### 6.3 Logs redaction must be enforced end-to-end

Docs say logs redact secrets, but diagnostics/export flows are not fully implemented. Add tests for redaction.

### 6.4 Android Keystore payload should be versioned

`identity.enc` should include a versioned envelope format:

```text
magic/version
algorithm
nonce
ciphertext
tag
```

or equivalent. This allows future migration.

### 6.5 Answer mode should remain explicitly advanced

Do not let placeholder answer mode imply complete support.

## 7. Recommended fix order

1. Fix Rust/Kotlin JSON schema compatibility.
2. Fix Android config paths and identity integration.
3. Enforce network policy in ForegroundService.
4. Fix FFI/JNI safety.
5. Fix foreground-service lifecycle.
6. Wire native build into Gradle.
7. Implement real setup wizard offer-mode flow.
8. Implement forwards add/edit/delete and forward details.
9. Implement network warning dialog and settings.
10. Implement import/export and diagnostics.
11. Fix validation failures.
12. Correct TODO completion status.

## 8. Bottom line

This is a promising Android scaffold, but not yet a working Android WebRTC Tunnel.

The biggest gap is not Compose UI polish. The biggest gap is integration correctness:

```text
Rust status/log JSON <-> Kotlin models
Android encrypted identity <-> Rust daemon startup
Android config paths <-> Rust config validation
Network policy <-> ForegroundService lifecycle
Native .so build <-> APK build
```

Fix those before adding more UI polish.
