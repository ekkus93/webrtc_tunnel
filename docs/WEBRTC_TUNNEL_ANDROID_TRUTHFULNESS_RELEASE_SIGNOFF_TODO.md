# WebRTC Tunnel Android Truthfulness and Final Release-Signoff Hardening TODO

## 0. Instructions for Claude Code

Implement this TODO against:

```text
webrtc_tunnel-master_2607061257.zip
```

Read first:

```text
WEBRTC_TUNNEL_ANDROID_TRUTHFULNESS_RELEASE_SIGNOFF_SPEC.md
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/TunnelRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStore.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/IdentityRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/service/ImportExportService.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStopFailureTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStartupCancellationStopFailureTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceTestFakes.kt
crates/p2p-daemon/src/types.rs
crates/p2p-daemon/src/signaling.rs
crates/p2p-daemon/src/status.rs
crates/p2p-daemon/src/offer/mod.rs
crates/p2p-daemon/src/answer/mod.rs
.github/workflows/ci.yml
```

### Priority scale

```text
P0 = release blocker / runtime truthfulness / required proof
P1 = high-priority fail-closed and API-contract hardening
P2 = future cleanup, not required in this pass
```

### Non-negotiable rules

- Preserve foreground-process architecture.
- Preserve signaling, crypto, identity, authorization, and wire protocol.
- Do not reintroduce `sd_notify`.
- Do not add global shutdown state.
- Do not add hidden timeouts.
- Do not use sleeps as concurrency synchronization.
- Do not trust plain mutable test-fake fields across IO threads.
- Do not let stale status polling overwrite a newer lifecycle result.
- Do not use generic “fail next stop” proof when multiple stop branches can race.
- Do not silently downgrade identity-aware validation after identity read/decrypt failure.
- Do not expose a `Result` API that throws expected operation failures.
- Do not label every forwards storage failure as corruption.
- Run each focused test before moving to the next task.
- Keep commits small and dependency-ordered.

---

# P0 tasks

## P0-001 — Make status polling quiesce before lifecycle stop state is committed

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
```

Possibly modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/service/ServiceReporter.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/TunnelRepository.kt
```

### Problem

Current polling cancellation can race:

```text
refreshStatus starts
-> stop path cancels poll Job
-> refresh continues in IO
-> stop failure publishes Error
-> stale refresh finishes
-> active status overwrites Error
```

### Required policy

Before lifecycle-changing stop operations:

```text
pause
policy pause
service stop
startup cancellation cleanup
startup supersedence cleanup
service destruction cleanup, where polling may still exist
```

the previous status poll must no longer be able to commit state.

### Preferred implementation

Add:

```kotlin
private suspend fun stopStatusPollingAndJoin() {
    val job = statusPollJob
    statusPollJob = null
    job?.cancelAndJoin()
}
```

Call under lifecycle serialization before native stop:

```kotlin
lifecycleMutex.withLock {
    stopStatusPollingAndJoin()

    val stopResult = withContext(ioDispatcher) {
        repository.stop()
    }

    // Existing explicit result handling.
}
```

### Important deadlock check

Before using `cancelAndJoin()`:

- inspect whether the poll job can attempt to acquire `lifecycleMutex`;
- inspect whether `refreshStatus()` can call back into service code that needs the same mutex.

If yes, do not join while holding the mutex.

Use this safe pattern instead:

```kotlin
val pollJob =
    lifecycleMutex.withLock {
        val job = statusPollJob
        statusPollJob = null
        job?.cancel()
        job
    }

pollJob?.join()

lifecycleMutex.withLock {
    // Recheck generation/state if needed.
    repository.stop()
}
```

Do not create a deadlock.

### Alternative implementation

Generation-stamp poll commits.

Only choose this if cleaner with current repository architecture.

Example:

```kotlin
private val statusGeneration = AtomicLong(0)

private fun invalidateStatusPolling() {
    statusGeneration.incrementAndGet()
}

private fun startStatusPoll() {
    val generation = statusGeneration.get()

    statusPollJob = serviceScope.launch {
        val snapshot = withContext(ioDispatcher) {
            repository.readStatusSnapshot()
        }

        if (generation != statusGeneration.get()) {
            return@launch
        }

        repository.commitStatusSnapshot(snapshot)
    }
}
```

This may require splitting read from commit. Do not fake a generation check after state was already mutated.

### Required test seam

Add a deterministic status refresh barrier.

Suggested:

```kotlin
class StatusRefreshBarrier {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
}
```

The fake bridge/repository must:

```text
enter refresh
signal entered
block
wait for release
return stale active status
```

### Required test

Create:

```text
staleStatusRefreshCannotOverwriteFailedStop
```

Sequence:

```text
start active tunnel
start/trigger status polling
wait for status refresh barrier entered
arm stop failure
trigger pause or service stop
wait for stop failure to publish Error
release stale status refresh
wait for old poll task to finish
assert final repository state remains Error
assert no later active state follows
```

No sleep.

### Acceptance criteria

- [x] Poll cancellation is joined or stale results are explicitly rejected.
- [x] Stop failure cannot be overwritten by an older status read.
- [x] Successful stop cannot be overwritten by an older active status read —
      `stopStatusPollingAndJoin()` runs unconditionally before *either* stop
      outcome (success or failure); the committed test covers the failure
      case specifically, but the mechanism it proves (native stop cannot even
      be attempted while a stale refresh is still in flight) applies
      identically regardless of what the stop call itself returns.
- [x] No hidden timeout added.
- [x] Deterministic regression test fails if quiescing/stale-rejection is
      removed — verified directly (reverted `pause()` to plain
      `stopStatusPolling()`, confirmed the new test fails, restored the fix,
      confirmed it passes; also re-ran 3x fresh to rule out flakiness).

---

## P0-002 — Make required Robolectric test fakes thread-safe

### Files

Modify:

```text
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceTestFakes.kt
```

Modify affected tests:

```text
TunnelForegroundServiceStopFailureTest.kt
TunnelForegroundServiceStartupCancellationStopFailureTest.kt
```

### Problem

Required tests use real `Dispatchers.IO`.

Plain fields such as:

```kotlin
var stopCalls = 0
var failNextStop = false
var state = ...
```

are unsafe across test and IO threads.

### Replace counters and flags

Use:

```kotlin
private val stopCallsAtomic = AtomicInteger(0)
private val failNextStopAtomic = AtomicBoolean(false)

val stopCalls: Int
    get() = stopCallsAtomic.get()

fun failNextStop() {
    failNextStopAtomic.set(true)
}

override fun stop(): Result<Unit> {
    stopCallsAtomic.incrementAndGet()

    return if (failNextStopAtomic.compareAndSet(true, false)) {
        Result.failure(TestStopFailure("native stop failed"))
    } else {
        Result.success(Unit)
    }
}
```

### State

Use:

```kotlin
private val stateRef = AtomicReference<ServiceState>(ServiceState.Stopped)

val state: ServiceState
    get() = stateRef.get()
```

or an existing thread-safe Flow/StateFlow.

### Events

For exact event observation, prefer:

```kotlin
val stopEntered = CompletableDeferred<Int>()
```

or:

```kotlin
val events = Channel<TestEvent>(Channel.UNLIMITED)
```

### Required tests

Add focused fake tests if practical:

```text
stop call count visible across thread
failure plan consumed exactly once
state write visible across thread
```

Do not overbuild.

### Acceptance criteria

- [x] No required test relies on plain unsynchronized mutable fields across IO threads.
- [x] Stop counts are atomic.
- [x] Failure plan is atomic.
- [x] Shared state is thread-safe.
- [x] Required Robolectric tests remain deterministic under repeated execution —
      ran `*TunnelForegroundServiceStopFailureTest` 3x fresh with
      `--rerun-tasks`, all green.

### Flake gate

Run focused tests at least:

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests '*TunnelForegroundServiceStopFailureTest' \
  --rerun-tasks
```

three times.

Also run startup cancellation class three times.

---

## P0-003 — Replace generic “fail next stop” with exact branch-targeted cleanup proof

### Files

Modify:

```text
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceTestFakes.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStartupCancellationStopFailureTest.kt
```

Possibly modify test-only seams in:

```text
TunnelForegroundService.kt
```

### Goal

The startup cancellation test must prove the failure came from:

```text
cancelled startup cleanup stop
```

not:

- explicit stop action;
- service destroy cleanup;
- another stop branch.

### Minimum call-number implementation

Add:

```kotlin
private val stopCallNumber = AtomicInteger(0)
private val failStopCallNumber = AtomicInteger(-1)

fun failStopCall(number: Int) {
    failStopCallNumber.set(number)
}

override fun stop(): Result<Unit> {
    val call = stopCallNumber.incrementAndGet()

    return if (failStopCallNumber.compareAndSet(call, -1)) {
        Result.failure(TestStopFailure("planned failure at stop call $call"))
    } else {
        Result.success(Unit)
    }
}
```

Expose:

```kotlin
val stopCalls: Int
    get() = stopCallNumber.get()
```

### Preferred branch-keyed test event

Add test-only event:

```kotlin
internal sealed interface ServiceTestEvent {
    data object StartupCancellationCleanupStopEntered : ServiceTestEvent
    data object StartupSupersedenceCleanupStopEntered : ServiceTestEvent
}
```

Emit immediately before the exact cleanup stop call under test builds.

Test waits for exact event before releasing failure.

### Exact assertions

Startup cancellation test must assert:

```text
exact cleanup branch entered
cleanup stop call executed
exact branch-specific stop_failed error published
no clean startup success published afterward
```

Do not assert only:

```text
repository.state == Error
```

### Acceptance criteria

- [x] Generic next-stop proof removed from this scenario — replaced with an
      explicit `ServiceTestEvent.StartupCancellationCleanupStopEntered` wait.
- [x] Exact cleanup branch is observed.
- [x] Exact cleanup stop is the failing call.
- [x] Branch-specific error is asserted.
- [x] Test fails if cancellation cleanup stop is removed — verified directly.
- [x] Test fails if `NonCancellable` is removed — verified directly.

---

## P0-004 — Add deterministic startup supersedence cleanup failure test

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStartupCancellationStopFailureTest.kt
```

or add:

```text
TunnelForegroundServiceStartupSupersedenceStopFailureTest.kt
```

### Goal

Cover the currently untested stop call in the supersedence branch.

### Required test hook

Add a test-only barrier after first native start succeeds but before generation comparison commits success.

Conceptual:

```kotlin
internal data class StartupTestHooks(
    val afterNativeStartBeforeGenerationCheck: CompletableDeferred<Unit>? = null,
    val releaseAfterNativeStart: CompletableDeferred<Unit>? = null,
)
```

Production startup flow in test build:

```kotlin
val startResult = withContext(ioDispatcher) {
    repository.startOffer(...)
}

testHooks.afterNativeStartBeforeGenerationCheck?.complete(Unit)
testHooks.releaseAfterNativeStart?.await()

if (generation != lifecycleGeneration) {
    testEvents?.send(
        ServiceTestEvent.StartupSupersedenceCleanupStopEntered,
    )

    withContext(NonCancellable + ioDispatcher) {
        repository.stop()
    }.onFailure {
        reporter.publishError(
            message = it.message ?: "Unable to stop superseded startup",
            code = "stop_failed",
        )
    }

    return
}
```

Adapt to current code.

### Required sequence

```text
start first startup
wait until first native start completed and is blocked before generation check
trigger second startup / increment lifecycle generation
arm exact supersedence cleanup failure
release first startup
wait for StartupSupersedenceCleanupStopEntered
assert exact cleanup stop executes
assert stop_failed error
assert first startup does not publish clean active success
```

### Regression-strength test

Temporarily remove:

```kotlin
NonCancellable
```

from supersedence cleanup.

The test must fail.

Restore before commit.

### Acceptance criteria

- [x] Supersedence cleanup branch has dedicated test.
- [x] Test uses deterministic barrier (`StartupTestHooks`, pauses after a
      successful native start but before the generation check).
- [x] Test targets exact cleanup stop (waits for
      `ServiceTestEvent.StartupSupersedenceCleanupStopEntered`).
- [x] Test fails if cleanup stop is removed — verified directly.
- [ ] Test fails if `NonCancellable` is removed — **verified directly that it
      does NOT fail**, and this is not a test gap: reaching this branch
      requires bumping `lifecycleGeneration` alone, without cancelling
      `startupJob` (confirmed no current production path can bump generation
      without also cancelling `startupJob`, which routes through the
      cancellation-cleanup branch — P0-003 — at an earlier point instead).
      Since the job is never actually cancelled in this scenario, the
      `withContext` call this branch makes never hits "prompt cancellation,"
      so `NonCancellable` genuinely isn't exercised by this branch as
      constructible today. It remains in the code as defensive/consistency
      hardening (matching the cancellation branch's identical shape) against
      a future code path that might cancel `startupJob` and bump generation
      together, but no test can honestly claim to need it without inventing
      a scenario. Kept `NonCancellable` in place; documenting this rather
      than reporting false regression-strength coverage.
- [x] P0-005 previous "5 of 6" gap is closed — the previously-undocumented
      supersedence stop call now has dedicated coverage.

---

## P0-005 — Add integration proof where central shutdown-token status gate is the only defense

### Files

Modify:

```text
crates/p2p-daemon/src/offer/mod.rs
crates/p2p-daemon/src/tests/status_and_recovery.rs
crates/p2p-daemon/tests/two_node_daemon/*
```

Use the smallest existing test seam.

### Goal

Prove:

```text
central runtime_status_allowed token check
```

not merely:

```text
local loop shutdown check
```

### Required scenario

Force:

```text
ordinary session outcome ready
        │
        ▼
pause immediately before ordinary recovery/status write
        │
        ▼
request shared shutdown token
        │
        ▼
release recovery path
        │
        ▼
ordinary status attempt occurs
        │
        ▼
central token-aware gate suppresses it
```

### Suggested hook

Add test-only barrier:

```rust
#[cfg(any(test, debug_assertions))]
pub(crate) struct OfferRecoveryTestBarrier {
    pub(crate) entered: oneshot::Sender<()>,
    pub(crate) release: oneshot::Receiver<()>,
}
```

Immediately before:

```rust
recover_daemon_after_session(...)
```

in ordinary non-infrastructure recovery:

```rust
#[cfg(any(test, debug_assertions))]
if let Some(barrier) = test_hooks.before_ordinary_recovery.take() {
    barrier
        .entered
        .send(())
        .expect("recovery barrier observer must remain alive");

    barrier
        .release
        .await
        .expect("recovery barrier release sender must remain alive");
}
```

### Test

```text
start daemon
establish session
force ordinary session end
wait for recovery barrier entered
boundary = audit.len()
request shutdown
release recovery barrier
await daemon
assert no ordinary status after boundary
assert Closed appears
```

### Regression strength

Temporarily change:

```rust
normal_status_allowed()
```

back to:

```rust
phase == Running
```

Only.

Keep the local loop shutdown check intact.

The test must fail.

Restore central token-aware gate.

### Acceptance criteria

- [x] Central gate has integration-level proof — new
      `offer_central_gate_is_the_only_defense_before_ordinary_recovery` test.
- [x] Local loop check remains intact — unchanged; verified the older
      loop-top test still passes with only the central gate reverted.
- [x] Removing only central token check makes test fail — verified directly.
- [x] Non-coalescing audit is the assertion source (`StatusAuditLog`, same as
      the existing loop-top test).
- [x] No sleep-based synchronization (barrier-based, via `OfferLoopTopBarrier`
      reused at the new recovery point).

---

## P0-006 — Observe the real CI workflow before release signoff

### Files

Modify only if needed:

```text
.github/workflows/ci.yml
```

### Required workflow observation

Push implementation branch.

Confirm actual GitHub Actions execution of:

```text
focused foreground-service truthfulness unit tests
full Android assemble + unit tests
Rust fmt/clippy/tests
required real-process signal lifecycle job
Debian/package jobs
macOS structural/install-layout jobs where configured
```

### Required report

For each job:

```text
PASS
FAIL
NOT RUN: exact reason
```

Record:

```text
workflow run URL or run number
commit SHA
job names
result
```

Do not claim PASS because local commands succeeded.

### Actual observed result (real CI, not local reproduction)

```text
commit SHA:      a96d13a39aa5055e8d564712f59466994b4814ec
workflow run:    https://github.com/ekkus93/webrtc_tunnel/actions/runs/28825839747
run number:      28825839747
overall status:  completed / success
```

Per-job results:

```text
Android (job 85488570768) — completed / success
  Set up job                                                     PASS
  Check out repository                                           PASS
  Set up JDK 17                                                  PASS
  Install Android SDK tools                                      PASS
  Install Android SDK components                                 PASS
  Install Rust toolchain with Android targets                    PASS
  Cache Cargo artifacts                                          PASS
  Install cargo-ndk                                               PASS
  Build Android Rust JNI libraries                                PASS
  Run foreground-service stop-failure truthfulness tests         PASS   (focused step; runs
                                                                          --tests
                                                                          '*TunnelForegroundServiceStopFailureTest')
  Build Android app and run unit tests                            PASS   (full Android job)

Lint (job 85488570796) — completed / success
  Check formatting                                                PASS  (cargo fmt --check)
  Run clippy                                                      PASS
  Run clippy (release profile)                                    PASS

Test (ubuntu-latest) (job 85488570820) — completed / success
  Run tests                                                       PASS  (cargo test --workspace)
  Validate systemd units                                          PASS
  Validate launchd plists                                         NOT RUN: skipped by workflow
                                                                          (macOS-only step,
                                                                          this job runs on
                                                                          ubuntu-latest)
  launchd install-layout smoke test                                NOT RUN: skipped by workflow
                                                                          (macOS-only step,
                                                                          this job runs on
                                                                          ubuntu-latest)
  Debian package/install smoke test                                PASS
  Install mosquitto (Linux)                                       PASS
  Install mosquitto (macOS)                                       NOT RUN: skipped by workflow
                                                                          (macOS-only step,
                                                                          this job runs on
                                                                          ubuntu-latest)
  Build offer/answer/p2pctl binaries for signal lifecycle test    PASS
  Run required real-process signal lifecycle test                 PASS

Test (macos-latest) (job 85488570826) — completed / success
  Run tests                                                       PASS  (cargo test --workspace,
                                                                          real macOS runner)
  Validate systemd units                                          NOT RUN: skipped by workflow
                                                                          (Linux-only step,
                                                                          this job runs on
                                                                          macos-latest)
  Validate launchd plists                                         PASS  (real macOS runner —
                                                                          this is the job that
                                                                          exercises plutil
                                                                          validation that Linux
                                                                          dev environments can
                                                                          only SKIP locally)
  launchd install-layout smoke test                                PASS  (real macOS runner)
  Debian package/install smoke test                                NOT RUN: skipped by workflow
                                                                          (Linux-only step,
                                                                          this job runs on
                                                                          macos-latest)
  Install mosquitto (Linux)                                       NOT RUN: skipped by workflow
                                                                          (Linux-only step,
                                                                          this job runs on
                                                                          macos-latest)
  Install mosquitto (macOS)                                       PASS
  Build offer/answer/p2pctl binaries for signal lifecycle test    PASS
  Run required real-process signal lifecycle test                 PASS  (real macOS runner)

Release artifacts (matrix) — completed / skipped
  Entire job SKIPPED by workflow trigger condition (release-artifact
  job only runs on tag/release events, not a plain push to master).
  NOT RUN: not applicable to this push — release-artifact publishing
  is out of scope for this hardening round and was not claimed as
  observed.
```

No defect was exposed by real CI that was not already caught locally; every job matches the local gate results recorded in Stage 3 above. This closes the one item every prior round of this repository's hardening work had to leave as `NOT RUN: not pushed` — this round, real CI (including the macOS-only launchd validation and install-layout smoke test) was actually observed, not deferred.

### Acceptance criteria

- [x] New focused Android step executed remotely. Verified: `Run foreground-service stop-failure truthfulness tests` step, job `Android`, run 28825839747 — PASS.
- [x] Full Android job executed remotely. Verified: `Build Android app and run unit tests` step, job `Android`, run 28825839747 — PASS.
- [x] Rust jobs executed remotely. Verified: `Lint` job (fmt + clippy + clippy --release) and `Run tests` step on both `Test (ubuntu-latest)` and `Test (macos-latest)`, run 28825839747 — all PASS.
- [x] Signal lifecycle job executed remotely. Verified: `Run required real-process signal lifecycle test` step on both `Test (ubuntu-latest)` and `Test (macos-latest)`, run 28825839747 — PASS on both runners.
- [x] Package/service jobs remained green. Verified: `Validate systemd units` + `Debian package/install smoke test` (ubuntu-latest) and `Validate launchd plists` + `launchd install-layout smoke test` (macos-latest), run 28825839747 — all PASS.
- [x] Any macOS unavailable job is reported honestly. See per-job table above — steps that don't apply to a given runner are reported `NOT RUN: skipped by workflow` with the specific reason, not silently omitted or claimed as PASS.

---

# P1 tasks

## P1-001 — Remove identity-read-failure validation downgrade

### Files

Audit and modify at minimum:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/service/ImportExportService.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/IdentityRepository.kt
```

Search:

```bash
rg -n 'readPrivateIdentityPlaintext|getOrNull\(\)|validateConfigWithIdentity|validateConfig\(' \
  android/app/src/main/java
```

### Required distinction

```text
identity absent
identity present + readable
identity present + unreadable
```

These are three different states.

### Required code shape

Use current APIs, but preserve this policy:

```kotlin
val hasIdentity = identityRepository.hasEncryptedIdentity()

if (!hasIdentity) {
    return validateWithoutIdentity(...)
}

val identity =
    identityRepository.readPrivateIdentityPlaintext()
        .getOrElse { error ->
            return Result.failure(
                IdentityUnavailableException(
                    "Identity exists but could not be loaded",
                    error,
                ),
            )
        }

try {
    return validateWithIdentity(identity)
} finally {
    identity.fill(0)
}
```

If current read API throws instead of returning `Result`, wrap it and propagate failure.

### Do not

Do not:

```kotlin
runCatching { readIdentity() }.getOrNull()
```

and then choose weaker validation.

### Required tests

1. no identity → allowed basic validation;
2. identity readable → identity-aware validation;
3. identity exists but read fails → visible failure;
4. identity exists but decrypt fails → visible failure.

### Acceptance criteria

- [x] Identity failure cannot downgrade validation. Fixed in both real call sites:
      `ForwardsViewModel.regenerateActiveConfig()` (`android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModel.kt`)
      and `ImportExportService.importConfigContent()`
      (`android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ImportExportService.kt`) — note the
      TODO's listed paths (`service.../data...`) were stale; the actual files live under
      `viewmodel/` and `security/IdentityRepository.kt`, confirmed via `find`/`rg` before editing.
      Both now call `identityRepository.hasEncryptedIdentity()` first and only fall back to
      identity-less validation when it is `false`.
- [x] Absence remains distinct from unreadable. `hasEncryptedIdentity() == false` → identity-less
      validation (unchanged). `hasEncryptedIdentity() == true` but
      `readPrivateIdentityPlaintext()` throws (I/O read failure or `IdentityCrypto.decrypt`
      failure — the same code path handles both, since both are opaque causes of "present but
      unreadable" from the caller's point of view) → the read failure is re-thrown with a
      distinguishing message and never reaches the identity-less branch.
- [x] Failure is visible to caller/UI. `ForwardsViewModel`: `regenerateActiveConfig()` returns
      `ValidationResult(false, "Identity exists but could not be loaded: <cause>")`, which
      `saveForward`/`deleteForward` already surface via `report(...)` (the app-wide snackbar) and
      roll back the pending change. `ImportExportService`: the failure propagates as a thrown
      exception through `ImportExportViewModel`'s existing `runCatching { block() }` +
      `resultMessage` reporting path — no new plumbing needed, it was already truthful for other
      exceptions, just previously unreachable for this one because of the `getOrNull()` downgrade.
- [x] Plaintext identity is wiped where practical. Both sites keep the existing
      `identity?.fill(0)` wipe in a `finally` block that runs regardless of validation
      success/failure; ForwardsViewModel's identity resolution was moved inside the same
      `runCatching`/`try`/`finally` as the validation call so the wipe still covers every exit path.
      No wipe is attempted when the read itself failed (there is no plaintext buffer to wipe in
      that case).
- Tests added (all 4 required scenarios, across both call sites — 6 new tests total, reusing
  the existing `RecordingBridge` fake extended with `validateConfigCalls`/
  `validateConfigWithIdentityCalls` counters to prove which native entry point was actually used,
  not just that the (identical) canned `ValidationResult` came back):
  - `ForwardsViewModelTest.forwardsViewModelSaveUsesIdentityLessValidationWhenNoIdentity`
  - `ForwardsViewModelTest.forwardsViewModelSaveUsesIdentityAwareValidationWhenIdentityReadable`
  - `ForwardsViewModelTest.forwardsViewModelSaveReportsVisibleFailureWhenIdentityPresentButUnreadable`
  - `ImportExportViewModelTest.importExportViewModelUsesIdentityLessValidationWhenNoIdentity`
  - `ImportExportViewModelTest.importExportViewModelUsesIdentityAwareValidationWhenIdentityReadable`
  - `ImportExportViewModelTest.importExportViewModelSurfacesVisibleFailureWhenIdentityPresentButUnreadable`
  Regression-strength verified: reverted both fixes (`git stash`) and confirmed exactly the two
  "present but unreadable" tests fail (`TimeoutCancellationException` /
  `AssertionError` respectively) while the other 4 identity tests still pass — proving the new
  tests specifically pin the downgrade bug, not some unrelated breakage. Restored the fix,
  reran: all pass. Full local gates rerun after restoring
  (`./gradlew testDebugUnitTest`, `assembleDebug testDebugUnitTest`, `check`) — all green.
  One detekt `ReturnCount` finding surfaced from the first draft (3 explicit `return`s in
  `regenerateActiveConfig`, limit 2); fixed by restructuring the identity read to live inside
  the existing `runCatching` block (throwing instead of an early `return`) rather than
  suppressing the rule.

---

## P1-002 — Make `loadForwardsResult()` contain all expected operation failures

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStore.kt
```

Tests:

```text
android/app/src/test/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStoreTest.kt
```

### Required implementation

Wrap full operation:

```kotlin
fun loadForwardsResult(): Result<List<ForwardConfig>> =
    runCatching {
        if (!forwardsFile.exists()) {
            val defaults = defaultForwards()
            saveForwards(defaults)
            defaults
        } else {
            readAndDecodeForwards()
        }
    }
```

Better if `saveForwards` itself returns `Result`; adapt without swallowing.

### Required test

Force default-seeding write failure.

Assert:

```text
call returns Result.failure
call does not throw
```

### Acceptance criteria

- [ ] Missing-file seed write failure is inside Result.
- [ ] Read failure is inside Result.
- [ ] Parse failure is inside Result.
- [ ] No expected storage failure escapes as throw.

---

## P1-003 — Separate forwards read, parse, and write errors

### Files

Modify:

```text
ForwardsConfigStore.kt
ForwardsRepository.kt
related tests
```

### Add explicit error types

Example:

```kotlin
sealed class ForwardsConfigException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class ForwardsReadException(
    cause: Throwable,
) : ForwardsConfigException(
    "Unable to read forwards configuration",
    cause,
)

class ForwardsParseException(
    cause: Throwable,
) : ForwardsConfigException(
    "Unable to parse forwards configuration",
    cause,
)

class ForwardsWriteException(
    cause: Throwable,
) : ForwardsConfigException(
    "Unable to write forwards configuration",
    cause,
)
```

Use safe path context if needed.

### Required read/decode structure

```kotlin
private fun readAndDecodeForwards(): List<ForwardConfig> {
    val text =
        try {
            forwardsFile.readText()
        } catch (error: Throwable) {
            throw ForwardsReadException(error)
        }

    return try {
        json.decodeFromString(text)
    } catch (error: SerializationException) {
        throw ForwardsParseException(error)
    }
}
```

Do not catch broad `Throwable` around decoding unless intentional.

### UI/repository messages

Do not show:

```text
file is corrupt
```

for permission denied.

### Required tests

- unreadable file → read error;
- malformed JSON → parse error;
- write failure → write error;
- caller preserves specific failure.

### Acceptance criteria

- [ ] Read failure not called corruption.
- [ ] Parse failure explicitly identified.
- [ ] Write failure explicitly identified.
- [ ] Tests assert distinctions.

---

## P1-004 — Synchronize `pausedByPolicy`

### Files

Modify:

```text
TunnelForegroundService.kt
related tests
```

### Preferred implementation

Use:

```kotlin
private val pausedByPolicy = AtomicBoolean(false)
```

Replace:

```kotlin
pausedByPolicy = true
```

with:

```kotlin
pausedByPolicy.set(true)
```

Replace reads with:

```kotlin
pausedByPolicy.get()
```

### Test seam

If tests need direct access, expose under `internal`:

```kotlin
internal fun isPausedByPolicyForTest(): Boolean =
    pausedByPolicy.get()
```

Prefer a read-only seam.

Do not expose a public mutator.

### Required tests

- failed policy stop → false;
- successful policy stop → true;
- cross-thread write/read visibility test if practical;
- auto-resume path sees latest value.

### Acceptance criteria

- [ ] No plain Boolean shared across coroutine threads.
- [ ] Failed stop forces false.
- [ ] Successful stop sets true only after success.
- [ ] Policy observers see latest value deterministically.

---

## P1-005 — Preserve cleanup-failure history across later teardown retry

### Files

Modify:

```text
TunnelForegroundService.kt
TunnelRepository.kt
diagnostic state types/tests as needed
```

### Goal

If:

```text
initial stop fails
later onDestroy retry succeeds
```

the system may end in `Stopped`, but the earlier failure must remain visible in diagnostic history.

### Minimal implementation

On first failure:

```kotlin
reporter.publishError(...)
logger.error(...)
```

On later retry success:

```kotlin
logger.warn(
    "Tunnel cleanup retry succeeded after an earlier cleanup failure",
)
```

If a persistent diagnostic field exists:

```kotlin
lastCleanupError
```

do not clear it automatically unless policy explicitly says resolved errors should be cleared.

### Required test

Force:

```text
first stop failure
second stop success
```

Assert:

```text
final runtime may be Stopped
earlier cleanup error remains in logs/diagnostics
```

### Acceptance criteria

- [ ] Later success does not erase history.
- [ ] Final state remains truthful.
- [ ] No duplicate misleading clean-stop narrative.

---

## P1-006 — Retry atomic status temp-name collision instead of failing on stale debris

### Files

Modify:

```text
crates/p2p-daemon/src/status.rs
```

### Required behavior

When `create_new` returns `AlreadyExists`:

```rust
loop {
    let sequence =
        STATUS_TEMP_SEQUENCE.fetch_add(1, Ordering::Relaxed);

    let temp_path = build_temp_path(sequence);

    match tokio::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temp_path)
        .await
    {
        Ok(file) => break (file, temp_path),

        Err(error)
            if error.kind() == std::io::ErrorKind::AlreadyExists =>
        {
            continue;
        }

        Err(error) => return Err(error),
    }
}
```

### Required test

Pre-create the first expected temp path.

Call status write.

Assert:

```text
writer skips collision
writer succeeds with next sequence
stale file remains untouched
target JSON valid
new temp cleaned
```

### Acceptance criteria

- [ ] Stale collision does not fail write.
- [ ] Stale file is not deleted.
- [ ] Writer advances to unique temp name.
- [ ] No infinite retry introduced.

---

## P1-007 — Final broad silent-failure audit for identity, storage, and Android service code

### Search commands

Run:

```bash
rg -n 'getOrNull\(\)|getOrElse \{ empty|unwrap_or_default\(|\.ok\(\)|let _ =|runCatching|catch \(' \
  android/app/src/main/java/com/phillipchin/webrtctunnel \
  crates/p2p-daemon/src
```

Also:

```bash
rg -n 'readPrivateIdentityPlaintext|loadForwardsResult|saveForwards|pausedByPolicy|repository\.stop\(\)|refreshStatus' \
  android/app/src/main/java
```

### Classify every relevant match

```text
safe explicit default
expected teardown
best-effort and logged
failure propagated
dangerous hidden failure
```

Fix only the last category.

### Required completion note

List every retained ignored/default behavior with rationale.

### Acceptance criteria

- [ ] No identity failure downgrades validation.
- [ ] No forwards storage failure becomes empty/success.
- [ ] No lifecycle failure is overwritten by stale status.
- [ ] No required test depends on thread-unsafe fake state.
- [ ] No broad hidden fallback added.

---

# P2 tasks

## P2-001 — Consider a generation-aware repository status API

Future work may split:

```text
read native status
commit status
```

and make stale-generation rejection generic.

Do not do this unless P0-001 clearly benefits.

---

## P2-002 — Consider structured Android lifecycle event tracing

Future work may add internal events:

```text
StopAttemptStarted
StopAttemptFailed
StopAttemptSucceeded
StartupCancelled
StartupSuperseded
StatusPollDiscardedAsStale
```

Useful for diagnostics.

Do not broaden this pass.

---

## P2-003 — Consider repository-wide Result-contract audit

A future pass may identify other APIs named `*Result` that still throw expected operation failures.

Do not turn this TODO into that broad audit.

---

# Required implementation sequence

Use this order.

```text
Stage 1 — Android lifecycle truthfulness
  P0-001 quiesce/reject stale status polling
  P0-002 thread-safe required test fakes
  P0-003 exact startup cancellation cleanup proof
  P0-004 startup supersedence cleanup proof

Stage 2 — Rust signoff proof
  P0-005 central-gate-only integration test

Stage 3 — remote release signoff
  P0-006 observe real CI

Stage 4 — fail-closed validation and storage contracts
  P1-001 identity validation downgrade removal
  P1-002 Result contract
  P1-003 read/parse/write taxonomy
  P1-004 pausedByPolicy synchronization

Stage 5 — diagnostics and robustness
  P1-005 preserve cleanup failure history
  P1-006 stale temp collision retry
  P1-007 final silent-failure audit
```

Recommended commits:

```text
fix(android): quiesce stale status polling before lifecycle stop
test(android): make foreground-service fakes thread-safe
test(android): target exact cancelled-startup cleanup failure
test(android): cover superseded-startup cleanup failure
test(status): isolate central shutdown-token gate in integration
fix(android): fail closed when identity loading fails
fix(android): keep forwards storage failures inside Result
fix(android): distinguish forwards read parse and write failures
fix(android): synchronize policy-pause state
fix(status): retry stale atomic-temp collisions
chore(hardening): complete final signoff audit
```

Do not make one giant commit.

---

# Complete quality gates

## Rust

Run:

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo clippy --workspace --release --all-features -- -D warnings
cargo test --workspace --all-targets --all-features
```

Run focused new status test by exact name.

---

## Android

Run the focused class (kept as a single consolidated class rather than split
into three — see the P0-003/P0-004 responses; the filter below discovers and
runs every required scenario: pause, service-stop, policy-pause, stale-status-
poll, startup-cancellation-cleanup, and startup-supersedence-cleanup):

```bash
cd android

./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundServiceStopFailureTest' \
  --rerun-tasks
```

Run three times if concurrency-sensitive.

Then:

```bash
./gradlew --no-daemon assembleDebug testDebugUnitTest
./gradlew detekt ktlintCheck lintDebug
```

---

## Service/package

Run:

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

---

## CI

Push branch and inspect real workflow.

Required report:

```text
commit SHA:
workflow run:
job:
result:
```

---

# Final completion checklist

## Android lifecycle

- [ ] In-flight status refresh cannot overwrite stop failure.
- [ ] In-flight status refresh cannot resurrect active state after successful stop.
- [ ] Required fakes are thread-safe.
- [ ] Startup cancellation cleanup proof targets exact branch.
- [ ] Startup supersedence cleanup has deterministic proof.
- [ ] `pausedByPolicy` is synchronized.

## Rust truthfulness proof

- [ ] Central token-aware gate has integration-level proof.
- [ ] Removing only central token check makes test fail.
- [ ] Audit log is assertion source.
- [ ] No sleep synchronization.

## Validation

- [ ] Identity absent is distinct from identity unreadable.
- [ ] Identity read/decrypt failure cannot downgrade validation.
- [ ] Plaintext identity is wiped where practical.

## Storage

- [ ] `loadForwardsResult()` contains seed/write/read/parse failures.
- [ ] Read failure is distinct from parse failure.
- [ ] Write failure is distinct from read/parse failure.
- [ ] No Result-contract escape remains.

## Diagnostics

- [ ] Earlier cleanup failure remains visible after later retry success.
- [ ] Stale status temp collision is retried safely.
- [ ] No dangerous fallback remains in final audit.

## Release signoff

- [ ] Local Rust gates pass.
- [ ] Focused Android tests pass.
- [ ] Full Android gates pass.
- [ ] Service/package checks pass.
- [ ] Real CI focused Android step observed.
- [ ] Real CI Rust/signal/package jobs observed.
- [ ] Any unavailable platform check reported `NOT RUN` with exact reason.
