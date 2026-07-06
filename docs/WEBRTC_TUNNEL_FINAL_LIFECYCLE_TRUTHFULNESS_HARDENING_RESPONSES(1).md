# Responses — WEBRTC_TUNNEL_FINAL_LIFECYCLE_TRUTHFULNESS_HARDENING_SPEC.md / _TODO.md

1. Q: P0-001's classifier references `DaemonError::OfferAcceptSupervisorFailed`, which doesn't exist today. The "supervisor channel closed unexpectedly" case is a separate, already-unconditionally-fatal branch (`offer/mod.rs:320-345`, currently `DaemonError::Logging(...)`) that never goes through the buggy cooldown/recovery path P0-001 is actually fixing. Should I (a) add this new variant anyway for classifier completeness/symmetry, replacing the generic `DaemonError::Logging(...)` currently used there, or (b) scope the classifier to just the variants that actually flow through the buggy active-session path (`OfferAcceptWorkerFailed` + P0-004's new `OfferAcceptMonitorJoinFailed`), leaving the supervisor-channel-closed path untouched?
   A: Choose **(b)**. Scope the classifier to the infrastructure-failure variants that can actually flow through the buggy active-session recovery path: `OfferAcceptWorkerFailed` and P0-004's `OfferAcceptMonitorJoinFailed`. Do **not** add `OfferAcceptSupervisorFailed` solely for symmetry.

   The existing supervisor-channel-closed branch is already unconditionally daemon-fatal and does not enter cooldown/recovery, so it may remain a direct fatal branch. Keep that behavior explicit and covered by a test if one already exists. If you happen to refactor that branch while implementing the task, replacing the generic `DaemonError::Logging(...)` with a specific error variant is acceptable as a separate error-taxonomy cleanup, but it is **not required** for P0-001 and must not expand the scope unnecessarily.

   Required invariant: every accept-worker/monitor infrastructure failure that can emerge from `run_offer_session()` must bypass normal session cooldown/recovery and terminate through the daemon finalizer. The already-fatal supervisor-channel-close path must remain fatal.

2. Q: `pauseForPolicy()`'s `pausedByPolicy` flag is set to `true` *before* `repository.stop()` even runs, and is read elsewhere (`TunnelForegroundService.kt:90-91`) to decide auto-resume-on-unmetered-network behavior. If `repository.stop()` fails, should `pausedByPolicy` remain `true` even though the tunnel didn't actually stop — risking it being silently excluded from auto-resume-on-unmetered logic — or should the flag/policy-blocked state be left unset/rolled back on failure so a retry path stays open?
   A: Roll it back. If `repository.stop()` fails, `pausedByPolicy` must **not** remain `true`, and the service must not publish the policy-blocked/paused state.

   `pausedByPolicy` describes an accomplished runtime state, not merely the intent to pause. Leaving it set after a failed stop would lie about the tunnel state and could suppress the later auto-resume/retry logic even though the tunnel never stopped cleanly.

   Required sequence:
   1. Do not commit `pausedByPolicy = true` before stop succeeds, or save the previous value and restore it on failure.
   2. Call `repository.stop()`.
   3. On success: set `pausedByPolicy = true`, publish the policy-paused state, and continue normal policy bookkeeping.
   4. On failure: leave/restore `pausedByPolicy = false`, publish the stop error, do not publish a normal paused/policy-blocked state, and keep the retry/reevaluation path open.

   If the UI needs to remember that a policy pause was *requested* but failed, represent that separately from `pausedByPolicy`; do not overload the successful-state flag.

3. Q: P0-009 (real answer task panic test) requires building an entire test-injection subsystem for answer sessions from scratch, mirroring the offer side's existing `session_hook`/`OfferSessionTestEvent` machinery, which doesn't currently exist on the answer side at all. This is a materially bigger lift than the TODO's "add a test-only hook" phrasing suggests. Confirming you want the full build-out (not a reduced-scope substitute)?
   A: I want the **real spawned answer-session task panic path tested deterministically**, but I do **not** require a large general-purpose mirror of the entire offer-side test framework.

   Build the smallest test-only injection mechanism needed to prove the full production supervision chain:
   `real tokio::spawn answer session task -> deterministic panic inside that task -> JoinHandle/FuturesUnordered observes panic -> registry cleanup -> remaining sessions drain -> terminal status attempted -> daemon returns Err`.

   A fabricated `AnswerTaskCompletion { outcome: Err(...) }` passed directly to the completion handler is not an acceptable substitute, because it bypasses the exact spawn/join path this task is meant to verify.

   Prefer a minimal `#[cfg(any(test, debug_assertions))]` hook, for example:
   - a test-only one-shot/action channel stored in `AnswerSessionTaskDeps`; or
   - a small `AnswerSessionTestHook` with a deterministic action such as `PanicAfterStarted`.

   The test should have an explicit synchronization point so it knows the real task has started and is registered before triggering the panic. Do not use a guessed sleep. Keep the hook entirely out of production behavior and do not build extra answer-side event machinery beyond what this test actually needs.

   So the answer is: **yes to the real end-to-end panic injection; no to unnecessary framework overbuilding**.

---

Fill in the `A:` lines above and share this file back (or paste the answers) when ready. No implementation will begin until then.
