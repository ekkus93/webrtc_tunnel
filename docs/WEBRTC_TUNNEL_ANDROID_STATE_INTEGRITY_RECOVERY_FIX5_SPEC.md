# WebRTC Tunnel Android State-Integrity Recovery Fix 5 Spec

## 1. Purpose

This spec defines the fifth Android-focused state-integrity recovery pass for the
WebRTC Tunnel app.

The latest code is close, but it is not signed off. Fix 4 resolved several important
items, including:

- `performStartupAttempt()` no longer using `runCatching`;
- `StartOutcome.Aborted` carrying a reason;
- active config write failure becoming fatal to startup;
- `writeConfig()` routing through the atomic writer;
- `recentLogs()` returning `LogsFetchResult`;
- config snapshot preserving existence separately from contents;
- native mode missing/future tests;
- basic network policy reporter abstraction.

The remaining work is now small but important. The latest review found one likely
runtime logic bug and several proof/diagnostic gaps. This pass should **not** redesign
the app. It should fix the exact remaining defects and add tests that prove the real
invariants.

---

## 2. Current signoff blockers

The Android build is not signed off because:

1. `handleStartupCompleted()` invalidates pending policy retry before the
   `NativeFailure` branch tries to consume it. This likely breaks one-event retry.
2. A pending retry destroy test still uses `assertTrue(..., true)` and proves nothing.
3. `NetworkPolicyEventReporter` exists, but production wiring still uses the no-op
   reporter, so delivery failure is not app-visible in production.
4. Network policy event redaction mutates or wraps the original throwable and can still
   leak the original message through `Log.w(..., throwable)`.
5. Transactional reset tests still do not prove early-stage stop:
   - config-stage failure prevents setup/forwards reset;
   - setup-stage failure prevents forwards reset.
6. Transactional reset tests still do not prove a true rollback failure.
7. `TransactionalReset` setup reset/restore paths still use `runCatching`.
8. `handlePolicyAllowed()` preference-read failure invalidates retry but does not
   publish a visible diagnostic.
9. `TunnelLifecycleCoordinator.processCommand()` may still allow unexpected exceptions
   to kill the processor without a visible lifecycle error.
10. Some final signoff evidence is only reported in the TODO file and could not be
    independently verified from the uploaded zip.

---

## 3. Non-negotiable invariants

### 3.1 Pending retry must be consumed before invalidation

For the `NativeFailure` startup completion path, the coordinator must check and consume
the pending policy retry before clearing it.

Wrong:

```text
invalidate pending retry
then check pending retry
```

Correct:

```text
read/consume pending retry
if it matches current generation -> submit retry
else -> publish visible failure / invalidate
```

### 3.2 Tests must prove behavior

A test that ends with:

```kotlin
assertTrue("...", true)
```

does not prove anything and must not be used as evidence.

### 3.3 Required diagnostics must be production diagnostics

A no-op reporter in production does not satisfy “diagnostic reaches reporter.”

If a required diagnostic exists only in `Log.w`, the task is not complete.

### 3.4 Redaction must not preserve the original secret-bearing throwable message

Do not pass an original throwable with an unredacted message to:

```kotlin
Log.w(tag, message, throwable)
```

or to reporter payloads.

Use a redacted string or a sanitized exception with no original message.

### 3.5 Transactional reset tests must simulate the exact failure

A rollback-failure test must make the rollback operation fail. A final-stage reset
failure is not a rollback-failure test.

### 3.6 Reset mutation/rollback paths use explicit try/catch

No `runCatching` in transactional reset mutation/rollback paths that can affect
persistent state.

---

# 4. P0 requirements

## P0-001 — Fix pending policy retry consumption order

### Problem

`handleStartupCompleted()` currently invalidates pending policy retry before
`StartOutcome.NativeFailure` tries to consume it. This makes the pending retry path
unreachable.

### Required behavior

When startup fails with a native failure while a policy retry is pending for that same
generation:

```text
pending retry is consumed
RetryPolicyResume is submitted exactly once
no extra network event is required
pending retry is cleared after consumption
```

### Required implementation

Do not unconditionally call `invalidatePendingPolicyRetry()` before the `when`.

Use branch-specific handling.

Recommended pattern:

```kotlin
private suspend fun handleStartupCompleted(
    command: LifecycleCommand.StartupCompleted,
) {
    val generation =
        command.generation

    if (lifecycleGeneration.get() != generation) {
        return
    }

    activeStartup = null

    val outcome =
        command.outcome

    if (outcome !is StartOutcome.VerifiedSuccess) {
        clearTemporaryMeteredAllowance()
    }

    when (outcome) {
        is StartOutcome.VerifiedSuccess -> {
            invalidatePendingPolicyRetry()
            handleVerifiedStartupSuccess(generation)
        }

        is StartOutcome.NativeFailure -> {
            handleNativeStartFailureWithPendingRetry(
                generation = generation,
                error = outcome.error,
            )
        }

        is StartOutcome.VerificationFailure -> {
            invalidatePendingPolicyRetry()
            handleVerificationFailure(
                generation = generation,
                error = outcome.error,
            )
        }

        is StartOutcome.PolicyBlocked -> {
            invalidatePendingPolicyRetry()
            handleStartupPolicyBlocked(
                generation = generation,
                reason = outcome.reason,
            )
        }

        is StartOutcome.Aborted -> {
            invalidatePendingPolicyRetry()
            handleStartupAborted(
                generation = generation,
                reason = outcome.reason,
            )
        }

        is StartOutcome.UnexpectedFailure -> {
            invalidatePendingPolicyRetry()
            handleUnexpectedStartupFailure(
                generation = generation,
                error = outcome.error,
            )
        }
    }
}
```

Native failure helper:

```kotlin
private suspend fun handleNativeStartFailureWithPendingRetry(
    generation: Long,
    error: TunnelError,
) {
    val pending =
        pendingPolicyResumeGeneration
            .getAndSet(null)

    if (pending == generation && pausedByPolicy.get()) {
        submitLifecycleCommand(
            LifecycleCommand.RetryPolicyResume(
                expectedGeneration = generation,
            ),
        )
        return
    }

    reporter.publishError(
        code = "native_start_failed",
        message = error.message,
    )
}
```

If `pausedByPolicy` should not be required in this exact branch because the pending
retry itself proves policy pause, document that and test it. The safer default is to
require it.

### Required tests

1. `nativeFailureConsumesPendingPolicyRetryBeforeInvalidation`
2. `nativeFailureWithoutPendingRetryPublishesFailure`
3. `nativeFailureWithStalePendingRetryDoesNotResume`
4. `nativeFailurePendingRetryRunsExactlyOnce`
5. existing one-event auto-resume test still passes without repeated events

### Acceptance

- Pending retry is not cleared before the native-failure branch can consume it.
- One-event retry path works.
- Retry does not run for stale generation.
- Retry does not run more than once.

---

## P0-002 — Replace meaningless pending retry destroy test

### Problem

A pending retry destroy test uses:

```kotlin
assertTrue("destroy should complete without triggering retry restart", true)
```

This does not prove that destroy invalidates pending retry.

### Required test behavior

The test must prove all of:

```text
pending retry exists before destroy
destroy invalidates it
late retry/start signal does not call native start
native start count remains unchanged
```

### Suggested test shape

```kotlin
@Test
fun pendingRetryThenDestroyDoesNotRestart() =
    runTest {
        val bridge =
            FakeTunnelBridge()

        val service =
            startServiceWithBridge(bridge)

        // Put service into state where a policy retry is pending.
        service.testHooks.setPausedByPolicy(true)
        service.testHooks.setPendingPolicyResumeGeneration(
            service.testHooks.lifecycleGeneration(),
        )

        val startCallsBeforeDestroy =
            bridge.startOfferCalls

        service.onDestroy()

        // Attempt to deliver a late retry after destroy.
        service.testHooks.submitLifecycleCommandIfPossible(
            LifecycleCommand.RetryPolicyResume(
                expectedGeneration =
                    service.testHooks.lifecycleGeneration(),
            ),
        )

        advanceUntilIdle()

        assertEquals(
            startCallsBeforeDestroy,
            bridge.startOfferCalls,
        )

        assertNull(
            service.testHooks.pendingPolicyResumeGeneration(),
        )
    }
```

Use the project’s actual fake/service/test hook names. If direct test hooks are not
available, use observable behavior:

```text
no new native start
service status remains stopped/destroyed
command processor closed
```

### Acceptance

- No `assertTrue(true)`.
- Test proves native start count does not increase.
- Test proves pending retry is cleared or impossible to consume.

---

## P0-003 — Wire real production network policy event diagnostics

### Problem

`NetworkPolicyEventReporter` exists, but production code constructs
`NetworkPolicyManager` with the default no-op reporter. Therefore event delivery
failure is still not app-visible in production.

### Required behavior

In production, failed network policy event delivery must publish a diagnostic through
the app’s existing reporter/log event/status mechanism.

### Required implementation option A — pass reporter into manager

If the app already has a diagnostics/reporter dependency:

```kotlin
class AppNetworkPolicyEventReporter(
    private val reporter: AppErrorReporter,
) : NetworkPolicyEventReporter {
    override fun reportNetworkPolicyEventDeliveryFailed(
        message: String,
    ) {
        reporter.publishError(
            code = "network_policy_event_delivery_failed",
            message = message,
        )
    }
}
```

Wire:

```kotlin
networkPolicyManager =
    NetworkPolicyManager(
        context = context.applicationContext,
        reporter =
            AppNetworkPolicyEventReporter(
                reporter = appReporter,
            ),
    )
```

### Required implementation option B — service-level reporter

If only the service has the reporter, create manager there:

```kotlin
val manager =
    NetworkPolicyManager(
        context = applicationContext,
        reporter = object : NetworkPolicyEventReporter {
            override fun reportNetworkPolicyEventDeliveryFailed(
                message: String,
            ) {
                reporter.publishError(
                    code = "network_policy_event_delivery_failed",
                    message = message,
                )
            }
        },
    )
```

### Reporter payload should be redacted string, not Throwable

Prefer:

```kotlin
interface NetworkPolicyEventReporter {
    fun reportNetworkPolicyEventDeliveryFailed(
        message: String,
    )
}
```

over:

```kotlin
fun reportNetworkPolicyEventDeliveryFailed(cause: Throwable?)
```

because Throwable can carry secrets and is harder to safely redact.

### Acceptance

- Production `NetworkPolicyManager` is not using `NoopNetworkPolicyEventReporter`.
- Failed active delivery reaches app diagnostic/event reporter.
- No secret-bearing raw Throwable is sent to reporter.

---

## P0-004 — Fix network event delivery redaction

### Problem

The current redaction approach can preserve the original throwable message. Passing a
Throwable to `Log.w(..., throwable)` may print unredacted content.

### Required implementation

Convert cause to redacted string immediately.

```kotlin
private fun redactedDeliveryFailureMessage(
    cause: Throwable?,
): String {
    val raw =
        cause?.message
            ?: "Network policy event could not be delivered"

    return SensitiveDataRedactor.redactText(raw)
}
```

Emit helper:

```kotlin
private fun ProducerScope<NetworkPolicyStatus>.emitPolicyStatus(
    status: NetworkPolicyStatus,
) {
    val result =
        trySend(status)

    if (result.isFailure) {
        val cause =
            result.exceptionOrNull()

        if (isExpectedChannelClose(cause)) {
            return
        }

        val message =
            redactedDeliveryFailureMessage(cause)

        Log.w(
            TAG,
            "Network policy event delivery failed: $message",
        )

        reporter.reportNetworkPolicyEventDeliveryFailed(
            message,
        )
    }
}
```

Do not pass `cause` to `Log.w` unless you have proven its message is redacted or empty.

### Required tests

1. sensitive IP/token/password in cause message is not present in reporter message;
2. sensitive value is not present in logged/constructed diagnostic message if testable;
3. expected channel close produces no diagnostic.

---

# 5. P1 requirements

## P1-001 — Convert remaining TransactionalReset `runCatching` mutation paths

### Problem

Transactional reset setup reset/restore still use `runCatching`.

### Required implementation

Use explicit `try/catch`.

Reset setup input:

```kotlin
private fun resetSetupInputStage():
    ResetStageResult {
    return try {
        configRepository.saveSetupInput(
            SetupConfigInput(),
        )

        ResetStageResult.Success(
            ResetStage.SetupInput,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        ResetStageResult.Failure(
            stage = ResetStage.SetupInput,
            reason =
                SensitiveDataRedactor.redactText(
                    error.message
                        ?: "Failed to reset setup input",
                ),
        )
    }
}
```

Restore setup input:

```kotlin
private fun restoreSetupInput(
    setupInput: SetupConfigInput,
): RollbackStageResult {
    return try {
        configRepository.saveSetupInput(
            setupInput,
        )

        RollbackStageResult.Success(
            ResetStage.SetupInput,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        RollbackStageResult.Failure(
            stage = ResetStage.SetupInput,
            reason =
                SensitiveDataRedactor.redactText(
                    error.message
                        ?: "Failed to restore setup input",
                ),
        )
    }
}
```

Use suspend variants if current repository methods are suspend.

### Required tests

1. cancellation during setup reset propagates;
2. cancellation during setup rollback propagates;
3. setup reset failure returns `ResetStageResult.Failure`;
4. setup rollback failure returns `RollbackStageResult.Failure`.

---

## P1-002 — Publish visible diagnostic for `handlePolicyAllowed()` preference-read failure

### Problem

`handlePolicyAllowed()` catches preference-read failure and only invalidates pending
retry. That is too quiet for lifecycle policy state.

### Required behavior

On non-cancellation failure:

```text
invalidate pending retry
publish redacted error code policy_allowed_preference_read_failed
do not resume
```

### Required implementation

```kotlin
private suspend fun handlePolicyAllowed() {
    requireRuntimeStartAllowed()
        .getOrElse { error ->
            publishQuarantineBlocked(error)
            return
        }

    val prefs =
        try {
            configRepository
                .preferences
                .first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
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

    // existing resume logic...
}
```

### Required tests

1. preference read failure publishes `policy_allowed_preference_read_failed`;
2. preference read failure invalidates pending retry;
3. preference read failure does not call native start/resume;
4. cancellation propagates.

---

## P1-003 — Catch unexpected lifecycle command exceptions visibly

### Problem

`TunnelLifecycleCoordinator.processCommand()` may not catch all unexpected exceptions
after rethrowing cancellation. An unexpected handler bug should not silently kill the
processor without a visible lifecycle error.

### Required behavior

```kotlin
private suspend fun processCommand(
    command: LifecycleCommand,
) {
    try {
        operations.handleCommand(command)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        operations.publishLifecycleError(
            code = "lifecycle_command_failed",
            error = error,
        )
    }
}
```

If the coordinator currently has separate catches for known exceptions, keep those
first, then add final `catch (error: Throwable)`.

### Required tests

1. unexpected command handler exception publishes `lifecycle_command_failed`;
2. command processor continues to process a later command after unexpected failure;
3. cancellation still propagates/stops processor.

---

## P1-004 — Add true transactional reset early-stage tests

### Required config-stage failure test

```kotlin
@Test
fun resetStopsImmediatelyWhenConfigStageFails() =
    runTest {
        val setup =
            RecordingSetupStore()

        val forwards =
            RecordingForwardsRepository()

        val coordinator =
            newCoordinator(
                configRepository =
                    configRepositoryFailingReset(),
                setupStore = setup,
                forwardsRepository = forwards,
            )

        val result =
            coordinator.resetConfiguration()

        assertTrue(result is ResetResult.Failed)
        assertFalse(setup.resetCalled)
        assertFalse(forwards.resetCalled)
    }
```

### Required setup-stage failure test

```kotlin
@Test
fun resetStopsImmediatelyWhenSetupStageFails() =
    runTest {
        val forwards =
            RecordingForwardsRepository()

        val coordinator =
            newCoordinator(
                configRepository =
                    configRepositoryFailingSetupReset(),
                forwardsRepository = forwards,
            )

        val result =
            coordinator.resetConfiguration()

        assertTrue(result is ResetResult.Failed)
        assertFalse(forwards.resetCalled)
    }
```

Use actual fake names. The important part is proving later stages do not run.

---

## P1-005 — Add true rollback-failure test

### Required test scenario

Simulate:

```text
snapshot succeeds
config reset succeeds
setup reset fails
config rollback fails
```

### Required assertions

```kotlin
val failed =
    result as ResetResult.Failed

assertTrue(
    failed.rollback.any {
        it is RollbackStageResult.Failure &&
            it.stage == ResetStage.Config
    },
)

assertFalse(result is ResetResult.Success)
```

Do not use final forwards reset failure as a substitute.

---

## P1-006 — Strengthen config delete failure tests if practical

Implementation uses `Files.deleteIfExists()`, which is correct. If tests cannot easily
force `Files.deleteIfExists()` failure portably, do not fake a pass with misleading
test names.

Acceptable options:

1. use a fake file operation abstraction for delete in tests;
2. create a directory/permission scenario if reliable on CI;
3. rename tests so they do not claim delete failure if they only cover success.

The key requirement: do not claim delete failure coverage unless delete actually fails.

---

# 6. P2 requirements

## P2-001 — Signoff evidence must be independently reproducible

Claude Code should record:

```text
git rev-parse HEAD
GitHub Actions workflow URL
workflow head SHA
focused lifecycle test command output
config/reset test command output
logs/preferences/network test command output
setup/identity test command output
full Android command output
```

If a check was not run:

```text
NOT RUN: exact reason
```

---

## 7. Validation commands

### Lifecycle

```bash
cd android

./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundServiceStopFailureTest' \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundService*' \
  --tests 'com.phillipchin.webrtctunnel.data.TunnelRepositoryTest' \
  --rerun-tasks
```

### Config/reset

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.data.ConfigRepositoryTest' \
  --tests 'com.phillipchin.webrtctunnel.data.TransactionalResetCoordinatorTest' \
  --tests 'com.phillipchin.webrtctunnel.data.ForwardsRepositoryTest' \
  --rerun-tasks
```

### Logs/preferences/network

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.LogsViewModelTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.NetworkPolicyViewModelTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.SettingsViewModelTest' \
  --rerun-tasks
```

### Setup identity

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.SetupSaveControllerTest' \
  --tests 'com.phillipchin.webrtctunnel.security.IdentityRepositoryTest' \
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
NativeFailure pending retry consumed before invalidation
one-event policy retry test proves retry still works
destroy pending retry test has real assertions
production NetworkPolicyManager uses real reporter
network delivery redaction does not log/pass raw Throwable message
TransactionalReset setup reset/restore have no runCatching
handlePolicyAllowed preference read failure publishes visible diagnostic
TunnelLifecycleCoordinator catches unexpected command exceptions visibly
reset config-stage failure test proves setup/forwards not called
reset setup-stage failure test proves forwards not called
rollback failure test simulates actual rollback failure
delete failure tests are real or honestly renamed
fresh validation evidence recorded
```
