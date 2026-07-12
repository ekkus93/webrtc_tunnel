# WebRTC Tunnel Android State-Integrity Recovery Fix 3 Spec

## 1. Purpose

This spec defines the third Android-focused state-integrity recovery pass for the
WebRTC Tunnel app.

The previous Fix 2 pass made substantial progress. The Android code now has a real
lifecycle coordinator, Android-side typed startup outcomes, better quarantine handling,
improved identity zeroization in the setup path, improved preference result handling,
improved transactional reset, and stronger config writes.

However, the latest review found that the code is still not signoff-ready. The
remaining problems are narrower than before, but they still matter because they affect
controller truth, startup ownership, cancellation safety, state races, and test
confidence.

This pass should be implemented as small focused changes. It is not a broad redesign.

---

## 2. Current status summary

### Improved since Fix 2

The following areas are materially better and should be preserved:

- `StartOutcome` is now described as Android-side classification rather than a JNI
  return type.
- `StartOutcome.PolicyBlocked` exists.
- Startup now routes more paths through `LifecycleCommand.StartupCompleted`.
- The failed-stop quarantine test no longer expects immediate restart after failed
  stop.
- `NetworkPolicyViewModel` folds `savePreferences()` results.
- `SetupViewModel` uses a failure-aware `persistPreferences` callback.
- `SetupSaveController.resolveStoredIdentity()` uses explicit ownership transfer
  instead of the old `runCatching(...).getOrNull()` plaintext-loss pattern.
- Transactional reset now snapshots state, stops on first failure, deletes absent
  prior config during rollback, and uses a scoped forwards restore API.
- `ensureDefaultConfig()` routes through the atomic config writer.
- `resolveNativeMode(null)` no longer silently becomes `TunnelMode.Offer`.

### Still not signoff-ready

The remaining important gaps are:

1. `performStartupAttempt()` still uses `runCatching` in a critical startup boundary.
2. `StartOutcome.Aborted` does not carry a reason.
3. Required P0 startup-completion tests are missing.
4. `handleRetryPolicyResume()` still lacks `pausedByPolicy` guard.
5. Pending policy retry invalidation is not complete on all required lifecycle
   boundaries.
6. `TunnelRepository.recentLogs()` still writes repository `_logsError` outside the
   ViewModel generation guard.
7. `TunnelRepository.recentLogs()` still uses `runCatching`.
8. `NetworkPolicyManager.trySend()` failures are logged but not reported through app
   diagnostics, and expected channel close/cancellation is not clearly filtered.
9. `ConfigRepository.writeConfig()` still direct-writes config.
10. `ConfigRepository.prepareActiveConfigForStart()` logs active-config rewrite failure
    and continues startup.
11. Transactional reset snapshot does not distinguish config absence from blank
    existing config.
12. Transactional reset tests are still too weak for stop-on-first-failure and
    rollback failure.
13. Local terminal state helpers do not clearly clear `remotePeerId`.
14. Native status schema tests are incomplete.
15. Some preference failure tests are weak and do not assert actual snackbar/error
    output.

---

## 3. Scope

### In scope

- Android app code under `android/app/src/main`
- Android tests under `android/app/src/test`
- Android CI/test commands only where needed for signoff
- Lifecycle coordinator service state
- Setup identity handling tests
- Config repository write paths
- Logs repository/ViewModel state flow
- Network policy event delivery diagnostics
- Transactional reset edge cases
- Native status schema handling and tests

### Out of scope

- Rust/WebRTC transport behavior
- iOS or desktop clients
- JNI contract redesign
- broad UI redesign
- new feature work
- TURN/STUN/WebRTC data-plane debugging

---

## 4. Non-negotiable invariants

### 4.1 Critical coroutine boundaries use explicit try/catch

For startup, status, logs, network policy, and lifecycle command boundaries:

```kotlin
try {
    ...
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    ...
}
```

Do not use `runCatching` in these paths.

Reason: `runCatching` catches `CancellationException`. It is too easy to accidentally
turn cancellation into an ordinary error result.

### 4.2 Active startup ownership must be total

Once `activeStartup` is created, startup must eventually submit one typed completion
or be cancelled.

No startup path may silently return without completion.

### 4.3 Pending policy retry is valid only while policy-paused

A retry command must not resume unless:

```text
lifecycleGeneration matches
native runtime is not quarantined
pausedByPolicy == true
```

### 4.4 Logs and logsError must share one generation owner

An older log refresh may not overwrite a newer log list **or** newer log error.

The repository must not update shared log-error state before the ViewModel generation
check.

### 4.5 Config writes must be serialized and truthful

No production `config.toml` write may bypass the mutex-backed atomic writer.

Startup must not silently continue after failing to write the active config used for
native start.

### 4.6 Rollback must restore exact captured prior state where rollback succeeds

A config file that existed as blank must be restored as an existing blank file, not
treated as absent.

A config file that did not exist must be restored as absent.

### 4.7 Tests must assert behavior, not merely execute paths

A test that ends with `assertTrue(true)` does not prove failure handling.

Snackbar/error tests must inspect emitted messages or state.

---

# 5. P0 requirements

## P0-001 — Finalize startup completion and cancellation safety

### Problem

`performStartupAttempt()` still uses `runCatching`, and `StartOutcome.Aborted` does
not carry a diagnostic reason. Required startup-completion tests are missing.

### Required changes

1. Convert `performStartupAttempt()` to explicit `try/catch`.
2. Change `StartOutcome.Aborted` from `data object` to a reason-carrying data class.
3. Ensure every startup preparation abort maps to a typed completion.
4. Add the missing focused tests.

### Required implementation pattern

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

### Required `StartOutcome` shape

Use the actual existing sealed type. The important part is that `Aborted` carries a
reason:

```kotlin
sealed interface StartOutcome {
    data object VerifiedSuccess : StartOutcome

    data class PolicyBlocked(
        val reason: String,
    ) : StartOutcome

    data class Aborted(
        val reason: String,
    ) : StartOutcome

    data class UnexpectedFailure(
        val error: Throwable,
    ) : StartOutcome

    // Keep existing NativeFailure / VerificationFailure variants.
}
```

### Required coordinator handling

The coordinator must still clear startup ownership for every non-stale completion:

```kotlin
private suspend fun handleStartupCompleted(
    command: LifecycleCommand.StartupCompleted,
) {
    if (lifecycleGeneration.get() != command.generation) {
        return
    }

    activeStartup = null

    when (val completion = command.completion) {
        is StartOutcome.PolicyBlocked ->
            handleStartupPolicyBlocked(
                generation = command.generation,
                reason = completion.reason,
            )

        is StartOutcome.Aborted ->
            handleStartupAborted(
                generation = command.generation,
                reason = completion.reason,
            )

        // existing branches...
    }

    submitPendingPolicyRetryIfValid(
        completedGeneration = command.generation,
    )
}
```

If the current code intentionally only consumes pending retry on a subset of failures,
that must be justified with tests. The default rule should be that completion clears
ownership and then consumes/invalidates pending retry deterministically.

### Required tests

Add or strengthen tests for:

1. policy-blocked initial start submits completion and clears active startup;
2. identity read failure submits completion and clears active startup;
3. config rewrite failure submits completion and clears active startup;
4. unexpected preparation failure publishes startup completion;
5. initial policy block plus one later `PolicyAllowed` starts exactly once;
6. cancellation in startup preparation propagates and does not become
   `UnexpectedFailure`.

---

## P0-002 — Finish pending retry invalidation and retry guard

### Problem

`handleRetryPolicyResume()` lacks a `pausedByPolicy` check, and pending retry
invalidation is not explicit on all required lifecycle boundaries.

### Required retry handler

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

### Required invalidation helper

```kotlin
private fun invalidatePendingPolicyRetry() {
    pendingPolicyResumeGeneration.set(null)
}
```

### Required invalidation points

Call the helper on:

```text
explicit Stop
explicit Pause
PolicyBlocked
StartOffer
AllowMeteredSession
Destroy
successful verified startup if retry no longer applies
startup abort/failure when retry should not be preserved
```

### Required tests

1. pending retry then STOP does not restart;
2. pending retry then PAUSE does not restart;
3. pending retry then PolicyBlocked does not restart;
4. pending retry then new StartOffer invalidates old retry;
5. RetryPolicyResume while `pausedByPolicy == false` does not resume;
6. valid retry while policy-paused runs once.

---

## P0-003 — Make active config preparation failure fatal to startup

### Problem

`prepareActiveConfigForStart()` logs failure and startup continues. That can run the
native tunnel with stale or wrong active config.

### Required behavior

`prepareActiveConfigForStart()` must return `Result<Unit>` or throw a typed startup
abort. Startup must not continue if active config rewrite fails.

### Suggested repository API

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

Use the real config model names.

### Startup preparation handling

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

### Required tests

1. active config rewrite failure produces startup completion;
2. active config rewrite failure clears active startup;
3. native start is not called after active config rewrite failure;
4. error is visible/redacted.

---

# 6. P1 requirements

## P1-001 — Make logs and logsError generation-consistent

### Problem

`LogsViewModel` applies logs/error under generation, but `TunnelRepository.recentLogs()`
still writes repository `_logsError` directly. This can let an older refresh set a
stale error after a newer success.

### Required repository model

Add or use:

```kotlin
data class LogsFetchResult(
    val logs: List<LogEntry>,
    val error: TunnelError?,
)
```

### Required repository method

```kotlin
suspend fun fetchRecentLogs(
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

### Required removal

Remove direct mutation of repository log error state from the refresh path:

```kotlin
_logsError.value = ...
```

If a repository-level `logsError` flow remains for compatibility, it must not be
updated by stale refreshes outside the ViewModel generation check.

### Required ViewModel behavior

```kotlin
fun refresh() {
    val generation =
        ++refreshGeneration

    viewModelScope.launch {
        val result =
            withContext(dispatcher) {
                repository.fetchRecentLogs(
                    maxEvents = maxEvents,
                )
            }

        if (generation != refreshGeneration) {
            return@launch
        }

        _logs.value = result.logs
        _logsError.value = result.error
    }
}
```

### Required tests

1. older failure cannot set error after newer success;
2. older success cannot clear newer failure;
3. older success cannot replace newer list;
4. UI displays current error.

---

## P1-002 — Complete config writer serialization

### Problem

`ensureDefaultConfig()` now uses the atomic writer, but `writeConfig()` still
direct-writes config. The atomic writer improved, but the direct bypass remains.

### Required behavior

No production method should directly call:

```kotlin
configFile.writeText(...)
```

for `config.toml`.

### Required change

Either route `writeConfig()` through the atomic writer:

```kotlin
suspend fun writeConfig(
    contents: String,
): Result<Unit> =
    writeConfigAtomically(contents)
```

or restrict it to test-only usage and remove it from production code. Prefer routing
through the atomic writer to avoid ambiguity.

### Required search

```bash
rg "configFile\\.writeText|Files\\.write|writeConfig\\(" android/app/src/main
```

### Required tests

1. `writeConfig()` uses atomic writer path;
2. overlapping config writes produce complete file;
3. atomic move unsupported fallback works;
4. temp file cleaned after failure.

---

## P1-003 — Preserve exact config snapshot in transactional reset

### Problem

Transactional reset snapshot currently treats blank existing config like absent config.
That violates exact rollback.

### Required model

Use an explicit existence bit:

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

### Required capture

```kotlin
private suspend fun captureConfigSnapshot():
    ConfigSnapshot {
    return if (configRepository.configFileExists()) {
        ConfigSnapshot(
            existed = true,
            contents = configRepository.readRawConfigText(),
        )
    } else {
        ConfigSnapshot(
            existed = false,
            contents = null,
        )
    }
}
```

Add narrowly scoped repository methods if needed:

```kotlin
internal fun configFileExists(): Boolean

internal suspend fun readRawConfigTextForTransactionalReset(): Result<String>
```

### Required restore

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
            configRepository.deleteConfigFileForTransactionalReset()
        }

    return result.toRollbackStageResult(
        ResetStage.Config,
    )
}
```

### Required tests

1. config absent before reset, rollback restores absent;
2. config blank existing before reset, rollback restores blank existing file;
3. config whitespace existing before reset, rollback restores whitespace;
4. config non-empty existing before reset, rollback restores exact content.

---

## P1-004 — Strengthen transactional reset tests

### Problem

Current reset tests do not fully prove stop-on-first-failure or rollback failure
semantics.

### Required tests

#### Stop on first failed stage

Make Config fail and assert SetupInput/Forwards reset did not run.

Make SetupInput fail and assert Forwards reset did not run.

Example shape:

```kotlin
@Test
fun resetStopsImmediatelyWhenConfigStageFails() =
    runTest {
        val setupStore =
            RecordingSetupStore()

        val forwardsRepo =
            RecordingForwardsRepository()

        val coordinator =
            newCoordinator(
                configRepository =
                    failingConfigRepository(),
                setupStore = setupStore,
                forwardsRepository = forwardsRepo,
            )

        val result =
            coordinator.resetConfiguration()

        assertTrue(result is ResetResult.Failed)
        assertFalse(setupStore.resetCalled)
        assertFalse(forwardsRepo.resetCalled)
    }
```

#### Real rollback failure

Simulate a successful config reset, then setup failure, then config rollback failure.

Assert:

```text
ResetResult.Failed
rollback contains Failure(Config)
overall result is not Success
```

---

## P1-005 — Strengthen preference failure tests

### Problem

Some tests execute failure paths but do not assert emitted snackbar/error output.

### Required NetworkPolicyViewModel tests

Use a fake snackbar/event sink.

```kotlin
assertThat(snackbar.messages)
    .contains("Failed to update network policy")

assertThat(snackbar.messages)
    .doesNotContain("Network policy updated")
```

If using JUnit assertions only:

```kotlin
assertTrue(
    snackbar.messages.any {
        it.contains("Failed to update network policy")
    },
)

assertFalse(
    snackbar.messages.any {
        it == "Network policy updated"
    },
)
```

### Required setup persistence tests

1. default `persistPreferences` returns `Result.failure` and setup save fails;
2. injected `persistPreferences` returns `Result.failure` and setup save fails;
3. success message is not emitted on failure.

---

## P1-006 — Make network policy event delivery visible through app diagnostics

### Problem

`trySend` failure is logged with `Log.w`, but not surfaced through app diagnostics.

### Required behavior

Inject or use a reporter:

```kotlin
interface NetworkPolicyEventReporter {
    fun reportNetworkPolicyEventDeliveryFailed(
        cause: Throwable?,
    )
}
```

Implementation can publish a redacted lifecycle/log event.

### Required handling

```kotlin
private fun ProducerScope<NetworkPolicyStatus>
    .emitPolicyStatus(
        status: NetworkPolicyStatus,
    ) {
    val result = trySend(status)

    if (result.isFailure) {
        if (isExpectedChannelClose(result.exceptionOrNull())) {
            return
        }

        reporter.reportNetworkPolicyEventDeliveryFailed(
            result.exceptionOrNull(),
        )
    }
}
```

Expected closure/cancellation should not produce noisy false errors.

### Required tests

1. failed active delivery publishes diagnostic;
2. expected close/cancellation does not publish false diagnostic;
3. service can still read current policy state afterward.

---

## P1-007 — Clear active peer on local terminal states

### Problem

Native terminal mapping clears peer, but local status mutators such as `setPolicyBlocked`
and `setLocalError` do not clearly clear `remotePeerId`.

### Required helper

```kotlin
private fun TunnelStatus.withoutActivePeer():
    TunnelStatus =
    copy(
        remotePeerId = null,
        activeSessionCount = 0,
        mqttConnected = false,
    )
```

### Required usage

Use this helper for local terminal transitions:

```text
setPolicyBlocked
setLocalError
setNoNetwork if present
setConfigInvalid if present
```

### Required tests

1. policy-blocked local status clears remote peer;
2. local error clears remote peer;
3. config invalid clears remote peer;
4. no-network clears remote peer.

---

## P1-008 — Complete native status schema tests

### Required tests

1. missing mode fails startup/status verification visibly;
2. future mode fails startup/status verification visibly;
3. unknown runtime state includes `native_status_schema_error` if that is the chosen
   diagnostic;
4. unknown listen state still includes redacted raw value.

If implementation intentionally only diagnoses unknown mode/listen and not unknown
runtime state, document that decision and add a test proving unknown runtime state maps
to safe Error state.

---

# 7. P2 requirements

## P2-001 — Record final signoff evidence

Final signoff must include:

```text
final production SHA
fresh workflow run URL/id
workflow head SHA
Android focused lifecycle result
Android setup/identity result
Android forwards/reset result
Android logs/preferences/network result
Android full result
NOT RUN reasons where applicable
```

No old CI run should be reused.

---

## 8. Validation commands

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

### Setup identity

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.SetupSaveControllerTest' \
  --tests 'com.phillipchin.webrtctunnel.security.IdentityRepositoryTest' \
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

## 9. Final signoff checklist

Do not sign off until:

```text
performStartupAttempt uses explicit try/catch
StartOutcome.Aborted carries reason
required startup-completion tests exist
RetryPolicyResume checks pausedByPolicy
pending retry invalidated on every required boundary
active config rewrite failure stops startup visibly
recentLogs does not write repository logsError outside generation guard
recentLogs uses explicit try/catch
writeConfig no longer direct-writes config
transactional reset captures config existence separately from contents
transactional reset tests prove first-failure stop and rollback failure
network policy event delivery failure reaches app diagnostics
local terminal states clear remote peer
native schema tests cover missing/future mode
preference failure tests assert actual error/no success output
fresh CI/signoff evidence recorded
```
