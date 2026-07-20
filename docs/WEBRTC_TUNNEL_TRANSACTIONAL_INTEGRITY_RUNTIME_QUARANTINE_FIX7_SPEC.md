# WebRTC Tunnel Transactional Integrity, Runtime Quarantine, and Failure Truthfulness FIX7 Specification

**Status:** Implementation specification  
**Date:** 2026-07-20  
**Baseline reviewed:** `webrtc_tunnel-master_2607201054.zip`  
**Primary review source:** `docs/review-source/WEBRTC_TUNNEL_FIX6_CODE_REVIEW_2026-07-20.md`  
**Implementation checklist:** `docs/WEBRTC_TUNNEL_TRANSACTIONAL_INTEGRITY_RUNTIME_QUARANTINE_FIX7_TODO.md`

---

## 1. Purpose

FIX6 materially improved atomic config writes, direct network diagnostics, lifecycle command ordering, forward revision receipts, redaction, and typed rollback models. It did not finish the state-integrity work. Several remaining paths can still:

- mutate live identity or secret files before validation succeeds;
- leave earlier transaction stages committed when cancellation occurs;
- leave the encrypted and public identity files mismatched;
- continue starting or resuming after a failed native stop-like operation;
- lose cleanup or rollback failures;
- silently reject a user operation;
- apply fail-closed handling only if an error reporter itself succeeds;
- preserve stale session truth after malformed native status;
- panic or invent timestamp zero when the wall clock is invalid;
- claim checklist coverage through tests that do not execute the named behavior.

FIX7 closes those gaps without redesigning the WebRTC protocol, MQTT signaling format, Android UI flow, or product feature set.

The primary goal is **truthful durable state under failure, cancellation, concurrency, and process lifecycle races**. A failed or cancelled operation must either leave the exact previous durable state intact or expose a durable, specific “rollback incomplete / runtime uncertain” condition that blocks unsafe continuation.

---

## 2. Source-of-truth and precedence

When documents disagree, use this order:

1. This FIX7 specification.
2. `WEBRTC_TUNNEL_TRANSACTIONAL_INTEGRITY_RUNTIME_QUARANTINE_FIX7_TODO.md`.
3. `review-source/WEBRTC_TUNNEL_FIX6_CODE_REVIEW_2026-07-20.md`.
4. Current production code and exact negative-path tests.
5. FIX6 and earlier specifications/TODOs as historical context only.

Every binding decision from earlier work that remains relevant is restated here. Claude Code must not infer a FIX7 requirement solely from an older checklist.

The review report referenced above is included in the handoff bundle at the exact repository path. No assistant-created file referenced by this specification is intentionally omitted.

---

## 3. Release decision

The baseline is **not release-ready**. FIX7 is a release-blocking hardening pass.

The application is not release-ready until all P0 requirements in the companion TODO are complete and all exact tests pass. P1 and P2 requirements are also required for FIX7 completion; they may not be deferred merely because a P0 smoke path appears to work.

---

## 4. Scope

### 4.1 In scope

#### Android persistence and validation

- Setup validation workspace isolation.
- One authoritative setup commit transaction.
- Exact snapshots and exact restoration, including file absence.
- Broker password file persistence as an explicit transaction stage.
- Cancellation-safe rollback for setup and reset.
- Cancellation-safe identity-pair commit and rollback.
- Cross-feature serialization for setup, import, forward activation, and reset.
- Candidate-file cleanup result composition.
- Imported private-key byte wiping.
- Required operation failures in durable UI state.

#### Android lifecycle and network truthfulness

- Native runtime quarantine for every failed stop-like operation.
- Cancellation semantics for unverified-start cleanup.
- Known offer-stop-while-Listening discrepancy.
- Fail-closed network handling that cannot be defeated by reporter failure.
- Safe network classification during construction and refresh.
- Stale-status field clearing on malformed native status.
- Unexpected lifecycle processor death visibility.
- Main-thread startup I/O removal.

#### Rust time and shutdown behavior

- Repository-wide fallible wall-clock policy.
- No pre-epoch panic.
- No first-failure timestamp zero.
- Protocol paths propagate typed clock failure.
- Optional diagnostics represent unavailable time explicitly.
- Cooperative offer shutdown while waiting for a peer returns success.

#### Enforcement and proof

- Exact negative-path tests, not helper substitutes.
- No `Thread.sleep` absence/overlap proofs.
- Type-aware ignored-result enforcement for authoritative mutation and cleanup APIs.
- Complete final signoff from one immutable commit.

### 4.2 Out of scope

- Automatic trading or unrelated application features.
- Protocol redesign or wire-format changes except a typed internal clock error where required.
- Replacing MQTT or WebRTC libraries.
- Rewriting the Android UI architecture.
- Multi-process file locking. The current app is single-process; JVM/process-local synchronization is acceptable, but this assumption must be documented.
- General refactoring unrelated to a named FIX7 requirement.

---

## 5. Non-negotiable invariants

### FIX7-INV-001 — Validation must not mutate authoritative state

Setup and import validation may create temporary files in an isolated cache workspace. It must not write:

- `identity.enc`;
- `identity.pub`;
- `authorized_keys`;
- active `config.toml`;
- `setup_input.json`;
- preferences;
- `runtime/mqtt_password.txt`;
- forwards storage.

Validation failure and validation cancellation must leave authoritative storage byte-for-byte and presence-for-presence unchanged.

### FIX7-INV-002 — One setup transaction owns all setup mutations

A successful setup save may mutate identity, authorized keys, broker secret, setup input, preferences, and active config. All these stages must be owned by one coordinator, serialized by one operation admission, snapshotted before mutation, and rolled back in reverse order on failure.

There must be no outer ad-hoc snapshot/restore in `SetupSaveController` around a smaller inner transaction.

### FIX7-INV-003 — Cancellation remains cancellation and still triggers recovery

`CancellationException` must propagate to the caller. Before propagation, any already-committed durable stages must be restored under deliberate `NonCancellable` recovery. Rollback failures must be preserved and reported; cancellation must never silently abandon partial state.

### FIX7-INV-004 — Exact prior state includes absence

For every snapshotted file, the model must distinguish:

- absent;
- present and empty;
- present and non-empty;
- present but unreadable, which is a snapshot failure and must abort before mutation.

Rollback must restore the exact previous presence and bytes. Parsed model values are not sufficient for exact file restoration.

### FIX7-INV-005 — Identity pair is one logical commit

`identity.enc` and `identity.pub` must never remain mismatched after a reported failure or cancellation. Once either file has been replaced, both prior snapshots must be independently restored on any subsequent failure. One restore failure must not prevent attempting the other.

### FIX7-INV-006 — Config rendering is pure

`renderOfferConfig` and equivalent render methods must perform no filesystem writes. They may only transform inputs into a string.

Broker-password file creation/replacement is an explicit persistence stage. It must use unique same-directory temporary files, replacement semantics, exact snapshot/rollback, restrictive permissions, and a consumed result.

### FIX7-INV-007 — Stop uncertainty blocks all starts and resumes

Any failure of an operation whose purpose is to stop the native runtime—including explicit stop, manual pause, policy pause, start-verification cleanup, and destroy fallback when observed—must set a common runtime-uncertain quarantine.

While quarantined:

- Start and Resume must not call native start APIs.
- Policy retry must be invalidated.
- A durable visible error must explain that a verified stop is required.
- Only a subsequently verified explicit STOP may clear quarantine.

### FIX7-INV-008 — Required safety action is independent of diagnostics delivery

A reporter, logger, snackbar, or diagnostics repository may itself fail. Such failure must not prevent:

- applying fail-closed network state;
- pausing/blocking the tunnel;
- setting runtime quarantine;
- closing command acceptance;
- rolling back durable state.

Safety state is primary. Reporting is secondary and guarded.

### FIX7-INV-009 — One application-level mutation admission governs config-related operations

Setup save, config import, forward mutation+activation, and configuration reset must not overlap. Per-screen mutexes are insufficient because different ViewModels can run concurrently.

A single application-scoped `ConfigurationMutationCoordinator` must either:

- serialize operations and execute the later operation using fresh state; or
- reject the later operation with a durable visible busy failure identifying the active operation.

The implementation must choose one policy consistently. FIX7 specifies visible reject-on-overlap for minimal behavior change.

### FIX7-INV-010 — Cleanup results are authoritative results

Candidate and temp cleanup failure must never be ignored. Composition rules:

- primary failure + cleanup failure: preserve the primary exception and attach cleanup as suppressed;
- cancellation + cleanup failure: preserve cancellation and attach cleanup as suppressed;
- primary success + cleanup failure: the overall operation fails with a fixed safe cleanup message;
- cleanup failure for a secret-bearing file must also emit a required redacted diagnostic.

### FIX7-INV-011 — Status error branches clear stale live truth

If native status cannot be decoded, reports an unknown mode, or otherwise cannot establish current live-session truth, the Android status model must clear or mark unknown all fields that would falsely imply a current connection:

- `remotePeerId`;
- active session count;
- MQTT connected;
- current forwards/session measurements as applicable.

Do not preserve stale live fields from a previous successful sample.

### FIX7-INV-012 — Wall-clock failure is explicit

No production Rust path may:

- panic because the clock is before the Unix epoch;
- call `.unwrap_or(0)` for a timestamp;
- return `0` from an uninitialized “last known” fallback;
- silently treat unavailable protocol time as valid.

Correctness-sensitive protocol paths return a typed error. Optional diagnostic events use `Option<u64>` or omit the event timestamp while preserving the primary event.

### FIX7-INV-013 — Required failures are durable

A required operation failure must live in screen/service state or another durable observable source. Snackbar and logs may mirror it but may not be the only copy.

### FIX7-INV-014 — Tests must execute the named production path

A helper unit test is not proof of caller integration. A differently named test is not automatically equivalent. Every required test must:

- drive the production entry point or a deliberately extracted production seam;
- force the stated failure at the stated stage;
- assert prior and final durable state;
- assert no false success;
- assert cancellation/reporting semantics where applicable.

---

## 6. Required architecture

## 6.1 Application-scoped configuration mutation coordinator

Add an application-scoped body property on `AppDependencies`; do not add a seventh constructor parameter.

Target API:

```kotlin
enum class ConfigurationOperation {
    SetupSave,
    ConfigImport,
    ForwardMutation,
    ConfigurationReset,
}

sealed interface ConfigurationAdmission<out T> {
    data class Completed<T>(val value: T) : ConfigurationAdmission<T>
    data class Busy(val active: ConfigurationOperation) : ConfigurationAdmission<Nothing>
}

class ConfigurationMutationCoordinator {
    private val mutex = Mutex()
    private val active = AtomicReference<ConfigurationOperation?>(null)

    suspend fun <T> tryRun(
        operation: ConfigurationOperation,
        block: suspend () -> T,
    ): ConfigurationAdmission<T> {
        if (!mutex.tryLock()) {
            return ConfigurationAdmission.Busy(
                active.get() ?: operation,
            )
        }

        active.set(operation)
        return try {
            ConfigurationAdmission.Completed(block())
        } finally {
            active.set(null)
            mutex.unlock()
        }
    }
}
```

`AppDependencies` target:

```kotlin
val configurationMutationCoordinator: ConfigurationMutationCoordinator by lazy {
    ConfigurationMutationCoordinator()
}
```

All four mutating feature paths must use this coordinator. Existing local mutexes may be removed or retained only for non-overlapping local actions, but they must not be treated as the authoritative cross-feature guard.

Busy mapping must be durable and specific, for example:

```text
configuration_operation_busy
Another configuration operation is already in progress: ConfigImport
```

The active operation name is not sensitive.

## 6.2 Shared exact file snapshot and restore primitives

Create a data/storage utility used by config secret, setup input, and identity storage.

```kotlin
class ExactFileSnapshot internal constructor(
    val existed: Boolean,
    val bytes: ByteArray?,
)

data class FileRestoreFailure(
    val logicalName: String,
    val reason: String,
)

internal fun captureExactFileSnapshot(file: File): ExactFileSnapshot =
    if (file.exists()) {
        ExactFileSnapshot(
            existed = true,
            bytes = file.readBytes(),
        )
    } else {
        ExactFileSnapshot(
            existed = false,
            bytes = null,
        )
    }
```

Restoration must use atomic replacement for present snapshots and checked deletion for absent snapshots. It must not silently ignore `File.delete()` returning false.

```kotlin
internal fun restoreExactFileSnapshot(
    logicalName: String,
    file: File,
    snapshot: ExactFileSnapshot,
    atomicReplace: (File, ByteArray) -> Unit,
): Result<Unit> =
    try {
        if (snapshot.existed) {
            atomicReplace(
                file,
                requireNotNull(snapshot.bytes) {
                    "$logicalName snapshot bytes are missing"
                },
            )
        } else {
            Files.deleteIfExists(file.toPath())
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
```

Snapshot byte arrays containing encrypted or secret material must be wiped when the transaction no longer needs them. Public identity and config snapshots are not plaintext private keys, but broker password snapshots are secret and must be wiped.

## 6.3 Isolated setup validation workspace

The current native validator requires referenced identity and `authorized_keys` files. FIX7 must satisfy that requirement without writing live repository files.

Create a unique cache directory per setup validation:

```text
cache/setup-validation-<random>/
  identity.enc or validation-specific private identity input
  identity.pub
  authorized_keys
  mqtt_password.txt, only if validator requires the file to exist
  candidate.toml
```

Prefer validator APIs that accept plaintext identity bytes directly, as the current config validation already has an identity-aware form. If the TOML references file paths, render the candidate with paths inside the workspace.

Target ownership type:

```kotlin
class SetupValidationWorkspace private constructor(
    val root: File,
    val identityPublicFile: File,
    val authorizedKeysFile: File,
    val brokerPasswordFile: File?,
    val candidateFile: File,
) : AutoCloseable {
    override fun close() {
        deleteDirectoryRecursivelyOrThrow(root)
    }

    companion object {
        fun create(
            cacheDir: File,
            includeBrokerPassword: Boolean,
        ): SetupValidationWorkspace {
            val root = Files.createTempDirectory(
                cacheDir.toPath(),
                "setup-validation-",
            ).toFile()
            return SetupValidationWorkspace(
                root = root,
                identityPublicFile = File(root, "identity.pub"),
                authorizedKeysFile = File(root, "authorized_keys"),
                brokerPasswordFile =
                    if (includeBrokerPassword) {
                        File(root, "mqtt_password.txt")
                    } else {
                        null
                    },
                candidateFile = File(root, "candidate.toml"),
            )
        }
    }
}
```

The production implementation must use a helper that composes workspace cleanup with primary failure/cancellation according to FIX7-INV-010. A simple `close()` whose failure is ignored is not acceptable.

Validation inputs:

- imported/private identity bytes resolved in memory;
- canonical public identity;
- current authorized keys plus the proposed new key, written only into the workspace;
- proposed broker password, written only into the workspace when necessary;
- candidate TOML rendered with workspace paths.

After validation succeeds, the workspace is deleted and the authoritative setup transaction begins. The transaction must not depend on temporary files still existing.

## 6.4 Pure config rendering and broker secret storage

Refactor `ConfigRepository.renderOfferConfig` so it does not call `resolveBrokerPasswordFile` and does no I/O.

Target signature:

```kotlin
fun renderOfferConfig(
    input: SetupConfigInput,
    forwards: List<ForwardConfig>,
    debugLogsEnabled: Boolean,
    androidIceMode: AndroidIceMode,
    brokerPasswordPath: String?,
): String
```

The caller decides the path. A dedicated repository owns the authoritative password file:

```kotlin
class BrokerSecretRepository(
    context: Context,
    private val atomicReplace: (File, ByteArray) -> Unit = ::atomicReplaceSecret,
) {
    private val lock = Any()
    private val passwordFile = File(context.filesDir, "runtime/mqtt_password.txt")

    fun captureSnapshot(): ExactFileSnapshot =
        synchronized(lock) {
            captureExactFileSnapshot(passwordFile)
        }

    @CheckResult
    fun persist(password: String?): Result<Unit> =
        synchronized(lock) {
            try {
                if (password.isNullOrEmpty()) {
                    Files.deleteIfExists(passwordFile.toPath())
                } else {
                    atomicReplace(passwordFile, password.encodeToByteArray())
                    restrictOwnerOnly(passwordFile)
                }
                Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

    @CheckResult
    fun restore(snapshot: ExactFileSnapshot): Result<Unit> =
        synchronized(lock) {
            restoreExactFileSnapshot(
                logicalName = "broker password",
                file = passwordFile,
                snapshot = snapshot,
                atomicReplace = atomicReplace,
            )
        }

    fun configPathOrNull(password: String?): String? =
        password?.takeIf(String::isNotEmpty)?.let { passwordFile.absolutePath }
}
```

Use Android-private storage and owner-only permissions. Do not log the password, bytes, or raw exception object.

The setup transaction adds `BrokerSecret` before `Config`, with Config last. Forward regeneration should not rewrite the secret file merely to render config; it uses the current persisted secret path. If forward activation needs to change the secret, that change must be an explicit transaction stage—not a render side effect.

## 6.5 Setup persistence transaction

Replace the two-layer controller rollback + coordinator transaction with one transaction request containing every proposed mutation:

```kotlin
data class SetupPersistenceRequest(
    val configContents: String,
    val setupInput: SetupConfigInput,
    val preferences: AndroidAppPreferences,
    val replacementIdentity: ReplacementIdentity?,
    val authorizedPublicIdentityToAdd: String?,
    val brokerPassword: String?,
)
```

Required stage order:

```text
Identity, when replacement requested
AuthorizedKeys, when new key requested
BrokerSecret, when password state changes or must be established
SetupInput
Preferences
Config LAST
```

The snapshot is captured once before mutation and includes exact state for every potentially changed resource.

Typed stage enum:

```kotlin
enum class SetupPersistenceStage {
    Snapshot,
    Identity,
    AuthorizedKeys,
    BrokerSecret,
    SetupInput,
    Preferences,
    Config,
}
```

Cancellation target shape:

```kotlin
suspend fun persist(
    request: SetupPersistenceRequest,
): SetupPersistenceResult = mutex.withLock {
    val snapshot = captureSnapshot().getOrElse { error ->
        return@withLock SetupPersistenceResult.Failed(
            failedStage = SetupPersistenceStage.Snapshot,
            reason = safeReason(error, "Failed to capture setup snapshot"),
            rollback = emptyList(),
        )
    }

    val committed = mutableListOf<SetupPersistenceStage>()
    try {
        for (stage in requestedStages(request)) {
            val result = applyStage(stage, request)
            if (result.isFailure) {
                return@withLock failureWithRollback(
                    failedStage = stage,
                    failure = result.exceptionOrNull(),
                    snapshot = snapshot,
                    committed = committed,
                )
            }
            committed += stage
        }
        SetupPersistenceResult.Success(committed.toList())
    } catch (cancelled: CancellationException) {
        val rollback = withContext(NonCancellable) {
            rollback(snapshot, committed)
        }
        attachRollbackFailures(cancelled, rollback)
        reportRollbackIncompleteIfNeeded(
            code = "setup_cancelled_rollback_incomplete",
            rollback = rollback,
        )
        throw cancelled
    } finally {
        snapshot.wipeSecretBytes()
    }
}
```

`failureWithRollback` must run rollback under `NonCancellable` too. A parent cancellation arriving during ordinary failure recovery must not abort rollback halfway through.

`reportRollbackIncompleteIfNeeded` must be a direct required reporter supplied to the coordinator or caller. It receives fixed/redacted text only. Reporter failure must be caught after rollback is complete.

## 6.6 Transactional reset cancellation and exact setup-input state

Reset snapshot must capture the exact setup-input file, not only parsed `SetupConfigInput`:

```kotlin
data class ResetSnapshot(
    val config: ExactFileSnapshot,
    val setupInput: ExactFileSnapshot,
    val forwards: List<ForwardConfig>,
)
```

If parsing is needed for UI, it is separate from rollback state.

The reset stage loop uses the same cancellation recovery rule as setup:

```kotlin
try {
    // stage loop
} catch (cancelled: CancellationException) {
    val rollback = withContext(NonCancellable) {
        rollbackFromSnapshot(snapshot, mutatedStages)
    }
    attachRollbackFailures(cancelled, rollback)
    reportRollbackIncompleteIfNeeded(
        code = "reset_cancelled_rollback_incomplete",
        rollback = rollback,
    )
    throw cancelled
}
```

Rollback must continue after each non-cancellation failure. Since rollback itself is inside `NonCancellable`, restore functions must not use cancellation as a control-flow escape. They should return typed failures.

## 6.7 Identity pair and storage restoration

Identity restoration needs a detailed result, not `Unit`:

```kotlin
enum class IdentityStorageFile {
    EncryptedIdentity,
    PublicIdentity,
    AuthorizedKeys,
}

sealed interface IdentityRestoreResult {
    data class Success(val file: IdentityStorageFile) : IdentityRestoreResult
    data class Failure(
        val file: IdentityStorageFile,
        val reason: String,
    ) : IdentityRestoreResult
}
```

`restoreStorageSnapshot` must attempt all three files and return all results.

Identity pair commit target semantics:

1. Encrypt plaintext before changing files.
2. Snapshot both pair files under `storageLock`.
3. Atomically replace encrypted file.
4. Atomically replace public file.
5. On any failure or cancellation after step 3, restore both prior snapshots independently.
6. Preserve the forward failure/cancellation and every rollback failure.
7. Wipe encrypted temporary byte buffers where practical; always wipe plaintext caller-owned bytes at the owner boundary.

Because the repository API is synchronous, rollback does not need `withContext(NonCancellable)` inside the repository. It does need explicit `catch (CancellationException)` followed by synchronous rollback before rethrow.

Target skeleton:

```kotlin
fun storeEncryptedIdentity(
    privateIdentity: ByteArray,
    publicIdentity: String,
) = synchronized(storageLock) {
    val encrypted = crypto.encrypt(privateIdentity)
    val priorEncrypted = snapshotOfFile(identityFile)
    val priorPublic = snapshotOfFile(publicFile)
    var encryptedReplaced = false

    try {
        atomicReplace(identityFile, encrypted)
        encryptedReplaced = true
        atomicReplace(publicFile, publicIdentity.encodeToByteArray())
    } catch (cancelled: CancellationException) {
        if (encryptedReplaced) {
            restoreIdentityPairAndAttach(
                primary = cancelled,
                priorEncrypted = priorEncrypted,
                priorPublic = priorPublic,
            )
        }
        throw cancelled
    } catch (error: Exception) {
        if (!encryptedReplaced) throw error
        val failures = restoreIdentityPair(priorEncrypted, priorPublic)
        if (failures.isNotEmpty()) {
            failures.forEach(error::addSuppressed)
            throw IdentityRollbackIncompleteException(
                "Failed to store identity pair; rollback incomplete",
                error,
            )
        }
        throw IdentityPersistenceException(
            "Failed to store identity pair; prior pair restored",
            error,
        )
    } finally {
        encrypted.fill(0)
    }
}
```

Do not use one `runCatching` around both restores.

## 6.8 Candidate and workspace cleanup composition

Create one reusable helper to prevent caller-level ignored cleanup:

```kotlin
internal suspend fun <T> withCandidateFile(
    cacheDir: File,
    prefix: String,
    block: suspend (File) -> T,
): T {
    val candidate = createCandidateFile(cacheDir, prefix)
    var primary: Throwable? = null
    try {
        return block(candidate)
    } catch (cancelled: CancellationException) {
        primary = cancelled
        throw cancelled
    } catch (error: Exception) {
        primary = error
        throw error
    } finally {
        val cleanup = deleteCandidateFileSafely(candidate).exceptionOrNull()
        if (cleanup != null) {
            if (primary != null) {
                primary.addSuppressed(cleanup)
            } else {
                throw CandidateCleanupException(
                    "Failed to remove temporary configuration candidate",
                    cleanup,
                )
            }
        }
    }
}
```

If the helper is used in a cancellation-sensitive context, the deletion operation itself is synchronous. If a future cleanup becomes suspend, run cleanup under `NonCancellable`.

Callers must map `CandidateCleanupException` to a durable fixed-message error and direct redacted diagnostic. Do not expose candidate paths if they could reveal user-controlled names.

Imported private identity bytes:

```kotlin
private fun importPrivateIdentityContent(content: String) {
    var privateBytes: ByteArray? = null
    try {
        val validated = deps.identityValidation.validatePrivateIdentity(content)
        require(validated.valid) {
            validated.message ?: "Invalid private identity"
        }
        privateBytes =
            requireNotNull(validated.canonicalPrivateIdentity) {
                "Missing canonical private identity"
            }.encodeToByteArray()
        deps.identityRepository.storeEncryptedIdentity(
            privateIdentity = privateBytes,
            publicIdentity = requireNotNull(validated.canonicalPublicIdentity),
        )
    } finally {
        privateBytes?.fill(0)
    }
}
```

## 6.9 Runtime quarantine

Centralize stop-like failure handling in `TunnelForegroundService` or a small extracted collaborator if the class function limit requires it.

```kotlin
private fun enterNativeRuntimeQuarantine(
    code: String,
    message: String,
) {
    nativeStopVerified.set(false)
    nativeRuntimeUncertain.set(true)
    invalidatePendingPolicyRetry()
    reporter.publishError(
        code = code,
        message = SensitiveDataRedactor.redactText(message),
    )
}
```

The safety state mutations must occur before `publishError`, because the reporter may throw.

Apply it to:

- explicit stop failure;
- manual pause stop failure;
- policy pause stop failure;
- start-verification cleanup stop failure;
- observed destroy fallback stop failure.

Every start/resume/policy retry path must call one guard before any native call:

```kotlin
private fun requireRuntimeStartAllowed(): Result<Unit> =
    when {
        nativeRuntimeUncertain.get() ->
            Result.failure(
                IllegalStateException(
                    "Native runtime state is uncertain; perform a verified stop before restarting",
                ),
            )
        else -> appInitializationCoordinator.requireReady()
    }
```

Only a verified explicit stop clears quarantine:

```kotlin
private fun markVerifiedStopped() {
    nativeStopVerified.set(true)
    nativeRuntimeUncertain.set(false)
    invalidatePendingPolicyRetry()
}
```

A pause success does not need to clear an existing quarantine because pause must never be attempted as a recovery mechanism once uncertain.

## 6.10 Unverified-start cleanup

`cleanupUnverifiedStart` must make its recovery contract explicit.

Preferred behavior: stopping a possibly started native runtime is mandatory cleanup, so perform stop under `NonCancellable`, then propagate the original cancellation.

```kotlin
internal suspend fun cleanupUnverifiedStart(
    stop: suspend () -> Result<Unit>,
    report: (String, String) -> Unit,
): Boolean {
    val result =
        try {
            withContext(NonCancellable) { stop() }
        } catch (error: Exception) {
            Result.failure(error)
        }

    return result.fold(
        onSuccess = { true },
        onFailure = { error ->
            reportSafely(
                code = "start_verification_cleanup_failed",
                message = SensitiveDataRedactor.redactText(
                    error.message ?: "Failed to stop unverified native runtime",
                ),
            )
            false
        },
    )
}
```

The caller must preserve an incoming cancellation separately. Do not place the whole orchestration inside `runCatching`.

## 6.11 Offer cooperative shutdown

The offer daemon must return `Ok(())` when a shutdown token is requested while it is Listening or waiting for a peer/local client and no primary failure preceded shutdown.

The main loop already has shutdown branches that return `Ok(())`; FIX7 must locate the actual later finalizer or worker-drain path that converts this condition into `Err`. Required rule:

```rust
if shutdown.is_shutdown_requested() && primary_error.is_none() {
    Ok(())
} else if let Some(error) = primary_error {
    Err(error)
} else {
    finalizer_result
}
```

Do not hide real worker, status-write, or cleanup failures that occur before shutdown. If cleanup itself fails during cooperative shutdown, return the cleanup failure and allow Android to quarantine/report it.

The Rust test must run the real `run_offer_daemon_with_status_and_shutdown` seam far enough to publish Listening, request shutdown with no peer, await completion, and assert `Ok(())` plus final stopped state.

The Android/mobile runtime test must assert that this successful completion maps to `AndroidRuntimeState::Stopped`, not Error.

## 6.12 Fail-closed network reporting

Reporter failure must not abort safety handling.

```kotlin
private fun reportNetworkDiagnosticSafely(
    reporter: NetworkPolicyDiagnosticReporter,
    code: String,
    message: String,
) {
    try {
        reporter.report(code, message)
    } catch (error: Exception) {
        Log.e(
            TAG,
            "Network diagnostic reporter failed: " +
                SensitiveDataRedactor.redactText(
                    error.message ?: "unknown reporter failure",
                ),
        )
    }
}
```

Classification failure order:

1. Construct fail-closed Unknown status.
2. Assign `_status`.
3. Attempt channel delivery.
4. Attempt reporter call safely.

Monitor-supervisor failure order:

1. Build fail-closed status.
2. Update repository status.
3. submit `PolicyBlocked` or otherwise stop tunnel.
4. report the monitor failure safely.
5. delay/retry.

If command submission fails because the lifecycle processor is dead, publish a separate durable `lifecycle_processor_unavailable` condition and stop the service. Do not continue retrying as though the tunnel were safely controlled.

`NetworkMonitorBackoff` constructor must require valid bounds:

```kotlin
init {
    require(initialDelayMs > 0)
    require(maxDelayMs >= initialDelayMs)
    require(multiplier >= 1.0)
}
```

Network classification during `NetworkPolicyManager` construction and refresh must catch `Exception`, produce canonical Unknown/blocked status, and report safely after state is set.

## 6.13 Tunnel status truthfulness

In every native status parse/decode/mode-error branch, construct a status that clears live fields rather than copying them from the previous sample.

Target helper:

```kotlin
private fun TunnelStatus.withUnknownRuntimeTruth(
    state: ServiceState,
    error: TunnelError,
): TunnelStatus =
    copy(
        serviceState = state,
        mqttConnected = false,
        activeSessionCount = 0,
        remotePeerId = null,
        activeForwards = emptyList(),
        lastError = error,
    )
```

Use the actual model field names. The principle is mandatory even if `activeForwards` has a different representation.

## 6.14 Lifecycle processor death

`TunnelLifecycleCoordinator` correctly closes command acceptance. The service must additionally observe unexpected processor completion.

Expose a completion callback or `StateFlow`:

```kotlin
sealed interface LifecycleProcessorState {
    data object Running : LifecycleProcessorState
    data object Stopped : LifecycleProcessorState
    data class Failed(val message: String) : LifecycleProcessorState
}
```

On unexpected failure:

- set native runtime uncertain if a runtime may be active;
- invalidate policy retry;
- publish durable `lifecycle_processor_failed`;
- stop accepting starts/resumes;
- stop the foreground service or move to an explicit Error state.

A mere `trySubmit == false` log/drop is not sufficient.

## 6.15 Main-thread startup

`WebRtcTunnelApplication.onCreate()` may create lightweight dependency objects and notification channels. Constructors invoked there must not perform disk reads, network classification, or other potentially blocking work.

Convert eager I/O dependencies to lazy initialization or explicit asynchronous initialization. At minimum inspect:

- `ForwardsConfigStore` / `ForwardsRepository` initial load;
- `NetworkPolicyManager` initial classification;
- config repository preference/setup reads;
- native bridge creation (already lazy and should remain so).

Add StrictMode/test-dispatcher proof that `onCreate()` does not execute the identified blocking operations on the main thread.

## 6.16 Durable NetworkPolicyViewModel failure

Add a state model:

```kotlin
data class NetworkPolicyUiState(
    val lastOperationFailure: OperationFailure? = null,
)
```

On save failure, assign a fixed/redacted durable failure before showing snackbar. On success, clear it.

The `networkStatus` flow must also catch `evaluateWithPolicy` exceptions and emit fail-closed status rather than terminating the UI flow.

## 6.17 `runCatching`, fatal errors, and cancellation audit

Production `runCatching` is allowed only for synchronous, non-cancellation-aware parsing/utility code where catching `Throwable` cannot normalize `Error` in a safety-critical path. Prefer explicit `try/catch (Exception)`.

Forbidden:

- suspend operation inside `runCatching`;
- persistence/rollback inside `runCatching` when the result is ignored;
- lifecycle/native cleanup inside `runCatching`;
- normalization of fatal `Error` into ordinary UI failure.

Every retained production `runCatching` must have a comment or documented inventory reason.

## 6.18 Static enforcement

Expand `@CheckResult` or the existing type-aware lint/detekt enforcement to authoritative mutation and cleanup methods, including:

- setup/config writes;
- setup-input save/restore result APIs after conversion;
- preference writes;
- forwards mutations and rollback;
- identity append/export and any new result-returning identity restore;
- broker-secret persist/restore;
- candidate deletion and workspace cleanup;
- lifecycle command submission where a false result requires handling.

Do not use regex/grep as the sole parser. Add permanent positive and negative fixtures or rule tests. CI must execute the exact task that runs the type-aware rule.

---

## 7. Required user-visible and diagnostic codes

Use stable codes. Messages shown below are safe defaults; exception details may be attached only after redaction.

| Code | Meaning |
|---|---|
| `configuration_operation_busy` | Another setup/import/forward/reset operation owns the global coordinator. |
| `setup_validation_failed` | Candidate failed validation; no durable state changed. |
| `setup_persistence_failed` | Setup failed and rollback completed. |
| `setup_rollback_incomplete` | Setup failed and one or more rollback stages failed. |
| `setup_cancelled_rollback_incomplete` | Cancellation propagated after incomplete rollback; required diagnostic. |
| `reset_failed` | Reset failed and rollback completed. |
| `reset_rollback_incomplete` | Reset rollback incomplete. |
| `reset_cancelled_rollback_incomplete` | Cancellation propagated after incomplete reset rollback. |
| `identity_persistence_failed` | Identity pair write failed and prior pair restored. |
| `identity_rollback_incomplete` | Identity pair write failed and pair restoration incomplete. |
| `candidate_cleanup_failed` | Secret-bearing candidate/workspace could not be removed. |
| `native_runtime_quarantined` | Native runtime stop state is uncertain; restart blocked. |
| `manual_pause_stop_failed` | Manual pause could not verify native stop. |
| `policy_pause_stop_failed` | Policy pause could not verify native stop. |
| `start_verification_cleanup_failed` | Cleanup of an unverified start failed. |
| `network_policy_classification_failed` | Classification failed; fail-closed status applied. |
| `network_policy_monitor_failed` | Monitor lifecycle failed; tunnel blocked and retry scheduled. |
| `network_policy_reporter_failed` | Secondary reporter failure; safety state already applied. |
| `lifecycle_processor_failed` | Command processor died unexpectedly. |
| `native_status_invalid` | Native status could not establish live truth; stale fields cleared. |
| `clock_unavailable` | Optional diagnostic time unavailable. Never use timestamp zero. |

Do not embed passwords, tokens, private identity text, whole TOML, filesystem content, or raw `Throwable` in diagnostics.

---

## 8. Required tests

The companion TODO contains the exhaustive checkbox list. The following categories are mandatory:

1. Setup validation failure and cancellation cause zero live-state mutation.
2. Setup cancellation at every stage restores every prior stage.
3. Setup cancellation rollback failure is reported while cancellation remains cancellation.
4. Reset cancellation at each later stage restores exact config/setup/forwards state.
5. Identity cancellation between pair writes restores both files.
6. Identity rollback attempts both files after the first restore fails.
7. Broker secret is unchanged after validation/config failure and restored after later-stage failure.
8. Render functions perform no filesystem I/O.
9. Setup/import/forwards/reset cross-feature overlaps are visibly rejected.
10. Candidate cleanup composition covers success, failure, and cancellation.
11. Imported private bytes are wiped on success, validation failure, persistence failure, and cancellation.
12. Manual and policy pause stop failures quarantine and block subsequent native starts.
13. Verified explicit stop clears quarantine; failed stop does not.
14. Offer shutdown while Listening with no peer completes successfully.
15. Reporter failure cannot prevent fail-closed status, pause, retry, or cleanup.
16. Invalid native status clears stale peer/session/MQTT fields.
17. Unexpected lifecycle processor death becomes durable visible failure.
18. `Application.onCreate()` does not perform identified disk/network work on main.
19. First-use wall-clock failure never returns zero and never panics.
20. Signaling codec surfaces typed clock failure.
21. No proof tests use elapsed-time sleeping.
22. Ignored authoritative results fail the static rule.

Tests must use barriers, injected fakes, virtual time, or deterministic call-count seams. Filesystem permission tricks and real-time sleeps are not acceptable where an injectable file-ops seam is practical.

---

## 9. Build and signoff requirements

### Android focused tests

The TODO will list exact classes after implementation. At minimum execute all touched coordinator, repository, ViewModel, service, network, and startup tests with `--rerun-tasks`.

### Android full validation

```bash
cd android
./gradlew --no-daemon ktlintCheck
./gradlew --no-daemon detekt
./gradlew --no-daemon lintDebug
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon assembleDebug
./gradlew --no-daemon check
```

CI must run `check` or the exact equivalent including type-resolved detekt and Android lint.

### Rust validation

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-features
```

### E2E

Required before signoff:

- Docker real-broker tunnel.
- Docker stop lifecycle.
- Android emulator setup-to-Listening smoke.
- Android emulator/user stop while Listening with no peer, proving final Stopped.
- At least one scripted metered/unmetered monitor transition or an explicit device limitation plus exact service-level integration test; unit-only coverage is no longer sufficient for final signoff if the emulator can expose the transition.
- Process-kill/destroy recovery test or a documented platform limitation plus exact observable-state integration proof.

### Evidence

Record:

- one final `git rev-parse HEAD`;
- clean `git status --short`;
- exact commands and full pass/fail summaries;
- final GitHub Actions run ID and completed status, not “in progress”;
- artifact/report paths;
- emulator/device identifiers and Android API level;
- any NOT RUN item with a precise reason and compensating evidence.

No checkbox may be marked complete based only on a commit message or a test with a misleading name.

---

## 10. Completion definition

FIX7 is complete only when:

- setup validation has no live side effects;
- setup and reset rollback on failure and cancellation;
- rollback failures are durable and visible;
- identity pair mismatch cannot survive failure/cancellation silently;
- broker password persistence is transactional and render is pure;
- all cross-feature config mutations share one admission coordinator;
- cleanup results are consumed and composed;
- all stop-like failures quarantine the runtime;
- offer cooperative stop finishes as Stopped;
- network fail-closed action survives reporter failure;
- stale native status fields are cleared;
- lifecycle processor death is visible and blocks unsafe continuation;
- application startup avoids identified main-thread I/O;
- Rust time never panics or invents zero;
- exact tests and static enforcement prove the implementation;
- final CI/E2E evidence is complete against one immutable commit.
