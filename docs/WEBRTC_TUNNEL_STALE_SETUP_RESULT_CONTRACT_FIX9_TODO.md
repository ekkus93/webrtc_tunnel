# WebRTC Tunnel — Stale Setup Operation and Result Contract Hardening FIX9 TODO

**Target branch:** `master`  
**Initial audit baseline:** `141a5425f620ae6b37a29ee0d8956cbfbd4d7b27`  
**Implementation baseline:** `6bad7a1f18b180676cc567031f93f7a99fb91d52`  
**Validated functional repair candidate:** `9ca07ff87d60e9c896bd1139a375fc84dbccc4cc`  
**Latest startup-hardening correction:** `673ea778d104673826f83592325fef46271133e9`  
**Detailed implementation-era checklist:** retained in Git history at `9ca07ff87d60e9c896bd1139a375fc84dbccc4cc^` and earlier revisions of this path.  
**Current disposition:** implementation and functional release-candidate validation are complete. The first documentation-only closure candidate failed the Android emulator startup gate, so FIX9 remains open until a new exact `[full-signoff]` commit containing this ledger passes every required gate below.

This file is the FIX9 closure ledger. It replaces the implementation-oriented unchecked checklist after the production paths, negative paths, static enforcement, Android instrumentation, and real-data-path E2E were implemented and validated. Historical code snippets and task-level instructions remain available through Git history.

---

## Implementation completion

### P0-001 — Real setup-operation stale/cancel semantics

- [x] Typed `SetupOperationToken` is the single freshness authority passed through real controller operations.
- [x] Admission records the owning coroutine `Job`; abandonment invalidates and cancels the exact owner.
- [x] A stale owner cannot clear a newer operation or publish stale busy, identity, forward, navigation, success, or error state.
- [x] Identity path import, URI import, and generation gate publication and wipe discarded private material.
- [x] Forward upsert and delete gate draft mutation and result publication.
- [x] Final save checks freshness before global mutation admission, before persistence, after persistence, and before foreground-service start.
- [x] Cancellation during persistence rolls back attempted stages under `NonCancellable`; incomplete rollback is durable and visible.
- [x] Setup baseline terminal state is published only after baseline admission releases.
- [x] Public ViewModel/controller tests cover cancellation during identity, forward, navigation, final-save, rollback, and start-from-review production boundaries with deterministic barriers.

Principal evidence:

- `SetupFix9CancellationRegressionTest`
- `SetupFix9NativeBarrierCancellationTest`
- `SetupStaleFinalSaveTest`
- `SetupDraftOperationCoordinationTest`
- `Fix9SetupFreshnessSourceAuditTest`

### P0-002 — `Result` contract hardening

- [x] `savePreferences(...)` converts every ordinary exception to `Result.failure` and preserves `CancellationException`.
- [x] `prepareActiveConfigForStart(...)` converts active-config read/rewrite failures to `Result.failure` and aborts start visibly.
- [x] `replaceConfigTransactionally(...)` converts snapshot-capture failures to `Result.failure` without writing or restoring.
- [x] Settings and network-policy callers publish stable visible failure codes.
- [x] Static enforcement rejects selected-subclass catches, pre-`try` `getOrThrow()`, ignored authoritative results, and fake `.also { }` consumption.

Principal evidence:

- `ConfigRepositoryFix9ResultContractTest`
- `Fix9ResultContractViewModelTest`
- `Fix9ResultContractSourceAuditTest`
- `CheckResultEnforcementFixtureTest`

### P0-003 — Coherent public-identity reads

- [x] `readPublicIdentity()` uses the same storage lock as identity-pair replacement.
- [x] Concurrency coverage proves readers cannot observe a half-written identity pair.

Principal evidence: `IdentityRepositoryCoherentReadTest`.

### P0-004 — Canonical private-identity import

- [x] Import requires canonical native private and public output.
- [x] Source text is never used as an unsafe fallback.
- [x] Canonical private bytes are wiped after use.
- [x] Missing canonical output fails visibly.

Principal evidence: `ImportExportCanonicalContractTest`.

### P0-005 — Draft-truth messaging

- [x] Setup-only forward mutations report draft updates/removals, not authoritative saved/deleted claims.
- [x] Source enforcement prevents regression to authoritative-sounding draft messages.

### P0-006 — Android broker-secret permissions

- [x] Persisted and restored broker-secret files are asserted as exact owner-only `0600` permissions on Android.
- [x] Permission-enforcement failure is returned and surfaced; execution does not silently continue.

Principal evidence: `BrokerSecretRepositoryInstrumentedTest.persistedAndRestoredBrokerSecretHasOwnerOnlyPermissions`.

### P0-007 / P1 — Documentation, inventories, and fail-closed enforcement

- [x] Every assistant-created evidence file referenced by FIX9 exists at the exact repository path named.
- [x] Unsafe `runCatching`, unchecked authoritative filesystem booleans, empty-snapshot fallbacks, controller authoritative writes, unsafe `catch (Throwable)`, and Rust zero-timestamp fallbacks remain covered by active inventories/audits.
- [x] Required concurrency tests use bounded latches/deferred barriers rather than timing sleeps.
- [x] No retry, suppression, relaxed assertion, silent skip, or enlarged timeout was accepted as a FIX9 repair.

---

## Validation defects found and repaired

### Diagnostics-export double admission

Exact candidate `34b95051defd1a63d67836f01de6b1716f694ac3` correctly failed the second full Android unit invocation in `LogsViewModelTest.concurrentExportIsRejectedWhileOneIsAlreadyInFlight`.

- [x] `590c66717a7c67cb4d8fd08f48daa881d8834641` atomically claims shared path/URI export admission before coroutine launch.
- [x] `6bad7a1f18b180676cc567031f93f7a99fb91d52` makes the regression deterministic with a blocked IO dispatcher and bounded latches.
- [x] Path-scoped run `30600821345` passed full Gradle `check`, the dedicated foreground-service stop suite, the second full debug unit invocation, and path-scoped signoff without retry.

### Android emulator Settings-navigation false-positive

Exact candidate `7be00838feef85d374f20aa8dd6b7365969ba3dd` reached the real emulator but failed with `could not find Settings nav tab`.

Root cause: the old `bounds_of_text()` pipeline ended in `awk`, which returned status zero for empty input. The readiness check therefore treated a missing node as present and attempted the Settings tap before Compose navigation semantics were available.

- [x] `9ca07ff87d60e9c896bd1139a375fc84dbccc4cc` makes missing-node/bounds resolution return failure.
- [x] Startup waits for app-owned Settings semantics instead of a fixed sleep.
- [x] Settings resolution accepts the visible label or the explicit `Settings tab icon` content description.
- [x] No hardcoded coordinate fallback, silent bypass, retry suppression, or timeout inflation was added.
- [x] Real emulator job `91074610794` completed the wizard, reached `Listening`, negotiated WebRTC with the dockerized answer, delivered the marker through the tunnel, and verified the `PING`/`PONG` data-plane probe.

### Documentation-candidate Android startup prerequisite failure

Exact documentation candidate `9b1d1999fa81865adb051574221a68f4e15e8d74` correctly remained unsigned because Android emulator job `91080851616` failed with `home never rendered Settings navigation`.

All earlier required jobs on that SHA passed, including RC diagnostics, broker-secret instrumentation, Rust/Linux/macOS/Docker, the Android full Gradle `check`, the dedicated stop-failure suite, and the second full debug unit invocation. The emulator booted, installed the APK, cleared app state, and launched the app, but the harness did not obtain Settings semantics during the bounded startup wait.

Inspection found a second fail-open startup contract: after `pm clear`, the harness ran `pm grant POST_NOTIFICATIONS ... || true`. A failed runtime-permission grant could therefore be silently ignored while `NotificationPermissionGate` displayed a modal over the Home surface. The harness also used a non-waiting ActivityManager launch and discarded `uiautomator` failure details.

- [x] `673ea778d104673826f83592325fef46271133e9` removes the ignored permission result.
- [x] Android API level and notification permission state are verified after `pm grant`.
- [x] Device wake/keyguard dismissal and `am start -W` ActivityManager completion are required.
- [x] Failed or stale UI dumps are emptied instead of reused and retain bounded diagnostics.
- [x] A visible notification-permission modal is reported as a prerequisite failure rather than dismissed silently.
- [x] Startup failure diagnostics are bounded and run before identity/broker input, preventing secret capture.
- [x] The semantic wait remains bounded at 30 seconds; no retry-only rerun or timeout inflation is used.

---

## Validated functional candidate evidence

Commit `9ca07ff87d60e9c896bd1139a375fc84dbccc4cc` passed the complete applicable commit-level release-candidate matrix:

- [x] `ci/rc-diagnostics` — success, run `30603969425`
- [x] `ci/full-matrix` — success, main run `30603969420`
- [x] `ci/release-candidate` — success, main run `30603969420`
- [x] broker-secret permission instrumentation — success, run `30603969417`
- [x] Rust formatting/clippy/tests/package/lifecycle gates — success
- [x] Linux and macOS tests/install-layout/lifecycle gates — success
- [x] Android full Gradle `check` — success
- [x] dedicated foreground-service stop-failure truthfulness suite — success
- [x] second `assembleDebug testDebugUnitTest` invocation — success without retry
- [x] Docker TLS/data-path and graceful-stop E2E — success
- [x] Android emulator real-data-path E2E — success, job `91074610794`
- [x] full matrix signoff — success, job `91076875217`

The main workflow had no failed jobs or steps. Release-artifact jobs were skipped because commit candidates do not satisfy the tag-only packaging condition.

---

## Final exact-SHA closure rule

The final FIX9 documentation/evidence candidate is **the exact `[full-signoff]` commit containing this ledger and the startup-hardening correction**. The SHA must be taken from Git after publication; a parent, sibling, retry from another SHA, or earlier successful candidate is not interchangeable.

FIX9 commit-level closure is complete only when all of the following status records refer to that exact commit:

- `ci/rc-diagnostics`: `success`
- `ci/full-matrix`: `success`
- `ci/release-candidate`: `success`
- broker-secret permission instrumentation workflow: `success`
- Android full check, dedicated stop suite, second unit invocation, and emulator real-data-path E2E: `success`
- Rust/Linux/macOS/Docker required jobs: `success`

The authoritative CI status issues and commit status API are the machine-readable closure record. This self-reference avoids embedding a guessed SHA before the final commit exists and fails closed if any required gate fails or belongs to a different SHA.

## Release-tag follow-up — outside commit-level FIX9 closure

- [ ] Create the eventual release tag only after commit-level FIX9 closure.
- [ ] Require tag-only APK/AAB packaging jobs to succeed on that release tag.
- [ ] Verify the produced release artifacts before publication.

Tag-only packaging is deliberately not a dependency of `ci/full-matrix` or `ci/release-candidate` for a `[full-signoff]` commit. Skipping those jobs on the commit candidate is expected and must not be misreported as artifact validation.
