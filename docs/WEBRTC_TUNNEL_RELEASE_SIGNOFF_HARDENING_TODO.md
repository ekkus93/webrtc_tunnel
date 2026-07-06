# WebRTC Tunnel Release-Signoff Hardening TODO

## 0. Instructions for Claude Code

Implement this TODO against:

```text
webrtc_tunnel-master_2607060947.zip
```

Read first:

```text
WEBRTC_TUNNEL_RELEASE_SIGNOFF_HARDENING_SPEC.md
crates/p2p-daemon/src/types.rs
crates/p2p-daemon/src/signaling.rs
crates/p2p-daemon/src/status.rs
crates/p2p-daemon/src/offer/mod.rs
crates/p2p-daemon/src/answer/mod.rs
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStore.kt
android/app/src/androidTest/java/com/phillipchin/webrtctunnel/TestWebRtcTunnelApplication.kt
android/app/src/androidTest/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceInstrumentationTest.kt
android/app/build.gradle.kts
.github/workflows/ci.yml
```

### Priority scale

```text
P0 = release signoff blocker: false runtime state or required proof not actually executed
P1 = important cleanup that removes latent silent-failure/test-maintenance risk
P2 = future cleanup; do not implement in this pass
```

### Non-negotiable rules

- Preserve the foreground-process architecture.
- Preserve signaling, crypto, identity, and wire protocol.
- Do not reintroduce `sd_notify` readiness.
- Do not add a global shutdown token.
- Do not add another hidden timeout.
- Do not replace deterministic test hooks with sleeps.
- Do not use `watch` as proof that an intermediate state was never emitted.
- Do not claim an `androidTest` test passed when CI ran only `testDebugUnitTest`.
- Do not report failed native stop as Paused or clean Stopped.
- Do not preserve unused lossy storage APIs for hypothetical compatibility.
- Run the focused test for each task before moving on.

---

# P0 tasks

## P0-001 — Make ordinary status suppression observe the shared ShutdownToken centrally

### Files

Modify:

```text
crates/p2p-daemon/src/types.rs
crates/p2p-daemon/src/signaling.rs
crates/p2p-daemon/src/offer/mod.rs
crates/p2p-daemon/src/answer/mod.rs
crates/p2p-daemon/src/tests/runtime_phase.rs
```

### Problem

Current ordinary status gating checks only:

```text
runtime.phase == Running
```

A narrow race remains:

```text
shutdown token requested
        ↓
phase still Running
        ↓
ordinary recovery writes status
        ↓
outer loop later observes shutdown
```

### Required design

Store a clone of the shared shutdown token in `DaemonRuntimeState`.

Recommended shape:

```rust
use crate::ShutdownToken;

pub(crate) struct DaemonRuntimeState {
    pub(crate) mqtt_connected: bool,
    pub(crate) last_transport_failure_at_ms: Option<u64>,
    pub(crate) forward_statuses: Vec<ForwardRuntimeStatus>,
    pub(crate) phase: DaemonRuntimePhase,
    shutdown: ShutdownToken,
}
```

Add constructors:

```rust
impl DaemonRuntimeState {
    pub(crate) fn new_connected() -> Self {
        Self::new_connected_with_shutdown(ShutdownToken::new())
    }

    pub(crate) fn new_connected_with_shutdown(shutdown: ShutdownToken) -> Self {
        Self {
            mqtt_connected: true,
            last_transport_failure_at_ms: None,
            forward_statuses: Vec::new(),
            phase: DaemonRuntimePhase::Starting,
            shutdown,
        }
    }

    pub(crate) fn normal_status_allowed(&self) -> bool {
        matches!(self.phase, DaemonRuntimePhase::Running)
            && !self.shutdown.is_shutdown_requested()
    }
}
```

Small naming adjustments are fine.

Do not expose the token publicly.

### Production wiring

Offer:

```rust
let mut runtime =
    DaemonRuntimeState::new_connected_with_shutdown(shutdown.clone());
```

Answer equivalent.

The token stored in runtime state must be a clone of the **same** token used by the daemon loop.

Forbidden:

```rust
DaemonRuntimeState::new_connected_with_shutdown(ShutdownToken::new())
```

in production daemon startup.

### Central status gate

Change:

```rust
fn runtime_status_allowed(ctx: &RuntimeContext<'_>) -> bool {
    matches!(ctx.runtime.phase, DaemonRuntimePhase::Running)
}
```

into:

```rust
fn runtime_status_allowed(ctx: &RuntimeContext<'_>) -> bool {
    ctx.runtime.normal_status_allowed()
}
```

Do not add separate shutdown checks to every status helper.

### Terminal status rule

Do not route terminal writers through the ordinary gate.

These remain strict and allowed after token request:

```text
write_answer_closed_status
write_offer_closed_status
```

### Focused tests

Add at least:

```rust
#[tokio::test]
async fn running_phase_suppresses_ordinary_status_after_shared_shutdown_request() {
    let config = test_config();
    let shutdown = ShutdownToken::new();
    let mut runtime =
        DaemonRuntimeState::new_connected_with_shutdown(shutdown.clone());
    runtime.phase = DaemonRuntimePhase::Running;

    // Construct test StatusWriter/audit recorder per P0-002.

    shutdown.request_shutdown();
    write_steady_state_status(&ctx).await;

    assert!(audit.snapshot().is_empty());
}
```

Also prove:

```text
Running + uncancelled → ordinary status allowed
Running + cancelled   → ordinary status suppressed
Draining              → suppressed
Closed                → suppressed
```

### Acceptance criteria

- [x] Runtime state can hold a clone of the shared shutdown token.
- [x] Existing `new_connected()` remains available for ordinary tests/helpers.
- [x] Offer production runtime uses the daemon's shared token.
- [x] Answer production runtime uses the daemon's shared token.
- [x] Ordinary status gate checks phase and actual token request.
- [x] Terminal Closed writers remain unaffected.
- [x] Test proves token request suppresses ordinary status before phase changes.

---

## P0-002 — Add a non-coalescing status audit recorder and replace the watch-based shutdown proof

### Files

Modify:

```text
crates/p2p-daemon/src/status.rs
crates/p2p-daemon/src/offer/mod.rs
crates/p2p-daemon/src/tests/runtime_phase.rs
crates/p2p-daemon/src/tests/status_and_recovery.rs
```

Modify the existing shutdown-boundary lifecycle test wherever it currently lives.

### Problem

The existing exact-boundary test observes a `watch::Receiver<DaemonStatus>`.

`watch` can coalesce:

```text
illegal WaitingForLocalClient
Closed
```

into a single observed:

```text
Closed
```

The test can therefore pass while the forbidden intermediate write occurred.

### Keep production watch behavior

Do not replace:

```rust
tokio::sync::watch::Sender<DaemonStatus>
```

for Android/latest-state consumers.

Add a separate test/debug audit mechanism.

### Add StatusAuditLog

Recommended implementation:

```rust
#[cfg(any(test, debug_assertions))]
use std::sync::{Arc, Mutex};

#[cfg(any(test, debug_assertions))]
#[derive(Clone, Default)]
pub struct StatusAuditLog {
    events: Arc<Mutex<Vec<DaemonStatus>>>,
}

#[cfg(any(test, debug_assertions))]
impl StatusAuditLog {
    pub fn len(&self) -> usize {
        self.events
            .lock()
            .expect("status audit log mutex poisoned")
            .len()
    }

    pub fn snapshot(&self) -> Vec<DaemonStatus> {
        self.events
            .lock()
            .expect("status audit log mutex poisoned")
            .clone()
    }

    fn record(&self, status: DaemonStatus) {
        self.events
            .lock()
            .expect("status audit log mutex poisoned")
            .push(status);
    }
}
```

No `.lock().ok()`.

No `unwrap_or_default()`.

A poisoned test recorder must fail loudly.

### Add optional audit field to StatusWriter

Conceptual shape:

```rust
pub struct StatusWriter {
    enabled: bool,
    path: PathBuf,
    sink: Option<tokio::sync::watch::Sender<DaemonStatus>>,

    #[cfg(any(test, debug_assertions))]
    audit: Option<StatusAuditLog>,
}
```

Update all constructors.

Add:

```rust
#[cfg(any(test, debug_assertions))]
pub fn with_audit(config: &AppConfig, audit: StatusAuditLog) -> Self {
    Self {
        enabled: config.health.write_status_file,
        path: config.health.status_file.clone(),
        sink: None,
        audit: Some(audit),
    }
}
```

If needed:

```rust
#[cfg(any(test, debug_assertions))]
pub fn with_sink_and_audit(
    config: &AppConfig,
    sink: tokio::sync::watch::Sender<DaemonStatus>,
    audit: StatusAuditLog,
) -> Self
```

### Record every write attempt

Inside `StatusWriter::write`:

```rust
#[cfg(any(test, debug_assertions))]
if let Some(audit) = &self.audit {
    audit.record(status.clone());
}
```

Record before optional status-file write.

The audit log is a record of emitted status attempts, not a file-system observer.

### Inject audit into offer daemon test

Extend the existing test hooks rather than changing public production API.

Example:

```rust
#[cfg(any(test, debug_assertions))]
#[derive(Default)]
pub(crate) struct OfferDaemonTestHooks {
    pub(crate) session_hook: Option<...>,
    pub(crate) worker_fault_hook: Option<...>,
    pub(crate) loop_top_barrier: Option<OfferLoopTopBarrier>,
    pub(crate) status_audit: Option<StatusAuditLog>,
}
```

Use the audit log when constructing `StatusWriter`.

Update every `OfferDaemonTestHooks { ... }` literal to include:

```rust
status_audit: None,
```

or use:

```rust
..Default::default()
```

where clear.

### Replace shutdown-boundary test

Required pattern:

```rust
let audit = StatusAuditLog::default();

// Start daemon with loop-top barrier + audit.

barrier_entered.wait().await;
let boundary = audit.len();
shutdown.request_shutdown();
barrier_release.release().await;

let result = tokio::time::timeout(TEST_TIMEOUT, daemon_task)
    .await
    .expect("offer daemon should stop")
    .expect("offer daemon task join");
assert!(result.is_ok());

let events = audit.snapshot();
for status in &events[boundary..] {
    assert!(
        !matches!(
            status.current_state,
            DaemonState::WaitingForLocalClient
                | DaemonState::Serving
                | DaemonState::Negotiating
                | DaemonState::TunnelOpen
        ),
        "normal state emitted after shutdown boundary: {:?}",
        status.current_state,
    );
}

assert!(
    events[boundary..]
        .iter()
        .any(|status| status.current_state == DaemonState::Closed),
    "terminal Closed status was not emitted after shutdown",
);
```

Do not use `watch` as the assertion source.

Do not search for the last ordinary state to infer a boundary.

### Regression-strength requirement

Temporarily disable the token-aware status gate from P0-001.

The test must fail.

Restore the fix before committing.

Document that verification in commit notes or the TODO completion note.

### Acceptance criteria

- [x] Production latest-state watch API remains unchanged.
- [x] Test-only audit recorder stores every status write without coalescing.
- [x] Audit mutex failure is loud.
- [x] Offer lifecycle test uses exact request boundary plus audit log.
- [x] Illegal post-request ordinary state fails deterministically.
- [x] Final Closed is still observed.
- [x] Test fails when the token-aware gate is temporarily removed (see note below).

**Regression-strength verification note**: reverting *only* `runtime_status_allowed`'s
shared-token check (back to a phase-only `matches!(ctx.runtime.phase, Running)`) does
**not**, by itself, make `offer_admits_no_ordinary_write_when_shutdown_lands_between_session_outcome_and_loop_top`
fail. The offer run loop has an independent, pre-existing local check —
`if shutdown.is_shutdown_requested() { break Ok(()); }` immediately before the
`write_steady_state_status` call at the loop top (`offer/mod.rs`, predates P0-001) —
that closes the exact same race window on its own. The test only fails when *both*
the P0-001 gate fix and that local check are reverted together (verified directly:
reverting both causes the test to fail with `WaitingForLocalClient` observed after
the shutdown boundary; restoring either one alone makes it pass). The two dedicated
unit tests added in P0-001 (`crates/p2p-daemon/src/tests/runtime_phase.rs`) exercise
`runtime_status_allowed`/`normal_status_allowed` directly against `RuntimeContext`,
bypassing the loop's local checks entirely, and are the tests that isolate and prove
the P0-001 gate fix specifically — those fail on a gate-only revert. This integration
test's proof is broader (no illegal state escapes the full daemon, via either
defense), which is what it was built to guarantee at the P0-002 stage; it is not a
narrow proof of the P0-001 gate function in isolation.

---

## P0-003 — Move critical TunnelForegroundService stop-failure proofs into testDebugUnitTest

### Files

Modify/add:

```text
android/app/build.gradle.kts
android/app/src/androidTest/java/com/phillipchin/webrtctunnel/TestWebRtcTunnelApplication.kt
android/app/src/androidTest/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceInstrumentationTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStopFailureTest.kt
.github/workflows/ci.yml
```

Preferred new shared test source:

```text
android/app/src/sharedTest/java/com/phillipchin/webrtctunnel/TestWebRtcTunnelApplication.kt
```

### Problem

The current truthfulness tests live under:

```text
src/androidTest
```

Required CI runs:

```text
testDebugUnitTest
```

Therefore the tests are not part of the required gate.

### Chosen approach

Port/duplicate the four critical stop-failure scenarios as Robolectric unit tests.

The project already has:

```text
Robolectric
AndroidX test core
unitTests.isIncludeAndroidResources = true
```

Do not add an emulator as a P0 dependency.

### Share test fakes without shipping them

Move the current test application/hooks/recording bridge to a source directory compiled by both `test` and `androidTest`.

Suggested Gradle configuration:

```kotlin
android {
    sourceSets {
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }
}
```

Move:

```text
TestWebRtcTunnelApplication
TestTunnelHooks
RecordingBridge
```

into that shared test directory.

Keep:

```text
TestTunnelRunner.kt
```

under `androidTest`.

Do not place test fakes under `src/main` or `src/debug` unless there is a documented reason and the release artifact is proven not to include them.

### Add focused Robolectric class

Create:

```text
android/app/src/test/java/com/phillipchin/webrtctunnel/
    TunnelForegroundServiceStopFailureTest.kt
```

Recommended annotation shape:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(
    application = TestWebRtcTunnelApplication::class,
    sdk = [35],
)
class TunnelForegroundServiceStopFailureTest {
    // ...
}
```

Use Robolectric/service APIs appropriate to the current code.

The test must execute the actual `TunnelForegroundService` action/coordinator path.

A detached pure function that maps `Result` to a state is not a substitute.

### Required test 1 — pause failure

Sequence:

```text
start offer
wait until RecordingBridge observed start
failNextStop()
send ACTION_PAUSE
wait until stop attempted
assert repository state == Error
assert no later Paused state is published
```

### Required test 2 — policy pause failure

Drive the actual policy pause coordinator path.

Required final assertions:

```text
repository state == Error
pausedByPolicy == false
normal policy-paused status not published
```

If `pausedByPolicy` is private, use a test-only query seam or assert externally observable behavior that proves retry remains possible.

Do not make the field public in production API.

### Required test 3 — stopServiceWork failure

Sequence:

```text
start offer
failNextStop()
send ACTION_STOP
assert stop attempted
assert tunnel repository remains Error
assert no clean Stopped state follows
```

Service self-teardown may still occur.

### Required test 4 — startup cancellation/supersedence failure

Use existing block hooks:

```text
block start or validation
inject stop failure
trigger stop/pause/supersedence
release blocked start
assert failed cleanup is visible
```

### CI focused step

Add before or alongside the full Android step:

```yaml
- name: Run foreground-service stop-failure truthfulness tests
  run: |
    cd android
    ./gradlew --no-daemon testDebugUnitTest \
      --tests '*TunnelForegroundServiceStopFailureTest'
```

Then keep:

```yaml
- name: Build Android app and run unit tests
  run: |
    cd android
    ./gradlew --no-daemon assembleDebug testDebugUnitTest
```

### Acceptance criteria

- [x] Four critical stop-failure scenarios exist under `src/test`.
- [x] They run with Robolectric through `testDebugUnitTest`.
- [x] They exercise the actual foreground service path.
- [x] Shared fakes are not shipped in production source sets.
- [x] Existing instrumentation coverage may remain.
- [x] CI explicitly runs the focused P0 test class.
- [x] Full Android unit-test gate also passes.

Implemented as two classes under `android/app/src/test/java/com/phillipchin/webrtctunnel/`:
`TunnelForegroundServiceStopFailureTest` (policy-pause failure via direct
`offer.pauseForPolicy()`, plus pause/stopServiceWork failure driven through the
real `onStartCommand` → `ServiceController.startCommand(...)` action path with
`Dispatchers.Unconfined`) and `TunnelForegroundServiceStartupCancellationStopFailureTest`
(the startup-cancellation scenario, needing genuine asynchrony — real
`Dispatchers.IO` rather than `Unconfined` — because it blocks a native start on
one thread while driving a second action from the test thread, matching how
`TunnelForegroundServiceInstrumentationTest` proves the same scenario under
real instrumentation). Fakes live in `TunnelForegroundServiceTestFakes.kt`,
`test`-only-scoped per the P0-004 finding above (not shared with `androidTest`).
CI wiring: a new `Run foreground-service stop-failure truthfulness tests` step
in `.github/workflows/ci.yml`, before the full Android build step.

**Significant finding**: building the startup-cancellation test caught a real
production bug, not just a test gap. `runOfferStart`'s two cleanup branches
(cancellation-catch and supersedence-check) called
`withContext(ioDispatcher) { repository.stop() }` from inside a coroutine whose
own Job is already cancelled at that point. kotlinx.coroutines' prompt-
cancellation behavior means that `withContext` rethrows `CancellationException`
immediately without ever running its block in that situation — so this cleanup
`stop()` call was silently never happening, leaving the native tunnel running
behind what looked like a cancelled/superseded startup. Fixed by wrapping both
calls in `withContext(NonCancellable + ioDispatcher) { repository.stop() }`.
Verified via the standard regression-strength check: reverted to plain
`ioDispatcher`, confirmed the new test fails, restored `NonCancellable`,
confirmed it passes.

---

## P0-004 — Make policy-pause failure clear pausedByPolicy unconditionally

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
```

Add/modify tests in:

```text
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStopFailureTest.kt
```

### Problem

Current code stores:

```kotlin
val previousPausedByPolicy = pausedByPolicy
```

and on stop failure restores:

```kotlin
pausedByPolicy = previousPausedByPolicy
```

A stale/reentrant true value can survive a failed stop.

### Required code shape

Prefer:

```kotlin
suspend fun pauseForPolicy(reason: String) {
    lifecycleMutex.withLock {
        lifecycleGeneration += 1
        reporter.stopStatusPolling()
        cancelStartupJobLocked()

        withContext(ioDispatcher) { repository.stop() }
            .fold(
                onSuccess = {
                    pausedByPolicy = true
                    repository.setPolicyBlocked(reason)
                    reporter.publishStatus(reason)
                },
                onFailure = { error ->
                    pausedByPolicy = false
                    reporter.publishError(
                        message =
                            error.message
                                ?: "Failed stopping tunnel after policy block",
                        code = "stop_failed",
                    )
                },
            )
    }
}
```

Do not set `pausedByPolicy = true` before successful stop.

Do not restore a previous true value.

### Required test

Prove stale state cannot survive failure.

Test setup should make the precondition effectively:

```text
pausedByPolicy == true
runtime active/retryable
next stop fails
```

Then invoke real policy pause path.

Assert:

```text
pausedByPolicy == false
repository state == Error
```

If directly forcing the private flag is not practical, add a minimal test-only seam.

Do not expose mutable policy state publicly.

### Acceptance criteria

- [x] Failed policy stop always leaves `pausedByPolicy == false`.
- [x] Successful policy stop sets it true only after stop succeeds.
- [x] Failure does not publish normal policy-paused state.
- [x] Test covers stale true precondition.
- [x] Retry/reevaluation remains possible.

Implemented as `android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStopFailureTest.kt`,
driving the real `TunnelForegroundService.OfferCoordinator.pauseForPolicy()` path
under Robolectric via `ServiceController.of(...)` with `Dispatchers.Unconfined`
(both `offer` and `pausedByPolicy` widened from `private` to `internal` so the
test reads/drives them directly — no new public mutator). Verified the
regression-strength requirement: temporarily reverted to the old
`pausedByPolicy = previousPausedByPolicy` restore, confirmed the test fails,
restored the fix, confirmed it passes again.

**Note for P0-003**: the spec's suggested `src/sharedTest/java` source set
(shared between `test` and `androidTest`) was tried first and reverted — merely
compiling the existing `TestWebRtcTunnelApplication`/`RecordingBridge` into the
`test` source set (even completely unused by any test) made unrelated
Robolectric `viewmodel` tests elsewhere in this module fail nondeterministically
depending on run composition. Root cause not fully pinned down (suspected
Robolectric default-`Application`/manifest resolution interaction with a second
`Application` subclass newly visible on the `test` classpath). P0-004's test
uses a small `test`-only-scoped duplicate instead
(`TunnelForegroundServiceTestFakes.kt`, not shared with `androidTest`). P0-003
should either reuse/extend that duplicate for its remaining 3 scenarios, or
re-investigate the sharedTest approach with this interaction in mind before
relying on it.

---

## P0-005 — Audit every TunnelForegroundService repository.stop() call site

### Files

Modify if needed:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceStopFailureTest.kt
```

### Goal

Every production call to:

```kotlin
repository.stop()
```

must intentionally handle its `Result`.

### Audit command

Run:

```bash
rg -n 'repository\.stop\(\)' \
  android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
```

For every match, document in the task completion note:

```text
call site
success behavior
failure behavior
covering test
```

### Allowed patterns

```kotlin
repository.stop().fold(...)
```

or:

```kotlin
val result = repository.stop()
if (result.isFailure) {
    // explicit error path
}
```

### Forbidden patterns

```kotlin
repository.stop()
```

with ignored result.

Also forbidden:

```kotlin
runCatching { repository.stop() }
```

when the nested `Result` is not inspected.

### Required scenarios

At minimum, ensure tests cover failure at:

```text
pause
policy pause
service stop
startup cancellation/supersedence
```

### Acceptance criteria

- [x] Every production `repository.stop()` call result is handled.
- [x] No nested Result is accidentally discarded.
- [ ] Every failure-capable stop call site has a focused test — 5 of 6 do; the
      remaining gap (call site 3 below, supersedence cleanup) is documented and
      deferred to P0-003, not silently dropped.
- [x] Audit command output is included in implementation notes.

### Audit output and call-site notes

```text
$ rg -n 'repository\.stop\(\)' android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
172:                            repository.stop()
376:                            withContext(ioDispatcher) { repository.stop() }.onFailure {
385:                        withContext(ioDispatcher) { repository.stop() }.onFailure {
436:                    withContext(ioDispatcher) { repository.stop() }
456:                    withContext(ioDispatcher) { repository.stop() }
483:                    val stopResult = withContext(ioDispatcher) { repository.stop() }
```

1. **Line 172 — `onDestroy()` teardown.** Success: falls through, `pausedByPolicy`
   cleared, allowance cleared. Failure: `.onFailure { publishError(...) }`, service
   still tears itself down below. Covering test: none dedicated (not in the
   spec's required-scenarios list); the service is being destroyed regardless of
   outcome so there is no user-visible state to assert beyond the published
   error, which the pattern already matches sites 4/6 below.
2. **Line 376 — `runOfferStart`'s `CancellationException` cleanup.** Success:
   returns silently (startup was cancelled, cleanup succeeded). Failure:
   `.onFailure { publishError(..., "stop_failed") }`. Covering test: added this
   task —
   `TunnelForegroundServiceInstrumentationTest.stopDuringPendingStartWithFailingCleanupStopPublishesError`.
3. **Line 385 — `runOfferStart` supersedence cleanup** (a newer start superseded
   this one after its native start already completed). Success/failure handling
   identical in shape to line 376, and shares the same `NonCancellable` fix
   found and applied in P0-003 (both branches had the identical
   prompt-cancellation bug). Covering test: still a **gap** — no test isolates
   *this specific branch* (as opposed to the cancellation-catch at line 376);
   reproducing it deterministically needs a second blocking hook (pause the
   *second*, superseding start after the first's native call already returned)
   that the current fakes don't have. The branch is exercised by inspection and
   shares its fix's verification with line 376's test, but remains untested in
   isolation.
4. **Line 436 — `pause()`.** Success: publishes the paused status. Failure:
   publishes `stop_failed` error. Covering test:
   `TunnelForegroundServiceInstrumentationTest.pauseWithFailingStopPublishesErrorNotPaused`
   (androidTest only — CI-reachability is P0-003's job).
5. **Line 456 — `pauseForPolicy()`.** Success: `pausedByPolicy = true` +
   `setPolicyBlocked`. Failure: `pausedByPolicy = false` (P0-004) + published
   error. Covering test:
   `TunnelForegroundServiceStopFailureTest.failedPolicyStopForcesPausedByPolicyFalseEvenFromStaleTruePrecondition`
   (`testDebugUnitTest`, in required CI).
6. **Line 483 — `stopServiceWork()`.** Success: "stopped" notification. Failure:
   published error; service still calls `stopForeground`/`stopSelf` below
   regardless. Covering test:
   `TunnelForegroundServiceInstrumentationTest.stopServiceWorkWithFailingStopStillStopsServiceButReportsError`
   (androidTest only).

All six call sites use an allowed pattern (`.onFailure { ... }` or
`.fold(...)`); none discard a `Result`, and none nest an uninspected `Result`
inside `runCatching`. The one open gap (line 385) is narrow, already scoped
into P0-003, and documented above rather than silently left unmentioned.

---

## P0-006 — Run complete release-signoff P0 gates

### Rust

Run:

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo clippy --workspace --release --all-features -- -D warnings
cargo test --workspace --all-targets --all-features
```

### Focused Rust tests

Run the exact tests changed in P0-001 and P0-002 individually.

Examples:

```bash
cargo test -p p2p-daemon running_phase_suppresses_ordinary_status_after_shared_shutdown_request -- --nocapture
cargo test -p p2p-daemon no_normal_status_after_exact_shutdown_boundary -- --nocapture
```

Use actual test names.

### Android focused gate

Run:

```bash
cd android
./gradlew --no-daemon testDebugUnitTest \
  --tests '*TunnelForegroundServiceStopFailureTest'
```

This command must execute at least four tests.

Do not accept:

```text
0 tests executed
```

### Android full gate

Run:

```bash
./gradlew --no-daemon assembleDebug testDebugUnitTest
```

### Service/package regression

Run:

```bash
scripts/check-systemd-units.sh
scripts/check-launchd-plists.sh
scripts/test-debian-package.sh
bash -n scripts/*.sh
sh -n packaging/debian/postinst packaging/debian/prerm packaging/debian/postrm
```

On macOS CI, also run:

```bash
scripts/test-launchd-install-layout.sh
```

### CI verification

Push the implementation branch and inspect the real workflow.

Confirm:

```text
focused foreground-service unit-test step ran
full Android unit-test step ran
Rust jobs ran
required signal lifecycle job still ran
```

### Reporting rule

For every gate:

```text
PASS: command actually executed successfully
FAIL: command executed and failed
NOT RUN: exact reason
```

### Acceptance criteria

- [x] All locally available Rust gates pass.
- [x] Focused non-coalescing shutdown-status test passes.
- [x] Focused Robolectric service truthfulness class executes and passes.
- [x] Full Android assemble + unit tests pass.
- [x] Service/package regression checks pass.
- [ ] Real CI executes the focused Android class — **NOT RUN**: not pushed to a
      remote in this session (commit/push discipline requires explicit user
      request; CI cannot be observed without pushing). The step is wired in
      `.github/workflows/ci.yml` and its exact command was run locally
      (reported PASS below) — that is the strongest verification available
      without pushing.
- [x] No unavailable check is reported as PASS.

### P0-006 gate report

```text
PASS: cargo fmt --all --check
PASS: cargo clippy --workspace --all-targets --all-features -- -D warnings
PASS: cargo clippy --workspace --release --all-features -- -D warnings
PASS: cargo test --workspace --all-targets --all-features (includes the
      docker-backed real_broker_tunnel E2E test — Docker was available)
PASS: cargo test -p p2p-daemon offer_steady_state_write_is_suppressed_when_running_but_shared_token_already_requested
PASS: cargo test -p p2p-daemon answer_registry_write_is_suppressed_when_running_but_shared_token_already_requested
PASS: cargo test -p p2p-daemon --test two_node_daemon offer_admits_no_ordinary_write_when_shutdown_lands_between_session_outcome_and_loop_top
PASS: cd android && ./gradlew --no-daemon testDebugUnitTest --tests '*TunnelForegroundServiceStopFailureTest'
      (5 tests executed, not 0 — re-run 3x fresh with --rerun-tasks to rule
      out flakiness after catching and fixing one real flake, see below)
PASS: cd android && ./gradlew --no-daemon assembleDebug testDebugUnitTest
PASS: cd android && ./gradlew detekt ktlintCheck lintDebug
PASS: scripts/check-systemd-units.sh
NOT RUN (expected): scripts/check-launchd-plists.sh — self-reports SKIP,
      not running on macOS; structural coverage comes from
      cargo test -p p2p-daemon --test launchd_plist_tests (part of the
      workspace test run above, which passed)
PASS: scripts/test-debian-package.sh (Docker-backed maintainer-script
      lifecycle scenarios all passed)
PASS: bash -n scripts/*.sh
PASS: sh -n packaging/debian/postinst packaging/debian/prerm packaging/debian/postrm
NOT RUN: scripts/test-launchd-install-layout.sh (macOS-only CI step; this
      environment is Linux)
NOT RUN: real CI workflow observation (not pushed this session)
```

**Flake caught and fixed during this gate run**: the first version of
`TunnelForegroundServiceStopFailureTest`'s pause/stopServiceWork tests used
`Dispatchers.Unconfined` (matching this project's `inlineTestDispatchers()`
convention elsewhere) on the theory that it would run every suspend call
synchronously. It doesn't: `onStartCommand`'s `serviceScope.launch { ... }`
returns as soon as the coroutine hits its first suspension, and `Unconfined`
has no event loop to keep pumping the rest of that work — unlike
`runBlocking`, which is what makes `inlineTestDispatchers()` safe elsewhere in
this codebase. This showed up as an intermittent single-test failure only
under the full P0-006 sweep, not in earlier isolated runs. Fixed by switching
to real `Dispatchers.IO` (matching the already-reliable startup-cancellation
test) and confirmed via 3 fresh (`--rerun-tasks`) runs with no failures.

---

# P1 tasks

## P1-001 — Remove unused lossy ForwardsConfigStore APIs

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStore.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStoreTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/data/ConfigRepositoryTest.kt
```

### Remove

Delete:

```kotlin
fun loadForwards(): List<ForwardConfig> =
    loadForwardsResult().getOrElse { emptyList() }
```

Delete:

```kotlin
fun deleteForward(forwardId: String) {
    val existing = loadForwardsResult().getOrNull() ?: return
    saveForwards(existing.filterNot { it.id == forwardId })
}
```

### Why removal is preferred

The reviewed snapshot has no production caller of these methods.

Keeping them creates latent behavior:

```text
read/parse failure → empty list
read/parse failure → silent delete no-op
```

There is no compatibility value to preserve.

### Update tests

Success reads:

```kotlin
val loaded = store.loadForwardsResult().getOrThrow()
```

Corrupt reads:

```kotlin
assertTrue(store.loadForwardsResult().isFailure)
```

Delete behavior should be tested through the repository/controller layer with result semantics.

### Search gate

Run:

```bash
rg -n 'loadForwards\(\)|deleteForward\(' \
  android/app/src/main \
  android/app/src/test \
  android/app/src/androidTest
```

Review every remaining match.

View-model/controller methods with the same names are allowed.

The removed `ForwardsConfigStore` methods must not remain.

### Acceptance criteria

- [x] Lossy `ForwardsConfigStore.loadForwards()` removed.
- [x] Silent `ForwardsConfigStore.deleteForward()` removed.
- [x] Tests use result-bearing reads.
- [x] Corrupt storage remains explicit failure.
- [x] Repository/controller delete failure coverage remains intact — added
      `ForwardsRepositoryTest.deleteBlockedWhenStartupBaselineIsCorrupt`,
      mirroring the existing `upsert` coverage for the same shared `mutate()`
      guard, since the store-level delete-on-corrupt test was removed along
      with `deleteForward()`.

---

## P1-002 — Address offer worker test handles by forward ID

### Files

Modify:

```text
crates/p2p-daemon/src/offer/mod.rs
```

Modify the two-forward worker failure test.

### Add test handle

Recommended:

```rust
#[cfg(any(test, debug_assertions))]
#[derive(Debug)]
pub struct OfferAcceptWorkerTestHandle {
    pub forward_id: String,
    pub abort_handle: tokio::task::AbortHandle,
}
```

### Change fault hook type

From conceptual:

```rust
mpsc::UnboundedSender<Vec<AbortHandle>>
```

to:

```rust
mpsc::UnboundedSender<Vec<OfferAcceptWorkerTestHandle>>
```

Populate at worker spawn time where forward ID is already known.

### Update test

Replace:

```rust
let ssh_worker = &handles[0];
```

with:

```rust
let ssh_worker = handles
    .iter()
    .find(|worker| worker.forward_id == "ssh")
    .expect("ssh worker test handle");
```

Keep the second worker alive and assert finalization stops it.

### Acceptance criteria

- [x] Worker fault hook exposes forward ID.
- [x] Test does not depend on vector order.
- [x] Production worker behavior is unchanged.
- [x] Two-forward fatal-failure regression still passes.

---

## P1-003 — Add direct unit coverage for StatusAuditLog semantics

### Files

Modify:

```text
crates/p2p-daemon/src/status.rs
```

### Required tests

#### Every write is retained

```text
write A
write B
write C
snapshot == [A, B, C]
```

#### Watch coalescing does not affect audit

Attach both watch sink and audit.

Write multiple states without polling watch between writes.

Assert:

```text
watch may show only latest
but
audit contains every write in order
```

This test documents why the two mechanisms both exist.

#### Audit clone shares same log

```text
clone audit
write through writer
both snapshots see same events
```

### Acceptance criteria

- [x] Audit is append-only.
- [x] Audit preserves order.
- [x] Audit is non-coalescing.
- [x] Clone observes shared log.
- [x] Production watch semantics remain latest-value.

---

## P1-004 — Keep instrumentation coverage but label it supplemental

### Files

Modify if needed:

```text
android/app/src/androidTest/java/com/phillipchin/webrtctunnel/TunnelForegroundServiceInstrumentationTest.kt
README.md or contributor/testing docs
```

### Goal

Avoid future confusion about proof ownership.

Required P0 proof:

```text
Robolectric test under testDebugUnitTest
```

Supplemental platform integration proof:

```text
androidTest instrumentation
```

Do not delete useful instrumentation tests merely because unit coverage now exists.

Add a brief test comment/document note:

```text
Critical stop-failure truthfulness is also covered by Robolectric unit tests that run in required CI. These instrumentation tests remain supplemental Android framework integration coverage.
```

### Acceptance criteria

- [ ] Required CI proof location is clear.
- [ ] Instrumentation tests remain available as supplemental coverage.
- [ ] No documentation claims instrumentation tests ran when only unit tests ran.

---

## P1-005 — Final silent-failure grep audit

### Goal

Do one final targeted search in files touched by this pass.

### Commands

Run:

```bash
rg -n 'unwrap_or_default\(|getOrElse \{ emptyList\(\) \}|getOrNull\(\) \?: return|\.ok\(\)|let _ =|runCatching' \
  crates/p2p-daemon/src/types.rs \
  crates/p2p-daemon/src/signaling.rs \
  crates/p2p-daemon/src/status.rs \
  crates/p2p-daemon/src/offer/mod.rs \
  crates/p2p-daemon/src/answer/mod.rs \
  android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt \
  android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStore.kt
```

### Classification

For every match, classify:

```text
safe default with explicit semantics
expected teardown/cancellation
best-effort but logged
unexpected failure hidden
```

Fix only the last category.

Do not mechanically delete legitimate ignored cleanup results.

### Deliverable

Add a short completion note listing intentionally retained ignored/default behaviors.

### Acceptance criteria

- [ ] No newly touched failure path becomes empty/default/success silently.
- [ ] Remaining ignored results have explicit rationale.
- [ ] No broad `|| true` or equivalent failure suppression added.

---

# P2 tasks

## P2-001 — Consider a general status-observer abstraction

Future work may unify:

```text
latest-value watch sink
audit/event sink
file writer
```

behind an internal observer abstraction.

Do not do this now.

The current pass needs only a small test-only audit recorder.

---

## P2-002 — Consider running instrumentation tests on an emulator in CI

Future coverage may add:

```text
connectedDebugAndroidTest
Gradle Managed Device
or an emulator CI runner
```

Do not make this a prerequisite for the current signoff because the required stop-failure proof moves to Robolectric unit tests.

---

## P2-003 — Consider removing all lossy convenience APIs project-wide

The current pass removes the known unused `ForwardsConfigStore` footguns.

A future audit may identify other APIs whose return type cannot represent I/O/parse failure.

Do not broaden this pass into a repository-wide API redesign.

---

# Required implementation sequence

Use this order.

```text
Stage 1 — central runtime truthfulness
  P0-001 shared-token-aware status gate
  P0-002 non-coalescing status audit + exact boundary test

Stage 2 — Android proof and state truthfulness
  P0-004 policy pause failure forces false
  P0-005 repository.stop() call-site audit
  P0-003 Robolectric stop-failure tests + CI discovery

Stage 3 — P0 signoff
  P0-006 complete quality gates

Stage 4 — latent footgun cleanup
  P1-001 remove lossy forwards-store APIs
  P1-002 worker test handles by forward ID
  P1-003 StatusAuditLog unit coverage
  P1-004 instrumentation coverage labeling
  P1-005 final silent-failure grep audit
```

Recommended commits:

```text
fix(status): suppress ordinary writes after shared shutdown request
test(status): record every lifecycle status write without coalescing
fix(android): clear policy-pause state when stop fails
test(android): run foreground-service stop failures under unit-test CI
fix(android): remove lossy forwards-store APIs
test(offer): address worker failure hooks by forward id
chore(hardening): complete final silent-failure audit
```

Do not make one giant commit.

---

# Final completion checklist

## Runtime truthfulness

- [ ] `DaemonRuntimeState` observes the shared shutdown token.
- [ ] Ordinary status requires `Running` and uncancelled token.
- [ ] Offer and answer use the daemon's real shared token.
- [ ] Terminal `Closed` remains allowed after cancellation.

## Test trust

- [ ] Shutdown-boundary test uses non-coalescing audit recorder.
- [ ] Audit contains every write in order.
- [ ] Exact boundary is captured immediately before token request.
- [ ] Test fails when token-aware status gating is removed.

## Android

- [ ] Failed policy stop forces `pausedByPolicy = false`.
- [ ] Every service `repository.stop()` result is handled.
- [ ] Four critical stop-failure scenarios run under `testDebugUnitTest`.
- [ ] Focused test class is explicitly invoked in CI.
- [ ] Instrumentation tests are supplemental, not sole proof.

## Storage

- [ ] Lossy `ForwardsConfigStore.loadForwards()` removed.
- [ ] Silent `ForwardsConfigStore.deleteForward()` removed.
- [ ] Tests use result-bearing APIs.

## Test maintenance

- [ ] Worker failure test selects by forward ID.
- [ ] No vector-order dependency remains.
- [ ] Audit-log unit tests document watch-vs-audit semantics.

## Quality gates

- [ ] `cargo fmt --all --check` passes.
- [ ] Debug/all-target/all-feature Clippy passes with warnings denied.
- [ ] Release/all-feature Clippy passes with warnings denied.
- [ ] Workspace tests pass.
- [ ] Focused foreground-service Robolectric class executes and passes.
- [ ] Android assemble + unit tests pass.
- [ ] systemd validation passes.
- [ ] launchd plist validation passes or is reported `NOT RUN` with exact reason.
- [ ] Debian package smoke test passes.
- [ ] macOS install-layout test passes on macOS or is reported `NOT RUN` with exact reason.
- [ ] Real CI executes the new focused Android test step.
