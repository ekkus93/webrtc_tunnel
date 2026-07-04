# WebRTC Tunnel Service Lifecycle and `systemd` Support Spec

## 1. Purpose

This spec defines a production-quality lifecycle model for the current WebRTC tunnel codebase so that `p2p-offer` and `p2p-answer` can:

1. run manually in a terminal;
2. run as normal foreground processes in Docker or another container runtime;
3. run as Linux `systemd` services;
4. shut down cleanly on `SIGINT` and `SIGTERM`;
5. preserve truthful status during and after shutdown; and
6. keep the shared Rust daemon code usable by the Android runtime and tests.

The implementation target is the repository snapshot:

```text
webrtc_tunnel-master_2607040500.zip
```

The main architectural rule is:

> `p2p-offer` and `p2p-answer` must remain ordinary foreground applications. `systemd`, Docker, a shell, Android, or a test harness may supervise them, but the daemon core must not have a special `systemd` mode.

No daemonization fork, PID file, background mode, or `--daemon` flag should be added.

---

## 2. Executive design decisions

### 2.1 One executable behavior in every environment

The same commands must continue to work:

```bash
p2p-offer run --config /path/to/config.toml
p2p-answer run --config /path/to/config.toml
```

The process remains in the foreground until:

- it fails fatally;
- the daemon core completes unexpectedly; or
- a shutdown request is received.

Deployment wrappers are external:

```text
                         same foreground binary
                                  │
               ┌──────────────────┼──────────────────┐
               │                  │                  │
               ▼                  ▼                  ▼
          manual shell         systemd            Docker
          Ctrl-C/SIGINT        SIGTERM            SIGTERM
               │                  │                  │
               └──────────────────┼──────────────────┘
                                  ▼
                         generic ShutdownToken
                                  │
                                  ▼
                     graceful daemon cleanup path
```

### 2.2 `systemd` is optional supervision, not application logic

The daemon crate must not:

- call `sd_notify` for this feature;
- require `systemd` libraries;
- query `systemd` state;
- fork into the background;
- create a PID file;
- behave differently because it detects a service manager.

The only process-specific integration required by the Linux binaries is standard signal handling.

### 2.3 Process signals are translated into a generic shutdown request

Required process mappings:

```text
SIGINT   -> request graceful shutdown
SIGTERM  -> request graceful shutdown
```

This covers:

- Ctrl-C in an interactive shell;
- `systemctl stop`;
- `docker stop`;
- Podman/Kubernetes-style process termination;
- ordinary `kill -TERM <pid>`.

The daemon state machines must receive a generic shutdown token, not a Unix signal number.

### 2.4 Existing public daemon entry points remain compatible

Current callers use APIs such as:

```rust
run_offer_daemon(...)
run_answer_daemon(...)
run_offer_daemon_with_status(...)
run_offer_daemon_with_transport(...)
run_answer_daemon_with_transport(...)
```

These existing functions should remain source-compatible. They should delegate to new shutdown-aware implementations using a token that is never externally cancelled.

New shutdown-aware APIs should be additive.

### 2.5 Graceful shutdown must be truthful and bounded by the supervisor, not hidden fallback code

The core must attempt deterministic cleanup and return `Ok(())` for a normal shutdown request.

The core must not silently:

- convert cleanup failures into success without logging them;
- abort all tasks immediately as the primary shutdown mechanism;
- leave `status.json` claiming the tunnel is open after the process has intentionally stopped;
- pretend MQTT is connected after shutdown;
- leave offer forwards reported as `listening` after their listeners have been dropped.

The first implementation should not add a hidden internal “abort after N seconds” fallback. `systemd` already provides the external hard-stop boundary through `TimeoutStopSec`. Manual and container users retain normal process-control mechanisms.

---

## 3. Current repository state

### 3.1 Binary entry points are already service-friendly

The current binaries are thin foreground launchers:

```text
bins/p2p-offer/src/main.rs
bins/p2p-answer/src/main.rs
```

Both currently:

1. parse `run --config ...`;
2. load config;
3. apply environment and CLI overrides;
4. validate config;
5. create runtime directories;
6. initialize logging;
7. load the identity;
8. validate identity peer ID;
9. load `authorized_keys`; and
10. await the long-running daemon function.

This is already the correct basic process model for `systemd` and containers.

### 3.2 The daemon crate already separates process lifetime from session lifetime

The crate root explicitly states that daemon lifetime is longer than session lifetime:

```text
crates/p2p-daemon/src/lib.rs
```

The role state machines live in:

```text
crates/p2p-daemon/src/offer/
crates/p2p-daemon/src/answer/
```

No architecture rewrite is required.

### 3.3 Current answer daemon lifecycle

The answer daemon currently has a top-level loop in:

```text
crates/p2p-daemon/src/answer/mod.rs
```

It selects between:

- idle MQTT signaling payloads; and
- events from per-session tasks.

Each answer session is represented by `AnswerSessionHandle`, which contains:

```rust
pub(crate) struct AnswerSessionHandle {
    pub(crate) generation: SessionGeneration,
    pub(crate) remote_peer_id: PeerId,
    pub(crate) inbound: mpsc::Sender<DecodedSignal>,
    pub(crate) status: SessionStatusSnapshot,
    pub(crate) task: JoinHandle<()>,
}
```

Per-session work runs in:

```text
crates/p2p-daemon/src/answer/session.rs
```

The session task already calls:

```rust
cleanup_active_session(&mut session).await;
```

before sending `AnswerSessionEvent::Ended`.

This is good. The missing piece is a normal way to ask the session task to exit.

### 3.4 Current offer daemon lifecycle

The offer daemon currently:

1. subscribes to MQTT;
2. binds all configured local offer listeners;
3. spawns one accept task per listener;
4. waits for either a local client or idle MQTT signaling;
5. calls `run_offer_session(...)` synchronously when a client arrives; and
6. returns to the waiting state after the session ends.

Relevant files:

```text
crates/p2p-daemon/src/offer/mod.rs
crates/p2p-daemon/src/offer/session/mod.rs
crates/p2p-daemon/src/offer/session/reconnect.rs
```

Current accept-loop tasks are spawned and their `JoinHandle`s are discarded. That prevents deterministic service shutdown.

### 3.5 Current cleanup primitive is useful but not sufficient by itself

`cleanup_active_session(...)` already:

- stops the bridge task;
- marks the bridge closed;
- drops the data channel handle; and
- closes the WebRTC peer.

That is the right terminal cleanup path.

The new lifecycle implementation must ensure normal shutdown reaches this function rather than simply dropping or aborting the whole daemon future.

### 3.6 Status already has the required terminal enum values

The current code already defines:

```rust
DaemonState::Closed
ForwardListenState::Stopped
```

No status schema migration is required merely to represent shutdown.

### 3.7 Android is an integration constraint

The shared daemon crate is used by:

```text
crates/p2p-mobile/src/runtime/mod.rs
```

The Android controller currently stores a daemon `JoinHandle` and calls:

```rust
task.abort();
```

on stop.

This feature must not break Android compilation or the current public daemon APIs. Adopting the new graceful token in Android is recommended as a follow-up task, but service support must not depend on an Android rewrite.

### 3.8 Docker already follows the correct foreground-process pattern

The existing Docker E2E stack uses shell setup followed by `exec`:

```text
mkdir -p ... && exec /p2pbin/p2p-answer run --config ...
mkdir -p ... && exec /p2pbin/p2p-offer run --config ...
```

Because of `exec`, the tunnel binary replaces the shell and receives container stop signals directly. The lifecycle work in this spec should make that existing pattern shut down cleanly without requiring `systemd` inside the container.

---

## 4. Goals

### 4.1 P0 goals

The implementation must:

- preserve manual foreground execution;
- preserve Docker/container execution;
- add `SIGINT` handling;
- add `SIGTERM` handling;
- add a reusable, platform-neutral shutdown token;
- propagate shutdown into both daemon state machines;
- propagate shutdown into active answer sessions;
- propagate shutdown into an active offer session;
- stop and join offer listener accept tasks;
- keep the answer event loop alive while answer sessions drain;
- close WebRTC peers and bridge tasks through existing cleanup paths;
- write final `Closed` status;
- report `mqtt_connected = false` after shutdown;
- report zero active sessions after shutdown;
- report offer listeners as `Stopped` after shutdown;
- return exit status 0 for normal graceful shutdown;
- add `systemd` unit files;
- document system-service, manual, and container operation; and
- add automated lifecycle tests.

### 4.2 P1 goals

Recommended follow-up work:

- migrate Android stop from unconditional task abort to the shared shutdown token;
- add Docker stop/restart lifecycle assertions;
- add a service-install helper;
- validate unit files automatically when `systemd-analyze` is available; and
- add a second-signal emergency-exit policy if desired.

### 4.3 P2 goals

Possible later packaging work:

- Debian packaging;
- dedicated package-created system user;
- package-managed config directories;
- upgrade/uninstall handling;
- templated multi-instance units; and
- optional `sd_notify` readiness/watchdog integration.

---

## 5. Non-goals

This pass must not:

- add a `--daemon` CLI flag;
- fork or double-fork;
- detach from the terminal;
- create PID files;
- require `systemd` at build time or runtime;
- put `systemd` inside Docker containers;
- replace MQTT signaling;
- change the signaling wire format;
- change identity or `authorized_keys` formats;
- add TURN;
- change forward configuration semantics;
- redesign the WebRTC data plane;
- change ordinary session recovery policy;
- rewrite Android UI code;
- add hidden silent fallback behavior; or
- treat forced task abort as the normal clean-stop path.

---

## 6. Required architecture

### 6.1 Layering

The target layering is:

```text
┌─────────────────────────────────────────────────────────────┐
│ Deployment supervisor                                       │
│                                                             │
│ shell / systemd / Docker / Android / test harness           │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            │ request shutdown
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ Process/platform adapter                                    │
│                                                             │
│ SIGINT + SIGTERM -> ShutdownToken                           │
│ Android stop       -> ShutdownToken (P1)                    │
│ test code          -> ShutdownToken                         │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ p2p-daemon lifecycle                                        │
│                                                             │
│ offer daemon / answer daemon                                │
│ listener tasks / session tasks / status                     │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ Existing session cleanup                                    │
│                                                             │
│ bridge stop -> data channel drop -> peer.close()             │
└─────────────────────────────────────────────────────────────┘
```

### 6.2 Generic shutdown token

Add:

```text
crates/p2p-daemon/src/shutdown.rs
```

Recommended implementation:

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

Properties required by tests:

- cloneable;
- idempotent shutdown request;
- a request made before `cancelled().await` returns immediately;
- a request is observed by every clone;
- no OS or `systemd` dependency;
- no hidden timeout.

The exact internal primitive may differ if there is a strong reason, but do not add a dependency solely for cancellation unless it materially simplifies the implementation.

### 6.3 Public API compatibility

Preserve existing APIs as wrappers.

Recommended shape:

```rust
pub async fn run_offer_daemon(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
) -> Result<(), DaemonError> {
    run_offer_daemon_with_shutdown(
        config,
        local_identity,
        authorized_keys,
        ShutdownToken::new(),
    )
    .await
}
```

Add the shutdown-aware API:

```rust
pub async fn run_offer_daemon_with_shutdown(
    config: AppConfig,
    local_identity: IdentityFile,
    authorized_keys: AuthorizedKeys,
    shutdown: ShutdownToken,
) -> Result<(), DaemonError>
```

Do the same for answer.

For status-streaming offer callers, preserve:

```rust
run_offer_daemon_with_status(...)
```

and add:

```rust
run_offer_daemon_with_status_and_shutdown(..., shutdown)
```

For injected signaling transports used by integration tests, add shutdown-aware variants rather than forcing tests to use process signals.

Suggested public API matrix:

| Existing API | New shutdown-aware API |
|---|---|
| `run_offer_daemon` | `run_offer_daemon_with_shutdown` |
| `run_answer_daemon` | `run_answer_daemon_with_shutdown` |
| `run_offer_daemon_with_status` | `run_offer_daemon_with_status_and_shutdown` |
| `run_offer_daemon_with_transport` | `run_offer_daemon_with_transport_and_shutdown` |
| `run_answer_daemon_with_transport` | `run_answer_daemon_with_transport_and_shutdown` |

The debug/test hook entry point may also receive an additive shutdown-aware variant if external integration tests need both the session hook and cancellation.

Do not change all existing tests at once just to pass a token. Existing wrapper behavior should keep them compiling.

---

## 7. Process signal integration

### 7.1 Signal adapter location

Add a small process-signal adapter, preferably:

```text
crates/p2p-daemon/src/process_signal.rs
```

This module is allowed to know about OS signals. The daemon state machines must not.

Suggested API:

```rust
pub async fn wait_for_process_shutdown_signal() -> Result<&'static str, std::io::Error>
```

Unix behavior:

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

Non-Unix behavior:

```rust
#[cfg(not(unix))]
pub async fn wait_for_process_shutdown_signal() -> Result<&'static str, std::io::Error> {
    tokio::signal::ctrl_c().await?;
    Ok("Ctrl-C")
}
```

A closed signal stream must not be treated as a successful shutdown signal.

### 7.2 Binary behavior

After normal config, identity, and logging initialization, the binaries should:

1. create a `ShutdownToken`;
2. start the shutdown-aware daemon future;
3. race daemon completion against the process signal future;
4. on signal, log the exact signal name;
5. request shutdown on the token;
6. await daemon cleanup; and
7. return success if cleanup succeeds.

Recommended shape:

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

The offer binary should use the same pattern.

### 7.3 Exit semantics

Required:

| Cause | Exit result |
|---|---|
| normal SIGINT | success / exit 0 |
| normal SIGTERM | success / exit 0 |
| config failure | nonzero |
| identity failure | nonzero |
| authorized key failure | nonzero |
| fatal daemon startup failure | nonzero |
| cleanup returns fatal error | nonzero |

A normal supervisor stop must not be logged as an application crash.

---

## 8. Answer daemon graceful shutdown

### 8.1 Critical deadlock constraint

Do not implement answer shutdown as:

```rust
shutdown.request_shutdown();
for handle in sessions {
    handle.task.await;
}
```

while the outer answer event loop has stopped.

That can deadlock.

An answer session may already be inside a helper that:

1. sends `AnswerSessionEvent::Publish` or `RawPublish` to the outer daemon; and
2. waits for the outer daemon to return a result through a oneshot channel.

Therefore:

> During answer shutdown, stop accepting new signaling/session work, but continue servicing the existing session event channel until every registered session has ended.

### 8.2 Required answer shutdown state machine

Use a `shutting_down` flag in the top-level answer loop.

Conceptual structure:

```rust
let mut shutting_down = false;
let mut shutdown = shutdown;

loop {
    if shutting_down && sessions_by_id.is_empty() {
        break;
    }

    tokio::select! {
        _ = shutdown.cancelled(), if !shutting_down => {
            tracing::info!("answer daemon shutdown requested; draining sessions");
            shutting_down = true;
        }

        payload = poll_idle_signal_payload(&mut ctx, &mut transport), if !shutting_down => {
            // Existing payload handling.
        }

        event = event_rx.recv() => {
            // Existing event handling remains active during drain.
        }
    }
}
```

Once `shutting_down` is true:

- do not poll MQTT for new idle work;
- do not create new sessions;
- do not route new broker payloads;
- continue handling already-queued session events;
- continue responding to session publish requests needed to let in-flight session code unwind;
- wait until the session registry is empty.

### 8.3 Pass shutdown into every answer session

When spawning a session:

```rust
let task = tokio::spawn(run_answer_session_task(
    Arc::clone(config),
    Arc::clone(local_identity),
    Arc::clone(authorized_keys),
    event_tx.clone(),
    inbound_rx,
    generation,
    session,
    shutdown.clone(),
));
```

The per-session loop adds:

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

The existing outer `run_answer_session_task(...)` must still execute:

```rust
cleanup_active_session(&mut session).await;
```

and emit `AnswerSessionEvent::Ended`.

### 8.4 Do not make `JoinHandle::abort()` the normal answer shutdown path

The existing `Ended` handler currently removes a session after the session task has already performed cleanup. That is not the same problem as aborting an active session before cleanup.

For service shutdown:

- request cancellation cooperatively;
- let the session task reach `cleanup_active_session`;
- let it emit `Ended`;
- let the registry remove it normally.

A forced abort may remain only as an explicit emergency fallback in a later, separately reviewed change.

### 8.5 Final answer status

After the registry is empty:

```text
current_state        = closed
mqtt_connected       = false
active_session_count = 0
sessions             = []
```

The answer status should retain the normal answer `session_capacity` value.

Then return `Ok(())`.

---

## 9. Offer daemon graceful shutdown

### 9.1 Track listener task ownership

Current `spawn_offer_accept_loops(...)` returns only an `mpsc::Receiver` and discards all spawned task handles.

Replace that internal shape with an owned runtime object.

Suggested structure:

```rust
struct OfferAcceptRuntime {
    accepted_clients: mpsc::Receiver<Result<OfferClient, p2p_tunnel::TunnelError>>,
    tasks: Vec<tokio::task::JoinHandle<()>>,
}
```

Suggested spawn shape:

```rust
fn spawn_offer_accept_loops(
    listeners: Vec<OfferListener>,
    shutdown: ShutdownToken,
) -> OfferAcceptRuntime
```

Each accept task must select between:

- shutdown; and
- `listener.accept_client()`.

Conceptual snippet:

```rust
let mut task_shutdown = shutdown.clone();
let task = tokio::spawn(async move {
    loop {
        tokio::select! {
            _ = task_shutdown.cancelled() => {
                tracing::debug!("offer accept loop stopping");
                break;
            }
            accepted = listener.accept_client() => {
                // Preserve existing queue-full and recoverable-listener-error behavior.
            }
        }
    }
});
```

The daemon must retain the `JoinHandle`s and join them during shutdown.

### 9.2 Idle offer shutdown

The top-level offer select loop adds a shutdown branch:

```rust
_ = shutdown.cancelled() => {
    tracing::info!("offer daemon shutdown requested");
    break;
}
```

When this branch wins:

1. no new local clients are accepted by the daemon state machine;
2. listener tasks observe the same token and exit;
3. listener objects drop;
4. task handles are joined;
5. queued local clients are dropped when the receiver/runtime is dropped;
6. final closed status is written; and
7. the daemon returns `Ok(())`.

### 9.3 Active offer session shutdown

Pass a shutdown token into:

```rust
run_offer_session(...)
```

The main offer session select loop adds:

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

The existing function epilogue must remain the cleanup authority:

```rust
cleanup_active_session(&mut session).await;
```

### 9.4 Reconnect cancellation

An offer session may be inside:

```rust
attempt_offer_reconnect(...).await
```

That path can sleep during backoff or wait for negotiation.

Do not require a large reconnect rewrite. Race the reconnect attempt against shutdown at the call site:

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
        return Ok(());
    }
};
```

Dropping the in-flight reconnect future is acceptable because the session immediately exits to the existing terminal cleanup path, which closes the peer.

### 9.5 Avoid transient false steady-state status during shutdown

After `run_offer_session(...)` returns, the outer daemon currently calls:

```rust
recover_daemon_after_session(&ctx, result).await;
```

During service shutdown, that would briefly write `WaitingForLocalClient` before `Closed`.

Required logic:

```rust
let result = run_offer_session(...).await;

if shutdown.is_shutdown_requested() {
    if let Err(error) = &result {
        tracing::warn!(reason = %error, "offer session ended with error during shutdown");
    }
    break;
}

// Existing ordinary recovery behavior.
recover_daemon_after_session(&ctx, result).await;
```

Normal session failure recovery remains unchanged.

### 9.6 Final offer status

After listener tasks and any active session are cleaned up:

```text
current_state        = closed
mqtt_connected       = false
active_session_count = 0
sessions             = []
```

Every configured offer listener should be represented as:

```text
listen_state = stopped
last_error   = null
```

Then return `Ok(())`.

---

## 10. Final status semantics

### 10.1 Required status matrix

| Runtime condition | `current_state` | `mqtt_connected` | sessions | offer forwards |
|---|---|---:|---:|---|
| answer serving | `serving` | latest known | current sessions | N/A/empty |
| offer idle | `waiting_for_local_client` | latest known | 0 | listening/error |
| active session | existing active state | latest known | active | listening/error |
| shutdown requested, draining | existing measured state may remain briefly | latest known | draining | listeners stopping |
| shutdown complete | `closed` | `false` | 0 | `stopped` |

### 10.2 Status write failure policy

The current status layer logs a warning and continues when a status file write fails.

This feature does not need to redesign that policy.

Required:

- final status write failure must emit a visible warning;
- do not claim the final status was written if it was not;
- do not convert a normal clean shutdown into an unrelated crash solely because status persistence failed unless the project intentionally changes the global status-write policy in a separate review.

### 10.3 Stale status limitation

No process can rewrite its status after `SIGKILL`, kernel panic, power loss, or host crash.

The required guarantee is therefore:

> On cooperative shutdown through SIGINT, SIGTERM, Android stop, or test cancellation, the daemon writes `Closed` before returning.

Consumers that need crash detection must also consider process supervision or status freshness; that is outside this pass.

---

## 11. `systemd` deployment design

### 11.1 Unit file location in the repository

Add:

```text
packaging/systemd/p2p-offer.service
packaging/systemd/p2p-answer.service
```

Do not hide unit files in docs-only prose.

### 11.2 Recommended installed filesystem layout

For a manual system-wide installation:

```text
/usr/local/bin/p2p-offer
/usr/local/bin/p2p-answer
/usr/local/bin/p2pctl

/etc/p2ptunnel/offer/config.toml
/etc/p2ptunnel/offer/identity
/etc/p2ptunnel/offer/authorized_keys
/etc/p2ptunnel/offer/mqtt_password

/etc/p2ptunnel/answer/config.toml
/etc/p2ptunnel/answer/identity
/etc/p2ptunnel/answer/authorized_keys
/etc/p2ptunnel/answer/mqtt_password

/var/lib/p2ptunnel-offer/
/var/log/p2ptunnel-offer/

/var/lib/p2ptunnel-answer/
/var/log/p2ptunnel-answer/
```

Using separate role directories prevents accidental state collisions if both services are installed on one host.

### 11.3 Dedicated service account

Recommended account:

```text
user:  p2ptunnel
group: p2ptunnel
```

The service should not run as root merely because it is a service.

If a forward must bind a privileged port below 1024, handle that as an explicit deployment decision. Do not silently run the entire daemon as root.

### 11.4 System-service config paths must be absolute

Do not use `~/...` in system service configs.

Offer example:

```toml
[paths]
identity = "/etc/p2ptunnel/offer/identity"
authorized_keys = "/etc/p2ptunnel/offer/authorized_keys"
state_dir = "/var/lib/p2ptunnel-offer"
log_dir = "/var/log/p2ptunnel-offer"
```

Answer example:

```toml
[paths]
identity = "/etc/p2ptunnel/answer/identity"
authorized_keys = "/etc/p2ptunnel/answer/authorized_keys"
state_dir = "/var/lib/p2ptunnel-answer"
log_dir = "/var/log/p2ptunnel-answer"
```

The service must not depend on a particular `HOME` value.

### 11.5 Prefer journald for system-service logs

Recommended service config:

```toml
[logging]
level = "info"
format = "json"
file_logging = false
stdout_logging = true
log_file = "/var/log/p2ptunnel-offer/p2ptunnel.log"
redact_secrets = true
redact_sdp = true
redact_candidates = true
log_rotation = "none"
```

The unused `log_file` remains required by the current config schema but is not opened when `file_logging = false`.

This avoids unbounded application-managed log files while `log_rotation = "none"` remains the only supported application setting.

### 11.6 Offer unit

Recommended baseline:

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

### 11.7 Answer unit

Recommended baseline:

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

### 11.8 Hardening caution

Do not blindly add service restrictions that break WebRTC networking or valid user configurations.

Specifically, do not add without dedicated testing:

- `PrivateNetwork=true`;
- a restrictive `RestrictAddressFamilies=` list;
- `IPAddressDeny=any`;
- empty capability sets if privileged local ports are a supported deployment;
- filesystem restrictions that prevent configured state/status/log writes.

Hardening is valuable only when tested against:

- interface discovery;
- DNS;
- MQTT TLS;
- STUN;
- direct ICE host candidates;
- local offer listeners; and
- configured status paths.

### 11.9 `Restart=on-failure` semantics

Normal SIGTERM/SIGINT graceful shutdown returns success, so `Restart=on-failure` must not restart a deliberately stopped service.

Fatal startup/runtime errors return nonzero, allowing `systemd` to restart according to policy.

Do not use `Restart=always` in the baseline unit because it would restart after a normal clean exit unless the administrator explicitly wants that behavior.

---

## 12. Manual execution requirements

Manual operation must remain unchanged:

```bash
p2p-answer run --config ./answer.toml
```

```bash
p2p-offer run --config ./offer.toml
```

Expected behavior:

```text
start
  -> foreground process
  -> Ctrl-C
  -> SIGINT observed
  -> shutdown token requested
  -> sessions/listeners clean up
  -> final Closed status
  -> exit 0
```

Do not require:

- `systemctl`;
- root;
- `/etc` paths;
- a service account;
- journald.

User-local `~/.config/p2ptunnel` and `~/.local/state/p2ptunnel` paths remain valid for manual execution.

---

## 13. Docker/container requirements

### 13.1 Do not run `systemd` in the container

The container runtime is already the supervisor.

Use the normal foreground binary.

Recommended Dockerfile shape:

```dockerfile
STOPSIGNAL SIGTERM
ENTRYPOINT ["/usr/local/bin/p2p-answer"]
CMD ["run", "--config", "/config/config.toml"]
```

Offer image:

```dockerfile
STOPSIGNAL SIGTERM
ENTRYPOINT ["/usr/local/bin/p2p-offer"]
CMD ["run", "--config", "/config/config.toml"]
```

### 13.2 Exec form is required

Preferred:

```dockerfile
ENTRYPOINT ["/usr/local/bin/p2p-answer"]
```

Avoid shell-form process launch:

```dockerfile
CMD p2p-answer run --config /config/config.toml
```

The existing E2E compose setup already uses `exec` after shell setup. Preserve that.

### 13.3 Docker logging

Recommended:

```toml
file_logging = false
stdout_logging = true
```

Let the container runtime collect stdout/stderr.

### 13.4 Docker state

If `status.json` or other state must survive container replacement, mount the configured state directory.

The binary itself must not care whether the path is:

```text
/var/lib/p2p
/data
/state
```

as long as the config and permissions are valid.

---

## 14. Android compatibility and follow-up

### 14.1 P0 compatibility requirement

P0 must preserve current Android compilation by keeping the existing public daemon wrappers.

The Android runtime may continue using:

```rust
run_offer_daemon_with_status(...)
run_answer_daemon(...)
```

until the P1 migration is implemented.

### 14.2 Recommended P1 Android migration

The Android controller should eventually own a `ShutdownToken` in addition to the daemon task.

Suggested state:

```rust
pub(crate) struct RuntimeInner {
    // existing fields...
    pub(crate) shutdown: Option<ShutdownToken>,
}
```

On start:

```rust
let shutdown = ShutdownToken::new();
let daemon_shutdown = shutdown.clone();
```

Run the shutdown-aware daemon API.

On stop:

```rust
if let Some(shutdown) = inner.shutdown.take() {
    shutdown.request_shutdown();
}
```

The Android controller should then allow the daemon task to finish normally. If Android requires a bounded synchronous FFI stop contract, design that timeout and any emergency abort explicitly and log it loudly. Do not silently fall back to `task.abort()` as if it were a clean stop.

---

## 15. Error-handling and observability rules

### 15.1 No quiet signal failure

If signal listener setup fails:

- return a real error;
- do not run indefinitely without the requested signal handling;
- do not print only debug-level output.

### 15.2 No quiet listener-task loss

If an offer accept task panics or its `JoinHandle` fails:

- log the join failure;
- do not silently treat it as a successful listener shutdown.

Whether it should fail the whole daemon during ordinary runtime is a separate policy decision; during explicit shutdown, the failure must at least be visible.

### 15.3 No fake clean shutdown after cleanup failure

If `peer.close()` fails, the existing cleanup warning remains visible.

Do not erase it.

If a new shutdown-specific cleanup step fails, log enough context to identify:

- role;
- session ID if applicable;
- remote peer if applicable;
- listener/forward ID if applicable; and
- the actual error.

### 15.4 Do not send mandatory broker traffic during global shutdown

Global shutdown should not depend on successfully publishing and receiving an acknowledged signaling `Close` message.

Required cleanup authority is local:

- stop new work;
- close bridge work;
- drop data channels;
- close the peer connection;
- stop listeners;
- write local final status.

A future best-effort remote close notification may be added only if it is bounded and cannot prevent process shutdown.

---

## 16. Detailed test plan

### 16.1 Shutdown token unit tests

Add tests for:

1. waiting clone wakes after request;
2. request before wait returns immediately;
3. multiple clones all wake;
4. repeated requests are harmless;
5. uncancelled token does not complete within a short test timeout.

### 16.2 Answer idle shutdown test

Using injected signaling transport:

1. start answer daemon with shutdown-aware API;
2. wait until status reports `Serving`;
3. request shutdown;
4. assert daemon future completes within a test timeout;
5. assert result is `Ok(())`;
6. read final status;
7. assert `Closed`;
8. assert MQTT false;
9. assert zero sessions.

### 16.3 Answer active-session shutdown test

Using the existing two-node harness where practical:

1. start answer with token;
2. establish a real session;
3. verify session appears in registry/status;
4. request answer shutdown;
5. keep event processing active during drain;
6. assert answer completes;
7. assert no answer session task remains registered;
8. assert final status is closed and empty;
9. assert peer/bridge cleanup path ran.

This test should catch the deadlock caused by stopping the answer event loop too early.

### 16.4 Offer idle shutdown test

1. configure at least one offer listener on an ephemeral port;
2. start offer daemon with token;
3. wait for listener status `Listening`;
4. request shutdown;
5. assert daemon completes;
6. assert final status `Closed`;
7. assert forward status `Stopped`;
8. rebind the same TCP port immediately.

Immediate port rebind is an important proof that listener ownership was actually released.

### 16.5 Offer active-session shutdown test

1. establish offer/answer pair;
2. connect a local offer client;
3. reach active/probing/tunnel state;
4. request offer shutdown;
5. assert offer session returns through cleanup;
6. assert client connection closes;
7. assert listener tasks exit;
8. assert daemon returns `Ok(())`;
9. assert final status `Closed`.

### 16.6 Offer reconnect/backoff shutdown test

1. force the offer into reconnect or backoff;
2. request shutdown;
3. assert it does not wait for the full configured reconnect sequence;
4. assert the reconnect future is dropped;
5. assert session cleanup closes the peer;
6. assert final status is `Closed`.

### 16.7 Manual process signal integration tests

Launch the real binaries as child processes.

For each role:

1. start process with a test config;
2. wait for steady state;
3. send SIGTERM;
4. assert exit code 0;
5. assert final status closed.

At least one test should send SIGINT and assert the same behavior.

Do not simulate process signals only by directly cancelling the token; both layers need coverage.

### 16.8 Docker lifecycle test

Extend the existing Docker E2E workflow or add a focused lifecycle test:

1. start daemon container;
2. run `docker stop` with a reasonable timeout;
3. assert container exits normally rather than timing out and being killed;
4. inspect logs for shutdown request and completion;
5. if state is mounted, assert final status is closed.

### 16.9 Regression suite

Required before completion:

```bash
cargo fmt --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace
```

Also run the existing real-broker and Docker E2E tests when the environment supports them.

Do not mark a test as passed merely because the environment lacked the dependency. Report skipped external tests explicitly.

---

## 17. Acceptance criteria

The feature is complete only when all of the following are true.

### Process behavior

- [ ] `p2p-offer run ...` still works manually.
- [ ] `p2p-answer run ...` still works manually.
- [ ] Ctrl-C triggers graceful shutdown.
- [ ] SIGTERM triggers graceful shutdown.
- [ ] Normal signal shutdown exits 0.
- [ ] Fatal startup errors still exit nonzero.
- [ ] No `--daemon` flag exists.
- [ ] No PID file exists.
- [ ] No fork/background behavior exists.

### Answer behavior

- [ ] Answer stops accepting new work after shutdown request.
- [ ] Existing answer session events continue to be serviced during drain.
- [ ] Active answer sessions observe shutdown.
- [ ] Active answer sessions reach `cleanup_active_session`.
- [ ] The answer registry drains to zero.
- [ ] No shutdown deadlock occurs while a session waits on a publish response.

### Offer behavior

- [ ] Offer accept task handles are retained.
- [ ] Offer accept loops observe shutdown.
- [ ] Offer accept tasks are joined.
- [ ] Active offer session observes shutdown.
- [ ] Reconnect/backoff can be interrupted by shutdown.
- [ ] Active offer session reaches `cleanup_active_session`.
- [ ] Bound listener ports are released.

### Status behavior

- [ ] Final state is `Closed`.
- [ ] Final `mqtt_connected` is false.
- [ ] Final session count is zero.
- [ ] Final session list is empty.
- [ ] Offer forwards are `Stopped`.
- [ ] Final status write failures are visible in logs.

### Deployment behavior

- [ ] `p2p-offer.service` exists.
- [ ] `p2p-answer.service` exists.
- [ ] Units run foreground binaries.
- [ ] Units use SIGTERM.
- [ ] Units use `Restart=on-failure`.
- [ ] Units have a finite `TimeoutStopSec`.
- [ ] Units run as an unprivileged service account by default.
- [ ] Manual execution requires no `systemd`.
- [ ] Docker execution requires no `systemd`.

### Compatibility

- [ ] Existing public daemon APIs still compile.
- [ ] Existing integration tests using transport-injected APIs still compile.
- [ ] Android crate still compiles.
- [ ] Existing Docker E2E foreground `exec` pattern still works.

---

## 18. Recommended implementation sequence

Implement in this order:

```text
1. Generic ShutdownToken
2. Additive shutdown-aware daemon APIs
3. Answer session token propagation
4. Answer drain state machine
5. Offer accept-task ownership
6. Offer session cancellation
7. Offer reconnect cancellation race
8. Final Closed status helpers
9. Process SIGINT/SIGTERM adapters
10. Wire binaries
11. Lifecycle unit/integration tests
12. systemd units
13. deployment documentation
14. Android graceful-stop migration (P1)
15. packaging/install polish (P1/P2)
```

This order keeps the core testable without sending real OS signals and avoids tying correctness to `systemd`.

---

## 19. Future extensions explicitly deferred

The following may be useful later, but should not be mixed into the first implementation:

- `sd_notify` readiness;
- `WatchdogSec=` integration;
- socket activation;
- templated `p2p-offer@.service` instances;
- dynamic credentials;
- Debian/RPM packaging;
- automatic service user creation;
- live config reload on SIGHUP;
- zero-downtime listener handoff;
- remote close notification during global shutdown; and
- configurable application-level shutdown deadlines.

The first goal is simpler and more important: one correct foreground daemon lifecycle that every supervisor can use.
