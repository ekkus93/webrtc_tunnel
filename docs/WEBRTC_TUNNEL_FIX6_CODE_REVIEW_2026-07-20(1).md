# WebRTC Tunnel FIX6 Code Review

**Review date:** 2026-07-20  
**Reviewed archive:** `webrtc_tunnel-master_2607201054.zip`  
**Checklist:** `WEBRTC_TUNNEL_STATE_INTEGRITY_FAILURE_VISIBILITY_FIX6_TODO(1).md`

## Executive verdict

**The code is materially better than the earlier baseline, but FIX6 is not complete and the app should not be treated as release-ready.** The TODO marks every item complete, yet source and test inspection show several release-blocking state-integrity failures, silent rollback/cleanup failures, cancellation bugs, and test claims that do not exercise the behavior their names imply.

The most important remaining defects are:

1. Setup writes identity state before validation, then silently ignores restoration failure.
2. Setup and reset transactions do not rollback already-committed stages when cancellation occurs.
3. Identity-pair persistence can leave the encrypted and public identity files mismatched if cancellation occurs between the two writes.
4. Pause and policy-pause stop failures do not quarantine the native runtime, so a later start/resume can run over an uncertain native process.
5. Rendering a config writes the plaintext MQTT password file outside the transaction and, in setup, potentially on the main thread.
6. The Rust timestamp work still permits timestamp `0` on the first clock failure, and another production crate still panics for a pre-epoch clock.
7. Several FIX6 test/checklist claims are indirect, incomplete, or false.

## Review method and validation limits

I unpacked the archive and inspected the Android and Rust production code, Android tests, Gradle configuration, CI workflow, and the repository copy of the FIX6 documents. The uploaded TODO and repository TODO are byte-for-byte identical.

I could not independently execute the build suites in this sandbox:

- Gradle attempted to download Gradle 8.7, but outbound DNS/network access is blocked.
- The Rust toolchain is not installed in the sandbox.
- The archive contains no `.git` directory, so recorded commit SHAs and commit-message evidence cannot be verified.

Consequently, the TODO's recorded PASS results are treated as documentary claims, not independently reproduced results. All implementation verdicts below are based on source and test inspection.

---

# What is good

## 1. The config atomic-write implementation is strong

`ConfigRepository.writeConfigAtomicallyWith` correctly keeps temp-file cleanup inside the returned `Result`, preserves the primary error, suppresses a secondary cleanup error, and rethrows cancellation with cleanup attached:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigRepository.kt:322-387`

The `ensureDefaultConfig` existence check and write are also correctly performed under the same mutex:

- `ConfigRepository.kt:103-121`

This is a meaningful improvement over a check-before-lock race and discarded write result.

## 2. Required network diagnostics no longer depend on a replay-zero bus

The old authoritative diagnostic `SharedFlow` is gone. `NetworkPolicyManager.monitor` requires a direct `NetworkPolicyDiagnosticReporter`, and the real `trySend` result is handled:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/network/NetworkPolicyManager.kt:25-35`
- `NetworkPolicyManager.kt:59-84`
- `NetworkPolicyManager.kt:114-141`

That is the right architectural direction: required diagnostics are direct, redacted, and not contingent on a collector being active.

## 3. Lifecycle command ordering and command-channel closure are thoughtfully implemented

The service submits lifecycle commands inline to an unlimited FIFO channel, avoiding per-command launch races. The coordinator closes the channel before setting `stopped`, and `trySubmit` uses `trySend(...).isSuccess`, matching the binding Q14 decision:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/TunnelLifecycleCoordinator.kt:39-58`
- `TunnelLifecycleCoordinator.kt:74-92`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt:314-330`

## 4. Forward mutations use save-then-publish and revision-aware receipts

`ForwardsRepository` serializes mutations, persists before publishing state, and uses revisioned receipts so an old rollback cannot overwrite a newer mutation. `ForwardsViewModel` reports revision mismatch distinctly:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsRepository.kt:90-214`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModel.kt:78-177`

This is substantially safer than optimistic in-memory mutation followed by best-effort persistence.

## 5. Typed transaction and rollback results are good scaffolding

Both setup persistence and reset use explicit stages and typed rollback results. Ordinary non-cancellation failures stop later forward stages and attempt rollback in reverse order:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/SetupPersistenceCoordinator.kt:18-65`
- `SetupPersistenceCoordinator.kt:93-203`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/TransactionalReset.kt:39-76`
- `TransactionalReset.kt:90-260`

The remaining defects are in edge-path semantics, not in the overall choice to model the transaction explicitly.

## 6. Secret redaction coverage is materially broader

The redactor contains structured-field and authorization-header handling and is applied at many important state/reporting boundaries. The test suite contains meaningful secret sentinels. That is a good direction for a tunnel application that handles credentials and private identity material.

## 7. Several fallbacks are justified and visible

These fallback choices are reasonable:

- Atomic move to same-directory replacement when `ATOMIC_MOVE` is unsupported, provided the fallback is logged.
- Network classification failure becoming `NetworkType.Unknown` and therefore fail-closed.
- `SharedFlow` lossiness for convenience-only snackbars, provided durable state owns required failures.
- Ignoring callback-channel sends after teardown in Rust where closure is expected and the primary state/error has already been recorded.
- Debug-only `getprop` failure returning “no override,” because the user preference remains authoritative.

---

# Release-blocking findings

## CRITICAL-1 — Setup's pre-validation identity rollback can fail silently

**Files:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupSaveController.kt:173-234`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/security/IdentityRepository.kt:119-125`

The controller snapshots identity storage, then writes the imported private identity and/or appends the remote authorized key before config validation:

- `SetupSaveController.kt:178-205`

On cancellation or any later exception, it restores the snapshot inside `runCatching`, but discards that `Result`:

- cancellation: `SetupSaveController.kt:223-227`
- ordinary failure: `SetupSaveController.kt:228-230`

If restoration fails, the user sees only the original save failure. The identity files or `authorized_keys` may remain changed, yet the operation is presented as rolled back. This is exactly the quiet state-integrity failure FIX6 was intended to eliminate.

The restore implementation itself worsens the problem: it restores three files sequentially and stops at the first exception, so later files are not attempted:

- `IdentityRepository.kt:119-125`

**Required fix:** Make pre-validation staging a first-class typed transaction. A restoration failure must produce `setup_rollback_incomplete`; restoration must continue across all files; the primary and rollback errors must both be preserved and redacted. Prefer validating against a temporary isolated validation directory rather than mutating live identity storage.

## CRITICAL-2 — Cancellation leaves partially committed setup state

**File:** `android/app/src/main/java/com/phillipchin/webrtctunnel/data/SetupPersistenceCoordinator.kt:93-121`

`applyStage` correctly rethrows `CancellationException`, but `persist` only rolls back when a stage returns `Result.failure`. If cancellation occurs after one or more stages committed, it escapes the loop and no rollback is attempted.

Example: Identity, AuthorizedKeys, and SetupInput may commit; cancellation during Preferences then leaves all three changes persisted.

The named test is misleading:

- `android/app/src/test/java/com/phillipchin/webrtctunnel/data/SetupPersistenceCoordinatorTest.kt:337-345`

It cancels only one preference write and merely asserts that cancellation propagates. It does not test cancellation at every stage, does not assert prior state restoration, and does not inspect rollback.

**Required fix:** Catch cancellation around the stage loop, perform rollback in `NonCancellable`, attach rollback failures as suppressed/typed evidence, then rethrow the original cancellation.

## CRITICAL-3 — Cancellation leaves partially reset configuration

**File:** `android/app/src/main/java/com/phillipchin/webrtctunnel/data/TransactionalReset.kt:90-127`

The reset coordinator has the same defect. If cancellation is thrown by a later reset stage, the function exits without calling `rollbackFromSnapshot`, leaving earlier reset stages committed.

**Required fix:** Treat cancellation as cancellation, but still restore already-mutated durable state in `NonCancellable` before rethrowing it.

## CRITICAL-4 — Identity-pair cancellation can leave encrypted/public files mismatched

**File:** `android/app/src/main/java/com/phillipchin/webrtctunnel/security/IdentityRepository.kt:74-103`

After replacing `identity.enc`, the code attempts to replace `identity.pub`. If the second operation throws `CancellationException`, cancellation is immediately rethrown with no rollback:

- `IdentityRepository.kt:83-88`

That can leave a new encrypted private identity paired with the old public identity.

The ordinary rollback also wraps both restore operations in one `runCatching`; if the first restore fails, the second is not attempted:

- `IdentityRepository.kt:90-100`

Furthermore, `IdentityRollbackIncompleteException` retains the forward-write error as cause but drops the rollback exception that explains why recovery failed.

**Required fix:** Once the first durable file has been changed, rollback must execute under `NonCancellable`. Restore both pair members independently, preserve each rollback failure, and rethrow cancellation only after recovery has been attempted.

## CRITICAL-5 — Pause-stop failures do not quarantine the native runtime

**File:** `android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt:935-989`

Explicit STOP correctly sets:

- `nativeStopVerified = false`
- `nativeRuntimeUncertain = true`

on stop failure:

- `TunnelForegroundService.kt:1012-1021`

Manual pause and policy pause do not. Their failure branches only publish an error:

- manual pause: `TunnelForegroundService.kt:949-954`
- policy pause: `TunnelForegroundService.kt:976-986`

The start guard blocks only when `nativeRuntimeUncertain` is true:

- `TunnelForegroundService.kt:527-551`

Therefore, a failed pause can be followed by Resume/Start even though the previous native runtime may still be running. This creates duplicate-runtime, duplicate-listener, or corrupted lifecycle risk.

**Required fix:** Every failed operation intended to stop the native runtime must enter the same quarantine state as explicit STOP failure. Only a verified explicit STOP should clear it.

## CRITICAL-6 — Config rendering mutates a plaintext password file outside the transaction

**File:** `android/app/src/main/java/com/phillipchin/webrtctunnel/data/ConfigRepository.kt:237-252,442-456`

`renderOfferConfig` is not a pure render. It calls `resolveBrokerPasswordFile`, which creates or overwrites:

`filesDir/runtime/mqtt_password.txt`

with the plaintext broker password:

- `ConfigRepository.kt:442-456`

This occurs before validation/commit in setup and forward regeneration. If validation or config persistence fails, the password file remains created or changed. It is not included in setup/reset snapshots or rollback.

In setup, `renderOfferConfig` is called outside `withContext(ioDispatcher)`:

- `SetupSaveController.kt:206-215`

so password-file I/O may occur on the main dispatcher.

**Required fix:** Separate pure config rendering from secret-file persistence. Persist the password file as an explicit transaction stage, with exact snapshot/rollback and restrictive file permissions. Render should accept the already-decided path and perform no I/O.

## CRITICAL-7 — Startup verification cleanup swallows cancellation

**File:** `android/app/src/main/java/com/phillipchin/webrtctunnel/data/UnverifiedStartContext.kt:20-36`

`cleanupUnverifiedStart` wraps a suspend `stop()` call in `runCatching`. Because `runCatching` catches `Throwable`, it converts `CancellationException` into a normal failure, publishes an operational error, and returns false instead of propagating cancellation.

There are no focused tests for this helper in the archive.

**Required fix:** Use explicit `try/catch`, rethrow cancellation, and run mandatory stop recovery under deliberate `NonCancellable` semantics if that is the chosen lifecycle contract.

## CRITICAL-8 — Rust wall-clock handling is still inconsistent and can still panic

**Files:**

- `crates/p2p-core/src/time.rs:11-28`
- `crates/p2p-mobile/src/runtime/state.rs:128-140`
- `crates/p2p-daemon/src/messages.rs:90-102`
- `crates/p2p-signaling/src/transport/codec.rs:204-208`

`resolve_unix_ms(None, last)` returns the atomic's current value. Both production atomics initialize to zero, so the first clock failure still returns `0`, despite comments and checklist claims that zero is never invented.

The test seeds a successful `42` before testing failure:

- `crates/p2p-core/src/time.rs:50-56`

so it does not cover first-use failure.

A separate production path still contains:

```rust
.expect("system clock is before unix epoch")
```

at `p2p-signaling/src/transport/codec.rs:204-208`.

That directly contradicts P2-002's repository-wide consistency goal.

**Required fix:** Make time acquisition fallible at all correctness-sensitive call sites. For optional diagnostic timestamps, represent unavailability explicitly rather than zero. For protocol freshness/replay/retry timing, propagate a typed error or use a monotonic clock where appropriate.

---

# High-severity findings

## HIGH-1 — Import overlap is silently discarded

**File:** `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ImportExportViewModel.kt:152-176`

When the import/export mutex is busy, the second operation executes:

```kotlin
if (!operationMutex.tryLock()) return@launch
```

No durable failure, snackbar, result message, or log is emitted. P1-005 explicitly requires rejection to be visible or serialization using fresh state.

Cancellation also bypasses the state update that clears `isBusy`; the `finally` unlocks the mutex, but the UI may retain `isBusy = true` if the owning scope remains alive long enough to render state.

## HIGH-2 — Candidate cleanup failures are still silently ignored in import and forwards

**Files:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ImportExportService.kt:77-80`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModel.kt:243-256`

Both call `deleteCandidateFileSafely(temp)` and discard the returned `Result`. The helper correctly returns cleanup failure, but the authoritative callers do not consume it.

This directly undermines P1-005-B and the hard rule against ignored required results. It can leave secret-bearing candidate configs in cache with no indication.

## HIGH-3 — Imported private identity bytes are not wiped

**File:** `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/ImportExportService.kt:83-89`

`importPrivateIdentityContent` creates a ByteArray from canonical private identity text and passes it to `storeEncryptedIdentity`, but never wipes it. The array remains in memory until garbage collection.

## HIGH-4 — Identity snapshot restoration is non-atomic and ignores failed deletion

**File:** `android/app/src/main/java/com/phillipchin/webrtctunnel/security/IdentityRepository.kt:221-245`

- Existing files are restored with direct `writeBytes`, not atomic replacement.
- Absent files are restored with `file.delete()`, but the Boolean result is ignored.
- A deletion failure is therefore reported as successful restoration.

These are unsafe rollback semantics for identity material.

## HIGH-5 — Independent screen-level locks do not prevent cross-feature stale writes

Setup, import, forwards, and reset each use their own mutex/coordinator. They all ultimately write `config.toml`, but there is no shared application-level operation coordinator or revision/CAS check covering the full read-render-validate-write transaction.

`ConfigRepository.writeMutex` serializes only the final file write. It does not prevent this interleaving:

1. Forward operation reads setup/forwards and renders candidate A.
2. Import commits config B.
3. Forward operation later obtains `writeMutex` and commits stale candidate A.

Relevant locks/callers:

- setup: `SetupSaveController.kt:67-128`
- import: `ImportExportViewModel.kt:148-176`
- forwards: `ForwardsViewModel.kt:53-134`
- reset: `TransactionalReset.kt:84-127`
- final writer: `ConfigRepository.kt:185-189`

## HIGH-6 — Setup snapshots are not captured under the same storage locks as writers

`SetupPersistenceCoordinator.captureSnapshot` reads config existence and contents separately and reads setup input outside a shared transaction lock:

- `SetupPersistenceCoordinator.kt:132-139`

`ConfigRepository.configFileExists` and `readConfig()` do not acquire `writeMutex`:

- `ConfigRepository.kt:130-138`

A concurrent config writer can change the file between those reads, producing an inconsistent snapshot.

## HIGH-7 — Reset does not preserve exact setup-input absence

**File:** `android/app/src/main/java/com/phillipchin/webrtctunnel/data/TransactionalReset.kt:175-187,246-250`

Reset snapshots `SetupConfigInput`, not the exact setup-input file state. If `setup_input.json` was absent, `loadSetupInputResult()` yields default input. Rollback then writes a new defaults file, so prior absence is not restored.

This conflicts with the “exact prior state” claim in the class documentation.

## HIGH-8 — Network fail-closed reporting can itself kill the monitor before fail-closed state is applied

**Files:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/network/NetworkPolicyManager.kt:93-108,148-162`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/network/NetworkMonitorSupervisor.kt:62-80`

The reporter contract does not specify or enforce non-throwing behavior.

- In classifier failure handling, `reporter.report(...)` is called before emitting the blocked Unknown status. If the reporter throws, the callback escapes before fail-closed state is applied.
- In unregister cleanup, a reporter exception can escape `awaitClose` cleanup.
- In `NetworkMonitorSupervisor`, the reporter is called before `onMonitorFailure`. If it throws, the supervisor terminates before blocking the tunnel or retrying.

Required failure reporting should never be capable of preventing the required safety transition.

## HIGH-9 — Network classification can throw during dependency construction

**File:** `android/app/src/main/java/com/phillipchin/webrtctunnel/network/NetworkPolicyManager.kt:37-53`

The initial StateFlow value invokes `classifier()` without protection at object construction. `refresh()` and `evaluateWithPolicy()` are also unguarded. Only callback-driven classification uses the fail-closed helper.

A classifier failure during `AppDependencies` construction can crash application startup rather than publish the intended failed-closed state.

## HIGH-10 — Policy retry can silently discard a quarantine/readiness failure

**File:** `android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt:666-670`

`handleRetryPolicyResume` uses `requireRuntimeStartAllowed().getOrNull()`, invalidates the token, and returns without a diagnostic. The direct `handlePolicyAllowed` path correctly publishes a visible error, so the retry path reintroduces the silent failure P0-004 aimed to remove.

## HIGH-11 — Tunnel status decode/invalid-mode failures preserve stale live-session fields

**File:** `android/app/src/main/java/com/phillipchin/webrtctunnel/data/TunnelRepository.kt:164-211,383-427`

On JSON decode failure, the repository changes `serviceState` and `lastError`, but does not clear `remotePeerId`, active sessions, or MQTT state. Similar early error branches for missing/unknown native mode bypass the normal mapping that clears peer state.

This can display an Error state alongside stale “current peer” or connection data. P1-001 fixed the normal zero-session mapping but not all error branches.

## HIGH-12 — A dead lifecycle processor is only observed as dropped commands

The coordinator correctly stops accepting commands after processor death, but the service merely logs later drops:

- `TunnelLifecycleCoordinator.kt:46-58,100-114`
- `TunnelForegroundService.kt:323-330`

If the processor dies because `onError` throws or a fatal error escapes, the native tunnel can remain active while future STOP/policy commands are rejected. There is no processor-death escalation that quarantines the runtime, updates durable state, or triggers best-effort stop.

## HIGH-13 — Application startup still performs synchronous disk/network work on the main thread

`WebRtcTunnelApplication.onCreate` constructs `AppDependencies` synchronously:

- `WebRtcTunnelApplication.kt:12-21`

`AppDependencies` eagerly constructs `ForwardsRepository`, whose constructor loads/seeds forwards from disk:

- `AppDependencies.kt:33-36`
- `ForwardsRepository.kt:47-59`

It also eagerly constructs `NetworkPolicyManager`, whose initial status invokes network classification:

- `AppDependencies.kt:15-18`
- `NetworkPolicyManager.kt:42`

The P1-003 test checks only that the application source does not contain `runBlocking`; it does not prove that `onCreate` is free of blocking I/O:

- `android/app/src/test/java/com/phillipchin/webrtctunnel/WebRtcTunnelApplicationInitTest.kt:29-47`

## HIGH-14 — Required network-policy preference errors are still snackbar-only

**File:** `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/NetworkPolicyViewModel.kt:10-34`

P1-008's binding scope explicitly includes `NetworkPolicyViewModel`, but its save failure is only sent to the lossy convenience snackbar. There is no durable `OperationFailure` state.

The TODO itself acknowledges this exception while still marking the acceptance criterion complete.

## HIGH-15 — The project's own signoff records an unresolved stop bug

The FIX6 TODO says that stopping an offer while it is Listening with no peer reports Error rather than Stopped, and explicitly defers the fix. Regardless of whether it predates FIX6, it remains a real release behavior defect in the reviewed handoff.

I could not reproduce or isolate the current Rust root cause without the toolchain/runtime environment, so the project's own recorded evidence is the basis for this finding.

---

# Medium-severity findings

## MEDIUM-1 — Redaction is still incomplete at ViewModel boundaries

- `ImportExportViewModel` stores and shows `Throwable.message` without redaction: `ImportExportViewModel.kt:167-173`.
- `ForwardsViewModel` explicitly returns a raw exception message and comments that holistic redaction is future work, although P1-009 is marked complete: `ForwardsViewModel.kt:258-264`.
- Manual/policy pause failures publish raw `it.message`: `TunnelForegroundService.kt:949-953,982-985`.

Stable error codes plus fixed safe user messages would be preferable; redacted details can be retained separately.

## MEDIUM-2 — The forward store ignores temp deletion failure

**File:** `android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStore.kt:138-160`

`temp.delete()` returns Boolean, but the result is ignored. A cleanup failure after a successful move is silent. The robust config cleanup pattern should be reused.

## MEDIUM-3 — `runCatching` still normalizes fatal `Error` in several production paths

Examples:

- JNI library load/start/stop: `RustTunnelBridge.kt:40-48,159-184`
- native status decode/poll: `TunnelRepository.kt:164-188`, `TunnelForegroundService.kt:470-489`
- identity writes/exports: `IdentityRepository.kt:152-205`
- forwards loading: `ForwardsConfigStore.kt:98-115`

Some boundary normalization is deliberate and visible, but `runCatching` catches every `Throwable`, including `OutOfMemoryError`, linkage errors, and other fatal errors. The project explicitly rejected converting fatal errors in the lifecycle coordinator; that policy should be consistent.

Use explicit `catch (Exception)` unless a specific `Error` is intentionally handled and documented.

## MEDIUM-4 — Some suspend orchestration still converts cancellation into ordinary UI failure

`SettingsViewModel.refreshPublicIdentity` wraps a suspend function in `runCatching`:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SettingsViewModel.kt:123-143`

A cancellation can become `publicIdentityLoadError` instead of propagating. Audit all suspend `runCatching` sites, not only the FIX6-named persistence paths.

## MEDIUM-5 — Snackbar loss is explicit but `tryEmit` result remains ignored

**File:** `android/app/src/main/java/com/phillipchin/webrtctunnel/data/SnackbarController.kt:8-22`

Lossiness is acceptable only for messages that have a durable owner elsewhere. Several screens still rely solely on this channel, so ignored emission is not always harmless. At minimum, `show` should return Boolean for diagnostics/tests, and required failures must be stored first.

## MEDIUM-6 — Backoff constructor accepts invalid parameters

**File:** `android/app/src/main/java/com/phillipchin/webrtctunnel/network/NetworkMonitorSupervisor.kt:20-28`

There is no validation that `baseMs > 0`, `maxMs >= baseMs`, or that shifting cannot overflow for custom values. Invalid injected values can produce negative or malformed delays. Add constructor requirements and overflow-safe multiplication.

## MEDIUM-7 — Imported setup identity test names overclaim wiping behavior

`plaintextIdentityIsWipedOnSuccessFailureAndCancellation` tests only a successful persistence, then manually wipes the caller's buffer:

- `SetupPersistenceCoordinatorTest.kt:349-369`

It does not test failure or cancellation, and it does not prove the production controller wipes all outcomes.

## MEDIUM-8 — `validationFailurePerformsNoPersistentMutation` does not test validation

The test injects an identity-encryption/store failure in the coordinator:

- `SetupPersistenceCoordinatorTest.kt:160-177`

The coordinator does not validate. This test does not exercise the controller's pre-validation live identity/authorized-key mutations, which are the risky path.

## MEDIUM-9 — Exact rapid-import and cleanup integration tests are missing

The following required named tests do not exist:

- `twoRapidConfigImportsCannotShareCandidateFile`
- `candidateCleanupFailureDoesNotHidePrimaryFailure`

The TODO claims helper-level uniqueness and helper-level failure-return tests cover them. They do not exercise actual ViewModel concurrency or caller consumption of cleanup failure; in production, those callers discard the cleanup result.

## MEDIUM-10 — Readiness tests omit the actual Initializing path

No exact test named `startWhileInitializingDoesNotCallNative` exists. The service initialization-gate tests cover Failed state, not a start issued while the coordinator remains Initializing.

No exact `startAfterReadyContinuesNormally` test exists either; the checklist relies on unrelated existing service tests.

## MEDIUM-11 — A real timing-proof sleep remains

**File:** `android/app/src/test/java/com/phillipchin/webrtctunnel/data/SetupPersistenceCoordinatorTest.kt:79-88`

`Thread.sleep(OVERLAP_WINDOW_MS)` is used as an absence/overlap proof. This directly conflicts with the work discipline and P2-001. The TODO incorrectly says only 10 ms polling sleeps remain.

## MEDIUM-12 — Static ignored-result enforcement covers only a subset of authoritative mutations

Annotated examples include `writeConfigAtomically`, config start preparation/reset deletion, and identity append/export. Important `Result`-returning operations remain unannotated:

- `savePreferences`
- `ensureDefaultConfig`
- `writeConfig`
- forward mutations and rollback
- `TunnelRepository.stop`
- `refreshStatusResult`
- candidate deletion

The TODO explicitly leaves several unannotated, so the acceptance claim that future discarded authoritative results fail CI is too broad.

Additionally, type-resolved detekt is attached to Gradle `check`:

- `android/app/build.gradle.kts:188-195`

but Android CI runs plain `detekt`, `lintDebug`, and `assembleDebug testDebugUnitTest`, not `check`:

- `.github/workflows/ci.yml:159-183`

Therefore, the claim that type-resolved detekt enforcement runs in CI is inaccurate.

## MEDIUM-13 — Final signoff is not fully trustworthy as recorded

- The archive has no `.git`, so SHA/status/commit-message evidence cannot be checked.
- The recorded CI run was “in progress at signoff time,” not a completed passing final run.
- The TODO names one SHA as the final code commit, then later records an emulator-discovered code fix under another SHA without clearly reconciling the final source revision.
- Some named tests are missing or only indirectly “covered.”

The signoff should be regenerated from one immutable final SHA with a completed CI run and attached machine-readable reports.

---

# Dangerous or silent fallback audit

## Not justified / should be changed

| Fallback or discard | Location | Why it is unsafe |
|---|---|---|
| Discarded identity snapshot restore `Result` | `SetupSaveController.kt:223-230` | Can leave identity state changed while reporting ordinary save failure. |
| Cancellation escapes setup/reset without rollback | `SetupPersistenceCoordinator.kt:93-121`; `TransactionalReset.kt:90-127` | Leaves partially committed durable state. |
| Cancellation between identity-pair writes skips rollback | `IdentityRepository.kt:83-88` | Can leave private/public identity mismatch. |
| Ignored candidate cleanup result | `ImportExportService.kt:77-80`; `ForwardsViewModel.kt:253-256` | Secret-bearing temporary config may remain with no diagnostic. |
| Import overlap returns silently | `ImportExportViewModel.kt:159` | User action is discarded without state or message. |
| Policy retry uses `getOrNull` and returns | `TunnelForegroundService.kt:666-670` | Quarantine/readiness failure disappears. |
| Pause stop failure does not quarantine | `TunnelForegroundService.kt:949-954,976-986` | Later start/resume may run over uncertain native runtime. |
| Reporter may throw before fail-closed transition | `NetworkMonitorSupervisor.kt:74-80`; `NetworkPolicyManager.kt:93-108` | Failure-reporting path can prevent safety action. |
| Setup input/reset absence collapsed to defaults | `TransactionalReset.kt:175-187,246-250` | Rollback does not restore exact state. |
| First Rust clock failure returns zero | `p2p-core/src/time.rs:21-28` plus zero-initialized statics | Reintroduces prohibited sentinel timestamp. |
| Pre-epoch `expect` | `p2p-signaling/src/transport/codec.rs:204-208` | Process panic on wall-clock anomaly. |
| Discarded `file.delete()` Boolean | `IdentityRepository.kt:221-245`; `ForwardsConfigStore.kt:155-157` | Cleanup/restore can be falsely reported successful. |

## Justified, with conditions

| Fallback | Location | Assessment |
|---|---|---|
| Atomic move to replacement move | Config, identity, forwards writers | Justified on filesystems without `ATOMIC_MOVE`; must preserve/report cleanup and replacement errors. Config does this best; identity/forwards need parity. |
| Fail-closed Unknown network | `NetworkPolicyManager` | Correct safety policy. Reporter failure must not prevent applying it. |
| Conflating network status flow | `NetworkPolicyManager.monitor` | Reasonable because current policy state is authoritative, not each historical callback. Required diagnostics must remain direct. |
| Lossy snackbar channel | `SnackbarController` | Acceptable only as UI convenience after required failure is stored durably. Not acceptable as the sole copy. |
| Debug property read failure → no override | `ConfigRepository.kt:405-420` | Reasonable; user preference remains the source of truth. |
| Rust teardown send failures ignored | Several callback/test teardown paths | Usually justified when receiver closure is expected and primary state is already recorded; keep comments and avoid using this for authoritative lifecycle events. |

---

# FIX6 task-by-task verification

Legend:

- **PASS** — target behavior is implemented in the inspected source, with no material contradiction found in that subtask.
- **PARTIAL** — core path exists, but acceptance or tests are incomplete, or an edge path invalidates the full claim.
- **FAIL** — implementation materially contradicts the task.
- **NOT VERIFIABLE** — documentary/build evidence cannot be independently checked from this archive/environment.

## Stage A prerequisite and execution-order items

| Item | Verdict | Review |
|---|---|---|
| A-1 candidate helpers + `mutationResult` | **PARTIAL** | Helpers exist and `mutationResult` rethrows cancellation. Import and forwards discard candidate-deletion results, defeating the helper contract. |
| A-2 P0-001-A + P1-003 | **PARTIAL** | `ensureDefaultConfig` is correct and readiness exists. `onCreate` still eagerly constructs dependencies that perform disk/network work; Initializing-start coverage is missing. |
| A-3 P0-001-C/D/E audit | **PARTIAL** | Main config write results are consumed, but cleanup results and several authoritative Results remain unenforced. |
| A-4 direct reporter | **PASS** for bus removal | Direct required reporter is implemented and old bus removed. Reporter exception-safety remains a P0-006 problem. |
| A-5 stale retry/quarantine visibility | **PARTIAL** | Direct PolicyAllowed path is visible; RetryPolicyResume silently drops guard failure. |

## P0-001 — Eliminate false success from discarded config-write results

| Subtask | Verdict | Review |
|---|---|---|
| P0-001-A `ensureDefaultConfig` preserves `Result` | **PASS** | Correct mutex/existence/write structure at `ConfigRepository.kt:103-121`. |
| P0-001-B setup write failure stops later stages/no success | **PASS** for the narrow config-write scenario | Config is last in coordinator and failure does not report “Configuration saved.” Broader setup atomicity still fails under P0-003. |
| P0-001-C import consumes config write result | **PARTIAL** | Write result and cancellation are handled. Candidate cleanup is ignored; imported private buffer is not wiped; ViewModel boundary does not consistently redact. |
| P0-001-D forward regeneration fails on config commit failure | **PARTIAL** | Forward receipt rollback and failed activation are implemented. Password-file side effect is outside rollback; candidate cleanup is ignored. |
| P0-001-E repository-wide discarded-result audit | **PARTIAL** | Several authoritative mutation Results remain unannotated or discarded. Static enforcement is not repository-wide. |

## P0-002 — Direct required network diagnostic reporter

| Subtask | Verdict | Review |
|---|---|---|
| P0-002-A reporter contract | **PASS** | Present, no default/no-op implementation. |
| P0-002-B reporter required by monitor | **PASS** | Required parameter and real `trySend` handling are present. |
| P0-002-C service direct wiring | **PASS** | Service uses direct reporter; old collector is gone. |
| P0-002-D remove/demote bus | **PASS** | Authoritative bus no longer exists. |
| P0-002-E actual delivery-result tests | **PASS by source inspection** | Production handler seam and corresponding named tests exist. Tests were not executed here. |

## P0-003 — Transactional setup persistence

| Subtask | Verdict | Review |
|---|---|---|
| P0-003-A validation separated from mutation | **FAIL** | Live identity/authorized-key files are deliberately mutated before validation (`SetupSaveController.kt:178-205`). The workaround is not robustly transactional. |
| P0-003-B exact snapshots | **PARTIAL** | Snapshot types exist. Identity restore is non-atomic, can stop early, and ignores deletion failure; config existence/content snapshot is not captured atomically against writers. |
| P0-003-C coordinator and typed stages | **PASS** | Types and required stage order exist. |
| P0-003-D explicit mutation helper/rollback | **FAIL** for cancellation semantics | Ordinary failure rollback exists; cancellation after committed stages skips rollback. |
| P0-003-E controller uses coordinator once/visible rollback failure | **FAIL** | Controller's outer identity rollback discards failure and cannot emit `setup_rollback_incomplete`. |
| P0-003-F named tests | **FAIL / overclaimed** | Several tests exist only in name: validation test is not validation; cancellation is one stage with no state assertions; wipe test covers success only; controller outer-rollback failure is absent. |

## P0-004 — Stale policy retry and quarantine visibility

**Verdict: PARTIAL**

The direct `handlePolicyAllowed` implementation follows the intended shape. However, `handleRetryPolicyResume` silently discards a failed start guard, and pause-stop failures do not set runtime quarantine. Therefore, quarantine visibility and lifecycle safety are not complete across all retry/resume paths.

## P0-005 — Cancellation in persistent mutation paths

| Subtask | Verdict | Review |
|---|---|---|
| P0-005-A `mutationResult` | **PASS** | Correct explicit cancellation behavior. |
| P0-005-B forwards repository mutations | **PASS** | Named repository operations use `mutationResult` and save-then-publish. |
| P0-005-C orchestration wrappers | **FAIL / PARTIAL** | Setup's rollback `runCatching` hides restoration failure; startup verification cleanup catches cancellation; additional suspend `runCatching` sites remain. Transaction cancellation also skips rollback. |

## P0-006 — Network monitor fail-closed and recovery

| Subtask | Verdict | Review |
|---|---|---|
| P0-006-A callback classification failure | **PARTIAL** | Callback path catches classifier errors and chooses Unknown. Reporter failure can escape before Unknown is applied; constructor/refresh/evaluate paths remain unguarded. |
| P0-006-B whole monitor lifecycle supervisor | **PARTIAL** | Supervisor wraps the lifecycle and retries. A throwing reporter or fail-closed callback can terminate it before safety action/retry. |
| P0-006-C unregister failure | **PARTIAL** | Unregister exception is caught/redacted, but reporter exception can escape cleanup. |

## P1-001 — Clear stale remote peer identity

**Verdict: PARTIAL**

The normal mapping uses `remotePeerId.takeIf { activeSessionCount > 0 }`, satisfying the main target. Error/unknown-mode branches can retain stale peer/session/MQTT fields because they return before the normal mapping clears them.

## P1-002 — Transactional reset hardening

| Subtask | Verdict | Review |
|---|---|---|
| P1-002-A contain snapshot exceptions | **PASS** | Snapshot failure is converted to typed failure and aborts mutation. |
| P1-002-B redact reset reasons | **PASS** | Central redaction helper is used in reviewed reset paths. |
| P1-002-C continue rollback after individual exception | **PASS** for ordinary exceptions | Explicit loop continues after stage exception. Cancellation of forward reset still bypasses rollback entirely. |
| P1-002-D distinct partial rollback code | **PASS** | Settings state distinguishes rollback-incomplete. |
| Exact-state/cancellation acceptance | **PARTIAL** | Setup-input absence is not exact; cancellation after mutation is not rolled back. |

## P1-003 — Explicit application readiness

| Subtask | Verdict | Review |
|---|---|---|
| P1-003-A readiness state/coordinator | **PARTIAL** | Coordinator/state exist. `start()` is not visibly guarded against repeated invocation, and eager dependencies still do main-thread I/O. |
| P1-003-B start gating | **PARTIAL / code path present** | Start/resume guards exist. Exact Initializing and Ready named tests are missing. Retry guard failure is silent. |

## P1-004 — Atomic identity and authorized-key persistence

| Subtask | Verdict | Review |
|---|---|---|
| P1-004-A one repository lock | **PASS** | Shared JVM lock covers pair/authorized-key mutation and snapshots. |
| P1-004-B unique atomic replacement | **PARTIAL** | Forward writes use unique temp/move. Cleanup failure is only logged and cannot affect result; rollback helpers use direct writes/deletes. |
| P1-004-C identity pair as one logical commit | **FAIL** | Cancellation after first replace skips rollback; rollback stops on first failure and loses rollback cause. |
| P1-004-D serialize authorized-key append | **PASS** | Read-modify-write is locked and atomic; duplicates avoid rewrite. |

## P1-005 — Serialize user operations and unique candidates

| Subtask | Verdict | Review |
|---|---|---|
| P1-005-A unique candidate helper | **PASS** | Uses `Files.createTempFile` with distinct prefixes. |
| P1-005-B safe deletion | **FAIL at integration boundary** | Helper returns `Result`, but import/forward callers discard it. |
| P1-005-C atomic busy guards | **PARTIAL** | Setup/forwards visibly reject overlap. Import silently returns. Locks are per feature and do not prevent cross-feature stale commits. |
| Required tests | **PARTIAL** | Candidate helper uniqueness exists; actual rapid-import and cleanup-composition tests are missing. |

## P1-006 — Atomic config cleanup inside `Result`

**Verdict: PASS**

The inspected config writer follows the required primary-error, cleanup-error, and cancellation composition. This is one of the strongest FIX6 implementations.

## P1-007 — Lifecycle processor exit closes command acceptance

**Verdict: PASS for the stated task**

Q14 ordering is correct, cancellation/fatal error behavior is explicit, and command acceptance closes with processor exit. Separate concern: the service does not safely escalate an unexpected processor death.

## P1-008 — Durable required operation failures

**Verdict: FAIL / PARTIAL**

Forwards, ImportExport, and Settings have durable failure fields. `NetworkPolicyViewModel`, explicitly in Q9 scope, remains snackbar-only. Import overlap is also silently dropped.

## P1-009 — Expanded redaction and fixed messages

| Subtask | Verdict | Review |
|---|---|---|
| P1-009-A structured redactor coverage | **PASS by source/test inspection** | Structured secrets and Basic auth coverage are present. |
| P1-009-B safe fixed boundary messages | **PARTIAL** | Multiple ViewModel/service paths still expose redacted-or-raw exception messages directly; forwards comment says redaction is future work. |

## P1-010 — Destroy-time cleanup semantics

**Verdict: PASS for the narrow semantics**

Comments accurately describe explicit STOP as authoritative and destroy cleanup as best effort. Observed fallback failure remains visible. The known offer-stop state discrepancy remains separate.

## P2-001 — Remove sleep-based proof tests

**Verdict: FAIL**

A real absence/overlap proof sleep remains in `SetupPersistenceCoordinatorTest.kt:79-88`. The TODO statement that only polling sleeps remain is false.

## P2-002 — Consistent Rust wall-clock failure behavior

**Verdict: FAIL**

First-use failure can still yield zero, and `p2p-signaling` retains a pre-epoch `expect` panic. The substitute test does not cover first-use failure.

## P2-003 — Static ignored-result enforcement

**Verdict: PARTIAL**

Android lint enforcement for selected `@CheckResult` methods is useful. It is not comprehensive, no permanent negative fixture is present in the archive, and CI does not run the `check` task that wires type-resolved detekt.

## P2-004 — Final signoff evidence

**Verdict: NOT VERIFIABLE and internally incomplete**

The archive lacks git metadata; builds cannot be run in this environment; the named final CI run was still in progress; exact named test coverage does not match several checklist claims; and the TODO itself records an unresolved stop bug.

---

# Test-quality discrepancies

The following checklist claims should not be accepted as proof without replacement tests:

1. `validationFailurePerformsNoPersistentMutation` is not a validation-path test.
2. `cancellationDuringAnyStagePropagates` tests one stage and no rollback/state integrity.
3. `plaintextIdentityIsWipedOnSuccessFailureAndCancellation` tests success only.
4. `twoRapidConfigImportsCannotShareCandidateFile` does not exist; helper uniqueness is not equivalent.
5. `candidateCleanupFailureDoesNotHidePrimaryFailure` does not exist; callers actually discard cleanup failure.
6. `startWhileInitializingDoesNotCallNative` does not exist.
7. `startAfterReadyContinuesNormally` does not exist under that contract.
8. `lateStartupCompletionAfterDestroyCannotRestartOrCrash` does not exist; a different test is claimed as coverage.
9. P2-002's Rust substitute test seeds a known-good timestamp and misses first-failure zero.
10. A real `Thread.sleep` remains as an overlap proof.

---

# Recommended remediation order

## P0 / release blockers

1. Redesign setup validation so it does not mutate live identity storage, or make the staging/rollback a fully typed transaction whose restoration failures are visible.
2. Add cancellation rollback to setup and reset coordinators under `NonCancellable`, preserving the original cancellation.
3. Fix identity-pair cancellation and rollback continuation/error preservation.
4. Quarantine runtime on every failed stop-like operation, including pause and policy pause.
5. Make config rendering pure; add broker-password persistence to transactional state with exact snapshot/rollback and restrictive permissions.
6. Fix `cleanupUnverifiedStart` cancellation semantics.
7. Fix Rust time handling across all crates, including the remaining signaling panic.
8. Resolve and test the known offer-stop-while-Listening state discrepancy.

## P1 / high priority

9. Add one application-level configuration-operation coordinator or revision/CAS protocol across setup/import/forwards/reset.
10. Consume and report every candidate/temp cleanup result.
11. Make identity snapshot restore atomic per file, continue after failures, and check delete outcomes.
12. Make fail-closed state application independent of reporter success.
13. Make network construction/refresh classification fail closed.
14. Add durable NetworkPolicyViewModel failure state and visible import overlap rejection.
15. Clear all stale session fields in every TunnelRepository error branch.
16. Move all eager forwards/network work out of `Application.onCreate`.

## P2 / enforcement and evidence

17. Expand `@CheckResult` or a type-aware custom rule to all authoritative mutation Results, including helper cleanup Results.
18. Run Gradle `check` in CI or invoke the type-resolved detekt tasks explicitly.
19. Replace the remaining proof sleep with a barrier/deferred event.
20. Replace indirect/misnamed tests with tests that drive the exact production path.
21. Regenerate signoff from one final immutable SHA after completed CI, emulator/device checks, and the offer-stop fix.

---

# Bottom line

FIX6 contains many good corrections, especially the atomic config writer, direct diagnostic reporter, lifecycle FIFO/channel closure, forward revision receipts, redaction improvements, and typed rollback structures. However, the implementation still has multiple quiet or unsafe failure paths at transaction and lifecycle boundaries. The most serious issue is that cancellation and rollback failure can leave durable state partially changed while the UI reports only an ordinary failure—or nothing at all.

**Recommendation: do not call FIX6 complete and do not release this revision until the CRITICAL findings above are fixed and verified with exact negative-path tests.**
