# WebRTC Tunnel FIX9 Validation Request

**Purpose:** trigger full validation for the completed FIX9 implementation and enforcement pass.

**Original validation requested from SHA:** `29ac4d4b1550663495bc43dc6b6116bfb75daef5`  
**Latest implementation/test baseline:** `022f754f724e9664e191594be9dde7623ae36de0`

This file creates traceable `[full-signoff]` commits that force the repository's full CI matrix, including lint, unit tests, Android validation, Rust validation, Docker/Android E2E, focused RC diagnostics, and broker-secret permission instrumentation.

## Previous validation history

- Run `30344714809` failed Android detekt; formatting and complexity findings were fixed without relaxing detekt.
- Run `30346404007` proved the broker-secret instrumentation test passed, but its custom status-posting step failed. The workflow now uses its authoritative job result.
- Runs `30347751225`, `30349093311`, and `30350167310` exposed remaining controller complexity findings; helper logic was moved out without weakening thresholds.
- Run `30353490703` exposed a Robolectric release-test latch race; commit `1f99b703e93d488eb2c2052bc765121759ef4c68` bound latches to the test Application instance.
- Run `30499169031` passed Rust lint, Linux, macOS, RC diagnostics, and Docker E2E. Android stopped at detekt with one `LongParameterList` and four `MaxLineLength` findings.
- Run `30499670720` again passed every non-Android main job and RC diagnostics. Android stopped at one final `LongMethod` finding in `commitSetup`.
- Commit `85e77ea5d933c9c7ea85e130bd873d3ff4f8a01b` extracted persistence-request construction without changing cancellation, rollback, or stale-commit warning behavior.
- Commit `25f6e4699aa042884eb9ab7cbafaed3026e7cb5a` aligned the source contract audit with the refactored persistence boundary.
- Temporary patch workflows and marker files were removed, issue #4 was closed, and the authoritative status publisher was restored to status-only behavior at `04386c97cdf317aff77803457e341dcf8c54c573`.
- Run `30502929767` passed Rust lint, Linux, macOS, RC diagnostics, and Docker E2E. Android reached the full Gradle check and failed only because `ConfigRepository.kt` had one unexpected-indentation ktlint finding; commit `039696b1c34f5bf8458d86b0d325fbd0557206c4` corrected it.
- Broker instrumentation run `30502929768` did not execute the test because the emulator action ran `cd android` and `./gradlew` in separate shells. Commit `0cf4bf61f2518dde1a1fd1b9745a4b47d2fc10c3` now invokes `./android/gradlew -p android` directly from repository root.
- Commits `1ce0153fca7e147b78107a5d1463a32608c7e873` and `a8ffde57af963e7e518ffe6d7ae200d288356ea4` added comment-resistant source enforcement for identity-draft, forward-draft, final-persist, and tunnel-start freshness boundaries.
- Runs `30504403878` and `30505101514` exposed only ktlint formatting defects in the pre-existing Result-contract source audit. Commits `096d0b4b8a23f23530e2b085772b8fb0f0700ab7` and `022f754f724e9664e191594be9dde7623ae36de0` corrected them without weakening any assertion.

## FIX9 completion changes under validation

- Active setup ownership captures and cancels the real coroutine `Job` when setup is abandoned.
- Final save checks freshness immediately before transactional persistence; cancellation inside persistence rolls back attempted stages.
- A stale operation that somehow observes a completed commit publishes a durable `setup_commit_completed_after_cancel` warning and never starts the tunnel.
- `IdentityRepository.readPublicIdentity()` is serialized under the same storage lock as identity-pair replacement.
- Setup forward edits use draft-truthful messages.
- `ConfigRepository.savePreferences()` has an injected writer seam proving ordinary thrown failures become `Result.failure` while cancellation propagates.
- Production-path stale tests cover path/URI import, generation, forward upsert/delete, navigation validation, pre-commit save cancellation, cancellation during transactional persistence, start-from-review, and newer-error preservation.
- Added coherent identity-pair concurrency proof, canonical identity-field failure tests, Result-contract tests, CheckResult enforcement, and source-level FIX9 freshness enforcement.

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
./android/gradlew -p android --no-daemon connectedDebugAndroidTest \
  -PskipRustBuild=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.phillipchin.webrtctunnel.data.BrokerSecretRepositoryInstrumentedTest
```

**Status:** final exact-SHA implementation/test validation requested; do not claim signoff until every required workflow passes for this commit.
