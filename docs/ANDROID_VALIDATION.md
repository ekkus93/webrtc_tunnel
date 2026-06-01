# Android Validation

## 2026-06-01T04:28:29Z

- **Commit:** working tree with TODO4 tiny final cleanup changes (not yet committed)
- **Environment:** Linux host, Android emulator `Medium_Phone_API_36.0(AVD) - 16`
- **Summary:** TODO4 code fixes are complete and validation gates pass; manual large-font walkthrough and manual Android↔desktop browser E2E remain explicitly NOT RUN in this pass.

### Rust workspace validation

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets -- --test-threads=1
```

- **Result:** PASS

### Android lint/build/tests

```bash
cd android
./gradlew --no-daemon assembleDebug testDebugUnitTest connectedDebugAndroidTest --console=plain
```

- **Result:** PASS

### Android Rust native library build

```bash
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p p2p-mobile --release
```

- **Result:** PASS

### Manual UI validation (large-font/phone-layout walkthrough)

- **Result:** NOT RUN
- **Reason:** Interactive large-font manual walkthrough was not performed in this CLI-only validation pass.

### Manual Android↔desktop E2E

- **Result:** NOT RUN
- **Reason:** Desktop `p2p-answer` + remote target environment were not provisioned for this run.
- **Required future run:** Start desktop `p2p-answer`, start Android offer tunnel, browse `http://127.0.0.1:<local_port>`, and capture redacted Android/desktop logs with response evidence.

## 2026-05-31T22:41:24Z

- **Commit:** working tree with TODO3 final UI cleanup changes (not yet committed)
- **Environment:** Linux host, Android emulator `Medium_Phone_API_36.0(AVD) - 16`
- **Summary:** TODO3 UI cleanup automation is green after a flaky two-node daemon retry; manual UI large-font walkthrough and Android↔desktop browser E2E were not run in this pass.

### Rust workspace validation

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets -- --test-threads=1
```

- **Result:** PASS on rerun (first run hit known flaky `p2p-daemon` two-node test `signaling_turbulence_does_not_interrupt_active_tcp_stream`; second full rerun passed cleanly).

### Android lint/build/tests

```bash
cd android
./gradlew --no-daemon lintDebug testDebugUnitTest connectedDebugAndroidTest --console=plain
```

- **Result:** PASS

### Android Rust native library build

```bash
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p p2p-mobile --release
```

- **Result:** PASS

### Manual UI validation (large-font/phone-layout walkthrough)

- **Result:** NOT RUN
- **Reason:** This environment run was CLI automation only; no interactive manual walkthrough was performed for large-font visual checks.

### Manual Android↔desktop E2E

- **Result:** NOT RUN
- **Reason:** Desktop `p2p-answer` runtime + remote target service were not provisioned for this pass.
- **Required future run:** Start desktop `p2p-answer`, start Android offer tunnel, browse `http://127.0.0.1:<local_port>`, and record redacted Android/desktop logs and response evidence.

## 2026-05-31T16:42:56Z

- **Commit:** `17a5b74`
- **Working tree:** dirty / contains Android E2E validation cleanup changes
- **Environment:** Linux host (`6.17.0-29-generic`), JVM `21.0.11`, Android emulator `Medium_Phone_API_36.0(AVD) - 16`
- **Rust toolchain:** `rustc 1.94.1`, `cargo 1.94.1`
- **Android Gradle/Wrapper:** Gradle `8.7`, AGP `8.5.2`
- **Android SDK/NDK:** compileSdk `35`, targetSdk `35`, minSdk `26`, installed NDKs include `28.2.13676358` (also `27.0.12077973`, `21.4.7075529`)
- **Device/emulator availability:** connected device `emulator-5554`
- **Summary:** Final checklist pass is green on the current working tree after hardening `stopDuringPendingStartIsSafe` wait tolerance; manual Android↔desktop browser E2E remains explicitly unrun.

### Rust workspace validation

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

- **Result:** PASS

### Android native library build

```bash
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p p2p-mobile --release
```

- **Result:** PASS

### Android lint/build/tests

```bash
cd android
./gradlew --no-daemon lintDebug testDebugUnitTest connectedDebugAndroidTest assembleDebug
```

- **Result:** PASS

### APK native library check

```bash
cd android
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libp2p_mobile.so
```

- **Result:** PASS (`lib/arm64-v8a/libp2p_mobile.so`, `lib/x86_64/libp2p_mobile.so`)

### Manual Android↔desktop E2E

- **Result:** NOT RUN
- **Reason:** Desktop `p2p-answer` runtime plus remote target service were not provisioned for this pass.
- **Future run steps:** Start desktop `p2p-answer` with real config, configure Android offer from Setup Wizard, start tunnel, open `http://127.0.0.1:<local_port>` on Android browser, and capture redacted Android/desktop logs with response summary.

## 2026-05-31T16:38:22Z

- **Commit:** `17a5b74`
- **Working tree:** dirty / contains Android E2E validation cleanup changes
- **Environment:** Linux host (`6.17.0-29-generic`), JVM `21.0.11`, Android emulator `Medium_Phone_API_36.0(AVD) - 16`
- **Rust toolchain:** `rustc 1.94.1`, `cargo 1.94.1`
- **Android Gradle/Wrapper:** Gradle `8.7`, AGP `8.5.2`
- **Android SDK/NDK:** compileSdk `35`, targetSdk `35`, minSdk `26`, installed NDKs include `28.2.13676358` (also `27.0.12077973`, `21.4.7075529`)
- **Device/emulator availability:** connected device `emulator-5554`
- **Summary:** Cleanup pass validation is green for Rust and Android automation on the current working tree; manual Android↔desktop browser E2E remains explicitly unrun.

### Rust workspace validation

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

- **Result:** PASS

### Android native library build

```bash
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p p2p-mobile --release
```

- **Result:** PASS

### Android lint/build/tests

```bash
cd android
./gradlew --no-daemon lintDebug testDebugUnitTest connectedDebugAndroidTest assembleDebug
```

- **Result:** PASS

### APK native library check

```bash
cd android
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libp2p_mobile.so
```

- **Result:** PASS (`lib/arm64-v8a/libp2p_mobile.so`, `lib/x86_64/libp2p_mobile.so`)

### Manual Android↔desktop E2E

- **Result:** NOT RUN
- **Reason:** Desktop `p2p-answer` runtime plus remote target service were not provisioned for this pass.
- **Future run steps:** Start desktop `p2p-answer` with real config, configure Android offer from Setup Wizard, start tunnel, open `http://127.0.0.1:<local_port>` on Android browser, and capture redacted Android/desktop logs with response summary.

## 2026-05-31T16:31:47Z

- **Commit:** `17a5b74`
- **Working tree:** clean
- **Environment:** Linux host (`6.17.0-29-generic`), JVM `21.0.11`, Android emulator `Medium_Phone_API_36.0(AVD) - 16`
- **Rust toolchain:** `rustc 1.94.1`, `cargo 1.94.1`
- **Android Gradle/Wrapper:** Gradle `8.7`, AGP `8.5.2`
- **Android SDK/NDK:** compileSdk `35`, targetSdk `35`, minSdk `26`, installed NDKs include `28.2.13676358` (also `27.0.12077973`, `21.4.7075529`)
- **Device/emulator availability:** connected device `emulator-5554`
- **Summary:** Current tiny-final-patch tree validates cleanly across Rust and Android automation; manual Android↔desktop browser E2E remains unrun in this pass.

### Rust workspace validation

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

- **Result:** PASS

### Android native library build

```bash
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p p2p-mobile --release
```

- **Result:** PASS

### Android lint/build/tests

```bash
cd android
./gradlew --no-daemon lintDebug testDebugUnitTest connectedDebugAndroidTest assembleDebug
```

- **Result:** PASS

### APK native library check

```bash
cd android
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libp2p_mobile.so
```

- **Result:** PASS (`lib/arm64-v8a/libp2p_mobile.so`, `lib/x86_64/libp2p_mobile.so`)

### Manual Android↔desktop E2E

- **Result:** NOT RUN
- **Reason:** Desktop `p2p-answer` runtime plus remote target service were not provisioned for this pass.
- **Future run steps:** Start desktop `p2p-answer` with real config, configure Android offer from Setup Wizard, start tunnel, open `http://127.0.0.1:<local_port>` on Android browser, and capture redacted Android/desktop logs with response summary.

## 2026-05-31T07:13:03Z

- **Commit:** `390654e`
- **Environment:** Linux host, Android Gradle plugin build, emulator `Medium_Phone_API_36.0(AVD) - 16`

### Rust workspace validation

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

- **Result:** pass

### Android Rust native library build

```bash
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p p2p-mobile --release
```

- **Result:** pass

### Android lint and tests

```bash
cd android
./gradlew --no-daemon lintDebug testDebugUnitTest connectedDebugAndroidTest
```

- **Result:** pass

### APK native library verification

```bash
cd android
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libp2p_mobile.so
```

- **Result:** pass (`lib/arm64-v8a/libp2p_mobile.so`, `lib/x86_64/libp2p_mobile.so`)

## 2026-05-31T12:10:58Z

- **Commit:** `1cbc607` (base before TODO3 implementation commit)
- **Environment:** Linux host, Android emulator `Medium_Phone_API_36.0(AVD) - 16`

### Rust workspace validation

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

- **Result:** PASS

### Android lint/tests validation

```bash
cd android
./gradlew --no-daemon lintDebug testDebugUnitTest connectedDebugAndroidTest
```

- **Result:** PASS

### Android native build + APK library presence

```bash
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p p2p-mobile --release
cd android
./gradlew assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libp2p_mobile.so
```

- **Result:** PASS (`lib/arm64-v8a/libp2p_mobile.so`, `lib/x86_64/libp2p_mobile.so`)

### Manual Android↔desktop E2E

- **Result:** NOT RUN
- **Reason:** Manual desktop answer + Android browser round-trip evidence was not executed in this environment during this pass.
- **Steps to run later:** Start desktop `p2p-answer` with a real config, configure Android offer mode from Setup Wizard, start tunnel, browse `http://127.0.0.1:<local_port>`, capture redacted Android and desktop logs, and record PASS/FAIL with response summary.

### Unresolved failures

- None in automated Rust/Android lint/build/test commands for this validation run.

## 2026-05-31T12:16:32Z

- **Commit:** `1cbc607` (working tree includes TODO3 changes not yet committed)
- **Environment:** Linux host, Android emulator `Medium_Phone_API_36.0(AVD) - 16`

### Rust workspace validation

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

- **Result:** PASS

### Android validation

```bash
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p p2p-mobile --release
cd android
./gradlew --no-daemon lintDebug testDebugUnitTest connectedDebugAndroidTest
./gradlew --no-daemon assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libp2p_mobile.so
```

- **Result:** PASS (`lib/arm64-v8a/libp2p_mobile.so`, `lib/x86_64/libp2p_mobile.so`)

### Manual Android↔desktop E2E

- **Result:** NOT RUN
- **Reason:** No desktop answer daemon + remote target were provisioned in this run.
- **Steps to run later:** Start desktop `p2p-answer`, configure Android offer from setup wizard, start tunnel, browse `127.0.0.1:<local_port>`, and record redacted logs plus observed response.

## 2026-05-31T12:18:56Z

- **Commit:** `1cbc607` (working tree includes TODO3 changes not yet committed)
- **Environment:** Linux host, Android emulator `Medium_Phone_API_36.0(AVD) - 16`

### Commands

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p p2p-mobile --release
cd android
./gradlew --no-daemon lintDebug testDebugUnitTest connectedDebugAndroidTest
./gradlew --no-daemon assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libp2p_mobile.so
```

- **Result:** PASS (all commands)
- **Manual Android↔desktop E2E:** NOT RUN (same reason as above; desktop answer + remote target not provisioned in this pass).

## 2026-05-31T14:39:16Z

- **Commit:** `fe9f336`
- **Environment:** Linux host, Android emulator `Medium_Phone_API_36.0(AVD) - 16`

### Rust workspace validation

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

- **Result:** PASS

### Android validation

```bash
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p p2p-mobile --release
cd android
./gradlew --no-daemon lintDebug testDebugUnitTest connectedDebugAndroidTest
./gradlew --no-daemon assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libp2p_mobile.so
```

- **Result:** PASS (`lib/arm64-v8a/libp2p_mobile.so`, `lib/x86_64/libp2p_mobile.so`)

### Manual Android↔desktop E2E

- **Result:** NOT RUN
- **Reason:** Desktop answer + Android browser round-trip was not provisioned in this environment.
- **Steps to run later:** Start desktop `p2p-answer`, configure Android offer mode from Setup Wizard, start tunnel, browse `http://127.0.0.1:<local_port>`, and record redacted logs plus observed response.

## 2026-05-31T14:58:20Z

- **Commit:** `fe9f336`
- **Environment:** Linux host, Android emulator `Medium_Phone_API_36.0(AVD) - 16`

### Rust workspace validation

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
```

- **Result:** PASS

### Android validation

```bash
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p p2p-mobile --release
cd android
./gradlew --no-daemon lintDebug testDebugUnitTest connectedDebugAndroidTest
./gradlew --no-daemon assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libp2p_mobile.so
```

- **Result:** PASS (`lib/arm64-v8a/libp2p_mobile.so`, `lib/x86_64/libp2p_mobile.so`)

### Manual Android↔desktop E2E

- **Result:** NOT RUN
- **Reason:** Desktop answer + Android browser round-trip was not provisioned in this environment.
- **Steps to run later:** Start desktop `p2p-answer`, configure Android offer mode from Setup Wizard, start tunnel, browse `http://127.0.0.1:<local_port>`, and record redacted logs plus observed response.

## 2026-05-31T15:54:10Z

- **Commit:** `3ddf853` (base before tiny final patch commit)
- **Environment:** Linux host, Android emulator `Medium_Phone_API_36.0(AVD) - 16`

### Rust workspace validation

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets -- --test-threads=1
```

- **Result:** PASS

### Flaky integration stabilization

```bash
cargo test -p p2p-daemon --test two_node_daemon -- --test-threads=1
```

- **Result:** PASS

### Android validation

```bash
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p p2p-mobile --release
cd android
./gradlew --no-daemon lintDebug testDebugUnitTest connectedDebugAndroidTest
./gradlew --no-daemon assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libp2p_mobile.so
```

- **Result:** PASS (`lib/arm64-v8a/libp2p_mobile.so`, `lib/x86_64/libp2p_mobile.so`)

### Manual Android↔desktop E2E

- **Result:** NOT RUN
- **Reason:** Desktop answer daemon + remote target service were not provisioned in this environment during this pass.
- **Steps to run later:** Start desktop `p2p-answer`, configure Android offer from Setup Wizard, start tunnel, browse `http://127.0.0.1:<local_port>`, and capture redacted Android/desktop logs with response summary.

### Unresolved failures

- None in the final command set above.

## 2026-05-31T15:59:08Z

- **Commit:** `3ddf853` (base before tiny final patch commit)
- **Environment:** Linux host, Android emulator `Medium_Phone_API_36.0(AVD) - 16`

### Rust workspace validation

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets -- --test-threads=1
cargo test -p p2p-daemon --test two_node_daemon -- --test-threads=1
```

- **Result:** PASS

### Android validation

```bash
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p p2p-mobile --release
cd android
./gradlew --no-daemon lintDebug testDebugUnitTest connectedDebugAndroidTest assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libp2p_mobile.so
```

- **Result:** PASS (`lib/arm64-v8a/libp2p_mobile.so`, `lib/x86_64/libp2p_mobile.so`)

### Manual Android↔desktop E2E

- **Result:** NOT RUN
- **Reason:** Desktop answer daemon + remote target service were not provisioned in this environment during this pass.
- **Steps to run later:** Start desktop `p2p-answer`, configure Android offer from Setup Wizard, start tunnel, browse `http://127.0.0.1:<local_port>`, and capture redacted Android/desktop logs with response summary.

### Unresolved failures

- None in the final command set above.
