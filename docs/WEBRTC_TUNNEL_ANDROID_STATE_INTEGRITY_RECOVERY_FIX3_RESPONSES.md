# Responses: WebRTC Tunnel Android State-Integrity Recovery Fix 3

## Q&A

### Identity and startup

1. **Identity zeroization in `performStartupAttempt`**
   Q: When converting from `runCatching` to explicit try/catch, should `identity.fill(0)` be in a `finally` block, or can it use `try-finally`? The current pattern has `try { ... } finally { identity.fill(0) }` INSIDE the `runCatching` scope — should this be preserved exactly, or should zeroization happen at a different level?
   A:

2. **`StartOutcome.Aborted` cascade**
   Q: Changing `Aborted` from `data object` to `data class Aborted(val reason: String)` affects ALL handlers. `classifyStartResult` and `handleStartupCompleted` both reference it. Should the reason come from the exception message, or should there be a hardcoded fallback per abort reason?
   A:

3. **`prepareActiveConfigForStart` parameters**
   Q: Should this method keep its current parameters `(iceMode: String, advertisedIpv4: String?)` and only change the return type to `Result<Unit>`, or should the parameters change to match the template `(mode: TunnelMode, localIpOverride: String?, iceModeOverride: IceMode?)`?
   A:

### Config writer

4. **`writeConfig` suspend vs non-suspend**
   Q: `writeConfig` currently writes synchronously via `configFile.writeText`. Routing through `writeConfigAtomically` requires `suspend`. Should `writeConfig` become `suspend`, or should callers be updated to use `writeConfigAtomically` directly?
   A:

5. **`recentLogs` suspend vs sync**
   Q: The spec says `recentLogs` should use explicit try/catch, but the current `recentLogs` is a synchronous function (not `suspend`). If it's wrapped with `withContext` in the ViewModel, the try/catch pattern applies. Should `recentLogs` become `suspend`, or stay sync with try/catch?
   A:

6. **Transactional reset `ResetSnapshot` model change**
   Q: Adding `existed: Boolean` (or converting to `ConfigSnapshot` type) requires updating the coordinator constructor and tests. Does the coordinator constructor need updating to pass the new snapshot shape?
   A:

### Network policy

7. **`NetworkPolicyManager` constructor for reporter**
   Q: Adding a `NetworkPolicyEventReporter` requires a second dependency. Should this be a constructor parameter, or should the manager accept a more general dependency (e.g., `AppDependencies`)?
   A:

8. **`pausedByPolicy` invalidation boundaries**
   Q: Which existing invalidation calls should be preserved vs. replaced? `handleStartupCompleted` currently invalidates on `VerifiedSuccess` and some failure paths — need to know the baseline before adding the `pausedByPolicy` check.
   A:

### Terminal states

9. **Terminal state helpers existence**
   Q: Do `setPolicyBlocked`, `setLocalError`, `setNoNetwork`, `setConfigInvalid` exist as distinct methods, or are these inline mutations that need to be extracted into helpers for P1-007?
   A:

10. **`resolveNativeMode(null)` behavior**
    Q: What should happen when mode is `null` — should it throw, abort, or produce a specific error diagnostic? Currently it returns `null` and the downstream handling determines the outcome.
    A:

### Reset tests

11. **`ResetStageResult` vs `RollbackStageResult`**
    Q: Should both types share a common supertype for easier test assertions? Currently they are separate sealed interfaces with similar shapes.
    A:

### Cross-cutting

12. **`runCatching` replacement scope**
    Q: Three functions use `runCatching` that the spec says should use try/catch: `performStartupAttempt`, `recentLogs`, and potentially others. Are there additional `runCatching` sites in the startup/lifecycle path that should be converted?
    A:

13. **P0-003 depends on P1-002**
    Q: `prepareActiveConfigForStart` returning `Result<Unit>` depends on the atomic writer path already working. Should P1-002 (config writer serialization) be completed before P0-003 (config preparation failure)?
    A:

14. **Preference failure test targets**
    Q: `SetupSaveControllerTest` and `NetworkPolicyViewModelTest` are referenced but may have been refactored since the spec was written. Do these test classes exist and match the spec expectations?
    A:

---

Please fill in the `A:` lines and share back when ready.