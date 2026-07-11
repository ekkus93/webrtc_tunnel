# Responses for WEBRTC_TUNNEL_STATE_INTEGRITY_RECOVERY_SPEC — Open Questions (17–23)

---

17. **Q:** `runCatching` vs `try`/`catch` in `TunnelLifecycleCoordinator.processCommands()`: The spec explicitly marks `runCatching` as "Wrong" for critical paths, requiring `try`/`catch` with `CancellationException` rethrown. Should `processCommands()` be converted to `try`/`catch`, or is `runCatching` acceptable where the result is handled?

    **A:** 

18. **Q:** The `failedAutoResumeLeavesPausedByPolicyTrueForNextRetry` test still loops network events until success via `waitForCondition` that re-emits `onAvailable` on each iteration. Should this test be rewritten to the one-event invariant (send exactly one event, assert outcome), or does the loop serve a different testing purpose here?

    **A:** 

19. **Q:** Has Logs generation ordering (P1-007) been implemented? The spec requires generation checks for log list and log error together, but this wasn't visible in the files reviewed.

    **A:** 

20. **Q:** Should status poll failures be published as visible errors with code `status_poll_failed`, or is silent swallowing intentional ("best-effort" design)?

    **A:** 

21. **Q:** Is notification retry wired through `submitLifecycleCommand` (thus going through the coordinator and guarded by quarantine), or does it have a direct path that needs the `requireRuntimeStartAllowed()` guard explicitly added?

    **A:** 

22. **Q:** Have sentinel-byte tests been written for P0-006 identity zeroization failure paths? The spec requires tests asserting zeroization on every failure path.

    **A:** 

23. **Q:** Should `onDestroy()` update `nativeStopVerified` after performing fallback `repository.stop()`, or is this intentionally omitted because the service is being destroyed?

    **A:** 

---

> Fill in the `A:` lines above and share back or paste the answers.
