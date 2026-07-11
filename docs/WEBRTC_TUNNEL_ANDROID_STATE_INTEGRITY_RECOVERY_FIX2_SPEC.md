# WebRTC Tunnel Android State-Integrity Recovery Fix 2 Spec

## 1. Purpose

This spec defines the next Android-focused corrective pass for the WebRTC Tunnel app.

The prior `WEBRTC_TUNNEL_STATE_INTEGRITY_RECOVERY_TODO` moved the code in the right
direction, but the latest review found that several release-blocking Android invariants
are still incomplete.

This pass is intentionally narrower than the previous recovery TODO. It does not ask
Claude Code to redesign the app. It asks Claude Code to finish the remaining Android
state-integrity holes with focused, test-first changes.

The most important problems to fix are:

1. startup attempts can abort before submitting `StartupCompleted`, leaving
   `activeStartup` stuck forever;
2. setup-time private identity resolution can still lose a plaintext byte buffer
   without wiping it;
3. preference persistence can still fail while the UI reports success;
4. transactional reset still has exact-restore and rollback correctness holes;
5. logs list and logs error are not generation-consistent;
6. config writes still have direct writer bypasses;
7. native status schema handling still has unsafe fallbacks;
8. network callback delivery failures can still disappear;
9. raw forwards mutation bypasses remain;
10. tests still include at least one wrong quarantine expectation.

The current code should not be treated as signed off until the P0 items in this spec
are implemented and verified.

---

## 2. Non-negotiable rules

### 2.1 Test the real invariant

Do not write tests that make broken behavior pass by sending more events, retrying
until success, or asserting the old unsafe behavior.

The following test pattern is forbidden for P0/P1 signoff:

```text
wait until success by repeatedly firing the same lifecycle/network event
```

The following behavior is forbidden:

```text
STOP fails
then START succeeds immediately
```

The correct behavior is:

```text
STOP fails
quarantine active
START blocked
STOP retry succeeds
quarantine clears
START allowed
```

### 2.2 No startup coroutine may disappear without completion

Every startup attempt that creates `activeStartup` must eventually submit a typed
startup completion to the coordinator, unless the entire service scope is cancelled.

This includes:

```text
policy blocked before native start
identity read failure
identity validation failure
config read/rewrite failure
preference read failure
local address resolution failure
native start failure
verification failure
unexpected exception
```

Returning from a startup coroutine without `StartupCompleted` is a state-machine bug.

### 2.3 Plaintext identity buffers must have one owner

A plaintext private identity byte array must either:

1. be explicitly transferred to the next owner; or
2. be wiped in a `finally` block.

The following is forbidden when plaintext bytes may already exist:

```kotlin
runCatching {
    val bytes = readPrivateIdentityPlaintext()
    ...
}.getOrNull()
```

### 2.4 Persistence methods returning `Result` must be folded

Do not ignore:

```kotlin
Result<Unit>
```

from:

```text
savePreferences()
resetForwards()
writeConfigAtomically()
repository save/reset methods
```

If an operation can fail, the caller must either return the failure, show it visibly,
or convert it into a typed outcome.

### 2.5 “Transactional” means exact restore where possible

Transactional reset must capture prior state before mutation. On failure, every
successfully mutated stage must be restored from the captured snapshot.

If a rollback stage fails, the overall reset result must be failure/partial recovery,
not success.

### 2.6 Critical coroutine boundaries use explicit try/catch

For lifecycle, network, startup, and status-poll boundaries, use:

```kotlin
try {
    ...
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    ...
}
```

Do not use `runCatching` in these paths.

### 2.7 No quiet compatibility fallback for malformed native status

A missing or unknown native status field that is required for correctness must produce
a schema error. Do not silently map missing values to plausible defaults such as
`TunnelMode.Offer`.

---

## 3. Priority model

- **P0** — Release-blocking correctness/security/state-integrity issue.
- **P1** — Required before final signoff, but may be implemented after P0.
- **P2** — Quality gate, CI, cleanup, or future-proofing.

Do not mark any task complete without a focused test or a clear reason why it cannot
be tested.

---

# 4. P0 requirements

## P0-001 — Make startup completion total

### Problem

`activeStartup` can be set, then `doStartOffer()` can return early after
`StartupAborted`, especially for policy-blocked startup or preparation failures.
That leaves startup ownership stuck forever.

### Required invariant

Once `activeStartup` is created, exactly one of the following must happen:

```text
StartupCompleted(generation, completion)
service scope cancelled
stale generation ignored by coordinator
```

The worker must not simply return.

### Required startup completion variants

Use the existing startup result type. Do not create a parallel hierarchy.

The existing type should cover at least:

```kotlin
sealed interface StartupCompletion {
    data object VerifiedSuccess : StartupCompletion

    data class NativeStartFailure(
        val error: TunnelError,
    ) : StartupCompletion

    data class VerificationFailure(
        val error: TunnelError,
    ) : StartupCompletion

    data class PolicyBlocked(
        val reason: String,
    ) : StartupCompletion

    data class Aborted(
        val reason: String,
    ) : StartupCompletion

    data class UnexpectedFailure(
        val error: Throwable,
    ) : StartupCompletion
}
```

If the project currently uses `StartOutcome`, extend that existing type instead of
adding `StartupCompletion`.

### Required structure

The startup worker should look like this shape:

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

The total startup attempt:

```kotlin
private suspend fun performStartupAttempt(
    generation: Long,
): StartupCompletion {
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
        StartupCompletion.PolicyBlocked(
            reason = blocked.message
                ?: "Blocked by network policy",
        )
    } catch (aborted: StartupAborted) {
        StartupCompletion.Aborted(
            reason = aborted.message
                ?: "Startup aborted",
        )
    } catch (error: Throwable) {
        StartupCompletion.UnexpectedFailure(
            error = error,
        )
    }
}
```

Policy-blocked startup should not set policy state and then escape. Prefer returning
a typed `PolicyBlocked` completion and letting the coordinator handle:

```text
activeStartup = null
pausedByPolicy = true
repository.setPolicyBlocked(...)
pending retry handling
```

### Coordinator handling

When completion arrives:

```kotlin
private suspend fun handleStartupCompleted(
    command: LifecycleCommand.StartupCompleted,
) {
    if (lifecycleGeneration.get() != command.generation) {
        return
    }

    activeStartup = null

    when (val completion = command.completion) {
        StartupCompletion.VerifiedSuccess ->
            handleVerifiedStartupSuccess(command.generation)

        is StartupCompletion.PolicyBlocked ->
            handleStartupPolicyBlocked(
                generation = command.generation,
                reason = completion.reason,
            )

        is StartupCompletion.NativeStartFailure ->
            handleNativeStartFailure(
                generation = command.generation,
                error = completion.error,
            )

        is StartupCompletion.VerificationFailure ->
            handleVerificationFailure(
                generation = command.generation,
                error = completion.error,
            )

        is StartupCompletion.Aborted ->
            handleStartupAborted(
                generation = command.generation,
                reason = completion.reason,
            )

        is StartupCompletion.UnexpectedFailure ->
            handleUnexpectedStartupFailure(
                generation = command.generation,
                error = completion.error,
            )
    }

    submitPendingPolicyRetryIfValid(
        completedGeneration = command.generation,
    )
}
```

### Required tests

1. policy-blocked initial start submits completion and clears `activeStartup`;
2. identity-read failure submits completion and clears `activeStartup`;
3. config rewrite failure submits completion and clears `activeStartup`;
4. unexpected preparation exception becomes visible completion;
5. one later `PolicyAllowed` event resumes an initially policy-blocked startup;
6. no third/repeated network event is needed.

---

## P0-002 — Fix setup-time private identity zeroization

### Problem

The setup save flow still uses `runCatching(...).getOrNull()` after reading plaintext
private identity bytes.

### Required invariant

Every path after `readPrivateIdentityPlaintext()` either transfers ownership or wipes
the buffer.

### Required implementation pattern

Replace the stored identity resolver with explicit ownership transfer:

```kotlin
private suspend fun resolveStoredIdentity(): ResolvedIdentity? =
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
                derivePeerId(publicIdentity)

            transferred = true

            ResolvedIdentity(
                privateIdentity = bytes,
                publicIdentity = publicIdentity,
                peerId = peerId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            if (!transferred) {
                bytes.fill(0)
            }
        }
    }
```

If the current function returns `Triple<ByteArray, String, String>`, replace it with a
small named data class:

```kotlin
private data class ResolvedIdentity(
    val privateIdentity: ByteArray,
    val publicIdentity: String,
    val peerId: String,
)
```

The caller that receives `privateIdentity` must wipe it in `finally` after the last use.

### Required tests

Use a fake identity repository that exposes the exact byte array returned to
production code.

Required sentinel tests:

1. private validation throws after bytes are read;
2. private validation returns invalid after bytes are read;
3. public identity read throws after bytes are read;
4. peer ID derivation throws after bytes are read;
5. success transfers ownership and the final owner wipes.

---

## P0-003 — Surface every preference-write failure

### Problem

Some ViewModels call `savePreferences()` and ignore `Result<Unit>`, then report success.

### Required behavior

Every preference save result must be folded.

### Network Policy pattern

```kotlin
private fun savePreferences(
    updated: AndroidAppPreferences,
) {
    viewModelScope.launch {
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
    }
}
```

### Setup callback type

If a setup callback persists preferences, its type must be failure-aware:

```kotlin
private val persistPreferences:
    suspend (AndroidAppPreferences) -> Result<Unit>
```

not:

```kotlin
suspend (AndroidAppPreferences) -> Unit
```

### Required tests

1. network policy save success shows success;
2. network policy save failure shows error and no success;
3. setup default persistence failure blocks success;
4. setup injected persistence failure blocks success;
5. cancellation propagates.

---

## P0-004 — Repair transactional reset exact restore

### Problem

Transactional reset now captures state, but rollback still has exact-restore holes.

### Required behavior

The reset must stop on first failed stage. It should not keep mutating additional
stages after a known failure.

### Required reset sequence

```kotlin
private suspend fun resetAll(): ResetResult {
    val snapshot =
        captureSnapshot()
            .getOrElse { error ->
                return ResetResult.Failed(
                    failedStage = ResetStage.Snapshot,
                    reason = redact(error),
                    rollback = emptyList(),
                )
            }

    val mutated =
        mutableListOf<ResetStage>()

    for (stage in resetOrder) {
        val result =
            resetStage(stage)

        if (result.isFailure) {
            val rollback =
                rollback(
                    snapshot = snapshot,
                    mutatedStages = mutated,
                )

            return ResetResult.Failed(
                failedStage = stage,
                reason = redact(result.exceptionOrNull()),
                rollback = rollback,
            )
        }

        mutated += stage
    }

    return ResetResult.Success(...)
}
```

### Required exact restore semantics

If prior config file did not exist:

```text
rollback must delete the config file created by reset
```

If prior config file existed:

```text
rollback must restore exact previous bytes/text
```

If prior forwards list was empty:

```text
rollback must persist and publish empty list
```

Do not skip restore just because the list is empty.

### Avoid raw forwards save bypass

Do not use a public raw `ForwardsRepository.save()` for rollback. Use a dedicated
internal repository restore method that is safe, mutex-protected, and not exposed as a
general mutation bypass.

Example:

```kotlin
internal suspend fun restoreForTransactionalReset(
    forwards: List<ForwardConfig>,
): Result<Unit> =
    mutex.withLock {
        store.saveForwards(forwards)
            .getOrElse {
                return@withLock Result.failure(it)
            }

        revision += 1L
        _forwards.value = forwards
        _loadError.value = null

        Result.success(Unit)
    }
```

### Required tests

1. config absent before reset, later stage fails, rollback deletes config;
2. config present before reset, later stage fails, rollback restores exact content;
3. setup input restored exactly;
4. prior empty forwards restored and persisted;
5. prior non-empty forwards restored and persisted;
6. reset stops after first failed stage;
7. rollback failure produces failure/partial-recovery result, not success.

---

## P0-005 — Remove restart-after-failed-stop test expectation

### Problem

At least one test still expects immediate restart after failed verified STOP.

### Required behavior

Rewrite the test to match quarantine:

```text
STOP fails
quarantine active
START blocked
STOP retry succeeds
quarantine clears
START allowed
```

### Required test assertions

1. failed STOP sets `nativeRuntimeUncertain`;
2. StartOffer while quarantined does not call native start;
3. explicit STOP while quarantined is accepted;
4. successful STOP clears quarantine;
5. StartOffer after verified STOP calls native start.

---

# 5. P1 requirements

## P1-001 — Remove raw forwards mutation bypass

### Problem

`ForwardsRepository.save()` remains and bypasses the receipt/mutation model.

### Required behavior

Delete public raw `save()` unless there is a proven caller that cannot use a safer API.
Transactional reset should use a scoped internal restore function, not a public raw
save.

### Required search

Search for:

```text
ForwardsRepository.save(
.save(forwards
saveIfRevisionMatches
snapshot()
ForwardsConfigStore.saveForwards
```

### Required acceptance

1. no public raw forwards save bypass remains;
2. every UI/user mutation uses receipt-based API;
3. transactional reset uses a scoped restore API;
4. `loadError` blocks user mutations;
5. restore API is not reachable by ViewModels.

---

## P1-002 — Fix config writer serialization completely

### Problem

Some config writes still use `configFile.writeText(...)` directly, and atomic write
fallback/cleanup is incomplete.

### Required behavior

All production `config.toml` writes must go through one writer mutex and atomic writer.

### Required atomic writer

```kotlin
private suspend fun writeConfigAtomically(
    content: String,
): Result<Unit> =
    writeMutex.withLock {
        val parent =
            configFile.parentFile

        val temp =
            File.createTempFile(
                "config-",
                ".tmp",
                parent,
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
            Files.deleteIfExists(temp.toPath())
        }
    }
```

### Required audit

Replace production direct writes in:

```text
ensureDefaultConfig()
writeConfig()
any setup/config writer that touches config.toml
```

If `writeConfig()` is test-only, make that explicit or restrict its visibility.

### Required tests

1. overlapping config writers produce one complete final file;
2. unsupported atomic move fallback succeeds;
3. temp file deleted after failure;
4. no production direct `configFile.writeText(...)` remains.

---

## P1-003 — Fix native status schema fallback and terminal peer cleanup

### Required changes

1. `resolveNativeMode(null)` must produce schema error, not `TunnelMode.Offer`.
2. unknown runtime state must include `native_status_schema_error` with redacted raw value.
3. terminal states must clear active peer/session fields.

### Required helper

```kotlin
private fun TunnelStatus.withoutActivePeer():
    TunnelStatus =
    copy(
        remotePeerId = null,
        activeSessionCount = 0,
        mqttConnected = false,
    )
```

Use for:

```text
Stopped
Error
PausedMeteredBlocked
NoNetwork
ConfigInvalid
```

### Required tests

1. null mode fails verification;
2. future mode fails verification;
3. unknown native runtime state is diagnosed;
4. policy-blocked local status clears remote peer;
5. local error clears remote peer;
6. config invalid clears remote peer.

---

## P1-004 — Make logs list and logs error generation-consistent

### Required model

Repository should return a value. ViewModel should own generation.

```kotlin
data class LogsFetchResult(
    val logs: List<LogEntry>,
    val error: TunnelError?,
)
```

Repository:

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
                message = SensitiveDataRedactor
                    .redactText(
                        error.message
                            ?: "Log refresh failed",
                    ),
            ),
        )
    }
}
```

ViewModel:

```kotlin
fun refresh() {
    val generation =
        ++refreshGeneration

    viewModelScope.launch {
        val result =
            withContext(ioDispatcher) {
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

### Required tests

1. older failure cannot set error after newer success;
2. older success cannot clear newer failure;
3. older success cannot replace newer list;
4. UI displays current error.

---

## P1-005 — Make network event delivery observable or non-lossy

### Problem

`callbackFlow.trySend(...)` failures are ignored.

### Required options

Preferred:

```text
collect a StateFlow of current network policy status instead of using lossy callbackFlow
```

Acceptable for this pass:

```kotlin
val sendResult =
    trySend(current)

if (sendResult.isFailure) {
    reporter.publishError(
        code = "network_policy_event_delivery_failed",
        message = "Network policy event could not be delivered",
    )
}
```

If `NetworkPolicyManager` does not currently have a reporter, inject a small reporter
interface rather than silently ignoring failure.

### Required tests

1. failed delivery publishes diagnostic;
2. cancellation closes cleanly;
3. service can still resynchronize from current policy state.

---

## P1-006 — Finish pending retry invalidation

### Required helper

```kotlin
private fun invalidatePendingPolicyRetry() {
    pendingPolicyResumeGeneration.set(null)
}
```

Call it on:

```text
Stop
Pause
PolicyBlocked
StartOffer
AllowMeteredSession
Destroy
```

Retry handler must include:

```kotlin
if (!pausedByPolicy.get()) {
    return
}
```

### Required tests

1. pending retry then STOP: no restart;
2. pending retry then PAUSE: no restart;
3. pending retry then PolicyBlocked: no restart;
4. pending retry then new StartOffer: old retry ignored;
5. valid retry runs once.

---

## P1-007 — Resolve false typed `StartOutcome` claim

### Decision

Use Option B for this pass.

Do not implement a typed JNI bridge now. Remove or correct comments that claim the
typed result exists through JNI.

Allowed wording:

```kotlin
/**
 * Android-side typed classification of native start results.
 *
 * The JNI bridge still exposes primitive success/failure. This type is used above
 * the bridge boundary to route startup completion through the lifecycle coordinator.
 */
```

Forbidden wording:

```text
Typed start outcome for JNI operations
Moves classification closer to JNI boundary
```

unless the bridge actually returns `StartOutcome`.

---

# 6. P2 requirements

## P2-001 — Keep CI signoff evidence honest

The GitHub workflow content is improved, but final signoff still requires:

```text
final production SHA
fresh workflow run ID/URL
workflow head SHA
exact PASS/FAIL/NOT RUN record
```

Do not check final signoff boxes without these.

---

## 7. Required validation

### Android focused lifecycle

```bash
cd android

./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundServiceStopFailureTest' \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundService*' \
  --tests 'com.phillipchin.webrtctunnel.data.TunnelRepositoryTest' \
  --rerun-tasks
```

Run three fresh times.

### Android identity/setup

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.SetupSaveControllerTest' \
  --tests 'com.phillipchin.webrtctunnel.security.IdentityRepositoryTest' \
  --rerun-tasks
```

### Forwards/reset

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.data.ForwardsRepositoryTest' \
  --tests 'com.phillipchin.webrtctunnel.data.TransactionalResetCoordinatorTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.SettingsViewModelTest' \
  --rerun-tasks
```

### Logs/preferences/network policy

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.LogsViewModelTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.NetworkPolicyViewModelTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.SettingsViewModelTest' \
  --rerun-tasks
```

### Full Android

```bash
./gradlew --no-daemon detekt
./gradlew --no-daemon ktlintCheck
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
./gradlew --no-daemon check
```

---

## 8. Final signoff checklist

Do not sign off until all are true:

```text
P0-001 every startup attempt submits completion or is cancelled
P0-002 setup stored-identity path wipes plaintext on every failure
P0-003 every preference save failure is visible
P0-004 reset rollback restores exact prior state where rollback succeeds
P0-005 failed STOP quarantine test rewritten correctly
P1-001 raw forwards save bypass removed
P1-002 all config writers serialized through atomic writer
P1-003 null/unknown native schema values fail visibly
P1-004 logs and logsError share generation ownership
P1-005 network event delivery failure visible or impossible
P1-006 pending retry invalidation explicit and complete
P1-007 StartOutcome comment/claim corrected
P2-001 final SHA and fresh workflow recorded
```
