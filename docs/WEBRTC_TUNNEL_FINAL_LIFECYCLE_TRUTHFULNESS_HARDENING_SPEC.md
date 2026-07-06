# WebRTC Tunnel Final Lifecycle Truthfulness and Failure-Propagation Hardening Specification

## 1. Document purpose

This specification defines the next corrective hardening pass for:

```text
webrtc_tunnel-master_2607060410.zip
```

It is a follow-up to:

```text
WEBRTC_TUNNEL_RUNTIME_TRUTHFULNESS_PACKAGING_HARDENING_SPEC.md
WEBRTC_TUNNEL_RUNTIME_TRUTHFULNESS_PACKAGING_HARDENING_TODO(1).md
```

The previous hardening pass made substantial and correct architectural improvements. This document does **not** replace those decisions. It addresses the remaining defects found during review of the 2607060410 snapshot.

The focus is narrow:

1. make infrastructure worker failure daemon-fatal in every runtime state;
2. make answer task completion robust to asynchronous session-ID replacement;
3. close the remaining shutdown admission/status races;
4. make Android service-layer state match native stop outcomes;
5. strengthen tests so they prove the claimed behavior instead of merely exercising handlers;
6. close remaining macOS permission, diagnostics-redaction, and quiet-failure gaps.

---

## 2. Current architecture that must be preserved

The following architecture is accepted and must remain unchanged:

```text
same foreground binaries
        |
        +-- manual shell / Ctrl-C
        +-- systemd / SIGTERM
        +-- launchd / SIGTERM
        +-- Docker / SIGTERM
        +-- Android / ShutdownToken
        +-- tests / ShutdownToken
```

Do not add:

- `--daemon`;
- process forking;
- PID files;
- systemd inside containers;
- systemd/launchd dependencies in the daemon core;
- a hidden daemon-core forced-abort timeout;
- a new signaling wire protocol;
- compatibility fallbacks that reinterpret errors as normal states.

The following previous changes are also accepted:

- `DaemonRuntimePhase::{Starting, Running, Draining, Closed}`;
- independent answer task completion observation;
- strict terminal status writes;
- atomic status replacement;
- removal of premature `sd_notify READY=1` support;
- package-specific Debian `/usr/bin` units;
- explicit Android native stop outcomes;
- typed `p2pctl status` parsing;
- `p2pctl check-config` authorization parity;
- least-privilege CI permissions.

Do not undo those improvements to solve the remaining defects.

---

## 3. Primary invariant: infrastructure failure is not a session outcome

The project currently distinguishes poorly between:

```text
ordinary session outcome
```

and:

```text
runtime infrastructure failure
```

That distinction must become explicit.

### 3.1 Ordinary session outcomes

Examples:

- remote session closes;
- probe failure;
- signaling timeout;
- local client disconnects;
- reconnect exhaustion;
- protocol/session error scoped to one session.

These may legitimately flow through:

```text
session result
    -> cooldown classification
    -> recover_daemon_after_session(...)
    -> return to waiting state
```

### 3.2 Infrastructure failures

Examples:

- offer accept worker dies unexpectedly;
- offer worker supervisor channel closes unexpectedly;
- offer worker monitor task panics;
- answer session task panics;
- answer completion stream cannot be trusted;
- mandatory finalization worker join fails.

These must flow through:

```text
infrastructure failure
        |
        v
record primary daemon error
        |
        v
request cooperative shutdown
        |
        v
stop/drain remaining work
        |
        v
attempt strict terminal status
        |
        v
return nonzero
```

An infrastructure failure must never be fed into ordinary session recovery merely because it happened while a session was active.

---

## 4. Offer worker failure policy

### 4.1 Required behavior while idle

The current idle behavior is approximately correct:

```text
worker exit event
    -> no shutdown requested
    -> OfferAcceptWorkerFailed
    -> daemon exits through finalizer
```

Preserve it.

### 4.2 Required behavior during an active session

The current active-session path is incorrect:

```text
worker dies
    -> run_offer_session returns OfferAcceptWorkerFailed
    -> outer daemon treats it as an ordinary session result
    -> cooldown/recovery may run
    -> daemon can continue if another listener worker is alive
```

Required behavior:

```text
worker dies during active session
    -> run_offer_session returns infrastructure error
    -> outer daemon classifies error as daemon-fatal
    -> skip probe cooldown
    -> skip recover_daemon_after_session
    -> break run loop with Err
    -> finalizer requests shutdown
    -> remaining workers stop
    -> monitor joins are observed
    -> terminal status attempted
    -> daemon returns Err
```

### 4.3 Error classification

Prefer an explicit helper rather than scattered pattern matches:

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

If the exact variant set differs, preserve the semantic rule.

The outer offer loop should handle the active-session result approximately as follows:

```rust
let result = run_offer_session(...).await;

if shutdown.is_shutdown_requested() {
    if let Err(error) = &result {
        tracing::warn!(reason = %error, "offer session ended with error during shutdown");
    }
    break Ok(());
}

if let Err(error) = &result {
    if is_offer_infrastructure_failure(error) {
        break Err(result.expect_err("matched Err above"));
    }
}

// Ordinary session policy only below this point.
if cooldown::session_outcome_enters_cooldown(&result) {
    // existing cooldown handling
} else {
    probe_cooldown.reset();
}

recover_daemon_after_session(&ctx, result).await;
```

Avoid `expect` if a cleaner match is available:

```rust
match result {
    Err(error) if is_offer_infrastructure_failure(&error) => break Err(error),
    ordinary_result => {
        // cooldown + ordinary recovery
    }
}
```

### 4.4 Multi-forward test requirement

A one-worker test is insufficient because channel closure can eventually fail the daemon even if the active-session classification is wrong.

The required regression test must use at least two configured offer forwards:

```text
worker A alive
worker B alive
        |
        v
active session established
        |
        v
force worker A failure
        |
        +-- worker B remains alive
        |
        v
daemon must still fail immediately
        |
        v
worker B receives cooperative shutdown
```

This test is mandatory.

---

## 5. Offer finalization and worker monitor joins

### 5.1 Finalization is mandatory after worker runtime starts

Once `OfferAcceptRuntime` exists, every exit must pass through one finalizer.

The simplest structural rule is:

```text
all fallible setup that can be completed before worker start
        |
        v
start accept runtime
        |
        v
run_result future
        |
        v
one finalizer
```

The following should be moved before accept-worker start where practical:

- `offer_remote_peer_id(&config)`;
- authorized peer lookup;
- any other immutable config-derived lookup that can return `Err`.

This prevents post-worker-start `?` from escaping the finalizer.

### 5.2 Top-of-loop shutdown gate

The offer loop must check shutdown before writing ordinary steady state:

```rust
loop {
    if shutdown.is_shutdown_requested() {
        break Ok(());
    }

    write_steady_state_status(&ctx).await;

    tokio::select! {
        biased;
        // ...
    }
}
```

The runtime phase alone is insufficient because the phase may remain `Running` until the finalizer begins.

### 5.3 Worker monitor join failures

Current warning-only behavior is not sufficient.

Required:

```rust
async fn stop_and_join_offer_accept_runtime(
    runtime: OfferAcceptRuntime,
) -> Result<(), DaemonError>
```

Every monitor must be awaited.

Policy:

```text
monitor completed normally during requested shutdown
    -> success

monitor returned recorded worker outcome
    -> already accounted for by runtime outcome

monitor JoinError::cancelled because explicit teardown policy did so
    -> only success if cancellation is an intentional, documented path

monitor panic / unexpected JoinError
    -> cleanup error
```

A monitor panic must not become:

```text
warning + Ok(())
```

### 5.4 Result precedence

Preserve primary failure:

```text
run_result Err + worker_result Err + closed_result Err
    -> return run_result Err
    -> log worker_result as secondary
    -> log closed_result as secondary

run_result Ok + worker_result Err
    -> return worker_result Err

run_result Ok + worker_result Ok + closed_result Err
    -> return closed_result Err
```

---

## 6. Answer task completion must use stable identity

### 6.1 Problem

The new independent completion architecture is correct, but session IDs can change asynchronously through `AnswerSessionEvent::Replaced`.

Race:

```text
session changes old_id -> new_id internally
        |
        v
Replaced event queued
        |
        v
session task completes
        |
        v
completion future ready
        |
        v
biased select handles completion first
        |
        v
registry still keyed by old_id
        |
        v
lookup only by final_session_id(new_id) misses
        |
        v
completion consumed as stale
        |
        v
Replaced event later installs new_id
        |
        v
registry entry remains forever
```

### 6.2 Stable identity

The stable identity is:

```text
SessionGeneration + remote PeerId
```

Use final session ID as a fast path, then fall back to stable identity.

Recommended helper:

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

Use this for normal task completion and join failure.

### 6.3 Completion/replacement ordering test

The test must force the exact ordering:

```text
Replaced event queued but not handled
        |
        v
task completion handled first
        |
        v
stable lookup finds old registry key
        |
        v
entry removed
        |
        v
late Replaced event is ignored as stale
        |
        v
registry remains empty
```

Do not rely on scheduler probability.

Add a deterministic test hook/barrier around:

- replacement-event send;
- task return;
- outer event delivery.

---

## 7. Answer shutdown admission race

The answer select guards the payload branch with `if !shutting_down`, but shutdown can be requested after the payload future becomes ready and before the branch processes it.

Required second admission gate:

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

    handle_answer_daemon_payload(..., payload).await;
}
```

Once the shared token is requested, no new answer session may be admitted.

The outer event loop must still process existing session events and task completions while draining.

---

## 8. Status truthfulness tests need an exact boundary

A test that finds the last normal status and then checks later states can pass even when the daemon emitted an illegal normal state after shutdown.

Required methodology:

```text
record status event count N
        |
        v
request shutdown
        |
        v
collect events[N..]
        |
        v
assert none are normal active states
```

Forbidden after the boundary:

```text
Serving
WaitingForLocalClient
Negotiating
TunnelOpen
Probing
```

The boundary must be the exact test action that requests shutdown.

Do not derive the boundary from the emitted state sequence itself.

---

## 9. Real answer task panic testing

The current handler-level test that manually constructs:

```rust
AnswerTaskCompletion {
    outcome: Err("panic"),
}
```

is useful as a unit test, but it does not prove task supervision.

A required integration-level test must:

```text
spawn real answer session task
        |
        v
test hook panics inside task
        |
        v
JoinHandle observes panic
        |
        v
completion stream emits join failure
        |
        v
registry entry removed
        |
        v
other sessions receive shutdown and drain
        |
        v
strict terminal status attempted
        |
        v
daemon returns Err
```

Use a debug/test-only hook. Do not add a production panic path.

---

## 10. Android stop truthfulness is end-to-end, not native-only

The Rust runtime now returns meaningful stop failures. That work is incomplete until every Kotlin consumer preserves the failure.

### 10.1 Required invariant

```text
native stop Result.failure
    must never be followed by
Paused / Stopped / policy-blocked-as-normal status
```

### 10.2 `pause()`

Required:

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

Do not publish `Paused` after failure.

### 10.3 `pauseForPolicy()`

Policy status may be published only after stop succeeds.

On failure:

- preserve stop error;
- do not claim the tunnel is safely paused;
- do not call the state normal just because a policy block was requested.

### 10.4 `stopServiceWork()`

Android may still need to stop the foreground service process, but the final tunnel status must preserve the failure.

Distinguish:

```text
foreground service stopped
```

from:

```text
tunnel stopped cleanly
```

If tunnel stop fails:

- publish error;
- do not show a clean `Tunnel stopped` notification;
- do not overwrite repository/native error state;
- service teardown may continue only with explicit error wording.

### 10.5 Start cancellation/supersedence

Any `repository.stop()` used to cancel a superseded startup must inspect its result.

Do not write:

```kotlin
repository.stop()
```

when the returned failure is semantically important.

---

## 11. macOS existing log directory validation

New log directories are created correctly, but existing directories are only checked for traversal.

Required helpers:

```bash
require_service_traverse() {
  local path="$1"
  sudo -u "$SERVICE_USER" test -x "$path" \
    || fail "$SERVICE_USER cannot traverse '$path'"
}

require_service_writable_directory() {
  local path="$1"
  sudo -u "$SERVICE_USER" test -x "$path" \
    || fail "$SERVICE_USER cannot traverse '$path'"
  sudo -u "$SERVICE_USER" test -w "$path" \
    || fail "$SERVICE_USER cannot write '$path'"
}
```

Prefer an actual create/delete probe when running on real macOS:

```bash
probe="$path/.p2ptunnel-write-probe-$$"
sudo -u "$SERVICE_USER" sh -c 'umask 077; : > "$1"' sh "$probe" \
  || fail "$SERVICE_USER cannot create files in '$path'"
sudo -u "$SERVICE_USER" rm -f "$probe" \
  || fail "$SERVICE_USER cannot remove files from '$path'"
```

Use the writable check for `/Library/Logs/P2PTunnel`.

The smoke test must include an existing non-writable directory scenario.

---

## 12. Diagnostic redaction must handle quoted multi-word values

Current `\S+` patterns can leak suffixes of quoted values.

Example:

```text
password: "super secret sentinel"
```

can become:

```text
password=***REDACTED*** secret sentinel"
```

Required value grammar:

```text
"double quoted value"
'single quoted value'
unquoted token
```

Use quoted alternatives before the unquoted token.

Example Kotlin pattern:

```kotlin
private val secretFieldRegex = Regex(
    pattern = """(?im)\b(password(?:[_ -][\w-]+)?|token(?:[_ -][\w-]+)?|api[_ -]?key|kex[_ -]?secret|signing[_ -]?key)\b\s*[:=]\s*(\"[^\"]*\"|'[^']*'|[^,\s]+)""",
)
```

Replacement should remove the entire value:

```kotlin
.replace(secretFieldRegex) { match ->
    "${match.groupValues[1]}=***REDACTED***"
}
```

Required tests use unique multi-word sentinels and assert every sentinel word is absent.

---

## 13. Diagnostics error JSON must be serialized

Do not manually concatenate JSON:

```kotlin
"{\"status_json_error\":\"" + message + "\"}"
```

Error text can contain quotes, backslashes, newlines, and control characters.

Use the serializer for success and failure.

Example:

```kotlin
@Serializable
private data class StatusDiagnosticsError(
    val status_json_error: String,
)
```

Then:

```kotlin
Json.encodeToString(
    StatusDiagnosticsError(
        status_json_error = SensitiveDataRedactor.redactText(errorText),
    ),
)
```

Add a test containing:

```text
quote: "
backslash: \
newline
```

and parse the returned JSON successfully.

---

## 14. Test-only synchronization failures must be loud

For deterministic barriers, these are not acceptable:

```rust
let _ = entered_tx.send(());
let _ = release_rx.await;
```

A broken test barrier means the test no longer proves its claim.

Use:

```rust
entered_tx
    .send(())
    .expect("publish barrier observer must remain alive");

release_rx
    .await
    .expect("publish barrier release sender must remain alive");
```

or propagate a dedicated test error.

---

## 15. Child process tests need cleanup guards

Every spawned signal-test child must be killed and reaped if:

- ready-marker wait fails;
- timeout expires;
- an assertion panics;
- signal delivery fails.

Use RAII or explicit scope guards.

Conceptual Rust:

```rust
struct ChildGuard {
    child: Option<std::process::Child>,
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

The exact async/sync implementation may differ.

No test failure should leave a child blocked forever in `wait_for_process_shutdown_signal()`.

---

## 16. Setup UI persistence failures must remain visible

### 16.1 Delete forward

Do not ignore:

```kotlin
val result = forwardsRepository.delete(forwardId)
```

Required:

```kotlin
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

### 16.2 Corrupt forwards storage

Setup validation must not call a convenience API that maps read/parse failure to `emptyList()`.

Use `loadForwardsResult()` and distinguish:

```text
Ok(emptyList())
    -> no forwards configured

Err(error)
    -> configuration storage failure
```

Do not tell the user to add a forward when the actual problem is that the existing file cannot be read or parsed.

---

## 17. Status temp files should be unique per write

The existing atomic replacement is a correct improvement, but PID-only temp names collide under concurrent same-process writers.

Use:

```text
PID + monotonic atomic sequence
```

Example:

```rust
static STATUS_TEMP_SEQUENCE: AtomicU64 = AtomicU64::new(0);

let sequence = STATUS_TEMP_SEQUENCE.fetch_add(1, Ordering::Relaxed);
let temp_path = parent.join(format!(
    ".{file_name}.tmp-{}-{sequence}",
    std::process::id(),
));
```

Prefer `create_new(true)` if practical.

Add a concurrent multi-writer/multi-reader test:

```text
multiple writers repeatedly write valid distinct status documents
multiple readers repeatedly parse target
assert every read is complete valid JSON
```

---

## 18. Error specificity cleanup

This is lower priority but should be addressed where touched.

Avoid:

```text
unknown error
```

when a more specific failure exists.

Examples:

- invalid handle;
- controller missing;
- runtime mutex poisoned;
- bridge error recording failed.

Do not redesign the entire FFI surface in this pass. Preserve the most specific error available at current boundaries.

---

## 19. Testing philosophy

### 19.1 Synchronization versus watchdog

Allowed:

```text
explicit event/barrier = synchronization
short timeout = watchdog
```

Forbidden:

```text
sleep 300 ms = synchronization
```

### 19.2 Handler test versus integration proof

Both may be useful, but do not confuse them.

Example:

```text
manual AnswerTaskCompletion Err
    proves completion handler policy

real spawned task panic
    proves task supervision path
```

A TODO acceptance criterion about the full path requires the second test.

### 19.3 Negative controls

Where practical, make regression tests fail if the key fix is removed.

Examples:

- two-forward worker death test fails if infrastructure errors are treated as recoverable;
- completion/replacement test fails if lookup uses final session ID only;
- shutdown-boundary test fails if a normal status is emitted after the exact request boundary.

---

## 20. Out of scope

Do not implement in this pass unless required by a concrete discovered bug:

- real `sd_notify` readiness;
- systemd watchdogs;
- second-signal forced process exit;
- bridge-task cooperative teardown redesign;
- debhelper migration;
- signed/notarized macOS installer;
- protocol changes;
- wire-format changes;
- new fallback modes.

---

## 21. Required implementation order

```text
Stage 1 — offer runtime correctness
  1. classify infrastructure failures
  2. make active-session worker death fatal
  3. move fallible immutable setup before worker start
  4. return monitor join failures
  5. add two-forward regression test

Stage 2 — answer runtime correctness
  6. stable completion lookup across replacement
  7. deterministic completion-before-Replaced test
  8. answer post-payload shutdown gate
  9. real answer task panic test

Stage 3 — status/shutdown test trust
  10. offer top-loop shutdown gate
  11. exact shutdown event boundary test
  12. loud test barriers
  13. child process cleanup guards
  14. unique atomic status temp files

Stage 4 — Android end-to-end truthfulness
  15. pause stop-result handling
  16. policy pause stop-result handling
  17. service stop-result handling
  18. startup cancellation stop-result handling

Stage 5 — macOS and diagnostics
  19. existing log directory writability
  20. macOS negative permission test
  21. quoted multi-word secret redaction
  22. serialized diagnostic error JSON
  23. setup delete/storage failure propagation

Stage 6 — final quality gates
```

---

## 22. Definition of done

The hardening pass is complete only when all of the following are true:

### Offer

- worker death is daemon-fatal both idle and active;
- a second healthy worker cannot mask failure of the first;
- monitor join failure cannot be reduced to warning + success;
- no normal status is written after shutdown request;
- all post-worker-start exits pass through finalization.

### Answer

- completion cannot be lost because a `Replaced` event is still queued;
- no new payload is admitted after shutdown request;
- a real task panic is observed through the actual spawned-task path;
- panic cannot strand registry state.

### Android

- native stop failure is not overwritten by Paused/Stopped/policy-normal state;
- every service-layer stop consumer checks the result;
- forced service teardown is not described as clean tunnel shutdown.

### macOS

- existing log directory is proven writable by `_p2ptunnel`;
- smoke tests include a non-writable existing directory failure case.

### Diagnostics

- quoted multi-word secret values are fully redacted;
- diagnostic error JSON is always valid JSON;
- setup persistence failures are not converted to success or empty configuration.

### Tests

- no regression test derives its shutdown boundary from the state sequence being tested;
- no required panic test merely injects a synthetic completion error;
- no barrier silently ignores broken synchronization channels;
- no signal-test failure leaks child processes.

### Quality gates

Run and report honestly:

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo clippy --workspace --release --all-features -- -D warnings
cargo test --workspace --all-targets --all-features

cd android
./gradlew --no-daemon assembleDebug testDebugUnitTest

scripts/check-systemd-units.sh
scripts/check-launchd-plists.sh
scripts/test-debian-package.sh
scripts/test-launchd-install-layout.sh
```

For a platform-specific gate that truly cannot run:

```text
NOT RUN: exact reason
```

Never report an unexecuted gate as passed.
