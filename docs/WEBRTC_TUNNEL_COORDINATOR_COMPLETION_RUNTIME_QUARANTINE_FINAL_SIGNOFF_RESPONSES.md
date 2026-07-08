# Responses: WEBRTC_TUNNEL_COORDINATOR_COMPLETION_RUNTIME_QUARANTINE_FINAL_SIGNOFF (Spec + TODO Review)

## Questions for the User

1. P0-001 architecture: Is the `StartupCompleted` lifecycle command a full replacement for the current startup completion flow, or an incremental addition? The current code handles startup completion inline in `runOfferStart()`.
A:

2. P0-004 vs P0-005 coordination: Runtime quarantine blocks automatic restart, but failed STOP keeps the service alive. Should quarantine clear after a verified STOP, or remain until explicitly cleared?
A:

3. P0-007 behavioral change: The spec says "Do not clear on verified startup success." This changes the current behavior where metered allowance is cleared on success. Should there be a migration path or is this a breaking change?
A:

4. P1-005 vs P1-006: Forwards reset vs partial reset reporting. Should `resetForwards()` report partial success/failure, or should partial reporting be a separate concern?
A:

5. P1-007 config write serialization: Current code may use `config.toml.tmp`. Should this be changed to a unique temp path per write, or is a shared mutex sufficient?
A:

6. P1-013 auto-resume: Initially policy-blocked startup should become auto-resumable. Does this add a retry mechanism, or does it convert the blocked startup to a pending retry?
A:

7. Test coverage: The TODO mentions 18 P0/P1 tasks with acceptance criteria. Should each task have a dedicated test, or is focused regression testing sufficient?
A:

## TODO Review Questions

8. P0-001: Is the `StartupCompleted` command a full rewrite of the startup completion flow, or an incremental change that preserves existing patterns?
A:

9. P0-002: The task replaces `RetryPolicyResume` with generation-bound retry. Should the current `RetryPolicyResume` object be converted to a data class with `expectedGeneration`, or added alongside?
A:

10. P0-004: Runtime quarantine blocks all automatic restart. Should this apply to policy retry only, or also to manual user-initiated restart attempts?
A:

11. P0-005: Failed STOP retention keeps service alive on failure. Current code calls `stopForegroundAndSelf()` on failure. Is this a breaking change to the STOP flow that requires user testing?
A:

12. P0-007: Metered allowance clearing on failure vs success. Should there be tests for both success (allowance preserved) and failure (allowance cleared) paths?
A:

13. P0-008: Identity wiping uses ownership transfer pattern in spec. Is the pattern (`transferred = false`) required for correctness, or is the current `finally { fill(0) }` sufficient?
A:

14. P1-001: Forwards mutation receipts vs current `ForwardsSnapshot`. Should `ForwardsSnapshot` be renamed to `ForwardsMutationReceipt`, or is `ForwardsSnapshot` being augmented?
A:

15. P1-005: Settings reset currently calls `forwardsStore.saveForwards(emptyList())` in SettingsViewModel. Should SettingsViewModel be updated to call `resetForwards()` after repository method is added?
A:

16. P1-006: Partial reset reporting applies to forwards reset only, or to config reset (config.toml, preferences, etc.) as well?
A:

17. P1-007: Config write serialization uses "unique temp file". Should this be a UUID-based temp path per write, or a counter-based path?
A:

18. P1-013: Initially policy-blocked startup auto-resume. Does this add a retry mechanism (like `RetryPolicyResume`), or does it convert the blocked startup to a pending retry via the existing mechanism?
A:

19. P1-014: Log refresh serialization mentions cancellation or generation. Current code may not serialize overlapping refreshes. Should this use a `refreshJob` pattern or generation counter?
A:

20. P1-015: LogsError UI wiring. Should `logsError` be added to LogsScreen as an error banner/card, or as a toast/snackbar?
A:

21. P1-016: Preference write failures should be visible. Should these be surfaced as error snackbars, or logged to diagnostics?
A:

## Cross-Cutting Coordination Questions

22. P0-001 vs P0-002 coupling: These tasks are coupled (StartupCompleted command and generation-bound retry). Should they be implemented together in a single commit, or as separate commits with tests for each?
A:

23. P0-004 vs P0-005 coordination: Runtime quarantine (P0-004) blocks restart, but failed STOP retention (P0-005) keeps service alive. Should quarantine apply only to automatic restart, or also block user-initiated STOP?
A:

24. P0-007 vs P1-011: Metered allowance clearing (P0-007) vs nativeStopVerified tracking (P1-011). Both affect lifecycle state after stop/pause. Should these be coordinated in the same commit?
A:

25. P1-005 vs P1-006: Forwards reset (P1-005) and partial reset reporting (P1-006). Does P1-006's stage tracking affect P1-005's implementation?
A:

26. P1-008 vs P1-009: Unknown mode handling (P1-008) and unknown listen state diagnosis (P1-009). Both relate to native schema drift. Should these be implemented together?
A:

27. Spec §2.2: `UnexpectedFailure` is a new `StartupCompletion` variant. Should this replace `NativeStartFailure` in some cases, or is it a separate path for unhandled exceptions?
A:

28. Spec §3: `pendingPolicyResumeGeneration` replaces `pendingPolicyResume` boolean. Is this a direct replacement, or does the boolean remain during transition?
A:

29. Spec §9: `start_verification_cleanup_failed` sticky cleanup code. Current code has `lastCleanupError` but may not include this code. Should `start_verification_cleanup_failed` be added to the sticky classification?
A:

---

Fill in the `A:` lines above with your answers, then share the file or paste the answers back for implementation guidance.
