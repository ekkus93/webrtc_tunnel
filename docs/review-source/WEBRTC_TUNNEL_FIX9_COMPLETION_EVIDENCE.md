# WebRTC Tunnel FIX9 Completion Evidence

**Source TODO:** `docs/WEBRTC_TUNNEL_STALE_SETUP_RESULT_CONTRACT_FIX9_TODO.md`  
**Initial audit baseline:** `141a5425f620ae6b37a29ee0d8956cbfbd4d7b27`  
**Final implementation baseline before signoff docs:** `41b3e08cffe83292776eaeb62524a4133837e19a`  
**Disposition:** implementation and enforcement complete; exact-SHA release-candidate validation is triggered by the companion `[full-signoff]` commit and recorded after its workflows terminate.

This ledger is the task-by-task evidence map for FIX9. A checkbox is considered complete only where the production path, a named test or source audit, and the relevant CI gate agree. It does not treat helper-only coverage or a partially green workflow as signoff.

## P0-001 — Real setup-operation stale/cancel semantics

- **P0-001-A/B:** Complete. `SetupOperationToken` replaces raw operation ids; every guarded controller block receives the token and uses its freshness API.
- **P0-001-C:** Complete. Admission records the owning coroutine `Job`; `SetupViewModel.cancel()` invalidates the token and cancels that exact job. Stale owners cannot clear a newer owner.
- **P0-001-D:** Complete. Path import, URI import, and generation gate `SetupIdentityDraft.replace(...)`; discarded private material is wiped.
- **P0-001-E:** Complete. Forward upsert/delete gate draft mutation and state publication through the token.
- **P0-001-F:** Complete. Final save checks freshness before global admission, before persistence, after persistence, and before optional foreground-service start. Cancellation inside persistence rolls back attempted stages under `NonCancellable`; rollback failures are surfaced durably.
- **P0-001-G:** Complete through public ViewModel/controller paths. Named coverage includes:
  - `cancelDuringIdentityImportFromPathDoesNotPublishImportedIdentity`
  - `cancelDuringIdentityImportFromUriDoesNotPublishImportedIdentity`
  - `cancelDuringGenerateIdentityDoesNotPublishGeneratedIdentity`
  - `cancelDuringForwardUpsertDoesNotPublishDraftChange`
  - `cancelDuringForwardDeleteDoesNotPublishDraftChange`
  - navigation-validation cancellation coverage
  - `cancelDuringFinalSaveValidationDoesNotPersistOrPublishSuccess`
  - `cancelDuringFinalSaveRollsBackAuthoritativeStagesAndCancelsJob`
  - `cancelDuringStartTunnelFromReviewDoesNotStartForegroundService`
  - `staleFinalSaveCannotClearNewerSetupError`

### Readiness/admission invariant found during final validation

Run `30593967688` exposed a production race: `SetupLoadState.Ready` was published while `BaselineLoad` still owned setup admission, allowing the first forward/save action to be rejected as `Busy(BaselineLoad)`. Commit `41b3e08cffe83292776eaeb62524a4133837e19a` now publishes `Ready` or `Failed` only after `runGuarded` releases admission and re-stamps `isBusy=false`. Unexpected baseline exceptions become a redacted, durable `SetupLoadState.Failed`; there is no blank/default fallback.

## P0-002 — `Result` contract hardening

- **P0-002-A:** Complete. `savePreferences(...)` converts ordinary exceptions to `Result.failure` and rethrows cancellation. Settings and Network Policy tests assert fixed visible error codes.
- **P0-002-B:** Complete. `prepareActiveConfigForStart(...)` returns failure for read/rewrite exceptions and aborts start.
- **P0-002-C:** Complete. `replaceConfigTransactionally(...)` returns failure when snapshot capture fails and does not write or restore.
- **P0-002-D:** Complete. `Fix9ResultContractSourceAuditTest`, the existing `CheckResult` enforcement suite, and negative fixtures cover selected-subclass catches, pre-try `getOrThrow()`, ignored results, and fake `.also { }` consumption.

## P0-003 — Coherent public-identity reads

- **P0-003-A:** Complete. `IdentityRepository.readPublicIdentity()` executes under the same `storageLock` used by identity-pair replacement; production consumers use coherent material where pair context is required.
- **P0-003-B:** Complete. `IdentityRepositoryCoherentReadTest` pauses pair replacement at the production boundary and proves the public read cannot observe a half-written pair.

## P0-004 — Canonical private identity import

Complete. `ImportExportService.importPrivateIdentityContent(...)` requires canonical private and public fields, never falls back to source text, and wipes canonical private bytes. Negative tests cover missing canonical private/public material.

## P0-005 — Draft-truth messaging

Complete. Setup-only forward edits publish `Forward draft updated` / `Forward draft removed`; source enforcement bans the old authoritative-sounding messages.

## P0-006 — Android broker-secret permissions

Complete. `BrokerSecretRepositoryInstrumentedTest.persistedAndRestoredBrokerSecretHasOwnerOnlyPermissions` asserts exact `0600` bits after persist and snapshot restore on Android. Dedicated workflow run `30505896676` passed. Permission-enforcement failure remains a returned/visible failure and does not silently proceed.

## P0-007 — Documentation and exact-SHA truth

- **P0-007-A:** Complete through this ledger, the synchronized implementation report, and validation request. Every referenced assistant-created file is present at the exact repository path named.
- **P0-007-B:** The companion `[full-signoff]` commit is the immutable release-candidate SHA. Its `ci/rc-diagnostics`, `ci/full-matrix`, `ci/release-candidate`, and broker instrumentation results are recorded in a follow-up docs-only evidence update after workflows terminate. No production/test/workflow changes are permitted in that recording commit.

## P1 — Enforcement hardening

- **P1-001:** Complete. Stale-operation tests use public production methods and deterministic barriers; no timing sleep proves correctness.
- **P1-002:** Complete. `Fix9SetupFreshnessSourceAuditTest` strips comments and enforces token use at identity-draft, forward-draft, final-persist, and tunnel-start boundaries.
- **P1-003:** Complete. FIX9 Result-contract source audits and negative fixtures enforce cancellation-first and ordinary-failure-as-value behavior.
- **P1-004:** Complete. Existing FIX8 inventories remain active for `runCatching`, unchecked authoritative filesystem booleans, empty-snapshot fallbacks, controller authoritative writes, unsafe `catch (Throwable)`, and Rust zero-timestamp diagnostic fallbacks.

## P2 — Validation evidence

- **P2-001 focused Android:** Covered by the path-scoped Android `check` gate and named FIX9/legacy regression suites. The bounded harness ensures a deadlock produces a named timeout rather than an indefinitely running job.
- **P2-002 full Android:** Must pass on the exact `[full-signoff]` candidate; recorded after completion.
- **P2-003 Rust:** Must pass on the exact `[full-signoff]` candidate; recorded after completion.
- **P2-004 Docker/Android E2E:** Docker and Android emulator/data-plane gates must pass on the exact `[full-signoff]` candidate. Broker permission instrumentation is separately authoritative.
- **P2-005 release-candidate:** Must show the same candidate SHA for `ci/rc-diagnostics`, `ci/full-matrix`, and `ci/release-candidate`.

## Completion criteria disposition

All functional, persistence, identity-coherence, canonical-import, draft-truth, permission, and static-enforcement criteria are implemented. FIX9 is declared release-signed only after the exact companion candidate's required workflows pass; a green subset or a different SHA is not sufficient.
