# WebRTC Tunnel Single-Owner Stop and State-Integrity Release-Signoff Specification

## 0. Document purpose

Implement this specification against:

```text
webrtc_tunnel-master_2607061637.zip
```

This is a **corrective release-signoff pass**.

It does not replace the project's accepted architecture. It preserves:

- foreground-process operation;
- the Rust daemon lifecycle model;
- shared `ShutdownToken` status truthfulness;
- offer/answer task supervision;
- signaling, crypto, identity, authorization, and wire protocol;
- Android JNI ownership model;
- atomic status-file replacement;
- systemd, launchd, Debian, and macOS packaging architecture;
- no `sd_notify` readiness;
- no daemon/fork/PID-file architecture.

The previous hardening pass substantially improved the code, but the 2607061637 snapshot still has several release-signoff defects:

1. startup cancellation can call `repository.stop()` concurrently with the lifecycle action that cancelled startup;
2. a duplicate stop can receive `NotRunning` success while another caller owns the real stop and may later fail;
3. `TunnelRepository` performs non-atomic read/modify/write operations on `MutableStateFlow`;
4. a native stop can return success even when final stopped state could not be read or verified;
5. forwards rollback persistence failure is ignored;
6. required Android tests still contain silent barrier timeout behavior and sleep-based correctness polling;
7. production code contains an unbounded test event channel and a synthetic supersedence seam for a state production cannot create;
8. auto-resume clears `pausedByPolicy` before resume succeeds;
9. initial forwards load failure is presented to the screen as an empty successful list;
10. `lastCleanupError` is omitted from status redaction;
11. a corrupt saved setup draft is silently ignored;
12. an unused store-level `upsertForward()` can throw despite returning `ValidationResult`;
13. the recorded remote CI run predates the final P1 production changes.

The central rule for this pass is:

> One lifecycle transition owns one native stop attempt, and no later observer, duplicate request, status refresh, rollback, or UI fallback may convert an uncertain or failed outcome into clean success.

---

# 1. Non-negotiable invariants

## 1.1 Exactly one service path owns startup cancellation cleanup

A lifecycle action that cancels `startupJob` must also own the resulting native cleanup.

Required:

```text
pause / policy pause / service stop / onDestroy
        ↓
advance lifecycle generation
        ↓
cancel startup job
        ↓
join startup job
        ↓
quiesce status polling
        ↓
perform one authoritative repository.stop()
        ↓
handle that exact Result
```

Forbidden:

```text
explicit stop path calls repository.stop()
AND
cancelled startup catch also calls repository.stop()
```

The cancelled startup coroutine must not independently compete for native stop ownership.

---

## 1.2 Startup join must not deadlock on `lifecycleMutex`

The current startup task calls `isCurrentGeneration()`, which acquires `lifecycleMutex`.

Therefore this is forbidden without restructuring:

```kotlin
lifecycleMutex.withLock {
    startupJob?.cancelAndJoin()
}
```

because the startup task may need the same mutex to finish.

Chosen design:

- make lifecycle generation atomic;
- make generation checks lock-free;
- then cancel-and-join startup while holding `lifecycleMutex`.

Target:

```kotlin
private val lifecycleGeneration = AtomicLong(0)

private fun isCurrentGeneration(startGeneration: Long): Boolean =
    lifecycleGeneration.get() == startGeneration
```

After this change, audit the entire startup call graph and prove it cannot acquire `lifecycleMutex` while `cancelStartupJobAndJoinLocked()` waits.

---

## 1.3 A duplicate/native no-op stop is not sufficient proof of clean shutdown

The Rust controller currently treats:

```text
StopOutcome::NotRunning
```

as success at the C/JNI boundary.

That is acceptable only if final native state is actually verified as `Stopped`.

Required Android rule:

```text
bridge.stop() failure
    → stop failure

bridge.stop() success
    + status read/decode failure
    → stop verification failure

bridge.stop() success
    + final native state != Stopped
    → stop verification failure

bridge.stop() success
    + final native state == Stopped
    → verified clean success
```

This prevents a duplicate caller from claiming success while the real owner is still stopping or has failed into `Error`.

---

## 1.4 Repository state changes must be atomic

`MutableStateFlow` is thread-safe, but this is not an atomic state transition:

```kotlin
_status.value = _status.value.copy(...)
```

A concurrent writer can update between the read and write.

Every state mutation must use a compare-and-set loop, `update`, or equivalent atomic transform based on the current value at commit time.

Preferred helper:

```kotlin
private inline fun updateStatus(
    transform: (TunnelStatus) -> TunnelStatus,
): TunnelStatus {
    while (true) {
        val current = _status.value
        val next = transform(current)
        if (_status.compareAndSet(current, next)) {
            return next
        }
    }
}
```

Use one atomic helper consistently.

---

## 1.5 Expensive native reads happen outside state mutation; merge happens at commit time

Do not hold a state mutation loop around JNI or JSON decode.

Correct shape:

```text
read/decode native status
        ↓
obtain immutable native snapshot
        ↓
atomic update against latest TunnelStatus
```

This ensures a concurrent cleanup error, network update, or policy update is merged rather than overwritten by a stale pre-read snapshot.

---

## 1.6 `stop()` success means verified stopped state

`TunnelRepository.stop()` must not return `Result.success(Unit)` merely because JNI returned zero.

It must verify final status.

Recommended error type:

```kotlin
class StopStatusVerificationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
```

Examples:

```text
Native stop returned success but final status could not be decoded.
Native stop returned success but final state was Error.
Native stop returned success but final state was Listening.
```

The service must not publish normal Paused/Stopped/policy-paused state for this outcome.

---

## 1.7 Rollback failure is a first-class failure

Current anti-pattern:

```kotlin
deps.forwardsRepository.save(before)
sync.message ?: "Forward update failed"
```

The `Result` is ignored.

Required semantics:

```text
forward mutation saved
        ↓
active config regeneration fails
        ↓
rollback succeeds
        → report original activation failure

rollback fails
        → report original activation failure
        + rollback failure
        + explicitly state saved forwards remain changed but active config was not updated
```

A rollback failure must never be hidden.

---

## 1.8 Required test synchronization must fail loudly

Forbidden:

```kotlin
latch.await(5, TimeUnit.SECONDS) // Boolean ignored
```

Required:

```kotlin
check(latch.await(5, TimeUnit.SECONDS)) {
    "blocked native call was never released"
}
```

Also forbidden for correctness proof:

```kotlin
Thread.sleep(...)
```

and:

```text
wait 500 ms and infer that an event never happened
```

Use events, channels, latches, `StateFlow.first`, or explicit task completion.

---

## 1.9 Test-only observability must not create production queues

Remove the production:

```kotlin
Channel<ServiceTestEvent>(Channel.UNLIMITED)
```

and the synthetic startup supersedence machinery if it is no longer needed after single-owner stop restructuring.

No release build should retain an unbounded queue with no consumer.

A nullable, non-buffering observer seam is acceptable only if a remaining test genuinely needs it:

```kotlin
internal fun interface ServiceTestObserver {
    fun onEvent(event: ServiceTestEvent)
}

internal var testObserver: ServiceTestObserver? = null
```

Prefer removing the seam entirely.

---

## 1.10 Policy retry state clears only after successful resume

Current bad sequence:

```text
unmetered event
    → pausedByPolicy = false
    → resume fails
    → retry state lost
```

Required:

```text
unmetered event
    → call resume while pausedByPolicy remains true
    → successful start clears flag in existing start-success path
    → failed resume leaves flag true for later retry
```

---

## 1.11 Initial forwards load failure is not an empty successful list

The repository may internally retain an empty list while load failed, but the UI must receive explicit failure state.

Required screen behavior:

```text
loadError != null
    → show error/recovery state
    → do not show “No forwards configured”
    → disable Add while no valid baseline exists
    → provide Retry
```

The corrupt/unreadable file remains untouched.

---

## 1.12 All diagnostic error fields must be redacted

`TunnelStatus.lastCleanupError` must receive the same redaction as `lastError`.

Required:

```kotlin
status.copy(
    lastError = status.lastError?.redacted(),
    lastCleanupError = status.lastCleanupError?.redacted(),
)
```

Add a unique sentinel test.

---

## 1.13 Corrupt setup draft must be visible and non-destructive

Required:

```text
saved setup missing
    → normal empty/default wizard

saved setup valid
    → prefill

saved setup exists but read/parse fails
    → visible error
    → no prefill
    → leave file untouched
```

Do not silently return.

---

## 1.14 Unused failure-mismatched APIs should be removed

`ForwardsConfigStore.upsertForward()` has no production caller and can throw `ForwardsWriteException` despite returning `ValidationResult`.

Remove it rather than preserving another latent footgun.

---

## 1.15 Release signoff CI must run on the final implementation SHA

The previously observed CI run was real and valuable, but later production changes were committed afterward.

Required final policy:

```text
finish all P0 and P1 implementation
        ↓
run all local gates
        ↓
commit final code/docs
        ↓
push only if authorized
        ↓
observe CI on that exact final SHA
```

Do not reuse an earlier green run as proof for later production code.

---

# 2. Chosen lifecycle architecture

## 2.1 Lifecycle generation becomes atomic

Replace:

```kotlin
internal var lifecycleGeneration: Long = 0
```

with:

```kotlin
private val lifecycleGeneration = AtomicLong(0)
```

Tests must no longer mutate it directly.

`startOffer()`:

```kotlin
lifecycleMutex.withLock {
    if (startupJob?.isActive == true) {
        reporter.publishStatus(getString(R.string.service_msg_already_starting))
        return
    }

    if (repository.status.value.serviceState.isTunnelRunning()) {
        reporter.publishStatus(getString(R.string.service_msg_already_running))
        return
    }

    val generation = lifecycleGeneration.incrementAndGet()
    startupJob = serviceScope.launch {
        doStartOffer(generation)
    }
}
```

Generation check:

```kotlin
private fun isCurrentGeneration(startGeneration: Long): Boolean =
    lifecycleGeneration.get() == startGeneration
```

No mutex.

---

## 2.2 Replace cancel-only helper with cancel-and-join

Replace:

```kotlin
private fun cancelStartupJobLocked() {
    startupJob?.cancel()
    startupJob = null
}
```

with:

```kotlin
private suspend fun cancelStartupJobAndJoinLocked() {
    val job = startupJob
    startupJob = null
    job?.cancelAndJoin()
}
```

The name must make the waiting behavior explicit.

---

## 2.3 Explicit lifecycle transitions own cleanup

Target order inside `lifecycleMutex`:

```text
advance generation
cancel + join startup
stop + join status poll
call verified repository.stop()
handle exact result
```

Example `pause()`:

```kotlin
suspend fun pause() {
    lifecycleMutex.withLock {
        lifecycleGeneration.incrementAndGet()
        cancelStartupJobAndJoinLocked()
        reporter.stopStatusPollingAndJoin()

        repository.stop().fold(
            onSuccess = {
                reporter.publishStatus(getString(R.string.service_msg_paused))
            },
            onFailure = { error ->
                reporter.publishError(
                    message = error.message ?: "Unable to stop tunnel",
                    code = stopFailureCode(error),
                )
            },
        )
    }
}
```

Apply equivalent ownership to:

- `pause()`;
- `pauseForPolicy()`;
- `stopServiceWork()`;
- `onDestroy()`.

---

## 2.4 Startup coroutine no longer stops native runtime on cancellation

Remove the cancellation catch that performs:

```kotlin
repository.stop()
```

The lifecycle transition that cancelled the job owns that stop.

Target:

```kotlin
val result =
    withContext(ioDispatcher) {
        repository.start(
            TunnelMode.Offer,
            configRepository.configPath,
            identity,
        )
    }
```

If cancellation occurs, allow `CancellationException` to unwind.

The `finally` that wipes `identity` remains mandatory.

---

## 2.5 Defensive stale generation after native start does not self-stop

After native start returns:

```kotlin
if (!isCurrentGeneration(startGeneration)) {
    // The lifecycle transition that advanced generation owns stop cleanup.
    return
}
```

Do not call a second stop here.

After restructuring, the current synthetic supersedence test seam should be deleted unless a real production path can still produce this state.

---

# 3. Repository state-integrity architecture

## 3.1 One atomic state helper

Add:

```kotlin
private inline fun updateStatus(
    transform: (TunnelStatus) -> TunnelStatus,
): TunnelStatus {
    while (true) {
        val current = _status.value
        val next = transform(current)
        if (_status.compareAndSet(current, next)) {
            return next
        }
    }
}
```

Use it for:

- native status commit;
- status decode failure;
- policy blocked;
- local error;
- network status;
- session metered allowance.

No production `_status.value = _status.value.copy(...)` remains.

---

## 3.2 Result-bearing status refresh

Add:

```kotlin
fun refreshStatusResult(): Result<TunnelStatus> {
    val native =
        runCatching {
            Json.decodeFromString<NativeRuntimeStatusDto>(
                bridge.getStatusJson(),
            )
        }.getOrElse { error ->
            updateStatus { current ->
                current.copy(
                    serviceState = ServiceState.Error,
                    lastError =
                        TunnelError(
                            code = "status_decode_failed",
                            message = "Native status decode failed",
                            details = SensitiveDataRedactor.redactText(
                                error.message ?: "unknown status decode error",
                            ),
                        ),
                )
            }
            return Result.failure(error)
        }

    val committed =
        updateStatus { current ->
            val mapped = native.toTunnelStatus(current)
            val resolved =
                if (isPolicyPausedState(current.serviceState) && native.active) {
                    mapped.copy(
                        serviceState = current.serviceState,
                        networkStatus = current.networkStatus,
                        mqttConnected = false,
                        activeSessionCount = 0,
                        lastError = current.lastError,
                    )
                } else {
                    mapped
                }

            SensitiveDataRedactor.redactStatus(resolved)
        }

    return Result.success(committed)
}
```

Keep:

```kotlin
fun refreshStatus() {
    refreshStatusResult()
}
```

only for callers whose contract is intentionally “publish error into status; no direct result needed.”

Document each ignored result.

---

## 3.3 Verified stop

Recommended:

```kotlin
class StopStatusVerificationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
```

Then:

```kotlin
fun stop(): Result<Unit> =
    bridge.stop().fold(
        onFailure = { Result.failure(it) },
        onSuccess = {
            refreshStatusResult().fold(
                onFailure = { error ->
                    Result.failure(
                        StopStatusVerificationException(
                            "Native stop returned success but final status could not be verified",
                            error,
                        ),
                    )
                },
                onSuccess = { status ->
                    if (status.serviceState == ServiceState.Stopped) {
                        Result.success(Unit)
                    } else {
                        Result.failure(
                            StopStatusVerificationException(
                                "Native stop returned success but final state was ${status.serviceState}",
                            ),
                        )
                    }
                },
            )
        },
    )
```

Adapt wording and types to current style.

---

## 3.4 Stop error codes

Use:

```text
stop_failed
stop_status_verification_failed
```

A top-level helper is acceptable:

```kotlin
private fun stopFailureCode(error: Throwable): String =
    if (error is StopStatusVerificationException) {
        "stop_status_verification_failed"
    } else {
        "stop_failed"
    }
```

`lastCleanupError` must retain both categories.

---

# 4. Deterministic test architecture

## 4.1 Fake stop calls must be observable by event

Test fake:

```kotlin
private val stopCallNumber = AtomicInteger(0)
private val stopCalls = Channel<Int>(Channel.UNLIMITED)

suspend fun awaitStopCall(): Int =
    withTimeout(TEST_TIMEOUT_MS) {
        stopCalls.receive()
    }

override fun stop(): Result<Unit> {
    val call = stopCallNumber.incrementAndGet()
    check(stopCalls.trySend(call).isSuccess) {
        "stop-call observer unexpectedly closed"
    }
    // planned result
}
```

Do not infer stop entry from elapsed time.

---

## 4.2 Blocking latches fail loudly

Required:

```kotlin
check(
    releaseLatch.await(5, TimeUnit.SECONDS),
) {
    "test never released blocked native operation"
}
```

Any timeout means the test setup failed.

---

## 4.3 Single-owner stop regression

Force:

```text
startOffer enters native start and blocks
        ↓
ACTION_PAUSE cancels startup
        ↓
release native start
        ↓
startup cancellation unwinds
        ↓
explicit pause owns one stop
        ↓
stop fails
```

Assert:

```text
exactly one native stop call
final repository state == Error
no Paused/Stopped clean state after failure
```

Regression strength:

- restore old startup cancellation cleanup stop;
- test must fail because two stop calls occur or clean state masks failure.

---

## 4.4 Atomic state regression

Force:

```text
refreshStatus decodes native snapshot and blocks before commit
        ↓
setLocalError(stop_failed, sentinel)
        ↓
release status commit
```

Assert:

```text
lastCleanupError sentinel survives
```

Also test network state if practical.

The old `_status.value = previous.copy(...)` behavior must fail.

---

## 4.5 Stop verification regression

Required tests:

1. native stop success + status read failure → `Result.failure`;
2. native stop success + final `Error` → `Result.failure`;
3. native stop success + final `Running/Listening` → `Result.failure`;
4. native stop success + final `Stopped` → success.

The service-level stop test must prove no clean notification/status after verification failure.

---

# 5. Forwards rollback integrity

## 5.1 Shared helper

Recommended:

```kotlin
private suspend fun rollbackAfterConfigSyncFailure(
    before: List<ForwardConfig>,
    syncFailure: ValidationResult,
    fallbackMessage: String,
): String {
    val original = syncFailure.message ?: fallbackMessage

    return deps.forwardsRepository.save(before).fold(
        onSuccess = {
            original
        },
        onFailure = { rollbackError ->
            val rollbackMessage = describeForwardsFailure(rollbackError)
            "$original. Rollback also failed; the forward change remains saved " +
                "but was not activated: $rollbackMessage"
        },
    )
}
```

Use for both save and delete.

The wording must make consistency state explicit.

---

# 6. UI and diagnostic truthfulness

## 6.1 Forwards load error

Expose:

```kotlin
val loadError: StateFlow<String?> = deps.forwardsRepository.loadError
```

On screen:

```text
loadError != null
    → error card
    → Retry action
    → Add disabled

loadError == null && forwards empty
    → normal empty-state card
```

Never display load failure as “No forwards configured.”

---

## 6.2 Setup draft load error

Replace:

```kotlin
loadSetupInputResult().getOrNull() ?: return
```

with explicit fold.

On failure:

```text
Saved setup could not be loaded. The existing saved draft was left untouched.
```

Do not destroy or overwrite the file.

---

## 6.3 Cleanup error redaction

`SensitiveDataRedactor.redactStatus()` must redact both:

- `lastError`;
- `lastCleanupError`.

Use a unique sentinel test.

---

# 7. Out of scope

Do not:

- redesign the Rust daemon;
- change wire protocol;
- change crypto format;
- change identity format;
- add global shutdown state;
- add hidden timeouts;
- add daemon mode;
- add PID files;
- reintroduce `sd_notify`;
- replace the entire Android state architecture;
- add a new broad event bus;
- implement a generic repository framework.

---

# 8. Release-signoff definition

Release signoff is reached only when all are true:

```text
startup cancellation never competes with explicit lifecycle stop
exactly one service path owns native stop for a cancelled startup
status state updates are atomic
stale native refresh cannot erase cleanup history or network state
native stop success is verified as final Stopped state
NotRunning cannot mask an in-progress or failed real stop
rollback persistence failure is visible
required test barriers fail loudly
required correctness tests contain no sleep polling
production contains no unbounded test event queue
failed auto-resume retains retry state
forwards load failure is visible, not an empty success
lastCleanupError is redacted
corrupt setup draft is visibly reported and left untouched
unused failure-mismatched store API is removed
all local gates pass
real CI runs on the exact final implementation SHA
```
