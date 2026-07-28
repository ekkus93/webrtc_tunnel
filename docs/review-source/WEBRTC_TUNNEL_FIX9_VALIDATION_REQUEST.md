# WebRTC Tunnel FIX9 Validation Request

**Purpose:** trigger full validation for the FIX9 implementation pass.

**Original validation requested from SHA:** `29ac4d4b1550663495bc43dc6b6116bfb75daef5`  
**Latest rerun requested after controller too-many-functions cleanup:** current `master` at this commit.

This file exists only to create traceable `[full-signoff]` commits that should force the repository's full CI matrix, including lint, unit tests, Android validation, Rust validation, and E2E checks according to `.github/workflows/ci.yml`.

The validation run `30344714809` failed in the Android job during `./gradlew --no-daemon check` because detekt reported FIX9 formatting/complexity findings. The follow-up commits fixed those findings without relaxing detekt configuration.

The validation run `30346404007` proved the broker-secret Android instrumentation test itself passed, but the workflow failed in its custom curl-based commit-status publication step. The workflow now relies on the GitHub Actions job result directly: no custom status POST, no `continue-on-error` masking.

The validation run `30347751225` reduced the Android failure to two detekt complexity findings: `importIdentityFromPath` long-method and `runSaveAndApply` nested-block-depth. The follow-up commits extracted both flows into smaller helpers without changing the intended stale-operation semantics.

The validation run `30349093311` reduced the Android failure to `TooManyFunctions` findings in `SetupIdentityController` and `SetupSaveController`. The follow-up commits moved pure/helper publication logic out of the controller classes without relaxing detekt thresholds.

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

**Status:** rerun requested after controller too-many-functions cleanup; not yet passed.
