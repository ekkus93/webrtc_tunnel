# Responses: WEBRTC_TUNNEL_COORDINATOR_COMPLETION_RUNTIME_QUARANTINE_FINAL_SIGNOFF (Spec + TODO Review)

## Questions for the User

1. P0-001 architecture: Is the `StartupCompleted` lifecycle command a full replacement for the current startup completion flow, or an incremental addition? The current code handles startup completion inline in `runOfferStart()`.
A: Treat `StartupCompleted` as the **replacement for the current inline completion-decision flow**, implemented by refactoring the existing code in place.

Keep the current startup preparation and `repository.start()` work, but move these decisions out of `runOfferStart()`:

```text
verified success handling
verification-failure cleanup
ordinary failure handling
pending-retry consumption
final lifecycle success/error publication
```

The worker should end with:

```text
classify result
        ↓
submit StartupCompleted(generation, completion)
        ↓
return
```

The lifecycle processor then owns the meaning of that completion.

Do not add a second completion flow alongside the current inline one. Reuse the existing `StartupCompletion`, `classifyStartupResult()`, and cleanup helpers.
2. P0-004 vs P0-005 coordination: Runtime quarantine blocks automatic restart, but failed STOP keeps the service alive. Should quarantine clear after a verified STOP, or remain until explicitly cleared?
A: Quarantine clears **only after a verified successful STOP** proves native runtime absence.

Required state transition:

```text
cleanup failure
        ↓
nativeRuntimeUncertain = true

later STOP fails
        ↓
remain quarantined

later STOP succeeds
and final native state verifies Stopped
        ↓
nativeRuntimeUncertain = false
```

It should not require a separate manual "clear quarantine" action.

Quarantine must not clear merely because:

```text
time passed
a start succeeded
the service restarted
status polling resumed
the UI changed state
```

The verified STOP is the recovery proof.
3. P0-007 behavioral change: The spec says "Do not clear on verified startup success." This changes the current behavior where metered allowance is cleared on success. Should there be a migration path or is this a breaking change?
A: This is an intentional correctness fix, not a migration feature.

The current behavior is wrong:

```text
Allow Metered for This Run
        ↓
start succeeds
        ↓
allowance immediately cleared
```

The new behavior should be:

```text
Allow Metered for This Run
        ↓
start succeeds
        ↓
allowance remains active for that run
```

No backward-compatibility migration is needed.

Add tests for the changed semantics and ensure the UI continues to show the allowance as active while the run is active.
4. P1-005 vs P1-006: Forwards reset vs partial reset reporting. Should `resetForwards()` report partial success/failure, or should partial reporting be a separate concern?
A: `resetForwards()` should report the result of the **forwards-reset stage only**.

Its contract should be simple and atomic:

```text
persist empty forwards
publish empty repository state
clear loadError
advance revision
        ↓
Result<Unit>
```

Partial reset reporting is a separate orchestration concern in `SettingsViewModel`.

Required layering:

```text
ForwardsRepository.resetForwards()
    → one stage result

SettingsViewModel.resetConfiguration()
    → combines config/setup/forwards stage results
    → reports partial completion
```

Do not make `ForwardsRepository` understand the other reset stages.
5. P1-007 config write serialization: Current code may use `config.toml.tmp`. Should this be changed to a unique temp path per write, or is a shared mutex sufficient?
A: Use **both**:

```text
one shared write mutex
+
unique temp path per write
```

The mutex prevents concurrent writers inside the process.

The unique temp path prevents one write from accidentally reusing another write's temp artifact and is safer for interrupted/stale temp files.

Preferred implementation is platform/library temp-file creation in the target directory, for example:

```kotlin
Files.createTempFile(
    configPath.parent,
    "config.toml.tmp-",
    ".partial",
)
```

Do not hand-roll UUID or counter names unless the existing filesystem abstraction requires it.

The final atomic move must still go through the one serialized writer.
6. P1-013 auto-resume: Initially policy-blocked startup should become auto-resumable. Does this add a retry mechanism, or does it convert the blocked startup to a pending retry?
A: Do not add a second retry loop.

An initially policy-blocked start should enter the existing policy-pause state machine:

```text
startup blocked before native start
        ↓
pausedByPolicy = true
repository.setPolicyBlocked(...)
        ↓
later PolicyAllowed
```

If no startup attempt is active when `PolicyAllowed` is processed, the coordinator starts one resume attempt directly.

If `PolicyAllowed` arrives while an attempt is still active, record the existing generation-bound pending retry and consume it after `StartupCompleted`.

So P1-013 **enables the existing mechanism** by truthfully marking the initial block as policy-paused. It does not create another retry mechanism.
7. Test coverage: The TODO mentions 18 P0/P1 tasks with acceptance criteria. Should each task have a dedicated test, or is focused regression testing sufficient?
A: Focused regression testing is sufficient; do not create one test method for every checkbox.

The rule is:

```text
every acceptance criterion must have evidence
```

A single deterministic test may prove several related criteria.

However, each P0 race/security invariant should have a direct focused regression, especially:

```text
stale retry after STOP
one-event retry
cleanup-failure quarantine
failed STOP retains foreground controller
identity wipe on failure
silent coroutine failure becomes visible
```

Do not rely only on broad integration tests for those.
## TODO Review Questions

8. P0-001: Is the `StartupCompleted` command a full rewrite of the startup completion flow, or an incremental change that preserves existing patterns?
A: It is a conceptual full replacement of the current completion-decision path, but an incremental refactor of the existing code.

Keep:

```text
startup preparation
repository.start()
classifyStartupResult()
existing cleanup helper
```

Replace:

```text
inline success handling
inline cleanup decision
inline retry creation
```

with:

```text
StartupCompleted command
        ↓
coordinator handler
```

Do not run both completion paths in parallel.
9. P0-002: The task replaces `RetryPolicyResume` with generation-bound retry. Should the current `RetryPolicyResume` object be converted to a data class with `expectedGeneration`, or added alongside?
A: Convert the existing `RetryPolicyResume` object into the generation-bound command.

Replace:

```kotlin
data object RetryPolicyResume
```

with:

```kotlin
data class RetryPolicyResume(
    val expectedGeneration: Long,
) : LifecycleCommand
```

Do not keep the old object alongside it.

There must be one retry command type and one validity rule.
10. P0-004: Runtime quarantine blocks all automatic restart. Should this apply to policy retry only, or also to manual user-initiated restart attempts?
A: Quarantine applies to:

```text
automatic policy resume
queued RetryPolicyResume
manual user Start
manual user Resume
```

because all of those can create or reactivate runtime while the previous runtime may still exist.

Quarantine does **not** block explicit user STOP.

STOP is the recovery operation and must always remain available.

Safest release policy:

```text
nativeRuntimeUncertain == true
    → only explicit STOP may attempt lifecycle recovery
```

Do not allow manual START to bypass quarantine.
11. P0-005: Failed STOP retention keeps service alive on failure. Current code calls `stopForegroundAndSelf()` on failure. Is this a breaking change to the STOP flow that requires user testing?
A: Yes. This is an intentional breaking correction to the STOP flow and it needs focused user-facing/service tests.

Current behavior:

```text
STOP fails
        ↓
controller disappears anyway
```

is unsafe.

Required behavior:

```text
STOP fails
        ↓
service remains foreground
        ↓
Error remains visible
        ↓
user can retry STOP
```

No migration path is needed, but tests must verify:

```text
stopSelf not called
foreground not removed
second STOP can succeed and then exit
```

A real-device smoke test is also appropriate before release because foreground-service behavior is Android-lifecycle-sensitive.
12. P0-007: Metered allowance clearing on failure vs success. Should there be tests for both success (allowance preserved) and failure (allowance cleared) paths?
A: Yes. Test both sides explicitly.

Required success test:

```text
AllowMeteredSession
        ↓
verified start succeeds
        ↓
allowance remains true
```

Required failure test:

```text
AllowMeteredSession
        ↓
startup fails
        ↓
allowance becomes false
```

Also test at least one lifecycle-ending path:

```text
successful allowed run
        ↓
Pause or STOP
        ↓
allowance becomes false
```

The success and failure semantics are different and both are easy to regress.
13. P0-008: Identity wiping uses ownership transfer pattern in spec. Is the pattern (`transferred = false`) required for correctness, or is the current `finally { fill(0) }` sufficient?
A: The exact `transferred = false` syntax is not mandatory, but the **ownership-transfer invariant is required**.

The current `finally { identity.fill(0) }` in the later startup function is insufficient because failures can happen before the buffer is returned to that function.

Any equivalent implementation is acceptable if it proves:

```text
before ownership transfer
    → every exit wipes buffer

after ownership transfer
    → exactly one new owner is responsible for wiping
```

The boolean-transfer pattern is recommended because it is explicit and easy for Qwen to implement correctly.

Do not keep the current pattern if a decrypted buffer can be lost before reaching the existing `finally`.
14. P1-001: Forwards mutation receipts vs current `ForwardsSnapshot`. Should `ForwardsSnapshot` be renamed to `ForwardsMutationReceipt`, or is `ForwardsSnapshot` being augmented?
A: Do not simply rename `ForwardsSnapshot`.

Keep `ForwardsSnapshot` only if it still has a legitimate read-only use elsewhere.

For rollback integrity, add a distinct:

```kotlin
ForwardsMutationReceipt
```

because its meaning is different:

```text
ForwardsSnapshot
    → observation of repository state

ForwardsMutationReceipt
    → proof of one exact committed mutation
       including before/after/revision
```

The ViewModel rollback path must stop doing:

```text
snapshot()
then mutate()
```

and use the receipt returned by the mutation itself.

If `ForwardsSnapshot` becomes unused after that refactor, delete it.
15. P1-005: Settings reset currently calls `forwardsStore.saveForwards(emptyList())` in SettingsViewModel. Should SettingsViewModel be updated to call `resetForwards()` after repository method is added?
A: Yes.

After adding:

```kotlin
ForwardsRepository.resetForwards()
```

update `SettingsViewModel.resetConfiguration()` to call that repository method.

Delete the direct production call to:

```kotlin
forwardsStore.saveForwards(emptyList())
```

from the reset flow.

The repository must update disk and its in-memory state together so old forwards cannot reappear later.
16. P1-006: Partial reset reporting applies to forwards reset only, or to config reset (config.toml, preferences, etc.) as well?
A: Partial reset reporting applies to **every mutating stage actually performed by `resetConfiguration()`**.

For the current flow, track the real stages such as:

```text
config.toml reset
saved setup-input reset
forwards reset
```

If preferences are also reset by the current implementation, include them. Do not invent a preferences stage if the reset function does not touch preferences.

The user-facing result should identify which real stages succeeded and failed.

Example:

```text
Reset partially completed:
- config reset: succeeded
- setup input reset: succeeded
- forwards reset: failed
```

This orchestration belongs in `SettingsViewModel`, not inside `resetForwards()`.
17. P1-007: Config write serialization uses "unique temp file". Should this be a UUID-based temp path per write, or a counter-based path?
A: Prefer the filesystem API's unique temp-file creation in the destination directory.

Recommended:

```kotlin
Files.createTempFile(
    configPath.parent,
    "config.toml.tmp-",
    ".partial",
)
```

This is better than manually choosing UUID or counter names.

A counter is acceptable only if the repository already owns a process-safe monotonic generator and cleanup policy.

A UUID is acceptable but unnecessary.

The important invariant is:

```text
unique temp path
same filesystem/directory for atomic move
shared write mutex
finally cleanup temp artifact
```
18. P1-013: Initially policy-blocked startup auto-resume. Does this add a retry mechanism (like `RetryPolicyResume`), or does it convert the blocked startup to a pending retry via the existing mechanism?
A: Use the existing policy-resume mechanism.

Do not create a new retry loop.

The blocked initial startup should set:

```text
pausedByPolicy = true
```

Then:

```text
later PolicyAllowed
    → if no attempt active:
          begin one resume attempt

    → if attempt active:
          record generation-bound pending retry
```

`RetryPolicyResume` is only needed when a valid `PolicyAllowed` intention must survive an already-active attempt.

Do not enqueue a retry immediately when the initial startup is blocked.
19. P1-014: Log refresh serialization mentions cancellation or generation. Current code may not serialize overlapping refreshes. Should this use a `refreshJob` pattern or generation counter?
A: Use a refresh generation as the correctness mechanism.

You may also cancel the previous `refreshJob` to reduce wasted work, but cancellation alone may not stop a blocking/non-cancellable repository call.

Recommended:

```kotlin
private val nextRefreshGeneration =
    AtomicLong(0)

private var refreshJob: Job? = null
```

On refresh:

```text
increment generation
cancel previous job
start new job with captured generation
```

Before publishing:

```text
captured generation == current generation
```

Otherwise discard stale result.

This deterministically prevents an older refresh from overwriting a newer result.
20. P1-015: LogsError UI wiring. Should `logsError` be added to LogsScreen as an error banner/card, or as a toast/snackbar?
A: Use a persistent inline error banner/card in `LogsScreen`.

Do not use a toast.

A snackbar is also weaker because:

- the failure may persist;
- refreshes are periodic;
- the user may open the screen after the failure event;
- snackbars disappear.

Recommended:

```text
logsError != null
    → visible error card/banner above log list
```

The synthetic error log entry may remain as supplementary evidence.
21. P1-016: Preference write failures should be visible. Should these be surfaced as error snackbars, or logged to diagnostics?
A: Use a user-visible error snackbar/message for user-initiated preference writes, and log diagnostics as a supplement.

Required behavior:

```text
save fails
    → visible error
    → no success snackbar
```

Do not rely only on diagnostics, because the user initiated the operation and needs immediate feedback.

Sensitive error details must be redacted before display or export.
## Cross-Cutting Coordination Questions

22. P0-001 vs P0-002 coupling: These tasks are coupled (StartupCompleted command and generation-bound retry). Should they be implemented together in a single commit, or as separate commits with tests for each?
A: Prefer separate commits, in dependency order:

```text
commit 1:
    P0-001 StartupCompleted coordinator path
    focused tests

commit 2:
    P0-002 generation-bound retry
    focused tests
```

P0-001 can temporarily preserve the existing retry semantics while moving completion decisions into the coordinator, as long as it remains compile/test clean.

Then P0-002 tightens retry validity.

Do not claim release signoff between the two commits.

If the local model cannot keep the intermediate state coherent, one combined commit is acceptable, but separate commits are preferred for reviewability.
23. P0-004 vs P0-005 coordination: Runtime quarantine (P0-004) blocks restart, but failed STOP retention (P0-005) keeps service alive. Should quarantine apply only to automatic restart, or also block user-initiated STOP?
A: Quarantine blocks restart, not recovery.

Allowed while quarantined:

```text
explicit STOP
```

Blocked while quarantined:

```text
automatic policy resume
RetryPolicyResume
manual Start
manual Resume
```

STOP must never be blocked because verified STOP is the operation that clears quarantine.
24. P0-007 vs P1-011: Metered allowance clearing (P0-007) vs nativeStopVerified tracking (P1-011). Both affect lifecycle state after stop/pause. Should these be coordinated in the same commit?
A: Coordinate them in the same implementation stage, but separate commits are preferred.

They touch related lifecycle branches:

```text
Pause
PolicyBlocked
Stop
```

P0-007 decides when temporary metered allowance clears.

P1-011 decides when verified native absence is recorded.

Implement and test them together conceptually so one branch does not update only one piece of lifecycle truth.

Recommended:

```text
commit A:
    metered allowance lifetime

commit B:
    nativeStopVerified consistency
```

Shared tests may cover both.
25. P1-005 vs P1-006: Forwards reset (P1-005) and partial reset reporting (P1-006). Does P1-006's stage tracking affect P1-005's implementation?
A: Yes. P1-006's stage tracking wraps the P1-005 repository reset.

Implementation order:

```text
P1-005:
    add atomic resetForwards()
    update SettingsViewModel to use it

P1-006:
    record the result of each reset stage
    produce explicit partial-success message
```

`resetForwards()` itself remains a single-stage atomic operation.

The stage tracker consumes its `Result`; it does not change the repository method's responsibility.
26. P1-008 vs P1-009: Unknown mode handling (P1-008) and unknown listen state diagnosis (P1-009). Both relate to native schema drift. Should these be implemented together?
A: Yes. Prefer implementing P1-008 and P1-009 together because they share:

```text
native schema parsing
native_status_schema_error
redaction policy
repository tests
```

Keep the task IDs and acceptance checks separate, but one focused commit is reasonable:

```text
fix(android): fail explicitly on unknown native status schema
```

Tests should cover unknown mode and unknown listen-state values independently.
27. Spec §2.2: `UnexpectedFailure` is a new `StartupCompletion` variant. Should this replace `NativeStartFailure` in some cases, or is it a separate path for unhandled exceptions?
A: `UnexpectedFailure` is a separate path.

Use:

```text
NativeStartFailure
    → repository.start() returned Result.failure
      under its normal contract

VerificationFailure
    → native start succeeded but post-start status
      could not be verified as active

UnexpectedFailure
    → code threw outside the expected Result contract
```

Examples of `UnexpectedFailure`:

```text
unexpected exception during preparation
unexpected repository/JNI throw
unexpected configuration access failure
```

Do not replace ordinary `NativeStartFailure` with `UnexpectedFailure`.
28. Spec §3: `pendingPolicyResumeGeneration` replaces `pendingPolicyResume` boolean. Is this a direct replacement, or does the boolean remain during transition?
A: Use `pendingPolicyResumeGeneration` as the direct replacement for the boolean.

Do not keep both as long-term state.

Preferred:

```kotlin
private val pendingPolicyResumeGeneration =
    AtomicReference<Long?>(null)
```

Meaning:

```text
null
    → no pending retry

generation N
    → one pending retry tied to generation N
```

During a short local refactor, both may temporarily exist to keep the code compiling, but the final implementation must have one source of truth.

If P0-001 makes every access strictly coordinator-owned, a plain nullable `Long` is also valid. Do not keep a redundant boolean.
29. Spec §9: `start_verification_cleanup_failed` sticky cleanup code. Current code has `lastCleanupError` but may not include this code. Should `start_verification_cleanup_failed` be added to the sticky classification?
A: Yes.

Add:

```text
start_verification_cleanup_failed
```

to the same sticky cleanup classification as:

```text
stop_failed
stop_status_verification_failed
```

Required behavior:

```text
start verification cleanup fails
        ↓
lastCleanupError set

later successful status refresh
        ↓
current status may recover
lastCleanupError remains
```

This is exactly P0-009.
---

Fill in the `A:` lines above with your answers, then share the file or paste the answers back for implementation guidance.
