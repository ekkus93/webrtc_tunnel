# WebRTC Tunnel FIX9 Validation Request

**Purpose:** trigger full validation for the FIX9 implementation pass.

**Original validation requested from SHA:** `29ac4d4b1550663495bc43dc6b6116bfb75daef5`  
**Latest validation baseline:** `1f99b703e93d488eb2c2052bc765121759ef4c68`

This file exists only to create traceable `[full-signoff]` commits that should force the repository's full CI matrix, including lint, unit tests, Android validation, Rust validation, and E2E checks according to `.github/workflows/ci.yml`.

The validation run `30344714809` failed in the Android job during `./gradlew --no-daemon check` because detekt reported FIX9 formatting/complexity findings. The follow-up commits fixed those findings without relaxing detekt configuration.

The validation run `30346404007` proved the broker-secret Android instrumentation test itself passed, but the workflow failed in its custom curl-based commit-status publication step. The workflow now relies on the GitHub Actions job result directly: no custom status POST, no `continue-on-error` masking.

The validation run `30347751225` reduced the Android failure to two detekt complexity findings: `importIdentityFromPath` long-method and `runSaveAndApply` nested-block-depth. The follow-up commits extracted both flows into smaller helpers without changing the intended stale-operation semantics.

The validation run `30349093311` reduced the Android failure to `TooManyFunctions` findings in `SetupIdentityController` and `SetupSaveController`. The follow-up commits moved pure/helper publication logic out of the controller classes without relaxing detekt thresholds.

The validation run `30350167310` reduced the Android failure to a single `TooManyFunctions` finding in `SetupIdentityController`. The follow-up commit moved the private identity path resolver out of the controller class, leaving the production freshness behavior unchanged.

The user updated `master` at `06fa50bac324dde4c540f0ccc57cb5496914ca43` to restore setup-wizard navigation logic and forward-save messages.

The validation run `30353490703` passed Android static analysis and debug unit tests, but release unit tests exposed a synchronization race in `TunnelForegroundServiceInitializationRaceTest`: Robolectric could start the configured Application before JUnit `@Before` replaced global latch references. Commit `1f99b703e93d488eb2c2052bc765121759ef4c68` binds the latches to each `BlockingInitTestApplication` instance and constructs the service controller in `setUp()`, ensuring the coroutine and assertions always observe the same latches.

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

**Status:** rerun requested after the release initialization-race test fix; not yet passed.
