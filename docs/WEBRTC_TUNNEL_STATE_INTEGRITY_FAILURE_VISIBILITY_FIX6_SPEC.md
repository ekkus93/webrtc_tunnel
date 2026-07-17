# WebRTC Tunnel State-Integrity and Failure-Visibility Recovery FIX6 Specification

**Status:** Proposed implementation specification  
**Baseline archive:** `webrtc_tunnel-master_2807170551.zip`  
**Baseline SHA-256:** `7ae84e70083c27b18bf8ad5ed46833e236919b231453ca252ff92897dbdb84ff`  
**Review input:** `WEBRTC_TUNNEL_FIX5_CODE_REVIEW.md`  
**Primary platform:** Android/Kotlin  
**Secondary scope:** one small Rust timestamp-consistency correction

---

# 1. Purpose

FIX6 closes the remaining state-integrity, failure-visibility, cancellation, and concurrency gaps found after FIX5.

The current code already has a strong lifecycle model, verified native start/stop transitions, generation tokens, rollback receipts for forwards, and a substantial test suite. FIX6 must preserve those strengths. It must not redesign the tunnel protocol, signaling architecture, JNI API, navigation, or user-facing product model.

The central requirement is:

> A persistent or lifecycle mutation must never be reported as successful unless every required stage actually succeeded, and every failure that can affect authoritative state must be visible, redacted, and test-proven.

---

# 2. Goals

FIX6 shall:

1. Eliminate every discarded `Result` from authoritative mutation paths.
2. Prevent setup, import, forward activation, and default-config creation from claiming success after failed persistence.
3. Replace the lossy network-policy diagnostic bus with a required, direct production reporter.
4. Make policy-driven resume obey the latest user preference and runtime quarantine state.
5. Propagate coroutine cancellation through all suspend mutation paths.
6. Make network-policy monitoring fail closed and recover visibly instead of dying silently.
7. Make setup persistence transactional across identity, authorized keys, setup input, preferences, and active config.
8. Ensure current runtime status never presents stale peer identity as active.
9. Harden reset snapshot and rollback so one rollback failure does not suppress later rollback attempts.
10. Serialize setup/import operations and use unique candidate files.
11. Make identity and authorized-key persistence atomic and concurrency-safe.
12. Keep lifecycle command acceptance consistent with processor liveness.
13. Expand redaction coverage and reduce dependence on arbitrary exception messages.
14. Replace timing-sleep proof tests with deterministic synchronization.
15. Record reproducible Android and Rust signoff evidence against one exact commit.

---

# 3. Non-goals

FIX6 shall not:

- change MQTT signaling protocol semantics;
- change WebRTC negotiation, multiplexing, STUN, ICE, or tunnel framing;
- add TURN, cellular support, or new networking policy options;
- replace DataStore, Keystore, the forwards file format, or the config file format;
- introduce a general-purpose database;
- redesign the Android UI;
- make destroy-time cleanup the authoritative stop path;
- add silent retries that conceal persistent failure;
- convert required errors into logging-only diagnostics;
- catch fatal JVM `Error` values and continue as though nothing happened.

---

# 4. Terminology

## 4.1 Authoritative mutation

Any operation that changes persistent or runtime state relied on by later operations, including:

- `config.toml` writes or deletion;
- `setup_input.json` writes;
- DataStore preference writes;
- `forwards.json` writes;
- identity or `authorized_keys` writes;
- tunnel start, pause, resume, or stop transitions;
- policy-retry generation changes.

## 4.2 Required diagnostic

A diagnostic whose loss can hide incorrect authoritative state or a failed safety mechanism. Required diagnostics must not depend solely on a replay-zero `SharedFlow`, transient snackbar, or `Log.w`.

## 4.3 Optional transient message

A convenience message such as a success toast. It may use the snackbar bus. It must not be the only record of a required failure.

## 4.4 Transaction

A staged operation that:

1. validates all inputs before the first mutation;
2. captures exact prior state;
3. applies mutations in a defined order;
4. stops at the first failed stage;
5. attempts rollback of every already-mutated stage in reverse order;
6. reports the failed stage and every rollback outcome;
7. never reports success if any required stage failed.

---

# 5. Global hard rules

These rules apply to all FIX6 tasks.

```text
no discarded Result from an authoritative mutation
no runCatching around suspend persistence code unless CancellationException is rethrown
no replay-zero SharedFlow as the sole transport for a required diagnostic
no ignored tryEmit/trySend failure
no Log.w-only required failure
no raw Throwable passed into redacted diagnostics
no arbitrary exception message shown without redaction
no success UI after a failed required stage
no fixed shared candidate filename
no check-then-act outside the lock that protects the act
no rollback map that stops after the first rollback exception
no current remotePeerId preserved from previous status when no session exists
no command accepted after the lifecycle processor has terminated
no Thread.sleep proof of “nothing else happened”
no assertTrue(true) or equivalent proof test
no signoff without exact commit and command output
```

Recoverable production code should catch `Exception`, not raw `Throwable`. `CancellationException` must be caught first and rethrown. Fatal `Error` values must normally escape.

---

# 6. Required system invariants

## INV-001 — Mutation-result truthfulness

Every call returning `Result` from an authoritative mutation must be consumed in the same logical operation using `getOrThrow`, `fold`, or an explicit result branch.

The following is forbidden:

```kotlin
deps.configRepository.writeConfigAtomically(candidate)
showSuccess()
```

The minimum acceptable shape is:

```kotlin
deps.configRepository
    .writeConfigAtomically(candidate)
    .getOrThrow()

showSuccess()
```

A caller may not infer success from validation if the subsequent write result was not checked.

## INV-002 — Success means all required stages committed

A setup save may report `Configuration saved` only after all required setup stages commit.

A config import may report success only after the config file is atomically replaced.

A forward mutation may report success only after:

1. the forwards repository mutation commits;
2. the regenerated config validates;
3. the regenerated config atomically commits;
4. any required runtime synchronization succeeds, or the existing forward mutation is rolled back and the failure is shown.

## INV-003 — Cancellation is not an operational failure

Any suspend mutation must rethrow `CancellationException`. Cancellation must not be converted into:

- `Result.failure`;
- `ValidationResult(false, ...)`;
- a snackbar error;
- rollback unless the caller explicitly owns a cancellation-safe transaction and has entered a non-cancellable cleanup section.

## INV-004 — Required diagnostics are delivered directly

Network policy delivery failures must reach a required reporter directly. The reporter must be supplied explicitly; there must be no default no-op reporter and no service-start subscription race.

A failed diagnostic delivery must itself have a visible fallback. Logging may supplement reporting but may not replace it.

## INV-005 — Network-policy enforcement fails closed

If monitoring cannot register, classify, collect, or unregister safely, the service must not continue as though policy enforcement remains active.

The service shall:

1. publish a redacted `network_policy_monitor_failed` diagnostic;
2. update network policy state to blocked/unknown;
3. submit an ordered policy-blocked lifecycle command or perform the existing safe pause path;
4. retry monitoring with bounded backoff while the service remains alive;
5. rethrow cancellation immediately.

## INV-006 — Latest preference wins

If `resumeOnUnmetered` is false, every pending policy resume token must be invalidated before returning. A pending token created under an older preference must never cause a later automatic resume.

Runtime quarantine must be published visibly from the policy-allowed path just as it is from manual start/resume paths.

## INV-007 — Setup persistence is transactional

Setup save must validate without mutating, then commit through a coordinator.

Validation helpers must not persist identity or authorized keys. Specifically:

- importing a private identity during setup resolution returns validated canonical material but does not call `storeEncryptedIdentity`;
- validating a remote public identity returns the canonical line but does not append it;
- candidate config validation uses a unique temporary file and deletes it safely;
- persistent mutation begins only after all validation succeeds.

The transaction must cover:

1. identity pair, if changing;
2. authorized keys, if changing;
3. setup input;
4. preferences;
5. active config, committed last.

The exact previous state of every potentially changed resource must be captured first. On failure, mutated stages are rolled back in reverse order. Rollback failure must be reported as partial recovery; it must never be relabeled as ordinary save failure.

## INV-008 — Current peer identity is current, not historical

`TunnelStatus.remotePeerId` represents an active current session. It must be `null` when `activeSessionCount == 0`, regardless of the previous status.

Historical identity may be retained only in a separately named field such as `lastRemotePeerId`, added in a later feature if required.

## INV-009 — Reset rollback is exhaustive and redacted

Snapshot capture must be exception-contained and cancellation-aware. Each rollback stage must be attempted independently. A failure restoring Config must not prevent SetupInput or Forwards restoration.

Every reset and rollback reason must be produced from a fixed safe message or `SensitiveDataRedactor.redactText`.

## INV-010 — Initialization failure is explicit

Default config creation must return `Result<Unit>`. The existence check and write must occur under the same config write mutex. The application must not block the main thread with unbounded `runBlocking` file work and must not continue silently after default creation failure.

Until initialization succeeds, start requests must fail visibly with `app_initialization_failed` or `config_initialization_failed`.

## INV-011 — Identity storage is atomic under one repository lock

Identity and authorized-key mutations must be serialized. Writes use unique same-directory temporary files and replacement. The encrypted private identity and public identity are treated as one logical pair with rollback if the second replacement fails.

Authorized-key append is performed under the same repository lock and cannot lose a concurrent append.

No plaintext private identity may be written to a temporary file by the Android repository.

## INV-012 — Candidate files are unique and operations are serialized

All validation candidate files use `Files.createTempFile` or `File.createTempFile`. Setup save, config import, and forward activation each have an operation mutex or atomic busy gate around the entire operation.

Two rapid invocations may either:

- serialize and re-read fresh state before the second operation; or
- reject the second invocation visibly as already in progress.

They may not run concurrently against one fixed candidate path.

## INV-013 — Atomic writer honors its `Result` contract

Temporary-file cleanup failure must not escape outside a method that claims to return `Result`.

If the primary write failed and cleanup also failed, preserve the primary failure and attach cleanup as suppressed or publish a separate redacted cleanup diagnostic. If the write succeeded but cleanup failed, return `Result.failure(cleanupError)`.

## INV-014 — Lifecycle command acceptance matches processor liveness

When the lifecycle processor exits for any reason, the coordinator marks itself stopped and closes the command channel. `trySubmit()` must return false afterward.

An operation cancellation that terminates the processor cannot leave an open queue accepting commands with no consumer.

Known recoverable exceptions are reported. Fatal `Error` values are not caught by a generic `Throwable` branch.

## INV-015 — Destroy cleanup is explicitly best effort

Explicit STOP/PAUSE remains authoritative. `onDestroy` cleanup may attempt a final verified stop but must be documented, coded, and tested as best effort. No persistent state invariant may depend solely on the asynchronous destroy coroutine completing before process death.

## INV-016 — Required UI failures are durable

A required mutation failure must be represented in ViewModel/service state or a durable diagnostic repository. A replay-zero snackbar may mirror the failure but cannot be the only representation.

## INV-017 — Redaction favors fixed messages

Where practical, publish a fixed error code and safe fixed message instead of an arbitrary exception message. When an exception message is needed, redact it first.

The redactor must cover at least:

- `broker_password=...` and other prefixed underscore fields;
- JSON quoted keys such as `{"password":"..."}`;
- TOML quoted or bare secret fields;
- `Authorization: Basic ...`;
- bearer tokens;
- MQTT URI credentials;
- private key blocks;
- project identity private fields;
- SDP, ICE candidates, decrypted payloads, and forwarded payloads.

## INV-018 — Wall-clock failure is consistent in Rust

Rust components must not silently use timestamp zero in one component and panic in another. A shared fallible timestamp helper must return a controlled error. Callers must either propagate that error or record a safe diagnostic and omit the optional log/message operation.

---

# 7. Required design changes

## 7.1 Authoritative config write API

Keep `writeConfigAtomically(contents): Result<Unit>`, but require every production caller to consume it.

`ensureDefaultConfig` changes to:

```kotlin
open suspend fun ensureDefaultConfig(contents: String): Result<Unit> =
    writeMutex.withLock {
        if (configFile.exists()) {
            Result.success(Unit)
        } else {
            writeConfigAtomicallyLocked(configFile, contents)
        }
    }
```

Do not call `writeConfigAtomically` from inside the mutex because it would attempt to acquire the same non-reentrant mutex.

The following production call sites must be corrected or replaced by the setup transaction:

- `SetupSaveController.persistConfig`;
- `ImportExportService.importConfigContent`;
- `ForwardsViewModel.regenerateActiveConfig`;
- `ConfigRepository.ensureDefaultConfig`;
- any additional call discovered by a repository-wide search.

## 7.2 Setup transaction model

Introduce a dedicated coordinator. Names may vary, but the responsibilities and result model are required.

Suggested types:

```kotlin
enum class SetupPersistenceStage {
    Snapshot,
    Identity,
    AuthorizedKeys,
    SetupInput,
    Preferences,
    Config,
}

data class SetupPersistenceRequest(
    val configContents: String,
    val setupInput: SetupConfigInput,
    val preferences: AndroidAppPreferences,
    val replacementIdentity: IdentityReplacement?,
    val authorizedPublicIdentityToAdd: String?,
)

sealed interface SetupPersistenceResult {
    data class Success(
        val committedStages: List<SetupPersistenceStage>,
    ) : SetupPersistenceResult

    data class Failed(
        val failedStage: SetupPersistenceStage,
        val message: String,
        val rollback: List<SetupRollbackStageResult>,
    ) : SetupPersistenceResult
}
```

`IdentityReplacement.privateIdentity` is plaintext and must be wiped by its owner in `finally`. It must never appear in data-class `toString`, logs, diagnostics, or assertion messages. Prefer a non-data class or override `toString`.

The coordinator owns an operation mutex. It captures snapshots before the first mutation. The config is committed last. Rollback is reverse-order and continues after individual rollback failure.

## 7.3 Config import

Config import validates with a unique candidate file, then atomically writes and checks the result. It does not need a multi-resource transaction if it changes only `config.toml`.

Required behavior:

- identity absence may use identity-less validation;
- present-but-unreadable identity is a visible failure;
- cancellation propagates;
- candidate temp cleanup is contained;
- write failure prevents success UI;
- exception message is redacted before display.

## 7.4 Forward activation

Preserve the existing mutation receipt architecture.

The activation sequence remains:

1. mutate forwards and receive receipt;
2. regenerate and validate config;
3. atomically commit config;
4. synchronize runtime if required;
5. on failure, call `rollbackReceipt(receipt)` unless revision changed.

`regenerateActiveConfig()` must convert config write failure into `ValidationResult(false, redactedMessage)`. It may not return the prior validation success after a failed write.

## 7.5 Network policy reporter

Remove `AppDiagnosticEventBus` from the required network-delivery path.

Use an explicit reporter supplied to `monitor`:

```kotlin
fun interface NetworkPolicyDiagnosticReporter {
    fun report(
        code: String,
        message: String,
    )
}

fun monitor(
    context: Context,
    reporter: NetworkPolicyDiagnosticReporter,
): Flow<NetworkPolicyStatus>
```

No default reporter is allowed.

`TunnelForegroundService` supplies a direct adapter to `StatusReporter.publishError`. This removes the subscription race and the ignored `tryEmit` path.

The reporter receives only a redacted string. `Throwable` is never passed through the interface.

## 7.6 Network monitor recovery

The service owns monitor retry and fail-closed behavior. Suggested policy:

- first failure: publish, block/pause, retry after 1 second;
- subsequent failures: capped exponential delays of 2, 4, 8, then 15 seconds;
- reset delay after a successful policy event;
- continue only while `serviceScope` is active;
- cancellation exits immediately.

The precise delay values are less important than deterministic tests and a cap. Inject a delay/backoff policy for tests rather than sleeping.

## 7.7 Policy-allowed handling

The handler must preserve the actual quarantine error and invalidate stale retry state in every non-resume branch.

Target behavior:

```kotlin
val runtimeAllowed = requireRuntimeStartAllowed()
runtimeAllowed.getOrElse { error ->
    invalidatePendingPolicyRetry()
    reporter.publishError(
        code = "native_runtime_quarantined",
        message = SensitiveDataRedactor.redactText(
            error.message ?: "Runtime restart is blocked",
        ),
    )
    return
}

if (!pausedByPolicy.get()) {
    invalidatePendingPolicyRetry()
    return
}

val prefs = /* cancellation-aware read */

if (!prefs.resumeOnUnmetered) {
    invalidatePendingPolicyRetry()
    return
}
```

## 7.8 Cancellation-aware mutation helper

A small helper may be used to reduce repetitive code:

```kotlin
suspend inline fun <T> mutationResult(
    crossinline block: suspend () -> T,
): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
```

Do not use it for code that must distinguish specific exception classes or perform rollback in a non-cancellable context.

## 7.9 Status mapping

Use the native value as current truth:

```kotlin
remotePeerId = remotePeerId.takeIf { activeSessionCount > 0 }
```

Do not use `previous.remotePeerId` as fallback for current status.

## 7.10 Reset hardening

Snapshot capture returns a redacted failure without mutation. Rollback uses an explicit loop, not `map`, so unexpected exceptions can be converted per stage and later stages still run.

The failed reset stage itself is rolled back only if its repository API can partially mutate before failure. Repository mutation contracts must explicitly state whether failure guarantees no commit. When that guarantee is absent, include the failed stage in rollback.

## 7.11 Application readiness

Replace main-thread initialization with explicit readiness state.

Suggested model:

```kotlin
sealed interface AppInitializationState {
    data object Initializing : AppInitializationState
    data object Ready : AppInitializationState
    data class Failed(val code: String, val message: String) : AppInitializationState
}
```

Application scope starts initialization on the IO dispatcher. Start actions check `Ready`; otherwise they publish a visible error and do not call native start.

If retaining synchronous initialization, it must be strictly bounded and must throw/abort visibly on failure. Silent `runBlocking` is prohibited.

## 7.12 Identity persistence

Identity repository methods must be serialized by one lock. The exact implementation may remain synchronous to limit API churn, provided all filesystem work is invoked from IO dispatchers.

Required internal primitives:

- unique temp write in target directory;
- atomic move with visible non-atomic fallback;
- exact snapshot of file presence and bytes/text;
- rollback after pair replacement failure;
- authorized-key read/modify/write under the same lock.

## 7.13 Lifecycle coordinator

Add a stopped flag and close the queue when the processor exits. `trySubmit` consults that state.

Catch order:

1. `CancellationException` -> rethrow;
2. recoverable `Exception` -> publish visible redacted error and continue only if publication succeeds;
3. `Error` -> not caught.

The processor’s `finally` closes the channel and marks stopped.

## 7.14 Required operation failure state

Where a ViewModel currently uses only `SnackbarController.report`, add an observable failure field, for example:

```kotlin
data class OperationFailure(
    val code: String,
    val message: String,
)
```

A success clears the prior operation failure. A transient snackbar may still mirror the event.

## 7.15 Rust timestamp helper

Create one helper in a shared crate or duplicate a consistently fallible helper if moving code is disproportionate:

```rust
pub fn unix_time_ms() -> Result<u64, SystemTimeError> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
}
```

No `.unwrap_or(0)` and no `.expect("system time is before unix epoch")` remain in the reviewed timestamp paths.

---

# 8. Required diagnostics and codes

At minimum, preserve or add these codes:

| Code | Required condition |
|---|---|
| `config_write_failed` | active config persistence failed |
| `config_import_failed` | config import validation or commit failed |
| `setup_persistence_failed` | setup transaction failed |
| `setup_rollback_incomplete` | one or more setup rollback stages failed |
| `forward_config_activation_failed` | forward saved/validated config could not commit or activate |
| `config_initialization_failed` | default config could not be created |
| `network_policy_event_delivery_failed` | unexpected callback-flow delivery failure |
| `network_policy_monitor_failed` | monitor setup or collection failure |
| `network_policy_classification_failed` | network classification failed and policy was forced blocked |
| `network_policy_unregister_failed` | callback unregistration failed |
| `policy_allowed_preference_read_failed` | policy handler could not read preferences |
| `native_runtime_quarantined` | automatic resume blocked by runtime uncertainty |
| `lifecycle_command_failed` | recoverable command handler exception |
| `lifecycle_processor_stopped` | command rejected because processor is no longer alive, when not normal teardown |
| `identity_persistence_failed` | identity pair could not commit |
| `authorized_keys_persistence_failed` | authorized key update could not commit |
| `reset_snapshot_failed` | reset snapshot capture failed |
| `reset_rollback_incomplete` | reset rollback had at least one failure |

Messages must be fixed safe text when possible. Any included exception message is redacted first.

---

# 9. Testing requirements

## 9.1 Negative-path testing

Every authoritative mutation needs tests for:

- success;
- validation failure before mutation;
- persistence failure;
- cancellation;
- rollback success;
- rollback failure where applicable;
- concurrent second invocation;
- no false success message/state.

## 9.2 Determinism

Tests proving exactly-once, no restart, or no later action must use:

- test dispatcher advancement;
- channels/latches/deferred barriers;
- explicit queue-drained hooks;
- observable generation/state changes.

Do not use `Thread.sleep` or real elapsed-time windows.

## 9.3 Diagnostic tests

Tests must exercise the production helper/path, not only a classifier function. Required cases:

- actual failed `trySend` reports exactly one redacted diagnostic;
- expected close reports none;
- no subscriber race exists because reporter is direct;
- reporter failure has a visible fallback;
- raw secret is absent from reporter and log message.

## 9.4 Transaction tests

Setup transaction tests must use recording fakes and assert exact stage order. A rollback-failure test is valid only when the rollback operation actually fails.

## 9.5 Cross-layer tests

At least one test per user-visible mutation verifies the ViewModel/controller does not emit success after repository failure.

---

# 10. Static enforcement

Add one of the following, in order of preference:

1. annotate mutation-returning methods with `@CheckResult` and enable the relevant lint inspection;
2. add a Detekt custom rule or forbidden-call review script that detects ignored `Result`-returning mutation calls;
3. add a repository script used in CI that searches known mutation calls and fails on bare expression statements.

This guard supplements tests; it does not replace them.

---

# 11. Migration and compatibility

- Existing persisted config, setup input, forwards, identity, and authorized-key formats remain unchanged.
- Existing error codes remain stable unless the code was never externally consumed.
- `AppDiagnosticEventBus` may remain for optional app-wide diagnostics, but network-policy delivery failure must not depend on it. Prefer removing it if unused after FIX6.
- Existing public ViewModel behavior may add durable error state without removing snackbar messages.
- No JNI signature change is required for the Android fixes.

---

# 12. Acceptance criteria

FIX6 is complete only when all conditions are true:

- [ ] every authoritative `Result` is consumed;
- [ ] setup/config import/forward activation cannot show false success;
- [ ] setup save is transactional with tested rollback;
- [ ] network policy delivery uses a direct required reporter with no no-op default;
- [ ] network monitor failure pauses/blocks safely and retries visibly;
- [ ] false `resumeOnUnmetered` invalidates pending retry;
- [ ] quarantine from policy resume is visible;
- [ ] cancellation propagates through all named suspend mutation methods;
- [ ] zero active sessions clears `remotePeerId`;
- [ ] reset snapshot and rollback continue safely and redact reasons;
- [ ] default config initialization is serialized and failure-visible;
- [ ] identity pair and authorized keys are serialized and atomically replaced;
- [ ] candidate files are unique and operations cannot overlap unsafely;
- [ ] config temp cleanup cannot escape the `Result` contract;
- [ ] lifecycle queue rejects commands after processor exit;
- [ ] required errors are durable, not snackbar-only;
- [ ] redactor regression suite includes structured secret formats;
- [ ] sleep-based proof tests in affected paths are replaced;
- [ ] Rust timestamp paths neither panic nor silently return zero;
- [ ] focused Android tests pass;
- [ ] full Android `check`, `lintDebug`, and `assembleDebug` pass;
- [ ] Rust `fmt`, `clippy`, and workspace tests pass;
- [ ] exact commit SHA and CI/local evidence are recorded.

---

# 13. Signoff evidence format

The completed TODO must record:

```text
git rev-parse HEAD:
git status --short:
Android focused command + result:
Android full check command + result:
Android lint command + result:
Android assemble command + result:
Rust fmt command + result:
Rust clippy command + result:
Rust test command + result:
GitHub Actions workflow URL or NOT RUN with exact reason:
Workflow head SHA or NOT RUN with exact reason:
Known unavailable device/E2E checks with exact reason:
```

Do not write `PASS` without preserving enough output to identify the command and zero-failure result.
