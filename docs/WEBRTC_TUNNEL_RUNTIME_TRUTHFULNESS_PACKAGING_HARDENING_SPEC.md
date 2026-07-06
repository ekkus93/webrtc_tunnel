# WebRTC Tunnel Runtime Truthfulness, Task Supervision, Packaging, and Diagnostics Hardening Spec

## 0. Authority, baseline, and scope

This spec defines the next hardening pass for the WebRTC tunnel repository after review of:

```text
webrtc_tunnel-master_2607052048.zip
```

It assumes the cross-platform service lifecycle architecture from the existing lifecycle work is accepted and should be preserved. It does **not** ask Claude Code to redo the original service feature from scratch.

The prior implementation plan was:

```text
WEBRTC_TUNNEL_SERVICE_LIFECYCLE_TODO(4).md
```

This document supersedes conflicting implementation details from older lifecycle/spec/TODO files **only where this spec explicitly addresses a defect or changes a policy**.

The goal of this pass is to make the running system tell the truth about:

- whether it is accepting work;
- whether its worker tasks are alive;
- whether shutdown was graceful;
- whether terminal status was persisted;
- whether a package can actually start the installed services;
- whether a required test actually ran;
- whether diagnostics were collected and redacted correctly.

The central rule is:

> An error, panic, forced abort, missing prerequisite, corrupt status, failed diagnostic collection, or unavailable worker must never be converted into a normal-looking state, an empty default, a green skip, or a false readiness signal.

---

## 1. Executive summary of the problems being fixed

The reviewed snapshot has a sound overall architecture, but the following correctness gaps remain.

### 1.1 Runtime lifecycle defects

1. The offer daemon can race shutdown against `accepted_clients.recv() == None` and return early through `?`, bypassing listener joins and the final `Closed` status.
2. The offer daemon writes `WaitingForLocalClient` before checking whether shutdown has already been requested.
3. The offer daemon writes a steady state before listeners have successfully bound.
4. Offer listener worker tasks are only observed during final shutdown; a task can die during normal operation while status still says `Listening`.
5. Answer session registry cleanup depends on receiving an `Ended` event from the session task; a panicking task can therefore leave a permanent registry entry and make drain shutdown hang.
6. Answer drain mode can continue writing `Serving` and other ordinary runtime states after shutdown begins.
7. A ready event and a shutdown event can become ready at the same time; new work can still be admitted unless there is an explicit post-receive admission check.
8. Final `Closed` status writes remain best-effort and status files are rewritten non-atomically.

### 1.2 Test-trust defects

1. The reconnect-shutdown test uses a fixed sleep instead of observing that reconnect/backoff was actually entered.
2. The answer drain test does not force the critical in-flight publish/oneshot case it is supposed to protect.
3. A unit test sends SIGTERM/SIGINT to the Cargo test process itself.
4. Required real-process signal coverage can self-skip when Docker or expected binaries are absent.

### 1.3 Packaging and service-installation defects

1. The Debian package installs binaries in `/usr/bin` but packages service files that execute `/usr/local/bin`.
2. Debian upgrade handling can stop active services and never restart them.
3. Ordinary Debian removal does not reliably run `systemctl daemon-reload` after unit files disappear.
4. macOS installers create `root:wheel 0750` config directories even though LaunchDaemons run as `_p2ptunnel:_p2ptunnel`, making the configuration directories inaccessible to the service.
5. macOS installation checks the user but not the group required by the plist.
6. `--enable` can bootstrap invalid/unreadable configuration into a launchd restart loop.

### 1.4 False readiness defect

The optional `sd_notify` implementation sends `READY=1` before the daemon future has been polled. The process can therefore be reported ready before:

- MQTT subscription;
- peer authorization validation inside the daemon;
- offer listener binding;
- establishment of the actual steady state.

This is false readiness.

**Decision for this hardening pass:** remove the premature `Type=notify` integration from the supported/shipped surface. Keep the correct `Type=simple` units. Real `sd_notify` readiness may return later only after the daemon core exposes a genuine, supervisor-neutral readiness event.

### 1.5 Android and diagnostics defects

1. A forced Android abort is later reported as ordinary `Stopped`, with `last_error` erased.
2. Mutex poisoning is converted into silent no-ops, empty logs, or `None`.
3. Android tracing installation failure is deliberately ignored.
4. Diagnostics collection converts failures into `{}` or an empty string.
5. Forward-status/config mismatches become host `""` and port `0`.
6. The diagnostics redactor intentionally leaves common secret syntaxes such as `password: secret` and `kex secret = ...` unredacted.
7. MQTT URL redaction changes `mqtt://` into `mqtts://`, corrupting diagnostic meaning.

### 1.6 CLI and CI truthfulness defects

1. `p2pctl` still panics when `HOME` is unavailable and no explicit config path is provided.
2. `p2pctl status` invents plausible defaults for missing schema fields.
3. `p2pctl check-config` does not validate configured peers against `authorized_keys`, although the daemon does.
4. CI grants `contents: write` to every build/test job instead of only the release job.

---

## 2. Accepted architecture that must not be rewritten

The following architecture is correct and must remain intact.

### 2.1 One foreground process model

The binaries remain ordinary foreground processes:

```text
manual shell      -> SIGINT / Ctrl-C
systemd           -> SIGTERM
launchd           -> SIGTERM
Docker/Podman     -> SIGTERM
Android           -> ShutdownToken
integration tests -> ShutdownToken or child-process signal
```

Do not add:

- `--daemon`;
- forking;
- PID files;
- a systemd library dependency in the generic daemon state machine;
- launchd-specific logic in the daemon core;
- systemd inside a container.

### 2.2 Generic cooperative shutdown

`ShutdownToken` remains the shared cancellation primitive.

Keep:

- request-before-wait semantics;
- idempotent shutdown requests;
- clone observation;
- no OS-specific logic in the token.

### 2.3 Recoverable session failure versus daemon failure

The process should continue after ordinary session-scoped failures when the daemon infrastructure remains healthy.

Examples of session-scoped failure:

- remote session close;
- ICE failure for one session;
- data-plane probe failure for one session;
- target connection failure for one stream/session.

Examples of daemon-fatal infrastructure failure:

- an offer listener worker panics or exits unexpectedly while not shutting down;
- an answer session task panics and its lifecycle can no longer be trusted;
- the answer event channel closes unexpectedly;
- final required terminal status persistence fails;
- all configured offer listeners are unavailable;
- required startup validation fails.

### 2.4 Partial listener bind policy remains allowed

It is acceptable for one configured offer forward to fail to bind while another succeeds.

Required behavior:

```text
forward A -> Listening
forward B -> Error(last_error = "address already in use")
daemon    -> continues
```

This policy is allowed because the failure is visible in logs/status.

What is **not** allowed is a listener task dying after successful startup while status silently remains `Listening`.

---

## 3. Non-goals

This pass does not:

- change the signaling wire format;
- change encryption, signatures, identity files, or authorized-key format;
- add TURN;
- replace the WebRTC library;
- redesign multiplexed forwarding;
- add a service-only daemon mode;
- add systemd to Docker;
- make answer mode newly supported on Android;
- implement signed/notarized macOS distribution;
- implement systemd watchdog heartbeats;
- implement multi-instance templates beyond correcting regressions in existing files;
- rewrite all best-effort networking cleanup into a new shutdown framework.

---

## 4. Non-negotiable implementation rules

1. **No post-start early return may bypass daemon finalization.** Once offer listener workers exist, every exit path must stop workers, observe worker results, attempt terminal status, and then return the correct primary error.
2. **No normal steady-state status after shutdown starts.** `Serving`, `WaitingForLocalClient`, session state, MQTT recovery state, and other normal runtime status writes must be suppressed once the daemon is draining.
3. **No new work after shutdown starts.** Check shutdown both before waiting and after a ready work item is received.
4. **No task failure may remain invisible until process shutdown.** Runtime worker completion must be observed while the daemon is running.
5. **No normal-path `JoinHandle::abort()` to acknowledge successful answer-session completion.** A task that completed must be joined/observed, not aborted after it says it finished.
6. **No hidden daemon-core timeout.** Android may retain an explicit UI/FFI grace period, but the generic daemon core must not silently add a forced-abort deadline.
7. **No false readiness.** Do not send `READY=1` before the runtime is genuinely ready.
8. **No required test that silently turns into a skip.** Required prerequisites belong in CI setup; absence in a required job is failure.
9. **No signals sent to the test runner itself.** Signal a child process.
10. **No package/service executable-path mismatch.** Every absolute `ExecStart`/`ExecStartPre` path in a packaged unit must exist in the staged package.
11. **No service install that root can read but the configured service user cannot.** Validate permissions as the actual service account.
12. **No forced abort reported as clean stop.**
13. **No mutex poison mapped to empty/default/no-op.**
14. **No redaction test that blesses a known secret leak as expected behavior.**
15. **No malformed status mapped to plausible defaults.**
16. **No silent best-effort failure.** An operation may remain nonfatal, but its failure must be logged with relevant context.
17. **No global CI write token for ordinary build/test jobs.**

---

## 5. Runtime phase model

Add an explicit internal daemon phase to prevent normal status helpers from lying during startup or shutdown.

Recommended shape:

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

Required transitions:

### Answer

```text
Starting
  -> MQTT subscription complete
  -> runtime/status context initialized
  -> Running
  -> initial Serving status

Running
  -> shutdown/fatal worker failure
  -> Draining
  -> no new signaling work
  -> process publish/cleanup/completion events only
  -> session registry reaches zero
  -> Closed
  -> required terminal status write
```

### Offer

```text
Starting
  -> MQTT subscription complete
  -> required peer authorization validated
  -> listeners bound (at least one succeeds)
  -> accept workers started
  -> Running
  -> first WaitingForLocalClient status

Running
  -> shutdown/fatal worker failure
  -> Draining
  -> active session cleanup
  -> listener workers stop
  -> worker results observed
  -> Closed
  -> required terminal status write
```

### Status policy

Normal status helpers must not emit runtime states unless phase is `Running`.

Terminal helpers bypass that suppression and emit only `Closed`.

This central rule is preferred over sprinkling one-off `if shutting_down` checks throughout every publish/status call.

---

## 6. Offer daemon run/finalize boundary

The offer daemon currently has post-listener-start `?` paths that can bypass cleanup. Refactor it into two explicit phases:

```text
startup (may return directly; no workers exist yet)
  -> create listener runtime
  -> run phase returns Result
  -> unconditional finalization
  -> return primary error or cleanup error
```

Recommended conceptual structure:

```rust
let run_result: Result<(), DaemonError> = async {
    loop {
        // admission gate
        // select: shutdown, accepted client, idle signal, worker exit
    }
}
.await;

// All post-start exits converge here.
ctx.runtime.phase = DaemonRuntimePhase::Draining;
shutdown.request_shutdown();

let worker_result = stop_and_join_offer_accept_runtime(...).await;
ctx.runtime.phase = DaemonRuntimePhase::Closed;
let closed_status_result = write_offer_closed_status_required(&mut ctx).await;

return merge_primary_and_cleanup_results(
    run_result,
    worker_result,
    closed_status_result,
);
```

Policy:

- Preserve the original primary runtime error.
- Log any additional cleanup failures at `error` severity.
- If the run phase succeeded but cleanup or terminal status failed, return that cleanup/status error.
- Never use `?` inside the post-start run phase in a way that skips the finalizer.

### Required shutdown/channel-close handling

`accepted_clients.recv() == None` has two meanings:

```text
shutdown already requested -> expected stop; break cleanly
shutdown not requested      -> daemon infrastructure failure
```

It must not always become `DaemonError::Logging("offer accept loop stopped")` through `?`.

### Required post-receive admission check

Even when shutdown and a client are ready simultaneously:

```rust
if shutdown.is_shutdown_requested() {
    drop(client);
    break;
}
```

No new offer session may start after shutdown is requested.

The same principle applies to idle signaling payloads: after a payload wakes the select, check whether runtime phase is still `Running` before processing it.

---

## 7. Offer listener worker supervision

### 7.1 Problem

Keeping `Vec<JoinHandle<()>>` until final shutdown is not enough. A worker can panic while the daemon is running.

### 7.2 Required policy

After successful bind/start, an offer accept worker that exits unexpectedly is daemon-fatal.

Rationale:

- status would otherwise lie;
- some configured entry points would silently stop accepting clients;
- maintaining partially degraded runtime state after an unexpected task death is more complex and should not be invented as a fallback.

Partial **bind** failure remains soft. Post-start **worker death** is fatal.

### 7.3 Required supervision architecture

The daemon must receive worker-exit events while running, including panic/join failure.

A recommended architecture is:

```text
accept worker JoinHandle
        |
        v
monitor future/task awaits JoinHandle
        |
        +--> OfferAcceptTaskExit { forward_id, outcome }
                          |
                          v
              daemon/session select loop
```

The exit event must distinguish:

- expected shutdown exit;
- accepted-client receiver closed;
- worker join failure/panic.

The event receiver must be available both:

- in the outer waiting loop;
- while `run_offer_session` is active.

An active session must not prevent prompt detection of listener-worker failure.

### 7.4 Fatal worker failure behavior

```text
unexpected worker exit
  -> record primary daemon error
  -> request shared shutdown
  -> active offer session observes shutdown and cleans up
  -> remaining listener workers stop
  -> all monitor/worker results observed
  -> terminal Closed attempted
  -> daemon returns nonzero
```

---

## 8. Answer session task supervision

### 8.1 Problem

The answer registry currently shrinks only when a task successfully sends `AnswerSessionEvent::Ended`.

A panic before that send can leave:

```text
session task dead
registry entry alive
shutdown drain waiting for empty registry
```

forever.

### 8.2 Required design

Task completion must be observed independently of a message sent by the task itself.

Recommended refactor:

1. `run_answer_session_task(...)` performs inner work and cleanup, then **returns** its `Result<(), DaemonError>`.
2. A completion future awaits the spawned task `JoinHandle` and retains session metadata.
3. The daemon selects over both:
   - `event_rx` for publish/status/replacement events;
   - task-completion futures for normal completion, error, panic, or cancellation.
4. Registry removal is driven by observed task completion, not by a self-reported `Ended` event.

A good implementation can use `futures_util::stream::FuturesUnordered`.

Conceptual result type:

```rust
pub(crate) struct AnswerTaskCompletion {
    pub(crate) initial_session_id: SessionId,
    pub(crate) generation: SessionGeneration,
    pub(crate) remote_peer_id: PeerId,
    pub(crate) outcome: Result<Result<(), DaemonError>, String>,
}
```

Where:

```text
Ok(Ok(()))       -> session completed normally
Ok(Err(error))    -> session failed normally; recover per existing policy
Err(join_reason)  -> task panic/cancel/join failure; daemon-fatal
```

Because same-peer pending replacement can change the session ID, registry cleanup for a join failure must be able to locate the current entry by stable identity:

```text
generation + remote_peer_id
```

Do not assume the original session ID remains the map key.

### 8.3 Remove normal completion abort

The current pattern:

```rust
handle.task.abort();
```

after receiving normal `Ended` must be removed.

A completed task should be observed as completed, not aborted after it reports completion.

### 8.4 Panic policy

A session task panic is daemon-fatal because internal task invariants may be compromised.

Required behavior:

```text
panic observed
  -> log error with generation/peer/session identity
  -> remove stale registry entry
  -> request daemon shutdown
  -> drain other sessions cooperatively
  -> terminal Closed attempt
  -> return nonzero
```

Do not hide the panic as an ordinary session error and continue serving.

---

## 9. Answer drain truthfulness and work admission

### 9.1 Keep event servicing during drain

The existing high-level rule remains correct:

```text
shutdown
  -> stop new broker work
  -> keep session event/completion servicing alive
  -> allow in-flight publish requests to complete
  -> sessions unwind
```

### 9.2 Suppress normal status during drain

During `Draining`:

- `AnswerSessionEvent::Status` may update the internal registry snapshot but must not write `Serving`.
- `Replaced` may update registry identity but must not write a normal status.
- publish/RawPublish handling must continue, but transport/status helpers must not emit ordinary session states.
- session completion must remove registry state but must not call normal `recover_daemon_after_session` steady-state logic.

### 9.3 Double admission gate

The answer payload branch must be protected both:

1. by the select guard (`phase == Running`);
2. by a check immediately after a payload is received.

This prevents a simultaneous ready payload from being admitted after shutdown.

---

## 10. Status persistence policy

### 10.1 Ordinary status is best-effort

It is acceptable for a nonterminal runtime status write to fail without terminating the daemon, provided:

- the failure is logged;
- the failure is not silently ignored.

### 10.2 Terminal status is required when status-file writing is enabled

Final `Closed` status must use a strict result-returning path.

Required policy:

```text
status-file writing disabled -> terminal writer returns Ok
status-file writing enabled  -> terminal write failure is returned
watch sink closed            -> still nonfatal/optional observer
```

Terminal helpers should return:

```rust
Result<(), DaemonError>
```

and daemon finalizers must propagate them according to primary-error precedence.

### 10.3 Atomic file replacement

Do not rewrite the status file directly with `tokio::fs::write(path, bytes)`.

Use same-directory temporary-file replacement:

```text
serialize complete JSON
  -> write sibling temporary file
  -> flush/close
  -> rename over final status file
```

On Linux/macOS, same-filesystem rename provides the required reader-visible atomic replacement.

Temporary-file cleanup failure after a failed write may be logged and remain secondary to the primary error.

### 10.4 Reader contract

Readers should see either:

- the previous complete JSON document; or
- the new complete JSON document.

They must not observe a deliberately truncated/partially rewritten target file.

---

## 11. `sd_notify` policy for this pass

### 11.1 Current implementation is not valid readiness

The current binary calls `notify_ready()` immediately after creating/pinning the daemon future. The future has not yet been polled.

Therefore `READY=1` can precede:

- daemon validation;
- MQTT subscription;
- listener binding.

### 11.2 Decision

Remove the premature `sd_notify` feature from the supported/shipped surface in this hardening pass.

Required removal includes:

- `notify_ready()` and `notify_stopping()` calls from both binaries;
- `p2p-offer-notify.service` and `p2p-answer-notify.service` from supported packaging/docs;
- documentation that suggests these units provide meaningful readiness;
- tests whose only purpose is to validate the premature implementation;
- optional dependency/feature if nothing else uses it.

The correct baseline remains:

```text
Type=simple
```

### 11.3 Future reintroduction

A future P2 feature may reintroduce `Type=notify` only with a supervisor-neutral daemon readiness event.

Required future readiness meanings:

#### Answer ready

```text
MQTT subscription complete
+ required config/authorization validation complete
+ answer runtime entered Serving
```

#### Offer ready

```text
MQTT subscription complete
+ required remote peer authorization complete
+ at least one listener successfully bound
+ accept workers started
+ offer runtime entered WaitingForLocalClient
```

The daemon core must emit a generic readiness event; only the binary adapter may translate it into `sd_notify`.

---

## 12. Signal test policy

### 12.1 Never signal the Cargo test process

Replace current in-process signal tests with child-process tests.

A good pattern is to re-exec the current test executable in a child-only mode:

```text
parent test
  -> spawn current_exe with CHILD_MODE=1
  -> child waits in wait_for_process_shutdown_signal()
  -> parent sends SIGTERM/SIGINT to child PID
  -> child records observed signal and exits 0
```

### 12.2 Required real-binary lifecycle coverage must not self-skip

A required CI job must provision its prerequisites first.

Preferred direction:

- build `p2p-offer`, `p2p-answer`, and `p2pctl` explicitly;
- provision a local test MQTT broker in the job;
- run the real-process signal suite;
- missing binaries/broker in that required job are failures.

Do not implement:

```rust
if !docker_available() {
    return;
}
```

inside a test that is supposed to prove required lifecycle behavior.

If platform coverage is not available, express that at the CI job/matrix level and document the gap. Do not produce green test results that silently mean “not tested.”

---

## 13. Deterministic lifecycle regression tests

### 13.1 Offer shutdown during reconnect

The test must observe a deterministic reconnect/backoff event before shutdown.

Add a test-only event hook, for example:

```rust
pub enum OfferSessionTestEvent {
    SessionStarted { session_id: SessionId },
    ReconnectStarted { session_id: SessionId },
    ReconnectBackoffStarted { session_id: SessionId, delay: Duration },
}
```

Test sequence:

```text
inject Disconnected
  -> await ReconnectBackoffStarted
  -> request shutdown
  -> assert exit before configured backoff
```

A fixed `sleep(300ms)` is not valid synchronization.

### 13.2 Answer drain with in-flight publish

The test must intentionally hold a session publish request in flight.

Required sequence:

```text
session emits Publish/RawPublish
  -> fake transport blocks completion
  -> test confirms publish is in flight
  -> request answer shutdown
  -> assert daemon has not deadlocked
  -> release transport barrier
  -> session completes
  -> final Closed
```

The timeout is only a watchdog, not synchronization.

### 13.3 Worker panic tests

Add deterministic tests proving:

- an offer accept worker panic becomes a visible daemon error and triggers cleanup;
- an answer session worker panic removes registry state, drains others, writes/attempts `Closed`, and returns nonzero.

Use explicit test hooks/fault injection, not timing or accidental panics.

---

## 14. Debian package correctness

### 14.1 Executable path consistency

The `.deb` installs binaries under:

```text
/usr/bin
```

Therefore packaged service units must use:

```text
/usr/bin/p2pctl
/usr/bin/p2p-offer
/usr/bin/p2p-answer
```

Do not ship `/usr/local/bin` service paths in the Debian package.

The source/manual-install units may continue to use `/usr/local/bin` if that remains the documented manual install location.

### 14.2 Package-tree assertion

Before `dpkg-deb --build`, parse every staged service unit and verify every absolute executable in:

```text
ExecStart=
ExecStartPre=
```

exists and is executable inside the staged tree.

A package build with broken service executable paths must fail.

### 14.3 Upgrade semantics

Do not stop active services in `prerm upgrade` and then forget to restart them.

Recommended hand-written policy:

```text
prerm remove/deconfigure -> stop active services
prerm upgrade            -> do not stop here
postinst configure       -> daemon-reload
                           -> try-restart only already-active services
```

`systemctl try-restart` must not start services that were inactive before installation/upgrade.

### 14.4 Remove semantics

After ordinary `remove`, run `systemctl daemon-reload` when systemd is active.

Keep the existing data policy:

- remove preserves config/state/logs;
- purge may remove package-owned config/state/logs as explicitly documented;
- identities are never silently replaced during install/upgrade.

---

## 15. macOS LaunchDaemon installation correctness

### 15.1 User and group must both exist

The plists require:

```text
UserName  = _p2ptunnel
GroupName = _p2ptunnel
```

Install scripts and package preinstall must validate both.

### 15.2 Directory ownership

New config directories:

```text
root:_p2ptunnel
0750
```

New log directory:

```text
_p2ptunnel:_p2ptunnel
0750
```

Existing directories must not be silently assumed safe.

The installer must verify that the service account can:

- traverse/read the role config directory;
- read `config.toml`, `identity`, and `authorized_keys` when present;
- write the configured state/log locations.

If existing ownership/permissions are wrong, fail with a concrete diagnostic rather than saying the directory is being “left untouched” and continuing.

### 15.3 Preflight before `--enable`

Before `launchctl bootstrap`, run configuration validation as the actual service user.

Conceptually:

```bash
sudo -u _p2ptunnel \
  /usr/local/bin/p2pctl check-config \
  --config "/Library/Application Support/P2PTunnel/offer/config.toml"
```

and answer equivalent.

Root-only validation is insufficient because root may read files the service cannot.

### 15.4 Package scripts

The macOS package preinstall/postinstall scripts must use the same user/group and permission model as the standalone installer. Do not maintain two contradictory installation policies.

---

## 16. Android stop outcome truthfulness

### 16.1 Required outcomes

Stopping must distinguish at least:

```text
Graceful
ForcedAbort { grace_period }
TaskJoinFailed { reason }
NotRunning
```

### 16.2 Public behavior

`AndroidTunnelController::stop()` should return a `Result` or explicit stop outcome internally so FFI can report failure.

A forced abort must not produce:

```text
state = Stopped
last_error = None
log = "runtime stopped"
```

Recommended behavior for forced abort:

```text
state = Error
active = false
last_error = "runtime required forced abort after ..."
config_path preserved for diagnostics
measured runtime metadata cleared
error/warn log retained
FFI stop call returns failure
```

### 16.3 Duplicate stop

Repeated stop remains safe.

If nothing is running, return success/`NotRunning` without corrupting an existing diagnostic from a previous forced abort unless the product deliberately clears it through a separate reset/start action.

---

## 17. Mutex poisoning and internal state access policy

A poisoned runtime mutex is an internal runtime error.

Forbidden conversions:

```text
poison -> return from stop silently
poison -> [] logs
poison -> None last_error
poison -> ignore bridge error
```

Required behavior:

- `stop()` returns an explicit error;
- `recent_logs()` returns a synthetic error event or a `Result` propagated through FFI;
- `last_error()` returns `Some("runtime mutex poisoned")` or a structured error;
- `record_bridge_error()` reports failure to record rather than silently doing nothing;
- daemon completion callback logs an explicit error if state update cannot acquire the mutex.

Do not recover poisoned inner state with `into_inner()` and continue as though invariants are intact.

---

## 18. Android tracing/log-buffer failure policy

### 18.1 Tracing installation

`install_tracing_once` must return a result describing whether installation succeeded.

If a global subscriber already exists:

- do not silently lose diagnostics;
- record an explicit error/warning into a path that does not depend on the failed subscriber;
- decide whether startup should fail.

For this pass, the recommended policy is:

```text
tracing bridge install failure -> startup failure on Android
```

because the Android UI relies on this bridge for native diagnostics.

If a softer policy is chosen, it must still produce a visible persistent diagnostic.

### 18.2 Log-buffer mutex poisoning

`LogBuffer::push` and `LogBuffer::recent` must not silently discard/empty on poison.

Prefer returning `Result` from the buffer API and propagate the error.

---

## 19. `p2pctl` correctness

### 19.1 Config path resolution

Use the same no-panic path resolution as the offer/answer binaries.

Missing `HOME` without `--config` must return a normal error.

### 19.2 Typed status parsing

Add `Deserialize` to the public status model and parse:

```rust
DaemonStatus
```

instead of `serde_json::Value` with `unwrap_or("unknown")`, `unwrap_or(false)`, and missing-array defaults.

Malformed/incompatible status must produce an explicit CLI error.

### 19.3 `check-config` parity with daemon startup

`check-config` must validate:

- config syntax and semantic validation;
- identity file load;
- identity peer ID match;
- authorized_keys load;
- every configured required peer exists in authorized_keys.

Do not add a dependency from `p2pctl` onto the full WebRTC daemon just to reuse this logic.

Preferred shared design:

- extract a pure/lightweight helper that enumerates required authorized peer IDs from `AppConfig`;
- daemon and CLI both use it;
- `p2pctl` performs authorized-key lookup itself.

---

## 20. Diagnostic redaction policy

### 20.1 Common separators and field-name variants

Secret-field redaction must cover common forms:

```text
password=secret
password: secret
password = secret
password : secret

token=secret
token: secret

api_key=secret
api-key: secret
api key: secret

kex_secret=secret
kex-secret: secret
kex secret = secret

signing_key=secret
signing-key: secret
signing key = secret
```

Tests that currently assert these are not redacted must be inverted into regression tests asserting removal.

### 20.2 Preserve diagnostic meaning

Credential redaction in MQTT URLs must preserve the original scheme:

```text
mqtt://  -> mqtt://***REDACTED***:***REDACTED***@
mqtts:// -> mqtts://***REDACTED***:***REDACTED***@
```

Do not rewrite insecure transport into secure transport in redacted diagnostics.

### 20.3 Redaction test philosophy

A known secret false negative is a failing security test, not a “documented quirk.”

Include table-driven tests with unique sentinel secrets and assert no sentinel survives.

---

## 21. Diagnostics collection failure policy

Do not convert collection errors into empty content.

Replace:

```kotlin
runCatching { ... }.getOrDefault("{}")
runCatching { ... }.getOrDefault("")
```

with explicit diagnostic sections.

Example output:

```text
status_json_error=<redacted error>
config_redacted_error=<redacted error>
```

A missing optional config file may be represented as:

```text
config_redacted=<not present>
```

only when absence is an expected state and not a read/permission failure.

---

## 22. Forward status/config mismatch policy

Do not map a missing configured-forward lookup to:

```text
host = ""
port = 0
```

A daemon-status forward ID that cannot be matched to captured configuration is an inconsistency.

Required behavior:

- surface an explicit error in Android runtime status/logs; or
- include an explicit `configuration_missing = true` style state.

Do not manufacture a plausible endpoint.

---

## 23. Best-effort operation logging

The following policy applies to production `let _ = future.await` patterns.

### 23.1 Nonfatal may remain nonfatal

Examples:

- close notification during teardown;
- peer close after primary session failure;
- optional error reply when the transport is already failing.

These may remain best-effort.

### 23.2 Failure must be visible

Use context-rich logs:

```rust
if let Err(error) = operation.await {
    tracing::warn!(
        reason = %error,
        session_id = %session_id,
        remote_peer_id = %remote_peer_id,
        "failed to publish best-effort close notification"
    );
}
```

Do not log expected `JoinError::cancelled()` from an intentional bridge-task abort as a warning. Distinguish intentional cancellation from panic/unexpected join failure.

---

## 24. CI permission policy

At workflow scope:

```yaml
permissions:
  contents: read
```

Only the release job receives:

```yaml
permissions:
  contents: write
```

Ordinary compilation, Cargo build scripts, Android dependencies, tests, and validation scripts do not need repository write permission.

---

## 25. Required test matrix

### 25.1 Rust unit/integration

Required:

- offer channel-close + shutdown race;
- no offer steady-state write after shutdown request;
- no offer steady-state write before listener runtime is ready;
- unexpected offer worker exit during idle -> daemon error + cleanup;
- unexpected offer worker exit during active session -> daemon error + session cleanup;
- answer task panic -> registry removal + daemon error + no drain hang;
- answer drain suppresses normal status writes;
- answer in-flight publish drain barrier test;
- terminal status write failure -> daemon returns error;
- atomic status reader never sees partial JSON under repeated writes;
- child-process SIGTERM adapter test;
- child-process SIGINT adapter test;
- deterministic reconnect-start/backoff test;
- typed `p2pctl status` malformed-schema rejection;
- `p2pctl check-config` missing authorized peer rejection.

### 25.2 Shell/package

Required:

- all shell scripts pass `bash -n` or `sh -n` as appropriate;
- Debian staged package unit executable paths exist;
- `dpkg-deb --build` succeeds;
- package install smoke test can start service far enough to run `ExecStartPre` from the packaged path;
- upgrade test verifies previously active services are restarted;
- remove test verifies daemon-reload occurs;
- systemd units validate;
- launchd plists validate.

### 25.3 Android/Kotlin

Required:

- graceful stop -> clean Stopped;
- forced abort -> Error/nonclean outcome preserved;
- task join failure -> visible error;
- mutex poison does not become empty/default/no-op;
- tracing install failure is visible;
- status/config diagnostics failures appear as explicit error sections;
- redactor covers colon, equals, spaces, hyphens/underscores;
- MQTT scheme is preserved;
- forward config mismatch is explicit.

### 25.4 CI

Required jobs must not self-skip their primary assertion because a prerequisite was never installed.

---

## 26. Recommended implementation order

Follow this dependency order:

```text
1. Add runtime phase model/status suppression.
2. Refactor offer run/finalize boundary.
3. Add offer worker supervision.
4. Refactor answer task completion supervision.
5. Make answer fatal paths drain/finalize.
6. Make terminal status strict and status file writes atomic.
7. Repair deterministic lifecycle tests.
8. Repair signal tests/CI prerequisites.
9. Remove false sd_notify readiness surface.
10. Repair Debian package path and package lifecycle.
11. Repair macOS account/permission/preflight logic.
12. Fix Android stop outcome and mutex error propagation.
13. Fix p2pctl strict parsing/preflight parity.
14. Fix redaction and diagnostics collection.
15. Audit/log remaining best-effort production failures.
16. Reduce CI permissions.
17. Run complete quality gates.
```

Do not combine all work into one giant commit.

Each P0 stage should compile and have its focused tests before the next stage.

---

## 27. Definition of done

This hardening pass is complete only when all of the following are true.

### Runtime

- Offer shutdown cannot bypass listener joins/final status through a ready channel-close branch.
- No new offer client or answer payload is admitted after shutdown request.
- No ordinary runtime status is written while `Draining`.
- Offer worker death is observed while running.
- Answer worker panic cannot strand a registry entry or hang shutdown.
- Normal answer task completion is not acknowledged with `abort()`.
- Every post-start daemon exit converges through finalization.

### Status

- Terminal `Closed` write failure is visible in the daemon result when status files are enabled.
- Status file replacement is atomic on Linux/macOS.
- `p2pctl status` rejects malformed schema.

### Services and packaging

- Baseline Linux service uses `Type=simple`.
- No shipped/documented unit falsely claims `Type=notify` readiness.
- Debian package service paths exist inside the package.
- Active Debian services survive upgrade by being restarted deliberately.
- macOS config directories are readable/traversable by `_p2ptunnel`.
- `--enable` validates config as `_p2ptunnel` before bootstrap.

### Android/diagnostics

- Forced abort is never reported as a clean stop.
- Mutex poison is never mapped to empty/default/no-op.
- Tracing installation failure is visible.
- Known common secret syntaxes are redacted.
- Diagnostics collection failures are explicit.

### Tests/CI

- No signal is sent to the Cargo test runner.
- Required signal coverage cannot silently self-skip.
- Reconnect and in-flight publish tests use explicit synchronization, not timing sleeps.
- Global CI token permission is read-only; only release publishing receives write permission.
- Formatting, clippy with denied warnings, workspace tests, Android tests, service validation, and package checks all pass.

