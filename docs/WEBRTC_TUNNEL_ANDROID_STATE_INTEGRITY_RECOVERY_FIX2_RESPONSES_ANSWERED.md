# Responses for WEBRTC_TUNNEL_ANDROID_STATE_INTEGRITY_RECOVERY_FIX2

---

1. **Q:** Should `StartOutcome.PolicyBlocked` be added as a new variant in `StartOutcome.kt` before P0-001 begins, or should the policy-blocked path remain as a separate error without going through `StartupCompleted`?

   **A:** Add `PolicyBlocked` to the existing startup result type before implementing P0-001.

   The policy-blocked path must go through `LifecycleCommand.StartupCompleted`. It should not remain as a side-effecting separate error path that returns from the startup coroutine without coordinator completion.

   Use the existing completion payload type. If the current project uses `StartOutcome`, add the variant there:

   ```kotlin
   data class PolicyBlocked(
       val reason: String,
   ) : StartOutcome
   ```

   Then the startup worker always submits:

   ```kotlin
   LifecycleCommand.StartupCompleted(
       generation = generation,
       completion = StartOutcome.PolicyBlocked(reason),
   )
   ```

   The coordinator should own the side effects: clear `activeStartup`, set `pausedByPolicy`, update repository status, and handle pending retry. Do not create a second completion abstraction.

2. **Q:** For P0-004 transactional reset: should `ConfigRepository.deleteConfigFileForTransactionalReset()` and `ForwardsRepository.restoreForTransactionalReset()` be `internal` or package-private?

   **A:** Use Kotlin `internal`.

   Kotlin does not have Java-style package-private visibility. These should not be public general-purpose APIs, and they should not be callable from ViewModels or normal user mutation paths.

   Recommended:

   ```kotlin
   internal suspend fun deleteConfigFileForTransactionalReset(): Result<Unit>
   ```

   and:

   ```kotlin
   internal suspend fun restoreForTransactionalReset(
       forwards: List<ForwardConfig>,
   ): Result<Unit>
   ```

   Keep them in the repository layer, document that they are only for transactional reset rollback, and test them through `TransactionalResetCoordinator` where possible. If direct unit tests need them, `internal` is fine for same-module Android tests.

3. **Q:** P1-002: should `ensureDefaultConfig()` in `ConfigRepository` be converted to use `writeConfigAtomically()`, or is it acceptable since it only runs when no config exists?

   **A:** Convert `ensureDefaultConfig()` to use the same serialized atomic writer.

   It is not acceptable to keep a direct `configFile.writeText(...)` production bypass just because the function usually runs when no config exists. The invariant is:

   ```text
   all production config.toml writes go through one mutex-backed writer
   ```

   Direct write in `ensureDefaultConfig()` can still race with another writer, produce partial content on crash, and undermine the audit.

   Use:

   ```kotlin
   if (!configFile.exists()) {
       writeConfigAtomically(defaultConfig)
           .getOrElse { error ->
               return Result.failure(error)
           }
   }
   ```

   Adjust the return shape to match the existing function.

4. **Q:** P1-003: should `resolveNativeMode()` return `Result<TunnelMode>` (simpler) or introduce `NativeModeResolution` sealed type as shown in the spec?

   **A:** Use `Result<TunnelMode>` if that fits the current code with less churn.

   The important requirement is not the specific wrapper type. The requirement is that missing or unknown native mode becomes an explicit schema error and cannot silently become `TunnelMode.Offer`.

   A simple implementation is fine:

   ```kotlin
   private fun resolveNativeMode(
       rawMode: String?,
   ): Result<TunnelMode> =
       when (rawMode) {
           "offer" -> Result.success(TunnelMode.Offer)
           "answer" -> Result.success(TunnelMode.Answer)
           null ->
               Result.failure(
                   NativeStatusSchemaException(
                       "native_status_schema_error: missing mode",
                   ),
               )
           else ->
               Result.failure(
                   NativeStatusSchemaException(
                       "native_status_schema_error: unknown mode ${SensitiveDataRedactor.redactText(rawMode)}",
                   ),
               )
       }
   ```

   Use a sealed `NativeModeResolution` only if the current status-mapping code needs to carry richer non-exception metadata. For Qwen/Claude Code, `Result<TunnelMode>` is the lower-risk path.

5. **Q:** P1-004: should `TunnelRepository.recentLogs()` stop writing to `_logsError.value` and let `LogsViewModel` own error state completely via `LogsFetchResult.error`?

   **A:** Yes.

   `TunnelRepository.recentLogs()` should stop directly writing `_logsError.value` for refresh results. The repository should return a typed value:

   ```kotlin
   data class LogsFetchResult(
       val logs: List<LogEntry>,
       val error: TunnelError?,
   )
   ```

   Then `LogsViewModel` should apply both `logs` and `error` only after its generation check passes.

   This is the only way to satisfy the requirement that an older refresh cannot overwrite a newer refresh’s logs or logs error.

6. **Q:** Does the current `LogsViewModel` generation-based stale-result protection satisfy P1-004, or should the repository's `_logsError` direct write be removed first?

   **A:** The current ViewModel generation check does **not** satisfy P1-004 if the repository still writes `_logsError` directly.

   Protecting only `_logs.value` is not enough. The stale race is:

   ```text
   refresh #1 starts
   refresh #2 starts and succeeds
   refresh #2 updates logs
   refresh #1 fails later
   repository writes logsError directly
   UI now shows stale error
   ```

   Remove the repository direct write as part of the same P1-004 change. The generation check must own both the log list and the error value.

7. **Q:** For P1-005 network event delivery: is converting `NetworkPolicyManager.monitor()` to emit `StateFlow<NetworkStatus>` instead of `callbackFlow` in scope, or is Option B (logging failed `trySend`) sufficient for this pass?

   **A:** Option B is sufficient for this pass.

   A `StateFlow` redesign may be cleaner long-term, but it is more invasive. For this fix pass, handle every `trySend` result and publish a visible diagnostic on failure.

   Example:

   ```kotlin
   val result = trySend(status)

   if (result.isFailure) {
       reporter.publishError(
           code = "network_policy_event_delivery_failed",
           message = "Network policy event could not be delivered",
       )
   }
   ```

   Do not report failure when cancellation/closure is the expected teardown path. If the channel is closed because the monitor is stopping, avoid a false alarm. But ordinary delivery failure while active must not be silent.

8. **Q:** (P0-001) Should `prepareOfferIdentity()` stop setting `pausedByPolicy` and `repository.setPolicyBlocked()` when it encounters a policy block, and instead throw `StartupPolicyBlocked` for the coordinator to handle?

   **A:** Yes.

   Move policy-block side effects out of `prepareOfferIdentity()` / startup preparation and into the coordinator’s startup-completion handler.

   `prepareOfferIdentity()` may inspect policy, but on block it should produce or throw a typed startup-block signal:

   ```kotlin
   throw StartupPolicyBlocked(
       "Blocked by network policy",
   )
   ```

   or directly return `StartOutcome.PolicyBlocked` if the function is refactored that way.

   The coordinator should handle:

   ```text
   activeStartup = null
   pausedByPolicy = true
   repository.setPolicyBlocked(...)
   publish status
   pending retry behavior
   ```

   This prevents the current bug where preparation mutates state and then exits without clearing startup ownership.

9. **Q:** (P0-002) Is the fake identity repository test approach (sentinel byte array verification) feasible with the current dependency injection structure?

   **A:** Yes, it should be feasible. The current ViewModel/controller layer already uses dependency injection-style fakes in tests.

   Use or extend the existing fake identity repository so it returns the exact mutable `ByteArray` that the test later inspects.

   Important: do not test only the source array if production code receives a copy. The fake should expose the actual array returned by `readPrivateIdentityPlaintext()`:

   ```kotlin
   class FakeIdentityRepository(
       private val plaintext: ByteArray,
   ) : IdentityRepository {
       val returnedPlaintext: ByteArray
           get() = plaintext

       override suspend fun readPrivateIdentityPlaintext(): ByteArray =
           plaintext
   }
   ```

   Then assert:

   ```kotlin
   assertTrue(
       fakeIdentityRepository
           .returnedPlaintext
           .all { it == 0.toByte() },
   )
   ```

   If the current interfaces make this hard, make the smallest test-only fake/refactor needed. Do not skip sentinel tests.

10. **Q:** (P1-001) Should `ForwardsRepository.save()` be deleted entirely, or kept for rollback (since `rollbackReceipt()` uses the receipt pattern, not `save()`)?

    **A:** Delete the public raw `save()` API.

    Do not keep it for rollback. Transactional reset should use a scoped internal restore API instead:

    ```kotlin
    internal suspend fun restoreForTransactionalReset(
        forwards: List<ForwardConfig>,
    ): Result<Unit>
    ```

    `rollbackReceipt()` should continue using the receipt pattern. Normal UI/user mutations should use receipt APIs. Tests that currently call `save()` should be rewritten to use public user operations, repository refresh/load setup, or the new internal restore only when specifically testing transactional rollback.

    The goal is:

    ```text
    no public raw forwards mutation bypass
    ```

11. **Q:** (Spec § P0-004) Does "stop on first failed stage" mean abort the loop immediately (avoiding any further mutations), or is it acceptable to collect all results and rollback after?

    **A:** Abort the loop immediately.

    Once a reset stage fails, do not mutate any later stages. Roll back only the stages that already mutated successfully.

    Required flow:

    ```text
    run stage A
    if A fails -> rollback none, return failure

    run stage B
    if B fails -> rollback A, return failure

    run stage C
    if C fails -> rollback B then A, return failure
    ```

    Collecting all stage failures by continuing to mutate after a known failure is not acceptable for this transactional reset. It expands the blast radius and makes exact recovery harder.

12. **Q:** (Spec § P1-003) Is the current `resolveNativeMode(null)` behavior (`TunnelMode.Offer` fallback) what needs to change, or is `null` already rejected?

    **A:** The `null -> TunnelMode.Offer` fallback is what needs to change.

    If the latest local code still has:

    ```kotlin
    null -> TunnelMode.Offer
    ```

    replace it. Missing mode must be a schema error.

    If Claude Code has already changed it in a newer local edit, then add/verify the test:

    ```text
    missing native mode fails startup/status verification visibly
    ```

    The acceptance condition is:

    ```text
    null mode cannot verify as Offer
    ```

13. **Q:** (Spec § P1-002) Should the `AtomicMoveNotSupportedException` fallback be added to `ConfigRepository.writeConfigAtomicallyLocked()`, or is that out of scope since it's internal?

    **A:** Add the fallback. It is in scope.

    The method being internal does not make it less important; it is the core production config writer. It should behave at least as safely as the forwards config writer.

    Required behavior:

    ```kotlin
    try {
        Files.move(
            temp.toPath(),
            configFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (unsupported: AtomicMoveNotSupportedException) {
        Files.move(
            temp.toPath(),
            configFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
    ```

    Also ensure temp cleanup happens in `finally`.

14. **Q:** (Spec § 2.6) Should `TunnelRepository.recentLogs()` be converted from `runCatching` to `try/catch`, or is `runCatching` acceptable since the result is handled?

    **A:** Convert it to explicit `try/catch`.

    `recentLogs()` is not as lifecycle-critical as startup or stop, but this pass is trying to remove ambiguous coroutine failure handling from controller-facing paths. `runCatching` catches `CancellationException`, so it is easy to accidentally turn cancellation into a normal error result.

    Use:

    ```kotlin
    try {
        LogsFetchResult(
            logs = bridge.recentLogs(maxEvents),
            error = null,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        LogsFetchResult(
            logs = emptyList(),
            error = TunnelError(
                code = "logs_refresh_failed",
                message = SensitiveDataRedactor.redactText(
                    error.message ?: "Log refresh failed",
                ),
            ),
        )
    }
    ```

15. **Q:** (Cross-Cutting) Should `TransactionalResetCoordinator.resetConfiguration()` convert `runCatching` to `try/catch` per spec rule 2.6?

    **A:** Yes.

    `resetConfiguration()` is a persistence mutation boundary with rollback behavior. It should use explicit `try/catch`, rethrow cancellation, and return typed reset failure for unexpected errors.

    Pattern:

    ```kotlin
    suspend fun resetConfiguration(): ResetResult {
        return try {
            resetConfigurationInternal()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ResetResult.Failed(
                failedStage = ResetStage.Unknown,
                reason = SensitiveDataRedactor.redactText(
                    error.message ?: "Reset failed",
                ),
                rollback = emptyList(),
            )
        }
    }
    ```

    Use the actual stage model. Do not let unexpected exceptions escape as crashes, and do not swallow cancellation.

16. **Q:** (Cross-Cutting) `ensureDefaultConfig()` directly writes `configFile.writeText(...)`. Should this be converted to use the atomic writer?

    **A:** Yes.

    This is the same answer as #3. Convert it to the mutex-backed atomic writer.

    The invariant is simple:

    ```text
    no production config.toml direct writes
    ```

    `ensureDefaultConfig()` is a production writer, even if it only runs when the file is absent.

17. **Q:** (Cross-Cutting) Should `TunnelRepository.recentLogs()` stop writing to the repository's `_logsError` and let `LogsViewModel` own error state completely via `LogsFetchResult.error`?

    **A:** Yes.

    This is the same answer as #5 and #6. Stop writing `_logsError` inside `TunnelRepository.recentLogs()` for refresh results.

    The repository should return:

    ```kotlin
    LogsFetchResult(
        logs = ...,
        error = ...,
    )
    ```

    The ViewModel should apply both fields only if its generation is still current.

    If `_logsError` remains as repository state for some other screen, then it must be updated only by the generation owner or replaced with a separate explicitly synchronized mechanism. For this pass, the simplest and safest answer is: ViewModel owns the refresh error state.

---

> Answers filled for all questions.
