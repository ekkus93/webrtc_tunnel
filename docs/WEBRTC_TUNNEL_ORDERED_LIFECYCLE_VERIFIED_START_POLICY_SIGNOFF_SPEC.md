# WebRTC Tunnel Ordered Lifecycle, Verified Start, and Policy-Integrity Release-Signoff Specification

## 0. Document purpose

This specification applies to:

```text
webrtc_tunnel-master_2607062147.zip
```

It is a corrective release-signoff pass after review of:

```text
WEBRTC_TUNNEL_SINGLE_OWNER_STOP_STATE_INTEGRITY_SIGNOFF_TODO(1).md
```

The latest snapshot has materially improved:

- cancelled startup no longer performs an independent competing native stop;
- explicit lifecycle stop cancels and joins startup;
- `TunnelRepository` state changes use atomic compare-and-set updates;
- native stop success requires verified final `Stopped`;
- rollback persistence failure is visible;
- the production test-event queue is removed;
- initial forwards-load failure is visible in the main UI;
- `lastCleanupError` is redacted;
- corrupt setup drafts fail visibly and non-destructively;
- CI was observed after the final production-code commit.

Those changes are preserved.

This specification addresses the remaining release-signoff problems:

1. lifecycle commands can execute in a different order than Android delivered them;
2. native start success is not verified;
3. network-policy blocking can miss an in-flight startup;
4. one legitimate later auto-resume event can be lost;
5. log retrieval failure incorrectly changes tunnel lifecycle state to `Error`;
6. `allowMeteredForCurrentRun` is shared across threads without explicit synchronization;
7. `allowMeteredForSessionAndStart()` is a two-step lifecycle transaction;
8. normal `ACTION_STOP` can cause a second native stop during `onDestroy()`;
9. `onDestroy()` does not join the network monitor before fallback cleanup;
10. forwards rollback can overwrite a newer concurrent mutation;
11. later forwards-load failure does not centrally block every mutation path;
12. required tests still use elapsed-time absence proofs;
13. unknown native mode/listen-state values become plausible-looking defaults;
14. active remote-peer display can retain stale terminal-state data.

The central theme is:

> Lifecycle intention, native runtime state, policy state, and user-visible state must have one ordered source of truth.

---

# 1. Non-negotiable invariants

## 1.1 Accepted lifecycle commands have a defined total order

The service receives multiple lifecycle intentions:

```text
START
PAUSE
RESUME
STOP
ALLOW_METERED_SESSION
POLICY_BLOCKED
POLICY_ALLOWED
```

Today, each intent can become an independent coroutine.

That permits:

```text
START arrives
PAUSE arrives later

PAUSE coroutine runs first
START coroutine runs second

final state = running
```

The required invariant is:

```text
accepted command order
    =
lifecycle processing order
```

A later command may intentionally supersede or cancel work started by an earlier command, but an older queued command may not run after a newer command and undo it.

---

## 1.2 One lifecycle coordinator owns state-changing native operations

The following operations must not be invoked by unrelated competing owners:

```text
repository.start(...)
repository.stop()
status-poll start/stop
policy pause/resume
temporary metered allowance transition
service-stop transition
```

The command processor is the ordering layer.

The existing lifecycle mutex may remain as a safety boundary during migration, but it must not become a second independent scheduler.

---

## 1.3 Native start success is not trusted without runtime verification

Stop already follows this rule:

```text
JNI stop success
        ↓
read final native status
        ↓
verify Stopped
        ↓
only then report success
```

Start must become symmetric:

```text
JNI start success
        ↓
read native status
        ↓
verify active-or-starting state
        ↓
only then commit start success
```

Forbidden:

```text
JNI start success
status decode fails
repository publishes Error
start() still returns success
service clears retry state
```

---

## 1.4 Start-verification failure must not leave an unowned native runtime

JNI start may succeed even when Kotlin cannot verify the resulting native state.

Therefore:

```text
start verification failed
```

does not necessarily mean:

```text
nothing is running
```

The lifecycle coordinator must perform authoritative cleanup.

Do not make the startup worker call `repository.stop()` independently.

Required policy:

```text
start worker returns verification failure
        ↓
ordered lifecycle coordinator owns cleanup
        ↓
later user STOP/PAUSE may supersede cleanup
        ↓
no competing native stop callers
```

---

## 1.5 Policy blocking is fail-safe during startup

A disallowed network must not be ignored merely because the UI state is currently:

```text
Stopped
Starting
Connecting
Reconnecting
Error
```

while native startup work may still be active.

Required policy:

```text
policy becomes disallowed
        ↓
ordered PolicyBlocked command
        ↓
cancel and join startup
        ↓
quiesce status polling
        ↓
verified stop if runtime may exist
        ↓
commit policy-paused state only after successful cleanup
```

Do not gate policy enforcement solely on `isTunnelRunning()`.

---

## 1.6 One real policy-allowed event must be sufficient

The system may not depend on repeatedly receiving identical connectivity callbacks.

Required:

```text
resume attempt fails
        ↓
pausedByPolicy remains true

one later allowed-network event arrives
        ↓
one retry intention is retained
        ↓
retry occurs after previous attempt fully completes
```

The implementation may use a pending-resume flag inside the lifecycle coordinator.

The test may not repeatedly refire the same event until success.

---

## 1.7 Ancillary failures must not rewrite lifecycle truth

Examples of ancillary operations:

```text
log retrieval
diagnostics export
UI-only data fetch
```

A log decode failure means:

```text
logs could not be retrieved
```

It does not mean:

```text
the tunnel runtime failed
```

Required:

```text
runtime lifecycle state
    remains runtime lifecycle state

logs error
    stored separately
```

---

## 1.8 Cleanup after ACTION_STOP is exactly once unless the first cleanup failed

Normal sequence:

```text
ACTION_STOP
        ↓
verified native stop succeeds
        ↓
stopSelf()
        ↓
onDestroy()
```

`onDestroy()` must not perform another native stop merely because destruction occurred.

Fallback cleanup is required only when native stop has not already been verified.

---

## 1.9 Rollback may not erase a newer mutation

Current rollback shape:

```text
before snapshot
        ↓
mutation A
        ↓
validation blocks
        ↓
mutation B
        ↓
A fails
        ↓
write old before snapshot
```

This can erase B.

Required:

```text
rollback is conditional on repository revision
```

If revision changed:

```text
do not overwrite newer data
surface concurrent-modification rollback failure
```

---

## 1.10 Tests prove ordering with events, not elapsed absence

Forbidden:

```text
wait 500 ms
no event observed
therefore event cannot happen
```

Forbidden:

```text
wait 3 seconds
second stop did not appear
therefore there is exactly one stop
```

Required proof:

```text
event A
event B
event C

assert index(A) < index(B) < index(C)
```

or:

```text
await command completion
assert exact call count
```

No correctness argument may depend on scheduler speed.

---

# 2. Ordered lifecycle-command architecture

## 2.1 Use one bounded command queue

Recommended:

```kotlin
private const val LIFECYCLE_COMMAND_CAPACITY = 32

private val lifecycleCommands =
    Channel<LifecycleEnvelope>(
        capacity = LIFECYCLE_COMMAND_CAPACITY,
    )
```

Do not use `Channel.UNLIMITED`.

Do not silently drop commands.

---

## 2.2 Give each accepted command a sequence

Recommended:

```kotlin
private data class LifecycleEnvelope(
    val sequence: Long,
    val command: LifecycleCommand,
)

private val nextLifecycleSequence =
    AtomicLong(0)
```

Submission:

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

Sequence is useful for:

- tests;
- diagnostics;
- proving command order.

---

## 2.3 Commands

Recommended shape:

```kotlin
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
```

Do not put test-only variants in the production command type.

---

## 2.4 onStartCommand becomes submission-only

Target:

```kotlin
override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
): Int {
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

    return START_STICKY
}
```

Do not launch one independent coroutine per command.

---

## 2.5 Network policy also submits commands

Forbidden:

```kotlin
serviceScope.launch {
    offer.pauseForPolicy(...)
}
```

from the monitor callback.

Required:

```kotlin
if (policy.tunnelAllowed) {
    submitLifecycleCommand(
        LifecycleCommand.PolicyAllowed,
    )
} else {
    submitLifecycleCommand(
        LifecycleCommand.PolicyBlocked(
            reason =
                policy.blockReason
                    ?: "Network policy blocked tunnel",
        ),
    )
}
```

Now policy and user commands participate in the same ordered lifecycle stream.

---

# 3. Command processor behavior

## 3.1 Processor

Recommended:

```kotlin
private val commandProcessorJob =
    serviceScope.launch {
        processLifecycleCommands()
    }
```

```kotlin
private suspend fun processLifecycleCommands() {
    for (envelope in lifecycleCommands) {
        handleLifecycleCommand(envelope)
    }
}
```

If current startup completion must be observed concurrently with new commands, use the existing startup job plus explicit pending state.

Do not block the processor for the full lifetime of a long-running tunnel.

---

## 3.2 Start commands initiate startup work and return to the processor

The command processor should:

```text
accept StartOffer
        ↓
establish new generation/intention
        ↓
launch one startupJob
        ↓
return to command loop
```

This allows a later PAUSE/STOP to cancel that startup.

The actor must not await the entire tunnel lifetime.

---

## 3.3 Later commands cancel earlier startup in order

Example:

```text
sequence 10: StartOffer
sequence 11: Pause
```

Required:

```text
handle 10
    → start startupJob

handle 11
    → cancel/join startupJob
    → verified stop
    → paused state
```

The older start may not execute after pause.

---

# 4. Verified start

## 4.1 Add start verification exception

Recommended:

```kotlin
class StartStatusVerificationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(
    message,
    cause,
)
```

---

## 4.2 Start success states

A verified start may return success only when the committed status is one of the application's legitimate active-or-starting states.

Prefer the existing helper:

```kotlin
status.serviceState
    .isTunnelActiveOrStarting()
```

Review the helper and ensure it includes exactly the intended set:

```text
Starting
Connecting
Reconnecting
Listening
Serving
Connected
```

Do not include:

```text
Stopped
Error
PausedMeteredBlocked
NoNetwork
```

---

## 4.3 Repository start implementation

Target:

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

---

## 4.4 Cleanup after unverified start

The startup worker must not call native stop independently.

Required coordination:

```text
startup result =
    StartStatusVerificationException
        ↓
startup job completes
        ↓
coordinator handles failure for current generation
        ↓
verified stop cleanup
```

If a later PAUSE/STOP command already advanced lifecycle intention and owns cleanup:

```text
stale start completion
    → no extra stop
```

Preserve both errors:

```text
original start-verification failure
cleanup failure
```

Suggested message:

```text
Native startup could not be verified.
Cleanup also failed: <redacted reason>
```

---

# 5. Policy-block integrity

## 5.1 PolicyBlocked always cancels startup

Do not use:

```kotlin
if (
    repository.status.value
        .serviceState
        .isTunnelRunning()
) {
    ...
}
```

as the gate.

Required:

```kotlin
private suspend fun handlePolicyBlocked(
    reason: String,
) {
    pendingPolicyResume = false

    lifecycleGeneration.incrementAndGet()

    cancelStartupJobAndJoin()
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
                code = stopFailureCode(error),
            )
        },
    )
}
```

Calling verified stop when already stopped is acceptable:

```text
native NotRunning
+
verified final Stopped
=
success
```

---

## 5.2 PolicyAllowed retains one pending retry intention

Coordinator state:

```kotlin
private var pendingPolicyResume = false
```

This field should be owned only by the command processor.

Behavior:

```text
PolicyAllowed
pausedByPolicy == false
    → no-op

PolicyAllowed
pausedByPolicy == true
no startup active
    → start one resume attempt

PolicyAllowed
pausedByPolicy == true
startup active
    → pendingPolicyResume = true
```

When the active startup attempt finishes:

```text
success
    → pausedByPolicy = false
    → pendingPolicyResume = false

failure
    → pausedByPolicy remains true
    → if pendingPolicyResume:
          clear pending flag
          start exactly one retry
```

A PAUSE, STOP, START_OFFER, or PolicyBlocked command must clear stale pending resume intention as appropriate.

---

# 6. Metered-session allowance

## 6.1 Synchronize shared allowance state

Replace:

```kotlin
private var allowMeteredForCurrentRun = false
```

with:

```kotlin
private val allowMeteredForCurrentRun =
    AtomicBoolean(false)
```

Use:

```kotlin
allowMeteredForCurrentRun.get()
allowMeteredForCurrentRun.set(true)
allowMeteredForCurrentRun.set(false)
```

---

## 6.2 Make AllowMeteredSession one ordered transaction

Forbidden:

```text
lock
set allowance
unlock

later:
startOffer()
```

Required command handling:

```text
AllowMeteredSession command
        ↓
set allowance
        ↓
update repository policy state
        ↓
begin startup before processing later command
```

The processor may then move to the next command.

A later PAUSE/STOP cancels that startup normally.

---

# 7. Ancillary error separation

## 7.1 recentLogs must not change tunnel lifecycle state

Current bad model:

```text
log decode failure
        ↓
serviceState = Error
```

Required:

```kotlin
private val _logsError =
    MutableStateFlow<TunnelError?>(null)

val logsError: StateFlow<TunnelError?> =
    _logsError.asStateFlow()
```

On failure:

```kotlin
_logsError.value =
    TunnelError(
        code = "log_decode_failed",
        message = "Native log retrieval failed",
        details =
            SensitiveDataRedactor.redactText(
                error.message
                    ?: "unknown log retrieval error",
            ),
    )
```

Keep the synthetic visible log entry if useful.

Do not modify:

```text
serviceState
mqttConnected
activeSessionCount
```

because log retrieval failed.

On successful retrieval:

```kotlin
_logsError.value = null
```

---

# 8. Destruction and duplicate stop

## 8.1 Track verified native-stop state

Recommended:

```kotlin
private val nativeStopVerified =
    AtomicBoolean(true)
```

Set:

```text
new startup attempt begins
    → false

verified stop succeeds
    → true
```

Do not set true merely because JNI returned success; only after repository stop verification.

---

## 8.2 ACTION_STOP

Required:

```text
ordered Stop command
        ↓
cancel/join startup
        ↓
stop/join poll
        ↓
verified repository.stop()
        ↓
nativeStopVerified = true
        ↓
stopSelf()
```

---

## 8.3 onDestroy fallback

Required:

```text
cancel network monitor
join network monitor in cleanup coroutine
        ↓
if nativeStopVerified == true
    → skip native stop

else
    → cancel/join startup
    → stop/join polling
    → verified native stop
```

Do not generate a fresh error after a previously verified clean STOP merely because a redundant second status read failed.

---

# 9. Forward mutation revisioning

## 9.1 Repository revision

Recommended:

```kotlin
private var revision: Long = 0
```

The repository mutex owns it.

On every successful persistence-changing mutation:

```kotlin
revision += 1
```

Expose snapshot:

```kotlin
data class ForwardsSnapshot(
    val forwards: List<ForwardConfig>,
    val revision: Long,
)
```

```kotlin
suspend fun snapshot(): ForwardsSnapshot =
    mutex.withLock {
        ForwardsSnapshot(
            forwards = _forwards.value,
            revision = revision,
        )
    }
```

---

## 9.2 Conditional rollback

Add:

```kotlin
class ForwardsRevisionMismatchException(
    expected: Long,
    actual: Long,
) : IllegalStateException(
    "Forwards changed concurrently; " +
        "expected revision $expected " +
        "but found $actual",
)
```

```kotlin
suspend fun saveIfRevisionMatches(
    expectedRevision: Long,
    forwards: List<ForwardConfig>,
): Result<Unit> =
    mutex.withLock {
        if (revision != expectedRevision) {
            return@withLock Result.failure(
                ForwardsRevisionMismatchException(
                    expectedRevision,
                    revision,
                ),
            )
        }

        runCatching {
            store.saveForwards(forwards)
            _forwards.value = forwards
            revision += 1
        }
    }
```

The mutation operation should return the revision after the successful mutation so rollback can target exactly that version.

---

## 9.3 Rollback message

If revision mismatch prevents rollback:

```text
Activation failed.
Automatic rollback was skipped because forwards changed again.
The newer changes were left untouched.
```

Do not overwrite newer data.

---

# 10. Load-error mutation block

## 10.1 Central rule

When:

```text
loadError != null
```

the repository must reject all mutations.

Do not rely only on disabling Add in one screen.

Required:

```kotlin
private fun ensureMutationAllowed(): Result<Unit> {
    val loadFailure = _loadError.value

    if (loadFailure != null) {
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

Apply to:

```text
upsert
delete
save
setup-controller mutation paths
details-screen mutation paths
```

Successful reload clears the block.

---

# 11. Strict native enum/string mapping

## 11.1 Unknown mode

Forbidden:

```kotlin
else -> TunnelMode.Offer
```

Preferred policy without adding a new enum:

```text
retain previous display mode only because model requires a value
AND
set serviceState = Error
AND
set lastError = native_status_schema_error
```

Unknown mode must never look like a valid Offer runtime.

---

## 11.2 Unknown listen state

Forbidden:

```text
unknown + no lastError
    → Stopped
```

Required:

```text
unknown
    → ListenState.Error
    → explicit configuration/runtime status error
```

Add sentinel tests for future/garbage values.

---

# 12. Active remote peer truthfulness

Current:

```text
remotePeerId ?: previous.remotePeerId
```

may retain a peer after terminal state.

Required minimum:

```text
Stopped
Error
policy-paused terminal state
    → active remotePeerId = null
```

If product wants last-known peer history, model separately later.

Do not present stale historical peer identity as current.

---

# 13. Test architecture

## 13.1 No elapsed-time absence proof

Remove correctness dependencies on:

```text
withTimeoutOrNull(500) { await event }
waitForCondition(3_000) { second call appears }
```

Timeouts are acceptable only to fail a test that is otherwise waiting for an expected positive event.

---

## 13.2 Test event recorder

Use test-fake events:

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
private val events =
    CopyOnWriteArrayList<FakeLifecycleEvent>()
```

After operation completes:

```kotlin
assertTrue(
    events.indexOf(
        FakeLifecycleEvent.StatusReadReleased,
    ) <
        events.indexOfFirst {
            it is FakeLifecycleEvent.StopEntered
        },
)
```

No negative waiting.

---

## 13.3 Exactly-one-stop proof

Because the explicit lifecycle path now cancels and joins startup:

```text
pause command completed
```

means:

```text
cancelled startup completed
+
authoritative stop completed
```

Therefore:

```kotlin
awaitFinalPauseResult()

assertEquals(
    1,
    bridge.stopCalls,
)
```

No 3-second settle wait.

A reverted independent cancellation cleanup must produce two calls before final command completion.

---

# 14. Error-code policy

Recommended additions:

```text
start_status_verification_failed
start_verification_cleanup_failed
lifecycle_command_queue_failed
policy_stop_failed
log_decode_failed
forwards_revision_mismatch
forwards_mutation_blocked_by_load_error
native_status_schema_error
```

Do not collapse unrelated failures into `unknown error`.

Do not expose raw secrets.

---

# 15. Scope boundaries

Do not:

- redesign Rust daemon architecture;
- change signaling wire format;
- change cryptographic identity format;
- change authorized-peer semantics;
- add `sd_notify`;
- add daemon/fork/PID-file behavior;
- add hidden global timeouts;
- add unbounded lifecycle queues;
- add test-only unbounded channels to production;
- rewrite all Android repositories;
- build a generic actor framework;
- add a full database transaction layer for forwards.

This pass is limited to lifecycle ordering, verified startup, policy integrity, and the named state-truthfulness issues.

---

# 16. Completion definition

Release signoff requires all of the following:

```text
accepted lifecycle commands process in defined order
later pause/stop cannot be undone by older queued start
start success requires verified active-or-starting state
unverified native start is cleaned up by the coordinator
policy block cancels in-flight startup
one later policy-allowed event is enough to retry
log retrieval failure does not rewrite tunnel lifecycle
metered-session allowance is synchronized and ordered
normal ACTION_STOP does not trigger redundant onDestroy stop
network monitor is joined before fallback destroy cleanup
rollback never overwrites a newer forwards mutation
load error blocks every forward mutation path
required tests use event ordering rather than absence timeouts
unknown native status values fail explicitly
final CI runs after every production change
```
