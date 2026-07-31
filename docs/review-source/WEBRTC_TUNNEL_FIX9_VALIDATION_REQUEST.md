# WebRTC Tunnel FIX9 Final Validation Request

**Purpose:** trigger the definitive exact-SHA documentation/evidence signoff after the FIX9 implementation, diagnostics-admission correction, Android UI semantic repair, and fail-closed emulator startup-prerequisite hardening.

**Initial audit baseline:** `141a5425f620ae6b37a29ee0d8956cbfbd4d7b27`  
**Implementation baseline:** `6bad7a1f18b180676cc567031f93f7a99fb91d52`  
**Validated functional repair candidate:** `9ca07ff87d60e9c896bd1139a375fc84dbccc4cc`  
**Latest startup-hardening correction:** `673ea778d104673826f83592325fef46271133e9`  
**Completion ledger:** `docs/review-source/WEBRTC_TUNNEL_FIX9_COMPLETION_EVIDENCE.md`  
**TODO closure ledger:** `docs/WEBRTC_TUNNEL_STALE_SETUP_RESULT_CONTRACT_FIX9_TODO.md`

This file triggers the final `[full-signoff]` candidate. The candidate SHA is the exact commit containing this file, all companion ledgers, and correction `673ea778d104673826f83592325fef46271133e9`. A successful parent, a status from another SHA, or a retry of an earlier candidate is insufficient.

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

The successful emulator run completed all seven setup steps, reached `Listening`, negotiated with the dockerized answer, delivered the marker through the Android tunnel, and verified `PING`/`PONG` data-plane traffic.

## Rejected documentation-only candidate

Candidate `9b1d1999fa81865adb051574221a68f4e15e8d74` was correctly rejected by main run `30606054232`.

It passed RC diagnostics, broker-secret instrumentation, Rust/Linux/macOS/Docker, the Android full Gradle check, the dedicated stop-failure suite, and the second full Android unit invocation. Android emulator job `91080851616` failed with:

`[e2e FAIL] home never rendered Settings navigation`

The emulator booted, installed the APK, cleared app state, and launched the app. Because the emulator E2E failed, that SHA did not satisfy `ci/full-matrix` or `ci/release-candidate`, and no FIX9 signoff was claimed.

## Startup-prerequisite correction under validation

Inspection of the rejected candidate found an authoritative result that the harness silently ignored:

`pm grant ... POST_NOTIFICATIONS ... || true`

After `pm clear`, a failed notification-permission grant could therefore be ignored while the app's `NotificationPermissionGate` displayed a modal over Home. The harness also used a non-waiting ActivityManager launch and suppressed failed/stale `uiautomator` evidence.

Correction `673ea778d104673826f83592325fef46271133e9`:

- validates the Android SDK level;
- requires `pm grant POST_NOTIFICATIONS` on API 33+;
- verifies package state reports `android.permission.POST_NOTIFICATIONS: granted=true`;
- requires device wake and keyguard dismissal;
- launches through `am start -W` and requires `Status: ok`;
- empties failed or invalid UI dumps so stale output cannot be reused;
- preserves bounded UI-dump errors;
- treats a visible notification-permission modal as a prerequisite failure rather than dismissing it silently;
- emits bounded permission, focus, activity, UI-hierarchy, and logcat diagnostics before identity or broker input;
- retains the existing 30-second Settings semantic bound.

No retry-only rerun, hardcoded coordinate fallback, silent dismissal, result suppression, or timeout inflation is part of the correction.

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

Required jobs must execute. A skipped required job, a pending status, a canceled job, or a status associated with another SHA is a failure of final signoff.

The authoritative CI status issues and commit status API are the machine-readable closure record. When they identify this exact SHA and every applicable conclusion above is successful, FIX9 commit-level closure is complete.

## Release-artifact boundary

Release APK/AAB packaging jobs are tag-only. They are expected to skip on this commit candidate and are not commit-level dependencies of `ci/full-matrix` or `ci/release-candidate`.

The eventual release tag must separately run and pass tag-only packaging, and the produced artifacts must be verified before publication. This final FIX9 commit does not claim release-tag artifact evidence that does not yet exist.

**Status rule:** final FIX9 commit-level signoff is complete if and only if every applicable required conclusion above succeeds on the exact `[full-signoff]` commit containing this request.
