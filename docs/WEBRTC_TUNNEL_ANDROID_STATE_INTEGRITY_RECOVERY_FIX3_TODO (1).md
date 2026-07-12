# WebRTC Tunnel Android State-Integrity Recovery Fix 3 TODO

This TODO implements `WEBRTC_TUNNEL_ANDROID_STATE_INTEGRITY_RECOVERY_FIX3_SPEC.md`.

The latest Android code is much improved, but it is not signoff-ready. This TODO is
focused only on the remaining Android blockers from the latest review.

---

# 0. Work discipline

For each task:

```text
1. inspect current implementation
2. add or strengthen a focused regression test
3. implement the smallest correct fix
4. run the focused test
5. run relevant formatting/lint
6. commit a scoped change
```

Do not keep tests that prove the wrong behavior.

Do not use `assertTrue(true)` as failure-path verification.

---

# P0 tasks

## P0-001 — Finalize startup completion and cancellation safety

**Priority:** P0

**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/StartOutcome.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundService*.kt
```

### Step 1 — convert `performStartupAttempt()` to explicit try/catch

Find current code using `runCatching`.

Replace with:

```kotlin
private suspend fun performStartupAttempt(
    generation: Long,
): StartOutcome {
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

Use the real function names if they differ.

### Step 2 — make `StartOutcome.Aborted` carry reason

Replace:

```kotlin
data object Aborted : StartOutcome
```

with:

```kotlin
data class Aborted(
    val reason: String,
) : StartOutcome
```

Update all handlers/tests to use `completion.reason`.

### Step 3 — make completion handling deterministic

Ensure every non-stale completion does:

```kotlin
activeStartup = null
```

before branch-specific logic.

Use this pattern:

```kotlin
private suspend fun handleStartupCompleted(
    command: LifecycleCommand.StartupCompleted,
) {
    if (lifecycleGeneration.get() != command.generation) {
        return
    }

    activeStartup = null

    when (val completion = command.completion) {
        is StartOutcome.VerifiedSuccess ->
            handleVerifiedStartupSuccess(
                command.generation,
            )

        is StartOutcome.PolicyBlocked ->
            handleStartupPolicyBlocked(
                command.generation,
                completion.reason,
            )

        is StartOutcome.Aborted ->
            handleStartupAborted(
                command.generation,
                completion.reason,
            )

        is StartOutcome.UnexpectedFailure ->
            handleUnexpectedStartupFailure(
                command.generation,
                completion.error,
            )

        // existing native/verification failure branches...
    }

    submitPendingPolicyRetryIfValid(
        completedGeneration = command.generation,
    )
}
```

If `submitPendingPolicyRetryIfValid()` should not run for a specific branch, add a
comment and a test proving why.

### Step 4 — tests

Add or strengthen tests:

```kotlin
@Test
fun policyBlockedInitialStartSubmitsCompletionAndClearsActiveStartup()

@Test
fun identityReadFailureSubmitsCompletionAndClearsActiveStartup()

@Test
fun configRewriteFailureSubmitsCompletionAndClearsActiveStartup()

@Test
fun unexpectedPreparationFailurePublishesStartupCompletion()

@Test
fun initialPolicyBlockThenOnePolicyAllowedStartsOnce()

@Test
fun startupPreparationCancellationPropagates()
```

### Acceptance

- [ ] `performStartupAttempt()` uses explicit `try/catch`.
- [ ] cancellation is rethrown.
- [ ] `StartOutcome.Aborted` carries reason.
- [ ] every active startup path submits completion or is cancelled.
- [ ] required tests exist and pass.

---

## P0-002 — Finish retry guard and pending retry invalidation

**Priority:** P0

**Files:**

```text
TunnelForegroundService.kt
TunnelForegroundService tests
```

### Step 1 — retry handler must check `pausedByPolicy`

Replace current handler with this shape:

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

Use the real method names.

### Step 2 — centralize invalidation

```kotlin
private fun invalidatePendingPolicyRetry() {
    pendingPolicyResumeGeneration.set(null)
}
```

### Step 3 — call invalidation on all required boundaries

Add calls on:

- [ ] explicit Stop;
- [ ] explicit Pause;
- [ ] PolicyBlocked;
- [ ] StartOffer;
- [ ] AllowMeteredSession;
- [ ] Destroy;
- [ ] any successful verified startup if stale retry no longer applies;
- [ ] startup abort/failure when retry should not be preserved.

### Tests

- [ ] pending retry then Stop does not restart;
- [ ] pending retry then Pause does not restart;
- [ ] pending retry then PolicyBlocked does not restart;
- [ ] pending retry then new StartOffer invalidates old retry;
- [ ] RetryPolicyResume while `pausedByPolicy == false` does not resume;
- [ ] valid retry while policy-paused runs once.

---

## P0-003 — Make active config preparation failure fatal to startup

**Priority:** P0

**Files:**

```text
ConfigRepository.kt
TunnelForegroundService.kt
TunnelForegroundService tests
```

### Step 1 — make repository method return `Result<Unit>`

Change active config preparation from “log and continue” to failure-aware result.

Pattern:

```kotlin
suspend fun prepareActiveConfigForStart(
    mode: TunnelMode,
    localIpOverride: String?,
    iceModeOverride: IceMode?,
): Result<Unit> =
    writeMutex.withLock {
        try {
            val current =
                readConfigLocked()

            val updated =
                current.copy(
                    node = current.node.copy(
                        role = mode,
                    ),
                    android = current.android.copy(
                        localIpOverride =
                            localIpOverride,
                        iceModeOverride =
                            iceModeOverride,
                    ),
                )

            writeConfigAtomicallyLocked(
                encodeConfig(updated),
            )

            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
```

Use actual config names.

### Step 2 — startup preparation must fail visibly

```kotlin
configRepository.prepareActiveConfigForStart(...)
    .getOrElse { error ->
        throw StartupAborted(
            "Failed to prepare active config: ${
                SensitiveDataRedactor.redactText(
                    error.message ?: "unknown error"
                )
            }"
        )
    }
```

or map to `StartOutcome.UnexpectedFailure`.

### Tests

- [ ] active config write failure submits startup completion;
- [ ] active config write failure clears active startup;
- [ ] native start is not called after active config failure;
- [ ] error is visible and redacted.

---

# P1 tasks

## P1-001 — Make logs and logsError generation-consistent

**Priority:** P1

**Files:**

```text
TunnelRepository.kt
LogsViewModel.kt
LogsViewModelTest.kt
LogsScreen.kt
```

### Step 1 — add fetch result type

```kotlin
data class LogsFetchResult(
    val logs: List<LogEntry>,
    val error: TunnelError?,
)
```

### Step 2 — repository returns result and does not mutate `_logsError`

Replace repository direct state writes:

```kotlin
_logsError.value = ...
```

with returned value:

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

### Step 3 — ViewModel generation owns both fields

```kotlin
fun refresh() {
    val generation =
        ++refreshGeneration

    viewModelScope.launch {
        val result =
            withContext(dispatcher) {
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

### Tests

- [ ] older failure cannot set error after newer success;
- [ ] older success cannot clear newer failure;
- [ ] older success cannot replace newer list;
- [ ] UI displays current error.

---

## P1-002 — Complete config writer serialization

**Priority:** P1

**Files:**

```text
ConfigRepository.kt
ConfigRepositoryTest.kt
```

### Step 1 — remove direct `writeConfig`

Find:

```kotlin
configFile.writeText(contents)
```

inside `writeConfig()`.

Replace with:

```kotlin
suspend fun writeConfig(
    contents: String,
): Result<Unit> =
    writeConfigAtomically(contents)
```

If callers expect non-suspending `Unit`, update callers/tests accordingly. Prefer
`Result<Unit>`.

### Step 2 — search for direct config writes

```bash
rg "configFile\\.writeText|Files\\.write|writeConfig\\(" android/app/src/main
```

### Tests

- [ ] `writeConfig()` uses atomic writer path;
- [ ] overlapping writes produce complete file;
- [ ] atomic move unsupported fallback works;
- [ ] temp file cleanup after failure.

---

## P1-003 — Preserve exact config snapshot in transactional reset

**Priority:** P1

**Files:**

```text
TransactionalReset.kt
ConfigRepository.kt
TransactionalResetCoordinatorTest.kt
```

### Step 1 — add config snapshot type

```kotlin
data class ConfigSnapshot(
    val existed: Boolean,
    val contents: String?,
)
```

Then:

```kotlin
data class ResetSnapshot(
    val config: ConfigSnapshot,
    val setupInput: SetupConfigInput,
    val forwards: List<ForwardConfig>,
)
```

### Step 2 — capture existence separately from contents

```kotlin
private suspend fun captureConfigSnapshot():
    Result<ConfigSnapshot> {
    return if (configRepository.configFileExists()) {
        configRepository
            .readRawConfigTextForTransactionalReset()
            .map { contents ->
                ConfigSnapshot(
                    existed = true,
                    contents = contents,
                )
            }
    } else {
        Result.success(
            ConfigSnapshot(
                existed = false,
                contents = null,
            )
        )
    }
}
```

### Step 3 — restore exact state

```kotlin
private suspend fun restoreConfig(
    snapshot: ConfigSnapshot,
): RollbackStageResult {
    val result =
        if (snapshot.existed) {
            configRepository.writeConfigAtomically(
                snapshot.contents.orEmpty(),
            )
        } else {
            configRepository
                .deleteConfigFileForTransactionalReset()
        }

    return result.fold(
        onSuccess = {
            RollbackStageResult.Success(
                ResetStage.Config,
            )
        },
        onFailure = { error ->
            RollbackStageResult.Failure(
                stage = ResetStage.Config,
                reason = redact(error),
            )
        },
    )
}
```

### Tests

- [ ] absent config restored as absent;
- [ ] blank existing config restored as existing blank file;
- [ ] whitespace config restored exactly;
- [ ] non-empty config restored exactly.

---

## P1-004 — Strengthen transactional reset tests

**Priority:** P1

**Files:**

```text
TransactionalResetCoordinatorTest.kt
```

### Stop on first failed stage

Add tests:

```kotlin
@Test
fun resetStopsImmediatelyWhenConfigStageFails()

@Test
fun resetStopsImmediatelyWhenSetupStageFails()
```

These must prove later stages did not run.

### Real rollback failure

Add test:

```kotlin
@Test
fun rollbackFailureIsReportedAsRollbackFailure()
```

It should simulate:

```text
config reset succeeds
setup reset fails
config rollback fails
```

Assert:

```text
ResetResult.Failed
rollback includes Failure(Config)
overall result is not Success
```

---

## P1-005 — Strengthen preference failure tests

**Priority:** P1

**Files:**

```text
NetworkPolicyViewModelTest.kt
SetupSaveControllerTest.kt
SetupViewModelTest.kt
```

### Network policy tests

Use fake snackbar sink and assert actual output:

```kotlin
assertTrue(
    snackbar.messages.any {
        it.contains(
            "Failed to update network policy",
        )
    },
)

assertFalse(
    snackbar.messages.any {
        it == "Network policy updated"
    },
)
```

### Setup tests

Add tests:

- [ ] default `persistPreferences` returns `Result.failure` and setup save fails;
- [ ] injected `persistPreferences` returns `Result.failure` and setup save fails;
- [ ] success message is not emitted on persistence failure.

---

## P1-006 — Make network policy event delivery visible through diagnostics

**Priority:** P1

**Files:**

```text
NetworkPolicyManager.kt
TunnelForegroundService.kt or reporter wiring
network policy tests
```

### Step 1 — add reporter interface

```kotlin
interface NetworkPolicyEventReporter {
    fun reportNetworkPolicyEventDeliveryFailed(
        cause: Throwable?,
    )
}
```

### Step 2 — emit helper

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

### Step 3 — expected close filter

```kotlin
private fun isExpectedChannelClose(
    cause: Throwable?,
): Boolean =
    cause is CancellationException ||
        cause is ClosedSendChannelException
```

Use the actual channel exception type available in your imports.

### Tests

- [ ] active failed delivery reports diagnostic;
- [ ] expected close does not report diagnostic;
- [ ] service can still resync from current status.

---

## P1-007 — Clear active peer on local terminal states

**Priority:** P1

**Files:**

```text
TunnelRepository.kt
TunnelRepositoryTest.kt
```

### Step 1 — add helper

```kotlin
private fun TunnelStatus.withoutActivePeer():
    TunnelStatus =
    copy(
        remotePeerId = null,
        activeSessionCount = 0,
        mqttConnected = false,
    )
```

### Step 2 — use helper

Use it in local terminal transitions:

- [ ] `setPolicyBlocked`;
- [ ] `setLocalError`;
- [ ] `setNoNetwork` if present;
- [ ] `setConfigInvalid` if present.

### Tests

- [ ] policy-blocked local status clears remote peer;
- [ ] local error clears remote peer;
- [ ] config invalid clears remote peer;
- [ ] no-network clears remote peer.

---

## P1-008 — Complete native status schema tests

**Priority:** P1

**Files:**

```text
TunnelRepositoryTest.kt
```

### Add tests

- [ ] missing mode fails startup/status verification visibly;
- [ ] future mode fails startup/status verification visibly;
- [ ] unknown runtime state maps to safe Error state;
- [ ] unknown listen state includes redacted raw value.

If unknown runtime state intentionally does not use `native_status_schema_error`, document the reason in the test name/comment.

---

# P2 tasks

## P2-001 — Record final signoff evidence

**Priority:** P2

After all P0/P1 fixes:

- [ ] final production SHA;
- [ ] fresh workflow run URL/id;
- [ ] workflow head SHA;
- [ ] focused lifecycle test result;
- [ ] setup/identity test result;
- [ ] config/reset test result;
- [ ] logs/preferences/network test result;
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

## Setup identity

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.SetupSaveControllerTest' \
  --tests 'com.phillipchin.webrtctunnel.security.IdentityRepositoryTest' \
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

- [ ] `performStartupAttempt()` uses explicit `try/catch`.
- [ ] Startup cancellation is rethrown.
- [ ] `StartOutcome.Aborted` carries reason.
- [ ] Policy-blocked startup completion test exists.
- [ ] Identity-read failure startup completion test exists.
- [ ] Config rewrite failure startup completion test exists.
- [ ] Unexpected preparation failure completion test exists.
- [ ] One-event policy resume test exists.
- [ ] Retry handler checks `pausedByPolicy`.
- [ ] Pending retry invalidated on Stop.
- [ ] Pending retry invalidated on Pause.
- [ ] Pending retry invalidated on PolicyBlocked.
- [ ] Pending retry invalidated on StartOffer.
- [ ] Pending retry invalidated on AllowMeteredSession.
- [ ] Pending retry invalidated on Destroy.
- [ ] Active config rewrite failure stops startup before native start.

## P1

- [ ] `recentLogs()` returns `LogsFetchResult`.
- [ ] `recentLogs()` does not write repository `_logsError`.
- [ ] Logs and logsError generation tests pass.
- [ ] `writeConfig()` no longer direct-writes.
- [ ] Transactional reset captures config existence separately.
- [ ] Reset tests prove stop-on-first-failure.
- [ ] Reset tests prove rollback failure is reported.
- [ ] Preference failure tests assert actual messages.
- [ ] Network policy event delivery failure reaches diagnostics.
- [ ] Local terminal states clear active peer.
- [ ] Native schema tests cover missing/future mode.

## P2

- [ ] Final signoff evidence recorded.
