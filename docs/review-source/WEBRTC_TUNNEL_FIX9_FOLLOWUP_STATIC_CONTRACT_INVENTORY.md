# WebRTC Tunnel — FIX9 Follow-up Static Contract Inventory

**Scope:** post-FIX9 review hardening  
**Baseline TODO:** `docs/WEBRTC_TUNNEL_FIX9_REVIEW_FOLLOWUP_TODO.md`

This inventory separates **type-aware/compiler-backed enforcement**, **source tripwires**, and **runtime proof**. A source-text audit is useful for detecting recognizable regressions, but it is not represented here as a semantic proof of all possible Kotlin programs.

## 1. Proof-level and type-aware enforcement

### Android Lint `CheckResult`

`android/app/build.gradle.kts` promotes Android Lint's `CheckResult` issue to a build error. Authoritative result-returning APIs carry `@CheckResult` or `@get:CheckResult`, so an ordinary bare ignored call fails the module's lint/check gate.

`CheckResultEnforcementFixtureTest` runs the real Android Lint detector against positive and negative fixtures. This proves that the actual detector, rather than a local reimplementation, flags a bare ignored result and accepts a consumed result.

### Runtime regression tests

Runtime tests remain the proof for behavior that source shape cannot establish, including:

- cancellation propagation and transactional rollback;
- stale setup operation suppression at real suspend/native boundaries;
- coherent identity-pair reads and atomic replacement;
- exact file snapshot/restore behavior;
- owner-only broker-secret permissions on Android;
- setup save rejection when a stored public identity cannot match the peer ID derived from the decrypted private identity.

## 2. Source tripwires

### Existing FIX9 source audit

`Fix9SourceContractAuditTest` checks stable source contracts for:

- setup-controller routing through `SetupOperationCoordinator.runGuarded`;
- final-save stale check immediately before persistence;
- cancellation of the admitted setup owner job;
- coherent public-identity reads under `storageLock`;
- cancellation-first `Result` contract bodies;
- selected-subclass catches, pre-`try` `getOrThrow()`, and fake `.also { }` negative fixtures.

These checks are intentionally narrow source windows. They provide a clear regression alarm but do not replace runtime tests or type-aware lint.

### Post-FIX9 follow-up audit

`Fix9FollowupContractAuditTest` adds:

- balanced-call scanning for authoritative results made to look consumed through `.also { }`, `.apply { }`, or `run { authoritativeCall(); Unit }`;
- positive and negative fixtures for those patterns;
- a mutation-sensitive file inventory that rejects `runCatching` and `catch (Throwable)` in authoritative mutation/controller paths;
- explicit production-path setup-token contract checks for identity path/URI import, generation, remote identity import/validation, forward upsert/delete, validation navigation, final save, and start-from-review.

The scanner reports the production file and offending API. It runs through the ordinary Gradle unit-test/check gate.

## 3. Authoritative result/receipt API inventory

The following APIs must have their result consumed, propagated, converted to a visible failure, or explicitly inspected:

### Configuration

- `ConfigRepository.savePreferences(...)`
- `ConfigRepository.prepareActiveConfigForStart(...)`
- `ConfigRepository.writeConfigAtomically(...)`
- `ConfigRepository.replaceConfigTransactionally(...)`
- `ConfigRepository.saveSetupInputAtomically(...)`
- `ConfigRepository.restoreSetupInputFileSnapshot(...)`
- `ConfigRepository.restoreConfigSnapshot(...)`
- `ConfigRepository.captureFilesSnapshot(...)`

### Broker secret

- `BrokerSecretRepository.persist(...)`
- `BrokerSecretRepository.restore(...)`
- `BrokerSecretRepository.captureSnapshot(...)`

### Identity

- `IdentityRepository.appendAuthorizedPublicIdentity(...)`
- `IdentityRepository.restoreStorageSnapshot(...)`
- `IdentityRepository.restoreIdentityPairSnapshot(...)`
- `IdentityRepository.restoreAuthorizedKeysSnapshot(...)`

### Forwards

- `ForwardsRepository.upsertWithReceipt(...)`
- `ForwardsRepository.deleteWithReceipt(...)`
- `ForwardsRepository.rollbackReceipt(...)`
- `ForwardsRepository.resetForwards(...)`
- `ForwardsRepository.captureForTransaction(...)`
- `ForwardsRepository.replaceForTransaction(...)`
- `ForwardsRepository.restoreForTransaction(...)`

### Transaction coordinator

- `SetupPersistenceCoordinator.persist(...)`

## 4. Accepted consumption forms

Accepted forms include:

- returning the result to the caller;
- `.getOrThrow()` within a cancellation-first protected mutation boundary;
- `.getOrElse { ... }`, `.fold(...)`, or equivalent visible failure mapping;
- checking `isSuccess` / `isFailure` and handling both states;
- using `exceptionOrNull()` as part of explicit composition/rollback reporting;
- exhaustive handling of a receipt/result sealed type.

Assignment alone is not treated as sufficient unless the assigned value is later inspected. Kotlin does not provide a meaningful `_` discard assignment for this purpose, so that proposed pattern is not an applicable production exception.

## 5. Forbidden patterns

- Bare ignored calls to an authoritative `@CheckResult` API.
- `.also { }` or `.apply { }` used only to make an authoritative result appear consumed.
- `run { authoritativeCall(); Unit }` or equivalent deliberate discard wrappers.
- `runCatching` around authoritative mutations, because it catches `CancellationException` and fatal `Error` values through `Throwable`.
- `catch (Throwable)` that swallows or converts cancellation/fatal errors.
- Selected-subclass-only catches in an API whose contract is to convert every ordinary `Exception` to `Result.failure`.
- `getOrThrow()` before the protecting `try` in a result-contract function.
- State publication after a setup suspend boundary without the admitted operation's freshness token.

## 6. Limitations

The follow-up scanner is deliberately dependency-light and source-oriented. It does not claim full AST/data-flow equivalence. Android Lint, Kotlin type resolution, unit/runtime regressions, and exact-SHA CI remain authoritative. A future custom Detekt rule may replace the source scanner when the project has a justified need for deeper interprocedural analysis; until then, the current layered model avoids both false claims and a bespoke compiler-plugin maintenance burden.
