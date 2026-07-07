# Responses: Ordered Lifecycle, Verified Start, and Policy-Integrity Release-Signoff

Covers the spec and TODO review for:
- `WEBRTC_TUNNEL_ORDERED_LIFECYCLE_VERIFIED_START_POLICY_SIGNOFF_SPEC.md`
- `WEBRTC_TUNNEL_ORDERED_LIFECYCLE_VERIFIED_START_POLICY_SIGNOFF_TODO.md`

---

## Questions requiring clarification before implementation

1. **`lifecycleGeneration` and `startupJob` definition**
Q: P0-003 references `lifecycleGeneration.incrementAndGet()` and P0-004 references `startupJob?.isActive`. These variables are not introduced in the P0-001 command-processor snippets. Should they be introduced in P0-001 as part of the command processor foundation, or do they already exist in `TunnelForegroundService`?
A: They already exist in the current `TunnelForegroundService`; do not introduce duplicate fields.

Reuse:

```kotlin
private var startupJob: Job? = null

private val lifecycleGeneration =
    AtomicLong(0)
```

Also reuse the existing lock-free generation helper:

```kotlin
private fun isCurrentGeneration(
    startGeneration: Long,
): Boolean =
    lifecycleGeneration.get() ==
        startGeneration
```

P0-001 should treat these as part of the existing lifecycle foundation and integrate them with the ordered command processor.

The command processor does not replace `startupJob`: it orders lifecycle intentions, while `startupJob` remains the cancellable handle for the currently executing asynchronous startup attempt.

Keep `lifecycleGeneration` as a defensive stale-completion guard. A later lifecycle command that supersedes an earlier startup should advance the generation before cancelling/joining that startup.

Do not create a second generation counter or expose either field publicly.
2. **`cancelStartupJobAndJoinLocked()` existence**
Q: P0-003's `handlePolicyBlocked` calls `cancelStartupJobAndJoinLocked()`. Does this function (or an equivalent) exist in the current codebase, or does it need to be created as part of the command processor?
A: It already exists. Reuse it.

Current implementation:

```kotlin
private suspend fun
    cancelStartupJobAndJoinLocked() {
    val job = startupJob
    startupJob = null
    job?.cancelAndJoin()
}
```

Do not create a second helper with overlapping semantics.

The current helper assumes the caller owns the lifecycle serialization boundary. If the command-processor refactor makes every call occur from one ordered processor rather than always under `lifecycleMutex`, either keep the current mutex discipline and name, or rename it to `cancelStartupJobAndJoin()` only after proving every caller still has exclusive lifecycle ownership.

Do not weaken `cancelAndJoin()` back to fire-and-forget `cancel()`.
3. **`reporter` object ownership and interface**
Q: P0-002 and P0-006 reference `reporter.stopStatusPollingAndJoin()` and `reporter.publishError()`. Does a `reporter` object exist in the current `TunnelForegroundService` with these methods, or does it need to be created/referenced differently?
A: The object already exists. Do not create a new reporter abstraction for this pass.

Current field:

```kotlin
private val reporter =
    StatusReporter()
```

The current inner `StatusReporter` already has:

```kotlin
suspend fun stopStatusPollingAndJoin()
```

and:

```kotlin
fun publishError(...)
```

The service also has a private convenience `publishError(...)` function that delegates to `reporter.publishError(...)`.

Use the existing object and the existing call style appropriate to the call site. For example:

```kotlin
reporter.stopStatusPollingAndJoin()
```

and either:

```kotlin
reporter.publishError(...)
```

or the service-level:

```kotlin
publishError(...)
```

Do not introduce a new interface merely to match the snippets.
4. **`refreshStatusResult()` in `TunnelRepository`**
Q: P0-002 replaces the start result with a verified status check using `refreshStatusResult()`. Does the current repository have a `Result<Unit>`-returning status refresh method, or does the current `refreshStatus()` need to be adapted to return a `Result`?
A: The current repository already has the result-bearing API needed:

```kotlin
fun refreshStatusResult():
    Result<TunnelStatus>
```

Use it directly.

Do not change it to `Result<Unit>`.

The current `refreshStatus()` is already a thin convenience wrapper whose contract is only to publish status:

```kotlin
fun refreshStatus() {
    refreshStatusResult()
}
```

P0-002 should make `start()` call `refreshStatusResult()` and inspect the returned committed `TunnelStatus`, just as current `stop()` already does.

No new refresh API is necessary.
5. **Network monitor component**
Q: P0-006's `onDestroy` cleanup needs to "cancel network monitor." What is the network monitor component in the current code? Does it have a cancellation mechanism (coroutine job, channel close, etc.) that can be joined?
A: The current network monitor is the existing coroutine job:

```kotlin
private var networkMonitorJob:
    Job? = null
```

It is created in `onCreate()` by launching a coroutine that collects:

```kotlin
networkPolicyManager
    .monitor(this@TunnelForegroundService)
```

So the correct cancellation mechanism is coroutine cancellation plus join.

Add or use:

```kotlin
private suspend fun
    stopNetworkMonitorAndJoin() {
    val job = networkMonitorJob
    networkMonitorJob = null
    job?.cancelAndJoin()
}
```

During destruction cleanup, stop accepting/submitting new network-policy lifecycle intentions, then cancel-and-join this job before fallback native cleanup.

Do not merely call `networkMonitorJob?.cancel()` and assume the collector has stopped.

Do not cancel the entire `serviceScope` before the monitor join and fallback native cleanup have completed.
6. **`ListenState.Error` enum value**
Q: P1-004 says unknown listen state should become `ListenState.Error`. Does the `ListenState` enum already have an `Error` variant, or does it need to be added before P1-004 can be implemented?
A: It already exists.

Current enum:

```kotlin
enum class ListenState {
    Listening,
    Stopped,
    Error,
    Disabled,
    Paused,
}
```

Do not add another enum value.

P1-004 only needs to change the unknown-string mapping so an unknown native value maps to the existing `ListenState.Error` and carries an explicit schema/status error instead of silently becoming `Stopped`.
7. **`ForwardsRepository` mutex**
Q: P1-002 assumes a mutex exists in `ForwardsRepository` for `saveIfRevisionMatches`. Does the current repository already have mutex-based serialization, or does the mutex need to be added?
A: The mutex already exists. Reuse it.

Current field:

```kotlin
private val mutex = Mutex()
```

Current `refresh()`, `save()`, and mutation operations already serialize through it.

Add the revision counter under the same mutex:

```kotlin
private var revision: Long = 0
```

Do not add a separate revision mutex.

All of these must be atomic with respect to that one existing mutex:

```text
read current forwards
read current revision
persist mutation
publish in-memory list
increment revision
compare expected rollback revision
conditionally persist rollback
```

The rollback revision must be captured while holding the mutex, not inferred afterward.
8. **`LogsViewModel.kt` or log consumer**
Q: P0-005 mentions modifying `LogsViewModel.kt` or "current log consumer." What is the exact file that consumes logs from `TunnelRepository` and needs to handle the new `logsError` state flow?
A: The direct consumer is:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/LogsViewModel.kt
```

It currently calls:

```kotlin
deps.tunnelRepository
    .recentLogs(maxEvents)
```

inside `refresh()`.

The rendered consumer is:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/ui/LogsScreen.kt
```

Recommended implementation:

1. Add `logsError` to `TunnelRepository`.
2. Expose it from `LogsViewModel`, for example:

```kotlin
val logsError:
    StateFlow<TunnelError?> =
        deps.tunnelRepository.logsError
```

3. Have `LogsScreen.kt` collect it and show a visible error/banner/card without changing tunnel lifecycle state.

The existing synthetic error log entry may remain as additional visibility.

Update tests in:

```text
android/app/src/test/java/com/phillipchin/webrtctunnel/data/TunnelRepositoryTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/LogsViewModelTest.kt
```

Do not route the log failure through `TunnelStatus.serviceState`.
9. **`SetupForwardsController.kt` or setup mutation controller**
Q: P1-003 mentions modifying `SetupForwardsController.kt` "or actual setup mutation controller." What is the exact file that handles forward mutations from the setup flow?
A: The exact setup-flow mutation controller is:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupForwardsController.kt
```

It currently calls:

```kotlin
deps.forwardsRepository.upsert(...)
```

and:

```kotlin
deps.forwardsRepository.delete(...)
```

The primary P1-003 enforcement belongs centrally in `ForwardsRepository`, so `SetupForwardsController` should not duplicate the `loadError` rule.

Its existing behavior already surfaces an invalid `ValidationResult` through the wizard's `errorMessage`.

Required flow:

```text
ForwardsRepository rejects mutation
        ↓
SetupForwardsController receives failure
        ↓
wizard displays the failure
```

Add or adjust setup coverage in `SetupViewModelTest.kt` to prove this path cannot bypass the central mutation block.
10. **P0-003 Test C — policy block while state says Error**
Q: Test C says "if runtime may still exist, policy command must still attempt cleanup." When repository state is `Error`, what exactly constitutes "cleanup" — does it call `repository.stop()` regardless, and if so, is a second stop after an error state safe/idempotent?
A: Yes. `PolicyBlocked` should call the authoritative verified `repository.stop()` regardless of the current UI/repository lifecycle label when runtime activity is uncertain.

The point of Test C is that:

```text
ServiceState.Error
```

does not prove:

```text
native runtime is absent
```

The Error may have come from a status read/decode problem, an unverified start, a previous cleanup problem, or another reporting failure.

Current stop semantics make the call safe for this purpose:

```text
native NotRunning
        +
verified final native Stopped
        =
success
```

If native stop fails, or post-stop status cannot be verified as `Stopped`, `repository.stop()` returns failure.

Required PolicyBlocked behavior:

```text
cancel/join startup
stop/join polling
call verified repository.stop()

success
    → pausedByPolicy = true
    → publish normal policy-paused state

failure
    → pausedByPolicy = false
    → publish visible error
    → do not publish normal policy-paused state
```

Do not skip cleanup merely because current state is `Error`, and do not add an `isTunnelRunning()` pre-check.
11. **`SensitiveDataRedactor.redactText()` availability**
Q: P0-005 uses `SensitiveDataRedactor.redactText()` in the logs error. Is this utility already available and does it accept an arbitrary string for redaction?
A: Yes.

Current utility:

```kotlin
SensitiveDataRedactor.redactText(
    input: String,
): String
```

It is already used throughout the Android app for arbitrary error messages, diagnostics text, log text, and status details.

Use it directly for the new `logsError.details`.

No new redaction API is needed.

Continue to avoid placing raw exception text into user-visible or exported diagnostics fields without passing it through this function.
12. **Spec §11.1 vs §4.2 — unknown mode interaction**
Q: §4.2 says start verification checks `isTunnelActiveOrStarting()`, and §11.1 says unknown native mode should set `serviceState = Error`. If the native status returns an unknown mode during start verification, does the verification fail (because Error is not in active-or-starting), making the explicit §11.1 error handling redundant for the start path? Or should the error be set first, then verification naturally fails?
A: Set the explicit schema error first, then let start verification naturally fail because the committed state is `Error`.

Required flow:

```text
native status decodes successfully
        ↓
mode string is unknown
        ↓
commit:
    serviceState = Error
    lastError.code =
        native_status_schema_error
    lastError contains a redacted,
        explicit unknown-mode reason
        ↓
start verification observes Error
        ↓
StartStatusVerificationException
```

The explicit schema handling is not redundant because it also:

- gives diagnostics the real root cause instead of only "final state was Error";
- applies to periodic status refresh outside startup;
- applies to an already-running tunnel if native schema drifts;
- prevents unknown mode from becoming a plausible-looking `Offer`.

Do not implement unknown mode by throwing a generic decode exception unless that exception is translated back into the specific `native_status_schema_error` contract.

Apply the same fail-explicitly principle to unknown listen-state strings.
13. **Implementation timing**
Q: Should I begin implementing immediately starting with Stage 1 (P0-001 + P1-001), or should these answers be provided first?
A: Use these answers as the clarification record, then begin implementation immediately with Stage 1.

No further confirmation is required.

Implement in the specified dependency order:

```text
Stage 1:
    P0-001 ordered command processor
    P1-001 atomic/ordered metered allowance

Stage 2:
    P0-002 verified start

Stage 3:
    P0-003 policy block integrity
    P0-004 reliable one-event retry
```

Run focused tests after each task as required by the TODO.

Do not push or claim final CI signoff until every P0/P1 production change in this pass is complete and local gates have been run.
---

Fill in the `A:` lines with your answers and share back when ready.
