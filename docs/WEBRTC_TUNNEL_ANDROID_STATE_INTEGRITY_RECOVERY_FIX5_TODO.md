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

- [x] `nativeFailureConsumesPendingPolicyRetryAndResumesExactlyOnce` (covers
      consumption, exactly-once, and the original "RunsExactlyOnce" item in one test —
      `TunnelForegroundServiceOrderingTest.kt`)
- [x] `nativeFailureWithoutPendingRetryPublishesFailure`
- [x] `nativeFailurePendingRetryWithoutPausedByPolicyDoesNotResume` (the actual "stale"
      condition per the answered Q1: pending generation matches but `pausedByPolicy` no
      longer holds — a mismatched-generation case can't occur through real command flow
      since `handleStartupCompleted` already early-returns on generation mismatch)
- [x] existing one-event policy retry test (`failedAutoResumeLeavesPausedByPolicyTrueForNextRetry`)
      still passes without repeated network events

### Acceptance

- [x] NativeFailure pending retry is read before invalidation.
- [x] one-event retry still works.
- [x] stale/invalidated pending retry does not resume (pausedByPolicy no longer true).
- [x] retry runs exactly once.

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

**Implemented via observable state** (no `submitLifecycleCommandIfPossible`-style hook
exists or was added, per the answered Q8): the test now establishes a genuine pending
retry via the same PolicyAllowed-arrives-during-in-flight-startup race P0-001 fixes,
destroys the service while that startup is still unresolved, and asserts native start
count is unchanged even after a further late trigger.

### Acceptance

- [x] no `assertTrue(true)`;
- [x] native start count is asserted;
- [x] pending retry cleared or late retry impossible.

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

**Implemented differently, per an explicit design-fork decision made with the user
mid-implementation**: adding a `diagnosticEventBus`/reporter-wired `NetworkPolicyManager`
as a new `AppDependencies` constructor parameter tripped detekt's `LongParameterList`
(max 6; this app was already at exactly 6). Rather than an invasive constructor
restructure touching ~10 test call sites, `NetworkPolicyManager` now always owns and
exposes its own `AppDiagnosticEventBus` (`diagnosticEvents`) directly — there is no
`NetworkPolicyEventReporter`/`NoopNetworkPolicyEventReporter`/`AppNetworkPolicyEventReporter`
interface layer at all, so there is structurally no no-op reporter left to accidentally
wire into production. `TunnelForegroundService` collects from
`networkPolicyManager.diagnosticEvents.events` while alive and relays into its own
`reporter.publishError`.

### Tests

- [x] production wiring does not use a no-op reporter — structurally guaranteed (no
      no-op reporter exists); `AppDependenciesNetworkPolicyWiringTest` proves
      `AppDependencies(context).networkPolicyManager.diagnosticEvents` is live and reachable
- [x] active delivery failure reaches the diagnostic bus (`AppNetworkPolicyEventReporterTest`
      equivalent folded into `NetworkPolicyManagerTest`/`AppDependenciesNetworkPolicyWiringTest`)
- [x] expected close does not report (`expectedCloseCancellationExceptionDoesNotReport`,
      `expectedCloseClosedSendChannelDoesNotReport`, pre-existing and still passing)

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

- [x] reporter message redacts password/token/api_key (`deliveryFailureRedactsSensitiveData`,
      `diagnosticIsRedactedIfCauseContainsSensitiveValue`) — `SensitiveDataRedactor` does not
      have an IP-address rule, so that specific case isn't covered; not required by FIX5
- [x] original raw secret does not appear in message
      (`redactedMessageDoesNotPreserveOriginalThrowableIdentityOrMessage`)
- [x] expected close produces no diagnostic (pre-existing tests, still passing)

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

- [x] cancellation during setup reset propagates (`cancellationDuringSetupResetPropagates`);
- [x] cancellation during setup rollback propagates (`cancellationDuringSetupRollbackPropagates`);
- [x] setup reset failure returns `ResetStageResult.Failure` (`setupResetFailureReturnsResetStageFailure`);
- [x] setup rollback failure returns `RollbackStageResult.Failure` (`setupRollbackFailureReturnsRollbackStageFailure`).

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

- [x] preference read failure publishes `policy_allowed_preference_read_failed` and
      does not call native start/resume, in one test
      (`policyAllowedPreferenceReadFailurePublishesVisibleDiagnosticAndDoesNotResume`);
      invalidation is implicit (the retry path is never taken)
- [x] cancellation propagates, i.e. is not converted into the failure diagnostic
      (`policyAllowedPreferenceReadCancellationDoesNotPublishFailureDiagnostic`)

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

- [x] unexpected handler exception publishes `lifecycle_command_failed`
      (`unexpectedExceptionPublishesLifecycleCommandFailed`, new `TunnelLifecycleCoordinatorTest.kt`);
- [x] processor continues with later command after unexpected exception
      (`processorContinuesWithLaterCommandAfterUnexpectedException`);
- [x] cancellation still stops processor
      (`cancellationExceptionFromHandlerStillStopsProcessorAndIsNotReportedAsFailure`).

---

## P1-004 — Add true reset early-stage tests

**Priority:** P1

**Files:**

```text
TransactionalResetCoordinatorTest.kt
```

### Required tests

- [x] `resetStopsImmediatelyWhenConfigStageFails`
- [x] `resetStopsImmediatelyWhenSetupStageFails`

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

- [x] `git rev-parse HEAD`: `3bb5ba919ffa368877aa4a55ce4691c9765a5b81` (HEAD at the start
      of this implementation pass; the FIX5 code changes described here are uncommitted
      in the working tree as of this evidence — see note below)
- [ ] GitHub Actions workflow URL/id: **NOT RUN: no GitHub Actions access from this
      environment; this pass ran all validation locally via `./gradlew`.**
- [ ] workflow head SHA: **NOT RUN: same reason.**
- [x] lifecycle focused result: PASS — `testDebugUnitTest` with
      `TunnelForegroundServiceStopFailureTest`, `TunnelForegroundService*`,
      `data.TunnelRepositoryTest` filters, 0 failures.
- [x] config/reset result: PASS — `TransactionalResetCoordinatorTest` (26 tests),
      `ForwardsRepositoryTest`, `ConfigRepositoryTest` all passing as part of the full run.
- [x] logs/preferences/network result: PASS — `LogsViewModelTest`,
      `NetworkPolicyViewModelTest`, `SettingsViewModelTest`, `NetworkPolicyManagerTest` all
      passing.
- [x] setup/identity result: PASS — `SetupSaveControllerTest`, `IdentityRepositoryTest`
      passing.
- [x] full Android result: PASS — `./gradlew check` (ktlint + detekt, including the
      type-resolution `detektDebugUnitTest`/`detektReleaseUnitTest`/`detektTest` variants +
      Android lint + `testDebugUnitTest` + `testReleaseUnitTest`), `./gradlew lintDebug`,
      `./gradlew assembleDebug` all green with zero errors/warnings-as-failures.
- [x] every unavailable check has `NOT RUN: exact reason` — see GitHub Actions items above.

**Note on commit state:** this evidence reflects the working tree at the end of this
implementation pass, run against the codebase as modified (not yet committed as of this
writing — commits are made only when the user explicitly asks, per this repo's
CLAUDE.md). Re-run `git rev-parse HEAD` after committing for the evidence to point at
the exact commit these results correspond to.

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

- [x] NativeFailure pending retry consumed before invalidation.
- [x] One-event policy retry still works.
- [x] Destroy pending retry test has real assertions.
- [x] Production NetworkPolicyManager uses real reporter — reimplemented as an always-live
      internal `AppDiagnosticEventBus` rather than an injected reporter interface (see
      P0-003 note above); no no-op path exists to misconfigure.
- [x] Network delivery diagnostic uses redacted string, not raw Throwable.
- [x] Network delivery redaction tests pass.

## P1

- [x] TransactionalReset setup reset has no `runCatching`.
- [x] TransactionalReset setup rollback has no `runCatching`.
- [x] PolicyAllowed preference read failure is visible.
- [x] Lifecycle coordinator catches unexpected command exceptions visibly.
- [x] Reset config-stage failure test proves setup/forwards not called.
- [x] Reset setup-stage failure test proves forwards not called.
- [x] Rollback failure test simulates actual rollback failure
      (`configRollbackFailureIsReportedAsRollbackStageFailure`); the three previously
      misleadingly-named tests were renamed to describe what they actually test
      (rollback success reporting), not deleted.
- [x] Delete failure tests are real (made `deleteConfigFileForTransactionalReset` `open`
      and force a genuine failure) — renamed from misleading names, not just relabeled.

## P2

- [x] Fresh signoff evidence recorded (see P2-001 above; GitHub Actions items NOT RUN —
      no CI access from this environment).
