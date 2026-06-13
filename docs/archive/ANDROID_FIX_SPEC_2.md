# Android WebRTC Tunnel Fix Spec 2

## 1. Purpose

This document is the second Android hardening/fix specification for the `webrtc_tunnel` / `rust_webrtc_tunnel` Android app.

The previous Android fix pass moved the app much closer to a real Android offer-mode WebRTC Tunnel client, but static review found several remaining blockers. The most important problem is that the Android UI and service now appear to be wired to the native Rust runtime, but the generated Android configuration and encrypted identity startup path are still not proven to be accepted by the Rust runtime.

This spec is intentionally implementation-focused. It is not a redesign. The goal is to make the current Android architecture actually functional, secure, and testable.

## 2. Target product

The target product remains:

```text
Android app in same repo
Kotlin + Jetpack Compose + Material 3
ForegroundService owns tunnel runtime
Kotlin calls shared Rust through JNI
Android offer mode connects to desktop Rust answer
Browser/other Android apps use 127.0.0.1:<port>
cellular/metered blocked by default
private identity encrypted at rest using Android Keystore
protocol compatible with desktop Rust
```

## 3. Non-negotiable rules

These rules remain mandatory:

- Do not merge this work to `master` until validation passes.
- Keep Android work on the Android feature branch unless the user explicitly says otherwise.
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
- Do not mark TODO items complete unless implemented and tested.

## 4. Current state summary

The current Android app has useful architecture and several good improvements:

- Android dependencies are organized through `AppDependencies`.
- `TunnelForegroundService` owns tunnel startup/stop.
- `TunnelRepository` bridges UI state to native runtime state.
- Native status/log DTO mapping exists.
- Android Keystore-based encrypted identity storage exists.
- `identity.enc` is decrypted at service start and passed to native JNI.
- `buildRustAndroid` / `verifyRustJniLibs` Gradle tasks exist.
- Setup, forwards, logs, import/export, diagnostics, and settings screens exist in some form.

However, the app must not be considered complete yet because several acceptance items were checked prematurely.

## 5. Confirmed blockers from code review

### 5.1 Android-generated config is likely incompatible with Rust validation

The Android config generator emits app-private paths and omits desktop paths. That part is good.

However, the generated `[broker.tls]` section omits `ca_file`, while the Rust config type still contains a required `broker.tls.ca_file` field and Rust validation requires it for `mqtts://` brokers.

This likely causes one of these failures:

1. TOML deserialization failure because the required field is missing.
2. Rust config validation failure because `broker.tls.ca_file` is empty.
3. Rust config validation failure because the referenced CA path does not exist.

The Android fix must choose and implement a real TLS CA strategy.

### 5.2 `startOfferWithIdentity()` still likely requires a plaintext identity file

The Android service decrypts `identity.enc` and calls native `startOffer(configPath, identityBytes)`. That is the right direction.

However, the native Rust controller still loads config through the standard `AppConfig::load_from_file()` path. That standard load path still validates `paths.identity` as a required existing file before the supplied identity bytes are used.

Android stores the private identity at:

```text
filesDir/identity.enc
```

but generates `paths.identity` as an app-private runtime plaintext path such as:

```text
filesDir/runtime/identity.toml
```

The app does not write that plaintext file. Therefore the native runtime may fail validation before using the decrypted identity bytes.

The fix must make the Rust mobile startup path validate config with an identity override, or implement the documented short-lived temp-file compatibility path.

Preferred solution:

```text
No plaintext private identity file.
Kotlin passes decrypted identity bytes to Rust.
Rust parses private identity from bytes.
Rust validates config without requiring paths.identity when identity bytes are provided.
```

Temporary acceptable solution:

```text
Decrypt to short-lived app-private temp file at startup.
Set paths.identity or equivalent to that temp path.
Start native runtime.
Delete the temp file immediately after Rust has loaded it.
Delete again on stop/error.
Never include temp identity path or contents in logs/diagnostics.
```

The preferred solution should be used unless it would require disproportionate Rust refactoring.

### 5.3 Private identity import is not sufficiently validated

Current import logic only checks that the file is non-empty and tries to infer a public identity from a line containing `peer_id`.

That is not enough.

Import must validate:

- file is a real `p2ptunnel-identity-v1` private identity;
- required private/public key fields exist;
- private/public key pairs match, if Rust has the capability to verify this;
- the peer ID/public identity rendered from the private identity matches Rust's canonical public identity format.

Do not derive `identity.pub` by copying a line containing `peer_id`.

### 5.4 Forwards have two sources of truth

The Forwards UI writes to a JSON file such as `forwards.json`, but tunnel startup uses `config.toml`.

If the user edits forwards after initial setup, those changes may not be reflected in the actual runtime config.

There must be exactly one authoritative source of truth, or every forward mutation must regenerate the active config used by Rust.

Preferred approach:

```text
Keep Android UI state in a structured repository.
Every setup/forward/config change regenerates config.toml atomically.
Tunnel start always uses the regenerated config.toml.
```

Alternative approach:

```text
Make config.toml the source of truth and parse/edit it directly.
```

The preferred structured repository approach is easier to test.

### 5.5 Config import is not transactional

Import currently writes the provided config to the real config path and then validates it. If validation fails, the app may be left with a broken config.

Import must validate a temporary copy first and only replace the active config if validation succeeds.

### 5.6 Network policy UI and service logic are inconsistent

The service gates startup on `allowMetered`. That is correct.

The Network Policy UI/status layer should show whether the tunnel is allowed under the current preferences. If the user explicitly enables metered/cellular use, the UI should not continue to display the network as blocked merely because the physical transport is metered.

Separate concepts must be modeled clearly:

```text
networkType: UnmeteredWifi | MeteredWifi | Cellular | NoNetwork | Unknown
allowedByDefault: true only for UnmeteredWifi
allowedByUserPolicy: derived from networkType + allowMetered
reasonIfBlocked: cellular, metered, unknown, no network, etc.
```

Unknown networks must fail safe unless the user explicitly chooses otherwise.

### 5.7 Setup wizard is still not fully functional

The setup wizard has a reasonable skeleton but does not fully implement the requested product flow.

It must become a real setup flow for offer mode:

```text
Choose Mode
Identity
MQTT Broker
Remote Peer
Forwards
Network Policy
Review
```

The Review step must support:

```text
Save
Start Tunnel
Back
```

The wizard must generate a config that validates against Rust.

### 5.8 Forwards management is incomplete

The Forwards screen must manage actual configured forwards and update the runtime config.

Required behavior:

- add/edit/delete/disable forward;
- local host defaults to `127.0.0.1`;
- local port validation;
- duplicate enabled local ports rejected;
- duplicate forward IDs rejected;
- remote target host/port not exposed on Android offer side;
- non-localhost bind requires advanced warning;
- display local URL;
- copy URL;
- open browser;
- test local port where feasible;
- show runtime/listening/paused/error/disabled status where feasible.

### 5.9 Diagnostics redaction is too narrow

Diagnostics and logs must redact:

- private identity material;
- `sign.private`;
- `kex.private`;
- MQTT password;
- password files;
- bearer tokens;
- API keys;
- SDP;
- ICE candidates;
- decrypted payloads;
- raw forwarded data;
- temporary plaintext identity paths if the temporary file strategy is used.

Redaction must be tested with realistic multiline examples.

### 5.10 Acceptance checklist was marked complete prematurely

The previous TODO checklist had all acceptance items checked. That is not acceptable. The checklist must be corrected and left unchecked until passing validation and end-to-end testing are documented.

## 6. Required architecture after this fix

### 6.1 Config architecture

The Android app must have a clear config flow:

```text
Setup/Settings/Forwards UI
        |
        v
Structured Android config state
        |
        v
Atomic config.toml renderer
        |
        v
Rust validation
        |
        v
TunnelForegroundService start
```

`config.toml` must be regenerated atomically whenever relevant user-facing config changes.

Atomic write pattern:

1. Render config to `config.toml.tmp`.
2. Validate temp config through Rust/mobile validation.
3. If valid, replace `config.toml`.
4. If invalid, leave existing `config.toml` unchanged and show an actionable error.

### 6.2 Identity architecture

Private identity must be encrypted at rest:

```text
filesDir/identity.enc
```

Public identity may be plaintext:

```text
filesDir/identity.pub
```

Remote authorized peers may be plaintext:

```text
filesDir/authorized_keys
```

The preferred runtime identity path is:

```text
identity.enc -> decrypt in memory -> JNI byte array -> Rust parses private identity bytes
```

Do not create a long-lived plaintext private identity file.

### 6.3 Network policy architecture

Network policy must be enforced by `TunnelForegroundService`, not just displayed by the UI.

Startup flow:

1. Service starts foreground notification promptly.
2. Service loads config and preferences.
3. Service reads current network status.
4. If disallowed, service remains foreground in paused/blocked state and does not start Rust.
5. If allowed, service starts Rust runtime.
6. Service observes network changes.
7. If network becomes disallowed, service stops/pauses Rust and closes listeners.
8. If unmetered returns and resume is enabled, service restarts Rust after rechecking config and identity.

### 6.4 Native JNI/FFI architecture

No panic may cross the FFI boundary.

All exported Rust FFI functions must:

- handle null pointers;
- handle invalid strings;
- avoid `expect()`/`unwrap()` on user-controlled data;
- return structured errors where feasible;
- update last error when returning failure;
- tolerate stop-before-start;
- tolerate double stop;
- make destroy semantics clear.

Kotlin must:

- surface `System.loadLibrary()` failures visibly;
- not call native methods after dispose/destroy;
- treat native unavailable as an actionable app error.

## 7. Security requirements

### 7.1 Private identity

- Private identity encrypted at rest using Android Keystore.
- No plaintext private identity file persists across app restarts.
- Private export requires explicit warning.
- Private import validates before storage.
- Diagnostics never include private identity material.
- Logs never include private identity material.

### 7.2 MQTT secrets

- Passwords must not be logged.
- Passwords must not appear in diagnostics.
- Prefer password files or encrypted preferences if password storage is needed.
- If stored in config for v1, the config export/diagnostics must redact it.

### 7.3 WebRTC/session data

- Do not log SDP.
- Do not log ICE candidates.
- Do not log decrypted payloads.
- Do not log forwarded data.
- Do not include these in diagnostics.

### 7.4 Network usage

- Cellular and metered networks are blocked by default.
- Enabling metered/cellular requires explicit warning and acceptance.
- Unknown networks fail safe by default.
- UI must clearly show blocked/paused state.

## 8. UI requirements

### 8.1 Home

Home screen must show:

- current service/runtime state;
- blocked/paused reason if blocked by network policy;
- actionable setup/config errors;
- current network type;
- whether current network is allowed by user policy;
- start/stop button with correct enabled/disabled state.

### 8.2 Setup wizard

Required steps:

1. Choose Mode
   - Offer mode enabled and default.
   - Answer mode disabled or clearly marked advanced/incomplete if not supported.

2. Identity
   - Generate identity if Rust API exists.
   - Import private identity.
   - Store private identity encrypted.
   - Display public identity.
   - Copy/share public identity.

3. MQTT Broker
   - Broker host.
   - Port.
   - TLS enabled.
   - CA strategy, if applicable.
   - Username optional.
   - Password optional.
   - Topic prefix optional, if supported by Rust config.
   - Validate required fields.

4. Remote Peer
   - Remote peer ID.
   - Remote public identity.
   - Paste/import remote public identity.
   - Write authorized peer file.

5. Forwards
   - Add/edit/delete/disable forwards.
   - Local host default `127.0.0.1`.
   - Local port.
   - Remote `forward_id`.
   - No arbitrary remote host/port selection.

6. Network Policy
   - Show actual current network state.
   - Metered/cellular disabled by default.
   - Warning dialog before enabling metered/cellular.

7. Review
   - Show mode, identity, remote peer, broker, network policy, forwards.
   - Save.
   - Start Tunnel.
   - Back.

### 8.3 Forwards screen

Must support:

- list forwards;
- add;
- edit;
- delete;
- disable/enable;
- copy local URL;
- open local URL in browser;
- display last error/status where available;
- reject duplicate enabled local ports;
- reject duplicate forward IDs.

### 8.4 Logs/Diagnostics

Must support:

- log level filters;
- copy visible logs;
- clear logs;
- export redacted diagnostics;
- show native logs;
- show decode errors visibly;
- never expose secrets.

## 9. Rust requirements

### 9.1 Mobile config validation

Add a Rust API suitable for Android-generated configs.

At minimum, add a validation path that can validate a config with in-memory identity material:

```rust
validate_config_with_identity_override(config_path, identity_toml_bytes)
```

or equivalent.

The validation must prove:

- Android-generated config deserializes.
- Required files exist or are intentionally bypassed for mobile identity override.
- MQTT TLS CA behavior is valid.
- authorized_keys exists and is readable.
- state/runtime directories are valid.
- forwards are valid.

### 9.2 TLS CA strategy

Choose one of these:

#### Option A: Rust supports default/native root store

If supported, make `broker.tls.ca_file` optional and allow Android config to omit it.

Requirements:

- update Rust config struct to use `Option<PathBuf>` where appropriate;
- update validation;
- update desktop behavior without breaking existing configs;
- document behavior.

#### Option B: Bundle an Android CA file

Requirements:

- add a CA bundle asset or generated app-private CA file;
- set `broker.tls.ca_file` to that actual path;
- prove file exists before validation;
- document update implications.

#### Option C: User-imported CA file only

Requirements:

- support TLS disabled or user-imported CA;
- UI must collect/import CA before allowing `mqtts://`;
- validation must provide an actionable error if missing.

Preferred: Option A if the Rust MQTT/TLS stack can support it safely.

### 9.3 Identity parsing/rendering

Rust should expose mobile-safe APIs if possible:

```text
generate_private_identity() -> private identity TOML bytes
render_public_identity(private identity TOML bytes) -> public identity string
validate_private_identity(private identity TOML bytes) -> ok/error
validate_public_identity(public identity string) -> ok/error
```

Use these APIs from Kotlin instead of ad hoc parsing.

## 10. Testing requirements

### 10.1 Unit tests

Add or update tests for:

- Android-generated default config has no desktop paths.
- Android-generated config deserializes and validates through Rust/mobile API.
- `mqtts://` config has a valid CA strategy.
- In-memory identity startup path does not require plaintext `paths.identity`.
- Private identity import rejects invalid input.
- Private identity import writes `identity.enc` and canonical `identity.pub`.
- No plaintext private identity remains at rest.
- Forward add/edit/delete regenerates active config.
- Duplicate enabled local ports are rejected.
- Duplicate forward IDs are rejected.
- Config import validates temp file first and rolls back on failure.
- Metered/cellular startup blocked by default.
- Enabling metered requires warning acceptance.
- Network status UI reflects `allowMetered`.
- Native status/log JSON decode works.
- Malformed native status/log JSON surfaces an error.
- Diagnostics redact realistic secrets.
- Missing native library surfaces actionable UI error.

### 10.2 Instrumentation / Robolectric tests

Add or update tests for:

- foreground service posts notification promptly;
- null intent does not crash or run hidden;
- stop action stops runtime;
- startup blocked on cellular/metered;
- running service pauses on disallowed network;
- resume on unmetered when enabled;
- setup wizard can produce and save a valid offer config;
- Start Tunnel from Review starts service or shows actionable blocked reason.

### 10.3 Rust tests

Add Rust tests for:

- mobile config validation with identity override;
- config validation does not require `paths.identity` when identity override is supplied;
- invalid identity override fails;
- missing authorized_keys creates actionable error or is created by Android before validation;
- TLS CA option behavior.

### 10.4 End-to-end manual validation

Document and run:

1. Build Rust Android library.
2. Install debug APK.
3. Start desktop Rust answer peer.
4. Configure Android app from UI in offer mode.
5. Add forward:
   ```text
   127.0.0.1:8080 -> llama
   ```
6. Start tunnel from Android.
7. Open Android browser:
   ```text
   http://127.0.0.1:8080
   ```
8. Confirm remote service responds.
9. Confirm logs/diagnostics redact secrets.
10. Confirm cellular/metered is blocked by default.

Record exact commands/results in:

```text
docs/ANDROID_VALIDATION.md
```

or

```text
docs/memory.md
```

## 11. Required validation commands

Run from repository root unless noted.

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

Android native build:

```bash
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o android/app/src/main/jniLibs \
  build -p p2p-mobile --release
```

Android build/tests:

```bash
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

If emulator/device is available:

```bash
cd android
./gradlew connectedDebugAndroidTest
```

Also verify APK contents:

```bash
unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep libp2p_mobile.so
```

Expected ABIs:

```text
lib/arm64-v8a/libp2p_mobile.so
lib/x86_64/libp2p_mobile.so
```

## 12. Acceptance criteria

Do not mark complete unless all of the following are true.

### 12.1 P0 runtime/config/security

- Android-generated `config.toml` validates through real Rust/mobile validation.
- TLS CA strategy is implemented and tested.
- `startOfferWithIdentity()` does not require long-lived plaintext `paths.identity`.
- `identity.enc` is decrypted and used by runtime startup.
- No plaintext private identity remains at rest.
- Private identity import is validated.
- Canonical public identity is generated/rendered from the private identity.
- Remote authorized key file is populated correctly.
- Android offer mode can start without config/identity validation failure.

### 12.2 Network/service

- ForegroundService starts notification promptly.
- ForegroundService owns runtime start/stop.
- Cellular/metered blocked by default.
- Unknown network blocked by default.
- Startup blocked before native runtime on disallowed networks.
- Running tunnel pauses/stops on transition to disallowed network.
- Resume on unmetered works when enabled.
- Stop action releases runtime and unregisters callbacks.

### 12.3 UI

- Setup wizard creates a valid offer config.
- Review step supports Save and Start Tunnel.
- Home shows real runtime status and actionable errors.
- Forwards add/edit/delete/disable updates active runtime config.
- Forward details support copy/open/test where feasible.
- Network Policy screen reflects user preferences.
- Import/export is functional and safe.
- Logs show native logs and decode failures.

### 12.4 Security

- Diagnostics redact private identity material.
- Diagnostics redact MQTT passwords/tokens.
- Diagnostics redact SDP and ICE candidates.
- Logs redact secrets.
- Private identity export requires explicit warning.
- Non-localhost bind requires advanced warning.

### 12.5 Compatibility

- Android offer connects to desktop Rust answer.
- Android browser reaches remote service via `127.0.0.1:<port>`.
- Protocol wire formats unchanged.
- Desktop Rust tests still pass.

### 12.6 Validation

- `cargo fmt --check` passes.
- `cargo clippy --workspace --all-targets --all-features -- -D warnings` passes.
- `cargo test --workspace --all-targets` passes.
- `cargo ndk ... build -p p2p-mobile --release` passes.
- `./gradlew assembleDebug` passes.
- `./gradlew testDebugUnitTest` passes.
- Connected Android tests pass if present and device/emulator is available.
- End-to-end validation is documented.

## 13. Implementation priority

Use this exact priority order:

1. Correct checklist honesty.
2. Fix Rust/Android config validation and TLS CA strategy.
3. Fix in-memory identity startup so no plaintext `paths.identity` is required.
4. Add Rust/mobile validation tests for Android-generated config.
5. Fix private identity import/public identity rendering.
6. Make forwards update the active runtime config.
7. Make config import transactional.
8. Make network policy state consistent between service and UI.
9. Finish setup wizard.
10. Finish forwards details.
11. Strengthen diagnostics/log redaction.
12. Harden FFI lifecycle and errors.
13. Run full validation.
14. Run Android offer ↔ desktop answer end-to-end test.
15. Only then update acceptance checklist.
