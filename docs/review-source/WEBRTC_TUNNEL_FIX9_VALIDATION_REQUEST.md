# WebRTC Tunnel FIX9 Validation Request

**Purpose:** trigger the definitive full release-candidate validation for FIX9 after implementation, production-path tests, static enforcement, bounded concurrency harnesses, and the setup readiness/admission race fix.

**Final implementation baseline before signoff docs:** `41b3e08cffe83292776eaeb62524a4133837e19a`  
**Completion ledger:** `docs/review-source/WEBRTC_TUNNEL_FIX9_COMPLETION_EVIDENCE.md`

This file is part of the final docs-only `[full-signoff]` candidate. The candidate must run the full Rust, Android, Docker, emulator/data-plane, RC diagnostics, release artifact, and broker-secret permission gates. A successful subset or evidence from another SHA is insufficient.

## Final implementation facts under validation

- Setup admission owns the actual coroutine `Job`; abandonment cancels and invalidates it.
- Identity import/generation, forward drafts, navigation, final persistence, and foreground-service start are freshness-gated on their production paths.
- Transactional save cancellation rolls back attempted stages under `NonCancellable`; incomplete rollback is durable and visible.
- `SetupLoadState.Ready` is published only after `BaselineLoad` releases admission. Unexpected baseline exceptions fail closed as a redacted durable failure.
- Public identity reads are serialized with identity-pair replacement.
- Canonical private/public identity output is mandatory on import; source-text fallback is removed.
- Identified `Result` APIs convert ordinary exceptions to failure values and rethrow cancellation.
- Comment-resistant source audits enforce setup freshness and Result contracts.
- Broker-secret permission instrumentation proves exact `0600` after persist and restore.

## Last bounded Android failure and correction

Run `30593967688` completed with six failures. The apparent final-save rollback timeout and four forward timeouts were not independent deadlocks: tests observed `SetupLoadState.Ready` while `BaselineLoad` still owned admission, so production forward/save methods were rejected as busy and never reached their barriers. Commit `41b3e08cffe83292776eaeb62524a4133837e19a` moved terminal load-state publication after admission release. No retry loop, delay, suppression, fallback, or relaxed threshold was added.

Run `30598677024` then passed full Gradle check, the dedicated stop-failure suite, assemble/unit packaging, and path-scoped full-matrix signoff for the final implementation baseline.

## Required exact-candidate conclusions

- `ci/rc-diagnostics`: success
- `ci/full-matrix`: success
- `ci/release-candidate`: success
- broker-secret instrumentation workflow: success
- path detection: Rust and Android required
- Android full Gradle check, unit tests, lint, detekt, ktlint, assemble, emulator/data-plane E2E: success
- Rust fmt/clippy/tests/release builds and real broker test: success
- Docker real-data-path and stop-lifecycle E2E: success
- release artifacts: success

**Status:** validation requested. Do not claim FIX9 release signoff until every required conclusion belongs to this exact candidate SHA.
