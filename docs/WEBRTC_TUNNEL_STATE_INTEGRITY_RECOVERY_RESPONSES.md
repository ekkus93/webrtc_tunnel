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

   Do not relax the integrity requirement into a vague “attempted restore” success condition.

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

   The prerequisite is not “create the command”; it is “make the existing completion model cover initial policy block and preserve coordinator ownership.”

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

> Fill in the `A:` lines above and share back or paste the answers.
