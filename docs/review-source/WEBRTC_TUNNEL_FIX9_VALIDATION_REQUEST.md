# WebRTC Tunnel FIX9 Validation Request

**Purpose:** trigger a new definitive full release-candidate validation after correcting the diagnostics-export double-admission race exposed by the previous exact candidate.

**Implementation baseline:** `6bad7a1f18b180676cc567031f93f7a99fb91d52`  
**Completion ledger:** `docs/review-source/WEBRTC_TUNNEL_FIX9_COMPLETION_EVIDENCE.md`  
**Previous failed candidate:** `34b95051defd1a63d67836f01de6b1716f694ac3`

This file is part of a docs-only `[full-signoff]` commit. A successful subset, a workflow from another SHA, or a retry of the failed candidate is insufficient.

## Why the prior candidate failed

Main run `30599682106` passed Rust, Linux, macOS, Docker, Android full Gradle `check`, and the dedicated stop-failure suite. Its second `assembleDebug testDebugUnitTest` invocation failed:

`LogsViewModelTest.concurrentExportIsRejectedWhileOneIsAlreadyInFlight`

The failure exposed a production check-before-launch race. Both diagnostics export APIs checked busy state before launch but claimed ownership only after the coroutine began. Two immediate callers could therefore both pass the check.

## Correction under validation

- `LogsViewModel.exportDiagnostics(...)` and `exportDiagnosticsToUri(...)` now share atomic `MutableStateFlow.compareAndSet(false, true)` admission before launch.
- The concurrency regression uses a blocked single-thread IO dispatcher and bounded latches. It does not rely on real IO being slow, sleeps, retries, or enlarged timeouts.
- The rejected export must never reach its destination.

Path-scoped Android run `30600821345` passed full Gradle `check`, the dedicated stop suite, the second full debug unit invocation, and path-scoped signoff on implementation baseline `6bad7a1f…` without retry.

All previously delivered FIX9 setup freshness, transactional rollback, Result contract, coherent identity, canonical import, draft-truth, static-enforcement, and broker-permission behavior remains in scope.

## Required conclusions on this exact candidate

- `ci/rc-diagnostics`: success
- `ci/full-matrix`: success
- `ci/release-candidate`: success
- broker-secret instrumentation: success
- Android full Gradle check: success
- dedicated foreground-service stop-failure suite: success
- second `assembleDebug testDebugUnitTest`: success without retry
- Android emulator/data-plane E2E: success
- Rust fmt/clippy/tests/package/lifecycle gates: success
- Docker TLS/data-path and graceful-stop E2E: success

Release APK/AAB jobs are tag-only and are expected to skip for this commit candidate. They are not part of commit-level full-matrix/release-candidate status and must be validated on the eventual release tag.

**Status:** validation requested. Do not claim FIX9 release signoff until every applicable conclusion belongs to the exact commit containing this request.
