# WebRTC Tunnel Final Lifecycle Truthfulness and Failure-Propagation Hardening TODO

## 0. Instructions for Claude Code

Implement this TODO against:

```text
webrtc_tunnel-master_2607060410.zip
```

Read first:

```text
WEBRTC_TUNNEL_FINAL_LIFECYCLE_TRUTHFULNESS_HARDENING_SPEC.md
crates/p2p-daemon/src/offer/mod.rs
crates/p2p-daemon/src/offer/session/mod.rs
crates/p2p-daemon/src/answer/mod.rs
crates/p2p-daemon/src/answer/session.rs
crates/p2p-daemon/src/error.rs
crates/p2p-daemon/src/status.rs
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/SensitiveDataRedactor.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SettingsViewModel.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupForwardsController.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStore.kt
scripts/install-launchd-services.sh
scripts/test-launchd-install-layout.sh
```

### Priority scale

```text
P0 = release blocker / runtime correctness / false-success behavior
P1 = high-priority diagnostics, test trust, permissions, and failure visibility
P2 = future cleanup not required for this pass
```

### Non-negotiable rules

- Preserve foreground-process architecture.
- Preserve current signaling, crypto, identity, and wire protocol.
- Infrastructure worker failure is never an ordinary session outcome.
- Do not write normal runtime status after shutdown is requested.
- Do not admit new answer work after shutdown is requested.
- Do not use final session ID as the only completion identity across asynchronous replacement.
- Do not report native Android stop failure as Paused or Stopped.
- Do not turn a test synchronization failure into a silent continuation.
- Do not claim a handler-only synthetic-error test proves a real task panic path.
- Do not interpret storage/read/delete failure as empty/default/success.
- Do not add hidden abort timeouts.
- Do not reintroduce premature `sd_notify` readiness.
- Commit incrementally in the required order at the end of this file.

---

# P0 tasks

## P0-001 — Make offer infrastructure failures daemon-fatal during active sessions

### Files

Modify:

```text
crates/p2p-daemon/src/offer/mod.rs
crates/p2p-daemon/src/error.rs
```

### Problem

`run_offer_session(...)` can return:

```rust
DaemonError::OfferAcceptWorkerFailed { ... }
```

while a session is active.

The outer daemon currently feeds the result through ordinary cooldown/recovery policy.

With multiple forwards, one worker can die while another remains alive, allowing the daemon to continue.

### Required policy

Add one explicit classification helper:

```rust
fn is_offer_infrastructure_failure(error: &DaemonError) -> bool {
    matches!(
        error,
        DaemonError::OfferAcceptWorkerFailed { .. }
            | DaemonError::OfferAcceptSupervisorFailed { .. }
            | DaemonError::OfferAcceptMonitorJoinFailed { .. }
    )
}
```

Adjust exact variants to current code.

After `run_offer_session(...)`:

```rust
let result = run_offer_session(...).await;

if shutdown.is_shutdown_requested() {
    if let Err(error) = &result {
        tracing::warn!(
            reason = %error,
            "offer session ended with error during shutdown",
        );
    }
    break Ok(());
}

match result {
    Err(error) if is_offer_infrastructure_failure(&error) => {
        tracing::error!(
            reason = %error,
            "offer runtime infrastructure failed during active session",
        );
        break Err(error);
    }

    ordinary_result => {
        if cooldown::session_outcome_enters_cooldown(&ordinary_result) {
            // existing cooldown logic
        } else {
            probe_cooldown.reset();
        }

        recover_daemon_after_session(&ctx, ordinary_result).await;
        tracing::info!("offer daemon returned to waiting state");
    }
}
```

### Do not

Do not solve this by making every session error fatal.

Only runtime infrastructure failures are daemon-fatal.

### Tests

Add a unit test for the classifier.

At minimum:

```rust
assert!(is_offer_infrastructure_failure(
    &DaemonError::OfferAcceptWorkerFailed {
        forward_id: "a".to_owned(),
        reason: "panic".to_owned(),
    },
));
```

Also assert an ordinary session error is not classified as infrastructure failure.

### Acceptance criteria

- [x] Active-session accept-worker failure skips cooldown.
- [x] Active-session accept-worker failure skips ordinary recovery.
- [x] Active-session infrastructure failure breaks run loop with `Err`.
- [x] Ordinary session failures retain existing recovery behavior.
- [x] Classification is tested.

---

## P0-002 — Add a two-forward active-session worker-failure regression test

### Files

Modify/add under:

```text
crates/p2p-daemon/src/tests/
crates/p2p-daemon/tests/two_node_daemon/
```

### Goal

Prove a surviving worker cannot mask another worker's death.

### Required topology

Configure at least two offer forwards:

```text
forward A -> worker A
forward B -> worker B
```

### Required sequence

```text
start offer daemon
-> prove both workers are alive/listening
-> establish active session
-> deterministically fail worker A
-> leave worker B alive
-> assert daemon begins fatal finalization
-> assert worker B receives cooperative shutdown
-> assert final status attempted
-> assert daemon returns Err
```

### Critical test property

The test must fail against the current bug where:

```text
worker A failure
-> run_offer_session Err
-> ordinary recovery
-> worker B keeps runtime alive
```

### Suggested test hook

If current `worker_fault_hook` returns abort handles indexed by worker order, preserve deterministic forward identity too.

Prefer:

```rust
#[cfg(any(test, debug_assertions))]
pub struct OfferAcceptWorkerTestHandle {
    pub forward_id: String,
    pub abort_handle: tokio::task::AbortHandle,
}
```

Then the test can fail a specific forward rather than depending on vector order.

### Acceptance criteria

- [x] Test uses at least two offer workers.
- [x] One worker is deliberately kept alive after the other fails.
- [x] Daemon still exits `Err`.
- [x] Remaining worker is stopped by finalization.
- [x] Test fails if P0-001 classification is removed. (Verified by temporarily reverting
      the classification locally and observing this test fail with a timeout.)

---

## P0-003 — Move immutable fallible offer setup before accept-worker startup

### Files

Modify:

```text
crates/p2p-daemon/src/offer/mod.rs
```

### Problem

After accept workers start, current code still performs fallible immutable setup such as:

```rust
let remote_peer_id = offer_remote_peer_id(&config)?;
let remote = authorized_keys
    .get_by_peer_id(&remote_peer_id)
    .cloned()
    .ok_or_else(...)?;
```

These paths structurally bypass the post-worker-start finalizer.

### Required ordering

Target order:

```text
validate config/authorized peers
subscribe broker
create status/runtime
bind listeners
resolve remote peer ID
lookup authorized remote
create replay cache/cooldown
spawn accept workers
enter Running
run loop
finalizer
```

Move:

```rust
let remote_peer_id = offer_remote_peer_id(&config)?;
let remote = authorized_keys
    .get_by_peer_id(&remote_peer_id)
    .cloned()
    .ok_or_else(|| DaemonError::MissingAuthorizedPeer(remote_peer_id.to_string()))?;
```

before:

```rust
spawn_offer_accept_loops(...)
```

### Acceptance criteria

- [x] No fallible immutable peer/config lookup remains after accept-runtime creation.
- [x] Every error after accept-runtime creation is represented in `run_result` or cleanup result.
- [x] Finalizer is structurally unavoidable after worker start.

---

## P0-004 — Return offer worker-monitor join failures as cleanup errors

### Files

Modify:

```text
crates/p2p-daemon/src/offer/mod.rs
crates/p2p-daemon/src/error.rs
```

### Problem

Unexpected monitor `JoinError` currently logs a warning but cleanup can still return success.

### Add error variant

Recommended:

```rust
#[error("offer accept monitor for forward '{forward_id}' failed: {reason}")]
OfferAcceptMonitorJoinFailed {
    forward_id: String,
    reason: String,
},
```

If monitor identity is unavailable at join time, restructure storage so it is retained.

### Return `Result`

Target:

```rust
async fn stop_and_join_offer_accept_runtime(
    runtime: OfferAcceptRuntime,
) -> Result<(), DaemonError>
```

Collect the first cleanup failure and log later ones as secondary:

```rust
let mut primary_cleanup_error: Option<DaemonError> = None;

for monitor in runtime.monitors {
    if let Err(error) = monitor.handle.await {
        let failure = DaemonError::OfferAcceptMonitorJoinFailed {
            forward_id: monitor.forward_id,
            reason: error.to_string(),
        };

        if primary_cleanup_error.is_none() {
            primary_cleanup_error = Some(failure);
        } else {
            tracing::error!(reason = %failure, "additional offer monitor cleanup failure");
        }
    }
}

match primary_cleanup_error {
    Some(error) => Err(error),
    None => Ok(()),
}
```

Use current monitor types rather than copying this blindly.

### Merge with final result

Preserve precedence:

```text
runtime error > cleanup error > terminal status error
```

### Acceptance criteria

- [x] Monitor panic cannot become warning + `Ok(())`.
- [x] Cleanup returns `Err` for unexpected monitor join failure.
- [x] Primary runtime error still wins if both runtime and cleanup fail.
- [x] Secondary cleanup errors are logged with context.
- [x] Focused test covers monitor panic/join failure.

---

## P0-005 — Add offer top-of-loop shutdown gate before ordinary status

### Files

Modify:

```text
crates/p2p-daemon/src/offer/mod.rs
```

### Required code shape

```rust
let run_result: Result<(), DaemonError> = async {
    loop {
        if shutdown.is_shutdown_requested() {
            break Ok(());
        }

        write_steady_state_status(&ctx).await;

        tokio::select! {
            biased;
            // existing branches
        }
    }
}
.await;
```

### Why

`DaemonRuntimePhase::Running` remains set until finalization begins.

Without the explicit gate:

```text
shutdown token requested
-> next loop iteration
-> WaitingForLocalClient emitted
-> shutdown branch observed
```

### Acceptance criteria

- [x] No ordinary steady-state write occurs after token is already requested.
- [x] Existing `Running` phase behavior remains unchanged before shutdown.
- [x] Regression test uses exact shutdown boundary, not state-sequence inference.

---

## P0-006 — Add answer post-payload shutdown admission gate

### Files

Modify:

```text
crates/p2p-daemon/src/answer/mod.rs
```

### Required code

In the payload branch:

```rust
payload = poll_idle_signal_payload(&mut ctx, &mut transport), if !shutting_down => {
    let Some(payload) = payload else {
        continue;
    };

    if shutdown.is_shutdown_requested() {
        shutting_down = true;
        begin_answer_drain(
            &mut ctx,
            &shutdown,
            &mut primary_error,
            None,
        );
        continue;
    }

    handle_answer_daemon_payload(
        // existing args
        payload,
    )
    .await;
}
```

### Test

Force both to be ready:

```text
payload ready
shutdown requested before payload branch processes admission
```

Assert no new session is created.

Use a barrier/event hook; do not use a sleep.

### Acceptance criteria

- [x] No new session admitted after shutdown request.
- [x] Existing sessions still drain.
- [x] Payload race test is deterministic.

---

## P0-007 — Resolve answer task completion by stable identity across session replacement

### Files

Modify:

```text
crates/p2p-daemon/src/answer/mod.rs
```

### Add helper

```rust
fn resolve_completion_registry_session_id(
    sessions: &HashMap<SessionId, AnswerSessionHandle>,
    final_session_id: SessionId,
    generation: SessionGeneration,
    remote_peer_id: &PeerId,
) -> Option<SessionId> {
    if sessions.get(&final_session_id).is_some_and(|handle| {
        handle.generation == generation && &handle.remote_peer_id == remote_peer_id
    }) {
        return Some(final_session_id);
    }

    find_session_id_by_generation_and_peer(sessions, generation, remote_peer_id)
}
```

### Use for normal completion

Do not do only:

```rust
let completed_session_id = result.final_session_id;
```

Instead resolve the current registry key using final ID fast path plus stable fallback.

### Use for join failure

Keep or refactor current stable lookup so both success and join failure use the same identity policy.

### Late replacement event policy

After completion removes the registry entry, a queued `Replaced` event for that generation/peer is stale and must not recreate state.

It may be logged at debug level if useful.

### Acceptance criteria

- [x] Normal completion uses stable fallback.
- [x] Join failure uses stable lookup.
- [x] Completion before queued `Replaced` cannot strand registry state.
- [x] Late replacement event cannot recreate a completed session.

---

## P0-008 — Add deterministic completion-before-Replaced regression test

### Files

Modify/add under:

```text
crates/p2p-daemon/src/tests/
crates/p2p-daemon/tests/two_node_daemon/
```

### Required ordering

Force:

```text
1. session internally adopts new session ID
2. Replaced event is queued
3. task completion becomes ready
4. outer loop handles completion first
5. Replaced event is delivered later
```

### Required assertions

- [x] stable lookup removes old-key registry entry;
- [x] peer mapping is removed;
- [x] late `Replaced` does not recreate entry;
- [x] drain terminates; N/A for this specific test's scenario (an ordinary Ok(())
      completion never triggers drain — that's exercised by the existing
      `answer_task_panic_removes_session_and_enters_drain_leaving_other_sessions_intact`
      test instead); the real daemon loop's `shutting_down && sessions_by_id.is_empty()`
      exit condition is unmodified by this task.
- [x] final status has zero sessions.

### Do not

Do not use random task scheduling to try to hit the race.

Add a test-only gate/barrier where necessary.

---

## P0-009 — Replace synthetic answer panic proof with a real spawned-task panic test

### Files

Modify:

```text
crates/p2p-daemon/src/answer/session.rs
crates/p2p-daemon/src/answer/mod.rs
crates/p2p-daemon/src/tests/*
```

### Add test-only hook

Example:

```rust
#[cfg(any(test, debug_assertions))]
#[derive(Clone, Debug)]
pub enum AnswerSessionTestAction {
    None,
    PanicAfterStart,
}
```

Or use an injected receiver/oneshot that can trigger panic inside the real spawned task.

The panic must happen inside:

```text
tokio::spawn(run_answer_session_task(...))
```

not inside the test itself.

### Required end-to-end proof

```text
real task panics
-> JoinHandle returns JoinError
-> FuturesUnordered completion becomes ready
-> registry entry removed
-> primary daemon error recorded
-> shutdown requested
-> remaining sessions drain
-> terminal status attempted
-> daemon returns Err
```

Keep the existing handler-level test if useful, but it is not a substitute.

### Acceptance criteria

- [x] Real spawned answer task panics.
- [x] Test runner itself does not panic.
- [x] Registry is not stranded.
- [x] Other session drains.
- [x] Daemon returns nonzero result.

---

## P0-010 — Fix no-post-shutdown-status regression test boundary

### Files

Modify existing lifecycle/status regression tests.

### Wrong pattern

Do not:

```text
find last WaitingForLocalClient
assert later events are Closed
```

### Required pattern

Immediately before requesting shutdown:

```rust
let boundary = observed_statuses.lock().unwrap().len();
shutdown.request_shutdown();
```

After completion:

```rust
let events = observed_statuses.lock().unwrap();
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

Use current synchronization primitives rather than `std::sync::Mutex` if async test style prefers something else.

### Acceptance criteria

- [x] Boundary is the exact shutdown request action.
- [x] Illegal state after shutdown makes test fail.
- [x] Test fails if P0-005 gate is removed.

---

## P0-011 — Make Android foreground service preserve stop failures end-to-end

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/TunnelForegroundService.kt
```

Add/update tests for service lifecycle behavior.

### `pause()`

Replace failure-then-unconditional-paused behavior.

Recommended:

```kotlin
val stopResult = withContext(ioDispatcher) {
    repository.stop()
}

stopResult.fold(
    onSuccess = {
        reporter.publishStatus(getString(R.string.service_msg_paused))
    },
    onFailure = { error ->
        reporter.publishError(
            message = error.message ?: "Unable to stop tunnel",
            code = "stop_failed",
        )
    },
)
```

### `pauseForPolicy()`

Only set/publish the normal policy-paused state after successful stop.

On failure:

- publish error;
- do not publish the normal policy status;
- retain enough policy context for retry if existing architecture supports it.

### `stopServiceWork()`

The Android foreground service may still stop itself, but:

```text
repository.stop failure
```

must not be followed by:

```text
Tunnel stopped
clean Stopped state
```

Suggested shape:

```kotlin
val stopResult = withContext(ioDispatcher) {
    repository.stop()
}

if (stopResult.isFailure) {
    val error = stopResult.exceptionOrNull()
    reporter.publishError(
        message = error?.message ?: "Unable to stop tunnel cleanly",
        code = "stop_failed",
    )
    notifications.show(
        notifications.buildErrorNotification(
            "Tunnel service stopped after tunnel cleanup failure",
        ),
    )

    // Foreground-service teardown may still continue if required,
    // but do not publish a clean tunnel stop.
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
    return
}

notifications.show(
    notifications.buildStatusNotification(ServiceState.Stopped, "Tunnel stopped"),
)
stopForeground(STOP_FOREGROUND_REMOVE)
stopSelf()
```

Adapt to existing notification APIs.

### Startup cancellation/supersedence

Audit every:

```kotlin
repository.stop()
```

in this service.

Each must intentionally handle `Result`.

### Acceptance criteria

- [x] `pause()` never publishes Paused after stop failure.
- [x] `pauseForPolicy()` never publishes normal policy-paused state after stop failure.
- [x] `stopServiceWork()` never claims clean tunnel stop after failure.
- [x] Startup-cancellation stop failures are visible.
- [x] Tests cover all stop call sites.

---

## P0-012 — Run complete P0 quality gates

After P0 tasks:

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo clippy --workspace --release --all-features -- -D warnings
cargo test --workspace --all-targets --all-features

cd android
./gradlew --no-daemon assembleDebug testDebugUnitTest
```

Also run the dedicated lifecycle tests individually so failures are easy to localize.

Reporting rule:

```text
PASS: command actually executed successfully
FAIL: command executed and failed
NOT RUN: exact reason
```

Do not mark P0 complete while a required locally available gate is unexecuted.

---

# P1 tasks

## P1-001 — Validate existing macOS log directory writability as the service account

### Files

Modify:

```text
scripts/install-launchd-services.sh
packaging/macos/scripts/postinstall
scripts/test-launchd-install-layout.sh
```

### Add separate helper

```bash
require_service_writable_directory() {
  local path="$1"

  sudo -u "$SERVICE_USER" test -x "$path" \
    || fail "service account $SERVICE_USER cannot traverse '$path'"

  sudo -u "$SERVICE_USER" test -w "$path" \
    || fail "service account $SERVICE_USER cannot write '$path'"
}
```

Prefer actual create/delete probe on real macOS:

```bash
require_service_create_delete() {
  local path="$1"
  local probe="$path/.p2ptunnel-write-probe-$$"

  sudo -u "$SERVICE_USER" sh -c 'umask 077; : > "$1"' sh "$probe" \
    || fail "$SERVICE_USER cannot create files in '$path'"

  sudo -u "$SERVICE_USER" rm -f "$probe" \
    || fail "$SERVICE_USER cannot remove files from '$path'"
}
```

### Use for existing log dir

Replace:

```bash
require_service_traverse "$LOG_DIR"
```

with writable/create-delete validation.

### Tests

Add scenario:

```text
existing log directory
root:wheel 0755 or otherwise non-writable by service user
-> installer must fail
```

Do not make the test double always run as the current writable user.

If true user switching cannot be simulated in a local shell test, add:

- a helper-unit test of permission decision logic; and
- a real macOS CI step using a dedicated temporary test account/group where safe.

### Acceptance criteria

- [ ] Existing log dir must be writable by `_p2ptunnel`.
- [ ] Traversable-but-nonwritable directory fails.
- [ ] Standalone installer and pkg script use same policy.
- [ ] Smoke test can detect regression.

---

## P1-002 — Redact quoted multi-word secret values completely

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/SensitiveDataRedactor.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/data/SensitiveDataRedactorTest.kt
```

### Replace token-only field regexes

Prefer one maintained field regex:

```kotlin
private val secretFieldRegex =
    Regex(
        pattern = """(?im)\b(password(?:[_ -][\w-]+)?|token(?:[_ -][\w-]+)?|api[_ -]?key|kex[_ -]?secret|signing[_ -]?key)\b\s*[:=]\s*(\"[^\"]*\"|'[^']*'|[^,\s]+)""",
    )
```

Then:

```kotlin
.replace(secretFieldRegex) { match ->
    "${match.groupValues[1]}=***REDACTED***"
}
```

Keep special multiline redactors such as SDP/decrypted payload where needed.

### Required tests

Use unique sentinels:

```text
password: "alpha secret sentinel"
token='beta secret sentinel'
api key: "gamma secret sentinel"
```

For each:

- assert `***REDACTED***` exists;
- assert every sentinel word is absent;
- assert following unrelated text remains.

Also test idempotence.

### Acceptance criteria

- [ ] Double-quoted multi-word values fully removed.
- [ ] Single-quoted multi-word values fully removed.
- [ ] Unquoted values still redacted.
- [ ] MQTT/MQTTS scheme preservation remains correct.
- [ ] No secret suffix remains after replacement.

---

## P1-003 — Serialize diagnostic error JSON instead of concatenating strings

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SettingsViewModel.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/SettingsViewModelTest.kt
```

### Add serializable error object

```kotlin
@Serializable
private data class StatusDiagnosticsError(
    val status_json_error: String,
)
```

### Error path

```kotlin
fun statusJson(): String =
    runCatching {
        Json.encodeToString(
            SensitiveDataRedactor.redactStatus(
                deps.tunnelRepository.status.value,
            ),
        )
    }.getOrElse { error ->
        Json.encodeToString(
            StatusDiagnosticsError(
                status_json_error =
                    SensitiveDataRedactor.redactText(
                        error.message ?: "unknown status serialization failure",
                    ),
            ),
        )
    }
```

Adapt serializer imports/types to current project.

### Required test

Force an error containing:

```text
quote "
backslash \
newline
password: "secret sentinel"
```

Assert:

- returned text parses as JSON;
- secret sentinel absent;
- escaped data round-trips.

### Acceptance criteria

- [ ] Error path always returns valid JSON.
- [ ] Error text remains redacted.
- [ ] Quotes/backslashes/newlines are escaped correctly.

---

## P1-004 — Make publish-barrier channel failure fail loudly

### Files

Modify test-only barrier code under:

```text
crates/p2p-daemon/tests/two_node_daemon/
```

### Replace

```rust
let _ = entered_tx.send(());
let _ = release_rx.await;
```

### With

```rust
entered_tx
    .send(())
    .expect("publish barrier observer must remain alive");

release_rx
    .await
    .expect("publish barrier release sender must remain alive");
```

Or propagate a test-only error that fails the owning test.

### Acceptance criteria

- [ ] Barrier observer disappearance fails test.
- [ ] Barrier release sender disappearance fails test.
- [ ] Test cannot silently continue without having synchronized.

---

## P1-005 — Add child-process cleanup guards to signal tests

### Files

Modify:

```text
crates/p2p-daemon/src/process_signal.rs
crates/p2p-daemon/tests/process_signal_shutdown.rs
```

### Goal

No timeout/assertion failure leaves a child process alive.

### Suggested guard

```rust
struct ChildGuard {
    child: Option<std::process::Child>,
}

impl ChildGuard {
    fn new(child: std::process::Child) -> Self {
        Self { child: Some(child) }
    }

    fn take(&mut self) -> std::process::Child {
        self.child.take().expect("child already taken")
    }
}

impl Drop for ChildGuard {
    fn drop(&mut self) {
        if let Some(child) = self.child.as_mut() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}
```

Use async equivalent where appropriate.

### Acceptance criteria

- [ ] Ready-marker timeout kills/reaps child.
- [ ] Signal-delivery failure kills/reaps child.
- [ ] Process-exit timeout kills/reaps child.
- [ ] Successful test does not double-kill/reap.

---

## P1-006 — Stop SetupForwardsController from reporting delete success on failure

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupForwardsController.kt
```

Add tests.

### Required behavior

```kotlin
val result = deps.forwardsRepository.delete(forwardId)

if (!result.isValid) {
    access.applyState(
        access.state().copy(
            errorMessage = result.message ?: "Failed to delete forward",
            saveResult = null,
        ),
    )
    return
}

access.applyState(
    access.state().copy(
        errorMessage = null,
        saveResult = "Forward deleted",
    ),
)
```

Use actual `ValidationResult` API.

### Acceptance criteria

- [ ] Delete failure never shows `Forward deleted`.
- [ ] Error message remains visible.
- [ ] Delete success still shows success.
- [ ] Tests cover persistence failure.

---

## P1-007 — Stop setup validation from treating corrupt forwards storage as empty

### Files

Modify callers of:

```text
ForwardsConfigStore.loadForwards()
```

especially setup validation.

Likely files:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SetupStepValidation.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStore.kt
```

### Required policy

Do not use:

```kotlin
loadForwardsResult().getOrElse { emptyList() }
```

for validation or any correctness-sensitive path.

Use:

```kotlin
val forwardsResult = store.loadForwardsResult()

return forwardsResult.fold(
    onSuccess = { forwards ->
        // Existing validation, where empty list is a real empty list.
    },
    onFailure = { error ->
        ValidationResult.invalid(
            "Unable to read forwards configuration: ${error.message ?: "unknown storage error"}",
        )
    },
)
```

Redact error text if it can contain sensitive path/content data.

### Convenience API

The `loadForwards()` convenience method may remain only if:

- callers are explicitly non-critical; and
- its lossy semantics are documented.

Prefer removing it if no valid caller remains.

### Acceptance criteria

- [ ] Corrupt JSON is reported as storage/config error.
- [ ] Permission/read failure is reported as storage/config error.
- [ ] Real empty list still produces no-forwards validation.
- [ ] No correctness-sensitive caller uses lossy fallback.

---

## P1-008 — Make atomic status temporary paths unique per write

### Files

Modify:

```text
crates/p2p-daemon/src/status.rs
```

### Add sequence

```rust
use std::sync::atomic::{AtomicU64, Ordering};

static STATUS_TEMP_SEQUENCE: AtomicU64 = AtomicU64::new(0);
```

Build temp path:

```rust
let sequence = STATUS_TEMP_SEQUENCE.fetch_add(1, Ordering::Relaxed);
let temp_path = parent.join(format!(
    ".{file_name}.tmp-{}-{sequence}",
    std::process::id(),
));
```

Prefer `OpenOptions::create_new(true)` if practical.

### Concurrent test

Run:

```text
4+ writers
4+ readers
many iterations
```

Each writer emits distinct valid status documents.

Readers assert every observed target file parses as complete JSON.

Also assert no temp files remain after successful completion.

### Acceptance criteria

- [ ] Concurrent same-process writes never share a temp path.
- [ ] Readers never observe malformed JSON.
- [ ] Temp files are cleaned on failure.
- [ ] No stale temp files remain after success.

---

## P1-009 — Preserve error detail in reset-configuration failure

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SettingsViewModel.kt
```

### Current problem

Failure becomes only:

```text
Reset failed
```

### Required behavior

At minimum log/store the redacted reason:

```kotlin
result.fold(
    onSuccess = {
        deps.snackbar.show("Configuration reset")
    },
    onFailure = { error ->
        val redacted = SensitiveDataRedactor.redactText(
            error.message ?: "unknown reset failure",
        )
        deps.logger.error("Configuration reset failed: $redacted")
        deps.snackbar.show("Reset failed: $redacted")
    },
)
```

Use actual logging dependency. If detailed user-facing errors are undesirable, keep snackbar generic but preserve detail in diagnostic logs.

### Acceptance criteria

- [ ] Underlying failure reason is not discarded.
- [ ] Sensitive content is redacted.
- [ ] UI still clearly reports failure.

---

## P1-010 — Preserve specific mobile controller lookup failures

### Files

Audit:

```text
crates/p2p-mobile/src/lib.rs
crates/p2p-mobile/src/jni_bridge.rs
crates/p2p-mobile/src/c_abi.rs
```

### Problem

Some paths still collapse distinct failures to:

```text
unknown error
```

### Required scope

Do not redesign the FFI API.

Where a specific error already exists, preserve it:

```text
invalid handle
runtime mutex poisoned
controller unavailable
bridge error recording failed
```

### Acceptance criteria

- [ ] No touched path discards a known specific error for `unknown error`.
- [ ] Existing ABI shape remains compatible.
- [ ] Tests assert specific error text where deterministic.

---

## P1-011 — Run package, service, Android, and platform validation

Run:

```bash
scripts/check-systemd-units.sh
scripts/check-launchd-plists.sh
scripts/test-debian-package.sh
scripts/test-launchd-install-layout.sh
bash -n scripts/*.sh
sh -n packaging/debian/postinst packaging/debian/prerm packaging/debian/postrm
```

On macOS, explicitly verify the new non-writable-existing-log-directory case.

Run Android tests covering:

```text
pause stop failure
policy pause stop failure
service stop failure
startup cancellation stop failure
quoted multi-word secret redaction
valid JSON error serialization
setup delete persistence failure
corrupt forwards storage
```

Report unavailable platform checks as:

```text
NOT RUN: exact reason
```

---

# P2 tasks

## P2-001 — Consider a typed infrastructure-failure category in `DaemonError`

Future cleanup may replace repeated variant matching with:

```rust
impl DaemonError {
    pub fn category(&self) -> DaemonErrorCategory { ... }
}
```

Possible categories:

```text
Session
Infrastructure
Configuration
Transport
ShutdownCleanup
```

Do not add this abstraction in the current pass unless it materially simplifies P0-001 without broad churn.

---

## P2-002 — Remove lossy convenience APIs entirely

Future cleanup may remove APIs like:

```text
loadForwards() -> List
```

when they necessarily hide I/O/parse failure.

The current pass only needs to remove them from correctness-sensitive call sites.

---

## P2-003 — Add generic task-supervision utilities

Offer and answer now have role-specific supervision.

A future pass may extract common supervision primitives only after behavior stabilizes.

Do not abstract prematurely.

---

# Required implementation sequence

```text
Stage 1
  P0-001 active-session infrastructure-failure classification
  P0-002 two-forward worker-failure regression
  P0-003 move fallible offer setup before worker start
  P0-004 return monitor join failures

Stage 2
  P0-007 stable answer completion lookup
  P0-008 deterministic completion-before-Replaced test
  P0-006 answer post-payload shutdown gate
  P0-009 real answer task panic test

Stage 3
  P0-005 offer top-loop shutdown gate
  P0-010 exact shutdown-boundary test
  P0-011 Android foreground service stop truthfulness
  P0-012 P0 quality gates

Stage 4
  P1-001 macOS log-directory writability
  P1-002 quoted multi-word redaction
  P1-003 serialized diagnostics error JSON
  P1-004 loud publish barrier
  P1-005 child cleanup guards

Stage 5
  P1-006 setup delete result handling
  P1-007 forwards storage failure propagation
  P1-008 unique status temp files
  P1-009 reset failure detail
  P1-010 specific mobile errors
  P1-011 final validation
```

Recommended commits:

```text
fix(offer): treat active-session worker death as daemon-fatal
fix(offer): route monitor join failures through finalization
refactor(offer): finish fallible setup before worker runtime
fix(answer): resolve task completion across queued session replacement
fix(answer): reject payload admission after shutdown request
test(answer): panic a real session task and verify supervision
fix(offer): gate steady-state writes on pending shutdown
test(lifecycle): use exact shutdown status boundary
fix(android): preserve stop failures through foreground service state
fix(macos): validate existing log directory writability
fix(android): redact quoted multi-word secrets
fix(android): serialize diagnostics error JSON
fix(test): fail loudly on broken barriers and reap child processes
fix(android): propagate setup persistence failures
fix(status): make atomic temp paths unique per write
```

---

# Final completion checklist

## Offer runtime

- [ ] Active-session worker death is daemon-fatal.
- [ ] Two-forward test proves surviving worker cannot mask failure.
- [ ] Infrastructure failures skip ordinary cooldown/recovery.
- [ ] Monitor join failure returns `Err`.
- [ ] No fallible immutable setup bypasses finalizer after worker start.
- [ ] No steady-state write occurs after shutdown request.

## Answer runtime

- [ ] Normal completion uses stable generation+peer fallback.
- [ ] Completion-before-Replaced race is deterministic and fixed.
- [ ] Late replacement event cannot recreate completed state.
- [ ] Payload ready/shutdown race cannot admit new work.
- [ ] Real spawned answer task panic is supervised end-to-end.

## Android

- [ ] `pause()` preserves stop failure.
- [ ] `pauseForPolicy()` preserves stop failure.
- [ ] `stopServiceWork()` does not claim clean stop after failure.
- [ ] Startup cancellation checks stop result.
- [ ] Setup delete failure is not reported as success.
- [ ] Corrupt forwards storage is not treated as empty.

## macOS

- [ ] Existing log directory is writable by `_p2ptunnel`.
- [ ] Traversable but non-writable directory fails validation.
- [ ] Smoke test detects the permission regression.

## Diagnostics

- [ ] Quoted multi-word secrets are fully removed.
- [ ] Diagnostic error path always returns valid JSON.
- [ ] Error details remain redacted.
- [ ] Reset failure reason is preserved in diagnostics/logs.

## Tests

- [ ] Shutdown status boundary is exact action boundary.
- [ ] Publish barrier failures are loud.
- [ ] Signal-test children are always reaped.
- [ ] Required task-panic test uses a real spawned task.
- [ ] Status atomic-write test covers concurrent writers.

## Quality gates

- [ ] `cargo fmt --all --check` passes.
- [ ] Debug/all-target/all-feature Clippy passes with warnings denied.
- [ ] Release/all-feature Clippy passes with warnings denied.
- [ ] Workspace tests pass.
- [ ] Android assemble + unit tests pass.
- [ ] systemd validation passes.
- [ ] launchd validation passes on macOS.
- [ ] Debian package smoke tests pass.
- [ ] macOS install-layout smoke tests pass.
