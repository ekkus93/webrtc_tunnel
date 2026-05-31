# Android WebRTC Tunnel Final Hardening Spec

## 1. Purpose

This is the final focused hardening pass for the Android app in `webrtc_tunnel`.

The previous Android fix passes substantially improved the app. The remaining work is not a redesign. It is a final correctness, lifecycle, security, Android UX, and validation-honesty pass.

The main goal is to remove the last blockers before final acceptance:

```text
Real Android offer ↔ desktop answer validation is either run and documented, or left unchecked.
ForegroundService lifecycle paths are nonblocking and safe.
Home/UI surfaces startup failures clearly.
Service logs/notifications are redacted.
Setup Wizard cannot create identity/peer mismatch.
Network Policy wizard claims are honest.
Raw config export is warned before export.
Android import/export uses Android-safe flows or is explicitly deferred.
p2p-mobile inherits workspace lint discipline or has narrow documented exceptions.
FFI destroy path is panic-safe.
```

## 2. Current status

The app is close, but not fully accepted.

Known good improvements from the previous pass:

- ForegroundService startup uses coroutine-based startup.
- Identity override startup is present.
- Rust/mobile config compatibility is much improved.
- Native logs are redacted before Logs screen display.
- generated TOML uses a string escaping helper.
- duplicate enabled `remoteForwardId` values are rejected.
- Network Policy core calculation is improved.
- fake bridge JSON now matches native DTO shape.
- native runtime normal/error completion updates status.
- validation docs now admit manual Android↔desktop E2E is not run.

Remaining issues:

- manual Android↔desktop E2E is still not run;
- checklist has some E2E-related contradictions;
- native stop/pause/onDestroy paths may still run synchronously on the service main thread;
- Home UI may remain stale after startup failure;
- service error logs/notifications are not consistently redacted;
- Setup Wizard local peer ID can diverge from generated/imported identity;
- wizard Network Policy controls/warnings are still not fully implemented;
- raw config export warns only after export;
- import/export UX uses raw file paths instead of Android Storage Access Framework/share flows;
- `p2p-mobile` does not fully inherit workspace lint policy;
- `p2ptunnel_destroy_runtime()` is not fully panic-wrapped.

## 3. Non-goals

Do not add:

- TURN;
- VPN/TUN mode;
- arbitrary Android remote host/port selection;
- new signaling protocol;
- new tunnel frame format;
- desktop protocol changes;
- speculative UI polish unrelated to the final blockers.

Do not check off incomplete items to make the TODO look done.

## 4. Required product behavior

The final Android app must remain:

```text
Kotlin + Jetpack Compose + Material 3
ForegroundService owns tunnel runtime
Kotlin calls shared Rust through JNI
Android offer mode connects to desktop Rust answer
Browser/other Android apps use 127.0.0.1:<local_port>
cellular/metered blocked by default
Unknown network blocked always
private identity encrypted at rest with Android Keystore
protocol compatible with desktop Rust
```

## 5. Final hardening requirements

### 5.1 E2E validation honesty

Manual Android offer ↔ desktop Rust answer validation is required before final compatibility acceptance.

If it is not run, the project must say:

```text
MANUAL E2E: NOT RUN
Reason: <specific reason>
```

The following must remain unchecked until a real test is documented:

```text
Android offer connects to desktop Rust answer.
Android browser reaches remote service via 127.0.0.1:<port>.
Manual E2E validation complete.
```

No checklist should contain contradictory checked E2E claims.

### 5.2 Home/UI startup failure surfacing

If startup fails during any stage, Home/UI must show an actionable error state:

- network blocked;
- missing encrypted identity;
- identity decrypt failed;
- config validation failed;
- native start failed;
- native library unavailable;
- service startup cancelled;
- native runtime exited with error.

The repository or shared state must be updated on failure. It is not enough to update only an Android notification.

### 5.3 Service error redaction

Any service error shown in:

- Android logs;
- notification text;
- repository status;
- Home screen;
- Logs screen;
- diagnostics;

must pass through the same redaction layer used for logs/diagnostics.

### 5.4 ForegroundService nonblocking lifecycle

Startup is already mostly asynchronous. Finish the remaining lifecycle paths.

The following must not block the service main thread:

- pause action;
- stop action;
- onDestroy cleanup;
- native `repository.stop()`;
- any identity/config cleanup that touches disk;
- any native bridge call that might block.

If a path is intentionally synchronous, document why it is guaranteed nonblocking.

### 5.5 Setup Wizard identity/local peer safety

The wizard must not allow the final config local peer ID to diverge from the generated/imported identity.

Acceptable strategies:

1. Make local peer ID derived from identity and read-only.
2. Move local peer ID entry before identity generation/import and validate identity matches it.
3. Remove manual local peer ID editing entirely and infer it from canonical public/private identity.
4. If manual override is retained, block save/start when identity peer ID and configured local peer ID differ.

Preferred: derive local peer ID from identity and make it read-only after identity generation/import.

### 5.6 Wizard Network Policy honesty

Either implement real wizard controls or uncheck/document those items.

If implemented, wizard Network Policy step must show:

- current network type;
- metered/unmetered state;
- allowed/blocked result;
- blocked reason;
- `allowMetered` toggle;
- metered/cellular warning before enabling;
- `resumeOnUnmetered` option;
- Unknown network is always blocked.

If not implemented, the TODO must clearly say Network Policy controls are handled in Settings, not completed in the wizard.

### 5.7 Raw config export warning

Raw config export may contain sensitive operational details. It must require a warning before writing the file.

Required behavior:

```text
Raw Config Export Warning

This config may include broker addresses, usernames, password file paths,
peer IDs, local paths, and other operational details.

It must never include private identity material, but it may still be sensitive.

[Cancel]
[Export Raw Config]
```

Redacted diagnostics export can remain separate.

### 5.8 Android-safe import/export UX

Raw absolute file paths are acceptable only as a developer/debug fallback.

For production UX, use Android-safe mechanisms:

- Storage Access Framework `ACTION_OPEN_DOCUMENT` for import;
- Storage Access Framework `ACTION_CREATE_DOCUMENT` for export;
- share sheet for public identity and diagnostics;
- app-private export followed by share intent, where appropriate.

If SAF/share flows are deferred, mark the relevant TODO items unchecked and document the deferral.

### 5.9 `p2p-mobile` lint policy

`crates/p2p-mobile` must not silently bypass workspace Clippy lint discipline.

Preferred:

```toml
[lints]
workspace = true
```

If Rust’s lint table structure requires separate handling for `unsafe_code`, keep the workspace Clippy lints and add a narrow documented exception for Rust `unsafe_code` only.

Do not broadly suppress:

- `unwrap_used`;
- `todo`;
- `dbg_macro`;
- panic-prone FFI paths;
- undocumented unsafe blocks where workspace policy requires documentation.

### 5.10 FFI destroy panic boundary

`p2ptunnel_destroy_runtime()` must be panic-safe.

Required:

- null handle safe;
- double destroy not promised safe at raw pointer level unless actually supported;
- panic from stop/drop must not unwind across FFI;
- failures must be logged/stored where feasible;
- Kotlin `dispose()` must remain double-call safe.

### 5.11 Test Local Port honesty

`Test Local Port` is currently deferred. That is acceptable only if the checklist remains unchecked and docs explain the deferral.

Do not mark `Forward details support copy/open/test where feasible` complete unless either:

- Test Local Port is implemented and tested; or
- the item is rewritten to say Test Local Port is deferred.

## 6. Validation requirements

### 6.1 Rust validation

Run:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

### 6.2 Android build/test validation

Run:

```bash
cargo ndk   -t arm64-v8a   -t x86_64   -o android/app/src/main/jniLibs   build -p p2p-mobile --release

cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

If a device/emulator is available:

```bash
./gradlew connectedDebugAndroidTest
```

### 6.3 APK native library validation

Run:

```bash
unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep libp2p_mobile.so
```

Expected:

```text
lib/arm64-v8a/libp2p_mobile.so
lib/x86_64/libp2p_mobile.so
```

### 6.4 Manual E2E validation

Required before compatibility acceptance:

1. Start desktop Rust answer.
2. Configure Android offer from Android UI only.
3. Import/generate Android identity.
4. Import desktop answer public identity.
5. Configure MQTT broker.
6. Configure local forward, for example:
   ```text
   127.0.0.1:8080 -> llama
   ```
7. Start Android tunnel.
8. Open Android browser:
   ```text
   http://127.0.0.1:8080
   ```
9. Confirm remote service responds.
10. Document result in `docs/ANDROID_VALIDATION.md`.

## 7. Documentation requirements

Update:

```text
docs/ANDROID_VALIDATION.md
docs/ANDROID_BUILD.md
docs/ANDROID_SECURITY.md
```

`docs/ANDROID_VALIDATION.md` must distinguish:

```text
PASS
FAIL
NOT RUN
```

Do not imply that NOT RUN means PASS.

## 8. Final acceptance criteria

This final hardening pass is accepted only when:

- E2E claims are either proven or left unchecked;
- Home/UI shows startup failures;
- service error messages are redacted;
- pause/stop/destroy paths do not block service main thread;
- Setup Wizard cannot create local peer/identity mismatch;
- Network Policy wizard items are either implemented or honestly unchecked;
- raw config export requires pre-export warning;
- import/export Android UX is implemented or honestly deferred;
- `p2p-mobile` lint policy is explicit and disciplined;
- FFI destroy is panic-safe;
- final validation docs are current and honest.
