# WebRTC Tunnel Ordered Lifecycle, Verified Start, and Policy-Integrity Release-Signoff TODO

## 0. Instructions for Claude Code

Implement this TODO against:

```text
webrtc_tunnel-master_2607062147.zip
```

Read first:

```text
WEBRTC_TUNNEL_ORDERED_LIFECYCLE_VERIFIED_START_POLICY_SIGNOFF_SPEC.md

android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
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
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModelTest.kt

.github/workflows/ci.yml
```

Correct any stale path in the list above by locating the actual file before editing.

### Priority scale

```text
P0 = release blocker / lifecycle order / false success / policy integrity
P1 = high-priority state integrity / UI truthfulness / schema strictness
P2 = future cleanup; do not implement in this pass
```

### Non-negotiable rules

- Preserve foreground-process architecture.
- Preserve the Rust daemon architecture.
- Preserve signaling, crypto, identity, authorization, and wire protocol.
- Do not reintroduce `sd_notify`.
- Do not add hidden timeouts.
- Do not add an unbounded lifecycle queue.
- Do not silently drop lifecycle commands.
- Do not launch independent START/PAUSE/RESUME/STOP coroutines from `onStartCommand`.
- Do not let network policy bypass lifecycle ordering.
- Do not report start success without verified runtime state.
- Do not let startup verification failure leave an unowned native runtime.
- Do not clear policy retry state before verified start success.
- Do not mutate tunnel lifecycle state because logs failed to load.
- Do not let rollback overwrite a newer forwards mutation.
- Do not permit forwards mutation while `loadError` is active.
- Do not use elapsed time to prove an event did not happen.
- Run focused tests after every task.
- Run remote CI only after every production change.

---

# P0 tasks

## P0-001 — Introduce one ordered lifecycle-command processor

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStopFailureTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceTestFakes.kt
```

### Goal

Fix:

```text
START arrives first
PAUSE arrives second
PAUSE coroutine runs first
START coroutine runs second
final state = running
```

### Step 1 — add bounded command model

Add near the service:

```kotlin
private const val LIFECYCLE_COMMAND_CAPACITY = 32

private sealed interface LifecycleCommand {
    data object StartOffer : LifecycleCommand

    data object Pause : LifecycleCommand

    data object Resume : LifecycleCommand

    data object Stop : LifecycleCommand

    data object AllowMeteredSession :
        LifecycleCommand

    data class PolicyBlocked(
        val reason: String,
    ) : LifecycleCommand

    data object PolicyAllowed :
        LifecycleCommand
}

private data class LifecycleEnvelope(
    val sequence: Long,
    val command: LifecycleCommand,
)
```

### Step 2 — add queue and sequence

```kotlin
private val lifecycleCommands =
    Channel<LifecycleEnvelope>(
        capacity = LIFECYCLE_COMMAND_CAPACITY,
    )

private val nextLifecycleSequence =
    AtomicLong(0)
```

Do not use `UNLIMITED`.

### Step 3 — submission helper

```kotlin
private fun submitLifecycleCommand(
    command: LifecycleCommand,
) {
    val envelope =
        LifecycleEnvelope(
            sequence =
                nextLifecycleSequence
                    .getAndIncrement(),
            command = command,
        )

    val result =
        lifecycleCommands.trySend(envelope)

    if (result.isFailure) {
        reporter.publishError(
            message =
                "Unable to queue lifecycle command " +
                    "${command::class.simpleName}",
            code =
                "lifecycle_command_queue_failed",
        )
    }
}
```

If current `publishError` must run from a coroutine, use a small explicit failure-reporting path.

Do not ignore queue failure.

### Step 4 — make onStartCommand submission-only

Replace independent launches.

Target:

```kotlin
when (intent?.action) {
    ACTION_START_OFFER ->
        submitLifecycleCommand(
            LifecycleCommand.StartOffer,
        )

    ACTION_PAUSE ->
        submitLifecycleCommand(
            LifecycleCommand.Pause,
        )

    ACTION_RESUME ->
        submitLifecycleCommand(
            LifecycleCommand.Resume,
        )

    ACTION_STOP ->
        submitLifecycleCommand(
            LifecycleCommand.Stop,
        )

    ACTION_ALLOW_METERED_SESSION ->
        submitLifecycleCommand(
            LifecycleCommand.AllowMeteredSession,
        )
}
```

No `serviceScope.launch { offer.* }` per command.

### Step 5 — processor

Add:

```kotlin
private val commandProcessorJob =
    serviceScope.launch {
        processLifecycleCommands()
    }
```

```kotlin
private suspend fun processLifecycleCommands() {
    var lastSequence = -1L

    for (envelope in lifecycleCommands) {
        check(envelope.sequence > lastSequence) {
            "Lifecycle command sequence regressed"
        }

        lastSequence = envelope.sequence

        handleLifecycleCommand(envelope)
    }
}
```

If network and Android senders can submit concurrently, channel FIFO plus sequence assignment is the source of accepted order.

Do not sort the queue after submission.

### Step 6 — route network policy through queue

Replace direct:

```text
serviceScope.launch { offer.pauseForPolicy(...) }
serviceScope.launch { offer.resume() }
```

with:

```kotlin
submitLifecycleCommand(
    if (policy.tunnelAllowed) {
        LifecycleCommand.PolicyAllowed
    } else {
        LifecycleCommand.PolicyBlocked(
            reason =
                policy.blockReason
                    ?: "Network policy blocked tunnel",
        )
    },
)
```

### Step 7 — preserve existing single-owner stop behavior

The command handler may call existing coordinator methods.

Do not reintroduce startup-owned cancellation cleanup.

### Required tests

#### Test A — START then PAUSE order

Force scheduler inversion opportunity.

Sequence:

```text
submit START
submit PAUSE
allow processor to run
```

Assert:

```text
START is handled before PAUSE
final state is not running
```

Use recorded command sequence, not `Thread.sleep`.

#### Test B — PAUSE then START order

Assert the opposite order:

```text
PAUSE
then START
final state active-or-starting
```

#### Test C — ALLOW_METERED then PAUSE

Assert old allow-metered command cannot resume after later pause.

### Regression-strength check

Temporarily restore per-command independent `serviceScope.launch`.

At least one ordering test must fail.

### Acceptance criteria

- [ ] onStartCommand no longer launches independent lifecycle coroutines.
- [ ] Network policy uses same command ordering path.
- [ ] Queue is bounded.
- [ ] Queue failure is visible.
- [ ] Accepted sequence order is monotonic.
- [ ] Later PAUSE/STOP cannot be undone by older START.
- [ ] Ordering regression test fails on old architecture.

---

## P0-002 — Verify native start success and clean up unverified startup

### Files

Modify:

```text
TunnelRepository.kt
TunnelForegroundService.kt
Models.kt
TunnelRepositoryTest.kt
TunnelForegroundServiceStopFailureTest.kt
```

### Step 1 — add exception

```kotlin
class StartStatusVerificationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(
    message,
    cause,
)
```

### Step 2 — inspect active-or-starting helper

Run:

```bash
rg -n 'isTunnelActiveOrStarting' \
  android/app/src/main/java
```

Confirm exact accepted states.

Required accepted set:

```text
Starting
Connecting
Reconnecting
Listening
Serving
Connected
```

Do not silently accept terminal states.

### Step 3 — replace repository start result

Use:

```kotlin
fun start(
    mode: TunnelMode,
    configPath: String,
    identityBytes: ByteArray? = null,
): Result<Unit> {
    val nativeResult =
        when (mode) {
            TunnelMode.Offer ->
                bridge.startOffer(
                    configPath,
                    identityBytes,
                )

            TunnelMode.Answer ->
                bridge.startAnswer(configPath)
        }

    return nativeResult.fold(
        onFailure = { error ->
            Result.failure(error)
        },
        onSuccess = {
            refreshStatusResult().fold(
                onFailure = { error ->
                    Result.failure(
                        StartStatusVerificationException(
                            "Native start returned success " +
                                "but runtime status could " +
                                "not be verified",
                            error,
                        ),
                    )
                },
                onSuccess = { status ->
                    if (
                        status.serviceState
                            .isTunnelActiveOrStarting()
                    ) {
                        Result.success(Unit)
                    } else {
                        Result.failure(
                            StartStatusVerificationException(
                                "Native start returned success " +
                                    "but final state was " +
                                    "${status.serviceState}",
                            ),
                        )
                    }
                },
            )
        },
    )
}
```

### Step 4 — do not clear retry state until verified success

Current success-only behavior remains:

```kotlin
result.onSuccess {
    pausedByPolicy.set(false)
}
```

A `StartStatusVerificationException` must not clear it.

### Step 5 — authoritative cleanup

When current-generation startup returns `StartStatusVerificationException`:

```text
do not start status polling
do not publish clean success
ordered lifecycle coordinator performs verified stop
```

Do not call `repository.stop()` directly from an unrelated startup cancellation catch.

Suggested service behavior:

```kotlin
private suspend fun cleanupUnverifiedStart(
    originalError: Throwable,
) {
    reporter.stopStatusPollingAndJoin()

    repository.stop().fold(
        onSuccess = {
            reporter.publishError(
                message =
                    originalError.message
                        ?: "Native startup could not be verified",
                code =
                    "start_status_verification_failed",
            )
        },
        onFailure = { cleanupError ->
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
                            cleanupError.message
                                ?: "unknown cleanup failure",
                        )
                    },
                code =
                    "start_verification_cleanup_failed",
            )
        },
    )
}
```

This function must be called only by the ordered lifecycle owner.

If a later PAUSE/STOP has superseded the generation, that later command owns cleanup.

### Required repository tests

1. native start success + `Starting` → success;
2. native start success + `Listening` → success;
3. native start success + status read failure → failure;
4. native start success + `Error` → failure;
5. native start success + `Stopped` → failure.

### Required service tests

1. verification failure does not clear `pausedByPolicy`;
2. verification failure does not start polling;
3. verification failure triggers exactly one coordinator-owned cleanup stop;
4. cleanup failure preserves both errors.

### Regression strength

Temporarily restore old:

```kotlin
result.onSuccess {
    refreshStatus()
}

return result
```

New tests must fail.

### Acceptance criteria

- [ ] JNI start success is not trusted alone.
- [ ] Active-or-starting final state is required.
- [ ] Read/decode failure is not clean start success.
- [ ] Error/Stopped is not clean start success.
- [ ] Verification failure does not clear policy retry state.
- [ ] Verification failure cleanup has one owner.
- [ ] Cleanup failure preserves original start failure.

---

## P0-003 — Make PolicyBlocked cancel in-flight startup and fail safe

### Files

Modify:

```text
TunnelForegroundService.kt
TunnelForegroundServiceStopFailureTest.kt
```

### Remove lifecycle-state gate

Delete policy logic equivalent to:

```kotlin
if (current.isTunnelRunning()) {
    offer.pauseForPolicy(...)
}
```

Policy enforcement must not depend solely on UI state.

### Command handler

Implement:

```kotlin
private suspend fun handlePolicyBlocked(
    reason: String,
) {
    pendingPolicyResume = false

    lifecycleGeneration.incrementAndGet()

    cancelStartupJobAndJoinLocked()
    reporter.stopStatusPollingAndJoin()

    repository.stop().fold(
        onSuccess = {
            pausedByPolicy.set(true)
            repository.setPolicyBlocked(reason)
        },
        onFailure = { error ->
            pausedByPolicy.set(false)

            reporter.publishError(
                message =
                    error.message
                        ?: "Unable to stop tunnel for policy",
                code =
                    stopFailureCode(error),
            )
        },
    )
}
```

Adapt to the command processor and current mutex ownership.

### Required test A — block before native start

Sequence:

```text
START accepted
startup blocks before native start
PolicyBlocked accepted
startup is cancelled/joined
release old startup barrier
```

Assert:

```text
old startup does not start native runtime
verified stop/policy state is truthful
```

### Required test B — block during native start

Sequence:

```text
native start in flight
policy becomes blocked
```

Assert:

```text
policy command waits for startup cancellation/completion
then performs one authoritative cleanup stop
final state is policy-paused or visible error
```

### Required test C — policy block while repository state says Error

If runtime may still exist, policy command must still attempt cleanup.

### Acceptance criteria

- [ ] PolicyBlocked does not depend on `isTunnelRunning()`.
- [ ] In-flight startup is cancelled and joined.
- [ ] Status polling is quiesced.
- [ ] Verified stop occurs before policy-paused state.
- [ ] Stop failure does not publish normal policy-paused state.
- [ ] Policy command is ordered with user commands.

---

## P0-004 — Make one later PolicyAllowed event reliably trigger retry

### Files

Modify:

```text
TunnelForegroundService.kt
TunnelForegroundServiceStopFailureTest.kt
```

### Coordinator-owned state

Add:

```kotlin
private var pendingPolicyResume = false
```

Only the lifecycle command processor may read/write it.

### PolicyAllowed behavior

```kotlin
private suspend fun handlePolicyAllowed() {
    if (!pausedByPolicy.get()) {
        pendingPolicyResume = false
        return
    }

    if (!preferences.resumeOnUnmetered) {
        return
    }

    if (startupJob?.isActive == true) {
        pendingPolicyResume = true
        return
    }

    beginPolicyResumeAttempt()
}
```

### Startup completion behavior

On verified success:

```text
pausedByPolicy = false
pendingPolicyResume = false
```

On failure:

```text
pausedByPolicy remains true

if pendingPolicyResume:
    pendingPolicyResume = false
    start exactly one retry
```

### Commands that clear pending resume

Review:

```text
Pause
Stop
StartOffer
PolicyBlocked
AllowMeteredSession
```

Clear stale pending policy-resume intention where later user/policy intent supersedes it.

### Required test

Exactly:

```text
policy pause succeeds
one PolicyAllowed event starts resume attempt
resume attempt fails
one later PolicyAllowed event arrives while first attempt is still completing
no third event is sent
second attempt occurs once
second attempt succeeds
pausedByPolicy clears
```

The test must not repeatedly fire network events inside a polling loop.

### Regression strength

Restore old `already starting -> no-op` behavior without pending intent.

Test must fail.

### Acceptance criteria

- [ ] One later allowed event is sufficient.
- [ ] No repeated synthetic event firing.
- [ ] Pending retry is coordinator-owned.
- [ ] Failed retry keeps policy pause true.
- [ ] Successful retry clears flag.
- [ ] PAUSE/STOP can supersede pending retry.

---

## P0-005 — Separate log retrieval failure from tunnel lifecycle state

### Files

Modify:

```text
TunnelRepository.kt
LogsViewModel.kt or current log consumer
TunnelRepositoryTest.kt
related log tests
```

### Add separate error state

```kotlin
private val _logsError =
    MutableStateFlow<TunnelError?>(null)

val logsError: StateFlow<TunnelError?> =
    _logsError.asStateFlow()
```

### recentLogs success

```kotlin
_logsError.value = null
```

### recentLogs failure

Set:

```kotlin
_logsError.value =
    TunnelError(
        code = "log_decode_failed",
        message =
            "Native log retrieval failed",
        details =
            SensitiveDataRedactor.redactText(
                error.message
                    ?: "unknown log retrieval error",
            ),
    )
```

Do not call `updateStatus`.

Do not change:

```text
serviceState
mqttConnected
activeSessionCount
```

Keep synthetic visible error-log entry if useful.

### Required tests

1. running tunnel + log decode failure → service remains running;
2. `logsError` becomes visible;
3. successful later retrieval clears `logsError`;
4. log failure does not suppress later policy-stop behavior.

### Acceptance criteria

- [ ] Log failure cannot set tunnel lifecycle `Error`.
- [ ] Log failure is separately visible.
- [ ] Successful retrieval clears ancillary error.
- [ ] Policy enforcement is unaffected by log UI failure.

---

## P0-006 — Eliminate duplicate normal-stop cleanup and join network monitor during destroy

### Files

Modify:

```text
TunnelForegroundService.kt
TunnelForegroundServiceStopFailureTest.kt
```

### Add verified-stop state

```kotlin
private val nativeStopVerified =
    AtomicBoolean(true)
```

Set false when a new native startup attempt begins.

Set true only after:

```text
repository.stop() returns verified success
```

### ACTION_STOP handler

After verified stop:

```kotlin
nativeStopVerified.set(true)
stopSelf()
```

### onDestroy

Required logic:

```text
cancel network monitor
cleanup coroutine joins network monitor

if nativeStopVerified == true:
    skip native stop

else:
    cancel/join startup
    stop/join polling
    verified repository.stop()
```

### Do not

Do not:

```text
ACTION_STOP verified success
then onDestroy repository.stop again
```

### Required tests

#### Test A — normal stop

Assert:

```text
ACTION_STOP
verified success
onDestroy
stopCalls == 1
```

No settle timeout.

#### Test B — destruction without prior stop

Assert fallback cleanup calls stop once.

#### Test C — stop failure then destroy

Define exact policy.

Recommended:

```text
first stop failed
nativeStopVerified remains false
onDestroy retries once
```

Preserve earlier failure history.

#### Test D — network monitor join

Use a deterministic monitor barrier.

Assert monitor cannot enqueue a policy command after destroy cleanup begins.

### Acceptance criteria

- [ ] Normal STOP causes one native stop.
- [ ] Destroy fallback still cleans unverified runtime.
- [ ] Stop failure may retry exactly once on destroy.
- [ ] Network monitor is cancel-and-joined.
- [ ] No post-destroy policy command appears.

---

## P0-007 — Remove elapsed-time absence proofs from required lifecycle tests

### Files

Modify:

```text
TunnelForegroundServiceStopFailureTest.kt
TunnelForegroundServiceTestFakes.kt
```

### Remove

```text
waitForCondition(3_000) { stopCalls >= 2 }
```

from exactly-one-stop proof.

Remove:

```text
withTimeoutOrNull(500) { awaitStopCall() }
```

used to prove no stop occurred yet.

### Test-fake event log

Add in test source:

```kotlin
internal sealed interface FakeLifecycleEvent {
    data object StatusReadEntered :
        FakeLifecycleEvent

    data object StatusReadReleased :
        FakeLifecycleEvent

    data class StopEntered(
        val call: Int,
    ) : FakeLifecycleEvent
}
```

Recorder:

```kotlin
private val lifecycleEvents =
    CopyOnWriteArrayList<
        FakeLifecycleEvent
    >()

fun lifecycleEventsSnapshot():
    List<FakeLifecycleEvent> =
        lifecycleEvents.toList()
```

Record:

```text
StatusReadEntered
StatusReadReleased
StopEntered(call)
```

### Stale-poll ordering assertion

After command completion:

```kotlin
val events =
    bridge.lifecycleEventsSnapshot()

val releasedIndex =
    events.indexOf(
        FakeLifecycleEvent.StatusReadReleased,
    )

val stopIndex =
    events.indexOfFirst {
        it is FakeLifecycleEvent.StopEntered
    }

assertTrue(releasedIndex >= 0)
assertTrue(stopIndex >= 0)
assertTrue(releasedIndex < stopIndex)
```

### Exactly-one-stop assertion

Wait for final command result.

Then:

```kotlin
assertEquals(
    1,
    bridge.stopCalls,
)
```

No settle window.

Because startup is joined before authoritative stop, a reverted competing startup cleanup must have completed before the lifecycle command finishes.

### Search gate

```bash
rg -n \
  'withTimeoutOrNull\(500|waitForCondition\(.*3_000|Thread\.sleep' \
  android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStopFailureTest.kt
```

Review every match.

### Acceptance criteria

- [ ] No timeout is used to prove absence.
- [ ] No settle wait is ignored.
- [ ] Event ordering proves poll quiescing.
- [ ] Final command completion proves exactly-one-stop count.
- [ ] Reverted old competing stop fails deterministically.

---

## P0-008 — Final release-signoff gates and CI on final implementation SHA

### Timing

Run this last.

After every P0 and P1 production change.

### Local Rust

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo clippy --workspace --release --all-features -- -D warnings
cargo test --workspace --all-targets --all-features
```

### Focused Android

At minimum:

```bash
cd android

./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundServiceStopFailureTest' \
  --tests 'com.phillipchin.webrtctunnel.data.TunnelRepositoryTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.ForwardsViewModelTest' \
  --rerun-tasks
```

Add exact classes created by this pass.

Run concurrency-sensitive classes three times fresh.

### Full Android

```bash
./gradlew --no-daemon assembleDebug testDebugUnitTest
./gradlew detekt ktlintCheck lintDebug
```

### Service/package

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

### Final SHA rule

Record final production-code SHA.

Remote CI must run on:

```text
that exact SHA
```

or:

```text
one later docs-only commit
whose parent is exactly that code SHA
```

Do not reuse workflow run:

```text
28841060284
```

for this new pass.

### Acceptance criteria

- [ ] All local gates reported PASS/FAIL/NOT RUN.
- [ ] Remote CI ran after every production change.
- [ ] Workflow head matches final code or one docs-only child.
- [ ] Linux and macOS jobs observed.
- [ ] Signal lifecycle jobs observed.
- [ ] Package/service jobs observed.
- [ ] No earlier workflow reused.

---

# P1 tasks

## P1-001 — Synchronize metered-session allowance and make the command atomic

### Files

Modify:

```text
TunnelForegroundService.kt
related tests
```

### Replace plain Boolean

```kotlin
private val allowMeteredForCurrentRun =
    AtomicBoolean(false)
```

Convert every read/write.

### Ordered command

`AllowMeteredSession` handling must perform:

```text
set allowance true
update repository allowance state
begin startup attempt
```

within one command handling step.

Do not:

```text
set flag
return
later call startOffer()
```

outside ordering.

### Required test

```text
AllowMeteredSession accepted
Pause accepted later
```

Assert later pause wins.

### Acceptance criteria

- [ ] No plain cross-thread allowance Boolean.
- [ ] Allow + start is one ordered lifecycle command.
- [ ] Later PAUSE/STOP supersedes it.

---

## P1-002 — Add revision-aware forwards rollback

### Files

Modify:

```text
ForwardsRepository.kt
ForwardsViewModel.kt
ForwardsRepositoryTest.kt
ForwardsViewModelTest.kt
```

### Add revision

Repository mutex owns:

```kotlin
private var revision: Long = 0
```

Add:

```kotlin
data class ForwardsSnapshot(
    val forwards: List<ForwardConfig>,
    val revision: Long,
)
```

### Mutation receipt

A successful mutation should return or expose:

```text
revision after mutation
```

Do not derive it outside the mutex.

### Conditional rollback

Add repository operation equivalent to:

```kotlin
suspend fun saveIfRevisionMatches(
    expectedRevision: Long,
    forwards: List<ForwardConfig>,
): Result<Unit>
```

If revision differs:

```kotlin
Result.failure(
    ForwardsRevisionMismatchException(
        expectedRevision,
        revision,
    ),
)
```

Do not write stale snapshot.

### ViewModel behavior

On activation failure:

```text
rollback revision matches
    → rollback

rollback revision changed
    → leave newer data untouched
    → visible message
```

Required message:

```text
Activation failed.
Automatic rollback was skipped because forwards changed again.
The newer changes were left untouched.
```

### Required deterministic test

```text
mutation A persists
validation A blocks
mutation B persists
validation A fails
A attempts rollback
```

Assert B remains.

### Acceptance criteria

- [ ] Rollback cannot overwrite newer mutation.
- [ ] Revision mismatch is visible.
- [ ] Newer data remains untouched.
- [ ] Normal rollback still works.

---

## P1-003 — Block every forward mutation while loadError is active

### Files

Modify:

```text
ForwardsRepository.kt
ForwardsViewModel.kt
SetupForwardsController.kt or actual setup mutation controller
related tests
```

### Central repository rule

Before every mutation:

```kotlin
if (_loadError.value != null) {
    return Result.failure(
        ForwardsMutationBlockedException(
            "Saved forwards could not be loaded. " +
                "Fix the problem and retry before editing.",
        ),
    )
}
```

Apply centrally.

Do not rely on one screen disabling Add.

### Required tests

1. startup load error blocks upsert;
2. later reload error blocks delete;
3. details-screen mutation path gets failure;
4. setup-controller mutation path gets failure;
5. successful reload clears block.

### Acceptance criteria

- [ ] No mutation path bypasses loadError.
- [ ] Error is visible.
- [ ] Saved corrupt/unreadable file remains untouched.
- [ ] Successful reload re-enables mutation.

---

## P1-004 — Make unknown native status values explicit errors

### Files

Modify:

```text
TunnelRepository.kt
TunnelRepositoryTest.kt
```

### Unknown mode

Replace:

```kotlin
else -> TunnelMode.Offer
```

Policy:

```text
retain previous display mode only because model requires a value
set lifecycle state Error
set lastError code native_status_schema_error
```

### Unknown listen state

Replace unknown fallback to `Stopped`.

Use:

```text
unknown
    → ListenState.Error
```

Attach explicit error text:

```text
Unknown native listen state: <redacted value>
```

### Required tests

1. unknown mode does not become clean Offer;
2. unknown listen state does not become Stopped;
3. future sentinel values become explicit error.

### Acceptance criteria

- [ ] No unknown mode → Offer fallback.
- [ ] No unknown listen-state → Stopped fallback.
- [ ] Schema drift is visible.

---

## P1-005 — Clear active remote peer on terminal state

### Files

Modify:

```text
TunnelRepository.kt
TunnelRepositoryTest.kt
```

### Minimum policy

When committed state is:

```text
Stopped
Error
PausedMeteredBlocked
NoNetwork
```

set:

```kotlin
remotePeerId = null
```

Retain last-known peer only while runtime is active/starting if that remains useful.

### Required test

```text
Connected with peer A
then Stopped
```

Assert no current peer is shown.

### Acceptance criteria

- [ ] Terminal state does not show stale active peer.
- [ ] Active state may retain peer between transient session updates.
- [ ] No history feature is added in this pass.

---

## P1-006 — Final false-success and silent-failure audit

### Lifecycle searches

```bash
rg -n \
  'serviceScope\.launch.*offer\.|repository\.start\(|repository\.stop\(|submitLifecycleCommand|PolicyBlocked|PolicyAllowed' \
  android/app/src/main
```

Classify every lifecycle owner.

### Start/stop truthfulness

```bash
rg -n \
  'onSuccess.*refreshStatus|StartStatusVerificationException|StopStatusVerificationException|pausedByPolicy\.set\(false\)' \
  android/app/src/main
```

### Policy state

```bash
rg -n \
  'allowMeteredForCurrentRun|isTunnelRunning\(|resumeOnUnmetered|pendingPolicyResume' \
  android/app/src/main
```

### Repository state domains

```bash
rg -n \
  'serviceState\s*=\s*ServiceState\.Error|recentLogs|logsError|remotePeerId\s*=' \
  android/app/src/main/java/com/phillipchin/webrtctunnel/data
```

### Forward mutation safety

```bash
rg -n \
  'forwardsRepository\.(save|upsert|delete)|loadError|revision|saveIfRevisionMatches' \
  android/app/src/main
```

### Test synchronization

```bash
rg -n \
  'withTimeoutOrNull|Thread\.sleep|waitForCondition|await\([^\n]*TimeUnit' \
  android/app/src/test/java/com/phillipchin/webrtctunnel
```

### Classification

For every relevant match:

```text
ordered lifecycle ownership
failure propagated
safe explicit default
expected teardown
best-effort and visible
hidden failure
false success
timing-based correctness proof
```

Fix the last three.

### Acceptance criteria

- [ ] No command-order inversion path remains.
- [ ] No unverified start success remains.
- [ ] No policy block ignores active startup.
- [ ] No ancillary error rewrites lifecycle state.
- [ ] No plain shared policy Boolean remains.
- [ ] No rollback can erase newer data.
- [ ] No load-error mutation bypass remains.
- [ ] No timeout proves event absence.
- [ ] Unknown native values do not become plausible defaults.

---

# P2 tasks

## P2-001 — Consider typed StartOutcome through JNI

Future work may expose:

```text
Started
AlreadyRunning
Rejected
TaskSpawnFailed
```

Do not implement unless Kotlin verification proves insufficient.

---

## P2-002 — Consider a dedicated lifecycle actor type

Future extraction:

```text
TunnelLifecycleCoordinator
```

may own:

```text
command queue
startup attempt
pending resume
verified stopped state
```

Do not extract during this pass unless `TunnelForegroundService` becomes unmaintainable.

---

## P2-003 — Consider structured forwards transaction receipts

Future repository API may return:

```text
MutationReceipt
RollbackReceipt
Conflict
```

The revision check in P1-002 is sufficient now.

---

# Required implementation sequence

Use this order:

```text
Stage 1 — lifecycle ordering
  P0-001 ordered command processor
  P1-001 atomic metered allowance

Stage 2 — start truthfulness
  P0-002 verified start + cleanup

Stage 3 — policy integrity
  P0-003 PolicyBlocked cancels startup
  P0-004 one-event resume retry

Stage 4 — lifecycle-domain separation
  P0-005 logs error separation
  P0-006 destroy/duplicate-stop cleanup

Stage 5 — deterministic proof
  P0-007 remove timing-based absence tests

Stage 6 — forward state integrity
  P1-002 revision-aware rollback
  P1-003 central load-error mutation block

Stage 7 — status schema truthfulness
  P1-004 strict unknown mapping
  P1-005 terminal remote-peer clearing

Stage 8 — final audit
  P1-006 silent-failure/false-success audit

Stage 9 — final signoff
  P0-008 local gates + remote CI
```

Recommended commits:

```text
fix(android): serialize lifecycle commands in accepted order
fix(android): verify native start before committing success
fix(android): make policy block cancel in-flight startup
fix(android): retain one pending policy-resume retry
fix(android): separate log retrieval errors from lifecycle state
fix(android): avoid redundant stop during service destruction
test(android): replace absence timeouts with event-order proof
fix(android): synchronize metered-session allowance
fix(android): make forwards rollback revision-aware
fix(android): block mutations while forwards load is failed
fix(android): reject unknown native status values explicitly
fix(android): clear stale active peer on terminal state
chore(hardening): complete final lifecycle false-success audit
```

Do not make one giant commit.

---

# Final completion checklist

## Lifecycle ordering

- [ ] Accepted lifecycle commands have one processing order.
- [ ] onStartCommand does not launch independent lifecycle coroutines.
- [ ] Network policy uses same ordering path.
- [ ] Queue is bounded and failure is visible.
- [ ] Later PAUSE/STOP cannot be undone by older START.

## Start truthfulness

- [ ] JNI start success requires verified active-or-starting state.
- [ ] Status read/decode failure is not clean start success.
- [ ] Error/Stopped is not clean start success.
- [ ] Verification failure cleanup has one owner.
- [ ] Cleanup failure preserves original start failure.
- [ ] Policy retry state clears only after verified success.

## Policy integrity

- [ ] PolicyBlocked cancels and joins startup.
- [ ] Policy block does not depend only on UI lifecycle state.
- [ ] One later PolicyAllowed event is enough to retry.
- [ ] Pending retry is superseded by later Pause/Stop/Block.
- [ ] Metered-session allowance is thread-safe and ordered.

## Lifecycle-domain separation

- [ ] Log retrieval failure does not change tunnel lifecycle state.
- [ ] Log error is separately visible.
- [ ] Normal ACTION_STOP causes one native stop.
- [ ] onDestroy fallback still cleans unverified runtime.
- [ ] Network monitor is joined before destroy cleanup.

## Test trust

- [ ] No timeout is used to prove absence.
- [ ] No ignored settle wait remains.
- [ ] Poll quiescing is proved by event order.
- [ ] Exactly-one-stop is asserted after command completion.
- [ ] Reverted old behavior fails deterministically.

## Forward state integrity

- [ ] Rollback cannot overwrite newer mutation.
- [ ] Revision mismatch is visible.
- [ ] loadError blocks every mutation path.
- [ ] Successful reload re-enables mutation.

## Native status truthfulness

- [ ] Unknown mode is explicit error.
- [ ] Unknown listen state is explicit error.
- [ ] Terminal state clears active remote peer.

## Final signoff

- [ ] Focused Android tests pass repeatedly.
- [ ] Full Android gates pass.
- [ ] Rust gates pass.
- [ ] Service/package gates pass.
- [ ] Remote CI ran after all production changes.
- [ ] Workflow SHA matches final code or one docs-only child.
- [ ] No earlier workflow reused.
- [ ] Every unavailable check is `NOT RUN` with exact reason.




