# WebRTC Tunnel FIX9 Final Validation Request

**Purpose:** trigger the definitive exact-SHA documentation/evidence signoff after the FIX9 implementation, diagnostics-admission correction, Android emulator E2E harness repair, and successful functional release-candidate run.

**Initial audit baseline:** `141a5425f620ae6b37a29ee0d8956cbfbd4d7b27`  
**Implementation baseline:** `6bad7a1f18b180676cc567031f93f7a99fb91d52`  
**Validated functional repair candidate:** `9ca07ff87d60e9c896bd1139a375fc84dbccc4cc`  
**Completion ledger:** `docs/review-source/WEBRTC_TUNNEL_FIX9_COMPLETION_EVIDENCE.md`  
**TODO closure ledger:** `docs/WEBRTC_TUNNEL_STALE_SETUP_RESULT_CONTRACT_FIX9_TODO.md`

This file is the trigger for the final documentation-only `[full-signoff]` candidate. The candidate SHA is the exact commit containing this file. A successful parent, a status from another SHA, or a retry of an earlier candidate is insufficient.

## Functional candidate already proven

Commit `9ca07ff87d60e9c896bd1139a375fc84dbccc4cc` passed the complete applicable release-candidate matrix:

- RC diagnostics run `30603969425`: success
- broker-secret permission instrumentation run `30603969417`: success
- main run `30603969420`: success
- `ci/rc-diagnostics`: success
- `ci/full-matrix`: success
- `ci/release-candidate`: success
- Rust lint/Linux/macOS: success
- Android full Gradle `check`: success
- dedicated foreground-service stop-failure suite: success
- second `assembleDebug testDebugUnitTest`: success without retry
- Docker real-data-path and graceful-stop E2E: success
- Android emulator real-data-path E2E job `91074610794`: success
- Full matrix signoff job `91076875217`: success
- Failed jobs and failed steps: none

## Android emulator E2E repair under final documentation signoff

The previous candidate `7be00838feef85d374f20aa8dd6b7365969ba3dd` failed after emulator boot and app launch because the shell harness treated an empty `uiautomator` lookup as success. The final implementation repair in `9ca07ff87d60e9c896bd1139a375fc84dbccc4cc`:

- returns failure for missing semantic nodes or unusable bounds;
- waits for actual Settings navigation semantics rather than a fixed startup sleep;
- locates Settings by visible text or the app-owned `Settings tab icon` content description;
- uses no coordinate fallback, silent skip, retry suppression, or timeout inflation.

The successful emulator run completed all seven setup steps, reached `Listening`, negotiated with the dockerized answer, delivered the marker through the Android tunnel, and verified `PING`/`PONG` data-plane traffic.

## Required conclusions on this exact final candidate

The exact commit containing this request must produce all of the following:

- `ci/rc-diagnostics`: success
- `ci/full-matrix`: success
- `ci/release-candidate`: success
- broker-secret permission instrumentation: success
- Rust formatting, clippy, tests, package, and lifecycle gates: success
- Linux and macOS install-layout/lifecycle gates: success
- Android full Gradle `check`: success
- dedicated foreground-service stop-failure truthfulness tests: success
- second `assembleDebug testDebugUnitTest`: success without retry
- Docker TLS/data-path and graceful-stop E2E: success
- Android emulator real-data-path E2E: success
- Full matrix signoff: success

Required jobs must execute. A skipped required job, a pending status, or a status associated with another SHA is a failure of final signoff.

The authoritative CI status issues and the commit status API are the machine-readable closure record. When they identify this exact SHA and every applicable conclusion above is successful, FIX9 commit-level closure is complete.

## Release-artifact boundary

Release APK/AAB packaging jobs are tag-only. They are expected to skip on this commit candidate and are not commit-level dependencies of `ci/full-matrix` or `ci/release-candidate`.

The eventual release tag must separately run and pass tag-only packaging, and the produced artifacts must be verified before publication. This final FIX9 commit does not claim release-tag artifact evidence that does not yet exist.

**Status rule:** final FIX9 commit-level signoff is complete if and only if every applicable required conclusion above succeeds on the exact `[full-signoff]` commit containing this request.
