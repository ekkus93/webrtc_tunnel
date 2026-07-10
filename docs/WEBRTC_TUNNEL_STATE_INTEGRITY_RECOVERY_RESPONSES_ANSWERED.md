# Responses for WEBRTC_TUNNEL_STATE_INTEGRITY_RECOVERY_SPEC / TODO

---

1. **Q:** Does `SensitiveDataRedactor` already exist in the codebase, or does it need to be created? (Blocks nearly all P0 tasks)

   **A:** 

   Yes. Reuse the existing `SensitiveDataRedactor`; do **not** create a parallel redaction abstraction.

   The current codebase already has `SensitiveDataRedactor.redactText()` and uses it in lifecycle/status/diagnostic paths. Route new visible errors through that existing implementation.

   Rule: if a task needs redaction, call the existing redactor. Only extend it if a concrete new secret pattern is proven missing.

2. **Q:** Does `LifecycleCommand.StartupCompleted` with a typed `StartupCompletion` payload already exist, or is it new? (Prerequisite for P1-006)

   **A:** 

   `LifecycleCommand.StartupCompleted` already exists and should be reused.

   Do **not** introduce a second completion command. The current tree already has a typed completion/result path associated with startup. Reuse the existing payload type if possible (`StartOutcome` in the current code), or extend that existing type with a policy-blocked variant.

   The invariant is one completion command carrying one typed startup result back to the coordinator. Do not add a parallel `StartupCompletion` hierarchy merely to match example text in the spec.

3. **Q:** Should rollback continue for all remaining stages even if one rollback stage fails? (Affects P0-001 rollback semantics)

   **A:** 

   Yes. Rollback should continue best-effort through **all remaining previously-mutated stages**, even if one rollback stage fails.

   Rules:

   1. Roll back only stages that actually mutated successfully.
   2. Roll back in reverse mutation order.
   3. Record each rollback failure.
   4. One rollback failure must not prevent attempts to restore the other mutated stages.
   5. Return the complete per-stage rollback outcome list.

   This maximizes recovery and makes the final state explicit.

4. **Q:** Is `Channel.UNLIMITED` intentional for the coordinator command channel, or should there be a bounded size with backpressure? (P0-003 memory concern)

   **A:** 

   Yes, `Channel.UNLIMITED` is intentional for this recovery pass.

   The coordinator carries low-volume, correctness-critical lifecycle commands. Dropping `STOP`, `StartupCompleted`, or policy transitions is worse than the bounded-memory risk here.

   Use `Channel.UNLIMITED` only for lifecycle commands; do not route logs, telemetry, polling ticks, or payload data through it. Coalesce noisy duplicate network-state events before submission if necessary.

   Revisit bounded backpressure later only if there is evidence of unbounded command production. Do **not** replace it with bounded `trySend()` semantics that can drop commands.

5. **Q:** Should `prepareStartupInputs()` have its own distinct failure boundary separate from `runNativeStart()`, or are they intentionally grouped under one `try/catch`? (P0-005 vs P0-004 scope)

   **A:** 

   Use separate logical functions, but one outer completion boundary.

   `prepareStartupInputs()` and `runNativeStart()` should remain distinct because they have different responsibilities and tests. However, the outer startup-attempt function must wrap **both**, so every unexpected preparation or native-start failure becomes a visible typed completion.

   ```kotlin
   private suspend fun performStartupAttempt(
       generation: Long,
   ): StartupCompletion {
       return try {
           val prepared = prepareStartupInputs(generation)
           runNativeStart(generation, prepared)
       } catch (cancelled: CancellationException) {
           throw cancelled
       } catch (error: Throwable) {
           StartupCompletion.UnexpectedFailure(error)
       }
   }
   ```

   Known preparation failures may map to more specific typed variants internally, but no preparation exception may bypass the completion command.

6. **Q:** Option A or Option B for P1-010 (typed `StartOutcome` bridge)? Option B is lower risk and defers to a future pass.

   **A:** 

   Choose **Option B** for this pass.

   Keep the current bridge API and remove/repair the false claim that typed `StartOutcome` is implemented through JNI. Do not widen this integrity-recovery pass into a JNI contract redesign.

   The existing `StartOutcome` type may still be used above the bridge boundary for Android-side classification, but comments and documentation must say exactly that.

   A true typed JNI/bridge boundary can be a later focused task.

7. **Q:** Should the signoff condition "transactional reset restores exact previous state" be relaxed to "attempted restore with per-stage outcome reported"? (If rollback can partially fail)

   **A:** 

   Do not relax the integrity requirement into a vague "attempted restore" success condition.

   Clarify it instead:

   - A successful rollback stage must restore the **exact captured previous state**.
   - If any rollback stage fails, the overall reset result is a failure/partial-recovery result, not success.
   - Every rollback stage outcome must be reported explicitly.

   Use wording such as:

   > On reset failure, every successfully completed rollback stage restores the exact captured prior state; any rollback failure is explicitly reported per stage and prevents the reset from being classified as successful.

   This is realistic without pretending underlying I/O can never fail.

---

8. **Q:** (P0-004) Is `LifecycleCommand.StartupCompleted` a new command or existing? If new, this is a prerequisite for P1-006.

   **A:** 

   Existing. Reuse the current `LifecycleCommand.StartupCompleted`; do not add another command.

   For P1-006, extend the existing startup result payload with a policy-blocked outcome, or the current equivalent, so initial policy block returns through the same completion path.

   The prerequisite is not "create the command"; it is "make the existing completion model cover initial policy block and preserve coordinator ownership."

9. **Q:** (P0-005) Should `prepareStartupInputs()` have its own outer boundary separate from `runNativeStart()` if preparation has distinct failure modes?

   **A:** 

   Same decision as #5: distinct functions, one outer completion boundary.

   Keep `prepareStartupInputs()` separately testable. Keep `runNativeStart()` separately testable. But `performStartupAttempt()` must catch unexpected errors from both and convert them into the single typed startup completion returned to the coordinator.

   Do not let preparation throw outside the lifecycle completion path.

10. **Q:** (P1-001) How many callers use the snapshot/rollback pattern? Is this a ViewModel-only change or does it touch the service layer?

    **A:** 

    From the reviewed tree, the dangerous `snapshot()` + mutation + revision-checked rollback pattern was in the forwards ViewModel/repository workflow. I did not identify a service-layer caller that needs this rollback pattern.

    Treat this primarily as a `ForwardsViewModel` + `ForwardsRepository` change.

    Before editing, still search the repository for:

    ```text
    snapshot(
    saveIfRevisionMatches(
    ForwardsRepository.save(
    ForwardsConfigStore.saveForwards(
    ```

    Migrate every mutation caller found. If another caller exists, it must use the same receipt API rather than keeping a bypass.

11. **Q:** (P1-003) Is there an existing config-write mutex, or does this task need to introduce one?

    **A:** 

    Yes. There is already a config write mutex / serialized atomic writer in `ConfigRepository`.

    Do **not** create a second mutex.

    P1-003 is to finish the invariant:

    - route every production `config.toml` writer through the existing mutex-backed atomic writer;
    - remove direct `configFile.writeText(...)` production bypasses;
    - ensure unique temp files;
    - clean temp files in `finally`;
    - add the overlapping-writer regression test.

    Reuse and strengthen the existing abstraction.

12. **Q:** (P1-006) Confirm whether P1-006 cannot start until P0-004 is complete.

    **A:** 

    Confirmed.

    P1-006 depends on the startup completion/coordinator model being correct first. Complete P0-004 before implementing final P1-006 behavior.

    Order:

    1. fix completion-driven startup ownership and one-event retry;
    2. add or extend the existing completion payload with `PolicyBlocked`;
    3. implement initially-blocked startup auto-resume through that path.

    Do not build P1-006 on the current `startupJob?.isActive` behavior.

13. **Q:** (Cross-Cutting #1) P1-006 depends on P0-004's `StartupCompletion` model — is this dependency explicit and acknowledged?

    **A:** 

    Yes. The dependency is explicit and acknowledged.

    P1-006 must reuse the completion-driven lifecycle model completed in P0-004. The initial policy-block case should become another typed completion handled by the coordinator after startup ownership is cleared.

    Update the TODO dependency note so Claude Code does not attempt P1-006 independently or introduce a parallel completion abstraction.

14. **Q:** (Cross-Cutting #4) `NativeRuntimeQuarantinedException` — should this be a custom `Exception` subclass or a sealed interface/result type?

    **A:** 

    Use a small custom exception subclass carried inside `Result.failure`.

    Recommended:

    ```kotlin
    internal class NativeRuntimeQuarantinedException(
        message: String,
    ) : IllegalStateException(message)
    ```

    Then:

    ```kotlin
    private fun requireRuntimeStartAllowed(): Result<Unit>
    ```

    can return `Result.failure(NativeRuntimeQuarantinedException(...))`.

    This is the smallest change and fits the current `Result`-based guard style. Do not add a new sealed hierarchy solely for quarantine unless the current lifecycle API already has a typed start-denial result that can be extended cleanly.

15. **Q:** (Cross-Cutting #5) Config file mutex (P1-003) and identity zeroization (P0-006) both touch config/identity file I/O — should these be coordinated in the same pass?

    **A:** 

    No. Keep them as separate scoped passes/commits.

    Identity zeroization is a P0 secret-ownership invariant. Config write serialization is a P1 persistence/concurrency invariant. They should not be coupled merely because both involve file-adjacent code.

    Recommended order:

    1. P0-006 identity zeroization;
    2. validate focused identity tests;
    3. later P1-003 config serialization.

    Coordinate only if a genuinely shared helper must change. Otherwise keep the blast radius small.

16. **Q:** (Cross-Cutting #6) The TODO requires deleting tests before replacement tests are added. Should the deleted tests be removed first, or should replacement tests be added alongside before cleanup?

    **A:** 

    Add the replacement regression first, or rewrite the incorrect test in the same scoped commit. Do not create a coverage gap.

    Preferred sequence:

    1. add the correct invariant test and confirm it fails against current code;
    2. implement the fix;
    3. remove or rewrite the workaround/opposite-invariant test;
    4. run the focused suite.

    For the repeated-network-event workaround, the replacement one-event test should exist before the loop is removed.

    For the restart-after-failed-STOP test, rewrite it in the same commit to:

    ```text
    STOP fails
    quarantine active
    START blocked
    STOP retry succeeds
    quarantine clears
    START allowed
    ```

    Never delete coverage first and plan to restore it later.

---

## Still open — need user decisions

17. **Q:** `runCatching` vs `try`/`catch` in `TunnelLifecycleCoordinator.processCommands()`: The spec explicitly marks `runCatching` as "Wrong" for critical paths, requiring `try`/`catch` with `CancellationException` rethrown. Should `processCommands()` be converted to `try`/`catch`, or is `runCatching` acceptable where the result is handled?

   **A:**

   Convert `processCommands()` to explicit `try`/`catch`.

   `runCatching` is not acceptable in this critical lifecycle path, even if the result is handled, because it makes it too easy to accidentally swallow `CancellationException` or obscure the intended cancellation semantics. The spec's example is intentional.

   Use this pattern:

   ```kotlin
   private suspend fun processCommand(
       command: LifecycleCommand,
   ) {
       try {
           operations.handleCommand(command)
       } catch (cancelled: CancellationException) {
           throw cancelled
       } catch (error: Throwable) {
           operations.publishLifecycleError(
               code = "lifecycle_command_failed",
               error = error,
           )
       }
   }
   ```

   If the current code has a loop around `for (command in commands)`, the `try`/`catch` should wrap each command handling operation, not the whole processor in a way that stops the loop after one unexpected command failure.

   The broader rule for this pass is:

   - critical coroutine boundary: explicit `try`/`catch`;
   - cancellation always rethrown;
   - unexpected error visible and redacted;
   - processor continues unless the scope is cancelled.

18. **Q:** The `failedAutoResumeLeavesPausedByPolicyTrueForNextRetry` test still loops network events until success via `waitForCondition` that re-emits `onAvailable` on each iteration. Should this test be rewritten to the one-event invariant (send exactly one event, assert outcome), or does the loop serve a different testing purpose here?

   **A:**

   Rewrite it to the one-event invariant.

   The loop that re-emits `onAvailable` inside `waitForCondition` is exactly the workaround the recovery spec was trying to eliminate. It can hide the race where one later PolicyAllowed event is not sufficient because startup completion ownership has not been cleared yet.

   The replacement test should send exactly:

   ```text
   PolicyAllowed #1 -> starts resume attempt #1
   attempt #1 fails or is completing
   PolicyAllowed #2 -> recorded as the one pending retry
   NO third PolicyAllowed event
   StartupCompleted #1 is handled
   retry attempt #2 runs exactly once
   ```

   Assertions should include:

   ```kotlin
   assertEquals(2, fakeBridge.startOfferCalls)
   ```

   or the project’s equivalent native-start call count.

   Do not keep the looping test as a second test unless it is renamed to something like “repeated network events are coalesced” and no longer used to prove the one-event retry requirement.

19. **Q:** Has Logs generation ordering (P1-007) been implemented? The spec requires generation checks for log list and log error together, but this wasn't visible in the files reviewed.

   **A:**

   Treat P1-007 as **not verified / not complete** until the code clearly ties log list and log error to the same generation.

   The requirement is not merely “older log list cannot overwrite newer log list.” It is:

   > older result cannot overwrite newer logs **or newer logsError**.

   The safe implementation is to return one typed result from the repository without mutating shared error state first:

   ```kotlin
   data class LogsFetchResult(
       val logs: List<LogEntry>,
       val error: String?,
   )
   ```

   Then the ViewModel generation check owns both fields:

   ```kotlin
   val generation = ++refreshGeneration

   val result =
       withContext(ioDispatcher) {
           repository.fetchRecentLogs()
       }

   if (generation != refreshGeneration) {
       return@launch
   }

   _logs.value = result.logs
   _logsError.value = result.error
   ```

   If `TunnelRepository.recentLogs()` still mutates a repository-level `logsError` before the ViewModel generation check, then P1-007 is not implemented.

   Required tests:

   1. older failure cannot set `logsError` after newer success;
   2. older success cannot clear newer failure;
   3. older success cannot replace newer list;
   4. UI still displays the current error when present.

20. **Q:** Should status poll failures be published as visible errors with code `status_poll_failed`, or is silent swallowing intentional ("best-effort" design)?

   **A:**

   Publish status poll failures visibly with code `status_poll_failed`.

   Silent swallowing is not acceptable for this path. Status polling is part of the controller’s state-integrity model; if it fails unexpectedly, the operator/user needs a visible redacted diagnostic. “Best effort” may be acceptable for low-value telemetry, but not for controller status refresh.

   Use explicit `try`/`catch`:

   ```kotlin
   try {
       repository.refreshStatus()
   } catch (cancelled: CancellationException) {
       throw cancelled
   } catch (error: Throwable) {
       reporter.publishError(
           code = "status_poll_failed",
           message =
               SensitiveDataRedactor.redactText(
                   error.message
                       ?: "Status poll failed"
               ),
       )
   }
   ```

   If repeated failures would be noisy, add rate limiting or coalescing, but do not make the failure invisible.

   Also remove any remaining pattern like:

   ```kotlin
   runCatching {
       repository.refreshStatus()
   }
   ```

   where the returned `Result` is discarded.

21. **Q:** Is notification retry wired through `submitLifecycleCommand` (thus going through the coordinator and guarded by quarantine), or does it have a direct path that needs the `requireRuntimeStartAllowed()` guard explicitly added?

   **A:**

   Assume notification retry may have a direct path until proven otherwise, and guard it explicitly.

   Even if a notification action currently routes through `submitLifecycleCommand`, add the quarantine guard at the actual start/resume handler boundary too. Defense in depth is appropriate here because the invariant is simple:

   > no start/resume path may start native runtime while `nativeRuntimeUncertain == true`.

   Required approach:

   1. Keep notification actions routed through the coordinator where possible.
   2. Add `requireRuntimeStartAllowed()` at the lowest shared start/resume entry point, such as `OfferCoordinator.startOffer()` or the service method that ultimately invokes native start.
   3. Also make command handlers return early with a visible `native_runtime_quarantined` event/message if blocked.

   Test both paths if possible:

   - notification retry while quarantined does not call native start;
   - normal StartOffer while quarantined does not call native start;
   - explicit STOP while quarantined still works.

   The guard should not rely solely on notification routing discipline.

22. **Q:** Have sentinel-byte tests been written for P0-006 identity zeroization failure paths? The spec requires tests asserting zeroization on every failure path.

   **A:**

   If those sentinel-byte tests are not visible in the current test tree, treat P0-006 as incomplete.

   The spec requires tests that prove zeroization after plaintext allocation on every failure path. Generic tests that only verify a helper function can zero an array are not enough.

   Add focused tests for at least:

   1. private identity validation throws after bytes are read;
   2. private identity validation returns invalid after bytes are read;
   3. public identity read throws after private bytes are read;
   4. peer ID derivation throws after private bytes are read;
   5. later startup preparation fails after ownership transfer and final owner wipes.

   Use a fake identity repository that returns a mutable sentinel `ByteArray`, then assert it becomes all zeros after failure.

   Example assertion:

   ```kotlin
   assertTrue(
       sentinel.all { it == 0.toByte() },
   )
   ```

   If production code copies the bytes, the fake should expose/track the actual array handed to the code under test, not merely the original source array.

23. **Q:** Should `onDestroy()` update `nativeStopVerified` after performing fallback `repository.stop()`, or is this intentionally omitted because the service is being destroyed?

   **A:**

   Yes. `onDestroy()` should update `nativeStopVerified` after fallback `repository.stop()`.

   Even though the service is being destroyed, the flag is part of the controller’s truth model and tests depend on avoiding double-stop behavior. If the fallback stop succeeds and status verification confirms absence, set:

   ```kotlin
   nativeStopVerified.set(true)
   nativeRuntimeUncertain.set(false)
   ```

   If the fallback stop fails, set or retain:

   ```kotlin
   nativeStopVerified.set(false)
   nativeRuntimeUncertain.set(true)
   ```

   and publish/log a redacted failure.

   Suggested pattern:

   ```kotlin
   if (!nativeStopVerified.get()) {
       repository.stop()
           .onSuccess {
               nativeStopVerified.set(true)
               nativeRuntimeUncertain.set(false)
           }
           .onFailure { error ->
               nativeStopVerified.set(false)
               nativeRuntimeUncertain.set(true)
               reporter.publishError(
                   code = "destroy_fallback_stop_failed",
                   message =
                       SensitiveDataRedactor.redactText(
                           error.message
                               ?: "Destroy fallback stop failed"
                       ),
               )
           }
   }
   ```

   This is not about keeping the dying service useful; it is about not leaving the lifecycle model internally contradictory and about making tests/assertions precise.


---

> Fill in the `A:` lines above and share back or paste the answers.
