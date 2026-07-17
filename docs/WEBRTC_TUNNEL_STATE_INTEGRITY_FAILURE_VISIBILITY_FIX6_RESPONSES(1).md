# FIX6 Spec + TODO Responses

Covers `WEBRTC_TUNNEL_STATE_INTEGRITY_FAILURE_VISIBILITY_FIX6_SPEC.md` and
`WEBRTC_TUNNEL_STATE_INTEGRITY_FAILURE_VISIBILITY_FIX6_TODO.md`, from the `/spec-todo`
review of those documents against the tree at `899246c`.

Context worth stating up front: I verified the review's premises against the real code
rather than trusting the documents. All four discarded `writeConfigAtomically()` results
are real (`SetupSaveController.kt:179`, `ImportExportService.kt:52`,
`ForwardsViewModel.kt:213`, `ConfigRepository.kt:72`), `persistConfig`'s `runCatching`
really does convert cancellation into a visible save error, and **FIX6 P0-002's verdict on
my FIX5 diagnostic bus is correct** — it is lossy exactly as described. The questions below
are the decisions I need before writing code, not objections to the work.

Q1 and Q2 are the important ones: they are the same structural pressure that produced the
broken FIX5 P0-003 design, and I would rather agree the approach now than improvise at the
point of failure like last time.


---

## Q1: detekt structural approach for `TunnelForegroundService` (highest priority)

`TunnelForegroundService` currently has **10 functions and detekt flags at 11** (it flagged
me at exactly 11 during FIX5). P0-006-B alone adds `runNetworkMonitor()`,
`onNetworkPolicySignal()`, a `networkPolicyReporter` member and `blockedUnknownPolicy()` →
**12–13 functions → guaranteed `TooManyFunctions` failure.** `LongMethod` (60 lines) and
`ReturnCount` (2) are also live constraints I hit repeatedly in FIX5. CLAUDE.md forbids
suppressions and threshold raises, so this must be solved structurally.

Options:

(a) Extract the monitor into its own class (e.g. `NetworkMonitorSupervisor`) owning the
retry/backoff/fail-closed logic. **My preference** — it mirrors the existing
`UnverifiedStartContext`/`NativeFailureAfterStartupContext` precedent in this codebase and
keeps the service thin.

(b) Extract to top-level functions taking a context object (exactly what
`cleanupUnverifiedStart` / `handleNativeFailureAfterStartup` already do).

(c) Restructure the service some other way you have in mind.

A: **Choose (a): extract a dedicated `NetworkMonitorSupervisor`.**

Required structure:

- `NetworkMonitorSupervisor` owns monitor registration/collection, retry and backoff,
  conversion of monitor failures to a fail-closed status, and direct required-diagnostic
  reporting.
- `TunnelForegroundService` owns only the supervisor job's lifecycle and submission of the
  resulting policy status into the existing lifecycle-command path.
- `NetworkPolicyManager` remains the low-level classifier/`callbackFlow` producer. Callback
  classification failures should be converted to a fail-closed status there; whole-flow
  registration, collection, and restart failures belong in the supervisor.
- Inject the delay/backoff function or a small retry policy so tests use virtual time and
  do not sleep.
- Do not add detekt suppressions, raise thresholds, or move this into a large top-level
  context bag merely to satisfy function counts.

A suitable boundary is:

```kotlin
internal class NetworkMonitorSupervisor(
    private val context: Context,
    private val manager: NetworkPolicyManager,
    private val reporter: NetworkPolicyEventReporter,
    private val retryPolicy: NetworkMonitorRetryPolicy,
    private val onStatus: suspend (NetworkPolicyStatus) -> Unit,
) {
    suspend fun run()
}
```

The exact constructor can follow existing dependency types, but the service must remain
thin and the supervisor must be directly unit-testable.

---

## Q2: Where do the new coordinators live?

`AppDependencies` is at **exactly 6 constructor params and fails at 7** — this is what
derailed FIX5 P0-003. P0-003 adds `SetupPersistenceCoordinator` and P1-003 adds
`AppInitializationCoordinator`.

Note an escape hatch I under-used in FIX5: `transactionalResetCoordinator`,
`forwardsRepository`, and `diagnosticsRepository` are already **body vals, not constructor
params**, so they cost nothing against `LongParameterList`. That likely works for both new
coordinators unless tests must inject fakes.

Options: (a) body vals (my preference); (b) constructor params, restructuring to stay ≤6;
(c) group dependencies into a config object.

A: **Choose (a): expose both as body properties of `AppDependencies`, preferably
thread-safe lazy body properties.** Do not add either coordinator to the
`AppDependencies` constructor.

Example:

```kotlin
val setupPersistenceCoordinator: SetupPersistenceCoordinator by lazy {
    SetupPersistenceCoordinator(
        configRepository = configRepository,
        identityRepository = identityRepository,
        forwardsRepository = forwardsRepository,
    )
}

val appInitializationCoordinator: AppInitializationCoordinator by lazy {
    AppInitializationCoordinator(
        configRepository = configRepository,
        // other existing dependencies only
    )
}
```

Tests should instantiate each coordinator directly with fakes. If a consumer needs a fake
coordinator, inject a narrow coordinator interface into that consumer's constructor rather
than adding another composition-root constructor parameter. Do not introduce a dependency
bundle/config object in FIX6 solely to evade detekt.

---

## Q3: Task ordering — the TODO is not executable as written

Two dependency inversions break the stated "work in priority order / one scoped commit /
don't mark complete until named tests pass" discipline:

- **P0-001-B** says "P0-003 replaces this method... Use this task's test to prove the bug,
  then satisfy it through P0-003." Its four named tests therefore **fail** until P0-003
  lands — a red intermediate commit.
- **P0-001-C**'s target code calls `createCandidateFile()` / `deleteCandidateFileSafely()`,
  which the TODO says "are implemented in P1-005". **A P0 task depends on a P1 task.**

Options: (a) resequence — do P1-005's helpers first, then P0-001-C; fold P0-001-B into
P0-003 as one unit (my preference); (b) keep document order and accept temporarily-failing
tests/commits; (c) `@Ignore` the not-yet-satisfiable tests temporarily (arguably violates
the hard rules).

A: **Choose (a), and rewrite the execution order before implementation.** Every commit
must remain green; do not use `@Ignore`, knowingly failing commits, or placeholder proof
tests.

Required changes to the TODO ordering:

1. Move `createCandidateFile()` and `deleteCandidateFileSafely()` into an explicit
   prerequisite/helper task before P0-001-C. P1-005 must then reuse and extend those
   helpers rather than claiming to introduce them later.
2. Fold P0-001-B's setup false-success tests and implementation into P0-003. They are one
   transaction change and must land together.
3. Fold P0-001-A and P1-003 together as stated in Q12 below.
4. Update cross-references so no task says its tests are expected to remain red until a
   later task.

The hard rule is: a task is not complete and must not be committed until its named focused
tests pass.

---

## Q4: `remotePeerId` — what is the actual native contract?

P1-001/INV-008 require `remotePeerId = remotePeerId.takeIf { activeSessionCount > 0 }`.
That is only correct if native never reports a peer *before* `activeSessionCount`
increments. The TODO itself admits the uncertainty ("If native status may report a remote
peer before session count increments, document and test the intended contract").

If native *can* report the peer first, this change trades a stale-peer bug for a
peer-flicker bug during session setup.

Do you know the contract, or should I read the Rust status-emitting path
(`p2p-mobile`/`p2p-daemon` status serialization) to establish it before implementing?

A: I read the Rust status path. **The native contract guarantees that the peer and
session count come from the same status snapshot, so the proposed stale-peer fix is
correct.**

Specifically:

- `p2p-daemon/src/status.rs` computes
  `active_session_count = sessions.len()` in `DaemonStatus::with_sessions()`.
- `p2p-mobile/src/runtime/state.rs` copies `daemon.active_session_count` and derives
  `remote_peer_id` from `daemon.sessions.first()` while holding the same borrowed daemon
  status snapshot.
- Therefore a zero-session native snapshot has no remote peer. Native does not validly
  publish a remote peer before incrementing the session count.

Implement the mapping without any fallback to the previous peer:

```kotlin
remotePeerId =
    remotePeerId.takeIf {
        activeSessionCount > 0
    },
```

Do not use `remotePeerId ?: previous.remotePeerId`. Add tests proving that a non-terminal
`running`/listening status with `activeSessionCount == 0` clears a previously displayed
peer, and that a positive session count preserves the current native peer. A malformed
positive-count/null-peer DTO must never resurrect the previous peer; leave the peer null
rather than fabricating or retaining one.

---

## Q5: Fail-closed status helper — reuse existing instead of inventing?

P0-006-A/B reference `NetworkPolicyStatus.blockedUnknown(reason=)` and
`blockedUnknownPolicy(message)`. **Neither exists** — `NetworkPolicyStatus` is a plain
6-field data class with no factories. The TODO hedges ("use an existing constructor/helper
rather than adding `blockedUnknown` if one already fits").

One already fits: `NetworkPolicyManager.evaluate(NetworkType.Unknown to false, allowMetered
= false)` already yields `tunnelAllowed = false`, `blockReason = "Unknown network"` — i.e.
exactly fail-closed. Use that instead of adding a new helper?

A: **Yes. Reuse the existing evaluator instead of adding `blockedUnknown`.**

Use:

```kotlin
NetworkPolicyManager.evaluate(
    snapshot = NetworkType.Unknown to false,
    allowMetered = false,
)
```

This provides the canonical fail-closed status with `tunnelAllowed = false` and
`blockReason = "Unknown network"`. Publish the separate safe diagnostic code through the
required reporter. Do not copy an exception message into `blockReason`, and do not add a
second factory with subtly different policy semantics.

---

## Q6: `Thread.sleep` scope in P2-001

There are 12 `Thread.sleep` uses across 5 test files, but most are the **polling**
`waitForCondition(...) { Thread.sleep(10) }` helper, not proofs of absence. §5 forbids
"Thread.sleep proof of 'nothing else happened'"; P2-001's acceptance says "no *proof*
sleeps".

Converting the ~4 genuine absence-proofs (the exactly-once retry, destroy pending-retry,
stale-generation, and stop-count tests) is tractable. Rewriting every Robolectric polling
helper to be event-driven is a much larger job.

Which is in scope — just the absence-proofs, or the polling helper too?

A: **Only the genuine absence-proof sleeps are in scope for P2-001.**

Convert the approximately four named tests that sleep after an event and then infer that
nothing else happened: exactly-once retry, destroy pending retry, stale generation, and
stop-count proof. Replace them with virtual-time draining, explicit completion barriers,
channels/deferreds, or observable state/counters.

The bounded `waitForCondition` polling helpers may remain when they wait for a positive
observable condition, have a strict timeout, and fail with a useful message. Rewriting all
Robolectric polling infrastructure is not part of FIX6. Do not add any new raw sleeps, and
do not use a polling helper as disguised proof that an event did not occur.

---

## Q7: Static enforcement mechanism (P2-003 / SPEC §10)

Three options are listed with no decision: (a) `@CheckResult` + Android lint;
(b) a detekt custom rule; (c) a CI script.

Note the TODO's own example script is **inverted**: with `set -euo pipefail`, `rg` exits 1
when it finds *nothing*, so the script **fails when the tree is clean** and **passes when it
finds violations**. It also says "Do not ship a grep that only prints findings and always
exits zero" — its own example does worse. So (c) needs writing from scratch regardless.

Which mechanism do you want?

A: **Use `@CheckResult` plus Android lint as the primary enforcement mechanism.** Do not
ship the regex/`rg` script, and delete the inverted example from the final TODO.

Requirements:

- Annotate every exposed authoritative mutation method returning `Result`, including the
  concrete declarations used at call sites.
- Ensure `lintDebug` and the normal `check`/CI workflow treat ignored-result findings as
  failures.
- First verify with a temporary deliberate bare call that this project's Android lint
  actually flags an ignored Kotlin/suspend result. Remove that deliberate violation after
  recording the proof.
- If built-in lint does not catch the deliberate Kotlin call, the approved fallback is a
  focused custom detekt rule with positive and negative rule tests. Do not silently fall
  back to grep-based syntax guessing.

No detekt threshold increase or suppression is authorized by this decision.

---

## Q8: Delete `AppDiagnosticEventBus` entirely?

After P0-002, nothing uses it — only `NetworkPolicyManager` and two tests reference it
today, and it was mine, added in FIX5. SPEC §7.5 says remove it from the delivery path;
§11 says it "may remain... prefer removing if unused". P0-002-D says delete if unused.

I would rather **delete it outright** than leave a lossy bus in the tree for someone to
reuse. Confirm?

A: **Yes. Delete `AppDiagnosticEventBus` outright.**

After the direct reporter is wired, remove:

- `AppDiagnosticEventBus`;
- `DiagnosticEventReporter`;
- `DiagnosticEvent`, if it has no other users;
- the old bus wiring tests and production imports.

Replace those tests with direct reporter tests that exercise the actual
`trySend`-failure path. Do not leave a known-lossy bus available for future accidental
reuse.

---

## Q9: P1-008 scope — which ViewModels?

P1-008 says "`ForwardsViewModel.kt`, `ImportExportViewModel.kt`, `SettingsViewModel.kt`,
**other mutating ViewModels**". "Other" is unbounded, so the task can't be verifiably
completed. Which ViewModels exactly are in scope beyond those three (e.g. `SetupViewModel`,
`HomeViewModel`, `LogsViewModel`)?

A: Make P1-008's ViewModel scope exact:

1. `ForwardsViewModel`
2. `ImportExportViewModel`
3. `SettingsViewModel`
4. `NetworkPolicyViewModel`

`SettingsViewModel` and `NetworkPolicyViewModel` are the primary missing durable-error
cases. `ForwardsViewModel` and `ImportExportViewModel` already have state that can be
hardened and tested, so keep them in the audit.

Explicitly exclude from P1-008:

- `SetupViewModel`, because `SetupWizardState.errorMessage`/`saveResult` are already durable
  screen state and P0-003 separately changes setup persistence;
- `LogsViewModel`, because it already exposes `message` and `logsError`;
- `HomeViewModel`, because start/stop failures are represented by the durable shared
  `TunnelStatus`/`TunnelError` state.

Controllers used by `SetupViewModel` are covered by P0-003/P1-004 rather than by P1-008.
Do not expand the task to every class containing `viewModelScope.launch`.

---

## Q10: Rust P2-002 ripple

Making `p2p-daemon/src/messages.rs`'s timestamp fallible changes function signatures up the
call chain (the workspace also denies `unwrap_used`). Options: (a) propagate `Result`
through callers; (b) contain it at the message-build boundary and surface a controlled
error there. Which?

A: **Choose (a) for daemon/protocol operations: propagate the fallible timestamp through
the narrow caller chain and convert it to a controlled `DaemonError`.**

Do not skip ACK registration, ACK retry scheduling, or transport-recovery bookkeeping on
a clock failure; silently omitting those operations can break protocol integrity. Add a
specific safe error variant such as `DaemonError::Clock`/`Time`, map
`SystemTimeError` into it, and let existing daemon/session boundaries handle and report the
failure.

The Android mobile log timestamp is different: it is diagnostics-only. There, preserve the
primary runtime error, emit a safe tracing diagnostic for the clock failure, and omit the
optional log entry rather than inventing timestamp zero.

Thus:

- daemon signaling/protocol timing: propagate `Result`;
- optional Android log append: diagnose safely and omit;
- no panic, zero fallback, `unwrap`, or `expect`.

---

## Q11: `Throwable` → `Exception` reversal — scope, and a broken detekt config

FIX5's spec **mandated** `catch (error: Throwable)` in `TransactionalReset`, and I
implemented exactly that. FIX6 §5 now mandates `Exception`, and §P1-002's examples silently
use `Exception`. Same code, opposite instruction, one release apart.

Separately: `app/detekt.yml` configures `TooGenericExceptionCaught.exceptionNames:
[java.lang.Throwable]`, i.e. it claims to flag `Throwable` catches — but it demonstrably
does **not**: my FIX5 `Throwable` catches passed `check` cleanly. So the config is not
enforcing what its comment says.

Two decisions: (a) which sites flip to `Exception` — just the ones FIX6 names
(`TunnelLifecycleCoordinator`, `handlePolicyAllowed`, reset), or every `Throwable` catch in
production? (b) should I investigate/fix the detekt config so the rule actually enforces
this? Per CLAUDE.md a detekt config change needs explicit sign-off.

A: FIX6 supersedes FIX5 on this point. **Recoverable production catches must use
`Exception`; fatal `Error` values must normally escape.**

Scope:

- Audit and replace every explicit production `catch (...: Throwable)` in the Android
  source tree, not only the three examples named in FIX6.
- Always catch and rethrow `CancellationException` first in suspend paths.
- Replace `runCatching` in suspend persistence/orchestration paths where it would swallow
  cancellation, as already required by P0-005.
- A helper parameter typed `Throwable?` for redacting or representing `Result.failure`
  is not itself a catch and may remain.
- Deliberate non-suspending platform boundaries such as native-library loading may retain
  narrowly justified handling of the specific `LinkageError` they expect; do not use a
  blanket `Throwable` catch.

**The detekt configuration change is explicitly approved.** Investigate and correct the
`TooGenericExceptionCaught` configuration for the project's detekt version so a production
`catch (error: Throwable)` is actually reported. Add a small regression proof or fixture,
and keep the change in a scoped commit. Do not raise thresholds or add suppressions.

---

## Q12: `ensureDefaultConfig` and P1-003 are effectively one unit

`ensureDefaultConfig` currently returns `Unit`; P0-001-A changes it to `Result<Unit>`. Its
only caller is `WebRtcTunnelApplication.onCreate()`'s `runBlocking` — which P1-003 deletes
and replaces with readiness-gated async init.

Doing P0-001-A alone means writing a `Result` consumer in `onCreate()` that P1-003 then
throws away. Fold P0-001-A and P1-003 into one change?

A: **Yes. Fold P0-001-A and P1-003 into one green change.**

The combined task must:

1. make `ensureDefaultConfig()` return `Result<Unit>`;
2. remove `runBlocking` from `Application.onCreate()`;
3. introduce explicit initialization readiness;
4. consume the default-config result in the initialization coordinator;
5. gate tunnel start until readiness is `Ready`;
6. expose a durable, redacted initialization failure when readiness is `Failed`.

Do not add an interim `onCreate()` result consumer that is immediately deleted in the next
commit. Name the combined task/commit around truthful application initialization, and run
both repository and readiness/start-gating tests before committing.

---

## Q13: Delivery shape

FIX6 is ~20 tasks including two new subsystems (setup transaction, init readiness),
identity atomicity, monitor retry/backoff, and ~90 named tests. FIX5 was a fraction of this
and consumed a full session.

Options: (a) staged — P0-001/P0-002/P0-004 first as the smallest set clearing the release
blockers (two of which I introduced or left behind), then the transaction work, then P1/P2;
(b) one branch, everything; (c) some other split.

A: **Choose staged delivery on one branch, with green scoped commits and review
checkpoints.** Do not attempt all FIX6 work as one undifferentiated change.

Use this sequence:

**Stage A — truthfulness and direct diagnostics**

- prerequisite unique-candidate/safe-delete helpers needed by P0-001-C;
- cancellation-aware result helper needed by touched suspend mutations;
- combined P0-001-A/P1-003 initialization-readiness change;
- P0-001-C, P0-001-D, and the discarded-result audit;
- P0-002 direct network reporter and deletion of the bus;
- P0-004 stale policy retry/quarantine visibility.

**Stage B — setup transaction**

- P0-003 together with P0-001-B;
- remaining P0-005 cancellation fixes required by the transaction and persistence paths.

**Stage C — network monitor integrity**

- P0-006 using the extracted `NetworkMonitorSupervisor` from Q1.

**Stage D — storage/lifecycle/UI hardening**

- P1 tasks in dependency order, reusing the helpers already introduced.

**Stage E — secondary enforcement and signoff**

- P2 deterministic tests, Rust clock handling, static enforcement, and final evidence.

Run focused tests for every scoped commit and the full relevant Android/Rust checks at each
stage checkpoint. Stage A is only an intermediate review checkpoint; do not call the app
release-ready until all P0 stages are complete.

---

## Q14 (minor): P1-007's `trySubmit` trips the spec's own rule

P1-007-B's `trySubmit` does `if (stopped.get()) return false` then `commands.trySend(...)`
— a check-then-act, which §5's hard rules forbid ("no check-then-act outside the lock that
protects the act"). It is benign in practice (`trySend` on a closed channel fails anyway,
so the race can only produce a correct `false`). Implement as written, or make it strictly
race-free?

A: **Make it strictly free of the redundant check-then-act. Do not implement the
snippet as written.**

The channel's closed state is the single authoritative acceptance gate:

```kotlin
fun trySubmit(command: LifecycleCommand): Boolean =
    commands.trySend(command).isSuccess
```

In processor shutdown/finalization, close the channel before publishing any observational
`stopped` state:

```kotlin
finally {
    commands.close()
    stopped.set(true)
}
```

`Channel.close()` is thread-safe and idempotent. `stopped` may remain for observation or
job-state assertions, but `trySubmit()` must not read it before sending. Tests must prove
that submissions after processor completion or explicit stop return `false`, and that
`stop()` remains idempotent.

---

All questions above are answered. These decisions supersede ambiguous or conflicting
wording in the original FIX6 SPEC/TODO. No application source code was changed while
preparing these responses.
