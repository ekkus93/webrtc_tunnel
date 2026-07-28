# WebRTC Tunnel — Stale Setup Operation and Result Contract Hardening FIX9 TODO

**Target branch:** `master`  
**Audit baseline:** `141a5425f620ae6b37a29ee0d8956cbfbd4d7b27`  
**Primary goal:** close the remaining FIX8 gaps found during the post-FIX8 audit, especially production-path stale setup operations, incomplete `Result` contracts, and remaining unsafe/truthfulness issues.

This TODO is intentionally implementation-oriented. Do not mark a task complete because a helper seam works in isolation. Every invariant below must be proven through the real production call path that a user can exercise.

---

## P0 — Must fix before further release signoff

# P0-001 — Enforce real setup-operation stale/cancel semantics

**Severity:** High  
**Problem:** `SetupOperationCoordinator` has `invalidate()` and `isStale(id)`, but several real controller paths ignore the operation token before publishing state or committing authoritative storage. `SetupViewModel.cancel()` can reset the wizard while an older import/generate/forward/save operation later publishes state or commits configuration.

**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupOperationCoordinator.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupIdentityController.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupForwardsController.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupSaveController.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/SetupDraftOperationCoordinationTest.kt
new focused tests as needed
```

## P0-001-A — Replace raw `Long` token with an explicit operation token

- [ ] Add a small internal token type that carries the operation id and operation name.
- [ ] Expose a single freshness API on the token, rather than requiring every caller to remember `operations.isStale(id)`.
- [ ] Make stale publication hard to write accidentally.
- [ ] Avoid ambiguous Boolean returns; stale, busy, cancelled, and completed must be distinguishable in tests.

Suggested shape:

```kotlin
internal class SetupOperationToken internal constructor(
    val id: Long,
    val operation: SetupDraftOperation,
    private val isStale: (Long) -> Boolean,
) {
    fun isFresh(): Boolean = !isStale(id)

    fun ensureFresh() {
        check(isFresh()) { "Setup operation became stale: $operation" }
    }

    inline fun publishIfFresh(block: () -> Unit): Boolean {
        if (!isFresh()) return false
        block()
        return true
    }
}
```

A stale operation should usually skip state publication and return normally. Use `ensureFresh()` only at boundaries where reaching the boundary at all is a programming error in the current design.

## P0-001-B — Make `runGuarded` pass the token to real controller blocks

- [ ] Change `runGuarded` from `block: suspend (id: Long) -> T` to `block: suspend (token: SetupOperationToken) -> T`.
- [ ] Keep immediate `isBusy=true` publication after admission.
- [ ] Keep cancellation-first handling.
- [ ] Keep redacted ordinary-exception safety net.
- [ ] Ensure the final `isBusy=false` re-stamp does not resurrect stale state after `cancel()`.
- [ ] If the final re-stamp is stale, it may only publish the current reset state without old operation data.

Suggested pattern:

```kotlin
suspend fun <T> runGuarded(
    access: WizardStateAccess,
    operation: SetupDraftOperation,
    block: suspend (SetupOperationToken) -> T,
): T? {
    try {
        val admission = tryRun(operation) { id ->
            val token = SetupOperationToken(id, operation, ::isStale)
            access.applyState(access.state())
            try {
                block(token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (token.isFresh()) {
                    access.applyState(
                        access.state().copy(
                            errorMessage = SensitiveDataRedactor.redactText(
                                error.message ?: "Setup action failed",
                            ),
                            saveResult = null,
                        ),
                    )
                }
                null
            }
        }
        return when (admission) {
            is SetupDraftAdmission.Completed -> admission.value
            is SetupDraftAdmission.Busy -> {
                access.applyState(
                    access.state().copy(
                        errorMessage =
                            "A setup action is already in progress (setup_draft_operation_busy): " +
                                "${admission.active}",
                        saveResult = null,
                    ),
                )
                null
            }
        }
    } finally {
        access.applyState(access.state())
    }
}
```

Adjust this snippet as needed if P0-001-C adds active-job cancellation.

## P0-001-C — Cancel or invalidate active setup work from `SetupViewModel.cancel()`

- [ ] Decide and implement one explicit policy for `cancel()` while an operation is active:
  - non-final setup actions: cancel active job, invalidate token, wipe draft, reset UI;
  - final save: either cancel and require rollback to complete, or block cancel with a durable visible message while the commit is at a point of no return.
- [ ] Do **not** silently reset UI while leaving a long-running operation free to publish or commit afterward.
- [ ] If using cancellation, track the active coroutine `Job` inside `SetupOperationCoordinator`.
- [ ] `invalidate()` must cancel the active operation job with a fixed `CancellationException` reason.
- [ ] A stale job must not be able to clear a newer operation's active owner.
- [ ] A cancelled final save must either roll back or surface a durable rollback-incomplete diagnostic; it must never quietly commit after the wizard was abandoned.

Suggested active-job shape:

```kotlin
private data class ActiveSetupDraftOperation(
    val id: Long,
    val operation: SetupDraftOperation,
    val job: Job,
)

fun invalidateAndCancelActive(reason: String = "setup abandoned") {
    val current = active.get()
    staleBefore.set(sequence.get())
    current?.job?.cancel(CancellationException(reason))
}
```

Inside `tryRun`, capture `currentCoroutineContext()[Job]` after admission and store it in the active token. Ensure tests cover stale-owner races.

## P0-001-D — Guard identity import/generate success publication

- [ ] `importIdentityFromPath()` must check freshness after file read and native validation, before `identityDraft.replace(...)`.
- [ ] `importIdentityFromUri(...)` must check freshness after content read and native validation, before `identityDraft.replace(...)`.
- [ ] `generateIdentity()` must check freshness after native generation, before `identityDraft.replace(...)`.
- [ ] If a `DraftIdentityReplacement` was built but the token is stale, wipe its private bytes before returning.
- [ ] Stale completion must not change `identityPeerId`, `localPublicIdentity`, `input.localPeerId`, `errorMessage`, or `saveResult`.

Suggested helper:

```kotlin
private inline fun DraftIdentityReplacement.useIfFresh(
    token: SetupOperationToken,
    block: (DraftIdentityReplacement) -> Unit,
) {
    if (!token.isFresh()) {
        wipe()
        return
    }
    block(this)
}
```

## P0-001-E — Guard forward draft edits

- [ ] `upsertForward(...)` must check freshness after validation and immediately before `access.setForwards(after)`.
- [ ] `deleteForward(...)` must check freshness immediately before `access.setForwards(...)`.
- [ ] Stale forward edit completion must not change the in-memory forwards draft.
- [ ] Stale forward edit completion must not publish `saveResult` or clear an error published by a newer state.

Suggested pattern:

```kotlin
access.operations.runGuarded(access, SetupDraftOperation.ForwardEdit) { token ->
    if (access.loadState() !is SetupLoadState.Ready) {
        token.publishIfFresh {
            access.applyState(
                access.state().copy(
                    errorMessage = setupLoadNotReadyMessage(access.loadState()),
                    saveResult = null,
                ),
            )
        }
        return@runGuarded
    }

    val after = withUpsert(access.forwards(), forward)
    val error = deps.forwardsStore.validateForwards(after)
    if (error != null) {
        token.publishIfFresh { access.applyState(access.state().copy(errorMessage = error, saveResult = null)) }
        return@runGuarded
    }

    token.publishIfFresh {
        access.setForwards(after)
        access.applyState(access.state().copy(errorMessage = null, saveResult = "Forward draft updated"))
    }
}
```

## P0-001-F — Guard final save and start-from-review

- [ ] `saveAndApplyConfigInternal()` must receive and use a setup token.
- [ ] Check freshness before validation starts.
- [ ] Check freshness after validation/native calls and before entering the global `ConfigurationMutationCoordinator`.
- [ ] Check freshness immediately before `persistence.persist(request)`.
- [ ] Check freshness after `persistence.persist(request)` returns and before publishing success/failure UI state.
- [ ] `startTunnelFromReview()` must not call `ContextCompat.startForegroundService(...)` if the save token became stale.
- [ ] If a stale operation somehow completed an authoritative commit, publish a durable, redacted warning instead of silently claiming normal cancellation. Prefer preventing this entirely.

Recommended policy:

```text
Before global SetupSave admission: stale means no-op and return false.
After global SetupSave admission but before persistence: stale means no-op and return false.
During persistence: cancellation must roll back through SetupPersistenceCoordinator.
After persistence success: stale means do not start tunnel and do not publish stale wizard state.
```

## P0-001-G — Real-path tests for stale setup operations

Add tests that use the public ViewModel/controller methods, not only direct calls to `operations.runGuarded`.

- [ ] `cancelDuringIdentityImportFromPathDoesNotPublishImportedIdentity`
- [ ] `cancelDuringIdentityImportFromUriDoesNotPublishImportedIdentity`
- [ ] `cancelDuringGenerateIdentityDoesNotPublishGeneratedIdentity`
- [ ] `cancelDuringForwardUpsertDoesNotPublishDraftChange`
- [ ] `cancelDuringForwardDeleteDoesNotPublishDraftChange`
- [ ] `cancelDuringNavigationValidationDoesNotAdvanceStep` (confirm existing coverage or add real-path coverage)
- [ ] `cancelBeforeFinalSaveCommitDoesNotPersistConfigIdentityForwardsOrPreferences`
- [ ] `cancelDuringFinalSaveRollsBackOrReportsRollbackIncomplete`
- [ ] `cancelDuringStartTunnelFromReviewDoesNotStartForegroundService`
- [ ] `staleFinalSaveCannotClearNewerSetupError`

Use deterministic fakes/barriers, not timing sleeps. The test must park at the production boundary being proven, call `viewModel.cancel()`, release the barrier, and assert that stale completion cannot publish or commit.

---

# P0-002 — Fix remaining `Result` contract violations

**Severity:** High  
**Problem:** Several APIs return `Result<T>` but can still throw ordinary exceptions. Callers fold the `Result` into durable UI state and therefore assume ordinary failures are values, not uncaught coroutine failures.

**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigRepository.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/data/ConfigRepositoryTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/SettingsViewModelTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/NetworkPolicyViewModelTest.kt
static audit tests as needed
```

## P0-002-A — `savePreferences` catches every ordinary exception

- [ ] Replace selected catches with cancellation-first `catch (Exception)`.
- [ ] Preserve `CancellationException` propagation.
- [ ] Ensure `SecurityException`, `IllegalArgumentException`, and DataStore ordinary failures become `Result.failure`.
- [ ] Add tests proving `SettingsViewModel.savePreferences(...)` publishes `preferences_save_failed` for an injected ordinary exception.
- [ ] Add tests proving `NetworkPolicyViewModel.savePreferences(...)` publishes `network_preference_save_failed` for an injected ordinary exception.

Target shape:

```kotlin
@CheckResult
open suspend fun savePreferences(update: AndroidAppPreferences): Result<Unit> =
    try {
        context.dataStore.edit { prefs ->
            // existing writes
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
```

## P0-002-B — `prepareActiveConfigForStart` returns `Result.failure` for read failures

- [ ] Wrap the config read and rewrite sequence in cancellation-first `try/catch(Exception)`.
- [ ] Do not allow `configContents` read failures to escape.
- [ ] Keep the current behavior for missing/blank config: no-op success.
- [ ] Add a test where the active config read throws `SecurityException`; assert the returned value is `Result.failure`.
- [ ] Add a start-path test proving this failure aborts native start visibly rather than falling through with stale config.

Suggested shape:

```kotlin
@CheckResult
open suspend fun prepareActiveConfigForStart(
    iceMode: String,
    advertisedIpv4: String?,
): Result<Unit> =
    fileMutex.withLock {
        try {
            val current = if (configFile.exists()) configFile.readText() else ""
            if (current.isBlank()) return@withLock Result.success(Unit)
            val withIceMode = upsertAndroidIceMode(current, resolveAndroidIceMode(iceMode))
            writeConfigAtomicallyWith(
                configFile,
                upsertAdvertisedLocalIpv4(withIceMode, advertisedIpv4),
                atomicFileOps,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
```

## P0-002-C — `replaceConfigTransactionally` returns `Result.failure` for snapshot capture failure

- [ ] Move prior snapshot capture inside the ordinary-failure `try/catch` or explicitly catch snapshot capture failure.
- [ ] Preserve cancellation propagation.
- [ ] If snapshot capture fails, do not attempt write or restore.
- [ ] Return `Result.failure(snapshotFailure)`.
- [ ] Add test `replaceConfigCaptureFailureReturnsFailureAndDoesNotWrite`.

Suggested shape:

```kotlin
internal open val replaceConfigTransactionally: suspend (String) -> Result<Unit> = { contents ->
    fileMutex.withLock {
        val priorSnapshot =
            try {
                captureExactFileSnapshot(configFile).getOrThrow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return@withLock Result.failure(error)
            }
        // existing write / restore logic
    }
}
```

## P0-002-D — Audit every `@CheckResult Result` API for ordinary throws

- [ ] Search every production Kotlin `Result<...>` return and verify ordinary exceptions are values.
- [ ] Re-verify suspend functions rethrow `CancellationException` first.
- [ ] Re-verify non-suspend helpers with injectable lambdas do not accidentally catch synthetic `CancellationException` as ordinary failure when tests inject it.
- [ ] Add a static test or source inventory that fails when a `Result` function catches only selected ordinary subclasses without an allowlist comment and direct test.
- [ ] Do not accept `.also { }` as consumption for a result whose value must alter behavior.

---

# P0-003 — Make public-identity reads coherent with identity-pair writes

**Severity:** Medium  
**Problem:** `storeEncryptedIdentity(...)` serializes encrypted/public pair writes under `storageLock`, but `readPublicIdentity()` reads `identity.pub` without the same lock. A concurrent replacement can still race a setup baseline or settings read.

**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/security/IdentityRepository.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupIdentityController.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SettingsViewModel.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/security/IdentityRepositoryTest.kt
related ViewModel tests
```

## P0-003-A — Lock `readPublicIdentity()` or replace it with a coherent material read

- [ ] Make `readPublicIdentity()` execute under `storageLock`; or
- [ ] Replace all production uses with a coherent snapshot API.
- [ ] Preserve the distinction between missing public identity and unreadable public identity where the caller needs it.
- [ ] Do not add a separate `hasPublicIdentity` check followed by an unlocked read.

Minimal acceptable fix:

```kotlin
fun readPublicIdentity(): String =
    synchronized(storageLock) {
        if (publicFile.exists()) publicFile.readText() else ""
    }
```

Better fix if more context is needed:

```kotlin
internal val readPublicIdentityResult: Result<String>
    get() = synchronized(storageLock) {
        try {
            Result.success(if (publicFile.exists()) publicFile.readText() else "")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
```

## P0-003-B — Add concurrency tests

- [ ] Add a fake/injected atomic replace barrier that pauses after encrypted identity replacement but before public identity replacement.
- [ ] During that pause, call the production public-identity read path.
- [ ] Prove the read cannot observe a half-replaced pair.
- [ ] Add `readPublicIdentityCannotObserveHalfWrittenPair` or equivalent.
- [ ] Ensure Setup baseline and Settings public identity load use the coherent path.

---

# P0-004 — Remove remaining canonical identity fallback in import/export service

**Severity:** Medium/Low  
**Problem:** Setup identity import fails closed on missing canonical private/public/peer fields, but `ImportExportService.importPrivateIdentityContent(...)` still uses `validated.canonicalPrivateIdentity ?: privateIdentity`. If the native validator regresses to `valid=true` without canonical private material, Kotlin silently persists source text.

**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ImportExportService.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/ImportExportServiceTest.kt
```

## P0-004-A — Require canonical private identity on private import

- [ ] Replace `validated.canonicalPrivateIdentity ?: privateIdentity` with `requireNotNull(validated.canonicalPrivateIdentity)`.
- [ ] Continue requiring canonical public identity.
- [ ] Preserve existing plaintext byte wiping in `finally`.
- [ ] Redact any resulting message before it reaches UI state.
- [ ] Add test `privateIdentityImportFailsWhenCanonicalPrivateMissing`.
- [ ] Add test `privateIdentityImportFailsWhenCanonicalPublicMissing` if it does not already exist.

Suggested change:

```kotlin
canonicalBytes =
    requireNotNull(validated.canonicalPrivateIdentity) {
        "Identity validation returned no canonical private identity"
    }.toByteArray()
val canonicalPublic =
    requireNotNull(validated.canonicalPublicIdentity) {
        "Identity validation returned no canonical public identity"
    }
deps.identityRepository.storeEncryptedIdentity(canonicalBytes, canonicalPublic)
```

---

# P0-005 — Fix setup draft-truth user messages

**Severity:** Medium  
**Problem:** Setup forward edits are draft-only, but UI messages still say `Forward saved` and `Forward deleted`, which implies authoritative persistence before Review commit.

**Files:**

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupForwardsController.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/SetupViewModelTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/SetupDraftOperationCoordinationTest.kt
```

## P0-005-A — Rename draft-only messages

- [ ] Replace `Forward saved` with `Forward draft updated`.
- [ ] Replace `Forward deleted` with `Forward draft removed`.
- [ ] Ensure identity import/generate messages do not imply authoritative persistence unless they explicitly say draft/imported/generated for setup.
- [ ] Update tests to assert draft-truth language.
- [ ] Add a static test banning `Forward saved` / `Forward deleted` from `SetupForwardsController`.

---

# P0-006 — Add real Android broker-secret permission verification

**Severity:** Medium  
**Problem:** Broker secret permission enforcement code is strong, but the prior FIX8 TODO admitted that a real Android bit-level instrumentation check was not run.

**Files:**

```text
android/app/src/androidTest/java/.../BrokerSecretRepositoryInstrumentedTest.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/BrokerSecretRepository.kt
.github/workflows/ci.yml or E2E script if this becomes CI-gated
tests/e2e/android_*.sh if host-side verification is easier
```

## P0-006-A — Instrument permission bits on Android

- [ ] Add an Android instrumentation test or E2E host check that persists a managed broker secret on an emulator/device.
- [ ] Use `Os.stat(path).st_mode and 0x1FF` or an equivalent reliable Android-side check.
- [ ] Assert the file mode is exactly `0600` after persist.
- [ ] Assert the file mode is exactly `0600` after restore from snapshot.
- [ ] Assert permission-enforcement failure returns `broker_secret_permissions_failed` and does not silently proceed.
- [ ] Ensure the test does not print the broker secret path or contents into logs beyond approved redacted diagnostics.

## P0-006-B — Decide CI gating

- [ ] If stable under GitHub Actions emulator, add it to CI.
- [ ] If emulator/device-specific, document it as manual release evidence and add the exact command to the signoff checklist.
- [ ] Do not mark this complete without either CI evidence or a recorded manual command/output against a real Android runtime.

---

# P0-007 — Update signoff/documentation truth after code fixes

**Severity:** Medium  
**Problem:** The old FIX8 TODO records an immutable signoff SHA that is no longer current. FIX9 must not repeat that evidence drift.

**Files:**

```text
docs/WEBRTC_TUNNEL_STALE_SETUP_RESULT_CONTRACT_FIX9_TODO.md
docs/review-source/WEBRTC_TUNNEL_FIX9_IMPLEMENTATION_REPORT.md
.github/workflows/ci.yml
```

## P0-007-A — Create a FIX9 implementation report during implementation

- [ ] Create `docs/review-source/WEBRTC_TUNNEL_FIX9_IMPLEMENTATION_REPORT.md`.
- [ ] Record each task, commit SHA, exact test command, and result.
- [ ] Record any deliberate deviation with a concrete production-path reason and test evidence.
- [ ] Do not reference unavailable assistant-created files.
- [ ] Keep the report synchronized with actual commits.

## P0-007-B — Final signoff must point to one exact SHA

- [ ] Record `git rev-parse HEAD` after all code/test/doc changes.
- [ ] Confirm `git status --short` is empty.
- [ ] Push the exact SHA.
- [ ] Trigger full signoff using `[full-signoff]` or a tag.
- [ ] Record final GitHub Actions run URL.
- [ ] Confirm `ci/full-matrix`, `ci/release-candidate`, and `ci/rc-diagnostics` all pass for the exact same SHA.
- [ ] If any production code changes after Android/Docker/manual evidence, re-run affected evidence or clearly classify the later change as docs-only.

---

## P1 — Test and enforcement hardening

# P1-001 — Add production-path stale-operation regression tests

**Goal:** prevent helper-only tests from falsely proving production behavior.

- [ ] Every new stale-operation test must call the real public method (`viewModel.identity.importIdentityFromPath()`, `viewModel.forwardsEditor.upsertForward(...)`, `viewModel.save.startTunnelFromReview(...)`, etc.).
- [ ] Use deterministic barriers/fakes to pause at the actual long-running boundary.
- [ ] Call `viewModel.cancel()` while the operation is paused.
- [ ] Release the barrier.
- [ ] Assert old state/data cannot publish after cancel.
- [ ] Assert no authoritative file changed unless the final-save policy explicitly allows and reports it.
- [ ] Assert `isBusy` returns to false after cancellation/rollback.
- [ ] Assert no stale success snackbar/message remains.

Suggested fake pattern:

```kotlin
class BlockingIdentityValidation(
    private val entered: CompletableDeferred<Unit>,
    private val release: CompletableDeferred<Unit>,
    private val result: IdentityValidationResult,
) : TunnelValidationBridge by defaultFakeValidation {
    override fun generateIdentity(peerId: String): IdentityValidationResult {
        entered.complete(Unit)
        runBlocking { release.await() }
        return result
    }
}
```

Prefer suspend-friendly fakes when possible; avoid sleeping/polling to create race windows.

# P1-002 — Add source-level enforcement for setup token use

- [ ] Add a source audit test that fails if `SetupIdentityController`, `SetupForwardsController`, or `SetupSaveController` call `runGuarded` and ignore the token.
- [ ] Ban `runGuarded(... ) { block() }` in setup controllers.
- [ ] Ban `runGuarded(... ) { ... access.applyState(...) ... }` without an adjacent token freshness guard unless explicitly allowlisted.
- [ ] Ban `identityDraft.replace`, `access.setForwards`, `commitSetup`, and `ContextCompat.startForegroundService` inside a setup guarded block unless guarded by token freshness.
- [ ] Keep the rule precise enough that comments cannot satisfy it.

# P1-003 — Expand `Result` contract source audit

- [ ] Find every production function/property annotated `@CheckResult` and returning `Result`.
- [ ] Find every production function whose declared return type is `Result<...>` even without `@CheckResult`.
- [ ] Require cancellation-first handling for suspend/coroutine-relevant APIs.
- [ ] Require ordinary `Exception` handling unless an allowlisted API documents and tests a narrower catch.
- [ ] Add negative fixtures for:
  - selected-subclass-only catch in a `Result` API;
  - bare `getOrThrow()` before a `try/catch` inside a `Result` API;
  - `.also { }` fake consumption where behavior depends on the result.

# P1-004 — Re-run existing FIX8 static inventories

- [ ] Production contains no `runCatching {`.
- [ ] Production contains no unchecked `File.delete()` / `mkdirs()` / `setReadable()` / `setWritable()` in authoritative paths.
- [ ] Production contains no `snapshot.bytes ?: ByteArray(0)` or equivalent fallback.
- [ ] Setup controllers contain no `storeEncryptedIdentity`, `upsertWithReceipt`, or `deleteWithReceipt` calls.
- [ ] Production contains no `catch (Throwable)` unless the existing explicit `CancellationException`/`Error`/`Exception` cleanup composition pattern is intentionally retained.
- [ ] Rust production contains no diagnostic fallback with `unix_ms: 0` or `"unix_ms":0`.

---

## P2 — Validation checklist

# P2-001 — Focused Android validation

Run focused tests before full suite. Add or adjust class names once implementation lands.

```bash
cd android
./gradlew --no-daemon testDebugUnitTest --rerun-tasks \
  --tests '*SetupDraftOperationCoordinationTest' \
  --tests '*SetupViewModelTest' \
  --tests '*SetupIdentityControllerTest' \
  --tests '*SetupForwardsControllerTest' \
  --tests '*SetupSaveControllerTest' \
  --tests '*ConfigRepositoryTest' \
  --tests '*SettingsViewModelTest' \
  --tests '*NetworkPolicyViewModelTest' \
  --tests '*IdentityRepositoryTest' \
  --tests '*ImportExportServiceTest' \
  --tests '*BrokerSecretRepositoryTest' \
  --tests '*ProductionRunCatchingAuditTest' \
  --tests '*AuthoritativeFilesystemBooleanAuditTest' \
  --tests '*SnapshotAndCandidateBlockEnforcementTest' \
  --tests '*CheckResultEnforcementFixtureTest'
```

- [ ] Focused tests PASS.
- [ ] Every new stale-operation test fails against the old implementation and passes after the fix.
- [ ] Every new `Result` contract test fails against the old implementation and passes after the fix.
- [ ] No test relies on arbitrary sleep to prove absence/order/exactly-once behavior.

# P2-002 — Full Android validation

```bash
cd android
./gradlew --no-daemon ktlintCheck
./gradlew --no-daemon detekt
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest --rerun-tasks
./gradlew --no-daemon testDebugUnitTest --rerun-tasks
./gradlew --no-daemon assembleDebug
./gradlew --no-daemon check
```

- [ ] ktlint PASS.
- [ ] detekt PASS.
- [ ] lintDebug PASS.
- [ ] Two consecutive full unit reruns PASS without retry-until-green.
- [ ] assembleDebug PASS.
- [ ] check PASS.

# P2-003 — Rust validation

Even if FIX9 is mostly Android/Kotlin, run the Rust side because CI/signoff covers the full project and the log/timestamp enforcement remains cross-language.

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo clippy --workspace --release --all-features -- -D warnings
cargo test --workspace --all-targets --all-features
cargo test -p p2p-daemon --test real_broker_tunnel --all-features
```

- [ ] fmt PASS.
- [ ] clippy debug/all-targets PASS.
- [ ] clippy release PASS.
- [ ] workspace tests PASS.
- [ ] real broker test executes and passes rather than self-skipping.

# P2-004 — Docker and Android E2E

```bash
cargo build --release -p p2p-offer -p p2p-answer -p p2pctl
tests/e2e/docker/run.sh
tests/e2e/docker/stop_lifecycle.sh
```

- [ ] Docker real TLS broker/data path PASS.
- [ ] Docker stop lifecycle PASS.

For Android:

```bash
# Use the repo's existing Android E2E setup.
tests/e2e/android_tunnel_e2e.sh
```

- [ ] Android APK installs.
- [ ] Setup wizard reaches Review.
- [ ] Final save commits identity/forwards/config consistently.
- [ ] Android offer reaches Listening.
- [ ] Real Android-to-docker answer PING/PONG/data marker PASS.
- [ ] Explicit STOP ends Stopped, not Error.
- [ ] Broker secret permission instrumentation evidence is recorded if not CI-gated.

# P2-005 — CI release-candidate signoff

- [ ] Push a final commit whose message includes `[full-signoff]`, or create a tag.
- [ ] Confirm CI path detection reports Rust and Android required.
- [ ] Confirm `ci/rc-diagnostics` success.
- [ ] Confirm `ci/full-matrix` success.
- [ ] Confirm `ci/release-candidate` success.
- [ ] Record exact SHA and run URLs in `docs/review-source/WEBRTC_TUNNEL_FIX9_IMPLEMENTATION_REPORT.md`.

---

## Completion criteria

FIX9 is complete only when all of the following are true:

- [ ] A setup operation stale after `cancel()` cannot publish old state.
- [ ] A setup operation stale after `cancel()` cannot silently commit authoritative config.
- [ ] Final save cancellation has one explicit, tested behavior: rollback, durable rollback-incomplete report, or visibly blocked cancel while commit is at a point of no return.
- [ ] Every `Result` API audited in P0-002 returns ordinary failures as `Result.failure` and rethrows cancellation.
- [ ] Public identity reads are coherent with identity-pair writes.
- [ ] Private identity import never falls back to source text when canonical private material is missing.
- [ ] Setup forward edit messages are draft-truthful.
- [ ] Broker secret owner-only permissions are proven on real Android or documented with exact manual release evidence.
- [ ] Static enforcement prevents reintroducing the fixed unsafe patterns.
- [ ] Full local and CI signoff belong to one exact SHA.
