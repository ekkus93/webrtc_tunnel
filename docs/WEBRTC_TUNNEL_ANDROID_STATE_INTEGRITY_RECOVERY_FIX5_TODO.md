# WebRTC Tunnel Android State-Integrity Recovery Fix 5 TODO

This TODO implements `WEBRTC_TUNNEL_ANDROID_STATE_INTEGRITY_RECOVERY_FIX5_SPEC.md`.

The code is close, but not signed off. This pass fixes the remaining exact bugs and
test proof gaps from the latest review. Do not redesign the app.

---

# 0. Work discipline

For every task:

```text
1. inspect current code
2. add or strengthen the exact focused test
3. implement the smallest fix
4. run focused tests
5. run lint/format if relevant
6. commit one scoped change
```

Hard rules:

```text
no assertTrue(true) proof tests
no Log.w-only required diagnostics
no raw Throwable with possibly secret message in redacted diagnostics
no pending retry invalidated before the branch that needs to consume it
no rollback-failure test unless rollback actually fails
```

---

# P0 tasks

## P0-001 — Fix NativeFailure pending retry consumption order

**Priority:** P0

**Files:**

```text
TunnelForegroundService.kt
TunnelForegroundService tests
```

### Problem

`handleStartupCompleted()` invalidates pending retry before the `NativeFailure` branch
reads it. This likely breaks one-event policy retry.

### Step 1 — remove unconditional invalidation before `when`

Find code like:

```kotlin
invalidatePendingPolicyRetry()

when (outcome) {
    is StartOutcome.NativeFailure -> {
        val pending =
            pendingPolicyResumeGeneration.getAndSet(null)
        ...
    }
}
```

This is wrong.

### Step 2 — handle invalidation per branch

Use this shape:

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
            handleNativeFailureAfterStartup(
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

### Step 3 — NativeFailure helper consumes pending first

```kotlin
private suspend fun handleNativeFailureAfterStartup(
    generation: Long,
    error: TunnelError,
) {
    val pending =
        pendingPolicyResumeGeneration
            .getAndSet(null)

    if (
        pending == generation &&
            pausedByPolicy.get()
    ) {
        submitLifecycleCommand(
            LifecycleCommand.RetryPolicyResume(
                expectedGeneration = generation,
            ),
        )
        return
    }

    reporter.publishError(
        code = error.code,
        message = error.message,
    )
}
```

Use project-specific error publication if different.

### Tests

- [ ] `nativeFailureConsumesPendingPolicyRetryBeforeInvalidation`
- [ ] `nativeFailureWithStalePendingRetryDoesNotResume`
- [ ] `nativeFailurePendingRetryRunsExactlyOnce`
- [ ] existing one-event policy retry test still passes without repeated network events

### Acceptance

- [ ] NativeFailure pending retry is read before invalidation.
- [ ] one-event retry still works.
- [ ] stale pending retry does not resume.
- [ ] retry runs exactly once.

---

## P0-002 — Replace meaningless destroy pending-retry test

**Priority:** P0

**Files:**

```text
PendingRetryInvalidationTest.kt
TunnelForegroundService tests
```

### Problem

Current destroy retry test uses:

```kotlin
assertTrue("...", true)
```

### Required replacement

The test must assert native start count does not increase and pending retry is cleared
or impossible to consume.

Suggested shape:

```kotlin
@Test
fun pendingRetryThenDestroyDoesNotRestart() =
    runTest {
        val bridge =
            FakeTunnelBridge()

        val service =
            startServiceWithBridge(bridge)

        service.testHooks.setPausedByPolicy(true)

        val generation =
            service.testHooks.lifecycleGeneration()

        service.testHooks.setPendingPolicyResumeGeneration(
            generation,
        )

        val startCallsBeforeDestroy =
            bridge.startOfferCalls

        service.onDestroy()

        service.testHooks.submitLifecycleCommandIfPossible(
            LifecycleCommand.RetryPolicyResume(
                expectedGeneration = generation,
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

Use actual fake/test hook names. If direct hooks do not exist, assert through observable
state and native start counts.

### Acceptance

- [ ] no `assertTrue(true)`;
- [ ] native start count is asserted;
- [ ] pending retry cleared or late retry impossible.

---

## P0-003 — Wire real network event delivery reporter in production

**Priority:** P0

**Files:**

```text
NetworkPolicyManager.kt
AppDependencies.kt or composition root
TunnelForegroundService.kt
network policy tests
```

### Step 1 — change reporter payload to redacted string

Prefer:

```kotlin
interface NetworkPolicyEventReporter {
    fun reportNetworkPolicyEventDeliveryFailed(
        message: String,
    )
}
```

### Step 2 — production reporter

Example:

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

### Step 3 — wire real reporter

Wrong for production:

```kotlin
NetworkPolicyManager(context)
```

because it uses no-op reporter.

Correct:

```kotlin
NetworkPolicyManager(
    context = context,
    reporter =
        AppNetworkPolicyEventReporter(
            reporter = appReporter,
        ),
)
```

Use actual dependency names.

### Tests

- [ ] production dependency wiring does not use `NoopNetworkPolicyEventReporter`;
- [ ] active delivery failure reaches fake reporter;
- [ ] expected close does not report.

---

## P0-004 — Fix network event delivery redaction

**Priority:** P0

**Files:**

```text
NetworkPolicyManager.kt
network policy tests
```

### Implementation

Do not pass raw Throwable to reporter or `Log.w`.

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

Emit:

```kotlin
private fun ProducerScope<NetworkPolicyStatus>
    .emitPolicyStatus(
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

### Tests

- [ ] reporter message redacts IP/password/token;
- [ ] original raw secret does not appear in message;
- [ ] expected close produces no diagnostic.

---

# P1 tasks

## P1-001 — Remove `runCatching` from TransactionalReset setup paths

**Priority:** P1

**Files:**

```text
TransactionalReset.kt
TransactionalResetCoordinatorTest.kt
```

### Reset setup input

Replace `runCatching` with:

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

### Restore setup input

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

### Tests

- [ ] cancellation during setup reset propagates;
- [ ] cancellation during setup rollback propagates;
- [ ] setup reset failure returns `ResetStageResult.Failure`;
- [ ] setup rollback failure returns `RollbackStageResult.Failure`.

---

## P1-002 — Publish visible error for `handlePolicyAllowed` preference-read failure

**Priority:** P1

**Files:**

```text
TunnelForegroundService.kt
TunnelForegroundService tests
```

### Implementation

Replace `runCatching` preference read with explicit try/catch:

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

    // existing resume logic
}
```

### Tests

- [ ] preference read failure publishes `policy_allowed_preference_read_failed`;
- [ ] preference read failure invalidates pending retry;
- [ ] preference read failure does not call native start/resume;
- [ ] cancellation propagates.

---

## P1-003 — Catch unexpected lifecycle command exceptions visibly

**Priority:** P1

**Files:**

```text
TunnelLifecycleCoordinator.kt
TunnelLifecycleCoordinator tests
```

### Implementation

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

If known exception branches already exist, keep them before the generic `Throwable`
catch.

### Tests

- [ ] unexpected handler exception publishes `lifecycle_command_failed`;
- [ ] processor continues with later command after unexpected exception;
- [ ] cancellation still stops processor.

---

## P1-004 — Add true reset early-stage tests

**Priority:** P1

**Files:**

```text
TransactionalResetCoordinatorTest.kt
```

### Required tests

- [ ] `resetStopsImmediatelyWhenConfigStageFails`
- [ ] `resetStopsImmediatelyWhenSetupStageFails`

Assertions:

```text
Config failure -> setup reset not called, forwards reset not called
Setup failure -> forwards reset not called
```

Use recording fakes. Do not fail only the final Forwards stage.

---

## P1-005 — Add true rollback-failure test

**Priority:** P1

**Files:**

```text
TransactionalResetCoordinatorTest.kt
```

### Required scenario

```text
snapshot succeeds
config reset succeeds
setup reset fails
config rollback fails
```

### Required assertion

```kotlin
val failed =
    result as ResetResult.Failed

assertTrue(
    failed.rollback.any {
        it is RollbackStageResult.Failure &&
            it.stage == ResetStage.Config
    },
)
```

Do not use a forwards reset failure as a substitute.

---

## P1-006 — Make delete-failure tests honest

**Priority:** P1

**Files:**

```text
ConfigRepositoryTest.kt
TransactionalResetCoordinatorTest.kt
```

If current tests claim delete failure but do not force delete failure, either:

1. implement a reliable fake file operation abstraction; or
2. create a reliable CI-safe failure case; or
3. rename tests so they only claim the success behavior they actually test.

Do not keep misleading test names.

---

# P2 tasks

## P2-001 — Record final signoff evidence

After fixes, record:

- [ ] `git rev-parse HEAD`;
- [ ] GitHub Actions workflow URL/id;
- [ ] workflow head SHA;
- [ ] lifecycle focused result;
- [ ] config/reset result;
- [ ] logs/preferences/network result;
- [ ] setup/identity result;
- [ ] full Android result;
- [ ] every unavailable check has `NOT RUN: exact reason`.

---

# Validation commands

## Lifecycle

```bash
cd android

./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundServiceStopFailureTest' \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundService*' \
  --tests 'com.phillipchin.webrtctunnel.data.TunnelRepositoryTest' \
  --rerun-tasks
```

## Config/reset

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.data.ConfigRepositoryTest' \
  --tests 'com.phillipchin.webrtctunnel.data.TransactionalResetCoordinatorTest' \
  --tests 'com.phillipchin.webrtctunnel.data.ForwardsRepositoryTest' \
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

## Setup identity

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.SetupSaveControllerTest' \
  --tests 'com.phillipchin.webrtctunnel.security.IdentityRepositoryTest' \
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

- [ ] NativeFailure pending retry consumed before invalidation.
- [ ] One-event policy retry still works.
- [ ] Destroy pending retry test has real assertions.
- [ ] Production NetworkPolicyManager uses real reporter.
- [ ] Network delivery diagnostic uses redacted string, not raw Throwable.
- [ ] Network delivery redaction tests pass.

## P1

- [ ] TransactionalReset setup reset has no `runCatching`.
- [ ] TransactionalReset setup rollback has no `runCatching`.
- [ ] PolicyAllowed preference read failure is visible.
- [ ] Lifecycle coordinator catches unexpected command exceptions visibly.
- [ ] Reset config-stage failure test proves setup/forwards not called.
- [ ] Reset setup-stage failure test proves forwards not called.
- [ ] Rollback failure test simulates actual rollback failure.
- [ ] Delete failure tests are real or renamed honestly.

## P2

- [ ] Fresh signoff evidence recorded.
