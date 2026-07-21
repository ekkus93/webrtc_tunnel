# WebRTC Tunnel Authoritative State, Atomic Commit, Durable Quarantine, and Failure Truthfulness FIX8 Specification

**Status:** Binding implementation specification  
**Target project:** `webrtc_tunnel`  
**Reviewed baseline:** `webrtc_tunnel-master_2607211131.zip`  
**Primary defect source:** `docs/review-source/WEBRTC_TUNNEL_FIX7_CODE_REVIEW_2026-07-21.md`  
**Companion execution document:** `docs/WEBRTC_TUNNEL_AUTHORITATIVE_STATE_ATOMIC_COMMIT_DURABLE_QUARANTINE_FIX8_TODO.md`

All files named above are included in the FIX8 handoff package at the exact repository paths shown. This specification supersedes FIX7 completion claims wherever the reviewed source contradicts those claims.

---

# 1. Purpose

FIX8 closes the remaining integrity gaps left after FIX7. The primary objective is not to add more error messages. It is to guarantee that every user-visible outcome agrees with authoritative disk and runtime state.

The reviewed code still permits these invalid outcomes:

1. A user abandons setup, but a generated/imported identity or edited forward remains committed.
2. A setup/reset stage changes a destination and then reports failure, but rollback skips the current stage.
3. Import or forward activation reports candidate-cleanup failure after committing a new `config.toml`.
4. Identity rollback silently ignores failed deletion or fabricates an empty file.
5. Runtime quarantine disappears when Android recreates the service in the same process.
6. A broker password write reports success without proving owner-only permissions.
7. Rust diagnostics represent failure as timestamp zero or as an indistinguishable empty list.
8. Tests prove that an error message appeared but do not prove exact authoritative restoration.

FIX8 must eliminate those outcomes at their source.

---

# 2. Release decision

The baseline is **NO-GO**. FIX8 is complete only when all requirements in this specification and every checkbox in the companion TODO are implemented and validated against one immutable Git commit.

No task may be marked complete based solely on:

- a test name;
- an earlier FIX7 comment or commit claim;
- a visible snackbar/error message;
- a repository model being restored while a backing file remains changed;
- a green rerun after an unexplained flaky failure;
- code inspection without the exact negative-path test requested here.

---

# 3. Binding architecture decisions

## 3.1 Setup is draft-only until final commit

The setup wizard owns a private draft. Before final Review save succeeds, setup may read authoritative state but may not mutate:

- encrypted identity;
- public identity;
- `authorized_keys`;
- managed broker secret;
- `setup_input.json`;
- application preferences;
- `forwards.json` or `ForwardsRepository` authoritative state;
- `config.toml`.

Generated/imported private identity bytes live in a ViewModel-owned, non-serializable draft holder. Draft forwards live in the setup ViewModel's own flow. Neither is written to an authoritative repository until the final setup transaction.

A setup cancel, ViewModel clear, identity replacement, or successful final save must wipe draft private bytes. A failed save may retain the draft for an explicit retry, but no copy may leak into UI state, `SavedStateHandle`, logs, exceptions, or data-class `toString()` output.

## 3.2 Final setup has one commit point

After isolated validation and candidate-workspace cleanup succeed, one `SetupPersistenceCoordinator.persist()` call commits all requested authoritative resources.

Required stage order:

```text
Identity
AuthorizedKeys
BrokerSecret
SetupInput
Preferences
Forwards
Config LAST
```

`Config` remains last because it references all earlier resources. `Forwards` is a real transactional stage; it may not be committed by `SetupForwardsController` before Review save.

## 3.3 Rollback includes the current attempted stage

A stage is added to the rollback set **before** its apply function is invoked. This is mandatory because an operation can replace its destination and then fail during permission enforcement, temp cleanup, status verification, or cancellation.

Every stage restore must be idempotent. On ordinary failure or cancellation, rollback runs in reverse attempted order under `NonCancellable`.

This rule applies to setup, reset, forward activation, and any repository operation whose public contract returns failure after a destination may have changed.

## 3.4 Exact snapshots are repository-owned

`config.toml`, `setup_input.json`, broker secret, identity files, and `forwards.json` are snapshotted as exact presence plus bytes. Parsing or re-rendering is not rollback.

Each repository captures and restores its own files under its own serialization lock. Callers may not perform separate `exists()` and `readText()` calls and call the result coherent.

`setup_input.json` is secret-bearing because it can contain a plaintext broker password. Its snapshot bytes must be wiped in `finally` after every transaction outcome.

## 3.5 Candidate cleanup precedes authoritative commit

A validation candidate or workspace must be removed successfully before any new authoritative config is committed.

Required order:

```text
create unique candidate/workspace
write candidate
validate candidate
clean candidate/workspace successfully
commit authoritative resources
```

No import or forward operation may commit `config.toml` from inside `withCandidateFile` or `withTemporaryDirectory`.

## 3.6 Configuration admission is authoritative

One application-scoped admission owner serializes these operations:

- final setup save;
- config import;
- forward mutation plus activation;
- configuration reset;
- settings preference mutation;
- network-policy preference mutation.

Wizard-local draft operations use a separate setup-local coordinator and never write authoritative state.

Busy responses must identify the actual active owner. The admission implementation may not contain a window where the lock is held but the active operation is still null.

## 3.7 Runtime quarantine is application-scoped

Native runtime safety state must outlive an individual `TunnelForegroundService` instance. The application-scoped owner records at least:

- whether runtime state is quarantined/uncertain;
- whether a native stop has been verified;
- the fixed/redacted quarantine reason code/message;
- a monotonic generation/version for tests and stale-update rejection.

Every service instance reads this owner. Recreating the service in the same process must not clear quarantine or permit native start.

Only a verified **explicit STOP** clears quarantine. Successful pause or destroy fallback may record an observed stop, but may not authorize recovery from a pre-existing quarantine.

Native status refresh cannot overwrite the quarantine overlay.

## 3.8 Diagnostic failure remains visible without invented time

`AndroidLogEvent.unix_ms` becomes nullable/optional across Rust serialization and Kotlin models. A diagnostic error without a clock sample is represented as a real error event with `unix_ms = null`, not `0`, and not an empty list.

The UI must display a fixed “time unavailable” representation for null timestamps.

## 3.9 Exception boundaries are explicit

Production `runCatching` is removed. Expected recoverable exceptions use explicit `try/catch (Exception)`; intentional native-library load failure catches only the specific link error being normalized.

One narrow exception is allowed: the shared cleanup-composition primitive may catch `Throwable` solely to preserve the exact primary throwable, run mandatory cleanup, attach cleanup failure as suppressed, and rethrow the same primary instance. It may not normalize, log, redact, or convert a fatal `Error` into an ordinary `Result.failure`.

---

# 4. Required invariants

## FIX8-INV-001 — No wizard pre-commit mutation

Before final setup commit success, exact snapshots of all authoritative files and repository-visible forwards must remain unchanged, including after:

- identity import from path;
- identity import from URI;
- identity generation;
- forward add/edit/delete;
- step validation;
- setup cancellation;
- ViewModel clear;
- final validation failure;
- final save cancellation before commit.

## FIX8-INV-002 — Draft identity ownership

- Private bytes are held only in a non-data draft object.
- Replacing a draft wipes the old byte array.
- `cancel()` and `onCleared()` wipe the draft.
- A save uses a copy or explicitly transferred ownership and wipes the save-owned bytes in `finally`.
- Missing canonical private/public identity or peer ID fails closed. No `orEmpty`, prior peer-ID fallback, or source-value fallback is accepted for required canonical fields.

## FIX8-INV-003 — Draft forwards ownership

- Setup forward edits modify only the setup draft flow.
- The global `ForwardsRepository` remains unchanged until final commit.
- Final setup validation uses the full draft; config rendering uses enabled draft forwards.
- Final setup persistence writes the full draft to `forwards.json` before writing `config.toml`.

## FIX8-INV-004 — Actual-owner admission

A rejected operation always reports the operation that actually owns admission. There is no mutex/metadata publication race.

## FIX8-INV-005 — Exact config-file snapshot API

`ConfigRepository` owns one serialization boundary for both `config.toml` and `setup_input.json` and exposes internal result-returning methods equivalent to:

```kotlin
internal class ConfigFilesSnapshot(
    val config: ExactFileSnapshot,
    val setupInput: ExactFileSnapshot,
) {
    fun wipeSecrets() = setupInput.wipe()
}

@CheckResult
internal suspend fun captureFilesSnapshot(): Result<ConfigFilesSnapshot>

@CheckResult
internal suspend fun restoreConfigSnapshot(snapshot: ExactFileSnapshot): Result<Unit>

@CheckResult
internal suspend fun restoreSetupInputSnapshot(snapshot: ExactFileSnapshot): Result<Unit>

@CheckResult
internal suspend fun saveSetupInputAtomically(input: SetupConfigInput): Result<Unit>
```

The exact shape may vary to satisfy detekt, but semantics may not.

## FIX8-INV-006 — Attempted-stage rollback

On a stage apply failure or cancellation, rollback includes the current stage and all earlier attempted stages. A failure before mutation is handled by an idempotent restore.

## FIX8-INV-007 — Stage-specific identity restoration

Setup rollback restores:

- `Identity` stage: encrypted identity and public identity only;
- `AuthorizedKeys` stage: `authorized_keys` only.

Do not restore the entire triplet twice for two distinct stages. Every restore uses required snapshot bytes and checked deletion.

## FIX8-INV-008 — Exact forwards transaction snapshot

`ForwardsRepository` owns a transactional snapshot containing:

- exact `forwards.json` presence/bytes;
- current in-memory list;
- load state/error needed to restore truthful UI state.

Rollback restores disk first and publishes in-memory state only after disk restoration succeeds. Revision advances after restore so stale receipts cannot become valid again.

## FIX8-INV-009 — Import cleanup-before-commit

Config import cannot write `config.toml` until candidate cleanup succeeds. A config write that returns failure after replacing the destination must restore the exact previous config and report rollback failure if restoration fails.

## FIX8-INV-010 — Forward activation transaction

A forward edit is validated as a proposed list before authoritative mutation. One transaction then commits:

```text
Forwards
Config
```

with exact snapshots, config last, attempted-stage rollback, cancellation rollback under `NonCancellable`, and durable rollback-incomplete reporting.

## FIX8-INV-011 — Reset repairs corrupt drafts

Reset snapshots exact bytes without parsing existing setup input or config. A corrupt `setup_input.json` must not prevent reset. Reset applies exact attempted-stage rollback and identifies which snapshot component failed.

## FIX8-INV-012 — Identity rollback is fail-closed

- `snapshot.existed == true` requires non-null bytes.
- Absent restore uses `Files.deleteIfExists` and consumes its outcome.
- Every pair/triplet member is attempted independently.
- Rollback failure is returned or attached as suppressed.
- Identity reads that require pair coherence use one locked repository method.

## FIX8-INV-013 — Owner-only broker secret

A broker secret persist/restore succeeds only if the resulting file is verified mode `0600` or an equivalent owner-read/write-only state on the platform.

A permission failure after replacement is a stage failure and triggers current-stage rollback. Temp files containing plaintext secrets receive owner-only permissions before content is written where the platform allows it.

## FIX8-INV-014 — Fatal-safe cleanup

Candidate/workspace/temp cleanup runs after value, ordinary exception, cancellation, and fatal `Error`. The primary throwable identity is preserved. Cleanup catches include `SecurityException` and other ordinary runtime failures, not only `IOException`.

## FIX8-INV-015 — Durable runtime quarantine

- Quarantine survives service recreation in the same application process.
- Every start/resume/retry path uses the application-scoped owner.
- Every blocked retry publishes durable `native_runtime_quarantined`/recovery-required state.
- Only verified explicit STOP clears quarantine.
- Destroy fallback success does not clear pre-existing quarantine.
- Native status refresh cannot replace quarantine with Connected/Listening/Stopped truth until explicit recovery.

## FIX8-INV-016 — Preference serialization and coherence

Settings and network-policy preference writes use global configuration admission. Setup reads preferences once after admission and uses that same object for validation rendering and the persistence request.

A concurrent preference write cannot be lost or overwritten by setup rollback.

## FIX8-INV-017 — Exactly-once application initialization

Concurrent `AppInitializationCoordinator.start()` calls may create candidates, but only the winning lazy job may begin `initialize()`. No losing job runs any initialization instruction.

## FIX8-INV-018 — No setup-screen main-thread file I/O

Setup draft loading and stored identity/forwards loading are asynchronous. Setup UI has explicit `Initializing`, `Ready`, and `Failed` load truth and blocks final save until baselines are ready.

## FIX8-INV-019 — No invented diagnostic timestamp

No production Rust/Kotlin/JNI fallback emits timestamp zero to mean unavailable. Double log-buffer/clock failure produces a visible error event with no timestamp.

## FIX8-INV-020 — Durable failure boundary

Every authoritative mutation failure is stored before optional snackbar/notification delivery. Raw secret-bearing exception text and raw `Throwable` logging are prohibited at UI, repository state, JNI JSON, and diagnostic boundaries.

---

# 5. Detailed component design

## 5.1 Setup identity draft

Add a non-data class owned by `SetupViewModel`, for example:

```kotlin
internal class SetupIdentityDraft {
    private val lock = Any()
    private var replacement: DraftIdentityReplacement? = null

    fun replace(
        privateIdentity: ByteArray,
        publicIdentity: String,
        peerId: String,
    ) = synchronized(lock) {
        require(privateIdentity.isNotEmpty())
        require(publicIdentity.isNotBlank())
        require(peerId.isNotBlank())
        replacement?.wipe()
        replacement = DraftIdentityReplacement(privateIdentity, publicIdentity, peerId)
    }

    fun copyForSave(): DraftIdentityReplacement? = synchronized(lock) {
        replacement?.copyForSave()
    }

    fun clear() = synchronized(lock) {
        replacement?.wipe()
        replacement = null
    }
}

internal class DraftIdentityReplacement(
    val privateIdentity: ByteArray,
    val publicIdentity: String,
    val peerId: String,
) {
    fun copyForSave() = DraftIdentityReplacement(
        privateIdentity.copyOf(),
        publicIdentity,
        peerId,
    )

    fun wipe() = privateIdentity.fill(0)
}
```

Do not put `DraftIdentityReplacement` in `SetupWizardState`.

`SetupIdentityController` validates and canonicalizes an imported/generated identity, then replaces the draft. It does not call `IdentityRepository.storeEncryptedIdentity()`.

## 5.2 Setup-local operation ownership

Add one shared setup-local operation coordinator for identity load/import/generate, draft forward edits, setup loading, and final save admission. It prevents overlapping actions from publishing stale UI state or clearing `isBusy` while another operation remains active.

This coordinator is not a substitute for global configuration admission. Final save acquires setup-local admission and then global `SetupSave` admission.

## 5.3 Global admission token

Replace the mutex-plus-late-metadata design with an atomic owner token or another implementation with equivalent no-window semantics:

```kotlin
private data class ActiveConfigurationMutation(
    val id: Long,
    val operation: ConfigurationOperation,
)

class ConfigurationMutationCoordinator {
    private val sequence = AtomicLong(0)
    private val active = AtomicReference<ActiveConfigurationMutation?>(null)

    suspend fun <T> tryRun(
        operation: ConfigurationOperation,
        block: suspend () -> T,
    ): ConfigurationAdmission<T> {
        val token = ActiveConfigurationMutation(sequence.incrementAndGet(), operation)
        if (!active.compareAndSet(null, token)) {
            return ConfigurationAdmission.Busy(
                active = requireNotNull(active.get()).operation,
            )
        }
        return try {
            ConfigurationAdmission.Completed(block())
        } finally {
            check(active.compareAndSet(token, null)) {
                "Configuration admission owner changed unexpectedly"
            }
        }
    }
}
```

Add `PreferenceMutation` to `ConfigurationOperation`.

## 5.4 Generic exact byte replacement

Consolidate same-directory temp creation, byte write, atomic move fallback, and cleanup composition into one internal primitive. Callers supply optional post-move verification, such as broker-secret permissions.

The primitive must:

- use `Files.createDirectories` and verify the parent is a directory;
- create a unique temp in the destination directory;
- optionally secure the temp before writing secret bytes;
- write bytes;
- atomic-move or replacement-move;
- run post-move verification;
- always clean temp;
- preserve primary and suppressed cleanup errors;
- never log raw paths or secret content.

## 5.5 Setup transaction result

`SetupPersistenceResult.Failed` continues to contain the primary failed stage and all rollback outcomes. The rollback list must include a result for the current attempted stage.

Cancellation attaches one `SetupRollbackException` per failed restore to the original `CancellationException` and rethrows it.

## 5.6 Forward activation coordinator

Add a data-layer coordinator rather than implementing a multi-resource transaction in a ViewModel. It owns exact snapshots and stage rollback for `Forwards` then `Config`.

The ViewModel remains responsible for:

- global admission;
- proposed-list construction;
- validation candidate lifecycle;
- durable UI mapping.

The coordinator owns authoritative mutation and rollback.

## 5.7 Runtime safety owner

Add an application-scoped collaborator, for example:

```kotlin
internal data class NativeRuntimeSafetySnapshot(
    val quarantined: Boolean,
    val stopVerified: Boolean,
    val code: String?,
    val message: String?,
    val generation: Long,
)

class NativeRuntimeSafetyState {
    private val lock = Any()
    private val _state = MutableStateFlow(
        NativeRuntimeSafetySnapshot(
            quarantined = false,
            stopVerified = true,
            code = null,
            message = null,
            generation = 0,
        ),
    )
    val state: StateFlow<NativeRuntimeSafetySnapshot> = _state.asStateFlow()

    fun markStartAttempted() = update {
        it.copy(stopVerified = false, generation = it.generation + 1)
    }

    fun quarantine(code: String, message: String) = update {
        it.copy(
            quarantined = true,
            stopVerified = false,
            code = code,
            message = SensitiveDataRedactor.redactText(message),
            generation = it.generation + 1,
        )
    }

    fun markObservedStopWithoutRecovery() = update {
        it.copy(stopVerified = true, generation = it.generation + 1)
    }

    fun markVerifiedExplicitStop() = update {
        NativeRuntimeSafetySnapshot(
            quarantined = false,
            stopVerified = true,
            code = null,
            message = null,
            generation = it.generation + 1,
        )
    }

    private inline fun update(transform: (NativeRuntimeSafetySnapshot) -> NativeRuntimeSafetySnapshot) {
        synchronized(lock) { _state.value = transform(_state.value) }
    }
}
```

Names may change, but the recovery distinction is binding.

## 5.8 Nullable log time

Rust:

```rust
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct AndroidLogEvent {
    pub unix_ms: Option<u64>,
    pub level: String,
    pub message: String,
}
```

Kotlin:

```kotlin
@Serializable
data class NativeLogEventDto(
    @SerialName("unix_ms") val unixMs: Long? = null,
    val level: String,
    val message: String,
)

data class LogEvent(
    val unixMs: Long?,
    val level: String,
    val message: String,
)
```

Normal events use `Some(timestamp)`. Failure events without a valid clock use `None`/`null`.

---

# 6. Error and status contract

Use fixed codes and redacted messages. Existing codes remain where already externally meaningful. Add or standardize:

| Code | Meaning |
|---|---|
| `configuration_operation_busy` | Another authoritative configuration operation owns admission. |
| `setup_draft_operation_busy` | A setup-local draft operation is already active. |
| `setup_draft_load_failed` | Setup baseline could not be loaded. |
| `setup_identity_invalid` | Imported/generated identity failed validation or lacked required canonical fields. |
| `setup_persistence_failed` | Setup failed and rollback completed. |
| `setup_rollback_incomplete` | Setup rollback failed for one or more stages. |
| `setup_cancelled_rollback_incomplete` | Cancelled setup could not fully restore attempted stages. |
| `config_import_failed` | Import failed before/at commit and previous config remains/restored. |
| `config_import_rollback_incomplete` | Import could not restore prior config. |
| `forward_activation_failed` | Proposed forward/config transaction failed and rollback completed. |
| `forward_activation_rollback_incomplete` | Forward/config rollback failed. |
| `reset_failed` | Reset failed and rollback completed. |
| `reset_rollback_incomplete` | Reset rollback failed. |
| `native_runtime_quarantined` | Native runtime state is uncertain. |
| `native_runtime_recovery_required` | Start/resume/retry rejected until verified explicit STOP. |
| `broker_secret_permissions_failed` | Owner-only secret permissions could not be enforced/verified. |
| `logs_diagnostic_unavailable` | Native logs failed and may lack timestamp. |

Do not include private identity, password, token, API key, raw TOML/JSON, or full private app path in messages.

---

# 7. Lock and ownership order

Required acquisition order:

```text
SetupWizardOperationCoordinator (setup UI only)
ConfigurationMutationCoordinator (authoritative global admission)
transaction coordinator mutex
one repository lock at a time
```

Rules:

- No code holding a repository lock may call another repository.
- No transaction coordinator may hold two repository locks simultaneously.
- Native validation occurs before authoritative transaction locks.
- Candidate/workspace cleanup occurs before authoritative transaction locks.
- DataStore reads/writes occur while global admission is held, but not while a file repository lock is held.
- Status reporting and snackbar/notification delivery occur after safety state mutation and outside repository locks.

---

# 8. Required test strategy

Every invariant requires at least one exact negative-path test. Tests must inspect exact bytes/presence and authoritative in-memory state, not only messages.

Use:

- `CompletableDeferred`, latches, injected operations, or lazy jobs for concurrency ordering;
- exact `ByteArray` identity observation seams for wiping;
- injected atomic-move, permission, delete, cleanup, and clock seams;
- service recreation with one shared `AppDependencies`/runtime-safety owner;
- real production controller/coordinator paths rather than reimplemented test logic.

Prohibited proof techniques:

- fixed sleeps for absence, ordering, overlap, exactly-once, or rollback completion;
- test names that claim restoration without byte-comparing the destination;
- only asserting `errorMessage`/snackbar;
- only asserting `ForwardsRepository.current()` when `forwards.json` or `config.toml` could differ;
- retrying CI until green without finding and fixing the nondeterminism.

---

# 9. Static enforcement

FIX8 must add or strengthen permanent enforcement for:

1. No production `runCatching` calls.
2. No unchecked `File.delete()` or `mkdirs()` in authoritative/secret paths.
3. No ignored `setReadable`/`setWritable` or equivalent permission result.
4. `@CheckResult` on authoritative mutation/snapshot/restore APIs.
5. No production `unix_ms: 0` or JSON `"unix_ms":0` diagnostic fallback.
6. No `ExactFileSnapshot(existed = true, bytes = null)` consumer fallback.
7. No config/setup rollback based on String re-rendering.
8. No setup controller call to identity/forwards authoritative mutation APIs.

Static checks supplement tests; they do not replace them.

---

# 10. Validation and signoff

FIX8 signoff requires:

- clean Git working tree;
- one recorded code-bearing signoff SHA;
- every FIX8 input document present at its exact path;
- focused Android tests with `--rerun-tasks`;
- three back-to-back full Android unit-test runs with no unexplained flakes;
- `ktlintCheck`, type-resolved detekt, `lintDebug`, `assembleDebug`, `check`;
- Rust fmt, clippy with `-D warnings`, and all workspace tests/all features;
- Docker real-broker/data-path and stop-lifecycle E2E;
- Android emulator setup, Listening/no-peer STOP, and real data path;
- live metered-to-unmetered transition;
- service recreation while quarantined;
- green CI on the exact signoff SHA, with no “rerun until green” substitution;
- final inventories and an implementation report created by Claude Code under `docs/review-source/`.

If an environmental check cannot run, it remains unchecked. It may not be converted to PASS by code inspection.

---

# 11. Non-goals

FIX8 does not:

- add automatic trading or unrelated product features;
- redesign the Rust WebRTC protocol;
- change the STUN-only policy unless required by an existing test;
- persist plaintext private identity to disk;
- introduce a second authoritative forwards repository;
- merge setup/reset/forward transactions into one oversized generic framework solely for abstraction;
- treat snackbar, notification, or logs as authoritative state;
- preserve compatibility with unsafe zero-timestamp diagnostic fallbacks.

---

# 12. Definition of done

FIX8 is done only when:

1. Setup drafts can be abandoned without any authoritative mutation.
2. Setup commits identity, keys, secret, setup input, preferences, full forwards, and config in one attempted-stage-safe transaction.
3. Setup/reset/import/forward failures and cancellations restore exact prior bytes, including the current attempted stage.
4. Identity deletion/missing-byte failures are visible.
5. Runtime quarantine survives service recreation and only verified explicit STOP clears it.
6. Broker-secret permissions are enforced and verified.
7. Fatal errors do not skip mandatory cleanup or become ordinary failures.
8. Rust/Kotlin diagnostics never invent timestamp zero or hide double failure as no logs.
9. Preference writes cannot race setup snapshots/rollback.
10. Exact negative-path tests and static checks prove each guarantee.
11. Final local, CI, Docker, and emulator evidence is complete against one immutable SHA.
