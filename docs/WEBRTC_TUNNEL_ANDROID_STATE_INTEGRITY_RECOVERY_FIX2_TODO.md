# WebRTC Tunnel Android State-Integrity Recovery Fix 2 TODO

This TODO implements `WEBRTC_TUNNEL_ANDROID_STATE_INTEGRITY_RECOVERY_FIX2_SPEC.md`.

The previous recovery pass improved the Android code, but several P0/P1 items remain
incomplete. This TODO is intentionally explicit for a local model. Implement it in
small focused commits.

---

# 0. Work discipline

For every task:

```text
1. inspect current code
2. write or update focused regression test first
3. confirm the test fails when practical
4. implement the smallest correct fix
5. run focused test
6. run formatting/lint where relevant
7. commit scoped change
```

Do not mark a checkbox complete because an older TODO said it was complete.

Do not keep tests that encode the wrong invariant.

---

# P0 tasks

## P0-001 — Make startup completion total

**Priority:** P0

**Problem:** `activeStartup` can be set and then startup can return after
`StartupAborted` without sending `StartupCompleted`.

**Files to inspect:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/TunnelLifecycleCoordinator.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundService*.kt
```

### Step 1 — identify current startup result type

- [ ] Find the current type used in `LifecycleCommand.StartupCompleted`.
- [ ] If it is `StartOutcome`, extend that type.
- [ ] Do not create a parallel completion hierarchy.

Add variants if missing:

```kotlin
data class PolicyBlocked(
    val reason: String,
) : StartOutcome

data class Aborted(
    val reason: String,
) : StartOutcome

data class UnexpectedFailure(
    val error: Throwable,
) : StartOutcome
```

Use the actual sealed type name in the codebase.

### Step 2 — replace early return from startup coroutine

Find code shaped like:

```kotlin
val identity =
    try {
        prepareOfferIdentity()
    } catch (_: StartupAborted) {
        return
    }

runOfferStart(identity, startGeneration)
```

Replace with a total startup attempt:

```kotlin
private suspend fun performStartupAttempt(
    generation: Long,
): StartOutcome {
    return try {
        val prepared =
            prepareStartupInputs(
                generation = generation,
            )

        runNativeStart(
            generation = generation,
            prepared = prepared,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (blocked: StartupPolicyBlocked) {
        StartOutcome.PolicyBlocked(
            reason = blocked.message
                ?: "Blocked by network policy",
        )
    } catch (aborted: StartupAborted) {
        StartOutcome.Aborted(
            reason = aborted.message
                ?: "Startup aborted",
        )
    } catch (error: Throwable) {
        StartOutcome.UnexpectedFailure(
            error = error,
        )
    }
}
```

If `StartupPolicyBlocked` does not exist, create a small internal exception:

```kotlin
private class StartupPolicyBlocked(
    message: String,
) : RuntimeException(message)
```

Use it only to route typed completion. Do not expose it to UI.

### Step 3 — startup worker must always submit completion

Use this pattern:

```kotlin
private fun launchStartup(
    generation: Long,
) {
    val job =
        serviceScope.launch {
            val completion =
                performStartupAttempt(
                    generation = generation,
                )

            coordinator.submit(
                LifecycleCommand.StartupCompleted(
                    generation = generation,
                    completion = completion,
                )
            )
        }

    activeStartup =
        ActiveStartup(
            generation = generation,
            job = job,
        )
}
```

### Step 4 — coordinator clears ownership for every completion

In the startup-completed handler:

```kotlin
private suspend fun handleStartupCompleted(
    command: LifecycleCommand.StartupCompleted,
) {
    if (lifecycleGeneration.get() != command.generation) {
        return
    }

    activeStartup = null

    when (val completion = command.completion) {
        is StartOutcome.VerifiedSuccess ->
            handleVerifiedStartupSuccess(command.generation)

        is StartOutcome.PolicyBlocked ->
            handleStartupPolicyBlocked(
                generation = command.generation,
                reason = completion.reason,
            )

        is StartOutcome.Aborted ->
            handleStartupAborted(
                generation = command.generation,
                reason = completion.reason,
            )

        is StartOutcome.NativeStartFailure ->
            handleNativeStartFailure(
                command.generation,
                completion.error,
            )

        is StartOutcome.VerificationFailure ->
            handleVerificationFailure(
                command.generation,
                completion.error,
            )

        is StartOutcome.UnexpectedFailure ->
            handleUnexpectedStartupFailure(
                command.generation,
                completion.error,
            )
    }

    submitPendingPolicyRetryIfValid(
        completedGeneration = command.generation,
    )
}
```

Adjust branch names to match the real type.

### Step 5 — policy-blocked startup handler

```kotlin
private suspend fun handleStartupPolicyBlocked(
    generation: Long,
    reason: String,
) {
    clearTemporaryMeteredAllowance()
    pausedByPolicy.set(true)
    nativeStopVerified.set(true)

    pendingPolicyResumeGeneration.set(null)

    repository.setPolicyBlocked(
        reason = reason,
    )

    reporter.publishStatus(
        "policy_blocked_before_start",
        reason,
    )
}
```

If a pending retry should be preserved for a later `PolicyAllowed`, do not clear it
here; instead clear stale retries and allow a future `PolicyAllowed` to submit a new
resume. The important part is that `activeStartup` is cleared.

### Tests

- [ ] `policyBlockedInitialStartSubmitsCompletionAndClearsActiveStartup`
- [ ] `identityReadFailureSubmitsCompletionAndClearsActiveStartup`
- [ ] `configRewriteFailureSubmitsCompletionAndClearsActiveStartup`
- [ ] `unexpectedPreparationFailurePublishesStartupCompletion`
- [ ] `initialPolicyBlockThenOnePolicyAllowedStartsOnce`
- [ ] no test loops network events until retry appears.

### Acceptance

- [ ] No startup path with active startup can return without completion.
- [ ] `activeStartup` cannot stay stuck after policy block.
- [ ] One later `PolicyAllowed` event is enough.

---

## P0-002 — Fix setup-time private identity zeroization

**Priority:** P0

**Problem:** `SetupSaveController.resolveStoredIdentity()` still loses plaintext bytes
through `runCatching(...).getOrNull()`.

**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupSaveController.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/SetupSaveControllerTest.kt
```

### Step 1 — create named resolved identity type

```kotlin
private data class ResolvedIdentity(
    val privateIdentity: ByteArray,
    val publicIdentity: String,
    val peerId: String,
)
```

### Step 2 — replace `runCatching(...).getOrNull()`

Replace the current resolver with:

```kotlin
private suspend fun resolveStoredIdentity():
    ResolvedIdentity? =
    withContext(dispatcher) {
        val bytes =
            deps.identityRepository
                .readPrivateIdentityPlaintext()

        var transferred = false

        try {
            val validated =
                deps.identityValidation
                    .validatePrivateIdentity(
                        bytes.decodeToString(),
                    )

            require(validated.valid) {
                validated.message
                    ?: "Stored private identity is invalid"
            }

            val publicIdentity =
                deps.identityRepository
                    .readPublicIdentity()

            val peerId =
                deps.identityValidation
                    .derivePeerId(
                        publicIdentity,
                    )

            transferred = true

            ResolvedIdentity(
                privateIdentity = bytes,
                publicIdentity = publicIdentity,
                peerId = peerId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        } finally {
            if (!transferred) {
                bytes.fill(0)
            }
        }
    }
```

If the real code derives peer ID differently, keep the real derivation but preserve the
same ownership pattern.

### Step 3 — final owner wipes

Where the returned private identity is consumed:

```kotlin
val resolved =
    resolveStoredIdentity()

val privateBytes =
    resolved?.privateIdentity

try {
    // use privateBytes
} finally {
    privateBytes?.fill(0)
}
```

Do not wipe before the final use.

### Step 4 — sentinel tests

Create fakes that expose the exact byte array returned to production code.

Test examples:

```kotlin
@Test
fun storedIdentityValidationThrowWipesPlaintext() =
    runTest {
        val sentinel =
            "PRIVATE_TEST_SENTINEL"
                .encodeToByteArray()

        val identityRepo =
            FakeIdentityRepository(
                privateBytes = sentinel,
                publicIdentity = "public",
            )

        val validation =
            FakeIdentityValidation(
                validatePrivateThrows =
                    IllegalArgumentException("bad"),
            )

        val controller =
            newController(
                identityRepository = identityRepo,
                identityValidation = validation,
            )

        controller.save(...)

        assertTrue(
            sentinel.all { it == 0.toByte() },
        )
    }
```

Required tests:

- [ ] validation throws -> zeroed;
- [ ] validation invalid -> zeroed;
- [ ] public identity read throws -> zeroed;
- [ ] peer ID derivation throws -> zeroed;
- [ ] success final owner wipes.

### Acceptance

- [ ] no `runCatching(...).getOrNull()` path owns plaintext bytes.
- [ ] every failure after plaintext read wipes bytes.
- [ ] tests prove actual production path zeroizes.

---

## P0-003 — Surface every preference-write failure

**Priority:** P0

**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/NetworkPolicyViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupSaveController.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/NetworkPolicyViewModelTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/SetupSaveControllerTest.kt
```

### Step 1 — NetworkPolicyViewModel folds Result

Replace ignored result:

```kotlin
deps.configRepository.savePreferences(updated)
deps.snackbar.show("Network policy updated")
```

with:

```kotlin
val result =
    withContext(deps.dispatchers.io) {
        deps.configRepository
            .savePreferences(updated)
    }

result.fold(
    onSuccess = {
        deps.snackbar.show(
            "Network policy updated",
        )
    },
    onFailure = { error ->
        deps.snackbar.show(
            SensitiveDataRedactor.redactText(
                error.message
                    ?: "Failed to update network policy",
            ),
        )
    },
)
```

### Step 2 — make setup persistence failure-aware

Change callback type:

```kotlin
private val persistPreferences:
    suspend (AndroidAppPreferences) -> Result<Unit>
```

Default:

```kotlin
persistPreferences = {
    deps.configRepository.savePreferences(it)
}
```

Call site:

```kotlin
persistPreferences(updated)
    .getOrElse { error ->
        throw PreferencePersistenceException(
            error.message
                ?: "Failed to save preferences",
            error,
        )
    }
```

or return a typed failure result. Do not show setup success if persistence failed.

### Tests

- [ ] network policy success shows success;
- [ ] network policy `Result.failure` shows error;
- [ ] network policy failure does not show success;
- [ ] setup default `Result.failure` blocks success;
- [ ] setup injected failure blocks success;
- [ ] cancellation propagates.

### Acceptance

- [ ] no `savePreferences(...)` result is ignored.
- [ ] no false success snackbar after failed write.

---

## P0-004 — Repair transactional reset exact restore

**Priority:** P0

**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/TransactionalReset.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsRepository.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/data/TransactionalResetCoordinatorTest.kt
```

### Step 1 — stop on first failed stage

If current reset continues after failure, change it.

Pattern:

```kotlin
val mutated =
    mutableListOf<ResetStage>()

for (stage in resetOrder) {
    val outcome =
        resetStage(stage)

    if (outcome is ResetStageResult.Failure) {
        val rollback =
            rollback(
                snapshot = snapshot,
                mutatedStages = mutated,
            )

        return ResetResult.Failed(
            failedStage = stage,
            reason = outcome.reason,
            rollback = rollback,
        )
    }

    mutated += stage
}
```

### Step 2 — restore absent config exactly

Current bad behavior:

```text
prior config absent
reset writes default config
later failure
rollback success but config file remains
```

Fix:

```kotlin
private suspend fun restoreConfig(
    priorConfig: String?,
): RollbackStageResult {
    val result =
        if (priorConfig == null) {
            configRepository
                .deleteConfigFileForTransactionalReset()
        } else {
            configRepository
                .writeConfigAtomically(priorConfig)
        }

    return result.fold(
        onSuccess = {
            RollbackStageResult.Success(
                ResetStage.Config,
            )
        },
        onFailure = { error ->
            RollbackStageResult.Failure(
                stage = ResetStage.Config,
                reason = redact(error),
            )
        },
    )
}
```

Add a narrowly scoped repository method if needed:

```kotlin
internal suspend fun deleteConfigFileForTransactionalReset():
    Result<Unit> =
    writeMutex.withLock {
        try {
            Files.deleteIfExists(
                configFile.toPath(),
            )
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
```

### Step 3 — restore forwards even when empty

Do not skip empty list restore.

Wrong:

```kotlin
if (forwards.isEmpty()) {
    Success
} else {
    forwardsRepository.save(forwards)
}
```

Correct:

```kotlin
val result =
    forwardsRepository
        .restoreForTransactionalReset(
            forwards,
        )
```

### Step 4 — add scoped restore API

In `ForwardsRepository`:

```kotlin
internal suspend fun restoreForTransactionalReset(
    forwards: List<ForwardConfig>,
): Result<Unit> =
    mutex.withLock {
        store.saveForwards(forwards)
            .getOrElse {
                return@withLock
                    Result.failure(it)
            }

        revision += 1L
        _forwards.value = forwards
        _loadError.value = null

        Result.success(Unit)
    }
```

This is not a public user mutation API.

### Tests

- [ ] config absent before reset and later failure -> config absent after rollback;
- [ ] config present before reset and later failure -> exact content restored;
- [ ] setup input restored exactly;
- [ ] prior empty forwards restored and persisted;
- [ ] prior non-empty forwards restored and persisted;
- [ ] reset stops after first failed stage;
- [ ] rollback failure result is not success.

### Acceptance

- [ ] successful rollback stage restores exact captured prior state.
- [ ] empty prior forwards are actually persisted.
- [ ] absent prior config is restored as absent.

---

## P0-005 — Rewrite failed-stop quarantine test

**Priority:** P0

**Files:**

```text
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStopFailureTest.kt
```

### Step 1 — find wrong test

Find any test that does:

```text
STOP fails
ACTION_START_OFFER
expect Connected/running
```

especially:

```text
laterSuccessfulStopDoesNotEraseEarlierCleanupFailureHistory
```

### Step 2 — rewrite expected behavior

Correct test flow:

```kotlin
@Test
fun failedStopQuarantinesUntilExplicitStopSucceeds() =
    runTest {
        // 1. Start successfully.
        // 2. Configure stop to fail.
        // 3. Send STOP.
        // 4. Assert Error/quarantine.
        // 5. Send START.
        // 6. Assert native start count did not increase.
        // 7. Configure stop to succeed.
        // 8. Send STOP.
        // 9. Assert quarantine cleared.
        // 10. Send START.
        // 11. Assert native start count increased.
    }
```

### Acceptance

- [ ] no test expects immediate restart after failed verified STOP.
- [ ] failed STOP blocks START.
- [ ] explicit STOP retry remains allowed.
- [ ] successful STOP clears quarantine.

---

# P1 tasks

## P1-001 — Remove raw forwards mutation bypass

**Priority:** P1

**Files:**

```text
ForwardsRepository.kt
TransactionalReset.kt
ForwardsRepositoryTest.kt
```

### Step 1 — search

```bash
rg "ForwardsRepository\\.save|\\.save\\(forwards|saveIfRevisionMatches|snapshot\\(" android/app/src
```

### Step 2 — delete or restrict raw save

- [ ] remove public `save()`;
- [ ] delete positive tests for public raw save;
- [ ] replace transactional reset usage with `restoreForTransactionalReset()`;
- [ ] ensure ViewModels cannot call restore API.

### Acceptance

- [ ] public raw save bypass removed.
- [ ] all user mutations use receipt API.
- [ ] transactional reset restore is scoped/internal.

---

## P1-002 — Serialize every config writer

**Priority:** P1

**Files:**

```text
ConfigRepository.kt
ConfigRepositoryTest.kt
```

### Step 1 — audit direct writes

```bash
rg "configFile\\.writeText|Files\\.write|writeConfig\\(|ensureDefaultConfig\\(" android/app/src/main
```

### Step 2 — route config.toml writes through atomic writer

Ensure:

```kotlin
ensureDefaultConfig()
```

uses:

```kotlin
writeConfigAtomically(defaultConfig)
```

If `writeConfig()` exists only for tests, either:

- mark it test-only/internal, or
- make it call `writeConfigAtomically()`.

### Step 3 — fix atomic writer fallback and cleanup

```kotlin
private suspend fun writeConfigAtomically(
    content: String,
): Result<Unit> =
    writeMutex.withLock {
        val temp =
            File.createTempFile(
                "config-",
                ".tmp",
                configFile.parentFile,
            )

        try {
            temp.writeText(content)

            try {
                Files.move(
                    temp.toPath(),
                    configFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (
                unsupported: AtomicMoveNotSupportedException
            ) {
                Files.move(
                    temp.toPath(),
                    configFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }

            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            Files.deleteIfExists(
                temp.toPath(),
            )
        }
    }
```

### Tests

- [ ] overlapping writers produce complete file;
- [ ] atomic move unsupported fallback works;
- [ ] temp cleanup after failure;
- [ ] no production direct config write remains.

---

## P1-003 — Fix native schema fallback and terminal peer cleanup

**Priority:** P1

**Files:**

```text
TunnelRepository.kt
TunnelRepositoryTest.kt
```

### Step 1 — null mode is schema error

Replace:

```kotlin
null -> TunnelMode.Offer
```

with schema failure.

Example:

```kotlin
private fun resolveNativeMode(
    rawMode: String?,
): NativeModeResolution =
    when (rawMode) {
        "offer" -> NativeModeResolution.Known(TunnelMode.Offer)
        "answer" -> NativeModeResolution.Known(TunnelMode.Answer)
        null ->
            NativeModeResolution.SchemaError(
                "native_status_schema_error: missing mode",
            )
        else ->
            NativeModeResolution.SchemaError(
                "native_status_schema_error: unknown mode ${redact(rawMode)}",
            )
    }
```

### Step 2 — terminal peer cleanup helper

```kotlin
private fun TunnelStatus.withoutActivePeer():
    TunnelStatus =
    copy(
        remotePeerId = null,
        activeSessionCount = 0,
        mqttConnected = false,
    )
```

Use it for:

```text
Stopped
Error
PausedMeteredBlocked
NoNetwork
ConfigInvalid
```

### Tests

- [ ] missing mode fails startup verification;
- [ ] future mode fails startup verification;
- [ ] policy blocked clears peer;
- [ ] local error clears peer;
- [ ] config invalid clears peer.

---

## P1-004 — Make logs and logsError generation-consistent

**Priority:** P1

**Files:**

```text
TunnelRepository.kt
LogsViewModel.kt
LogsViewModelTest.kt
LogsScreen.kt
```

### Step 1 — add result type

```kotlin
data class LogsFetchResult(
    val logs: List<LogEntry>,
    val error: TunnelError?,
)
```

### Step 2 — repository returns result, does not mutate shared error

```kotlin
suspend fun fetchRecentLogs(
    maxEvents: Int,
): LogsFetchResult {
    return try {
        LogsFetchResult(
            logs = bridge.recentLogs(maxEvents),
            error = null,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        LogsFetchResult(
            logs = emptyList(),
            error = TunnelError(
                code = "logs_refresh_failed",
                message =
                    SensitiveDataRedactor
                        .redactText(
                            error.message
                                ?: "Log refresh failed",
                        ),
            ),
        )
    }
}
```

### Step 3 — ViewModel generation owns both

```kotlin
fun refresh() {
    val generation =
        ++refreshGeneration

    viewModelScope.launch {
        val result =
            withContext(dispatcher) {
                repository.fetchRecentLogs(maxEvents)
            }

        if (generation != refreshGeneration) {
            return@launch
        }

        _logs.value = result.logs
        _logsError.value = result.error
    }
}
```

### Tests

- [ ] older failure cannot set error after newer success;
- [ ] older success cannot clear newer failure;
- [ ] older success cannot replace newer list;
- [ ] UI displays current error.

---

## P1-005 — Make network event delivery observable

**Priority:** P1

**Files:**

```text
NetworkPolicyManager.kt
network policy tests
```

### Option A — preferred

Remove lossy `callbackFlow` delivery and expose a `StateFlow<NetworkPolicyStatus>`.

### Option B — acceptable for this pass

Handle failed `trySend`.

```kotlin
private fun ProducerScope<NetworkPolicyStatus>
    .emitPolicyStatus(
        status: NetworkPolicyStatus,
    ) {
    val result = trySend(status)

    if (result.isFailure) {
        reporter.publishError(
            code = "network_policy_event_delivery_failed",
            message =
                "Network policy event could not be delivered",
        )
    }
}
```

If reporter is not available, inject one.

### Tests

- [ ] failed send result publishes diagnostic;
- [ ] cancellation does not publish false error;
- [ ] service can resync from current status.

---

## P1-006 — Finish pending retry invalidation

**Priority:** P1

**Files:**

```text
TunnelForegroundService.kt
TunnelForegroundService tests
```

### Step 1 — add helper

```kotlin
private fun invalidatePendingPolicyRetry() {
    pendingPolicyResumeGeneration.set(null)
}
```

### Step 2 — call helper on every lifecycle boundary

- [ ] Stop;
- [ ] Pause;
- [ ] PolicyBlocked;
- [ ] StartOffer;
- [ ] AllowMeteredSession;
- [ ] Destroy.

### Step 3 — retry handler guard

```kotlin
if (!pausedByPolicy.get()) {
    return
}
```

### Tests

- [ ] pending retry then Stop -> no restart;
- [ ] pending retry then Pause -> no restart;
- [ ] pending retry then PolicyBlocked -> no restart;
- [ ] pending retry then new StartOffer -> old retry ignored;
- [ ] valid retry runs once.

---

## P1-007 — Correct StartOutcome bridge claim

**Priority:** P1

**Files:**

```text
StartOutcome.kt
docs/comments mentioning typed JNI result
```

Replace misleading wording with:

```kotlin
/**
 * Android-side typed classification of native start results.
 *
 * The JNI bridge still exposes primitive success/failure. This type is used above
 * the bridge boundary to route startup completion through the lifecycle coordinator.
 */
```

### Acceptance

- [ ] no comment claims JNI returns `StartOutcome`;
- [ ] no comment says classification moved to JNI boundary unless true.

---

# P2 tasks

## P2-001 — Record final signoff evidence

**Priority:** P2

After code fixes:

- [ ] final production SHA;
- [ ] fresh workflow run URL/ID;
- [ ] workflow head SHA;
- [ ] Android focused lifecycle result;
- [ ] Android identity/setup result;
- [ ] Android forwards/reset result;
- [ ] Android logs/preferences/network result;
- [ ] Android full result;
- [ ] every unavailable check uses `NOT RUN: exact reason`.

---

# Final validation commands

## Focused lifecycle

```bash
cd android

./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundServiceStopFailureTest' \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundService*' \
  --tests 'com.phillipchin.webrtctunnel.data.TunnelRepositoryTest' \
  --rerun-tasks
```

Run three fresh times.

## Identity/setup

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.SetupSaveControllerTest' \
  --tests 'com.phillipchin.webrtctunnel.security.IdentityRepositoryTest' \
  --rerun-tasks
```

## Forwards/reset

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.data.ForwardsRepositoryTest' \
  --tests 'com.phillipchin.webrtctunnel.data.TransactionalResetCoordinatorTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.SettingsViewModelTest' \
  --rerun-tasks
```

## Logs/preferences/network

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.LogsViewModelTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.NetworkPolicyViewModelTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.SettingsViewModelTest' \
  --rerun-tasks
```

## Full Android

```bash
./gradlew --no-daemon detekt
./gradlew --no-daemon ktlintCheck
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
./gradlew --no-daemon check
```

# Completion checklist

## P0

- [ ] Every active startup submits completion or is cancelled.
- [ ] Policy-blocked startup clears startup ownership.
- [ ] One later PolicyAllowed event resumes from policy block.
- [ ] Setup stored identity path wipes plaintext on validation throw.
- [ ] Setup stored identity path wipes plaintext on validation invalid.
- [ ] Setup stored identity path wipes plaintext on public identity read failure.
- [ ] Setup stored identity path wipes plaintext on peer derivation failure.
- [ ] Every preference write result is folded.
- [ ] Network policy write failure is visible.
- [ ] Setup preference write failure blocks success.
- [ ] Transactional reset restores absent config as absent.
- [ ] Transactional reset restores empty forwards as empty persisted state.
- [ ] Transactional reset stops after first failed stage.
- [ ] Failed STOP quarantine test rewritten.

## P1

- [ ] Raw forwards save bypass removed.
- [ ] Config writers all use serialized atomic writer.
- [ ] Atomic move fallback and temp cleanup implemented.
- [ ] Missing native mode fails visibly.
- [ ] Terminal states clear active peer.
- [ ] Logs and logsError share one generation.
- [ ] Network event delivery failure visible or impossible.
- [ ] Pending retry invalidation centralized.
- [ ] Retry handler checks pausedByPolicy.
- [ ] StartOutcome JNI claim corrected.

## P2

- [ ] Fresh CI/signoff evidence recorded.
