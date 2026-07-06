# WebRTC Tunnel Release-Signoff Hardening Specification

## 1. Document purpose

This specification defines the final corrective hardening pass for:

```text
webrtc_tunnel-master_2607060947.zip
```

It is intentionally narrower than the prior lifecycle, runtime-truthfulness, packaging, and diagnostics plans.

The previous passes already established and largely implemented the correct architecture:

- `p2p-offer` and `p2p-answer` remain ordinary foreground processes;
- shutdown is cooperative through `ShutdownToken`;
- answer sessions are supervised independently of self-reported completion;
- offer accept workers are supervised and infrastructure failure is daemon-fatal;
- terminal status is strict and status-file replacement is atomic;
- premature `sd_notify` readiness has been removed;
- Debian and macOS service packaging has been hardened;
- Android native stop outcomes distinguish graceful stop from forced abort and join failure;
- CLI status/config validation is strict;
- diagnostics redaction and error serialization are substantially improved.

Do **not** reopen those decisions or redesign the application.

This pass addresses only the remaining issues found in the review of the `2607060947` snapshot:

1. ordinary status writes are still gated only by local runtime phase, not the already-requested shared shutdown token;
2. the exact shutdown-boundary regression test observes a coalescing `watch` stream and can miss illegal intermediate states;
3. the foreground-service stop-failure tests live only under `androidTest` while required CI runs only `testDebugUnitTest`;
4. `pauseForPolicy()` can restore `pausedByPolicy = true` after a failed stop;
5. the service needs one explicit audit proving every `repository.stop()` result is intentionally handled;
6. `ForwardsConfigStore` still contains unused lossy convenience/mutation APIs that can silently turn storage failure into empty/no-op behavior;
7. the offer worker-failure regression test still identifies workers by vector order instead of forward identity.

The goal is release signoff, not new feature work.

---

## 2. Executive decision summary

### 2.1 Central shutdown truthfulness must be enforced where status is written

The current runtime phase model is good but insufficient by itself.

Today, normal status is allowed when:

```text
runtime.phase == Running
```

There is a narrow window where:

```text
ShutdownToken requested
        ↓
daemon has not yet observed the token
        ↓
runtime.phase still Running
        ↓
ordinary recovery writes WaitingForLocalClient or Serving
```

The final design is:

```text
normal status allowed
    only if
        phase == Running
        AND
        shared ShutdownToken has not been requested
```

This must be enforced centrally in the ordinary status gate, not by adding more scattered call-site checks.

### 2.2 The runtime state owns a clone of the shared shutdown token

Do not add the token to every status helper parameter.

Do not add a global token.

Store a clone in `DaemonRuntimeState`:

```rust
pub(crate) struct DaemonRuntimeState {
    pub(crate) mqtt_connected: bool,
    pub(crate) last_transport_failure_at_ms: Option<u64>,
    pub(crate) forward_statuses: Vec<ForwardRuntimeStatus>,
    pub(crate) phase: DaemonRuntimePhase,
    shutdown: ShutdownToken,
}
```

Production daemons create runtime state with the same shared token they already use:

```rust
let mut runtime =
    DaemonRuntimeState::new_connected_with_shutdown(shutdown.clone());
```

Existing unit tests may keep using:

```rust
DaemonRuntimeState::new_connected()
```

which creates its own uncancelled token for compatibility.

### 2.3 `watch` remains correct for latest-state consumers, but not for audit tests

Do **not** replace the Android/latest-state `watch::Sender<DaemonStatus>` API.

A `watch` channel is intentionally lossy:

```text
WaitingForLocalClient
Closed
```

can be observed only as:

```text
Closed
```

That is correct for UI consumers that only need the latest state.

It is not correct for a regression test trying to prove that **no illegal intermediate state was ever emitted**.

Add a separate, test-only, non-coalescing status audit recorder.

The preferred design is a synchronous append-only test recorder attached to `StatusWriter`:

```rust
#[cfg(any(test, debug_assertions))]
#[derive(Clone, Default)]
pub struct StatusAuditLog {
    events: Arc<Mutex<Vec<DaemonStatus>>>,
}
```

Every attempted `StatusWriter::write(...)` records exactly one event in the audit log before optional file I/O.

This gives a trustworthy exact boundary:

```text
boundary = audit.len()
shutdown.request_shutdown()
await daemon
inspect audit[boundary..]
```

No coalescing is possible.

### 2.4 Required Android stop-failure tests must run under the existing unit-test CI gate

The critical foreground-service truthfulness tests currently live only under:

```text
android/app/src/androidTest/
```

The required Android CI job runs:

```bash
./gradlew --no-daemon assembleDebug testDebugUnitTest
```

Therefore those tests are not part of the required gate.

The final decision is:

> Port the four critical stop-failure scenarios to Robolectric unit tests under `src/test`, using the already-present Robolectric dependency.

Do **not** make P0 completion depend on adding and maintaining an emulator runner.

Existing instrumentation tests may remain as additional coverage.

The required P0 scenarios are:

1. `pause()` stop failure reports `Error`, never `Paused`;
2. `pauseForPolicy()` stop failure reports `Error`, never policy-paused;
3. `stopServiceWork()` stop failure may tear down the Android service but never claims a clean tunnel stop;
4. startup cancellation/supersedence observes and surfaces a failed `repository.stop()` result.

These tests must run when CI executes `testDebugUnitTest`.

### 2.5 A failed policy stop must force `pausedByPolicy = false`

`pausedByPolicy` is an achieved-state flag:

```text
true = tunnel successfully stopped because policy required a pause
```

It is not:

```text
true = a policy pause was attempted
```

Therefore:

```text
repository.stop() fails
        ↓
pausedByPolicy = false
        ↓
publish error
        ↓
do not publish normal policy-paused state
        ↓
retry/reevaluation remains possible
```

Do not restore a previous `true` value after a failed stop.

### 2.6 Unused lossy storage APIs should be removed, not merely documented

The latest tree shows no production caller of:

```kotlin
ForwardsConfigStore.loadForwards()
ForwardsConfigStore.deleteForward(...)
```

`loadForwards()` converts read/parse failure into `emptyList()`.

`deleteForward()` converts read/parse failure into a silent no-op.

Because these APIs are unused by production code, remove them now instead of preserving future footguns.

Correctness-sensitive code must use:

```kotlin
loadForwardsResult()
```

or the repository layer.

### 2.7 Worker-failure tests should address workers by forward ID

The current two-forward regression is conceptually correct, but worker selection by vector index creates an unnecessary ordering dependency.

The test seam should return:

```rust
pub struct OfferAcceptWorkerTestHandle {
    pub forward_id: String,
    pub abort_handle: AbortHandle,
}
```

The test then chooses the worker by ID.

This is P1 because the current ordering is deterministic today and does not invalidate the production fix.

---

## 3. Non-negotiable invariants

The implementation must preserve all of the following.

### 3.1 Process architecture

- Keep offer and answer as foreground processes.
- No `--daemon` mode.
- No fork/double-fork.
- No PID files.
- No systemd or launchd dependency in generic daemon state machines.
- No systemd or launchd inside Docker.

### 3.2 Shutdown truthfulness

Once the shared `ShutdownToken` is requested:

- no ordinary `WaitingForLocalClient` write;
- no ordinary `Serving` write;
- no `Negotiating` write;
- no `TunnelOpen` write;
- no ordinary session-recovery write;
- final `Closed` remains allowed and required.

This applies even before the outer loop has locally changed phase to `Draining`.

### 3.3 Test truthfulness

- A latest-value `watch` stream may not be used as proof that an intermediate state was never emitted.
- Required tests must run in the required CI gate.
- A test present only under `androidTest` is not proof for a CI job that runs only `testDebugUnitTest`.
- Test synchronization failure must remain loud.
- Do not replace deterministic hooks with sleeps.

### 3.4 Android state truthfulness

- Failed native stop must never be followed by normal `Paused` or clean `Stopped` state.
- `pausedByPolicy` must be true only after a successful policy stop.
- Every `repository.stop()` call in `TunnelForegroundService` must intentionally inspect its `Result`.

### 3.5 Storage truthfulness

- Storage read/parse failure is not an empty list.
- Storage delete failure is not a successful delete.
- Unused lossy convenience APIs should be deleted.

---

## 4. Accepted architecture that must not be reopened

The following work is considered correct baseline and is out of scope unless a new regression is discovered while implementing this pass.

### Offer

- accept workers are owned and supervised;
- active-session accept-worker failure is daemon-fatal;
- monitor join failures return cleanup errors;
- immutable fallible peer lookup occurs before worker startup;
- every post-worker-start exit goes through finalization;
- shutdown releases listener ports;
- reconnect wait is cancellation-aware.

### Answer

- session task completion is independently observed;
- completion identity uses final ID fast path plus generation/peer fallback;
- task panic cannot strand a registry entry;
- fatal paths drain before returning;
- payload admission is checked after payload readiness.

### Status

- runtime phases are `Starting`, `Running`, `Draining`, `Closed`;
- terminal `Closed` status is strict;
- status file replacement is same-directory atomic;
- temp file names are unique per write.

### Android native runtime

- graceful and forced stop are distinct;
- forced abort and task join failure propagate through FFI/JNI;
- mutex poisoning is not empty/default/no-op;
- tracing/log-buffer failure is visible.

### Packaging and services

- baseline systemd is `Type=simple`;
- premature `sd_notify` readiness is removed;
- Debian package units use `/usr/bin`;
- manual units may use `/usr/local/bin`;
- launchd service-user preflight exists;
- macOS log directory uses a create/delete permission probe.

### Diagnostics and CLI

- typed strict `p2pctl status` parsing;
- `check-config` validates required authorized peers;
- quoted multi-word secret values are redacted;
- diagnostics error JSON is serialized;
- CI default permissions are read-only.

---

## 5. Remaining defect A: phase-only status suppression

### 5.1 Current condition

Current ordinary status gating is conceptually:

```rust
fn runtime_status_allowed(ctx: &RuntimeContext<'_>) -> bool {
    matches!(ctx.runtime.phase, DaemonRuntimePhase::Running)
}
```

That suppresses status after local transition to `Draining`.

It does not suppress status in this interval:

```text
T0: shared shutdown token requested
T1: runtime phase still Running
T2: normal recovery helper executes
T3: outer select observes token
T4: phase becomes Draining
```

A normal status emitted at T2 is false.

### 5.2 Required runtime-state API

Add:

```rust
impl DaemonRuntimeState {
    pub(crate) fn new_connected_with_shutdown(shutdown: ShutdownToken) -> Self {
        Self {
            mqtt_connected: true,
            last_transport_failure_at_ms: None,
            forward_statuses: Vec::new(),
            phase: DaemonRuntimePhase::Starting,
            shutdown,
        }
    }

    pub(crate) fn new_connected() -> Self {
        Self::new_connected_with_shutdown(ShutdownToken::new())
    }

    pub(crate) fn normal_status_allowed(&self) -> bool {
        matches!(self.phase, DaemonRuntimePhase::Running)
            && !self.shutdown.is_shutdown_requested()
    }
}
```

Field visibility may remain private.

### 5.3 Required production wiring

Offer:

```rust
let mut runtime =
    DaemonRuntimeState::new_connected_with_shutdown(shutdown.clone());
```

Answer equivalent.

Do not create a separate token for runtime status.

### 5.4 Required central gate

Change:

```rust
fn runtime_status_allowed(ctx: &RuntimeContext<'_>) -> bool {
    matches!(ctx.runtime.phase, DaemonRuntimePhase::Running)
}
```

to:

```rust
fn runtime_status_allowed(ctx: &RuntimeContext<'_>) -> bool {
    ctx.runtime.normal_status_allowed()
}
```

Now every ordinary status helper shares the exact same rule.

### 5.5 Terminal status

Terminal writers intentionally bypass ordinary status gating and remain unchanged:

```text
write_answer_closed_status
write_offer_closed_status
```

A requested shutdown must suppress normal state but must not suppress `Closed`.

---

## 6. Remaining defect B: coalescing status observation

### 6.1 Why the current watch-based proof is insufficient

A `watch` receiver is latest-state transport.

It may observe:

```text
Closed
```

when the writer actually emitted:

```text
WaitingForLocalClient
Closed
```

Therefore this test statement is invalid:

```text
I did not observe WaitingForLocalClient after shutdown,
therefore it was never emitted.
```

### 6.2 Required test-only audit recorder

Preferred implementation:

```rust
#[cfg(any(test, debug_assertions))]
#[derive(Clone, Default)]
pub struct StatusAuditLog {
    events: Arc<Mutex<Vec<DaemonStatus>>>,
}

#[cfg(any(test, debug_assertions))]
impl StatusAuditLog {
    pub fn len(&self) -> usize {
        self.events.lock().expect("status audit log mutex").len()
    }

    pub fn snapshot(&self) -> Vec<DaemonStatus> {
        self.events.lock().expect("status audit log mutex").clone()
    }

    fn record(&self, status: DaemonStatus) {
        self.events
            .lock()
            .expect("status audit log mutex")
            .push(status);
    }
}
```

Use `std::sync::Mutex` only because this is a test/debug recorder and the critical section is one vector push.

Do not add an async mutex around a synchronous append.

### 6.3 Attach audit log to StatusWriter

Conceptual shape:

```rust
pub struct StatusWriter {
    enabled: bool,
    path: PathBuf,
    sink: Option<watch::Sender<DaemonStatus>>,

    #[cfg(any(test, debug_assertions))]
    audit: Option<StatusAuditLog>,
}
```

All normal constructors set audit to `None`.

Add test/debug constructor or builder:

```rust
#[cfg(any(test, debug_assertions))]
pub fn with_audit(
    config: &AppConfig,
    audit: StatusAuditLog,
) -> Self {
    Self {
        enabled: config.health.write_status_file,
        path: config.health.status_file.clone(),
        sink: None,
        audit: Some(audit),
    }
}
```

If a test needs both latest-state watch and audit, add a small `with_sink_and_audit` constructor.

### 6.4 Record before file I/O

Inside `write`:

```rust
#[cfg(any(test, debug_assertions))]
if let Some(audit) = &self.audit {
    audit.record(status.clone());
}
```

Then keep the existing watch sink and file behavior.

### 6.5 Full-daemon test seam

Extend the existing offer test hooks with an optional audit log instead of replacing production APIs.

Example:

```rust
#[cfg(any(test, debug_assertions))]
#[derive(Default)]
pub(crate) struct OfferDaemonTestHooks {
    // existing fields...
    pub(crate) status_audit: Option<StatusAuditLog>,
}
```

When present, create a `StatusWriter` that records every write.

Update every test-hook constructor to set the new field explicitly or rely on `Default` plus struct update syntax.

### 6.6 Exact boundary test

Required pattern:

```rust
let boundary = audit.len();
shutdown.request_shutdown();

let result = timeout(TEST_TIMEOUT, daemon_task)
    .await
    .expect("daemon should stop")
    .expect("daemon task join");

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
```

Also assert final `Closed` is present.

Do not infer the boundary from state values.

Do not use `watch` as the audit source.

---

## 7. Remaining defect C: required Android tests are outside the required CI gate

### 7.1 Current mismatch

Critical tests currently exist under:

```text
src/androidTest
```

Required CI runs:

```text
testDebugUnitTest
```

Therefore:

```text
tests exist
≠
required CI executes them
```

### 7.2 Chosen P0 solution: Robolectric unit tests

The project already has:

```text
Robolectric 4.14.1
AndroidX test core
Android resources enabled for unit tests
```

Use that existing infrastructure.

Do not add an emulator as a P0 prerequisite.

### 7.3 Shared test application/fakes

The current `TestWebRtcTunnelApplication`, `TestTunnelHooks`, and `RecordingBridge` contain no instrumentation-only APIs.

Make them available to both unit and instrumentation tests.

Preferred structure:

```text
android/app/src/sharedTest/java/com/phillipchin/webrtctunnel/
    TestWebRtcTunnelApplication.kt
```

Configure:

```kotlin
android {
    sourceSets {
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }
}
```

Keep `TestTunnelRunner.kt` under `androidTest` because it is instrumentation-specific.

Equivalent sharing is acceptable if it does not ship test hooks in production APKs.

### 7.4 Required Robolectric test class

Add under:

```text
android/app/src/test/java/com/phillipchin/webrtctunnel/
    TunnelForegroundServiceStopFailureTest.kt
```

Use:

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

Adapt lifecycle driving to Robolectric APIs and existing service architecture.

The test must exercise the actual `TunnelForegroundService` methods/action handling, not only a detached result-mapping helper.

### 7.5 Required four scenarios

#### Pause failure

```text
start offer
inject next stop failure
send ACTION_PAUSE
assert final repository serviceState == Error
assert state never becomes Paused after the failure
```

#### Policy pause failure

Drive the service into the policy-pause path using the smallest existing test seam.

Assert:

```text
stop failure
→ pausedByPolicy false
→ Error state
→ no policy-paused normal state
```

If direct policy evaluation is cumbersome under Robolectric, expose a test-only service seam that invokes the real `pauseForPolicy()` coordinator method. Do not test only a synthetic helper.

#### Stop-service failure

```text
start offer
inject next stop failure
send ACTION_STOP
assert repository state == Error
assert no clean Stopped publication follows
```

The Android service may still call `stopSelf()`.

The tunnel state must remain error-truthful.

#### Startup cancellation failure

Force a start to remain pending, inject stop failure, trigger supersedence/pause/stop, and prove the failed cleanup is surfaced.

### 7.6 CI requirement

The existing Android CI command may remain:

```bash
./gradlew --no-daemon assembleDebug testDebugUnitTest
```

because the critical tests will now run under it.

Add a focused command before or as part of the full test step:

```bash
./gradlew --no-daemon testDebugUnitTest \
  --tests '*TunnelForegroundServiceStopFailureTest'
```

This makes accidental test relocation/non-discovery obvious.

Instrumentation tests may still run separately when an emulator is available, but they are no longer the sole proof of P0 behavior.

---

## 8. Remaining defect D: policy pause flag rollback

### 8.1 Current behavior

Current failure branch restores:

```kotlin
pausedByPolicy = previousPausedByPolicy
```

The normal caller probably enters with `false`, but a stale/reentrant `true` value can survive a failed stop.

### 8.2 Required behavior

On failed policy stop:

```kotlin
onFailure = { error ->
    pausedByPolicy = false
    reporter.publishError(
        message = error.message ?: "Failed stopping tunnel after policy block",
        code = "stop_failed",
    )
}
```

Do not publish normal policy-blocked state.

Do not set `pausedByPolicy = true` before stop succeeds.

### 8.3 Test

Explicitly begin the test with stale state if a test seam allows it:

```text
pausedByPolicy = true
real tunnel is active
next stop fails
pauseForPolicy requested
```

Assert final flag is false.

This proves the implementation does not merely restore stale state.

---

## 9. Remaining defect E: stop-result audit

Search every production call in:

```text
TunnelForegroundService.kt
```

for:

```kotlin
repository.stop()
```

Each call must be one of:

```text
fold/onSuccess/onFailure
explicit Result inspection
returned to a caller that handles Result
```

Forbidden:

```kotlin
repository.stop()
```

with ignored return value.

Forbidden:

```kotlin
runCatching { repository.stop() }
```

if the nested `Result` is discarded.

Add a focused test for every call site that can encounter failure.

---

## 10. Remaining defect F: lossy forwards-store APIs

### 10.1 Current APIs

```kotlin
fun loadForwards(): List<ForwardConfig> =
    loadForwardsResult().getOrElse { emptyList() }
```

and:

```kotlin
fun deleteForward(forwardId: String) {
    val existing = loadForwardsResult().getOrNull() ?: return
    saveForwards(existing.filterNot { it.id == forwardId })
}
```

### 10.2 Current usage

In the reviewed snapshot, these methods have no production callers.

They are used only by tests.

### 10.3 Required action

Delete both methods.

Update tests to use:

```kotlin
loadForwardsResult().getOrThrow()
```

for success cases.

For delete behavior, test through the repository/controller layer that owns result semantics.

Do not preserve lossy APIs for hypothetical compatibility when no production caller exists.

### 10.4 Follow-up search

After removal:

```bash
rg -n 'loadForwards\(\)|deleteForward\(' android/app/src/main android/app/src/test
```

Review every match intentionally.

The names may still exist in view models/controllers; the important point is that the `ForwardsConfigStore` lossy methods are gone.

---

## 11. P1 test-seam cleanup: forward identity

### 11.1 Current test seam

The worker-failure test receives:

```text
Vec<AbortHandle>
```

and assumes vector order matches forward order.

### 11.2 Preferred test type

```rust
#[cfg(any(test, debug_assertions))]
#[derive(Debug)]
pub struct OfferAcceptWorkerTestHandle {
    pub forward_id: String,
    pub abort_handle: tokio::task::AbortHandle,
}
```

Return:

```text
Vec<OfferAcceptWorkerTestHandle>
```

The test selects:

```rust
let ssh_worker = worker_handles
    .iter()
    .find(|worker| worker.forward_id == "ssh")
    .expect("ssh worker");
```

This is test-infrastructure cleanup, not a production behavior change.

---

## 12. Test plan

### 12.1 Rust unit tests

At minimum:

```text
Running phase + token not requested → ordinary status allowed
Running phase + token requested     → ordinary status suppressed
Draining phase                       → ordinary status suppressed
Closed phase                         → ordinary status suppressed
terminal Closed write                → still emitted after token request
```

### 12.2 Offer lifecycle test

Use:

```text
loop-top barrier
shared ShutdownToken
non-coalescing StatusAuditLog
```

Required sequence:

```text
run offer
reach loop-top barrier
capture audit boundary
request shutdown
release barrier
await daemon
inspect every audit event after boundary
```

No normal state is allowed.

### 12.3 Answer status test

A focused status-helper test is sufficient once the central gate uses the shared token:

```text
phase Running
request shared token
attempt write_answer_registry_status
assert audit log unchanged
```

Existing full answer drain tests remain in place.

### 12.4 Android unit tests

Must run under `testDebugUnitTest`:

```text
pause stop failure
policy pause stop failure
service stop failure
startup cancellation stop failure
```

### 12.5 Storage tests

After deleting lossy methods:

```text
corrupt file → loadForwardsResult is failure
valid empty file/list → successful empty list
repository delete failure remains visible
controller delete failure never reports success
```

---

## 13. CI and validation requirements

### Rust

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo clippy --workspace --release --all-features -- -D warnings
cargo test --workspace --all-targets --all-features
```

### Focused lifecycle tests

Run the exact test names added/modified in this pass individually.

Do not rely only on the full workspace command.

### Android

```bash
cd android
./gradlew --no-daemon testDebugUnitTest \
  --tests '*TunnelForegroundServiceStopFailureTest'
./gradlew --no-daemon assembleDebug testDebugUnitTest
```

The first command proves the P0 class is discoverable and executable.

### Service/package regression

This pass should not change package/service behavior, but still run:

```bash
scripts/check-systemd-units.sh
scripts/check-launchd-plists.sh
scripts/test-debian-package.sh
bash -n scripts/*.sh
sh -n packaging/debian/postinst packaging/debian/prerm packaging/debian/postrm
```

Run the macOS install-layout test on macOS if CI is available.

### Reporting

Use only:

```text
PASS: command actually executed successfully
FAIL: command executed and failed
NOT RUN: exact reason
```

Do not treat a previous CI run as a locally executed pass.

---

## 14. Out of scope

Do not implement during this pass:

- `sd_notify` readiness;
- second-signal forced exit;
- generic task-supervision framework;
- daemon error-category refactor;
- new signaling features;
- wire-format changes;
- crypto changes;
- new packaging format;
- Android answer-mode enablement;
- UI redesign.

---

## 15. Definition of done

This pass is complete only when all are true.

### Runtime truthfulness

- ordinary status gate checks both runtime phase and shared shutdown token;
- offer and answer production runtime states use the same shared token as their daemon loops;
- terminal `Closed` remains allowed after shutdown;
- exact boundary test records every status write without coalescing.

### Android truthfulness

- failed policy stop forces `pausedByPolicy = false`;
- no foreground-service `repository.stop()` result is ignored;
- all four critical stop-failure scenarios run under `testDebugUnitTest`;
- instrumentation tests are no longer the sole proof of P0 behavior.

### Storage truthfulness

- unused lossy `ForwardsConfigStore.loadForwards()` is removed;
- unused silent `ForwardsConfigStore.deleteForward()` is removed;
- tests use result-bearing APIs.

### Verification

- Rust gates pass;
- focused lifecycle tests pass;
- focused Robolectric stop-failure tests pass;
- Android assemble + unit tests pass;
- service/package regression checks pass or unavailable platform checks are reported exactly as `NOT RUN`.

---

## 16. Handoff instruction to Claude Code

Implement in the dependency order in the TODO.

Do not mark a checkbox complete because:

- code exists but the required test does not run;
- a latest-state observer happened not to see an illegal intermediate state;
- a failure path is “unlikely”;
- an unused lossy API currently has no caller;
- an old CI run once passed.

For each task:

1. implement the smallest coherent change;
2. add/repair the deterministic test;
3. run the focused test;
4. run the relevant broader gate;
5. commit before moving to the next dependency stage.
