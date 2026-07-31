# WebRTC Tunnel FIX9 Implementation Report

**TODO / closure ledger:** `docs/WEBRTC_TUNNEL_STALE_SETUP_RESULT_CONTRACT_FIX9_TODO.md`  
**Initial audit baseline:** `141a5425f620ae6b37a29ee0d8956cbfbd4d7b27`  
**Implementation baseline:** `6bad7a1f18b180676cc567031f93f7a99fb91d52`  
**Validated functional repair candidate:** `9ca07ff87d60e9c896bd1139a375fc84dbccc4cc`  
**Task evidence:** `docs/review-source/WEBRTC_TUNNEL_FIX9_COMPLETION_EVIDENCE.md`  
**Status:** implementation and functional exact-SHA release-candidate validation complete. Final documentation/evidence closure is valid only if the exact `[full-signoff]` commit containing this report passes all applicable same-SHA gates.

## Delivered behavior

FIX9 provides one stale-operation contract across setup baseline loading, identity import/generation, forward edits, navigation validation, final transactional save, and start-from-review:

- an admitted operation owns a typed freshness token and the real coroutine `Job`;
- abandonment invalidates and cancels the exact owner;
- stale work cannot publish identity, forward, navigation, success, or error state;
- final persistence checks freshness before mutation and rolls back cancellation during mutation;
- a commit observed after abandonment is reported durably and never starts the tunnel;
- setup `Ready` is published only after baseline admission releases.

Result-returning APIs now represent ordinary failures as values and preserve cancellation. Public identity reads are coherent with pair replacement. Private identity import requires canonical native output. Setup forward messages state draft truth. Broker-secret persist/restore permissions are tested as exact `0600` on Android.

## Release-validation hardening delivered during FIX9

### Setup readiness/admission race

Run `30593967688` exposed that terminal setup load state could become visible before `BaselineLoad` released operation admission. Commit `41b3e08cffe83292776eaeb62524a4133837e19a` now publishes terminal load state only after admission release. Unexpected baseline exceptions fail closed as redacted durable failures; no blank/default fallback was introduced.

### Diagnostics-export double admission

Exact candidate `34b95051defd1a63d67836f01de6b1716f694ac3` correctly failed the second full Android unit invocation in `LogsViewModelTest.concurrentExportIsRejectedWhileOneIsAlreadyInFlight`.

The production race was a check-before-launch error: both diagnostics export APIs inspected busy state before launch but claimed ownership only inside the coroutine. Two immediate callers could therefore both pass the check.

- `590c66717a7c67cb4d8fd08f48daa881d8834641` atomically claims shared path/URI export admission with `MutableStateFlow.compareAndSet(false, true)` before launch.
- `6bad7a1f18b180676cc567031f93f7a99fb91d52` replaces scheduler-speed assumptions with a blocked single-thread IO dispatcher and bounded latches.
- Path-scoped run `30600821345` passed full Gradle `check`, the dedicated stop-failure suite, the second full debug unit invocation, and path-scoped signoff without retry.

### Android emulator Settings-navigation race

Exact candidate `7be00838feef85d374f20aa8dd6b7365969ba3dd` passed the non-emulator matrix but failed the real emulator E2E with `could not find Settings nav tab`.

The old `bounds_of_text()` shell pipeline ended in `awk`. Empty input therefore produced empty output but status zero, so the readiness check falsely treated a missing node as present and attempted navigation before Compose semantics were available.

Commit `9ca07ff87d60e9c896bd1139a375fc84dbccc4cc` repairs the actual harness contract:

- missing semantic nodes or unusable bounds return failure;
- startup waits for exported Settings semantics instead of a fixed sleep;
- the Settings control is located by visible text or its explicit `Settings tab icon` accessibility description;
- both selectors remain app-owned UI semantics from the same `uiautomator` tree;
- no coordinate fallback, silent skip, retry suppression, or timeout inflation was added.

## Principal correction commits

- `41b3e08cffe83292776eaeb62524a4133837e19a` — truthful setup readiness after baseline admission release
- `590c66717a7c67cb4d8fd08f48daa881d8834641` — atomic diagnostics export admission
- `6bad7a1f18b180676cc567031f93f7a99fb91d52` — deterministic diagnostics concurrency regression
- `9ca07ff87d60e9c896bd1139a375fc84dbccc4cc` — fail-closed Android UI semantic lookup and readiness wait

## Principal production files

- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupOperationCoordinator.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupViewModel.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupIdentityController.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupForwardsController.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupSaveController.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/SetupPersistenceCoordinator.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigRepository.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/security/IdentityRepository.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ImportExportService.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/LogsViewModel.kt`
- `tests/e2e/lib/android_wizard.sh`

## Principal evidence

- `SetupFix9CancellationRegressionTest`
- `SetupFix9NativeBarrierCancellationTest`
- `SetupStaleFinalSaveTest`
- `SetupDraftOperationCoordinationTest`
- `IdentityRepositoryCoherentReadTest`
- `ConfigRepositoryFix9ResultContractTest`
- `Fix9ResultContractViewModelTest`
- `Fix9SetupFreshnessSourceAuditTest`
- `Fix9ResultContractSourceAuditTest`
- `CheckResultEnforcementFixtureTest`
- `ImportExportCanonicalContractTest`
- `BrokerSecretRepositoryInstrumentedTest`
- `LogsViewModelTest.concurrentExportIsRejectedWhileOneIsAlreadyInFlight`
- Android emulator real-data-path E2E job `91074610794`

## Exact successful functional candidate

Commit `9ca07ff87d60e9c896bd1139a375fc84dbccc4cc` passed every applicable commit-level release-candidate gate:

- RC diagnostics: run `30603969425`, success
- broker-secret permission instrumentation: run `30603969417`, success
- main release-candidate matrix: run `30603969420`, success
- `ci/rc-diagnostics`: success
- `ci/full-matrix`: success
- `ci/release-candidate`: success
- Rust lint, Linux, and macOS jobs: success
- Android full Gradle `check`: success
- dedicated foreground-service stop-failure truthfulness tests: success
- second `assembleDebug testDebugUnitTest` invocation: success without retry
- Docker real-data-path and graceful-stop E2E: success
- Android emulator real-data-path E2E: success, job `91074610794`
- full matrix signoff: success, job `91076875217`

The emulator run completed all seven setup steps, reached `Listening`, launched the dockerized answer, delivered the marker through the Android-offer tunnel, and verified the answer received `PING` and returned `PONG`.

## Final documentation/evidence signoff rule

The final candidate is the exact `[full-signoff]` commit containing this report and the companion closure documents. Its SHA is intentionally self-referenced rather than guessed before publication.

Commit-level FIX9 closure becomes valid only when the authoritative commit status API and CI status issues show, for that exact SHA:

- `ci/rc-diagnostics`: success
- `ci/full-matrix`: success
- `ci/release-candidate`: success
- broker-secret permission instrumentation: success
- all required Rust/Linux/macOS/Android/Docker jobs: success
- Android emulator real-data-path E2E: success

A successful parent, a rerun from another SHA, or the previously validated functional candidate is not a substitute for the final documentation SHA.

## Release artifacts

Release APK/AAB packaging is tag-only by workflow design. Those jobs are expected to skip on a `[full-signoff]` commit and are not dependencies of commit-level `ci/full-matrix` or `ci/release-candidate`.

The eventual release tag must separately pass tag-only packaging and artifact verification before publication. FIX9 commit-level closure does not claim that future tag evidence already exists.
