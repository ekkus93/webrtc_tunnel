# WebRTC Tunnel Authoritative State, Atomic Commit, Durable Quarantine, and Failure Truthfulness FIX8 TODO

This TODO implements:

- `docs/WEBRTC_TUNNEL_AUTHORITATIVE_STATE_ATOMIC_COMMIT_DURABLE_QUARANTINE_FIX8_SPEC.md`
- against the code reviewed as `webrtc_tunnel-master_2607211131.zip`;
- using `docs/review-source/WEBRTC_TUNNEL_FIX7_CODE_REVIEW_2026-07-21.md` as the binding defect source.

All referenced assistant-created input files are included in the FIX8 handoff bundle at the exact paths above. Do not add a reference to another generated review, response, template, or companion document unless that file is also committed at the exact path named.

No checkbox in this document is pre-completed. Do not mark a task complete until production code, exact negative-path tests, focused validation, static checks, and the task commit SHA all exist.

---

# 0. Binding execution order

The order below is executable. Follow it unless a real code dependency requires a documented reorder.

## Stage A — eliminate setup prewrites and fix admission

1. **P0-001** setup-owned identity/forwards drafts and draft lifecycle.
2. **P0-002** actual-owner global admission and preference serialization.

## Stage B — exact repository primitives and complete transactions

3. **P0-003** exact config/setup-input APIs, atomic writes, and attempted-stage semantics.
4. **P0-004** exact forwards transaction state and one complete setup transaction.
5. **P0-005** import and forward activation cleanup-before-commit transactions.
6. **P0-006** exact reset that repairs corrupt state and rolls back the attempted stage.

## Stage C — security and runtime truth

7. **P0-007** identity rollback, checked deletion, and coherent reads.
8. **P0-008** broker-secret permissions and fatal-safe cleanup/file operations.
9. **P0-009** application-scoped runtime quarantine and quarantine-preserving status.
10. **P0-010** Rust/Kotlin diagnostic timestamp truthfulness.

## Stage D — integration and boundary hardening

11. **P1-001** setup operation ownership, redaction, and asynchronous baseline loading.
12. **P1-002** exactly-once application initialization.
13. **P1-003** Result contracts, `runCatching`, filesystem result, and raw-log audit.
14. **P1-004** close misleading/missing FIX7 production-path tests and CI flakiness.

## Stage E — permanent enforcement

15. **P2-001** static enforcement and negative fixtures.

## Stage F — immutable signoff

16. **P2-002** final local/CI/Docker/emulator evidence against one SHA.

Every task commit must be green. Do not intentionally commit a failing test, `@Ignore`, placeholder assertion, TODO-returning production branch, or temporary static-rule violation.

---

# 1. Work discipline

For every task:

```text
1. read the FIX8 spec, this task, current production code, and related tests
2. add/strengthen the exact negative-path test first
3. run it and confirm it fails for the intended reason
4. implement the smallest coherent production change
5. run the focused test class with --rerun-tasks
6. run ktlint/detekt/lint for Android or fmt/clippy for Rust
7. inspect git diff for unrelated changes and secret-bearing output
8. commit one scoped change
9. record the commit SHA beside every completed checkbox in this task
10. do not update another task's checkbox without its own evidence
```

## Hard rules

```text
no setup identity or forwards authoritative mutation before final Review commit
no private identity bytes in StateFlow/data class/SavedStateHandle/log/exception/toString
no partial current stage omitted from rollback
no String/default reconstruction presented as exact file rollback
no config commit inside candidate/workspace scope
no candidate/workspace cleanup Result discarded
no checked deletion replaced by File.delete()
no snapshot.existed=true accepted with null bytes
no permission setter/result ignored for a plaintext broker secret
no runtime quarantine stored only on one Service instance
no start/resume/retry while application-scoped runtime safety is quarantined
no destroy fallback allowed to clear pre-existing quarantine
no native status poll allowed to overwrite quarantine truth
no preference write outside global configuration admission
no production runCatching
no broad catch(Throwable) except the single cleanup-composition primitive that rethrows the same primary
no fatal Error converted to Result.failure
no diagnostic timestamp zero meaning unavailable
no double diagnostic failure converted to an indistinguishable empty list
no raw secret-bearing Throwable in logs/UI/state/JNI JSON
no Thread.sleep proving absence, ordering, overlap, exactly-once, or rollback completion
no test accepted as restoration proof unless exact destination bytes/presence are asserted
no signoff while any required validation is still running, skipped, flaky, or unchecked
```

## Required initial inventories

Save outputs under `.aiworkflow/logs/fix8/` or another committed evidence path named in the final implementation report.

```bash
mkdir -p .aiworkflow/logs/fix8

git rev-parse HEAD | tee .aiworkflow/logs/fix8/initial-head.txt
git status --short | tee .aiworkflow/logs/fix8/initial-status.txt

cd android
rg -n 'storeEncryptedIdentity\(|upsertWithReceipt\(|deleteWithReceipt\(' \
  app/src/main/java/com/phillipchin/webrtctunnel/viewmodel \
  | tee ../.aiworkflow/logs/fix8/setup-authoritative-mutation-inventory.txt

rg -n 'runCatching\s*\{|\.delete\(\)|mkdirs\(\)|setReadable\(|setWritable\(' \
  app/src/main/java/com/phillipchin/webrtctunnel \
  | tee ../.aiworkflow/logs/fix8/unsafe-api-inventory.txt

rg -n 'savePreferences\(|writeConfigAtomically\(|saveSetupInput\(|restoreSetupInput|readConfig\(|configFileExists' \
  app/src/main app/src/test \
  | tee ../.aiworkflow/logs/fix8/config-preference-inventory.txt

rg -n 'nativeRuntimeUncertain|nativeStopVerified|native_runtime_quarantined|getOrNull\(\)' \
  app/src/main/java/com/phillipchin/webrtctunnel \
  | tee ../.aiworkflow/logs/fix8/quarantine-inventory.txt

rg -n 'Thread\.sleep|assertFalse\s*\(\s*waitForCondition|delay\(' app/src/test \
  | tee ../.aiworkflow/logs/fix8/test-timing-inventory.txt

cd ..
rg -n 'unix_ms\s*:\s*0|"unix_ms"\s*:\s*0|Vec::new\(\).*log|recent_logs' crates \
  | tee .aiworkflow/logs/fix8/rust-diagnostic-fallback-inventory.txt
```

---

# P0 — Release blockers

# P0-001 — Make setup identity and forwards draft-only until final commit

**Review findings:** CRITICAL-1, HIGH-4, MEDIUM-5.  
**Primary files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupIdentityController.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupForwardsController.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupSaveController.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupIdentityDraft.kt (new, suggested)
related tests
```

## P0-001-A — Add a private identity draft owner

- [ ] Add a non-data `SetupIdentityDraft` owned by `SetupViewModel`. (`SHA: ______`)
- [ ] Store replacement private bytes, canonical public identity, and canonical peer ID only in that draft. (`SHA: ______`)
- [ ] Do not expose private bytes through `SetupWizardState`, `StateFlow`, Compose state, `SavedStateHandle`, logs, or exceptions. (`SHA: ______`)
- [ ] Wipe the previous byte array before replacing the draft. (`SHA: ______`)
- [ ] Wipe on setup cancel, ViewModel `onCleared`, and successful final commit. (`SHA: ______`)
- [ ] A save obtains an owned copy/transfer and wipes the save-owned bytes in `finally`. (`SHA: ______`)
- [ ] A failed save may retain the original draft for retry; the failed attempt's copy must still be wiped. (`SHA: ______`)

Target shape:

```kotlin
internal class SetupIdentityDraft {
    private val lock = Any()
    private var replacement: DraftIdentityReplacement? = null

    fun replace(
        privateIdentity: ByteArray,
        publicIdentity: String,
        peerId: String,
    ) = synchronized(lock) {
        require(privateIdentity.isNotEmpty())
        require(publicIdentity.isNotBlank())
        require(peerId.isNotBlank())
        replacement?.wipe()
        replacement = DraftIdentityReplacement(privateIdentity, publicIdentity, peerId)
    }

    fun copyForSave(): DraftIdentityReplacement? = synchronized(lock) {
        replacement?.copyForSave()
    }

    fun clear() = synchronized(lock) {
        replacement?.wipe()
        replacement = null
    }
}

internal class DraftIdentityReplacement(
    val privateIdentity: ByteArray,
    val publicIdentity: String,
    val peerId: String,
) {
    fun copyForSave() = DraftIdentityReplacement(
        privateIdentity = privateIdentity.copyOf(),
        publicIdentity = publicIdentity,
        peerId = peerId,
    )

    fun wipe() = privateIdentity.fill(0)
}
```

Do not make either class a data class.

## P0-001-B — Refactor every setup identity action

- [ ] `importIdentityFromPath()` reads, validates, requires canonical private/public/peer ID, and replaces the draft. (`SHA: ______`)
- [ ] `importIdentityFromUri()` does the same and no longer calls `IdentityRepository.storeEncryptedIdentity`. (`SHA: ______`)
- [ ] `generateIdentity()` does the same and no longer calls `IdentityRepository.storeEncryptedIdentity`. (`SHA: ______`)
- [ ] Remove `canonicalPublicIdentity.orEmpty()`. Missing canonical public identity is `setup_identity_invalid`. (`SHA: ______`)
- [ ] Remove `generated.peerId ?: current.input.localPeerId`. Missing generated peer ID fails closed. (`SHA: ______`)
- [ ] Do not re-read an import path at final save; save uses the validated draft to avoid TOCTOU replacement. (`SHA: ______`)
- [ ] Wipe every temporary encoded private `ByteArray` after ownership is transferred or validation fails. (`SHA: ______`)
- [ ] Redact native/file error messages before assigning UI state. (`SHA: ______`)

Suggested canonicalization helper:

```kotlin
private fun requireCanonicalIdentity(
    validated: IdentityValidationResult,
): DraftIdentityReplacement {
    require(validated.valid) { validated.message ?: "Invalid private identity" }
    val canonicalPrivate = requireNotNull(validated.canonicalPrivateIdentity) {
        "Identity validation returned no canonical private identity"
    }
    val canonicalPublic = requireNotNull(validated.canonicalPublicIdentity) {
        "Identity validation returned no canonical public identity"
    }
    val peerId = requireNotNull(validated.peerId) {
        "Identity validation returned no peer ID"
    }
    require(canonicalPrivate.isNotBlank())
    require(canonicalPublic.isNotBlank())
    require(peerId.isNotBlank())

    // The current bridge returns canonical private identity as a String; do not retain it.
    // The byte array below has explicit ownership and wiping.
    return DraftIdentityReplacement(canonicalPrivate.encodeToByteArray(), canonicalPublic, peerId)
}
```

Do not silently use `sourcePrivateIdentity` as the canonical value. If the native API intentionally does not canonicalize private identity, change its result contract explicitly and document that decision rather than using `?: source`.

## P0-001-C — Make setup forwards a pure draft

- [ ] `refreshForwards()` loads a baseline copy into the wizard draft. (`SHA: ______`)
- [ ] `upsertForward()` validates and changes only `SetupViewModel._forwards`. (`SHA: ______`)
- [ ] `deleteForward()` changes only `SetupViewModel._forwards`. (`SHA: ______`)
- [ ] Remove setup-controller calls to `ForwardsRepository.upsertWithReceipt/deleteWithReceipt`. (`SHA: ______`)
- [ ] The authoritative repository/list/file remains unchanged until final setup transaction success. (`SHA: ______`)
- [ ] Setup cancel discards the draft and asynchronously reloads the authoritative baseline. (`SHA: ______`)

Target mutation:

```kotlin
fun upsertForward(forward: ForwardConfig) {
    launchDraftOperation(SetupDraftOperation.ForwardEdit) {
        val before = access.forwards()
        val after = before.toMutableList().apply {
            val index = indexOfFirst { it.id == forward.id }
            if (index >= 0) set(index, forward) else add(forward)
        }
        deps.forwardsStore.validateForwards(after)?.let { error ->
            publishDraftFailure(error)
            return@launchDraftOperation
        }
        access.setForwards(after)
        access.applyState(access.state().copy(errorMessage = null, saveResult = "Forward draft updated"))
    }
}
```

The user-facing text must not claim the forward is authoritatively saved before final Review commit.

## P0-001-D — Final-save identity resolution

- [ ] `SetupSaveController` checks the draft first. (`SHA: ______`)
- [ ] If a draft exists, request an `IdentityReplacement` using the save-owned bytes. (`SHA: ______`)
- [ ] If no draft exists, read the already-stored identity coherently through `IdentityRepository`. (`SHA: ______`)
- [ ] Remove final-save branching based on `importIdentityPath`. (`SHA: ______`)
- [ ] Final save compares canonical draft/stored peer ID to `input.localPeerId` and fails closed. (`SHA: ______`)
- [ ] On successful persistence, clear the draft; on failure leave the original draft available for retry. (`SHA: ______`)

## P0-001-E — Exact tests

Add/strengthen:

- [ ] `setupWizardPathImportDoesNotMutateLiveIdentityBeforeFinalSave` (`SHA: ______`)
- [ ] `setupWizardUriImportDoesNotMutateLiveIdentityBeforeFinalSave` (`SHA: ______`)
- [ ] `setupWizardGenerateDoesNotMutateLiveIdentityBeforeFinalSave` (`SHA: ______`)
- [ ] `setupWizardForwardUpsertDoesNotMutateLiveForwardsOrConfigBeforeFinalSave` (`SHA: ______`)
- [ ] `setupWizardForwardDeleteDoesNotMutateLiveForwardsOrConfigBeforeFinalSave` (`SHA: ______`)
- [ ] `abandoningSetupWizardLeavesEveryAuthoritativeFileByteExact` (`SHA: ______`)
- [ ] `setupViewModelClearWipesDraftPrivateBytes` (`SHA: ______`)
- [ ] `replacingDraftIdentityWipesPreviousPrivateBytes` (`SHA: ______`)
- [ ] `failedFinalSaveWipesAttemptCopyButRetainsRetryableDraft` (`SHA: ______`)
- [ ] `successfulFinalSaveWipesAndClearsDraft` (`SHA: ______`)
- [ ] `missingCanonicalPublicIdentityFailsWithoutFallback` (`SHA: ______`)
- [ ] `missingGeneratedPeerIdFailsWithoutPriorPeerFallback` (`SHA: ______`)
- [ ] `pathFileReplacementAfterValidationCannotChangeCommittedIdentity` (`SHA: ______`)

For all “does not mutate” tests, snapshot exact bytes/presence of identity files, `authorized_keys`, secret, setup input, preferences, forwards, and config before the action and compare afterward.

## Acceptance

- [ ] No setup action writes authoritative identity or forwards before final commit. (`SHA: ______`)
- [ ] Setup abandonment is side-effect-free. (`SHA: ______`)
- [ ] Draft private bytes have explicit, tested ownership and wiping. (`SHA: ______`)
- [ ] No required identity field uses an empty/prior/source fallback. (`SHA: ______`)

---

# P0-002 — Fix actual-owner admission and serialize preference mutations

**Review findings:** HIGH-3, HIGH-8.  
**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigurationMutationCoordinator.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SettingsViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/NetworkPolicyViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupSaveController.kt
related tests
```

## P0-002-A — Replace late active metadata

- [ ] Add `ConfigurationOperation.PreferenceMutation`. (`SHA: ______`)
- [ ] Replace mutex-plus-late-`active.set` with an atomic owner token or equivalent no-window implementation. (`SHA: ______`)
- [ ] A busy result always uses the current token's operation. (`SHA: ______`)
- [ ] Release uses token identity, not only enum equality. (`SHA: ______`)
- [ ] Cancellation, ordinary exception, and fatal `Error` release admission and propagate unchanged. (`SHA: ______`)

Target implementation:

```kotlin
private data class ActiveConfigurationMutation(
    val id: Long,
    val operation: ConfigurationOperation,
)

class ConfigurationMutationCoordinator {
    private val sequence = AtomicLong(0)
    private val active = AtomicReference<ActiveConfigurationMutation?>(null)

    suspend fun <T> tryRun(
        operation: ConfigurationOperation,
        block: suspend () -> T,
    ): ConfigurationAdmission<T> {
        val token = ActiveConfigurationMutation(sequence.incrementAndGet(), operation)
        if (!active.compareAndSet(null, token)) {
            return ConfigurationAdmission.Busy(requireNotNull(active.get()).operation)
        }
        return try {
            ConfigurationAdmission.Completed(block())
        } finally {
            check(active.compareAndSet(token, null)) {
                "Configuration admission owner changed unexpectedly"
            }
        }
    }

    internal fun activeOperationForTest(): ConfigurationOperation? = active.get()?.operation
}
```

## P0-002-B — Serialize preference writes

- [ ] `SettingsViewModel.savePreferences` owns `PreferenceMutation` admission around the complete read/modify/write operation. (`SHA: ______`)
- [ ] `NetworkPolicyViewModel` preference writes do the same. (`SHA: ______`)
- [ ] Busy rejection is durable `configuration_operation_busy` and names the active operation. (`SHA: ______`)
- [ ] Success clears prior durable preference failure. (`SHA: ______`)
- [ ] Lifecycle/network reevaluation triggered by a preference change occurs after successful persistence; reporter failure cannot change persistence truth. (`SHA: ______`)

## P0-002-C — Use one preference snapshot during setup

- [ ] After global SetupSave admission, read preferences once. (`SHA: ______`)
- [ ] Use that same object for isolated validation rendering, final config rendering, and `SetupPersistenceRequest.preferences`. (`SHA: ______`)
- [ ] Remove the second `loadPreferences()` from `commitSetup`. (`SHA: ______`)
- [ ] Setup rollback restores the snapshot captured by the coordinator; no concurrent preference write can occur because global admission is held. (`SHA: ______`)

## P0-002-D — Tests

- [ ] `busyAdmissionDuringOwnerPublicationAlwaysReportsActualOwner` (`SHA: ______`)
- [ ] `sameOperationTypeCannotClearAnotherOwnerToken` (`SHA: ______`)
- [ ] `fatalErrorReleasesTokenAndPropagatesSameInstance` (`SHA: ______`)
- [ ] `settingsPreferenceMutationBlocksConcurrentSetupSaveDurably` (`SHA: ______`)
- [ ] `setupSaveBlocksConcurrentNetworkPreferenceMutationDurably` (`SHA: ______`)
- [ ] `concurrentPreferenceWriteCannotBeLostBySetupRollback` (`SHA: ______`)
- [ ] `setupValidationAndCommitUseSamePreferenceSnapshot` (`SHA: ______`)

Use barriers placed before the first persistence write; no timing assertions.

## Acceptance

- [ ] Busy always names the real owner. (`SHA: ______`)
- [ ] Every authoritative preference write is globally serialized. (`SHA: ______`)
- [ ] Setup render and persisted preferences are derived from one snapshot. (`SHA: ______`)

---

# P0-003 — Add exact config/setup-input APIs and attempted-stage rollback semantics

**Review findings:** CRITICAL-2, CRITICAL-3, HIGH-12.  
**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ExactFileSnapshot.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/AtomicFileReplacement.kt (new, suggested)
android/app/src/main/java/com/phillipchin/webrtctunnel/data/SetupPersistenceCoordinator.kt
related tests
```

## P0-003-A — Remove legacy String snapshots

- [ ] Delete `SetupInputSnapshot`, `captureSetupInputSnapshot`, and `restoreSetupInputSnapshot`. (`SHA: ______`)
- [ ] Remove `contents.orEmpty()` restoration. (`SHA: ______`)
- [ ] Remove all unchecked setup-input `File.delete()`. (`SHA: ______`)
- [ ] Do not store config snapshot as `configExisted + String`. (`SHA: ______`)

## P0-003-B — One repository file-serialization boundary

- [ ] Replace/rename `writeMutex` with one mutex that serializes both config and setup-input file operations. (`SHA: ______`)
- [ ] `readConfig`, config existence, exact capture, config writes/deletes/restores all use it. (`SHA: ______`)
- [ ] setup-input exact capture, atomic save, load read, and restore all use it. (`SHA: ______`)
- [ ] Avoid nested/reentrant acquisition by providing private `...Locked` helpers. (`SHA: ______`)

Suggested model:

```kotlin
internal class ConfigFilesSnapshot(
    val config: ExactFileSnapshot,
    val setupInput: ExactFileSnapshot,
) {
    fun wipeSecrets() = setupInput.wipe()
}
```

Suggested APIs:

```kotlin
@CheckResult
internal open suspend fun captureFilesSnapshot(): Result<ConfigFilesSnapshot> =
    fileMutex.withLock {
        mutationResult {
            ConfigFilesSnapshot(
                config = captureExactFileSnapshot(configFile).getOrThrow(),
                setupInput = captureExactFileSnapshot(setupInputFile).getOrThrow(),
            )
        }
    }

@CheckResult
internal open suspend fun saveSetupInputAtomically(input: SetupConfigInput): Result<Unit> =
    fileMutex.withLock {
        atomicReplaceBytesLocked(
            destination = setupInputFile,
            bytes = Json.encodeToString(input).encodeToByteArray(),
        )
    }
```

The actual helper names may change. Do not call a public locking method while already holding the same mutex.

## P0-003-C — Generic atomic byte replacement

- [ ] Create/centralize one same-directory temp plus atomic/replacement move primitive. (`SHA: ______`)
- [ ] Use `Files.createDirectories`, not ignored `mkdirs`. (`SHA: ______`)
- [ ] Support byte writes so exact snapshots do not round-trip through UTF-8 String. (`SHA: ______`)
- [ ] Return failure for every ordinary exception, including `SecurityException`. (`SHA: ______`)
- [ ] Preserve cancellation. (`SHA: ______`)
- [ ] Compose temp cleanup into the result. (`SHA: ______`)
- [ ] Allow an injected post-move verifier for broker-secret permissions. (`SHA: ______`)

## P0-003-D — Mark stage attempted before apply

- [ ] Rename `committed` to `attempted` or otherwise reflect the actual semantics. (`SHA: ______`)
- [ ] Add each stage before `applyStage`. (`SHA: ______`)
- [ ] Ordinary apply failure rolls back `attempted`, including current stage. (`SHA: ______`)
- [ ] Cancellation rolls back `attempted`, including current stage. (`SHA: ______`)
- [ ] Rollback remains reverse-order, `NonCancellable`, exhaustive, and idempotent. (`SHA: ______`)
- [ ] Success result lists successfully applied stages, not a stage that failed and was rolled back. (`SHA: ______`)

Target loop:

```kotlin
val applied = mutableListOf<SetupPersistenceStage>()
val attempted = mutableListOf<SetupPersistenceStage>()
try {
    for (stage in requestedStages(request)) {
        attempted += stage
        val result = applyStage(stage, request)
        if (result.isFailure) {
            return@withLock failureWithRollback(
                failedStage = stage,
                failure = result.exceptionOrNull(),
                snapshot = snapshot,
                attempted = attempted,
            )
        }
        applied += stage
    }
    SetupPersistenceResult.Success(applied)
} catch (cancelled: CancellationException) {
    val rollback = withContext(NonCancellable) {
        rollback(snapshot, attempted)
    }
    attachRollbackFailures(cancelled, rollback)
    throw cancelled
}
```

A stage that failed before mutation is still restored; restore is required to be idempotent.

## P0-003-E — Exact setup snapshot and wiping

- [ ] `SetupSnapshot` contains `ConfigFilesSnapshot`, not String-derived fields. (`SHA: ______`)
- [ ] Capture config/setup with one repository method. (`SHA: ______`)
- [ ] Snapshot capture failure aborts before any mutation. (`SHA: ______`)
- [ ] `SetupSnapshot.wipeSecrets()` wipes broker-secret and setup-input bytes. (`SHA: ______`)
- [ ] Wiping runs after success, ordinary failure, cancellation, and fatal propagation. (`SHA: ______`)

## P0-003-F — Result contracts

- [ ] `savePreferences()` catches all ordinary `Exception`, rethrows cancellation. (`SHA: ______`)
- [ ] config delete/write/restore helpers catch all ordinary `Exception`, not only `IOException`. (`SHA: ______`)
- [ ] Every authoritative API is `@CheckResult` and all callers consume it. (`SHA: ______`)

## P0-003-G — Exact tests

- [ ] `setupInputAtomicWriteFailureBeforeMoveLeavesPriorBytesExact` (`SHA: ______`)
- [ ] `setupInputFailureAfterMoveRestoresCurrentStageExactBytes` (`SHA: ______`)
- [ ] `setupInputCancellationAfterMoveRestoresCurrentStageExactBytes` (`SHA: ______`)
- [ ] `configFailureAfterMoveRestoresCurrentStageExactBytes` (`SHA: ______`)
- [ ] `configCleanupFailureAfterMoveRestoresCurrentStageExactBytes` (`SHA: ______`)
- [ ] `setupSnapshotDistinguishesAbsentPresentEmptyAndNonUtf8Bytes` (`SHA: ______`)
- [ ] `setupSnapshotCaptureIsSerializedAgainstConfigAndSetupWriters` (`SHA: ______`)
- [ ] `setupInputSnapshotBytesWipedAfterSuccessFailureCancellationAndFatalError` (`SHA: ______`)
- [ ] `snapshotFailurePerformsZeroMutationIncludingCurrentStage` (`SHA: ______`)
- [ ] `rollbackIncludesCurrentAttemptedStageForEverySetupStage` (`SHA: ______`)
- [ ] `securityExceptionFromConfigOperationReturnsFailureAndTriggersRollback` (`SHA: ______`)

Use injected file operations that perform the destination move and then fail cleanup/verification. The test must assert exact destination restoration.

## Acceptance

- [ ] Setup/config snapshots are exact, coherent, and secret-wiped. (`SHA: ______`)
- [ ] Failure/cancellation cannot skip a partially/post-commit-mutated current stage. (`SHA: ______`)
- [ ] No authoritative config/setup Result API unexpectedly throws an ordinary exception outside its contract. (`SHA: ______`)

---

# P0-004 — Add exact forwards transaction state and complete the setup transaction

**Review findings:** CRITICAL-1, CRITICAL-2, CRITICAL-3.  
**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStore.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/SetupPersistenceCoordinator.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupSaveController.kt
related tests
```

## P0-004-A — Exact forwards store snapshot/restore

- [ ] Extend `ForwardsStore` with internal exact snapshot/restore support or add an equivalent repository-owned file collaborator. (`SHA: ______`)
- [ ] Snapshot distinguishes absent, present-empty, and exact bytes. (`SHA: ______`)
- [ ] Restore uses atomic replacement or checked deletion. (`SHA: ______`)
- [ ] No list re-serialization is presented as exact rollback. (`SHA: ______`)

Possible interface:

```kotlin
interface TransactionalForwardsStore : ForwardsStore {
    @CheckResult
    fun captureExactSnapshot(): Result<ExactFileSnapshot>

    @CheckResult
    fun restoreExactSnapshot(snapshot: ExactFileSnapshot): Result<Unit>
}
```

## P0-004-B — Repository transaction snapshot

- [ ] Add `ForwardsTransactionSnapshot` captured under `ForwardsRepository` mutex. (`SHA: ______`)
- [ ] Include exact file snapshot, current list, load state, and load error needed for truthful restoration. (`SHA: ______`)
- [ ] `captureForTransaction()` fails if baseline is not Ready; do not snapshot placeholder empty state. (`SHA: ______`)
- [ ] `replaceForTransaction()` validates, saves, then publishes. (`SHA: ______`)
- [ ] `restoreForTransaction()` restores disk first, then in-memory state. (`SHA: ______`)
- [ ] Successful restore advances revision to invalidate pre-transaction receipts. (`SHA: ______`)
- [ ] Result APIs are `@CheckResult`. (`SHA: ______`)

## P0-004-C — Make ordinary forwards mutations failure-atomic

- [ ] `upsertWithReceipt`, `deleteWithReceipt`, reset, and transactional replace cannot leave disk changed when returning failure. (`SHA: ______`)
- [ ] Capture exact store state before save and self-restore if save reports failure after destination mutation. (`SHA: ______`)
- [ ] A self-restore failure returns a composed/typed rollback-incomplete failure. (`SHA: ______`)
- [ ] In-memory list is not published until final persistence success. (`SHA: ______`)

This is required even outside setup; the setup coordinator is not the only caller of `ForwardsRepository`.

## P0-004-D — Add setup `Forwards` stage

- [ ] Add `SetupPersistenceStage.Forwards` immediately before `Config`. (`SHA: ______`)
- [ ] Add full draft `forwards: List<ForwardConfig>` to `SetupPersistenceRequest`. (`SHA: ______`)
- [ ] Capture forwards transaction snapshot before first mutation. (`SHA: ______`)
- [ ] Apply full draft through `ForwardsRepository.replaceForTransaction`. (`SHA: ______`)
- [ ] Restore exact forwards state for failure/cancellation. (`SHA: ______`)
- [ ] Config remains last. (`SHA: ______`)

Required order:

```text
Identity
AuthorizedKeys
BrokerSecret
SetupInput
Preferences
Forwards
Config
```

## P0-004-E — Stage-specific identity restore

- [ ] Add `IdentityRepository.restoreIdentityPairSnapshot` for encrypted/public only. (`SHA: ______`)
- [ ] Add `restoreAuthorizedKeysSnapshot` for authorized keys only. (`SHA: ______`)
- [ ] `SetupPersistenceStage.Identity` uses pair restore. (`SHA: ______`)
- [ ] `AuthorizedKeys` uses authorized-key restore. (`SHA: ______`)
- [ ] Do not restore the full triplet twice during one rollback. (`SHA: ______`)

## P0-004-F — Setup controller request

- [ ] Pass the full setup draft forwards to `SetupPersistenceRequest`. (`SHA: ______`)
- [ ] Render validation/final config from enabled members of the same full draft. (`SHA: ______`)
- [ ] Pass the one preference snapshot from P0-002. (`SHA: ______`)
- [ ] Call the coordinator exactly once after validation workspace cleanup. (`SHA: ______`)
- [ ] Clear identity/forward drafts only after `SetupPersistenceResult.Success`. (`SHA: ______`)

## P0-004-G — Tests

- [ ] `setupCommitsFullDraftForwardsBeforeConfig` (`SHA: ______`)
- [ ] `forwardsFailureRollsBackCurrentStageAndEveryEarlierSetupStage` (`SHA: ______`)
- [ ] `configFailureRestoresExactForwardsBytesListLoadStateAndEarlierStages` (`SHA: ______`)
- [ ] `cancellationDuringForwardsRestoresCurrentForwardsStageAndEarlierStages` (`SHA: ______`)
- [ ] `cancellationDuringConfigRestoresForwardsAndEveryEarlierStage` (`SHA: ______`)
- [ ] `setupForwardsRollbackFailureIsListedAndDurable` (`SHA: ______`)
- [ ] `setupSuccessPublishesRepositoryForwardsOnlyAfterDiskCommit` (`SHA: ______`)
- [ ] `forwardsSavePostMoveCleanupFailureReturnsFailureAndRestoresDisk` (`SHA: ______`)
- [ ] `forwardsOrdinaryMutationRollbackFailureIsNotHidden` (`SHA: ______`)
- [ ] `setupIdentityAndAuthorizedKeysRollbackUseDistinctRestoreMembers` (`SHA: ______`)

## Acceptance

- [ ] Final setup is one transaction including authoritative forwards. (`SHA: ______`)
- [ ] A failed/cancelled forwards stage cannot leave disk, repository, or config inconsistent. (`SHA: ______`)
- [ ] Setup rollback does not duplicate holistic identity restoration. (`SHA: ______`)

---

# P0-005 — Make config import and forward activation cleanup-before-commit transactions

**Review findings:** CRITICAL-4, P1-001 test gap.  
**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ImportExportService.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardConfigurationCoordinator.kt (new, suggested)
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigRepository.kt
related tests
```

## P0-005-A — Config import ordering

- [ ] Candidate write and native validation occur inside `withCandidateFile`. (`SHA: ______`)
- [ ] Authoritative config write occurs only after `withCandidateFile` returns successfully, proving cleanup succeeded. (`SHA: ______`)
- [ ] Cleanup failure means no config write was attempted. (`SHA: ______`)
- [ ] Private identity bytes remain wiped on success/failure/cancellation. (`SHA: ______`)

Target shape:

```kotlin
val validatedContents = withCandidateFile(cacheDir, "config-import-") { candidate ->
    candidate.writeText(contents)
    val validation = validator.validate(candidate.absolutePath)
    require(validation.valid) { validation.message ?: "Imported config is invalid" }
    contents
} // candidate cleanup has completed here

configRepository.replaceConfigTransactionally(validatedContents).getOrThrow()
```

Do not call `writeConfigAtomically` inside the candidate block.

## P0-005-B — Failure-atomic config replace

- [ ] Add a repository method that captures exact config, attempts replacement, and restores exact prior state if the attempt returns failure after destination mutation. (`SHA: ______`)
- [ ] Run restore under `NonCancellable` when called from suspend mutation. (`SHA: ______`)
- [ ] Return a typed/composed rollback-incomplete failure when restore fails. (`SHA: ______`)
- [ ] Keep capture/attempt/restore under config repository serialization. (`SHA: ______`)

Possible result:

```kotlin
sealed interface ConfigReplacementResult {
    data object Success : ConfigReplacementResult
    data class Failed(
        val reason: String,
        val rollbackFailure: String? = null,
    ) : ConfigReplacementResult
}
```

A `Result<Unit>` with a typed exception is also acceptable if callers map rollback-incomplete separately.

## P0-005-C — Proposed-forward validation before mutation

- [ ] Build proposed list in memory from the authoritative baseline. (`SHA: ______`)
- [ ] Validate list and render candidate without mutating `ForwardsRepository`. (`SHA: ______`)
- [ ] Clean candidate successfully before authoritative mutation. (`SHA: ______`)
- [ ] No receipt is created before validation. (`SHA: ______`)

## P0-005-D — Forward configuration coordinator

- [ ] Add a data-layer coordinator with stages `Forwards`, `Config`. (`SHA: ______`)
- [ ] Capture exact forwards and config snapshots before mutation. (`SHA: ______`)
- [ ] Add stage to attempted set before apply. (`SHA: ______`)
- [ ] Apply forwards, then config. (`SHA: ______`)
- [ ] Roll back current and earlier attempted stages under `NonCancellable`. (`SHA: ______`)
- [ ] Cancellation rethrows with rollback failures suppressed. (`SHA: ______`)
- [ ] ViewModel maps rollback-complete and rollback-incomplete durably. (`SHA: ______`)

## P0-005-E — Preserve primary failure identity

- [ ] Validation failure remains primary when cleanup also fails; cleanup is suppressed. (`SHA: ______`)
- [ ] Cancellation remains primary when cleanup also fails. (`SHA: ______`)
- [ ] Config/forwards apply failure remains primary when rollback fails; rollback detail remains inspectable/redacted. (`SHA: ______`)

## P0-005-F — Exact tests

- [ ] `configImportCleanupFailurePerformsNoAuthoritativeConfigWrite` (`SHA: ______`)
- [ ] `configImportWritePostMoveFailureRestoresPreviousConfigBytes` (`SHA: ______`)
- [ ] `configImportRollbackFailureMapsConfigImportRollbackIncomplete` (`SHA: ______`)
- [ ] `configImportCancellationBeforeCommitLeavesConfigExact` (`SHA: ______`)
- [ ] `forwardCandidateCleanupFailureLeavesPreviousConfigAndForwardsExact` (`SHA: ______`)
- [ ] `forwardValidationFailureLeavesPreviousConfigAndForwardsExact` (`SHA: ______`)
- [ ] `forwardConfigFailureRestoresExactPreviousForwardsAndConfig` (`SHA: ______`)
- [ ] `forwardConfigCleanupFailureAfterMoveRestoresExactPreviousForwardsAndConfig` (`SHA: ______`)
- [ ] `forwardCancellationDuringConfigRestoresBothResources` (`SHA: ______`)
- [ ] `forwardRollbackContinuesAfterOneRestoreFailure` (`SHA: ______`)
- [ ] `forwardRollbackIncompleteListsEveryFailedRestore` (`SHA: ______`)

Strengthen the existing FIX7 tests: assert exact `config.toml` bytes, exact `forwards.json` bytes/presence, and repository list. Visible failure alone is insufficient.

## Acceptance

- [ ] Candidate cleanup failure cannot coexist with a newly committed config. (`SHA: ______`)
- [ ] Forward/config activation is one truthful transaction. (`SHA: ______`)
- [ ] All post-move failures restore exact prior state or report rollback incomplete. (`SHA: ______`)

---

# P0-006 — Make reset exact, repair corrupt drafts, and roll back the attempted stage

**Review findings:** CRITICAL-2, HIGH-5.  
**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/TransactionalReset.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SettingsViewModel.kt
related tests
```

## P0-006-A — Snapshot exact bytes without parsing

- [ ] Reset uses `ConfigRepository.captureFilesSnapshot()`. (`SHA: ______`)
- [ ] Remove `readConfig().toByteArray()` config snapshot. (`SHA: ______`)
- [ ] Do not call `loadSetupInputResult().getOrThrow()` before reset. (`SHA: ______`)
- [ ] Capture forwards with the exact transaction snapshot from P0-004. (`SHA: ______`)
- [ ] Snapshot failure identifies `Config`, `SetupInput`, or `Forwards` accurately. (`SHA: ______`)

## P0-006-B — Atomic reset mutations

- [ ] Setup-input reset uses `saveSetupInputAtomically(SetupConfigInput())`. (`SHA: ______`)
- [ ] Config reset uses atomic config replacement. (`SHA: ______`)
- [ ] Forwards reset is failure-atomic. (`SHA: ______`)
- [ ] No reset stage can return failure after a destination change without current-stage rollback. (`SHA: ______`)

## P0-006-C — Attempted-stage rollback

- [ ] Add each reset stage before apply. (`SHA: ______`)
- [ ] Ordinary failure restores current and earlier stages. (`SHA: ______`)
- [ ] Cancellation restores current and earlier stages under `NonCancellable`. (`SHA: ______`)
- [ ] Restore exact config/setup/forwards state. (`SHA: ______`)
- [ ] Wipe setup-input snapshot bytes in `finally`. (`SHA: ______`)

## P0-006-D — Corrupt-state repair

- [ ] Corrupt setup JSON does not block reset. (`SHA: ______`)
- [ ] Non-UTF-8 config/setup bytes do not block snapshot/reset. (`SHA: ______`)
- [ ] Reset success produces known defaults and clears prior durable reset failure. (`SHA: ______`)
- [ ] Reset failure preserves/restores corrupt prior bytes exactly rather than “repairing” during rollback. (`SHA: ______`)

## P0-006-E — Tests

- [ ] `corruptSetupInputDoesNotPreventReset` (`SHA: ______`)
- [ ] `nonUtf8ConfigSnapshotRestoresExactBytesAfterResetFailure` (`SHA: ______`)
- [ ] `resetSetupInputPostMoveFailureRestoresCurrentStageAndConfig` (`SHA: ______`)
- [ ] `resetForwardsPostMoveFailureRestoresCurrentStageSetupInputAndConfig` (`SHA: ______`)
- [ ] `resetCancellationDuringEachStageRestoresCurrentAndEarlierStages` (`SHA: ______`)
- [ ] `resetSnapshotFailureNamesActualComponentAndPerformsNoMutation` (`SHA: ______`)
- [ ] `resetRollbackRestoresAbsentPresentEmptyAndCorruptFilesExactly` (`SHA: ______`)
- [ ] `resetSnapshotSecretBytesWipedAfterSuccessFailureCancellationAndFatalError` (`SHA: ______`)

## Acceptance

- [ ] Reset can repair corrupt drafts. (`SHA: ______`)
- [ ] Reset rollback is byte-exact and includes current stage. (`SHA: ______`)
- [ ] Failure diagnostics identify the actual component. (`SHA: ______`)

---

# P0-007 — Make identity restore fail-closed, checked, and coherent

**Review findings:** CRITICAL-5, MEDIUM-3.  
**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/security/IdentityRepository.kt
related identity/setup tests
```

## P0-007-A — Remove fabricated bytes and unchecked deletion

- [ ] Replace every `snapshot.bytes ?: ByteArray(0)` with `requireNotNull`. (`SHA: ______`)
- [ ] Replace every identity rollback `File.delete()` with `Files.deleteIfExists`. (`SHA: ______`)
- [ ] Consume/check all deletion results/exceptions. (`SHA: ______`)
- [ ] Missing bytes for a present snapshot return a restore failure naming the logical file. (`SHA: ______`)

Target helper:

```kotlin
private fun restoreStoredFile(
    logical: IdentityStorageFile,
    file: File,
    snapshot: StoredFileSnapshot,
    atomicReplace: (File, ByteArray) -> Unit,
): Result<Unit> =
    try {
        if (snapshot.existed) {
            atomicReplace(
                file,
                requireNotNull(snapshot.bytes) { "$logical snapshot bytes are missing" },
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

## P0-007-B — Stage-specific restore APIs

- [ ] Add pair-only detailed restore. (`SHA: ______`)
- [ ] Add authorized-keys-only detailed restore. (`SHA: ______`)
- [ ] Keep holistic restore for callers that truly need all three. (`SHA: ______`)
- [ ] Every member is attempted independently. (`SHA: ______`)
- [ ] Returned reasons are fixed/redacted. (`SHA: ______`)

## P0-007-C — Coherent identity reads

- [ ] Add one locked method that reads encrypted identity and public identity as one coherent pair. (`SHA: ______`)
- [ ] Setup/stored identity resolution uses it. (`SHA: ______`)
- [ ] `readPublicIdentity`, `hasEncryptedIdentity`, and snapshot-related file reads cannot observe a pair replacement halfway through. (`SHA: ______`)
- [ ] Do not hold the storage lock while invoking native validation. Copy required file data, release, then validate. (`SHA: ______`)

Possible model:

```kotlin
internal class StoredIdentityMaterial(
    val encryptedPayload: ByteArray,
    val publicIdentity: String,
) {
    fun wipe() = encryptedPayload.fill(0)
}
```

If decryption produces plaintext, its owner must wipe it separately.

## P0-007-D — Directory and export checks

- [ ] Replace ignored identity/export parent `mkdirs()` with checked `Files.createDirectories`. (`SHA: ______`)
- [ ] Export failures are fixed/redacted at the ViewModel boundary. (`SHA: ______`)
- [ ] Atomic replacement cleanup does not log raw secret paths/Throwable. (`SHA: ______`)

## P0-007-E — Tests

- [ ] `identityPairCancellationAbsentEncryptedDeleteFailureIsSuppressed` (`SHA: ______`)
- [ ] `identityPairCancellationAbsentPublicDeleteFailureIsSuppressed` (`SHA: ______`)
- [ ] `presentSnapshotWithMissingBytesFailsWithoutCreatingEmptyIdentity` (`SHA: ______`)
- [ ] `pairRestoreAttemptsPublicAfterEncryptedDeleteFailure` (`SHA: ______`)
- [ ] `authorizedKeysRestoreFailureDoesNotReRestoreIdentityPair` (`SHA: ______`)
- [ ] `coherentIdentityReadNeverObservesMismatchedPairDuringReplacement` (`SHA: ______`)
- [ ] `identityReaderDoesNotHoldLockDuringNativeValidation` (`SHA: ______`)
- [ ] `identityExportParentCreationFailureIsVisibleAndRedacted` (`SHA: ______`)

## Acceptance

- [ ] Identity rollback never fabricates bytes or ignores deletion. (`SHA: ______`)
- [ ] Pair/read coherence is repository-enforced. (`SHA: ______`)
- [ ] Setup stage restores are distinct and exhaustive. (`SHA: ______`)

---

# P0-008 — Enforce broker-secret permissions and fatal-safe cleanup/file operations

**Review findings:** HIGH-1, HIGH-2, HIGH-12, MEDIUM-1/2/4.  
**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/BrokerSecretRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/MutationHelpers.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStore.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/security/IdentityRepository.kt
related tests
```

## P0-008-A — Owner-only permission enforcer

- [ ] Add an injectable permission enforcer/verifier. (`SHA: ______`)
- [ ] Use Android `Os.chmod`/`Os.stat` or an equivalent exact mode API where supported. (`SHA: ______`)
- [ ] Require resulting permission bits equivalent to `0600`. (`SHA: ______`)
- [ ] Secure the temp file before writing plaintext where feasible, and verify destination after move. (`SHA: ______`)
- [ ] Permission enforcement/verification failure returns `broker_secret_permissions_failed`. (`SHA: ______`)
- [ ] Restore also enforces/verifies owner-only permissions. (`SHA: ______`)
- [ ] Remove ignored `setReadable/setWritable` calls. (`SHA: ______`)

Suggested Android implementation:

```kotlin
private const val OWNER_READ_WRITE_MODE = 0x180 // octal 0600

internal fun enforceOwnerOnly(file: File) {
    Os.chmod(file.absolutePath, OWNER_READ_WRITE_MODE)
    val actual = Os.stat(file.absolutePath).st_mode and 0x1FF
    check(actual == OWNER_READ_WRITE_MODE) {
        "Broker secret permissions could not be verified"
    }
}
```

Use an injected fake in JVM tests. Do not include the path in the error message.

## P0-008-B — Fatal-safe cleanup composition

- [ ] Rewrite `withCleanupComposition` so cleanup runs after value, Exception, cancellation, and fatal Error. (`SHA: ______`)
- [ ] Preserve and rethrow the exact primary throwable instance. (`SHA: ______`)
- [ ] Attach cleanup failure as suppressed when primary exists. (`SHA: ______`)
- [ ] On primary success plus ordinary cleanup failure, throw fixed-message `CandidateCleanupException`. (`SHA: ______`)
- [ ] On primary success plus fatal cleanup `Error`, propagate that same `Error`. (`SHA: ______`)
- [ ] The narrow `catch (Throwable)` exists only in this primitive and is documented/enforced. (`SHA: ______`)

Target shape:

```kotlin
private sealed interface ScopedOutcome<out T> {
    data class Value<T>(val value: T) : ScopedOutcome<T>
    data class Failure(val throwable: Throwable) : ScopedOutcome<Nothing>
}

private suspend fun <T> withCleanupComposition(
    cleanup: () -> Result<Unit>,
    block: suspend () -> T,
): T {
    val outcome: ScopedOutcome<T> =
        try {
            ScopedOutcome.Value(block())
        } catch (primary: Throwable) {
            // Deliberately captured only so mandatory cleanup can run; rethrown unchanged below.
            ScopedOutcome.Failure(primary)
        }

    val cleanupFailure: Throwable? =
        try {
            cleanup().exceptionOrNull()
        } catch (failure: Throwable) {
            failure
        }

    return when (outcome) {
        is ScopedOutcome.Value -> {
            when (cleanupFailure) {
                null -> outcome.value
                is Error -> throw cleanupFailure
                else -> throw CandidateCleanupException(
                    "Failed to remove temporary configuration candidate",
                    cleanupFailure,
                )
            }
        }
        is ScopedOutcome.Failure -> {
            cleanupFailure?.let(outcome.throwable::addSuppressed)
            throw outcome.throwable
        }
    }
}
```

This is the only permitted production `catch (Throwable)`.

## P0-008-C — Checked directory/delete operations

- [ ] `createCandidateFile` and `withTemporaryDirectory` use `Files.createDirectories`. (`SHA: ______`)
- [ ] All secret/authoritative parent creation is checked. (`SHA: ______`)
- [ ] Cleanup helpers catch ordinary `Exception`, including `SecurityException`. (`SHA: ______`)
- [ ] No bare `File.delete()` remains in production authoritative paths. (`SHA: ______`)
- [ ] `ForwardsConfigStore.saveForwards` captures all ordinary exceptions and preserves primary/cleanup identity. (`SHA: ______`)
- [ ] Logging uses fixed messages plus redacted text; do not pass raw Throwable where it may reveal private paths. (`SHA: ______`)

## P0-008-D — Tests

- [ ] `brokerSecretPermissionFailureAfterMoveRestoresPriorSecret` (`SHA: ______`)
- [ ] `brokerSecretPermissionFailureBeforeFirstSecretLeavesFileAbsent` (`SHA: ______`)
- [ ] `brokerSecretRestoreVerifiesOwnerOnlyPermissions` (`SHA: ______`)
- [ ] `candidateFatalErrorRunsCleanupAndPropagatesSameErrorInstance` (`SHA: ______`)
- [ ] `workspaceFatalErrorRunsRecursiveCleanupAndPropagatesSameErrorInstance` (`SHA: ______`)
- [ ] `cleanupSecurityExceptionIsSuppressedOnPrimaryFailure` (`SHA: ______`)
- [ ] `cleanupFatalErrorAfterSuccessPropagatesSameError` (`SHA: ______`)
- [ ] `parentDirectoryCreationFailureOccursBeforeCandidateCreation` (`SHA: ______`)
- [ ] `noRawSecretPathAppearsInCleanupOrPermissionDiagnostics` (`SHA: ______`)

## Acceptance

- [ ] Broker-secret success proves owner-only permissions. (`SHA: ______`)
- [ ] Fatal errors cannot skip mandatory cleanup. (`SHA: ______`)
- [ ] Filesystem Boolean/runtime failures are not ignored or allowed to replace primary truth. (`SHA: ______`)

---

# P0-009 — Move runtime quarantine into application-scoped authoritative state

**Review findings:** CRITICAL-6, HIGH-10.  
**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/NativeRuntimeSafetyState.kt (new, suggested)
android/app/src/main/java/com/phillipchin/webrtctunnel/data/AppDependencies.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/OfferCoordinator.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/TunnelRepository.kt
related tests
```

## P0-009-A — Add application-scoped safety owner

- [ ] Add `NativeRuntimeSafetyState` as an application-scoped `AppDependencies` property. (`SHA: ______`)
- [ ] Store quarantine, stop verification, fixed/redacted reason, and generation. (`SHA: ______`)
- [ ] Expose read-only `StateFlow` or immutable snapshot. (`SHA: ______`)
- [ ] All updates are atomic/thread-safe. (`SHA: ______`)
- [ ] Remove service-owned `nativeRuntimeUncertain` and `nativeStopVerified` as sources of truth. (`SHA: ______`)

Use the target shape in the FIX8 spec or an equivalent model.

## P0-009-B — Apply state transitions consistently

- [ ] New native start attempt marks stop unverified without clearing quarantine. (`SHA: ______`)
- [ ] Every stop-like failure quarantines before reporting. (`SHA: ______`)
- [ ] Successful pause records observed stop but does not clear pre-existing quarantine. (`SHA: ______`)
- [ ] Successful destroy fallback records observed stop but does not clear pre-existing quarantine. (`SHA: ______`)
- [ ] Only verified explicit STOP clears quarantine. (`SHA: ______`)
- [ ] Explicit STOP failure preserves/enters quarantine. (`SHA: ______`)

## P0-009-C — Guard all start/resume/retry paths

- [ ] ACTION start reads application-scoped safety owner. (`SHA: ______`)
- [ ] Manual resume reads it. (`SHA: ______`)
- [ ] Policy resume reads it. (`SHA: ______`)
- [ ] Pending policy retry reads it. (`SHA: ______`)
- [ ] Automatic reconnect/start path, if present, reads it. (`SHA: ______`)
- [ ] Every guard failure clears pending retry and publishes durable recovery-required state. (`SHA: ______`)
- [ ] Replace `handleRetryPolicyResume`'s `getOrNull()` silent return with `getOrElse` plus durable reporting. (`SHA: ______`)

Suggested mapping:

```kotlin
service.requireRuntimeStartAllowed().getOrElse { error ->
    service.invalidatePendingPolicyRetry()
    service.reporter.publishErrorSafely(
        code = "native_runtime_recovery_required",
        message = SensitiveDataRedactor.redactText(
            error.message ?: "Verified explicit stop is required before restart",
        ),
    )
    return
}
```

## P0-009-D — Preserve quarantine through repository refresh

- [ ] Inject/read runtime safety state in `TunnelRepository`. (`SHA: ______`)
- [ ] `refreshStatusResult` overlays/preserves quarantined Error state regardless of mapped native active state. (`SHA: ______`)
- [ ] Status decode/unknown errors still clear stale live peer/session/MQTT fields. (`SHA: ______`)
- [ ] A native Stopped status alone does not clear quarantine. (`SHA: ______`)
- [ ] Explicit-stop recovery clears safety owner first/atomically with final status publication so no start window exists. (`SHA: ______`)

## P0-009-E — Service recreation

- [ ] New service instance initializes from shared safety owner. (`SHA: ______`)
- [ ] Recreated service cannot start while owner is quarantined. (`SHA: ______`)
- [ ] Recreated service can receive explicit STOP and clear quarantine only after verification. (`SHA: ______`)
- [ ] Old service destruction cannot clear owner state after a newer service generation has changed it; use generation/token checks where needed. (`SHA: ______`)

## P0-009-F — Tests

- [ ] `serviceRecreationWhileQuarantinedStillBlocksNativeStart` (`SHA: ______`)
- [ ] `serviceRecreationWhileQuarantinedStillBlocksManualResume` (`SHA: ______`)
- [ ] `pendingPolicyRetryQuarantineGuardFailureIsDurableAndVisible` (`SHA: ______`)
- [ ] `destroyFallbackSuccessDoesNotClearPreexistingQuarantine` (`SHA: ______`)
- [ ] `successfulPauseDoesNotClearPreexistingQuarantine` (`SHA: ______`)
- [ ] `nativeStatusRefreshCannotOverwriteQuarantineWithConnected` (`SHA: ______`)
- [ ] `nativeStatusRefreshCannotOverwriteQuarantineWithStopped` (`SHA: ______`)
- [ ] `verifiedExplicitStopClearsSharedQuarantineForLaterServiceInstance` (`SHA: ______`)
- [ ] `staleServiceDestroyCannotClearNewerRuntimeSafetyGeneration` (`SHA: ______`)
- [ ] `reporterFailureCannotPreventSharedQuarantineTransition` (`SHA: ______`)

Construct two service instances sharing one test application/dependency graph. Do not simulate recreation by mutating a local Boolean.

## Acceptance

- [ ] Quarantine survives service recreation and status polling. (`SHA: ______`)
- [ ] Every blocked retry is visible. (`SHA: ______`)
- [ ] Only verified explicit STOP authorizes recovery. (`SHA: ______`)

---

# P0-010 — Remove Rust/JNI zero and empty diagnostic fallbacks

**Review findings:** HIGH-6.  
**Files:**

```text
crates/p2p-mobile/src/runtime/types.rs
crates/p2p-mobile/src/runtime/state.rs
crates/p2p-mobile/src/runtime/log_bridge.rs
crates/p2p-mobile/src/runtime/mod.rs
crates/p2p-mobile/src/c_abi.rs
crates/p2p-mobile/src/jni_bridge.rs
crates/p2p-core/tests/no_pre_epoch_panics.rs
android/app/src/main/java/com/phillipchin/webrtctunnel/model/Models.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/TunnelRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/ui/LogsScreen.kt
related tests
```

## P0-010-A — Make log timestamp optional end to end

- [ ] Change Rust `AndroidLogEvent.unix_ms` to `Option<u64>`. (`SHA: ______`)
- [ ] Normal log events use `Some(unix_ms)`. (`SHA: ______`)
- [ ] Update serde/JNI/C ABI tests. (`SHA: ______`)
- [ ] Change Kotlin `NativeLogEventDto.unixMs` and `LogEvent.unixMs` to nullable. (`SHA: ______`)
- [ ] Redaction preserves null. (`SHA: ______`)
- [ ] UI formats null as fixed “time unavailable” text and uses a stable key not dependent solely on timestamp. (`SHA: ______`)
- [ ] Export/share logs represent null explicitly and do not print `0`. (`SHA: ______`)

## P0-010-B — Visible fallback events

- [ ] JNI invalid-UTF8 log fallback returns an error event with `unix_ms: null`. (`SHA: ______`)
- [ ] C ABI log-buffer failure with available clock returns `Some(time)`. (`SHA: ______`)
- [ ] C ABI log-buffer plus clock failure returns one error event with `None`, not `Vec::new()`. (`SHA: ______`)
- [ ] Message is fixed/redacted and does not include raw poison/internal details beyond an approved safe reason. (`SHA: ______`)
- [ ] Extract pure helper seams so both fallback branches are directly unit-tested. (`SHA: ______`)

Target:

```rust
fn diagnostic_failure_event(message: impl Into<String>, unix_ms: Option<u64>) -> AndroidLogEvent {
    AndroidLogEvent {
        unix_ms,
        level: "error".to_owned(),
        message: message.into(),
    }
}
```

## P0-010-C — Strengthen static guard

- [ ] Detect production struct literals `unix_ms: 0`. (`SHA: ______`)
- [ ] Detect production JSON fallback containing `"unix_ms":0`. (`SHA: ______`)
- [ ] Detect `None => Vec::new()` in the recent-log failure path through a direct behavior test, not regex alone. (`SHA: ______`)
- [ ] Keep legitimate test data with timestamp zero only where explicitly testing deserialization; do not let it satisfy production fallback checks. (`SHA: ______`)

## P0-010-D — Tests

- [ ] `jniInvalidUtf8LogFallbackUsesNullTimestampNotZero` (`SHA: ______`)
- [ ] `recentLogAndClockDoubleFailureReturnsVisibleUntimedErrorEvent` (`SHA: ______`)
- [ ] `normalRecentLogSerializesSomeTimestamp` (`SHA: ______`)
- [ ] `kotlinDecodesNullNativeLogTimestamp` (`SHA: ______`)
- [ ] `logsScreenDisplaysTimeUnavailableForNullTimestamp` (`SHA: ______`)
- [ ] `logExportNeverPrintsZeroForUnavailableTimestamp` (`SHA: ______`)
- [ ] `workspaceContainsNoProductionZeroTimestampDiagnosticFallback` (`SHA: ______`)

## Acceptance

- [ ] Unavailable time is null/None, never zero. (`SHA: ______`)
- [ ] Double diagnostic failure remains visible. (`SHA: ______`)
- [ ] Rust/Kotlin schema and UI agree. (`SHA: ______`)

---

# P1 — High-priority integration and boundary hardening

# P1-001 — Serialize setup-local operations, redact boundaries, and load asynchronously

**Review findings:** HIGH-4, HIGH-11, MEDIUM-5.  
**Files:**

```text
SetupViewModel.kt
SetupIdentityController.kt
SetupForwardsController.kt
SetupSaveController.kt
new setup-local coordinator/load-state files as needed
related tests
```

## P1-001-A — Shared setup-local operation coordinator

- [ ] Add `SetupDraftOperation` values for baseline load, identity action, forward edit, validation/navigation, and final save. (`SHA: ______`)
- [ ] One shared coordinator serializes all asynchronous setup actions. (`SHA: ______`)
- [ ] `isBusy` is derived from actual ownership, not independently toggled by several controllers. (`SHA: ______`)
- [ ] Busy rejection is durable/visible and names active setup operation. (`SHA: ______`)
- [ ] Final save holds setup-local ownership while acquiring/using global SetupSave admission. (`SHA: ______`)
- [ ] No stale action completion may overwrite newer draft state; use operation token/generation where cancellation is allowed. (`SHA: ______`)

## P1-001-B — Explicit setup baseline state

- [ ] Add `SetupLoadState.Initializing/Ready/Failed`. (`SHA: ______`)
- [ ] Move setup-input read/decode to IO dispatcher. (`SHA: ______`)
- [ ] Move stored identity baseline load to IO. (`SHA: ______`)
- [ ] Move forwards baseline load to IO. (`SHA: ______`)
- [ ] Publish Ready only when all required baselines are coherent. (`SHA: ______`)
- [ ] Block Next/final save while Initializing or Failed. (`SHA: ______`)
- [ ] Failure is durable `setup_draft_load_failed`; do not silently use defaults when an existing file is corrupt. (`SHA: ______`)

Suggested initialization:

```kotlin
init {
    viewModelScope.launch {
        setupOperations.run(SetupDraftOperation.BaselineLoad) {
            val loaded = withContext(deps.dispatchers.io) { loadSetupBaseline(deps) }
            applyLoadedBaseline(loaded)
        }
    }
}
```

No synchronous file read/decode may occur in constructor/init before launch.

## P1-001-C — Boundary error handling/redaction

- [ ] Every setup action catches cancellation first and rethrows. (`SHA: ______`)
- [ ] Every ordinary failure produces fixed/redacted UI failure. (`SHA: ______`)
- [ ] `launchBusy`/replacement helper does not allow uncaught ordinary exceptions to merely clear busy. (`SHA: ______`)
- [ ] Remote public identity import removes `runCatching`. (`SHA: ______`)
- [ ] No raw native validation message is assigned without redaction unless it is a fixed application-authored message. (`SHA: ______`)
- [ ] Success clears prior error; cancellation emits no ordinary success/failure. (`SHA: ______`)

## P1-001-D — Tests

- [ ] `setupViewModelConstructionPerformsNoFileIoOnMainThread` (`SHA: ______`)
- [ ] `setupLoadInitializingBlocksNextAndSave` (`SHA: ______`)
- [ ] `setupLoadReadyUsesLoadedDraftBaseline` (`SHA: ______`)
- [ ] `setupLoadFailureIsDurableAndDoesNotUseBlankFallback` (`SHA: ______`)
- [ ] `overlappingIdentityAndForwardActionsCannotPublishStaleBusyOrState` (`SHA: ______`)
- [ ] `setupActionExceptionIsRedactedAndDurable` (`SHA: ______`)
- [ ] `setupActionCancellationEmitsNoOrdinaryResultAndReleasesOwnership` (`SHA: ______`)

## Acceptance

- [ ] Setup screen has no main-thread file I/O. (`SHA: ______`)
- [ ] Setup busy/load state is truthful across all controllers. (`SHA: ______`)
- [ ] No setup failure escapes silently or leaks raw details. (`SHA: ______`)

---

# P1-002 — Make application initialization exactly once under concurrency

**Review finding:** HIGH-7.  
**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/AppInitialization.kt
related tests
```

## P1-002-A — Lazy winner-only start

- [ ] Create candidate job with `CoroutineStart.LAZY`. (`SHA: ______`)
- [ ] CAS the lazy job into `startedJob`. (`SHA: ______`)
- [ ] Only the winner calls `start()`. (`SHA: ______`)
- [ ] Cancel the losing lazy job before it can execute. (`SHA: ______`)
- [ ] Repeated callers return the same winner job. (`SHA: ______`)

Target:

```kotlin
fun start(): Job {
    startedJob.get()?.let { return it }

    val candidate = scope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
        initialize()
    }
    return if (startedJob.compareAndSet(null, candidate)) {
        candidate.start()
        candidate
    } else {
        candidate.cancel()
        requireNotNull(startedJob.get())
    }
}
```

## P1-002-B — Tests

- [ ] `concurrentInitializationStartRunsInitializeExactlyOnce` (`SHA: ______`)
- [ ] `losingLazyInitializationJobExecutesNoInstruction` (`SHA: ______`)
- [ ] `allConcurrentCallersReceiveSameWinnerJob` (`SHA: ______`)
- [ ] `initializationFailureStillPublishesOneFailedState` (`SHA: ______`)

Use a barrier at the first line of `initialize`; assert entry count exactly one.

## Acceptance

- [ ] Initialization is genuinely exactly-once, not cancel-after-start. (`SHA: ______`)

---

# P1-003 — Complete Result, runCatching, filesystem, and raw-log audit

**Review findings:** HIGH-9, HIGH-12, MEDIUM-1/2/4.  
**Files:** all production Kotlin inventory hits and related tests/static config.

## P1-003-A — Remove production `runCatching`

- [ ] Replace pure parse/read uses with explicit `try/catch (Exception)`. (`SHA: ______`)
- [ ] Replace `System.loadLibrary` use with explicit `catch (UnsatisfiedLinkError)` only. (`SHA: ______`)
- [ ] Let unrelated fatal errors propagate. (`SHA: ______`)
- [ ] Delete “safe as runCatching” marker comments and the marker-based enforcement test. (`SHA: ______`)
- [ ] No production `runCatching {` remains. (`SHA: ______`)

Example:

```kotlin
val loadFailure: UnsatisfiedLinkError? =
    try {
        System.loadLibrary("p2p_mobile")
        null
    } catch (error: UnsatisfiedLinkError) {
        error
    }
```

Do not catch `LinkageError` broadly unless every subtype is deliberately normalized and tested.

## P1-003-B — Result APIs catch every ordinary exception

- [ ] Audit every public/internal `Result`-returning mutation/snapshot/restore. (`SHA: ______`)
- [ ] Rethrow cancellation where suspend/coroutine-relevant. (`SHA: ______`)
- [ ] Catch `Exception`, not selected subclasses only, unless the signature explicitly documents throwing other ordinary exceptions. (`SHA: ______`)
- [ ] Add `@CheckResult` and consume all authoritative results. (`SHA: ______`)
- [ ] Do not use `.also { }` as fake consumption when the result should be interpreted; add an explicit `ignoreResultBecause...` helper only for genuinely side-effect-authoritative calls, or redesign. (`SHA: ______`)

## P1-003-C — Filesystem and raw logging inventory

- [ ] Replace every ignored `mkdirs`, `delete`, permission setter in authoritative paths. (`SHA: ______`)
- [ ] Audit remaining `File.delete()` uses and document non-authoritative exceptions. (`SHA: ______`)
- [ ] Remove raw `Throwable` logging from identity, forwards, broker-secret, config, notification, and ViewModel failure paths. (`SHA: ______`)
- [ ] Log fixed code plus redacted message; never private app paths or content. (`SHA: ______`)
- [ ] A logging failure cannot replace the primary operation outcome. (`SHA: ______`)

## P1-003-D — Tests/static fixtures

- [ ] `productionContainsNoRunCatchingCall` (`SHA: ______`)
- [ ] `nativeLibraryLoadNormalizesOnlyUnsatisfiedLinkError` (`SHA: ______`)
- [ ] `fatalErrorFromParserOrPropertyReadPropagates` (`SHA: ______`)
- [ ] `securityExceptionFromEachResultApiBecomesFailureOrDocumentedThrow` (`SHA: ______`)
- [ ] `rawPrivatePathSentinelNeverAppearsInProductionDiagnosticStateOrLogs` (`SHA: ______`)
- [ ] `authoritativeFilesystemOperationsContainNoUncheckedBooleanResult` (`SHA: ______`)

## Acceptance

- [ ] Production has no `runCatching`. (`SHA: ______`)
- [ ] Result contracts match caller assumptions. (`SHA: ______`)
- [ ] No authoritative filesystem failure is ignored or leaked raw. (`SHA: ______`)

---

# P1-004 — Close missing/misleading production-path tests and CI nondeterminism

**Review findings:** P2-001 gaps, incomplete FIX7 signoff, CI flakes.  
**Files:** related Android/Rust tests, test seams, CI.

## P1-004-A — Strengthen misleading cleanup tests

- [ ] Existing import cleanup test asserts exact previous `config.toml` bytes/presence. (`SHA: ______`)
- [ ] Existing forward cleanup test asserts exact previous config and forwards file/list. (`SHA: ______`)
- [ ] Rename any test whose body proves less than its name. (`SHA: ______`)
- [ ] Every rollback-incomplete test injects failure in a restore, not only a forward apply. (`SHA: ______`)

## P1-004-B — Complete previously unchecked exact paths

- [ ] Add/execute Android stop-while-Listening/no-peer integration/instrumentation test. (`SHA: ______`)
- [ ] Add deterministic late-startup-completion-after-destroy test using an injectable pause point or lifecycle collaborator. (`SHA: ______`)
- [ ] Add a real production reporter-callback failure test through an injectable reporter/notification seam. (`SHA: ______`)
- [ ] Add service-recreation quarantine integration test from P0-009. (`SHA: ______`)
- [ ] Add live metered-to-unmetered emulator E2E step/script. (`SHA: ______`)

If a branch is genuinely unreachable after refactoring, remove the misleading requirement/code path and prove unreachability through the new production collaborator tests. Do not retain a named “deviation” as final completion.

## P1-004-C — Remove CI timing nondeterminism

- [ ] Inventory every test using real `Dispatchers.IO` plus bounded polling. (`SHA: ______`)
- [ ] Replace ordering/absence uses with barriers/injected dispatchers. (`SHA: ______`)
- [ ] Positive external convergence polls have one shared helper and documented bounded purpose. (`SHA: ______`)
- [ ] Do not merely widen timeouts without identifying the event seam. (`SHA: ______`)
- [ ] Run affected classes repeatedly before full signoff. (`SHA: ______`)

## P1-004-D — Tests for test seams

- [ ] `lateStartupCompletionAfterDestroyIsRejectedByRealGenerationPath` (`SHA: ______`)
- [ ] `productionReporterThrowCannotPreventQuarantineOrProcessorFailureTruth` (`SHA: ______`)
- [ ] `stopWhileListeningWithoutPeerReportsStoppedNotErrorInstrumentation` (`SHA: ______`)
- [ ] `meteredToUnmeteredTransitionPausesAndResumesAccordingToPreferenceE2E` (`SHA: ______`)

## Acceptance

- [ ] No FIX8/FIX7 invariant is accepted through an honestly-labeled but incomplete deviation. (`SHA: ______`)
- [ ] CI-relevant tests are deterministic without retry-until-green. (`SHA: ______`)

---

# P2 — Enforcement and signoff

# P2-001 — Add permanent static enforcement and negative fixtures

**Files:** Android build/detekt/lint/test config, Rust tests, CI.

## P2-001-A — Android enforcement

- [ ] Production `runCatching` is forbidden by a permanent rule/test. (`SHA: ______`)
- [ ] Bare authoritative `File.delete()` is forbidden. (`SHA: ______`)
- [ ] Ignored `mkdirs`, `setReadable`, and `setWritable` are forbidden or absent. (`SHA: ______`)
- [ ] Setup controllers are forbidden from calling `storeEncryptedIdentity`, `upsertWithReceipt`, or `deleteWithReceipt`. (`SHA: ______`)
- [ ] `@CheckResult` enforcement covers new exact snapshot/restore/transaction APIs. (`SHA: ______`)
- [ ] Add a committed negative fixture/rule test for at least one ignored authoritative result; do not rely only on a historical temporary edit. (`SHA: ______`)
- [ ] The one cleanup-composition `catch (Throwable)` is allowlisted by exact function/file; any second production hit fails. (`SHA: ______`)

## P2-001-B — Snapshot/fallback enforcement

- [ ] No production `snapshot.bytes ?: ByteArray(0)` or equivalent exists. (`SHA: ______`)
- [ ] No setup/config rollback uses `orEmpty` or String-derived exact snapshots. (`SHA: ______`)
- [ ] No config write exists inside a `withCandidateFile/withTemporaryDirectory` block in import/forward paths. (`SHA: ______`)
- [ ] Add source/architecture tests for these boundaries. (`SHA: ______`)

## P2-001-C — Rust enforcement

- [ ] Production zero timestamp diagnostic fallback fails a permanent test. (`SHA: ______`)
- [ ] Recent-log double failure returning empty list fails a direct unit test. (`SHA: ______`)
- [ ] Existing pre-epoch panic inventory remains green. (`SHA: ______`)

## P2-001-D — CI wiring

- [ ] `./gradlew --no-daemon check` runs type-resolved detekt/lint/tests and new fixtures. (`SHA: ______`)
- [ ] Rust fmt/clippy/test commands include all features/targets. (`SHA: ______`)
- [ ] CI does not auto-rerun failed tests and report only the successful attempt as signoff. (`SHA: ______`)
- [ ] Preserve first-failure artifacts/logs. (`SHA: ______`)

## Acceptance

- [ ] Reintroducing any FIX8 unsafe fallback or ignored authoritative result fails CI. (`SHA: ______`)
- [ ] Static checks are precise enough not to be satisfied by comments. (`SHA: ______`)

---

# P2-002 — Final validation and immutable signoff

Do not begin signoff while any checkbox above is open.

## P2-002-A — Repository and handoff state

- [ ] Record `git rev-parse HEAD`: `________________`.  
- [ ] Record branch: `________________`.  
- [ ] `git status --short` is empty.  
- [ ] Confirm these exact input files exist:

```text
docs/WEBRTC_TUNNEL_AUTHORITATIVE_STATE_ATOMIC_COMMIT_DURABLE_QUARANTINE_FIX8_SPEC.md
docs/WEBRTC_TUNNEL_AUTHORITATIVE_STATE_ATOMIC_COMMIT_DURABLE_QUARANTINE_FIX8_TODO.md
docs/review-source/WEBRTC_TUNNEL_FIX7_CODE_REVIEW_2026-07-21.md
docs/review-source/WEBRTC_TUNNEL_FIX8_HANDOFF_MANIFEST.md
```

- [ ] Create `docs/review-source/WEBRTC_TUNNEL_FIX8_IMPLEMENTATION_REPORT.md` during implementation with commit/task/command/evidence details. This is a required Claude Code output, not an input assumed to exist.  
- [ ] Confirm no handoff document references another unavailable assistant-created file.  

## P2-002-B — Focused Android validation

Construct one explicit `testDebugUnitTest --rerun-tasks` command covering every touched class, including at minimum:

```text
SetupViewModelTest
SetupIdentityControllerTest
SetupForwardsControllerTest
SetupSaveControllerTest
SetupValidationWorkspaceIntegrationTest
ConfigurationMutationCoordinatorTest
ConfigurationMutationIntegrationTest
ConfigRepositoryTest
ExactFileSnapshotTest
MutationHelpersTest
SetupPersistenceCoordinatorTest
ForwardsConfigStoreTest
ForwardsRepositoryTest
ForwardConfigurationCoordinatorTest
ImportExportServiceTest
ImportExportViewModelTest
ForwardsViewModelTest
TransactionalReset*Test
SettingsViewModelTest
NetworkPolicyViewModelTest
IdentityRepositoryTest
IdentityPersistenceAtomicityTest
BrokerSecretRepositoryTest
AppInitializationCoordinatorTest
TunnelRepositoryTest
TunnelLifecycleCoordinatorTest
TunnelForegroundService*Test
```

- [ ] Focused command recorded.  
- [ ] Focused result PASS with zero failures.  

## P2-002-C — Full Android validation

```bash
cd android
./gradlew --no-daemon ktlintCheck
./gradlew --no-daemon detekt
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest --rerun-tasks
./gradlew --no-daemon testDebugUnitTest --rerun-tasks
./gradlew --no-daemon testDebugUnitTest --rerun-tasks
./gradlew --no-daemon assembleDebug
./gradlew --no-daemon check
```

- [ ] ktlint PASS.  
- [ ] type-resolved detekt PASS.  
- [ ] lintDebug PASS.  
- [ ] three consecutive full unit reruns PASS without retry or ordering leakage.  
- [ ] assembleDebug PASS.  
- [ ] check PASS.  

If one run fails, signoff stops until the failure is understood and fixed. Do not rerun until green and call it complete.

## P2-002-D — Rust validation

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-features
cargo test -p p2p-daemon --test real_broker_tunnel --all-features
```

- [ ] fmt PASS.  
- [ ] clippy PASS, zero warnings.  
- [ ] workspace tests PASS.  
- [ ] real broker test executes and PASSes rather than self-skipping.  
- [ ] Null-timestamp and double-log-failure tests identified in report.  

## P2-002-E — Docker and emulator E2E

- [ ] Docker real TLS broker/data path PASS.  
- [ ] Docker stop lifecycle PASS.  
- [ ] Android APK installs.  
- [ ] Setup wizard reaches Review without pre-commit identity/forwards file mutation (instrumented/debug evidence).  
- [ ] Final save commits identity/forwards/config consistently.  
- [ ] Android reaches real Listening with no peer.  
- [ ] User STOP while Listening ends Stopped, not Error.  
- [ ] Real Android-to-dockerized-answer PING/PONG/data marker PASS.  
- [ ] Force stop-like failure, recreate service in same process/test application, and prove start remains quarantined.  
- [ ] Verified explicit STOP clears shared quarantine and later start succeeds.  
- [ ] Live metered-to-unmetered transition obeys `resumeOnUnmetered`.  
- [ ] Candidate cleanup failure injection proves no authoritative import/forward config commit.  

## P2-002-F — CI

- [ ] Push exact signoff SHA.  
- [ ] Final GitHub Actions URL/run ID recorded: `________________`.  
- [ ] Every job green on first signoff run.  
- [ ] CI head SHA exactly matches local signoff SHA.  
- [ ] Android/Rust/test artifacts/logs retained and named in implementation report.  
- [ ] No skipped required check.  

## P2-002-G — Final inventories

```bash
cd android
rg -n 'runCatching\s*\{' app/src/main/java/com/phillipchin/webrtctunnel
rg -n '\.delete\(\)|mkdirs\(\)|setReadable\(|setWritable\(' app/src/main/java/com/phillipchin/webrtctunnel
rg -n 'snapshot\.bytes\s*\?:\s*ByteArray\(0\)|contents\.orEmpty\(\)' app/src/main/java/com/phillipchin/webrtctunnel
rg -n 'storeEncryptedIdentity\(|upsertWithReceipt\(|deleteWithReceipt\(' app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/Setup*
rg -n 'catch\s*\([^)]*Throwable' app/src/main/java/com/phillipchin/webrtctunnel
rg -n 'Thread\.sleep|assertFalse\s*\(\s*waitForCondition' app/src/test
cd ..
rg -n 'unix_ms\s*:\s*0|"unix_ms"\s*:\s*0' crates bins
```

- [ ] Every output is empty or each remaining hit is documented with exact safe scope in the implementation report.  
- [ ] Exactly one production `catch (Throwable)` remains, in the named cleanup-composition primitive, if that implementation shape was used.  
- [ ] No setup authoritative mutation hit remains.  
- [ ] No production zero diagnostic timestamp hit remains.  

## P2-002-H — Final acceptance

- [ ] Setup abandonment is byte-exact side-effect-free.  
- [ ] Setup is one transaction including forwards and current attempted stage.  
- [ ] Reset/import/forward failures restore exact prior state.  
- [ ] Identity rollback cannot silently fail or create empty replacement data.  
- [ ] Broker secret success proves owner-only permissions.  
- [ ] Fatal errors run cleanup and propagate unchanged.  
- [ ] Runtime quarantine survives service recreation and status refresh.  
- [ ] Only verified explicit STOP clears quarantine.  
- [ ] Diagnostic failure uses null timestamp and remains visible.  
- [ ] Preference writes are globally serialized.  
- [ ] Initialization is exactly once.  
- [ ] Tests are deterministic and prove exact production paths.  
- [ ] All local, CI, Docker, and emulator evidence belongs to one immutable SHA.  

---

# Completion checklist

Do not complete this summary independently; it mirrors the detailed acceptance sections above.

## P0

- [ ] setup identity and forwards are draft-only;
- [ ] global admission reports the actual owner and includes preferences;
- [ ] config/setup snapshots are exact and current attempted stages roll back;
- [ ] setup transaction includes exact forwards and config last;
- [ ] import/forward cleanup succeeds before commit and transactions restore exact state;
- [ ] reset repairs corrupt drafts and restores attempted stages exactly;
- [ ] identity rollback uses required bytes and checked deletion;
- [ ] broker secret permissions are enforced/verified and fatal cleanup is mandatory;
- [ ] runtime quarantine is application-scoped and explicit-STOP-only recovery;
- [ ] Rust/Kotlin diagnostics never invent zero or hide double failure.

## P1

- [ ] setup-local operations/load/error boundaries are truthful;
- [ ] application initialization is exactly once under concurrency;
- [ ] production `runCatching`, unchecked filesystem results, and raw secret logging are removed;
- [ ] missing/misleading production-path tests and CI timing failures are closed.

## P2

- [ ] permanent enforcement rejects regressions;
- [ ] immutable local/CI/Docker/emulator signoff is complete.
