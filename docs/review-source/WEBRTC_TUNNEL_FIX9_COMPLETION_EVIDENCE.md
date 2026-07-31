# WebRTC Tunnel FIX9 Completion Evidence

**Source TODO:** `docs/WEBRTC_TUNNEL_STALE_SETUP_RESULT_CONTRACT_FIX9_TODO.md`  
**Initial audit baseline:** `141a5425f620ae6b37a29ee0d8956cbfbd4d7b27`  
**Implementation baseline submitted for the next exact-SHA signoff:** `6bad7a1f18b180676cc567031f93f7a99fb91d52`  
**Disposition:** functional implementation and enforcement are complete; release signoff remains conditional on all required workflows succeeding on the exact companion `[full-signoff]` commit.

This ledger maps FIX9 requirements to production paths and named evidence. A helper seam or partially green workflow is not treated as completion.

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
- `Fix9ResultContractSourceAuditTest`, the existing `CheckResult` enforcement suite, and negative fixtures reject selected-subclass catches, pre-try `getOrThrow()`, ignored results, and fake `.also { }` consumption.

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

## Failed exact candidate `34b95051defd1a63d67836f01de6b1716f694ac3`

The candidate was correctly rejected:

- `ci/rc-diagnostics`: **success**, run `30599682091`
- broker-secret instrumentation: **success**, run `30599682072`
- Rust lint/Linux/macOS/Docker: **success**, main run `30599682106`
- Android full Gradle `check`: **success**
- dedicated foreground-service stop-failure suite: **success**
- second `assembleDebug testDebugUnitTest`: **failure**
- `ci/full-matrix`: **failure**
- `ci/release-candidate`: **failure**
- Android emulator E2E: correctly skipped because Android failed

The failing test, `LogsViewModelTest.concurrentExportIsRejectedWhileOneIsAlreadyInFlight`, exposed a real check-before-launch race: both diagnostics export entry points checked `_isBusy` before launching but claimed it only inside the coroutine. Two immediate calls could both be admitted.

## Diagnostics export admission correction

- Commit `590c66717a7c67cb4d8fd08f48daa881d8834641` atomically claims shared path/URI export admission with `MutableStateFlow.compareAndSet(false, true)` before launch.
- Commit `6bad7a1f18b180676cc567031f93f7a99fb91d52` replaces scheduler-speed assumptions with a blocked single-thread IO dispatcher and bounded latches. The regression now proves admission synchronously, attempts the second export while IO is parked, releases the first, and verifies the rejected destination is never written.
- No retry, sleep, suppression, relaxed rule, or extended timeout is used.
- Path-scoped Android run `30600821345` passed full Gradle `check`, the dedicated stop-failure suite, the second `assembleDebug testDebugUnitTest`, and path-scoped full-matrix signoff.

## Exact-SHA signoff requirements

The companion `[full-signoff]` commit must prove, on that same SHA:

- `ci/rc-diagnostics`: success
- `ci/full-matrix`: success
- `ci/release-candidate`: success
- broker-secret instrumentation: success
- Android full Gradle check and the second full debug unit invocation: success
- Android emulator real-data-path E2E: success
- Rust fmt/clippy/tests/package/lifecycle gates: success
- Docker TLS/data-path and graceful-stop E2E: success

Release APK/AAB packaging jobs are **tag-only** by workflow design. They are expected to skip on a `[full-signoff]` commit and are not dependencies of `ci/full-matrix` or `ci/release-candidate`; they must pass when a release tag is created.

FIX9 is not release-signed until the exact companion candidate satisfies every applicable requirement above.
