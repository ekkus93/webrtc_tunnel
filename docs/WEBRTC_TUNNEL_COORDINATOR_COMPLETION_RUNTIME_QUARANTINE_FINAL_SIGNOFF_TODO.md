# WebRTC Tunnel Coordinator Completion, Runtime Quarantine, and Final State-Integrity Signoff TODO

## 0. Instructions for Claude Code / Qwen3.6 27B

Implement this TODO against:

```text
webrtc_tunnel-master_2607080811.zip
```

This TODO is intentionally explicit for a local model.

### Read first

```text
WEBRTC_TUNNEL_COORDINATOR_COMPLETION_RUNTIME_QUARANTINE_FINAL_SIGNOFF_SPEC.md

android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/TunnelRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SettingsViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupSaveController.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/LogsViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/ui/LogsScreen.kt

android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStopFailureTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceTestFakes.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/data/TunnelRepositoryTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/data/ForwardsRepositoryTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModelTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/SettingsViewModelTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/LogsViewModelTest.kt

.github/workflows/ci.yml
```

Correct stale paths by locating the real file.

---

## 0.1 Do not create parallel abstractions

Reuse current:

```text
StartupCompletion
classifyStartupResult()
cleanupUnverifiedStart()
RetryPolicyResume
startupJob
lifecycleGeneration
nativeStopVerified
pendingPolicyResume
reporter
refreshStatusResult()
ForwardsRepository mutex
SensitiveDataRedactor.redactText()
```

Modify them in place.

---

## 0.2 Required work discipline

For every task:

```text
1. inspect current code
2. write/update focused regression
3. implement smallest correct fix
4. run focused test
5. run formatting/lint
6. commit scoped change
```

Do not mark boxes complete without evidence.

---

# P0 tasks

## P0-001 — Return startup completion to the lifecycle coordinator

### Goal

Remove cleanup/retry/final lifecycle decisions from the startup coroutine.

### Files

```text
TunnelForegroundService.kt
TunnelForegroundServiceStopFailureTest.kt
TunnelForegroundServiceTestFakes.kt
```

### Step 1 — add internal completion command

```kotlin
data class StartupCompleted(
    val generation: Long,
    val completion: StartupCompletion,
) : LifecycleCommand
```

### Step 2 — add unexpected failure variant

```kotlin
data class UnexpectedFailure(
    val error: Throwable,
) : StartupCompletion
```

### Step 3 — startup worker does work only

Target:

```kotlin
private suspend fun runOfferStartWork(
    generation: Long,
): StartupCompletion {
    return try {
        val result =
            withContext(ioDispatcher) {
                repository.start(
                    mode = TunnelMode.Offer,
                    configPath =
                        configRepository.configPath,
                    identityBytes = identity,
                )
            }

        classifyStartupResult(result)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        StartupCompletion.UnexpectedFailure(error)
    }
}
```

Then submit:

```kotlin
submitLifecycleCommand(
    LifecycleCommand.StartupCompleted(
        generation = generation,
        completion = completion,
    ),
)
```

### Step 4 — coordinator handles result

```kotlin
private suspend fun handleStartupCompleted(
    command: LifecycleCommand.StartupCompleted,
) {
    if (
        lifecycleGeneration.get() !=
            command.generation
    ) {
        return
    }

    startupJob = null

    when (val completion = command.completion) {
        StartupCompletion.VerifiedSuccess ->
            handleVerifiedStartupSuccess(
                command.generation,
            )

        is StartupCompletion.NativeStartFailure ->
            handleNativeStartFailure(
                command.generation,
                completion.error,
            )

        is StartupCompletion.VerificationFailure ->
            handleVerificationFailure(
                command.generation,
                completion.error,
            )

        is StartupCompletion.UnexpectedFailure ->
            handleUnexpectedStartupFailure(
                command.generation,
                completion.error,
            )
    }
}
```

### Do not

Startup worker must not:

```text
call repository.stop()
queue RetryPolicyResume
call offer.resume()
publish final success
```

### Required tests

1. startup result returns through lifecycle queue;
2. stale generation completion has no side effects;
3. unexpected exception becomes visible completion;
4. no retry/stop occurs from worker.

### Acceptance criteria

- [ ] Startup worker only performs work/classification.
- [ ] Coordinator owns completion decisions.
- [ ] Unexpected startup exception becomes visible.
- [ ] Stale completion is ignored.

---

## P0-002 — Bind policy retry to lifecycle generation

### Replace retry command

```kotlin
data class RetryPolicyResume(
    val expectedGeneration: Long,
) : LifecycleCommand
```

### Pending generation

```kotlin
private val pendingPolicyResumeGeneration =
    AtomicReference<Long?>(null)
```

### Consume after completion

```kotlin
private fun submitPendingPolicyRetryIfValid(
    completedGeneration: Long,
) {
    val pending =
        pendingPolicyResumeGeneration
            .getAndSet(null)

    if (pending != completedGeneration) {
        return
    }

    submitLifecycleCommand(
        LifecycleCommand.RetryPolicyResume(
            expectedGeneration =
                completedGeneration,
        ),
    )
}
```

### Handler

```kotlin
private suspend fun handleRetryPolicyResume(
    expectedGeneration: Long,
) {
    if (
        lifecycleGeneration.get() !=
            expectedGeneration
    ) {
        return
    }

    if (nativeRuntimeUncertain.get()) {
        return
    }

    if (!pausedByPolicy.get()) {
        return
    }

    beginPolicyResumeAttempt()
}
```

### Clear pending retry on

```text
Stop
Pause
PolicyBlocked
StartOffer
AllowMeteredSession
Destroy
```

### Tests

1. pending retry then STOP → no restart;
2. pending retry then PolicyBlocked → no restart;
3. valid retry runs exactly once.

### Acceptance criteria

- [ ] Retry carries generation.
- [ ] Later lifecycle intent invalidates stale retry.
- [ ] Stale retry cannot restart after STOP.
- [ ] Valid retry runs once.

---

## P0-003 — Retry only after old startup fully completes

### Required order

```text
startup work finishes
StartupCompleted handled
startupJob = null
cleanup completes if required
pending retry checked
retry submitted
```

Do not submit retry from inside `startupJob`.

### Exact regression test

```text
PolicyAllowed #1 starts resume
attempt #1 fails

PolicyAllowed #2 arrives while #1 still completing

NO third event
```

Assert attempt #2 happens once and succeeds.

Delete repeated network-callback refiring loops.

### Acceptance criteria

- [ ] Old startup ownership clears before retry.
- [ ] One later event is sufficient.
- [ ] No repeated-event test loop.
- [ ] Retry runs exactly once.

---

## P0-004 — Quarantine uncertain native runtime

### Add

```kotlin
private val nativeRuntimeUncertain =
    AtomicBoolean(false)
```

### Set true

When verification-failure cleanup stop fails.

Also evaluate verified-stop failure paths.

### On quarantine

```kotlin
nativeRuntimeUncertain.set(true)
pendingPolicyResumeGeneration.set(null)
```

Block:

```text
PolicyAllowed
RetryPolicyResume
automatic resume
automatic start
```

Allow explicit STOP.

Clear only after verified successful stop.

### Tests

1. cleanup failure sets quarantine;
2. PolicyAllowed does not restart;
3. retry command does not restart;
4. explicit STOP allowed;
5. verified STOP clears quarantine.

### Acceptance criteria

- [ ] Cleanup failure blocks auto-restart.
- [ ] Explicit STOP remains available.
- [ ] Verified STOP clears quarantine.
- [ ] No clean stopped/paused claim on failure.

---

## P0-005 — Keep foreground service alive after failed verified STOP

### Required

On stop success:

```text
mark verified
clear quarantine
stop foreground
stop self
```

On stop failure:

```text
mark unverified
set quarantine
publish Error
remain foreground
remain alive
```

Do not call `stopSelf()` on failure.

### Tests

1. stop failure → service remains alive;
2. stop failure → foreground remains;
3. second STOP succeeds → then service exits;
4. status-verification failure behaves the same.

### Acceptance criteria

- [ ] Failed stop does not abandon controller.
- [ ] User can retry.
- [ ] Service exits only after verified absence.

---

## P0-006 — Add controlled failure boundaries

### Command processor

Store:

```kotlin
private var commandProcessorJob: Job? = null
```

Catch unexpected errors and publish:

```text
lifecycle_command_failed
```

### Network monitor

Catch unexpected errors and publish:

```text
network_policy_monitor_failed
```

Do not silently stop monitoring.

### Startup worker

Convert unexpected throwable to:

```text
StartupCompletion.UnexpectedFailure
```

### Status poll

Replace discarded outer `runCatching`.

Publish:

```text
status_poll_failed
```

### Tests

1. command handler throws → visible error;
2. network preference read throws → visible error;
3. startup prep throws → visible completion;
4. status refresh throws unexpectedly → visible error.

### Acceptance criteria

- [ ] No critical child coroutine dies silently.
- [ ] No discarded Result in these paths.
- [ ] Cancellation rethrown.

---

## P0-007 — Preserve metered allowance through successful run

Remove clearing from verified startup success.

Clear on:

```text
startup failure
pause success
policy pause success
verified stop
destroy
```

### Tests

1. AllowMeteredSession + successful start → allowance remains true;
2. next network callback does not pause run;
3. pause clears;
4. failed start clears.

### Acceptance criteria

- [ ] Successful authorized run retains override.
- [ ] Override ends with run/failure/pause.
- [ ] UI remains truthful.

---

## P0-008 — Wipe identity buffers on every failure path

### Startup preparation

Use explicit ownership transfer:

```kotlin
val identity =
    identityRepository
        .readPrivateIdentityPlaintext()

var transferred = false

try {
    // prepare + validate
    transferred = true
    return identity
} finally {
    if (!transferred) {
        identity.fill(0)
    }
}
```

### Stored identity validation

Remove any `runCatching(...).getOrNull()` path that can lose a decrypted buffer.

### Tests

Force:

```text
config validation failure
config rewrite failure
address resolution failure
stored identity validation failure
```

Assert sentinel bytes are zeroed.

### Acceptance criteria

- [ ] Every failed preparation path wipes plaintext.
- [ ] Successful transfer has one owner.
- [ ] No plaintext lost through getOrNull.

---

## P0-009 — Make start-cleanup failure sticky history

Include:

```text
start_verification_cleanup_failed
```

in sticky cleanup classification.

### Test

Set cleanup failure, then perform successful status refresh.

Assert `lastCleanupError` remains.

### Acceptance criteria

- [ ] Cleanup failure survives later refresh.
- [ ] Current lifecycle state may recover separately.

---

# P1 tasks

## P1-001 — Use atomic forwards mutation receipts

Add:

```kotlin
data class ForwardsMutationReceipt(
    val before: List<ForwardConfig>,
    val after: List<ForwardConfig>,
    val committedRevision: Long,
)
```

Under one mutex:

```text
capture before
apply mutation
persist
increment revision
return receipt
```

Do not call `snapshot()` then mutate.

### Race test

Intervening mutation must not be erased by rollback.

### Acceptance criteria

- [ ] Receipt belongs to exact mutation.
- [ ] Intervening mutation preserved.
- [ ] Snapshot+mutation split removed.

---

## P1-002 — Advance revision on successful refresh

Successful canonical refresh increments revision.

### Test

Old receipt cannot rollback across refresh.

### Acceptance criteria

- [ ] Refresh invalidates old receipt.

---

## P1-003 — Block mutation whenever loadError is active

Central guard:

```kotlin
if (_loadError.value != null) {
    return Result.failure(
        ForwardsMutationBlockedException(...)
    )
}
```

### Tests

1. initial load error blocks;
2. later refresh error blocks;
3. successful refresh clears block.

### Acceptance criteria

- [ ] Current loadError always blocks mutation.
- [ ] Historical valid baseline cannot bypass.

---

## P1-004 — Remove raw ForwardsRepository.save() bypass

Search callers.

If unused, delete it.

Otherwise make it obey load-error + revision invariants.

### Acceptance criteria

- [ ] No raw mutation bypass remains.

---

## P1-005 — Reset forwards through repository state

Add:

```kotlin
suspend fun resetForwards(): Result<Unit>
```

Under repository mutex:

```text
persist empty list
publish empty list
clear loadError
increment revision
```

Settings reset must call repository, not store directly.

### Test

Old forwards do not reappear after reset + new mutation.

### Acceptance criteria

- [ ] Disk and memory reset together.
- [ ] Old forwards cannot resurrect.

---

## P1-006 — Report partial reset explicitly

Track each reset stage.

On partial failure, report which succeeded and failed.

Do not only say generic `"Reset failed"`.

### Acceptance criteria

- [ ] Partial state is visible.

---

## P1-007 — Serialize config.toml writes

Add one repository write mutex.

Use a unique temp file.

All config writers use same atomic writer.

### Concurrency test

Two overlapping writers produce one complete valid final config.

### Acceptance criteria

- [ ] One serialized write boundary.
- [ ] No fixed shared temp race.
- [ ] Final config always complete.

---

## P1-008 — Reject unknown native mode explicitly

Unknown mode:

```text
retain previous mode structurally
ServiceState.Error
native_status_schema_error
```

### Test

`future_mode_v99` must not verify as successful start.

### Acceptance criteria

- [ ] Unknown mode cannot become Offer.
- [ ] Start verification fails visibly.

---

## P1-009 — Diagnose unknown listen state

Unknown raw value:

```text
ListenState.Error
native_status_schema_error
explicit redacted value
```

### Acceptance criteria

- [ ] Root cause visible.

---

## P1-010 — Clear active peer on all terminal states

Clear on:

```text
Stopped
Error
PausedMeteredBlocked
NoNetwork
```

### Acceptance criteria

- [ ] No stale active peer in terminal state.

---

## P1-011 — Keep nativeStopVerified truthful

Set true after verified successful:

```text
Pause
PolicyBlocked
Stop
unverified-start cleanup
destroy fallback
```

Set false when native start begins.

### Tests

Pause → destroy does not second-stop.

Policy pause → destroy does not second-stop.

---

## P1-012 — Block duplicate starts in transitional states

Use:

```text
isTunnelActiveOrStarting()
```

Tests:

```text
Starting
Connecting
Reconnecting
```

No duplicate start.

---

## P1-013 — Make initially policy-blocked startup auto-resumable

When startup is blocked before native start:

```kotlin
pausedByPolicy.set(true)
repository.setPolicyBlocked(...)
```

### Test

Blocked initial start → one allowed event → one resume.

---

## P1-014 — Serialize overlapping log refreshes

Use cancellation or generation.

Older refresh cannot overwrite newer result.

---

## P1-015 — Wire logsError into actual UI

Collect in `LogsScreen`.

Show visible error banner/card.

Remove dead flow if not used.

---

## P1-016 — Surface preference-write failures

Wrap settings/network-policy preference writes.

On failure:

```text
visible error
no success snackbar
```

### Acceptance criteria

- [ ] Failed write visible.
- [ ] No false success.

---

# P2 tasks

## P2-001 — Extract TunnelLifecycleCoordinator

**Status:** Implemented (refactored)

Coordinator is a pure command bus — it does NOT own lifecycle state.
- Ordered command processing (FIFO via bounded channel)
- Submits commands to `CoordinatorOperations` for processing
- All lifecycle state lives in `TunnelForegroundService`
- Platform-specific operations delegated through `CoordinatorOperations` interface

The coordinator was refactored to be a command router that submits lifecycle operations
to the service's coordinator operations rather than managing state independently.

## P2-002 — Typed StartOutcome through JNI

**Status:** Implemented

Created `StartOutcome.kt` with typed result for JNI start operations.
Moves classification closer to the JNI boundary, replacing post-hoc `StartupCompletion`
classification in the coordinator.

## P2-003 — Transactional multi-file settings reset

**Status:** Implemented

Created `TransactionalResetCoordinator` with rollback capability.
Provides atomic multi-file reset with:
- Mutex-serialized execution
- Stage-by-stage outcome tracking
- Automatic rollback on partial failure
- Explicit reporting of which stages succeeded/failed

---

# Required implementation order

```text
Stage 1
  P0-001 coordinator completion
  P0-002 generation-bound retry
  P0-003 one-event retry

Stage 2
  P0-004 quarantine
  P0-005 failed STOP retention

Stage 3
  P0-006 failure boundaries

Stage 4
  P0-007 metered allowance
  P0-009 sticky cleanup history
  P1-011 nativeStopVerified
  P1-012 duplicate starts
  P1-013 initial policy block resume

Stage 5
  P0-008 identity wiping

Stage 6
  P1-001 mutation receipts
  P1-002 refresh revision
  P1-003 loadError block
  P1-004 raw save removal
  P1-005 repository reset

Stage 7
  P1-006 partial reset reporting
  P1-007 config write serialization
  P1-016 preference write failures

Stage 8
  P1-008 unknown mode
  P1-009 listen diagnosis
  P1-010 terminal peer

Stage 9
  P1-014 log refresh ordering
  P1-015 logsError UI

Stage 10
  final audit and signoff
```

Recommended commits:

```text
fix(android): return startup completion to lifecycle coordinator
fix(android): bind policy retry to lifecycle generation
fix(android): retry only after startup completion
fix(android): quarantine uncertain native runtime
fix(android): keep service alive after unverified stop
fix(android): add lifecycle coroutine failure boundaries
fix(android): preserve metered allowance for active run
fix(android): wipe private identity on failure paths
fix(android): preserve start-cleanup failure history
fix(android): return atomic forwards mutation receipts
fix(android): invalidate rollback receipts on refresh
fix(android): block mutation during forwards load failure
refactor(android): remove raw forwards save bypass
fix(android): reset forwards through repository
fix(android): report partial configuration reset
fix(android): serialize config writes
fix(android): reject unknown native mode
fix(android): diagnose unknown listen state
fix(android): clear stale peer on terminal state
fix(android): keep verified-stop state consistent
fix(android): block duplicate transitional starts
fix(android): auto-resume initially policy-blocked start
fix(android): serialize log refreshes
fix(android): surface logs error in UI
fix(android): report preference write failures
```

Do not make one giant commit.

---

# Focused test commands

## Lifecycle

```bash
cd android

./gradlew --no-daemon testDebugUnitTest   --tests 'com.phillipchin.webrtctunnel.TunnelForegroundServiceStopFailureTest'   --tests 'com.phillipchin.webrtctunnel.data.TunnelRepositoryTest'   --rerun-tasks
```

Run three fresh times after P0 lifecycle changes.

## Forwards/reset

```bash
./gradlew --no-daemon testDebugUnitTest   --tests 'com.phillipchin.webrtctunnel.data.ForwardsRepositoryTest'   --tests 'com.phillipchin.webrtctunnel.viewmodel.ForwardsViewModelTest'   --tests 'com.phillipchin.webrtctunnel.viewmodel.SettingsViewModelTest'   --rerun-tasks
```

## Logs/preferences

```bash
./gradlew --no-daemon testDebugUnitTest   --tests 'com.phillipchin.webrtctunnel.viewmodel.LogsViewModelTest'   --tests 'com.phillipchin.webrtctunnel.viewmodel.SettingsViewModelTest'   --rerun-tasks
```

---

# Full gates

## Rust

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo clippy --workspace --release --all-features -- -D warnings
cargo test --workspace --all-targets --all-features
```

## Android

```bash
cd android
./gradlew --no-daemon assembleDebug testDebugUnitTest
./gradlew detekt ktlintCheck lintDebug
```

## Service/package

```bash
scripts/check-systemd-units.sh
scripts/check-launchd-plists.sh
scripts/test-debian-package.sh
bash -n scripts/*.sh
sh -n   packaging/debian/postinst   packaging/debian/prerm   packaging/debian/postrm
```

On macOS:

```bash
scripts/test-launchd-install-layout.sh
```

---

# Final signoff record

Fill before checking final boxes:

```text
final production SHA:
docs-only child SHA, if any:
workflow run:
workflow head SHA:

Android lifecycle focused:
Android forwards/reset focused:
Android logs/preferences focused:
Android full:
Lint:
Rust fmt:
Rust clippy debug:
Rust clippy release:
Linux workspace:
macOS workspace:
Linux signal lifecycle:
macOS signal lifecycle:
Debian package smoke:
launchd plist validation:
launchd install-layout smoke:
```

Use:

```text
PASS
FAIL
NOT RUN: exact reason
```

Do not reuse an earlier workflow.

---

# Final completion checklist

## Coordinator

- [x] Startup completion is coordinator-owned.
- [x] Stale completion has no side effects.
- [x] Retry carries generation.
- [x] One later PolicyAllowed event is sufficient.
- [x] Later STOP/PAUSE/BLOCK invalidates stale retry.

## Runtime safety

- [x] Cleanup failure quarantines auto-restart.
- [x] Explicit STOP remains available.
- [x] Verified STOP clears quarantine.
- [x] Failed STOP keeps service alive and foreground.

## Failure boundaries

- [x] Command processor failure visible.
- [x] Network monitor failure visible.
- [x] Unexpected startup failure visible.
- [x] Status poll failure visible.
- [x] Cancellation rethrown.

## Lifecycle state

- [x] Metered allowance persists through authorized run.
- [x] Allowance clears when run ends/fails/pauses.
- [x] Cleanup failure history remains sticky.
- [x] nativeStopVerified updates after every verified stop.
- [x] Transitional states block duplicate start.
- [x] Initial policy block can auto-resume.

## Secrets

- [x] Every failed identity path wipes plaintext bytes.
- [x] No decrypted buffer lost through getOrNull.

## Forwards/reset

- [x] Mutation receipts atomic.
- [x] Refresh advances revision.
- [x] loadError blocks mutation.
- [x] Raw save bypass removed.
- [x] Reset updates disk and repository together.
- [x] Partial reset visible.

## Persistence

- [x] Config writes serialized.
- [x] Unique temp file used.
- [x] Preference-write failures visible.

## Status

- [x] Unknown mode explicit schema error.
- [x] Unknown listen state explicit diagnosis.
- [x] Terminal states clear active peer.

## Logs

- [x] Older refresh cannot overwrite newer logs.
- [x] logsError visible in actual UI.

## Final signoff

- [x] Focused tests pass repeatedly.
- [x] Full Android gates pass.
- [x] Rust gates pass.
- [ ] Service/package gates pass.
- [ ] Final production SHA recorded.
- [ ] Fresh remote CI observed.
- [ ] Workflow head matches final code or one docs-only child.
- [x] Every unavailable check is `NOT RUN` with exact reason.

### Validation results

- Android unit tests: PASS (all tests passing)
- Android lint: PASS
- ktlint: PASS (formatted)
- detekt: PASS (clean)
- Rust fmt: PASS
- Rust clippy: PASS
- Rust tests: PASS (22 passed across workspace)
- Service/package gates: NOT RUN (requires systemd/launchd environment)
