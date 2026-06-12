# Android build guide

This repository includes an Android app in `android/` plus a Rust JNI bridge crate in `crates/p2p-mobile`.

## Prerequisites

- Android Studio (or Android SDK command line tools)
- Android SDK platform 35 and Build Tools
- Android NDK installed and discoverable by `cargo-ndk`
- Rust toolchain (`rustup`, `cargo`)
- `cargo-ndk`:

```bash
cargo install cargo-ndk
```

- Rust Android targets:

```bash
rustup target add aarch64-linux-android x86_64-linux-android
```

## Build Rust JNI libraries

From repository root:

```bash
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o android/app/src/main/jniLibs \
  build -p p2p-mobile --release
```

You can also run the Gradle helper task:

```bash
cd android
./gradlew buildRustAndroid
```

## Build Android app

```bash
cd android
./gradlew assembleDebug
```

## Run Android unit tests

```bash
cd android
./gradlew testDebugUnitTest
```

## Local Kotlin-only workflow (skip the Rust build)

By default every Gradle build verifies/builds the Rust JNI libraries (via
`preBuild` → `verifyRustJniLibs`), which requires `cargo-ndk`. For fast local
Kotlin lint/unit-test cycles you can skip that step:

```bash
cd android
./gradlew testDebugUnitTest -PskipRustBuild=true
./gradlew lintDebug -PskipRustBuild=true
```

`-PskipRustBuild=true` only affects the build/verify-before-everything step;
packaging tasks (`assembleDebug`/`packageDebug`/release) still run
`requireRustJniLibs`, so an APK/AAB is never produced without the native
libraries — they fail clearly if the `.so` files are missing. A normal
`./gradlew assembleDebug` (no flag) builds and verifies the native libs as before.

## Full validation commands used in this repo

From repository root:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-targets
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build -p p2p-mobile --release
```

From `android/`:

```bash
./gradlew lintDebug assembleDebug testDebugUnitTest
```

## Common failures

- `cargo-ndk: command not found`: install with `cargo install cargo-ndk`.
- Missing Android targets: run `rustup target add aarch64-linux-android x86_64-linux-android`.
- APK missing native libs: rerun `cargo ndk ... -o android/app/src/main/jniLibs ...` and then `./gradlew assembleDebug`.

## Install APK

With a connected device or emulator:

```bash
cd android
./gradlew installDebug
```
