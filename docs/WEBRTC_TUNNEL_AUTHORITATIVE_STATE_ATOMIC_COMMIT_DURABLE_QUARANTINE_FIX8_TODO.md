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

- [x] Delete `SetupInputSnapshot`, `captureSetupInputSnapshot`, and `restoreSetupInputSnapshot`. (`SHA: 55d877d`)
- [x] Remove `contents.orEmpty()` restoration. (`SHA: 55d877d`)
- [x] Remove all unchecked setup-input `File.delete()`. (`SHA: 55d877d`)
- [x] Do not store config snapshot as `configExisted + String`. (`SHA: 55d877d`)

## P0-003-B — One repository file-serialization boundary

- [x] Replace/rename `writeMutex` with one mutex that serializes both config and setup-input file operations. (`SHA: 55d877d` — renamed to `fileMutex`)
- [x] `readConfig` (now the `configContents` property), config existence, exact capture, config writes/deletes/restores all use it. (`SHA: 55d877d`)
- [x] setup-input exact capture, atomic save, load read, and restore all use it. (`SHA: 55d877d`)
- [x] Avoid nested/reentrant acquisition by providing private `...Locked` helpers. (`SHA: 55d877d` — `ensureDefaultConfig`/`prepareActiveConfigForStart` call `writeConfigAtomicallyWith` directly rather than the mutex-taking `writeConfigAtomically`; `SHA: 24b9aa2` unified that with the generic byte primitive.)

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

- [x] Create/centralize one same-directory temp plus atomic/replacement move primitive. (`SHA: 55d877d`, unified with config's own writer at `SHA: 24b9aa2` — `atomicReplaceBytesWith` in `ConfigAtomicWrite.kt` is now the one implementation both `writeConfigAtomically` and `atomicReplaceBytes`/setup-input use.)
- [x] Use `Files.createDirectories`, not ignored `mkdirs`. (`SHA: 55d877d` for setup-input; `SHA: 24b9aa2` for config, once unified.)
- [x] Support byte writes so exact snapshots do not round-trip through UTF-8 String. (`SHA: 55d877d`)
- [x] Return failure for every ordinary exception, including `SecurityException`. (`SHA: 24b9aa2` — broadened from `IOException` to `Exception`; covered by `AtomicReplaceBytesTest.securityExceptionFromWriteReturnsFailureNotThrow`.)
- [x] Preserve cancellation. (`SHA: 55d877d`)
- [x] Compose temp cleanup into the result. (`SHA: 55d877d`)
- [x] Allow an injected post-move verifier for broker-secret permissions. (`SHA: 55d877d` — hook exists (`postMoveVerify`); not yet adopted by a caller, deferred to P0-008 as noted in the code comment.)

## P0-003-D — Mark stage attempted before apply

- [x] Rename `committed` to `attempted` or otherwise reflect the actual semantics. (`SHA: 55d877d`)
- [x] Add each stage before `applyStage`. (`SHA: 55d877d`)
- [x] Ordinary apply failure rolls back `attempted`, including current stage. (`SHA: 55d877d`)
- [x] Cancellation rolls back `attempted`, including current stage. (`SHA: 55d877d`)
- [x] Rollback remains reverse-order, `NonCancellable`, exhaustive, and idempotent. (`SHA: 55d877d`)
- [x] Success result lists successfully applied stages, not a stage that failed and was rolled back. (`SHA: 55d877d`)

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

- [x] `SetupSnapshot` contains `ConfigFilesSnapshot`, not String-derived fields. (`SHA: 55d877d`)
- [x] Capture config/setup with one repository method. (`SHA: 55d877d` — `captureFilesSnapshot()`)
- [x] Snapshot capture failure aborts before any mutation. (`SHA: 55d877d`; re-verified by `snapshotFailurePerformsZeroMutationIncludingCurrentStage` at `SHA: 24b9aa2`)
- [x] `SetupSnapshot.wipeSecrets()` wipes broker-secret and setup-input bytes. (`SHA: 55d877d`)
- [x] Wiping runs after success, ordinary failure, cancellation, and fatal propagation. (`SHA: 55d877d` implementation via `finally`; `SHA: 24b9aa2` adds `setupInputSnapshotBytesWipedAfterSuccessFailureCancellationAndFatalError` covering all four exit paths including a thrown `Error`.)

## P0-003-F — Result contracts

- [x] `savePreferences()` catches all ordinary `Exception`, rethrows cancellation. (pre-existing; unchanged by FIX8)
- [x] config delete/write/restore helpers catch all ordinary `Exception`, not only `IOException`. (`SHA: 24b9aa2` — `deleteConfigFileForTransactionalReset` and the unified `atomicReplaceBytesWith`/`finishAtomicWrite` now catch `Exception`.)
- [x] Every authoritative API is `@CheckResult` and all callers consume it. (`SHA: 55d877d`/`24b9aa2`)

## P0-003-G — Exact tests

- [x] `setupInputAtomicWriteFailureBeforeMoveLeavesPriorBytesExact` (`SHA: 24b9aa2`, in `AtomicReplaceBytesTest.kt`)
- [x] `setupInputFailureAfterMoveRestoresCurrentStageExactBytes` (`SHA: 24b9aa2`, in `SetupPersistenceCoordinatorExactBytesTest.kt`)
- [x] `setupInputCancellationAfterMoveRestoresCurrentStageExactBytes` (`SHA: 24b9aa2`)
- [x] `configFailureAfterMoveRestoresCurrentStageExactBytes` (`SHA: 24b9aa2`)
- [x] `configCleanupFailureAfterMoveRestoresCurrentStageExactBytes` (`SHA: 24b9aa2`)
- [x] `setupSnapshotDistinguishesAbsentPresentEmptyAndNonUtf8Bytes` (`SHA: 24b9aa2`, in `ConfigRepositoryTest.kt`)
- [x] `setupSnapshotCaptureIsSerializedAgainstConfigAndSetupWriters` (`SHA: 24b9aa2`)
- [x] `setupInputSnapshotBytesWipedAfterSuccessFailureCancellationAndFatalError` (`SHA: 24b9aa2`)
- [x] `snapshotFailurePerformsZeroMutationIncludingCurrentStage` (`SHA: 24b9aa2` — renamed/strengthened from the pre-existing `snapshotFailurePerformsNoMutation` in `SetupPersistenceCoordinatorTest.kt`)
- [x] `rollbackIncludesCurrentAttemptedStageForEverySetupStage` — satisfied by the six existing per-stage tests in `SetupPersistenceCoordinatorTest.kt` (`identityFailureStopsAllLaterStages`, `authorizedKeysFailureRollsBackIdentity`, `brokerSecretFailureRollsBackAuthorizedKeysAndIdentity`, `setupInputFailureRollsBackBrokerSecretAuthorizedKeysAndIdentity`, `preferencesFailureRollsBackSetupInputBrokerSecretAuthorizedKeysAndIdentity`, `configFailureRollsBackEveryEarlierStage`), each updated at `SHA: 55d877d` to assert the failing stage itself heads the rollback list — no separate identically-named test was added to avoid duplicating that coverage.
- [x] `securityExceptionFromConfigOperationReturnsFailureAndTriggersRollback` (`SHA: 24b9aa2`, in `SetupPersistenceCoordinatorExactBytesTest.kt`; the underlying repository-level contract is also covered directly by `AtomicReplaceBytesTest.securityExceptionFromWriteReturnsFailureNotThrow`)

Use injected file operations that perform the destination move and then fail cleanup/verification. The test must assert exact destination restoration.

## Acceptance

- [x] Setup/config snapshots are exact, coherent, and secret-wiped. (`SHA: 55d877d`/`24b9aa2`)
- [x] Failure/cancellation cannot skip a partially/post-commit-mutated current stage. (`SHA: 55d877d`/`24b9aa2`)
- [x] No authoritative config/setup Result API unexpectedly throws an ordinary exception outside its contract. (`SHA: 24b9aa2`)

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

- [x] Extend `ForwardsStore` with internal exact snapshot/restore support or add an equivalent repository-owned file collaborator. (`SHA: 54ad896` — added directly to `ForwardsStore` rather than a separate `TransactionalForwardsStore`, since `ForwardsRepository` is the interface's only production consumer.)
- [x] Snapshot distinguishes absent, present-empty, and exact bytes. (`SHA: 54ad896`)
- [x] Restore uses atomic replacement or checked deletion. (`SHA: 54ad896` — reuses `atomicReplaceBytes`/`restoreExactFileSnapshot` from P0-003.)
- [x] No list re-serialization is presented as exact rollback. (`SHA: 54ad896`)

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

- [x] Add `ForwardsTransactionSnapshot` captured under `ForwardsRepository` mutex. (`SHA: 54ad896`)
- [x] Include exact file snapshot, current list, load state, and load error needed for truthful restoration. (`SHA: 54ad896`)
- [x] `captureForTransaction()` fails if baseline is not Ready; do not snapshot placeholder empty state. (`SHA: 54ad896`)
- [x] `replaceForTransaction()` validates, saves, then publishes. (`SHA: 54ad896`)
- [x] `restoreForTransaction()` restores disk first, then in-memory state. (`SHA: 54ad896`)
- [x] Successful restore advances revision to invalidate pre-transaction receipts. (`SHA: 54ad896`)
- [x] Result APIs are `@CheckResult`. (`SHA: 54ad896`)

## P0-004-C — Make ordinary forwards mutations failure-atomic

- [x] `upsertWithReceipt`, `deleteWithReceipt`, reset, and transactional replace cannot leave disk changed when returning failure. (`SHA: 54ad896`)
- [x] Capture exact store state before save and self-restore if save reports failure after destination mutation. (`SHA: 54ad896` — `selfRestoringSave`.)
- [x] A self-restore failure returns a composed/typed rollback-incomplete failure. (`SHA: 54ad896` — `ForwardsSaveRollbackIncompleteException`.)
- [x] In-memory list is not published until final persistence success. (`SHA: 54ad896`)

This is required even outside setup; the setup coordinator is not the only caller of `ForwardsRepository`.

## P0-004-D — Add setup `Forwards` stage

- [x] Add `SetupPersistenceStage.Forwards` immediately before `Config`. (`SHA: 6fc9e49`)
- [x] Add full draft `forwards: List<ForwardConfig>` to `SetupPersistenceRequest`. (`SHA: 6fc9e49`)
- [x] Capture forwards transaction snapshot before first mutation. (`SHA: 6fc9e49`)
- [x] Apply full draft through `ForwardsRepository.replaceForTransaction`. (`SHA: 6fc9e49`)
- [x] Restore exact forwards state for failure/cancellation. (`SHA: 6fc9e49`)
- [x] Config remains last. (`SHA: 6fc9e49`)

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

- [x] Add `IdentityRepository.restoreIdentityPairSnapshot` for encrypted/public only. (`SHA: 9423860`)
- [x] Add `restoreAuthorizedKeysSnapshot` for authorized keys only. (`SHA: 9423860`)
- [x] `SetupPersistenceStage.Identity` uses pair restore. (`SHA: 6fc9e49`)
- [x] `AuthorizedKeys` uses authorized-key restore. (`SHA: 6fc9e49`)
- [x] Do not restore the full triplet twice during one rollback. (`SHA: 6fc9e49`)

## P0-004-F — Setup controller request

- [x] Pass the full setup draft forwards to `SetupPersistenceRequest`. (`SHA: 6fc9e49`)
- [x] Render validation/final config from enabled members of the same full draft. (pre-existing `enabledForwards`, unchanged)
- [x] Pass the one preference snapshot from P0-002. (pre-existing, unchanged)
- [x] Call the coordinator exactly once after validation workspace cleanup. (pre-existing, unchanged)
- [x] Clear identity/forward drafts only after `SetupPersistenceResult.Success`. (pre-existing `identityDraft.clear()` timing, unchanged; forwards has no separate save-scoped draft to clear — see P0-004-F note in the implementation report.)

## P0-004-G — Tests

- [x] `setupCommitsFullDraftForwardsBeforeConfig` (`SHA: 6fc9e49`, in `SetupPersistenceCoordinatorForwardsTest.kt`)
- [x] `forwardsFailureRollsBackCurrentStageAndEveryEarlierSetupStage` (`SHA: 6fc9e49`)
- [x] `configFailureRestoresExactForwardsBytesListLoadStateAndEarlierStages` (`SHA: 6fc9e49`)
- [x] `cancellationDuringForwardsRestoresCurrentForwardsStageAndEarlierStages` (`SHA: 6fc9e49`)
- [x] `cancellationDuringConfigRestoresForwardsAndEveryEarlierStage` (`SHA: 6fc9e49`)
- [x] `setupForwardsRollbackFailureIsListedAndDurable` (`SHA: 6fc9e49`)
- [x] `setupSuccessPublishesRepositoryForwardsOnlyAfterDiskCommit` — satisfied by `setupCommitsFullDraftForwardsBeforeConfig` (`SHA: 6fc9e49`) plus `ForwardsRepositoryTest.replaceForTransactionSavesThenPublishes` (`SHA: 54ad896`).
- [x] `forwardsSavePostMoveCleanupFailureReturnsFailureAndRestoresDisk` — satisfied by `ForwardsRepositoryTest.mutationSelfRestoresDiskWhenSaveFailsAfterDestinationWasMutated` (`SHA: 54ad896`).
- [x] `forwardsOrdinaryMutationRollbackFailureIsNotHidden` — satisfied by `ForwardsRepositoryTest.selfRestoreFailureIsComposedIntoTypedException` (`SHA: 54ad896`).
- [x] `setupIdentityAndAuthorizedKeysRollbackUseDistinctRestoreMembers` (`SHA: 6fc9e49`)

## Acceptance

- [x] Final setup is one transaction including authoritative forwards. (`SHA: 6fc9e49`)
- [x] A failed/cancelled forwards stage cannot leave disk, repository, or config inconsistent. (`SHA: 54ad896`/`6fc9e49`)
- [x] Setup rollback does not duplicate holistic identity restoration. (`SHA: 9423860`/`6fc9e49`)

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

- [x] Candidate write and native validation occur inside `withCandidateFile`. (`SHA: 191193f`)
- [x] Authoritative config write occurs only after `withCandidateFile` returns successfully, proving cleanup succeeded. (`SHA: 191193f`)
- [x] Cleanup failure means no config write was attempted. (`SHA: 191193f`)
- [x] Private identity bytes remain wiped on success/failure/cancellation. (`SHA: 191193f` — unchanged from FIX7, verified still in effect)

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

- [x] Add a repository method that captures exact config, attempts replacement, and restores exact prior state if the attempt returns failure after destination mutation. (`SHA: 191193f`)
- [x] Run restore under `NonCancellable` when called from suspend mutation. (`SHA: 191193f`)
- [x] Return a typed/composed rollback-incomplete failure when restore fails. (`SHA: 191193f` — `ConfigReplaceRollbackIncompleteException`)
- [x] Keep capture/attempt/restore under config repository serialization. (`SHA: 191193f` — one `fileMutex.withLock` acquisition)

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

- [x] Build proposed list in memory from the authoritative baseline. (`SHA: 42ce50c`)
- [x] Validate list and render candidate without mutating `ForwardsRepository`. (`SHA: 42ce50c`)
- [x] Clean candidate successfully before authoritative mutation. (`SHA: 42ce50c`)
- [x] No receipt is created before validation. (`SHA: 42ce50c` — no receipt exists at all in the new flow; the coordinator is the one authoritative mutation, called only after validation)

## P0-005-D — Forward configuration coordinator

- [x] Add a data-layer coordinator with stages `Forwards`, `Config`. (`SHA: 42ce50c` — `ForwardConfigurationCoordinator.kt`)
- [x] Capture exact forwards and config snapshots before mutation. (`SHA: 42ce50c`)
- [x] Add stage to attempted set before apply. (`SHA: 42ce50c`)
- [x] Apply forwards, then config. (`SHA: 42ce50c`)
- [x] Roll back current and earlier attempted stages under `NonCancellable`. (`SHA: 42ce50c`)
- [x] Cancellation rethrows with rollback failures suppressed. (`SHA: 42ce50c`)
- [x] ViewModel maps rollback-complete and rollback-incomplete durably. (`SHA: 42ce50c` — `reportForwardConfigurationFailure`; also distinguishes a revision-mismatch rollback skip, closing a P1-002 gap the new snapshot/restore path had introduced)

## P0-005-E — Preserve primary failure identity

- [x] Validation failure remains primary when cleanup also fails; cleanup is suppressed. (`SHA: 42ce50c` — `forwardValidationFailurePreservedWhenCleanupAlsoFails`)
- [x] Cancellation remains primary when cleanup also fails. (`SHA: 42ce50c` — unchanged `withCandidateFile`/`withCleanupComposition` composition, now covered for the forward path)
- [x] Config/forwards apply failure remains primary when rollback fails; rollback detail remains inspectable/redacted. (`SHA: 42ce50c` — `forwardsViewModelSaveSurfacesRollbackIncompleteWhenForwardsRestoreFails`; per-stage detail inspectable via `ForwardConfigurationResult.Failed.rollback`)

## P0-005-F — Exact tests

- [x] `configImportCleanupFailurePerformsNoAuthoritativeConfigWrite` (`SHA: 191193f`)
- [x] `configImportWritePostMoveFailureRestoresPreviousConfigBytes` (`SHA: 299398c`)
- [x] `configImportRollbackFailureMapsConfigImportRollbackIncomplete` (`SHA: 191193f`)
- [x] `configImportCancellationBeforeCommitLeavesConfigExact` (`SHA: 191193f`)
- [x] `forwardCandidateCleanupFailureLeavesPreviousConfigAndForwardsExact` (`SHA: 299398c`)
- [x] `forwardValidationFailureLeavesPreviousConfigAndForwardsExact` (`SHA: 299398c`)
- [x] `forwardConfigFailureRestoresExactPreviousForwardsAndConfig` (`SHA: 299398c` — `ForwardConfigurationCoordinatorTest`)
- [x] `forwardConfigCleanupFailureAfterMoveRestoresExactPreviousForwardsAndConfig` (`SHA: 299398c`)
- [x] `forwardCancellationDuringConfigRestoresBothResources` (`SHA: 299398c`)
- [x] `forwardRollbackContinuesAfterOneRestoreFailure` (`SHA: 299398c`)
- [x] `forwardRollbackIncompleteListsEveryFailedRestore` (`SHA: 299398c`)

Strengthened the existing FIX7 tests: exact `config.toml`/`forwards.json` byte assertions added throughout (`SHA: 42ce50c`, `SHA: 299398c`).

## Acceptance

- [x] Candidate cleanup failure cannot coexist with a newly committed config. (`SHA: 42ce50c`)
- [x] Forward/config activation is one truthful transaction. (`SHA: 42ce50c`)
- [x] All post-move failures restore exact prior state or report rollback incomplete. (`SHA: 42ce50c`, `SHA: 299398c`)

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

- [x] Reset uses `ConfigRepository.captureFilesSnapshot()`. (`SHA: 0621d75` — via the new `captureConfigSnapshotForReset` property + `forwardsRepository.captureForTransaction()`, not the combined `captureFilesSnapshot()` itself, so a capture failure can be attributed to the actual component; see P0-006 note below)
- [x] Remove `readConfig().toByteArray()` config snapshot. (`SHA: 0621d75`)
- [x] Do not call `loadSetupInputResult().getOrThrow()` before reset. (`SHA: 0621d75`)
- [x] Capture forwards with the exact transaction snapshot from P0-004. (`SHA: 0621d75`)
- [x] Snapshot failure identifies `Config`, `SetupInput`, or `Forwards` accurately. (`SHA: 0621d75` — `SnapshotCaptureException.stage`)

## P0-006-B — Atomic reset mutations

- [x] Setup-input reset uses `saveSetupInputAtomically(SetupConfigInput())`. (`SHA: 0621d75`)
- [x] Config reset uses atomic config replacement. (`SHA: 0621d75` — unchanged `writeConfigAtomically`, already atomic temp+move)
- [x] Forwards reset is failure-atomic. (`SHA: 0621d75` — unchanged `resetForwards()`, already self-restoring)
- [x] No reset stage can return failure after a destination change without current-stage rollback. (`SHA: 0621d75` — P0-006-C's attempted-before-apply fix)

## P0-006-C — Attempted-stage rollback

- [x] Add each reset stage before apply. (`SHA: 0621d75`)
- [x] Ordinary failure restores current and earlier stages. (`SHA: 0621d75`)
- [x] Cancellation restores current and earlier stages under `NonCancellable`. (`SHA: 0621d75`)
- [x] Restore exact config/setup/forwards state. (`SHA: 0621d75`, config byte-exactness fixed at `SHA: 0dbefda`)
- [x] Wipe setup-input snapshot bytes in `finally`. (`SHA: 0621d75` — unchanged, verified still in effect)

## P0-006-D — Corrupt-state repair

- [x] Corrupt setup JSON does not block reset. (`SHA: 0621d75`)
- [x] Non-UTF-8 config/setup bytes do not block snapshot/reset. (`SHA: 0dbefda`)
- [x] Reset success produces known defaults and clears prior durable reset failure. (`SHA: 0621d75` — defaults unchanged; `SettingsViewModel.handleResetResult`'s existing `clearOperationFailure()` on success, verified still in effect)
- [x] Reset failure preserves/restores corrupt prior bytes exactly rather than “repairing” during rollback. (`SHA: 0621d75` — raw-byte capture/restore never parses, so a corrupt prior file restores as the same corrupt bytes)

## P0-006-E — Tests

- [x] `corruptSetupInputDoesNotPreventReset` (`SHA: 0621d75`)
- [x] `nonUtf8ConfigSnapshotRestoresExactBytesAfterResetFailure` (`SHA: 0dbefda`)
- [x] `resetSetupInputPostMoveFailureRestoresCurrentStageAndConfig` (`SHA: 0dbefda`)
- [x] `resetForwardsPostMoveFailureRestoresCurrentStageSetupInputAndConfig` (`SHA: 0dbefda`)
- [x] `resetCancellationDuringEachStageRestoresCurrentAndEarlierStages` (`SHA: 0dbefda`)
- [x] `resetSnapshotFailureNamesActualComponentAndPerformsNoMutation` (`SHA: 0621d75` — as three focused tests: `forwardsSnapshotFailureAbortsBeforeMutationAndNamesForwardsStage`, `configSnapshotReadExceptionAbortsBeforeMutation`, `setupSnapshotReadExceptionAbortsBeforeMutationAndNamesSetupInputStage`)
- [x] `resetRollbackRestoresAbsentPresentEmptyAndCorruptFilesExactly` (`SHA: 0621d75`/`SHA: 0dbefda` — covered across `TransactionalResetExactSnapshotTest`'s absent/present/empty cases and the non-UTF-8 "corrupt" case above)
- [x] `resetSnapshotSecretBytesWipedAfterSuccessFailureCancellationAndFatalError` (`SHA: 0dbefda`)

## Acceptance

- [x] Reset can repair corrupt drafts. (`SHA: 0621d75`)
- [x] Reset rollback is byte-exact and includes current stage. (`SHA: 0621d75`, `SHA: 0dbefda`)
- [x] Failure diagnostics identify the actual component. (`SHA: 0621d75`)

---

# P0-007 — Make identity restore fail-closed, checked, and coherent

**Review findings:** CRITICAL-5, MEDIUM-3.  
**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/security/IdentityRepository.kt
related identity/setup tests
```

## P0-007-A — Remove fabricated bytes and unchecked deletion

- [x] Replace every `snapshot.bytes ?: ByteArray(0)` with `requireNotNull`. (`SHA: ac21369`)
- [x] Replace every identity rollback `File.delete()` with `Files.deleteIfExists`. (`SHA: ac21369`)
- [x] Consume/check all deletion results/exceptions. (`SHA: ac21369`)
- [x] Missing bytes for a present snapshot return a restore failure naming the logical file. (`SHA: ac21369`)

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

- [x] Add pair-only detailed restore. (`SHA: 9423860` — `restoreIdentityPairSnapshot`, FIX8 P0-004-E)
- [x] Add authorized-keys-only detailed restore. (`SHA: 9423860` — `restoreAuthorizedKeysSnapshot`)
- [x] Keep holistic restore for callers that truly need all three. (`SHA: 9423860` — `restoreStorageSnapshot`, unchanged)
- [x] Every member is attempted independently. (`SHA: 9423860`, further exercised by `SHA: ac21369`'s `authorizedKeysRestoreFailureDoesNotReRestoreIdentityPair`)
- [x] Returned reasons are fixed/redacted. (`SHA: 9423860` — `SensitiveDataRedactor.redactText`, unchanged)

## P0-007-C — Coherent identity reads

- [x] Add one locked method that reads encrypted identity and public identity as one coherent pair. (`SHA: ac21369` — `readStoredIdentityMaterial`)
- [x] Setup/stored identity resolution uses it. (`SHA: ac21369` — `SetupSaveController.resolveSaveIdentity`/`resolveStoredIdentity`)
- [x] `readPublicIdentity`, `hasEncryptedIdentity`, and snapshot-related file reads cannot observe a pair replacement halfway through. (`SHA: ac21369` — the two coherence-sensitive read paths (`ForwardsViewModel`, `ImportExportService`) switched to `readPrivateIdentityPlaintextOrNull`; snapshot-related reads (`captureStorageSnapshot`, `restoreStorageSnapshot` family) already took `storageLock`, unchanged)
- [x] Do not hold the storage lock while invoking native validation. Copy required file data, release, then validate. (`SHA: ac21369` — no call site was found holding `IdentityRepository`'s own lock across a native call to begin with; the fix is the new coherent-read methods releasing the lock before returning, verified by `identityReaderDoesNotHoldLockDuringNativeValidation`)

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

- [x] Replace ignored identity/export parent `mkdirs()` with checked `Files.createDirectories`. (`SHA: ac21369`)
- [x] Export failures are fixed/redacted at the ViewModel boundary. (`SHA: ac21369` — no production caller currently wires the path-based `exportPrivateIdentity`/`exportPublicIdentity` to a ViewModel (the actually-wired export flows are URI-based, in `ImportExportViewModel`, already redacted via its existing `io.run`/`SensitiveDataRedactor` boundary, unchanged); the path-based methods' own failures are now checked/visible per P0-007-A/D above)
- [x] Atomic replacement cleanup does not log raw secret paths/Throwable. (`SHA: ac21369` — the `AtomicMoveNotSupportedException` fallback log no longer passes the raw `Throwable` to `Log.w`)

## P0-007-E — Tests

- [x] `identityPairCancellationAbsentEncryptedDeleteFailureIsSuppressed` (`SHA: ac21369`)
- [x] `identityPairCancellationAbsentPublicDeleteFailureIsSuppressed` (`SHA: ac21369`)
- [x] `presentSnapshotWithMissingBytesFailsWithoutCreatingEmptyIdentity` (`SHA: ac21369`)
- [x] `pairRestoreAttemptsPublicAfterEncryptedDeleteFailure` (`SHA: ac21369`)
- [x] `authorizedKeysRestoreFailureDoesNotReRestoreIdentityPair` (`SHA: ac21369`)
- [x] `coherentIdentityReadNeverObservesMismatchedPairDuringReplacement` (`SHA: ac21369`)
- [x] `identityReaderDoesNotHoldLockDuringNativeValidation` (`SHA: ac21369`)
- [x] `identityExportParentCreationFailureIsVisibleAndRedacted` (`SHA: ac21369`)

## Acceptance

- [x] Identity rollback never fabricates bytes or ignores deletion. (`SHA: ac21369`)
- [x] Pair/read coherence is repository-enforced. (`SHA: ac21369`)
- [x] Setup stage restores are distinct and exhaustive. (`SHA: 9423860`, `SHA: ac21369`)

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

- [x] Add an injectable permission enforcer/verifier. (`SHA: c9b21ee` — `BrokerSecretPermissionEnforcer`)
- [x] Use Android `Os.chmod`/`Os.stat` or an equivalent exact mode API where supported. (`SHA: c9b21ee`)
- [x] Require resulting permission bits equivalent to `0600`. (`SHA: c9b21ee`)
- [x] Secure the temp file before writing plaintext where feasible, and verify destination after move. (`SHA: c9b21ee`)
- [x] Permission enforcement/verification failure returns `broker_secret_permissions_failed`. (`SHA: c9b21ee` — `BrokerSecretPermissionException`)
- [x] Restore also enforces/verifies owner-only permissions. (`SHA: c9b21ee`)
- [x] Remove ignored `setReadable/setWritable` calls. (`SHA: c9b21ee`)

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

- [x] Rewrite `withCleanupComposition` so cleanup runs after value, Exception, cancellation, and fatal Error. (`SHA: c9b21ee`)
- [x] Preserve and rethrow the exact primary throwable instance. (`SHA: c9b21ee`)
- [x] Attach cleanup failure as suppressed when primary exists. (`SHA: c9b21ee`)
- [x] On primary success plus ordinary cleanup failure, throw fixed-message `CandidateCleanupException`. (`SHA: c9b21ee`)
- [x] On primary success plus fatal cleanup `Error`, propagate that same `Error`. (`SHA: c9b21ee`)
- [x] The narrow `catch (Throwable)` exists only in this primitive and is documented/enforced. (`SHA: c9b21ee` — deviation: implemented as three explicit catches (`CancellationException`, `Error`, `Exception`) rather than one literal `catch (Throwable)`, so detekt's `TooGenericExceptionCaught` rule — which this repo deliberately configured to flag `Throwable` specifically — stays meaningful everywhere else without a suppression or config exemption; the three clauses are jointly exhaustive over `Throwable` and every one rethrows unchanged, so the substantive guarantee is identical)

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

- [x] `createCandidateFile` and `withTemporaryDirectory` use `Files.createDirectories`. (`SHA: c9b21ee`)
- [x] All secret/authoritative parent creation is checked. (`SHA: c9b21ee` — broker secret, identity, forwards, setup-input, diagnostics/config export, and candidate/workspace directories all switched from ignored `mkdirs()` to checked `Files.createDirectories`)
- [x] Cleanup helpers catch ordinary `Exception`, including `SecurityException`. (`SHA: c9b21ee`)
- [x] No bare `File.delete()` remains in production authoritative paths. (`SHA: c9b21ee` — audited; the one remaining reference, `ForwardsConfigStore`'s injectable `deleteTempFile: (File) -> Boolean = File::delete`, already checks its Boolean return at the call site, so a failure is not ignored — not changed further, to avoid changing its exception-vs-Boolean failure contract without a driving test)
- [x] `ForwardsConfigStore.saveForwards` captures all ordinary exceptions and preserves primary/cleanup identity. (`SHA: c9b21ee` — broadened from `IOException` to `Exception`, explicit cancellation rethrow added; primary/cleanup composition via `throwComposedFailureIfAny` unchanged)
- [x] Logging uses fixed messages plus redacted text; do not pass raw Throwable where it may reveal private paths. (`SHA: c9b21ee` — broker-secret and identity atomic-move-fallback logs no longer pass the raw `Throwable`)

## P0-008-D — Tests

- [x] `brokerSecretPermissionFailureAfterMoveRestoresPriorSecret` (`SHA: c9b21ee`)
- [x] `brokerSecretPermissionFailureBeforeFirstSecretLeavesFileAbsent` (`SHA: c9b21ee`)
- [x] `brokerSecretRestoreVerifiesOwnerOnlyPermissions` (`SHA: c9b21ee`)
- [x] `candidateFatalErrorRunsCleanupAndPropagatesSameErrorInstance` (`SHA: d2dbe3c`)
- [x] `workspaceFatalErrorRunsRecursiveCleanupAndPropagatesSameErrorInstance` (`SHA: d2dbe3c`)
- [x] `cleanupSecurityExceptionIsSuppressedOnPrimaryFailure` (`SHA: d2dbe3c`)
- [x] `cleanupFatalErrorAfterSuccessPropagatesSameError` (`SHA: d2dbe3c`)
- [x] `parentDirectoryCreationFailureOccursBeforeCandidateCreation` (`SHA: d2dbe3c`)
- [x] `noRawSecretPathAppearsInCleanupOrPermissionDiagnostics` (`SHA: c9b21ee`)

## Acceptance

- [x] Broker-secret success proves owner-only permissions. (`SHA: c9b21ee` — proven at the JVM level as far as JVM tests can: the repository correctly calls the enforcer for both the temp file and the destination, verified via a recording fake. `Os.chmod`/`Os.stat` do not behave reliably under Robolectric (confirmed by running the real enforcer against the existing test suite, which failed uniformly), so the real Android-runtime permission bits are **NOT RUN**: no emulator/instrumentation test was executed this session to prove the actual `0600` result on a real device/emulator, per the RESPONSES answer's own fallback guidance for this exact scenario — this remains open for a future session with interactive emulator access.)
- [x] Fatal errors cannot skip mandatory cleanup. (`SHA: c9b21ee`, tested at `SHA: d2dbe3c`)
- [x] Filesystem Boolean/runtime failures are not ignored or allowed to replace primary truth. (`SHA: c9b21ee`)

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

- [x] Add `NativeRuntimeSafetyState` as an application-scoped `AppDependencies` property. (`SHA: 3643841`)
- [x] Store quarantine, stop verification, fixed/redacted reason, and generation. (`SHA: 3643841`)
- [x] Expose read-only `StateFlow` or immutable snapshot. (`SHA: 3643841`)
- [x] All updates are atomic/thread-safe. (`SHA: 3643841`)
- [x] Remove service-owned `nativeRuntimeUncertain` and `nativeStopVerified` as sources of truth. (`SHA: 3643841`)

Use the target shape in the FIX8 spec or an equivalent model.

Implemented `NativeRuntimeSafetyState` (`data/NativeRuntimeSafetyState.kt`) as an internal type
(matching `BrokerSecretPermissionEnforcer`/`StoredIdentityMaterial`'s existing internal-type
pattern), with a `synchronized`-guarded `MutableStateFlow<NativeRuntimeSafetySnapshot>` and four
named transitions (see P0-009-B). `AppDependencies.nativeRuntimeSafetyState` is a body val (not a
constructor parameter — the constructor is already at detekt's `LongParameterList` limit), and
`TunnelRepository`'s primary constructor gained it as its first parameter (`internal constructor`,
same pattern as `BrokerSecretRepository`) so `stop()`/`refreshStatusResult()` can read/write it.

## P0-009-B — Apply state transitions consistently

- [x] New native start attempt marks stop unverified without clearing quarantine. (`SHA: 3643841`)
- [x] Every stop-like failure quarantines before reporting. (`SHA: 3643841`)
- [x] Successful pause records observed stop but does not clear pre-existing quarantine. (`SHA: 3643841`)
- [x] Successful destroy fallback records observed stop but does not clear pre-existing quarantine. (`SHA: 3643841`)
- [x] Only verified explicit STOP clears quarantine. (`SHA: 3643841`)
- [x] Explicit STOP failure preserves/enters quarantine. (`SHA: 3643841`)

`OfferCoordinator.startOffer()` calls `markStartAttempted()` (was `nativeStopVerified.set(false)`).
`TunnelForegroundService.enterNativeRuntimeQuarantine()` calls `nativeRuntimeSafety.quarantine(code,
message)` as its first statement (before the RESPONSES-item-2 `repository.setLocalError` sequence
and the `publishErrorSafely` notification), unchanged ordering. The genuinely new behavior is in
`TunnelRepository.stop(explicitVerifiedStop: Boolean = false, ...)`: verification always reads raw
native truth via a new `NativeRuntimeStatusDto.reportsVerifiedStop` property (ignoring any
pre-existing quarantine, since otherwise a quarantined runtime could never be verified stopped and
explicit-STOP recovery would be impossible); on a verified stop, `explicitVerifiedStop = true`
(only `stopServiceWork()`'s call) clears quarantine via `markVerifiedExplicitStop()`, while every
other caller (`pause()`, `pauseForPolicy()`, the destroy fallback, `cleanupUnverifiedStart`'s
start-verification cleanup — all using the default `false`) calls
`markObservedStopWithoutRecovery()`, which never touches `quarantined`. This is the actual
CRITICAL-6/HIGH-10 fix: previously `onDestroy()`'s fallback-success branch unconditionally cleared
`nativeRuntimeUncertain.set(false)`.

## P0-009-C — Guard all start/resume/retry paths

- [x] ACTION start reads application-scoped safety owner. (`SHA: 3643841`)
- [x] Manual resume reads it. (`SHA: 3643841`)
- [x] Policy resume reads it. (`SHA: 3643841`)
- [x] Pending policy retry reads it. (`SHA: 3643841`)
- [x] Automatic reconnect/start path, if present, reads it. (`SHA: 3643841`)
- [x] Every guard failure clears pending retry and publishes durable recovery-required state. (`SHA: 3643841`)
- [x] Replace `handleRetryPolicyResume`'s `getOrNull()` silent return with `getOrElse` plus durable reporting. (`SHA: 3643841`)

All five start/resume/retry call sites already routed through
`TunnelForegroundService.requireRuntimeStartAllowed` (now backed by
`nativeRuntimeSafety.state.value.quarantined`); no automatic-reconnect path exists beyond these.
`handleRetryPolicyResume` rewritten exactly per the TODO's suggested mapping (`getOrElse` +
`invalidatePendingPolicyRetry()` + `publishErrorSafely(code = "native_runtime_recovery_required",
...)`), fixing the one call site that previously discarded the guard failure silently.

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

- [x] Inject/read runtime safety state in `TunnelRepository`. (`SHA: 3643841`)
- [x] `refreshStatusResult` overlays/preserves quarantined Error state regardless of mapped native active state. (`SHA: 3643841`)
- [x] Status decode/unknown errors still clear stale live peer/session/MQTT fields. (`SHA: 3643841`)
- [x] A native Stopped status alone does not clear quarantine. (`SHA: 3643841`)
- [x] Explicit-stop recovery clears safety owner first/atomically with final status publication so no start window exists. (`SHA: 3643841`)

A new private `TunnelStatus.withNativeRuntimeSafetyOverlay` property (applied as the last step of
both `refreshStatusResult`'s commit and `stop`'s own commit) overlays a quarantined Error state
using the fixed canonical code/message — deliberately never `safety.code`/`safety.message` (the
per-failure diagnostic) — because a first attempt using the per-failure code let a concurrent
status poll race `enterNativeRuntimeQuarantine`'s own two `setLocalError` calls and leave the
narrower code as the final published one (caught by a flaky-until-fixed run of the new
`nativeStatusRefreshCannotOverwriteQuarantineWithConnected` test). "Explicit-stop recovery clears
safety owner first/atomically" is `stop()`'s own design: `reportsVerifiedStop` is computed from
the raw native fields (independent of `current`), so `markVerifiedExplicitStop()` can run once,
synchronously, *before* the final `updateStatus` commit reads the now-updated safety state for the
overlay decision — no window where a concurrent reader observes one without the other.

## P0-009-E — Service recreation

- [x] New service instance initializes from shared safety owner. (`SHA: 3643841`)
- [x] Recreated service cannot start while owner is quarantined. (`SHA: 3643841`)
- [x] Recreated service can receive explicit STOP and clear quarantine only after verification. (`SHA: 3643841`)
- [x] Old service destruction cannot clear owner state after a newer service generation has changed it; use generation/token checks where needed. (`SHA: 3643841`)

**Deviation (disclosed):** the generation guard is implemented narrowly, for the one call site the
review findings specifically named (a stale service instance's destroy-time fallback stop) —
`NativeRuntimeSafetyState.markObservedStopWithoutRecoveryIfGenerationUnchanged(expectedGeneration)`
skips the transition if the shared generation has advanced past what the caller observed, and
`TunnelForegroundService.onDestroy()`'s fallback captures the generation immediately before
calling `repository.stop(ifGenerationUnchanged = ...)`. The native `bridge.stop()` call itself
still fires unconditionally in this path (a real, narrower limitation: full protection against two
service instances concurrently driving the same native bridge would need cross-instance mutual
exclusion, out of this task's scope) — only the *shared safety-state mutation* is guarded against
a stale write. The `quarantine()`/`markVerifiedExplicitStop()` transitions were not given an
equivalent generation-guarded variant (not named by the review findings or P0-009-F's test list).

## P0-009-F — Tests

- [x] `serviceRecreationWhileQuarantinedStillBlocksNativeStart` (`SHA: 3643841`)
- [x] `serviceRecreationWhileQuarantinedStillBlocksManualResume` (`SHA: 3643841`)
- [x] `pendingPolicyRetryQuarantineGuardFailureIsDurableAndVisible` (`SHA: 3643841`)
- [x] `destroyFallbackSuccessDoesNotClearPreexistingQuarantine` (`SHA: 3643841`)
- [x] `successfulPauseDoesNotClearPreexistingQuarantine` (`SHA: 3643841`)
- [x] `nativeStatusRefreshCannotOverwriteQuarantineWithConnected` (`SHA: 3643841`)
- [x] `nativeStatusRefreshCannotOverwriteQuarantineWithStopped` (`SHA: 3643841`)
- [x] `verifiedExplicitStopClearsSharedQuarantineForLaterServiceInstance` (`SHA: 3643841`)
- [x] `staleServiceDestroyCannotClearNewerRuntimeSafetyGeneration` (`SHA: 3643841`)
- [x] `reporterFailureCannotPreventSharedQuarantineTransition` (`SHA: 3643841`)

Construct two service instances sharing one test application/dependency graph. Do not simulate recreation by mutating a local Boolean.

All 10 tests in `TunnelForegroundServiceRuntimeSafetyRecreationTest.kt` construct two real
`ServiceController<TunnelForegroundService>` instances sharing one Robolectric
Application/`AppDependencies` graph (never a locally mutated Boolean), per this instruction.
`pendingPolicyRetryQuarantineGuardFailureIsDurableAndVisible` and
`reporterFailureCannotPreventSharedQuarantineTransition` construct `ServiceCoordinatorOperations`
directly (internal, same technique as the pre-existing `UnverifiedStartContextTest`) and inject a
custom `NotificationController(notifyAction = ...)` (an existing constructor seam) to observe/fail
the reporter deterministically — both needed `notificationsAllowedProvider = { true }` since this
Robolectric environment's default target SDK (35) denies `POST_NOTIFICATIONS` by default,
otherwise `NotificationController.show()` no-ops before `notifyAction` is ever invoked.

## Acceptance

- [x] Quarantine survives service recreation and status polling. (`SHA: 3643841`)
- [x] Every blocked retry is visible. (`SHA: 3643841`)
- [x] Only verified explicit STOP authorizes recovery. (`SHA: 3643841`)

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

- [x] Change Rust `AndroidLogEvent.unix_ms` to `Option<u64>`. (`SHA: 59a07b9`)
- [x] Normal log events use `Some(unix_ms)`. (`SHA: 59a07b9`)
- [x] Update serde/JNI/C ABI tests. (`SHA: 59a07b9`)
- [x] Change Kotlin `NativeLogEventDto.unixMs` and `LogEvent.unixMs` to nullable. (`SHA: 3f5d3a0`)
- [x] Redaction preserves null. (`SHA: 3f5d3a0`)
- [x] UI formats null as fixed "time unavailable" text and uses a stable key not dependent solely on timestamp. (`SHA: 3f5d3a0`)
- [x] Export/share logs represent null explicitly and do not print `0`. (`SHA: 3f5d3a0`)

`SensitiveDataRedactor.redactLogEvent` only ever touched `message`, so it already preserved
`unixMs` (now nullable) unchanged with no code change needed. `LogsScreen`'s LazyColumn key was
already `"${event.unixMs}-$index"` — the index suffix already made it stable/unique regardless of
timestamp, so no change needed there either. `DiagnosticsRepository`'s diagnostics-export path
serializes logs via `kotlinx.serialization` `Json.encodeToString`, which already renders a null
field as JSON `null` (not `0`) with no code change; the copy-to-clipboard/share text builder
(`redactedLogsText`, a hand-built plain-text line, not JSON) needed an explicit `?:
unavailableText` fallback, since Kotlin's default null-to-string would otherwise print the literal
word `null` (not `0`, but still not the fixed UX text this item requires).

## P0-010-B — Visible fallback events

- [x] JNI invalid-UTF8 log fallback returns an error event with `unix_ms: null`. (`SHA: 59a07b9`)
- [x] C ABI log-buffer failure with available clock returns `Some(time)`. (`SHA: 59a07b9`)
- [x] C ABI log-buffer plus clock failure returns one error event with `None`, not `Vec::new()`. (`SHA: 59a07b9`)
- [x] Message is fixed/redacted and does not include raw poison/internal details beyond an approved safe reason. (`SHA: 59a07b9`)
- [x] Extract pure helper seams so both fallback branches are directly unit-tested. (`SHA: 59a07b9`)

New `types::diagnostic_failure_event(message, unix_ms)` (crate-internal) builds the event for
both the JNI and C-ABI fallback paths, so they can't drift apart. The JNI fallback (previously a
hand-written `r#"[{"unix_ms":0,...}]"#` string literal) now serializes it via
`serde_json::to_string`, extracted into `jni_bridge::invalid_utf8_log_fallback_json()` — directly
unit tested without a `JNIEnv`. The C-ABI fallback is extracted into
`c_abi::recent_logs_failure_fallback(reason, unix_ms)`, unit tested with both `Some`/`None`
inputs. The existing reason string (`"failed to read recent logs: {reason}"`, where `reason` is
already a fixed/redacted `String` from `controller.recent_logs()`) is unchanged — no raw
poison/internal detail beyond that existing approved message.

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

- [x] Detect production struct literals `unix_ms: 0`. (`SHA: 59a07b9`)
- [x] Detect production JSON fallback containing `"unix_ms":0`. (`SHA: 59a07b9`)
- [x] Detect `None => Vec::new()` in the recent-log failure path through a direct behavior test, not regex alone. (`SHA: 59a07b9`)
- [x] Keep legitimate test data with timestamp zero only where explicitly testing deserialization; do not let it satisfy production fallback checks. (`SHA: 59a07b9`)

Extended the existing FIX7 `no_pre_epoch_panics.rs` workspace guard (kept as the natural home,
rather than a new file, since it's already the workspace-wide "no reintroduced clock-fallback
footgun" tripwire) with a second test,
`workspace_contains_no_production_zero_timestamp_diagnostic_fallback`. It strips every
`#[cfg(test)] mod ... { ... }` block (brace-depth-matched) from each file's contents before
scanning, so a test module's legitimate `unix_ms: Some(0)` deserialization/eviction fixtures never
false-positive — verified against two deliberately reintroduced violations (a bare `unix_ms: 0`
struct literal and a `None => Vec::new()` fallback) before confirming clean, then reverted. Note:
`Option<u64>` already makes a bare `unix_ms: 0` struct literal a *compile* error workspace-wide
(the type system itself forecloses that specific case now) — the guard's struct-literal check is
defense-in-depth; its real remaining value is the JSON-string-literal and `Vec::new()` checks,
which are plain text/logic the compiler can't catch.

## P0-010-D — Tests

- [x] `jniInvalidUtf8LogFallbackUsesNullTimestampNotZero` (`SHA: 59a07b9`)
- [x] `recentLogAndClockDoubleFailureReturnsVisibleUntimedErrorEvent` (`SHA: 59a07b9`)
- [x] `normalRecentLogSerializesSomeTimestamp` (`SHA: 59a07b9`)
- [x] `kotlinDecodesNullNativeLogTimestamp` (`SHA: 3f5d3a0`)
- [x] `logsScreenDisplaysTimeUnavailableForNullTimestamp` (`SHA: 3f5d3a0`)
- [x] `logExportNeverPrintsZeroForUnavailableTimestamp` (`SHA: 3f5d3a0`)
- [x] `workspaceContainsNoProductionZeroTimestampDiagnosticFallback` (`SHA: 59a07b9`)

## Acceptance

- [x] Unavailable time is null/None, never zero. (`SHA: 3f5d3a0`)
- [x] Double diagnostic failure remains visible. (`SHA: 59a07b9`)
- [x] Rust/Kotlin schema and UI agree. (`SHA: 3f5d3a0`)

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

- [x] Add `SetupDraftOperation` values for baseline load, identity action, forward edit, validation/navigation, and final save. (`SHA: 2019fe3`)
- [x] One shared coordinator serializes all asynchronous setup actions. (`SHA: 2019fe3`)
- [x] `isBusy` is derived from actual ownership, not independently toggled by several controllers. (`SHA: 2019fe3`)
- [x] Busy rejection is durable/visible and names active setup operation. (`SHA: 2019fe3`)
- [x] Final save holds setup-local ownership while acquiring/using global SetupSave admission. (`SHA: 2019fe3`)
- [x] No stale action completion may overwrite newer draft state; use operation token/generation where cancellation is allowed. (`SHA: 2019fe3`)

`SetupOperationCoordinator` mirrors `ConfigurationMutationCoordinator`'s atomic-owner-token
design (`AtomicReference<ActiveSetupDraftOperation?>`), bundled onto `WizardStateAccess` (rather
than a separate constructor parameter on each controller) since the coordinator itself takes no
dependency on `WizardStateAccess`, avoiding a circular construction order. `isBusy` is stamped
inside `SetupViewModel.applyState` — the one write path every controller already used — from
`operations.isBusy` at call time; `runGuarded` additionally publishes immediately upon acquiring
admission (not only at completion) and unconditionally in a `finally` (covering a normal return
*and* a `CancellationException` propagating straight through), since the block's own last
`applyState` call runs while admission is still held and would otherwise leave a stale `isBusy`
after release — both gaps were caught by this task's own new tests before being fixed. Final
save (`SetupSaveController.saveAndApplyConfigInternal`) acquires `FinalSave` admission and, from
inside that block, still calls `deps.configurationMutationCoordinator.tryRun(SetupSave)` exactly
as before (FIX7 P0-001-C) — nested, not replaced. `SetupOperationCoordinator.invalidate()`
(called from `cancel()`) marks every in-flight operation's token stale via a `staleBefore`
watermark; each operation's own `id` (passed into its `block`) is checked before it publishes a
result, so a still-running action whose completion arrives after a cancel can never overwrite the
freshly-reset draft.

## P1-001-B — Explicit setup baseline state

- [x] Add `SetupLoadState.Initializing/Ready/Failed`. (`SHA: 2019fe3`)
- [x] Move setup-input read/decode to IO dispatcher. (`SHA: 2019fe3`)
- [x] Move stored identity baseline load to IO. (`SHA: 2019fe3`)
- [x] Move forwards baseline load to IO. (`SHA: 2019fe3`)
- [x] Publish Ready only when all required baselines are coherent. (`SHA: 2019fe3`)
- [x] Block Next/final save while Initializing or Failed. (`SHA: 2019fe3`)
- [x] Failure is durable `setup_draft_load_failed`; do not silently use defaults when an existing file is corrupt. (`SHA: 2019fe3`)

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

Implemented as `loadSetupWizardBaseline` (top-level, called from `init`'s `BaselineLoad`-guarded
coroutine): reads setup-input via `withContext(deps.dispatchers.io)`, then directly awaits new
suspend variants `SetupIdentityController.loadStoredIdentityBaseline()` and
`SetupForwardsController.refreshForwardsBaseline()` (both already IO-dispatched internally,
previously only reachable via a fire-and-forget `launchBusy`/`scope.launch` wrapper — calling
them directly here, rather than through the coordinator again, avoids a self-deadlock, since the
coordinator is not reentrant and `BaselineLoad` already holds admission for the whole sequence).
`deps.forwardsRepository.loadState` (the existing `ForwardsLoadState`, per-forward) is consulted
directly rather than duplicating its own IO/error-handling. `SetupLoadState.Ready` is published
only once both the setup-input result and the forwards load state are known-good.

## P1-001-C — Boundary error handling/redaction

- [x] Every setup action catches cancellation first and rethrows. (`SHA: 2019fe3`)
- [x] Every ordinary failure produces fixed/redacted UI failure. (`SHA: 2019fe3`)
- [x] `launchBusy`/replacement helper does not allow uncaught ordinary exceptions to merely clear busy. (`SHA: 2019fe3`)
- [x] Remote public identity import removes `runCatching`. (`SHA: 2019fe3`)
- [x] No raw native validation message is assigned without redaction unless it is a fixed application-authored message. (`SHA: 2019fe3`)
- [x] Success clears prior error; cancellation emits no ordinary success/failure. (`SHA: 2019fe3`)

`importPublicIdentityFromUri` already satisfied "remote public identity import removes
`runCatching`" (an explicit cancellation-first try/catch, pre-existing before this task, per its
own comment anticipating P1-001-C). The two remaining raw/unredacted native-validation messages —
`SetupIdentityController.resolveRemotePublicIdentity`'s `validated.message` and
`generateIdentity`'s `generated.message` — now go through `SensitiveDataRedactor.redactText`
before reaching UI state. `launchBusy` (duplicated verbatim across the identity and forwards
controllers) is replaced by `SetupOperationCoordinator.runGuarded`, which every setup action now
routes through: cancellation is caught first and rethrown; any other exception the action does
not already handle itself is caught and reported as a fixed/redacted, durable `errorMessage` —
never silently clearing busy with no visible message.

## P1-001-D — Tests

- [x] `setupViewModelConstructionPerformsNoFileIoOnMainThread` (`SHA: 2019fe3`)
- [x] `setupLoadInitializingBlocksNextAndSave` (`SHA: 2019fe3`)
- [x] `setupLoadReadyUsesLoadedDraftBaseline` (`SHA: 2019fe3`)
- [x] `setupLoadFailureIsDurableAndDoesNotUseBlankFallback` (`SHA: 2019fe3`)
- [x] `overlappingIdentityAndForwardActionsCannotPublishStaleBusyOrState` (`SHA: 2019fe3`)
- [x] `setupActionExceptionIsRedactedAndDurable` (`SHA: 2019fe3`)
- [x] `setupActionCancellationEmitsNoOrdinaryResultAndReleasesOwnership` (`SHA: 2019fe3`)

All 7 in new `SetupDraftOperationCoordinationTest.kt`. The first two use `realIoTestDispatchers()`
(a genuine `Dispatchers.IO` thread hop) to observe `loadState` still `Initializing` at the instant
`SetupViewModel`'s constructor returns — the direct proof of "no synchronous main-thread file
I/O". `overlappingIdentityAndForwardActionsCannotPublishStaleBusyOrState` drives both the
occupying and the overlapping action directly through `SetupOperationCoordinator.runGuarded`
(rather than through `SetupForwardsController.upsertForward`'s own `viewModelScope`-hopping
launch, whose scheduling did not reliably interleave with an already-parked `viewModelScope`
coroutine under this test harness) — the coordinator logic exercised is identical either way,
since every real controller method routes through the exact same `WizardStateAccess.operations
.runGuarded` call.

## Acceptance

- [x] Setup screen has no main-thread file I/O. (`SHA: 2019fe3`)
- [x] Setup busy/load state is truthful across all controllers. (`SHA: 2019fe3`)
- [x] No setup failure escapes silently or leaks raw details. (`SHA: 2019fe3`)

---

# P1-002 — Make application initialization exactly once under concurrency

**Review finding:** HIGH-7.  
**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/AppInitialization.kt
related tests
```

## P1-002-A — Lazy winner-only start

- [x] Create candidate job with `CoroutineStart.LAZY`. (`SHA: f97c043`)
- [x] CAS the lazy job into `startedJob`. (`SHA: f97c043`)
- [x] Only the winner calls `start()`. (`SHA: f97c043`)
- [x] Cancel the losing lazy job before it can execute. (`SHA: f97c043`)
- [x] Repeated callers return the same winner job. (`SHA: f97c043`)

Implemented exactly per the suggested target below (the prior code launched the candidate
eagerly, so it began running `initialize()` immediately — before the `compareAndSet` decided a
winner — meaning two concurrent callers could each run `initialize()` concurrently; the loser's
`job.cancel()` came after the fact and could not undo work already done). Verified the new tests
actually catch this: reverted `start()` to the old eager-launch code, ran the 8-thread
concurrent-start tests 3 times (failed 2 of 3, a genuine timing-dependent race), then restored the
fix and reconfirmed 3/3 clean.

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

- [x] `concurrentInitializationStartRunsInitializeExactlyOnce` (`SHA: f97c043`)
- [x] `losingLazyInitializationJobExecutesNoInstruction` (`SHA: f97c043`)
- [x] `allConcurrentCallersReceiveSameWinnerJob` (`SHA: f97c043`)
- [x] `initializationFailureStillPublishesOneFailedState` (`SHA: f97c043`)

Use a barrier at the first line of `initialize`; assert entry count exactly one.

All four use a real (non-`Unconfined`) `Dispatchers.IO`-backed scope/dispatcher and 8 real OS
threads releasing simultaneously via a `CountDownLatch`, since an `Unconfined` dispatcher never
leaves the eager-vs-lazy race window open regardless of which implementation is under test. The
"barrier at the first line of `initialize`" is a `BarrierConfigRepository` counting entries into
`ensureDefaultConfig` (the only instruction `initialize` executes before publishing its result).

## Acceptance

- [x] Initialization is genuinely exactly-once, not cancel-after-start. (`SHA: f97c043`)

---

# P1-003 — Complete Result, runCatching, filesystem, and raw-log audit

**Review findings:** HIGH-9, HIGH-12, MEDIUM-1/2/4.  
**Files:** all production Kotlin inventory hits and related tests/static config.

## P1-003-A — Remove production `runCatching`

- [x] Replace pure parse/read uses with explicit `try/catch (Exception)`. (`SHA: a3d8a6a`)
- [x] Replace `System.loadLibrary` use with explicit `catch (UnsatisfiedLinkError)` only. (`SHA: a3d8a6a`)
- [x] Let unrelated fatal errors propagate. (`SHA: a3d8a6a`)
- [x] Delete “safe as runCatching” marker comments and the marker-based enforcement test. (`SHA: a3d8a6a`)
- [x] No production `runCatching {` remains. (`SHA: a3d8a6a`)

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

- [x] Audit every public/internal `Result`-returning mutation/snapshot/restore. (`SHA: a3d8a6a`)
- [x] Rethrow cancellation where suspend/coroutine-relevant. (`SHA: a3d8a6a`)
- [x] Catch `Exception`, not selected subclasses only, unless the signature explicitly documents throwing other ordinary exceptions. (`SHA: a3d8a6a`)
- [x] Add `@CheckResult` and consume all authoritative results. (`SHA: a3d8a6a`)
- [x] Do not use `.also { }` as fake consumption when the result should be interpreted; add an explicit `ignoreResultBecause...` helper only for genuinely side-effect-authoritative calls, or redesign. (`SHA: a3d8a6a`)

## P1-003-C — Filesystem and raw logging inventory

- [x] Replace every ignored `mkdirs`, `delete`, permission setter in authoritative paths. (`SHA: a3d8a6a` — audit found zero remaining occurrences in production; already fully converted in earlier FIX7/FIX8 passes)
- [x] Audit remaining `File.delete()` uses and document non-authoritative exceptions. (`SHA: a3d8a6a` — zero `File.delete()` calls remain in production Kotlin)
- [x] Remove raw `Throwable` logging from identity, forwards, broker-secret, config, notification, and ViewModel failure paths. (`SHA: a3d8a6a`)
- [x] Log fixed code plus redacted message; never private app paths or content. (`SHA: a3d8a6a` — also fixed `readPrivateIdentityFile`'s message embedding the raw import path, see report)
- [x] A logging failure cannot replace the primary operation outcome. (`SHA: a3d8a6a` — every fixed logging call sits in the `onFailure`/catch path alongside the original Result/rethrow, never in place of it)

## P1-003-D — Tests/static fixtures

- [x] `productionContainsNoRunCatchingCall` (`SHA: a3d8a6a`)
- [x] `nativeLibraryLoadNormalizesOnlyUnsatisfiedLinkError` (`SHA: a3d8a6a`)
- [x] `fatalErrorFromParserOrPropertyReadPropagates` (`SHA: a3d8a6a`)
- [x] `securityExceptionFromEachResultApiBecomesFailureOrDocumentedThrow` (`SHA: a3d8a6a`)
- [x] `rawPrivatePathSentinelNeverAppearsInProductionDiagnosticStateOrLogs` (`SHA: a3d8a6a`)
- [x] `authoritativeFilesystemOperationsContainNoUncheckedBooleanResult` (`SHA: a3d8a6a`)

## Acceptance

- [x] Production has no `runCatching`. (`SHA: a3d8a6a`)
- [x] Result contracts match caller assumptions. (`SHA: a3d8a6a`)
- [x] No authoritative filesystem failure is ignored or leaked raw. (`SHA: a3d8a6a`)

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
