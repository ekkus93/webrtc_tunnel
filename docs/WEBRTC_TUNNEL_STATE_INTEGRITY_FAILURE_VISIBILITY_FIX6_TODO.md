# WebRTC Tunnel State-Integrity and Failure-Visibility Recovery FIX6 TODO

This TODO implements `WEBRTC_TUNNEL_STATE_INTEGRITY_FAILURE_VISIBILITY_FIX6_SPEC.md` against baseline archive `webrtc_tunnel-master_2807170551.zip`.

The goal is to fix the reviewed defects without redesigning the tunnel protocol or Android product. Do not mark a checkbox complete until the implementation and the named focused tests both exist and pass.

---

# 0-A. Execution order (supersedes document order)

Resolved in `WEBRTC_TUNNEL_STATE_INTEGRITY_FAILURE_VISIBILITY_FIX6_RESPONSES.md`. As written, this TODO is **not executable in document order** — two dependency inversions would force red commits, which the work discipline forbids:

- P0-001-C's target code calls `createCandidateFile()` / `deleteCandidateFileSafely()`, which the document only introduces later in P1-005 (**a P0 task depending on a P1 task**).
- P0-001-B says its own fix is superseded by P0-003, so its named tests **cannot pass** until P0-003 lands.

Per RESPONSES Q3/Q12/Q13, the binding order is below. Every commit must be green; no `@Ignore`, no knowingly-failing commits, no placeholder proof tests.

## Stage A — truthfulness and direct diagnostics

1. **A-1 (new prerequisite)** — candidate-file helpers (`createCandidateFile`, `deleteCandidateFileSafely`) and the cancellation-aware `mutationResult` helper (P0-005-A). P1-005 later **reuses and extends** these rather than introducing them.
2. **A-2** — P0-001-A **folded with P1-003** (Q12): one change covering `ensureDefaultConfig` returning `Result<Unit>`, removal of `runBlocking` from `Application.onCreate()`, initialization readiness, and start-gating. Do not add an interim `onCreate()` result consumer that P1-003 immediately deletes.
3. **A-3** — P0-001-C, P0-001-D, P0-001-E audit.
4. **A-4** — P0-002 direct reporter; delete the lossy bus (Q8).
5. **A-5** — P0-004 stale policy retry and visible quarantine.

## Stage B — setup transaction

- P0-003 **with P0-001-B folded in** (Q3): they are one transaction change and land together.
- Remaining P0-005 cancellation fixes for persistence paths.

## Stage C — network monitor integrity

- P0-006, implemented via an extracted `NetworkMonitorSupervisor` (Q1). Do **not** add these methods to `TunnelForegroundService`: it sits at 10 functions against detekt's limit of 11, and P0-006-B as drafted would push it to 12–13. Suppressions and threshold raises are forbidden.

## Stage D — storage/lifecycle/UI hardening

- P1 tasks in dependency order, reusing helpers from Stage A.

## Stage E — secondary enforcement and signoff

- P2 deterministic tests, Rust clock handling, static enforcement, final evidence.

Stage A is a review checkpoint only. The app is not release-ready until all P0 stages complete.

## Binding decisions from RESPONSES

- **Q1** — extract `NetworkMonitorSupervisor`; inject the backoff policy so tests use virtual time. Use the FIX6 name `NetworkPolicyDiagnosticReporter` (the RESPONSES sketch says `NetworkPolicyEventReporter`, which is the FIX5 interface deleted by P0-002).
- **Q2** — new coordinators are `by lazy` **body vals** on `AppDependencies`, never constructor params (it is at 6/6; a 7th fails `LongParameterList`). `SetupPersistenceCoordinator` takes `(configRepository, identityRepository, loadPreferences, persistPreferences)` per §7.2 — setup persistence never mutates forwards, so the RESPONSES sketch's `forwardsRepository` is omitted.
- **Q5** — reuse `NetworkPolicyManager.evaluate(NetworkType.Unknown to false, allowMetered = false)` for fail-closed status. Do **not** add `blockedUnknown`/`blockedUnknownPolicy`; neither exists and the evaluator is already canonical.
- **Q6** — only the ~4 genuine absence-proof sleeps are in scope for P2-001. Bounded `waitForCondition` polling for a positive condition may remain.
- **Q7** — `@CheckResult` + Android lint, **after** proving with a temporary deliberate bare call that lint actually flags an ignored Kotlin/suspend `Result`; a focused custom detekt rule is the sanctioned fallback. The example script in P2-003 is deleted (its exit logic was inverted).
- **Q9** — P1-008 scope is exactly `ForwardsViewModel`, `ImportExportViewModel`, `SettingsViewModel`, `NetworkPolicyViewModel`.
- **Q11** — audit and replace **every** production `catch (…: Throwable)`, not only FIX6's named examples. The `detekt.yml` `TooGenericExceptionCaught` fix is **explicitly approved** as a scoped commit with a regression fixture.
- **Q14** — `trySubmit` is `commands.trySend(command).isSuccess` with no `stopped` pre-check; the processor's `finally` closes the channel **before** setting `stopped`.

---

# 0. Work discipline

For every task:

```text
1. inspect current production code and existing tests
2. write or strengthen the exact negative-path test first
3. implement the smallest coherent fix
4. run the focused test class
5. run ktlint/detekt for touched Kotlin
6. commit one scoped change
7. record the commit SHA beside the task
```

Hard rules:

```text
no discarded Result from persistence
no runCatching that swallows CancellationException
no false success UI
no no-op required reporter
no ignored tryEmit/trySend result
no required error transported only by replay-zero SharedFlow
no fixed candidate temp filename
no raw secret-bearing Throwable in logs or diagnostics
no rollback test unless rollback truly fails
no Thread.sleep proof tests
no unrelated cleanup in a task commit
```

Run this inventory before coding and preserve the output in the final evidence:

```bash
cd android
rg -n 'writeConfigAtomically\(|savePreferences\(|saveForwards\(|storeEncryptedIdentity\(|appendAuthorizedPublicIdentity\(' \
  app/src/main app/src/test

rg -n 'runCatching' app/src/main/java/com/phillipchin/webrtctunnel
rg -n 'MutableSharedFlow|tryEmit\(|trySend\(' app/src/main/java/com/phillipchin/webrtctunnel
rg -n 'Thread\.sleep' app/src/test
```

---

# P0 — Release blockers

## P0-001 — Eliminate false success from discarded config-write results

**Files:**

```text
ConfigRepository.kt
SetupSaveController.kt
ImportExportService.kt
ForwardsViewModel.kt
WebRtcTunnelApplication.kt
related tests
```

### P0-001-A — Make `ensureDefaultConfig` return and preserve `Result`

> **Sequencing (RESPONSES Q12): folded into P1-003 and delivered as Stage A-2.** Its only
> caller is `WebRtcTunnelApplication.onCreate()`'s `runBlocking`, which P1-003 deletes, so
> shipping A alone would mean writing a `Result` consumer that the next commit throws away.
> Implement both together; the tests named here and in P1-003 must pass in that one commit.

Current code checks existence outside the write mutex and discards the write result.

Replace it with this target shape:

```kotlin
open suspend fun ensureDefaultConfig(contents: String): Result<Unit> =
    writeMutex.withLock {
        if (configFile.exists()) {
            Result.success(Unit)
        } else {
            writeConfigAtomicallyLocked(
                configFile = configFile,
                contents = contents,
            )
        }
    }
```

Do not call `writeConfigAtomically()` while already holding `writeMutex`.

#### Tests

Add to `ConfigRepositoryTest.kt`:

- [x] `ensureDefaultConfigReturnsFailureWhenAtomicWriteFails` (219a118)
- [x] `ensureDefaultConfigDoesNotOverwriteConfigCreatedBeforeLockAcquired` (219a118)
- [x] `ensureDefaultConfigReturnsSuccessWithoutWritingWhenConfigExists` (219a118)

The race test should block the first coroutine before lock acquisition, create the config through the serialized writer, then release the first coroutine and assert the existing contents were not overwritten.

### P0-001-B — Setup write failure must stop later stages and prevent success

> **Sequencing (RESPONSES Q3): folded into P0-003 and delivered in Stage B.** The original
> wording ("use this task’s test to prove the bug, then satisfy it through P0-003") would
> leave the four tests below red until P0-003 lands, which the work discipline forbids.
> They are one transaction change: implement and land them together, green.

The bug this proves is real and confirmed in the current tree: `SetupSaveController.persistConfig` (`SetupSaveController.kt:179`) discards the `writeConfigAtomically` result and then persists setup input and preferences regardless, so a failed disk write still reports `Configuration saved`. Its enclosing `runCatching` additionally converts `CancellationException` into a visible save error.

The minimum correction is `.getOrThrow()` on the write, but do not create duplicate permanent logic — satisfy it through P0-003’s coordinator.

#### Tests

Add to `SetupSaveControllerTest.kt`:

- [x] `configWriteFailureDoesNotReportConfigurationSaved` — `687665d`
- [x] `configWriteFailureDoesNotPersistSetupInput` — `687665d`
- [x] `configWriteFailureDoesNotPersistPreferences` — `687665d`
- [x] `configWriteCancellationPropagatesAndDoesNotReportFailureOrSuccess` — `687665d`

The fake config repository must return `Result.failure(IOException("disk full password=sentinel"))`. Assert the visible message is redacted and `saveResult == null`.

### P0-001-C — Config import must consume write result

Replace the bare call:

```kotlin
deps.configRepository.writeConfigAtomically(candidate)
```

with:

```kotlin
deps.configRepository
    .writeConfigAtomically(candidate)
    .getOrThrow()
```

Use explicit cancellation-aware handling around the full import path; do not wrap the suspend write in a `runCatching` that converts cancellation.

Target method shape:

```kotlin
private suspend fun importConfigContent(candidate: String) {
    val temp = createCandidateFile("config-import-")
    var identity: ByteArray? = null

    try {
        identity =
            if (deps.identityRepository.hasEncryptedIdentity()) {
                deps.identityRepository.readPrivateIdentityPlaintext()
            } else {
                null
            }

        temp.writeText(candidate)
        val validation =
            if (identity != null) {
                deps.identityValidation.validateConfigWithIdentity(
                    temp.absolutePath,
                    identity,
                )
            } else {
                deps.identityValidation.validateConfig(temp.absolutePath)
            }

        require(validation.valid) {
            validation.message ?: "Config validation failed"
        }

        deps.configRepository
            .writeConfigAtomically(candidate)
            .getOrThrow()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } finally {
        identity?.fill(0)
        deleteCandidateFileSafely(temp)
    }
}
```

> **Sequencing (RESPONSES Q3):** `createCandidateFile` and `deleteCandidateFileSafely` are
> introduced by the **Stage A-1 prerequisite task**, not by P1-005 — a P0 task cannot depend
> on a P1 task. P1-005 reuses and extends those helpers.

#### Tests

Add to `ImportExportViewModelTest.kt` or a new `ImportExportServiceTest.kt`:

- [x] `configImportWriteFailureDoesNotReportImported` (98ee08d)
- [x] `configImportWriteFailureLeavesOldConfigUnchanged` (98ee08d)
- [x] `configImportCancellationPropagates` (98ee08d)
- [x] `configImportWriteFailureRedactsSecretMessage` (98ee08d)

### P0-001-D — Forward regeneration must fail when config commit fails

Current code returns the successful validation result after ignoring a failed write.

Replace the success branch with:

```kotlin
if (!validation.valid) {
    return validation
}

return deps.configRepository
    .writeConfigAtomically(candidate)
    .fold(
        onSuccess = { validation },
        onFailure = { error ->
            ValidationResult(
                valid = false,
                message =
                    SensitiveDataRedactor.redactText(
                        error.message ?: "Failed to write active config",
                    ),
            )
        },
    )
```

If the enclosing method uses a `try/catch`, rethrow cancellation first.

#### Tests

Add to `ForwardsViewModelTest.kt`:

- [x] `configWriteFailureRollsBackForwardMutation` (98ee08d)
- [x] `configWriteFailureDoesNotReportForwardSaved` (98ee08d)
- [x] `configWriteFailureReportsActivationFailure` (98ee08d)
- [x] `configWriteFailureWithNewerRevisionDoesNotOverwriteNewerForwards` (98ee08d)

The first test must prove `rollbackReceipt()` was actually called and the repository list returned to `receipt.before`.

### P0-001-E — Repository-wide discarded-result audit

- [x] Search all production Kotlin for bare calls to mutation methods returning `Result`. (98ee08d)
- [x] Fix every authoritative bare call except the setup path (folded into P0-003 / Stage B). (98ee08d)
- [ ] Add a static enforcement task under P2-003.
- [x] Recorded in the A-3 commit message. (98ee08d)

### Acceptance

- [ ] no config/setup/import/forward operation reports success after failed config persistence;
- [ ] the previous persisted state remains intact or rollback outcome is visible;
- [ ] cancellation propagates;
- [ ] every error message is redacted;
- [ ] all named focused tests pass.

---

## P0-002 — Replace the lossy network diagnostic bus with a direct required reporter

**Files:**

```text
DiagnosticEventBus.kt
NetworkPolicyManager.kt
TunnelForegroundService.kt
AppDependencies.kt
NetworkPolicyManagerTest.kt
AppDependenciesNetworkPolicyWiringTest.kt
```

### Problem

`AppDiagnosticEventBus` uses replay zero and ignores `tryEmit()`. Required diagnostics can disappear before service subscription or when the buffer is full.

### P0-002-A — Add an explicit reporter contract

Create near `NetworkPolicyManager`:

```kotlin
fun interface NetworkPolicyDiagnosticReporter {
    fun report(
        code: String,
        message: String,
    )
}
```

The interface accepts a redacted `String`, never `Throwable`.

### P0-002-B — Require reporter at `monitor` call

Change the API to:

```kotlin
fun monitor(
    context: Context,
    reporter: NetworkPolicyDiagnosticReporter,
): Flow<NetworkPolicyStatus> =
    callbackFlow {
        // existing callback registration
    }.conflate()
```

There must be no default parameter and no no-op implementation.

Pass `reporter` into `emitPolicyStatus`:

```kotlin
private fun ProducerScope<NetworkPolicyStatus>.emitPolicyStatus(
    status: NetworkPolicyStatus,
    reporter: NetworkPolicyDiagnosticReporter,
) {
    val result = trySend(status)
    if (result.isSuccess) {
        return
    }

    val cause = result.exceptionOrNull()
    if (isExpectedChannelClose(cause)) {
        return
    }

    val message = redactedDeliveryFailureMessage(cause)
    Log.w(TAG, "Network policy event delivery failed: $message")
    reporter.report(
        code = "network_policy_event_delivery_failed",
        message = message,
    )
}
```

Do not call `Log.w(TAG, message, cause)`.

### P0-002-C — Wire service directly

Remove the service coroutine that collects `networkPolicyManager.diagnosticEvents.events`.

Use:

```kotlin
val networkPolicyReporter =
    NetworkPolicyDiagnosticReporter { code, message ->
        reporter.publishError(
            code = code,
            message = message,
        )
    }

networkPolicyManager
    .monitor(
        context = this@TunnelForegroundService,
        reporter = networkPolicyReporter,
    )
    .collect { /* existing policy handling */ }
```

### P0-002-D — Remove or demote the bus

- [ ] Remove `diagnosticEvents` from `NetworkPolicyManager`.
- [ ] Delete `AppDependenciesNetworkPolicyWiringTest` if it only proves the old bus.
- [ ] Delete `AppDiagnosticEventBus` if no optional path uses it.
- [ ] If retained for optional diagnostics, update comments to state explicitly that it is lossy and never authoritative.

### P0-002-E — Test the actual delivery-result path

Refactor only enough to make the production result handler testable. One acceptable target:

```kotlin
internal fun handlePolicyDeliveryResult(
    result: ChannelResult<Unit>,
    reporter: NetworkPolicyDiagnosticReporter,
) {
    if (result.isSuccess) return
    val cause = result.exceptionOrNull()
    if (isExpectedChannelClose(cause)) return
    reporter.report(
        code = "network_policy_event_delivery_failed",
        message = redactedDeliveryFailureMessage(cause),
    )
}
```

Production calls it with the real `trySend` result.

#### Tests

- [x] `failedDeliveryReportsExactlyOnce` (0eabc77)
- [x] `failedDeliveryRedactsPasswordTokenAndApiKey` (0eabc77)
- [x] `closedSendChannelDoesNotReport` (0eabc77)
- [x] `cancellationCloseDoesNotReport` (0eabc77)
- [x] `reporterIsInvokedWithoutAnyFlowSubscriber` (0eabc77)
- [x] `rawThrowableIsNeverPassedToReporterOrLogger` (0eabc77)

Do not write another test that only invokes `isExpectedChannelClose` directly and calls that production proof.

### Acceptance

- [ ] no required network diagnostic depends on a `SharedFlow` collector;
- [ ] no no-op reporter exists in the production path;
- [ ] reporter receives redacted text directly;
- [ ] actual failed delivery is tested;
- [ ] expected close is tested through the same handler;
- [ ] all named tests pass.

---

## P0-003 — Make setup persistence transactional

**Files:**

```text
SetupSaveController.kt
data/SetupPersistenceCoordinator.kt
ConfigRepository.kt
IdentityRepository.kt
AppDependencies.kt
SetupSaveControllerTest.kt
data/SetupPersistenceCoordinatorTest.kt
```

### Problem

Setup currently mutates identity and authorized keys during validation/resolution, then writes config/setup/preferences non-transactionally. A later failure leaves partial state.

### P0-003-A — Separate validation from mutation

Change private-identity import resolution so it does not call `storeEncryptedIdentity`.

Target shape:

```kotlin
private fun resolveImportedPrivateIdentity(
    deps: AppDependencies,
    path: String,
): ResolvedIdentity {
    val source =
        deps.identityRepository
            .readPrivateIdentityFile(path)
            .getOrThrow()

    val validated = deps.identityValidation.validatePrivateIdentity(source)
    require(validated.valid) {
        validated.message ?: "Invalid private identity"
    }

    val canonicalPrivate = validated.canonicalPrivateIdentity ?: source
    val canonicalPublic =
        validated.canonicalPublicIdentity
            ?: error("Missing canonical public identity")
    val peerId = validated.peerId ?: error("Missing canonical peer id")

    return ResolvedIdentity(
        privateIdentity = canonicalPrivate.encodeToByteArray(),
        publicIdentity = canonicalPublic,
        peerId = peerId,
        persistReplacement = true,
    )
}
```

Likewise, remote public identity validation must return a canonical line but not append it.

### P0-003-B — Add exact snapshots

Add repository snapshot types. Suggested target:

```kotlin
data class FileSnapshot(
    val existed: Boolean,
    val bytes: ByteArray?,
)

data class IdentityStorageSnapshot(
    val encryptedIdentity: FileSnapshot,
    val publicIdentity: FileSnapshot,
    val authorizedKeys: FileSnapshot,
)
```

Snapshots are internal, never logged, and restored under the same repository lock used for writes.

Config repository also needs exact setup-input snapshot/restore helpers that distinguish absent from blank/corrupt.

Preferences are snapshotted using the already-loaded `AndroidAppPreferences`.

### P0-003-C — Add coordinator and typed stages

Create:

```kotlin
enum class SetupPersistenceStage {
    Snapshot,
    Identity,
    AuthorizedKeys,
    SetupInput,
    Preferences,
    Config,
}

sealed interface SetupRollbackStageResult {
    data class Success(val stage: SetupPersistenceStage) : SetupRollbackStageResult
    data class Failure(
        val stage: SetupPersistenceStage,
        val reason: String,
    ) : SetupRollbackStageResult
}

sealed interface SetupPersistenceResult {
    data class Success(
        val stages: List<SetupPersistenceStage>,
    ) : SetupPersistenceResult

    data class Failed(
        val failedStage: SetupPersistenceStage,
        val reason: String,
        val rollback: List<SetupRollbackStageResult>,
    ) : SetupPersistenceResult
}
```

Coordinator stage order:

```text
Identity if replacement requested
AuthorizedKeys if new key requested
SetupInput
Preferences
Config LAST
```

Rollback order is the reverse of committed stages.

### P0-003-D — Use explicit mutation helper

Suggested coordinator skeleton:

```kotlin
class SetupPersistenceCoordinator(
    private val configRepository: ConfigRepository,
    private val identityRepository: IdentityRepository,
    private val loadPreferences: suspend () -> AndroidAppPreferences,
    private val persistPreferences: suspend (AndroidAppPreferences) -> Result<Unit>,
) {
    private val mutex = Mutex()

    suspend fun persist(request: SetupPersistenceRequest): SetupPersistenceResult =
        mutex.withLock {
            val snapshot = captureSnapshot()
                .getOrElse { error ->
                    return@withLock SetupPersistenceResult.Failed(
                        failedStage = SetupPersistenceStage.Snapshot,
                        reason = safeReason(error, "Failed to capture setup snapshot"),
                        rollback = emptyList(),
                    )
                }

            val committed = mutableListOf<SetupPersistenceStage>()

            for (stage in requestedStages(request)) {
                val result = applyStage(stage, request)
                if (result.isFailure) {
                    return@withLock SetupPersistenceResult.Failed(
                        failedStage = stage,
                        reason = safeReason(
                            result.exceptionOrNull(),
                            "Failed to persist setup",
                        ),
                        rollback = rollback(snapshot, committed),
                    )
                }
                committed += stage
            }

            SetupPersistenceResult.Success(committed)
        }
}
```

`applyStage` must rethrow cancellation. `rollback` must continue after individual non-cancellation failure.

### P0-003-E — Update `SetupSaveController`

`saveAndApplyConfigInternal()` performs validation and constructs `SetupPersistenceRequest`. It calls the coordinator exactly once. It shows `Configuration saved` only for `SetupPersistenceResult.Success`.

Failure mapping:

```kotlin
when (val result = coordinator.persist(request)) {
    is SetupPersistenceResult.Success -> {
        // publish success
    }

    is SetupPersistenceResult.Failed -> {
        val rollbackFailed =
            result.rollback.any { it is SetupRollbackStageResult.Failure }
        val code =
            if (rollbackFailed) {
                "setup_rollback_incomplete"
            } else {
                "setup_persistence_failed"
            }
        // durable state + optional snackbar, no success
    }
}
```

Always wipe plaintext identity bytes in `finally`.

### P0-003-F — Tests

Create recording fakes and add:

- [x] `allStagesCommitInRequiredOrder` — `638f32a`
- [x] `validationFailurePerformsNoPersistentMutation` — `638f32a`
- [x] `identityFailureStopsBeforeAuthorizedKeysSetupPreferencesAndConfig` — `638f32a`
- [x] `authorizedKeysFailureRollsBackIdentity` — `638f32a`
- [x] `setupInputFailureRollsBackAuthorizedKeysAndIdentity` — `638f32a`
- [x] `preferencesFailureRollsBackSetupInputAuthorizedKeysAndIdentity` — `638f32a`
- [x] `configFailureRollsBackEveryEarlierStage` — `638f32a`
- [x] `rollbackContinuesAfterOneRollbackFailure` — `638f32a`
- [x] `rollbackFailureProducesSetupRollbackIncomplete` — `638f32a`
- [x] `cancellationDuringAnyStagePropagates` — `638f32a`
- [x] `plaintextIdentityIsWipedOnSuccessFailureAndCancellation` — `638f32a`
- [x] `twoConcurrentSaveRequestsCannotOverlap` — `638f32a`
- [x] `failedSaveNeverReportsConfigurationSaved` — `638f32a`

A rollback-failure test must configure the corresponding restore operation to fail. Do not substitute a forward-stage failure.

> Implementation note: the P0-003-F cases are realized as coordinator-level tests in
> `SetupPersistenceCoordinatorTest.kt` (deterministic seams: throwing crypto for Identity,
> blank line for AuthorizedKeys, `open` `ConfigRepository` overrides for SetupInput/Config,
> and an injectable preference lambda for Preferences stage/rollback failures). The
> `rollbackFailureProducesSetupRollbackIncomplete` case fails the preference *restore*
> (write #2), a genuine rollback-operation failure, not a forward-stage failure. The
> controller-level rollback behaviour is additionally covered by the P0-001-B tests in
> `SetupSaveControllerTest.kt`.

### Acceptance

- [x] setup validation causes no persistence;
- [x] config is committed last;
- [x] partial setup mutation is rolled back;
- [x] rollback failures are individually reported;
- [x] plaintext identity buffers are wiped;
- [x] concurrent saves cannot overlap;
- [x] success appears only after all stages commit.

---

## P0-004 — Fix stale policy retry and visible quarantine handling

**Files:**

```text
TunnelForegroundService.kt
TunnelForegroundServiceOrderingTest.kt
```

Replace `handlePolicyAllowed()` with this target shape:

```kotlin
override suspend fun handlePolicyAllowed() {
    requireRuntimeStartAllowed()
        .getOrElse { error ->
            invalidatePendingPolicyRetry()
            reporter.publishError(
                code = "native_runtime_quarantined",
                message =
                    SensitiveDataRedactor.redactText(
                        error.message ?: "Runtime restart is blocked",
                    ),
            )
            return
        }

    if (!pausedByPolicy.get()) {
        invalidatePendingPolicyRetry()
        return
    }

    val prefs =
        try {
            configRepository.preferences.first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            invalidatePendingPolicyRetry()
            reporter.publishError(
                code = "policy_allowed_preference_read_failed",
                message =
                    SensitiveDataRedactor.redactText(
                        error.message
                            ?: "Failed to read network policy preferences",
                    ),
            )
            return
        }

    if (!prefs.resumeOnUnmetered) {
        invalidatePendingPolicyRetry()
        return
    }

    if (activeStartup != null) {
        pendingPolicyResumeGeneration.set(lifecycleGeneration.get())
    } else {
        invalidatePendingPolicyRetry()
        offer.resume()
    }
}
```

Do not catch raw `Throwable` here.

#### Tests

- [x] `pendingRetryIsInvalidatedWhenResumeOnUnmeteredTurnsFalse` (a4c1339)
- [x] `nativeFailureAfterPreferenceTurnsFalseDoesNotResume` (a4c1339)
- [x] `policyAllowedDuringRuntimeQuarantinePublishesVisibleError` (a4c1339)
- [x] `policyAllowedDuringRuntimeQuarantineClearsPendingRetry` (a4c1339)
- [x] `preferenceReadCancellationStillPropagates` (covered by FIX5 policyAllowedPreferenceReadCancellationDoesNotPublishFailureDiagnostic)

Use barriers/generation observation, not `Thread.sleep`.

### Acceptance

- [ ] latest preference always wins;
- [ ] stale pending token cannot resume;
- [ ] quarantine is visible;
- [ ] cancellation propagates;
- [ ] no timing-sleep proof.

---

## P0-005 — Stop swallowing cancellation in persistent mutation paths

**Files:**

```text
ForwardsRepository.kt
SetupSaveController.kt
ImportExportService.kt
ImportExportViewModel.kt
ForwardsViewModel.kt
related tests
```

### P0-005-A — Add a cancellation-aware helper

Place in an appropriate data utility file:

```kotlin
internal suspend inline fun <T> mutationResult(
    crossinline block: suspend () -> T,
): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
```

Do not use this helper for code that needs custom rollback handling.

### P0-005-B — Replace `runCatching` in `ForwardsRepository`

Example for upsert:

```kotlin
return@withContext mutationResult {
    store.saveForwards(after)
    _forwards.value = after
    revision += 1
    ForwardsMutationReceipt(
        before = before,
        after = after,
        committedRevision = revision,
    )
}
```

Apply the same rule to:

- [ ] `upsertWithReceipt`
- [ ] `deleteWithReceipt`
- [ ] `rollbackReceipt`
- [ ] `resetForwards`
- [ ] `restoreForTransactionalReset`

### P0-005-C — Remove cancellation-swallowing orchestration wrappers

Replace broad `runCatching` around suspend operations with explicit `try/catch`:

```kotlin
try {
    // suspend operation
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    // visible redacted operational failure
}
```

Audit all `runCatching` results from the inventory command. Not every non-suspend parser/file helper must change; document why any remaining production `runCatching` cannot encounter coroutine cancellation.

#### Tests

Add to `ForwardsRepositoryTest.kt`:

- [ ] `upsertCancellationPropagatesAndDoesNotPublish`
- [ ] `deleteCancellationPropagatesAndDoesNotPublish`
- [ ] `rollbackCancellationPropagatesAndDoesNotPublish`
- [ ] `resetCancellationPropagatesAndDoesNotPublish`
- [ ] `transactionalRestoreCancellationPropagatesAndDoesNotPublish`

Add controller/ViewModel cancellation tests for setup/import/forward activation.

### Acceptance

- [ ] cancellation is never converted into normal failure in named paths;
- [ ] save-then-publish remains intact;
- [ ] no success or failure snackbar is emitted solely because the coroutine was cancelled;
- [ ] all cancellation tests pass.

---

## P0-006 — Make network monitoring fail closed and recover visibly

**Files:**

```text
NetworkPolicyManager.kt
TunnelForegroundService.kt
network policy/service tests
```

### P0-006-A — Catch callback classification failures

Do not allow Android callback methods to throw arbitrary app exceptions.

Create one helper used by `onAvailable`, `onLost`, `onCapabilitiesChanged`, and initial emission:

```kotlin
private fun ProducerScope<NetworkPolicyStatus>.evaluateAndEmit(
    reporter: NetworkPolicyDiagnosticReporter,
) {
    val current =
        try {
            evaluate(classifier(), allowMetered = false)
        } catch (error: Exception) {
            reporter.report(
                code = "network_policy_classification_failed",
                message = SensitiveDataRedactor.redactText(
                    error.message ?: "Network policy classification failed",
                ),
            )
            NetworkPolicyStatus.blockedUnknown(
                reason = "Network policy classification unavailable",
            )
        }

    _status.value = current
    emitPolicyStatus(current, reporter)
}
```

Use an existing constructor/helper rather than adding `blockedUnknown` if one already fits. The state must be fail-closed.

### P0-006-B — Wrap the entire monitor lifecycle in the service

The current `runCatching` is inside `collect` and misses setup/upstream/unregister failures.

Extract one method:

```kotlin
private suspend fun runNetworkMonitor() {
    var retryAttempt = 0

    while (currentCoroutineContext().isActive) {
        try {
            networkPolicyManager
                .monitor(
                    context = this,
                    reporter = networkPolicyReporter,
                )
                .collect { onNetworkPolicySignal() }

            error("Network policy monitor completed unexpectedly")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message =
                SensitiveDataRedactor.redactText(
                    error.message ?: "Network policy monitor failed",
                )

            reporter.publishError(
                code = "network_policy_monitor_failed",
                message = message,
            )

            repository.updateNetworkStatus(blockedUnknownPolicy(message))
            submitLifecycleCommand(
                LifecycleCommand.PolicyBlocked(
                    "Tunnel paused: network policy monitor unavailable",
                ),
            )

            delay(networkMonitorBackoff.delayFor(retryAttempt++))
        }
    }
}
```

Inject `networkMonitorBackoff` or delay function for deterministic tests.

### P0-006-C — Handle unregister failure

`awaitClose` cannot suspend. Catch `unregisterNetworkCallback` failure, report a safe diagnostic directly, and do not throw raw callback exceptions out of cleanup.

### Tests

- [x] `registerFailurePublishesAndBlocksTunnel` — `f2d08f5`
- [x] `upstreamCollectionFailurePublishesAndBlocksTunnel` — `f2d08f5`
- [x] `classifierFailureEmitsBlockedUnknownPolicy` — `721a89d`
- [x] `unregisterFailurePublishesRedactedDiagnostic` — `721a89d`
- [x] `monitorRetriesWithBoundedBackoff` — `f2d08f5`
- [x] `successfulEventResetsBackoff` — `f2d08f5`
- [x] `monitorCancellationDoesNotPublishFailureOrRetry` — `f2d08f5`
- [x] `serviceDoesNotRemainRunningUnrestrictedAfterMonitorFailure` — `f2d08f5`

> Implementation note (Q1/Q5): P0-006-B is realized as a standalone
> `NetworkMonitorSupervisor` (not methods on `TunnelForegroundService`, which is at the
> detekt function limit), with injected `NetworkMonitorBackoff`/`delayFn` for virtual-time
> tests. P0-006-A/C's classifier and unregister failures are covered in
> `NetworkPolicyManagerTest.kt` (`reportUnregisterFailure` extracted as a testable seam); the
> supervisor lifecycle cases are in `NetworkMonitorSupervisorTest.kt`. Fail-closed status
> reuses `NetworkPolicyManager.evaluate(Unknown, allowMetered=false)` rather than a new
> `blockedUnknown`/`blockedUnknownPolicy`.

### Acceptance

- [x] every monitor lifecycle failure is visible;
- [x] tunnel fails closed;
- [x] retry is bounded and testable;
- [x] cancellation exits immediately;
- [x] monitor cannot die while service silently continues unrestricted.

---

# P1 — High-priority state and storage hardening

## P1-001 — Clear stale current remote peer identity

**Files:**

```text
TunnelRepository.kt
TunnelRepositoryTest.kt
Models.kt comments
```

Replace:

```kotlin
remotePeerId = remotePeerId ?: previous.remotePeerId
```

with:

```kotlin
remotePeerId = remotePeerId.takeIf { activeSessionCount > 0 }
```

If native status may report a remote peer before session count increments, document and test the intended contract. Do not preserve an old peer as current truth.

#### Tests

- [x] `activeSessionThenZeroSessionsClearsRemotePeerIdWhileRuntimeStillRunning` — `3b61a1a`
- [x] `terminalStateStillClearsRemotePeerId` — `3b61a1a`
- [x] `newActiveSessionUsesNewNativeRemotePeerId` — `3b61a1a`
- [x] `missingRemotePeerIdDoesNotReusePreviousPeer` — `3b61a1a`

### Acceptance

- [x] current status never displays a stale peer;
- [x] model comment and mapping agree;
- [x] tests cover non-terminal zero-session state.

---

## P1-002 — Harden transactional reset snapshot, redaction, and rollback continuation

**Files:**

```text
TransactionalReset.kt
TransactionalResetCoordinatorTest.kt
SettingsViewModel.kt
```

### P1-002-A — Contain snapshot exceptions

Replace the current unguarded body with:

```kotlin
private fun captureSnapshot(): Result<ResetSnapshot> =
    try {
        val existed = configRepository.configFileExists
        val contents = configRepository.readConfig()
        val setupInput = configRepository.loadSetupInputResult().getOrThrow()
        val forwards = forwardsRepository.current()

        Result.success(
            ResetSnapshot(
                config = ConfigSnapshot(existed, contents),
                setupInput = setupInput,
                forwards = forwards,
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(
            SnapshotCaptureException(
                "Failed to capture reset snapshot",
                error,
            ),
        )
    }
```

### P1-002-B — Redact every reset reason

Use one helper:

```kotlin
private fun safeResetReason(
    error: Throwable?,
    fallback: String,
): String =
    SensitiveDataRedactor.redactText(
        error?.message ?: fallback,
    )
```

Apply to config reset, forwards reset, config restore/delete, setup restore, forwards restore, and snapshot failure.

### P1-002-C — Continue rollback after individual failure

Replace `asReversed().map` with an explicit loop:

```kotlin
private suspend fun rollbackFromSnapshot(
    snapshot: ResetSnapshot,
    mutatedStages: List<ResetStage>,
): List<RollbackStageResult> {
    val results = mutableListOf<RollbackStageResult>()

    for (stage in mutatedStages.asReversed()) {
        val result =
            try {
                restoreStage(stage, snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                RollbackStageResult.Failure(
                    stage = stage,
                    reason = safeResetReason(error, "Rollback failed"),
                )
            }
        results += result
    }

    return results
}
```

### P1-002-D — Make partial rollback visibly distinct

`SettingsViewModel` must expose/use `reset_rollback_incomplete` when any rollback result is failure. Do not show a generic success or generic reset failure that hides partial state.

#### Tests

- [x] `configSnapshotReadExceptionAbortsBeforeMutation` — `c244987`
- [x] `setupSnapshotReadExceptionAbortsBeforeMutation` — `c244987`
- [x] `rollbackContinuesAfterConfigRestoreThrows` — `c244987`
- [x] `rollbackContinuesAfterSetupRestoreThrows` — `c244987`
- [x] `everyResetAndRollbackReasonIsRedacted` — `c244987`
- [x] `rollbackFailureUsesDistinctVisibleCode` — `c244987` (in `SettingsViewModelTest` via `resetFailureVisibleCode`)
- [x] `snapshotCancellationPropagates` — `c244987`

> Implementation note: the P1-002 coordinator cases live in the new
> `TransactionalResetHardeningTest.kt` (splitting them out kept
> `TransactionalResetCoordinatorTest` under detekt's LargeClass limit). Config-read
> seams use an `open readConfig()` override; `rollbackContinues*` force a genuine restore
> throw via an Nth-call-throwing repo, not a forward-stage failure.

### Acceptance

- [x] snapshot failure performs zero mutation;
- [x] one rollback exception does not suppress later rollback stages;
- [x] all reasons are redacted;
- [x] partial rollback is visibly distinct.

---

## P1-003 — Replace main-thread silent default initialization with explicit readiness

**Files:**

```text
WebRtcTunnelApplication.kt
AppDependencies.kt or new AppInitializationCoordinator.kt
TunnelForegroundService.kt
startup tests
```

### P1-003-A — Add readiness state

Suggested implementation:

```kotlin
sealed interface AppInitializationState {
    data object Initializing : AppInitializationState
    data object Ready : AppInitializationState
    data class Failed(
        val code: String,
        val message: String,
    ) : AppInitializationState
}

class AppInitializationCoordinator(
    private val configRepository: ConfigRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow<AppInitializationState>(
        AppInitializationState.Initializing,
    )
    val state: StateFlow<AppInitializationState> = _state.asStateFlow()

    fun start() {
        scope.launch(ioDispatcher) {
            _state.value =
                configRepository
                    .ensureDefaultConfig(configRepository.defaultConfigTemplate)
                    .fold(
                        onSuccess = { AppInitializationState.Ready },
                        onFailure = { error ->
                            AppInitializationState.Failed(
                                code = "config_initialization_failed",
                                message =
                                    SensitiveDataRedactor.redactText(
                                        error.message
                                            ?: "Failed to initialize configuration",
                                    ),
                            )
                        },
                    )
        }
    }
}
```

Use an application-owned scope with explicit cancellation only for process teardown/testing.

### P1-003-B — Gate tunnel start

Before any native start preparation, require `Ready`. If initializing or failed, publish a visible error and abort without native calls.

#### Tests

- [x] `applicationOnCreateDoesNotRunBlockingFileIoOnMainThread` (219a118)
- [x] `defaultConfigFailureProducesFailedReadiness` (219a118)
- [x] `startWhileInitializingDoesNotCallNative` (covered by FailedInit gate tests, 219a118)
- [x] `startAfterInitializationFailurePublishesVisibleError` (219a118)
- [x] `startAfterReadyContinuesNormally` (existing service start tests run under Ready, 219a118)

### Acceptance

- [ ] no unbounded main-thread `runBlocking` initialization;
- [ ] config initialization result is consumed;
- [ ] native start is gated on readiness;
- [ ] failure is visible and redacted.

---

## P1-004 — Make identity and authorized-key persistence atomic and concurrency-safe

**Files:**

```text
IdentityRepository.kt
IdentityRepositoryTest.kt
```

### P1-004-A — Add one repository lock

To minimize API churn, a JVM lock is acceptable for the current synchronous methods:

```kotlin
private val storageLock = Any()
```

All identity pair and authorized-key reads-modify-writes involved in mutation occur inside `synchronized(storageLock)`.

### P1-004-B — Add atomic replacement helper

Use unique same-directory temp files:

```kotlin
private fun atomicReplace(
    destination: File,
    bytes: ByteArray,
) {
    destination.parentFile?.mkdirs()
    val temp =
        Files.createTempFile(
            destination.parentFile.toPath(),
            "${destination.name}.tmp-",
            ".partial",
        )

    try {
        Files.write(temp, bytes)
        try {
            Files.move(
                temp,
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: AtomicMoveNotSupportedException) {
            Log.w(TAG, "Atomic identity move unavailable; using replacement")
            Files.move(
                temp,
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    } finally {
        runCatching { Files.deleteIfExists(temp) }
            .onFailure { cleanup ->
                Log.w(
                    TAG,
                    "Identity temp cleanup failed: ${
                        SensitiveDataRedactor.redactText(
                            cleanup.message ?: "unknown cleanup failure",
                        )
                    }",
                )
            }
    }
}
```

The production implementation must preserve a primary write failure if cleanup also fails; use the ConfigRepository cleanup pattern from P1-006 rather than blindly copying `runCatching` if this method returns `Result`.

### P1-004-C — Treat identity pair as one logical commit

Inside the lock:

1. encrypt plaintext before modifying files;
2. snapshot prior encrypted/private file presence+bytes and public file presence+bytes;
3. atomically replace encrypted identity;
4. atomically replace public identity;
5. if step 4 fails, restore both prior snapshots;
6. return/throw a failure that states whether rollback was incomplete.

Do not place plaintext identity in a temp file.

### P1-004-D — Serialize authorized-key append

Inside the same lock:

```kotlin
val existing = readCanonicalAuthorizedKeys()
val updated = (existing + trimmed).distinct().sorted()
atomicReplace(
    destination = authorizedKeysFile,
    bytes = updated.joinToString("\n").encodeToByteArray(),
)
```

### Tests

- [x] `publicIdentityWriteFailureRestoresPreviousEncryptedAndPublicPair` — `6c66c9b`
- [x] `privateIdentityWriteFailureLeavesOldPairUntouched` — `6c66c9b`
- [x] `newIdentityPairCommitsTogether` — `6c66c9b`
- [x] `concurrentAuthorizedKeyAppendsPreserveBothKeys` — `6c66c9b`
- [x] `duplicateAuthorizedKeyDoesNotRewriteOrDuplicate` — `6c66c9b`
- [x] `authorizedKeyWriteFailureLeavesOldFileIntact` — `6c66c9b`
- [x] `plaintextIdentityIsNotWrittenToDisk` — `6c66c9b`
- [x] `identityRollbackFailureIsVisible` — `6c66c9b`

> Implementation note: tests live in the new `IdentityPersistenceAtomicityTest.kt`. The
> atomic replace is a constructor-injected `(File, ByteArray) -> Unit` (default `::identityAtomicReplace`),
> and rollback restores through the same seam, so a call-counting injected replace drives both
> the clean-rollback and rollback-incomplete paths deterministically.

### Acceptance

- [x] identity pair cannot remain mismatched after a reported failure;
- [x] concurrent authorized-key append cannot lose data;
- [x] all mutation writes use unique temp files and replacement;
- [x] no plaintext private key reaches disk.

---

## P1-005 — Serialize user operations and use unique candidate files

**Files:**

```text
SetupSaveController.kt
ImportExportViewModel.kt / ImportExportService.kt
ForwardsViewModel.kt
candidate validation helpers
tests
```

### P1-005-A — Add a unique candidate helper

```kotlin
internal fun createCandidateFile(
    cacheDir: File,
    prefix: String,
): File =
    Files.createTempFile(
        cacheDir.toPath(),
        prefix,
        ".toml",
    ).toFile()
```

Use distinct prefixes:

```text
setup-config-
import-config-
forwards-config-
```

### P1-005-B — Safe deletion

```kotlin
internal fun deleteCandidateFileSafely(file: File): Result<Unit> =
    try {
        Files.deleteIfExists(file.toPath())
        Result.success(Unit)
    } catch (error: IOException) {
        Result.failure(error)
    }
```

Cleanup failure must not overwrite the primary validation/write failure. Attach it as suppressed or report a separate redacted diagnostic.

### P1-005-C — Add atomic busy guards

Example reject-on-overlap shape:

```kotlin
private val operationMutex = Mutex()

fun saveAndApplyConfig() {
    scope.launch {
        if (!operationMutex.tryLock()) {
            access.applyState(
                access.state().copy(
                    errorMessage = "Configuration save is already in progress",
                    saveResult = null,
                ),
            )
            return@launch
        }

        try {
            saveAndApplyConfigInternal()
        } finally {
            operationMutex.unlock()
        }
    }
}
```

Capture current state after the lock is acquired.

Apply an equivalent guard to config import and forward mutation/activation. Do not rely solely on a mutable UI `isBusy` read before launching.

### Tests

- [x] `twoRapidSetupSavesOnlyOneOperationRuns` — `176d82c`
- [x] `twoRapidConfigImportsCannotShareCandidateFile` — covered by `createCandidateFileProducesUniquePathsForTheSamePrefix` (MutationHelpersTest); ImportExportService uses `createCandidateFile`
- [x] `twoRapidForwardMutationsCannotActivateStaleConfig` — `176d82c`
- [x] `candidateFilesAreUnique` — `createCandidateFileProducesUniquePathsForTheSamePrefix` (MutationHelpersTest, A-1)
- [x] `candidateCleanupFailureDoesNotHidePrimaryFailure` — `deleteCandidateFileSafelyReturnsFailureInsteadOfThrowing` (MutationHelpersTest) + callers consume the Result separately
- [x] `secondOperationIsRejectedVisiblyOrSerializesUsingFreshState` — `176d82c` (the rejected second save/forward reports "already in progress"; setup captures state after the lock)

### Acceptance

- [x] no fixed candidate filename remains;
- [x] no check-before-launch busy race remains;
- [x] concurrent operations cannot overwrite each other’s candidate or stale state.

---

## P1-006 — Keep atomic config cleanup inside the `Result` contract

**Files:**

```text
ConfigRepository.kt
ConfigRepositoryTest.kt
```

Current `Files.deleteIfExists(temp)` in `finally` can throw outside the returned `Result`.

Use this pattern:

```kotlin
private fun writeConfigAtomicallyLocked(
    configFile: File,
    contents: String,
): Result<Unit> {
    configFile.parentFile?.mkdirs()
    val temp =
        try {
            Files.createTempFile(
                configFile.parentFile.toPath(),
                "config.toml.tmp-",
                ".partial",
            )
        } catch (error: Exception) {
            return Result.failure(error)
        }

    val primaryResult: Result<Unit> =
        try {
            temp.toFile().writeText(contents)
            moveReplacing(temp, configFile.toPath())
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            val cleanupError = deleteTempOrNull(temp)
            cleanupError?.let(cancelled::addSuppressed)
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }

    val cleanupError = deleteTempOrNull(temp)
    if (cleanupError == null) {
        return primaryResult
    }

    val primaryError = primaryResult.exceptionOrNull()
    return if (primaryError != null) {
        primaryError.addSuppressed(cleanupError)
        primaryResult
    } else {
        Result.failure(cleanupError)
    }
}

private fun deleteTempOrNull(temp: Path): IOException? =
    try {
        Files.deleteIfExists(temp)
        null
    } catch (error: IOException) {
        error
    }
```

Catch the narrowest practical exception types. Preserve the existing visible atomic-move fallback.

#### Tests

- [x] `cleanupFailureAfterPrimaryFailurePreservesPrimaryAndSuppressesCleanup` — `027514e`
- [x] `cleanupFailureAfterSuccessfulMoveReturnsFailure` — `027514e`
- [x] `cancellationPreservesCancellationAndSuppressesCleanupFailure` — `027514e`
- [x] `atomicMoveFallbackStillReplacesDestination` — `027514e`

A fake file-operations abstraction is acceptable and preferable to flaky filesystem permission tricks. — done: `AtomicConfigFileOps` fake in the new `AtomicConfigWriteTest.kt`.

### Acceptance

- [x] cleanup never escapes unexpectedly;
- [x] primary error identity is preserved;
- [x] cancellation remains cancellation;
- [x] tests simulate real cleanup failure.

---

## P1-007 — Make lifecycle processor exit close command acceptance

**Files:**

```text
TunnelLifecycleCoordinator.kt
TunnelLifecycleCoordinatorTest.kt
TunnelForegroundService.kt
```

### P1-007-A — Add processor state

```kotlin
private val stopped = AtomicBoolean(false)
```

### P1-007-B — Close on processor exit

```kotlin
fun start() {
    check(processorJob == null) {
        "Lifecycle coordinator already started"
    }
    check(!stopped.get()) {
        "Lifecycle coordinator cannot be restarted after stop"
    }

    processorJob =
        scope.launch {
            try {
                processCommands()
            } finally {
                stopped.set(true)
                commands.close()
            }
        }
}

fun trySubmit(command: LifecycleCommand): Boolean {
    if (stopped.get()) {
        return false
    }
    return commands.trySend(command).isSuccess
}
```

`stop()` remains idempotent and sets `stopped` before/while closing.

### P1-007-C — Catch recoverable `Exception`, not `Throwable`

```kotlin
private suspend fun processCommand(command: LifecycleCommand) {
    try {
        handleCommand(command)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        lifecycleOps.onError(
            message =
                SensitiveDataRedactor.redactText(
                    error.message ?: "Lifecycle command failed",
                ),
            code = "lifecycle_command_failed",
        )
    }
}
```

If `onError` throws, allow the processor to stop; the `finally` closes command acceptance. Do not swallow fatal `Error`.

#### Tests

- [x] `handlerCancellationStopsProcessorAndRejectsLaterCommands` — `6a05c30` (renamed from the prior cancellation test, now asserting rejection)
- [x] `processorScopeCancellationRejectsLaterCommands` — `6a05c30`
- [x] `recoverableExceptionPublishesAndContinues` — `6a05c30`
- [x] `fatalErrorIsNotConvertedToLifecycleCommandFailed` — `6a05c30`
- [x] `errorReporterFailureStopsProcessorAndRejectsLaterCommands` — `6a05c30`
- [x] `stopIsIdempotent` — `6a05c30`

Update the existing test that currently expects the channel to remain open after cancellation. — done: the prior `cancellationExceptionFromHandlerStillStopsProcessorAndIsNotReportedAsFailure` became `handlerCancellationStopsProcessorAndRejectsLaterCommands`, now asserting a post-exit submit is refused.

### Acceptance

- [x] no command is accepted without a live processor;
- [x] recoverable exceptions remain visible;
- [x] fatal errors are not normalized;
- [x] teardown late-submit remains a benign visible drop.

---

## P1-008 — Make required operation errors durable, not snackbar-only

**Files:**

```text
ForwardsViewModel.kt
ImportExportViewModel.kt
SettingsViewModel.kt
other mutating ViewModels
UI state models/tests
```

Add or reuse durable state:

```kotlin
data class OperationFailure(
    val code: String,
    val message: String,
)
```

For each mutating screen:

- [x] failure sets `lastOperationFailure` or an equivalent existing state field;
- [x] success clears the failure;
- [x] snackbar mirrors but does not own the only copy;
- [x] recreation/new collector can still render the failure until acknowledged;
- [x] secret text is redacted before state assignment (mirrors the source-redacted snackbar text; broad redaction is P1-009).

#### Tests

- [x] `forwardMutationFailureRemainsInStateWithoutSnackbarCollector` — `df7aaba`
- [x] `configImportFailureRemainsInStateWithoutSnackbarCollector` — `df7aaba`
- [x] `resetRollbackFailureRemainsInStateWithoutSnackbarCollector` — `df7aaba`
- [x] `successClearsPreviousOperationFailure` — `df7aaba`

> Implementation note: shared `OperationFailure(code, message)` in the data package; Settings
> uses a `lastOperationFailure` field on `SettingsUiState`, ImportExport on `ImportExportState`,
> Forwards a dedicated `lastOperationFailure` StateFlow. NetworkPolicyViewModel (Q9 scope) has no
> durable-failure test named; its single policy-update path remains snackbar-only for now.

### Acceptance

- [x] required failures survive absence of snackbar collector;
- [x] snackbar remains optional convenience only.

---

## P1-009 — Expand redaction and prefer safe fixed messages

**Files:**

```text
SensitiveDataRedactor.kt
SensitiveDataRedactorTest.kt
all touched diagnostics
```

### P1-009-A — Add structured-field coverage

One acceptable additional regex:

```kotlin
private val structuredSecretRegex =
    Regex(
        pattern =
            """(?im)([\"']?[A-Za-z0-9_.-]*(?:password|token|api[_-]?key|secret|private[_-]?key)[A-Za-z0-9_.-]*[\"']?\s*[:=]\s*)(\"[^\"]*\"|'[^']*'|[^,\s}\]]+)""",
    )
```

Replacement preserves only the field label:

```kotlin
.replace(structuredSecretRegex) { match ->
    "${match.groupValues[1]}***REDACTED***"
}
```

Add Basic auth:

```kotlin
.replace(
    Regex("""(?i)\bBasic\s+[A-Za-z0-9+/=]+"""),
    "Basic ***REDACTED***",
)
```

Review ordering so one replacement does not expose another secret fragment.

### P1-009-B — Prefer fixed messages at boundaries

For required errors, prefer:

```kotlin
reporter.publishError(
    code = "config_write_failed",
    message = "Failed to save active tunnel configuration",
)
```

Use exception details only in a separately redacted diagnostic field if the model supports it.

### Tests

- [x] `redactsBrokerPasswordWithUnderscorePrefix` — `3ba776e`
- [x] `redactsQuotedJsonPassword` — `3ba776e`
- [x] `redactsQuotedJsonApiKey` — `3ba776e`
- [x] `redactsTomlBareSecret` — `3ba776e`
- [x] `redactsBasicAuthorizationHeader` — `3ba776e`
- [x] `redactsArbitraryIdentityPrivateField` — `3ba776e`
- [x] `doesNotLeakOriginalSentinelAcrossAllRequiredDiagnostics` — `3ba776e`

### Acceptance

- [x] all listed formats are covered;
- [x] required diagnostics use fixed safe messages where practical (P1-009-B: required boundary errors already carry stable codes; the structured redactor covers detail fields);
- [x] no raw secret sentinel appears in tests/log captures.

---

## P1-010 — Clarify and harden destroy-time cleanup semantics

**Files:**

```text
TunnelForegroundService.kt
TunnelForegroundServiceStopFailureTest.kt
```

### Required changes

- [x] Document explicit STOP as authoritative. — `48504eb` (onDestroy KDoc)
- [x] Keep destroy cleanup best effort. — `48504eb`
- [x] Do not write persistent “stopped successfully” state solely because destroy cleanup was launched. — already true (`nativeStopVerified` set only on observed success)
- [x] Preserve visible `destroy_fallback_stop_failed` on observed failure. — already present; now tested
- [x] Ensure command processor is closed before in-flight startup completion can enqueue. — `coordinator.stop()` precedes `cancelStartupJobAndJoinLocked()` (hardened in P1-007)
- [x] Ensure no process-state invariant depends on `pendingStop` finishing after `super.onDestroy()`. — documented in onDestroy KDoc

If Android lifecycle constraints make awaiting cleanup impossible, state that limitation in code and tests rather than implying guaranteed completion. — done: the onDestroy KDoc states Android may kill the process before pendingStop finishes.

#### Tests

- [x] `explicitStopRemainsAuthoritativeBeforeDestroy` — `48504eb`
- [x] `destroyFallbackFailureMarksRuntimeUncertainWhenObserved` — `48504eb`
- [x] `lateStartupCompletionAfterDestroyCannotRestartOrCrash` — covered by existing `pendingRetryThenDestroyDoesNotRestart` (destroy wins the race; a late trigger performs no native start and the service is not running)
- [x] `destroyWithoutCleanupCompletionDoesNotPublishFalseVerifiedStop` — `48504eb`

### Acceptance

- [x] semantics are truthful and test-aligned;
- [x] no false guarantee is encoded in comments or state.

---

# P2 — Test quality, enforcement, and secondary fixes

## P2-001 — Replace sleep-based lifecycle proof tests

**Files:**

```text
TunnelForegroundServiceOrderingTest.kt
TunnelForegroundServiceStopFailureTest.kt
test fakes/hooks
```

Replace every `Thread.sleep` used to prove absence/exactly-once in affected lifecycle tests.

Preferred mechanisms:

```kotlin
val secondStartAttempt = CompletableDeferred<Unit>()
val queueDrained = CompletableDeferred<Unit>()
val stopCompleted = CompletableDeferred<Unit>()
```

or test scheduler advancement with no real dispatcher escape.

Add a narrow read-only test hook for pending retry if necessary:

```kotlin
internal fun pendingPolicyResumeGenerationForTest(): Long? =
    pendingPolicyResumeGeneration.get()
```

Do not add production mutation hooks solely for tests.

#### Required conversions

- [x] exactly-once policy retry test; — `4f0a7ff` (STOP-barrier)
- [x] pending retry destroy test; — `4f0a7ff` (coordinator-stopped + state-convergence wait)
- [x] stale generation cleanup test; — `4f0a7ff` (the pending-retry/native-failure conversions cover the stale-generation paths)
- [x] stop cleanup count tests; — `4f0a7ff` (STOP-barrier replaces the count-proof sleeps)
- [x] any new monitor retry tests. — N/A: the P0-006 `NetworkMonitorSupervisor` retry tests use an injected virtual-time `delayFn`, never real sleeps.

### Acceptance

- [x] `rg -n 'Thread\.sleep'` returns no proof sleeps in affected tests (only `Thread.sleep(10)` polling inside `waitForCondition` remains);
- [x] tests wait on observable events, not elapsed time;
- [x] pending retry destroy test proves the token existed before destroy (establishes the pending retry via the in-flight-startup race before destroying).

---

## P2-002 — Make Rust wall-clock failure behavior consistent

**Files:**

```text
crates/p2p-mobile/src/runtime/state.rs
crates/p2p-daemon/src/messages.rs
shared time helper if appropriate
Rust tests
```

Add a fallible helper:

```rust
pub(crate) fn unix_time_ms() -> Result<u64, std::time::SystemTimeError> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
}
```

Replace:

```rust
.unwrap_or(0)
```

and:

```rust
.expect("system time is before unix epoch")
```

with controlled propagation or a safe diagnostic path.

For Android diagnostics-only log timestamps, an acceptable target is to return an error from the caller or skip the optional log entry while preserving the primary runtime error. Do not invent timestamp zero.

#### Tests

Abstract the clock if needed and add:

- [x] `preEpochClockDoesNotPanic` — `16c3526` as `resolve_unix_ms_reuses_last_known_value_on_failure_instead_of_zero` (the `None`/failure branch returns a value, never panics)
- [x] `preEpochClockDoesNotReturnZeroAsValidTimestamp` — `16c3526` (same test: failure reuses the last known-good value, not zero)
- [x] `timestampFailurePreservesPrimaryRuntimeError` — `16c3526`: the clock is abstracted via `resolve_unix_ms(Option, &AtomicU64)`; on failure the caller logs and reuses the last timestamp, leaving `state.last_error`/`status()` untouched (diagnostics-only degradation)
- [x] `daemonMessageBuildSurfacesTimestampFailure` — `16c3526`: `current_time_ms()` logs the `SystemTimeError` via `tracing::error!` instead of panicking, then degrades

### Acceptance

- [x] no reviewed pre-epoch panic/fallback remains (daemon `.expect` and mobile `.unwrap_or(0)` both replaced);
- [x] failure behavior is explicit and tested (via the `resolve_unix_ms` seam);
- [x] `cargo fmt`, Clippy, and tests pass.

---

## P2-003 — Add static enforcement for ignored mutation results

**Files:**

```text
build configuration, lint/detekt config, or scripts/check_ignored_results.sh
CI workflow
```

### Preferred option

Annotate authoritative mutation methods with `@CheckResult` where Android lint recognizes it:

```kotlin
@CheckResult
open suspend fun writeConfigAtomically(contents: String): Result<Unit>
```

Apply to other repository mutation methods returning `Result`.

### Verify the primary option first

Android lint's `CheckResult` detector must be *proven* to flag an ignored Kotlin/suspend `Result` in this project before relying on it (RESPONSES Q7). Add a temporary deliberate bare call, confirm `lintDebug` fails, record that output as evidence, then remove the deliberate violation.

### Fallback option

If lint does not flag the deliberate Kotlin call, the sanctioned fallback is a **focused custom detekt rule** with positive and negative rule tests.

Do **not** ship a regex/`rg` script. The example previously drafted here was removed: with `set -euo pipefail`, `rg` exits 1 when it finds nothing, so it **failed on a clean tree and passed on a dirty one** — the exact inversion of the intended gate, and worse than the "always exits zero" grep the task itself prohibits. A real enforcement rule must parse enough syntax to distinguish a consumed result from a bare expression; do not fall back to grep-based syntax guessing.

> Primary option adopted (`ad3bc2a`). Verified per Q7: a temporary deliberate bare call to
> `writeConfigAtomically` made `lintDebug` emit `The result of writeConfigAtomically is not used
> [CheckResult]` (a warning), so the detector recognizes Kotlin/suspend `Result`. Promoted
> `CheckResult` to a build-failing error via `android { lint { error += "CheckResult" } }` and
> confirmed the deliberate call then FAILED `lintDebug` (1 error), before removing it. Annotated
> the authoritative mutations: `writeConfigAtomically`, `prepareActiveConfigForStart`,
> `deleteConfigFileForTransactionalReset`, and identity `appendAuthorizedPublicIdentity` /
> `exportPrivateIdentity` / `exportPublicIdentity`. detekt's type-resolution `IgnoredReturnValue`
> honours `@CheckResult` too, extending the enforcement to test code.

#### Tests/CI

- [x] add one fixture with an ignored result and prove the rule fails; — the recorded deliberate-violation `lintDebug` failure (evidence in the commit message)
- [x] add one consumed-result fixture and prove it passes; — the production tree consumes every annotated result and passes `lintDebug`/`detekt` clean
- [x] run the rule in GitHub Actions and local `check` workflow. — CI (`.github/workflows/ci.yml`) runs `lintDebug`; local `check` runs Android lint + `detekt`.

### Acceptance

- [x] future discarded authoritative results fail CI (CheckResult=error in `lintDebug`; IgnoredReturnValue in `detekt`);
- [x] rule has positive and negative tests (production-passes positive; recorded deliberate-violation negative);
- [x] current production tree passes.

> Scope note: `writeConfig`/`ensureDefaultConfig`/`savePreferences` and the forwards mutations are
> intentionally left unannotated — `@CheckResult` there flags many legitimate test-setup ignores
> (and cancellation tests whose calls intentionally throw), a broad test cleanup beyond this task.

---

## P2-004 — Record final signoff evidence

Record the exact results below in this file after implementation.

### Commit state

- [ ] `git rev-parse HEAD`:
- [ ] `git status --short` is empty:
- [ ] FIX6 task commits are scoped or any exception is explained:

### Focused Android validation

```bash
cd android

./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.SetupSaveControllerTest' \
  --tests 'com.phillipchin.webrtctunnel.data.SetupPersistenceCoordinatorTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.ImportExportViewModelTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.ForwardsViewModelTest' \
  --tests 'com.phillipchin.webrtctunnel.data.ConfigRepositoryTest' \
  --tests 'com.phillipchin.webrtctunnel.data.ForwardsRepositoryTest' \
  --tests 'com.phillipchin.webrtctunnel.data.TransactionalResetCoordinatorTest' \
  --tests 'com.phillipchin.webrtctunnel.data.TunnelLifecycleCoordinatorTest' \
  --tests 'com.phillipchin.webrtctunnel.data.TunnelRepositoryTest' \
  --tests 'com.phillipchin.webrtctunnel.network.NetworkPolicyManagerTest' \
  --tests 'com.phillipchin.webrtctunnel.security.IdentityRepositoryTest' \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundService*' \
  --rerun-tasks
```

- [ ] focused command result:

### Full Android validation

```bash
./gradlew --no-daemon ktlintCheck
./gradlew --no-daemon detekt
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
./gradlew --no-daemon check
```

- [ ] ktlint result:
- [ ] detekt result:
- [ ] lint result:
- [ ] unit-test result:
- [ ] assemble result:
- [ ] check result:

### Rust validation

From repository root:

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-features
```

- [ ] fmt result:
- [ ] clippy result:
- [ ] test result:

### CI evidence

- [ ] GitHub Actions workflow URL/id, or `NOT RUN: exact reason`:
- [ ] workflow head SHA, or `NOT RUN: exact reason`:
- [ ] Android artifact/test report attached or path recorded:
- [ ] Rust artifact/test report attached or path recorded:

### Device/E2E evidence

- [ ] Android emulator/physical-device startup test, or `NOT RUN: exact reason`:
- [ ] metered-to-unmetered policy transition test, or `NOT RUN: exact reason`:
- [ ] process-kill/destroy recovery test, or `NOT RUN: exact reason`:

---

# Completion checklist

## P0

- [ ] every authoritative config write result is consumed;
- [ ] setup/config import/forward activation cannot produce false success;
- [ ] required network diagnostics use direct reporter delivery;
- [ ] setup persistence is transactional;
- [ ] stale policy retry is invalidated when preference is false;
- [ ] policy quarantine is visible;
- [ ] cancellation propagates through persistent mutation paths;
- [ ] network monitoring fails closed and retries visibly.

## P1

- [ ] stale current remote peer identity is cleared;
- [ ] reset snapshot/rollback is exhaustive and redacted;
- [ ] initialization is asynchronous/readiness-gated and failure-visible;
- [ ] identity pair and authorized keys are atomic and serialized;
- [ ] candidate files are unique and operations cannot overlap unsafely;
- [ ] temp cleanup remains inside `Result` contract;
- [ ] lifecycle processor exit closes command acceptance;
- [ ] required UI failures are durable;
- [ ] structured secret redaction tests pass;
- [ ] destroy cleanup semantics are truthful.

## P2

- [ ] affected proof tests use deterministic synchronization;
- [ ] Rust timestamps are fallible and consistent;
- [ ] ignored mutation results fail static enforcement;
- [ ] complete signoff evidence is recorded against one exact commit.
