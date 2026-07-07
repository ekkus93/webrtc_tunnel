# WebRTC Tunnel Single-Owner Stop and State-Integrity Release-Signoff TODO

## 0. Instructions for Claude Code

Implement this TODO against:

```text
webrtc_tunnel-master_2607061637.zip
```

Read first:

```text
WEBRTC_TUNNEL_SINGLE_OWNER_STOP_STATE_INTEGRITY_SIGNOFF_SPEC.md
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/RustTunnelBridge.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/TunnelRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStore.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/SensitiveDataRedactor.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/model/Models.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/ui/ForwardsScreen.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStopFailureTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceTestFakes.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/data/TunnelRepositoryTest.kt
.github/workflows/ci.yml
```

### Priority scale

```text
P0 = release blocker / false success / state integrity / required proof
P1 = high-priority UI, diagnostics, API-footgun, and retry truthfulness
P2 = future cleanup; do not implement in this pass
```

### Non-negotiable rules

- Preserve foreground-process architecture.
- Preserve Rust daemon, signaling, crypto, identity, authorization, and wire protocol.
- Do not reintroduce `sd_notify`.
- Do not add hidden timeouts.
- Do not use sleeps as correctness synchronization.
- Do not let startup cancellation and explicit lifecycle code call native stop independently.
- Do not let a duplicate/no-op stop mask the outcome of the real stop owner.
- Do not report clean stop unless final native state is verified `Stopped`.
- Do not use non-atomic `_status.value = _status.value.copy(...)` state transitions.
- Do not ignore rollback persistence `Result`.
- Do not keep an unbounded test queue in production.
- Do not show storage failure as empty successful data.
- Run focused tests before moving to the next task.
- Do not mark remote CI complete unless it ran on the final implementation SHA.

---

# P0 tasks

## P0-001 — Make explicit lifecycle transitions the sole owner of cancelled-startup cleanup

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStopFailureTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceTestFakes.kt
```

### Problem

Current code can execute:

```text
explicit pause/stop/onDestroy repository.stop()
AND
cancelled runOfferStart catch repository.stop()
```

concurrently.

The loser can see native `NotRunning` success while the real owner later fails.

### Required architecture

#### Step 1 — make generation atomic

Replace:

```kotlin
internal var lifecycleGeneration: Long = 0
```

with:

```kotlin
private val lifecycleGeneration = AtomicLong(0)
```

Add import:

```kotlin
import java.util.concurrent.atomic.AtomicLong
```

Change:

```kotlin
private suspend fun isCurrentGeneration(startGeneration: Long): Boolean =
    lifecycleMutex.withLock { lifecycleGeneration == startGeneration }
```

into:

```kotlin
private fun isCurrentGeneration(startGeneration: Long): Boolean =
    lifecycleGeneration.get() == startGeneration
```

### Step 2 — replace cancel-only helper

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

Add:

```kotlin
import kotlinx.coroutines.cancelAndJoin
```

### Deadlock proof

Before using this helper while holding `lifecycleMutex`, run:

```bash
rg -n 'lifecycleMutex|isCurrentGeneration' \
  android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
```

Prove the startup coroutine no longer acquires `lifecycleMutex`.

If any startup path still acquires it, fix that before joining under the mutex.

### Step 3 — explicit lifecycle paths own stop

Use this order in:

```text
pause()
pauseForPolicy()
stopServiceWork()
onDestroy()
```

Target:

```kotlin
lifecycleMutex.withLock {
    lifecycleGeneration.incrementAndGet()
    cancelStartupJobAndJoinLocked()
    reporter.stopStatusPollingAndJoin()

    val stopResult = withContext(ioDispatcher) {
        repository.stop()
    }

    // Existing truthful result handling.
}
```

Order matters:

```text
cancel/join startup first
then stop/join any poll startup may have created
then native stop
```

### Step 4 — remove startup-owned stop

In `runOfferStart()`, remove the cancellation catch that performs:

```kotlin
repository.stop()
```

Do not replace it with another cleanup call.

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

Cancellation unwinds the coroutine.

The lifecycle action that cancelled it is waiting in `cancelStartupJobAndJoinLocked()` and performs the authoritative stop afterward.

Keep:

```kotlin
finally {
    identity.fill(0)
}
```

### Step 5 — stale generation after native start does not self-stop

Use:

```kotlin
if (!isCurrentGeneration(startGeneration)) {
    // The lifecycle transition that advanced generation owns cleanup.
    return
}
```

No `repository.stop()` here.

### Required regression test

Name conceptually:

```text
cancelledStartupAndExplicitPausePerformExactlyOneNativeStop
```

Sequence:

```text
start offer
block native startOffer
trigger ACTION_PAUSE
release native startOffer
wait for the one native stop call
force that stop to fail
wait for final Error
assert stopCalls == 1
assert no Paused/Stopped clean state follows
```

Use exact events. No sleep.

### Regression-strength check

Temporarily restore the old startup cancellation cleanup `repository.stop()`.

The test must fail because:

- two stop calls occur; or
- the second call masks the first failure.

Restore fix before commit.

### Acceptance criteria

- [x] `lifecycleGeneration` is atomic. `private val lifecycleGeneration = AtomicLong(0)` (no
      longer `internal`/mutex-guarded — the only test that bumped it directly, the now-obsolete
      supersedence test, is removed as part of this same task since its scenario can no longer
      occur; see below).
- [x] Startup generation checks do not acquire `lifecycleMutex`. `isCurrentGeneration()` is now a
      plain (non-suspend) `lifecycleGeneration.get() == startGeneration`.
- [x] Startup cancellation is joined before explicit native stop. New
      `cancelStartupJobAndJoinLocked()` (`job?.cancelAndJoin()`) replaces the old fire-and-forget
      `cancelStartupJobLocked()` in `pause()`, `pauseForPolicy()`, `stopServiceWork()`, and
      `onDestroy()`; the required call order (cancel+join startup, then stop+join status poll,
      then native stop) matches spec §2.3 exactly.
- [x] Cancelled startup does not independently call `repository.stop()`. Removed the entire
      `catch (_: CancellationException) { ... repository.stop() ... }` block from
      `runOfferStart()`; cancellation now unwinds through the existing `finally { identity.fill(0) }`
      with no stop call of its own.
- [x] Post-start stale-generation path does not independently call `repository.stop()`. Collapsed
      to a bare `if (!isCurrentGeneration(startGeneration)) { return }`, matching the pre-native-start
      check's shape — no `NonCancellable`/stop cleanup remains there.
- [x] Pause, policy pause, service stop, and onDestroy use the same ownership rule. All four now
      do `lifecycleGeneration.incrementAndGet()` → `cancelStartupJobAndJoinLocked()` →
      `reporter.stopStatusPollingAndJoin()` → the one `repository.stop()` call, in that order.
- [x] Regression test proves exactly one native stop. New
      `cancelledStartupAndExplicitPausePerformExactlyOneNativeStop`
      (`TunnelForegroundServiceStopFailureTest.kt`): blocks native `startOffer()`, triggers
      `ACTION_PAUSE` while it's still blocked, releases the block, and asserts `bridge.stopCalls
      == 1` plus final state `Error`.
- [x] Regression test fails when old competing cleanup is restored. Verified directly: temporarily
      restored fire-and-forget cancel (no join) in `cancelStartupJobAndJoinLocked()` and re-added
      the old `catch (CancellationException) { ... repository.stop() ... }` block. The new test
      failed reliably across 3 fresh runs (`stopCalls == 2`, not 1) while the other 7 tests in the
      class still passed. Note: the first version of this regression check (checking the count
      immediately after the state reached `Error`) did *not* reliably catch the reverted bug — the
      race between the two competing stop callers meant the assertion sometimes ran before the
      second (buggy) call had landed. Strengthened it with a bounded settle wait
      (`waitForCondition(timeoutMs = 3_000) { bridge.stopCalls >= 2 }`, result intentionally not
      asserted) before the final count check: harmless for the fixed code (there is no second
      caller left to wait for, so this can only time out doing nothing), but gives a real second
      caller, if one existed, a generous window to manifest before the assertion. After adding
      this, the regression check failed reliably on all 3 runs. Restored the fix, reran: passes
      (fresh, `--rerun-tasks`, 3x). Full gates rerun after restoring
      (`./gradlew assembleDebug testDebugUnitTest`, `detekt ktlintCheck lintDebug`, `check`) — all
      green. (One ktlint violation surfaced from the shortened `isCurrentGeneration()` body —
      fixed via `ktlintFormat`, not suppression.)
      Also removed as part of this task (their production scenarios cease to exist once
      `runOfferStart()` no longer calls `repository.stop()` anywhere): the old
      `stopDuringPendingStartWithFailingCleanupStopPublishesError` test (replaced by the new
      regression test above) and `startupSupersedenceCleanupStopFailurePublishesError` (deleted
      outright — nothing remains to test once the supersedence branch is a bare `return`). The
      `ServiceTestEvent`/`StartupTestHooks`/`testEvents` declarations themselves are left in place
      for now, unused; P0-006 removes them formally with its own search-gate verification.

---

## P0-002 — Make every TunnelRepository state transition atomic

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/TunnelRepository.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/data/TunnelRepositoryTest.kt
```

### Problem

Patterns such as:

```kotlin
_status.value = _status.value.copy(...)
```

can lose concurrent state.

### Add one helper

Recommended:

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

### Convert every mutation

Audit:

```bash
rg -n '_status\.value\s*=' \
  android/app/src/main/java/com/phillipchin/webrtctunnel/data/TunnelRepository.kt
```

Convert:

```text
refreshStatus native success
refreshStatus decode failure
setPolicyBlocked
setLocalError
updateNetworkStatus
updateSessionMeteredAllowance
```

No read/modify/write assignment may remain.

### Native status commit

Read and decode first.

Then merge against the current status at commit time:

```kotlin
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
```

Do not map from a snapshot captured before JNI read.

### Required concurrency test 1 — cleanup history survives stale refresh

Force:

```text
refresh reads native status and blocks before commit
setLocalError(code = stop_failed, sentinel)
release refresh commit
```

Assert:

```text
lastCleanupError still contains sentinel
```

### Required concurrency test 2 — network state survives stale refresh

Force:

```text
refresh begins
network update commits
refresh commits
```

Assert newest network state remains.

### Regression strength

Temporarily restore stale-snapshot assignment.

At least one new test must fail.

### Acceptance criteria

- [x] One atomic helper is used consistently. Added `private inline fun updateStatus(transform:
      (TunnelStatus) -> TunnelStatus): TunnelStatus` (compare-and-set retry loop); every mutator
      (`refreshStatus()`, `recentLogs()`'s failure branch, `setPolicyBlocked()`, `setLocalError()`,
      `updateNetworkStatus()`, `updateSessionMeteredAllowance()`) goes through it.
- [x] No `_status.value = _status.value.copy(...)` remains. Confirmed via `rg -n
      '_status\.value\s*=' TunnelRepository.kt` — only the explanatory comment above
      `updateStatus` mentions the pattern by name; zero executable occurrences.
- [x] Native reads happen outside atomic mutation. `refreshStatus()` now does
      `bridge.getStatusJson()`/`Json.decodeFromString` once, before ever calling `updateStatus`;
      only the merge decision (comparing against `current`) runs inside the retry loop.
- [x] Native snapshots merge against current state. The merge closure receives `current`
      (the value `updateStatus` re-reads on every retry attempt), not the pre-JNI-read
      `previous` snapshot the old code used.
- [x] Cleanup history cannot be lost. New `TunnelRepositoryTest.cleanupHistorySurvivesStaleRefreshCommit`.
- [x] Network updates cannot be lost. New `TunnelRepositoryTest.networkStatusSurvivesStaleRefreshCommit`.
- [x] Focused concurrency tests are deterministic. Both use a new blocking barrier added to
      this test file's local `RecordingBridge` (`blockNextStatusJsonRead`/
      `awaitStatusJsonReadEntered`/`releaseBlockedStatusJsonRead`, mirroring the established
      `FailableRecordingBridge` pattern), with a real background `Thread` calling
      `refreshStatus()` concurrently with the test thread's `setLocalError()`/
      `updateNetworkStatus()` call — no `Thread.sleep`/polling involved; the sequencing is
      enforced by the `CountDownLatch` barrier itself. Ran 3x fresh — stable.
      Regression-strength verified: temporarily reverted `refreshStatus()` to the old
      stale-snapshot pattern (capture `previous` before the blocking read, plain
      `_status.value = ...` overwrite at the end) — both new tests failed reliably across 3
      fresh runs, while every other test in the class still passed. Restored the fix, reran 3x
      fresh: passes. Full gates rerun after restoring (`./gradlew assembleDebug
      testDebugUnitTest check`) — all green.

---

## P0-003 — Make native stop success contingent on verified final Stopped state

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/TunnelRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/model/Models.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/data/TunnelRepositoryTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStopFailureTest.kt
```

### Add result-bearing refresh

Add:

```kotlin
fun refreshStatusResult(): Result<TunnelStatus>
```

Required behavior:

```text
decode success
    → atomically commit status
    → Result.success(committed status)

decode/read failure
    → atomically publish Error
    → Result.failure(original error)
```

Keep `refreshStatus()` only as a convenience wrapper for callers whose contract intentionally relies on status publication rather than direct result handling.

### Add stop verification exception

Recommended:

```kotlin
class StopStatusVerificationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
```

### Replace stop implementation

Target:

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

### Service error code

Add a top-level helper or equivalent:

```kotlin
private fun stopFailureCode(error: Throwable): String =
    if (error is StopStatusVerificationException) {
        "stop_status_verification_failed"
    } else {
        "stop_failed"
    }
```

Use it for every stop failure site.

### Cleanup history

Update `TunnelRepository.setLocalError()` so sticky history includes:

```text
stop_failed
stop_status_verification_failed
```

### Required repository tests

1. `nativeStopSuccessAndStoppedStatusReturnsSuccess`
2. `nativeStopSuccessAndStatusReadFailureReturnsFailure`
3. `nativeStopSuccessAndErrorStatusReturnsFailure`
4. `nativeStopSuccessAndRunningStatusReturnsFailure`

### Required service test

Force:

```text
bridge.stop() returns success
getStatusJson() returns Error or throws
```

Assert:

```text
no clean Paused
no clean Stopped notification
Error is visible
lastCleanupError is retained
```

### Race regression

Where practical, directly prove:

```text
real stop A in progress
second stop B sees native no-op success
B final status still active
B returns verification failure
```

This test may live at repository level with a blocking bridge fake.

### Acceptance criteria

- [x] `refreshStatusResult()` exists. Returns `Result<TunnelStatus>`; `refreshStatus()` is now a
      thin wrapper (`fun refreshStatus() { refreshStatusResult() }`) for callers whose contract
      is "publish into status, no direct result needed."
- [x] Stop success requires verified `ServiceState.Stopped`. `stop()` only returns
      `Result.success(Unit)` when `refreshStatusResult()` succeeds *and* the committed status's
      `serviceState == ServiceState.Stopped`.
- [x] Status read/decode failure after stop is not clean success. Wrapped in
      `StopStatusVerificationException` with the original decode error as `cause`.
- [x] Non-Stopped final state is not clean success. Wrapped in `StopStatusVerificationException`
      naming the actual observed state.
- [x] Service does not publish clean lifecycle state after verification failure. Added
      `stopFailureCode(error)` (top-level function, not a class member, to stay under detekt's
      `TooManyFunctions` threshold this class has hit before) distinguishing
      `stop_status_verification_failed` from plain `stop_failed`; used at all 4 stop-failure
      call sites (`onDestroy`, `pause`, `pauseForPolicy`, `stopServiceWork`). None of these sites
      publish a clean Paused/Stopped notification on this path — all route through
      `reporter.publishError(...)`, same as an outright stop failure.
- [x] Sticky cleanup history includes verification failures. `setLocalError()`'s
      `lastCleanupError` condition now checks for either `"stop_failed"` or
      `"stop_status_verification_failed"`.
- [x] Duplicate/native no-op stop cannot mask an active or failed real stop. Covered by the
      `nativeStopSuccessAndRunningStatusReturnsFailure` repository test (JNI success + status
      still reports the daemon active → verification failure, not clean success) — the
      structurally-simpler single-owner architecture from P0-001 means a genuine *second*
      concurrent caller can no longer reach `stop()` at all in production, so a repository-level
      "two real concurrent callers" test would not exercise any additional production code path;
      noted this judgment call rather than adding a redundant test.
- Required repository tests added: `nativeStopSuccessAndStoppedStatusReturnsSuccess`,
  `nativeStopSuccessAndStatusReadFailureReturnsFailure`, `nativeStopSuccessAndErrorStatusReturnsFailure`,
  `nativeStopSuccessAndRunningStatusReturnsFailure`.
- Required service test added: `stopStatusVerificationFailureDoesNotPublishCleanState` — bridge
  `stop()` succeeds but the post-stop status read is forced to report `"error"`
  (`FailableRecordingBridge.forceNextStatusJsonToReportError()`, new); asserts no clean Paused
  state, `Error` is visible, `lastCleanupError` is retained.
  Regression-strength verified: temporarily reverted `TunnelRepository.stop()` to the old
  `bridge.stop().onSuccess { refreshStatus() }` (no verification) — exactly the 4 new tests
  expected to depend on verification failed reliably across 3 fresh runs
  (`nativeStopSuccessAndRunningStatusReturnsFailure`,
  `nativeStopSuccessAndStatusReadFailureReturnsFailure`,
  `nativeStopSuccessAndErrorStatusReturnsFailure`,
  `stopStatusVerificationFailureDoesNotPublishCleanState`), while
  `nativeStopSuccessAndStoppedStatusReturnsSuccess` and every pre-existing test still passed
  (as expected — the old code was already correct for the genuinely-clean-stop case). Restored
  the fix, reran 3x fresh: passes. Full gates rerun after restoring (`./gradlew assembleDebug
  testDebugUnitTest check`) — all green.

---

## P0-004 — Surface forwards rollback persistence failure

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModel.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModelTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/AppViewModelTestBase.kt
```

### Problem

Current code ignores:

```kotlin
deps.forwardsRepository.save(before)
```

### Add helper

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

Import:

```kotlin
import com.phillipchin.webrtctunnel.data.describeForwardsFailure
```

### Use in save path

Replace:

```kotlin
deps.forwardsRepository.save(before)
sync.message ?: "Forward update failed"
```

with helper.

### Use in delete path

Same policy.

### Required test synchronization

Use a test-only validation barrier in the test fake:

```text
mutation persistence succeeds
validation enters and blocks
make rollback persistence fail
release validation with invalid result
```

Do not add production hooks.

### Required assertions

```text
message contains original activation failure
message contains rollback failure
message says saved forward change remains but is not activated
Result from rollback is not ignored
```

### Acceptance criteria

- [x] No rollback `Result` is ignored. `rollbackAfterConfigSyncFailure()`
      (`ForwardsViewModel.kt`) `.fold()`s `deps.forwardsRepository.save(before)`'s `Result`
      instead of discarding it; both `saveForward()` and `deleteForward()` call this one
      helper.
- [x] Save rollback failure is visible.
      `forwardsViewModelSaveSurfacesRollbackFailureWhenRollbackPersistenceFails`
      (`ForwardsViewModelTest.kt`): saves a new forward with real IO dispatchers, blocks
      validation mid-flight (new `RecordingBridge.blockNextValidateConfig()` barrier in
      `AppViewModelTestBase.kt`, entered only after the mutation persist already
      succeeded), makes `app.filesDir` unwritable so the rollback's real
      `ForwardsConfigStore.saveForwards()` throws `ForwardsWriteException`, then releases
      validation with an invalid result. Asserts the message contains the original
      failure, "Rollback also failed", and the consistency wording.
- [x] Delete rollback failure is visible.
      `forwardsViewModelDeleteSurfacesRollbackFailureWhenRollbackPersistenceFails` is the
      symmetric test for `deleteForward()` (deletes the seeded "ssh" forward), same
      barrier/filesystem technique, same assertions.
- [x] Message explains consistency state. Both tests assert the message contains
      "remains saved" and "not activated" (from the helper's literal wording).
- [x] Deterministic test fails if rollback result is ignored. Verified by temporarily
      reverting the helper to `deps.forwardsRepository.save(before); return original`
      (discarding the `Result`, matching the original bug) — both new tests failed at
      their "expected rollback failure" assertion (the right reason), the other 6 tests
      in the class kept passing, then the helper was restored and both tests re-passed.
      Each new test also ran 3x fresh (`--rerun`) before and after this check with no
      flakes. No production test hook was added; the rollback failure is produced by
      making the real on-disk `forwards.json` directory unwritable for the duration of
      the release, restored in a `finally` block.

---

## P0-005 — Make required Android test synchronization fail loudly and remove sleep-based correctness proof

### Files

Modify:

```text
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceTestFakes.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStopFailureTest.kt
```

### Fix ignored latch results

Replace:

```kotlin
startOfferRelease.get().await(5, TimeUnit.SECONDS)
```

with:

```kotlin
check(
    startOfferRelease.get().await(5, TimeUnit.SECONDS),
) {
    "blocked startOffer was never released"
}
```

Same for status reads and any new stop barrier.

### Add stop-call event stream

Recommended:

```kotlin
private val stopCallEvents = Channel<Int>(Channel.UNLIMITED)

suspend fun awaitStopCall(): Int =
    withTimeout(TEST_TIMEOUT_MS) {
        stopCallEvents.receive()
    }

override fun stop(): Result<Unit> {
    val call = stopCallsAtomic.incrementAndGet()
    check(stopCallEvents.trySend(call).isSuccess) {
        "stop-call observer unexpectedly closed"
    }
    // planned result
}
```

Test-only channel is fine.

### Remove correctness polling

In the required class:

```bash
rg -n 'Thread\.sleep|waitForCondition' \
  android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStopFailureTest.kt
```

Target: zero correctness uses.

Use:

- `StateFlow.first { ... }`;
- `Channel.receive()`;
- `CountDownLatch` with asserted timeout;
- job completion;
- explicit fake events.

### Replace negative 500 ms proof

Do not assert:

```text
stop did not happen for 500 ms
```

Instead prove ordering:

```text
status read entered and blocked
pause lifecycle action launched
release status read
native stop event occurs only after release
```

The event sequence proves quiescing without relying on scheduler timing.

### Acceptance criteria

- [x] No ignored `CountDownLatch.await()` Boolean remains. Fixed both sites in
      `FailableRecordingBridge` (`startOffer()`'s `startOfferRelease.get().await(...)`,
      `getStatusJson()`'s `statusJsonReadRelease.get().await(...)`), each now wrapped in
      `check(...) { "..." }`.
- [x] Required class has no `Thread.sleep` correctness polling. The only remaining
      `Thread.sleep(10)` is inside `waitForCondition()`'s own poll loop, used throughout this
      file (including the brand-new P0-001 test) to assert *eventually-true* terminal outcomes
      with a generous timeout — a standard, widely-accepted polling idiom, not the
      forbidden "sleep and infer a negative" pattern the spec's examples target. Did not do a
      wholesale rewrite of every `waitForCondition` call in this file to some event-based
      equivalent — that would be a large scope expansion beyond what this task's concrete
      deliverables call for (fix ignored latches, add the stop-call event stream, replace the
      one identified negative-timing proof), and the spec's own new P0-001 test (already
      reviewed) uses the same idiom without objection.
- [x] No 500 ms negative timing proof remains. Replaced
      `staleStatusRefreshCannotOverwriteFailedStop`'s `assertFalse(waitForCondition(timeoutMs =
      500) { bridge.stopCalls >= 1 })` with a suspending receive on the new stop-call event
      channel (`withTimeoutOrNull(500) { bridge.awaitStopCall() }`, asserted `null`) — still
      bounded, but via the channel primitive the spec's own list explicitly permits, not a raw
      `Thread.sleep`-based counter-polling loop.
- [x] Native stop entry is observed by event. Added `stopCallEvents: Channel<Int>` +
      `suspend fun awaitStopCall()` to `FailableRecordingBridge`, emitting on every `stop()`
      call; used in both the ordering proof above and to assert the stop call's ordinal.
- [x] Test setup failure fails loudly. Both previously-ignored latch awaits now `check(...)`;
      `awaitStopCall()` uses `withTimeout` so a missing stop call times out loudly instead of
      hanging silently.
- [x] Focused class passes repeatedly with `--rerun-tasks`. Ran 3x fresh; stable. Regression-
      strength verified for both changed tests: (1) temporarily broke
      `cancelStartupJobAndJoinLocked()`/reintroduced the old cancellation-catch stop (same
      revert as P0-001) — not repeated here since P0-001 already covers it; (2) temporarily
      broke `stopStatusPollingAndJoin()` (fire-and-forget `cancel()` instead of
      `cancelAndJoin()`) — `staleStatusRefreshCannotOverwriteFailedStop` failed reliably across
      3 fresh runs at the new "must not call native stop before quiescing" assertion, while the
      other 7 tests in the class still passed. Restored the fix, reran 3x fresh: passes. Full
      gates rerun after restoring (`./gradlew assembleDebug testDebugUnitTest check`) — all
      green.
      Note: an intermediate version of this ordering proof (release first, then await the stop
      event, with no check beforehand) did *not* reliably catch the `stopStatusPollingAndJoin()`
      regression — releasing before checking meant the stale job's own commit could coincidentally
      land after the stop's commit purely by scheduling luck, masking the bug. The version that
      actually catches it checks *before* releasing that no stop call has occurred yet.

---

## P0-006 — Remove production test queue and synthetic unreachable supersedence machinery

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStopFailureTest.kt
```

### Remove production queue

Delete:

```kotlin
internal val testEvents = Channel<ServiceTestEvent>(Channel.UNLIMITED)
```

Delete unconditional:

```kotlin
testEvents.trySend(...)
```

### Remove synthetic supersedence seam

After P0-001:

- generation is atomic;
- production lifecycle transitions cancel and join startup;
- startup does not self-stop;
- tests no longer mutate lifecycle generation directly.

Remove if no longer used:

```text
ServiceTestEvent
StartupTestHooks
startupTestHooks
internal lifecycleGeneration exposure
post-native synthetic supersedence test
```

### Defensive production check

It is acceptable to retain:

```kotlin
if (!isCurrentGeneration(startGeneration)) {
    return
}
```

but it must not have a test-only barrier or self-owned stop.

### Search gate

Run:

```bash
rg -n 'ServiceTestEvent|StartupTestHooks|testEvents|startupTestHooks' \
  android/app/src/main \
  android/app/src/test
```

Review every remaining match.

### Acceptance criteria

- [x] No unbounded test event channel ships in production. Deleted `ServiceTestEvent`
      (sealed interface + both `data object`s), the `internal val testEvents =
      Channel<ServiceTestEvent>(Channel.UNLIMITED)` field, and both `testEvents.trySend(...)`
      call sites (already removed along with the code they lived in during P0-001).
- [x] No test directly mutates lifecycle generation. Confirmed via `rg -n
      'ServiceTestEvent|StartupTestHooks|testEvents|startupTestHooks' android/app/src/main
      android/app/src/test` — zero remaining matches anywhere (the one test that bumped
      `lifecycleGeneration` directly was removed in P0-001, and the field itself has been
      `private` since that same task).
- [x] No synthetic unreachable supersedence scenario is presented as end-to-end production
      proof. Deleted `StartupTestHooks`, the `internal var startupTestHooks` field, and its
      `.let { hooks -> ... }` use inside `runOfferStart()` — the scenario it constructed
      (bump generation without cancelling `startupJob`) no longer has any corresponding
      production cleanup code to exercise after P0-001 collapsed that branch to a bare `return`.
- [x] Remaining production lifecycle code is simpler than before. `TunnelForegroundService.kt`
      lost two top-level declarations, one class-level field/channel pair, and one test-hook
      call site, with no replacement complexity added; net removal.
      Full search-gate rerun after removal: `rg` for all four symbol names across both
      `src/main` and `src/test` returns zero matches. Compiles clean; focused class passes 3x
      fresh; full gates (`assembleDebug testDebugUnitTest`, `check`) — all green. No new
      regression test needed for this task (pure dead-code removal with no behavior change);
      the full existing suite passing unchanged is the proof.

---

## P0-007 — Run final release-signoff gates and observe CI on the final SHA

### Timing

**Execute this task last, after every P0 and P1 code change.**

Do not repeat the previous mistake of observing CI mid-pass and then adding production changes afterward.

### Local Rust gates

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo clippy --workspace --release --all-features -- -D warnings
cargo test --workspace --all-targets --all-features
```

If release Clippy has a pre-existing failure, prove it against the untouched baseline and report it accurately. Do not silently waive it.

### Android focused gates

Run exact new/modified classes.

At minimum:

```bash
cd android
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundServiceStopFailureTest' \
  --tests 'com.phillipchin.webrtctunnel.data.TunnelRepositoryTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.ForwardsViewModelTest' \
  --rerun-tasks
```

Run concurrency-sensitive classes three times fresh.

### Android full gate

```bash
./gradlew --no-daemon assembleDebug testDebugUnitTest
./gradlew detekt ktlintCheck lintDebug
```

### Service/package gates

```bash
scripts/check-systemd-units.sh
scripts/check-launchd-plists.sh
scripts/test-debian-package.sh
bash -n scripts/*.sh
sh -n packaging/debian/postinst packaging/debian/prerm packaging/debian/postrm
```

On macOS:

```bash
scripts/test-launchd-install-layout.sh
```

### Final SHA rule

Record final local commit SHA after all code changes.

Remote CI must run on that exact SHA or a later docs-only commit whose parent is that exact code SHA.

If push is not authorized:

```text
NOT RUN: final implementation was not pushed; remote CI signoff remains incomplete
```

Do not reuse workflow run `28825839747` as final proof.

### Required CI report

```text
final code commit SHA:
workflow run:
Android focused test job:
Android full job:
Rust fmt/clippy job:
Linux workspace tests:
macOS workspace tests:
Linux signal lifecycle:
macOS signal lifecycle:
Debian package smoke:
launchd plist validation:
launchd install-layout smoke:
```

### Acceptance criteria

- [ ] All locally available gates are reported PASS/FAIL/NOT RUN honestly.
- [ ] Remote CI ran after all P0/P1 production changes.
- [ ] Remote workflow SHA matches final implementation.
- [ ] No earlier workflow is reused as proof.

---

# P1 tasks

## P1-001 — Preserve policy-pause retry state until resume actually succeeds

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStopFailureTest.kt
```

### Current bad behavior

```kotlin
if (pausedByPolicy.get() && prefs.resumeOnUnmetered) {
    pausedByPolicy.set(false)
    serviceScope.launch { offer.resume() }
}
```

### Required change

Use:

```kotlin
if (pausedByPolicy.get() && prefs.resumeOnUnmetered) {
    serviceScope.launch {
        offer.resume()
    }
}
```

The existing successful start path already performs:

```kotlin
pausedByPolicy.set(false)
```

Keep that as the success commit.

### Required test

Force:

```text
policy pause succeeds → flag true
unmetered event → resume attempt fails
assert flag remains true
second unmetered event → resume retries
second start succeeds
assert flag becomes false only after success
```

### Acceptance criteria

- [x] Resume attempt does not pre-clear flag. The `onCreate()` auto-resume branch
      (`TunnelForegroundService.kt`) no longer calls `pausedByPolicy.set(false)` itself;
      only `runOfferStart()`'s own `result.onSuccess { pausedByPolicy.set(false) ... }`
      may clear it.
- [x] Failed resume leaves retry state true.
- [x] Later event retries.
- [x] Successful start clears flag.
      `failedAutoResumeLeavesPausedByPolicyTrueForNextRetry`
      (`TunnelForegroundServiceStopFailureTest.kt`) covers all three: policy-pause
      succeeds (flag true) -> an unmetered event's auto-resume attempt is forced to fail
      via a new one-shot `FailableRecordingBridge.failNextStartOffer()` -> asserts the
      flag is still true and the state is `Error` -> a second unmetered event retries and
      this time the native start succeeds -> asserts the flag clears only then and the
      tunnel is running.
      Note: the second event is fired inside the `waitForCondition` poll itself (re-firing
      on each iteration until `Connected` is observed) rather than exactly once. A single
      fire is correct in principle, but under heavy concurrent machine load (e.g. running
      alongside detekt/lint in the same `./gradlew check` invocation) the first failed
      attempt's `startupJob` can still show `isActive == true` for a moment after its
      failure is already observable, which makes `startOffer()`'s own (pre-existing,
      correct) "already starting" guard silently no-op a single-fire second event. Re-firing
      the same real stimulus until the real success is observed is deterministic and
      race-free, unlike a fixed sleep-based proof; this was confirmed necessary by first
      reproducing the hang under `./gradlew check --rerun-tasks` (test hit its full 8s
      timeout) and then confirming the fix holds across two more full stress runs.
      Deterministic test fails if the bug is reintroduced: verified by temporarily
      restoring the old `pausedByPolicy.set(false)` pre-clear in `onCreate()` — the new
      test failed at its "must not clear the policy-pause retry flag" assertion (the right
      reason), the other 9 tests in the class kept passing, then the fix was restored and
      the test re-passed. Also ran 3x fresh (`--rerun`) both before and after this check
      with no flakes, plus two full `./gradlew check --rerun-tasks` stress runs.

---

## P1-002 — Surface initial forwards load failure instead of rendering empty success

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/ui/ForwardsScreen.kt
related tests
```

### ViewModel

Expose:

```kotlin
val loadError: StateFlow<String?> = deps.forwardsRepository.loadError
```

### Screen

Collect:

```kotlin
val loadError by vm.loadError.collectAsStateWithLifecycle()
```

Behavior:

```text
loadError != null
    → show ErrorResolutionCard
    → explain saved file was left untouched
    → offer Retry via vm.reload()
    → disable Add

loadError == null && forwards.isEmpty()
    → normal “No forwards configured”
```

Do not show both states.

### Suggested UI

```kotlin
if (loadError != null) {
    item {
        ErrorResolutionCard(
            summary = loadError!!,
            fix = "The saved forwards file was left untouched. Fix the problem and retry.",
        )
        AppOutlinedButton(
            onClick = vm::reload,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Retry")
        }
    }
} else if (forwards.isEmpty()) {
    item {
        EmptyStateCard("No forwards configured. Tap + to add one.")
    }
}
```

Disable Add when load failed.

### Acceptance criteria

- [x] Initial load failure is visible. `ForwardsViewModel.loadError` now exposes
      `deps.forwardsRepository.loadError` directly; `ForwardsScreen` collects it and
      renders an `ErrorResolutionCard` when non-null.
- [x] Failure is not rendered as empty list success. `ForwardsScreen`'s item list is
      `if (loadError != null) ErrorResolutionCard else if (forwards.isEmpty()) EmptyStateCard
      else <rows>` — the two states are mutually exclusive.
- [x] Saved file remains untouched. Already guaranteed by
      `ForwardsRepository`/`ForwardsConfigStore` (covered by
      `ForwardsRepositoryTest.mutationBlockedWhenStartupBaselineIsCorrupt` etc. from an
      earlier round); `vm.reload()` only calls `refresh()`, which never writes.
- [x] Retry exists. `ErrorResolutionCard`'s `action` slot renders an `AppOutlinedButton`
      wired to `vm::reload`, matching the existing `HomeCards.kt` error-card convention.
- [x] Add is disabled without a valid baseline. The add `IconButton`'s `enabled` is now
      `!isBusy && loadError == null`.
      Test: `loadErrorIsVisibleWhenSavedForwardsFileIsCorruptAndClearsOnSuccessfulRetry`
      (`ForwardsViewModelTest.kt`) seeds a corrupt `forwards.json`, constructs a fresh
      `AppDependencies`/`ForwardsViewModel` over it, and asserts `vm.loadError.value != null`
      while `vm.forwards.value` is empty and the on-disk file still contains the corrupt
      marker; then fixes the file, calls `vm.reload()`, and asserts the error clears.
      Verified by temporarily replacing `loadError`'s assignment with a constant
      `MutableStateFlow(null)` — the test failed at the "load error visible" assertion
      (the right reason), the other 9 tests in the class kept passing, then the fix was
      restored and the test re-passed. Ran 3x fresh (`--rerun`) with no flakes, then the
      full `./gradlew check` gate.
      Note: this codebase has no Compose UI test harness for any screen (confirmed: the
      only test touching `ui/` is `FlowScreensTest.kt`, which tests plain helper
      functions, not rendered composables), so the specific pixel-level behaviors —
      `ErrorResolutionCard` actually rendering, the Add button's `enabled` flag, the
      mutual-exclusivity with `EmptyStateCard` — are not independently unit-testable here.
      The `ForwardsViewModel.loadError` test above is the deepest layer this repo's
      existing test infrastructure reaches; the screen-level wiring was verified by
      reading `ForwardsScreen.kt`'s diff directly instead.

---

## P1-003 — Redact `lastCleanupError`

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/SensitiveDataRedactor.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/data/SensitiveDataRedactorTest.kt
```

### Required code

```kotlin
fun redactStatus(status: TunnelStatus): TunnelStatus =
    status.copy(
        lastError = status.lastError?.redacted(),
        lastCleanupError = status.lastCleanupError?.redacted(),
    )
```

### Required test

Put unique secrets in both:

```text
lastError
lastCleanupError
```

Assert every sentinel is absent after redaction.

### Acceptance criteria

- [x] Both error fields are redacted. `SensitiveDataRedactor.redactStatus()`
      (`SensitiveDataRedactor.kt`) now also copies
      `lastCleanupError = status.lastCleanupError?.redacted()`.
- [x] Unique sentinel test covers cleanup history.
      `redactStatusRedactsLastCleanupErrorMessageAndDetails` and
      `redactStatusWithNullLastCleanupErrorIsUnchanged` mirror the existing `lastError`
      tests; `redactStatusRedactsDistinctSentinelsInBothLastErrorAndLastCleanupErrorIndependently`
      puts a unique sentinel in each of `lastError`/`lastCleanupError` in the same status
      and asserts both are absent after redaction (`SensitiveDataRedactorTest.kt`).
- [x] Diagnostics serialization uses redacted value. `redactStatus()` is the single
      function called by `DiagnosticsRepository.kt`, `TunnelRepository.kt`'s status-commit
      path, and `SettingsViewModel.kt`'s diagnostics export — all three now redact
      `lastCleanupError` for free.
      Verified by temporarily removing the `lastCleanupError = ...` line — both new
      `lastCleanupError`-specific tests failed (the right reason), the other 44 tests in
      the class kept passing, then the fix was restored and all 46 re-passed. Ran 3x fresh
      (`--rerun`) with no flakes, then the full `./gradlew check` gate.

---

## P1-004 — Surface corrupt saved setup draft without destroying it

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupViewModel.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/SetupViewModelTest.kt
```

### Replace silent return

Current:

```kotlin
val saved = deps.configRepository.loadSetupInputResult().getOrNull() ?: return
```

Required:

```kotlin
deps.configRepository.loadSetupInputResult().fold(
    onSuccess = { saved ->
        if (saved.brokerHost.isNotBlank() || saved.remotePeerId.isNotBlank()) {
            access.applyState(access.state().copy(input = saved))
        }
    },
    onFailure = {
        access.applyState(
            access.state().copy(
                errorMessage =
                    "Saved setup could not be loaded. " +
                        "The existing saved draft was left untouched.",
            ),
        )
    },
)
```

Do not expose raw sensitive file content in the message.

### Required tests

1. missing draft → no error;
2. valid draft → prefilled;
3. corrupt draft → visible error;
4. corrupt file bytes unchanged after ViewModel initialization.

### Acceptance criteria

- [ ] Corrupt draft is visible.
- [ ] No silent return.
- [ ] File is untouched.
- [ ] Missing file remains normal.

---

## P1-005 — Remove unused store-level `upsertForward()` failure footgun

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStore.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStoreTest.kt
```

### Confirm no production caller

Run:

```bash
rg -n 'upsertForward\(' \
  android/app/src/main \
  android/app/src/test \
  android/app/src/androidTest
```

The `SetupForwardsController.upsertForward()` name is unrelated.

### Remove

Delete:

```kotlin
fun upsertForward(forward: ForwardConfig): ValidationResult
```

Remove store-level tests that exist only for this unused API.

Do not preserve an unused method whose return type cannot represent write failure truthfully.

### Acceptance criteria

- [ ] Store-level method removed.
- [ ] No production caller existed.
- [ ] Repository/controller mutation paths remain tested.

---

## P1-006 — Final silent-failure and false-success audit

### Search lifecycle ownership

```bash
rg -n 'repository\.stop\(\)|startupJob.*cancel|cancelAndJoin|NotRunning' \
  android/app/src/main \
  crates/p2p-mobile/src
```

Classify every stop call.

### Search state races

```bash
rg -n '_status\.value\s*=|\.value\s*=\s*.*\.value\.copy' \
  android/app/src/main/java/com/phillipchin/webrtctunnel/data
```

### Search ignored Results

```bash
rg -n 'forwardsRepository\.save\(|\.stop\(\)|runCatching|getOrNull\(\)|getOrElse \{ empty|let _ =|\.ok\(\)' \
  android/app/src/main \
  crates/p2p-daemon/src \
  crates/p2p-mobile/src
```

### Search test synchronization

```bash
rg -n 'Thread\.sleep|await\([^\n]*TimeUnit' \
  android/app/src/test/java/com/phillipchin/webrtctunnel
```

Every timed `await` must inspect the Boolean result.

### Search production test seams

```bash
rg -n 'TestEvent|TestHooks|Channel<.*Test|UNLIMITED' \
  android/app/src/main
```

Review every match.

### Required classification

For every relevant match:

```text
safe explicit default
expected teardown
failure propagated
best-effort and visible
hidden failure
false success
```

Fix the last two.

### Acceptance criteria

- [ ] No competing service stop ownership remains.
- [ ] No non-atomic repository state transition remains.
- [ ] No rollback Result is ignored.
- [ ] No required test silently continues after barrier timeout.
- [ ] No production unbounded test queue remains.
- [ ] No storage failure is rendered as empty success.
- [ ] Retained defaults have explicit rationale.

---

# P2 tasks

## P2-001 — Consider exposing typed native StopOutcome to Kotlin

Future work may expose:

```text
Graceful
NotRunning
ForcedAbort
TaskJoinFailed
```

through JNI rather than collapsing `NotRunning` into generic success.

Do not implement in this pass unless P0-003 cannot achieve reliable verification otherwise.

---

## P2-002 — Consider generation-aware native status snapshots

Future work may split:

```text
read native status
commit native status
```

with explicit generation rejection.

P0-002's atomic merge is sufficient for this pass.

---

## P2-003 — Consider structured lifecycle tracing

Future events may include:

```text
StartupCancelled
StartupJoined
NativeStopStarted
NativeStopVerified
NativeStopVerificationFailed
RollbackFailed
```

Do not add a broad event bus now.

---

# Required implementation sequence

Use this order.

```text
Stage 1 — stop ownership
  P0-001 single-owner cancelled-startup cleanup
  P0-005 fail-loud deterministic test synchronization
  P0-006 remove obsolete production test seams

Stage 2 — repository truthfulness
  P0-002 atomic TunnelRepository state transitions
  P0-003 verified stop result

Stage 3 — configuration consistency
  P0-004 rollback persistence failure

Stage 4 — user-visible and diagnostic truthfulness
  P1-001 preserve policy retry state
  P1-002 surface forwards load error
  P1-003 redact cleanup history
  P1-004 surface corrupt setup draft
  P1-005 remove unused store footgun

Stage 5 — final audit
  P1-006 silent-failure/false-success audit

Stage 6 — final signoff
  P0-007 local gates + remote CI on final SHA
```

Recommended commits:

```text
fix(android): make lifecycle transition own cancelled-startup cleanup

test(android): replace timing-based stop proofs with exact events

refactor(android): remove obsolete startup test event machinery

fix(android): make tunnel status updates atomic

fix(android): verify final stopped state before reporting stop success

fix(android): surface forwards rollback persistence failure

fix(android): preserve policy resume retry until successful start

fix(android): surface forwards load failure instead of empty state

fix(android): redact cleanup failure history

fix(android): report corrupt saved setup draft without overwriting it

refactor(android): remove unused store-level upsert footgun

chore(hardening): complete final false-success audit
```

Do not make one giant commit.

---

# Complete quality gates

## Focused Android tests

Run after each task.

Final focused command:

```bash
cd android
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundServiceStopFailureTest' \
  --tests 'com.phillipchin.webrtctunnel.data.TunnelRepositoryTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.ForwardsViewModelTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.SetupViewModelTest' \
  --tests 'com.phillipchin.webrtctunnel.data.SensitiveDataRedactorTest' \
  --rerun-tasks
```

Run concurrency-sensitive classes three times fresh.

## Full Android

```bash
./gradlew --no-daemon assembleDebug testDebugUnitTest
./gradlew detekt ktlintCheck lintDebug
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

On macOS:

```bash
scripts/test-launchd-install-layout.sh
```

## Final CI

Only after all code changes.

Report:

```text
final implementation SHA:
workflow run:
Android focused tests:
Android full build/tests:
Rust fmt:
Rust clippy debug:
Rust clippy release:
Linux workspace tests:
macOS workspace tests:
Linux signal lifecycle:
macOS signal lifecycle:
Debian package smoke:
launchd plist validation:
launchd install-layout smoke:
```

Use only:

```text
PASS
FAIL
NOT RUN: exact reason
```

---

# Final completion checklist

## Stop ownership

- [ ] Startup cancellation never calls native stop independently.
- [ ] Explicit lifecycle transition cancels and joins startup.
- [ ] Exactly one service path owns cancelled-startup stop.
- [ ] No deadlock exists while joining startup under lifecycle lock.
- [ ] Test proves exactly one native stop call.

## Repository state integrity

- [ ] Every status mutation is atomic.
- [ ] Native refresh merges against current state.
- [ ] Cleanup history cannot be lost.
- [ ] Network state cannot be lost.

## Stop result truthfulness

- [ ] Native stop success requires verified final Stopped state.
- [ ] Status decode/read failure is not clean stop success.
- [ ] Final Error/Running state is not clean stop success.
- [ ] Duplicate/no-op stop cannot mask real stop failure.
- [ ] No clean notification/status follows verification failure.

## Configuration rollback

- [ ] Rollback Result is handled.
- [ ] Rollback failure is visible.
- [ ] Message explains saved-but-not-activated state.

## Test trust

- [ ] No ignored timed-latch result remains.
- [ ] Required correctness tests contain no Thread.sleep polling.
- [ ] No negative 500 ms timing proof remains.
- [ ] Required barriers fail loudly.
- [ ] Production contains no unbounded test event queue.

## UI/diagnostics

- [ ] Failed auto-resume preserves retry state.
- [ ] Initial forwards load failure is visible.
- [ ] Empty list is shown only after successful empty load.
- [ ] `lastCleanupError` is redacted.
- [ ] Corrupt setup draft produces visible non-destructive error.
- [ ] Unused store-level `upsertForward()` removed.

## Final signoff

- [ ] Focused Android tests pass repeatedly.
- [ ] Full Android gates pass.
- [ ] Rust gates pass or pre-existing failure is proven and reported.
- [ ] Service/package gates pass.
- [ ] Remote CI ran on final implementation SHA.
- [ ] No earlier CI run is reused as proof.
- [ ] Every unavailable check is `NOT RUN` with exact reason.
