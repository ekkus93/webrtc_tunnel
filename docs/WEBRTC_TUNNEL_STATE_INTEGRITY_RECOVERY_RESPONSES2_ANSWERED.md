# Responses for WEBRTC_TUNNEL_STATE_INTEGRITY_RECOVERY_SPEC — Open Questions (17–23)

---

17. **Q:** `runCatching` vs `try`/`catch` in `TunnelLifecycleCoordinator.processCommands()`: The spec explicitly marks `runCatching` as "Wrong" for critical paths, requiring `try`/`catch` with `CancellationException` rethrown. Should `processCommands()` be converted to `try`/`catch`, or is `runCatching` acceptable where the result is handled?

    **A:** Convert `processCommands()` / per-command handling to explicit `try`/`catch`.

    `runCatching` is not acceptable in this critical lifecycle path for this recovery pass, even if the `Result` is handled. It makes cancellation semantics too easy to obscure or accidentally regress. The spec’s example is intentional.

    Use this shape around each command handling operation:

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

    If the current implementation loops over `for (command in commands)`, wrap each individual command handling operation, not the whole processor in a way that terminates the processor after one unexpected command failure.

    Intended invariant:

    ```text
    cancellation -> propagates and stops the coroutine normally
    unexpected command failure -> visible redacted error, processor continues
    service teardown -> coordinator is cancelled/closed deliberately
    ```

---

18. **Q:** The `failedAutoResumeLeavesPausedByPolicyTrueForNextRetry` test still loops network events until success via `waitForCondition` that re-emits `onAvailable` on each iteration. Should this test be rewritten to the one-event invariant (send exactly one event, assert outcome), or does the loop serve a different testing purpose here?

    **A:** Rewrite it to the one-event invariant.

    The loop that re-emits `onAvailable` inside `waitForCondition` is the workaround the recovery spec is trying to eliminate. It can hide the bug where one later `PolicyAllowed` event is not sufficient because startup completion ownership has not yet been cleared.

    The replacement test should send exactly:

    ```text
    PolicyAllowed #1 starts resume attempt #1
    attempt #1 fails or is completing
    PolicyAllowed #2 arrives while attempt #1 completion is still pending
    NO third PolicyAllowed event
    StartupCompleted #1 is handled
    retry attempt #2 runs exactly once
    ```

    Assert the native-start call count exactly. For example:

    ```kotlin
    assertEquals(
        2,
        fakeBridge.startOfferCalls,
    )
    ```

    or the project’s equivalent counter.

    Do not keep the looping test as proof of the retry invariant. It may remain only if it is renamed and repurposed as a separate “repeated network events are coalesced / harmless” test, and it must not be used to satisfy P0-004.

---

19. **Q:** Has Logs generation ordering (P1-007) been implemented? The spec requires generation checks for log list and log error together, but this wasn't visible in the files reviewed.

    **A:** Treat P1-007 as **not verified / not complete** unless the current code clearly ties the log list and log error to the same generation.

    The requirement is not just:

    ```text
    older log list cannot overwrite newer log list
    ```

    It is:

    ```text
    older result cannot overwrite newer logs OR newer logsError
    ```

    The safe implementation is for the repository to return one typed result without mutating shared error state before the ViewModel generation check.

    Recommended model:

    ```kotlin
    data class LogsFetchResult(
        val logs: List<LogEntry>,
        val error: String?,
    )
    ```

    ViewModel ownership:

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

    If `TunnelRepository.recentLogs()` still mutates repository-level `logsError` before the ViewModel generation check, then P1-007 is not implemented.

    Required tests:

    ```text
    older failure cannot set logsError after newer success
    older success cannot clear newer failure
    older success cannot replace newer list
    UI still displays the current error when present
    ```

---

20. **Q:** Should status poll failures be published as visible errors with code `status_poll_failed`, or is silent swallowing intentional ("best-effort" design)?

    **A:** Publish status poll failures visibly with code `status_poll_failed`.

    Silent swallowing is not intentional for this path. Status polling is part of the controller’s state-integrity model. If it fails unexpectedly, the operator/user needs a visible redacted diagnostic.

    “Best effort” is acceptable for low-value telemetry. It is not acceptable for controller status refresh.

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

    If repeated failures would be noisy, add rate limiting or coalescing. Do not make the failure invisible.

    Remove any remaining pattern like this when the returned `Result` is discarded:

    ```kotlin
    runCatching {
        repository.refreshStatus()
    }
    ```

---

21. **Q:** Is notification retry wired through `submitLifecycleCommand` (thus going through the coordinator and guarded by quarantine), or does it have a direct path that needs the `requireRuntimeStartAllowed()` guard explicitly added?

    **A:** Assume notification retry may have a direct path until proven otherwise, and guard it explicitly.

    Even if a notification action currently routes through `submitLifecycleCommand`, the quarantine guard should also live at the lowest shared start/resume boundary. Defense in depth is appropriate because the invariant is simple:

    ```text
    no start/resume path may start native runtime while nativeRuntimeUncertain == true
    ```

    Required approach:

    1. Keep notification actions routed through the coordinator where possible.
    2. Add `requireRuntimeStartAllowed()` at the actual shared start/resume entry point, such as `OfferCoordinator.startOffer()` or the service method that ultimately invokes native start.
    3. Make command handlers return early with a visible `native_runtime_quarantined` event/message if blocked.

    Tests should cover:

    ```text
    notification retry while quarantined -> native start not called
    normal StartOffer while quarantined -> native start not called
    explicit STOP while quarantined -> still allowed
    verified STOP clears quarantine -> later StartOffer allowed
    ```

    Do not rely solely on notification routing discipline.

---

22. **Q:** Have sentinel-byte tests been written for P0-006 identity zeroization failure paths? The spec requires tests asserting zeroization on every failure path.

    **A:** If the sentinel-byte tests are not visible in the current test tree, treat P0-006 as incomplete.

    Generic tests that only verify a helper can zero an array are not enough. The tests must prove that the actual failure paths zero the actual plaintext buffer after it has been read/decrypted.

    Required tests:

    ```text
    private identity validation throws after bytes are read
    private identity validation returns invalid after bytes are read
    public identity read throws after private bytes are read
    peer ID derivation throws after private bytes are read
    later startup preparation fails after ownership transfer and final owner wipes
    ```

    Use a fake identity repository that returns a mutable sentinel `ByteArray`, then assert that the array handed to production code becomes all zeros.

    Example assertion:

    ```kotlin
    assertTrue(
        sentinel.all {
            it == 0.toByte()
        },
    )
    ```

    If production code copies the bytes, the fake must expose or track the actual array handed to the code under test. Do not assert against only the original source array if the production path receives a copy.

---

23. **Q:** Should `onDestroy()` update `nativeStopVerified` after performing fallback `repository.stop()`, or is this intentionally omitted because the service is being destroyed?

    **A:** Yes. `onDestroy()` should update `nativeStopVerified` after fallback `repository.stop()`.

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

    This is not about keeping the dying service useful. It is about keeping the lifecycle truth model internally consistent and making teardown tests precise.

---

> Answers filled for questions 17–23.
