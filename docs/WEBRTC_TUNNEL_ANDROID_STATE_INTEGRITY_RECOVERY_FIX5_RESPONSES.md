# FIX5 Spec + TODO Responses

Covers `WEBRTC_TUNNEL_ANDROID_STATE_INTEGRITY_RECOVERY_FIX5_SPEC.md` and
`WEBRTC_TUNNEL_ANDROID_STATE_INTEGRITY_RECOVERY_FIX5_TODO.md`.

This replaces the first draft of this file. Code review confirmed every P0/P1 defect in
the spec is still present in the current code (nothing from FIX5 has been implemented
yet), and narrowed the original 8 open questions down to the 4 below that genuinely
need your input — the other 4 (Q5–Q8 from the first draft) turned out to be answerable
directly from the codebase and don't need a decision:

- Q5 (P0-004 before P0-003): confirmed necessary — P0-004 changes the reporter
  interface signature (`Throwable?` → `String`), so P0-003's wiring code won't compile
  until P0-004 lands first. Will sequence it that way.
- Q6 (keep existing specific catches before the new generic one in `processCommand`):
  required by Kotlin catch-ordering rules anyway. Will keep them.
- Q7 (existing `NetworkPolicyManager` tests need updating after the interface change):
  confirmed necessary — the build won't compile otherwise. In scope.
- Q8 (`service.testHooks.submitLifecycleCommandIfPossible(...)` doesn't exist): confirmed
  by reading the test file. Will use existing observable state (bridge start-call counts,
  `pendingPolicyResumeGeneration`) instead of inventing a new test hook.

---

## Q1: Should `pausedByPolicy.get()` be required in the NativeFailure pending-retry check?

In `handleStartupCompleted()`'s `NativeFailure` branch, once the pending retry is read
and matches the current generation, should resuming also require
`pausedByPolicy.get() == true`, or does the pending retry itself already prove policy
had paused the tunnel (making the extra check redundant)? The spec's stated default is
"require it" (safer), but notes the alternative. Any reason to relax that for this app?

A:

---

## Q2: Where should `NetworkPolicyManager`'s production reporter come from?

I read `AppDependencies.kt` and `DiagnosticsRepository.kt` to check the spec's proposed
options, and neither fits cleanly:

- There is no existing "AppErrorReporter" abstraction at the app level.
  `DiagnosticsRepository` only builds/exports a redacted diagnostics payload — it has no
  `publishError`-style method. The only thing with `publishError` is
  `TunnelForegroundService.StatusReporter`, an **inner class of the service** that
  doesn't exist until the service starts.
- `NetworkPolicyManager` is a **single app-wide instance** created once in
  `AppDependencies` (line 14) and shared by `SetupViewModel`, `NetworkPolicyViewModel`,
  and `TunnelForegroundService` — they all observe the same `status` `StateFlow`. The
  spec's "Option B" (construct a second `NetworkPolicyManager` inside
  `TunnelForegroundService.onCreate()`) would fork that shared state into two instances,
  which breaks the current single-source-of-truth design.

So the real choice is between:

(a) Add a small app-wide diagnostic event bus/reporter to `AppDependencies` (e.g. a
`MutableSharedFlow<DiagnosticEvent>`) that `NetworkPolicyManager` reports into at
construction time, and have `TunnelForegroundService` subscribe to it and relay events
through its existing `StatusReporter.publishError`.

(b) Give `NetworkPolicyManager` a mutable/settable reporter reference (defaulting to
no-op) that `TunnelForegroundService.onCreate()` installs on the shared instance when
the service starts, so notifications only appear while the service is alive (arguably
correct anyway, since delivery failures matter most while the service is monitoring).

(c) Something else you'd prefer.

A:

---

## Q3: Should `Log.w` still include a `Throwable`, or only the redacted string?

Once P0-004 is fixed so the throwable itself is never passed with an unredacted
message, is it acceptable to keep passing a (now genuinely redacted, freshly
constructed) `Throwable`/stack trace to `Log.w` for debuggability, or do you want
`Log.w` to take only the redacted `String` with no throwable at all, to eliminate any
risk of a future regression reintroducing a leak through that path?

A:

---

## Q4: For the P1-005 true rollback-failure test, fake or real failure?

Is it acceptable to use a fake/failing `ConfigRepository` (or a `RecordingSetupStore`-
style fake) to force the **rollback** operation itself to fail (not just the forward
stage), or do you want a real file-permission-based failure scenario? Faking is simpler
and CI-reliable; a real filesystem scenario is more "real" but risks flakiness across
CI environments.

A:

---

Fill in the `A:` lines and share back when ready.
