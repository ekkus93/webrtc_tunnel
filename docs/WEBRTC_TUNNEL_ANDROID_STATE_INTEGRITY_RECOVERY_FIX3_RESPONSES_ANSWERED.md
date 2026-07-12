# Responses: WebRTC Tunnel Android State-Integrity Recovery Fix 3

## Q&A

### Identity and startup

1. **Identity zeroization in `performStartupAttempt`**  
   Q: When converting from `runCatching` to explicit try/catch, should `identity.fill(0)` be in a `finally` block, or can it use `try-finally`? The current pattern has `try { ... } finally { identity.fill(0) }` INSIDE the `runCatching` scope — should this be preserved exactly, or should zeroization happen at a different level?  
   A: Preserve zeroization with `try/finally` around the owned identity buffer, but remove the outer `runCatching`.

   The ownership rule is:

   ```text
   whoever owns the plaintext identity bytes must wipe them in finally
   ```

   Recommended shape:

   ```kotlin
   private suspend fun runNativeStartWithIdentity(
       generation: Long,
       identity: ByteArray,
   ): StartOutcome {
       return try {
           runNativeStart(
               generation = generation,
               identity = identity,
           )
       } finally {
           identity.fill(0)
       }
   }
   ```

   Then `performStartupAttempt()` should use explicit `try/catch` outside that:

   ```kotlin
   private suspend fun performStartupAttempt(
       generation: Long,
   ): StartOutcome {
       return try {
           val prepared =
               prepareStartupInputs(
                   generation = generation,
               )

           runNativeStartWithIdentity(
               generation = generation,
               identity = prepared.identity,
           )
       } catch (cancelled: CancellationException) {
           throw cancelled
       } catch (blocked: StartupPolicyBlocked) {
           StartOutcome.PolicyBlocked(
               reason = blocked.message
                   ?: "Blocked by network policy",
           )
       } catch (aborted: StartupAborted) {
           StartOutcome.Aborted(
               reason = aborted.message
                   ?: "Startup aborted",
           )
       } catch (error: Throwable) {
           StartOutcome.UnexpectedFailure(error)
       }
   }
   ```

   Do **not** move zeroization to a higher level where ownership becomes ambiguous. The current `try { ... } finally { identity.fill(0) }` idea is correct; the problem is only the surrounding `runCatching`.

2. **`StartOutcome.Aborted` cascade**  
   Q: Changing `Aborted` from `data object` to `data class Aborted(val reason: String)` affects ALL handlers. `classifyStartResult` and `handleStartupCompleted` both reference it. Should the reason come from the exception message, or should there be a hardcoded fallback per abort reason?  
   A: Use the exception message when available, with a safe hardcoded fallback.

   Pattern:

   ```kotlin
   catch (aborted: StartupAborted) {
       StartOutcome.Aborted(
           reason = aborted.message
               ?: "Startup aborted",
       )
   }
   ```

   For known abort sources, prefer meaningful messages when throwing:

   ```kotlin
   throw StartupAborted(
       "Failed to prepare active config",
   )
   ```

   or:

   ```kotlin
   throw StartupAborted(
       "Stored identity is unavailable",
   )
   ```

   `classifyStartResult()` usually should not produce `Aborted`; it classifies native-start results. If it currently references `StartOutcome.Aborted`, update that reference to use:

   ```kotlin
   StartOutcome.Aborted(
       reason = "Startup aborted",
   )
   ```

   The rule is: specific message at throw site, fallback at catch site.

3. **`prepareActiveConfigForStart` parameters**  
   Q: Should this method keep its current parameters `(iceMode: String, advertisedIpv4: String?)` and only change the return type to `Result<Unit>`, or should the parameters change to match the template `(mode: TunnelMode, localIpOverride: String?, iceModeOverride: IceMode?)`?  
   A: Keep the current parameters for this pass and change only the return type/behavior.

   Do **not** widen the scope by changing the method signature to the template unless the current parameters are already wrong. The goal of P0-003 is narrow:

   ```text
   active config write failure must stop startup visibly
   ```

   So change:

   ```kotlin
   fun prepareActiveConfigForStart(
       iceMode: String,
       advertisedIpv4: String?,
   )
   ```

   to:

   ```kotlin
   suspend fun prepareActiveConfigForStart(
       iceMode: String,
       advertisedIpv4: String?,
   ): Result<Unit>
   ```

   or keep it non-suspending only if it does not call suspend writer APIs. Since it should use the serialized writer, `suspend` is likely correct.

   Preserve existing call sites as much as possible.

### Config writer

4. **`writeConfig` suspend vs non-suspend**  
   Q: `writeConfig` currently writes synchronously via `configFile.writeText`. Routing through `writeConfigAtomically` requires `suspend`. Should `writeConfig` become `suspend`, or should callers be updated to use `writeConfigAtomically` directly?  
   A: Prefer making `writeConfig` a suspending wrapper around `writeConfigAtomically()`.

   This preserves the public repository method while removing the direct-write bypass:

   ```kotlin
   suspend fun writeConfig(
       contents: String,
   ): Result<Unit> =
       writeConfigAtomically(contents)
   ```

   Then update callers/tests to handle `Result<Unit>`.

   If a test needs a quick direct file seed, do **not** use production `writeConfig()` for that. Use test fixture setup that writes test files directly in the test temp directory. Production repository methods should preserve production invariants.

5. **`recentLogs` suspend vs sync**  
   Q: The spec says `recentLogs` should use explicit try/catch, but the current `recentLogs` is a synchronous function (not `suspend`). If it's wrapped with `withContext` in the ViewModel, the try/catch pattern applies. Should `recentLogs` become `suspend`, or stay sync with try/catch?  
   A: It may stay synchronous if the underlying bridge call is synchronous, but it must still use explicit `try/catch`.

   Recommended low-churn shape:

   ```kotlin
   fun fetchRecentLogs(
       maxEvents: Int,
   ): LogsFetchResult {
       return try {
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
                   message =
                       SensitiveDataRedactor.redactText(
                           error.message
                               ?: "Log refresh failed",
                       ),
               ),
           )
       }
   }
   ```

   Then the ViewModel can keep:

   ```kotlin
   withContext(dispatcher) {
       repository.fetchRecentLogs(maxEvents)
   }
   ```

   Make it `suspend` only if the repository itself needs to switch dispatcher or call suspend APIs.

6. **Transactional reset `ResetSnapshot` model change**  
   Q: Adding `existed: Boolean` (or converting to `ConfigSnapshot` type) requires updating the coordinator constructor and tests. Does the coordinator constructor need updating to pass the new snapshot shape?  
   A: The coordinator constructor should not need to change just because the internal snapshot shape changes.

   Keep `ConfigSnapshot` and `ResetSnapshot` internal implementation details of `TransactionalResetCoordinator`.

   Expected model:

   ```kotlin
   private data class ConfigSnapshot(
       val existed: Boolean,
       val contents: String?,
   )

   private data class ResetSnapshot(
       val config: ConfigSnapshot,
       val setupInput: SetupConfigInput,
       val forwards: List<ForwardConfig>,
   )
   ```

   Tests should interact through the public coordinator behavior:

   ```text
   resetConfiguration()
   result
   final file state
   final setup state
   final forwards state
   ```

   Only update constructor parameters if tests currently inject snapshot-producing fakes directly. Prefer not to expose snapshot shape in the constructor.

### Network policy

7. **`NetworkPolicyManager` constructor for reporter**  
   Q: Adding a `NetworkPolicyEventReporter` requires a second dependency. Should this be a constructor parameter, or should the manager accept a more general dependency (e.g., `AppDependencies`)?  
   A: Use a narrow constructor parameter, not `AppDependencies`.

   Do not make `NetworkPolicyManager` depend on the whole app dependency graph.

   Recommended:

   ```kotlin
   interface NetworkPolicyEventReporter {
       fun reportNetworkPolicyEventDeliveryFailed(
           cause: Throwable?,
       )
   }
   ```

   Constructor:

   ```kotlin
   class NetworkPolicyManager(
       private val context: Context,
       private val reporter: NetworkPolicyEventReporter =
           NetworkPolicyEventReporter.Noop,
   )
   ```

   with:

   ```kotlin
   object Noop : NetworkPolicyEventReporter {
       override fun reportNetworkPolicyEventDeliveryFailed(
           cause: Throwable?,
       ) = Unit
   }
   ```

   Wire the real reporter from the service/app composition root. Tests can pass a fake reporter.

8. **`pausedByPolicy` invalidation boundaries**  
   Q: Which existing invalidation calls should be preserved vs. replaced? `handleStartupCompleted` currently invalidates on `VerifiedSuccess` and some failure paths — need to know the baseline before adding the `pausedByPolicy` check.  
   A: Preserve existing invalidations and add the missing explicit boundary invalidations. Do not remove an existing invalidation unless a test proves it is wrong.

   Baseline rule:

   ```text
   pending retry is valid only for the specific policy-paused generation it was created for
   ```

   Required invalidation points:

   - explicit Stop;
   - explicit Pause;
   - new explicit StartOffer;
   - AllowMeteredSession;
   - Destroy;
   - VerifiedSuccess;
   - non-policy startup failure/abort;
   - quarantine;
   - entering a new PolicyBlocked state for a different generation.

   `handleRetryPolicyResume()` must also guard:

   ```kotlin
   if (!pausedByPolicy.get()) {
       invalidatePendingPolicyRetry()
       return
   }
   ```

   For `PolicyBlocked` completion, clear stale retry from previous generations. A future `PolicyAllowed` should create a fresh retry/resume intent.

### Terminal states

9. **Terminal state helpers existence**  
   Q: Do `setPolicyBlocked`, `setLocalError`, `setNoNetwork`, `setConfigInvalid` exist as distinct methods, or are these inline mutations that need to be extracted into helpers for P1-007?  
   A: If distinct methods already exist, update those methods. If some are inline mutations, extract small helpers only where needed.

   Do not perform a broad repository refactor.

   Add the shared status helper:

   ```kotlin
   private fun TunnelStatus.withoutActivePeer():
       TunnelStatus =
       copy(
           remotePeerId = null,
           activeSessionCount = 0,
           mqttConnected = false,
       )
   ```

   Then apply it wherever local terminal statuses are created:

   ```kotlin
   _status.value =
       _status.value
           .copy(serviceState = ServiceState.PausedMeteredBlocked)
           .withoutActivePeer()
   ```

   or inside existing methods such as `setPolicyBlocked()` / `setLocalError()`.

10. **`resolveNativeMode(null)` behavior**  
    Q: What should happen when mode is `null` — should it throw, abort, or produce a specific error diagnostic? Currently it returns `null` and the downstream handling determines the outcome.  
    A: Returning `null` is acceptable if downstream handling reliably converts it into a visible schema error and failed verification.

    The required behavior is:

    ```text
    null mode cannot verify as Offer
    null mode produces native_status_schema_error or equivalent visible schema diagnostic
    startup/status verification fails safely
    ```

    Low-churn acceptable implementation:

    ```kotlin
    private fun resolveNativeMode(
        rawMode: String?,
    ): TunnelMode? =
        when (rawMode) {
            "offer" -> TunnelMode.Offer
            "answer" -> TunnelMode.Answer
            else -> null
        }
    ```

    Then downstream must do:

    ```kotlin
    val mode =
        resolveNativeMode(raw.mode)
            ?: return schemaError(
                "native_status_schema_error: missing or unknown mode",
            )
    ```

    If that downstream schema-error path is already in place, keep it and add tests. Do not throw unless the surrounding code is already exception-based.

### Reset tests

11. **`ResetStageResult` vs `RollbackStageResult`**  
    Q: Should both types share a common supertype for easier test assertions? Currently they are separate sealed interfaces with similar shapes.  
    A: No. Keep them separate.

    They represent different phases:

    ```text
    ResetStageResult = forward reset stage outcome
    RollbackStageResult = recovery/rollback stage outcome
    ```

    Sharing a supertype only for test convenience is unnecessary and can blur semantics.

    Instead add small test helpers:

    ```kotlin
    private fun List<RollbackStageResult>
        .failureFor(stage: ResetStage):
        RollbackStageResult.Failure? =
        filterIsInstance<RollbackStageResult.Failure>()
            .firstOrNull { it.stage == stage }
    ```

    and:

    ```kotlin
    private fun ResetResult.failedOrThrow():
        ResetResult.Failed =
        this as? ResetResult.Failed
            ?: error("Expected failed reset, got $this")
    ```

### Cross-cutting

12. **`runCatching` replacement scope**  
    Q: Three functions use `runCatching` that the spec says should use try/catch: `performStartupAttempt`, `recentLogs`, and potentially others. Are there additional `runCatching` sites in the startup/lifecycle path that should be converted?  
    A: Yes. Search and convert `runCatching` in critical startup/lifecycle/status/config-prep paths, but do not mechanically remove every `runCatching` in the entire app.

    Required search:

    ```bash
    rg "runCatching" android/app/src/main/java/com/phillipchin/webrtctunnel
    ```

    Convert if the site is in:

    ```text
    startup attempt
    lifecycle command processing
    policy resume/monitoring
    status polling
    active config preparation
    recent logs refresh
    transactional reset mutation/rollback boundary
    plaintext identity ownership
    ```

    It may remain if:

    ```text
    non-critical pure parsing/helper
    no CancellationException concern
    no lifecycle/security/persistence state mutation
    Result is immediately and explicitly folded
    ```

    When in doubt for lifecycle/security/persistence code, use explicit `try/catch`.

13. **P0-003 depends on P1-002**  
    Q: `prepareActiveConfigForStart` returning `Result<Unit>` depends on the atomic writer path already working. Should P1-002 (config writer serialization) be completed before P0-003 (config preparation failure)?  
    A: Yes, do the config writer fix first, even if it is labeled P1.

    Practical order:

    1. Make `writeConfig()` / config writer serialization correct.
    2. Ensure `writeConfigAtomically()` has fallback and cleanup.
    3. Change `prepareActiveConfigForStart()` to return `Result<Unit>`.
    4. Make startup fail visibly when active config preparation fails.

    The labels reflect severity of observed behavior, but implementation dependencies matter. It is fine to complete P1-002 before P0-003.

14. **Preference failure test targets**  
    Q: `SetupSaveControllerTest` and `NetworkPolicyViewModelTest` are referenced but may have been refactored since the spec was written. Do these test classes exist and match the spec expectations?  
    A: Use the current test class names if they changed. The requirement is behavioral, not tied to exact class names.

    Target whichever current tests own these behaviors:

    ```text
    Network policy preference save
    setup preference persistence
    setup save/apply workflow
    snackbar/error event emission
    ```

    If `NetworkPolicyViewModelTest` exists, strengthen it there. If the logic moved, add the test next to the new owner.

    If `SetupSaveControllerTest` no longer owns preference persistence, test the class that does. Do not skip the tests because names changed.

---

Please use these answers as the implementation decisions for Fix 3.
