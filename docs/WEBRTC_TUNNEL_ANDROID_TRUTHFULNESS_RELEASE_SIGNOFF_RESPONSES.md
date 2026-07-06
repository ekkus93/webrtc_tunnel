# Responses — WEBRTC_TUNNEL_ANDROID_TRUTHFULNESS_RELEASE_SIGNOFF_SPEC.md / _TODO.md

1.
Q: For P0-002/P0-003/P0-004 (thread-safe fakes, exact cancellation-branch proof, exact supersedence-branch proof): should I keep everything in the single consolidated `TunnelForegroundServiceStopFailureTest.kt` (current reality, avoids re-splitting a file I merged for a good reason last round), or split back into separate classes matching the TODO's assumed layout (`...StartupCancellationStopFailureTest`, `...StartupSupersedenceStopFailureTest`) so the CI test-filter patterns in the "Complete quality gates" section match exactly?
A:

2.
Q: P0-006 requires an actual push to observe real CI — unlike the prior round (where "observe real CI" was reported `NOT RUN` and left deferred), this TODO's Stage 3 is explicitly a required step, and the completion definition includes "real CI ran the focused Android step," which can only be satisfied by pushing to the remote. Per this project's CLAUDE.md and this session's established pattern, I push only when explicitly asked. Do you want me to push once Stage 1/2 implementation is done and local gates are green, so we can observe real CI — or should I implement everything and again report Stage 3 as deferred/`NOT RUN` until you separately invoke a push?
A:

---
Fill in the `A:` lines above and share this file back (or paste the answers) when ready — implementation will begin once both are answered.
