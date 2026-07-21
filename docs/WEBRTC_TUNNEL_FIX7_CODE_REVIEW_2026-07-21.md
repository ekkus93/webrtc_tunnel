# WebRTC Tunnel FIX7 Code Review

**Reviewed snapshot:** `webrtc_tunnel-master_2607211131.zip`  
**Checklist reviewed:** `WEBRTC_TUNNEL_TRANSACTIONAL_INTEGRITY_RUNTIME_QUARANTINE_FIX7_TODO(1).md`  
**Review date:** 2026-07-21  
**Verdict:** **NO-GO** — FIX7 is not correctly complete in this snapshot.

## 1. Scope and validation limits

This review inspected the Android/Kotlin production code, Android unit-test source, Rust production code, Rust test source, CI workflow, FIX7 spec/responses/TODO, and handoff manifest.

I attempted a focused Android test run:

```text
cd android
./gradlew --no-daemon -PskipRustBuild=true testDebugUnitTest \
  --tests '*ConfigurationMutationCoordinatorTest'
```

It could not start because the Gradle wrapper attempted to download Gradle 8.7 and outbound DNS/network access is unavailable:

```text
java.net.UnknownHostException: services.gradle.org
```

Rust validation could not be run because `cargo`/`rustc` are not installed in the sandbox. The ZIP contains no `.git` directory, so commit SHAs, clean-tree claims, and the exact CI HEAD cannot be independently verified from this archive.

Accordingly:

- “Pass (static)” means the production path appears correct by source inspection.
- “Tests present, not executed” means named test source exists, but it was not run here.
- The TODO's historical CI/E2E claims are documentary evidence only, not independent proof of this exact archive.

## 2. Executive summary

FIX7 contains several strong architectural improvements: a global mutation coordinator, pure config rendering, isolated setup validation, staged setup persistence, `NonCancellable` rollback, per-file identity restore results, runtime quarantine helpers, fail-closed network handling, typed Rust clock errors, and a much broader negative-path test suite.

However, older side paths bypass or undermine those mechanisms. The most important problems are:

1. The setup wizard writes live identity and forwards before final validation/transactional save.
2. Setup and reset can partially mutate `setup_input.json`, report failure, and skip restoring the current stage.
3. Setup snapshots are not byte-exact or captured under the repository's writer lock; the setup-input snapshot can contain a plaintext broker password and is not wiped.
4. Import/forward config writes happen before candidate cleanup; cleanup failure reports failure but leaves the new config committed.
5. Identity cancellation rollback silently treats a failed `File.delete()` as success and fabricates empty bytes for impossible snapshots.
6. Runtime quarantine is service-instance-local, can vanish on service recreation, and one retry path silently discards the guard failure.
7. Broker-secret owner-only permission calls ignore their boolean return values.
8. Rust/JNI still emits a synthetic `unix_ms: 0`, while another double-failure path silently returns an empty log list.
9. Several checked-off tests prove only message visibility, not restoration of authoritative state.

## 3. What is good

### Architecture and transaction design

- `ConfigurationMutationCoordinator` is application-scoped and most setup/import/forward/reset authoritative operations use it.
- `ConfigRepository.renderOfferConfig` is now a pure renderer; broker-secret persistence is separated.
- Final setup validation uses an isolated workspace and passes identity bytes directly to native validation rather than writing plaintext private identity to disk.
- `SetupPersistenceCoordinator` has a clear stage order with config last:
  `Identity -> AuthorizedKeys -> BrokerSecret -> SetupInput -> Preferences -> Config`.
- Ordinary failure and cancellation rollback run under `NonCancellable` and collect per-stage rollback outcomes.
- Reset rollback proceeds in reverse order and continues after individual restore failures.
- Identity triplet restoration returns detailed per-file results and attempts all members.

### Runtime truthfulness and safety

- Stop-like failures are routed through a central quarantine helper before visible reporting.
- Invalid native status paths clear stale peer/session/MQTT truth through a shared mapping helper.
- Lifecycle command submission uses a non-lossy channel and returns a checked Boolean when the processor is gone.
- Network policy classification and monitor failures generally fail closed before reporting.
- Reporter failures are contained in the network-policy layer and cannot defeat fail-closed state transitions.

### Rust runtime

- Correctness-sensitive timestamps now return typed errors instead of panicking or inventing zero.
- Optional mobile diagnostic timestamps use `Option<u64>`/skip semantics in the main runtime paths.
- Offer daemon shutdown result precedence and mobile `Stopped`/`Error` mapping are carefully separated.
- The Rust tests include injected clock failure seams and cooperative shutdown coverage.

### Tests and enforcement

- The test suite is extensive and frequently uses deterministic barriers rather than timing guesses.
- Android CI runs `./gradlew --no-daemon check`, which is materially better than untyped detekt alone.
- Many authoritative result APIs are annotated with `@CheckResult`.
- Redaction is centralized in many ViewModel/service boundaries.

## 4. Critical findings

### CRITICAL-1 — The setup wizard mutates live identity and forwards before final save

**Files:**

- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupIdentityController.kt:76-115,146-173`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupForwardsController.kt:40-77`
- `android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupSaveController.kt:222-260,353-389`

`importIdentityFromUri()` and `generateIdentity()` call `IdentityRepository.storeEncryptedIdentity()` immediately. `SetupForwardsController` immediately changes the shared `ForwardsRepository`. None of these operations owns `ConfigurationMutationCoordinator` admission, and none waits for final Review validation.

Consequences:

- Cancelling/abandoning the wizard can leave a new identity or new forwards committed.
- Setup validation is not truly side-effect-free end to end.
- The final setup transaction may omit the Identity stage because it sees the identity as already stored.
- Wizard forward edits can leave `forwards.json` changed while `config.toml` still contains the previous forwarding configuration.
- These writes can race config import/reset/final setup save.

**Required correction:** Keep generated/imported identity bytes and draft forwards in wizard-owned memory. The only authoritative writes should occur in one final globally admitted setup transaction. Add explicit cancel/abandon tests that byte-compare all live files.

### CRITICAL-2 — Current-stage partial mutations are not rolled back

**Files:**

- `ConfigRepository.kt:174-179`
- `SetupPersistenceCoordinator.kt:143-158,195-226`
- `TransactionalReset.kt:129-163,192-205`

Both setup and reset add a stage to `committed`/`mutatedStages` only after the stage API returns success. That is safe only if every failure/cancellation guarantees zero mutation.

`saveSetupInput()` directly calls `writeText()` and is not atomic. A write can truncate or partially replace the file and then throw. Because the stage never gets added to the rollback list, rollback skips it.

The same structural bug affects any operation that can commit its destination and then report a cleanup/permission failure.

**Required correction:** Either:

- make every stage API transactional and guarantee failure means no destination mutation; or
- add the current/attempted stage to rollback before applying it and make restores idempotent.

`saveSetupInput()` should become a serialized, atomic, `Result<Unit>` mutation.

### CRITICAL-3 — Setup persistence does not capture exact coherent snapshots

**Files:**

- `SetupPersistenceCoordinator.kt:115-126,185-193`
- `ExactFileSnapshot.kt:81-114`
- `ConfigRepository.kt:98-106`

The coordinator stores setup input as `SetupInputSnapshot(contents: String?)`, not `ExactFileSnapshot`, and stores config as separate `configExisted` plus `String` contents. The reads are not performed under `ConfigRepository.writeMutex`.

Problems:

- byte-exact state is not preserved;
- existence and content are separate TOCTOU reads;
- invalid/non-UTF-8 bytes cannot round-trip;
- `snapshot.contents.orEmpty()` fabricates an empty file for an impossible snapshot;
- absent setup input is restored using unchecked `File.delete()`;
- `setup_input.json` can contain the plaintext broker password, but the String snapshot cannot be wiped; `SetupSnapshot.wipeSecrets()` wipes only the broker-secret file snapshot.

This directly contradicts P0-004-B and the secret-snapshot acceptance claim.

**Required correction:** Add repository-owned, lock-protected exact snapshot/restore methods for both `config.toml` and `setup_input.json`. Store both as `ExactFileSnapshot`; wipe setup-input bytes in `finally`.

### CRITICAL-4 — Cleanup failure after config commit leaves authoritative state changed

**Files:**

- `ImportExportService.kt:43-95`
- `ForwardsViewModel.kt:92-129,170-196,292-361`
- `ImportExportServiceTest.kt:162-185`
- `ForwardsViewModelTest.kt:408-437`

Config import validates a candidate, commits `config.toml`, then exits the `withCandidateFile` block. If candidate cleanup fails, the import throws/report fails but does not restore the previous config.

Forward activation has the same sequence. On cleanup failure it reports invalid and rolls back only the forwards receipt. The already-written `config.toml` still contains the new forwards, while `forwards.json` and in-memory forwards return to the old list.

The named tests only assert visible failure and forwards rollback; they do not assert that `config.toml` was restored.

**Required correction:** Snapshot config before write and restore it on every post-write failure, including candidate cleanup. Better, separate candidate lifecycle from commit: cleanup the candidate successfully first, then commit authoritative config last.

### CRITICAL-5 — Identity rollback can silently fail and fabricate data

**File:** `IdentityRepository.kt:287-317,351-367`

`restorePairFile()` uses:

```kotlin
atomicReplace(file, snapshot.bytes ?: ByteArray(0))
```

and, for an absent snapshot:

```kotlin
file.delete()
```

The empty-byte fallback hides a corrupted/impossible snapshot. `File.delete()` returning `false` is ignored, so cancellation rollback can leave a newly-created identity/public file in place and still report successful restoration.

The detailed triplet restore has the same empty-byte fallback, although it uses checked deletion.

**Required correction:** Use `requireNotNull(snapshot.bytes)` and `Files.deleteIfExists()` everywhere. Add a real pair-rollback test where deletion returns/throws failure and verify it is attached to the propagating cancellation.

### CRITICAL-6 — Runtime quarantine is not durable across service recreation

**Files:**

- `TunnelForegroundService.kt:167-192,565-580,650-672`
- `OfferCoordinator.kt:184-198`
- `AppDependencies.kt:70-75`

`nativeRuntimeUncertain` and `nativeStopVerified` are fields on a single `TunnelForegroundService` instance. A recreated service starts with `nativeRuntimeUncertain=false`, while the application-scoped `TunnelRepository` and native bridge may survive in the same process. The start guard checks only the new service instance's Boolean.

Additionally, `handleRetryPolicyResume()` calls `getOrNull()`, clears the pending retry, and returns without publishing the guard failure. That directly violates the checked P0-007-C requirement that a quarantine guard failure be durable/visible.

`onDestroy()` also clears quarantine after best-effort fallback stop, despite P0-007-D stating only verified explicit STOP clears it.

**Required correction:** Put quarantine state in an application-scoped runtime-state owner (or persist it), have every service instance initialize from that owner, and make every guard failure publish a durable failure. Define one explicit recovery operation and use it consistently.

## 5. High-severity findings

### HIGH-1 — Broker-secret owner-only permissions are silently assumed

`BrokerSecretRepository.kt:82-87` ignores all four Boolean results from `setReadable`/`setWritable`. Persistence reports success even if the password remains group/world-readable or unwritable.

Use `Files.setPosixFilePermissions` where supported, or check every Boolean and fail the transaction. Verify resulting permissions, not just setter invocation.

### HIGH-2 — Scoped cleanup does not actually run for fatal `Error`

`MutationHelpers.kt:103-130` captures only `CancellationException` and `Exception` before invoking cleanup. A fatal `Error` escapes before line 119, so secret-bearing candidate/workspace cleanup is skipped. Also, cleanup helpers catch only `IOException`; a thrown `SecurityException` can replace the primary failure/cancellation.

This contradicts the helper's “cleanup always runs” comment and the hard rule that fatal errors propagate without losing cleanup.

### HIGH-3 — Busy responses can name the wrong active operation

`ConfigurationMutationCoordinator.kt:48-52` acquires the mutex and only afterward sets `active`. A competing caller can lose `tryLock()` in that window, read `active == null`, and report its own requested operation as the active operation.

The existing test barriers enter the operation block after `active.set`, so they do not cover this admission-window race.

### HIGH-4 — Setup identity paths contain unsafe fallbacks and unhandled failures

`SetupIdentityController.kt`:

- uses `canonicalPublicIdentity.orEmpty()` and can persist an empty public identity;
- uses `generated.peerId ?: current.input.localPeerId`, silently associating a generated key with a fallback peer ID;
- converts private identity Strings to temporary ByteArrays and never wipes those arrays;
- assigns raw exception/native messages to `errorMessage` without redaction;
- `generateIdentity()`, stored identity loading, and remote validation can throw out of `launchBusy`; the UI merely clears busy state and receives no durable failure;
- multiple identity actions can overlap, making `isBusy` false while another action remains active and allowing stale state writes.

These are not justified fallbacks. Missing canonical identity fields should fail closed.

### HIGH-5 — Reset's “exact config snapshot” is text-derived and reset refuses corrupt drafts

`TransactionalReset.kt:208-244,278-297` creates an `ExactFileSnapshot` from `readConfig().toByteArray()`, not exact file bytes under writer serialization. Restoration converts bytes back through UTF-8 String.

It also calls `loadSetupInputResult().getOrThrow()` before capturing exact bytes. A corrupt setup draft blocks the reset operation that should be able to repair it.

Any snapshot failure is reported as `failedStage = Config`, even if setup-input or forwards capture failed.

### HIGH-6 — Rust diagnostic failure paths still invent zero or hide the error

- `crates/p2p-mobile/src/jni_bridge.rs:203-207` emits a synthetic log entry with `"unix_ms":0` on invalid UTF-8.
- `crates/p2p-mobile/src/c_abi.rs:160-172` returns an empty list when both log retrieval and clock sampling fail, making “diagnostic failure” indistinguishable from “no logs.”
- `crates/p2p-core/tests/no_pre_epoch_panics.rs` checks the removed function name and `duration_since(...).expect/unwrap`, but not literal/synthetic zero timestamps, so it misses the JNI violation.

### HIGH-7 — App initialization can launch twice under concurrent `start()`

`AppInitialization.kt:53-62` launches the job before `compareAndSet`. Two concurrent callers can both begin `initialize()`; the losing job is cancelled only after it has already started. The implementation is sequentially idempotent, not exactly-once under concurrency.

### HIGH-8 — Preference mutations are outside global configuration admission

`SettingsViewModel.savePreferences()` and `NetworkPolicyViewModel.savePreferences()` do not use `ConfigurationMutationCoordinator`, while setup persistence snapshots and later writes preferences. A concurrent preference save can be lost, or a setup rollback can overwrite a newer preference value.

`SetupSaveController` also reads preferences once for render/validation and again for commit, allowing the config and persisted preference stage to derive from different snapshots.

### HIGH-9 — The `runCatching` audit does not satisfy its stated fatal-error guarantee

Production still uses `runCatching` in parser/file/URI/property/native-load paths. `runCatching` catches every `Throwable`, including fatal `Error`s. Comments claiming a synchronous call “cannot swallow a fatal Error” are incorrect.

`RunCatchingInventoryTest` checks only that a marker comment appears within six lines; it does not prove the call is synchronous, non-mutating, non-native, cancellation-safe, or fatal-error-safe.

The `System.loadLibrary` case may intentionally normalize `UnsatisfiedLinkError`, but that exception should be caught explicitly. Other sites should use `try/catch (Exception)`.

### HIGH-10 — Native status refresh can erase durable quarantine truth

`TunnelRepository.refreshStatusResult()` preserves policy-paused state but not `native_runtime_quarantined`. A manual/late native poll can replace the repository's Error/quarantine status with a mapped native state even while the service-local guard remains uncertain—or after that guard was lost on recreation.

### HIGH-11 — Setup ViewModel performs disk I/O during main-thread construction

`SetupViewModel.init` calls `loadStoredSetupInput()` synchronously, and that calls `File.readText()` plus JSON decode. The application-level main-thread startup issue was improved, but opening the setup screen can still block the main thread on disk/parse work.

### HIGH-12 — Some `Result` APIs can still throw ordinary exceptions outside the result

Examples:

- `ConfigRepository.savePreferences()` catches only `IllegalStateException` and `IOException`.
- `deleteConfigFileForTransactionalReset()` and atomic config helpers catch only `IOException` in key places.
- cleanup helpers catch only `IOException`.

Callers often immediately `fold` the returned Result and assume no ordinary exception escapes. An unexpected `SecurityException` or other runtime exception can bypass durable state mapping and, in reset, bypass rollback orchestration.

## 6. Medium findings and maintainability concerns

1. Numerous `mkdirs()` results are ignored. A false return is discovered later through a less precise error, if at all.
2. `ForwardsConfigStore` and several notification/file paths log raw `Throwable`s; throwable messages can include private filesystem paths or unredacted details.
3. `IdentityRepository.readPrivateIdentityPlaintext()` and `readPublicIdentity()` are not synchronized with pair replacement, so a reader can observe pair transition state.
4. `ForwardsConfigStore.saveForwards()` catches only `IOException`; cleanup predicates (`exists`, deletion seam) can throw and replace the primary failure.
5. Setup/identity `launchBusy` uses a Boolean rather than operation ownership/reference counting, so overlapping actions can make busy state untruthful.
6. The TODO's commit-specific signoff cannot be reconstructed from the ZIP because `.git` metadata is absent.
7. The final signoff explicitly contains four unchecked items and records three different CI flakes before the fourth successful attempt; that should be treated as unresolved test-infrastructure risk, not a clean immutable signoff.

## 7. FIX7 task/subtask audit

Legend:

- **PASS (static):** production implementation appears consistent with the subtask.
- **PARTIAL:** substantial implementation exists, but one or more requirements are violated.
- **FAIL:** the central guarantee is not met.
- **PRESENT / NOT RUN:** named tests or CI configuration exist but could not be executed here.
- **NOT VERIFIABLE:** requires Git/CI/emulator history unavailable in this ZIP/sandbox.

### P0-001 — Application-wide configuration mutation coordinator: **PARTIAL**

| Subtask | Verdict | Review |
|---|---|---|
| P0-001-A operation/admission types | PARTIAL | Types and finally release exist; active-operation metadata has an acquisition-window race. |
| P0-001-B AppDependencies body property | PASS (static) | Correct application-scoped lazy property. |
| P0-001-C replace authoritative local admission | FAIL | Final setup/import/normal forwards/reset use it, but setup identity and setup-wizard forwards mutate live state outside it; preference writes also race setup. |
| P0-001-D tests | PRESENT / NOT RUN | Named tests exist, but no test covers the active metadata window or wizard-side bypasses. |

### P0-002 — Exact snapshots and cleanup composition: **PARTIAL / FAIL**

| Subtask | Verdict | Review |
|---|---|---|
| P0-002-A exact file snapshot | PASS (static) for helper | Core helper distinguishes absent/empty. It is not used consistently by setup persistence. |
| P0-002-B checked exact restore | PARTIAL | Core helper is checked; legacy setup restore still uses `orEmpty()` and unchecked `File.delete()`. |
| P0-002-C candidate/workspace scope helper | FAIL | Cleanup is skipped for fatal `Error`; thrown non-IOException cleanup can replace primary; parent-directory creation is unchecked. |
| P0-002-D tests | PRESENT / NOT RUN | Tests exist but do not cover fatal Error cleanup or thrown cleanup callback. |

### P0-003 — Pure rendering and isolated setup validation: **PARTIAL**

| Subtask | Verdict | Review |
|---|---|---|
| P0-003-A pure render | PASS (static) | Renderer has no direct persistence side effects. |
| P0-003-B BrokerSecretRepository | PARTIAL | Central ownership/locking/atomic replacement exist; permission enforcement and mkdir success are not checked. |
| P0-003-C isolated validation workspace | PASS (static) for final-save path | Final Review validation is isolated and does not write plaintext private identity to workspace. |
| P0-003-D setup validation flow | FAIL end to end | Wizard identity generation/URI import and wizard forward edits already mutate live state before this flow. |
| P0-003-E forward render path | PARTIAL | Pure render and missing-secret failure exist; post-commit candidate cleanup failure leaves config inconsistent. |
| P0-003-F tests | PRESENT / NOT RUN | Tests cover final-save workspace but not pre-save wizard mutations or authoritative config restoration after cleanup failure. |

### P0-004 — One setup transaction with cancellation rollback: **FAIL**

| Subtask | Verdict | Review |
|---|---|---|
| P0-004-A request/stage model | PASS (static) | Stage model/order including broker secret is correct. |
| P0-004-B exact snapshot under serialization | FAIL | Setup input/config are String-derived and not captured under repository writer serialization; setup secret snapshot is not wiped. |
| P0-004-C ordinary rollback | PARTIAL | Prior successful stages roll back under `NonCancellable`; current partially/post-commit-failed stage is excluded. |
| P0-004-D cancellation rollback | PARTIAL | Prior stages roll back and cancellation propagates; current stage is excluded unless its API self-recovers. |
| P0-004-E controller mapping | PASS (static) for coordinator results | Success/failure mapping is mostly truthful, but wizard prewrites bypass the transaction. |
| P0-004-F tests | PRESENT / NOT RUN | Broad tests exist; missing byte-exact setup snapshot, partial-write, and post-commit cleanup rollback cases. |

### P0-005 — Exact cancellation-safe reset: **FAIL / PARTIAL**

| Subtask | Verdict | Review |
|---|---|---|
| P0-005-A exact setup-input API | PASS (static) | Exact setup-input snapshot/restore API exists. Forward reset mutation still uses non-atomic `saveSetupInput`. |
| P0-005-B reset snapshot model | PARTIAL | Setup input is exact/wiped; config snapshot is reconstructed from String and not writer-serialized. |
| P0-005-C cancellation rollback | PARTIAL | Prior stages restore under `NonCancellable`; current partial stage can be skipped. |
| P0-005-D Settings mapping | PASS (static) | Durable reset codes and cancellation diagnostic mapping exist. |
| P0-005-E tests | PRESENT / NOT RUN | Extensive tests exist but do not prove partial-write restoration or raw-byte config restoration. |

### P0-006 — Identity rollback and exhaustive restore: **FAIL / PARTIAL**

| Subtask | Verdict | Review |
|---|---|---|
| P0-006-A detailed restore results | PARTIAL | Per-file results/all-three attempt exist; missing bytes are silently replaced with empty arrays. |
| P0-006-B pair rollback after cancellation | FAIL | Both restores are attempted, but absent-file deletion uses unchecked `File.delete()` and can silently fail. |
| P0-006-C preserve rollback causes | PARTIAL | Forward cause and suppressed failures are preserved only when helpers detect the failure. Unchecked deletion is invisible. |
| P0-006-D snapshot coherence | PARTIAL | Snapshot and writers use one lock; ordinary identity readers do not. |
| P0-006-E tests | PRESENT / NOT RUN | Tests exist but do not cover `File.delete() == false` in pair rollback or impossible missing snapshot bytes. |

### P0-007 — Runtime quarantine and unverified-start cleanup: **FAIL / PARTIAL**

| Subtask | Verdict | Review |
|---|---|---|
| P0-007-A central transition | PARTIAL | Central helper is good, but state is service-instance-local rather than durable. |
| P0-007-B all stop-like failures | PASS (static) | Reviewed call sites route failures through quarantine. |
| P0-007-C block all starts/resumes/retries | FAIL | Normal paths guard, but retry guard failure is silently discarded; service recreation resets the guard. |
| P0-007-D only verified explicit STOP clears | FAIL | Destroy fallback success clears quarantine. |
| P0-007-E cleanupUnverifiedStart | PASS (static) | Mandatory cleanup runs under `NonCancellable` and cancellation is preserved. |
| P0-007-F tests | PRESENT / NOT RUN | Tests exist but do not prove cross-service-instance quarantine or durable retry-guard reporting. |

### P0-008 — Cooperative offer stop while Listening: **PASS (static), NOT EXECUTED**

| Subtask | Verdict | Review |
|---|---|---|
| P0-008-A reproduce/root-cause | PASS by source/test presence | The documented deviation is honest; the merge invariant and real mobile overwrite bug are represented. |
| P0-008-B daemon precedence | PASS (static) | Shutdown flag and error precedence appear correct. |
| P0-008-C mobile mapping | PASS (static) | Graceful join does not overwrite an already-recorded Error. |
| P0-008-D tests | PARTIAL / NOT RUN | Rust tests exist; the specifically named Android integration test remains unchecked. |

### P0-009 — Network fail-closed independent of reporter: **PASS (static), NOT EXECUTED**

| Subtask | Verdict | Review |
|---|---|---|
| P0-009-A safe reporter | PASS (static) | Safety-first, Exception-only catch, no recursion. |
| P0-009-B classification order | PASS (static) | Unknown/blocked state is assigned/delivered before reporting. |
| P0-009-C supervisor safety order | PASS (static) | Dead lifecycle processor stops retry and is surfaced. |
| P0-009-D backoff validation | PASS (static) | Constructor validation and overflow/cap logic are present. |
| P0-009-E tests | PRESENT / NOT RUN | Test source exists; one old TODO test name was superseded by construction-no-classification behavior. |

### P0-010 — Rust wall-clock consistency: **PARTIAL / FAIL**

| Subtask | Verdict | Review |
|---|---|---|
| P0-010-A call-site classification | PARTIAL | Main inventory is thoughtful, but JNI/C-ABI synthetic failure paths were missed. |
| P0-010-B remove old zero fallback | PASS for core helper | `resolve_optional_unix_ms` correctly avoids first-use zero. |
| P0-010-C typed codec error | PASS (static) | Correctness-sensitive signaling paths propagate typed errors. |
| P0-010-D daemon retry/message behavior | PASS (static) | Correctness-sensitive paths use fallible time. |
| P0-010-E mobile diagnostics | FAIL | JNI still emits `unix_ms:0`; C-ABI double failure silently emits an empty list. |
| P0-010-F clock seams | PASS by source presence | Injected clock seams exist. |
| P0-010-G tests/static guard | FAIL / PRESENT | Tests exist but static guard does not detect the literal JNI zero timestamp. |

### P1-001 — Import rejection, cleanup, and wiping: **PARTIAL / FAIL**

| Subtask | Verdict | Review |
|---|---|---|
| P1-001-A visible busy | PASS (static) | Import uses global admission and durable busy state. |
| P1-001-B cancellation-safe busy | PASS (static) | Busy is set inside admission and cleared in non-suspending finally. |
| P1-001-C scoped candidate | FAIL semantically | Helper is used, but authoritative config is committed before cleanup succeeds and is not restored. |
| P1-001-D private-byte wiping | PASS for ImportExportService | Canonical imported private bytes are wiped there; setup wizard URI/generation paths still leak temporary arrays. |
| P1-001-E tests | PARTIAL / NOT RUN | Named tests exist; cleanup-after-success tests do not assert config rollback. |

### P1-002 — Clear stale truth and observe processor death: **PASS (static), NOT EXECUTED**

| Subtask | Verdict | Review |
|---|---|---|
| P1-002-A invalid-status clearing | PASS (static) | Shared invalid mapping clears peer/session/MQTT/forward truth. |
| P1-002-B processor completion | PASS (static) | Unexpected processor exit invokes owner callback/quarantine/stop. |
| P1-002-C submission failure | PASS (static) | Active-service failure is consumed and escalated. |
| P1-002-D tests | PRESENT / NOT RUN | Named tests exist. Quarantine durability across recreation remains outside them. |

### P1-003 — Remove main-thread startup I/O: **PARTIAL**

| Subtask | Verdict | Review |
|---|---|---|
| P1-003-A inventory constructor side effects | PASS for AppDependencies scope | Identified app-construction I/O was moved. |
| P1-003-B move work off main | PASS for Application startup | Forwards/network initialization is deferred. SetupViewModel still reads setup file synchronously when screen opens. |
| P1-003-C initialization idempotence | FAIL under concurrency | Sequential calls return one job; concurrent calls can launch two initializers before CAS. |
| P1-003-D tests | PARTIAL / NOT RUN | State tests exist; no concurrent-start exact-once test. |

### P1-004 — Durable network-policy failures and ViewModel boundaries: **PARTIAL**

| Subtask | Verdict | Review |
|---|---|---|
| P1-004-A durable network state | PASS (static) | Failure stored before snackbar; success clears. |
| P1-004-B flow exception handling | PASS (static) | Evaluation failure emits blocked Unknown and flow can continue. |
| P1-004-C boundary redaction | FAIL as repository-wide claim | SetupIdentityController uses raw exception/native messages and has unhandled coroutine exceptions; raw throwable logs remain. |
| P1-004-D tests | PARTIAL / NOT RUN | Shared ViewModel tests do not cover setup identity controller boundaries. |

### P1-005 — Unsafe fallback/temp cleanup/exception audit: **FAIL / PARTIAL**

| Subtask | Verdict | Review |
|---|---|---|
| P1-005-A forward temp deletion | PARTIAL | Composition exists but catches only IOException and has post-commit semantics problems. |
| P1-005-B runCatching audit | FAIL | `runCatching` remains and still catches fatal Error; marker-comment test does not prove safety. |
| P1-005-C snackbar lossiness | PASS (static) | Required operation failures generally have durable state. Setup wizard controller errors are a separate gap. |
| P1-005-D backoff validation | PASS (static) | Implemented in P0-009. |
| P1-005-E tests/static fixtures | PARTIAL / NOT RUN | Tests exist; static runCatching fixture checks comments, not behavior. |

### P2-001 — Test quality: **PARTIAL / FAIL**

| Subtask | Verdict | Review |
|---|---|---|
| P2-001-A remove proof sleeps | PARTIAL | Many sleeps remain inside documented positive convergence helpers; this is acceptable only for those uses. Tests were not executed here. |
| P2-001-B remove misleading claims | FAIL | Import/forward cleanup tests claim failed outcome but do not verify authoritative config restoration; two declared coverage deviations remain. |
| P2-001-C quality rules | PARTIAL | Many good barriers/seams exist, but real reporter callback and late-startup-after-destroy proofs remain absent. |

### P2-002 — Type-aware ignored-result enforcement: **PARTIAL**

| Subtask | Verdict | Review |
|---|---|---|
| P2-002-A annotate authoritative results | PARTIAL | Many Result APIs annotated; Java Boolean outcomes (`delete`, `mkdirs`, permission setters) and some throwing helper contracts remain unchecked. |
| P2-002-B permanent fixtures | PARTIAL | Positive consumed forms exist. The TODO relies on historical temporary violations rather than a committed negative rule fixture, despite asking for one. |
| P2-002-C CI | PASS (static) | CI runs Gradle `check`; lint CheckResult is build-failing. Could not execute here. |

### P2-003 — Final validation and immutable signoff: **NOT SATISFIED / NOT VERIFIABLE**

| Subtask | Verdict | Review |
|---|---|---|
| P2-003-A repository state | NOT VERIFIABLE | ZIP has no `.git`; commit/tree claims cannot be checked. |
| P2-003-B focused Android tests | NOT RUN HERE | Gradle wrapper download failed due unavailable network. |
| P2-003-C full Android validation | NOT RUN HERE | Same limitation. |
| P2-003-D Rust validation | NOT RUN HERE | Rust toolchain unavailable. |
| P2-003-E E2E | INCOMPLETE BY TODO | Metered-to-unmetered is explicitly unchecked; process-kill proof is service-destroy integration, not OS hard-kill E2E. |
| P2-003-F CI | DOCUMENTARY ONLY | URL/SHA claim exists, but exact archive HEAD cannot be verified; run required four attempts after three different flaky failures. |
| P2-003-G final inventories | FAIL as current claim | Current source still contains unsafe `File.delete()`, ignored permission/mkdir results, retained fatal-catching `runCatching`, and a Rust `unix_ms:0` fallback. |

## 8. Tests that should be added or strengthened first

1. `setupWizardIdentityActionsDoNotMutateLiveIdentityBeforeFinalSave`
2. `abandoningSetupWizardRestoresOrNeverChangesIdentityAndForwards`
3. `setupWizardForwardEditDoesNotChangeLiveForwardsOrConfigBeforeCommit`
4. `setupInputPartialWriteFailureRestoresExactPriorBytes`
5. `setupPersistenceConfigPostCommitCleanupFailureRestoresConfigAndEveryPriorStage`
6. `brokerSecretPostCommitPermissionOrCleanupFailureRestoresPriorSecret`
7. `identityCancellationAbsentFileDeleteFailureIsSuppressedAndVisible`
8. `candidateFatalErrorStillRunsCleanupAndPropagatesSameError`
9. `cleanupCallbackThrowPreservesPrimaryFailureOrCancellation`
10. `configImportCleanupFailureRestoresPreviousConfigBytes`
11. `forwardCleanupFailureRestoresPreviousConfigAndForwards`
12. `serviceRecreationWhileQuarantinedStillBlocksNativeStart`
13. `pendingPolicyRetryQuarantineGuardFailureIsDurableAndVisible`
14. `concurrentInitializationStartRunsInitializeExactlyOnce`
15. `jniLogFallbackNeverSerializesZeroTimestamp`
16. `doubleLogAndClockFailureRemainsVisibleWithoutInventedTimestamp`
17. `concurrentPreferenceWriteCannotBeLostBySetupRollback`
18. `configurationBusyAlwaysReportsActualOwnerDuringAdmissionWindow`

## 9. Recommended correction order

1. Remove all setup-wizard pre-commit mutations; make wizard identity/forwards draft-only.
2. Redesign transaction stage accounting so the current attempted stage is restored on any failure/cancellation.
3. Replace setup-input/config String snapshots with repository-locked `ExactFileSnapshot`s and wipe setup-input bytes.
4. Move candidate cleanup before authoritative commit or add exact config rollback around import/forward activation.
5. Fix identity deletion and missing-byte fallbacks.
6. Move runtime quarantine into application-scoped authoritative state and fix silent retry guard handling.
7. Enforce broker-secret permissions and all directory/deletion outcomes.
8. Replace retained `runCatching` with explicit exception types and strengthen static fixtures.
9. Fix Rust diagnostic zero/empty fallbacks and expand the static guard.
10. Add the missing exact negative-path tests, then rerun full Android/Rust/Docker/emulator validation against one Git commit.

## 10. Release recommendation

Do not treat FIX7 as complete and do not build a release from this snapshot. The current code has multiple paths where the UI reports failure or cancellation while authoritative disk/runtime state has changed, exactly the class of silent integrity problem FIX7 was intended to eliminate.
