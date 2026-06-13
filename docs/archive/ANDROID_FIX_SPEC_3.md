# Android WebRTC Tunnel Fix Spec 3

## 1. Purpose

This is the third Android hardening/fix pass for `webrtc_tunnel`.

The previous pass substantially improved the Android app:

- Android-generated config is much closer to Rust compatibility.
- `broker.tls.ca_file` is no longer a hard Android blocker.
- encrypted `identity.enc` is now wired into native startup via identity override.
- Rust-backed identity helpers exist.
- forwards mutations regenerate active config.
- config import is transactional.
- Gradle has native Rust build integration.

However, the app is not fully accepted yet. Several items are still over-claimed, and several Android lifecycle/security/UI edges remain incomplete.

This spec defines the remaining work needed before the Android app can be considered ready.

## 2. Primary goal

Prove and harden the Android offer-mode app so that:

```text
Android app generates a valid Rust config.
Android app uses encrypted identity at rest.
ForegroundService owns runtime start/stop safely.
Network policy is enforced consistently by service and UI.
Logs and diagnostics are safe to display/share.
Android offer connects to desktop Rust answer.
Android browser reaches remote service through 127.0.0.1:<port>.
Validation results are documented honestly.
```

## 3. Non-goals

Do not expand scope into unrelated features.

Do not:

- add TURN;
- add VPN/TUN mode;
- add arbitrary Android remote host/port targeting;
- change MQTT signaling wire format;
- change tunnel frame format;
- change desktop Rust protocol semantics;
- silently allow cellular/metered data by default;
- persist plaintext private identity;
- hide validation failures by checking off incomplete TODO items;
- spend time on UI polish before runtime/lifecycle/security correctness.

## 4. Required operating model

The Android app must remain:

```text
Kotlin + Jetpack Compose + Material 3
ForegroundService owns tunnel runtime
Kotlin calls shared Rust through JNI
Android offer mode connects to desktop Rust answer
Browser/other Android apps use 127.0.0.1:<local_port>
cellular/metered blocked by default
private identity encrypted at rest with Android Keystore
protocol compatible with desktop Rust
```

## 5. Current known issues to fix

### 5.1 Checklist honesty / validation claims

`ANDROID_FIX_TODO_2.md` or its updated copy appears to over-check items. Some items may be implemented, but not all are proven.

Specifically, do not claim these are complete until directly validated and documented:

- Android offer connects to desktop Rust answer.
- Android browser reaches remote service through localhost.
- end-to-end validation is documented.
- setup wizard is truly complete.
- network policy UI and service behavior match.
- logs are redacted before display.
- foreground-service lifecycle is fully safe.
- JNI/FFI destroy/dispose handling is safe.
- final acceptance checklist is complete.

Any acceptance item must be backed by one of:

```text
a passing automated test name,
a documented validation command and result,
or a documented manual E2E test with exact environment and result.
```

### 5.2 Missing real E2E proof

The project must include a real Android offer ↔ desktop answer validation record.

The validation record must show:

- git commit;
- date;
- desktop command;
- desktop config summary;
- MQTT broker summary, with secrets redacted;
- Android device/emulator model/API level;
- Android network type;
- Android setup values;
- Android local URL tested;
- remote service tested;
- result/status/body summary;
- relevant redacted logs;
- whether validation was run or not run.

If E2E cannot be run locally, the docs must say:

```text
NOT RUN
Reason: <specific reason>
```

and the acceptance items must remain unchecked.

### 5.3 ForegroundService main-thread blocking

`TunnelForegroundService` must not perform long-running config/identity/native startup work synchronously on the service main thread.

Allowed on the main thread:

- create notification channel;
- call `startForeground()` promptly;
- parse small intent action;
- launch coroutine/work item.

Not allowed on the main thread:

- DataStore blocking reads via `runBlocking`;
- identity file read/decrypt;
- Rust config validation;
- Rust runtime startup;
- network callback registration if it can block;
- any I/O-heavy or native startup operation.

After `startForeground()` is called, the service should launch startup work on a service-owned coroutine scope using an I/O dispatcher or otherwise clearly background-safe execution.

### 5.4 Network policy inconsistency

Service and UI must use one shared network-policy calculation.

Required policy:

```text
Unmetered Wi-Fi: allowed
Metered Wi-Fi: allowed only if allowMetered = true
Cellular: allowed only if allowMetered = true
No network: blocked
Unknown: blocked always
```

`Unknown` must remain fail-safe even when metered/cellular is allowed.

If `pauseOnMetered` exists, it must have defined semantics. Either:

- implement it, or
- remove it from preferences/UI/tests/docs.

The UI must not show a network as blocked when service would allow it, or vice versa.

### 5.5 Logs are not safe until redacted before display

Diagnostics export redaction is not enough.

Native logs must be redacted before:

- display on Logs screen;
- copy logs action;
- diagnostics export;
- any sharing/export flow.

The same redaction policy should be used for all log and diagnostics surfaces.

Redaction targets include:

- private identity TOML fields;
- private key material;
- MQTT passwords;
- bearer/API tokens;
- SDP blobs;
- ICE candidates;
- decrypted payload markers;
- forwarded data markers;
- temp identity paths, if temp-file fallback exists;
- any URL-embedded credentials.

### 5.6 Generated TOML must be safe

Do not render TOML by raw string interpolation without escaping.

Any user-provided or imported field that appears in generated TOML must be serialized safely.

At minimum, escape:

- quotes;
- backslashes;
- newlines;
- carriage returns;
- tabs;
- other TOML-sensitive content.

Preferred implementation:

```text
Use a TOML serialization library or structured config serializer.
```

Acceptable implementation:

```text
A small, tested TOML string escaping helper used consistently by ConfigRepository.
```

### 5.7 Duplicate runtime forward IDs

Android must reject duplicate enabled `remoteForwardId` values before config rendering.

The Rust config uses the forward `id` as the remote forward ID. Therefore two Android forwards with different UI IDs but the same `remoteForwardId` create duplicate Rust forward IDs.

Reject with an actionable error before rendering:

```text
Duplicate remote forward ID: <id>
```

### 5.8 Setup wizard remains partial

The setup wizard must either implement or uncheck/document the following:

- Remote Peer step supports paste and file import of remote public identity.
- Remote Peer step validates public identity and writes `authorized_keys`.
- Network Policy step shows current network state and policy result.
- Network Policy step requires the metered/cellular warning before enabling metered.
- MQTT Broker step exposes supported TLS behavior clearly.
- MQTT Broker step exposes topic prefix if supported by config.
- Review step supports Back, Save, and Start Tunnel.
- Start Tunnel from Review performs save, validation, identity check, network check, and service start or actionable blocked error.
- Public identity can be copied and shared/exported.

### 5.9 Forwards UI remains partial

The Forwards screen must either implement or uncheck/document the following:

- runtime/listening/paused/error state where available;
- last error;
- Copy URL;
- Open Browser;
- Test Local Port, if feasible;
- enable/disable;
- edit;
- delete;
- config regeneration for every mutation;
- no stale `config.toml`.

If Test Local Port is not feasible in this pass, remove or uncheck that acceptance item and document why.

### 5.10 JNI/FFI hardening is incomplete

The JNI/FFI boundary must be robust around:

- null handles;
- invalid pointers;
- invalid strings;
- interior NUL;
- panics;
- destroy after destroy;
- stop before start;
- double stop;
- calls after Kotlin dispose;
- meaningful error strings.

`p2ptunnel_destroy_runtime()` must be panic-safe, or the reason for exception must be documented and narrowly justified.

Kotlin methods after `dispose()` should fail locally with a clear error instead of calling native with an invalid handle.

### 5.11 `p2p-mobile` lint policy

`crates/p2p-mobile` must have an explicit lint policy.

Preferred:

```toml
[lints]
workspace = true
```

If mobile FFI requires exceptions, they must be narrow, explicit, and documented in `Cargo.toml` or crate-level attributes.

Do not silently exempt the mobile crate from workspace linting.

### 5.12 Fake bridge/status DTO mismatch

Any fake or test bridge used through `TunnelRepository` must emit the same native status/log JSON schema expected by production code.

`FakeTunnelBridge.getStatusJson()` must emit `NativeRuntimeStatusDto`, not `TunnelStatus`, unless it is never used through repository decode paths.

### 5.13 Native runtime clean exit state

If the Rust mobile runtime task exits successfully, status must become stopped/inactive rather than remaining running forever.

On normal daemon completion:

```text
state = stopped
active = false
last_error = null
```

On error completion:

```text
state = error
active = false
last_error = <redacted actionable error>
```

## 6. Security requirements

### 6.1 Private identity

Required:

- private identity stored encrypted at rest in `identity.enc`;
- Android Keystore-protected key;
- no long-lived plaintext private identity;
- import validates before storage;
- canonical public identity rendered from private identity;
- private export requires explicit warning confirmation.

### 6.2 Logs and diagnostics

Required:

- redact before display/export;
- no private key material;
- no MQTT passwords;
- no bearer/API tokens;
- no SDP/ICE candidates;
- no decrypted payloads;
- no raw forwarded data;
- no temp private identity path if temp fallback exists.

### 6.3 Network policy

Required:

- cellular/metered blocked by default;
- unknown blocked always;
- enabling metered/cellular requires explicit warning;
- service enforces policy before native start;
- service pauses/stops tunnel on disallowed network transition.

## 7. Validation requirements

### 7.1 Rust

Run:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

### 7.2 Android native build

Run:

```bash
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o android/app/src/main/jniLibs \
  build -p p2p-mobile --release
```

### 7.3 Android build/tests

Run:

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

### 7.4 APK contents

Run:

```bash
unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep libp2p_mobile.so
```

Expected:

```text
lib/arm64-v8a/libp2p_mobile.so
lib/x86_64/libp2p_mobile.so
```

### 7.5 Manual E2E

Required before final acceptance:

1. Start desktop Rust answer.
2. Configure Android offer using Android UI only.
3. Import/generate Android identity.
4. Import desktop public identity.
5. Configure MQTT.
6. Configure local forward such as:
   ```text
   127.0.0.1:8080 -> llama
   ```
7. Start Android tunnel.
8. Open Android browser:
   ```text
   http://127.0.0.1:8080
   ```
9. Confirm remote service responds.
10. Document exact result in `docs/ANDROID_VALIDATION.md`.

## 8. Documentation requirements

Update or create:

```text
docs/ANDROID_VALIDATION.md
docs/ANDROID_BUILD.md
docs/ANDROID_SECURITY.md
```

`docs/ANDROID_VALIDATION.md` must be honest. If something was not run, write `NOT RUN` and keep acceptance unchecked.

## 9. Final acceptance criteria

The patch is accepted only when all are true:

- Android-generated config validates through real Rust/mobile validation.
- Android identity override startup works without long-lived plaintext private identity.
- ForegroundService starts promptly and moves blocking work off main thread.
- Network policy service/UI behavior is consistent.
- Logs and diagnostics are redacted before display/export.
- Generated TOML is safely serialized.
- Forwards mutations update active runtime config.
- Setup wizard can produce a complete offer-mode config.
- Android offer connects to desktop Rust answer.
- Android browser reaches remote service via localhost.
- Full validation commands pass or unavailable commands are honestly documented and left unchecked.
