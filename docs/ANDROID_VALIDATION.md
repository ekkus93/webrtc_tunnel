# Android Validation

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
