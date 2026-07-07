# Responses: Ordered Lifecycle, Verified Start, and Policy-Integrity Release-Signoff

Covers the spec and TODO review for:
- `WEBRTC_TUNNEL_ORDERED_LIFECYCLE_VERIFIED_START_POLICY_SIGNOFF_SPEC.md`
- `WEBRTC_TUNNEL_ORDERED_LIFECYCLE_VERIFIED_START_POLICY_SIGNOFF_TODO.md`

---

## Questions requiring clarification before implementation

1. **`lifecycleGeneration` and `startupJob` definition**
Q: P0-003 references `lifecycleGeneration.incrementAndGet()` and P0-004 references `startupJob?.isActive`. These variables are not introduced in the P0-001 command-processor snippets. Should they be introduced in P0-001 as part of the command processor foundation, or do they already exist in `TunnelForegroundService`?
A:

2. **`cancelStartupJobAndJoinLocked()` existence**
Q: P0-003's `handlePolicyBlocked` calls `cancelStartupJobAndJoinLocked()`. Does this function (or an equivalent) exist in the current codebase, or does it need to be created as part of the command processor?
A:

3. **`reporter` object ownership and interface**
Q: P0-002 and P0-006 reference `reporter.stopStatusPollingAndJoin()` and `reporter.publishError()`. Does a `reporter` object exist in the current `TunnelForegroundService` with these methods, or does it need to be created/referenced differently?
A:

4. **`refreshStatusResult()` in `TunnelRepository`**
Q: P0-002 replaces the start result with a verified status check using `refreshStatusResult()`. Does the current repository have a `Result<Unit>`-returning status refresh method, or does the current `refreshStatus()` need to be adapted to return a `Result`?
A:

5. **Network monitor component**
Q: P0-006's `onDestroy` cleanup needs to "cancel network monitor." What is the network monitor component in the current code? Does it have a cancellation mechanism (coroutine job, channel close, etc.) that can be joined?
A:

6. **`ListenState.Error` enum value**
Q: P1-004 says unknown listen state should become `ListenState.Error`. Does the `ListenState` enum already have an `Error` variant, or does it need to be added before P1-004 can be implemented?
A:

7. **`ForwardsRepository` mutex**
Q: P1-002 assumes a mutex exists in `ForwardsRepository` for `saveIfRevisionMatches`. Does the current repository already have mutex-based serialization, or does the mutex need to be added?
A:

8. **`LogsViewModel.kt` or log consumer**
Q: P0-005 mentions modifying `LogsViewModel.kt` or "current log consumer." What is the exact file that consumes logs from `TunnelRepository` and needs to handle the new `logsError` state flow?
A:

9. **`SetupForwardsController.kt` or setup mutation controller**
Q: P1-003 mentions modifying `SetupForwardsController.kt` "or actual setup mutation controller." What is the exact file that handles forward mutations from the setup flow?
A:

10. **P0-003 Test C — policy block while state says Error**
Q: Test C says "if runtime may still exist, policy command must still attempt cleanup." When repository state is `Error`, what exactly constitutes "cleanup" — does it call `repository.stop()` regardless, and if so, is a second stop after an error state safe/idempotent?
A:

11. **`SensitiveDataRedactor.redactText()` availability**
Q: P0-005 uses `SensitiveDataRedactor.redactText()` in the logs error. Is this utility already available and does it accept an arbitrary string for redaction?
A:

12. **Spec §11.1 vs §4.2 — unknown mode interaction**
Q: §4.2 says start verification checks `isTunnelActiveOrStarting()`, and §11.1 says unknown native mode should set `serviceState = Error`. If the native status returns an unknown mode during start verification, does the verification fail (because Error is not in active-or-starting), making the explicit §11.1 error handling redundant for the start path? Or should the error be set first, then verification naturally fails?
A:

13. **Implementation timing**
Q: Should I begin implementing immediately starting with Stage 1 (P0-001 + P1-001), or should these answers be provided first?
A:

---

Fill in the `A:` lines with your answers and share back when ready.
