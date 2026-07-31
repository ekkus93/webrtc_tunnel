# WebRTC Tunnel FIX9 Completion Evidence

**Source TODO / closure ledger:** `docs/WEBRTC_TUNNEL_STALE_SETUP_RESULT_CONTRACT_FIX9_TODO.md`  
**Initial audit baseline:** `141a5425f620ae6b37a29ee0d8956cbfbd4d7b27`  
**Implementation baseline:** `6bad7a1f18b180676cc567031f93f7a99fb91d52`  
**Validated functional repair candidate:** `9ca07ff87d60e9c896bd1139a375fc84dbccc4cc`  
**Latest startup-hardening correction:** `673ea778d104673826f83592325fef46271133e9`  
**Disposition:** functional implementation, enforcement, and one complete exact-SHA release-candidate run are proven. The first documentation-only closure candidate failed the emulator startup gate, so final closure remains conditional on a new exact `[full-signoff]` commit passing the same applicable gates.

This ledger maps FIX9 requirements to production paths and named evidence. A helper seam, partial matrix, successful parent SHA, or retry from another commit is not treated as completion.

## P0-001 — Real setup-operation stale/cancel semantics

- **P0-001-A/B:** Complete. `SetupOperationToken` replaces raw operation ids; guarded controller blocks receive one freshness authority.
- **P0-001-C:** Complete. Admission records the owning coroutine `Job`; abandonment invalidates and cancels that owner, and a stale owner cannot release a newer operation.
- **P0-001-D:** Complete. Path import, URI import, and identity generation gate draft replacement and wipe discarded private material.
- **P0-001-E:** Complete. Forward upsert/delete gate draft mutation and result publication.
- **P0-001-F:** Complete. Final save checks freshness before global admission, before persistence, after persistence, and before tunnel start. Cancellation during persistence rolls back attempted stages under `NonCancellable`; incomplete rollback is durable and visible.
- **P0-001-G:** Complete through public ViewModel/controller paths with deterministic production-boundary barriers. Coverage includes path/URI import, generation, forward upsert/delete, navigation validation, pre-commit cancellation, rollback cancellation, start-from-review cancellation, and stale-error preservation.

### Baseline readiness/admission invariant

Run `30593967688` exposed a production race: `SetupLoadState.Ready` could become visible while `BaselineLoad` still owned setup admission. Commit `41b3e08cffe83292776eaeb62524a4133837e19a` now publishes terminal load state only after admission release. Unexpected baseline exceptions fail closed as a redacted durable failure; there is no blank/default fallback.

## P0-002 — `Result` contract hardening

- `savePreferences(...)`, `prepareActiveConfigForStart(...)`, and `replaceConfigTransactionally(...)` convert ordinary exceptions to `Result.failure` and preserve cancellation.
- Behavioral tests prove fixed visible failure codes and no mutation after snapshot-capture failure.
- `Fix9ResultContractSourceAuditTest`, the existing `CheckResult` enforcement suite, and negative fixtures reject selected-subclass catches, pre-`try` `getOrThrow()`, ignored results, and fake `.also { }` consumption.

## P0-003 — Coherent public-identity reads

`IdentityRepository.readPublicIdentity()` uses the same storage lock as identity-pair replacement. `IdentityRepositoryCoherentReadTest` pauses the real replacement boundary and proves readers cannot observe a half-written pair.

## P0-004 — Canonical private identity import

`ImportExportService.importPrivateIdentityContent(...)` requires canonical private and public output, never falls back to source text, and wipes canonical private bytes. Negative tests cover missing canonical material.

## P0-005 — Draft-truth messaging

Setup-only forward changes report draft updates/removals. Source enforcement bans authoritative-sounding saved/deleted messages on these paths.

## P0-006 — Android broker-secret permissions

`BrokerSecretRepositoryInstrumentedTest.persistedAndRestoredBrokerSecretHasOwnerOnlyPermissions` asserts exact `0600` bits after persist and restore. A permission-enforcement failure is returned and surfaced; execution does not silently proceed.

## P0-007 / P1 — Documentation and enforcement

- Every assistant-created file referenced by FIX9 evidence exists at the exact repository path named.
- `Fix9SetupFreshnessSourceAuditTest` strips comments and enforces token use at identity, forward, persist, and tunnel-start boundaries.
- FIX9 Result audits and the existing FIX8 inventories remain active for unsafe `runCatching`, unchecked authoritative filesystem booleans, empty-snapshot fallbacks, controller authoritative writes, unsafe `catch (Throwable)`, and Rust zero-timestamp fallbacks.
- Required concurrency tests use bounded latches/deferred barriers rather than timing sleeps.

## Failed exact candidate — diagnostics admission

Candidate `34b95051defd1a63d67836f01de6b1716f694ac3` was correctly rejected:

- `ci/rc-diagnostics`: success, run `30599682091`
- broker-secret instrumentation: success, run `30599682072`
- Rust lint/Linux/macOS/Docker: success, main run `30599682106`
- Android full Gradle `check`: success
- dedicated foreground-service stop-failure suite: success
- second `assembleDebug testDebugUnitTest`: failure
- `ci/full-matrix`: failure
- `ci/release-candidate`: failure
- Android emulator E2E: correctly skipped because Android failed

The failing test, `LogsViewModelTest.concurrentExportIsRejectedWhileOneIsAlreadyInFlight`, exposed a real check-before-launch race: both diagnostics export entry points checked `_isBusy` before launching but claimed it only inside the coroutine. Two immediate calls could both be admitted.

### Diagnostics export correction

- Commit `590c66717a7c67cb4d8fd08f48daa881d8834641` atomically claims shared path/URI export admission with `MutableStateFlow.compareAndSet(false, true)` before launch.
- Commit `6bad7a1f18b180676cc567031f93f7a99fb91d52` replaces scheduler-speed assumptions with a blocked single-thread IO dispatcher and bounded latches.
- No retry, sleep, suppression, relaxed rule, or extended timeout is used.
- Path-scoped Android run `30600821345` passed full Gradle `check`, the dedicated stop-failure suite, the second `assembleDebug testDebugUnitTest`, and path-scoped full-matrix signoff.

## Failed exact candidate — Android Settings navigation false-positive

Candidate `7be00838feef85d374f20aa8dd6b7365969ba3dd` reached the real Android emulator and failed the E2E script with:

`[e2e FAIL] could not find Settings nav tab`

The emulator booted, the APK installed, and the app launched. The failure was in the shell harness readiness contract, not emulator provisioning or WebRTC.

### Root cause and repair

The old `bounds_of_text()` pipeline ended with `awk`. When no node matched, `awk` produced no coordinates but still returned success. The startup readiness check therefore treated a missing Settings node as found and immediately attempted the tap.

Commit `9ca07ff87d60e9c896bd1139a375fc84dbccc4cc`:

- makes missing-node and unusable-bounds lookup return failure;
- waits for actual app-owned Settings semantics instead of relying on a fixed startup sleep;
- resolves Settings by either visible text or the existing `Settings tab icon` content description;
- keeps selectors screen-size independent and sourced from the same `uiautomator` tree;
- adds no coordinate fallback, silent bypass, retry suppression, or timeout inflation.

## Exact successful functional candidate

Commit `9ca07ff87d60e9c896bd1139a375fc84dbccc4cc` passed every applicable commit-level gate.

### Commit statuses

- `ci/rc-diagnostics`: **success**, run `30603969425`
- `ci/full-matrix`: **success**, run `30603969420`
- `ci/release-candidate`: **success**, run `30603969420`

### Independent instrumentation

- broker-secret permission instrumentation: **success**, run `30603969417`

### Main workflow `30603969420`

- Detect changed paths: success
- Lint: success
- Test (Linux): success
- Test (macOS): success
- Android: success
  - full Gradle `check`: success
  - foreground-service stop-failure truthfulness tests: success
  - second `assembleDebug testDebugUnitTest`: success without retry
- Docker real-data-path E2E: success
- Android emulator real-data-path E2E: success, job `91074610794`
- Full matrix signoff: success, job `91076875217`
- Failed jobs: none
- Failed steps: none

### Real Android data-path proof

The emulator job completed the production wizard path: Mode, identity generation, TLS broker configuration, remote peer authorization, forwards and network-policy defaults, review/start, and `Listening`. It then launched the dockerized answer, triggered WebRTC negotiation, delivered the marker through the Android-offer tunnel, and verified that the answer received `PING` and replied `PONG`.

## Failed exact documentation candidate — startup prerequisite

Candidate `9b1d1999fa81865adb051574221a68f4e15e8d74` was correctly rejected by main run `30606054232`.

### Gates that passed on `9b1d1999...`

- RC diagnostics run `30606054239`: success
- broker-secret permission instrumentation run `30606054195`: success
- Rust lint/Linux/macOS/Docker: success
- Android full Gradle `check`: success
- dedicated foreground-service stop-failure suite: success
- second `assembleDebug testDebugUnitTest`: success without retry

### Gate that failed

- Android emulator real-data-path E2E job `91080851616`: failure
- exact failure: `[e2e FAIL] home never rendered Settings navigation`
- emulator boot, APK installation, app-state reset, and app launch all occurred before the failure
- full matrix and release-candidate signoff therefore did not complete successfully

### Startup prerequisite correction

Inspection found that `android_install_app()` cleared app state and then ran:

`pm grant ... POST_NOTIFICATIONS ... || true`

That ignored an authoritative runtime-permission result. If the grant failed, `NotificationPermissionGate` could display a modal over the Home surface while the harness continued to poll for Settings semantics. The harness also did not wait for ActivityManager launch completion and suppressed UI-dump errors.

Commit `673ea778d104673826f83592325fef46271133e9` corrects those contracts:

- Android API level parsing is required and validated.
- `pm grant POST_NOTIFICATIONS` must succeed on API 33+.
- package state must report `android.permission.POST_NOTIFICATIONS: granted=true`.
- device wake and keyguard dismissal must succeed.
- `am start -W` must complete with `Status: ok`.
- failed/stale UI dumps produce empty XML rather than reusable stale data.
- dump errors are preserved for bounded reporting.
- a visible notification-permission modal is treated as an explicit prerequisite failure.
- startup diagnostics include bounded permission, focus, activity, UI hierarchy, and logcat evidence before any setup secret is entered.
- the Settings semantic wait remains bounded at 30 seconds; no retry-only rerun, silent dismissal, or timeout inflation is introduced.

## Final documentation/evidence exact-SHA rule

The final candidate is the exact `[full-signoff]` commit containing this ledger, its companion documents, and correction `673ea778d104673826f83592325fef46271133e9`. The SHA is intentionally defined by the published commit rather than guessed in advance.

FIX9 commit-level closure is valid only when all of these records identify that exact SHA:

- `ci/rc-diagnostics`: success
- `ci/full-matrix`: success
- `ci/release-candidate`: success
- broker-secret permission instrumentation: success
- Android full Gradle check and second full debug unit invocation: success
- Android emulator real-data-path E2E: success
- Rust formatting/clippy/tests/package/lifecycle gates: success
- Linux/macOS install-layout and lifecycle gates: success
- Docker TLS/data-path and graceful-stop E2E: success

The authoritative CI status issues and commit status API are the machine-readable closure evidence. Any failure, pending state, skipped required job, or status belonging to another SHA keeps FIX9 open.

## Release artifacts

Release APK/AAB packaging jobs are tag-only by workflow design. They are expected to skip on a `[full-signoff]` commit and are not dependencies of commit-level `ci/full-matrix` or `ci/release-candidate`.

They must pass on the eventual release tag, and the produced artifacts must be verified before publication. This future tag evidence is intentionally not claimed by FIX9 commit-level signoff.
