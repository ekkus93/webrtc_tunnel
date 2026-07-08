# Responses: Lifecycle Coordinator, Verified-Start Cleanup, and Rollback-Integrity Spec/TODO Review

## Q: Implementation baseline
The TODO references `webrtc_tunnel-master2607072301.zip` — should I assume I'm working against the current codebase state (where some P0-001 code already exists) or a clean baseline?

A: Work against the **current codebase state represented by the latest snapshot/repository**, not a clean pre-P0 baseline.

Some P0-001-era code already exists. Preserve working improvements and modify them in place.

Required rule:

```text
existing implementation
    → inspect
    → keep correct parts
    → replace incorrect parts
```

Do **not**:

- re-add fields or helpers that already exist;
- create a second lifecycle queue;
- create a second `cleanupUnverifiedStart()`;
- revert working verified-start or verified-stop logic just to match the snippets literally.

The TODO snippets describe the desired end state and may overlap with code already present.

Before adding any symbol, search for it and reuse the existing implementation where possible.
## Q: Destroy sequence
The spec says processor should be joined before startup (§5.3), but current code does the opposite. Which sequence is correct for P0-004?

A: Use the specification's order for P0-004:

```text
1. stop accepting new lifecycle commands
2. close lifecycle command input
3. cancel and join network monitor
4. cancel and join command processor
5. cancel and join startupJob
6. stop and join status polling
7. perform verified fallback stop if nativeStopVerified == false
```

The reason the processor must stop before `startupJob` cleanup is that the processor can create, replace, cancel, or otherwise act on `startupJob`. Destruction must first remove that competing owner.

If the processor is currently waiting for `startupJob`, cancelling the processor should cancel that wait. After the processor is fully joined, destruction owns `startupJob` exclusively and can cancel-and-join it safely.

Do not keep the current opposite ordering merely because it already exists.

Before implementing, check for a concrete deadlock cycle. The required invariant is:

```text
after commandProcessorJob is joined,
no code can create or replace startupJob
```

Then destruction may safely clean up the remaining startup job.
## Q: `StartupCompletion` integration
The existing implementation has `cleanupUnverifiedStart()` but the TODO shows adding it as new. Should I fix the existing implementation or add new functionality alongside it?

A: Fix the existing implementation in place.

Do not add a second `cleanupUnverifiedStart()` or a second startup-completion system alongside the current one.

Required steps:

1. Find the current `cleanupUnverifiedStart()`.
2. Compare it against P0-001.
3. Correct its ownership, error preservation, `nativeStopVerified` updates, and call sites.
4. Ensure it is invoked only for current-generation `StartStatusVerificationException`.
5. Ensure a later lifecycle command can supersede it without producing a second competing stop.

The TODO says "add" because it describes required behavior, not because a duplicate function is desired.
## Q: `pendingPolicyResume` ownership
The spec wants coordinator ownership, but current implementation has it accessible from multiple coroutines. Should I refactor to make it coordinator-owned immediately or phase it?

A: Refactor this now; do not defer the correctness fix to a later pass.

However, do not force a large actor rewrite merely to change the field type.

For this pass, use **coordinator-owned semantics**:

```text
external lifecycle commands
    → processor owns setting/clearing pendingPolicyResume

startup completion
    → may only atomically consume the pending flag
    → may only submit RetryPolicyResume
    → must never call offer.resume(), start(), or stop() directly
```

A practical implementation may keep:

```kotlin
private val pendingPolicyResume =
    AtomicBoolean(false)
```

because startup completion currently occurs on a separate coroutine.

Allowed outside the processor:

```kotlin
if (
    pendingPolicyResume
        .compareAndSet(true, false)
) {
    submitLifecycleCommand(
        LifecycleCommand.RetryPolicyResume,
    )
}
```

Forbidden outside the processor:

```kotlin
offer.resume()
repository.start(...)
repository.stop()
```

The processor must clear stale pending retry when it handles:

```text
Pause
Stop
StartOffer
PolicyBlocked
AllowMeteredSession
```

This gives coordinator-owned behavior without requiring a large actor extraction in the same pass.
## Q: P1-001 scope
The forwards mutation receipt system (P1-001 through P1-004) is a major architectural change. Should this be in the same release or deferred to a separate pass?

A: Keep P1-001 through P1-004 in this release-signoff pass.

They fix a real data-loss/integrity problem:

```text
rollback can erase a newer forwards mutation
```

That is not cosmetic cleanup.

Implement them after all P0 lifecycle blockers, exactly as the dependency order says.

It is acceptable to split the work into separate small commits, but not to defer it while still claiming release signoff.

Policy:

```text
P0 lifecycle blockers complete
        ↓
P1 forwards transaction integrity complete
        ↓
final signoff
```

If the forwards receipt work is deferred, release signoff remains incomplete.
## Q: Test infrastructure
P0-008 requires `FakeLifecycleEvent` and test infrastructure. Should I assume these exist or create them as part of this TODO?

A: Extend the existing test infrastructure; do not assume a blank test harness and do not create a second parallel event system.

The current code already has test-side lifecycle/status event recording from the previous pass.

In:

```text
TunnelForegroundServiceTestFakes.kt
```

find the existing test event type and recorder.

Reuse/extend it with the missing boundaries, for example:

```kotlin
data class CommandStarted(
    val sequence: Long,
    val name: String,
) : FakeLifecycleEvent

data class CommandCompleted(
    val sequence: Long,
    val name: String,
) : FakeLifecycleEvent

data object StatusReadReleased :
    FakeLifecycleEvent
```

If the exact existing type has a different name, extend that type instead of creating `FakeLifecycleEvent` in parallel.

The required result is one test-only event stream that can prove:

```text
StatusReadReleased < StopEntered

CommandStarted < CommandCompleted

CommandCompleted
    → final stopCalls assertion
```
## Q: P0-003 retry timing
Should the retry start before or after the old startup's cleanup completes? The acceptance criterion says "old startup fully completes before retry begins" but current implementation checks `pendingPolicyResume` immediately after startup completion.

A: The retry starts **after the old startup attempt is fully complete**.

For an ordinary native start failure:

```text
startup result finalized
startupJob ownership cleared
        ↓
pending retry may be consumed
        ↓
RetryPolicyResume submitted
```

For a `StartStatusVerificationException`, "fully complete" also includes mandatory cleanup:

```text
verification failure
        ↓
coordinator-owned verified stop cleanup
        ↓
cleanup result finalized
        ↓
only then may a still-valid pending retry be considered
```

If cleanup fails:

```text
DO NOT auto-retry
```

because the native runtime may still exist.

Required ordering:

```text
old attempt complete
old cleanup complete if required
startupJob no longer active/owned
        ↓
check pending retry still valid
        ↓
submit exactly one RetryPolicyResume
```

The retry must never begin while the old startup job or its cleanup is still active.
## Q: P0-008 old behavior restoration
The test scenario for "reverted old behavior fails deterministically" requires temporarily restoring old behavior to prove the test catches it. What specific old behavior should be restored?

A: Use two specific temporary regressions, one for each proof.

### A. Stale-poll ordering regression

Temporarily restore the old broken behavior by making status-poll shutdown cancel without joining, or by allowing native stop to enter before the blocked status read is released.

For example, temporarily replace:

```kotlin
job?.cancelAndJoin()
```

with:

```kotlin
job?.cancel()
```

The test asserting:

```text
StatusReadReleased < StopEntered
```

must fail.

### B. Exactly-one-stop regression

Temporarily restore the old competing cancelled-startup cleanup stop.

For example, add the previous independent:

```kotlin
repository.stop()
```

back into the startup cancellation cleanup path.

After the PAUSE command reaches `CommandCompleted`, the test:

```kotlin
assertEquals(
    1,
    bridge.stopCalls,
)
```

must fail because two stop calls occurred.

These are temporary local regression checks only. Never commit the reverted behavior.
## Q: P1-005 mode field behavior
Should the mode field show the previous value or a default value when an unknown native mode is encountered?

A: Retain the **previous/current mode value**, not a newly invented default.

The data model requires a `TunnelMode`, so on unknown native mode:

```text
mode field
    → previous mode value only as a structural fallback

serviceState
    → Error

lastError.code
    → native_status_schema_error
```

Example:

```text
previous mode = Answer
native mode = future_mode_v99
        ↓
display/model mode remains Answer
BUT
runtime state is Error
schema error is visible
```

Do not change unknown mode to `Offer`.

The retained previous mode is not considered valid native truth; it merely satisfies the non-null model shape while the explicit Error state communicates that the status schema is invalid.
## Q: Cross-cutting — Destroy sequence consistency
The spec's destroy order (§5.3) differs from current implementation. Current code joins startup before processor, spec says processor before startup. Which sequence is correct and does it affect P0-004 implementation?

A: The specification order is correct:

```text
stop intake
close channel
join network monitor
join/cancel command processor
join/cancel startup
join status polling
fallback verified stop
```

This is the same answer as the earlier destroy-sequence question.

The cross-cutting reason is ownership:

```text
command processor alive
    → it may still create/replace/cancel startupJob

command processor joined
    → destruction has exclusive ownership of startupJob
```

Therefore startup cleanup must come after processor shutdown.

P0-004 should change the current implementation to this order.

If implementation reveals a concrete deadlock cycle, do not silently reverse the sequence. Document the cycle and restructure the wait/ownership so the invariant still holds:

```text
no command processor can create lifecycle work
before destruction cleans startup/native runtime
```
## Q: Cross-cutting — `pendingPolicyResume` state management
Multiple tasks (P0-002, P0-003, P0-004) touch this flag. The spec wants coordinator ownership but current implementation has it accessible from multiple coroutines. How should this be refactored?

A: Apply one coordinated refactor now.

Use this ownership table:

```text
Lifecycle processor:
    sets pending retry
    clears pending retry
    clears stale retry on superseding commands
    handles RetryPolicyResume

Startup completion:
    may atomically consume pending retry
    may submit RetryPolicyResume
    may not directly start/resume/stop

Network monitor:
    submits PolicyAllowed/PolicyBlocked only
    never touches pendingPolicyResume directly
```

For the current architecture, keeping `pendingPolicyResume` as `AtomicBoolean` is acceptable.

Required operations:

```kotlin
// Processor: record one pending retry.
pendingPolicyResume.set(true)

// Superseding processor command:
pendingPolicyResume.set(false)

// Startup completion, after full completion/cleanup:
if (
    pendingPolicyResume
        .compareAndSet(true, false)
) {
    submitLifecycleCommand(
        LifecycleCommand.RetryPolicyResume,
    )
}
```

Do not phase the direct `offer.resume()` call. Remove that bypass in this pass.
## Q: Cross-cutting — Test infrastructure gaps
P0-008's test boundary fixes require `FakeLifecycleEvent` and test infrastructure that might not exist. Should these be created as part of this TODO or are they already present?

A: Create the missing pieces as part of this TODO, but extend the existing test-only infrastructure rather than adding a second system.

The current test suite already has lifecycle/status event recording.

Required approach:

```text
find existing event type
find existing event recorder
extend them
```

Add only missing events such as:

```text
StatusReadReleased
CommandStarted
CommandCompleted
```

Do not add production test channels.

Do not add sleep-based alternatives because an event is inconvenient.

P0-008 explicitly includes the test-infrastructure work needed to make its proof possible.
## Q: Cross-cutting — Security considerations
The spec handles `StartStatusVerificationException` and cleanup failures but doesn't explicitly address security implications of unowned native runtimes or failed cleanup scenarios. Should security implications be documented or addressed in implementation?

A: Address the security implications in the implementation and document them briefly in code/TODO evidence.

An unverified-start cleanup failure is a security and policy-integrity condition because:

```text
UI may believe startup failed
native tunnel may still be alive
network policy may be violated
user may think the tunnel is stopped
```

Required behavior when cleanup fails:

```text
publish:
    start_verification_cleanup_failed

nativeStopVerified:
    false

normal success/policy-paused state:
    DO NOT publish

automatic policy retry:
    DO NOT run

temporary metered allowance:
    clear unless an explicitly documented recovery path requires otherwise

service:
    remain visibly Error/foreground as appropriate so the user can retry Stop
    and destroy-time fallback cleanup can still run
```

Preserve both the verification failure and cleanup failure in the visible diagnostic message, with sensitive text redacted.

Do not claim `Stopped`, `Paused`, or successful cleanup.

No separate threat-model document is required for this pass, but add a concise code comment near the failure handling explaining that cleanup failure means native runtime existence is uncertain and automatic restart is prohibited.
---

Please fill in the `A:` lines above and share back when ready. Implementation will begin after responses are received.