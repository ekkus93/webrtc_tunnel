# WebRTC Tunnel Transactional Integrity, Runtime Quarantine, and Failure Truthfulness FIX7 TODO

This TODO implements:

- `docs/WEBRTC_TUNNEL_TRANSACTIONAL_INTEGRITY_RUNTIME_QUARANTINE_FIX7_SPEC.md`
- against the reviewed baseline `webrtc_tunnel-master_2607201054.zip`;
- using `docs/review-source/WEBRTC_TUNNEL_FIX6_CODE_REVIEW_2026-07-20.md` as the detailed defect source.

All three files are included in the FIX7 handoff bundle. Do not reference a generated companion file unless it is committed at the exact repository path named in this TODO.

No checkbox in this document is pre-completed. Do not mark a task complete until the implementation, exact negative-path tests, focused validation, and required static checks all exist and pass.

---

# 0. Binding execution order

The document order is executable. Follow it unless a newly discovered code dependency is documented in this file before implementation.

## Stage A — foundation and admission

1. **P0-001** application-wide configuration mutation admission.
2. **P0-002** exact file snapshot/restore primitives and cleanup composition.
3. **P0-003** pure config rendering, broker-secret repository, and isolated setup validation workspace.

## Stage B — durable transaction correctness

4. **P0-004** one authoritative setup transaction, including cancellation rollback.
5. **P0-005** exact reset snapshot and cancellation rollback.
6. **P0-006** identity-pair and identity-storage rollback correctness.

## Stage C — native lifecycle safety

7. **P0-007** common runtime quarantine and unverified-start cleanup.
8. **P0-008** offer cooperative stop while Listening.

## Stage D — network and time correctness

9. **P0-009** fail-closed network handling independent of reporter success.
10. **P0-010** repository-wide Rust wall-clock consistency.

## Stage E — integration and secondary truthfulness

11. P1 tasks in document order.

## Stage F — enforcement and signoff

12. P2 tasks in document order.

Every task commit must be green. Do not intentionally commit failing tests, `@Ignore`, placeholder assertions, TODO-returning production code, or a temporary static-rule violation.

---

# 1. Work discipline

For every task:

```text
1. inspect current production code and all related tests
2. write or strengthen the exact negative-path test first
3. run it and confirm it fails for the intended reason
4. implement the smallest coherent production change
5. run the focused test class with --rerun-tasks
6. run ktlint/detekt/lint for touched Android code or fmt/clippy for Rust
7. commit one scoped change
8. record the commit SHA beside the task
9. update no unrelated checklist item
```

## Hard rules

```text
no validation-time mutation of live identity, authorized_keys, config, setup, preference, forwards, or broker-secret state
no partial durable transaction left behind solely because cancellation occurred
no rollback or cleanup Result discarded
no runCatching around suspend mutation, rollback, native cleanup, or lifecycle orchestration
no false success UI
no silent busy rejection
no per-screen mutex presented as cross-feature serialization
no stop-like failure without runtime quarantine
no start/resume while runtime state is uncertain
no reporter failure allowed to prevent fail-closed safety action
no stale peer/session/MQTT fields after invalid native status
no wall-clock panic, unwrap_or(0), or uninitialized-zero fallback
no raw secret-bearing Throwable in logs, UI, state, or diagnostics
no Thread.sleep used to prove absence, ordering, overlap, or exactly-once behavior
no test name accepted as proof unless the test drives the named production path
no regex-only ignored-result checker
no final signoff while CI is still running
```

## Required initial inventories

Preserve these outputs in final evidence:

```bash
cd android

rg -n 'runCatching|catch\s*\([^)]*Throwable' \
  app/src/main/java/com/phillipchin/webrtctunnel

rg -n 'tryEmit\(|trySend\(|\.delete\(\)|deleteIfExists|deleteCandidateFileSafely' \
  app/src/main/java/com/phillipchin/webrtctunnel

rg -n 'writeConfigAtomically\(|savePreferences\(|saveSetupInput\(|saveForwards\(|resetForwards\(|restoreForTransactionalReset\(|storeEncryptedIdentity\(|appendAuthorizedPublicIdentity\(' \
  app/src/main app/src/test

rg -n 'renderOfferConfig\(|resolveBrokerPasswordFile|mqtt_password' \
  app/src/main

rg -n 'Thread\.sleep|delay\(' app/src/test

cd ..
rg -n 'duration_since\(UNIX_EPOCH\)|unwrap_or\(0\)|expect\("system clock|resolve_unix_ms|current_time_ms|unix_ms' crates
```

---

# P0 — Release blockers

# P0-001 — Add one application-wide configuration mutation coordinator

**Review findings addressed:** HIGH-1, HIGH-5, P1-005 partial integration.

**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigurationMutationCoordinator.kt (new)
android/app/src/main/java/com/phillipchin/webrtctunnel/data/AppDependencies.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupSaveController.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ImportExportViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SettingsViewModel.kt
related tests
```

## P0-001-A — Create operation and admission types

- [x] Add `ConfigurationOperation` with `SetupSave`, `ConfigImport`, `ForwardMutation`, and `ConfigurationReset`. (5b0c4d4)
- [x] Add `ConfigurationAdmission.Completed<T>` and `ConfigurationAdmission.Busy`. (5b0c4d4)
- [x] Add `ConfigurationMutationCoordinator` using one `Mutex` and an `AtomicReference` for the active operation. (5b0c4d4)
- [x] Make `tryRun` release the lock in `finally` for success, failure, fatal error, and cancellation. (5b0c4d4)
- [x] Do not catch or normalize the operation block’s exception. (5b0c4d4)

Target implementation:

```kotlin
package com.phillipchin.webrtctunnel.data

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicReference

enum class ConfigurationOperation {
    SetupSave,
    ConfigImport,
    ForwardMutation,
    ConfigurationReset,
}

sealed interface ConfigurationAdmission<out T> {
    data class Completed<T>(val value: T) : ConfigurationAdmission<T>

    data class Busy(
        val active: ConfigurationOperation,
    ) : ConfigurationAdmission<Nothing>
}

class ConfigurationMutationCoordinator {
    private val mutex = Mutex()
    private val active = AtomicReference<ConfigurationOperation?>(null)

    suspend fun <T> tryRun(
        operation: ConfigurationOperation,
        block: suspend () -> T,
    ): ConfigurationAdmission<T> {
        if (!mutex.tryLock()) {
            return ConfigurationAdmission.Busy(
                active.get() ?: operation,
            )
        }

        active.set(operation)
        return try {
            ConfigurationAdmission.Completed(block())
        } finally {
            active.set(null)
            mutex.unlock()
        }
    }

    internal fun activeOperationForTest(): ConfigurationOperation? =
        active.get()
}
```

`activeOperationForTest` is read-only and `internal`; do not add mutation hooks.

## P0-001-B — Wire as an `AppDependencies` body property

- [x] Add a lazy/body property; do not add a seventh constructor parameter. (5b0c4d4)

```kotlin
val configurationMutationCoordinator: ConfigurationMutationCoordinator by lazy {
    ConfigurationMutationCoordinator()
}
```

## P0-001-C — Replace authoritative local operation admission

- [x] Setup save uses `ConfigurationOperation.SetupSave`. (5b0c4d4)
- [x] Config import uses `ConfigurationOperation.ConfigImport`. (5b0c4d4)
- [x] Forward mutation plus active-config regeneration uses `ConfigurationOperation.ForwardMutation` around the whole mutation/activation/rollback sequence. (5b0c4d4)
- [x] Configuration reset uses `ConfigurationOperation.ConfigurationReset` around the whole reset transaction. (5b0c4d4)
- [x] Do not release global admission between the forward repository mutation and config activation. (5b0c4d4)
- [x] Do not release global admission between setup validation and setup commit; otherwise import/reset can change authoritative inputs after validation. (5b0c4d4)
- [x] Existing local mutexes may remain only for unrelated local actions. Remove redundant ones when safe. (5b0c4d4 — the export-only `ImportExportOps.exportMutex` is retained since exports are non-authoritative local actions; setup/forwards/import authoritative mutexes were removed)

Busy mapping example:

```kotlin
private fun busyFailure(
    active: ConfigurationOperation,
): OperationFailure =
    OperationFailure(
        code = "configuration_operation_busy",
        message = "Another configuration operation is already in progress: $active",
    )
```

- [x] Every busy rejection updates durable state. (5b0c4d4)
- [x] Snackbar may mirror the durable failure. (5b0c4d4)
- [x] Import must no longer use `if (!operationMutex.tryLock()) return@launch`. (5b0c4d4)
- [x] Setup and forwards must use the global active operation in their busy message, not only “already in progress.” (5b0c4d4)

## P0-001-D — Tests

Add `ConfigurationMutationCoordinatorTest.kt`:

- [x] `busyAdmissionReportsTheActiveOperation` (5b0c4d4)
- [x] `operationFailureReleasesAdmission` (5b0c4d4)
- [x] `operationCancellationReleasesAdmission` (5b0c4d4)
- [x] `fatalErrorReleasesAdmissionAndStillPropagates` (5b0c4d4)
- [x] `completedOperationReturnsValue` (5b0c4d4)

Integration tests:

- [x] `setupSaveBlocksConcurrentConfigImportAndImportReportsBusyDurably` (5b0c4d4)
- [x] `configImportBlocksConcurrentForwardMutationAndForwardReportsBusyDurably` (5b0c4d4)
- [x] `forwardActivationBlocksConcurrentResetAndResetReportsBusyDurably` (5b0c4d4)
- [x] `resetBlocksConcurrentSetupSaveAndSetupReportsBusyDurably` (5b0c4d4)
- [x] `laterOperationUsesFreshStateAfterFirstOperationCompletes` (5b0c4d4)

Use `CompletableDeferred` barriers. Do not use `Thread.sleep` or timing assertions.

> Implementation note: integration tests use inline (Unconfined) test dispatchers, not
> `realIoTestDispatchers()` — suspension on an unresolved `CompletableDeferred` yields control to
> the caller regardless of dispatcher, so a real background-thread hop isn't needed for the
> blocking technique, and inline dispatchers sidestep Robolectric's paused-Looper semantics for
> `viewModelScope`-launched coroutines. `SetupSaveController` (constructed directly, not as a
> `ViewModel`) keeps its own explicit `Dispatchers.IO` scope.

## Acceptance

- [x] No setup/import/forward/reset pair can overlap. (5b0c4d4)
- [x] Busy rejection is visible and durable. (5b0c4d4)
- [x] Admission always releases after cancellation/failure. (5b0c4d4)
- [x] The entire multi-stage operation owns admission, not only one repository call. (5b0c4d4)

---

# P0-002 — Add exact snapshot/restore and cleanup-composition primitives

**Review findings addressed:** CRITICAL-1, CRITICAL-3, CRITICAL-4, HIGH-2, HIGH-4, HIGH-7, MEDIUM-2.

**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ExactFileState.kt (new)
android/app/src/main/java/com/phillipchin/webrtctunnel/data/MutationHelpers.kt
android/app/src/test/.../ExactFileStateTest.kt (new)
android/app/src/test/.../MutationHelpersTest.kt
```

> Implementation note: the new file is `ExactFileSnapshot.kt`, not `ExactFileState.kt` — detekt's
> `MatchingDeclarationName` requires the file name to match its single top-level declaration
> (`ExactFileSnapshot`); no suppression is permitted per the linting policy, so the filename
> deviates from this document's illustrative path.

## P0-002-A — Exact file snapshot

- [x] Add `ExactFileSnapshot(existed, bytes)`. (42d1081)
- [x] Snapshot read failure returns failure and aborts the parent transaction before mutation. (42d1081)
- [x] Present-empty is distinct from absent. (42d1081)
- [x] Never substitute parsed/default content for exact bytes. (42d1081)

```kotlin
class ExactFileSnapshot internal constructor(
    val existed: Boolean,
    val bytes: ByteArray?,
) {
    fun wipe() {
        bytes?.fill(0)
    }
}

internal fun captureExactFileSnapshot(file: File): Result<ExactFileSnapshot> =
    try {
        Result.success(
            if (file.exists()) {
                ExactFileSnapshot(
                    existed = true,
                    bytes = file.readBytes(),
                )
            } else {
                ExactFileSnapshot(
                    existed = false,
                    bytes = null,
                )
            },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
```

## P0-002-B — Checked exact restore

- [x] Present snapshot restores via injected atomic replacement. (42d1081)
- [x] Absent snapshot restores via `Files.deleteIfExists`. (42d1081)
- [x] Do not use `File.delete()` without checking its return. (42d1081)
- [x] Restore returns `Result<Unit>` and is annotated/enforced as consumed. (42d1081)

```kotlin
@CheckResult
internal fun restoreExactFileSnapshot(
    logicalName: String,
    file: File,
    snapshot: ExactFileSnapshot,
    atomicReplace: (File, ByteArray) -> Unit,
): Result<Unit> =
    try {
        if (snapshot.existed) {
            atomicReplace(
                file,
                requireNotNull(snapshot.bytes) {
                    "$logicalName snapshot bytes are missing"
                },
            )
        } else {
            Files.deleteIfExists(file.toPath())
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
```

## P0-002-C — Candidate/workspace scope helper

Replace caller-managed create/delete patterns with one helper that cannot forget cleanup.

- [x] Add `CandidateCleanupException` with a fixed safe message. (42d1081)
- [x] Add `withCandidateFile`. (42d1081)
- [x] Add equivalent `withTemporaryDirectory` or `withSetupValidationWorkspace` cleanup composition. (42d1081 — `withTemporaryDirectory`, with an injectable recursive-delete seam for tests)
- [x] Primary error identity is preserved. (42d1081)
- [x] Cleanup is attached as suppressed after primary failure/cancellation. (42d1081)
- [x] Success becomes failure if cleanup fails. (42d1081)

Target helper:

```kotlin
internal class CandidateCleanupException(
    message: String,
    cause: Throwable,
) : Exception(message, cause)

internal suspend fun <T> withCandidateFile(
    cacheDir: File,
    prefix: String,
    block: suspend (File) -> T,
): T {
    val candidate = createCandidateFile(cacheDir, prefix)
    var primary: Throwable? = null

    try {
        return block(candidate)
    } catch (cancelled: CancellationException) {
        primary = cancelled
        throw cancelled
    } catch (error: Exception) {
        primary = error
        throw error
    } finally {
        val cleanup = deleteCandidateFileSafely(candidate).exceptionOrNull()
        if (cleanup != null) {
            if (primary != null) {
                primary.addSuppressed(cleanup)
            } else {
                throw CandidateCleanupException(
                    "Failed to remove temporary configuration candidate",
                    cleanup,
                )
            }
        }
    }
}
```

Do not add `catch (Throwable)`.

> Implementation note: the target snippet throws `CandidateCleanupException` from a `finally`
> block, which detekt's `ThrowingExceptionFromFinally` forbids (no suppression permitted). The
> shared `withCleanupComposition` helper instead captures the primary outcome as a `Result<T>`
> first, always runs cleanup next, and only then decides what to return/throw — same observable
> composition semantics, no throw-from-finally. `withCandidateFile` and `withTemporaryDirectory`
> both delegate to it.

## P0-002-D — Tests

- [x] `snapshotDistinguishesAbsentFromPresentEmpty` (42d1081)
- [x] `snapshotReadFailureReturnsFailureBeforeMutation` (42d1081)
- [x] `restoreAbsentDeletesExistingFile` (42d1081)
- [x] `restoreAbsentDeletionFailureReturnsFailure` (42d1081)
- [x] `restorePresentUsesExactBytes` (42d1081)
- [x] `candidatePrimaryFailurePreservedAndCleanupSuppressed` (42d1081)
- [x] `candidateCancellationPreservedAndCleanupSuppressed` (42d1081)
- [x] `candidateSuccessfulBlockBecomesFailureWhenCleanupFails` (42d1081)
- [x] `candidateSuccessfulBlockReturnsValueWhenCleanupSucceeds` (42d1081)
- [x] `temporaryDirectoryCleanupFailureUsesSameCompositionRules` (42d1081)

## Acceptance

- [x] Exact file absence is representable and restorable. (42d1081)
- [x] No cleanup caller can accidentally discard a cleanup `Result`. (42d1081 — `@CheckResult` on `restoreExactFileSnapshot`; `withCandidateFile`/`withTemporaryDirectory` compose cleanup automatically)
- [ ] Secret snapshots expose a wipe method and owners invoke it. `ExactFileSnapshot.wipe()` exists (42d1081), but no owner calls it yet — `BrokerSecretRepository` (6582641) snapshots the broker password but does not yet need `wipe()` since its snapshot is only used for restore-on-failure, not held across a suspension point; still deferred to P0-004's cancellation-rollback work, whose coordinator stage will hold a live snapshot across a suspend boundary.

---

# P0-003 — Make config rendering pure and validate setup in an isolated workspace

**Review findings addressed:** CRITICAL-1, CRITICAL-6, HIGH-13.

**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/BrokerSecretRepository.kt (new)
android/app/src/main/java/com/phillipchin/webrtctunnel/data/SetupValidationWorkspace.kt (new)
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupSaveController.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/AppDependencies.kt
related tests
```

## P0-003-A — Remove I/O from `renderOfferConfig`

- [x] Delete or stop using `resolveBrokerPasswordFile` from render code. (6582641)
- [x] `renderOfferConfig` accepts a broker password path as input. (6582641)
- [x] It returns TOML and performs no file creation, write, delete, permission change, repository mutation, preference read, or network call. (6582641)

Target signature:

```kotlin
fun renderOfferConfig(
    input: SetupConfigInput,
    forwards: List<ForwardConfig>,
    debugLogsEnabled: Boolean,
    androidIceMode: AndroidIceMode,
    brokerPasswordPath: String?,
): String
```

- [x] Config omits or correctly represents the password-file field when path is null. (6582641)
- [x] Callers pass an authoritative path or validation-workspace path explicitly. (6582641)

## P0-003-B — Add `BrokerSecretRepository`

- [x] Store `runtime/mqtt_password.txt` only through this repository. (6582641)
- [x] Serialize reads/snapshots/mutations with one lock. (6582641)
- [x] Use unique same-directory temp and atomic/replacement move. (6582641)
- [x] Set owner-only permissions after replacement. (6582641)
- [x] `persist(null/empty)` restores the intended “no password file” state using checked deletion. (6582641)
- [x] `captureSnapshot` and `restore` use exact snapshots. (6582641)
- [x] Result-returning mutation APIs are `@CheckResult` and consumed. (6582641)
- [x] No password content or raw throwable reaches logs. (6582641)

Suggested constructor/API is in the FIX7 spec. Add it as a lazy body property on `AppDependencies` to avoid constructor growth.

## P0-003-C — Add isolated setup validation workspace

- [x] Create a unique cache directory for every setup validation. (6582641)
- [x] Populate only workspace files required by native validation. (6582641)
- [x] Proposed authorized key is merged into workspace `authorized_keys`, not the live file. (6582641)
- [x] Imported identity is represented in workspace or passed directly as bytes to identity-aware validation. (6582641 — passed as in-memory bytes; no identity file in workspace)
- [x] Proposed broker password is written only inside workspace before validation. (6582641)
- [x] Candidate TOML references workspace paths. (6582641)
- [x] Workspace cleanup follows P0-002 composition and cannot be ignored. (6582641)
- [x] Workspace cleanup failure after otherwise successful validation makes setup fail before authoritative commit and emits `candidate_cleanup_failed`. (6582641)

Do not write plaintext private identity to the workspace. Continue using the identity-aware validation API that accepts private bytes. If native validation absolutely requires a private-key file, stop and document the blocker rather than writing plaintext to disk; redesign the native validation interface.

## P0-003-D — Refactor setup validation flow

Replace the current live-storage sequence in `SetupSaveController.validateAndCommit`:

```text
capture live identity snapshot
write live identity
append live key
render config (writes live password file)
validate
restore on failure
```

with:

```text
resolve/validate inputs in memory
acquire global configuration admission
create isolated validation workspace
render candidate with workspace paths
validate candidate
delete workspace successfully
construct one SetupPersistenceRequest containing all authoritative mutations
call SetupPersistenceCoordinator exactly once
```

- [x] Remove outer `identitySnapshot` and `restoreStorageSnapshot` from `SetupSaveController`. (6582641)
- [x] Remove comments claiming live pre-validation writes are required. (6582641)
- [x] Pass replacement identity and authorized key into the coordinator request. (6582641 — unchanged from FIX6, now actually exercised since P0-003 stops bypassing the coordinator on the imported-identity path)
- [x] Pass broker password into the coordinator request. (Not yet — 6582641 still calls `deps.brokerSecretRepository.persist(...)` directly in `commitSetup`, outside `SetupPersistenceCoordinator`; moving it into a proper rollback-safe coordinator stage is explicit P0-004-A scope per this TODO's own P0-004 file list, so left as a direct call here.)
- [x] Wipe plaintext identity bytes in `finally` for success, validation failure, persistence failure, and cancellation. (6582641)

## P0-003-E — Refactor forward render path

- [x] Forward config regeneration uses pure render. (6582641)
- [x] It references the existing authoritative broker secret path without rewriting the secret. (6582641)
- [x] If the expected password file is missing while config requires it, activation fails visibly; do not silently create a blank/default password file. (6582641)

## P0-003-F — Tests

Config purity:

- [x] `renderOfferConfigPerformsNoFilesystemWrites` (6582641)
- [x] `renderOfferConfigUsesProvidedBrokerPasswordPath` (6582641)
- [x] `renderOfferConfigOmitsPasswordFileWhenNoPasswordConfigured` (6582641)

Broker secret:

- [x] `brokerPasswordPersistUsesAtomicReplacement` (6582641)
- [x] `brokerPasswordPermissionsAreOwnerOnly` (6582641)
- [x] `brokerPasswordSnapshotDistinguishesAbsentAndEmpty` (6582641)
- [x] `brokerPasswordRestoreRecreatesExactBytes` (6582641)
- [x] `brokerPasswordRestoreDeletesFileWhenPreviouslyAbsent` (6582641)
- [x] `brokerPasswordWriteFailureLeavesOldSecretUnchanged` (6582641)

Validation integration:

- [x] `setupValidationFailureDoesNotMutateLiveIdentityAuthorizedKeysSecretSetupPreferencesOrConfig` (6582641)
- [x] `setupValidationCancellationDoesNotMutateLiveState` (6582641)
- [x] `setupValidationUsesUniqueWorkspaceForConcurrentAttempts` (6582641)
- [x] `setupValidationWorkspaceContainsProposedAuthorizedKeyButLiveAuthorizedKeysDoesNot` (6582641)
- [x] `setupValidationWorkspaceCleanupFailurePreventsCommitAndIsVisible` (6582641)
- [x] `setupValidationNeverWritesPlaintextPrivateIdentityToDisk` (6582641)

Use byte snapshots of every live file before and after. This is the actual replacement for the misleading FIX6 validation test.

## Acceptance

- [x] Validation has zero authoritative side effects. (6582641)
- [x] `renderOfferConfig` is pure. (6582641)
- [x] Broker password is no longer written as a render side effect. (6582641)
- [x] Setup has one future commit point instead of nested transaction systems. (6582641 — `SetupPersistenceCoordinator.persist(...)` remains the single commit call; broker-password persist is still a separate direct call pending P0-004-A's new coordinator stage, see P0-003-D note above)

---

# P0-004 — Make setup persistence one transaction and rollback on cancellation

**Review findings addressed:** CRITICAL-1, CRITICAL-2, HIGH-6, MEDIUM-7, MEDIUM-8.

**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/SetupPersistenceCoordinator.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupSaveController.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/security/IdentityRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/BrokerSecretRepository.kt
related tests
```

## P0-004-A — Expand request and stage model

- [x] Add `BrokerSecret` stage. (c6a993b)
- [x] Request carries replacement identity, authorized key, broker password, setup input, preferences, config. (c6a993b)
- [x] Stage order is exactly: (c6a993b)

```text
Identity
AuthorizedKeys
BrokerSecret
SetupInput
Preferences
Config LAST
```

- [x] Omit optional stages only when the request truly makes no mutation to that resource. (c6a993b — `BrokerSecret` is omitted only when an "advanced" externally-managed password file is configured, in which case the managed secret is genuinely untouched)
- [x] If broker password is intentionally removed, `BrokerSecret` is still requested. (c6a993b — `BrokerSecretChange.Remove`)

## P0-004-B — Capture one exact snapshot under writer serialization

- [x] Capture identity triplet through `IdentityRepository` lock. (c6a993b — unchanged from FIX6/P0-002, `captureStorageSnapshot()`)
- [x] Capture broker secret through `BrokerSecretRepository` lock. (c6a993b)
- [x] Capture setup-input exact bytes/presence through `ConfigRepository` lock or dedicated repository API. (c6a993b — unchanged from FIX6, `captureSetupInputSnapshot`)
- [x] Capture exact config bytes/presence through `ConfigRepository` write mutex/locked snapshot API. (c6a993b — unchanged from FIX6, `configFileExists`/`readConfig()`)
- [x] Capture preferences from authoritative loaded state. (c6a993b — unchanged from FIX6, `loadPreferences()`)
- [x] Do not read a file outside its repository lock and later assume the snapshot is coherent. (c6a993b — each repository owns its own lock; the coordinator calls one atomic snapshot method per repository)
- [x] Snapshot failure aborts before the first mutation. (c6a993b — `captureSnapshot()` runs entirely before the stage loop; a thrown exception there returns `Failed(Snapshot, ...)` with an empty rollback)

Avoid deadlock: define and document one lock order. Recommended parent coordinator order:

```text
ConfigurationMutationCoordinator admission
SetupPersistenceCoordinator mutex
repository methods acquire one repository lock at a time; never hold two repository locks simultaneously
```

Capture each repository snapshot via an atomic repository method, then release its lock before the next repository call.

## P0-004-C — Roll back ordinary failure under `NonCancellable`

- [x] On stage failure, rollback all committed stages in reverse order. (c6a993b — unchanged behavior from FIX6, now also covers `BrokerSecret`)
- [x] Wrap the whole rollback call in `withContext(NonCancellable)`. (c6a993b — new; the FIX6 code did not wrap this)
- [x] Continue after individual rollback failure. (c6a993b — unchanged from FIX6)
- [x] Return every rollback result. (c6a993b — unchanged from FIX6)
- [x] Report `setup_rollback_incomplete` when any rollback fails. (c6a993b — unchanged from FIX6, `SetupSaveController.commitSetup`'s existing mapping)
- [x] Preserve fixed/redacted primary reason and rollback reasons. (c6a993b — unchanged from FIX6, `safeReason(...)`)

## P0-004-D — Roll back cancellation before rethrow

Use this target shape:

```kotlin
try {
    for (stage in requestedStages(request)) {
        val result = applyStage(stage, request)
        if (result.isFailure) {
            return@withLock failureWithRollback(
                failedStage = stage,
                failure = result.exceptionOrNull(),
                snapshot = snapshot,
                committed = committed,
            )
        }
        committed += stage
    }
    SetupPersistenceResult.Success(committed.toList())
} catch (cancelled: CancellationException) {
    val rollback = withContext(NonCancellable) {
        rollback(snapshot, committed)
    }
    rollback
        .filterIsInstance<SetupRollbackStageResult.Failure>()
        .forEach { failure ->
            cancelled.addSuppressed(
                SetupRollbackException(
                    stage = failure.stage,
                    message = failure.reason,
                ),
            )
        }
    if (rollback.any { it is SetupRollbackStageResult.Failure }) {
        reportSafely(
            code = "setup_cancelled_rollback_incomplete",
            message = "Cancelled setup could not be fully rolled back",
        )
    }
    throw cancelled
} finally {
    snapshot.wipeSecrets()
}
```

- [x] Cancellation is never converted to `SetupPersistenceResult.Failed`. (c6a993b — the stage loop's `catch (cancelled: CancellationException)` rolls back then rethrows; it never returns a `Failed`)
- [x] Rollback failure is not hidden merely because the caller is cancelled. (c6a993b — failed rollback stages are attached to the propagating `CancellationException` as suppressed `SetupRollbackException`s)
- [x] Reporter failure is caught after rollback; it does not replace cancellation. (c6a993b — deviation from the illustrative `reportSafely(...)` snippet, per binding note below)

Deviation: no `reportSafely(code, message)` reporter function exists in this codebase (the spec/TODO's snippet is illustrative). Instead, `SetupPersistenceCoordinator` attaches failed rollback stages as suppressed `SetupRollbackException`s on the propagating `CancellationException`, and `SetupSaveController.reportRollbackIncompleteIfPresent` (called synchronously in `runSaveAndApply`'s `catch (cancelled: CancellationException)`, before rethrowing) inspects `cancelled.suppressedExceptions` and sets the one required `setup_cancelled_rollback_incomplete` diagnostic via the existing `access.applyState` — no new reporter abstraction was introduced, and `IdentityRepository.kt` (listed in this task's file list) was not touched since no gap required it.

## P0-004-E — Controller mapping

- [x] Controller calls coordinator exactly once after isolated validation. (c6a993b — unchanged from FIX7 P0-003-D, `commitSetup`'s single `persistence.persist(request)` call)
- [x] Success appears only for `SetupPersistenceResult.Success`. (c6a993b — unchanged from FIX6/P0-003)
- [x] Ordinary rollback-complete failure maps to durable `setup_persistence_failed`. (c6a993b — unchanged from FIX6, `commitSetup`'s existing mapping)
- [x] Ordinary rollback-incomplete failure maps to durable `setup_rollback_incomplete`. (c6a993b — unchanged from FIX6)
- [x] Cancellation emits no normal success/failure snackbar, except the direct required rollback-incomplete diagnostic from the transaction layer. (c6a993b — `reportRollbackIncompleteIfPresent`)
- [x] Plaintext identity is wiped in all outcomes. (c6a993b — unchanged `finally` in `validateAndCommit`, now proven for persistence-failure/cancellation specifically too, see P0-004-F wiping tests)

## P0-004-F — Exact tests

Stage order and normal failure:

- [x] `allSetupStagesCommitInRequiredOrderIncludingBrokerSecret` (c6a993b)
- [x] `snapshotFailurePerformsNoMutation` (c6a993b)
- [x] `identityFailureStopsAllLaterStages` (c6a993b)
- [x] `authorizedKeysFailureRollsBackIdentity` (c6a993b)
- [x] `brokerSecretFailureRollsBackAuthorizedKeysAndIdentity` (c6a993b)
- [x] `setupInputFailureRollsBackBrokerSecretAuthorizedKeysAndIdentity` (c6a993b)
- [x] `preferencesFailureRollsBackSetupInputBrokerSecretAuthorizedKeysAndIdentity` (c6a993b)
- [x] `configFailureRollsBackEveryEarlierStage` (c6a993b)
- [x] `rollbackContinuesAfterEachIndividualRestoreFailure` (c6a993b)
- [x] `rollbackIncompleteReturnsEveryFailedRollbackStage` (c6a993b)

Cancellation—one test per meaningful point, not one generic test:

- [x] `cancellationBeforeFirstMutationPerformsNoRollbackAndPropagates` (c6a993b)
- [x] `cancellationDuringAuthorizedKeysRollsBackIdentityAndPropagates` (c6a993b)
- [x] `cancellationDuringBrokerSecretRollsBackAuthorizedKeysAndIdentity` (c6a993b)
- [x] `cancellationDuringSetupInputRollsBackBrokerSecretAuthorizedKeysAndIdentity` (c6a993b)
- [x] `cancellationDuringPreferencesRollsBackAllEarlierStages` (c6a993b)
- [x] `cancellationDuringConfigRollsBackAllEarlierStages` (c6a993b)
- [x] `cancellationRollbackContinuesAfterOneRestoreFailure` (c6a993b)
- [x] `cancellationRollbackFailureIsReportedAndAttachedAsSuppressed` (c6a993b)
- [x] `cancellationNeverReportsConfigurationSavedOrOrdinarySaveFailure` (c6a993b — `SetupSaveControllerTest`, controller-level integration)

Wiping:

- [x] `plaintextIdentityIsWipedOnSetupSuccess` (c6a993b — `SetupSaveControllerTest`)
- [x] `plaintextIdentityIsWipedOnValidationFailure` (c6a993b — `SetupSaveControllerTest`)
- [x] `plaintextIdentityIsWipedOnPersistenceFailure` (c6a993b — `SetupSaveControllerTest`)
- [x] `plaintextIdentityIsWipedOnCancellation` (c6a993b — `SetupSaveControllerTest`)
- [x] `brokerSecretSnapshotBytesAreWipedAfterSuccessFailureAndCancellation` (c6a993b — `SetupPersistenceCoordinatorTest`; required adding a small injectable `readBytes` seam to `BrokerSecretRepository`, mirroring its existing `atomicReplace` seam, so a test can observe the exact byte array a snapshot captured)

Concurrency:

- [x] `twoSetupCoordinatorCallsCannotOverlap` (c6a993b — renamed from FIX6's `twoConcurrentSaveRequestsCannotOverlap`)
- [x] `globalAdmissionPreventsSetupFromRacingImportForwardAndReset` (c6a993b — `ConfigurationMutationIntegrationTest`; consolidates the pairwise P0-001-D proofs into one test showing a single in-flight SetupSave rejects Import, ForwardMutation, and ConfigurationReset all at once)

## Acceptance

- [x] Setup is one transaction. (c6a993b)
- [x] Cancellation restores every earlier stage before propagation. (c6a993b)
- [x] Rollback failure is durable and specific. (c6a993b)
- [x] Config remains last. (c6a993b — unchanged)
- [x] No outer controller restore exists. (c6a993b — unchanged from FIX7 P0-003-D; `SetupSaveController` still has no identity snapshot/restore of its own)

---

# P0-005 — Make reset exact and cancellation-safe

**Review findings addressed:** CRITICAL-3, HIGH-7.

**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/TransactionalReset.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SettingsViewModel.kt
related tests
```


## P0-005-A — Exact setup-input snapshot API

- [x] Add `captureSetupInputFileSnapshot(): Result<ExactFileSnapshot>` under repository serialization. (dc5c14a — top-level in `ExactFileSnapshot.kt`, not a `ConfigRepository` member, to keep that file/class under detekt's TooManyFunctions threshold; takes the file explicitly like its FIX6 `captureSetupInputSnapshot` sibling)
- [x] Add `restoreSetupInputFileSnapshot(snapshot): Result<Unit>` using atomic replacement or checked deletion. (dc5c14a — `ConfigRepository.restoreSetupInputFileSnapshot`, open member so tests can inject a rollback-restore failure like every other reset stage)
- [x] Convert `saveSetupInput` to a consumed `Result<Unit>` or add an authoritative result-returning method used by transactions. (dc5c14a — via the new `restoreSetupInputFileSnapshot`; `saveSetupInput` itself is unchanged and still used for the *forward* reset mutation, which legitimately always writes a value)
- [x] Do not restore absent setup input by writing default JSON. (dc5c14a)

## P0-005-B — Reset snapshot model

Use:

```kotlin
data class ResetSnapshot(
    val config: ExactFileSnapshot,
    val setupInput: ExactFileSnapshot,
    val forwards: List<ForwardConfig>,
)
```

- [x] Wipe snapshot bytes in `finally` after reset/rollback finishes. (dc5c14a — `ResetSnapshot.wipeSecrets()`, only `setupInput` needs it: config.toml never embeds the broker password directly, only a path to it)
- [x] Snapshot capture cancellation propagates. (dc5c14a — unchanged from FIX6/P1-002, `captureSnapshot()`'s own catch rethrows `CancellationException`)
- [x] Any snapshot read failure aborts before mutation. (dc5c14a — unchanged from FIX6/P1-002)

## P0-005-C — Cancellation rollback

- [x] Track `mutatedStages` only after each successful mutation. (dc5c14a — unchanged from FIX6, `applyStages`)
- [x] On cancellation, run reverse rollback under `NonCancellable`. (dc5c14a — new; the FIX6 code did not do this, matching the same gap P0-004 fixed for setup persistence)
- [x] Attach rollback failures as suppressed to the original cancellation. (dc5c14a — `ResetRollbackException`)
- [x] Emit direct `reset_cancelled_rollback_incomplete` only when rollback is incomplete. (dc5c14a — `SettingsViewModel.resetConfiguration()`'s `catch (cancelled: CancellationException)`)
- [x] Rethrow the original cancellation. (dc5c14a)

Deviation: no `reportSafely(...)` reporter exists in this codebase (as with P0-004); the top-level pure function `resetCancelledRollbackIncompleteMessage` (kept top-level, not a `SettingsViewModel` member, to stay under detekt's TooManyFunctions threshold) computes the message from `cancelled.suppressedExceptions`, and the ViewModel publishes it via the existing `publishOperationFailure`.

## P0-005-D — Settings state mapping

- [x] Normal rollback-complete reset failure uses durable `reset_failed`. (dc5c14a — unchanged from FIX6, `resetFailureVisibleCode`)
- [x] Normal rollback-incomplete uses `reset_rollback_incomplete`. (dc5c14a — unchanged from FIX6)
- [x] Busy rejection uses `configuration_operation_busy`. (dc5c14a — unchanged from FIX7 P0-001-C)
- [x] Success clears prior failure. (dc5c14a — unchanged from FIX6, `handleResetResult`'s `clearOperationFailure()`)
- [x] Cancellation does not emit ordinary reset failure/success. (dc5c14a — `handleResetResult` is never reached on cancellation; only the one required rollback-incomplete diagnostic may publish)

## P0-005-E — Tests

Exact state:

- [x] `resetSnapshotDistinguishesAbsentSetupInputFromDefaultSetupInput` (dc5c14a — `TransactionalResetExactSnapshotTest`)
- [x] `failedResetRestoresAbsentSetupInputAsAbsent` (dc5c14a)
- [x] `failedResetRestoresPresentEmptySetupInputExactly` (dc5c14a — uses deliberately unusual JSON formatting to prove byte-exact, not re-serialized, restoration)
- [x] `setupInputSnapshotReadFailureAbortsBeforeMutation` (dc5c14a)

Cancellation:

- [x] `cancellationDuringSetupInputResetRestoresConfig` (dc5c14a)
- [x] `cancellationDuringForwardsResetRestoresSetupInputAndConfig` (dc5c14a)
- [x] `resetCancellationRollbackContinuesAfterRestoreFailure` (dc5c14a)
- [x] `resetCancellationRollbackFailureIsReportedAndSuppressed` (dc5c14a)
- [x] `resetCancellationDoesNotReportSuccessOrOrdinaryFailure` (dc5c14a — `SettingsViewModelTest`)

Normal rollback:

- [x] `oneRollbackFailureDoesNotPreventRemainingResetRestores` (dc5c14a)
- [x] `resetRollbackIncompleteListsEveryFailedRestore` (dc5c14a)
- [x] `resetSnapshotSecretBytesAreWiped` (dc5c14a — required adding a `setupInputReadBytes` seam to `TransactionalResetCoordinator`'s constructor, mirroring `BrokerSecretRepository`'s `readBytes` seam)

## Acceptance

- [x] Reset restores exact absence/presence/bytes. (dc5c14a)
- [x] Cancellation cannot leave an earlier reset stage committed silently. (dc5c14a)
- [x] Settings failure state is durable and truthful. (dc5c14a)

---

# P0-006 — Make identity commit and snapshot restoration cancellation-safe and exhaustive

**Review findings addressed:** CRITICAL-4, HIGH-4, HIGH-6.

**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/security/IdentityRepository.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/security/IdentityPersistenceAtomicityTest.kt
new focused identity restore tests
```

## P0-006-A — Detailed restore results

- [ ] Add an enum for `EncryptedIdentity`, `PublicIdentity`, `AuthorizedKeys`.
- [ ] `restoreStorageSnapshot` returns a list of per-file success/failure results.
- [ ] It attempts all three files even after one failure.
- [ ] It uses atomic replacement for present snapshots.
- [ ] It uses checked deletion for absent snapshots.
- [ ] It redacts reasons before returning them to callers.
- [ ] Its returned result is statically required to be consumed.

Target loop:

```kotlin
fun restoreStorageSnapshot(
    snapshot: IdentityStorageSnapshot,
): List<IdentityRestoreResult> =
    synchronized(storageLock) {
        listOf(
            IdentityStorageFile.EncryptedIdentity to
                Pair(identityFile, snapshot.encryptedIdentity),
            IdentityStorageFile.PublicIdentity to
                Pair(publicFile, snapshot.publicIdentity),
            IdentityStorageFile.AuthorizedKeys to
                Pair(authorizedKeysFile, snapshot.authorizedKeys),
        ).map { (logical, pair) ->
            val (file, fileSnapshot) = pair
            restoreIdentityFile(logical, file, fileSnapshot)
        }
    }
```

An explicit loop may be clearer than nested pairs; use readable production code.

## P0-006-B — Identity pair rollback after cancellation

- [ ] After encrypted identity replacement succeeds, catch cancellation from public replacement.
- [ ] Restore encrypted and public snapshots synchronously before rethrowing cancellation.
- [ ] Attempt both restores independently.
- [ ] Attach every restore failure as suppressed to cancellation.
- [ ] Emit/report `identity_rollback_incomplete` through the owning transaction if restore failed.

Do not leave the current branch:

```kotlin
catch (cancelled: CancellationException) {
    throw cancelled
}
```

without recovery.

## P0-006-C — Preserve rollback causes

- [ ] `IdentityRollbackIncompleteException` retains the forward failure as cause.
- [ ] Every rollback failure is attached as suppressed.
- [ ] Error message does not contain identity content or raw file bytes.
- [ ] One restore failure does not prevent the second restore attempt.

Suggested helper:

```kotlin
private fun restoreIdentityPair(
    priorEncrypted: StoredFileSnapshot,
    priorPublic: StoredFileSnapshot,
): List<Exception> {
    val failures = mutableListOf<Exception>()

    restorePairFileResult(identityFile, priorEncrypted)
        .exceptionOrNull()
        ?.let(failures::add)

    restorePairFileResult(publicFile, priorPublic)
        .exceptionOrNull()
        ?.let(failures::add)

    return failures
}
```

## P0-006-D — Snapshot coherence

- [ ] Setup transaction captures identity storage through one locked method.
- [ ] No caller reads files separately outside `storageLock` to construct a supposed identity snapshot.
- [ ] Document single-process/JVM-lock assumption.

## P0-006-E — Tests

- [ ] `cancellationDuringPublicIdentityReplaceRestoresPriorEncryptedAndPublicPair`
- [ ] `cancellationRollbackFailureIsSuppressedAndCancellationPropagates`
- [ ] `encryptedRestoreFailureDoesNotSkipPublicRestore`
- [ ] `publicRestoreFailureDoesNotEraseEncryptedRestoreResult`
- [ ] `identityRollbackIncompleteExceptionContainsEveryRollbackFailure`
- [ ] `restoreStorageSnapshotAttemptsAllThreeFilesAfterFirstFailure`
- [ ] `restoreStorageSnapshotDeletesFilesThatWerePreviouslyAbsent`
- [ ] `failedDeleteIsReturnedAsRestoreFailure`
- [ ] `concurrentSnapshotAndAuthorizedKeyAppendAreSerialized`
- [ ] `concurrentSnapshotAndIdentityCommitAreSerialized`
- [ ] `plaintextIdentityNeverReachesDiskOnAnyFailurePath`

## Acceptance

- [ ] Pair mismatch cannot survive cancellation silently.
- [ ] All restore members are attempted.
- [ ] Forward and rollback failures remain inspectable without leaking secrets.

---

# P0-007 — Quarantine every failed stop-like operation and fix unverified-start cleanup cancellation

**Review findings addressed:** CRITICAL-5, CRITICAL-7, HIGH-10.

**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/UnverifiedStartContext.kt
service/lifecycle tests
```

## P0-007-A — Central quarantine transition

- [ ] Add one helper or extracted collaborator that performs safety state changes before reporting.
- [ ] Set `nativeStopVerified = false`.
- [ ] Set `nativeRuntimeUncertain = true`.
- [ ] Invalidate pending policy retry.
- [ ] Update repository/service state to Error/uncertain as appropriate.
- [ ] Publish fixed/redacted error through a safely guarded reporter.

```kotlin
private fun enterNativeRuntimeQuarantine(
    code: String,
    message: String,
) {
    nativeStopVerified.set(false)
    nativeRuntimeUncertain.set(true)
    invalidatePendingPolicyRetry()
    repository.setLocalError(
        code = "native_runtime_quarantined",
        message = "Native runtime state is uncertain; a verified stop is required",
    )
    publishErrorSafely(
        code = code,
        message = SensitiveDataRedactor.redactText(message),
    )
}
```

Use actual repository/reporting APIs. Do not publish before setting quarantine.

## P0-007-B — Apply to every stop-like failure

- [ ] Explicit STOP failure.
- [ ] Explicit STOP final-status verification failure.
- [ ] Manual pause native stop failure.
- [ ] Policy pause native stop failure.
- [ ] Start-verification cleanup failure.
- [ ] Observed destroy fallback stop failure.
- [ ] Any new stop-like helper introduced by FIX7.

Codes should remain specific (`manual_pause_stop_failed`, etc.) while durable state also shows `native_runtime_quarantined` semantics.

## P0-007-C — Block every start/resume/retry while quarantined

- [ ] ACTION start path.
- [ ] manual Resume.
- [ ] policy Resume.
- [ ] pending policy retry after startup completion/failure.
- [ ] automatic reconnect/start path if present.
- [ ] start-from-review path after service receives intent.

- [ ] Guard failure must be durably visible, not silently discarded by a policy retry helper.
- [ ] No native start call occurs.
- [ ] Pending generation/token is cleared.

## P0-007-D — Only verified explicit STOP clears quarantine

- [ ] Successful pause does not clear pre-existing quarantine.
- [ ] Successful start never clears quarantine.
- [ ] Destroy best-effort completion does not claim authoritative recovery unless the explicit stop verification contract is met.
- [ ] Verified explicit STOP sets `nativeRuntimeUncertain = false` and `nativeStopVerified = true`.

## P0-007-E — Fix `cleanupUnverifiedStart`

- [ ] Remove suspend `stop()` from `runCatching`.
- [ ] Define mandatory cleanup under `NonCancellable`.
- [ ] Preserve incoming/original cancellation in the caller.
- [ ] If cleanup stop fails, enter runtime quarantine before cancellation/error propagation.
- [ ] Reporter failure cannot hide quarantine.

One acceptable orchestration:

```kotlin
internal suspend fun attemptUnverifiedStartCleanup(
    stop: suspend () -> Result<Unit>,
): Result<Unit> =
    withContext(NonCancellable) {
        try {
            stop()
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
```

The caller catches cancellation around verification, invokes cleanup in `NonCancellable`, quarantines on cleanup failure, then rethrows cancellation.

## P0-007-F — Tests

Quarantine:

- [ ] `manualPauseStopFailureEntersRuntimeQuarantine`
- [ ] `policyPauseStopFailureEntersRuntimeQuarantine`
- [ ] `startVerificationCleanupFailureEntersRuntimeQuarantine`
- [ ] `destroyFallbackStopFailureEntersRuntimeQuarantineWhenObserved`
- [ ] `quarantineClearsPendingPolicyRetry`

Start blocking:

- [ ] `startAfterManualPauseFailureDoesNotCallNative`
- [ ] `resumeAfterPolicyPauseFailureDoesNotCallNative`
- [ ] `pendingPolicyRetryAfterQuarantineDoesNotCallNative`
- [ ] `quarantineGuardFailureIsDurableAndVisible`
- [ ] `verifiedExplicitStopClearsQuarantineAndAllowsLaterStart`
- [ ] `failedExplicitStopDoesNotClearQuarantine`

Cleanup cancellation:

- [ ] `unverifiedStartCleanupRunsWhenVerificationCoroutineIsCancelled`
- [ ] `unverifiedStartCleanupCancellationDoesNotBecomeOrdinaryFailure`
- [ ] `cleanupFailureDuringCancellationQuarantinesThenPropagatesCancellation`
- [ ] `cleanupReporterFailureCannotPreventQuarantine`

Use barriers and native-call recorders.

## Acceptance

- [ ] No failed native stop-like operation permits later start/resume.
- [ ] Cancellation cannot skip mandatory unverified-start cleanup.
- [ ] Runtime uncertainty is durable and visible.

---

# P0-008 — Fix cooperative offer stop while Listening with no peer

**Review finding addressed:** HIGH-15 and FIX6 signoff follow-up.

**Files:**

```text
crates/p2p-daemon/src/offer/mod.rs
crates/p2p-daemon offer tests
crates/p2p-mobile/src/runtime/mod.rs
crates/p2p-mobile/src/runtime/tests.rs
Android stop integration tests if needed
```

## P0-008-A — Reproduce before changing code

- [ ] Add a Rust test using the real offer daemon/status/shutdown seam.
- [ ] Reach `Listening`/waiting state with at least one bound forward and no peer/session.
- [ ] Request cooperative shutdown.
- [ ] Await daemon result.
- [ ] Confirm current baseline returns `Err` and identify exact finalizer/worker result that produces it.
- [ ] Record the root cause in a code comment near the fix.

Do not “fix” only `p2p-mobile` by mapping every error during shutdown to success. The daemon must distinguish cooperative stop from real failure correctly.

## P0-008-B — Correct daemon result precedence

- [ ] Shutdown with no prior primary failure returns `Ok(())` after successful cleanup/drain.
- [ ] A real pre-shutdown primary error remains `Err`.
- [ ] Cleanup/drain failure remains `Err`.
- [ ] A worker exit that is expected because shutdown was requested is not promoted to primary failure.
- [ ] Status finishes in stopped/idle truth, not Listening.

Target precedence:

```rust
match (primary_error, cleanup_result, shutdown.is_shutdown_requested()) {
    (Some(error), _, _) => Err(error),
    (None, Err(error), _) => Err(error),
    (None, Ok(()), true) => Ok(()),
    (None, Ok(()), false) => Ok(()),
}
```

Adapt to actual types. Preserve existing shutdown race gates.

## P0-008-C — Mobile mapping

- [ ] Successful offer task completion maps to `AndroidRuntimeState::Stopped`.
- [ ] Real daemon `Err` still maps to Error.
- [ ] Stop verification sees final Stopped.
- [ ] No false success is introduced for cleanup failure.

## P0-008-D — Tests

- [ ] `offerShutdownWhileListeningWithoutPeerReturnsOk`
- [ ] `offerShutdownWhileListeningPublishesFinalStoppedStatus`
- [ ] `offerShutdownAfterPrimaryFailureStillReturnsPrimaryFailure`
- [ ] `offerShutdownCleanupFailureStillReturnsFailure`
- [ ] `expectedAcceptWorkerExitDuringShutdownIsNotAnError`
- [ ] `mobileRuntimeMapsCooperativeOfferShutdownToStopped`
- [ ] `androidStopWhileListeningWithoutPeerReportsStoppedNotError`

The Android test may use JNI/integration seam or emulator E2E, but the Rust daemon and mobile runtime tests are mandatory.

## Acceptance

- [ ] User Stop while Listening/no peer ends in Stopped.
- [ ] Real failures are not hidden.
- [ ] Existing carefully built shutdown race tests remain green.

---

# P0-009 — Make network fail-closed handling independent of reporter success

**Review findings addressed:** HIGH-8, HIGH-9, MEDIUM-6, P0-006 partial.

**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/network/NetworkPolicyManager.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/network/NetworkMonitorSupervisor.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
related tests
```

## P0-009-A — Add safe reporter wrapper

- [ ] Required safety actions occur before reporting.
- [ ] Catch `Exception`, not `Throwable`.
- [ ] Redact reporter failure message.
- [ ] Do not recursively invoke the same failing reporter to report reporter failure.
- [ ] Log a fixed safe secondary message.

```kotlin
internal fun reportNetworkDiagnosticSafely(
    reporter: NetworkPolicyDiagnosticReporter,
    code: String,
    message: String,
) {
    try {
        reporter.report(code, message)
    } catch (error: Exception) {
        Log.e(
            NETWORK_TAG,
            "Network diagnostic reporter failed: " +
                SensitiveDataRedactor.redactText(
                    error.message ?: "unknown reporter failure",
                ),
        )
    }
}
```

## P0-009-B — Classification failure order

For callback and initial/refresh classification:

- [ ] Catch classifier `Exception`.
- [ ] Produce canonical Unknown with `allowMetered = false`.
- [ ] Assign internal status first.
- [ ] Try to deliver status.
- [ ] Report classification failure safely last.
- [ ] A throwing reporter cannot cause the callback to throw.

## P0-009-C — Monitor-supervisor safety order

On register/upstream/unregister/collection failure:

- [ ] Update repository to fail-closed Unknown.
- [ ] Submit policy-blocked lifecycle command.
- [ ] If command submission fails, expose `lifecycle_processor_unavailable` and stop service/control flow.
- [ ] Report monitor failure safely.
- [ ] Retry with bounded backoff only while lifecycle processor/control plane remains available.
- [ ] Cancellation publishes nothing and retries nothing.

## P0-009-D — Validate backoff constructor

- [ ] `initialDelayMs > 0`.
- [ ] `maxDelayMs >= initialDelayMs`.
- [ ] multiplier/growth factor valid and finite.
- [ ] delay calculation cannot overflow to negative.
- [ ] cap is enforced.

## P0-009-E — Tests

- [ ] `classifierFailureAppliesBlockedUnknownBeforeReporterCall`
- [ ] `throwingClassificationReporterDoesNotEscapeCallback`
- [ ] `throwingDeliveryReporterDoesNotPreventStatusUpdate`
- [ ] `registerFailureBlocksTunnelEvenWhenReporterThrows`
- [ ] `upstreamFailureBlocksTunnelEvenWhenReporterThrows`
- [ ] `unregisterFailureReporterThrowDoesNotEscapeCleanup`
- [ ] `monitorFailureSubmitsPolicyBlockedBeforeReporting`
- [ ] `failedPolicyBlockedSubmissionStopsSupervisorAndIsVisible`
- [ ] `constructorClassificationFailureProducesBlockedUnknown`
- [ ] `refreshClassificationFailureProducesBlockedUnknown`
- [ ] `monitorCancellationWithThrowingReporterStillExitsWithoutRetry`
- [ ] `invalidBackoffParametersAreRejected`
- [ ] `backoffCalculationIsCappedAndCannotOverflow`

## Acceptance

- [ ] Reporter failure cannot defeat safety state.
- [ ] Classification is fail-closed in every entry path.
- [ ] Dead control plane is escalated, not silently retried.

---

# P0-010 — Make Rust wall-clock behavior consistent across the workspace

**Review finding addressed:** CRITICAL-8, P2-002 failure.

**Files:**

```text
crates/p2p-core/src/time.rs
crates/p2p-mobile/src/runtime/state.rs
crates/p2p-daemon/src/messages.rs
crates/p2p-signaling/src/transport/codec.rs
crates/p2p-signaling/src/error.rs
all other inventory hits
Rust tests
```

## P0-010-A — Classify call sites

For every `SystemTime`/Unix timestamp call, classify it as:

1. **Correctness-sensitive**: replay freshness, protocol timestamp, retry timing, expiration. Must return/propagate typed error.
2. **Diagnostics-only**: log timestamp. May use `Option<u64>` or omit timestamp; must not return zero as valid.

- [ ] Record the call-site inventory and classification in the commit or TODO evidence.

## P0-010-B — Remove `resolve_unix_ms` zero fallback

- [ ] Do not initialize a last-known atomic to zero and return it on first failure.
- [ ] Prefer deleting `resolve_unix_ms` if its semantics are ambiguous.
- [ ] If retaining last-known fallback for diagnostics, return `Option<u64>`:

```rust
pub fn resolve_optional_unix_ms(
    fresh: Result<u64, SystemTimeError>,
    last: &AtomicU64,
) -> Option<u64> {
    match fresh {
        Ok(ms) => {
            last.store(ms, Ordering::Relaxed);
            Some(ms)
        }
        Err(_) => {
            let prior = last.load(Ordering::Relaxed);
            (prior != 0).then_some(prior)
        }
    }
}
```

Zero is a sentinel only inside the atomic and never escapes as a timestamp.

## P0-010-C — Protocol codec typed error

Replace:

```rust
.expect("system clock is before unix epoch")
```

with fallible propagation.

One target:

```rust
fn current_time_ms() -> Result<u64, SignalingError> {
    p2p_core::time::unix_time_ms().map_err(|error| {
        SignalingError::Clock(format!(
            "system clock is unavailable: {error}"
        ))
    })
}
```

Then:

```rust
now_ms: current_time_ms()?,
```

Use a fixed/typed error without leaking unnecessary system details over the wire.

## P0-010-D — Daemon retry/message behavior

- [ ] Correctness-sensitive retry timestamps return `Result` to caller.
- [ ] Do not reuse a stale timestamp for retry deadlines unless the algorithm explicitly proves it safe.
- [ ] Do not build a protocol message with timestamp zero.
- [ ] A timestamp error preserves any prior primary runtime error.

## P0-010-E — Mobile diagnostic logs

- [ ] Change `AndroidLogEvent.unix_ms` to `Option<u64>` if feasible, or skip an optional log entry when time is unavailable.
- [ ] Do not invent zero.
- [ ] Runtime state/last error remains authoritative even if log timestamp is unavailable.

If JNI/JSON schema requires a numeric field, change schema deliberately and update consumers; do not retain zero for convenience.

## P0-010-F — Inject clock seams

- [ ] Add a small clock trait/function parameter for deterministic pre-epoch tests.
- [ ] Do not mutate system clock in tests.

Example:

```rust
pub trait UnixClock: Send + Sync {
    fn unix_time_ms(&self) -> Result<u64, SystemTimeError>;
}
```

A function pointer seam is acceptable if simpler.

## P0-010-G — Tests

- [ ] `firstClockFailureReturnsNoneForDiagnosticTimestampNotZero`
- [ ] `subsequentDiagnosticClockFailureMayReuseNonZeroKnownTimestamp`
- [ ] `signalingDecodeClockFailureReturnsTypedErrorAndDoesNotPanic`
- [ ] `signalingDecodeClockFailureDoesNotRecordReplayEntry`
- [ ] `daemonMessageBuildClockFailureReturnsError`
- [ ] `daemonRetryClockFailureDoesNotUseZeroDeadline`
- [ ] `mobileLogClockFailurePreservesPrimaryRuntimeState`
- [ ] `workspaceContainsNoPreEpochExpectOrUnwrapOrZeroFallback`

The last item must be backed by source inventory plus tests/static check; do not use a brittle grep as the only guard.

## Acceptance

- [ ] No production pre-epoch panic remains.
- [ ] First clock failure never yields zero.
- [ ] Protocol time failure is explicit.
- [ ] Optional diagnostics degrade without corrupting primary truth.

---

# P1 — High-priority integration and truthfulness

# P1-001 — Fix import rejection, candidate cleanup, and private-byte wiping

**Review findings addressed:** HIGH-1, HIGH-2, HIGH-3, MEDIUM-4.

**Files:**

```text
ImportExportViewModel.kt
ImportExportService.kt
ForwardsViewModel.kt
SetupSaveController.kt candidate validation helper
related tests
```

## P1-001-A — Visible import busy state

- [ ] Use global coordinator.
- [ ] Busy rejection sets durable `lastOperationFailure` with `configuration_operation_busy`.
- [ ] It clears/sets `isBusy` truthfully.
- [ ] It may mirror snackbar.
- [ ] No bare `return@launch`.

## P1-001-B — Cancellation-safe UI busy cleanup

- [ ] Set `isBusy = true` only after admission succeeds.
- [ ] Clear `isBusy` in non-suspending `finally` state assignment.
- [ ] Cancellation rethrows and emits no ordinary result message.
- [ ] A destroyed ViewModel naturally disappears; no extra global UI write is required.

Target pattern:

```kotlin
viewModelScope.launch {
    when (
        val admission = deps.configurationMutationCoordinator.tryRun(
            ConfigurationOperation.ConfigImport,
        ) {
            state.value = state.value.copy(isBusy = true)
            try {
                service.importConfig(...)
            } finally {
                state.value = state.value.copy(isBusy = false)
            }
        }
    ) {
        is ConfigurationAdmission.Completed -> handleResult(admission.value)
        is ConfigurationAdmission.Busy -> publishBusy(admission.active)
    }
}
```

Ensure cancellation from the block is not converted into `Completed(Result.failure(...))` by an inner helper.

## P1-001-C — Use scoped candidate helper

- [ ] Import config uses `withCandidateFile`.
- [ ] Forward activation uses `withCandidateFile`.
- [ ] Setup candidate validation uses workspace helper.
- [ ] Remove all bare/discarded `deleteCandidateFileSafely` caller calls.
- [ ] Cleanup-only failure maps to durable `candidate_cleanup_failed`.

## P1-001-D — Wipe imported private bytes

- [ ] Hold canonical private bytes in a nullable variable.
- [ ] Wipe in `finally` after success/failure/cancellation.
- [ ] Do not retain canonical private string longer than necessary; where API permits, convert and clear references promptly.

## P1-001-E — Tests

- [ ] `secondConfigImportIsRejectedVisiblyWithActiveOperation`
- [ ] `cancelledImportClearsBusyAndEmitsNoOrdinaryResult`
- [ ] `configImportCleanupFailureAfterWriteSuccessReportsFailureNotImported`
- [ ] `configImportPrimaryFailurePreservedWhenCleanupAlsoFails`
- [ ] `configImportCancellationPreservedWhenCleanupAlsoFails`
- [ ] `forwardCandidateCleanupFailurePreventsSavedSuccess`
- [ ] `forwardPrimaryFailurePreservedWhenCleanupAlsoFails`
- [ ] `importedPrivateBytesWipedOnSuccess`
- [ ] `importedPrivateBytesWipedOnValidationFailure`
- [ ] `importedPrivateBytesWipedOnPersistenceFailure`
- [ ] `importedPrivateBytesWipedOnCancellation`

## Acceptance

- [ ] No import is silently dropped.
- [ ] No secret-bearing candidate cleanup failure is silent.
- [ ] Imported private bytes are wiped in all outcomes.

---

# P1-002 — Clear stale native truth and surface lifecycle processor death

**Review findings addressed:** HIGH-11, HIGH-12, P1-001 partial.

**Files:**

```text
TunnelRepository.kt
TunnelLifecycleCoordinator.kt
TunnelForegroundService.kt
Models.kt comments
related tests
```

## P1-002-A — Clear stale fields in every invalid-status branch

- [ ] Decode failure clears remote peer, active sessions, MQTT connected, and stale active forward/session metrics.
- [ ] Unknown native mode does the same.
- [ ] Missing required status field does the same.
- [ ] Terminal state continues clearing peer.
- [ ] Model comments describe that these are current truth, not last-known truth.

Add a helper so branches cannot diverge.

## P1-002-B — Observe lifecycle processor completion

- [ ] Expose processor state or completion callback.
- [ ] Differentiate expected `stop()` from unexpected cancellation/failure.
- [ ] Unexpected death sets durable `lifecycle_processor_failed`.
- [ ] If native runtime may be active, enter runtime quarantine.
- [ ] Stop accepting start/resume.
- [ ] Service stops or enters explicit Error; do not merely log dropped commands.

## P1-002-C — Submission failure mapping

- [ ] Every required `trySubmit == false` site consumes the false result.
- [ ] Teardown-late benign submissions may be logged at debug only when service is known destroyed.
- [ ] Active-service submission failure is durable and escalated.

## P1-002-D — Tests

- [ ] `decodeFailureClearsPreviousRemotePeerSessionAndMqttTruth`
- [ ] `unknownNativeModeClearsPreviousRemotePeerSessionAndMqttTruth`
- [ ] `newValidStatusAfterInvalidStatusUsesOnlyNewFields`
- [ ] `unexpectedLifecycleProcessorFailureIsDurable`
- [ ] `unexpectedLifecycleProcessorFailureQuarantinesPossibleRuntime`
- [ ] `activeServiceCommandSubmissionFailureIsNotSilentlyDropped`
- [ ] `teardownLateSubmissionRemainsBenignAndDoesNotCrash`

## Acceptance

- [ ] Invalid status never displays stale live connection truth.
- [ ] A dead processor cannot leave the service pretending to be controlled.

---

# P1-003 — Remove remaining main-thread startup I/O

**Review finding addressed:** HIGH-13, P1-003 partial.

**Files:**

```text
WebRtcTunnelApplication.kt
AppDependencies.kt
ForwardsConfigStore.kt
ForwardsRepository.kt
NetworkPolicyManager.kt
AppInitializationCoordinator.kt
startup tests
```

## P1-003-A — Inventory constructor side effects

- [ ] Identify every constructor/property initializer executed by `AppDependencies(this)`.
- [ ] Record disk reads, JSON parsing, network classification, binder calls, and native initialization.
- [ ] Keep native bridge lazy.

## P1-003-B — Move work off main

- [ ] Make forwards loading lazy/asynchronous with explicit Initializing/Ready/Failed state if needed.
- [ ] Make network initial classification guarded/lazy and fail-closed.
- [ ] Do not synchronously read setup/config/preferences in `Application.onCreate`.
- [ ] Notification channel setup may remain; it is Android-required lightweight initialization.

## P1-003-C — Initialization coordinator idempotence

- [ ] `start()` may be called once or is explicitly idempotent.
- [ ] Repeated start does not launch duplicate initialization.
- [ ] Exact `Initializing`, `Ready`, and `Failed` paths are tested.
- [ ] Start gating consumes state without blocking main.

## P1-003-D — Tests

- [ ] `applicationOnCreateDoesNotReadForwardsOnMainThread`
- [ ] `applicationOnCreateDoesNotClassifyNetworkOnMainThread`
- [ ] `applicationOnCreateDoesNotPerformConfigFileIoOnMainThread`
- [ ] `initializationStartIsIdempotent`
- [ ] `startWhileExactlyInitializingDoesNotCallNative`
- [ ] `startAfterReadyCallsNative`
- [ ] `startAfterFailedInitializationIsDurableAndVisible`

Use injected fakes/dispatchers or StrictMode-style test seams; do not infer coverage from unrelated service tests.

## Acceptance

- [ ] Identified disk/network work is absent from main-thread `onCreate`.
- [ ] Readiness tests execute all three states.

---

# P1-004 — Make required network-policy failures durable and harden ViewModel boundaries

**Review findings addressed:** HIGH-14, MEDIUM-1, P1-008/P1-009 partial.

**Files:**

```text
NetworkPolicyViewModel.kt
network policy UI state/model/composable
ForwardsViewModel.kt
ImportExportViewModel.kt
SettingsViewModel.kt
SetupSaveController.kt
related tests
```

## P1-004-A — Durable network-policy state

- [ ] Add `NetworkPolicyUiState.lastOperationFailure` or equivalent.
- [ ] Preference save failure sets fixed/redacted durable failure.
- [ ] Success clears it.
- [ ] Snackbar mirrors only.
- [ ] New collector/recreation sees the failure until acknowledged or a success clears it.

## P1-004-B — Flow exception handling

- [ ] `evaluateWithPolicy` exception does not terminate `networkStatus` flow.
- [ ] Emit canonical fail-closed Unknown.
- [ ] Store/report classification failure safely.

## P1-004-C — Boundary redaction

- [ ] Audit every `OperationFailure` assignment and ViewModel error state assignment.
- [ ] Redact before assignment.
- [ ] Prefer fixed safe messages for config write, candidate cleanup, identity persistence, reset, and network preference errors.
- [ ] Remove comments that defer redaction to future work.

## P1-004-D — Tests

- [ ] `networkPreferenceFailureRemainsInStateWithoutSnackbarCollector`
- [ ] `networkPreferenceSuccessClearsPriorFailure`
- [ ] `networkPolicyFailureMessageRedactsPasswordTokenApiKeyAndPrivateKey`
- [ ] `networkStatusEvaluationFailureEmitsBlockedUnknownAndFlowContinues`
- [ ] `allMutatingViewModelFailureStatesRejectSecretSentinel`

## Acceptance

- [ ] Network policy joins the durable-failure contract.
- [ ] No required ViewModel failure depends only on snackbar.
- [ ] Failure state is redacted at assignment boundary.

---

# P1-005 — Complete unsafe fallback, temp cleanup, and exception audits

**Review findings addressed:** MEDIUM-2 through MEDIUM-6 and remaining dangerous/silent fallback audit.

**Files:**

```text
ForwardsConfigStore.kt
SnackbarController.kt
all production Kotlin inventory hits
network backoff code
related tests/config
```

## P1-005-A — Forward store temp deletion

- [ ] Replace ignored temp deletion with checked cleanup composition.
- [ ] Preserve primary save failure and suppress cleanup failure.
- [ ] Successful save + cleanup failure returns failure.
- [ ] No raw temp path/secret content in logs.

## P1-005-B — `runCatching` audit

For every production `runCatching`:

- [ ] Remove it from suspend orchestration.
- [ ] Remove it from persistence/rollback/native cleanup.
- [ ] Replace safety-critical uses with explicit cancellation-first `try/catch (Exception)`.
- [ ] Ensure fatal `Error` propagates.
- [ ] Document why retained synchronous parser/utility uses cannot encounter coroutine cancellation and are safe to normalize.

The broker TCP probe may use explicit `try/catch (Exception)` and must redact its failure message. Do not catch `Throwable` through `runCatching`.

## P1-005-C — Snackbar lossiness

Snackbar remains convenience-only.

- [ ] Document it as lossy/non-authoritative.
- [ ] Consider returning Boolean from `show` or logging a debug-only drop.
- [ ] Do not promote snackbar delivery failure to operation failure when durable state already owns the error.
- [ ] No required failure exists only in snackbar.

## P1-005-D — Backoff validation

- [ ] Implement constructor invariants from P0-009.
- [ ] Add overflow-safe delay calculation.

## P1-005-E — Tests/static regression fixtures

- [ ] `forwardStorePrimaryFailurePreservesAndSuppressesCleanupFailure`
- [ ] `forwardStoreCleanupFailureAfterSuccessReturnsFailure`
- [ ] `fatalErrorFromMutationIsNotConvertedToOrdinaryFailure`
- [ ] `cancellationFromEachAuditedSuspendPathPropagates`
- [ ] `snackbarDropDoesNotEraseDurableFailure`
- [ ] `retainedRunCatchingInventoryContainsOnlyApprovedSynchronousSites`

## Acceptance

- [ ] No dangerous production `runCatching` remains.
- [ ] No silent temp cleanup remains.
- [ ] Optional snackbar loss cannot erase required truth.

---

# P2 — Test quality, static enforcement, and final evidence

# P2-001 — Replace indirect, misnamed, and sleep-based proof tests

**Review findings addressed:** MEDIUM-7 through MEDIUM-11 and Test-quality discrepancies.

## P2-001-A — Remove proof sleeps

- [ ] Replace the remaining setup overlap `Thread.sleep` with a `CompletableDeferred`/barrier.
- [ ] Search all Android tests.
- [ ] A bounded poll may remain only for positive external state convergence where no event seam exists; document each remaining occurrence.
- [ ] No `Thread.sleep` proves absence, exactly-once, ordering, or overlap.

## P2-001-B — Remove misleading coverage claims

Replace or rename tests so the following behaviors have exact production-path tests:

- [ ] validation performs no live persistent mutation;
- [ ] cancellation at every setup stage rolls back prior state;
- [ ] plaintext identity wipe on success, validation failure, persistence failure, and cancellation;
- [ ] two real rapid imports use global admission and unique workspace/candidate behavior;
- [ ] cleanup composition at real import and forward callers;
- [ ] exact Initializing start gate;
- [ ] exact Ready start path;
- [ ] late startup completion after destroy;
- [ ] first-use Rust clock failure;
- [ ] offer stop while Listening.

Do not retain an old test name if the body does not prove it.

## P2-001-C — Test quality rules

- [ ] Rollback-failure tests fail a restore operation, not a forward operation.
- [ ] Cancellation tests assert durable state after rollback, not only thrown exception.
- [ ] Wipe tests observe the actual ByteArray instance through a seam.
- [ ] Concurrency tests prove the first operation acquired admission before starting the second.
- [ ] Reporter-failure tests throw from the actual production reporter callback.
- [ ] Clock tests inject failure before any successful clock sample.

## Acceptance

- [ ] Test names and bodies agree.
- [ ] No elapsed-time proof remains.
- [ ] Every FIX7 invariant has at least one exact negative-path test.

---

# P2-002 — Expand type-aware ignored-result enforcement

**Review finding addressed:** MEDIUM-12, P2-003 partial.

**Files:**

```text
Android annotations/APIs
build.gradle.kts lint configuration
Detekt rule/config/tests if needed
.github/workflows/ci.yml
```

## P2-002-A — Annotate authoritative results

At minimum enforce consumption for:

- [ ] config write/delete/restore;
- [ ] setup-input save/restore after result conversion;
- [ ] preferences save;
- [ ] forwards mutation/reset/rollback/restore;
- [ ] identity authorized-key append and detailed restore result;
- [ ] broker secret persist/restore;
- [ ] candidate deletion if still public outside scoped helper;
- [ ] workspace cleanup result;
- [ ] lifecycle `trySubmit` at required active-service sites.

`@CheckResult` is acceptable where Android lint and detekt prove coverage. A custom type-aware detekt rule is required where annotation cannot distinguish legitimate throws/test setup.

## P2-002-B — Permanent positive and negative fixtures

- [ ] Add a small rule test or fixture that ignores an authoritative result and fails.
- [ ] Add consumed-result forms that pass: `.getOrThrow`, `fold`, `getOrElse`, `isFailure` handling, returned result, and explicit assignment/use.
- [ ] Do not rely only on a historical commit message describing a temporary violation.

## P2-002-C — CI

- [ ] Run `./gradlew --no-daemon check` in GitHub Actions, or invoke all equivalent type-resolved tasks explicitly.
- [ ] Confirm ignored-result fixture/rule tests run in CI.
- [ ] Confirm Android lint `CheckResult` is build-failing.
- [ ] Confirm current production tree passes.

## Acceptance

- [ ] Future ignored authoritative mutation/cleanup results fail CI.
- [ ] The rule is syntax/type aware.
- [ ] Positive and negative enforcement is permanently testable.

---

# P2-003 — Final validation and immutable signoff

Do not begin final signoff while any known issue remains open in this TODO.

## P2-003-A — Repository state

- [ ] Record `git rev-parse HEAD`.
- [ ] Record `git status --short`; it must be empty.
- [ ] Confirm all referenced FIX7 documents and review source exist at exact paths.
- [ ] Confirm no spec/TODO references an unavailable assistant-created file.
- [ ] Record task commit SHAs and explain any intentionally combined task.

## P2-003-B — Focused Android tests

Construct one command containing all touched test classes and run with `--rerun-tasks`. At minimum include:

```text
ConfigurationMutationCoordinatorTest
ExactFileStateTest
MutationHelpersTest
SetupValidationWorkspaceTest
BrokerSecretRepositoryTest
SetupPersistenceCoordinatorTest
SetupSaveControllerTest
TransactionalResetCoordinatorTest / hardening class
IdentityPersistenceAtomicityTest
ImportExportViewModelTest
ImportExportServiceTest
ForwardsViewModelTest
SettingsViewModelTest
NetworkPolicyViewModelTest
NetworkPolicyManagerTest
NetworkMonitorSupervisorTest
TunnelLifecycleCoordinatorTest
TunnelRepositoryTest
UnverifiedStartContextTest
TunnelForegroundService* tests
Application/startup tests
```

- [ ] Focused Android result recorded.

## P2-003-C — Full Android validation

```bash
cd android
./gradlew --no-daemon ktlintCheck
./gradlew --no-daemon detekt
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest --rerun-tasks
./gradlew --no-daemon assembleDebug
./gradlew --no-daemon check
```

- [ ] ktlint PASS.
- [ ] detekt PASS.
- [ ] lintDebug PASS.
- [ ] full unit tests PASS on at least three forced reruns to expose ordering leakage.
- [ ] assembleDebug PASS.
- [ ] check PASS.

## P2-003-D — Rust validation

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-features
```

- [ ] fmt PASS.
- [ ] clippy PASS with zero warnings.
- [ ] all workspace tests PASS.
- [ ] exact offer-stop and first-clock-failure tests identified in output.

## P2-003-E — E2E

- [ ] Docker real-broker tunnel PASS.
- [ ] Docker stop lifecycle PASS.
- [ ] Android emulator installs and reaches Listening.
- [ ] Android user Stop while Listening/no peer ends in Stopped, not Error.
- [ ] Config setup validation failure leaves live identity/authorized keys/broker secret unchanged in an integration test.
- [ ] Metered-to-unmetered transition exercised on emulator/device, or precise NOT RUN reason plus exact service integration test.
- [ ] Process-kill/destroy recovery exercised, or precise platform limitation plus exact integration proof.

## P2-003-F — CI

- [ ] Final GitHub Actions run is complete and green.
- [ ] Record run URL/ID.
- [ ] Record exact head SHA and verify it equals signoff SHA.
- [ ] Record Android and Rust artifact/report paths.
- [ ] Do not write “in progress at signoff time.”

## P2-003-G — Final inventories

Record final outputs:

```bash
cd android
rg -n 'runCatching|catch\s*\([^)]*Throwable' app/src/main/java/com/phillipchin/webrtctunnel
rg -n 'Thread\.sleep' app/src/test
rg -n 'deleteCandidateFileSafely' app/src/main/java/com/phillipchin/webrtctunnel
rg -n 'resolveBrokerPasswordFile|mqtt_password' app/src/main/java/com/phillipchin/webrtctunnel
cd ..
rg -n 'duration_since\(UNIX_EPOCH\)|unwrap_or\(0\)|expect\("system clock|resolve_unix_ms' crates
```

For every remaining hit, record why it is safe and in scope.

## Acceptance

- [ ] One immutable commit has complete local, CI, Rust, Android, Docker, and emulator evidence.
- [ ] No check is marked PASS based solely on indirect coverage or a historical claim.
- [ ] Known offer-stop defect is closed.

---

# Completion checklist

## P0

- [ ] one application-wide coordinator serializes setup/import/forward/reset;
- [ ] exact file snapshots preserve absence and bytes;
- [ ] cleanup results are composed and never ignored;
- [ ] config rendering is pure;
- [ ] setup validation mutates no live state;
- [ ] broker password persistence is transactional;
- [ ] setup failure and cancellation rollback all prior stages;
- [ ] reset failure and cancellation restore exact state;
- [ ] identity pair cannot remain mismatched after failure/cancellation;
- [ ] every stop-like failure enters runtime quarantine;
- [ ] quarantine blocks all starts/resumes/retries;
- [ ] offer stop while Listening finishes Stopped;
- [ ] network safety action survives reporter failure;
- [ ] Rust time never panics or invents zero.

## P1

- [ ] import overlap is visible and durable;
- [ ] imported private bytes are wiped in every outcome;
- [ ] candidate cleanup integration is exact;
- [ ] invalid native status clears stale live truth;
- [ ] unexpected lifecycle processor death is visible;
- [ ] main-thread startup avoids identified disk/network work;
- [ ] NetworkPolicyViewModel failure is durable;
- [ ] ViewModel boundary redaction is complete;
- [ ] unsafe `runCatching` and silent temp cleanup are removed;
- [ ] optional snackbar loss does not own required truth.

## P2

- [ ] exact production-path tests replace indirect claims;
- [ ] no timing-sleep proof remains;
- [ ] authoritative ignored results fail CI;
- [ ] final signoff is complete against one immutable SHA.
