# Responses — WEBRTC_TUNNEL_ANDROID_TRUTHFULNESS_RELEASE_SIGNOFF_SPEC.md / _TODO.md

1.
Q: For P0-002/P0-003/P0-004 (thread-safe fakes, exact cancellation-branch proof, exact supersedence-branch proof): should I keep everything in the single consolidated `TunnelForegroundServiceStopFailureTest.kt` (current reality, avoids re-splitting a file I merged for a good reason last round), or split back into separate classes matching the TODO's assumed layout (`...StartupCancellationStopFailureTest`, `...StartupSupersedenceStopFailureTest`) so the CI test-filter patterns in the "Complete quality gates" section match exactly?
A: Keep the current single consolidated `TunnelForegroundServiceStopFailureTest.kt`. Do **not** split tests back into separate classes merely to match the TODO's assumed file/class layout.

The requirement is scenario coverage and reliable CI execution, not a particular source-file organization. The consolidated file is the current working design and avoids unnecessary churn.

Update the TODO commands and CI filters to match the actual test class that exists. Prefer filtering the real class explicitly, for example:

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundServiceStopFailureTest'
```

If all cancellation, supersedence, pause, policy-pause, service-stop, and stale-status scenarios are methods in that class, that single class filter should execute all of them. Verify the Gradle output shows the expected nonzero test count and that every required scenario has a distinct named test method.

Do not create placeholder classes just to satisfy stale filter patterns. Update the "Complete quality gates" section and `.github/workflows/ci.yml` so the filters reflect repository reality. The acceptance criteria are:

- the consolidated class contains the required scenarios;
- the focused command actually discovers and runs them;
- CI uses the same real filter;
- no required scenario is silently omitted because of a filename/class-name mismatch.

2.
Q: P0-006 requires an actual push to observe real CI — unlike the prior round (where "observe real CI" was reported `NOT RUN` and left deferred), this TODO's Stage 3 is explicitly a required step, and the completion definition includes "real CI ran the focused Android step," which can only be satisfied by pushing to the remote. Per this project's CLAUDE.md and this session's established pattern, I push only when explicitly asked. Do you want me to push once Stage 1/2 implementation is done and local gates are green, so we can observe real CI — or should I implement everything and again report Stage 3 as deferred/`NOT RUN` until you separately invoke a push?
A: Yes. This response explicitly authorizes you to push the implementation branch once Stages 1 and 2 are complete and all locally available gates are green, so P0-006 can observe the real GitHub Actions workflow.

Use the normal current implementation branch. Do **not** force-push, rewrite remote history, create a tag/release, merge the branch, or push unrelated work.

Required sequence:

1. Complete Stages 1 and 2.
2. Run all locally available focused and full quality gates.
3. Commit the scoped implementation in small, intentional commits.
4. Push the current implementation branch to the existing remote.
5. Observe the resulting GitHub Actions run.
6. Record the commit SHA, workflow run URL/number, job names, and actual PASS/FAIL/NOT RUN results.
7. If CI exposes a defect caused by this implementation, fix it, rerun local gates, commit, and push the corrective commit so the required workflow can be observed again.

This authorization is limited to the branch and work required by this hardening TODO. Do not merge to the default branch or publish a release without a separate explicit instruction.

If push or CI observation is impossible because of authentication, repository permissions, or a protected-branch restriction, report `NOT RUN: exact reason` rather than claiming completion.

---
Fill in the `A:` lines above and share this file back (or paste the answers) when ready — implementation will begin once both are answered.
