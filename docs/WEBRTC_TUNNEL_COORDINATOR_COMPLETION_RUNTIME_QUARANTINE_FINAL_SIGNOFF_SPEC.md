# WebRTC Tunnel Coordinator Completion, Runtime Quarantine, and Final State-Integrity Signoff Specification

## 0. Purpose

This specification applies to:

```text
webrtc_tunnel-master_2607080811.zip
```

It is the next corrective release-signoff pass after the latest code review.

The latest snapshot made real progress:

- startup results are classified into verified success, native-start failure, and verification failure;
- verification failure now attempts cleanup;
- cleanup failure preserves both immediate errors;
- direct `offer.resume()` was replaced with a queued `RetryPolicyResume`;
- startup failure clears temporary metered allowance;
- focused startup-cleanup tests were added.

Preserve those improvements.

This pass fixes the remaining blockers:

1. startup completion still makes lifecycle decisions from the startup coroutine;
2. retry commands carry no generation/token and can survive a later STOP;
3. retry can be queued while the old startup job is still active and silently no-op;
4. cleanup failure does not quarantine uncertain native runtime state;
5. failed verified STOP still destroys the foreground controller;
6. command processor, network monitor, startup worker, and status poll lack controlled failure boundaries;
7. temporary metered allowance is cleared on successful startup;
8. decrypted identity buffers are not wiped on every preparation failure;
9. start-cleanup failure history is not sticky;
10. forwards rollback can erase intervening mutations;
11. successful forwards refresh does not invalidate rollback revisions;
12. current `loadError` does not centrally block all mutations;
13. settings reset bypasses the shared forwards repository;
14. config writes use a fixed temp path without one shared write lock;
15. unknown native mode can become valid `Offer`;
16. peer identity can remain visible in policy-terminal states;
17. duplicate starts are allowed in transitional states;
18. initially policy-blocked startup does not become auto-resumable;
19. overlapping log refreshes can reorder;
20. some test boundaries still prove the wrong event.

The central architectural rule is:

> Startup workers perform work. The lifecycle coordinator decides what the result means.

---

# 1. Non-negotiable invariants

## 1.1 Startup workers do not own lifecycle follow-up

A startup worker may:

```text
prepare
call repository.start()
classify the result
report StartupCompleted
```

A startup worker may not:

```text
retry
stop
pause
decide whether a stale result still owns cleanup
publish final lifecycle success
```

Required flow:

```text
startup worker
    -> StartupCompleted(generation, completion)
    -> lifecycle coordinator
    -> success / cleanup / retry / stale rejection
```

## 1.2 Retry intentions are generation-bound

Required command:

```kotlin
data class RetryPolicyResume(
    val expectedGeneration: Long,
) : LifecycleCommand
```

If current generation differs, the retry is stale and must be discarded.

At minimum these invalidate older retries:

```text
Stop
Pause
PolicyBlocked
StartOffer
AllowMeteredSession
Destroy
```

## 1.3 Retry begins only after old startup ownership is cleared

Forbidden:

```text
startup failure handler queues retry
while startupJob is still active
```

Required:

```text
startup work finishes
StartupCompleted handled
startupJob ownership cleared
cleanup completes if required
pending retry checked
retry submitted
```

## 1.4 Cleanup failure quarantines automatic restart

If start verification fails and cleanup stop also fails:

```text
native runtime existence is uncertain
```

Required:

```text
nativeRuntimeUncertain = true
pending retry cleared
automatic resume blocked
automatic start blocked
normal stopped/paused success not published
```

Only a later verified successful stop clears quarantine.

## 1.5 Failed verified STOP does not abandon the controller

Forbidden:

```text
repository.stop() fails
publish Error
stopForeground()
stopSelf()
```

Required:

```text
repository.stop() fails
remain foreground
show visible Error
allow user to retry Stop
```

The service voluntarily exits only after verified runtime absence.

## 1.6 Critical child coroutine failure is visible

Controlled boundaries are required for:

```text
command processor
network policy monitor
startup worker
status poller
```

Forbidden:

```text
runCatching { ... }
discard Result
```

Forbidden:

```text
child dies under SupervisorJob
service stays alive
no visible error
```

## 1.7 Temporary metered allowance lasts through the authorized run

Clear on:

```text
startup failure
pause
policy pause
verified stop
destroy
```

Do not clear on verified startup success.

## 1.8 Decrypted identity buffers are wiped on every ownership exit

Any decrypted private-identity `ByteArray` must be wiped if:

```text
validation fails
config preparation fails
address resolution fails
startup aborts
startup succeeds and ownership ends
```

## 1.9 Cleanup failure history is sticky

Sticky cleanup codes include:

```text
stop_failed
stop_status_verification_failed
start_verification_cleanup_failed
```

Current status may recover; cleanup history remains.

## 1.10 Forwards mutation receipt is atomic

Required transaction:

```text
lock
capture before
apply mutation
persist
increment revision
return receipt
unlock
```

Rollback uses that receipt's exact revision.

## 1.11 Successful forwards refresh advances revision

Any canonical state replacement invalidates older rollback receipts.

## 1.12 Current load failure blocks all mutation

Required:

```text
loadError != null
    -> upsert blocked
    -> delete blocked
    -> raw save blocked/removed
```

Historical `hasValidBaseline` may not override a current read failure.

## 1.13 Shared config writes are serialized

All writers to `config.toml` use:

```text
one mutex
one atomic writer
unique temp file
```

## 1.14 Native schema drift fails explicitly

Unknown mode must not become `Offer`.

Unknown listen state must include explicit raw-value diagnosis.

---

# 2. Coordinator completion architecture

## 2.1 Lifecycle command

Add:

```kotlin
data class StartupCompleted(
    val generation: Long,
    val completion: StartupCompletion,
) : LifecycleCommand
```

Keep existing `StartupCompletion` variants and add:

```kotlin
data class UnexpectedFailure(
    val error: Throwable,
) : StartupCompletion
```

## 2.2 Startup worker contract

The worker returns a `StartupCompletion`.

Target shape:

```kotlin
private suspend fun runOfferStartWork(
    generation: Long,
): StartupCompletion {
    return try {
        val result =
            withContext(ioDispatcher) {
                repository.start(
                    mode = TunnelMode.Offer,
                    configPath =
                        configRepository.configPath,
                    identityBytes = identity,
                )
            }

        classifyStartupResult(result)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        StartupCompletion.UnexpectedFailure(error)
    }
}
```

Then submit:

```kotlin
submitLifecycleCommand(
    LifecycleCommand.StartupCompleted(
        generation = generation,
        completion = completion,
    ),
)
```

The worker does not retry or stop directly.

## 2.3 Coordinator handler

Target:

```kotlin
private suspend fun handleStartupCompleted(
    command: LifecycleCommand.StartupCompleted,
) {
    if (
        lifecycleGeneration.get() !=
            command.generation
    ) {
        return
    }

    startupJob = null

    when (val completion = command.completion) {
        StartupCompletion.VerifiedSuccess ->
            handleVerifiedStartupSuccess(
                command.generation,
            )

        is StartupCompletion.NativeStartFailure ->
            handleNativeStartFailure(
                command.generation,
                completion.error,
            )

        is StartupCompletion.VerificationFailure ->
            handleVerificationFailure(
                command.generation,
                completion.error,
            )

        is StartupCompletion.UnexpectedFailure ->
            handleUnexpectedStartupFailure(
                command.generation,
                completion.error,
            )
    }
}
```

The exact names may differ. Ownership must not.

---

# 3. Generation-bound policy retry

Use:

```kotlin
private val pendingPolicyResumeGeneration =
    AtomicReference<Long?>(null)
```

Record the generation when `PolicyAllowed` arrives during active startup.

Consume only after coordinator completion:

```kotlin
private fun submitPendingPolicyRetryIfValid(
    completedGeneration: Long,
) {
    val pending =
        pendingPolicyResumeGeneration
            .getAndSet(null)

    if (pending != completedGeneration) {
        return
    }

    submitLifecycleCommand(
        LifecycleCommand.RetryPolicyResume(
            expectedGeneration =
                completedGeneration,
        ),
    )
}
```

Handler:

```kotlin
private suspend fun handleRetryPolicyResume(
    expectedGeneration: Long,
) {
    if (
        lifecycleGeneration.get() !=
            expectedGeneration
    ) {
        return
    }

    if (nativeRuntimeUncertain.get()) {
        return
    }

    if (!pausedByPolicy.get()) {
        return
    }

    beginPolicyResumeAttempt()
}
```

---

# 4. Runtime quarantine

Add:

```kotlin
private val nativeRuntimeUncertain =
    AtomicBoolean(false)
```

Set true when cleanup after verification failure fails.

Also consider stop-verification failure as uncertain runtime.

On quarantine:

```text
pending retry cleared
PolicyAllowed rejected
RetryPolicyResume rejected
automatic restart rejected
```

Explicit STOP remains allowed.

Only verified successful stop clears quarantine.

---

# 5. STOP failure policy

For normal user STOP:

```kotlin
repository.stop().fold(
    onSuccess = {
        nativeStopVerified.set(true)
        nativeRuntimeUncertain.set(false)
        stopForegroundAndSelf()
    },
    onFailure = { error ->
        nativeStopVerified.set(false)
        nativeRuntimeUncertain.set(true)

        reporter.publishError(
            message =
                error.message
                    ?: "Unable to verify tunnel stopped",
            code = stopFailureCode(error),
        )

        // Stay alive and foreground.
    },
)
```

Do not destroy the only controller of an uncertain runtime.

---

# 6. Controlled failure boundaries

## Command processor

Store the processor `Job`.

Catch unexpected command exceptions and report:

```text
lifecycle_command_failed
```

Rethrow cancellation.

## Network monitor

Catch unexpected monitor errors and report:

```text
network_policy_monitor_failed
```

Do not silently terminate policy enforcement.

## Startup worker

Convert unexpected exceptions into:

```text
StartupCompletion.UnexpectedFailure
```

## Status poller

Replace discarded outer `runCatching` with explicit reporting:

```text
status_poll_failed
```

Expected repository status errors remain repository state; unexpected exceptions become visible diagnostics.

---

# 7. Metered allowance lifetime

Do not clear temporary allowance after verified startup success.

Clear on:

```text
native start failure
unexpected startup failure
verification failure after cleanup resolution
pause success
policy pause success
verified stop
destroy
```

---

# 8. Private identity ownership

Use ownership transfer:

```kotlin
val identity =
    identityRepository
        .readPrivateIdentityPlaintext()

var transferred = false

try {
    // prepare and validate
    transferred = true
    return identity
} finally {
    if (!transferred) {
        identity.fill(0)
    }
}
```

The receiving owner wipes later.

Do the same for stored-identity validation helpers. Do not lose plaintext buffers through `getOrNull()`.

---

# 9. Sticky cleanup history

Treat:

```text
start_verification_cleanup_failed
```

as a sticky cleanup failure code.

Later status refresh may change `lastError`; it must not erase `lastCleanupError`.

---

# 10. Forwards integrity

Use:

```kotlin
data class ForwardsMutationReceipt(
    val before: List<ForwardConfig>,
    val after: List<ForwardConfig>,
    val committedRevision: Long,
)
```

Capture `before`, persist `after`, increment revision, and return receipt under one mutex.

Successful refresh increments revision.

Mutation guard checks current `loadError`.

Delete unused raw repository `save()` if no legitimate caller remains.

---

# 11. Settings reset integrity

Do not call `forwardsStore.saveForwards(emptyList())` behind the repository.

Add repository-owned reset:

```kotlin
suspend fun resetForwards(): Result<Unit>
```

It must:

```text
persist empty list
publish empty list
clear loadError
increment revision
```

under repository mutex.

If multi-file reset partially succeeds, tell the user exactly which stages succeeded and failed.

---

# 12. Config write serialization

One `ConfigRepository` write mutex.

All config writers go through the same atomic writer.

Use a unique temp path, not a fixed shared `config.toml.tmp`.

---

# 13. Native status truthfulness

Unknown mode:

```text
retain previous mode structurally
ServiceState.Error
native_status_schema_error
redacted raw value
```

Unknown listen state:

```text
ListenState.Error
native_status_schema_error
explicit raw-value diagnosis
```

---

# 14. Terminal peer truthfulness

Clear `remotePeerId` in:

```text
Stopped
Error
PausedMeteredBlocked
NoNetwork
```

---

# 15. Duplicate start prevention

Use:

```text
isTunnelActiveOrStarting()
```

to block duplicate start in:

```text
Starting
Connecting
Reconnecting
Listening
Serving
Connected
```

---

# 16. Initially policy-blocked startup

When startup is blocked before native start:

```text
pausedByPolicy = true
repository.setPolicyBlocked(...)
```

Later allowed network may auto-resume if configured.

---

# 17. Logs and preferences

Serialize overlapping log refreshes by cancellation or generation.

Wire `logsError` into the actual screen or remove dead state.

Preference-write failures must be visible and must not produce a success snackbar.

---

# 18. Test invariants

Required:

```text
stale retry after STOP does not restart
one later PolicyAllowed event is sufficient
cleanup failure blocks auto-retry
verified STOP clears quarantine
failed STOP keeps service foreground
metered allowance survives successful run
identity bytes zeroed on all failures
StatusReadReleased < StopEntered
CommandCompleted -> stopCalls == 1
```

No repeated synthetic network-event loop.

No elapsed-time absence proof.

---

# 19. Scope boundaries

Do not:

- redesign Rust WebRTC architecture;
- change signaling or crypto formats;
- add unbounded queues;
- add hidden retry loops;
- add sleep-based correctness tests;
- add a generic enterprise actor framework;
- mark acceptance criteria complete without evidence.

---

# 20. Release completion

Release signoff requires:

```text
startup completion is coordinator-owned
retry is generation-bound
old startup fully completes before retry
cleanup failure quarantines auto-restart
failed STOP keeps controller alive
critical coroutine failures are visible
metered override lasts through authorized run
identity bytes wipe on every failure
cleanup failure history remains sticky
forwards receipts are atomic
refresh advances revision
loadError blocks mutation
reset updates repository and disk together
config writes are serialized
unknown mode is explicit error
terminal states clear peer
duplicate starts are blocked
initial policy block can auto-resume
logs cannot reorder
final CI is tied to final implementation SHA
```
