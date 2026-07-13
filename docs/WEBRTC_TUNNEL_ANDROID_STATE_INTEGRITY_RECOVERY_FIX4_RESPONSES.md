# Responses — WEBRTC_TUNNEL_ANDROID_STATE_INTEGRITY_RECOVERY_FIX4_SPEC.md / TODO.md

1. P1-006 mechanism: The `NetworkPolicyManager` uses `Flow` (not a channel). The spec's `trySend` + `ClosedSendChannelException` pattern doesn't apply. Should the network policy delivery diagnostic handle `Flow` collector failures (cancellation/closed collector), or is there a separate channel-based delivery path?
A: 

2. `refreshStatus() runCatching` (line 388): There's a `runCatching` wrapping `repository.refreshStatus()` in `TunnelForegroundService.kt` that wasn't listed for removal. Is this intentionally out of scope for Fix 4?
A: 

3. `prepareOfferIdentity() runCatching` (line 713): There's a `runCatching` wrapping identity read. This is nested inside `performStartupAttempt`, so the outer fix will handle it — should the inner `runCatching` also be replaced with explicit `try/catch`?
A: 

4. P2-001 signoff: The signoff evidence includes workflow run URLs/SHAs. Is there a GitHub Actions workflow configured for this repo, or should P2-001 be adapted for local evidence only?
A: 

5. `writeConfigAtomicallyLocked` signature: The spec shows a single-arg signature, but the actual code takes `(configFile: File, contents: String)`. Should the fix keep the current two-arg signature?
A: 

6. P1-002: `deleteConfigFileForTransactionalReset()` currently uses `runCatching` with `configFile.delete()`. The spec recommends `Files.deleteIfExists` (NIO). Should the fix migrate to `Files.deleteIfExists` or just add proper failure handling to the existing `File.delete()` call?
A: 

7. P1-003: `SnapshotCaptureException` doesn't exist yet and needs to be defined. Should this be a new custom exception class, or can it reuse an existing exception type (e.g., wrapped `IOException`)?
A: 

8. P0-002: `invalidatePendingPolicyRetry()` is already called in many places (12+ call sites). The task is about adding *missing* calls. Which specific events currently don't call it and should? (The spec lists: Stop, Pause, StartOffer, AllowMeteredSession, Destroy, VerifiedSuccess, VerificationFailure, UnexpectedFailure, Aborted, quarantine set, PolicyBlocked.)
A: 

9. P1-007 native schema tests: The spec mentions `native_status_schema_error` for missing/future modes. What native schema mapping code needs to be tested, and where is the mode/runtime-state parsing in the codebase?
A: 

---

Please fill in the `A:` lines above and share the file or paste the answers back.