# WebRTC Tunnel FIX9 Validation Request

**Purpose:** trigger full validation for the FIX9 implementation pass.

**Validation requested from SHA:** `29ac4d4b1550663495bc43dc6b6116bfb75daef5`

This file exists only to create a traceable `[full-signoff]` commit that should force the repository's full CI matrix, including lint, unit tests, Android validation, Rust validation, and E2E checks according to `.github/workflows/ci.yml`.

Requested validation commands, mirrored from `docs/review-source/WEBRTC_TUNNEL_FIX9_IMPLEMENTATION_REPORT.md`:

```bash
cd android
./gradlew --no-daemon ktlintCheck
./gradlew --no-daemon detekt
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest --rerun-tasks
./gradlew --no-daemon assembleDebug
./gradlew --no-daemon check
```

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo clippy --workspace --release --all-features -- -D warnings
cargo test --workspace --all-targets --all-features
cargo build --release -p p2p-offer -p p2p-answer -p p2pctl
tests/e2e/docker/run.sh
tests/e2e/docker/stop_lifecycle.sh
```

```bash
cd android
./gradlew --no-daemon connectedDebugAndroidTest \
  -PskipRustBuild=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.phillipchin.webrtctunnel.data.BrokerSecretRepositoryInstrumentedTest
```

**Status at creation:** requested, not yet passed.
