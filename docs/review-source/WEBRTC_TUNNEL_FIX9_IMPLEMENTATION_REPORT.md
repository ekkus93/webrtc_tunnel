# WebRTC Tunnel FIX9 Implementation Report

**TODO:** `docs/WEBRTC_TUNNEL_STALE_SETUP_RESULT_CONTRACT_FIX9_TODO.md`  
**Initial baseline:** `141a5425f620ae6b37a29ee0d8956cbfbd4d7b27`  
**Implementation baseline submitted for the next exact-SHA signoff:** `6bad7a1f18b180676cc567031f93f7a99fb91d52`  
**Task evidence:** `docs/review-source/WEBRTC_TUNNEL_FIX9_COMPLETION_EVIDENCE.md`  
**Status:** implementation/enforcement complete; exact-SHA release signoff pending.

## Delivered behavior

FIX9 provides one stale-operation contract across setup baseline loading, identity import/generation, forward edits, navigation validation, final transactional save, and start-from-review:

- an admitted operation owns a typed freshness token and the real coroutine `Job`;
- abandonment invalidates and cancels the exact owner;
- stale work cannot publish identity, forward, navigation, success, or error state;
- final persistence checks freshness before mutation and rolls back cancellation during mutation;
- a commit observed after abandonment is reported durably and never starts the tunnel;
- setup `Ready` is published only after baseline admission releases.

Result-returning APIs now represent ordinary failures as values and preserve cancellation. Public identity reads are coherent with pair replacement. Private identity import requires canonical native output. Setup forward messages state draft truth. Broker-secret persist/restore permissions are tested as exact `0600` on Android.

## Additional release-validation hardening

The failed exact candidate `34b95051defd1a63d67836f01de6b1716f694ac3` exposed a diagnostics-export double-admission race only on the second full unit invocation. `LogsViewModel` now atomically claims busy ownership before launching either path or URI export. The regression parks IO behind a bounded latch, so it no longer depends on dispatcher speed.

Principal correction commits:

- `41b3e08cffe83292776eaeb62524a4133837e19a` — truthful setup readiness after baseline admission release
- `590c66717a7c67cb4d8fd08f48daa881d8834641` — atomic diagnostics export admission
- `6bad7a1f18b180676cc567031f93f7a99fb91d52` — deterministic diagnostics concurrency regression

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

## Validation history

- `30505896676`: broker-secret instrumentation passed on an earlier candidate.
- `30593967688`: exposed the setup readiness/admission race.
- `30598677024`: final setup implementation baseline passed the path-scoped Android gate.
- `30600821345`: diagnostics admission baseline `6bad7a1f…` passed full Gradle `check`, the dedicated stop suite, the second full debug unit invocation, and path-scoped signoff without retry.
- Exact candidate `34b95051…`:
  - RC diagnostics and broker instrumentation passed.
  - Rust lint/Linux/macOS/Docker passed.
  - Android full check and dedicated stop suite passed.
  - the second full debug unit invocation failed the diagnostics concurrency regression.
  - full-matrix and release-candidate statuses correctly failed.

The next companion `[full-signoff]` commit changes documentation only relative to implementation baseline `6bad7a1f…`. Its exact workflow results determine release signoff. Tag-only release packaging is validated when a release tag is created, not by the commit candidate workflow.
