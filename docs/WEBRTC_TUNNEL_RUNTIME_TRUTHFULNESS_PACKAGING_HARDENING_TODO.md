# WebRTC Tunnel Runtime Truthfulness, Task Supervision, Packaging, and Diagnostics Hardening TODO

## 0. Instructions for Claude Code

Implement this TODO against:

```text
webrtc_tunnel-master_2607052048.zip
```

Read first:

```text
WEBRTC_TUNNEL_RUNTIME_TRUTHFULNESS_PACKAGING_HARDENING_SPEC.md
crates/p2p-daemon/src/offer/mod.rs
crates/p2p-daemon/src/offer/session/mod.rs
crates/p2p-daemon/src/answer/mod.rs
crates/p2p-daemon/src/answer/session.rs
crates/p2p-daemon/src/types.rs
crates/p2p-daemon/src/signaling.rs
crates/p2p-daemon/src/status.rs
crates/p2p-daemon/src/process_signal.rs
bins/p2p-offer/src/main.rs
bins/p2p-answer/src/main.rs
bins/p2pctl/src/main.rs
crates/p2p-mobile/src/runtime/mod.rs
crates/p2p-mobile/src/runtime/state.rs
crates/p2p-mobile/src/runtime/log_bridge.rs
android/app/src/main/java/com/phillipchin/webrtctunnel/data/SensitiveDataRedactor.kt
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SettingsViewModel.kt
scripts/build-deb.sh
scripts/install-launchd-services.sh
packaging/debian/*
packaging/macos/scripts/*
.github/workflows/ci.yml
```

### Priority scale

```text
P0 = correctness/release blocker; can hang shutdown, lie about state, ship a broken package, or silently omit required verification
P1 = high-priority hardening; failure visibility, diagnostics, CLI truthfulness, and security hygiene
P2 = future improvement; useful but not required for this hardening pass
```

### Non-negotiable rules

- Preserve the foreground-process architecture.
- Do not add `--daemon`, forking, PID files, or systemd inside Docker.
- Preserve the current signaling/crypto/identity/wire protocol.
- Do not silently reinterpret a failure as empty/default/success.
- Do not use a timing sleep as synchronization when a deterministic event hook can be added.
- Do not make a required CI test self-skip because its prerequisite was never provisioned.
- Do not send SIGTERM/SIGINT to the Cargo test runner itself.
- Do not add a hidden daemon-core forced-abort timeout.
- Do not mark a forced Android abort as a clean stop.
- Do not bless known secret leaks as “documented behavior.”
- Do not make a package pass build while its units point at executables absent from that package.
- Commit incrementally in the dependency order at the end of this file.

---

# P0 tasks

## P0-001 — Add an explicit daemon runtime phase and centrally suppress false normal status writes

### Files

Modify:

```text
crates/p2p-daemon/src/types.rs
crates/p2p-daemon/src/signaling.rs
crates/p2p-daemon/src/answer/mod.rs
crates/p2p-daemon/src/offer/mod.rs
```

### Problem

Normal status helpers can still write:

```text
Serving
WaitingForLocalClient
Negotiating
other active session states
```

after shutdown has already started.

The offer daemon also writes `WaitingForLocalClient` before listener binding succeeds.

### Required design

Add:

```rust
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) enum DaemonRuntimePhase {
    Starting,
    Running,
    Draining,
    Closed,
}
```

Add to `DaemonRuntimeState`:

```rust
pub(crate) phase: DaemonRuntimePhase,
```

Recommended initialization:

```rust
impl DaemonRuntimeState {
    pub(crate) fn new_connected() -> Self {
        Self {
            mqtt_connected: true,
            last_transport_failure_at_ms: None,
            forward_statuses: Vec::new(),
            phase: DaemonRuntimePhase::Starting,
        }
    }
}
```

### Normal status suppression

Add a helper:

```rust
fn runtime_status_allowed(ctx: &RuntimeContext<'_>) -> bool {
    matches!(ctx.runtime.phase, DaemonRuntimePhase::Running)
}
```

Normal status helpers must return without writing when phase is not `Running`.

For example:

```rust
pub(crate) async fn write_daemon_status(
    ctx: &RuntimeContext<'_>,
    snapshot: StatusSnapshot,
) {
    if !runtime_status_allowed(ctx) {
        tracing::debug!(
            phase = ?ctx.runtime.phase,
            state = ?snapshot.current_state,
            "suppressing nonterminal status outside Running phase",
        );
        return;
    }

    // Existing construction + best-effort write.
}
```

Do the same for ordinary answer status writes.

### Required phase transitions

Answer:

```rust
ctx.runtime.phase = DaemonRuntimePhase::Running;
write_answer_registry_status(...).await;
```

only after startup is actually complete.

On shutdown/fatal task failure:

```rust
ctx.runtime.phase = DaemonRuntimePhase::Draining;
```

Before strict terminal write:

```rust
ctx.runtime.phase = DaemonRuntimePhase::Closed;
```

Offer must not enter `Running` or write waiting state until:

- broker subscription succeeded;
- required remote peer authorization succeeded;
- at least one listener bound;
- accept runtime started.

### Acceptance criteria

- [x] `DaemonRuntimePhase` exists.
- [x] Normal status helpers do not write in `Starting`, `Draining`, or `Closed`.
- [x] Offer does not write `WaitingForLocalClient` before listener startup is complete.
- [x] Answer does not write `Serving` before startup is complete.
- [x] Publish/transport helpers during drain do not resurrect ordinary runtime states.
- [x] Focused tests prove status remains non-normal from shutdown request through final `Closed`.

---

## P0-002 — Refactor the offer daemon so every post-listener-start exit goes through one finalizer

### Files

Modify:

```text
crates/p2p-daemon/src/offer/mod.rs
crates/p2p-daemon/src/error.rs
```

### Problem

This branch can race shutdown:

```rust
client = accept_runtime.accepted_clients.recv() => {
    let client = client
        .ok_or_else(|| DaemonError::Logging("offer accept loop stopped".to_owned()))??;
}
```

If channel close wins the select, `?` returns before:

```rust
join_offer_accept_tasks(...).await;
write_offer_closed_status(...).await;
```

### Required structure

After `accept_runtime` exists, do not return directly from the run loop.

Recommended shape:

```rust
let run_result: Result<(), DaemonError> = async {
    loop {
        if shutdown.is_shutdown_requested() {
            break Ok(());
        }

        tokio::select! {
            biased;

            _ = shutdown.cancelled() => {
                tracing::info!("offer daemon shutdown requested");
                break Ok(());
            }

            client = accept_runtime.accepted_clients.recv() => {
                let Some(client) = client else {
                    if shutdown.is_shutdown_requested() {
                        break Ok(());
                    }
                    break Err(DaemonError::Logging(
                        "all offer accept workers stopped unexpectedly".to_owned(),
                    ));
                };

                let client = match client {
                    Ok(client) => client,
                    Err(error) => break Err(error.into()),
                };

                // Second admission gate: select readiness is not enough.
                if shutdown.is_shutdown_requested() {
                    drop(client);
                    break Ok(());
                }

                // Existing cooldown/session handling.
            }

            payload = poll_idle_signal_payload(&mut ctx, transport) => {
                if shutdown.is_shutdown_requested() {
                    break Ok(());
                }
                // Existing payload handling.
            }
        }
    }
}
.await;

ctx.runtime.phase = DaemonRuntimePhase::Draining;
shutdown.request_shutdown();

let worker_result = stop_and_join_offer_accept_runtime(accept_runtime).await;
ctx.runtime.phase = DaemonRuntimePhase::Closed;
let closed_result = write_offer_closed_status_required(&mut ctx).await;

merge_offer_run_and_cleanup_results(run_result, worker_result, closed_result)
```

### Error precedence helper

Use explicit precedence. Example:

```rust
fn merge_offer_run_and_cleanup_results(
    run_result: Result<(), DaemonError>,
    worker_result: Result<(), DaemonError>,
    closed_result: Result<(), DaemonError>,
) -> Result<(), DaemonError> {
    match run_result {
        Err(primary) => {
            if let Err(error) = worker_result {
                tracing::error!(reason = %error, "offer worker cleanup also failed");
            }
            if let Err(error) = closed_result {
                tracing::error!(reason = %error, "offer terminal status also failed");
            }
            Err(primary)
        }
        Ok(()) => worker_result.and(closed_result),
    }
}
```

A small equivalent helper is fine. Preserve the primary runtime error.

### Acceptance criteria

- [x] `accepted_clients.recv() == None` during requested shutdown exits cleanly.
- [x] Unexpected receiver close is an error.
- [x] No post-start `?` can bypass the finalizer.
- [x] Shutdown is checked before waiting and after work becomes ready.
- [x] Listener shutdown/join is attempted on every post-start exit.
- [x] Strict terminal `Closed` write is attempted on every post-start exit.
- [x] Primary error is preserved if cleanup also fails. (No P0-002-era secondary error source yet exists — join_offer_accept_tasks logs but doesn't return Result; the actual merge-with-precedence helper lands in P0-006, once the terminal writer becomes Result-returning.)

---

## P0-003 — Supervise offer accept workers while the daemon is running

### Files

Modify:

```text
crates/p2p-daemon/src/offer/mod.rs
crates/p2p-daemon/src/offer/session/mod.rs
crates/p2p-daemon/src/error.rs
```

### Problem

The daemon currently stores worker `JoinHandle`s and checks them only during final shutdown.

A worker can panic while another worker keeps the channel alive, leaving status falsely `Listening`.

### Required policy

After successful bind/start:

```text
unexpected accept-worker exit = daemon-fatal infrastructure failure
```

Partial bind failure remains allowed.

### Recommended event model

Add:

```rust
#[derive(Debug)]
enum OfferAcceptLoopExitReason {
    Shutdown,
    ClientQueueClosed,
}

#[derive(Debug)]
struct OfferAcceptTaskExit {
    forward_id: String,
    outcome: Result<OfferAcceptLoopExitReason, String>,
}
```

Refactor the accept loop into an async function that returns a reason:

```rust
async fn run_offer_accept_loop(
    listener: OfferListener,
    tx: mpsc::Sender<Result<OfferClient, p2p_tunnel::TunnelError>>,
    mut shutdown: ShutdownToken,
) -> OfferAcceptLoopExitReason {
    loop {
        tokio::select! {
            _ = shutdown.cancelled() => {
                return OfferAcceptLoopExitReason::Shutdown;
            }
            accepted = listener.accept_client() => {
                // Existing queue-full and retry behavior.
                // Return ClientQueueClosed only when tx is closed.
            }
        }
    }
}
```

Wrap each worker JoinHandle with a completion monitor that captures panic/join failure and sends `OfferAcceptTaskExit`.

Conceptual snippet:

```rust
let worker = tokio::spawn(run_offer_accept_loop(
    listener,
    client_tx,
    task_shutdown,
));

let forward_id_for_monitor = forward_id.clone();
monitors.push(tokio::spawn(async move {
    let outcome = match worker.await {
        Ok(reason) => Ok(reason),
        Err(error) => Err(error.to_string()),
    };

    if exit_tx
        .send(OfferAcceptTaskExit {
            forward_id: forward_id_for_monitor.clone(),
            outcome,
        })
        .is_err()
    {
        tracing::error!(
            forward_id = %forward_id_for_monitor,
            "offer accept worker exit could not be delivered to supervisor",
        );
    }
}));
```

Use an unbounded exit channel or another channel that cannot deadlock a worker monitor during failure reporting.

### Observe worker exits while idle and active

`OfferAcceptRuntime` should include:

```rust
worker_exits: mpsc::UnboundedReceiver<OfferAcceptTaskExit>,
monitors: Vec<JoinHandle<()>>,
```

The outer daemon select must observe `worker_exits.recv()`.

`OfferSessionIo` must also receive access to this stream so an active session notices worker death promptly.

Recommended session branch:

```rust
exit = io.worker_exits.recv() => {
    let Some(exit) = exit else {
        return Err(DaemonError::Logging(
            "offer accept-worker supervisor channel closed unexpectedly".to_owned(),
        ));
    };

    if shutdown.is_shutdown_requested() {
        return Ok(());
    }

    return Err(DaemonError::OfferAcceptWorkerFailed {
        forward_id: exit.forward_id,
        reason: format!("{:?}", exit.outcome),
    });
}
```

Add a specific `DaemonError` variant rather than hiding this inside a generic log error.

### Final join policy

`stop_and_join_offer_accept_runtime(...)` must:

- request/observe shutdown;
- await every monitor;
- treat monitor panic/join failure as an error;
- not silently warn and return `Ok`.

### Acceptance criteria

- [x] Worker exit/panic is observed during idle daemon operation.
- [x] Worker exit/panic is observed during an active offer session.
- [x] Unexpected worker death is daemon-fatal.
- [x] Remaining workers receive cooperative shutdown.
- [x] Worker monitor join failures are returned or logged as secondary errors.
- [x] Status cannot remain `Listening` after an undetected worker death.
- [x] Deterministic test hook can force one worker failure.

---

## P0-004 — Replace answer self-reported `Ended` completion with independently observed task completion

### Files

Modify:

```text
crates/p2p-daemon/Cargo.toml
crates/p2p-daemon/src/types.rs
crates/p2p-daemon/src/answer/mod.rs
crates/p2p-daemon/src/answer/session.rs
```

### Problem

If an answer session task panics before sending `AnswerSessionEvent::Ended`, the registry entry can remain forever and shutdown drain can hang.

The current normal completion path also does:

```rust
handle.task.abort();
```

after the task reports it ended.

### Dependency

Add the existing workspace dependency:

```toml
futures-util.workspace = true
```

Do not add a new cancellation library.

### Session task return value

Change:

```rust
pub(crate) async fn run_answer_session_task(...) {
    ...
    cleanup_active_session(...).await;
    event_tx.send(Ended { ... }).await;
}
```

to:

```rust
pub(crate) async fn run_answer_session_task(
    deps: AnswerSessionTaskDeps,
    mut inbound: mpsc::Receiver<DecodedSignal>,
    generation: SessionGeneration,
    mut session: ActiveSession,
    shutdown: ShutdownToken,
) -> AnswerSessionTaskResult {
    let result = run_answer_session_task_inner(
        &deps,
        &mut inbound,
        generation,
        &mut session,
        shutdown,
    )
    .await;

    if let Err(error) = &result {
        tracing::warn!(
            reason = %error,
            session_id = %session.session_id,
            remote_peer_id = %session.remote_peer_id,
            "answer session failed",
        );
    }

    cleanup_active_session(&mut session).await;

    AnswerSessionTaskResult {
        final_session_id: session.session_id,
        generation,
        remote_peer_id: session.remote_peer_id.clone(),
        result,
    }
}
```

Add:

```rust
pub(crate) struct AnswerSessionTaskResult {
    pub(crate) final_session_id: SessionId,
    pub(crate) generation: SessionGeneration,
    pub(crate) remote_peer_id: PeerId,
    pub(crate) result: Result<(), DaemonError>,
}

pub(crate) struct AnswerTaskCompletion {
    pub(crate) initial_session_id: SessionId,
    pub(crate) generation: SessionGeneration,
    pub(crate) remote_peer_id: PeerId,
    pub(crate) outcome: Result<AnswerSessionTaskResult, String>,
}
```

### Completion futures

Maintain:

```rust
use futures_util::{FutureExt, StreamExt, stream::FuturesUnordered};

let mut session_completions = FuturesUnordered::new();
```

At spawn:

```rust
let initial_session_id = session_id;
let completion_remote_peer_id = remote_peer_id.clone();
let task = tokio::spawn(run_answer_session_task(
    deps,
    inbound_rx,
    generation,
    session,
    shutdown.clone(),
));

session_completions.push(
    async move {
        let outcome = task.await.map_err(|error| error.to_string());
        AnswerTaskCompletion {
            initial_session_id,
            generation,
            remote_peer_id: completion_remote_peer_id,
            outcome,
        }
    }
    .boxed(),
);
```

The registry handle should no longer own the normal-completion `JoinHandle`.

Remove `AnswerSessionEvent::Ended` after all call sites/tests are migrated.

### Completion select branch

Add:

```rust
completion = session_completions.next(), if !session_completions.is_empty() => {
    let completion = completion.expect("guarded by is_empty");
    handle_answer_task_completion(..., completion).await;
}
```

Do not use `expect` if the stream API/guard can still legally return `None`; handle it explicitly if necessary.

### Registry lookup after session replacement

A pending same-peer replacement can change the map key. For join failure, locate the registry entry by stable identity:

```rust
fn find_session_id_by_generation_and_peer(
    sessions: &HashMap<SessionId, AnswerSessionHandle>,
    generation: SessionGeneration,
    remote_peer_id: &PeerId,
) -> Option<SessionId> {
    sessions.iter().find_map(|(session_id, handle)| {
        (handle.generation == generation && &handle.remote_peer_id == remote_peer_id)
            .then_some(*session_id)
    })
}
```

Use the final session ID returned by normal task completion when available.

### Panic/join failure policy

On `Err(join_reason)`:

```text
remove registry entry
remove peer mapping
log ERROR
record primary daemon failure
request shared shutdown
continue draining other tasks
return nonzero after finalization
```

### Acceptance criteria

- [x] Session registry does not depend on a self-sent `Ended` event.
- [x] `AnswerSessionEvent::Ended` is removed or no longer authoritative.
- [x] Normal task completion is not followed by `abort()`.
- [x] Task panic/join failure is observed independently.
- [x] Same-peer replacement does not make completion cleanup miss the current map entry.
- [x] Panic cannot strand a session registry entry.
- [x] Panic triggers daemon shutdown and nonzero result after cooperative drain.

---

## P0-005 — Make answer fatal paths enter drain/finalize instead of returning immediately

### Files

Modify:

```text
crates/p2p-daemon/src/answer/mod.rs
crates/p2p-daemon/src/error.rs
```

### Problem

Fatal branches such as an unexpected event-channel close can return directly, bypassing cooperative drain and strict terminal status.

### Required state

Track:

```rust
let mut primary_error: Option<DaemonError> = None;
```

Add a helper:

```rust
fn begin_answer_drain(
    ctx: &mut RuntimeContext<'_>,
    shutdown: &ShutdownToken,
    primary_error: &mut Option<DaemonError>,
    error: Option<DaemonError>,
) {
    ctx.runtime.phase = DaemonRuntimePhase::Draining;
    if primary_error.is_none() {
        *primary_error = error;
    } else if let Some(error) = error {
        tracing::error!(reason = %error, "additional answer daemon failure during drain");
    }
    shutdown.request_shutdown();
}
```

Equivalent code is fine.

### Event channel close

Replace direct return:

```rust
return Err(DaemonError::Logging(
    "answer session event channel closed".to_owned(),
));
```

with:

```text
record primary error
enter Draining
request shutdown
continue consuming task completions until registry empty
```

### Task panic/join failure

Use the same drain path.

### Final result

After all sessions are gone:

```rust
ctx.runtime.phase = DaemonRuntimePhase::Closed;
let closed_result = write_answer_closed_status_required(&mut ctx).await;

match primary_error {
    Some(error) => {
        if let Err(close_error) = closed_result {
            tracing::error!(reason = %close_error, "answer terminal status also failed");
        }
        Err(error)
    }
    None => closed_result,
}
```

### Acceptance criteria

- [x] No fatal answer branch with active sessions bypasses drain.
- [x] Event channel failure triggers drain and nonzero result.
- [x] Task panic triggers drain and nonzero result.
- [x] Other sessions still receive cooperative shutdown.
- [x] Terminal status is attempted after registry reaches zero.

---

## P0-006 — Make final `Closed` status strict and status-file replacement atomic

### Files

Modify:

```text
crates/p2p-daemon/src/status.rs
crates/p2p-daemon/src/signaling.rs
crates/p2p-daemon/src/answer/mod.rs
crates/p2p-daemon/src/offer/mod.rs
```

### Part A — atomic status file write

Replace:

```rust
tokio::fs::write(&self.path, json).await?;
```

with same-directory temporary-file replacement.

Recommended helper:

```rust
async fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), std::io::Error> {
    let parent = path.parent().unwrap_or_else(|| Path::new("."));
    tokio::fs::create_dir_all(parent).await?;

    let file_name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("status.json");
    let temp_path = parent.join(format!(
        ".{file_name}.tmp-{}",
        std::process::id(),
    ));

    let write_result = async {
        let mut file = tokio::fs::File::create(&temp_path).await?;
        use tokio::io::AsyncWriteExt;
        file.write_all(bytes).await?;
        file.flush().await?;
        drop(file);
        tokio::fs::rename(&temp_path, path).await
    }
    .await;

    if write_result.is_err() {
        if let Err(cleanup_error) = tokio::fs::remove_file(&temp_path).await {
            if cleanup_error.kind() != std::io::ErrorKind::NotFound {
                tracing::warn!(
                    reason = %cleanup_error,
                    path = %temp_path.display(),
                    "failed to remove status temporary file",
                );
            }
        }
    }

    write_result
}
```

A more collision-resistant same-directory temp name is welcome. Do not use a different filesystem.

### Part B — strict terminal writer

Keep ordinary:

```rust
write_status_or_log(...)
```

for nonterminal updates.

Add:

```rust
pub(crate) async fn write_status_required(
    status: &StatusWriter,
    daemon_status: DaemonStatus,
) -> Result<(), DaemonError> {
    status.write(daemon_status).await
}
```

Change:

```rust
write_answer_closed_status(...)
write_offer_closed_status(...)
```

to return `Result<(), DaemonError>` and use the strict writer.

When status-file writing is disabled, `StatusWriter::write` already returns `Ok(())`; preserve that behavior.

Optional watch sink closure remains nonfatal.

### Tests

Add:

1. terminal write failure returns error;
2. ordinary status write failure is still warning-only;
3. repeated writer/reader stress test never parses partial JSON;
4. old complete status or new complete status may be seen, never malformed JSON.

### Acceptance criteria

- [x] Final `Closed` write is result-returning.
- [x] Daemon exit reflects terminal write failure when no earlier primary error exists.
- [x] Earlier primary error is preserved if terminal write also fails.
- [x] Status target is atomically replaced on Linux/macOS.
- [x] Reader stress test sees no partial JSON.

---

## P0-007 — Remove the premature `sd_notify` readiness implementation from the supported surface

### Files

Remove/modify:

```text
crates/p2p-daemon/src/notify.rs
crates/p2p-daemon/src/lib.rs
crates/p2p-daemon/Cargo.toml
bins/p2p-offer/Cargo.toml
bins/p2p-answer/Cargo.toml
bins/p2p-offer/src/main.rs
bins/p2p-answer/src/main.rs
packaging/systemd/p2p-offer-notify.service
packaging/systemd/p2p-answer-notify.service
docs/SYSTEMD.md
README.md
.github/workflows/ci.yml (only if feature-specific references remain)
```

### Decision

Do **not** try to rescue the current coarse readiness claim.

The current call:

```rust
let daemon = run_offer_daemon_with_shutdown(...);
tokio::pin!(daemon);
notify_ready();
```

sends readiness before the daemon future is polled.

### Required changes

- Remove `notify_ready()` calls.
- Remove `notify_stopping()` calls tied only to this feature.
- Remove the optional `sd-notify` dependency/feature if unused afterward.
- Remove or stop shipping the `*-notify.service` files.
- Remove documentation claiming these units provide readiness.
- Remove tests that only validate the removed premature adapter.
- Keep baseline `Type=simple` service units unchanged except for other tasks in this TODO.

### Future note

Add a short comment/document note only if useful:

```text
Type=notify is intentionally not shipped until the daemon core exposes a genuine readiness event after broker subscription and listener/runtime startup.
```

Do not leave an undocumented dead feature.

### Acceptance criteria

- [ ] No `READY=1` is sent before daemon readiness.
- [ ] No supported unit uses `Type=notify` without a genuine readiness source.
- [ ] Default/manual/Docker/launchd behavior is unchanged.
- [ ] `cargo clippy --all-features` no longer carries a misleading optional readiness feature.

---

## P0-008 — Make offer shutdown-during-reconnect testing deterministic

### Files

Modify:

```text
crates/p2p-daemon/src/offer/mod.rs
crates/p2p-daemon/src/offer/session/mod.rs
crates/p2p-daemon/tests/two_node_daemon/shutdown_tests.rs
```

### Problem

Current synchronization:

```rust
tokio::time::sleep(Duration::from_millis(300)).await;
shutdown.request_shutdown();
```

can still race the ordinary session loop instead of proving reconnect/backoff cancellation.

### Add explicit test event

Recommended:

```rust
#[cfg(any(test, debug_assertions))]
#[derive(Clone, Debug)]
pub enum OfferSessionTestEvent {
    SessionStarted { session_id: SessionId },
    ReconnectStarted { session_id: SessionId },
    ReconnectBackoffStarted {
        session_id: SessionId,
        delay: Duration,
    },
}
```

Either extend `OfferSessionTestHandle` with an event receiver or add a separate event channel.

Emit the event at the actual reconnect/backoff transition, not before it.

### Test flow

```rust
injector.inject(IceConnectionState::Disconnected).await?;

let event = timeout(Duration::from_secs(5), test_events.recv())
    .await
    .expect("reconnect event should arrive")
    .expect("test event channel should stay open");

assert!(matches!(
    event,
    OfferSessionTestEvent::ReconnectBackoffStarted { .. }
));

shutdown.request_shutdown();
```

Remove the fixed 300 ms synchronization sleep.

### Acceptance criteria

- [x] Test observes actual reconnect/backoff state.
- [x] Shutdown is requested only after the observed event.
- [x] Daemon exits before configured backoff.
- [x] Test does not rely on a guessed sleep for correctness.

---

## P0-009 — Add the missing answer in-flight publish drain test

### Files

Modify/add under:

```text
crates/p2p-daemon/tests/two_node_daemon/
```

Possibly extend:

```text
crates/p2p-daemon/tests/two_node_daemon/harness/
```

### Goal

Prove the outer answer loop remains alive while a session is blocked waiting for an in-flight publish result.

### Required deterministic transport barrier

Add a fake-transport hook/barrier that can:

1. observe a publish request;
2. block its completion;
3. notify the test that it is blocked;
4. release it on command.

Conceptual helper:

```rust
struct PublishBarrier {
    entered_tx: oneshot::Sender<()>,
    release_rx: oneshot::Receiver<()>,
}
```

In the fake transport publish path:

```rust
if let Some(barrier) = self.publish_barrier.take() {
    let _ = barrier.entered_tx.send(());
    let _ = barrier.release_rx.await;
}
```

For test-only synchronization, sender/receiver closure should fail the test loudly rather than silently proceeding.

### Required sequence

```text
establish answer session
trigger signaling action that emits Publish/RawPublish
wait for barrier entered
request answer shutdown
assert daemon is draining but has not deadlocked/returned incorrectly
release publish barrier
await daemon completion
assert Closed + zero sessions
```

### Acceptance criteria

- [ ] The publish is proven in flight before shutdown.
- [ ] The answer event loop continues servicing the publish path during drain.
- [ ] No deadlock.
- [ ] Final registry is empty.
- [ ] Final state is `Closed`.

---

## P0-010 — Replace signal-adapter tests that signal the Cargo test process with child-process tests

### Files

Modify:

```text
crates/p2p-daemon/src/process_signal.rs
```

### Problem

Current tests run:

```text
kill -TERM <current Cargo test process PID>
kill -INT  <current Cargo test process PID>
```

This violates the lifecycle test safety requirement.

### Recommended child re-exec pattern

Use one test with child mode:

```rust
#[test]
fn sigterm_and_sigint_are_observed_in_child_processes() {
    assert_child_observes_signal("-TERM", "SIGTERM");
    assert_child_observes_signal("-INT", "SIGINT");
}
```

Helper shape:

```rust
fn assert_child_observes_signal(flag: &str, expected: &str) {
    const CHILD_MODE: &str = "P2P_SIGNAL_TEST_CHILD";
    const RESULT_PATH: &str = "P2P_SIGNAL_TEST_RESULT";

    if std::env::var_os(CHILD_MODE).is_some() {
        let result_path = std::env::var_os(RESULT_PATH).expect("result path");
        let runtime = tokio::runtime::Runtime::new().expect("runtime");
        let observed = runtime
            .block_on(wait_for_process_shutdown_signal())
            .expect("signal should be observed");
        std::fs::write(result_path, observed).expect("write child result");
        return;
    }

    // Parent creates temp result path, re-execs current test binary with --exact,
    // waits for an explicit child-ready marker, signals child PID, then asserts result.
}
```

Do not use a blind sleep to assume the child's signal handlers are registered. Add an explicit ready file/pipe/channel marker from the child after it has constructed the signal-wait future/registered handlers.

One practical approach is to split registration from waiting in the adapter under test:

```rust
pub struct ProcessShutdownSignals { ... }

impl ProcessShutdownSignals {
    pub fn install() -> io::Result<Self> { ... }
    pub async fn wait(&mut self) -> io::Result<&'static str> { ... }
}
```

The child can call `install()`, create the ready marker, then `wait()`.

### Acceptance criteria

- [ ] Test runner PID is never signaled.
- [ ] Child signals are deterministic without registration sleep.
- [ ] SIGTERM and SIGINT both tested on Unix.
- [ ] Adapter setup failures remain real errors.

---

## P0-011 — Make real-binary signal lifecycle coverage required instead of self-skipping

### Files

Modify:

```text
crates/p2p-daemon/tests/process_signal_shutdown.rs
.github/workflows/ci.yml
```

Possibly add:

```text
scripts/run-process-signal-tests.sh
```

### Required CI behavior

Create a dedicated lifecycle job or dedicated steps that explicitly build:

```bash
cargo build -p p2p-offer -p p2p-answer -p p2pctl
```

Then run the signal integration test with explicit binary paths:

```yaml
env:
  P2P_OFFER_BIN: ${{ github.workspace }}/target/debug/p2p-offer
  P2P_ANSWER_BIN: ${{ github.workspace }}/target/debug/p2p-answer
```

### Broker prerequisite

Do not make the required test depend on an unprovisioned Docker installation and then return green when unavailable.

Preferred cross-platform direction:

- provision `mosquitto` in the job (`apt` on Linux, `brew` on macOS);
- spawn a local test broker process from the integration test using the existing generated TLS fixtures/config logic;
- kill it through a test guard.

If macOS full-binary broker coverage cannot be implemented in this pass:

- require full real-binary signal coverage on Linux;
- require child-process signal-adapter coverage on Linux and macOS;
- document macOS full-binary signal coverage as a known gap;
- do not claim the skipped test passed on macOS.

### Remove internal skips

In the required job, these must be failures:

```text
binary missing
broker executable missing
required setup missing
```

Do not use:

```rust
return; // SKIP
```

for the primary required assertion.

### Acceptance criteria

- [ ] Offer SIGTERM real process test is required.
- [ ] Answer SIGTERM real process test is required.
- [ ] At least one role SIGINT real process test is required.
- [ ] Required CI job builds exact binaries first.
- [ ] Missing prerequisite fails the required job.
- [ ] No signal test sends a signal to the test runner.

---

## P0-012 — Fix Debian package executable paths and add a staged-tree path assertion

### Files

Modify/add:

```text
scripts/build-deb.sh
packaging/debian/systemd/p2p-offer.service
packaging/debian/systemd/p2p-answer.service
```

### Problem

Package binaries:

```text
/usr/bin/p2p-offer
/usr/bin/p2p-answer
/usr/bin/p2pctl
```

Current packaged units execute:

```text
/usr/local/bin/...
```

### Required fix

Create package-specific unit files using `/usr/bin`.

Example offer lines:

```ini
ExecStartPre=/usr/bin/p2pctl check-config --config /etc/p2ptunnel/offer/config.toml
ExecStart=/usr/bin/p2p-offer run --config /etc/p2ptunnel/offer/config.toml
```

Answer equivalent.

Update `build-deb.sh` to install package-specific units.

Do not change the manually installed `/usr/local/bin` baseline units unless the docs/install strategy is deliberately changed everywhere.

### Staged-tree assertion

Add a helper before `dpkg-deb --build`:

```bash
verify_staged_unit_executables() {
  local unit line command exe staged
  for unit in "$STAGE"/lib/systemd/system/p2p-*.service; do
    while IFS= read -r line; do
      command="${line#*=}"
      exe="${command%% *}"
      case "$exe" in
        /*)
          staged="$STAGE$exe"
          [ -x "$staged" ] || fail \
            "$(basename "$unit") references $exe, but $staged is absent or not executable"
          ;;
      esac
    done < <(grep -E '^ExecStart(Pre)?=' "$unit")
  done
}
```

Equivalent parsing is fine. Keep it simple and explicit.

Call it before building the package.

### Acceptance criteria

- [ ] Package units use `/usr/bin`.
- [ ] Manual units may remain `/usr/local/bin`.
- [ ] Package build fails on any missing absolute ExecStart executable.
- [ ] `dpkg -c` shows all referenced executables.
- [ ] Package smoke test reaches the packaged `p2pctl` `ExecStartPre` path.

---

## P0-013 — Fix Debian upgrade and remove lifecycle semantics

### Files

Modify:

```text
packaging/debian/prerm
packaging/debian/postinst
packaging/debian/postrm
```

### Required prerm policy

Do not stop services on `upgrade`.

Recommended:

```sh
case "$1" in
  remove|deconfigure)
    if [ -d /run/systemd/system ]; then
      for unit in p2p-offer.service p2p-answer.service; do
        if systemctl is-active --quiet "$unit" 2>/dev/null; then
          systemctl stop "$unit"
        fi
      done
    fi
    ;;
esac
```

### Required postinst upgrade behavior

After `daemon-reload`:

```sh
for unit in p2p-offer.service p2p-answer.service; do
  if systemctl is-active --quiet "$unit" 2>/dev/null; then
    systemctl try-restart "$unit"
  fi
done
```

Because `try-restart` only affects active units, first install must not auto-start an inactive service.

If restart fails, let the maintainer script fail visibly. Do not append `|| true`.

### Required postrm behavior

Run:

```sh
systemctl daemon-reload
```

after both:

```text
remove
purge
```

when systemd is active.

Keep current data-preservation policy unless separately changed:

- remove keeps config/state/logs;
- purge removes explicitly documented package-owned data;
- service account is not silently reused/deleted.

### Acceptance criteria

- [ ] `prerm upgrade` does not stop the running tunnel.
- [ ] Active services are restarted after upgrade/configure.
- [ ] Inactive services are not auto-started by upgrade.
- [ ] Remove triggers daemon-reload.
- [ ] Restart failure is visible.
- [ ] Upgrade lifecycle has an automated package test.

---

## P0-014 — Fix macOS user/group validation and service-readable directory permissions

### Files

Modify:

```text
scripts/install-launchd-services.sh
packaging/macos/scripts/preinstall
packaging/macos/scripts/postinstall
docs/LAUNCHD.md
docs/MACOS_PACKAGING.md
```

### Required account checks

Use both:

```bash
dscl . -read "/Users/_p2ptunnel"
dscl . -read "/Groups/_p2ptunnel"
```

Fail if either is absent.

### New directory ownership

Config role directories:

```bash
install -d -m 0750 -o root -g _p2ptunnel "$dir"
```

Log directory:

```bash
install -d -m 0750 -o _p2ptunnel -g _p2ptunnel "$LOG_DIR"
```

### Existing directories

Do not merely print “leaving as-is.” Validate.

Add a helper, for example:

```bash
require_service_traverse() {
  local path="$1"
  sudo -u "$SERVICE_USER" test -x "$path" \
    || fail "service account $SERVICE_USER cannot traverse $path"
}
```

For files that exist, validate readability as the service user:

```bash
sudo -u "$SERVICE_USER" test -r "$file" \
  || fail "$SERVICE_USER cannot read $file"
```

The exact macOS command may use `/usr/bin/sudo -u`; verify on macOS CI/manual validation.

Do not run the service as root to avoid fixing permissions.

### Acceptance criteria

- [ ] User and group both validated.
- [ ] New config dirs are `root:_p2ptunnel 0750`.
- [ ] New log dir is service-user owned/writable.
- [ ] Existing inaccessible directories cause explicit failure.
- [ ] Standalone installer and `.pkg` scripts use the same policy.
- [ ] Docs match actual ownership.

---

## P0-015 — Validate launchd configuration as the actual service user before `--enable`

### Files

Modify:

```text
scripts/install-launchd-services.sh
docs/LAUNCHD.md
```

### Required order for `--enable`

```text
validate plist
install plist
validate user/group
validate service-user file access
run p2pctl check-config as _p2ptunnel
bootstrap
```

### Recommended helper

```bash
validate_role_config_as_service_user() {
  local role="$1"
  local config="$APP_SUPPORT_ROOT/$role/config.toml"

  [ -f "$config" ] || fail "missing $config; refusing to bootstrap $role"

  sudo -u "$SERVICE_USER" \
    /usr/local/bin/p2pctl check-config --config "$config" \
    || fail "$role config failed validation as $SERVICE_USER"
}
```

Run both role validations before bootstrapping either role so the script does not leave a half-enabled pair because the second config was bad.

If only one-role install is desired later, add an explicit role option. Do not infer silently.

### Acceptance criteria

- [ ] `--enable` refuses missing config.
- [ ] `--enable` refuses unreadable config/identity/authorized_keys.
- [ ] Validation runs as `_p2ptunnel`, not root.
- [ ] Both configs validate before either bootstrap occurs.
- [ ] No invalid-config relaunch loop is created by the installer.

---

## P0-016 — Add focused regression tests for lifecycle truthfulness and worker failure

### Files

Add/modify under:

```text
crates/p2p-daemon/src/tests/
crates/p2p-daemon/tests/two_node_daemon/
```

### Required tests

#### Offer channel-close race

Force:

```text
shutdown requested
accept workers exit
accepted_clients channel closes
```

Assert:

- daemon returns `Ok(())` for normal shutdown;
- worker joins run;
- final `Closed` exists.

Run enough controlled iterations to catch select-order regressions, but prefer deterministic branch control over probabilistic stress.

#### No post-shutdown steady state

Use a status sink and record all states after shutdown request.

Assert no state after request is:

```text
WaitingForLocalClient
Serving
Negotiating
TunnelOpen
```

Only terminal/drain-internal non-emitted behavior and final `Closed` are allowed.

#### Offer worker panic

Add a test-only panic/exit hook for one accept worker.

Assert:

- failure observed while daemon is running;
- daemon requests shutdown;
- remaining worker(s) stop;
- final status attempted;
- return is `Err`.

#### Answer task panic

Add a test-only panic hook inside one answer worker.

Assert:

- registry entry removed;
- other sessions drain;
- shutdown does not hang;
- daemon returns `Err`.

### Acceptance criteria

- [x] All four regression classes have deterministic tests.
- [x] No fixed sleep is the primary synchronization mechanism.
- [x] Tests fail when supervision/finalization is removed.

---

## P0-017 — Run complete P0 quality gates and do not mark P0 complete on an unexecuted required test

### Required commands

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo clippy --workspace --release --all-features -- -D warnings
cargo test --workspace --all-targets --all-features
```

Android:

```bash
cd android
./gradlew --no-daemon assembleDebug testDebugUnitTest
```

Service/package:

```bash
scripts/check-systemd-units.sh
scripts/check-launchd-plists.sh
bash -n scripts/*.sh
sh -n packaging/debian/postinst packaging/debian/prerm packaging/debian/postrm
scripts/build-deb.sh
```

Also run the dedicated required real-process signal job/test.

### Reporting rule

For each unavailable platform-specific gate, report:

```text
NOT RUN: exact reason
```

Do not report it as passed.

P0 is not complete if a required Linux gate was skipped because a dependency was simply not installed.

---

# P1 tasks

## P1-001 — Preserve Android graceful versus forced stop outcome and return stop failures through FFI

### Files

Modify:

```text
crates/p2p-mobile/src/runtime/mod.rs
crates/p2p-mobile/src/runtime/types.rs
crates/p2p-mobile/src/c_abi.rs
crates/p2p-mobile/src/jni_bridge.rs
crates/p2p-mobile/src/runtime/tests.rs
Android bridge/repository tests as needed
```

### Add internal outcome

```rust
#[derive(Clone, Debug, Eq, PartialEq)]
enum StopOutcome {
    Graceful,
    NotRunning,
    ForcedAbort { grace_period: Duration },
    TaskJoinFailed { reason: String },
}
```

### Return result

Change controller stop to return:

```rust
pub fn stop(&self) -> Result<(), String>
```

and make the grace-period helper return `StopOutcome` or `Result<StopOutcome, String>`.

### Evaluate timeout/join result correctly

Current timeout result contains both timeout and task join status. Distinguish:

```text
Ok(Ok(()))  -> Graceful
Ok(Err(e))  -> TaskJoinFailed
Err(_)       -> ForcedAbort
```

Do not treat task panic/cancellation as clean.

### Forced abort state

Recommended:

```rust
inner.state.state = AndroidRuntimeState::Error;
inner.state.active = false;
inner.state.last_error = Some(format!(
    "runtime required forced abort after {:?}",
    grace_period,
));
// Preserve config_path for diagnostics.
reset_runtime_metadata(&mut inner.state);
```

Log at `error` or `warn`, not `info`.

Return failure through C ABI/JNI so Kotlin `stop()` receives `Result.failure`.

### Duplicate stop

A second stop when nothing is active remains safe.

Do not silently clear a previous forced-abort diagnostic merely because stop is called again.

### Acceptance criteria

- [ ] Graceful stop yields clean `Stopped`.
- [ ] Forced abort is visible and returns failure.
- [ ] Task join failure is visible and returns failure.
- [ ] `last_error` is not cleared after forced abort.
- [ ] Kotlin repository receives failure.
- [ ] Duplicate stop is safe.

---

## P1-002 — Replace Android mutex-poison no-ops/defaults with explicit errors

### Files

Modify:

```text
crates/p2p-mobile/src/runtime/mod.rs
crates/p2p-mobile/src/runtime/log_bridge.rs
crates/p2p-mobile/src/lib.rs
crates/p2p-mobile/src/c_abi.rs
crates/p2p-mobile/src/runtime/tests.rs
```

### Stop

Replace:

```rust
Err(_) => return,
```

with:

```rust
Err(_) => return Err("runtime mutex poisoned".to_owned()),
```

### Recent logs

Preferred API:

```rust
pub fn recent_logs(&self, max_events: usize) -> Result<Vec<AndroidLogEvent>, String>
```

On poison:

```rust
Err("runtime mutex poisoned".to_owned())
```

The FFI/JNI layer may serialize a synthetic error log event if preserving the existing JSON-array surface is necessary.

Do not return `[]`.

### Last error

On poison, return:

```rust
Some("runtime mutex poisoned".to_owned())
```

or a structured result.

Do not use `.lock().ok()`.

### Completion callback

Replace:

```rust
if let Ok(mut inner) = log_state.lock() { ... }
```

with explicit error logging through a mechanism that does not require that mutex.

### Bridge error record

`record_bridge_error` should return `Result<(), String>`.

`catch_api_recording` must not silently discard failure to record the primary error.

### Acceptance criteria

- [ ] No runtime mutex poison path becomes a no-op.
- [ ] No poison path becomes empty logs.
- [ ] No poison path becomes `None` error.
- [ ] FFI error propagation remains specific.

---

## P1-003 — Make Android tracing bridge and log-buffer failures visible

### Files

Modify:

```text
crates/p2p-mobile/src/runtime/log_bridge.rs
crates/p2p-mobile/src/runtime/mod.rs
```

### Install result

Change:

```rust
pub(crate) fn install_tracing_once(...)
```

to return a result.

A simple approach with `OnceLock<Result<(), String>>` is preferable to `Once`, because later callers need to know whether the first installation succeeded.

Conceptual shape:

```rust
static INSTALL_RESULT: OnceLock<Result<(), String>> = OnceLock::new();

pub(crate) fn install_tracing_once(
    buffer: LogBuffer,
    level: &str,
) -> Result<(), String> {
    INSTALL_RESULT
        .get_or_init(|| {
            let layer = AndroidLogLayer { buffer }
                .with_filter(level_filter(level));
            tracing_subscriber::registry()
                .with(layer)
                .try_init()
                .map_err(|error| format!("failed to install Android tracing bridge: {error}"))
        })
        .clone()
}
```

If the error type/result cloning needs adjustment, preserve the semantics.

### Startup policy

Recommended:

```rust
install_tracing_once(...)
    .map_err(|error| record_start_error(&mut inner, error))?;
```

Do not start while silently losing the diagnostics surface the app depends on.

### LogBuffer

Change `push`/`recent` to return `Result` or otherwise expose poison.

Do not silently discard events or return empty logs.

### Acceptance criteria

- [ ] Tracing install failure is visible.
- [ ] Tracing install failure is tested.
- [ ] LogBuffer poison is visible.
- [ ] No `let _ = try_init()` remains.

---

## P1-004 — Make `p2pctl` config-path resolution and status parsing strict

### Files

Modify:

```text
bins/p2pctl/src/main.rs
crates/p2p-daemon/src/status.rs
bins/p2pctl/Cargo.toml (only if needed for status type access)
```

### Config path

Copy the no-panic path resolver pattern already used by `p2p-offer` and `p2p-answer`.

Remove:

```rust
.expect("default config dir")
```

### Typed status

Add `Deserialize`:

```rust
#[derive(Clone, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub struct DaemonStatus { ... }
```

Do the same for nested status types.

Parse:

```rust
let status: DaemonStatus = serde_json::from_str(&content)?;
```

Change rendering to accept `&DaemonStatus`.

No:

```rust
unwrap_or("unknown")
unwrap_or(false)
missing sessions -> "none"
```

for required schema fields.

### Acceptance criteria

- [ ] Missing HOME yields normal error.
- [ ] Malformed status JSON yields error.
- [ ] Missing required field yields error.
- [ ] Valid zero-session status renders correctly.
- [ ] Valid multi-session status renders correctly.

---

## P1-005 — Make `p2pctl check-config` match daemon authorization preflight

### Files

Modify:

```text
crates/p2p-core/src/config/*
crates/p2p-daemon/src/config.rs
bins/p2pctl/src/main.rs
```

### Extract lightweight required-peer enumeration

Preferred shape in `p2p-core`:

```rust
impl AppConfig {
    pub fn required_authorized_peer_ids(&self) -> Result<Vec<&PeerId>, ConfigError> {
        match self.node.role {
            NodeRole::Offer => {
                let peer = self.peer.as_ref().ok_or_else(|| {
                    ConfigError::InvalidConfig(
                        "[peer].remote_peer_id must be set for offer role".to_owned(),
                    )
                })?;
                Ok(vec![&peer.remote_peer_id])
            }
            NodeRole::Answer => Ok(self
                .forwards
                .iter()
                .filter_map(|forward| forward.answer.as_ref())
                .flat_map(|answer| answer.allow_remote_peers.iter())
                .collect()),
        }
    }
}
```

Deduplicate if useful, but preserve clear errors.

### Daemon

Refactor `validate_config_authorized_peers` to use this helper.

### CLI

`check_config` must load:

```rust
let authorized_keys = AuthorizedKeys::from_file(&config.paths.authorized_keys)?;
```

Then:

```rust
for peer_id in config.required_authorized_peer_ids()? {
    if authorized_keys.get_by_peer_id(peer_id).is_none() {
        return Err(format!(
            "required peer '{}' is missing from {}",
            peer_id,
            config.paths.authorized_keys.display(),
        )
        .into());
    }
}
```

### Acceptance criteria

- [ ] Offer missing remote authorized key fails `check-config`.
- [ ] Answer missing allowed peer fails `check-config`.
- [ ] Valid config passes.
- [ ] Daemon and CLI use the same required-peer enumeration logic.
- [ ] `p2pctl` does not depend on the full WebRTC daemon solely for validation.

---

## P1-006 — Fix diagnostics redactor false negatives and preserve MQTT URL scheme

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/data/SensitiveDataRedactor.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/data/SensitiveDataRedactorTest.kt
```

### Generic secret-field regex

Use common separators and field-name variants.

One maintainable approach:

```kotlin
private val secretFieldRegex =
    Regex(
        pattern = """(?im)\b(password(?:[_ -][\w-]+)?|token(?:[_ -][\w-]+)?|api[_ -]?key|kex[_ -]?secret|signing[_ -]?key)\b\s*[:=]\s*([^,\s]+|\"[^\"]*\")""",
    )
```

Then normalize only the value:

```kotlin
.replace(secretFieldRegex) { match ->
    "${match.groupValues[1]}=***REDACTED***"
}
```

Adjust exact pattern to avoid overmatching, but cover the required test table.

### Preserve MQTT scheme

Use capture group:

```kotlin
.replace(
    Regex("""(?i)\b(mqtts?)://([^:@/\s]+):([^@/\s]+)@"""),
) { match ->
    "${match.groupValues[1]}://***REDACTED***:***REDACTED***@"
}
```

### Required table tests

Include:

```text
password=hunter2
password: hunter2
password = hunter2
password : hunter2
api key: sk_live_123
api-key=sk_live_123
kex secret = deadbeef
kex_secret: deadbeef
signing key: deadbeef
```

Every test uses a unique sentinel and asserts it is absent.

Delete/invert tests that currently assert a secret remains unredacted.

### Acceptance criteria

- [ ] Colon variants redacted.
- [ ] Space/hyphen/underscore variants redacted.
- [ ] MQTT and MQTTS schemes preserved.
- [ ] Redaction remains idempotent.
- [ ] No test documents a known secret leak as acceptable behavior.

---

## P1-007 — Replace diagnostics collection empty fallbacks with explicit error sections

### Files

Modify:

```text
android/app/src/main/java/com/phillipchin/webrtctunnel/viewmodel/SettingsViewModel.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/SettingsViewModelTest.kt
```

### Status collection

Replace:

```kotlin
.getOrDefault("{}")
```

with a result type or explicit rendered error.

Recommended:

```kotlin
private fun statusDiagnosticsSection(): String =
    runCatching {
        Json.encodeToString(
            SensitiveDataRedactor.redactStatus(deps.tunnelRepository.status.value),
        )
    }.fold(
        onSuccess = { "status_json=$it" },
        onFailure = { error ->
            "status_json_error=${SensitiveDataRedactor.redactText(error.message ?: "unknown status serialization failure")}" 
        },
    )
```

### Config collection

Distinguish:

```text
file absent (expected/optional)
read/permission failure
redaction failure
```

Do not return empty string for all three.

### Share payload

Build sections that preserve errors:

```text
status_json=...
config_redacted=...
```

or:

```text
status_json_error=...
config_redacted_error=...
```

### Acceptance criteria

- [ ] Serialization failure is visible.
- [ ] Config read failure is visible.
- [ ] Missing optional file is distinguishable from permission/read failure.
- [ ] Error text is redacted before sharing.

---

## P1-008 — Stop inventing empty host/port for forward-status/config mismatches

### Files

Modify:

```text
crates/p2p-mobile/src/runtime/state.rs
crates/p2p-mobile/src/runtime/types.rs
crates/p2p-mobile/src/runtime/tests.rs
```

### Problem

Current:

```rust
.unwrap_or_default()
```

creates:

```text
local_host = ""
local_port = 0
```

### Recommended model

Make endpoint fields optional:

```rust
pub struct AndroidForwardRuntimeStatus {
    pub id: String,
    pub local_host: Option<String>,
    pub local_port: Option<u16>,
    pub listen_state: String,
    pub last_error: Option<String>,
    pub configuration_error: Option<String>,
}
```

When missing:

```rust
configuration_error: Some(format!(
    "daemon reported forward '{}' but no matching configured endpoint exists",
    forward.id,
))
```

Also emit an error log once per mismatch if practical.

If changing the serialized model is too disruptive, preserve host/port fields but set a visible error state and do not use port 0 as if it were real.

### Acceptance criteria

- [ ] Missing config match is explicit.
- [ ] UI cannot display `:0` as a real endpoint.
- [ ] Test covers daemon/config mismatch.

---

## P1-009 — Audit production best-effort async operations and log failures with context

### Files

Audit at minimum:

```text
crates/p2p-daemon/src/answer/session.rs
crates/p2p-daemon/src/answer/mod.rs
crates/p2p-daemon/src/offer/session/*
crates/p2p-daemon/src/types.rs
crates/p2p-webrtc/src/peer.rs
crates/p2p-tunnel/src/multiplex/*
```

### Scope rule

Do not mechanically replace every `let _ =`.

Classify each occurrence:

```text
expected cancellation / receiver gone because owner is shutting down
best-effort but operationally useful failure
unexpected invariant failure
```

### Required logging pattern

For nonfatal but meaningful failures:

```rust
if let Err(error) = publish_message(...).await {
    tracing::warn!(
        reason = %error,
        session_id = %session.session_id,
        remote_peer_id = %session.remote_peer_id,
        "failed to publish best-effort close notification",
    );
}
```

For intentional task abort join:

```rust
match handle.await {
    Err(error) if error.is_cancelled() => {}
    Err(error) => tracing::warn!(reason = %error, "aborted bridge task failed unexpectedly"),
    Ok(()) => {}
}
```

For channel send where receiver closure is expected during teardown, add a comment explaining why no log is needed.

### Deliverable

Add a short audit comment or commit notes listing intentionally ignored categories. Do not leave unexplained production `let _ = ...await` around important signaling/lifecycle operations.

### Acceptance criteria

- [ ] Teardown publish failures are logged.
- [ ] Peer close failures are logged where currently silent.
- [ ] Intentional cancellation is not noisy.
- [ ] Remaining ignored results have an explicit justification.

---

## P1-010 — Reduce GitHub Actions permissions to least privilege

### Files

Modify:

```text
.github/workflows/ci.yml
```

### Workflow default

Change:

```yaml
permissions:
  contents: write
```

to:

```yaml
permissions:
  contents: read
```

### Release job

Add:

```yaml
release-artifacts:
  permissions:
    contents: write
```

at the job level.

If `actions/upload-artifact` or another job requires a different permission, add only the exact required permission at that job.

### Acceptance criteria

- [ ] Lint/test/Android jobs do not get contents write.
- [ ] Release publishing still works.
- [ ] Workflow syntax validates.

---

## P1-011 — Add package/install smoke tests that verify what will actually run

### Files

Add under one of:

```text
tests/packaging/
scripts/test-debian-package.sh
scripts/test-launchd-install-layout.sh
```

### Debian smoke test

On Linux CI:

1. build release/debug binaries as appropriate;
2. build `.deb`;
3. inspect package contents;
4. extract package into a temporary root;
5. verify unit executable paths exist in extracted tree;
6. optionally install into a disposable container/VM and run `systemctl cat`/`ExecStartPre` validation.

Do not require starting a real tunnel without config.

The minimum smoke assertion is that service execution reaches the packaged `p2pctl` path instead of failing with “No such file.”

### macOS static/install validation

At minimum on macOS CI:

- plist syntax;
- user/group check helper behavior with test doubles where possible;
- path/ownership helper unit/shell tests;
- package payload plist/binary path consistency.

### Acceptance criteria

- [ ] Debian path mismatch cannot recur unnoticed.
- [ ] Upgrade lifecycle script behavior is tested.
- [ ] macOS permission policy has executable validation, not docs only.

---

## P1-012 — Update docs so operational claims match the hardened behavior

### Files

Update:

```text
README.md
docs/SYSTEMD.md
docs/LAUNCHD.md
docs/DEBIAN_PACKAGING.md
docs/MACOS_PACKAGING.md
```

### Required corrections

- Remove premature `Type=notify` readiness instructions.
- State baseline systemd unit is `Type=simple`.
- Document package `/usr/bin` versus manual `/usr/local/bin` distinction.
- Document Debian upgrade restart behavior.
- Document macOS `root:_p2ptunnel` config directories.
- Document service-user preflight before launchd bootstrap.
- Document forced Android abort as an error, not clean stop.
- Document strict `p2pctl status` schema behavior if relevant.

### Acceptance criteria

- [ ] No docs claim false readiness.
- [ ] No docs tell users to create `root:wheel 0750` config dirs for `_p2ptunnel` service.
- [ ] Package paths match package files.

---

# P2 tasks

## P2-001 — Reintroduce real supervisor-neutral readiness and `sd_notify`

Do not implement until P0 is complete.

### Future architecture

Add a generic one-shot readiness event from daemon core.

Answer ready only after:

```text
MQTT subscribed
required authorization validated
runtime entered Serving
```

Offer ready only after:

```text
MQTT subscribed
remote peer authorized
at least one listener bound
accept workers started
runtime entered WaitingForLocalClient
```

The binary may translate that event into `sd_notify READY=1` behind an optional feature.

The daemon core must not depend on systemd.

Add watchdog support only after readiness semantics are proven.

---

## P2-002 — Make bridge-task teardown cooperative before abort fallback

Current session cleanup may abort bridge tasks intentionally.

Future work may add:

```text
request bridge stop
bounded/session-local join
explicit abort fallback with warning
```

Do not mix this into P0 unless a concrete bug requires it.

---

## P2-003 — Replace hand-assembled Debian packaging with standard debhelper/systemd helpers

Possible future work:

- `debhelper`;
- `dh_installsystemd`;
- standard maintainer-script generation;
- distro policy cleanup;
- reproducible package build.

The P0 hand-built package must still be correct before this migration.

---

## P2-004 — Add second-signal emergency process exit

Optional future behavior:

```text
first signal  -> cooperative shutdown
second signal -> explicit warning + forced process exit
```

Do not implement until normal shutdown/task supervision tests are reliable.

Never force-exit on the first signal.

---

## P2-005 — Add signed/notarized macOS packaging and automated service-account provisioning

Future scope:

- signed/notarized `.pkg`;
- safe UID/GID allocation;
- tested install/upgrade/uninstall;
- Apple Silicon/Intel path policy.

Do not make this a prerequisite for manual macOS execution.

---

# Required implementation sequence

Use this sequence. Do not do one giant pass.

```text
Stage 1
  P0-001 runtime phase/status suppression
  P0-002 offer run/finalize boundary

Stage 2
  P0-003 offer worker supervision
  P0-016 offer worker/channel race tests

Stage 3
  P0-004 answer task supervision
  P0-005 answer fatal drain/finalize
  P0-016 answer panic tests

Stage 4
  P0-006 strict/atomic terminal status

Stage 5
  P0-008 deterministic reconnect test
  P0-009 in-flight publish drain test
  P0-010 child-process signal adapter test
  P0-011 required real-binary signal job

Stage 6
  P0-007 remove false sd_notify surface

Stage 7
  P0-012 Debian path fix
  P0-013 Debian upgrade/remove lifecycle
  P0-014 macOS account/permissions
  P0-015 launchd service-user preflight

Stage 8
  P1-001 Android stop outcome
  P1-002 mutex poison policy
  P1-003 tracing/log buffer

Stage 9
  P1-004 strict p2pctl status/path
  P1-005 check-config authorization parity
  P1-006 redactor
  P1-007 diagnostics collection
  P1-008 forward mismatch
  P1-009 best-effort audit
  P1-010 CI permissions

Stage 10
  P1-011 package smoke tests
  P1-012 docs
  P0-017 complete quality gates
```

Recommended commits should be small enough to bisect. Example:

```text
fix(daemon): add runtime phase and suppress drain status
fix(offer): funnel all exits through listener finalizer
fix(offer): supervise accept workers during runtime
fix(answer): observe session task completion independently
fix(status): make terminal writes strict and atomic
test(lifecycle): make reconnect and publish-drain tests deterministic
test(signals): signal child processes and require binary coverage
revert(systemd): remove premature sd_notify readiness
fix(debian): align packaged unit binary paths
fix(debian): preserve active services across upgrades
fix(macos): make service config readable by _p2ptunnel
fix(android): preserve forced-stop failure outcome
fix(android): stop hiding mutex and tracing failures
fix(cli): make status/check-config strict
fix(android): close diagnostics redaction and collection gaps
chore(ci): apply least-privilege permissions
```

---

# Final completion checklist

## Daemon lifecycle

- [ ] Runtime phase exists.
- [ ] No normal status outside `Running`.
- [ ] Offer post-start exits always finalize.
- [ ] Offer channel-close/shutdown race is fixed.
- [ ] Offer work admission is checked after select wake.
- [ ] Offer worker death is supervised while idle.
- [ ] Offer worker death is supervised during active session.
- [ ] Answer session completion is independent of self-sent `Ended`.
- [ ] Normal answer completion does not call `abort()`.
- [ ] Answer panic cannot strand registry state.
- [ ] Fatal answer paths drain before returning.

## Status

- [ ] Ordinary status remains best-effort and logged.
- [ ] Terminal status is strict.
- [ ] Status replacement is atomic.
- [ ] Reader stress test sees no partial JSON.

## Tests

- [ ] Reconnect test observes actual reconnect/backoff event.
- [ ] Answer drain test forces in-flight publish.
- [ ] Signal adapter tests signal child processes only.
- [ ] Required real-binary signal test cannot self-skip.
- [ ] Worker panic tests exist.

## Services and packages

- [ ] Premature `Type=notify` support removed.
- [ ] Baseline `Type=simple` units remain.
- [ ] Debian package units use `/usr/bin`.
- [ ] Build script verifies staged unit executable paths.
- [ ] Active Debian services restart after upgrade.
- [ ] Remove runs daemon-reload.
- [ ] macOS user and group both checked.
- [ ] macOS config dirs are readable by `_p2ptunnel`.
- [ ] `--enable` validates both roles as `_p2ptunnel` before bootstrap.

## Android and diagnostics

- [ ] Forced abort is not clean stop.
- [ ] Stop failure reaches Kotlin.
- [ ] Mutex poison is never empty/default/no-op.
- [ ] Tracing install failure is visible.
- [ ] Log-buffer poison is visible.
- [ ] Secret colon/space variants are redacted.
- [ ] MQTT scheme is preserved.
- [ ] Diagnostics collection errors are explicit.
- [ ] Forward/config mismatch is explicit.

## CLI and CI

- [ ] `p2pctl` missing HOME is a normal error.
- [ ] `p2pctl status` uses typed strict parsing.
- [ ] `p2pctl check-config` verifies authorized peers.
- [ ] CI default permission is `contents: read`.
- [ ] Release job alone has `contents: write`.

## Quality gates

- [ ] `cargo fmt --all --check` passes.
- [ ] Clippy debug/all-targets/all-features passes with warnings denied.
- [ ] Clippy release/all-features passes with warnings denied.
- [ ] Workspace tests pass.
- [ ] Android unit tests pass.
- [ ] systemd validation passes.
- [ ] launchd validation passes on macOS.
- [ ] Debian package builds and package smoke tests pass.
- [ ] Required real-process signal suite actually ran.

