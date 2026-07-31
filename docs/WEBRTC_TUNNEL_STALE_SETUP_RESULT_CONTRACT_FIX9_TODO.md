# WebRTC Tunnel — Stale Setup Operation and Result Contract Hardening FIX9 TODO

**Target branch:** `master`  
**Initial audit baseline:** `141a5425f620ae6b37a29ee0d8956cbfbd4d7b27`  
**Implementation baseline:** `6bad7a1f18b180676cc567031f93f7a99fb91d52`  
**Validated functional repair candidate:** `9ca07ff87d60e9c896bd1139a375fc84dbccc4cc`  
**Startup-hardening correction:** `673ea778d104673826f83592325fef46271133e9`  
**FIX9 exact-SHA closure:** `9503b4aba0f3046446a0392522fa7eac242a9343`  
**Current disposition:** core FIX9 implementation and commit-level release-candidate validation are complete.

This file is the FIX9 closure ledger. Historical implementation snippets and the detailed implementation-era checklist remain available in Git history at revisions before the closure-ledger conversion.

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
- [x] Public ViewModel/controller tests cover cancellation at production suspend/native/persistence boundaries with deterministic barriers.

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
- [x] Static enforcement rejects selected-subclass catches, pre-`try` `getOrThrow()`, ignored authoritative results, and fake result-consumption patterns.

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
- [x] Source text is never used as an unsafe import fallback.
- [x] Canonical private bytes are wiped after use.
- [x] Missing canonical import output fails visibly.

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

The old bounds pipeline returned success for empty input, allowing a missing semantic node to masquerade as present.

- [x] `9ca07ff87d60e9c896bd1139a375fc84dbccc4cc` makes missing-node/bounds resolution return failure.
- [x] Startup waits for app-owned Settings semantics instead of a fixed sleep.
- [x] Settings resolution accepts the visible label or explicit `Settings tab icon` content description.
- [x] No hardcoded coordinate fallback, silent bypass, retry suppression, or timeout inflation was added.
- [x] Emulator job `91074610794` completed the wizard, reached `Listening`, delivered the tunnel marker, and verified the PING/PONG probe.

### Documentation-candidate Android startup prerequisite failure

Exact documentation candidate `9b1d1999fa81865adb051574221a68f4e15e8d74` correctly remained unsigned because Android emulator job `91080851616` failed with `home never rendered Settings navigation`.

Inspection found a second fail-open startup contract: after `pm clear`, the harness ignored `pm grant POST_NOTIFICATIONS` failure. It also used a non-waiting ActivityManager launch and discarded UI-dump failure detail.

- [x] `673ea778d104673826f83592325fef46271133e9` removes the ignored permission result.
- [x] Android API level and notification permission state are verified after `pm grant`.
- [x] Device wake/keyguard dismissal and `am start -W` completion are required.
- [x] Failed/stale UI dumps are emptied rather than reused and retain bounded diagnostics.
- [x] A visible permission modal is reported as a prerequisite failure rather than dismissed silently.
- [x] Diagnostics run before identity/broker input and remain bounded/redacted.
- [x] The semantic wait remains bounded at 30 seconds; no retry-only rerun or timeout inflation is used.

---

## Exact-SHA FIX9 closure evidence

Commit `9503b4aba0f3046446a0392522fa7eac242a9343` passed the complete applicable commit-level release-candidate matrix:

- [x] `ci/rc-diagnostics` — success
- [x] `ci/full-matrix` — success
- [x] `ci/release-candidate` — success
- [x] broker-secret permission instrumentation — success
- [x] Rust formatting/clippy/tests/package/lifecycle gates — success
- [x] Linux and macOS tests/install-layout/lifecycle gates — success
- [x] Android full Gradle `check` — success
- [x] dedicated foreground-service stop-failure truthfulness suite — success
- [x] second `assembleDebug testDebugUnitTest` invocation — success without retry
- [x] Docker TLS/data-path and graceful-stop E2E — success
- [x] Android emulator real-data-path E2E — success
- [x] full matrix signoff — success

Release-artifact jobs skipped on that commit because commit candidates do not satisfy the tag-only packaging condition. That skip was expected and was not reported as artifact validation.

---

## Post-closure review follow-up

The post-FIX9 comprehensive review found non-blocking release-process/test-contract/static-audit issues. They are implemented and validated separately through:

- `docs/WEBRTC_TUNNEL_FIX9_REVIEW_FOLLOWUP_TODO.md`
- `docs/review-source/WEBRTC_TUNNEL_FIX9_REVIEW_FOLLOWUP_IMPLEMENTATION_REPORT.md`

Those changes do not retroactively invalidate FIX9 closure.

## Current tagged release-artifact contract

The current tag workflow publishes **Rust CLI/daemon archives only**. It does not build, sign, verify, or publish Android APK/AAB release assets.

- [ ] Create an eventual release tag only when an actual Rust release is intended.
- [ ] Require the existing tag-only Rust archive jobs to succeed on that release tag.
- [ ] Verify the Rust archives before publication.
- [x] Do not claim APK/AAB validation from the current workflow.

Android release publication is deliberately outside the current contract because the project does not yet define a production signing identity, protected keystore/credential flow, certificate-fingerprint verification, or signed-artifact provenance policy. Future Android work is tracked in:

- `docs/WEBRTC_TUNNEL_ANDROID_SIGNED_RELEASE_ARTIFACTS_TODO.md`

Unsigned or debug-signed Android files must not be presented as production release assets. Tag-only packaging remains outside `ci/full-matrix` and `ci/release-candidate` for an ordinary `[full-signoff]` commit.
