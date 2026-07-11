# Responses for WEBRTC_TUNNEL_ANDROID_STATE_INTEGRITY_RECOVERY_FIX2

---

1. **Q:** Should `StartOutcome.PolicyBlocked` be added as a new variant in `StartOutcome.kt` before P0-001 begins, or should the policy-blocked path remain as a separate error without going through `StartupCompleted`?

   **A:**

2. **Q:** For P0-004 transactional reset: should `ConfigRepository.deleteConfigFileForTransactionalReset()` and `ForwardsRepository.restoreForTransactionalReset()` be `internal` or package-private?

   **A:**

3. **Q:** P1-002: should `ensureDefaultConfig()` in `ConfigRepository` be converted to use `writeConfigAtomically()`, or is it acceptable since it only runs when no config exists?

   **A:**

4. **Q:** P1-003: should `resolveNativeMode()` return `Result<TunnelMode>` (simpler) or introduce `NativeModeResolution` sealed type as shown in the spec?

   **A:**

5. **Q:** P1-004: should `TunnelRepository.recentLogs()` stop writing to `_logsError.value` and let `LogsViewModel` own error state completely via `LogsFetchResult.error`?

   **A:**

6. **Q:** Does the current `LogsViewModel` generation-based stale-result protection satisfy P1-004, or should the repository's `_logsError` direct write be removed first?

   **A:**

7. **Q:** For P1-005 network event delivery: is converting `NetworkPolicyManager.monitor()` to emit `StateFlow<NetworkStatus>` instead of `callbackFlow` in scope, or is Option B (logging failed `trySend`) sufficient for this pass?

   **A:**

8. **Q:** (P0-001) Should `prepareOfferIdentity()` stop setting `pausedByPolicy` and `repository.setPolicyBlocked()` when it encounters a policy block, and instead throw `StartupPolicyBlocked` for the coordinator to handle?

   **A:**

9. **Q:** (P0-002) Is the fake identity repository test approach (sentinel byte array verification) feasible with the current dependency injection structure?

   **A:**

10. **Q:** (P1-001) Should `ForwardsRepository.save()` be deleted entirely, or kept for rollback (since `rollbackReceipt()` uses the receipt pattern, not `save()`)?

    **A:**

11. **Q:** (Spec § P0-004) Does "stop on first failed stage" mean abort the loop immediately (avoiding any further mutations), or is it acceptable to collect all results and rollback after?

    **A:**

12. **Q:** (Spec § P1-003) Is the current `resolveNativeMode(null)` behavior (`TunnelMode.Offer` fallback) what needs to change, or is `null` already rejected?

    **A:**

13. **Q:** (Spec § P1-002) Should the `AtomicMoveNotSupportedException` fallback be added to `ConfigRepository.writeConfigAtomicallyLocked()`, or is that out of scope since it's internal?

    **A:**

14. **Q:** (Spec § 2.6) Should `TunnelRepository.recentLogs()` be converted from `runCatching` to `try/catch`, or is `runCatching` acceptable since the result is handled?

    **A:**

15. **Q:** (Cross-Cutting) Should `TransactionalResetCoordinator.resetConfiguration()` convert `runCatching` to `try/catch` per spec rule 2.6?

    **A:**

16. **Q:** (Cross-Cutting) `ensureDefaultConfig()` directly writes `configFile.writeText(...)`. Should this be converted to use the atomic writer?

    **A:**

17. **Q:** (Cross-Cutting) Should `TunnelRepository.recentLogs()` stop writing to the repository's `_logsError` and let `LogsViewModel` own error state completely via `LogsFetchResult.error`?

    **A:**

---

> Fill in the `A:` lines above and share back or paste the answers.