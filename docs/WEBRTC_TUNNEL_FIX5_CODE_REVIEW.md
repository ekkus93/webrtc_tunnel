# WebRTC Tunnel Code Review and FIX5 Implementation Audit

**Reviewed archive:** `webrtc_tunnel-master_2807170551.zip`  
**Archive SHA-256:** `7ae84e70083c27b18bf8ad5ed46833e236919b231453ca252ff92897dbdb84ff`  
**FIX5 TODO SHA-256:** `fca9780147b9b8aaf05eb07ee4249f6470b97022cd98b2a295ac671f18dab5fc`  
**Review date:** 2026-07-17

## Executive verdict

The codebase has substantial hardening work and a large test suite, but I would **not sign off this tree for release yet**.

The most important issue is a repeated silent-failure pattern: several production call sites invoke `ConfigRepository.writeConfigAtomically()`, receive a `Result<Unit>`, and discard it. A failed disk write can therefore be reported as success. I found this in setup save, config import, forward regeneration, and initial default-config creation.

FIX5 itself is mixed:

- The native-failure retry-ordering fix, early reset tests, real rollback-failure test, and delete-failure tests are substantially implemented.
- The production network-diagnostic redesign in P0-003 is **not reliable**. It replaces an explicit no-op reporter with a lossy `SharedFlow` whose `tryEmit()` result is ignored. This can silently discard the exact diagnostic P0-003 was meant to guarantee.
- P1-002 is incomplete: when `resumeOnUnmetered` is false, `handlePolicyAllowed()` does not invalidate an already pending policy retry.
- The recorded P2 signoff evidence cannot be independently verified from the supplied archive because `.git` metadata and test reports are absent, and this review environment could not download Gradle or run Cargo.

## Scope and validation performed

Static review covered:

- 59 Android production Kotlin files
- 34 Android unit-test files containing 383 `@Test` annotations
- 138 Rust source files containing approximately 532 Rust test annotations
- Android lifecycle, configuration, identity, network-policy, import/export, forwards, reset, JNI, and status-mapping paths
- Rust workspace architecture, crypto/signaling boundaries, mobile FFI/runtime, status handling, and multiplexing error patterns
- Every task and named subtask in the supplied FIX5 TODO

Validation performed successfully:

- ZIP integrity: pass
- Uploaded FIX5 TODO exactly matches the copy in the repository: pass
- Shell script syntax (`bash -n`): pass
- systemd unit structural validation: pass; only expected missing-installed-binary messages occurred
- launchd validation: not run because this is not macOS

Validation not run:

- Android Gradle tests/lint/build: Gradle 8.7 was not cached and the isolated environment could not reach `services.gradle.org`
- Rust tests/clippy/fmt: `cargo` was not installed
- Commit SHA verification: archive contains no `.git` directory
- GitHub Actions verification: no workflow URL, run ID, head SHA, or downloaded test artifacts were included

Accordingly, test references below mean that the test source exists and was inspected; they do not mean I re-executed it in this environment.

---

# What is good

## 1. Lifecycle ordering is materially better than a coroutine-per-command design

`TunnelLifecycleCoordinator` uses an unlimited FIFO channel, and callers now enqueue inline with `trySubmit()` rather than launching an independent coroutine for each command. That removes a real reordering race in which a later STOP could overtake an earlier START.

Relevant code:

- `android/app/src/main/java/com/phillipchin/webrtctunnel/data/TunnelLifecycleCoordinator.kt:23-68`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt:275-292`

The late-command behavior during teardown is explicit: `trySubmit()` returns false instead of throwing `ClosedSendChannelException` from a detached coroutine.

## 2. Native start and stop are verified instead of trusting JNI return codes

`TunnelRepository.start()` and `stop()` perform a post-operation status refresh and require an appropriate final native state. This is much better than treating a zero JNI return code as proof that the native runtime is really running or stopped.

Relevant code:

- `TunnelRepository.kt:71-151`

## 3. Generation tokens and cancellation/join are used to prevent stale state commits

The foreground service increments a lifecycle generation, checks it after expensive work, and cancels plus joins startup/status jobs before authoritative stop or pause transitions. That is the correct general model for preventing stale asynchronous work from resurrecting old state.

## 4. The pending retry is consumed atomically in the NativeFailure path

`pendingPolicyResumeGeneration.getAndSet(null)` gives the intended exactly-once consumption behavior, and the retry additionally requires `pausedByPolicy` to still be true.

Relevant code:

- `NativeFailureAfterStartupContext.kt:28-37`
- `TunnelForegroundService.kt:607-669`

## 5. Runtime uncertainty is modeled explicitly

`nativeRuntimeUncertain` and `nativeStopVerified` prevent optimistic restart after an unverified stop. Explicit quarantine is substantially safer than assuming the native process stopped merely because a cleanup call was attempted.

## 6. Unknown native enum/schema values fail closed

Unknown native modes and listen states are mapped to explicit schema/error states rather than silently defaulting to a plausible-looking value.

Relevant code:

- `TunnelRepository.kt:350-378`
- `TunnelRepository.kt:383-434`

## 7. Persistent forwards use save-before-publish semantics

`ForwardsRepository` updates its in-memory `StateFlow` only after persistence succeeds, uses a mutex, and uses revisioned mutation receipts to avoid rolling back over a newer mutation.

Relevant code:

- `ForwardsRepository.kt:33-202`

The cancellation handling is flawed in these methods, discussed below, but the consistency architecture itself is good.

## 8. Atomic replacement is used for config and forwards files

Both stores write a same-directory temporary file and move it over the destination, using `ATOMIC_MOVE` where available. Falling back to a non-atomic replace when the filesystem does not support atomic moves is reasonable when it is visible and the caller receives failures.

## 9. Identity plaintext is often wiped deliberately

The code uses `finally` and `fill(0)` in many paths, and `IdentityRepository.usePrivateIdentityPlaintext()` provides a useful ownership helper. The FIX5-adjacent tests use sentinels to verify wiping rather than merely asserting that a method was called.

## 10. Android exposure defaults are sensible

- `android:allowBackup="false"`
- `android:fullBackupContent="false"`
- foreground service is not exported
- no broad storage permission
- native libraries are required before packaging

## 11. Rust has strong baseline engineering controls

The workspace denies `unwrap_used`, `todo`, and `dbg_macro`; unsafe code is forbidden except at the JNI/FFI boundary where it is explicitly allowed. The mobile boundary uses panic containment and converts many FFI failures into explicit status/error payloads. Crypto/signaling code includes authorization, AEAD, signing, freshness, and replay checks.

---

# Findings

## Release blocker 1 — Config write failures are silently reported as success

### Affected paths

1. `SetupSaveController.persistConfig()`
   - `SetupSaveController.kt:173-195`
   - `writeConfigAtomically(candidate)` at line 179 returns `Result<Unit>` and is ignored.

2. Config import
   - `ImportExportService.kt:30-56`
   - `writeConfigAtomically(candidate)` at line 52 is ignored.

3. Forward regeneration
   - `ForwardsViewModel.kt:175-221`
   - line 213 ignores the write result and then returns the prior successful validation result.

4. Initial default config
   - `ConfigRepository.kt:68-75`
   - `ensureDefaultConfig()` ignores the write result.

### Impact

- Setup can show **“Configuration saved”** even though `config.toml` was not updated.
- Config import can show **“Config imported”** even though the imported config was not persisted.
- Forward editing can show **“Forward saved”** while `forwards.json` changed but active `config.toml` did not, leaving the UI and runtime configuration divergent.
- Application startup can continue with no config after silently failing to create the default.

This is exactly the class of quiet failure that later creates confusing, state-dependent bugs.

### Required fix

Every mutation call returning `Result` must be consumed explicitly:

```kotlin
configRepository.writeConfigAtomically(candidate).getOrThrow()
```

For forward regeneration, convert write failure into `ValidationResult(false, redactedMessage)`. Add focused tests that inject `Result.failure(IOException("disk full"))` and assert:

- no success message;
- no subsequent state mutation that assumes config persistence;
- the exact visible failure code/message;
- cancellation still propagates.

A lint rule or code-review rule should prohibit discarded Kotlin `Result` values from repository mutation methods.

---

## Release blocker 2 — P0-003’s diagnostic bus silently loses required errors

### Affected code

- `DiagnosticEventBus.kt:29-35`
- `NetworkPolicyManager.kt:23-34`
- `TunnelForegroundService.kt:199-207`
- `AppDependenciesNetworkPolicyWiringTest.kt:23-50`

`AppDiagnosticEventBus` uses:

```kotlin
MutableSharedFlow<DiagnosticEvent>(extraBufferCapacity = 64)
```

with replay left at zero. It then calls:

```kotlin
_events.tryEmit(event)
```

and discards the Boolean result.

### Why this is unsafe

- With no active subscriber and `replay = 0`, an emission is lost. `tryEmit()` may still report success because there is nobody to receive it.
- With a slow subscriber and a full extra buffer, `tryEmit()` returns false. The code ignores that failure.
- The service collector is started asynchronously in `onCreate()`. A diagnostic emitted before the collector registers is lost.
- Each `NetworkPolicyManager` owns a private bus, so the diagnostic has no durable app-level owner outside the service lifetime.

The comment claiming failures are “never silently discarded” is therefore incorrect.

### Test problem

`AppDependenciesNetworkPolicyWiringTest` explicitly uses `Dispatchers.Unconfined` to force the collector to register before emission because replay is zero. This avoids the production race instead of proving the production behavior.

`NetworkPolicyManagerTest.activeFailedDeliveryReportsDiagnostic()` does not cause a delivery failure or inspect the bus. It only asserts that a generic `RuntimeException` is not classified as an expected channel close.

The expected-close tests likewise test only the classifier, not that no event is emitted through the actual failure path.

### Required fix

Prefer the original explicit production reporter design. It can be wired without adding another `AppDependencies` constructor argument by constructing `NetworkPolicyManager` inside the composition root with a reporter that closes over the existing app reporter, or by grouping dependency parameters into a configuration object.

At minimum:

- make the diagnostic sink durable for required errors;
- do not use fire-and-forget `tryEmit()` without checking its result;
- provide a secondary visible/log fallback if delivery fails;
- test the real failed-`trySend` path;
- test emission before subscription;
- test buffer saturation;
- test expected close through the same helper.

P0-003 is **not correctly implemented** in the supplied tree.

---

## Release blocker 3 — Setup/save/import operations are not transactional

### Affected code

- `SetupSaveController.kt:91-196`
- `SetupSaveController.kt:250-283`
- `IdentityRepository.kt:21-27`
- `IdentityRepository.kt:63-83`

The setup flow can mutate these resources in sequence:

1. import and persist private identity;
2. append remote authorized key;
3. write `config.toml`;
4. write `setup_input.json`;
5. write DataStore preferences.

There is no snapshot/rollback around this sequence. A later failure leaves earlier changes committed. Examples:

- private identity is replaced, then config validation or preference persistence fails;
- authorized remote key is appended, then config write fails;
- config writes, then setup-input write fails;
- config and setup input write, then preference persistence fails.

The current error UI reports the save as failed but does not explain which mutations already committed.

### Required fix

Create a setup-save coordinator with explicit snapshot, staged writes, rollback, and per-stage results, similar in spirit to `TransactionalResetCoordinator`. At minimum, validate everything before the first persistent mutation and order operations so the authoritative config commit is last. Identity/public-key replacement needs its own atomic snapshot/restore semantics.

---

## High 1 — P1-002 leaves a stale pending auto-resume when the preference is false

### Affected code

`TunnelForegroundService.kt:558-589`

The method invalidates on guard failure and preference-read failure. It invalidates or resumes when `resumeOnUnmetered` is true. It does nothing when the preference is false.

A real race is possible:

1. an event records `pendingPolicyResumeGeneration` while auto-resume is enabled;
2. the user disables `resumeOnUnmetered` before the in-flight native start completes;
3. another policy-allowed event reads the new false preference but does not invalidate the old pending retry;
4. the in-flight start fails;
5. `NativeFailure` consumes the still-matching pending generation and resumes despite the user’s new preference.

The FIX5 TODO explicitly required invalidation in the false branch. That subtask was not implemented.

### Additional quiet failure

The quarantine guard uses `requireRuntimeStartAllowed().isFailure` and returns silently. Other start/resume commands publish `native_runtime_quarantined`. Policy-driven resume should also publish a visible quarantine diagnostic.

### Required fix

```kotlin
if (!prefs.resumeOnUnmetered) {
    invalidatePendingPolicyRetry()
    return
}
```

Preserve and publish the quarantine error rather than testing only `isFailure`.

Add tests for:

- pending retry + preference flips to false before native failure;
- policy allowed while runtime is quarantined;
- no native restart and pending state cleared.

---

## High 2 — Cancellation is still swallowed in persistent mutation paths

### Affected code

- `ForwardsRepository.kt:88-118`
- `ForwardsRepository.kt:124-143`
- `ForwardsRepository.kt:151-167`
- `ForwardsRepository.kt:174-184`
- `ForwardsRepository.kt:193-201`
- `SetupSaveController.kt:91-150,173-195,250-297`
- `ImportExportViewModel.kt:142-156`
- `ForwardsViewModel.kt:192-221`

These suspend paths use `runCatching`, which catches `CancellationException` and converts it into a normal failure value. That can cause a cancelled reset/save/import to continue with rollback, state publication, or user messages rather than terminating promptly.

P1-001 correctly fixed cancellation for setup-input reset and rollback, but the neighboring config/forwards mutation machinery still has the same class of bug.

### Required fix

Use explicit `try/catch` in suspend mutation code:

```kotlin
try {
    // mutation
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
```

Do not catch raw `Throwable` for ordinary recoverable storage failures.

---

## High 3 — Stale remote peer identity can be presented as current

### Affected code

- `Models.kt:116-118` says `remotePeerId` is null when no session is active.
- `TunnelRepository.kt:400-423` uses:

```kotlin
remotePeerId = remotePeerId ?: previous.remotePeerId
```

If native status transitions from an active session to `running` with `activeSessionCount == 0` and no `remote_peer_id`, a prior peer remains displayed in non-terminal `Listening`, `Serving`, or reconnecting state.

### Impact

The UI can claim that the tunnel is currently associated with an old peer when no session exists. This is a truthfulness and operator-diagnostics problem.

### Required fix

Set the current peer directly from native status. Preserve historical peer identity only in a separately named field such as `lastRemotePeerId`. Add a test for active session -> zero sessions while the runtime remains running.

---

## High 4 — Network monitor failures outside the collection lambda can kill monitoring silently

### Affected code

`TunnelForegroundService.kt:211-244`

The `runCatching` wraps only the body of `collect`. It does not catch failures from:

- `NetworkPolicyManager.monitor()` setup;
- `registerNetworkCallback()`;
- callback-flow upstream execution;
- `unregisterNetworkCallback()`;
- classifier/evaluation invoked directly from Android network callbacks.

An uncaught exception in the `serviceScope` child can terminate the monitor. Because the scope uses supervision, the service may continue running without future policy events.

### Required fix

Wrap the whole collection or use `Flow.catch`, rethrow cancellation, publish a redacted visible diagnostic, and define an explicit retry/backoff policy. Catch classifier failures inside callback methods so an Android callback thread is not allowed to throw arbitrary application exceptions.

---

## High 5 — App initialization blocks the main thread and silently ignores default-config failure

### Affected code

- `WebRtcTunnelApplication.kt:13-20`
- `ConfigRepository.kt:68-75`

`Application.onCreate()` uses `runBlocking` for file I/O. This can delay process startup and contributes to ANR risk on slow storage.

The existence check also occurs outside `writeMutex`, so the comment claiming first-write serialization is incomplete: another writer can create the config after the check and before the lock, after which the default writer may overwrite it.

### Required fix

Put the existence check and write under the same mutex, return/throw a failure, and initialize asynchronously with a visible readiness/error state or perform a minimal synchronous operation whose failure aborts startup explicitly.

---

## Medium 1 — Transactional reset still has uncontained failure paths and raw diagnostics

### Affected code

`TransactionalReset.kt:90-268`

Problems outside the narrow FIX5 setup-input changes:

- `captureSnapshot()` does not catch exceptions from `configFileExists`, `readConfig()`, or `forwardsRepository.current()`.
- Config and forwards reset/rollback reasons use raw `error.message` rather than `SensitiveDataRedactor`.
- `rollbackFromSnapshot().map` can abort all remaining rollback stages if a restore method unexpectedly throws.
- The failing stage is not rolled back. This assumes every stage is perfectly all-or-nothing; that contract is not enforced by the coordinator.
- `SettingsViewModel` logs and shows the composed reset failure string directly.

### Required fix

Make snapshot and each rollback stage cancellation-aware and exception-contained. Redact every persisted/visible reason. Continue attempting independent rollback stages after a non-cancellation failure and record all outcomes.

---

## Medium 2 — Fixed temporary filenames and non-atomic busy guards permit concurrent-operation races

### Affected code

- `SetupSaveController.kt:71-89,285-297`
- `ImportExportService.kt:30-56`
- `ForwardsViewModel.kt:191-220`
- `ImportExportViewModel.kt:142-156`
- `SetupIdentityController.kt:188-196`

Fixed cache filenames include:

- `config-candidate.toml`
- `config-import-candidate.toml`
- `config-forwards-candidate.toml`

Several UI busy checks occur before launching a coroutine. Two rapid invocations can both see `false` and start. `SetupSaveController` does not set `isBusy` at all.

Concurrent validation can overwrite/delete another operation’s candidate file or commit stale state.

### Required fix

Use a `Mutex` or atomic compare-and-set around the complete operation and use `File.createTempFile()` for every candidate. Tests should launch two operations concurrently and prove only one mutation commits.

---

## Medium 3 — Identity storage is not atomic and authorized-key updates can lose concurrent writes

### Affected code

`IdentityRepository.kt:21-27,63-83`

- encrypted private identity and public identity are written as two independent direct writes;
- failure between them leaves a mismatched pair;
- authorized-key append is an unlocked read-modify-write of the entire file;
- concurrent appends can overwrite one another;
- writes do not use temporary files plus replacement.

### Required fix

Use a repository mutex and atomic file replacement. Treat the private/public pair as one transaction with rollback or a versioned container. Use restrictive file permissions where practical and test interruption between pair writes.

---

## Medium 4 — Atomic config writer’s cleanup can escape its `Result` contract

### Affected code

`ConfigRepository.kt:204-243`

The main operation is converted to `Result.failure`, but `Files.deleteIfExists(temp)` runs in `finally` without its own catch. A cleanup `IOException` can escape and replace the intended success/failure result.

### Required fix

Catch cleanup failure separately. On an existing primary failure, attach cleanup failure as suppressed or report it independently; on success, return a clear cleanup failure rather than throwing outside the declared `Result` behavior.

---

## Medium 5 — Lifecycle coordinator can become a dead, still-accepting queue after cancellation

### Affected code

- `TunnelLifecycleCoordinator.kt:23-103`
- `TunnelLifecycleCoordinatorTest.kt:123-149`

Any handler `CancellationException` kills the processor. The channel remains open, so `trySubmit()` can continue returning true while no consumer exists. The test explicitly documents this as the expected state.

That is dangerous if a dependency throws `CancellationException` for an operation-specific reason rather than because the service is shutting down: subsequent STOP/PAUSE commands can be accepted and never executed.

The generic catch also catches fatal JVM `Error` types and attempts to continue as though they were normal handler failures. If `onError()` itself throws, the processor still dies.

### Required fix

Close the channel and mark the coordinator stopped when the processor exits. Distinguish scope cancellation from unexpected operation cancellation where possible. Catch recoverable `Exception`, not arbitrary `Throwable`, and guard error publication.

---

## Medium 6 — `onDestroy()` cleanup is best-effort but is treated as authoritative

### Affected code

`TunnelForegroundService.kt:298-343`

Cleanup is launched asynchronously, foreground status is removed immediately, `super.onDestroy()` is called, and only afterward does `invokeOnCompletion` cancel the scope. Android may terminate the process before this coroutine completes.

The explicit STOP path should remain the authoritative state transition. Destroy-time cleanup should be documented and surfaced as best effort, not relied upon for state integrity.

---

## Medium 7 — The redactor does not cover common structured secret formats

### Affected code

`SensitiveDataRedactor.kt:7-36`

The tests cover many useful forms, but examples that can bypass the current field regex include:

- `broker_password=secret` because the underscore before `password` prevents the leading word boundary from matching;
- JSON such as `{"password":"secret"}` because the quoted key is not followed directly by `:`;
- Basic-auth headers;
- arbitrary private identity TOML fields not named exactly `sign.private` or `kex.private`.

Therefore, “redacted Throwable message” is not a proof that arbitrary exception text is safe.

### Required fix

Add structured JSON/TOML/URI redaction where possible and expand regression tests with actual messages produced by config, MQTT, DataStore, JNI, and file-I/O failures. Prefer fixed diagnostic messages plus non-secret error codes over exposing arbitrary exception text.

---

## Medium 8 — Required user-visible errors sometimes rely on a deliberately lossy snackbar bus

### Affected code

`SnackbarController.kt:8-22`

The controller has replay zero, drops oldest messages during bursts, and ignores `tryEmit()` result. This is acceptable for optional transient UX hints, but some persistence failures are only shown through this channel. A required error can disappear when no collector is active or under a burst.

Required mutation failures should also be held in durable screen/application state or a diagnostic record.

---

## Low/Test quality 1 — Several “exactly once/no extra action” tests use elapsed sleeps

Examples:

- `TunnelForegroundServiceOrderingTest.kt:290-293`
- `TunnelForegroundServiceOrderingTest.kt:313-318`
- `TunnelForegroundServiceOrderingTest.kt:364-369`
- `TunnelForegroundServiceStopFailureTest.kt:462-476`

These tests use 200-300 ms sleeps to prove absence. They can pass before a delayed bug fires or become flaky on slow CI. The test utilities already mention event-driven alternatives; use latches, fake-scheduler advancement, channel barriers, or explicit queue-drained signals.

## Low/Test quality 2 — The destroy pending-retry test does not directly prove pending state existed

`pendingRetryThenDestroyDoesNotRestart()` exercises a credible race and asserts native start count, so it is far better than `assertTrue(true)`. However, because pending state is not observable, the test also passes if the second event never recorded a pending retry. Add a narrow read-only test hook or observable event specifically for this invariant.

## Low/Rust 1 — Wall-clock failure behavior is inconsistent

- Android mobile runtime timestamp falls back to zero if system time is before the Unix epoch: `p2p-mobile/src/runtime/state.rs:127-132`.
- Daemon message timestamp panics: `p2p-daemon/src/messages.rs:90-94`.

This is unlikely on normal devices, but the same condition should not be silently falsified in one component and panic another. Use a fallible timestamp function and surface a controlled error.

---

# FIX5 task-by-task audit

Legend:

- **PASS** — requested code and meaningful focused tests are present.
- **PARTIAL** — core change exists, but a required branch/proof is missing or an adjacent implementation defeats the guarantee.
- **FAIL** — implementation does not provide the requested production guarantee.
- **UNVERIFIED** — documentary claim cannot be proven from this archive/environment.

## P0-001 — Fix NativeFailure pending retry consumption order

**Status: PASS, with cross-path caveat**

### Code

- No unconditional pending-retry invalidation occurs before NativeFailure reads it.
- Non-NativeFailure outcomes invalidate before branch handling.
- NativeFailure calls `getAndSet(null)` first and requires matching generation plus `pausedByPolicy`.

### Tests inspected

- `nativeFailureConsumesPendingPolicyRetryAndResumesExactlyOnce`
- `nativeFailureWithoutPendingRetryPublishesFailure`
- `nativeFailurePendingRetryWithoutPausedByPolicyDoesNotResume`
- `failedAutoResumeLeavesPausedByPolicyTrueForNextRetry`

### Caveats

- Exact-once absence proof uses `Thread.sleep(200)`.
- P1-002’s missing false-preference invalidation creates a separate stale-retry route that can still violate user policy.

## P0-002 — Replace meaningless destroy pending-retry test

**Status: PASS, proof could be stronger**

- No literal `assertTrue(true)` remains in the target test.
- Native start count is asserted before/after destroy.
- Late triggers are shown unable to restart after teardown.
- The test uses a real service race rather than a synthetic always-true assertion.

Caveat: it does not directly observe that the pending generation was actually stored.

## P0-003 — Wire real network event delivery reporter in production

**Status: FAIL**

- The no-op reporter class was removed structurally.
- However, the replacement bus can silently discard events with no subscriber or a full buffer.
- `tryEmit()` result is ignored.
- Service subscription is asynchronous and service-lifetime-bound.
- The claimed active-delivery-failure test does not exercise actual delivery failure.
- The wiring test intentionally registers synchronously to avoid the production race.

This does not satisfy the production visibility requirement.

## P0-004 — Fix network event delivery redaction

**Status: PARTIAL/PASS at helper level; end-to-end proof incomplete**

### Implemented correctly

- Raw `Throwable` is not passed into `Log.w` on this path.
- Cause message is converted to a redacted string.
- Basic password/token/api-key secret tests assert the original value is absent.

### Missing proof

- Expected-close tests exercise only `isExpectedChannelClose`, not the actual emission/report path.
- Required diagnostic delivery can still be lost by P0-003’s bus.
- Redaction does not cover all structured formats.

## P1-001 — Remove `runCatching` from TransactionalReset setup paths

**Status: PASS for the named paths**

- setup-input reset uses explicit try/catch;
- setup-input rollback uses explicit try/catch;
- cancellation is rethrown;
- reset and rollback failures become typed stage failures;
- all four requested tests exist and exercise the intended calls.

Caveat: ForwardsRepository and other neighboring suspend mutation paths still swallow cancellation with `runCatching`.

## P1-002 — Publish visible error for `handlePolicyAllowed` preference-read failure

**Status: PARTIAL**

### Implemented

- preference-read failure invalidates pending retry;
- diagnostic code `policy_allowed_preference_read_failed` is published;
- cancellation is rethrown rather than converted to that diagnostic;
- failure test asserts no native resume.

### Missing/incorrect

- `resumeOnUnmetered == false` does not invalidate pending retry, despite the TODO’s required code shape.
- runtime quarantine is silently ignored in this path.
- the cancellation test proves no failure diagnostic and no restart, but not directly that the exact cancellation propagated through the full processor lifecycle.

## P1-003 — Catch unexpected lifecycle command exceptions visibly

**Status: PASS per the requested behavior, with architectural caveat**

- unexpected handler exception publishes `lifecycle_command_failed`;
- later command is processed;
- cancellation is not converted into a normal diagnostic;
- named tests are meaningful.

Caveat: after cancellation, the open channel still accepts commands with no consumer, and catching raw `Throwable` includes fatal JVM errors.

## P1-004 — Add true reset early-stage tests

**Status: PASS**

- Config-stage failure is injected on the first config write.
- Test asserts setup input is unchanged and forwards save count remains zero.
- SetupInput-stage failure is injected directly.
- Test asserts forwards save count remains zero.

## P1-005 — Add true rollback-failure test

**Status: PASS**

The test creates the required scenario:

1. snapshot succeeds;
2. Config reset succeeds;
3. SetupInput reset throws;
4. Config restore is the second config write and returns failure.

It asserts a `RollbackStageResult.Failure` for `ResetStage.Config`.

## P1-006 — Make delete-failure tests honest

**Status: PASS**

A fake `ConfigRepository` overrides `deleteConfigFileForTransactionalReset()` to return a genuine failure. Tests assert both:

- Config rollback is reported as failure; and
- the config file physically remains after failed deletion.

This is a legitimate fake-operation approach under the TODO’s accepted options.

## P2-001 — Record final signoff evidence

**Status: PARTIAL / UNVERIFIED**

### Present in TODO

- claimed commit SHA;
- focused/full local Gradle results;
- explicit `NOT RUN` reasons for GitHub Actions URL and head SHA.

### Not independently verifiable

- no `.git` metadata in archive;
- no Gradle XML/HTML reports or console logs supplied;
- no GitHub Actions run URL or artifacts;
- this environment could not execute Gradle or Cargo.

The TODO also says one commit contains both FIX5 and two unrelated fixes, which does not satisfy the document’s own “commit one scoped change” discipline.

---

# Recommended repair order

1. **Fix every discarded config-write `Result` and add injected write-failure tests.**
2. **Replace the lossy P0-003 bus with a guaranteed production diagnostic sink and test real delivery failure.**
3. **Invalidate pending retry when auto-resume is false; publish quarantine visibly.**
4. **Make setup save/import transactional, including identity and authorized-key mutations.**
5. **Remove cancellation-swallowing `runCatching` from suspend mutation paths.**
6. **Fix stale `remotePeerId` mapping.**
7. **Harden reset snapshot/rollback containment and redact all reasons.**
8. **Serialize setup/import operations and use unique temp files.**
9. **Make identity files atomic and concurrency-safe.**
10. **Replace sleep-based absence tests and attach actual CI evidence before signoff.**

# Minimum new focused tests

- setup config write failure does not report saved and does not persist later stages;
- config import write failure does not report imported;
- forward regeneration write failure rolls back the forward mutation and does not report saved;
- default config creation failure is visible;
- diagnostic emitted before service subscription is still delivered;
- diagnostic bus saturation cannot silently lose a required event;
- actual failed `ProducerScope.trySend` publishes exactly one redacted diagnostic;
- expected channel close through the same helper publishes none;
- pending retry + `resumeOnUnmetered` changed to false never resumes;
- policy allowed during native quarantine publishes a visible error;
- active peer -> zero sessions clears current `remotePeerId`;
- cancellation during each ForwardsRepository mutation propagates;
- concurrent setup saves cannot overlap or share a candidate file;
- identity private/public pair failure restores the prior pair;
- rollback continues to later independent stages after one rollback failure.

# Final assessment

The project is considerably better engineered than a typical tunnel prototype: it has explicit lifecycle state, verified native transitions, a serious test suite, replay-resistant signaling, and many prior silent-failure paths have been addressed. The remaining defects, however, are concentrated in the exact area this hardening series is trying to guarantee: **truthful persistent state and visible failure reporting**.

The ignored `Result` calls and lossy diagnostic bus are release blockers. FIX5 should remain unsigned until those are corrected and the focused tests plus full Android/Rust CI evidence are rerun against an identifiable commit.
