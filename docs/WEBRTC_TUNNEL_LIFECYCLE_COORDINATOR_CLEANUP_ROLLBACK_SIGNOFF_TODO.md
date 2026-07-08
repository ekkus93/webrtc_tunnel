# WebRTC Tunnel Lifecycle Coordinator, Verified-Start Cleanup, and Rollback-Integrity Release-Signoff TODO

## 0. Instructions for Claude Code / Qwen3.6 27B

Implement this TODO against:

```text
webrtc_tunnel-master2607072301.zip
```

This TODO is intentionally explicit for a local model.

### Read these files first

```text
WEBRTC_TUNNEL_LIFECYCLE_COORDINATOR_CLEANUP_ROLLBACK_SIGNOFF_SPEC.md

android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/TunnelRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/model/Models.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/SensitiveDataRedactor.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupForwardsController.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/LogsViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/ui/LogsScreen.kt

android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStopFailureTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceTestFakes.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/data/TunnelRepositoryTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/data/ForwardsRepositoryTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModelTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/SetupViewModelTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/LogsViewModelTest.kt

.github/workflows/ci.yml
```

Correct stale paths by locating the real file before editing.

---

## 0.1 Do not invent parallel abstractions

Before adding a field/helper/class, search for an existing equivalent.

Reuse current:

```text
startupJob
lifecycleGeneration
cancelStartupJobAndJoinLocked()
reporter
refreshStatusResult()
networkMonitorJob
ListenState.Error
ForwardsRepository mutex
SensitiveDataRedactor.redactText()
```

Do not create duplicates.

---

## 0.2 Required work discipline

For every task:

```text
1. read current implementation
2. add or update focused regression test
3. confirm old behavior fails the test where practical
4. implement smallest correct fix
5. run focused test
6. run relevant formatting/lint
7. commit small scoped change
```

Do not mark acceptance boxes complete before evidence exists.

---

# P0 tasks

## P0-001 — Add coordinator-owned cleanup for verified-start failure

### Files

Modify:

```text
TunnelForegroundService.kt
TunnelRepository.kt only if needed
TunnelForegroundServiceStopFailureTest.kt
TunnelForegroundServiceTestFakes.kt
```

### Problem

`TunnelRepository.start()` can return:

```text
StartStatusVerificationException
```

after JNI start succeeded.

Current service does not perform required cleanup.

### Required behavior

```text
native start succeeds
verification fails
current generation still owns startup
        ↓
one verified repository.stop()
        ↓
publish start verification failure

cleanup stop also fails
        ↓
publish combined failure
```

### Step 1 — classify startup completion

Add:

```kotlin
private sealed interface StartupCompletion {
    data object VerifiedSuccess :
        StartupCompletion

    data class NativeStartFailure(
        val error: Throwable,
    ) : StartupCompletion

    data class VerificationFailure(
        val error:
            StartStatusVerificationException,
    ) : StartupCompletion
}
```

### Step 2 — map repository result

Use:

```kotlin
private fun classifyStartupResult(
    result: Result<Unit>,
): StartupCompletion =
    result.fold(
        onSuccess = {
            StartupCompletion.VerifiedSuccess
        },
        onFailure = { error ->
            if (
                error is
                    StartStatusVerificationException
            ) {
                StartupCompletion
                    .VerificationFailure(
                        error,
                    )
            } else {
                StartupCompletion
                    .NativeStartFailure(
                        error,
                    )
            }
        },
    )
```

### Step 3 — implement cleanup

Add:

```kotlin
private suspend fun
    cleanupUnverifiedStart(
    originalError:
        StartStatusVerificationException,
) {
    reporter.stopStatusPollingAndJoin()

    repository.stop().fold(
        onSuccess = {
            nativeStopVerified.set(true)

            reporter.publishError(
                message =
                    originalError.message
                        ?: "Native startup could not be verified",
                code =
                    "start_status_verification_failed",
            )
        },
        onFailure = { cleanupError ->
            nativeStopVerified.set(false)

            reporter.publishError(
                message =
                    buildString {
                        append(
                            originalError.message
                                ?: "Native startup could not be verified",
                        )
                        append(
                            ". Cleanup also failed: ",
                        )
                        append(
                            SensitiveDataRedactor
                                .redactText(
                                    cleanupError.message
                                        ?: "unknown cleanup failure",
                                ),
                        )
                    },
                code =
                    "start_verification_cleanup_failed",
            )
        },
    )
}
```

### Step 4 — ownership rule

Before cleanup:

```kotlin
if (!isCurrentGeneration(startGeneration)) {
    return
}
```

A later lifecycle command owns cleanup.

### Do not

Do not:

```kotlin
serviceScope.launch {
    repository.stop()
}
```

Do not add cleanup in a cancellation catch.

### Required tests

1. JNI start success + status verification failure:
   - exactly one cleanup stop;
   - no polling starts;
   - policy retry state does not clear.

2. Cleanup stop failure:
   - error code `start_verification_cleanup_failed`;
   - message contains both failures;
   - nativeStopVerified remains false.

3. Later STOP supersedes failed startup:
   - no extra cleanup stop from stale startup completion.

### Acceptance criteria

- [ ] Verification failure is distinguished from native start failure.
- [ ] Current-generation verification failure triggers one cleanup stop.
- [ ] Stale generation does not perform extra cleanup.
- [ ] Cleanup failure preserves original failure.
- [ ] No lifecycle-changing call is launched outside coordinator ownership.

---

## P0-002 — Remove direct policy retry outside lifecycle processor

### Files

Modify:

```text
TunnelForegroundService.kt
TunnelForegroundServiceStopFailureTest.kt
```

### Problem

Delete direct retry equivalent to:

```kotlin
serviceScope.launch {
    offer.resume()
}
```

from startup completion.

### Step 1 — add internal retry command

```kotlin
private sealed interface LifecycleCommand {
    // existing commands

    data object RetryPolicyResume :
        LifecycleCommand
}
```

### Step 2 — submit through queue

```kotlin
private fun
    submitPendingPolicyRetry() {
    submitLifecycleCommand(
        LifecycleCommand
            .RetryPolicyResume,
    ).onFailure { error ->
        reporter.publishError(
            message =
                error.message
                    ?: "Unable to queue policy retry",
            code =
                "lifecycle_command_queue_failed",
        )
    }
}
```

Adapt if current submit helper returns `Unit`; make failure inspectable.

### Step 3 — dispatch

```kotlin
LifecycleCommand.RetryPolicyResume ->
    handleRetryPolicyResume()
```

### Required test

```text
pending retry exists
STOP queued later
startup failure occurs
```

Assert:

```text
STOP wins
no direct resume occurs after STOP
```

### Acceptance criteria

- [ ] No `serviceScope.launch { offer.resume() }` remains.
- [ ] Retry goes through lifecycle ordering.
- [ ] Later STOP/Pause/Block can supersede retry.
- [ ] Queue failure is visible.

---

## P0-003 — Make one later PolicyAllowed event reliably retry exactly once

### Files

Modify:

```text
TunnelForegroundService.kt
TunnelForegroundServiceStopFailureTest.kt
```

### Problem

Current retry can run before old `startupJob` becomes inactive and silently no-op.

### Required rule

```text
startup attempt fully finishes
        ↓
startupJob ownership cleared
        ↓
pending retry consumed
        ↓
one retry command submitted
```

### Implement

When startup completes:

```kotlin
startupJob = null
```

before consuming pending retry.

Then:

```kotlin
if (pendingPolicyResume) {
    pendingPolicyResume = false
    submitPendingPolicyRetry()
}
```

Prefer coordinator-owned plain Boolean if all accesses are serialized there.

### Commands that clear pending retry

Centralize:

```kotlin
private fun clearPendingPolicyResume() {
    pendingPolicyResume = false
}
```

Call for:

```text
Pause
Stop
StartOffer
PolicyBlocked
AllowMeteredSession
```

### Required exact test

Send:

```text
PolicyAllowed #1
resume attempt fails

PolicyAllowed #2 arrives while attempt #1 is still completing

NO PolicyAllowed #3
```

Assert:

```text
attempt #2 occurs exactly once
attempt #2 succeeds
pausedByPolicy clears
```

Do not repeatedly refire network events.

### Acceptance criteria

- [ ] Old startup fully completes before retry begins.
- [ ] Exactly one later event is sufficient.
- [ ] No repeated synthetic event loop.
- [ ] Later Stop/Pause/Block clears stale retry.
- [ ] Retry occurs exactly once.

---

## P0-004 — Make lifecycle processor failure visible and shut it down before destroy cleanup

### Files

Modify:

```text
TunnelForegroundService.kt
TunnelForegroundServiceStopFailureTest.kt
```

### Step 1 — store processor Job

```kotlin
private var commandProcessorJob:
    Job? = null
```

### Step 2 — start explicitly

```kotlin
commandProcessorJob =
    serviceScope.launch {
        processLifecycleCommands()
    }
```

### Step 3 — command error boundary

```kotlin
private suspend fun
    processLifecycleCommands() {
    for (envelope in lifecycleCommands) {
        try {
            dispatchLifecycleCommand(
                envelope,
            )
        } catch (
            cancelled: CancellationException
        ) {
            throw cancelled
        } catch (error: Throwable) {
            reporter.publishError(
                message =
                    SensitiveDataRedactor
                        .redactText(
                            error.message
                                ?: "Lifecycle command failed",
                        ),
                code =
                    "lifecycle_command_failed",
            )
        }
    }
}
```

Do not catch invariant violations that should be processor-fatal unless explicitly documented.

### Step 4 — stop accepting commands

```kotlin
private val acceptingLifecycleCommands =
    AtomicBoolean(true)
```

Submission must fail visibly when false.

### Step 5 — destroy order

Required:

```text
acceptingLifecycleCommands = false
close lifecycle channel
cancel/join network monitor
cancel/join command processor
cancel/join startup
stop/join polling
fallback verified stop if needed
```

### Required tests

1. command handler throws:
   - visible `lifecycle_command_failed`;
   - processor policy behaves as documented.

2. queued START exists when destroy begins:
   - processor stops before native cleanup;
   - no startup begins after destroy boundary.

3. network monitor callback races destroy:
   - no post-destroy command accepted.

### Acceptance criteria

- [ ] Processor Job is retained.
- [ ] Unexpected command failure is visible.
- [ ] Destruction stops command intake first.
- [ ] Processor is joined/cancelled before fallback native cleanup.
- [ ] No post-destroy command can start runtime.

---

## P0-005 — Make preference-read failure visible

### Files

Modify:

```text
TunnelForegroundService.kt
related tests
```

### Replace silent fallback

Delete:

```kotlin
runCatching {
    configRepository
        .preferences
        .first()
}
    .getOrNull()
```

Use:

```kotlin
private suspend fun
    readPreferencesOrReportFailure():
    TunnelPreferences? =
    runCatching {
        configRepository
            .preferences
            .first()
    }.getOrElse { error ->
        reporter.publishError(
            message =
                "Unable to read tunnel preferences: " +
                    SensitiveDataRedactor
                        .redactText(
                            error.message
                                ?: "unknown preference error",
                        ),
            code =
                "preferences_read_failed",
        )

        null
    }
```

### Required test

Preference source fails.

Assert:

```text
visible preferences_read_failed
no silent "resume disabled" interpretation
```

### Acceptance criteria

- [ ] Dependency failure is not converted to false.
- [ ] Error is redacted and visible.
- [ ] Retry state remains truthful.

---

## P0-006 — Make lifecycle sequence instrumentation real or remove it

### Files

Modify:

```text
TunnelForegroundService.kt
ordering tests
```

### Preferred implementation

Use:

```kotlin
private data class LifecycleEnvelope(
    val sequence: Long,
    val command: LifecycleCommand,
)
```

Queue envelopes.

Consumer:

```kotlin
check(
    envelope.sequence >
        lastSequence,
)
```

### Alternative

Remove the counter entirely and remove every claim that monotonic sequence is verified.

### Required test

Submit concurrent senders.

Assert consumed envelope sequence is strictly increasing.

### Acceptance criteria

- [ ] No dead sequence counter remains.
- [ ] Monotonic claim matches actual code/test.

---

## P0-007 — Route unsupported START_ANSWER through lifecycle ordering

### Files

Modify:

```text
TunnelForegroundService.kt
related tests
```

### Required

Add:

```kotlin
LifecycleCommand
    .UnsupportedStartAnswer
```

`onStartCommand()` submits it.

Handler:

```text
visible unsupported error
ordered shutdown/stop behavior
```

Do not directly call `stopSelf(startId)` outside lifecycle ordering.

### Required test

```text
StartOffer queued
StartAnswer arrives later
```

Assert deterministic order and final stopped/error state.

### Acceptance criteria

- [ ] No service intent bypasses lifecycle ordering.
- [ ] Unsupported answer mode is visible.
- [ ] Queued offer start cannot survive unordered stopSelf.

---

## P0-008 — Fix exact lifecycle test boundaries

### Files

Modify:

```text
TunnelForegroundServiceStopFailureTest.kt
TunnelForegroundServiceTestFakes.kt
```

### Stale poll

Assert:

```text
StatusReadReleased
        <
StopEntered
```

Not:

```text
StatusReadEntered < StopEntered
```

### Exactly one stop

Add test-only:

```text
CommandStarted(sequence)
CommandCompleted(sequence)
```

Await `Pause CommandCompleted`.

Then:

```kotlin
assertEquals(
    1,
    bridge.stopCalls,
)
```

### Required test

Temporarily restore competing cleanup stop.

Test must fail deterministically.

### Acceptance criteria

- [ ] Release-before-stop boundary is asserted.
- [ ] Exactly-one-stop waits for command completion.
- [ ] No elapsed absence proof remains.
- [ ] Reverted old behavior fails deterministically.

---

# P1 tasks

## P1-001 — Replace forwards snapshot+mutation with atomic mutation receipt

### Files

Modify:

```text
ForwardsRepository.kt
ForwardsViewModel.kt
ForwardsRepositoryTest.kt
ForwardsViewModelTest.kt
```

### Add receipt

```kotlin
data class ForwardsMutationReceipt(
    val before:
        List<ForwardConfig>,
    val after:
        List<ForwardConfig>,
    val committedRevision: Long,
)
```

### Add receipt-returning upsert

Use one mutex critical section:

```kotlin
suspend fun upsertWithReceipt(
    forward: ForwardConfig,
): Result<ForwardsMutationReceipt> =
    mutex.withLock {
        ensureMutationAllowedLocked()
            .getOrElse {
                return@withLock
                    Result.failure(it)
            }

        val before =
            _forwards.value

        val after =
            before
                .filterNot {
                    it.id == forward.id
                } +
                forward

        runCatching {
            store.saveForwards(after)

            _forwards.value = after
            revision += 1

            ForwardsMutationReceipt(
                before = before,
                after = after,
                committedRevision =
                    revision,
            )
        }
    }
```

Implement equivalent delete.

### ViewModel

Do not call:

```text
snapshot()
then upsert()
```

Call:

```text
upsertWithReceipt()
```

Use returned receipt for rollback.

### Required race test

```text
A begins
B mutates between old snapshot point and A mutation
A validation fails
A rollback
```

Assert B remains.

### Acceptance criteria

- [ ] Before-state and mutation commit are atomic.
- [ ] Rollback receipt belongs to exact mutation.
- [ ] Intervening mutation cannot be erased.

---

## P1-002 — Increment revision on successful refresh

### Files

Modify:

```text
ForwardsRepository.kt
ForwardsRepositoryTest.kt
```

### Required

Successful refresh:

```kotlin
_forwards.value = loaded
_loadError.value = null
hasValidBaseline = true
revision += 1
```

### Required test

```text
mutation receipt revision N
external file change
refresh succeeds
rollback old receipt
```

Assert revision mismatch and refreshed data remains.

### Acceptance criteria

- [ ] Every canonical state replacement advances revision.
- [ ] Old receipt cannot overwrite refreshed data.

---

## P1-003 — Enforce loadError block on every mutation

### Files

Modify:

```text
ForwardsRepository.kt
ForwardsViewModel.kt
SetupForwardsController.kt
related tests
```

### Central guard

```kotlin
private fun
    ensureMutationAllowedLocked():
    Result<Unit> {
    if (_loadError.value != null) {
        return Result.failure(
            ForwardsMutationBlockedException(
                "Saved forwards could not be loaded. " +
                    "Fix the problem and retry before editing.",
            ),
        )
    }

    return Result.success(Unit)
}
```

Use for:

```text
upsert
delete
raw save if retained
conditional rollback where applicable
```

### Required tests

1. initial load error blocks upsert;
2. successful load then later refresh error blocks delete;
3. details mutation blocked;
4. setup mutation blocked;
5. successful refresh clears block.

### Acceptance criteria

- [ ] `loadError != null` blocks all mutations.
- [ ] `hasValidBaseline` cannot bypass current error.
- [ ] Error is visible through all callers.

---

## P1-004 — Remove or harden raw ForwardsRepository.save()

### Files

Modify:

```text
ForwardsRepository.kt
callers/tests
```

### Search

```bash
rg -n 'forwardsRepository\.save\(' \
  android/app/src/main \
  android/app/src/test
```

### Preferred

If no legitimate production caller remains:

```text
delete save()
```

### Otherwise

It must:

```text
check loadError
persist under mutex
publish state
increment revision
return explicit result
```

Do not keep a bypass.

### Acceptance criteria

- [ ] No raw mutation bypass remains.
- [ ] Unused footgun removed where possible.

---

## P1-005 — Make unknown native mode a schema error

### Files

Modify:

```text
TunnelRepository.kt
TunnelRepositoryTest.kt
```

### Replace

```kotlin
else -> TunnelMode.Offer
```

### Required

```text
offer  → Offer
answer → Answer
other  → schema error
```

Because model requires a mode value:

```text
retain previous mode
set ServiceState.Error
set lastError.code = native_status_schema_error
```

### Required test

Unknown sentinel:

```text
future_mode_v99
```

Assert:

```text
not clean Offer
ServiceState.Error
native_status_schema_error
```

### Acceptance criteria

- [ ] Unknown mode cannot become plausible Offer.
- [ ] Root cause is visible.

---

## P1-006 — Add explicit unknown listen-state diagnosis

### Files

Modify:

```text
TunnelRepository.kt
TunnelRepositoryTest.kt
```

### Required

Unknown:

```text
ListenState.Error
+
native_status_schema_error
+
"Unknown native listen state: <redacted value>"
```

### Acceptance criteria

- [ ] Unknown listen value has explicit diagnosis.
- [ ] No generic silent Error only.

---

## P1-007 — Clear active peer on every terminal state

### Files

Modify:

```text
TunnelRepository.kt
related tests
```

### Required states

```text
Stopped
Error
PausedMeteredBlocked
NoNetwork
```

Centralize helper.

### Required tests

```text
Connected(peer A)
→ PausedMeteredBlocked

Connected(peer A)
→ NoNetwork
```

Assert `remotePeerId == null`.

### Acceptance criteria

- [ ] Every terminal state clears active peer.
- [ ] Active transient state may retain peer.

---

## P1-008 — Keep nativeStopVerified truthful after every verified stop

### Files

Modify:

```text
TunnelForegroundService.kt
related tests
```

### Set true after verified success from:

```text
Pause
PolicyBlocked
Stop
Unverified-start cleanup
Destroy fallback
```

Set false when a new native start attempt begins.

### Required tests

1. Pause succeeds then onDestroy:
   - no redundant second stop.

2. Policy pause succeeds then onDestroy:
   - no redundant second stop.

### Acceptance criteria

- [ ] Flag represents verified native runtime absence.
- [ ] Destroy does not repeat already-verified stop.

---

## P1-009 — Scope temporary metered allowance to one attempted run

### Files

Modify:

```text
TunnelForegroundService.kt
related tests
```

### Clear on:

```text
startup failure
pause success
policy pause success
stop success
destroy completion
```

### Required test

```text
AllowMeteredSession
startup fails
ordinary StartOffer later
```

Assert ordinary start does not inherit temporary allowance.

### Acceptance criteria

- [ ] Temporary override does not leak into later run.

---

## P1-010 — Use active-or-starting predicate for duplicate-start prevention

### Files

Modify:

```text
TunnelForegroundService.kt
related tests
```

### Replace weaker gate

Use:

```kotlin
isTunnelActiveOrStarting()
```

unless there is a documented exception.

### Required tests

Start requested while:

```text
Starting
Connecting
Reconnecting
```

No duplicate start.

### Acceptance criteria

- [ ] Transitional active startup states block duplicate start.

---

## P1-011 — Serialize overlapping log refreshes

### Files

Modify:

```text
LogsViewModel.kt
LogsViewModelTest.kt
```

### Preferred

Cancel previous refresh:

```kotlin
private var refreshJob:
    Job? = null

fun refresh() {
    refreshJob?.cancel()

    refreshJob =
        viewModelScope.launch {
            // load logs
        }
}
```

Or use generation.

### Required test

```text
refresh A starts
refresh B starts later
B succeeds
A fails later
```

Assert stale A cannot overwrite B's newer result/error state.

### Acceptance criteria

- [ ] Older refresh cannot overwrite newer result.

---

## P1-012 — Wire logsError to the actual UI or remove redundant state

### Files

Modify:

```text
LogsViewModel.kt
LogsScreen.kt
related tests
```

### Preferred

Expose:

```kotlin
val logsError =
    deps.tunnelRepository.logsError
```

Collect in screen and show visible error card/banner.

The synthetic error log may remain.

### Acceptance criteria

- [ ] Separate logs error is actually visible.
- [ ] No dead state flow remains.

---

# P2 tasks

## P2-001 — Consider extracting TunnelLifecycleCoordinator

Future work only.

Do not implement unless service remains unmaintainable after this pass.

---

## P2-002 — Consider typed StartOutcome through JNI

Future work only.

---

## P2-003 — Consider structured forwards transaction API

Future work only.

The mutation receipt in P1-001 is sufficient now.

---

# Required implementation sequence

Use this exact order.

```text
Stage 1 — close unowned runtime
  P0-001 verified-start cleanup

Stage 2 — restore one lifecycle path
  P0-002 remove direct retry bypass
  P0-003 reliable pending retry
  P0-005 preference-read failure
  P0-006 real sequence envelopes
  P0-007 ordered START_ANSWER

Stage 3 — destruction safety
  P0-004 processor failure + shutdown

Stage 4 — deterministic proof
  P0-008 exact lifecycle test boundaries

Stage 5 — forwards transaction integrity
  P1-001 atomic mutation receipt
  P1-002 refresh revision
  P1-003 loadError block
  P1-004 remove/harden raw save

Stage 6 — native status truthfulness
  P1-005 unknown mode
  P1-006 unknown listen diagnosis
  P1-007 terminal peer clearing

Stage 7 — lifecycle state cleanup
  P1-008 nativeStopVerified
  P1-009 temporary metered allowance
  P1-010 duplicate-start predicate

Stage 8 — logs UI truthfulness
  P1-011 overlapping refreshes
  P1-012 logsError UI

Stage 9 — final audit and signoff
```

Recommended small commits:

```text
fix(android): clean up unverified native startup
fix(android): route policy retry through lifecycle coordinator
fix(android): retain one policy retry until startup completion
fix(android): make lifecycle processor failure visible
fix(android): fail visibly when preferences cannot be read
fix(android): attach sequence envelopes to lifecycle commands
fix(android): order unsupported answer action
test(android): prove lifecycle boundaries with completion events
fix(android): return atomic forwards mutation receipts
fix(android): invalidate rollback receipts on refresh
fix(android): block all mutations while forwards load failed
refactor(android): remove unsafe raw forwards save
fix(android): reject unknown native mode explicitly
fix(android): diagnose unknown listen states
fix(android): clear stale peer on every terminal state
fix(android): keep verified-stop state consistent
fix(android): scope metered override to one attempted run
fix(android): block duplicate starts in transitional states
fix(android): serialize log refreshes
fix(android): surface logs error in UI
```

Do not make one giant commit.

---

# Focused test commands

## Android service/repository

```bash
cd android

./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundServiceStopFailureTest' \
  --tests 'com.phillipchin.webrtctunnel.data.TunnelRepositoryTest' \
  --rerun-tasks
```

Run three fresh times after P0 lifecycle changes.

## Forwards

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.data.ForwardsRepositoryTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.ForwardsViewModelTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.SetupViewModelTest' \
  --rerun-tasks
```

## Logs

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.LogsViewModelTest' \
  --rerun-tasks
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
sh -n \
  packaging/debian/postinst \
  packaging/debian/prerm \
  packaging/debian/postrm
```

On macOS:

```bash
scripts/test-launchd-install-layout.sh
```

---

# Final signoff record

Before checking final boxes, fill:

```text
final production SHA:
docs-only child SHA, if any:
workflow run:
workflow head SHA:

Android focused:
Android full:
Lint:
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

## Verified start

- [ ] Verification failure performs one coordinator-owned cleanup stop.
- [ ] Stale generation performs no extra cleanup.
- [ ] Cleanup failure preserves both errors.

## Lifecycle ordering

- [ ] No direct resume/start/stop bypasses coordinator.
- [ ] Pending retry waits until startup fully completes.
- [ ] Later Stop/Pause/Block clears stale retry.
- [ ] One later PolicyAllowed event is sufficient.
- [ ] Processor failure is visible.
- [ ] Processor is stopped before destroy fallback cleanup.
- [ ] Preference-read failure is visible.
- [ ] Sequence instrumentation is real.
- [ ] Unsupported answer action is ordered.

## Test trust

- [ ] `StatusReadReleased < StopEntered`.
- [ ] Exactly-one-stop waits for command completion.
- [ ] No elapsed absence proof remains.
- [ ] Required regressions fail when old behavior is restored.

## Forwards integrity

- [ ] Mutation receipt captures before-state atomically.
- [ ] Refresh increments revision.
- [ ] loadError blocks every mutation.
- [ ] Raw save bypass removed/hardened.
- [ ] Intervening mutation cannot be erased.

## Native status truthfulness

- [ ] Unknown mode is explicit schema error.
- [ ] Unknown listen state has explicit diagnosis.
- [ ] Every terminal state clears active peer.

## Lifecycle state

- [ ] nativeStopVerified updates after every verified stop.
- [ ] Metered override ends with attempted run.
- [ ] Transitional startup states block duplicate start.

## Logs

- [ ] Older refresh cannot overwrite newer logs result.
- [ ] logsError is visible in actual UI.

## Final signoff

- [ ] Focused tests pass repeatedly.
- [ ] Full Android gates pass.
- [ ] Rust gates pass.
- [ ] Service/package gates pass.
- [ ] Final production SHA recorded.
- [ ] Fresh remote CI observed.
- [ ] Workflow head matches final code or one docs-only child.
- [ ] Every unavailable check is `NOT RUN` with exact reason.
