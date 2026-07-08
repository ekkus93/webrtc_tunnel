# WebRTC Tunnel Lifecycle Coordinator, Verified-Start Cleanup, and Rollback-Integrity Release-Signoff Specification

## 0. Document purpose

This specification applies to:

```text
webrtc_tunnel-master2607072301.zip
```

It is the next corrective release-signoff pass after review of:

```text
WEBRTC_TUNNEL_ORDERED_LIFECYCLE_VERIFIED_START_POLICY_SIGNOFF_TODO(1).md
```

The latest snapshot has materially improved:

- external Android lifecycle commands mostly enter a bounded command queue;
- `TunnelRepository.start()` verifies runtime state after JNI start;
- `TunnelRepository.stop()` verifies final `Stopped`;
- log retrieval errors no longer rewrite tunnel lifecycle state;
- temporary metered allowance is atomic;
- forwards rollback has revision-related protection;
- production test-only event channels remain out of main code;
- several previous timing-based test waits were removed.

Those changes are preserved.

This pass fixes the remaining release blockers and false-success paths:

1. verified-start failure can leave a native runtime alive and unowned;
2. pending policy retry bypasses the ordered lifecycle processor;
3. one legitimate later `PolicyAllowed` event can still be lost;
4. stale pending retry can survive later `Pause`, `Stop`, `PolicyBlocked`, or `StartOffer`;
5. the lifecycle command processor can die silently;
6. `onDestroy()` can race queued commands because the processor remains active;
7. preference-read failure is silently interpreted as `resumeOnUnmetered = false`;
8. the lifecycle sequence counter is dead instrumentation;
9. unsupported `ACTION_START_ANSWER` bypasses ordered lifecycle handling;
10. forwards rollback can still overwrite an intervening mutation;
11. successful forwards refresh does not invalidate old rollback revisions;
12. `loadError` does not centrally block every mutation path;
13. a raw repository save API can bypass rollback/load-error invariants;
14. unknown native mode still becomes plausible `Offer`;
15. unknown listen state lacks a specific schema diagnosis;
16. terminal peer clearing is incomplete;
17. verified-stop state is not consistently updated after pause/policy stop;
18. temporary metered allowance can survive a failed run;
19. duplicate-start prevention uses a weaker state predicate;
20. stale-poll and exactly-one-stop tests still do not prove the exact boundaries required;
21. several checked TODO acceptance criteria have no focused regression test;
22. the final CI record for this pass must be explicit and tied to the actual final implementation SHA.

The central rule is:

> Every lifecycle-changing native operation must have one coordinator-owned path, one explicit completion result, and one deterministic test boundary.

---

# 1. Non-negotiable invariants

## 1.1 No lifecycle-changing call may bypass the coordinator

The following operations must be coordinator-owned:

```text
start offer
resume after policy pause
pause
policy pause
stop
temporary metered start
cleanup after unverified start
destroy-time fallback stop
```

Forbidden:

```kotlin
serviceScope.launch {
    offer.resume()
}
```

from startup completion or any other internal callback.

Forbidden:

```text
startup worker decides independently to call repository.stop()
```

Required:

```text
all lifecycle intentions
        ↓
one coordinator
        ↓
one ordering rule
```

---

## 1.2 Verified-start failure owns cleanup

A start can fail after JNI success:

```text
JNI start succeeds
        ↓
runtime may exist
        ↓
status read fails
or
status says Error/Stopped
        ↓
StartStatusVerificationException
```

This is not an ordinary "nothing started" failure.

Required:

```text
current generation still owns startup
        ↓
coordinator performs verified stop cleanup
        ↓
publish original verification failure

cleanup also fails
        ↓
publish both failures
```

A later `Pause`/`Stop`/`PolicyBlocked` that superseded the startup owns cleanup instead.

---

## 1.3 Pending policy retry is an intention, not a direct method call

Required:

```text
PolicyAllowed arrives during active startup
        ↓
record one pending retry intention

startup attempt fully completes
        ↓
if pending retry still valid
        ↓
coordinator begins exactly one retry
```

Forbidden:

```text
consume pending flag
        ↓
launch direct resume coroutine
```

---

## 1.4 Later lifecycle intent supersedes stale pending retry

At minimum, these commands invalidate a prior pending policy retry:

```text
Pause
Stop
PolicyBlocked
StartOffer
AllowMeteredSession
```

The exact rules must be explicit in one place.

No stale retry may restart the tunnel after a later user stop.

---

## 1.5 Command processor failure must be visible

If the lifecycle processor throws:

```text
service remains alive
processor dead
queue still accepting
```

is forbidden.

Required:

- keep the processor `Job`;
- report command failures explicitly;
- distinguish command-local failures from processor-fatal failures;
- stop accepting commands during destruction;
- join/cancel the processor before fallback native cleanup.

---

## 1.6 Dependency failure is not a user preference

Forbidden:

```kotlin
runCatching {
    preferences.first()
}.getOrNull()
    ?.resumeOnUnmetered == true
```

because it maps:

```text
read failure
```

to:

```text
user disabled auto-resume
```

Required:

```text
preference read failure
    → visible failure
    → no silent fallback
```

---

## 1.7 Rollback snapshot and mutation must be one atomic transaction

Forbidden:

```text
snapshot
unlock

other mutation

lock
perform my mutation
```

Required mutation receipt:

```text
under one repository mutex:
    capture before
    apply mutation
    persist
    increment revision
    return receipt
```

Rollback targets the exact committed revision in that receipt.

---

## 1.8 Successful repository refresh changes revision

Any operation that replaces the canonical in-memory forwards list must invalidate old rollback receipts.

Required:

```text
successful refresh
    → publish new list
    → increment revision
```

---

## 1.9 `loadError` blocks every mutation centrally

Forbidden:

```text
hasValidBaseline == true
loadError != null
mutation allowed
```

Required:

```text
loadError != null
    → upsert blocked
    → delete blocked
    → raw save blocked/removed
    → setup mutation blocked
    → details mutation blocked
```

---

## 1.10 Unknown native values fail explicitly

Forbidden:

```text
unknown mode → Offer
unknown listen state → generic Error without root cause
```

Required:

```text
unknown mode
    → ServiceState.Error
    → native_status_schema_error
    → explicit redacted reason

unknown listen state
    → ListenState.Error
    → explicit redacted reason
```

---

## 1.11 Tests prove exact boundaries

Required stale-poll order:

```text
StatusReadEntered
StatusReadReleased
StopEntered
```

Assertion:

```text
StatusReadReleased < StopEntered
```

Required exactly-one-stop proof:

```text
PauseCommandStarted
PauseCommandCompleted
```

After `PauseCommandCompleted`:

```text
stopCalls == 1
```

No elapsed-time absence inference.

---

# 2. Target architecture

## 2.1 Lifecycle coordinator responsibilities

The existing service may retain the queue and helper objects, but the following state must have one owner:

```text
lifecycle command queue
command processor Job
startupJob
lifecycle generation
pending policy retry
verified native stop state
temporary metered allowance intent
destroying/acceptingCommands state
```

A separate class is optional.

The minimum acceptable structure is one clearly delimited coordinator section in `TunnelForegroundService`.

Do not scatter these rules across unrelated coroutines.

---

## 2.2 Lifecycle commands

Use one command type:

```kotlin
private sealed interface LifecycleCommand {
    data object StartOffer :
        LifecycleCommand

    data object Pause :
        LifecycleCommand

    data object Resume :
        LifecycleCommand

    data object Stop :
        LifecycleCommand

    data object AllowMeteredSession :
        LifecycleCommand

    data class PolicyBlocked(
        val reason: String,
    ) : LifecycleCommand

    data object PolicyAllowed :
        LifecycleCommand

    data object RetryPolicyResume :
        LifecycleCommand

    data object UnsupportedStartAnswer :
        LifecycleCommand
}
```

`RetryPolicyResume` is internal. External callers do not submit it directly.

---

## 2.3 Envelope and monotonic sequence

The current sequence counter must either be removed or made real.

Preferred:

```kotlin
private data class LifecycleEnvelope(
    val sequence: Long,
    val command: LifecycleCommand,
)
```

Queue:

```kotlin
private val lifecycleCommands =
    Channel<LifecycleEnvelope>(
        capacity =
            LIFECYCLE_COMMAND_CAPACITY,
    )
```

Submission:

```kotlin
private fun submitLifecycleCommand(
    command: LifecycleCommand,
): Result<Long> {
    if (!acceptingLifecycleCommands.get()) {
        return Result.failure(
            IllegalStateException(
                "Lifecycle command processor is shutting down",
            ),
        )
    }

    val sequence =
        nextLifecycleSequence
            .getAndIncrement()

    val result =
        lifecycleCommands.trySend(
            LifecycleEnvelope(
                sequence = sequence,
                command = command,
            ),
        )

    return if (result.isSuccess) {
        Result.success(sequence)
    } else {
        Result.failure(
            IllegalStateException(
                "Unable to queue lifecycle command " +
                    "${command::class.simpleName}",
                result.exceptionOrNull(),
            ),
        )
    }
}
```

Every failed submission must be visible.

---

## 2.4 Processor job

Store it:

```kotlin
private var commandProcessorJob:
    Job? = null
```

Start:

```kotlin
commandProcessorJob =
    serviceScope.launch {
        processLifecycleCommands()
    }
```

Processor:

```kotlin
private suspend fun
    processLifecycleCommands() {
    var lastSequence = -1L

    for (envelope in lifecycleCommands) {
        check(
            envelope.sequence >
                lastSequence,
        ) {
            "Lifecycle command sequence regressed: " +
                "${envelope.sequence} <= $lastSequence"
        }

        lastSequence =
            envelope.sequence

        dispatchLifecycleCommand(
            envelope,
        )
    }
}
```

The sequence is now real and testable.

---

## 2.5 Command exception policy

Recommended:

```kotlin
private suspend fun
    dispatchLifecycleCommand(
    envelope: LifecycleEnvelope,
) {
    try {
        handleLifecycleCommand(
            envelope.command,
        )
    } catch (
        cancelled: CancellationException
    ) {
        throw cancelled
    } catch (error: Throwable) {
        reporter.publishError(
            message =
                SensitiveDataRedactor
                    .redactText(
                        error.message
                            ?: "Lifecycle command failed",
                    ),
            code =
                "lifecycle_command_failed",
        )
    }
}
```

If a particular invariant violation should be fatal, do not catch it as command-local. Document the fatal type and stop the service explicitly.

---

# 3. Verified-start cleanup

## 3.1 Distinguish native start failure from verification failure

```text
bridge start failed
    → ordinary start failure
    → no native runtime assumed

StartStatusVerificationException
    → native start succeeded
    → runtime may exist
    → cleanup required
```

Do not collapse these.

---

## 3.2 Coordinator-owned cleanup

Recommended result type:

```kotlin
private sealed interface StartupCompletion {
    data object VerifiedSuccess :
        StartupCompletion

    data class NativeStartFailure(
        val error: Throwable,
    ) : StartupCompletion

    data class VerificationFailure(
        val error:
            StartStatusVerificationException,
    ) : StartupCompletion
}
```

The startup worker returns the completion to lifecycle ownership.

Conceptually:

```kotlin
private suspend fun runOfferStart(
    startGeneration: Long,
): StartupCompletion {
    val result =
        repository.start(
            mode = TunnelMode.Offer,
            configPath = configPath,
            identityBytes = identityBytes,
        )

    return result.fold(
        onSuccess = {
            StartupCompletion.VerifiedSuccess
        },
        onFailure = { error ->
            if (
                error is
                    StartStatusVerificationException
            ) {
                StartupCompletion
                    .VerificationFailure(error)
            } else {
                StartupCompletion
                    .NativeStartFailure(error)
            }
        },
    )
}
```

The coordinator handles the result.

---

## 3.3 Cleanup rule

```kotlin
private suspend fun
    handleStartupCompletion(
    generation: Long,
    completion: StartupCompletion,
) {
    if (!isCurrentGeneration(generation)) {
        return
    }

    when (completion) {
        StartupCompletion.VerifiedSuccess -> {
            startupJob = null
            pausedByPolicy.set(false)
            nativeStopVerified.set(false)
            maybeConsumePendingPolicyRetryAfterSuccess()
        }

        is StartupCompletion.NativeStartFailure -> {
            startupJob = null
            handleOrdinaryStartFailure(
                completion.error,
            )
            maybeConsumePendingPolicyRetryAfterFailure()
        }

        is StartupCompletion.VerificationFailure -> {
            startupJob = null
            cleanupUnverifiedStart(
                completion.error,
            )
        }
    }
}
```

The exact code may differ, but ownership must remain equivalent.

---

## 3.4 Cleanup function

```kotlin
private suspend fun
    cleanupUnverifiedStart(
    originalError:
        StartStatusVerificationException,
) {
    reporter.stopStatusPollingAndJoin()

    repository.stop().fold(
        onSuccess = {
            nativeStopVerified.set(true)

            reporter.publishError(
                message =
                    originalError.message
                        ?: "Native startup could not be verified",
                code =
                    "start_status_verification_failed",
            )
        },
        onFailure = { cleanupError ->
            nativeStopVerified.set(false)

            reporter.publishError(
                message =
                    buildString {
                        append(
                            originalError.message
                                ?: "Native startup could not be verified",
                        )
                        append(
                            ". Cleanup also failed: ",
                        )
                        append(
                            SensitiveDataRedactor
                                .redactText(
                                    cleanupError.message
                                        ?: "unknown cleanup failure",
                                ),
                        )
                    },
                code =
                    "start_verification_cleanup_failed",
            )
        },
    )
}
```

Do not start a policy retry before this cleanup ownership is resolved.

---

# 4. Pending policy retry

## 4.1 Coordinator-owned state

Use either:

```kotlin
private var pendingPolicyResume =
    false
```

if only the processor touches it, or atomic state if startup completion still occurs off-processor.

Preferred end state:

```text
only coordinator reads/writes pendingPolicyResume
```

Then use a plain Boolean inside the coordinator.

---

## 4.2 Commands that clear pending retry

Centralize:

```kotlin
private fun clearPendingPolicyResume() {
    pendingPolicyResume = false
}
```

Call when handling:

```text
Pause
Stop
StartOffer
PolicyBlocked
AllowMeteredSession
```

Do not rely on incidental flag changes elsewhere.

---

## 4.3 PolicyAllowed

```kotlin
private suspend fun
    handlePolicyAllowed() {
    if (!pausedByPolicy.get()) {
        pendingPolicyResume = false
        return
    }

    val preferences =
        readPreferencesOrReportFailure()
            ?: return

    if (!preferences.resumeOnUnmetered) {
        pendingPolicyResume = false
        return
    }

    if (startupJob?.isActive == true) {
        pendingPolicyResume = true
        return
    }

    beginPolicyResumeAttempt()
}
```

---

## 4.4 Preference read must fail visibly

```kotlin
private suspend fun
    readPreferencesOrReportFailure():
    TunnelPreferences? {
    return runCatching {
        configRepository
            .preferences
            .first()
    }.getOrElse { error ->
        reporter.publishError(
            message =
                "Unable to read tunnel preferences: " +
                    SensitiveDataRedactor
                        .redactText(
                            error.message
                                ?: "unknown preference error",
                        ),
            code =
                "preferences_read_failed",
        )

        null
    }
}
```

Do not convert failure to `false`.

---

## 4.5 Retry only after startup completion

If `PolicyAllowed` arrives while startup is active:

```text
pendingPolicyResume = true
```

When startup finishes:

```text
clear startupJob ownership first
        ↓
check whether pending retry is still valid
        ↓
submit RetryPolicyResume
```

Recommended:

```kotlin
private fun
    submitPendingPolicyRetryIfNeeded() {
    if (!pendingPolicyResume) {
        return
    }

    pendingPolicyResume = false

    submitLifecycleCommand(
        LifecycleCommand
            .RetryPolicyResume,
    ).onFailure { error ->
        reporter.publishError(
            message =
                error.message
                    ?: "Unable to queue policy retry",
            code =
                "lifecycle_command_queue_failed",
        )
    }
}
```

No direct `offer.resume()` coroutine.

---

# 5. Service destruction

## 5.1 Stop accepting commands first

```kotlin
private val acceptingLifecycleCommands =
    AtomicBoolean(true)
```

At destruction start:

```kotlin
acceptingLifecycleCommands.set(false)
```

---

## 5.2 Close lifecycle input

```kotlin
lifecycleCommands.close()
```

A failed post-close submission must be visible if it can still originate from a live caller.

---

## 5.3 Join order

Required:

```text
stop accepting commands
        ↓
close lifecycle channel
        ↓
cancel/join network monitor
        ↓
cancel/join command processor
        ↓
cancel/join startup
        ↓
stop/join status polling
        ↓
verified fallback stop if needed
```

Suggested helper:

```kotlin
private suspend fun
    stopCommandProcessorAndJoin() {
    val job = commandProcessorJob
    commandProcessorJob = null
    job?.cancelAndJoin()
}
```

---

## 5.4 Verified-stop state

Set:

```text
verified start begins
    → nativeStopVerified = false

verified stop succeeds from:
    Pause
    PolicyBlocked
    Stop
    unverified-start cleanup
    destroy fallback
    → nativeStopVerified = true
```

Do not update the flag merely because JNI returned success.

---

# 6. Forward mutation receipts

## 6.1 Receipt type

```kotlin
data class ForwardsMutationReceipt(
    val before:
        List<ForwardConfig>,
    val after:
        List<ForwardConfig>,
    val committedRevision: Long,
)
```

---

## 6.2 Atomic upsert/delete mutation

Example:

```kotlin
suspend fun upsertWithReceipt(
    forward: ForwardConfig,
): Result<ForwardsMutationReceipt> =
    mutex.withLock {
        ensureMutationAllowedLocked()
            .getOrElse {
                return@withLock
                    Result.failure(it)
            }

        val before =
            _forwards.value

        val after =
            before
                .filterNot {
                    it.id == forward.id
                } +
                forward

        runCatching {
            store.saveForwards(after)

            _forwards.value = after
            revision += 1

            ForwardsMutationReceipt(
                before = before,
                after = after,
                committedRevision =
                    revision,
            )
        }
    }
```

Delete should follow the same pattern.

---

## 6.3 Conditional rollback

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
            return@withLock Result.failure(
                ForwardsRevisionMismatchException(
                    expected =
                        receipt.committedRevision,
                    actual =
                        revision,
                ),
            )
        }

        runCatching {
            store.saveForwards(
                receipt.before,
            )

            _forwards.value =
                receipt.before

            revision += 1
        }
    }
```

---

## 6.4 Refresh invalidates old receipts

Successful refresh:

```kotlin
mutex.withLock {
    val loaded =
        store.loadForwardsResult()
            .getOrThrow()

    _forwards.value = loaded
    _loadError.value = null
    hasValidBaseline = true
    revision += 1
}
```

Every canonical state replacement advances revision.

---

# 7. Load-error mutation block

## 7.1 Central guard

```kotlin
private fun
    ensureMutationAllowedLocked():
    Result<Unit> {
    val loadFailure =
        _loadError.value

    if (loadFailure != null) {
        return Result.failure(
            ForwardsMutationBlockedException(
                "Saved forwards could not be loaded. " +
                    "Fix the problem and retry before editing.",
            ),
        )
    }

    return Result.success(Unit)
}
```

Use it in every mutating API.

---

## 7.2 Remove unsafe raw save

Search:

```bash
rg -n '\.save\(' \
  android/app/src/main/java/com/phillipchin/webrtctunnel
```

If `ForwardsRepository.save()` has no legitimate production caller after mutation receipts are implemented:

```text
delete it
```

Otherwise it must:

- reject while `loadError != null`;
- persist under mutex;
- increment revision;
- return an appropriate receipt or explicit non-rollbackable result.

Do not retain a convenience footgun.

---

# 8. Strict status schema

## 8.1 Mode mapping

Required:

```kotlin
private fun parseNativeMode(
    rawMode: String?,
    previous: TunnelMode,
): NativeModeResult =
    when (rawMode) {
        "offer" ->
            NativeModeResult.Valid(
                TunnelMode.Offer,
            )

        "answer" ->
            NativeModeResult.Valid(
                TunnelMode.Answer,
            )

        else ->
            NativeModeResult.Invalid(
                fallbackMode = previous,
                rawValue = rawMode,
            )
    }
```

Unknown mode:

```text
retain previous enum only because model requires one
serviceState = Error
lastError.code = native_status_schema_error
```

---

## 8.2 Listen-state mapping

Unknown value:

```text
ListenState.Error
+
explicit error reason
```

Do not rely only on enum mapping.

---

# 9. Remote-peer truthfulness

Clear `remotePeerId` when state is:

```text
Stopped
Error
PausedMeteredBlocked
NoNetwork
```

Centralize:

```kotlin
private fun shouldClearActivePeer(
    state: ServiceState,
): Boolean =
    when (state) {
        ServiceState.Stopped,
        ServiceState.Error,
        ServiceState.PausedMeteredBlocked,
        ServiceState.NoNetwork ->
            true

        else ->
            false
    }
```

Use it in every status/policy transition.

---

# 10. Metered allowance lifetime

Temporary allowance means:

```text
this attempted run
```

Clear when:

```text
startup fails
pause succeeds
policy pause succeeds
stop succeeds
service destruction completes
```

Do not carry it into a later unrelated start.

---

# 11. Duplicate-start predicate

Use:

```text
isTunnelActiveOrStarting()
```

for duplicate-start prevention unless there is a documented reason not to.

Do not allow another start merely because the state is transitional rather than fully running.

---

# 12. Test architecture

## 12.1 Lifecycle command test events

Test-only:

```kotlin
sealed interface FakeLifecycleEvent {
    data class CommandStarted(
        val sequence: Long,
        val name: String,
    ) : FakeLifecycleEvent

    data class CommandCompleted(
        val sequence: Long,
        val name: String,
    ) : FakeLifecycleEvent

    data object StatusReadEntered :
        FakeLifecycleEvent

    data object StatusReadReleased :
        FakeLifecycleEvent

    data class StopEntered(
        val call: Int,
    ) : FakeLifecycleEvent
}
```

---

## 12.2 Stale-poll proof

Required:

```text
StatusReadEntered
StatusReadReleased
StopEntered
```

Assert:

```kotlin
assertTrue(
    releasedIndex <
        stopEnteredIndex,
)
```

Not merely:

```text
entered < stop
```

---

## 12.3 Exactly-one-stop proof

Await:

```text
Pause CommandCompleted
```

Then:

```kotlin
assertEquals(
    1,
    bridge.stopCalls,
)
```

No settle timeout.

---

## 12.4 One-event retry proof

Exactly:

```text
policy pause
PolicyAllowed #1
resume attempt fails

PolicyAllowed #2 arrives while first attempt is completing

no PolicyAllowed #3

retry occurs exactly once
retry succeeds
```

Do not fire repeated connectivity events in a polling loop.

---

# 13. Final CI/signoff record

The final TODO must contain:

```text
final production SHA:
docs-only child SHA, if any:
workflow run:
workflow head SHA:

Android focused:
Android full:
Lint:
Linux workspace:
macOS workspace:
Linux signal lifecycle:
macOS signal lifecycle:
Debian package smoke:
launchd plist validation:
launchd install-layout smoke:
```

Use only:

```text
PASS
FAIL
NOT RUN: exact reason
```

Do not mark final CI complete without the actual record.

---

# 14. Scope boundaries

Do not:

- redesign Rust signaling or WebRTC architecture;
- change cryptographic identity format;
- change authorized peer semantics;
- add `sd_notify`;
- add a generic enterprise actor framework;
- add unbounded queues;
- add hidden retry loops;
- add sleep-based tests;
- preserve unused dangerous convenience APIs merely for compatibility;
- mark TODO boxes complete without code/test evidence.

---

# 15. Completion definition

Release signoff requires:

```text
verified-start failure performs one coordinator-owned cleanup
cleanup failure preserves both errors
no direct lifecycle-changing call bypasses coordinator
pending retry waits until startup fully completes
later Stop/Pause/PolicyBlocked clears stale retry
command processor failure is visible
command processor is shut down before destroy fallback cleanup
preference read failure is visible
sequence instrumentation is real or removed
unsupported answer action has ordered behavior
forward mutation returns atomic receipt
refresh increments revision
loadError blocks all mutation
raw save footgun removed or hardened
unknown mode is explicit schema error
unknown listen state has explicit diagnosis
all terminal states clear active peer
verified-stop flag updated after every verified stop
temporary metered allowance ends with attempted run
duplicate-start uses active-or-starting predicate
tests prove exact event boundaries
final SHA + fresh CI run are recorded
```
