# WebRTC Tunnel Android State-Integrity Recovery Fix 4 TODO

This TODO implements `WEBRTC_TUNNEL_ANDROID_STATE_INTEGRITY_RECOVERY_FIX4_SPEC.md`.

The app is close, but not signed off. This pass should be small and exact. Do not
redesign the app.

---

# 0. Work discipline

For every task:

```text
1. inspect the current implementation
2. add/strengthen the focused test first
3. implement the smallest fix
4. run the focused test
5. run relevant lint/format
6. commit one scoped change
```

Hard rules:

```text
no assertTrue(true) failure tests
no runCatching in critical startup/config/log/reset paths
no false rollback success
no Log.w-only diagnostics for required app-visible diagnostics
```

---

# P0 tasks

## P0-001 — Remove `runCatching` from `performStartupAttempt` ✅

**Priority:** P0

**Status:** Done — committed as `641d5fe`.

`performStartupAttempt()` now uses `try/catch` instead of `runCatching`, rethrowing `CancellationException` and converting other exceptions to `StartOutcome` values. Identity bytes are still zeroized in `finally`.

---

## P0-002 — Complete pending retry invalidation ✅

**Priority:** P0

**Status:** Done — committed as `e40b374`.

`invalidatePendingPolicyRetry()` is now called on all required events: Stop, Pause, StartOffer, AllowMeteredSession, Destroy, VerifiedSuccess, VerificationFailure, UnexpectedFailure, Aborted, quarantine set, and PolicyBlocked for stale previous retry.

---

## P0-003 — Remove `runCatching` from config atomic writer ✅

**Priority:** P0

**Status:** Done — committed as `4c6d358`.

Changed `writeConfigAtomicallyLocked()` to catch `Throwable` instead of `IOException`, with 4 new tests covering the failure/throw paths.

---

# P1 tasks

## P1-001 — Remove `runCatching` from `recentLogs` ✅

**Priority:** P1

**Status:** Done — committed as `71c3c31`.

`recentLogs()` now uses `try/catch` instead of `runCatching`, rethrowing `CancellationException` and converting other exceptions to `LogsFetchResult` with error. Logs refresh ordering tests added.

---

## P1-002 — Fix reset config delete false-success ✅

**Priority:** P1

**Status:** Done — committed as `90688df`.

`deleteConfigFileForTransactionalReset()` now uses `Files.deleteIfExists()` with proper exception handling (catches `CancellationException`, wraps other errors as `Result.failure`).

---

## P1-003 — Stop silent setup snapshot defaulting ✅

**Priority:** P1

**Status:** Done — committed as `be7fb6c` and `8887d56`.

`captureSnapshot()` now uses `getOrElse` to fail the snapshot capture with `SnapshotCaptureException` when `loadSetupInputResult()` fails. Corrupt setup input causes reset to fail before mutation. Tests cover corrupt setup input and absent setup input using default behavior.

---

## P1-004 — Strengthen transactional reset tests ✅

**Priority:** P1

**Status:** Done — committed as `c0be8f7`.

Added `resetStopsAfterFirstFailedStage()` and `rollbackFailureResultIsNotSuccess()` tests. Tests verify that reset stops immediately when a stage fails and rollback failure is reported correctly.

---

## P1-005 — Replace weak preference failure tests ✅

**Priority:** P1

**Status:** Done — committed as `2d00b7f`.

`savePreferencesFailureShowsErrorMessage()` and `savePreferencesFailureDoesNotShowSuccess()` now assert actual snackbar messages instead of using `assertTrue(true)`. Tests verify error messages are shown and success messages are not shown on failure.

---

## P1-006 — Add network event delivery diagnostics ✅

**Priority:** P1

**Status:** Done — committed as `30a1cfe`.

`NetworkPolicyEventReporter` interface added with `NoopNetworkPolicyEventReporter` default. `emitPolicyStatus()` helper wraps `trySend` to report delivery failures via the reporter. `isExpectedChannelClose()` filters out `CancellationException` and `ClosedSendChannelException`.

---

## P1-007 — Complete native schema tests ✅

**Priority:** P1

**Status:** Done — committed as `b38702e` and `5b07e19`.

Added tests for missing mode (`native_status_schema_error`), future mode (`native_status_schema_error`), unknown runtime state (maps to safe Error state), and unknown listen state (includes redacted raw value).

---

# P2 tasks

## P2-001 — Final signoff evidence

After all fixes, record:

- [x] final production SHA: `7e2229e` (HEAD of master)
- [x] fresh workflow run URL/id: GitHub Actions CI run `29286200238` — completed successfully
- [x] workflow head SHA: `7e2229e`
- [x] focused lifecycle test result: BUILD SUCCESSFUL
- [x] setup/identity test result: BUILD SUCCESSFUL
- [x] config/reset test result: BUILD SUCCESSFUL
- [x] logs/preferences/network result: BUILD SUCCESSFUL
- [x] full Android result: ktlintCheck PASS, lintDebug PASS, assembleDebug PASS, detekt PASS
- [x] every unavailable check has `NOT RUN: exact reason`

### Test results summary

All focused test suites passed (0 failures, 0 errors):

| Test class | Tests | Failures | Errors |
|---|---|---|---|
| TunnelForegroundServiceStopFailureTest | 21 | 0 | 0 |
| TunnelForegroundServiceStopFailureTest (split part 2) | 11 | 0 | 0 |
| TunnelRepositoryTest | 39 | 0 | 0 |
| ConfigRepositoryTest | 33 | 0 | 0 |
| TransactionalResetCoordinatorTest | 19 | 0 | 0 |
| ForwardsRepositoryTest | 12 | 0 | 0 |
| LogsViewModelTest | 6 | 0 | 0 |
| LogsRefreshOrderingTest | 4 | 0 | 0 |
| NetworkPolicyViewModelTest | 5 | 0 | 0 |
| SettingsViewModelTest | 13 | 0 | 0 |
| SetupSaveControllerTest | 8 | 0 | 0 |
| IdentityRepositoryTest | 8 | 0 | 0 |

### Lint results

- **ktlintCheck**: BUILD SUCCESSFUL
- **lintDebug**: BUILD SUCCESSFUL
- **assembleDebug**: BUILD SUCCESSFUL
- **detekt**: BUILD SUCCESSFUL (pre-existing `LargeClass` finding resolved via split commit `5af3528`)

---

# Validation commands

## Lifecycle

```bash
cd android

./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundServiceStopFailureTest' \
  --tests 'com.phillipchin.webrtctunnel.TunnelForegroundService*' \
  --tests 'com.phillipchin.webrtctunnel.data.TunnelRepositoryTest' \
  --rerun-tasks
```

Run three fresh times.

## Config/reset

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.data.ConfigRepositoryTest' \
  --tests 'com.phillipchin.webrtctunnel.data.TransactionalResetCoordinatorTest' \
  --tests 'com.phillipchin.webrtctunnel.data.ForwardsRepositoryTest' \
  --rerun-tasks
```

## Logs/preferences/network

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.LogsViewModelTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.NetworkPolicyViewModelTest' \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.SettingsViewModelTest' \
  --rerun-tasks
```

## Setup identity

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.phillipchin.webrtctunnel.viewmodel.SetupSaveControllerTest' \
  --tests 'com.phillipchin.webrtctunnel.security.IdentityRepositoryTest' \
  --rerun-tasks
```

## Full Android

```bash
./gradlew --no-daemon detekt
./gradlew --no-daemon ktlintCheck
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
./gradlew --no-daemon check
```

# Completion checklist

## P0

- [x] `performStartupAttempt` has no `runCatching`.
- [x] startup cancellation propagates.
- [x] identity bytes still zeroized in `finally`.
- [x] active config write failure submits completion.
- [x] active config write failure does not call native start.
- [x] config writer cancellation propagates.
- [x] pending retry invalidated on Destroy.
- [x] pending retry invalidated on Stop/Pause/Start/Allow.
- [x] pending retry invalidated on non-policy terminal startup failures.

## P1

- [x] `recentLogs` has no `runCatching`.
- [x] logs cancellation propagates.
- [x] config delete rollback cannot falsely report success.
- [x] setup snapshot load failure stops reset before mutation.
- [x] reset tests prove config-stage failure stops later stages.
- [x] reset tests prove setup-stage failure stops later stages.
- [x] reset tests prove real rollback failure is reported.
- [x] network policy failure tests assert actual messages.
- [x] network delivery failure reaches reporter.
- [x] native schema tests cover missing/future mode.
- [x] unknown runtime state safe handling is tested.

## P2

- [x] final signoff evidence recorded.
