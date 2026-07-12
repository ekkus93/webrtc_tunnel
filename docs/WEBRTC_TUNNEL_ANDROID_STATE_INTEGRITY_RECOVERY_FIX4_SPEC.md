# WebRTC Tunnel Android State-Integrity Recovery Fix 4 Spec

## 1. Purpose

This spec defines the fourth Android-focused state-integrity recovery pass for the
WebRTC Tunnel app.

The latest code is close, but it is still not signed off. Fix 3 improved several
major areas, including config snapshot shape, `StartOutcome.Aborted`, config writer
routing, logs result ownership, and local terminal peer cleanup. The remaining work is
now narrower and should not become a broad redesign.

This pass is intended for Claude Code running with a local model. Be literal. Do not
invent a new architecture. Fix the exact remaining Android issues and strengthen the
tests that currently do not prove the invariants.

---

## 2. Current status

### Good progress to preserve

Do not regress these:

- `StartOutcome.Aborted` carries a reason.
- `StartOutcome.PolicyBlocked` exists.
- `StartOutcome` comment no longer falsely claims JNI returns typed outcomes.
- `writeConfig()` routes through `writeConfigAtomically()`.
- `prepareActiveConfigForStart()` returns `Result<Unit>`.
- active config preparation failure stops startup for ordinary failure cases.
- `ConfigSnapshot(existed, contents)` exists for reset rollback.
- reset rollback can distinguish absent config from blank/whitespace config.
- public raw `ForwardsRepository.save()` is gone.
- `LogsFetchResult` exists.
- `LogsViewModel` owns local log error state and applies logs/error under generation.
- `setPolicyBlocked()` and `setLocalError()` use terminal peer cleanup.
- network policy preference save folds `Result<Unit>`.
- setup preference persistence is failure-aware.
- setup identity plaintext handling uses explicit ownership transfer.

### Remaining blockers

The current build is not signed off because:

1. `performStartupAttempt()` still uses `runCatching` in a critical startup boundary.
2. required startup-completion tests are still missing.
3. pending policy retry invalidation is incomplete, especially destroy/policy-pause/failure paths.
4. `writeConfigAtomicallyLocked()` still uses `runCatching`, which can swallow cancellation.
5. `recentLogs()` still uses `runCatching`.
6. network policy event delivery failure is only logged, not reported through app diagnostics.
7. `deleteConfigFileForTransactionalReset()` ignores `File.delete()` failure.
8. setup-input snapshot for reset still silently defaults on load failure/corruption.
9. transactional reset tests still do not prove early-stage stop or real rollback failure.
10. network policy failure tests still use `assertTrue(true)`.
11. missing/future native mode and unknown runtime state tests are incomplete.
12. final signoff evidence is not recorded.

---

## 3. Non-negotiable rules

### 3.1 Critical boundaries must not use `runCatching`

Do not use `runCatching` in:

```text
startup attempt
active config preparation
config atomic writer
status polling
network policy monitor
recent logs refresh
transactional reset mutation/rollback
plaintext identity ownership paths
```

Use explicit cancellation-safe `try/catch`:

```kotlin
try {
    ...
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    ...
}
```

### 3.2 Tests must prove the invariant

The following does not count as a test:

```kotlin
assertTrue(true)
```

A failure-path test must assert the failure signal, state, or message.

### 3.3 Rollback must not falsely report success

Rollback must not report success when the underlying filesystem operation failed.

Specifically:

```text
File.delete() returning false is not success if the file still exists.
```

### 3.4 No stale lifecycle retry

A pending policy retry is valid only while the same generation is policy-paused.

Retry is invalid on:

```text
Stop
Pause
new StartOffer
AllowMeteredSession
Destroy
VerifiedSuccess
non-policy terminal startup failure
quarantine
```

### 3.5 Missing native schema fields fail visibly

A missing or future native mode must not verify as a safe running state. It must return
or publish a visible schema diagnostic.

---

# 4. P0 requirements

## P0-001 — Remove `runCatching` from `performStartupAttempt()`

### Problem

`performStartupAttempt()` still uses `runCatching`. It manually rethrows cancellation,
but the critical startup boundary still has the forbidden shape.

### Required implementation

Replace this pattern:

```kotlin
val outcome =
    runCatching {
        val identity = prepareOfferIdentity()
        try {
            classifyStartAndZeroIdentity(identity, generation)
        } finally {
            identity.fill(0)
        }
    }.fold(...)
```

with explicit `try/catch`:

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

If the current code uses a `PreparedStartupInputs` object, keep that shape. The rule is
the same: explicit `try/catch`, cancellation rethrown, identity bytes wiped by the
owner in `finally`.

### Required tests

Add or strengthen:

```text
policyBlockedInitialStartSubmitsCompletionAndClearsActiveStartup
identityReadFailureSubmitsCompletionAndClearsActiveStartup
activeConfigWriteFailureSubmitsCompletionAndClearsActiveStartup
unexpectedPreparationFailurePublishesStartupCompletion
startupPreparationCancellationPropagates
```

The tests may use observable behavior instead of private `activeStartup`, such as:

```text
a later StartOffer is not blocked by stale "already starting"
native start call count
visible status/error
lifecycle state
```

### Acceptance

- No `runCatching` in `performStartupAttempt()`.
- Cancellation is rethrown.
- Identity bytes are still wiped exactly once by the final owner.
- Required tests exist and prove completion is submitted or cancellation propagates.

---

## P0-002 — Finish pending policy retry invalidation

### Problem

`handleRetryPolicyResume()` now checks `pausedByPolicy`, but invalidation is still
incomplete on destroy/policy-pause/failure paths.

### Required helper

Use the existing helper or add it:

```kotlin
private fun invalidatePendingPolicyRetry() {
    pendingPolicyResumeGeneration.set(null)
}
```

### Required calls

Call `invalidatePendingPolicyRetry()` on:

```text
explicit Stop
explicit Pause
new StartOffer
AllowMeteredSession
Destroy
VerifiedSuccess
NativeFailure when retry should not survive
VerificationFailure
UnexpectedFailure
Aborted
quarantine set
```

For `PolicyBlocked`, invalidate stale retry from prior generation. A future
`PolicyAllowed` should create the fresh retry/resume intent.

### Required retry handler

The handler must retain this shape:

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

### Required tests

1. pending retry then Destroy does not restart after service recreation or late command;
2. pending retry then explicit Pause does not restart;
3. pending retry then explicit Stop does not restart;
4. pending retry then new StartOffer invalidates old retry;
5. pending retry then non-policy startup failure does not restart;
6. valid retry while policy-paused runs exactly once.

---

## P0-003 — Fix cancellation safety in config writing and active config preparation

### Problem

`writeConfigAtomicallyLocked()` still uses `runCatching`. This can turn
`CancellationException` into `Result.failure`, which can then become `StartupAborted`
instead of proper coroutine cancellation.

### Required implementation

Replace:

```kotlin
private fun writeConfigAtomicallyLocked(...): Result<Unit> =
    runCatching {
        ...
    }
```

with:

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

If the current function already creates parent directories, preserve that behavior.

### Required active config test

Simulate config write failure from `prepareActiveConfigForStart()` and assert:

```text
StartupCompleted submitted
active startup cleared
native start not called
visible/redacted error or aborted outcome
```

### Required cancellation test

Simulate cancellation during active config preparation and assert:

```text
CancellationException propagates
not converted to StartOutcome.Aborted
not converted to StartOutcome.UnexpectedFailure
```

If simulating filesystem cancellation is hard, test at the repository abstraction/fake
level by making `prepareActiveConfigForStart()` throw `CancellationException`.

---

# 5. P1 requirements

## P1-001 — Remove `runCatching` from `recentLogs()`

### Problem

`recentLogs()` now returns `LogsFetchResult`, but still uses `runCatching`.

### Required implementation

Use explicit `try/catch`:

```kotlin
fun recentLogs(
    maxEvents: Int,
): LogsFetchResult {
    return try {
        val logs =
            bridge.recentLogs(maxEvents)

        LogsFetchResult(
            logs = logs,
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

Keep the current method name if desired.

### Required tests

1. older failure cannot set error after newer success;
2. older success cannot clear newer failure;
3. older success cannot replace newer list;
4. cancellation propagates from logs refresh.

---

## P1-002 — Fix transactional reset delete false-success

### Problem

`deleteConfigFileForTransactionalReset()` ignores the Boolean return from
`File.delete()`.

### Required implementation

Preferred:

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

If using `File.delete()`:

```kotlin
val deleted =
    configFile.delete()

if (!deleted && configFile.exists()) {
    return Result.failure(
        IOException(
            "Failed to delete config file",
        )
    )
}
```

### Required tests

1. absent snapshot rollback deletes config when delete succeeds;
2. delete failure reports rollback failure;
3. delete failure does not report rollback success;
4. final file still existing after delete failure is treated as failure.

---

## P1-003 — Fix setup-input snapshot fallback in transactional reset

### Problem

`captureSnapshot()` uses:

```kotlin
loadSetupInputResult().getOrDefault(SetupConfigInput())
```

This silently snapshots defaults if setup input is corrupt/unreadable.

### Required behavior

Snapshot capture must fail if setup input cannot be read, unless the file is truly
absent and defaults are the defined prior state.

### Required implementation

Add a raw/existence-aware setup snapshot if possible:

```kotlin
data class SetupInputSnapshot(
    val existed: Boolean,
    val input: SetupConfigInput,
)
```

Low-churn acceptable behavior:

```kotlin
private suspend fun captureSnapshot():
    Result<ResetSnapshot> {
    val config =
        captureConfigSnapshot()
            .getOrElse {
                return Result.failure(it)
            }

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

    val forwards =
        forwardsRepository
            .currentForResetSnapshot()
            .getOrElse {
                return Result.failure(it)
            }

    return Result.success(
        ResetSnapshot(
            config = config,
            setupInput = setupInput,
            forwards = forwards,
        )
    )
}
```

If absent setup input normally maps to default, make that explicit inside
`loadSetupInputResult()` or add a method that distinguishes absent from corrupt.

### Required tests

1. corrupt setup input makes reset fail before mutation;
2. corrupt setup input does not reset config;
3. corrupt setup input does not reset forwards;
4. absent setup input still behaves according to intended app default.

---

## P1-004 — Strengthen transactional reset tests

### Required stop-on-first-failure tests

Add tests that fail early stages, not the last stage.

```kotlin
@Test
fun resetStopsImmediatelyWhenConfigStageFails()

@Test
fun resetStopsImmediatelyWhenSetupStageFails()
```

Assertions:

```text
when Config fails:
  setup reset not called
  forwards reset not called

when SetupInput fails:
  forwards reset not called
```

Use recording fakes.

### Required rollback failure test

Simulate:

```text
config reset succeeds
setup reset fails
config rollback fails
```

Assert:

```text
ResetResult.Failed
rollback contains RollbackStageResult.Failure(Config)
overall result is not Success
```

Do not count a forwards reset failure as rollback failure.

---

## P1-005 — Replace weak preference failure tests

### Problem

`NetworkPolicyViewModelTest` still uses `assertTrue(true)` in failure tests.

### Required implementation

Use a fake snackbar/event sink.

Example:

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

Test failure:

```kotlin
@Test
fun savePreferencesFailureShowsErrorMessage() =
    runTest {
        val snackbar =
            RecordingSnackbar()

        val viewModel =
            newViewModel(
                snackbar = snackbar,
                savePreferencesResult =
                    Result.failure(
                        IOException(
                            "disk full",
                        )
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

1. network policy failure shows error;
2. network policy failure does not show success;
3. setup preference persistence failure does not show success;
4. setup persistence failure emits/returns failure state.

---

## P1-006 — Report network event delivery failure through diagnostics

### Problem

`NetworkPolicyManager.emitPolicyStatus()` logs failed `trySend`, but does not publish an
app diagnostic.

### Required reporter

```kotlin
interface NetworkPolicyEventReporter {
    fun reportNetworkPolicyEventDeliveryFailed(
        cause: Throwable?,
    )
}
```

Default no-op:

```kotlin
object NoopNetworkPolicyEventReporter :
    NetworkPolicyEventReporter {
    override fun reportNetworkPolicyEventDeliveryFailed(
        cause: Throwable?,
    ) = Unit
}
```

Constructor:

```kotlin
class NetworkPolicyManager(
    private val context: Context,
    private val reporter: NetworkPolicyEventReporter =
        NoopNetworkPolicyEventReporter,
)
```

Emit helper:

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

Expected close filter:

```kotlin
private fun isExpectedChannelClose(
    cause: Throwable?,
): Boolean =
    cause is CancellationException ||
        cause is ClosedSendChannelException
```

Use the available channel exception type in the project.

### Required tests

1. active failed delivery reports diagnostic;
2. expected close does not report diagnostic;
3. diagnostic message is redacted if cause has sensitive content.

---

## P1-007 — Complete native schema tests

### Required tests

Add tests in `TunnelRepositoryTest` or equivalent:

```text
missing mode returns native_status_schema_error
future mode returns native_status_schema_error
unknown runtime state maps to safe Error state
unknown listen state includes redacted raw value
```

If the implementation intentionally does not use `native_status_schema_error` for
unknown runtime state, the test name/comment must document why and assert safe Error
state.

---

# 6. P2 requirements

## P2-001 — Final signoff evidence

Final response from Claude Code must include:

```text
final production SHA
fresh workflow run URL/id
workflow head SHA
focused lifecycle test result
setup/identity test result
config/reset test result
logs/preferences/network result
full Android result
NOT RUN reasons for anything unavailable
```

No signoff without fresh evidence.

---

## 7. Validation commands

### Focused lifecycle

```bash
cd android

./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundServiceStopFailureTest' \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundService*' \
  --tests 'com.phillipchin.webrtctunnel.data.TunnelRepositoryTest' \
  --rerun-tasks
```

Run three fresh times.

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

## 8. Signoff checklist

Do not sign off until all are true:

```text
performStartupAttempt has no runCatching
writeConfigAtomicallyLocked has no runCatching
recentLogs has no runCatching
startup cancellation propagates
active config write failure submits completion and does not start native
pending retry invalidated on destroy/pause/stop/start/allow/failure
delete config rollback cannot falsely report success
setup snapshot read failure stops reset before mutation
transactional reset tests prove early-stage stop
rollback failure test simulates actual rollback failure
network policy failure tests assert actual messages
network event delivery failure reaches app diagnostics
missing/future native mode tests exist
unknown runtime state safe handling test exists
fresh validation evidence recorded
```
