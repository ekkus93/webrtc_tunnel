# WebRTC Tunnel Service Lifecycle and `systemd` Support TODO

## 0. Instructions for Claude Code

Implement this TODO against the repository snapshot reviewed for:

```text
webrtc_tunnel-master_2607040500.zip
```

Read first:

```text
WEBRTC_TUNNEL_SERVICE_LIFECYCLE_SPEC.md
crates/p2p-daemon/src/lib.rs
crates/p2p-daemon/src/answer/mod.rs
crates/p2p-daemon/src/answer/session.rs
crates/p2p-daemon/src/offer/mod.rs
crates/p2p-daemon/src/offer/session/mod.rs
crates/p2p-daemon/src/offer/session/reconnect.rs
crates/p2p-daemon/src/types.rs
crates/p2p-daemon/src/signaling.rs
crates/p2p-daemon/src/status.rs
bins/p2p-offer/src/main.rs
bins/p2p-answer/src/main.rs
crates/p2p-mobile/src/runtime/mod.rs
```

### Non-negotiable implementation rules

- Keep `p2p-offer` and `p2p-answer` as foreground processes.
- Do not add `--daemon`.
- Do not fork.
- Do not add PID files.
- Do not require `systemd` to run the binaries.
- Do not put `systemd` inside Docker.
- Preserve current manual commands.
- Preserve existing public daemon APIs as compatibility wrappers.
- Add shutdown-aware APIs; do not force every existing caller to pass a token immediately.
- Use cooperative shutdown as the primary path.
- Do not make `JoinHandle::abort()` the normal service-stop path.
- Do not silently swallow signal setup failure.
- Do not silently swallow task join failure.
- Do not stop the answer event loop before active answer sessions have drained.
- Do not write a transient normal steady state after shutdown has already been requested.
- Do not claim `mqtt_connected = true` after final shutdown.
- Do not leave offer forwards reported as `listening` after listener tasks are gone.
- Do not add a hidden hard-coded abort timeout in the daemon core.
- Do not change signaling wire format, crypto, identity format, forward semantics, or WebRTC architecture.
- Run formatting, linting, and tests before marking tasks complete.

### Priority definitions

```text
P0 = required for correct service/manual/container lifecycle support
P1 = important integration/polish after P0 is correct
P2 = packaging or advanced supervisor integration
```

---

# P0 tasks

## P0-001 — Add a generic `ShutdownToken`

### Files

Create:

```text
crates/p2p-daemon/src/shutdown.rs
```

Modify:

```text
crates/p2p-daemon/src/lib.rs
```

### Goal

Create one cloneable cancellation primitive that can be triggered by:

- process signals;
- tests;
- Android stop in a later task; or
- any future supervisor adapter.

The token must not know about `systemd`, Docker, Unix PIDs, or Android.

### Recommended implementation

Use the existing Tokio dependency. Do not add a new dependency just for cancellation.

```rust
use tokio::sync::watch;

#[derive(Clone, Debug)]
pub struct ShutdownToken {
    sender: watch::Sender<bool>,
    receiver: watch::Receiver<bool>,
}

impl Default for ShutdownToken {
    fn default() -> Self {
        Self::new()
    }
}

impl ShutdownToken {
    pub fn new() -> Self {
        let (sender, receiver) = watch::channel(false);
        Self { sender, receiver }
    }

    pub fn request_shutdown(&self) {
        let _ = self.sender.send(true);
    }

    pub fn is_shutdown_requested(&self) -> bool {
        *self.receiver.borrow()
    }

    pub async fn cancelled(&mut self) {
        if self.is_shutdown_requested() {
            return;
        }

        while self.receiver.changed().await.is_ok() {
            if self.is_shutdown_requested() {
                return;
            }
        }
    }
}
```

If Clippy or borrow checking requires a small adjustment, preserve the semantics.

### Why the token stores both sender and receiver

Every clone keeps the channel alive. Existing compatibility wrappers can create a token and never externally cancel it without the receiver waking because a sender was dropped.

### Add unit tests

At minimum:

```rust
#[tokio::test]
async fn shutdown_request_wakes_waiter() {
    let token = ShutdownToken::new();
    let mut waiter = token.clone();

    let task = tokio::spawn(async move {
        waiter.cancelled().await;
    });

    token.request_shutdown();
    task.await.expect("waiter task");
}
```

```rust
#[tokio::test]
async fn request_before_wait_returns_immediately() {
    let token = ShutdownToken::new();
    token.request_shutdown();

    let mut waiter = token.clone();
    tokio::time::timeout(
        std::time::Duration::from_millis(100),
        waiter.cancelled(),
    )
    .await
    .expect("already-cancelled token should resolve");
}
```

```rust
#[tokio::test]
async fn every_clone_observes_shutdown() {
    let token = ShutdownToken::new();
    let mut first = token.clone();
    let mut second = token.clone();

    token.request_shutdown();

    first.cancelled().await;
    second.cancelled().await;
}
```

```rust
#[test]
fn repeated_shutdown_requests_are_idempotent() {
    let token = ShutdownToken::new();
    token.request_shutdown();
    token.request_shutdown();
    assert!(token.is_shutdown_requested());
}
```

### Export

In `crates/p2p-daemon/src/lib.rs`:

```rust
mod shutdown;

pub use shutdown::ShutdownToken;
```

### Acceptance criteria

- [ ] Token is cloneable.
- [ ] Token has no OS-specific logic.
- [ ] Request is idempotent.
- [ ] Request-before-wait works.
- [ ] All clones observe request.
- [ ] Existing crates compile.
- [ ] No new dependency was added solely for cancellation.

---

## P0-002 — Add shutdown-aware daemon APIs without breaking existing callers

### Files

Modify:

```text
crates/p2p-daemon/src/lib.rs
crates/p2p-daemon/src/answer/mod.rs
crates/p2p-daemon/src/offer/mod.rs
```

### Goal

Keep current public APIs working while introducing explicit shutdown-aware variants.

### Answer API

Keep:

```rust
pub async fn run_answer_daemon(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
) -> Result<(), DaemonError>
```

Change it into a compatibility wrapper:

```rust
pub async fn run_answer_daemon(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
) -> Result<(), DaemonError> {
    run_answer_daemon_with_shutdown(
        config,
        local_identity,
        authorized_keys,
        ShutdownToken::new(),
    )
    .await
}
```

Add:

```rust
pub async fn run_answer_daemon_with_shutdown(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError>
```

Keep the current transport-injected API and add:

```rust
pub async fn run_answer_daemon_with_transport_and_shutdown<
    T: DaemonSignalingTransport,
>(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    transport: T,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError>
```

The existing:

```rust
run_answer_daemon_with_transport(...)
```

must delegate to the new function with a fresh uncancelled token.

### Offer API

Keep and wrap:

```rust
run_offer_daemon(...)
run_offer_daemon_with_transport(...)
run_offer_daemon_with_status(...)
```

Add:

```rust
run_offer_daemon_with_shutdown(..., shutdown)
run_offer_daemon_with_transport_and_shutdown(..., shutdown)
run_offer_daemon_with_status_and_shutdown(..., status_sink, shutdown)
```

If the test-hook entry point needs cancellation coverage, add an additive shutdown-aware variant rather than breaking its current signature.

### Important wrapper rule

Do not implement compatibility wrappers by storing a token globally.

Each call gets its own token:

```rust
ShutdownToken::new()
```

### Export from `lib.rs`

Update re-exports deliberately. Example shape:

```rust
pub use answer::{
    run_answer_daemon,
    run_answer_daemon_with_shutdown,
    run_answer_daemon_with_transport,
    run_answer_daemon_with_transport_and_shutdown,
};
```

and equivalent offer exports.

### Acceptance criteria

- [ ] Current binary call sites still compile before they are migrated.
- [ ] Current Android call sites still compile.
- [ ] Existing integration tests still compile.
- [ ] Tests can now directly request daemon shutdown without OS signals.
- [ ] No global shutdown state exists.

---

## P0-003 — Add a process signal adapter for SIGINT and SIGTERM

### Files

Create:

```text
crates/p2p-daemon/src/process_signal.rs
```

Modify:

```text
crates/p2p-daemon/src/lib.rs
```

### Goal

Translate process termination signals into one observable event for the CLI binaries.

The daemon state machines must never parse Unix signal numbers themselves.

### Recommended Unix implementation

```rust
#[cfg(unix)]
pub async fn wait_for_process_shutdown_signal() -> Result<&'static str, std::io::Error> {
    use tokio::signal::unix::{signal, SignalKind};

    let mut interrupt = signal(SignalKind::interrupt())?;
    let mut terminate = signal(SignalKind::terminate())?;

    tokio::select! {
        received = interrupt.recv() => {
            received
                .map(|()| "SIGINT")
                .ok_or_else(|| std::io::Error::other("SIGINT signal stream closed"))
        }
        received = terminate.recv() => {
            received
                .map(|()| "SIGTERM")
                .ok_or_else(|| std::io::Error::other("SIGTERM signal stream closed"))
        }
    }
}
```

### Recommended non-Unix implementation

```rust
#[cfg(not(unix))]
pub async fn wait_for_process_shutdown_signal() -> Result<&'static str, std::io::Error> {
    tokio::signal::ctrl_c().await?;
    Ok("Ctrl-C")
}
```

### Export

```rust
mod process_signal;

pub use process_signal::wait_for_process_shutdown_signal;
```

### Failure policy

Forbidden:

```rust
let _ = wait_for_process_shutdown_signal().await;
```

Forbidden:

```rust
wait_for_process_shutdown_signal().await.ok();
```

A signal listener setup/stream failure must remain a real error.

### Acceptance criteria

- [ ] SIGINT is supported on Unix.
- [ ] SIGTERM is supported on Unix.
- [ ] Ctrl-C works on non-Unix targets supported by Tokio.
- [ ] Closed signal stream is an error.
- [ ] No `systemd` dependency exists.

---

## P0-004 — Wire both binaries to race daemon completion against process shutdown

### Files

Modify:

```text
bins/p2p-answer/src/main.rs
bins/p2p-offer/src/main.rs
```

### Goal

The binaries must preserve all current startup validation and then supervise the shutdown-aware daemon future.

### Imports

Answer should import the new symbols:

```rust
use p2p_daemon::{
    ShutdownToken,
    apply_answer_overrides,
    apply_env_overrides,
    run_answer_daemon_with_shutdown,
    setup_logging,
    wait_for_process_shutdown_signal,
};
```

Offer equivalent:

```rust
use p2p_daemon::{
    ShutdownToken,
    apply_env_overrides,
    apply_offer_overrides,
    run_offer_daemon_with_shutdown,
    setup_logging,
    wait_for_process_shutdown_signal,
};
```

### Recommended answer runner

Replace only the final daemon call. Keep config and identity startup order unchanged.

```rust
let shutdown = ShutdownToken::new();
let daemon = run_answer_daemon_with_shutdown(
    config,
    local_identity,
    authorized_keys,
    shutdown.clone(),
);
tokio::pin!(daemon);

let result = tokio::select! {
    result = &mut daemon => result,
    signal = wait_for_process_shutdown_signal() => {
        let signal = signal?;
        tracing::info!(signal, "process shutdown requested");
        shutdown.request_shutdown();
        daemon.await
    }
};

result?;
```

Use the same pattern for offer.

### Important behavior

If the daemon returns first:

- return its result;
- do not wait forever for a signal.

If a signal arrives first:

- log signal name;
- request cooperative shutdown;
- await cleanup;
- return cleanup result.

### Do not

- call `std::process::exit(0)` directly on signal;
- abort the daemon future;
- fork;
- background the process;
- add a PID file;
- add a service-only CLI flag.

### Acceptance criteria

- [ ] Existing command syntax is unchanged.
- [ ] SIGINT causes token request.
- [ ] SIGTERM causes token request.
- [ ] Normal shutdown returns success.
- [ ] Fatal daemon result remains nonzero through existing `main` error handling.

---

## P0-005 — Make answer session tasks cooperatively cancellable

### Files

Modify:

```text
crates/p2p-daemon/src/answer/mod.rs
crates/p2p-daemon/src/answer/session.rs
```

Possibly modify imports in:

```text
crates/p2p-daemon/src/types.rs
```

### Goal

Every active answer session must observe the daemon shutdown request and reach the existing cleanup epilogue.

### Change session task signature

Current conceptual signature:

```rust
run_answer_session_task(
    config,
    local_identity,
    authorized_keys,
    event_tx,
    inbound,
    generation,
    session,
)
```

Add:

```rust
mut shutdown: ShutdownToken
```

Pass a clone from the daemon when the session is spawned.

### Change inner session loop signature

Add a mutable token reference or owned mutable token:

```rust
shutdown: &mut ShutdownToken
```

### Add select arm

In `run_answer_session_task_inner(...)`:

```rust
_ = shutdown.cancelled() => {
    tracing::info!(
        session_id = %session.session_id,
        remote_peer_id = %session.remote_peer_id,
        "answer session shutdown requested"
    );
    return Ok(());
}
```

### Preserve the cleanup epilogue

The outer session task must still do this after the inner function returns:

```rust
cleanup_active_session(&mut session).await;
```

Then it must still send:

```rust
AnswerSessionEvent::Ended { ... }
```

### Do not

Do not change the normal shutdown path to:

```rust
handle.task.abort();
```

before the session task performs cleanup.

### Acceptance criteria

- [ ] Every spawned answer session gets a token clone.
- [ ] Shutdown wakes an idle answer session select loop.
- [ ] Shutdown reaches `cleanup_active_session`.
- [ ] Session sends `Ended` after cleanup.
- [ ] Ordinary session errors still work as before.

---

## P0-006 — Add answer daemon drain mode without deadlocking session events

### Files

Modify:

```text
crates/p2p-daemon/src/answer/mod.rs
```

### Goal

After shutdown request:

- stop accepting new broker work;
- keep processing existing session events;
- let every session end;
- then write final closed status.

### Critical warning

Do not stop reading `event_rx` and then await session tasks.

Session code may already be waiting for the outer daemon to service `Publish` or `RawPublish` and return a oneshot result.

### Recommended top-level loop shape

Introduce:

```rust
let mut shutdown = shutdown;
let mut shutting_down = false;
```

Then restructure the loop:

```rust
loop {
    if shutting_down && sessions_by_id.is_empty() {
        break;
    }

    tokio::select! {
        _ = shutdown.cancelled(), if !shutting_down => {
            tracing::info!(
                active_session_count = sessions_by_id.len(),
                "answer daemon shutdown requested; draining active sessions"
            );
            shutting_down = true;
        }

        payload = poll_idle_signal_payload(&mut ctx, &mut transport), if !shutting_down => {
            let Some(payload) = payload else {
                continue;
            };

            // Keep existing handle_answer_daemon_payload(...) logic.
        }

        event = event_rx.recv() => {
            let Some(event) = event else {
                return Err(DaemonError::Logging(
                    "answer session event channel closed".to_owned()
                ));
            };

            // Keep existing handle_answer_session_event(...) logic.
        }
    }
}
```

### Why this works

When the shared token is requested:

- session select loops begin unwinding;
- sessions that are already awaiting publish responses can still complete because the outer event loop continues processing events;
- each session reaches cleanup;
- each session emits `Ended`;
- the registry shrinks to zero;
- only then does the daemon leave the loop.

### Do not route new MQTT payloads while draining

The payload select arm must be guarded:

```rust
if !shutting_down
```

Do not create new answer sessions after shutdown has started.

### Edge case: no active sessions

If shutdown arrives while the answer daemon is idle:

- set `shutting_down = true`;
- next loop check sees empty registry;
- exit immediately to final status.

### Acceptance criteria

- [ ] No new broker payload handling during drain.
- [ ] Session event handling remains active.
- [ ] Zero-session shutdown exits promptly.
- [ ] Multi-session shutdown drains all sessions.
- [ ] No deadlock when a session has an in-flight publish request.

---

## P0-007 — Add explicit final answer `Closed` status

### Files

Modify:

```text
crates/p2p-daemon/src/answer/mod.rs
crates/p2p-daemon/src/signaling.rs
```

Optionally add a focused helper in:

```text
crates/p2p-daemon/src/status.rs
```

### Goal

After all answer sessions are gone, write truthful terminal status.

### Required final values

```text
current_state        = closed
mqtt_connected       = false
active_session_count = 0
sessions             = []
```

### Recommended code shape

Before final status:

```rust
ctx.runtime.mqtt_connected = false;
```

Then either directly:

```rust
write_answer_status(
    &ctx,
    AnswerStatusSnapshot {
        current_state: DaemonState::Closed,
        sessions: Vec::new(),
    },
)
.await;
```

or add a small dedicated helper:

```rust
pub(crate) async fn write_answer_closed_status(ctx: &RuntimeContext<'_>) {
    write_answer_status(
        ctx,
        AnswerStatusSnapshot {
            current_state: DaemonState::Closed,
            sessions: Vec::new(),
        },
    )
    .await;
}
```

### Important

Do not call `write_answer_registry_status(...)` for final shutdown because that helper hard-codes:

```rust
DaemonState::Serving
```

### Acceptance criteria

- [ ] Final answer state is `Closed`.
- [ ] Final MQTT state is false.
- [ ] Final session count is zero.
- [ ] Final session list is empty.
- [ ] Session capacity remains correct for answer role.

---

## P0-008 — Replace unowned offer accept tasks with an owned runtime object

### Files

Modify:

```text
crates/p2p-daemon/src/offer/mod.rs
```

### Goal

Retain ownership of every listener accept task so shutdown can stop and join them deterministically.

### Add internal runtime struct

Recommended:

```rust
struct OfferAcceptRuntime {
    accepted_clients: mpsc::Receiver<Result<OfferClient, p2p_tunnel::TunnelError>>,
    tasks: Vec<tokio::task::JoinHandle<()>>,
}
```

### Change spawn function

Current:

```rust
fn spawn_offer_accept_loops(
    listeners: Vec<OfferListener>,
) -> mpsc::Receiver<Result<OfferClient, p2p_tunnel::TunnelError>>
```

Target:

```rust
fn spawn_offer_accept_loops(
    listeners: Vec<OfferListener>,
    shutdown: ShutdownToken,
) -> OfferAcceptRuntime
```

### Recommended implementation skeleton

```rust
fn spawn_offer_accept_loops(
    listeners: Vec<OfferListener>,
    shutdown: ShutdownToken,
) -> OfferAcceptRuntime {
    let (tx, rx) = mpsc::channel(64);
    let mut tasks = Vec::with_capacity(listeners.len());

    for listener in listeners {
        let tx = tx.clone();
        let mut task_shutdown = shutdown.clone();

        tasks.push(tokio::spawn(async move {
            loop {
                tokio::select! {
                    _ = task_shutdown.cancelled() => {
                        tracing::debug!(
                            forward_id = listener.forward_id(),
                            "offer accept loop stopping"
                        );
                        break;
                    }

                    accepted = listener.accept_client() => {
                        match accepted {
                            Ok(accepted) => match tx.try_send(Ok(accepted)) {
                                Ok(()) => {}
                                Err(mpsc::error::TrySendError::Full(Ok(dropped))) => {
                                    tracing::warn!(
                                        forward_id = dropped.forward_id(),
                                        "offer pending client queue is full; closing local client"
                                    );
                                }
                                Err(mpsc::error::TrySendError::Closed(_)) => break,
                                Err(mpsc::error::TrySendError::Full(Err(_))) => {}
                            },
                            Err(error) => {
                                tracing::warn!(
                                    reason = %error,
                                    "offer accept loop hit recoverable listener error"
                                );

                                tokio::select! {
                                    _ = task_shutdown.cancelled() => break,
                                    _ = sleep(DAEMON_RUNTIME_RETRY_DELAY) => {}
                                }
                            }
                        }
                    }
                }
            }
        }));
    }

    drop(tx);
    OfferAcceptRuntime { accepted_clients: rx, tasks }
}
```

### Important improvement in the error backoff

The current accept loop sleeps unconditionally after a recoverable listener error.

Make that sleep interruptible by shutdown as shown above. Otherwise shutdown can be delayed by the full retry delay.

### Preserve test helper compatibility

The current test-only helper:

```rust
spawn_offer_accept_loop(listener)
```

may remain as a wrapper that creates an uncancelled token and returns the receiver, or tests may be migrated deliberately.

Do not break unrelated listener tests without reason.

### Acceptance criteria

- [ ] All accept task handles are retained.
- [ ] All accept tasks receive token clones.
- [ ] Listener-error backoff is interruptible.
- [ ] Queue-full behavior remains unchanged.
- [ ] Closed receiver behavior exits task.

---

## P0-009 — Add deterministic offer accept-task join logic

### Files

Modify:

```text
crates/p2p-daemon/src/offer/mod.rs
```

### Goal

After shutdown request, await listener task completion and report join failures.

### Recommended helper

```rust
async fn join_offer_accept_tasks(tasks: Vec<tokio::task::JoinHandle<()>>) {
    for task in tasks {
        if let Err(error) = task.await {
            tracing::warn!(
                reason = %error,
                "offer accept task failed while stopping"
            );
        }
    }
}
```

If you want join failure to make daemon shutdown return `Err`, make that policy explicit and test it. Do not silently discard it.

### Ordering

Recommended offer shutdown order:

```text
1. shared shutdown token already requested
2. active offer session exits and cleans up, if any
3. accept loops observe token and exit
4. join accept tasks
5. drop accepted-client receiver/queue
6. write final Closed status
7. return Ok(())
```

### Acceptance criteria

- [ ] Join handles are awaited.
- [ ] Join failures are visible.
- [ ] No normal shutdown path immediately aborts accept tasks.
- [ ] Listener ports are released after shutdown.

---

## P0-010 — Propagate shutdown into `run_offer_session`

### Files

Modify:

```text
crates/p2p-daemon/src/offer/mod.rs
crates/p2p-daemon/src/offer/session/mod.rs
```

### Goal

An active offer session must stop cooperatively and reach the existing cleanup epilogue.

### Add argument

Recommended:

```rust
pub(crate) async fn run_offer_session<'a, T: DaemonSignalingTransport>(
    config: &'a AppConfig,
    codec: &SignalCodec<'_>,
    transport: &mut T,
    ctx: &mut RuntimeContext<'_>,
    io: OfferSessionIo<'a>,
    mut shutdown: ShutdownToken,
) -> Result<(), DaemonError>
```

### Add main select arm

Inside the session loop:

```rust
_ = shutdown.cancelled() => {
    tracing::info!(
        session_id = %session.session_id,
        remote_peer_id = %session.remote_peer_id,
        "offer session shutdown requested"
    );
    return Ok(());
}
```

### Preserve cleanup epilogue

Do not return from the whole function before this existing code runs:

```rust
cleanup_active_session(&mut session).await;
```

The shutdown branch should return from the inner async result block so the function epilogue still runs.

### Watch the nested `result = async { ... }.await` structure

The current function intentionally collects the loop result and then performs cleanup.

Keep this structure:

```rust
let result = async {
    loop {
        // select including shutdown
    }
}
.await;

if let Err(error) = &result {
    // existing log
}

cleanup_active_session(&mut session).await;
result
```

Do not use a direct top-level `return Ok(())` that bypasses cleanup.

### Acceptance criteria

- [ ] Active offer session observes shutdown.
- [ ] Probe future is dropped on shutdown.
- [ ] Offer bridge future is dropped/stopped through existing cleanup semantics.
- [ ] WebRTC peer closes.
- [ ] Function returns success for normal shutdown.

---

## P0-011 — Make offer reconnect/backoff interruptible by shutdown

### Files

Modify primarily:

```text
crates/p2p-daemon/src/offer/session/mod.rs
```

Avoid rewriting unless necessary:

```text
crates/p2p-daemon/src/offer/session/reconnect.rs
```

### Goal

Shutdown must not wait for a long reconnect sequence.

### Current issue

The ICE-state branch calls:

```rust
attempt_offer_reconnect(...).await?
```

That function may:

- sleep for backoff;
- run ICE restart;
- run renegotiation;
- wait for reconnect response timeouts.

### Recommended minimal change

Race the whole reconnect attempt against shutdown at the call site:

```rust
let reconnected = tokio::select! {
    result = attempt_offer_reconnect(
        ctx,
        codec,
        transport,
        &mut session,
        remote,
    ) => result?,

    _ = shutdown.cancelled() => {
        tracing::info!(
            session_id = %session.session_id,
            remote_peer_id = %session.remote_peer_id,
            "offer reconnect interrupted by shutdown"
        );
        return Ok(());
    }
};
```

Then preserve existing logic using `reconnected`.

### Why this is preferred

It avoids threading shutdown through every reconnect helper while still cancelling:

- backoff sleep;
- reconnect signaling wait;
- ICE restart wait;
- renegotiation wait.

The reconnect future is dropped, then the offer session exits through the normal cleanup epilogue.

### Acceptance criteria

- [ ] Shutdown during reconnect completes promptly.
- [ ] Shutdown during reconnect does not publish the ordinary terminal ICE failure path after cancellation.
- [ ] Session cleanup still runs.
- [ ] Normal reconnect behavior remains unchanged when no shutdown is requested.

---

## P0-012 — Add offer top-level shutdown branch and avoid false steady-state recovery

### Files

Modify:

```text
crates/p2p-daemon/src/offer/mod.rs
```

### Goal

The outer offer daemon must stop correctly whether idle or active.

### Setup

Make shutdown mutable for `cancelled()`:

```rust
let mut shutdown = shutdown;
```

Create accept runtime with token clone:

```rust
let mut accept_runtime = spawn_offer_accept_loops(listeners, shutdown.clone());
```

Use:

```rust
accept_runtime.accepted_clients.recv()
```

where the current code uses `accepted_clients.recv()`.

Pass:

```rust
&mut accept_runtime.accepted_clients
```

into `OfferSessionIo`.

### Add outer select arm

```rust
_ = shutdown.cancelled() => {
    tracing::info!("offer daemon shutdown requested");
    break;
}
```

### After active session returns

Add the shutdown check before ordinary recovery:

```rust
let result = run_offer_session(
    &config,
    &codec,
    transport,
    &mut ctx,
    OfferSessionIo {
        client,
        accepted_clients: &mut accept_runtime.accepted_clients,
        remote: &remote,
        #[cfg(any(test, debug_assertions))]
        session_hook: session_hook.clone(),
    },
    shutdown.clone(),
)
.await;

if shutdown.is_shutdown_requested() {
    if let Err(error) = &result {
        tracing::warn!(
            reason = %error,
            "offer session ended with error during shutdown"
        );
    }
    break;
}

// Existing normal-session behavior only.
if cooldown::session_outcome_enters_cooldown(&result) {
    // existing logic
} else {
    probe_cooldown.reset();
}
recover_daemon_after_session(&ctx, result).await;
```

### Why ordering matters

Do not call:

```rust
recover_daemon_after_session(...)
```

first during shutdown because it writes `WaitingForLocalClient`.

### Acceptance criteria

- [ ] Idle offer shutdown exits loop.
- [ ] Active session shutdown exits loop after session cleanup.
- [ ] Shutdown does not enter probe cooldown.
- [ ] Shutdown does not write ordinary waiting steady state after cancellation.
- [ ] Normal non-shutdown session recovery remains unchanged.

---

## P0-013 — Add explicit final offer `Closed` status with stopped forwards

### Files

Modify:

```text
crates/p2p-daemon/src/status.rs
crates/p2p-daemon/src/signaling.rs
crates/p2p-daemon/src/offer/mod.rs
```

### Goal

After listener tasks are stopped, status must describe the actual stopped runtime.

### Add `stopped` constructor

Recommended:

```rust
impl ForwardRuntimeStatus {
    pub fn stopped(id: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            listen_state: ForwardListenState::Stopped,
            last_error: None,
        }
    }

    // existing listening/error constructors...
}
```

### Build final forward list

Use only configured offer-side forwards if that matches current status semantics:

```rust
ctx.runtime.forward_statuses = config
    .forwards
    .iter()
    .filter(|forward| forward.offer.is_some())
    .map(|forward| ForwardRuntimeStatus::stopped(forward.id.clone()))
    .collect();
```

### Set transport false

```rust
ctx.runtime.mqtt_connected = false;
```

### Write closed status

```rust
write_daemon_status(
    &ctx,
    StatusSnapshot {
        active_session_id: None,
        current_state: DaemonState::Closed,
    },
)
.await;
```

### Required ordering

```text
request shutdown
-> session cleanup
-> listener task shutdown
-> join listener tasks
-> mark forward statuses stopped
-> set MQTT false
-> write Closed
-> return
```

### Acceptance criteria

- [ ] Final offer state is `Closed`.
- [ ] Final MQTT state is false.
- [ ] No active session is reported.
- [ ] Every offer listener status is `Stopped`.
- [ ] No stale `Listening` status remains after cooperative stop.

---

## P0-014 — Add lifecycle tests for `ShutdownToken`

### Files

Prefer:

```text
crates/p2p-daemon/src/shutdown.rs
```

or a dedicated test module.

### Required tests

- [ ] waiter wakes after request;
- [ ] request-before-wait resolves immediately;
- [ ] multiple clones wake;
- [ ] repeated requests are harmless;
- [ ] uncancelled token remains pending for a short timeout.

### Negative test snippet

```rust
#[tokio::test]
async fn uncancelled_token_remains_pending() {
    let mut token = ShutdownToken::new();

    let result = tokio::time::timeout(
        std::time::Duration::from_millis(25),
        token.cancelled(),
    )
    .await;

    assert!(result.is_err(), "uncancelled token unexpectedly resolved");
}
```

Do not use long test sleeps.

---

## P0-015 — Add answer idle-shutdown integration test

### Files

Likely:

```text
crates/p2p-daemon/src/tests/status_and_recovery.rs
```

or:

```text
crates/p2p-daemon/tests/two_node_daemon/recovery_tests.rs
```

Use existing fake/in-memory transport helpers.

### Test flow

```text
start answer daemon with shutdown token
-> wait until Serving/status exists
-> request shutdown
-> await daemon with timeout
-> assert Ok
-> assert final Closed status
-> assert mqtt_connected false
-> assert zero sessions
```

### Pseudocode

```rust
let shutdown = ShutdownToken::new();
let daemon_shutdown = shutdown.clone();

let task = tokio::spawn(run_answer_daemon_with_transport_and_shutdown(
    config,
    identity,
    authorized_keys,
    transport,
    daemon_shutdown,
));

wait_for_status_state(&status_path, DaemonState::Serving).await;
shutdown.request_shutdown();

let result = tokio::time::timeout(Duration::from_secs(2), task)
    .await
    .expect("answer daemon should stop")
    .expect("answer task join");

assert!(result.is_ok());
```

Then parse status and assert terminal fields.

### Acceptance criteria

- [ ] No active sessions required.
- [ ] Test is deterministic.
- [ ] Test fails if shutdown token is ignored.
- [ ] Test fails if final state remains `Serving`.

---

## P0-016 — Add answer active-session drain test

### Files

Prefer existing two-node harness:

```text
crates/p2p-daemon/tests/two_node_daemon/
```

### Goal

Prove the outer answer event loop remains alive while session tasks unwind.

### Required scenario

1. Start answer with token.
2. Start offer and establish a session.
3. Confirm answer reports at least one session.
4. Request answer shutdown.
5. Await answer completion with timeout.
6. Assert final status closed and zero sessions.

### Stronger deadlock coverage

Where practical, request shutdown while a session is actively processing signaling or a publish request rather than only when completely idle.

The test should fail if implementation does:

```text
stop event loop
-> await session
-> session waits for event loop
```

### Acceptance criteria

- [ ] Active session drains.
- [ ] No timeout/deadlock.
- [ ] Final registry empty.
- [ ] Final status closed.

---

## P0-017 — Add offer idle-shutdown and listener-release test

### Files

Likely:

```text
crates/p2p-daemon/src/tests/status_and_recovery.rs
```

or a new focused test module.

### Goal

Prove listener tasks stop and ports are released.

### Test flow

1. Reserve/select an ephemeral port using existing test helpers.
2. Configure offer listener.
3. Start offer daemon with token.
4. Wait for `Listening` status.
5. Request shutdown.
6. Await daemon completion.
7. Assert `Closed`.
8. Assert forward `Stopped`.
9. Bind a new `TcpListener` to the same port.

### Important assertion

```rust
let rebound = tokio::net::TcpListener::bind(original_addr)
    .await
    .expect("offer listener port should be released after shutdown");

drop(rebound);
```

### Acceptance criteria

- [ ] Port is re-bindable immediately after daemon completion.
- [ ] Forward status is stopped.
- [ ] No accept task remains alive.

---

## P0-018 — Add offer active-session shutdown test

### Files

Use the existing two-node harness.

### Test flow

1. Start answer.
2. Start offer with shutdown token.
3. Connect local client.
4. Reach active tunnel/probing state.
5. Request offer shutdown.
6. Await offer completion.
7. Assert local client connection closes.
8. Assert final status closed.
9. Assert listener port is released.

### Acceptance criteria

- [ ] Active offer session reaches cleanup.
- [ ] WebRTC peer closes.
- [ ] Listener tasks stop.
- [ ] Offer returns `Ok(())` for normal shutdown.

---

## P0-019 — Add offer shutdown-during-reconnect test

### Files

Likely:

```text
crates/p2p-daemon/src/tests/reconnect.rs
```

or external two-node recovery tests.

### Goal

Prove shutdown interrupts reconnect/backoff.

### Test flow

1. Force ICE failure or reconnect state.
2. Configure a backoff/timeout longer than the test's expected shutdown latency.
3. Wait until reconnect/backoff state is observed.
4. Request shutdown.
5. Assert daemon exits before the full reconnect timeout/backoff.

### Do not

Do not make the test sleep for the full production timeout.

Use existing test config overrides and short bounded timeouts.

### Acceptance criteria

- [ ] Shutdown wins over reconnect wait.
- [ ] No ordinary terminal ICE-failure publication is required after cancellation.
- [ ] Cleanup runs.

---

## P0-020 — Add real-process SIGTERM/SIGINT integration coverage

### Files

Create a focused integration test or shell test, for example:

```text
tests/lifecycle/process_signal_shutdown.sh
```

or a Rust integration test if repository conventions favor it.

### Goal

Test the layer that direct token tests do not cover:

```text
OS signal -> process adapter -> ShutdownToken -> daemon cleanup
```

### Required coverage

At least:

- `p2p-answer` + SIGTERM;
- `p2p-offer` + SIGTERM;
- one role + SIGINT.

### Suggested shell pattern

```bash
"$BIN" run --config "$CONFIG" >"$LOG" 2>&1 &
pid=$!

wait_for_ready_state
kill -TERM "$pid"
wait "$pid"
code=$?

[ "$code" -eq 0 ] || fail "daemon exited $code"
assert_status_closed
```

### Test safety

Use traps to kill child processes on test failure.

Never send signals to the test runner itself.

### Acceptance criteria

- [ ] Real SIGTERM reaches graceful path.
- [ ] Real SIGINT reaches graceful path.
- [ ] Exit code is 0.
- [ ] Final status is closed.

---

## P0-021 — Add production `systemd` unit files

### Files

Create:

```text
packaging/systemd/p2p-offer.service
packaging/systemd/p2p-answer.service
```

### Offer unit

Use this as the initial implementation unless repo-specific install paths require a documented adjustment:

```ini
[Unit]
Description=WebRTC P2P Tunnel Offer Service
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=p2ptunnel
Group=p2ptunnel
UMask=0077

ExecStartPre=/usr/local/bin/p2pctl check-config --config /etc/p2ptunnel/offer/config.toml
ExecStart=/usr/local/bin/p2p-offer run --config /etc/p2ptunnel/offer/config.toml

Restart=on-failure
RestartSec=5s
TimeoutStopSec=30s
KillSignal=SIGTERM

StateDirectory=p2ptunnel-offer
LogsDirectory=p2ptunnel-offer

StandardOutput=journal
StandardError=journal

NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
LockPersonality=true
RestrictRealtime=true

[Install]
WantedBy=multi-user.target
```

### Answer unit

```ini
[Unit]
Description=WebRTC P2P Tunnel Answer Service
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=p2ptunnel
Group=p2ptunnel
UMask=0077

ExecStartPre=/usr/local/bin/p2pctl check-config --config /etc/p2ptunnel/answer/config.toml
ExecStart=/usr/local/bin/p2p-answer run --config /etc/p2ptunnel/answer/config.toml

Restart=on-failure
RestartSec=5s
TimeoutStopSec=30s
KillSignal=SIGTERM

StateDirectory=p2ptunnel-answer
LogsDirectory=p2ptunnel-answer

StandardOutput=journal
StandardError=journal

NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
LockPersonality=true
RestrictRealtime=true

[Install]
WantedBy=multi-user.target
```

### Important

Do not add networking restrictions without running the real tunnel stack under them.

In particular, do not casually add:

```ini
PrivateNetwork=true
RestrictAddressFamilies=...
IPAddressDeny=any
```

WebRTC, STUN, MQTT, DNS, and interface enumeration must keep working.

### Acceptance criteria

- [ ] Both units exist.
- [ ] Both run foreground binaries.
- [ ] Both use SIGTERM.
- [ ] Both have finite stop timeout.
- [ ] Both restart on failure, not always.
- [ ] Both use explicit config paths.
- [ ] Both default to unprivileged service user.

---

## P0-022 — Add system-service deployment documentation

### Files

Create one of:

```text
docs/SYSTEMD.md
```

or a clearly named equivalent.

Update:

```text
README.md
```

with a short link to the detailed guide.

### Required documentation sections

#### Build and install binaries

Example:

```bash
cargo build --release -p p2p-offer -p p2p-answer -p p2pctl
sudo install -m 0755 target/release/p2p-offer /usr/local/bin/p2p-offer
sudo install -m 0755 target/release/p2p-answer /usr/local/bin/p2p-answer
sudo install -m 0755 target/release/p2pctl /usr/local/bin/p2pctl
```

#### Create service account

Example:

```bash
sudo useradd --system --home /nonexistent --shell /usr/sbin/nologin p2ptunnel
```

Document distro differences if needed; do not hide failures with `|| true`.

#### Create config directories

```bash
sudo install -d -m 0750 -o root -g p2ptunnel /etc/p2ptunnel/offer
sudo install -d -m 0750 -o root -g p2ptunnel /etc/p2ptunnel/answer
```

#### Identity permissions

Private identity:

```text
owner/group must permit the p2ptunnel service to read it
must satisfy existing world-readable/world-writable validation
```

Give concrete safe examples.

#### Absolute service paths

Document:

```text
/etc/p2ptunnel/...
/var/lib/p2ptunnel-offer
/var/lib/p2ptunnel-answer
/var/log/p2ptunnel-offer
/var/log/p2ptunnel-answer
```

#### Journald logging

Recommended config:

```toml
file_logging = false
stdout_logging = true
```

Commands:

```bash
journalctl -u p2p-offer.service
journalctl -u p2p-answer.service
journalctl -u p2p-offer.service -f
```

#### Install/enable/start

```bash
sudo install -m 0644 packaging/systemd/p2p-offer.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now p2p-offer.service
```

Equivalent answer instructions.

#### Stop/restart/status

```bash
sudo systemctl stop p2p-offer.service
sudo systemctl restart p2p-offer.service
sudo systemctl status p2p-offer.service
```

#### Troubleshooting

Include:

```bash
/usr/local/bin/p2pctl check-config --config /etc/p2ptunnel/offer/config.toml
journalctl -u p2p-offer.service -b
```

### Acceptance criteria

- [ ] A new Linux user can install the service without guessing paths.
- [ ] Manual mode is explicitly still supported.
- [ ] Docker mode is explicitly still supported.
- [ ] Docs do not imply `systemd` is mandatory.

---

## P0-023 — Document manual and Docker lifecycle behavior

### Files

Update:

```text
README.md
```

and/or existing deployment docs.

### Manual section

Must preserve:

```bash
p2p-offer run --config ./config.toml
p2p-answer run --config ./config.toml
```

Document Ctrl-C behavior:

```text
Ctrl-C requests graceful shutdown, closes active sessions/listeners, writes final status, and exits.
```

### Docker section

Document exec-form launch:

```dockerfile
STOPSIGNAL SIGTERM
ENTRYPOINT ["/usr/local/bin/p2p-offer"]
CMD ["run", "--config", "/config/config.toml"]
```

Answer equivalent.

State explicitly:

```text
Do not run systemd inside the container. The container runtime supervises the foreground p2p process.
```

### Existing Docker E2E

Preserve the existing:

```text
/bin/sh -c "... && exec /p2pbin/p2p-offer ..."
```

pattern.

### Acceptance criteria

- [ ] Manual mode documented.
- [ ] Docker mode documented.
- [ ] `systemd` clearly optional.
- [ ] Same binary invocation shown in every environment.

---

## P0-024 — Add status tests for stopped forwards

### Files

Modify:

```text
crates/p2p-daemon/src/status.rs
```

and/or daemon lifecycle tests.

### Unit test

```rust
#[test]
fn stopped_forward_status_is_truthful() {
    let status = ForwardRuntimeStatus::stopped("ssh");
    assert_eq!(status.listen_state, ForwardListenState::Stopped);
    assert!(status.last_error.is_none());
}
```

### Serialization test

Assert:

```json
{
  "id": "ssh",
  "listen_state": "stopped",
  "last_error": null
}
```

### Acceptance criteria

- [ ] Constructor tested.
- [ ] Serialization remains snake_case.
- [ ] No secret data is introduced.

---

## P0-025 — Run full Rust quality gates and fix all regressions

### Commands

Run:

```bash
cargo fmt --check
```

Then:

```bash
cargo clippy --workspace --all-targets --all-features -- -D warnings
```

Then:

```bash
cargo test --workspace
```

Also run existing focused tests:

```bash
cargo test -p p2p-daemon
```

```bash
cargo test -p p2p-mobile
```

Run real broker test when environment supports it:

```bash
cargo test -p p2p-daemon --test real_broker_tunnel
```

Run Docker E2E when Docker is available:

```bash
tests/e2e/docker/run.sh
```

### Rules

- Do not suppress Clippy warnings globally.
- Do not add `#[allow(...)]` merely to get green without explaining why the code is correct.
- Do not weaken existing workspace lints.
- Do not change tests to accept stale status.
- Do not turn shutdown assertions into sleeps when a deterministic state wait is possible.
- Report unavailable external test dependencies explicitly.

### Acceptance criteria

- [ ] Formatting passes.
- [ ] Clippy passes with warnings denied.
- [ ] Workspace tests pass.
- [ ] Daemon tests pass.
- [ ] Mobile crate tests pass.
- [ ] External tests run or are explicitly reported unavailable.

---

# P1 tasks

## P1-001 — Migrate Android stop to the shared graceful shutdown token

### Files

Modify:

```text
crates/p2p-mobile/src/runtime/mod.rs
crates/p2p-mobile/src/runtime/state.rs
crates/p2p-mobile/src/runtime/tests.rs
```

### Goal

Use the lifecycle work for Android instead of immediately aborting the daemon task.

### Add controller-owned token

In `RuntimeInner`:

```rust
pub(crate) shutdown: Option<ShutdownToken>,
```

### Start path

Create:

```rust
let shutdown = ShutdownToken::new();
let daemon_shutdown = shutdown.clone();
```

Store the controller copy:

```rust
inner.shutdown = Some(shutdown);
```

Use shutdown-aware daemon APIs in the spawned task.

Offer:

```rust
run_offer_daemon_with_status_and_shutdown(
    config_clone,
    identity,
    authorized_keys,
    status_tx,
    daemon_shutdown,
)
.await
```

Answer equivalent.

### Stop path

Replace normal unconditional:

```rust
task.abort();
```

with:

```rust
if let Some(shutdown) = inner.shutdown.take() {
    shutdown.request_shutdown();
}
```

### Important FFI contract question

Current `stop()` is synchronous. Do not hold the controller mutex while blocking indefinitely waiting for task completion.

A safe implementation may require:

1. take token/task/runtime ownership out of the mutex;
2. request shutdown;
3. allow the Tokio runtime to drive the task;
4. wait outside the mutex if a bounded wait is required;
5. reacquire mutex to finalize state.

Do not casually call `Runtime::block_on` while holding a mutex used by the daemon completion callback.

### Emergency abort fallback

If Android needs a bounded fallback:

- make timeout explicit;
- log a warning/error with timeout duration;
- abort only after cooperative stop fails;
- do not call the result a clean stop.

### Acceptance criteria

- [ ] Normal Android stop requests token.
- [ ] Normal stop reaches daemon cleanup.
- [ ] No mutex deadlock.
- [ ] Duplicate stop remains safe.
- [ ] Existing status reset behavior remains correct.
- [ ] Any forced abort fallback is explicit and tested.

---

## P1-002 — Add Docker stop lifecycle verification

### Files

Modify/add under:

```text
tests/e2e/docker/
```

### Goal

Prove existing `exec` launch receives `docker stop` SIGTERM.

### Test idea

After normal tunnel success, or in a focused script:

```bash
docker compose stop -t 10 offer answer
```

Assert:

- no forced kill timeout;
- exit was normal;
- logs include shutdown request;
- mounted status file is closed if status writing is enabled for the test.

### Acceptance criteria

- [ ] Container runtime stop reaches signal adapter.
- [ ] No `systemd` inside container.
- [ ] Existing tunnel E2E behavior still passes.

---

## P1-003 — Add `systemd-analyze verify` helper

### Files

Create, for example:

```text
scripts/check-systemd-units.sh
```

### Goal

Validate unit syntax where `systemd-analyze` exists.

### Behavior

If tool is installed:

```bash
systemd-analyze verify packaging/systemd/p2p-offer.service
systemd-analyze verify packaging/systemd/p2p-answer.service
```

If tool is not installed:

- print an explicit skip message;
- return success only if this helper is documented as optional;
- do not pretend verification ran.

### CI caution

Verification may complain about missing install-path binaries or users in CI. Handle expected environment-specific warnings deliberately; do not blanket-ignore all stderr.

### Acceptance criteria

- [ ] Syntax verification runs on suitable Linux environments.
- [ ] Skips are explicit.
- [ ] Unexpected verify errors fail the helper.

---

## P1-004 — Add a manual service-install helper

### Files

Optional create:

```text
scripts/install-systemd-services.sh
```

### Goal

Automate the documented manual installation without hiding failures.

### Required safety

Use:

```bash
set -euo pipefail
```

Do not use patterns such as:

```bash
command || true
```

for required setup.

### Suggested responsibilities

- verify running as root or via sudo context;
- verify binaries exist;
- create service user if absent;
- create config directories;
- install unit files;
- run `systemctl daemon-reload`;
- print next steps.

Do not automatically enable/start a service unless the user explicitly requested that behavior or the script has a clear flag.

### Acceptance criteria

- [ ] Failures are visible.
- [ ] Existing config is not overwritten silently.
- [ ] Identity files are not generated or replaced unexpectedly.

---

## P1-005 — Add a second-signal emergency exit policy, if desired

### Goal

Improve manual ergonomics for a hypothetical stuck graceful shutdown.

### Suggested behavior

```text
first SIGINT/SIGTERM -> cooperative shutdown
second SIGINT/SIGTERM -> explicit warning, immediate process termination
```

Do not implement this until normal graceful shutdown tests are reliable.

### Requirements if implemented

- log that cleanup is being bypassed;
- return a non-success or conventional signal exit as appropriate;
- test with a deliberately stuck test daemon;
- never trigger forced exit on the first signal.

---

# P2 tasks

## P2-001 — Add Debian packaging

### Scope

Package:

```text
/usr/bin/p2p-offer
/usr/bin/p2p-answer
/usr/bin/p2pctl
/lib/systemd/system/p2p-offer.service
/lib/systemd/system/p2p-answer.service
```

Package scripts may create:

```text
p2ptunnel system user/group
/etc/p2ptunnel/offer
/etc/p2ptunnel/answer
```

### Requirements

- preserve user config on upgrade;
- do not overwrite private identity files;
- stop services cleanly during package operations;
- do not delete user state on ordinary uninstall unless purge semantics explicitly apply.

---

## P2-002 — Consider templated multi-instance units

Possible future units:

```text
p2p-offer@.service
p2p-answer@.service
```

Possible config mapping:

```text
/etc/p2ptunnel/offer/%i/config.toml
/etc/p2ptunnel/answer/%i/config.toml
```

Do not add this complexity to P0.

---

## P2-003 — Consider `sd_notify` readiness and watchdog support

Possible future work:

- `Type=notify`;
- readiness after MQTT subscription and listener binding;
- watchdog heartbeats;
- richer service health.

Do not make `sd_notify` a dependency of the generic daemon lifecycle.

---

# Final completion checklist

## Core lifecycle

- [ ] `ShutdownToken` exists and is tested.
- [ ] Existing public APIs remain compatible.
- [ ] Shutdown-aware APIs exist.
- [ ] SIGINT adapter exists.
- [ ] SIGTERM adapter exists.
- [ ] Binaries request cooperative shutdown.

## Answer

- [ ] Answer session tasks receive shutdown token.
- [ ] Answer session loop observes token.
- [ ] Answer event loop enters drain mode.
- [ ] Answer event loop keeps processing session events during drain.
- [ ] Registry reaches zero.
- [ ] Final answer status is closed.

## Offer

- [ ] Offer accept task handles are retained.
- [ ] Accept loops observe shutdown.
- [ ] Listener error backoff is interruptible.
- [ ] Accept tasks are joined.
- [ ] Active offer session observes shutdown.
- [ ] Reconnect wait is interruptible.
- [ ] No false waiting-state recovery is written during shutdown.
- [ ] Final offer status is closed.
- [ ] Final offer forward statuses are stopped.

## Deployment

- [ ] `p2p-offer.service` exists.
- [ ] `p2p-answer.service` exists.
- [ ] Units use unprivileged user.
- [ ] Units use `Restart=on-failure`.
- [ ] Units use SIGTERM.
- [ ] Units have `TimeoutStopSec`.
- [ ] Manual use remains documented.
- [ ] Docker use remains documented.
- [ ] No `systemd` requirement exists for manual/container use.

## Validation

- [ ] Token tests pass.
- [ ] Answer idle shutdown test passes.
- [ ] Answer active drain test passes.
- [ ] Offer idle shutdown test passes.
- [ ] Offer active shutdown test passes.
- [ ] Reconnect shutdown test passes.
- [ ] Real SIGTERM test passes.
- [ ] Real SIGINT test passes.
- [ ] Listener port release is verified.
- [ ] Final status assertions pass.
- [ ] `cargo fmt --check` passes.
- [ ] Clippy with `-D warnings` passes.
- [ ] `cargo test --workspace` passes.
- [ ] Android crate still compiles/tests.
- [ ] Docker E2E passes when available.

# Definition of done

This work is done when a single unchanged foreground command can be supervised correctly by all of these environments:

```text
manual terminal -> Ctrl-C -> graceful cleanup -> exit 0
systemd         -> SIGTERM -> graceful cleanup -> exit 0
Docker          -> SIGTERM -> graceful cleanup -> exit 0
unit/integration test -> ShutdownToken -> graceful cleanup -> Ok(())
```

and when neither daemon leaves its final cooperative-shutdown status claiming that MQTT, sessions, or offer listeners are still active.
