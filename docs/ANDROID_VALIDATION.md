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
