# Responses for WEBRTC_TUNNEL_STATE_INTEGRITY_RECOVERY_SPEC / TODO

---

1. **Q:** Does `SensitiveDataRedactor` already exist in the codebase, or does it need to be created? (Blocks nearly all P0 tasks)

   **A:** 

2. **Q:** Does `LifecycleCommand.StartupCompleted` with a typed `StartupCompletion` payload already exist, or is it new? (Prerequisite for P1-006)

   **A:** 

3. **Q:** Should rollback continue for all remaining stages even if one rollback stage fails? (Affects P0-001 rollback semantics)

   **A:** 

4. **Q:** Is `Channel.UNLIMITED` intentional for the coordinator command channel, or should there be a bounded size with backpressure? (P0-003 memory concern)

   **A:** 

5. **Q:** Should `prepareStartupInputs()` have its own distinct failure boundary separate from `runNativeStart()`, or are they intentionally grouped under one `try/catch`? (P0-005 vs P0-004 scope)

   **A:** 

6. **Q:** Option A or Option B for P1-010 (typed `StartOutcome` bridge)? Option B is lower risk and defers to a future pass.

   **A:** 

7. **Q:** Should the signoff condition "transactional reset restores exact previous state" be relaxed to "attempted restore with per-stage outcome reported"? (If rollback can partially fail)

   **A:** 

---

8. **Q:** (P0-004) Is `LifecycleCommand.StartupCompleted` a new command or existing? If new, this is a prerequisite for P1-006.

   **A:** 

9. **Q:** (P0-005) Should `prepareStartupInputs()` have its own outer boundary separate from `runNativeStart()` if preparation has distinct failure modes?

   **A:** 

10. **Q:** (P1-001) How many callers use the snapshot/rollback pattern? Is this a ViewModel-only change or does it touch the service layer?

    **A:** 

11. **Q:** (P1-003) Is there an existing config-write mutex, or does this task need to introduce one?

    **A:** 

12. **Q:** (P1-006) Confirm whether P1-006 cannot start until P0-004 is complete.

    **A:** 

13. **Q:** (Cross-Cutting #1) P1-006 depends on P0-004's `StartupCompletion` model — is this dependency explicit and acknowledged?

    **A:** 

14. **Q:** (Cross-Cutting #4) `NativeRuntimeQuarantinedException` — should this be a custom `Exception` subclass or a sealed interface/result type?

    **A:** 

15. **Q:** (Cross-Cutting #5) Config file mutex (P1-003) and identity zeroization (P0-006) both touch config/identity file I/O — should these be coordinated in the same pass?

    **A:** 

16. **Q:** (Cross-Cutting #6) The TODO requires deleting tests before replacement tests are added. Should the deleted tests be removed first, or should replacement tests be added alongside before cleanup?

    **A:** 

---

> Fill in the `A:` lines above and share back or paste the answers.
