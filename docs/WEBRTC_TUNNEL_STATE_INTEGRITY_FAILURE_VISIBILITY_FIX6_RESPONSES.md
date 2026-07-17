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

A:

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

A:

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

A:

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

A:

---

## Q5: Fail-closed status helper — reuse existing instead of inventing?

P0-006-A/B reference `NetworkPolicyStatus.blockedUnknown(reason=)` and
`blockedUnknownPolicy(message)`. **Neither exists** — `NetworkPolicyStatus` is a plain
6-field data class with no factories. The TODO hedges ("use an existing constructor/helper
rather than adding `blockedUnknown` if one already fits").

One already fits: `NetworkPolicyManager.evaluate(NetworkType.Unknown to false, allowMetered
= false)` already yields `tunnelAllowed = false`, `blockReason = "Unknown network"` — i.e.
exactly fail-closed. Use that instead of adding a new helper?

A:

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

A:

---

## Q7: Static enforcement mechanism (P2-003 / SPEC §10)

Three options are listed with no decision: (a) `@CheckResult` + Android lint;
(b) a detekt custom rule; (c) a CI script.

Note the TODO's own example script is **inverted**: with `set -euo pipefail`, `rg` exits 1
when it finds *nothing*, so the script **fails when the tree is clean** and **passes when it
finds violations**. It also says "Do not ship a grep that only prints findings and always
exits zero" — its own example does worse. So (c) needs writing from scratch regardless.

Which mechanism do you want?

A:

---

## Q8: Delete `AppDiagnosticEventBus` entirely?

After P0-002, nothing uses it — only `NetworkPolicyManager` and two tests reference it
today, and it was mine, added in FIX5. SPEC §7.5 says remove it from the delivery path;
§11 says it "may remain... prefer removing if unused". P0-002-D says delete if unused.

I would rather **delete it outright** than leave a lossy bus in the tree for someone to
reuse. Confirm?

A:

---

## Q9: P1-008 scope — which ViewModels?

P1-008 says "`ForwardsViewModel.kt`, `ImportExportViewModel.kt`, `SettingsViewModel.kt`,
**other mutating ViewModels**". "Other" is unbounded, so the task can't be verifiably
completed. Which ViewModels exactly are in scope beyond those three (e.g. `SetupViewModel`,
`HomeViewModel`, `LogsViewModel`)?

A:

---

## Q10: Rust P2-002 ripple

Making `p2p-daemon/src/messages.rs`'s timestamp fallible changes function signatures up the
call chain (the workspace also denies `unwrap_used`). Options: (a) propagate `Result`
through callers; (b) contain it at the message-build boundary and surface a controlled
error there. Which?

A:

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

A:

---

## Q12: `ensureDefaultConfig` and P1-003 are effectively one unit

`ensureDefaultConfig` currently returns `Unit`; P0-001-A changes it to `Result<Unit>`. Its
only caller is `WebRtcTunnelApplication.onCreate()`'s `runBlocking` — which P1-003 deletes
and replaces with readiness-gated async init.

Doing P0-001-A alone means writing a `Result` consumer in `onCreate()` that P1-003 then
throws away. Fold P0-001-A and P1-003 into one change?

A:

---

## Q13: Delivery shape

FIX6 is ~20 tasks including two new subsystems (setup transaction, init readiness),
identity atomicity, monitor retry/backoff, and ~90 named tests. FIX5 was a fraction of this
and consumed a full session.

Options: (a) staged — P0-001/P0-002/P0-004 first as the smallest set clearing the release
blockers (two of which I introduced or left behind), then the transaction work, then P1/P2;
(b) one branch, everything; (c) some other split.

A:

---

## Q14 (minor): P1-007's `trySubmit` trips the spec's own rule

P1-007-B's `trySubmit` does `if (stopped.get()) return false` then `commands.trySend(...)`
— a check-then-act, which §5's hard rules forbid ("no check-then-act outside the lock that
protects the act"). It is benign in practice (`trySend` on a closed channel fails anyway,
so the race can only produce a correct `false`). Implement as written, or make it strictly
race-free?

A:

---

Fill in the `A:` lines and share back when ready. No code has been written or changed.
