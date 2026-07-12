# WebRTC Tunnel Android State-Integrity Recovery Fix 4 TODO

This TODO implements `WEBRTC_TUNNEL_ANDROID_STATE_INTEGRITY_RECOVERY_FIX4_SPEC.md`.

The app is close, but not signed off. This pass should be small and exact. Do not
redesign the app.

---

# 0. Work discipline

For every task:

```text
1. inspect the current implementation
2. add/strengthen the focused test first
3. implement the smallest fix
4. run the focused test
5. run relevant lint/format
6. commit one scoped change
```

Hard rules:

```text
no assertTrue(true) failure tests
no runCatching in critical startup/config/log/reset paths
no false rollback success
no Log.w-only diagnostics for required app-visible diagnostics
```

---

# P0 tasks

## P0-001 — Remove `runCatching` from `performStartupAttempt`

**Priority:** P0

**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundService*.kt
```

### Step 1 — replace implementation

Find `performStartupAttempt()`.

Replace `runCatching` with:

```kotlin
private suspend fun performStartupAttempt(
    generation: Long,
): StartOutcome {
    return try {
        val identity =
            prepareOfferIdentity()

        try {
            classifyStartAndZeroIdentity(
                identity = identity,
                generation = generation,
            )
        } finally {
            identity.fill(0)
        }
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

Adjust function names if current code differs.

### Step 2 — preserve identity wiping

Do not remove the inner:

```kotlin
finally {
    identity.fill(0)
}
```

The owner of the plaintext bytes must wipe them.

### Step 3 — tests

Add/strengthen:

- [ ] `policyBlockedInitialStartSubmitsCompletionAndClearsActiveStartup`;
- [ ] `identityReadFailureSubmitsCompletionAndClearsActiveStartup`;
- [ ] `activeConfigWriteFailureSubmitsCompletionAndClearsActiveStartup`;
- [ ] `unexpectedPreparationFailurePublishesStartupCompletion`;
- [ ] `startupPreparationCancellationPropagates`.

Observable proof may be:

```text
later StartOffer is not blocked by stale already-starting state
native start call count remains zero on prep failure
status/error is visible
CancellationException is thrown
```

### Acceptance

- [ ] no `runCatching` in `performStartupAttempt`;
- [ ] cancellation rethrows;
- [ ] identity bytes still wiped;
- [ ] required startup tests exist.

---

## P0-002 — Complete pending retry invalidation

**Priority:** P0

**Files:**

```text
TunnelForegroundService.kt
TunnelForegroundService tests
```

### Step 1 — verify retry handler

Ensure:

```kotlin
private suspend fun handleRetryPolicyResume(
    expectedGeneration: Long,
) {
    requireRuntimeStartAllowed()
        .getOrElse { error ->
            publishQuarantineBlocked(error)
            return
        }

    if (lifecycleGeneration.get() != expectedGeneration) {
        return
    }

    if (!pausedByPolicy.get()) {
        invalidatePendingPolicyRetry()
        return
    }

    invalidatePendingPolicyRetry()
    offer.resume()
}
```

### Step 2 — add missing invalidation calls

Ensure `invalidatePendingPolicyRetry()` is called on:

- [ ] explicit Stop;
- [ ] explicit Pause;
- [ ] new StartOffer;
- [ ] AllowMeteredSession;
- [ ] Destroy / `onDestroy`;
- [ ] VerifiedSuccess;
- [ ] VerificationFailure;
- [ ] UnexpectedFailure;
- [ ] Aborted;
- [ ] quarantine set;
- [ ] PolicyBlocked for stale previous retry.

### Step 3 — tests

- [ ] pending retry then Destroy does not restart;
- [ ] pending retry then explicit Pause does not restart;
- [ ] pending retry then explicit Stop does not restart;
- [ ] pending retry then new StartOffer invalidates old retry;
- [ ] pending retry then non-policy startup failure does not restart;
- [ ] valid retry while policy-paused runs exactly once.

---

## P0-003 — Remove `runCatching` from config atomic writer

**Priority:** P0

**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigRepository.kt
ConfigRepositoryTest.kt
TunnelForegroundService tests
```

### Step 1 — replace writer body

Replace `runCatching` in `writeConfigAtomicallyLocked()` with:

```kotlin
private fun writeConfigAtomicallyLocked(
    content: String,
): Result<Unit> {
    val temp =
        File.createTempFile(
            "config-",
            ".tmp",
            configFile.parentFile,
        )

    return try {
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

Preserve parent directory creation if the current code does it.

### Step 2 — active config tests

- [ ] active config write failure submits startup completion;
- [ ] active config write failure clears active startup;
- [ ] native start is not called after active config failure;
- [ ] cancellation during active config preparation propagates.

---

# P1 tasks

## P1-001 — Remove `runCatching` from `recentLogs`

**Priority:** P1

**Files:**

```text
TunnelRepository.kt
LogsViewModelTest.kt
```

### Implementation

Replace `runCatching` with:

```kotlin
fun recentLogs(
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
                    SensitiveDataRedactor.redactText(
                        error.message
                            ?: "Log refresh failed",
                    ),
            ),
        )
    }
}
```

### Tests

- [ ] cancellation propagates;
- [ ] older failure cannot set error after newer success;
- [ ] older success cannot clear newer failure;
- [ ] older success cannot replace newer list.

---

## P1-002 — Fix reset config delete false-success

**Priority:** P1

**Files:**

```text
ConfigRepository.kt
TransactionalResetCoordinatorTest.kt
```

### Implementation

Replace ignored `File.delete()` with:

```kotlin
internal fun deleteConfigFileForTransactionalReset():
    Result<Unit> {
    return try {
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

If this function is currently `suspend`, keep it suspend.

### Tests

- [ ] delete failure is reported as rollback failure;
- [ ] delete failure does not produce rollback success;
- [ ] file still existing after attempted delete is failure.

---

## P1-003 — Stop silent setup snapshot defaulting

**Priority:** P1

**Files:**

```text
TransactionalReset.kt
ConfigRepository.kt
TransactionalResetCoordinatorTest.kt
```

### Problem pattern

Replace:

```kotlin
setupInput =
    configRepository
        .loadSetupInputResult()
        .getOrDefault(SetupConfigInput())
```

### Implementation

Use explicit failure:

```kotlin
val setupInput =
    configRepository
        .loadSetupInputResult()
        .getOrElse { error ->
            return Result.failure(
                SnapshotCaptureException(
                    "Failed to read setup input",
                    error,
                )
            )
        }
```

If absent setup input should mean default, make `loadSetupInputResult()` return success
with default only for actual absence. Corrupt/unreadable input must be failure.

### Tests

- [ ] corrupt setup input makes reset fail before mutation;
- [ ] corrupt setup input does not reset config;
- [ ] corrupt setup input does not reset forwards;
- [ ] absent setup input still uses intended default behavior.

---

## P1-004 — Strengthen transactional reset tests

**Priority:** P1

**Files:**

```text
TransactionalResetCoordinatorTest.kt
```

### Add early-failure tests

```kotlin
@Test
fun resetStopsImmediatelyWhenConfigStageFails()

@Test
fun resetStopsImmediatelyWhenSetupStageFails()
```

Required assertions:

```text
Config failure -> setup reset not called, forwards reset not called
Setup failure -> forwards reset not called
```

### Add real rollback failure test

```kotlin
@Test
fun rollbackFailureIsReportedAsRollbackFailure()
```

Simulate:

```text
config reset succeeds
setup reset fails
config rollback fails
```

Assert:

```text
ResetResult.Failed
rollback includes RollbackStageResult.Failure(Config)
overall result is not Success
```

Do not use forwards reset failure as a fake rollback failure.

---

## P1-005 — Replace weak preference failure tests

**Priority:** P1

**Files:**

```text
NetworkPolicyViewModelTest.kt
SetupSaveControllerTest.kt
SetupViewModelTest.kt
```

### Replace `assertTrue(true)`

No failure test may use:

```kotlin
assertTrue(true)
```

as its primary assertion.

### Fake snackbar pattern

```kotlin
private class RecordingSnackbar :
    SnackbarReporter {
    val messages =
        mutableListOf<String>()

    override suspend fun show(
        message: String,
    ) {
        messages += message
    }
}
```

### Network policy failure test

```kotlin
@Test
fun savePreferencesFailureShowsErrorAndNoSuccess() =
    runTest {
        val snackbar =
            RecordingSnackbar()

        val viewModel =
            newViewModel(
                snackbar = snackbar,
                savePreferencesResult =
                    Result.failure(
                        IOException("disk full"),
                    ),
            )

        viewModel.savePreferences(...)

        advanceUntilIdle()

        assertTrue(
            snackbar.messages.any {
                it.contains("disk full") ||
                    it.contains("Failed to update network policy")
            },
        )

        assertFalse(
            snackbar.messages.any {
                it == "Network policy updated"
            },
        )
    }
```

### Required tests

- [ ] network policy failure shows error;
- [ ] network policy failure does not show success;
- [ ] setup preference failure does not show success;
- [ ] setup preference failure returns/emits failure.

---

## P1-006 — Add network event delivery diagnostics

**Priority:** P1

**Files:**

```text
NetworkPolicyManager.kt
network policy tests
```

### Reporter interface

```kotlin
interface NetworkPolicyEventReporter {
    fun reportNetworkPolicyEventDeliveryFailed(
        cause: Throwable?,
    )
}
```

### No-op default

```kotlin
object NoopNetworkPolicyEventReporter :
    NetworkPolicyEventReporter {
    override fun reportNetworkPolicyEventDeliveryFailed(
        cause: Throwable?,
    ) = Unit
}
```

### Constructor

```kotlin
class NetworkPolicyManager(
    private val context: Context,
    private val reporter: NetworkPolicyEventReporter =
        NoopNetworkPolicyEventReporter,
)
```

### Emit helper

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

        reporter
            .reportNetworkPolicyEventDeliveryFailed(
                cause,
            )
    }
}
```

### Expected close filter

```kotlin
private fun isExpectedChannelClose(
    cause: Throwable?,
): Boolean =
    cause is CancellationException ||
        cause is ClosedSendChannelException
```

### Tests

- [ ] active failed delivery reports diagnostic;
- [ ] expected close does not report diagnostic;
- [ ] diagnostic is redacted if cause contains sensitive value.

---

## P1-007 — Complete native schema tests

**Priority:** P1

**Files:**

```text
TunnelRepositoryTest.kt
```

Add tests:

- [ ] missing mode returns `native_status_schema_error`;
- [ ] future mode returns `native_status_schema_error`;
- [ ] unknown runtime state maps to safe Error state;
- [ ] unknown listen state includes redacted raw value.

If unknown runtime state intentionally does not use `native_status_schema_error`, document
that in the test and assert safe Error state.

---

# P2 tasks

## P2-001 — Final signoff evidence

After all fixes, record:

- [ ] final production SHA;
- [ ] fresh workflow run URL/id;
- [ ] workflow head SHA;
- [ ] focused lifecycle test result;
- [ ] setup/identity test result;
- [ ] config/reset test result;
- [ ] logs/preferences/network result;
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

Run three fresh times.

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

- [ ] `performStartupAttempt` has no `runCatching`.
- [ ] startup cancellation propagates.
- [ ] identity bytes still zeroized in `finally`.
- [ ] active config write failure submits completion.
- [ ] active config write failure does not call native start.
- [ ] config writer cancellation propagates.
- [ ] pending retry invalidated on Destroy.
- [ ] pending retry invalidated on Stop/Pause/Start/Allow.
- [ ] pending retry invalidated on non-policy terminal startup failures.

## P1

- [ ] `recentLogs` has no `runCatching`.
- [ ] logs cancellation propagates.
- [ ] config delete rollback cannot falsely report success.
- [ ] setup snapshot load failure stops reset before mutation.
- [ ] reset tests prove config-stage failure stops later stages.
- [ ] reset tests prove setup-stage failure stops later stages.
- [ ] reset tests prove real rollback failure is reported.
- [ ] network policy failure tests assert actual messages.
- [ ] network delivery failure reaches reporter.
- [ ] native schema tests cover missing/future mode.
- [ ] unknown runtime state safe handling is tested.

## P2

- [ ] final signoff evidence recorded.
