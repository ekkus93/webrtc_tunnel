# WebRTC Tunnel State-Integrity Recovery Spec

## 1. Purpose

This spec defines the corrective hardening pass required after review of the current
WebRTC Tunnel codebase and the previous
`WEBRTC_TUNNEL_COORDINATOR_COMPLETION_RUNTIME_QUARANTINE_FINAL_SIGNOFF_TODO`.

The previous pass added several good architectural ideas:

- lifecycle generations;
- startup completion commands;
- native runtime verification;
- runtime quarantine;
- sticky cleanup history;
- shared forwards repository state;
- native status schema validation;
- structured redaction;
- explicit log errors;
- Android lifecycle coordination.

However, the latest code review found that several checked TODO items are either only
partially implemented or implemented in ways that violate the intended invariants.

This pass is not a broad refactor. It is a focused state-integrity recovery effort.

The most important goals are:

1. make uncertain native runtime quarantine a hard, non-bypassable invariant;
2. make lifecycle command processing owned, non-lossy, cancellation-correct, and teardown-safe;
3. make policy retry genuinely one-event and completion-driven;
4. eliminate critical silent failures;
5. guarantee plaintext identity zeroization on every failure path;
6. replace the current fake transactional reset with real snapshot/restore semantics;
7. implement true atomic forwards mutation receipts and remove mutation bypasses;
8. make config and preference persistence truthful;
9. make status/log generation ordering and terminal-state cleanup correct;
10. align tests with the intended invariants instead of hiding failures.

---

## 2. Non-negotiable rules

### 2.1 No silent failure

The following are forbidden in critical lifecycle, security, or persistence paths:

```kotlin
runCatching { ... }
```

when the returned `Result` is ignored.

Also forbidden:

```kotlin
runCatching { ... }.getOrNull()
```

when:

- the failure is user-visible;
- the failure changes lifecycle behavior;
- the operation owns plaintext secret material;
- the operation mutates persistent state.

A failure must result in one of:

- an explicit returned `Result`;
- a typed outcome;
- a visible repository error;
- a visible lifecycle event;
- a redacted diagnostic entry.

### 2.2 No fake transaction terminology

Do not call an operation transactional unless:

1. prior state is captured before mutation;
2. mutations are ordered;
3. failure is detected correctly;
4. rollback restores the exact captured prior state;
5. rollback failures are reported separately.

A second reset is not rollback.

### 2.3 No quarantine bypass

When native runtime state is uncertain:

```text
nativeRuntimeUncertain == true
```

the only allowed lifecycle recovery command is explicit STOP.

The following must be blocked:

```text
StartOffer
Resume
AllowMeteredSession
PolicyAllowed automatic resume
RetryPolicyResume
notification retry/start
automatic startup
```

Quarantine clears only after a verified successful native STOP.

### 2.4 Critical lifecycle commands must not be dropped

A lifecycle command queue may not silently or visibly discard:

```text
STOP
PAUSE
START
PolicyBlocked
PolicyAllowed
StartupCompleted
```

Logging that a command was dropped is not sufficient.

### 2.5 Cancellation must propagate

Every coroutine boundary that catches `Throwable` must do:

```kotlin
catch (cancelled: CancellationException) {
    throw cancelled
}
```

before handling other exceptions.

### 2.6 Test the invariant, not the workaround

Do not make a race test pass by repeatedly sending more events.

If the invariant says:

```text
one later PolicyAllowed event is enough
```

the test must send exactly one later event.

---

## 3. Priority model

- **P0** — release-blocking correctness/security/state-integrity issue.
- **P1** — high-priority integrity hardening required before final signoff.
- **P2** — architecture/CI cleanup that should follow P0/P1.

No P0 or P1 item should be marked complete without focused tests.

---

# 4. P0 requirements

## P0-001 — Replace fake transactional reset with real transactional semantics

### Problem

The current `TransactionalResetCoordinator` has two critical defects:

1. a nested `Result<Unit>` can be misclassified as success when wrapped in `runCatching`;
2. rollback performs reset operations again instead of restoring prior state.

### Required behavior

Before mutating any reset stage, capture a complete snapshot.

Recommended model:

```kotlin
data class ResetSnapshot(
    val configToml: ByteArray?,
    val setupInput: SetupConfigInput,
    val forwards: List<ForwardConfig>,
)
```

The implementation may use a richer type if needed.

The reset process must be:

```text
capture prior state
    ↓
reset config
    ↓
reset setup input
    ↓
reset forwards
    ↓
success
```

If a stage fails:

```text
record exact failed stage
    ↓
restore all already-mutated stages from snapshot
    ↓
record rollback success/failure for each stage
    ↓
return PartialFailure / RollbackFailure
```

### Required result model

Use explicit outcomes.

Example:

```kotlin
sealed interface ResetResult {
    data class Success(
        val stages: List<ResetStageResult>,
    ) : ResetResult

    data class Failed(
        val failedStage: ResetStage,
        val cause: String,
        val rollback: List<RollbackStageResult>,
    ) : ResetResult
}
```

Do not collapse all failure modes into:

```text
Reset failed
```

### Important nested-Result rule

This is wrong:

```kotlin
runCatching {
    forwardsRepository.resetForwards()
}.fold(
    onSuccess = { ResetStageResult.Success(...) },
    onFailure = { ... },
)
```

because `resetForwards()` returns `Result<Unit>`.

Use:

```kotlin
val result = forwardsRepository.resetForwards()

val stageResult =
    result.fold(
        onSuccess = {
            ResetStageResult.Success(ResetStage.Forwards)
        },
        onFailure = { error ->
            ResetStageResult.Failure(
                stage = ResetStage.Forwards,
                reason = error.message ?: "unknown error",
            )
        },
    )
```

### Rollback

A real rollback for forwards must restore the captured list, not call reset again.

A real rollback for config must restore the captured bytes/string, not write the default config again.

### Required tests

At minimum:

1. all stages succeed;
2. config reset fails before mutation;
3. setup reset fails after config reset;
4. forwards reset returns `Result.failure`;
5. rollback restores previous config;
6. rollback restores previous setup input;
7. rollback restores previous forwards;
8. rollback failure is reported explicitly;
9. false success from nested `Result` is impossible.

---

## P0-002 — Enforce runtime quarantine at every start/resume boundary

### Problem

The current code blocks some automatic resume paths while quarantined, but direct starts and other resume paths can bypass quarantine.

### Required invariant

Create one canonical guard.

Example:

```kotlin
private fun canStartOrResume(): Boolean =
    !nativeRuntimeUncertain.get()
```

Better:

```kotlin
private fun requireRuntimeStartAllowed(): Result<Unit> {
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

Use the guard in every path that can start native runtime:

```text
ACTION_START_OFFER
CoordinatorOperations.startOffer()
OfferCoordinator.startOffer()
resume()
allowMeteredForSessionAndStart()
PolicyAllowed
RetryPolicyResume
notification Retry/Start
automatic startup
```

### Explicit STOP remains available

While quarantined:

```text
STOP = allowed
everything that can start/restart = blocked
```

### Required tests

1. stop failure sets quarantine;
2. StartOffer while quarantined does not call native start;
3. Resume while quarantined does not call native start;
4. AllowMeteredSession while quarantined does not call native start;
5. PolicyAllowed while quarantined does not call native start;
6. RetryPolicyResume while quarantined does not call native start;
7. notification retry while quarantined does not call native start;
8. explicit STOP is accepted;
9. verified STOP clears quarantine;
10. after verified STOP, StartOffer is allowed again.

Delete or rewrite any test that currently expects restart immediately after an unverified STOP failure.

---

## P0-003 — Make lifecycle coordinator owned, non-lossy, and teardown-safe

### Problem

The coordinator currently owns an independent `CoroutineScope`, the processor Job is not lifecycle-owned, and bounded `trySend` semantics can drop lifecycle commands.

### Required behavior

The service must own the coordinator processor lifetime.

Preferred shape:

```kotlin
class TunnelLifecycleCoordinator(
    private val operations: CoordinatorOperations,
    private val scope: CoroutineScope,
) {
    private val commands =
        Channel<LifecycleCommand>(
            capacity = Channel.UNLIMITED,
        )

    private var processorJob: Job? = null

    fun start() {
        check(processorJob == null) {
            "Lifecycle coordinator already started"
        }

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

    suspend fun submit(command: LifecycleCommand) {
        commands.send(command)
    }
}
```

The exact queue design can differ, but these invariants must hold:

- service controls processor lifetime;
- service teardown closes/cancels coordinator;
- cancellation propagates;
- critical commands are not dropped.

### Command submission

For service coroutine paths, use suspending submission:

```kotlin
coordinator.submit(command)
```

For callback paths that cannot suspend, explicitly hand off to the service scope:

```kotlin
serviceScope.launch {
    coordinator.submit(command)
}
```

Do not use a lossy `trySend` for STOP.

### Teardown

`onDestroy()` must:

```text
stop accepting new lifecycle work
close/cancel network monitor
close/cancel status poll
close/cancel coordinator
perform verified native fallback stop if needed
cancel service scope
```

Order must be deliberate and tested.

### Required tests

1. STOP is not dropped under queue pressure;
2. command ordering remains FIFO;
3. coordinator processor stops on service teardown;
4. queued work cannot execute after teardown;
5. command handler cancellation is rethrown;
6. unexpected command handler failure becomes visible.

---

## P0-004 — Make policy retry completion-driven and truly one-event

### Problem

The current implementation uses `startupJob?.isActive` as a proxy for whether startup completion has been fully coordinated.

That is insufficient because the coroutine can finish before its `StartupCompleted` command has been processed.

### Required state model

Use explicit startup ownership.

Example:

```kotlin
private data class ActiveStartup(
    val generation: Long,
    val job: Job,
)

private var activeStartup: ActiveStartup? = null
```

The coordinator is the only authority that clears it.

Important rule:

```text
worker finished != lifecycle completion handled
```

### Required sequence

```text
startup worker finishes
    ↓
StartupCompleted command queued
    ↓
coordinator handles StartupCompleted
    ↓
generation checked
    ↓
startup ownership cleared
    ↓
cleanup completes if required
    ↓
pending retry checked
    ↓
retry submitted once
```

### Pending retry

Recommended:

```kotlin
private val pendingPolicyResumeGeneration =
    AtomicReference<Long?>(null)
```

A PolicyAllowed event during active startup should only record intent:

```kotlin
pendingPolicyResumeGeneration.set(
    activeStartupGeneration
)
```

The completion handler consumes it once.

### Required helper

```kotlin
private fun consumePendingRetryFor(
    completedGeneration: Long,
): Boolean {
    return pendingPolicyResumeGeneration
        .getAndSet(null) == completedGeneration
}
```

### Do not

- retry from inside the worker;
- repeatedly re-fire network callbacks in the test;
- use `job.isActive` as the final completion authority.

### Required exact regression test

```text
PolicyAllowed #1 starts resume attempt #1

attempt #1 reaches failure

PolicyAllowed #2 arrives before attempt #1 completion command is handled

NO third network event

StartupCompleted #1 is handled

retry attempt #2 runs exactly once

attempt #2 succeeds
```

Assert native start call count exactly equals 2.

---

## P0-005 — Complete critical failure boundaries

### Required boundaries

The following paths must convert unexpected exceptions into visible redacted outcomes:

1. lifecycle command processing;
2. network policy monitoring;
3. startup preparation;
4. native start work;
5. status polling;
6. preference reads that affect lifecycle decisions.

### Command processor pattern

```kotlin
private suspend fun processCommand(
    command: LifecycleCommand,
) {
    try {
        handleCommand(command)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        reporter.publishError(
            code = "lifecycle_command_failed",
            message = SensitiveDataRedactor.redactText(
                error.message ?: error::class.java.simpleName
            ),
        )
    }
}
```

### Status poll pattern

Replace:

```kotlin
runCatching {
    repository.refreshStatus()
}
```

with:

```kotlin
try {
    repository.refreshStatus()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    reporter.publishError(
        code = "status_poll_failed",
        message =
            SensitiveDataRedactor.redactText(
                error.message ?: "unexpected status poll failure"
            ),
    )
}
```

### Startup preparation

All work before native start must also produce a visible completion.

Recommended outer boundary:

```kotlin
private suspend fun performStartupAttempt(
    generation: Long,
): StartupCompletion {
    return try {
        val prepared =
            prepareStartupInputs(generation)

        runNativeStart(
            generation = generation,
            prepared = prepared,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        StartupCompletion.UnexpectedFailure(error)
    }
}
```

### Preference read

Do not use:

```kotlin
runCatching {
    configRepository.preferences.first()
}.getOrNull()
```

Use explicit failure reporting.

### Required tests

1. command handler throws;
2. network monitor preference read throws;
3. startup preparation throws;
4. native start throws;
5. status refresh throws;
6. cancellation from each child boundary propagates.

---

## P0-006 — Close every private-identity zeroization hole

### Problem

The service startup path uses explicit ownership transfer, but stored identity resolution still contains a `runCatching(...).getOrNull()` path that can lose a decrypted byte array without wiping it.

### Required ownership rule

A plaintext identity byte array has exactly one owner.

The owner must either:

- transfer ownership explicitly; or
- wipe the buffer in `finally`.

### Required pattern

```kotlin
private suspend fun resolveStoredIdentity(): ResolvedIdentity? {
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

        val peerId =
            derivePeerId(publicIdentity)

        transferred = true

        return ResolvedIdentity(
            privateIdentity = bytes,
            publicIdentity = publicIdentity,
            peerId = peerId,
        )
    } finally {
        if (!transferred) {
            bytes.fill(0)
        }
    }
}
```

The receiving owner must wipe the buffer when finished.

### Required tests

Force failure after plaintext allocation at each stage:

1. private identity validation throws;
2. private identity validation returns invalid;
3. public identity read throws;
4. peer-id derivation throws;
5. later startup preparation throws after ownership transfer.

Use sentinel bytes and assert they are zeroed.

---

## P0-007 — Eliminate false-success preference writes

### Problem

Some ViewModels discard `Result<Unit>` from preference persistence and still show success.

### Required behavior

Every preference write must inspect the `Result`.

Example:

```kotlin
viewModelScope.launch {
    val result =
        withContext(deps.dispatchers.io) {
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
                SensitiveDataRedactor.redactText(
                    error.message
                        ?: "Failed to update network policy"
                )
            )
        },
    )
}
```

### Setup persistence callback

Do not use a callback shaped as:

```kotlin
(AndroidAppPreferences) -> Unit
```

if persistence can fail.

Prefer:

```kotlin
suspend (AndroidAppPreferences) -> Result<Unit>
```

### Required tests

1. Network Policy save success → success message;
2. Network Policy save failure → visible error, no success;
3. Setup preference save failure → setup does not claim success;
4. cancellation is rethrown.

---

## P0-008 — Clear temporary metered allowance on every terminal run path

### Required clear points

Clear temporary allowance on:

```text
native startup failure
verification failure
unexpected startup failure
startup preparation abort
ordinary pause success
policy pause success
verified stop
destroy
```

Do not clear it on verified startup success.

### Required helper

Centralize:

```kotlin
private fun clearTemporaryMeteredAllowanceForRunEnd() {
    temporaryMeteredAllowance.set(false)
}
```

### Required tests

1. successful authorized run retains allowance;
2. next network callback does not immediately pause it;
3. ordinary pause clears;
4. policy pause clears;
5. pre-native preparation failure clears;
6. native start failure clears;
7. verified stop clears.

---

# 5. P1 requirements

## P1-001 — Implement true atomic forwards mutation receipts

### Required model

```kotlin
data class ForwardsMutationReceipt(
    val before: List<ForwardConfig>,
    val after: List<ForwardConfig>,
    val committedRevision: Long,
)
```

### Required repository operation

Capture before, mutate, persist, publish, increment revision, and create receipt under one mutex.

Example:

```kotlin
suspend fun upsertWithReceipt(
    forward: ForwardConfig,
): Result<ForwardsMutationReceipt> =
    mutex.withLock {
        mutationGuard()
            .getOrElse {
                return@withLock Result.failure(it)
            }

        val before = _forwards.value

        val after =
            before
                .filterNot { it.id == forward.id } +
                forward

        store.saveForwards(after)
            .getOrElse {
                return@withLock Result.failure(it)
            }

        revision += 1L
        _forwards.value = after

        Result.success(
            ForwardsMutationReceipt(
                before = before,
                after = after,
                committedRevision = revision,
            )
        )
    }
```

Use the real project validation/order semantics.

### Rollback

Rollback must use the receipt and exact revision.

```kotlin
suspend fun rollback(
    receipt: ForwardsMutationReceipt,
): Result<Unit> =
    mutex.withLock {
        if (revision != receipt.committedRevision) {
            return@withLock Result.failure(
                StaleMutationReceiptException()
            )
        }

        store.saveForwards(receipt.before)
            .getOrElse {
                return@withLock Result.failure(it)
            }

        revision += 1L
        _forwards.value = receipt.before

        Result.success(Unit)
    }
```

### Required tests

1. receipt before/after exact;
2. revision exact;
3. intervening mutation blocks rollback;
4. refresh increments revision and blocks old receipt;
5. rollback restores exact prior list.

---

## P1-002 — Remove all forwards mutation bypasses

### Required

Audit and remove:

```text
save()
saveIfRevisionMatches()
direct store mutation from UI/ViewModel
snapshot() + mutate rollback pattern
```

unless a method is required and fully enforces:

- loadError guard;
- revision invariants;
- persistence result;
- shared-state publication.

Prefer one repository mutation surface.

### `loadError`

Every mutation entry point must call the same guard:

```kotlin
private fun mutationGuard(): Result<Unit> {
    val error = _loadError.value

    return if (error == null) {
        Result.success(Unit)
    } else {
        Result.failure(
            ForwardsMutationBlockedException(
                "Forwards cannot be modified until the load error is resolved."
            )
        )
    }
}
```

### Required tests

1. initial load error blocks every mutation;
2. later refresh error blocks every mutation;
3. successful refresh clears the block;
4. no raw `save()` bypass remains.

---

## P1-003 — Repair config write serialization

### Required invariant

All production writes to `config.toml` must go through one serialized atomic writer.

Required:

```text
one mutex
unique temp file
flush/close
ATOMIC_MOVE + REPLACE_EXISTING
replace-move fallback if ATOMIC_MOVE unsupported
temp cleanup in finally
```

### Remove direct production writes

Audit:

```text
ensureDefaultConfig()
writeConfig()
saveSetupInput()
any test helper accidentally used in production
```

`config.toml` must not be directly written with:

```kotlin
configFile.writeText(...)
```

outside the serialized writer.

### Temp cleanup

Use:

```kotlin
val temp = ...
try {
    ...
    Files.move(...)
} finally {
    Files.deleteIfExists(temp.toPath())
}
```

### Required concurrency test

Two overlapping config writers must produce one complete valid final config.

No mixed/truncated content.

---

## P1-004 — Fix terminal-state peer cleanup and schema fallbacks

### Terminal states

Clear `remotePeerId` for all terminal/local terminal states:

```text
Stopped
Error
PausedMeteredBlocked
NoNetwork
ConfigInvalid
```

Centralize terminal status mutation if possible.

### Missing native mode

Do not silently map:

```kotlin
null -> TunnelMode.Offer
```

If native mode is required by the status schema, null must produce:

```text
ServiceState.Error
native_status_schema_error
```

### Unknown native runtime state

Unknown raw runtime state should produce an explicit redacted schema diagnostic, not only generic Error.

### Required tests

1. null native mode fails verification;
2. future mode fails verification;
3. unknown runtime state includes schema error;
4. local policy pause clears peer;
5. local Error clears peer;
6. NoNetwork clears peer.

---

## P1-005 — Make `nativeStopVerified` truthful on every verified stop

Set true after every verified stop-like transition:

```text
ordinary Pause
policy Pause
explicit Stop
unverified-start cleanup
destroy fallback stop
```

Set false immediately before native start begins.

### Destroy fallback

On:

```kotlin
repository.stop()
```

success during destroy, set:

```kotlin
nativeStopVerified.set(true)
```

### Required tests

1. pause → destroy does not stop again;
2. policy pause → destroy does not stop again;
3. successful destroy fallback marks verified;
4. failed destroy fallback remains unverified/quarantined.

---

## P1-006 — Fix initially policy-blocked startup auto-resume

### Problem

Initial policy block currently exits startup without always producing a completion point that can consume pending retry intent.

### Required solution

Either:

1. initial policy block returns a typed `StartupCompletion.PolicyBlocked`, handled by coordinator; or
2. the coordinator explicitly clears startup ownership before entering policy-paused state.

Preferred typed completion:

```kotlin
sealed interface StartupCompletion {
    data object VerifiedSuccess : StartupCompletion

    data class PolicyBlocked(
        val reason: String,
    ) : StartupCompletion

    ...
}
```

Coordinator handles it:

```text
startup ownership cleared
pausedByPolicy = true
repository.setPolicyBlocked(...)
pending retry checked only after ownership clear
```

### Required test

```text
initial StartOffer
network policy blocks before native start
one PolicyAllowed event
exactly one resume/start
no second network event
```

---

## P1-007 — Make log refresh generation cover both data and error

### Problem

The ViewModel protects the log list with generation ordering, but repository-level `logsError` can still be overwritten by an older request.

### Preferred fix

Move the native log fetch result into one typed value:

```kotlin
data class LogsRefreshResult(
    val logs: List<LogEntry>,
    val error: String?,
)
```

Repository method should not mutate shared error before generation ownership is checked.

Example:

```kotlin
suspend fun fetchRecentLogs(): LogsRefreshResult {
    return try {
        LogsRefreshResult(
            logs = bridge.recentLogs(),
            error = null,
        )
    } catch (error: Throwable) {
        LogsRefreshResult(
            logs = emptyList(),
            error = redactor.redact(...),
        )
    }
}
```

ViewModel:

```kotlin
val generation = ++refreshGeneration

val result =
    withContext(ioDispatcher) {
        repository.fetchRecentLogs()
    }

if (generation != refreshGeneration) {
    return@launch
}

_logs.value = result.logs
_logsError.value = result.error
```

### Required tests

1. older success cannot replace newer success;
2. older failure cannot replace newer success;
3. older success cannot clear newer failure;
4. visible error remains wired in UI.

---

## P1-008 — Fix network callback delivery failures

### Problem

`callbackFlow` paths use `trySend(current)` and ignore failure.

### Required behavior

At minimum, handle the result.

Example:

```kotlin
val sendResult = trySend(current)

if (sendResult.isFailure) {
    reporter.publishError(
        code = "network_policy_event_delivery_failed",
        message = "Network policy event could not be delivered",
    )
}
```

Prefer a design where lifecycle-relevant network state cannot be lost.

Since `_status` is already a `StateFlow`, consider collecting that directly instead of duplicating status delivery through a lossy callback channel.

### Required tests

1. delivery failure visible;
2. service can resynchronize from current policy state;
3. cancellation closes cleanly.

---

## P1-009 — Make pending retry invalidation explicit

Clear pending policy retry on:

```text
Stop
Pause
PolicyBlocked
StartOffer
AllowMeteredSession
Destroy
```

Do not rely only on generation mismatch.

Required helper:

```kotlin
private fun invalidatePendingPolicyRetry() {
    pendingPolicyResumeGeneration.set(null)
}
```

Use it at each lifecycle intent boundary.

`handleRetryPolicyResume()` must also verify:

```kotlin
if (!pausedByPolicy.get()) {
    return
}
```

### Required tests

1. pending retry then Stop → no restart;
2. pending retry then Pause → no restart;
3. pending retry then PolicyBlocked → no restart;
4. pending retry then StartOffer → old retry cannot restart;
5. valid retry runs once.

---

## P1-010 — Fix typed StartOutcome architecture or stop claiming it exists

### Problem

`StartOutcome.kt` exists, but JNI still returns primitive status/result shapes and classification remains post-hoc.

Choose one:

### Option A — implement typed boundary

Convert bridge API to return typed `StartOutcome`.

Example Kotlin-facing bridge:

```kotlin
interface TunnelBridge {
    suspend fun startOffer(
        configPath: String,
        identityBytes: ByteArray,
    ): StartOutcome
}
```

JNI may still transport an encoded integer/JSON internally, but the bridge boundary must decode it once.

### Option B — remove the P2 implementation claim

If typed JNI boundary is not desired now:

- keep current `Result<Unit>`;
- delete misleading comments/status claiming P2-002 is implemented.

Do not preserve an architectural completion claim that is false.

---

# 6. P2 requirements

## P2-001 — Strengthen CI final signoff

Remote CI should enforce the same Android quality gates claimed in signoff.

Add to Android workflow:

```bash
./gradlew --no-daemon detekt
./gradlew --no-daemon ktlintCheck
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
```

Do not rely only on local claims.

### Final signoff record must include

```text
final production SHA
workflow run URL/id
workflow head SHA
focused lifecycle tests
focused forwards/reset tests
focused logs/preferences tests
Android full
lint
detekt
ktlint
Rust fmt
Rust clippy debug
Rust clippy release
Rust test
service/package checks
```

Use only:

```text
PASS
FAIL
NOT RUN: exact reason
```

---

# 7. Required implementation order

Use this order.

## Stage 1 — stop dangerous reset behavior

```text
P0-001 real transactional reset
```

Do this first because the current implementation can destroy or misreport data.

## Stage 2 — runtime quarantine and lifecycle command safety

```text
P0-002 quarantine invariant
P0-003 coordinator ownership/non-lossy delivery
```

## Stage 3 — retry state machine

```text
P0-004 one-event completion-driven retry
```

## Stage 4 — failure boundaries and secrets

```text
P0-005 critical failure boundaries
P0-006 identity zeroization
P0-007 preference false-success elimination
P0-008 metered allowance lifetime
```

## Stage 5 — forwards repository integrity

```text
P1-001 atomic mutation receipts
P1-002 remove mutation bypasses
```

## Stage 6 — persistence and status truth

```text
P1-003 config write serialization
P1-004 terminal peer/schema fallback fixes
P1-005 nativeStopVerified
P1-006 initial policy-block auto-resume
```

## Stage 7 — logs/network/retry cleanup

```text
P1-007 log generation ordering
P1-008 network event delivery
P1-009 explicit pending retry invalidation
```

## Stage 8 — architecture claims and CI

```text
P1-010 typed StartOutcome claim
P2-001 CI signoff enforcement
```

---

# 8. Validation gates

## Android focused lifecycle

```bash
cd android

./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundServiceStopFailureTest' \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundService*' \
  --tests 'com.phillipchin.webrtctunnel.data.TunnelRepositoryTest' \
  --rerun-tasks
```

Run three fresh times after lifecycle/race changes.

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

## Full Android

```bash
./gradlew --no-daemon detekt
./gradlew --no-daemon ktlintCheck
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
./gradlew --no-daemon check
```

## Rust

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

# 9. Final signoff conditions

Do not sign off until all are true:

- transactional reset restores exact previous state on failure;
- a `Result.failure` cannot be misclassified as stage success;
- quarantine blocks every start/resume path;
- STOP remains available during quarantine;
- verified STOP is the only way to clear quarantine;
- coordinator cannot outlive service;
- STOP cannot be dropped because a channel is full;
- cancellation propagates from every child coroutine;
- one later PolicyAllowed event is sufficient;
- no repeated network-event test workaround exists;
- status poll failures are visible;
- startup preparation failures are visible;
- every plaintext identity buffer is wiped or explicitly transferred;
- preference writes never show false success;
- metered allowance clears on all terminal/failure paths;
- forwards mutation receipt is captured atomically;
- no raw forwards mutation bypass remains;
- config writes use one serialized atomic boundary;
- null/unknown native schema values fail visibly;
- terminal states clear active peer;
- log data and log error are generation-consistent;
- CI runs the same Android gates claimed in signoff;
- final SHA and fresh CI head SHA are recorded.

If any item cannot be validated, mark it:

```text
NOT RUN: exact reason
```

Do not infer PASS from older runs.
