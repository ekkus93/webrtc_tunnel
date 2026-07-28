# WebRTC Tunnel FIX9 Implementation Report

**TODO:** `docs/WEBRTC_TUNNEL_STALE_SETUP_RESULT_CONTRACT_FIX9_TODO.md`  
**Initial FIX9 baseline:** `141a5425f620ae6b37a29ee0d8956cbfbd4d7b27`  
**Current implementation SHA:** `b7e5d836426163a4c8f6f8ecd3299b6f8088760e`  
**Status:** implementation pass complete; validation not yet proven by local/CI output in this report.

This report records what was changed during the FIX9 Ralph-loop implementation pass. It deliberately does **not** claim release signoff. The GitHub status API returned no statuses for the current implementation SHA at the time this report was created.

---

## Implemented changes

### P0-001 — Setup stale/cancel semantics

Implemented explicit `SetupOperationToken` in `SetupOperationCoordinator` and changed guarded setup operations to receive the token instead of a raw `Long`.

Key behavior now expected:

- `cancel()` calls `operations.invalidate()`.
- Any in-flight setup operation whose token is stale skips UI publication through `publishIfFresh`.
- Identity import wipes the validated replacement private bytes when cancellation makes the operation stale before publication.
- Final save carries the token through setup-local admission, global `ConfigurationMutationCoordinator.SetupSave` admission, validation, persistence, and optional start-from-review.
- `startTunnelFromReview()` starts the foreground service only through the fresh-success callback.

Files changed:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupOperationCoordinator.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupIdentityController.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupForwardsController.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupSaveController.kt`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupViewModel.kt`

Tests added/expanded:

- `cancelDuringIdentityImportFromPathDoesNotPublishImportedIdentity`
- `cancelDuringFinalSaveValidationDoesNotPersistOrPublishSuccess`

Support test seam added:

- `RecordingBridge.blockNextPrivateIdentityValidation()`
- `RecordingBridge.privateIdentityValidationEnteredNow()`
- `RecordingBridge.releaseBlockedPrivateIdentityValidation(...)`

### P0-002 — `Result` contract hardening

Implemented ordinary-exception handling for the identified `ConfigRepository` gaps.

Files changed:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigRepository.kt`

Changes:

- `savePreferences(...)` now returns `Result.failure(error)` for all ordinary `Exception`s and rethrows `CancellationException`.
- `prepareActiveConfigForStart(...)` now wraps config read/rewrite failures in `Result.failure` and rethrows `CancellationException`.
- `replaceConfigTransactionally(...)` now returns `Result.failure` when prior snapshot capture fails before attempting any write or restore.

### P0-003 — Public identity read coherence

Partially implemented.

Changed `ImportExportViewModel.publicIdentityForShare()` to read the public identity through `IdentityRepository.readStoredIdentityMaterial`, the existing coherent encrypted/public snapshot API, instead of calling the standalone `readPublicIdentity()` path.

Files changed:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ImportExportViewModel.kt`

Remaining work:

- `IdentityRepository.readPublicIdentity()` itself still needs to be locked or deprecated/replaced completely.
- `SettingsViewModel` still needs to be moved to the coherent snapshot read path or covered by a locked repository method.

### P0-004 — Remove private-identity canonical fallback

Implemented.

Files changed:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ImportExportService.kt`

Change:

- `ImportExportService.importPrivateIdentityContent(...)` now requires `validated.canonicalPrivateIdentity` and no longer falls back to the original source private identity text.
- Canonical public identity remains required.
- Canonical private bytes are still wiped in `finally`.

### P0-005 — Setup draft-truth messages

Implemented.

Files changed:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupForwardsController.kt`

Changes:

- `Forward saved` -> `Forward draft updated`
- `Forward deleted` -> `Forward draft removed`

### P0-006 — Android broker-secret permission instrumentation

Implemented test file; not yet validated.

Files added:

- `android/app/src/androidTest/java/com/phillipchin/webrtctunnel/data/BrokerSecretRepositoryInstrumentedTest.kt`

Test added:

- `persistedAndRestoredBrokerSecretHasOwnerOnlyPermissions`

Expected evidence:

- After persist, `Os.stat(path).st_mode and 0x1FF == 0x180`.
- After restore from snapshot, the same permission bits are verified.

### P0-007 — Documentation/signoff truth

Implemented this report.

Files added:

- `docs/review-source/WEBRTC_TUNNEL_FIX9_IMPLEMENTATION_REPORT.md`

---

## Validation still required

Run the focused Android tests first:

```bash
cd android
./gradlew --no-daemon testDebugUnitTest --rerun-tasks \
  --tests '*SetupDraftOperationCoordinationTest' \
  --tests '*SetupStaleFinalSaveTest' \
  --tests '*SetupSaveControllerTest' \
  --tests '*ConfigRepositoryTest' \
  --tests '*ImportExportServiceTest'
```

Run instrumentation for the broker-secret permission evidence:

```bash
cd android
./gradlew --no-daemon connectedDebugAndroidTest \
  -PskipRustBuild=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.phillipchin.webrtctunnel.data.BrokerSecretRepositoryInstrumentedTest
```

Then run the broader validation from the FIX9 TODO:

```bash
cd android
./gradlew --no-daemon ktlintCheck
./gradlew --no-daemon detekt
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest --rerun-tasks
./gradlew --no-daemon assembleDebug
./gradlew --no-daemon check
```

Repository-level validation still required:

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo clippy --workspace --release --all-features -- -D warnings
cargo test --workspace --all-targets --all-features
cargo build --release -p p2p-offer -p p2p-answer -p p2pctl
tests/e2e/docker/run.sh
tests/e2e/docker/stop_lifecycle.sh
```

---

## Known remaining gaps

1. `IdentityRepository.readPublicIdentity()` is not yet changed directly. `ImportExportViewModel` has been moved to the coherent pair snapshot, but `SettingsViewModel` and the repository method itself still need cleanup.
2. No source-level enforcement test has been added yet for banning stale setup publication without token freshness guards.
3. No source-level enforcement test has been added yet for all remaining `Result` API catch-contract patterns.
4. The newly added tests were not run in this environment.
5. The current SHA has no CI statuses at the time this report was written.

---

## Current disposition

Implementation work has advanced substantially, especially for stale setup operations and broker-secret Android permission evidence. This is **not** a release-candidate signoff until the validation commands above pass and a final `[full-signoff]` SHA is recorded.
