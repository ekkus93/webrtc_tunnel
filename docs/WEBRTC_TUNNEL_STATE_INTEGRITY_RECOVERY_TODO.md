# WebRTC Tunnel State-Integrity Recovery TODO

This TODO implements `WEBRTC_TUNNEL_STATE_INTEGRITY_RECOVERY_SPEC.md`.

The current code must not be treated as signed off until all P0 items are complete.

---

# 0. Work discipline

For every task:

```text
1. inspect current code
2. add/update focused regression test first
3. implement the smallest correct fix
4. run focused test
5. run formatting/lint
6. commit one scoped change
```

Do not make one giant commit.

Do not mark boxes complete because an older TODO had them checked.

Do not preserve tests that encode the wrong invariant.

---

# P0 tasks

## P0-001 — Replace fake transactional reset

**Priority:** P0

**Files to inspect:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/TransactionalReset.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SettingsViewModel.kt
related tests
```

### Step 1 — add exact prior-state snapshot

- [ ] Add a snapshot type for every reset stage.
- [ ] Capture current config bytes/text.
- [ ] Capture current setup input.
- [ ] Capture current forwards list.
- [ ] Capture before any reset mutation starts.

Suggested model:

```kotlin
data class ResetSnapshot(
    val configToml: ByteArray?,
    val setupInput: SetupConfigInput,
    val forwards: List<ForwardConfig>,
)
```

Use the real repository types.

### Step 2 — fix nested `Result` handling

- [ ] Search for `runCatching { repositoryMethodReturningResult() }`.
- [ ] Remove nested-`Result` misuse.
- [ ] Explicitly fold the inner `Result`.

Wrong:

```kotlin
runCatching {
    forwardsRepository.resetForwards()
}.fold(
    onSuccess = {
        StageOutcome.Success(Stage.Forwards)
    },
    onFailure = { error ->
        StageOutcome.Failure(
            Stage.Forwards,
            error.message ?: "unknown"
        )
    },
)
```

Correct:

```kotlin
val result =
    forwardsRepository.resetForwards()

val outcome =
    result.fold(
        onSuccess = {
            StageOutcome.Success(
                Stage.Forwards
            )
        },
        onFailure = { error ->
            StageOutcome.Failure(
                stage = Stage.Forwards,
                reason =
                    error.message
                        ?: "unknown error",
            )
        },
    )
```

### Step 3 — implement real restore operations

- [ ] Add restore config from snapshot.
- [ ] Add restore setup input from snapshot.
- [ ] Add restore forwards from snapshot.
- [ ] Do not call reset again as rollback.

Suggested flow:

```kotlin
private suspend fun rollback(
    snapshot: ResetSnapshot,
    mutatedStages: List<ResetStage>,
): List<RollbackStageResult> {
    val results =
        mutableListOf<RollbackStageResult>()

    for (stage in mutatedStages.asReversed()) {
        val result =
            when (stage) {
                ResetStage.Config ->
                    restoreConfig(
                        snapshot.configToml
                    )

                ResetStage.SetupInput ->
                    restoreSetupInput(
                        snapshot.setupInput
                    )

                ResetStage.Forwards ->
                    restoreForwards(
                        snapshot.forwards
                    )
            }

        results +=
            result.fold(
                onSuccess = {
                    RollbackStageResult.Success(
                        stage
                    )
                },
                onFailure = { error ->
                    RollbackStageResult.Failure(
                        stage = stage,
                        reason =
                            error.message
                                ?: "unknown error",
                    )
                },
            )
    }

    return results
}
```

### Step 4 — model reset and rollback outcomes explicitly

- [ ] Add exact failed stage.
- [ ] Add exact rollback outcomes.
- [ ] Keep messages redacted.
- [ ] Remove generic-only `"Reset failed"` result.

Suggested:

```kotlin
sealed interface ResetResult {
    data class Success(
        val stages: List<ResetStageResult>,
    ) : ResetResult

    data class Failed(
        val failedStage: ResetStage,
        val reason: String,
        val rollback:
            List<RollbackStageResult>,
    ) : ResetResult
}
```

### Tests

- [ ] all stages succeed;
- [ ] config stage fails;
- [ ] setup stage fails after config mutation;
- [ ] forwards returns `Result.failure`;
- [ ] config restored exactly;
- [ ] setup input restored exactly;
- [ ] forwards restored exactly;
- [ ] rollback failure reported;
- [ ] no nested `Result` false success.

### Acceptance

- [ ] No reset operation is described as transactional unless exact prior state can be restored.
- [ ] A forward-reset failure cannot become `StageOutcome.Success`.
- [ ] Rollback never means “reset again.”

---

## P0-002 — Make quarantine non-bypassable

**Priority:** P0

**Files to inspect:**

```text
TunnelForegroundService.kt
OfferCoordinator / start helper files
notification action wiring
lifecycle command handling
related tests
```

### Step 1 — add one canonical start/resume guard

- [ ] Add `requireRuntimeStartAllowed()`.
- [ ] Use one exception/result type.

Example:

```kotlin
private fun requireRuntimeStartAllowed():
    Result<Unit> {
    if (nativeRuntimeUncertain.get()) {
        return Result.failure(
            NativeRuntimeQuarantinedException(
                "Native runtime state is uncertain; explicit STOP is required before restart."
            )
        )
    }

    return Result.success(Unit)
}
```

### Step 2 — apply it everywhere

Guard:

- [ ] ACTION_START_OFFER;
- [ ] coordinator StartOffer;
- [ ] OfferCoordinator.startOffer();
- [ ] resume();
- [ ] allowMeteredForSessionAndStart();
- [ ] PolicyAllowed;
- [ ] RetryPolicyResume;
- [ ] notification Retry/Start;
- [ ] automatic startup.

Example:

```kotlin
private suspend fun handleStartOffer() {
    requireRuntimeStartAllowed()
        .getOrElse { error ->
            reporter.publishError(
                code = "native_runtime_quarantined",
                message =
                    SensitiveDataRedactor
                        .redactText(
                            error.message
                                ?: "Runtime restart is blocked"
                        ),
            )
            return
        }

    offer.startOffer()
}
```

### Step 3 — explicit STOP remains available

- [ ] STOP bypasses the start guard.
- [ ] successful verified STOP clears quarantine.
- [ ] failed STOP keeps quarantine.

### Tests

- [ ] failed stop sets quarantine;
- [ ] StartOffer blocked;
- [ ] Resume blocked;
- [ ] AllowMeteredSession blocked;
- [ ] PolicyAllowed blocked;
- [ ] RetryPolicyResume blocked;
- [ ] notification retry blocked;
- [ ] explicit STOP allowed;
- [ ] successful STOP clears;
- [ ] later StartOffer allowed.

### Acceptance

- [ ] No code path can start native runtime while quarantined.
- [ ] Delete/rewrite any test that currently restarts immediately after failed verified STOP.

---

## P0-003 — Make lifecycle coordinator owned and non-lossy

**Priority:** P0

**Files:**

```text
TunnelLifecycleCoordinator.kt
TunnelForegroundService.kt
coordinator tests
```

### Step 1 — remove independent unmanaged scope

- [ ] Service owns coordinator scope.
- [ ] Coordinator does not create an independent lifetime that survives service.

Suggested constructor:

```kotlin
class TunnelLifecycleCoordinator(
    private val operations:
        CoordinatorOperations,
    private val scope: CoroutineScope,
)
```

### Step 2 — store processor Job

```kotlin
private var processorJob: Job? = null
```

- [ ] prevent double start;
- [ ] close/cancel processor on teardown.

Suggested:

```kotlin
fun start() {
    check(processorJob == null)

    processorJob =
        scope.launch {
            processCommands()
        }
}

suspend fun stop() {
    commands.close()
    processorJob?.cancelAndJoin()
    processorJob = null
}
```

### Step 3 — remove lossy critical `trySend`

- [ ] STOP cannot be dropped.
- [ ] PAUSE cannot be dropped.
- [ ] StartupCompleted cannot be dropped.

Preferred:

```kotlin
suspend fun submit(
    command: LifecycleCommand,
) {
    commands.send(command)
}
```

Callback handoff:

```kotlin
serviceScope.launch {
    coordinator.submit(command)
}
```

### Step 4 — rethrow cancellation

Wrong:

```kotlin
runCatching {
    handleCommand(command)
}.onFailure { error ->
    if (error !is CancellationException) {
        ...
    }
}
```

Correct:

```kotlin
try {
    handleCommand(command)
} catch (
    cancelled: CancellationException
) {
    throw cancelled
} catch (error: Throwable) {
    reporter.publishError(
        code = "lifecycle_command_failed",
        message =
            SensitiveDataRedactor
                .redactText(
                    error.message
                        ?: "Lifecycle command failed"
                ),
    )
}
```

### Tests

- [ ] FIFO order;
- [ ] queue pressure does not drop STOP;
- [ ] processor cancelled on teardown;
- [ ] queued work does not run after teardown;
- [ ] cancellation propagates;
- [ ] unexpected handler error visible.

---

## P0-004 — Fix one-event policy retry state machine

**Priority:** P0

**Files:**

```text
TunnelForegroundService.kt
lifecycle command tests
policy retry tests
```

### Step 1 — stop using `startupJob?.isActive` as completion authority

- [ ] add explicit startup ownership;
- [ ] coordinator clears ownership.

Suggested:

```kotlin
private data class ActiveStartup(
    val generation: Long,
    val job: Job,
)

private var activeStartup:
    ActiveStartup? = null
```

### Step 2 — startup worker submits completion only

```kotlin
private fun launchStartup(
    generation: Long,
) {
    val job =
        serviceScope.launch {
            val completion =
                performStartupAttempt(
                    generation
                )

            coordinator.submit(
                LifecycleCommand
                    .StartupCompleted(
                        generation =
                            generation,
                        completion =
                            completion,
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

### Step 3 — completion handler clears ownership first

```kotlin
private suspend fun handleStartupCompleted(
    command:
        LifecycleCommand.StartupCompleted,
) {
    if (
        lifecycleGeneration.get() !=
            command.generation
    ) {
        return
    }

    activeStartup = null

    when (val completion =
        command.completion) {
        ...
    }

    if (
        consumePendingRetryFor(
            command.generation
        )
    ) {
        coordinator.submit(
            LifecycleCommand
                .RetryPolicyResume(
                    expectedGeneration =
                        command.generation,
                )
        )
    }
}
```

### Step 4 — pending retry records intent only

```kotlin
private fun requestPolicyRetryAfterStartup() {
    val generation =
        activeStartup?.generation
            ?: return

    pendingPolicyResumeGeneration
        .set(generation)
}
```

### Exact regression

- [ ] event #1 starts attempt #1;
- [ ] attempt #1 fails;
- [ ] one event #2 arrives before completion processed;
- [ ] no event #3;
- [ ] attempt #2 starts exactly once;
- [ ] attempt #2 succeeds.

Delete any loop that repeatedly triggers network callbacks until success.

---

## P0-005 — Complete failure boundaries

**Priority:** P0

### Command processor

- [ ] unexpected error visible;
- [ ] cancellation rethrown.

### Network monitor

Replace `runCatching` callback bodies with explicit try/catch.

- [ ] failure code `network_policy_monitor_failed`;
- [ ] cancellation rethrown;
- [ ] monitoring does not silently die.

### Startup preparation

- [ ] unexpected config read failure becomes completion;
- [ ] unexpected preference read failure becomes completion;
- [ ] unexpected identity read failure becomes completion;
- [ ] unexpected address resolution failure becomes completion.

Suggested outer function:

```kotlin
private suspend fun performStartupAttempt(
    generation: Long,
): StartupCompletion {
    return try {
        val prepared =
            prepareStartupInputs(
                generation
            )

        runNativeStart(
            generation,
            prepared,
        )
    } catch (
        cancelled: CancellationException
    ) {
        throw cancelled
    } catch (error: Throwable) {
        StartupCompletion
            .UnexpectedFailure(error)
    }
}
```

### Status poll

Remove discarded `runCatching`.

```kotlin
try {
    repository.refreshStatus()
} catch (
    cancelled: CancellationException
) {
    throw cancelled
} catch (error: Throwable) {
    reporter.publishError(
        code = "status_poll_failed",
        message =
            SensitiveDataRedactor
                .redactText(
                    error.message
                        ?: "Status poll failed"
                ),
    )
}
```

### Preference read

Remove:

```kotlin
runCatching {
    configRepository.preferences.first()
}.getOrNull()
```

Use visible failure.

### Tests

- [ ] command handler throws;
- [ ] network preference read throws;
- [ ] startup prep throws;
- [ ] native start throws;
- [ ] status refresh throws;
- [ ] cancellation propagates in each boundary.

---

## P0-006 — Fix private identity zeroization completely

**Priority:** P0

**Files:**

```text
SetupSaveController.kt
startup preparation code
IdentityRepository.kt
identity tests
```

### Step 1 — remove `getOrNull()` ownership loss

Search:

```text
runCatching
getOrNull
readPrivateIdentityPlaintext
```

- [ ] remove any path that can lose plaintext reference.

### Step 2 — explicit owner transfer

Use:

```kotlin
val bytes =
    deps.identityRepository
        .readPrivateIdentityPlaintext()

var transferred = false

try {
    val validated =
        deps.identityValidation
            .validatePrivateIdentity(
                bytes.decodeToString()
            )

    require(validated.valid) {
        validated.message
            ?: "Stored private identity is invalid"
    }

    val publicIdentity =
        deps.identityRepository
            .readPublicIdentity()

    transferred = true

    return ResolvedIdentity(
        privateIdentity = bytes,
        publicIdentity = publicIdentity,
        peerId =
            derivePeerId(
                publicIdentity
            ),
    )
} finally {
    if (!transferred) {
        bytes.fill(0)
    }
}
```

### Step 3 — receiving owner wipes

- [ ] identify final owner;
- [ ] wipe after native start/validation finishes;
- [ ] wipe on cancellation.

### Tests

Use sentinel bytes.

- [ ] validation throws → zeroed;
- [ ] validation invalid → zeroed;
- [ ] public identity read throws → zeroed;
- [ ] peer ID derivation throws → zeroed;
- [ ] later startup prep throws → final owner zeroes.

---

## P0-007 — Surface every preference-write failure

**Priority:** P0

**Files:**

```text
NetworkPolicyViewModel.kt
SettingsViewModel.kt
Setup persistence/controller files
ConfigRepository.kt
tests
```

### Network Policy

Replace ignored result:

```kotlin
deps.configRepository
    .savePreferences(updated)

deps.snackbar.show(
    "Network policy updated"
)
```

with:

```kotlin
val result =
    withContext(
        deps.dispatchers.io
    ) {
        deps.configRepository
            .savePreferences(updated)
    }

result.fold(
    onSuccess = {
        deps.snackbar.show(
            "Network policy updated"
        )
    },
    onFailure = { error ->
        deps.snackbar.show(
            SensitiveDataRedactor
                .redactText(
                    error.message
                        ?: "Failed to update network policy"
                )
        )
    },
)
```

### Setup callback

Change callback type from:

```kotlin
(AndroidAppPreferences) -> Unit
```

to:

```kotlin
suspend (
    AndroidAppPreferences
) -> Result<Unit>
```

if persistence can fail.

### Tests

- [ ] success message only after actual success;
- [ ] failed network policy write visible;
- [ ] no false success snackbar;
- [ ] setup preference write failure blocks success;
- [ ] cancellation rethrown.

---

## P0-008 — Fix temporary metered allowance lifetime

**Priority:** P0

### Clear on

- [ ] native startup failure;
- [ ] verification failure;
- [ ] unexpected failure;
- [ ] startup preparation abort;
- [ ] ordinary pause success;
- [ ] policy pause success;
- [ ] verified stop;
- [ ] destroy.

### Preserve on

- [ ] verified startup success;
- [ ] active authorized run.

### Centralize

```kotlin
private fun endTemporaryMeteredAllowance() {
    temporaryMeteredAllowance
        .set(false)
}
```

### Tests

- [ ] successful run retains;
- [ ] next callback does not pause;
- [ ] ordinary pause clears;
- [ ] policy pause clears;
- [ ] pre-native failure clears;
- [ ] native start failure clears;
- [ ] stop clears.

---

# P1 tasks

## P1-001 — Implement atomic forwards mutation receipts

**Priority:** P1

**Files:**

```text
ForwardsRepository.kt
ForwardsViewModel.kt
tests
```

### Add receipt

```kotlin
data class ForwardsMutationReceipt(
    val before: List<ForwardConfig>,
    val after: List<ForwardConfig>,
    val committedRevision: Long,
)
```

### Upsert under one mutex

```kotlin
suspend fun upsertWithReceipt(
    forward: ForwardConfig,
): Result<ForwardsMutationReceipt> =
    mutex.withLock {
        mutationGuard()
            .getOrElse {
                return@withLock
                    Result.failure(it)
            }

        val before =
            _forwards.value

        val after =
            applyUpsert(
                before,
                forward,
            )

        store.saveForwards(after)
            .getOrElse {
                return@withLock
                    Result.failure(it)
            }

        revision += 1L
        _forwards.value = after

        Result.success(
            ForwardsMutationReceipt(
                before = before,
                after = after,
                committedRevision =
                    revision,
            )
        )
    }
```

### Rollback exact receipt

```kotlin
suspend fun rollback(
    receipt:
        ForwardsMutationReceipt,
): Result<Unit> =
    mutex.withLock {
        if (
            revision !=
                receipt.committedRevision
        ) {
            return@withLock
                Result.failure(
                    StaleMutationReceiptException()
                )
        }

        store.saveForwards(
            receipt.before
        ).getOrElse {
            return@withLock
                Result.failure(it)
        }

        revision += 1L
        _forwards.value =
            receipt.before

        Result.success(Unit)
    }
```

### Remove ViewModel pattern

Delete:

```kotlin
val beforeSnapshot =
    repository.snapshot()

repository.upsert(...)

repository.saveIfRevisionMatches(...)
```

### Tests

- [ ] exact before/after;
- [ ] exact revision;
- [ ] intervening mutation preserved;
- [ ] old receipt invalid after refresh;
- [ ] rollback restores exact list.

---

## P1-002 — Remove forwards mutation bypasses

**Priority:** P1

### Audit

Search:

```text
ForwardsRepository.save(
saveIfRevisionMatches
ForwardsConfigStore.saveForwards
snapshot()
```

- [ ] delete unused public `save()`;
- [ ] delete bypass methods;
- [ ] migrate direct store callers;
- [ ] every mutation uses `mutationGuard()`.

Suggested guard:

```kotlin
private fun mutationGuard():
    Result<Unit> {
    val error = _loadError.value

    return if (error == null) {
        Result.success(Unit)
    } else {
        Result.failure(
            ForwardsMutationBlockedException(
                "Forwards mutation is blocked until the load error is resolved."
            )
        )
    }
}
```

### Tests

- [ ] initial load error blocks all mutations;
- [ ] later refresh error blocks all mutations;
- [ ] successful refresh clears block;
- [ ] no raw save API remains.

---

## P1-003 — Serialize every config.toml writer

**Priority:** P1

### Audit direct writes

Search:

```text
configFile.writeText
Files.write
writeConfig(
ensureDefaultConfig(
```

- [ ] production `config.toml` writes all go through one writer mutex.

### Atomic writer

Use unique temp file and `finally`.

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
                    StandardCopyOption
                        .ATOMIC_MOVE,
                    StandardCopyOption
                        .REPLACE_EXISTING,
                )
            } catch (
                unsupported:
                    AtomicMoveNotSupportedException
            ) {
                Files.move(
                    temp.toPath(),
                    configFile.toPath(),
                    StandardCopyOption
                        .REPLACE_EXISTING,
                )
            }

            Result.success(Unit)
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            Files.deleteIfExists(
                temp.toPath()
            )
        }
    }
```

### Tests

- [ ] two overlapping writers;
- [ ] final file complete;
- [ ] no shared temp filename race;
- [ ] temp cleanup after move failure.

---

## P1-004 — Fix status schema fallbacks and terminal peer cleanup

**Priority:** P1

### Native mode

Replace:

```kotlin
null -> TunnelMode.Offer
```

with explicit schema error if mode is required.

### Unknown runtime state

- [ ] Error state;
- [ ] `native_status_schema_error`;
- [ ] redacted raw value.

### Terminal peer cleanup

Clear `remotePeerId` on:

- [ ] Stopped;
- [ ] Error;
- [ ] PausedMeteredBlocked;
- [ ] NoNetwork;
- [ ] ConfigInvalid.

Central helper example:

```kotlin
private fun TunnelStatus
    .withoutActivePeer():
    TunnelStatus =
    copy(
        remotePeerId = null,
        activeSessionCount = 0,
        mqttConnected = false,
    )
```

### Tests

- [ ] null mode fails;
- [ ] future mode fails;
- [ ] unknown runtime state diagnosed;
- [ ] policy pause clears peer;
- [ ] local Error clears peer;
- [ ] NoNetwork clears peer.

---

## P1-005 — Keep `nativeStopVerified` truthful

**Priority:** P1

Set true after verified success for:

- [ ] Pause;
- [ ] PolicyBlocked;
- [ ] Stop;
- [ ] unverified-start cleanup;
- [ ] destroy fallback.

Set false before native start begins.

Destroy example:

```kotlin
repository.stop()
    .onSuccess {
        nativeStopVerified.set(true)
    }
    .onFailure { error ->
        nativeStopVerified.set(false)
        nativeRuntimeUncertain.set(true)
        ...
    }
```

### Tests

- [ ] pause → destroy no second stop;
- [ ] policy pause → destroy no second stop;
- [ ] destroy fallback success verified;
- [ ] destroy fallback failure quarantined.

---

## P1-006 — Make initial policy block auto-resumable with one event

**Priority:** P1

### Preferred typed completion

Add:

```kotlin
data class PolicyBlocked(
    val reason: String,
) : StartupCompletion
```

The startup attempt returns it instead of throwing away coordination.

Coordinator:

```text
clear startup ownership
set pausedByPolicy
publish policy-blocked state
then allow one later PolicyAllowed event to resume
```

### Test

```text
StartOffer
initial policy block
one PolicyAllowed
exactly one native start
```

No repeated callback loop.

---

## P1-007 — Serialize log result and log error together

**Priority:** P1

### Add typed fetch result

```kotlin
data class LogsFetchResult(
    val logs: List<LogEntry>,
    val error: String?,
)
```

Repository must not mutate global error before generation check.

ViewModel owns generation:

```kotlin
val generation =
    ++refreshGeneration

val result =
    withContext(ioDispatcher) {
        repository.fetchRecentLogs()
    }

if (
    generation !=
        refreshGeneration
) {
    return@launch
}

_logs.value = result.logs
_logsError.value = result.error
```

### Tests

- [ ] old success cannot replace new success;
- [ ] old failure cannot replace new success;
- [ ] old success cannot clear new failure;
- [ ] UI error remains visible.

---

## P1-008 — Make network event delivery observable

**Priority:** P1

### Audit

Search:

```text
trySend(
callbackFlow
```

- [ ] handle failed send result;
- [ ] publish redacted error;
- [ ] prefer direct StateFlow collection if possible.

Example:

```kotlin
val result =
    trySend(current)

if (result.isFailure) {
    reporter.publishError(
        code =
            "network_policy_event_delivery_failed",
        message =
            "Network policy event could not be delivered",
    )
}
```

### Tests

- [ ] failed delivery visible;
- [ ] current state can resynchronize;
- [ ] cancellation clean.

---

## P1-009 — Explicitly invalidate pending retry

**Priority:** P1

Add:

```kotlin
private fun invalidatePendingPolicyRetry() {
    pendingPolicyResumeGeneration
        .set(null)
}
```

Call on:

- [ ] Stop;
- [ ] Pause;
- [ ] PolicyBlocked;
- [ ] StartOffer;
- [ ] AllowMeteredSession;
- [ ] Destroy.

Retry handler:

```kotlin
if (!pausedByPolicy.get()) {
    return
}
```

### Tests

- [ ] pending + Stop → no restart;
- [ ] pending + Pause → no restart;
- [ ] pending + block → no restart;
- [ ] pending + new StartOffer invalidates old retry;
- [ ] valid retry once.

---

## P1-010 — Resolve typed `StartOutcome` claim

**Priority:** P1

Choose one.

### Option A — implement bridge-level typed result

- [ ] bridge returns `StartOutcome`;
- [ ] decode JNI primitive once at bridge boundary;
- [ ] coordinator no longer performs post-hoc `Result<Unit>` classification.

Example:

```kotlin
interface TunnelBridge {
    suspend fun startOffer(
        configPath: String,
        identityBytes: ByteArray,
    ): StartOutcome
}
```

### Option B — remove false completion claim

- [ ] keep current bridge API;
- [ ] remove docs/comments saying typed result exists through JNI;
- [ ] mark task not implemented.

Do not keep an inaccurate architecture claim.

---

# P2 tasks

## P2-001 — Enforce Android quality gates in remote CI

**Priority:** P2

**Files:**

```text
.github/workflows/ci.yml
```

Add remote commands:

```bash
cd android
./gradlew --no-daemon detekt
./gradlew --no-daemon ktlintCheck
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
```

- [ ] fresh workflow run;
- [ ] record workflow ID/URL;
- [ ] record workflow head SHA;
- [ ] ensure head matches final production SHA or one docs-only child.

---

# Required test replacements

## Delete repeated network-callback workaround

Search for comments like:

```text
keep re-firing the network event
until retry is observed
```

- [ ] delete the loop;
- [ ] replace with one-event invariant test.

## Delete restart-after-failed-stop expectation

Any test that expects:

```text
STOP fails
then START succeeds immediately
```

must be rewritten.

Correct:

```text
STOP fails
quarantine active
START blocked
STOP retry succeeds
quarantine clears
START allowed
```

---

# Final validation

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

## Forwards/reset

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.data.ForwardsRepositoryTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.ForwardsViewModelTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.SettingsViewModelTest' \
  --rerun-tasks
```

## Logs/preferences

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.LogsViewModelTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.NetworkPolicyViewModelTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.SettingsViewModelTest' \
  --rerun-tasks
```

## Android full

```bash
./gradlew --no-daemon detekt
./gradlew --no-daemon ktlintCheck
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
./gradlew --no-daemon check
```

## Rust full

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo clippy --workspace --release --all-features -- -D warnings
cargo test --workspace --all-targets --all-features
```

## Service/package

```bash
scripts/check-systemd-units.sh
scripts/check-launchd-plists.sh
scripts/test-debian-package.sh
bash -n scripts/*.sh
sh -n packaging/debian/postinst packaging/debian/prerm packaging/debian/postrm
```

macOS:

```bash
scripts/test-launchd-install-layout.sh
```

---

# Final completion checklist

## P0

- [ ] Real transactional reset implemented.
- [ ] Nested `Result` false-success bug removed.
- [ ] Real snapshot restore implemented.
- [ ] Quarantine blocks every start/resume path.
- [ ] STOP remains available in quarantine.
- [ ] Verified STOP is only quarantine clear.
- [ ] Coordinator lifetime owned by service.
- [ ] Critical lifecycle commands cannot be dropped.
- [ ] Cancellation rethrown.
- [ ] One-event retry test passes with exactly one later event.
- [ ] Status poll failure visible.
- [ ] Startup-preparation failures visible.
- [ ] No private identity plaintext lost through `getOrNull`.
- [ ] Preference-write failures visible.
- [ ] No false success snackbar.
- [ ] Metered allowance clears on every terminal/failure path.

## P1

- [ ] Atomic forwards mutation receipt implemented.
- [ ] Snapshot+mutation split removed.
- [ ] Raw forwards save bypass removed.
- [ ] loadError blocks every mutation.
- [ ] All config.toml writers serialized.
- [ ] Config temp files cleaned on failure.
- [ ] Null/unknown native schema values fail visibly.
- [ ] Terminal states clear active peer.
- [ ] nativeStopVerified truthful on destroy fallback.
- [ ] Initially policy-blocked start resumes from one event.
- [ ] Log list and log error share generation ownership.
- [ ] Network event delivery failures visible.
- [ ] Pending policy retry invalidated explicitly.
- [ ] Typed StartOutcome claim is either truly implemented or removed.

## P2 / signoff

- [ ] Remote CI runs detekt.
- [ ] Remote CI runs ktlintCheck.
- [ ] Remote CI runs lintDebug.
- [ ] Remote CI runs Android unit tests.
- [ ] Remote CI builds debug APK.
- [ ] Final production SHA recorded.
- [ ] Fresh workflow run recorded.
- [ ] Workflow head SHA recorded.
- [ ] Every unavailable check uses `NOT RUN: exact reason`.

Do not sign off until all P0 and P1 items are complete.
