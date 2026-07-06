# Responses — WEBRTC_TUNNEL_FINAL_LIFECYCLE_TRUTHFULNESS_HARDENING_SPEC.md / _TODO.md

1. Q: P0-001's classifier references `DaemonError::OfferAcceptSupervisorFailed`, which doesn't exist today. The "supervisor channel closed unexpectedly" case is a separate, already-unconditionally-fatal branch (`offer/mod.rs:320-345`, currently `DaemonError::Logging(...)`) that never goes through the buggy cooldown/recovery path P0-001 is actually fixing. Should I (a) add this new variant anyway for classifier completeness/symmetry, replacing the generic `DaemonError::Logging(...)` currently used there, or (b) scope the classifier to just the variants that actually flow through the buggy active-session path (`OfferAcceptWorkerFailed` + P0-004's new `OfferAcceptMonitorJoinFailed`), leaving the supervisor-channel-closed path untouched?
   A:

2. Q: `pauseForPolicy()`'s `pausedByPolicy` flag is set to `true` *before* `repository.stop()` even runs, and is read elsewhere (`TunnelForegroundService.kt:90-91`) to decide auto-resume-on-unmetered-network behavior. If `repository.stop()` fails, should `pausedByPolicy` remain `true` even though the tunnel didn't actually stop — risking it being silently excluded from auto-resume-on-unmetered logic — or should the flag/policy-blocked state be left unset/rolled back on failure so a retry path stays open?
   A:

3. Q: P0-009 (real answer task panic test) requires building an entire test-injection subsystem for answer sessions from scratch, mirroring the offer side's existing `session_hook`/`OfferSessionTestEvent` machinery, which doesn't currently exist on the answer side at all. This is a materially bigger lift than the TODO's "add a test-only hook" phrasing suggests. Confirming you want the full build-out (not a reduced-scope substitute)?
   A:

---

Fill in the `A:` lines above and share this file back (or paste the answers) when ready. No implementation will begin until then.
