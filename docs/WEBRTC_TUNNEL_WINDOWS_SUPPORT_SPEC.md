# WebRTC Tunnel Windows Support Spec

## 1. Purpose

This spec defines how the WebRTC tunnel gains first-class Windows support so that:

1. `p2p-offer` and `p2p-answer` run manually in a terminal on Windows;
2. Windows peers interoperate with existing Linux, macOS, and Android peers with no
   protocol, crypto, or wire-format divergence;
3. the answer role runs as a native Windows service under the Service Control Manager;
4. the offer role is equally supported, so a Windows host can be either end of a tunnel;
5. private key material at rest is protected on Windows to a standard equivalent to the
   `0600` guarantee enforced on unix;
6. shutdown is clean and truthful when the SCM stops the service; and
7. the shared Rust core stays usable, unmodified, by the Linux, macOS, and Android builds.

The immediate deployment target is a GPU workstation running the **answer** role as a
service, forwarding RDP (3389) plus local model-server ports (ollama, llama-server,
ComfyUI) to a remote offer peer. The offer role on Windows is an explicit requirement but
a later milestone.

The main architectural rule is:

> The wire protocol, cryptographic identity format, and daemon state machines must remain
> byte-identical on every platform. Exactly two categories of behavior may differ per OS:
> **how a secret is protected at rest on local disk**, and **how the process is hosted and
> asked to stop**. Nothing that crosses the network may differ.

No fork of the codebase, no Windows-specific protocol behavior, and no per-OS branch
inside the daemon state machines.

---

## 2. Executive design decisions

### 2.1 One codebase, not a Windows fork

The entire unix dependency in production code is five files:

```text
crates/p2p-core/src/config/paths.rs      HOME expansion, world-writable checks
crates/p2p-crypto/src/identity.rs        0600 private-key permission check
crates/p2p-daemon/src/process_signal.rs  SIGINT/SIGTERM handling
bins/p2pctl/src/commands.rs              HOME, set_permissions on keygen
bins/p2p-offer|p2p-answer/src/main.rs    HOME for config resolution
```

Everything else — `p2p-signaling`, `p2p-webrtc`, `p2p-tunnel`, the protocol framing, the
crypto, the multiplexer, the config *schema* and validation rules — is portable Rust that
already compiles and passes on Windows.

A separate Windows implementation would fork a codebase that is ~95% identical to
accommodate five files, and would make protocol drift between platforms a question of
*when* rather than *if*. That is rejected.

### 2.2 `p2p-mobile` is the precedent

Android is a stranger target than Windows: different libc, no shared filesystem
conventions, a JNI boundary, its own ICE constraints. It did **not** get a fork. It got a
thin wrapper crate over the shared core plus a small number of
`#[cfg(target_os = "android")]` branches. That is what keeps Android interoperating with
Linux and macOS by construction — it is literally the same code.

Windows follows the same shape and needs less than Android did.

### 2.3 The `p2p-platform` crate is the only place OS differences live

OS-specific code must not accumulate as scattered `#[cfg(unix)]` / `#[cfg(not(unix))]`
pairs inside crates whose job is portability. `p2p-core` in particular must return to being
pure schema and validation logic with no OS calls.

All OS divergence lives behind one small crate with a per-OS implementation module.
Android falls under the unix implementation unchanged.

### 2.4 ed25519 identity and file permissions are orthogonal layers

These were conflated during early design discussion and must not be again:

- **ed25519 keys are the wire layer.** Identity, signing, `authorized_keys`, message
  verification. Byte-identical on every platform. This is what makes peers interoperate.
- **File permissions are the local at-rest layer.** "Can another user *on this machine*
  read my private key file?" This never crosses the wire. A peer has no knowledge of, and
  no reason to care about, how its counterpart stores its key on disk.

Consequently: Linux checks POSIX mode bits, Windows checks its own equivalent, Android
relies on app-private storage. **No platform needs to understand another platform's
permission model.** Windows adopting DACL-based protection imposes nothing on Linux, macOS,
or Android.

### 2.5 File-permission enforcement must be honest about what it cannot do

`AppConfig::validate` refuses to load a config that disables
`security.refuse_world_readable_identity` or `security.refuse_world_writable_paths`
(`crates/p2p-core/src/config/validate.rs`). The design intent is unambiguous: these
protections are mandatory.

Before this work, both checks were `#[cfg(not(unix))]` stubs returning `Ok(())` on Windows:
ten `validate_non_world_writable` call sites in `validate.rs` plus the
`validate_private_file_permissions` call in `identity.rs`. A security control that the
config forbids disabling was silently disabled by the platform.

The rule going forward:

> A platform that cannot enforce a promised security check must report that it cannot,
> not return success.

`p2p_platform::ENFORCES_FILE_PERMISSIONS` exists for exactly this. Callers that surface
these guarantees to a user must consult it rather than implying enforcement that is not
happening.

### 2.6 The Windows service runs under a virtual account

The service runs as `NT SERVICE\p2ptunnel`, not `LocalSystem`.

Rationale: the answer daemon makes outbound connections to the broker and inbound-to-
localhost connections to forward targets. Neither requires machine-level authority.
Running a network-facing service that forwards RDP as `LocalSystem` grants an attacker who
compromises it full machine control for no operational benefit. A virtual account needs no
password management, is created automatically at install time, and gets a distinct SID that
the config directory ACL can be scoped to.

### 2.7 At-rest protection on Windows comes from install-time ACLs, not runtime checks

Where the identity file lives determines the actual risk:

- Under `%USERPROFILE%`, the default ACL already restricts access to that user plus SYSTEM
  and Administrators — effectively equivalent to `0600`.
- Under `%ProgramData%`, the default inherited ACL commonly grants `BUILTIN\Users` read
  access. A private key written there with inherited permissions is readable by **every
  local user**.

Because a service's config belongs under `%ProgramData%`, the second case is the real
exposure. The fix is to apply a restrictive DACL to the config directory **once, at service
install time**, granting only the service's virtual account and Administrators.

Runtime DACL *validation* would be defense in depth on top of that. It is deliberately not
required for correctness, because it needs `GetNamedSecurityInfo` and ACL walking through
raw Win32 FFI, and the workspace sets `unsafe_code = "forbid"` with `p2p-mobile`'s JNI
boundary as its single documented exception. Adding a second exception is a policy decision
to be made explicitly, not smuggled in as an implementation detail.

### 2.8 Shutdown translates SCM control codes into the existing shutdown token

`crates/p2p-daemon/src/process_signal.rs` already has the correct abstraction: daemon state
machines never observe a signal number, only a `ShutdownToken` derived from one. Windows
adds a new producer of that token, driven by `SERVICE_CONTROL_STOP`, and changes nothing
downstream.

The existing `#[cfg(not(unix))]` fallback calls `tokio::signal::ctrl_c()`. That is correct
for a foreground console process and **insufficient for a service** — the SCM does not send
console signals. Both paths must exist.

---

## 3. Current repository state

### 3.1 The workspace already builds and passes on Windows

`cargo build --workspace`, `cargo clippy --workspace --all-targets`, and
`cargo fmt --all --check` are clean on `x86_64-pc-windows-msvc`.

### 3.2 The WebRTC data plane is proven working on Windows

`crates/p2p-daemon/tests/two_node_daemon` drives real `WebRtcPeer::new` →
`RTCPeerConnection` (see `crates/p2p-daemon/src/answer/session.rs`), negotiates real
ICE/DTLS/SCTP over loopback, and round-trips real bytes
`client → tunnel → echo target → back`, including concurrent peers and fault isolation.
41 tests pass in roughly seven seconds. Only the *signaling* transport is in-memory there;
the data plane is genuine.

This retires the largest open risk in the port.

### 3.3 What is not yet proven on Windows

- Real MQTT signaling over `mqtts://` (rumqttc + rustls). Cross-platform crates, low risk,
  but untested — the Docker broker E2E is gated off.
- Real-network (non-loopback) ICE, NAT traversal, and STUN.
- Anything service-related: SCM hosting, shutdown via `SERVICE_CONTROL_STOP`.

### 3.4 Two integration tests are unix-gated

`real_broker_tunnel.rs` and `process_signal_shutdown.rs` carry `#![cfg(unix)]` because they
used `PermissionsExt` and `ExitStatusExt` unconditionally, which prevented the `p2p-daemon`
test binary from compiling at all on Windows.

`real_broker_tunnel.rs` is legitimately unix-shaped: it `chmod`s certs for a Linux mosquitto
container's bind mount. `process_signal_shutdown.rs` is different — it points at a real gap,
because Windows needs an equivalent driven by SCM control codes. That gap is tracked, not
accepted.

### 3.5 Android is an integration constraint, not a target of this work

Android builds from a Windows host already work end to end (`./gradlew check` passes,
including the Rust JNI cross-compile via `cargo-ndk`). Eight Kotlin unit tests fail on
Windows for test-harness portability reasons — `File.setReadable`/`setWritable` are no-ops
on Windows, one test compares a raw path against a TOML-escaped one, one shells out to bash
with an unquoted Windows path. These are host-portability defects in the test harness, not
Android app defects, and must not be fixed by changing app behavior.

---

## 4. Goals

### 4.1 P0 goals

- All OS-specific production code lives behind `p2p-platform`.
- `cargo fmt`, `cargo clippy`, and `cargo test --workspace` pass on Windows in CI as a
  required gate.
- Windows config and state directory layout is defined and implemented.
- `p2p-answer` runs as a Windows service under a virtual account and stops cleanly on
  `SERVICE_CONTROL_STOP`.
- The service installer applies a restrictive DACL to the config directory.
- Startup honestly reports when a promised permission check is not enforced.

### 4.2 P1 goals

- Real `mqtts://` signaling proven on Windows.
- Windows shutdown lifecycle test equivalent to `process_signal_shutdown.rs`.
- Android test-harness portability fixed so `./gradlew check` is green on a Windows host.
- Offer role validated on Windows against a real remote answer peer.

### 4.3 P2 goals

- MSI or equivalent packaging.
- Windows added to the release-artifacts matrix.
- A Windows-native broker E2E replacing the Docker-based one.

---

## 5. Non-goals

- No Windows fork of the codebase.
- No per-OS branching inside daemon state machines, protocol code, or crypto.
- No change to signaling wire format, identity format, forward semantics, or WebRTC
  architecture.
- No weakening of the fail-closed security knobs in `AppConfig::validate` to accommodate
  Windows.
- No `unsafe` in `p2p-platform` unless explicitly approved as a documented workspace policy
  exception, on the model of `p2p-mobile`.
- Windows containers and Windows-on-ARM are out of scope.

---

## 6. Required architecture

### 6.1 Layering

```text
        p2p-offer / p2p-answer / p2pctl / p2p-service-windows
                              │
                      p2p-daemon (state machines)
                              │
       ┌──────────────┬───────┴────────┬───────────────┐
       │              │                │               │
  p2p-signaling  p2p-webrtc      p2p-tunnel      p2p-crypto
       │              │                │               │
       └──────────────┴───────┬────────┴───────────────┘
                              │
                          p2p-core            ← pure schema + validation
                              │
                        p2p-platform          ← the ONLY OS seam
                              │
              ┌───────────────┴───────────────┐
              │                               │
         platform/unix.rs              platform/windows.rs
      (Linux, macOS, Android)
```

Nothing above `p2p-platform` may contain `#[cfg(unix)]`, `#[cfg(windows)]`, or
`#[cfg(target_os = ...)]` for permission or home-directory concerns. Existing
Android-specific ICE branches in `p2p-webrtc` are a separate, pre-existing concern and are
not in scope here.

### 6.2 The platform seam interface

This is the **target** interface. Implementation status is marked, because two of these do
not exist yet and tasks that call them are blocked on the tasks that add them.

```rust
pub const ENFORCES_FILE_PERMISSIONS: bool;                                      // implemented

pub fn home_dir() -> Option<PathBuf>;                                           // implemented
pub fn ensure_private_file_permissions(path: &Path) -> Result<(), PlatformError>; // implemented
pub fn ensure_not_writable_by_others(path: &Path) -> Result<(), PlatformError>;   // implemented

pub fn default_config_dir() -> Result<PathBuf, PlatformError>;                  // implemented
pub fn service_config_dir(role: &str) -> Result<PathBuf, PlatformError>;        // implemented
pub fn create_private_file(path: &Path, contents: &[u8]) -> Result<(), PlatformError>; // TODO P0-004
```

`default_config_dir()` resolves the **interactive user** layout only. It never returns a
service path, and it never tries to detect whether it is running as a service. The two
layouts are selected by the caller, not sniffed:

* An explicit `--config` wins over everything.
* A service supervisor always passes `--config` explicitly. This is not a Windows-specific
  concession; it is what the existing packaging already does on every platform
  (`packaging/systemd/p2p-answer.service` passes `/etc/p2ptunnel/answer/config.toml`,
  `packaging/launchd/com.p2ptunnel.answer.plist` passes
  `/Library/Application Support/P2PTunnel/answer/config.toml`).
* `service_config_dir(role)` exists so the supervisor and the installer agree on that path
  without hardcoding it in two places. It is a path constructor, not a detector.

Deciding the layout by inspecting the process token or the `USERNAME`/`USERDOMAIN`
environment would be a guess, and a wrong guess means a service reading a config out of a
user-writable directory. Requiring the caller to be explicit removes that failure mode
entirely.

`home_dir()` reads `HOME` on unix. On Windows it prefers `USERPROFILE` and only falls back
to `HOME`, because POSIX-emulation shells (Git Bash, MSYS, Cygwin) set `HOME` to a
unix-shaped path such as `/c/Users/name`, which `PathBuf` would treat as a drive-relative
path and silently resolve incorrectly.

### 6.3 Windows filesystem layout

```text
Service (virtual account NT SERVICE\p2ptunnel):
  config     %ProgramData%\p2ptunnel\<role>\config.toml
  identity   %ProgramData%\p2ptunnel\<role>\identity
  state      %ProgramData%\p2ptunnel\<role>\state\
  logs       %ProgramData%\p2ptunnel\<role>\state\log\

Interactive user:
  config     %USERPROFILE%\.config\p2ptunnel\config.toml
  identity   %USERPROFILE%\.config\p2ptunnel\identity
```

Which of the two applies is chosen by the caller, never guessed — see §6.2.
`default_config_dir()` returns the interactive path; the service is started with an
explicit `--config` under `%ProgramData%`.

The `%ProgramData%` tree must have a DACL granting only `NT SERVICE\p2ptunnel` and
`BUILTIN\Administrators`, with inheritance enabled so the identity file is protected on
creation rather than by a later fixup.

#### Logging in service mode

A service runs in Session 0 with no console attached, so `stdout` goes nowhere a user can
read. A service config must therefore log to a file:

```toml
[logging]
file_logging = true
stdout_logging = false
log_file = 'C:\ProgramData\p2ptunnel\answer\state\log\p2ptunnel.log'
```

Leaving `stdout_logging = true` is not an error and does no harm, but on its own it produces
a service that runs correctly while appearing to emit nothing. The interactive-user layout
is unaffected and may keep stdout logging.

### 6.4 Service hosting topology

#### Why a separate wrapper binary rather than making `p2p-answer` SCM-aware

`WEBRTC_TUNNEL_SERVICE_LIFECYCLE_SPEC.md` sets a rule this work must not break:

> `p2p-offer` and `p2p-answer` must remain ordinary foreground applications. `systemd`,
> `launchd`, Docker, a shell, Android, or a test harness may supervise them, but the daemon
> core must not have a special supervisor-specific mode.

**"Foreground" here is a statement about process lifecycle, not about anything visible on a
screen.** It means the process does not daemonize itself: no `fork()` into the background,
no detaching from its parent, no PID file, no `--daemon` flag. It starts, runs as a direct
child of whatever launched it, and exits when asked. The pattern being banned is the classic
Unix daemon that forks and orphans itself, which leaves a service manager with no handle on
the process it is supposed to supervise. `systemd` uses `Type=simple` for exactly this
reason.

Nothing about this rule implies a window, a console, or a desktop presence. A Windows
service installed per this spec runs in **Session 0**, which has been isolated from every
interactive user session since Windows Vista. It has no desktop. There is no window to
show, minimize, or close; it runs with nobody logged in and survives logoff. It appears in
`services.msc` and Task Manager's Services tab, and is controlled with `sc.exe start` /
`sc.exe stop`. The rule and a headless service are not in tension — the rule is what keeps
the process cleanly supervisable in the first place.

On unix that rule is free, because `systemd` and `launchd` supervise an ordinary foreground
process from the outside. **Windows is genuinely different.** A service process must call
`StartServiceCtrlDispatcher` on its main thread shortly after start to connect to the SCM
and dispatch into `ServiceMain`. An arbitrary console executable cannot be run as a native
Windows service without either doing that itself or being run under a third-party shim.

So the choice is: give `p2p-answer` a supervisor-specific mode (violating the rule above),
or put the SCM-specific mode in a separate binary. This spec chooses the second.
`p2p-offer` and `p2p-answer` stay exactly what they are today on every platform.

#### One binary, two registered services

`p2p-service-windows` takes `--role offer|answer` and is registered **twice**, as two
independent services, mirroring the unix arrangement of separate `p2p-offer.service` and
`p2p-answer.service` units:

```text
  Service "p2ptunnel-answer"              Service "p2ptunnel-offer"
  binPath: p2p-service-windows.exe        binPath: p2p-service-windows.exe
           --role answer                           --role offer
  config:  %ProgramData%\p2ptunnel\       config:  %ProgramData%\p2ptunnel\
           answer\config.toml                      offer\config.toml
```

Each service has its own name, its own config directory (the `<role>` element already
present in §6.3), its own state and log directories, and its own lifecycle. Neither role is
privileged over the other; the initial deployment happens to install only `answer`, but
`offer` is registered the same way when needed.

```text
              Service Control Manager
                       │
             SERVICE_CONTROL_STOP
                       │
                       ▼
     p2p-service-windows --role <offer|answer>   ← SCM plumbing only
                       │
              ShutdownToken                      ← existing generic primitive
                       │
                       ▼
        run_answer_daemon / run_offer_daemon
                (unchanged, shared)
```

`p2p-service-windows` contains SCM registration, the control handler, and status reporting.
It must contain no tunnel logic. The daemon entry points it calls are the same ones the
foreground binaries and the Android runtime call.

---

## 7. Security model

| Concern | Unix | Windows |
|---|---|---|
| Identity file protection | `0600` mode bits, verified at load | Restrictive DACL applied at install; inherited on creation |
| Path not writable by others | mode bit `0o002` check | Directory DACL scoped to service account |
| Enforcement reported | `ENFORCES_FILE_PERMISSIONS = true` | `false` until runtime DACL validation is approved |
| Wire identity | ed25519 — **identical** | ed25519 — **identical** |

The fail-closed knobs in `AppConfig::validate` remain fail-closed on every platform. They
are not relaxed for Windows. Where the check cannot be enforced, the daemon must say so at
startup rather than implying it passed.

---

## 8. Testing strategy

- Unit tests for `p2p-platform` run on both implementations; unix-only assertions are
  gated within the unix module, not by skipping the crate.
- `two_node_daemon` (real WebRTC, in-memory signaling) is the portable data-plane gate and
  must pass on all three desktop platforms.
- `real_broker_tunnel` stays Linux-only; a Windows-native broker E2E is a P2 goal.
- `process_signal_shutdown` stays unix-only; a Windows equivalent driven by
  `SERVICE_CONTROL_STOP` is a P1 goal and must assert the same truthfulness properties:
  exit code, terminal `Closed` status, no lingering listeners.

## 9. CI

`windows-latest` runs `cargo fmt --all --check`, `cargo clippy --workspace --all-targets
--all-features -- -D warnings`, and `cargo test --workspace --all-targets --all-features`,
and is a required signoff gate alongside Linux and macOS.

It deliberately does not run the Docker broker E2E — `windows-latest` runners cannot
reliably host Linux containers — nor the signal lifecycle test, which is unix-only by
construction.
