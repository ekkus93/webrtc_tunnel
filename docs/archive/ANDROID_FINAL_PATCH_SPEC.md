# Android WebRTC Tunnel Final Patch Spec

## 1. Purpose

This is the final focused patch spec for the Android app in `webrtc_tunnel`.

The app is close to acceptable. Previous passes addressed the major Android runtime/config/security blockers. This final patch should not redesign the app or start another broad implementation cycle. It should fix the remaining concrete issues found in the latest review.

## 2. Scope

This patch covers only:

1. `p2p-mobile` lint inheritance / lint discipline.
2. Panic-safe Rust FFI destroy path.
3. ForegroundService STOP-during-START lifecycle race.
4. stale synchronous `startAnswer()` path.
5. config import temp-file cleanup.
6. flaky `Thread.sleep()` tests.
7. Setup Wizard remote public identity placement/labeling.
8. Android↔desktop E2E validation status remaining honest.

## 3. Non-goals

Do not add or change:

- TURN;
- VPN/TUN mode;
- arbitrary Android remote host/port selection;
- MQTT signaling wire format;
- tunnel frame format;
- desktop Rust protocol semantics;
- major UI redesign;
- new networking features;
- new identity format;
- new config format beyond what is required for the fixes below.

## 4. Current known-good baseline

The latest Android app already appears to have:

- encrypted identity-at-rest path;
- Rust identity override startup path;
- Android-generated config compatible with Rust/mobile validation;
- optional/default TLS CA behavior;
- foreground-service-owned tunnel runtime;
- service error redaction for logs/notifications/status;
- Home/UI startup failure surfacing;
- safe TOML string escaping;
- duplicate remote forward ID validation;
- raw config export warning;
- SAF-style import/export/share flows;
- Test Local Port action;
- honest `NOT RUN` documentation for manual Android↔desktop E2E.

Do not regress these.

## 5. Remaining issues to fix

### 5.1 `p2p-mobile` lint discipline

`crates/p2p-mobile` is the JNI/FFI boundary. It must not silently bypass workspace lint discipline.

The root workspace has Clippy lint policy. The mobile crate currently has a local lint configuration that allows unsafe Rust, but it must also inherit the workspace Clippy policy or document the narrowest equivalent.

Required outcome:

- workspace Clippy lint policy applies to `p2p-mobile`;
- `unsafe_code` is allowed only because JNI/FFI requires it;
- the unsafe exception is documented;
- broad warning suppression is not introduced;
- `cargo clippy --workspace --all-targets --all-features -- -D warnings` passes.

Preferred implementation:

```toml
[lints]
workspace = true
```

If Cargo does not allow combining workspace lints with a crate-specific `unsafe_code` override in the desired way, use the narrowest equivalent configuration and document why.

### 5.2 Panic-safe FFI destroy path

`p2ptunnel_destroy_runtime()` must not allow Rust panics to unwind across FFI.

Required outcome:

- null handle remains safe;
- Kotlin double `dispose()` remains safe;
- raw double destroy is not promised safe unless actually implemented;
- `controller.stop()` or drop panic cannot unwind across FFI;
- panic is logged/stored where feasible;
- Kotlin calls after dispose fail locally with a clear error.

Implementation should use `std::panic::catch_unwind` or the existing FFI panic-boundary helper.

### 5.3 STOP-during-START lifecycle race

The service currently starts native runtime asynchronously, but STOP can still race with an in-flight native start.

The unsafe sequence to prevent:

```text
START begins native startup.
STOP is received and stops/cancels service state.
Native START returns success after STOP.
START coroutine publishes Running state after STOP.
```

Required outcome:

- duplicate START is serialized;
- STOP during pending START is deterministic;
- STOP wins over older in-flight START;
- stale START completion cannot publish Running after STOP;
- network-policy pause during START cannot publish stale Running;
- start-stop-start still works.

Acceptable designs:

1. lifecycle generation token;
2. single serialized actor/event loop;
3. mutex plus explicit desired-state checking;
4. equivalent deterministic lifecycle state machine.

A generation-token design is acceptable:

```text
generation increments on every START/STOP/PAUSE.
START captures generation.
Before publishing Running, START checks generation is still current and desired state is Running.
If stale, it stops native runtime and exits without publishing Running.
```

### 5.4 stale `startAnswer()` path

Android v1 is offer-mode focused. If answer mode is disabled, the stale synchronous `startAnswer()` path should not remain as a hidden footgun.

Required outcome, choose one:

- remove `startAnswer()` if unused; or
- convert it to the same async/lifecycle-safe service flow as offer mode; or
- keep answer mode explicitly disabled and ensure any attempted answer start immediately returns a redacted actionable error without native startup.

Do not leave a synchronous native start path that can block the service main thread.

### 5.5 Config import temp-file cleanup

Candidate config import writes a temp file. If validation fails, the temp file must still be deleted.

Required outcome:

- temp config file is deleted on success;
- temp config file is deleted on validation failure;
- temp config file is deleted on exception;
- validation failure does not replace active config;
- validation failure does not leave stale cache files.

Use `try/finally` or equivalent.

### 5.6 Replace `Thread.sleep()` in tests

Tests should not rely on fixed sleeps when deterministic coroutine/test synchronization is possible.

Required outcome:

- remove `Thread.sleep()` from relevant Android unit tests;
- use `runTest`, test dispatchers, `advanceUntilIdle`, polling with timeout, or a deterministic synchronization primitive;
- tests remain reliable and non-flaky.

### 5.7 Setup Wizard remote public identity placement / labeling

The latest code appears to validate remote public identity consistency, but the UI still places or labels remote public identity awkwardly.

Required outcome:

- remote public identity input belongs in the Remote Peer step; or
- the Identity step clearly separates local identity from remote peer identity with accurate labels;
- user cannot confuse local identity with remote peer identity;
- Review step clearly shows local peer and remote peer separately;
- existing local/remote peer mismatch validation remains in place.

This is a UX correctness cleanup, not a visual redesign.

### 5.8 E2E validation remains honest

Manual Android offer ↔ desktop answer E2E is still not complete unless actually run.

Required outcome:

- keep E2E compatibility unchecked until the test is run;
- preserve `NOT RUN` in `docs/ANDROID_VALIDATION.md` when not run;
- do not imply automated tests substitute for manual Android browser ↔ desktop answer validation;
- when E2E is run, document exact steps/results.

Manual E2E must include:

```text
desktop answer command
Android device/emulator
Android API level
Android app build
MQTT broker summary with secrets redacted
configured local forward
Android browser URL
remote service response
redacted Android logs
redacted desktop logs
PASS/FAIL result
```

## 6. Validation requirements

Run after patch:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

Build Android native library:

```bash
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o android/app/src/main/jniLibs \
  build -p p2p-mobile --release
```

Run Android build/tests:

```bash
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

If a device/emulator is available:

```bash
./gradlew connectedDebugAndroidTest
```

Verify APK native libraries:

```bash
unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep libp2p_mobile.so
```

Expected:

```text
lib/arm64-v8a/libp2p_mobile.so
lib/x86_64/libp2p_mobile.so
```

## 7. Documentation requirements

Update:

```text
docs/ANDROID_VALIDATION.md
docs/ANDROID_BUILD.md, if lint/build instructions changed
docs/ANDROID_SECURITY.md, if FFI/lint/security notes changed
```

Validation docs must distinguish:

```text
PASS
FAIL
NOT RUN
```

Do not mark unavailable validation as passing.

## 8. Final acceptance criteria

This final patch is accepted when:

- `p2p-mobile` has explicit workspace-compatible lint discipline;
- FFI destroy cannot unwind panic across FFI;
- STOP during START cannot publish stale Running state;
- answer-mode service path is removed, disabled safely, or async-safe;
- config import temp files are cleaned on all paths;
- tests no longer rely on `Thread.sleep()`;
- remote public identity UI labeling/placement is not confusing;
- E2E compatibility remains honestly unchecked unless actually run;
- full validation is run and documented, or unavailable parts are clearly marked `NOT RUN`.
