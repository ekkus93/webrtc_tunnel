# WebRTC Tunnel FIX9 Validation Request

**Purpose:** trigger full validation for the FIX9 implementation pass.

**Original validation requested from SHA:** `29ac4d4b1550663495bc43dc6b6116bfb75daef5`  
**Latest implementation/test baseline:** `1c92815b84f151edc49266232724bd6f99f3f3e1`

This file exists only to create traceable `[full-signoff]` commits that force the repository's full CI matrix, including lint, unit tests, Android validation, Rust validation, Docker/Android E2E, focused RC diagnostics, and broker-secret permission instrumentation.

## Previous validation history

- Run `30344714809` failed Android detekt; the reported formatting and complexity findings were fixed without relaxing detekt.
- Run `30346404007` proved the broker-secret instrumentation test passed, but its custom status-posting step failed. The workflow now uses its authoritative job result.
- Run `30347751225` exposed two remaining controller complexity findings; helper extraction fixed both.
- Run `30349093311` exposed `TooManyFunctions` in the setup controllers; helper logic was moved out without weakening thresholds.
- Run `30350167310` exposed the final `SetupIdentityController` function-count finding; the resolver was moved out.
- Run `30353490703` passed static analysis and debug tests but exposed a Robolectric release-test latch race; commit `1f99b703e93d488eb2c2052bc765121759ef4c68` bound latches to the test Application instance.

## FIX9 completion changes under validation

- Active setup ownership now captures and cancels the real coroutine `Job` when setup is abandoned.
- Final save checks freshness immediately before transactional persistence; cancellation inside persistence rolls back attempted stages.
- A stale operation that somehow observes a completed commit publishes a durable `setup_commit_completed_after_cancel` warning and never starts the tunnel.
- `IdentityRepository.readPublicIdentity()` is serialized under the same storage lock as identity-pair replacement.
- Setup forward edits use draft-truthful messages.
- `ConfigRepository.savePreferences()` has an injected writer seam proving ordinary thrown failures become `Result.failure` while cancellation propagates.
- Production-path stale tests now cover path/URI import, generation, forward upsert/delete, navigation validation, pre-commit save cancellation, cancellation during transactional persistence, start-from-review, and newer-error preservation.
- Added coherent identity-pair concurrency proof, canonical identity-field failure tests, Result-contract tests, and source-level FIX9 enforcement tests.

## Required validation

```bash
cd android
./gradlew --no-daemon ktlintCheck
./gradlew --no-daemon detekt
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest --rerun-tasks
./gradlew --no-daemon testDebugUnitTest --rerun-tasks
./gradlew --no-daemon assembleDebug
./gradlew --no-daemon check
```

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo clippy --workspace --release --all-features -- -D warnings
cargo test --workspace --all-targets --all-features
cargo test -p p2p-daemon --test real_broker_tunnel --all-features
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

**Status:** full FIX9 validation requested; do not claim signoff until every required workflow passes for this exact commit SHA.
