# WebRTC Tunnel Windows Support TODO

## 0. Instructions for Claude Code

Implement this TODO against the `windows` branch.

Read first:

```text
docs/WEBRTC_TUNNEL_WINDOWS_SUPPORT_SPEC.md
crates/p2p-platform/src/lib.rs
crates/p2p-platform/src/unix.rs
crates/p2p-platform/src/windows.rs
crates/p2p-core/src/config/paths.rs
crates/p2p-core/src/config/validate.rs
crates/p2p-crypto/src/identity.rs
crates/p2p-daemon/src/process_signal.rs
crates/p2p-daemon/tests/process_signal_shutdown.rs
bins/p2pctl/src/commands.rs
bins/p2p-offer/src/main.rs
bins/p2p-answer/src/main.rs
.github/workflows/ci.yml
```

### Non-negotiable implementation rules

- Do not fork the codebase for Windows.
- Do not add `#[cfg(unix)]`, `#[cfg(windows)]`, or `#[cfg(target_os = ...)]` for
  permission or home-directory concerns anywhere above `p2p-platform`.
- Do not change signaling wire format, identity format, crypto, forward semantics, or
  WebRTC architecture.
- Do not relax the fail-closed security knobs in `AppConfig::validate` to accommodate
  Windows.
- Do not make a platform return `Ok(())` from a security check it cannot actually enforce.
  Report the gap via `ENFORCES_FILE_PERMISSIONS`.
- Do not add `unsafe` to `p2p-platform` without explicit sign-off recorded in this file;
  the workspace sets `unsafe_code = "forbid"` and `p2p-mobile` is its only documented
  exception.
- Do not put tunnel logic in `p2p-service-windows`. It is SCM plumbing only.
- Do not make `ctrl_c()` the service stop path. The SCM sends control codes, not console
  signals.
- Do not fix Android host-portability test failures by changing Android app behavior.
- Do not run the Docker broker E2E on `windows-latest`.
- Do not suppress lint findings to make a gate pass. See `CLAUDE.md`.
- Run `cargo fmt --all --check`, `cargo clippy --workspace --all-targets`, and
  `cargo test --workspace` before marking any task complete.

### Priority definitions

```text
P0 = required for a correct, secure Windows service deployment
P1 = validation and integration work after P0 is correct
P2 = packaging and release-matrix work
```

### Recording decisions

Two open policy questions are called out inline as **DECISION REQUIRED**. Do not
implement past them without sign-off; record the answer in this file when given.

Decisions recorded so far:

- **2026-10-15, service topology:** a single service named `p2ptunnel`, running as one
  shared virtual account `NT SERVICE\p2ptunnel`, with its role fixed at install time. This
  replaces the earlier one-service-per-role draft (`p2ptunnel-answer` / `p2ptunnel-offer`),
  which contradicted the account name. See spec §2.6 and §6.4. P0-007 and P0-008 follow
  from it.
- **2026-10-15, P2-004 runtime DACL validation:** option 1. Use the `windows-permissions`
  crate pinned to exactly `=0.2.4` through its safe API, with no `unsafe` anywhere in our
  crates. The user accepted the risk that it has been unmaintained since 2021 (its last
  release was 2021-06-29), and any panic or error inside the check counts as a rejection.
  The basis is a research comparison of a safe-wrapper crate, a documented `unsafe`
  exception, and shelling out to `icacls` / `Get-Acl`.

---

# P0 tasks

## P0-001 — Extract the `p2p-platform` OS seam — **DONE**

### Files
```text
crates/p2p-platform/                    (new)
Cargo.toml
crates/p2p-core/Cargo.toml
crates/p2p-core/src/config/paths.rs
```

### Goal
Create the single OS seam crate and move `p2p-core`'s home-directory and world-writable
logic behind it.

### Status
Complete. `home_dir()`, `ensure_private_file_permissions()`,
`ensure_not_writable_by_others()`, and `ENFORCES_FILE_PERMISSIONS` exist with unix and
windows implementations. `p2p-core/src/config/paths.rs` delegates instead of carrying its
own `cfg` pair.

### Acceptance criteria
- [x] `p2p-core` contains no `#[cfg(unix)]` for permissions or home resolution.
- [x] `home_dir()` prefers `USERPROFILE` on Windows and falls back to `HOME`.
- [x] `cargo test -p p2p-platform -p p2p-core` passes on Windows.

---

## P0-002 — Test-fixture portability — **DONE**

### Files
```text
crates/p2p-core/src/config/tests/support.rs
crates/p2p-core/src/config/tests/loading.rs
crates/p2p-core/src/config/tests/data_plane.rs
crates/p2p-core/tests/config_parsing.rs
crates/p2p-mobile/src/runtime/tests.rs
crates/p2p-mobile/tests/tracing_install_failure.rs
bins/p2pctl/src/commands/tests.rs
```

### Goal
Fixtures that hand-build TOML embedded raw `Path::display()` output. On Windows those are
backslashes, and TOML basic strings read `\U` as an 8-digit unicode escape, so configs
failed to parse.

### Status
Complete. A `toml_path()` helper doubles backslashes at every hand-built TOML site.
`advertised_local_ipv4_invalid_is_rejected` no longer builds a directory name from the
test value `"::1"` (`:` is reserved in Windows paths).

### Acceptance criteria
- [x] 25 previously failing tests pass across `p2p-core`, `p2p-mobile`, `p2pctl`.
- [x] Production code unaffected — `identity.rs::render_toml()` never interpolates paths.

---

## P0-003 — Windows CI job — **DONE**

### Files
```text
.github/workflows/ci.yml
```

### Status
Complete. `test-windows` runs fmt, clippy, and tests on `windows-latest` and is wired into
the `signoff` gate alongside Linux and macOS.

### Acceptance criteria
- [x] Job added and required in `signoff`.
- [x] **Confirm green on the first real run.** Green in CI #667 (2026-10-17, `d78722f`).
      It took four runs: every failure was a test-fixture or CI-setup assumption that held
      on the dev machine and not on the runner (a PowerShell 7 `PSModulePath` leak, the
      SCM test's temp-dir ACL, the nonexistent Chocolatey `mosquitto` package). No product
      bug turned up, and the gate was never demoted.

---

## P0-004 — Move the remaining unix-only production code behind the seam — **DONE**

**Depends on P0-006**, which adds `default_config_dir()`. Do that task first; the
`HOME`-replacement step below calls a function that does not exist until it lands. The
`identity.rs` and `create_private_file` parts of this task have no such dependency and can
proceed independently.

### Files
```text
crates/p2p-crypto/src/identity.rs
bins/p2pctl/src/commands.rs
bins/p2p-offer/src/main.rs
bins/p2p-answer/src/main.rs
crates/p2p-platform/src/lib.rs
crates/p2p-platform/src/unix.rs
crates/p2p-platform/src/windows.rs
```

### Goal
`p2p-crypto` and `p2pctl` still carry their own `#[cfg(unix)]` permission code and `HOME`
lookups. Move them to the seam so `p2p-platform` is genuinely the only OS-aware place.

### Recommended implementation
- Replace `identity.rs`'s `validate_private_file_permissions` pair with a call to
  `p2p_platform::ensure_private_file_permissions`, mapping `PlatformError` to
  `CryptoError::Permission`.
- Add `p2p_platform::create_private_file(path, contents)` — `0600` on unix, and on Windows
  a file created inside a directory whose DACL already restricts it (see P0-006). Use it
  for `write_identity_files` in `p2pctl`.
- Replace every `std::env::var_os("HOME")` in the three binaries and `p2pctl` with
  `p2p_platform::default_config_dir()`.

### Implementation notes
- `create_private_file` on unix opens with mode `0600` (so the key is never briefly
  world-readable under the umask, which the old `fs::write` + `set_permissions` sequence
  allowed) and then re-applies `0600`, because `mode` only takes effect when the file is
  *created* and `keygen --force` overwrites an existing one. On Windows it is a plain write:
  the file inherits the directory's DACL, which is the actual protection (P0-008), and
  `ENFORCES_FILE_PERMISSIONS = false` already says nothing stronger is checked.
- The binaries' `resolve_config_path` helpers take the default directory as a lazily
  evaluated `FnOnce() -> Result<PathBuf, PlatformError>` instead of an `Option<home>`. An
  explicit `--config` never consults the environment, and tests can inject a directory
  without touching real environment variables. The home → `.config/p2ptunnel` mapping
  itself is tested once, in `p2p-platform`.
- `p2p-crypto` maps `PlatformError::Io` to `CryptoError::IoPath`, so a missing identity file
  reports the same error variant as before.

### Scope of the `cfg(unix)` grep
`crates/p2p-daemon/src/process_signal.rs` also carries `#[cfg(unix)]`. That is *signal
handling*, not a permission or home-directory concern, so it is outside this task and the
"Non-negotiable implementation rules" above. It belongs to P0-007, which introduces the
SCM stop path alongside it. The acceptance grep below excludes it on that basis.

### Acceptance criteria
- [x] `grep -rn 'cfg(unix)' crates bins --include=*.rs` outside `p2p-platform` returns only
      test files and `process_signal.rs` (see scope note). (The Android ICE branches use
      `cfg(target_os = "android")`, not `cfg(unix)`, so they will not appear in this grep
      at all — they are a separate, pre-existing concern and are out of scope.)
- [x] `grep -rn 'var_os("HOME")' crates bins --include=*.rs` returns nothing outside
      `p2p-platform`.
- [x] `p2pctl keygen` works from native PowerShell, not only Git Bash. (Verified against a
      throwaway `%USERPROFILE%` with `HOME` unset: write, refuse-without-`--force`, replace.)

---

## P0-005 — Report unenforced permission checks at startup — **DONE**

### Files
```text
bins/p2p-offer/src/main.rs
bins/p2p-answer/src/main.rs
crates/p2p-daemon/src/logging.rs
```

### Goal
`AppConfig::validate` refuses to load a config that disables
`refuse_world_readable_identity` or `refuse_world_writable_paths`, so a user is entitled to
believe they are enforced. On Windows they currently are not.

### Recommended implementation
At daemon startup, if `!p2p_platform::ENFORCES_FILE_PERMISSIONS`, emit a single
`tracing::warn!` naming what is not enforced and what provides the protection instead
(the config directory DACL). Do not warn per-path; once per process.

### Implementation notes
- `p2p_daemon::report_unenforced_platform_checks()` lives in `logging.rs`, next to the
  subscriber it needs, and is called by both binaries immediately after `setup_logging`.
  P0-007's service binary calls it the same way.
- A `std::sync::Once` guard makes "once per process" a property of the function rather
  than of every caller remembering to call it once.
- The message text is built by a pure function taking the `ENFORCES_FILE_PERMISSIONS`
  value as a parameter, so both the Windows (warn) and unix (silent) branches are unit
  tested on every host rather than only on the host whose constant matches.

### Acceptance criteria
- [x] Warning appears exactly once on Windows startup. (Verified by running `p2p-answer`
      against a filled-in `docs/examples/answer-config.toml`: one line on stdout, one in the
      log file.)
- [x] No warning on unix. (Covered by the `enforced_permission_checks_produce_no_warning`
      unit test plus unix's `ENFORCES_FILE_PERMISSIONS = true`; not yet run on a unix host —
      Linux/macOS CI will exercise the unit test.)
- [x] Wording states plainly that the check did not run, not that it passed.

---

## P0-006 — Windows config and state directory layout — **DONE**

**P0-004 depends on this task** for `default_config_dir()`. Consider doing it first.

### Files
```text
crates/p2p-platform/src/lib.rs
crates/p2p-platform/src/windows.rs
crates/p2p-platform/src/unix.rs
docs/WEBRTC_TUNNEL_WINDOWS_SUPPORT_SPEC.md  (sections 6.2, 6.3)
```

### Goal
Implement `default_config_dir()` per spec §6.3: `%ProgramData%\p2ptunnel\<role>\` for the
service, `%USERPROFILE%\.config\p2ptunnel\` for an interactive user.

### Design decision — the layout is chosen by the caller, not detected

The original sketch said "select the service path when running under the service account".
That was dropped. Detecting the service account without `unsafe` means sniffing
`USERNAME`/`USERDOMAIN`, and a wrong guess means a service silently reading its config out
of a user-writable directory — a security regression, not a cosmetic one.

The existing packaging already establishes the correct pattern: **supervisors pass
`--config` explicitly on every platform.** `packaging/systemd/p2p-answer.service` passes
`/etc/p2ptunnel/answer/config.toml`; `packaging/launchd/com.p2ptunnel.answer.plist` passes
`/Library/Application Support/P2PTunnel/answer/config.toml`. Neither relies on a default.
Windows follows suit, so this is consistency with the existing design rather than an
exception to it.

The seam therefore exposes two path constructors and no detector:

- `default_config_dir()` — interactive user only.
- `service_config_dir(role)` — the supervisor/installer path, so P0-007 and P0-008 agree on
  it without hardcoding the same string twice.

### Status
Complete. Both functions exist on both platforms, with `PlatformError::Unresolved` for the
"environment does not name a home / `%ProgramData%`" case. `service_config_dir` restricts a
role to ASCII letters, digits, `-`, and `_`, so it cannot be walked out of its tree.

### Acceptance criteria
- [x] Both layouts resolve correctly and are covered by unit tests.
- [x] Unix behavior is unchanged.

---

## P0-007 — `p2p-service-windows` binary with SCM integration — **VERIFIED (offer role)**

**Status:** `bins/p2p-service-windows` builds, is clippy-clean, and its CLI unit tests pass.
Run by hand, it refuses with a clear message pointing at `p2p-answer` / `p2p-offer`.
**Verified elevated on 2026-10-17** with the offer role: after `install.ps1 -Role offer`,
`sc.exe qc` shows `--role offer` in `binPath` and `NT SERVICE\p2ptunnel`; `sc.exe start`
reached RUNNING and the daemon read `%ProgramData%\p2ptunnel\offer\config.toml`; a request
through the forwarded ollama port returned 200 and `p2pctl status` showed `tunnel_open`;
`sc.exe stop` reached STOPPED in under a second with `WIN32_EXIT_CODE` and
`SERVICE_EXIT_CODE` both 0, no listener left on any forward port and no
`p2p-service-windows` process left; a second start/stop cycle behaved the same. The
**answer role has not been installed and run as a service**.

### Files
```text
bins/p2p-service-windows/               (new)
crates/p2p-daemon/src/process_signal.rs
```

### Goal
Host `run_answer_daemon` / `run_offer_daemon` under the Service Control Manager, with
`SERVICE_CONTROL_STOP` driving the existing `ShutdownToken`.

### Recommended implementation
- SCM registration, control handler, and `SERVICE_STATUS` reporting live only in this
  binary.
- The control handler resolves the same `ShutdownToken` the unix signal adapter produces.
  Nothing downstream of the token changes.
- Keep `ctrl_c()` as the foreground-console path; a service and a console process are two
  producers of one generic shutdown request.
- Report `SERVICE_STOP_PENDING` with a truthful wait hint while sessions drain, then
  `SERVICE_STOPPED`.

### On `unsafe_code = "forbid"`
Read this before assuming a policy exception is needed — the constraint is narrower than it
first appears.

`unsafe_code` is a **per-crate** lint. It fires on `unsafe` written in *this* crate's own
source. A dependency being full of `unsafe` internally is irrelevant, so calling a crate
like `windows-service` through its safe API trips nothing.

The one real hazard is **macro expansion**: `forbid` also fires on `unsafe` that a macro
expands into our crate, and `forbid` cannot be overridden by an inner `allow`.
`windows-service` exposes `define_windows_service!`, which generates the `extern "system"`
entry point. Whether that expansion contains `unsafe` must be **verified empirically** —
add the dependency, write the smallest possible service skeleton, and see whether it
compiles under the workspace lint.

Only if it does not compile is there a decision to make, and then the options are: a
documented `bins/p2p-service-windows` exception on the model of `p2p-mobile`, or a
different crate. Record the outcome of the experiment here either way.

**Outcome (2026-10-15): no exception needed.** A minimal skeleton using
`define_windows_service!` from `windows-service` 0.8.1 compiles cleanly under the workspace
`unsafe_code = "forbid"`. The control check was that adding a bare `unsafe {}` block to the
same crate fails with "usage of an `unsafe` block … requested on the command line with
`-F unsafe-code`", so the lint really was active. The macro expansion contains no `unsafe`
in our crate.

### Implementation notes
- **Config location.** The binary resolves its config as
  `p2p_platform::service_config_dir(role)\config.toml`. The installer's `binPath` passes only
  `--role`, so the installer and the wrapper agree on one path through the seam instead of
  each hardcoding it (spec §6.2). An optional `--config` override exists for diagnosis.
- **Startup glue is shared (changed 2026-10-16 after code review).** It was first
  duplicated from `p2p-answer` / `p2p-offer` to leave them untouched, but that let the
  three copies drift. It now lives in `p2p-daemon::startup` as two functions,
  `load_runtime_config` (load, env overrides, broker URL, optional role check, validate,
  runtime dirs) and `load_identity_and_keys`. All three binaries call them with
  `setup_logging` and `report_unenforced_platform_checks` between, which also keeps the
  service's per-phase exit codes meaningful. The foreground binaries keep their
  behavior and flags; they gain no supervisor-specific mode, which is what the
  acceptance criterion protects. Only the service asks for the role check, so
  a foreground run behaves exactly as before.
- **One service-name constant.** `p2p_platform::SERVICE_NAME` is the single Rust source for
  `p2ptunnel`. The service binary and the DACL check use it, and
  `scripts/check-windows-service-scripts.ps1` fails if it differs from the installer's
  `$P2pServiceName`.
- **Status reporting.** `StartPending` while the config and identity load, then `Running`
  with `STOP` accepted. On `SERVICE_CONTROL_STOP` the handler only calls
  `ShutdownToken::request_shutdown()`. The service thread then reports `StopPending`,
  advancing the checkpoint every few seconds while sessions drain, so the wait hint stays
  truthful. Draining is capped at 30 s, matching `TimeoutStopSec=30s` in the shipped
  systemd units. If the daemon is still running at the cap, the service reports `Stopped`
  with a service-specific non-zero exit code, never as a clean stop.
- **Exit codes.** A clean stop reports `NO_ERROR`. A failure reports a service-specific
  code by phase (config, logging, identity/keys, daemon error, drain timeout), so
  `sc.exe query p2ptunnel` can tell them apart. A daemon that finishes on its own, with no
  stop request, is also reported as a daemon failure rather than a clean stop, so the SCM's
  restart-on-failure recovery actions can fire.
- **Errors before logging starts.** Config and logging-setup failures happen before the
  log file exists. The wrapper appends them, with a timestamp and the exit-code phase, to
  `service_config_dir(role)\state\startup-error.log`. That is the one directory the
  installer lets the service account write, and it doesn't depend on the config that just
  failed to load. Writing to it is best-effort and never masks the original failure.
  Event Log integration would need a registered event source and is not planned.
- **Non-Windows hosts.** `windows-service` is a `cfg(windows)` target dependency, and
  `main` on other platforms prints that the binary is Windows-only and exits 1. That keeps
  `cargo clippy --workspace` and `cargo test --workspace` clean on Linux and macOS CI.

### Acceptance criteria
- [x] Runs as the single service `p2ptunnel` per spec §6.4. The role is fixed at install
      time by `--role answer|offer` in `binPath`, and the config is read from
      `p2p_platform::service_config_dir(role)`. (Checked for `offer`.)
- [ ] `sc.exe start p2ptunnel` / `sc.exe stop p2ptunnel` work with either role installed.
      (Works with `offer`; `answer` not yet tried.)
- [x] The service hosts exactly one daemon. Running both roles as services at once is out
      of scope (spec §6.4). (By construction: `--role` selects one daemon per process.)
- [x] Stop is graceful: exit code 0, terminal `Closed` status, no lingering listeners.
      (Exit code and listeners from the elevated install run; the `Closed` status file from
      the P1-002 test.)
- [x] Binary contains no tunnel logic. (SCM plumbing, status reporting and the shared
      startup helpers; the daemon entry points are the ones the foreground binaries call.)
- [x] `p2p-offer` and `p2p-answer` are unchanged — no supervisor-specific mode is added to
      either, per the rule quoted in spec §6.4. (Their startup code now calls the shared
      `p2p_daemon::startup` helpers, with the same flags, behavior and error text; no
      supervisor mode was added.)

---

## P0-008 — Service installer: virtual account and restrictive DACL — **VERIFIED (mostly)**

**Status:** `packaging/windows/install.ps1`, `uninstall.ps1`, `p2ptunnel-common.ps1` and
`README.md` exist, and `scripts/check-windows-service-scripts.ps1` validates them
statically. CI runs that check in `test-windows`. The install also sets
`sc.exe failureflag p2ptunnel 1`, so the recovery actions fire on the wrapper's non-zero
`STOPPED` exits as well as on crashes. **Run elevated on 2026-10-17:** install, a refused
second install, start, stop, a second start/stop and `uninstall.ps1 -RemoveData` all behaved
as specified; see the ticked criteria. Delayed-auto start, restart-on-failure actions
(5 s, 5 s, 30 s) and the failure flag were read back with `sc.exe qc`, `qfailure` and
`qfailureflag`. Not yet shown: an actual read attempt by another account, and the
logoff/no-user-logged-in criterion.

### Files
```text
packaging/windows/                      (new)
scripts/
```

### Goal
Install the service under `NT SERVICE\p2ptunnel` and lock down the config directory.

### Recommended implementation
- The installer takes the role (`answer` or `offer`) as a parameter and registers the single
  service `p2ptunnel` with `binPath= "<install dir>\p2p-service-windows.exe --role <role>"`.
  The service name must be exactly `p2ptunnel`, because the virtual account's name comes
  from it (spec §2.6). Installing when `p2ptunnel` already exists must fail clearly instead
  of silently switching roles. To switch roles, uninstall and then install again.
- Create the service with `sc.exe create p2ptunnel ... obj= "NT SERVICE\p2ptunnel"`, which
  materializes the virtual account without password management.
- Apply a DACL to `%ProgramData%\p2ptunnel` granting only that account,
  `NT AUTHORITY\SYSTEM`, and `BUILTIN\Administrators`, with inheritance on below it so the
  identity file is protected at creation rather than by a later fixup. Inheritance *from*
  `%ProgramData%` must be disabled on that directory, or `BUILTIN\Users` read access flows
  back in.
- Mirror the validation style of `scripts/check-systemd-units.sh` and
  `scripts/check-launchd-plists.sh` with an equivalent Windows check script.
- Any config the installer seeds must set `file_logging = true` and
  `stdout_logging = false`, with `log_file` under the role's state/log directory. A service
  has no console, so a stdout-only config yields a service that works correctly while
  appearing to emit nothing. See spec §6.3.

### Acceptance criteria
- [ ] A standard non-admin user cannot read the installed identity file. Verify with
      `icacls` and by attempting a read as another account. (`icacls` on the identity
      lists only SYSTEM, Administrators and the service account, with no Users,
      Authenticated Users or Everyone entry. No read was attempted as another account.)
- [x] The service starts and reaches steady state under the virtual account. (The
      `p2p-service-windows` process owner was `NT SERVICE\p2ptunnel`; `tunnel_open`.)
- [x] Log output lands in the role's log file, and is not lost to a nonexistent console.
      (`state\log\p2ptunnel.log` was written.)
- [ ] Service runs with no user logged in and survives logoff. (Not tested; the runs were
      made from a logged-in session.)
- [x] Uninstall removes the service and does not leave the account or ACLs behind.
      (`sc.exe query` returns 1060 afterwards and `%ProgramData%\p2ptunnel` is gone. The
      installer leaves the binary in `-InstallDir` by design.)
- [x] Re-running install while `p2ptunnel` already exists fails with a clear message.

---

# P1 tasks

## P1-001 — Prove real `mqtts://` signaling on Windows — **HARNESS WRITTEN**

**Status:** `tests/e2e/windows/mqtts-loopback.ps1` automates the manual proof. It hasn't
been run, because mosquitto isn't installed on the dev machine.

### Goal
The Docker broker E2E is Linux-only, so real MQTT-over-TLS signaling has never run on
Windows. rumqttc and rustls are cross-platform and the risk is low, but it is unverified.

### Recommended implementation
Install mosquitto natively on Windows (the upstream installer) and run a manual offer↔answer tunnel
against it, forwarding one port. This can precede the automated version in P2-003.

### Acceptance criteria
- [x] A Windows peer completes signaling against a real TLS broker and passes data.
      (Two ways: the P2-003 test against a local mosquitto with TLS passes on the dev machine
      and on `windows-latest` in CI #667, and on 2026-10-17 a Windows `p2p-offer` completed a
      tunnel through the public `mqtts://broker.emqx.io:8883`. The `mqtts-loopback.ps1`
      script itself has still not been run.)

---

## P1-002 — Windows shutdown lifecycle test — **PASSES ELEVATED**

**Status:** `bins/p2p-service-windows/tests/scm_shutdown.rs` registers a throwaway
`p2ptunnel-test-<pid>` service, never `p2ptunnel` itself, with `--role offer` and a temp
config. A minimal in-process fake TLS MQTT broker keeps the daemon alive: with an
unreachable broker it exits after about 24 s and never binds its listener. The test then
asserts RUNNING, a `listening` forward, a real `SERVICE_CONTROL_STOP`, a clean exit code, a
final `closed` status, and that the port is released. It skips on access denied unless
`P2P_REQUIRE_SCM_TEST=1` is set. **First elevated run, 2026-10-17, found two test bugs, now
fixed; it passes** (STOPPED about 200 ms after the stop request). (1) The fixture's temp
directory inherited the user's ACL, and the service runs as LocalSystem, where the runtime
DACL check rejects an identity another ordinary account can read; the fixture now limits its
directory to SYSTEM and Administrators with `icacls`, as the installer does. (2) Windows
retransmits the SYN to a closed loopback port for about two seconds before reporting the
refusal, so the "port refuses connections" probe's 2 s timeout read a refusal as `TimedOut`;
it is now 10 s. The failure output also now includes `state\startup-error.log`. Hosted
`windows-latest` runners are elevated, so it will likely run for real in CI.

### Files
```text
crates/p2p-daemon/tests/                (new Windows-gated test)
```

### Goal
`process_signal_shutdown.rs` is `#![cfg(unix)]`, so Windows has zero coverage of daemon
shutdown. With RDP forwarded by an always-on service, "did the listener actually close"
stops being cosmetic.

### Recommended implementation
Mirror the unix test's assertions — exit code, terminal `Closed` status, no lingering
listeners — but drive it through `SERVICE_CONTROL_STOP` rather than a signal.

### Acceptance criteria
- [x] Test is `#![cfg(windows)]` and runs in the `test-windows` CI job. (It ran for real,
      elevated, in CI #667.)
- [x] Asserts the same truthfulness properties as the unix test. (Executed elevated; passes.)

---

## P1-003 — Android test-harness portability on a Windows host — **DONE on Windows**

**Status:** committed in 79c72c5. On Windows, 790 tests pass with none skipped and
`./gradlew check` is green, run with `-PskipRustBuild`. That run also fixed a ninth test,
which had been silently skipping on Windows. Not yet confirmed on Linux CI.

### Files
```text
android/app/src/test/java/com/phillipchin/webrtctunnel/data/ForwardsConfigStoreTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/data/ForwardsRepositoryTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/data/ConfigRepositoryTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/ForwardsViewModelTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/viewmodel/SetupStepValidationTest.kt
android/app/src/test/java/com/phillipchin/webrtctunnel/ProbeEvidenceShellContractTest.kt
```

### Goal
Eight Kotlin unit tests fail on a Windows host. All are test-harness defects, not app
defects.

Categories:
1. Six use `File.setReadable(false)` / `setWritable(false)` / `setReadOnly()` to simulate
   an unreadable file. These are no-ops on Windows — NTFS ACLs do not map to POSIX mode
   bits — so the error path under test never triggers.
2. One asserts `template.contains(filesDir.absolutePath)` while the production code
   correctly TOML-escapes the path.
3. One invokes bash with an unquoted Windows path, so `\U`-style sequences are stripped.

### Recommended implementation
Prefer injecting a failing filesystem abstraction over relying on OS permission
side-effects. Do **not** change app behavior to make these pass.

### Acceptance criteria
- [x] `./gradlew check` is green on a Windows host. (Run with `-PskipRustBuild`; the
      Rust JNI build was not part of that run.)
- [ ] Still green on Linux CI.
- [x] No production Kotlin behavior changed. (Only the six test files changed.)

---

## P1-004 — Validate the offer role on Windows

### Goal
Spec §1 requires both roles. Only the answer role is exercised by the initial deployment.

**Status (2026-10-17):** verified manually. `p2p-offer.exe` (release build, run in the
foreground with a copy of a Linux offer config) completed a tunnel to a Linux `p2p-answer`
over `mqtts://broker.emqx.io:8883` and the data-plane probe was acknowledged. An HTTP GET
through the forwarded ollama port returned 200 with a real response body. This also covers
the real-TLS-broker half of P1-001 from the Windows side (default system trust,
`ca_file = ""`).

### Acceptance criteria
- [x] A Windows offer peer completes a tunnel against a non-Windows answer peer over a
      real broker and real network.

---

## P1-006 — Let `p2pctl` write directly into the service config directory — **DONE**

### Files
```text
bins/p2pctl/src/main.rs
bins/p2pctl/src/commands.rs
bins/p2pctl/src/commands/tests.rs
docs/WINDOWS.md, packaging/windows/install.ps1 (operator instructions)
```

### Goal
`p2pctl keygen` and `add-authorized-key` can only write into the interactive user's
directory. Setting up the service therefore means copying the identity into
`%ProgramData%\p2ptunnel\<role>\`. A file that is *moved* rather than copied keeps the ACL
it had, so this manual step is an easy way to leave the private key readable. The P0-008
installer work flagged it.

### Recommended implementation
- `p2pctl keygen <PEER_ID> [--dir <DIR>]` writes `identity` / `identity.pub` into `DIR`
  instead of the default config directory. The file is created in place through
  `p2p_platform::create_private_file`, so on Windows it inherits the directory's DACL
  at creation.
- `p2pctl add-authorized-key <IDENTITY_PUB> [--authorized-keys <FILE>]` appends to `FILE`
  instead of the default.
- Both default to exactly today's behavior when the flag is omitted.
- Update the operator instructions to use these flags instead of copy steps.

### Acceptance criteria
- [x] Without the flags, behavior and output are unchanged.
- [x] `keygen --dir` creates the identity in the given directory, and refuses to overwrite
      without `--force` exactly as before.
- [x] `add-authorized-key --authorized-keys` appends to the given file with the same
      duplicate-peer refusal.

---

## P1-005 — Real-network ICE validation

### Goal
All WebRTC validation so far is loopback. NAT traversal and STUN behaviour on Windows are
unverified.

**Status (2026-10-17):** verified manually. Windows on a phone hotspot (private
10.172.x address, public 172.59.x) connected to a Linux answer running in a bridge-mode
Docker container behind a home/office NAT. ICE reached `connected` about 1 s after checking
started, with no Windows Firewall rule for `p2p-offer.exe`. STUN only, no TURN, so symmetric
NAT / CGNAT is still untested. The same test from the answer's own LAN fails (`ice_failed`):
the answer advertises only its Docker bridge address plus the shared public address, which a
router without hairpin NAT cannot loop back, and Windows Firewall drops the answer's inbound
checks. That is a same-LAN deployment limit, not a Windows defect.

### Acceptance criteria
- [x] A tunnel is established between two hosts on different networks with a Windows peer
      at one end.

---

# P2 tasks

## P2-001 — Windows packaging — **CODE COMPLETE, NOT BUILT**

**Status:** `packaging/windows/wix/` (`Product.wxs`, `build-msi.ps1`, `README.md`) defines a
WiX v4+ MSI. It installs the binaries and runs the reviewed `install.ps1` / `uninstall.ps1`
as deferred elevated custom actions. `ROLE=answer|offer` is required and is stored for
upgrades and repairs; `REMOVEDATA=1` is optional. It has never been built, because WiX isn't
installed here. **Open decision for the user:** WiX v6+ is under the OSMF EULA, so
`build-msi.ps1` passes the EULA acceptance through only with an opt-in `-AcceptEula`, and
no CI build step exists.

### Goal
An installable artifact, matching the intent of `packaging/debian` and `packaging/macos`.

### Acceptance criteria
- [ ] Produces a signed-installable package that performs P0-008's account and ACL setup.
- [x] A documented uninstall path. (`packaging/windows/wix/README.md`; `uninstall.ps1`.)

---

## P2-002 — Add Windows to the release-artifacts matrix — **CODE COMPLETE**

**Status:** `release-artifacts` builds `windows-latest` and publishes a `.zip` with all four
`.exe` files, `packaging/windows/`, `docs/examples/` and `docs/WINDOWS.md`, laid out the
way `install.ps1` expects. **It has not run on a tag yet.** The job is tag-only, needs the
whole CI matrix (including the Docker and Android emulator E2Es) and publishes a public
GitHub Release, so it was not exercised that way.

**Checked locally on 2026-10-17.** `cargo build --release --workspace` succeeds on Windows
(7 min). The workflow's own Windows packaging step, extracted from `ci.yml` and run
unchanged, produced a 21.8 MB zip with `p2pctl`, `p2p-offer`, `p2p-answer`,
`p2p-service-windows`, `README.md`, `LICENSE`, `packaging/windows/*`, `docs/examples/*` and
`docs/WINDOWS.md`. Extracted into an empty directory, the three daemons/tools run, the
scripts parse, and `install.ps1`'s default config templates (`..\..\docs\examples`) resolve.
That run found one bug, now fixed: `WINDOWS.md` was placed at the zip root, which broke its
relative links, so it now ships as `docs/WINDOWS.md`. Not covered locally: the hosted
runner, `upload-artifact`, the release upload, and running `install.ps1` from the
extracted tree. The local run used Windows PowerShell 5.1, whose `Compress-Archive` writes
backslash entry separators; the runner's `pwsh` 7 writes forward slashes. Neither the
binaries nor the installer care, but check an Explorer extraction of the first real
release.

### Files
```text
.github/workflows/ci.yml
```

### Goal
`release-artifacts` currently builds `ubuntu-latest` and `macos-latest` only.

### Acceptance criteria
- [ ] Tagged builds publish a Windows artifact.
- [x] Archive format is appropriate for Windows (zip, not tar.gz). (In the workflow; not
      yet run on a tag.)

---

## P2-003 — Windows-native broker E2E — **DONE**

**Status:** `crates/p2p-daemon/tests/native_broker_tunnel.rs` skips when mosquitto is
absent, unless `P2P_REQUIRE_BROKER_TEST` is set. `test-windows` installs mosquitto and runs
it. The user relaxed gating on 2026-10-15, so that one step is `continue-on-error` until it
has passed on the hosted runner. Then remove the flag.

**Hosted runs (2026-10-17).** CI #666 failed at install: the step used
`choco install mosquitto`, but the Chocolatey community repository has no `mosquitto`
package, so nothing ran. The step now downloads the upstream installer
(`mosquitto-2.0.22-install-windows-x64.exe`, not Authenticode-signed) with its SHA-256
pinned in the workflow, and installs it silently with `/S`. **CI #667 passed**: the install
worked and `full_tunnel_over_native_tls_broker` passed in about 3.5 s of test time. The
`continue-on-error` flag has been removed, so the step is now a required gate. Its
remaining risk is that every run downloads from mosquitto.org; if that proves flaky, cache
the installer or host it as a release asset rather than loosening the gate again.

### Goal
Replace the Docker dependency for Windows with a native mosquitto so the real-broker path
is covered in CI.

### Acceptance criteria
- [x] Equivalent coverage to `real_broker_tunnel.rs`, running in the `test-windows` job.

---

## P2-004 — Runtime DACL validation — **DONE**

### Goal
Defense in depth on top of P0-008's install-time ACL: verify at load time that no ACE
grants read access to anyone but the owner, SYSTEM, and Administrators, so
`ENFORCES_FILE_PERMISSIONS` can become `true` on Windows.

### DECISION REQUIRED
Unlike P0-007, this one probably does need a policy call. See P0-007's note for how
`unsafe_code = "forbid"` actually works — the question is only ever whether `unsafe` ends
up in *our* crate's source or macro expansion, never whether a dependency contains it.

Calling `GetNamedSecurityInfo` and walking the ACL directly through `windows-sys` is raw
FFI, which is unambiguously `unsafe` in `p2p-platform` and would be rejected. So this needs
one of:

1. a vetted safe wrapper crate that exposes DACL inspection without `unsafe` at the call
   site — investigate availability and maintenance status before committing;
2. a documented `p2p-platform` exception on the model of `p2p-mobile`; or
3. shelling out to `icacls` and parsing its output — no `unsafe`, but fragile and
   locale-sensitive, so treat as a last resort.

Record the decision here. Note this is genuinely optional: P0-008's install-time ACL is what
provides the actual protection, and this task only upgrades `ENFORCES_FILE_PERMISSIONS`
from advisory to verified.

**Decided 2026-10-15: option 1, `windows-permissions = "=0.2.4"`.** See "Decisions recorded
so far". Rules for the check:
- Read the file's owner and DACL through `GetNamedSecurityInfo`. The returned DACL already
  includes inherited ACEs, so do not skip them. Skip `INHERIT_ONLY` ACEs when checking a
  directory itself.
- Consider only *allow* ACEs, and reject conservatively without crediting deny ordering.
- Count these as read access: `FILE_READ_DATA` (0x1), `FILE_READ_EA` (0x8),
  `GENERIC_READ`, `GENERIC_ALL` and `MAXIMUM_ALLOWED`. Count these as write access:
  `FILE_WRITE_DATA`, `FILE_APPEND_DATA`, `FILE_WRITE_EA`, `FILE_DELETE_CHILD`, `DELETE`,
  `WRITE_DAC`, `WRITE_OWNER`, `GENERIC_WRITE` and `GENERIC_ALL`.
- Allowed principals are matched by SID, never by name: the file's owner, SYSTEM
  (S-1-5-18), Administrators (S-1-5-32-544), and the SID the current process runs as.
  Under the service, that last one is `NT SERVICE\p2ptunnel`, which the installer's ACE
  grants. That covers an installer-created file owned by Administrators without
  `p2p-platform` hardcoding the service name.
- **Amended 2026-10-16 (code review):** the allowlist also always includes the
  `NT SERVICE\p2ptunnel` SID, not only when the process runs as it. The installer grants
  that account read access to the identity and Modify on `state\`, so any other process
  loading the service's files, such as `p2pctl check-config` or a hand-run `p2p-answer`
  from an elevated shell, would otherwise reject the installer's own ACEs. The SID is
  derived, not looked up by name: `S-1-5-80-` followed by the five little-endian 32-bit
  words of the SHA-1 of the upper-cased UTF-16LE service name. That is how Windows derives
  it, it is independent of the system language, and a test pins it to the value
  `sc.exe showsid` reports.
- `ensure_not_writable_by_others` checks the nearest existing ancestor, as on unix.
- Wrap the crate calls in `catch_unwind`. A panic or an error counts as a rejection,
  reported through `PlatformError`, never silently as success.

### Acceptance criteria
- [x] `ENFORCES_FILE_PERMISSIONS` is `true` on Windows.
- [x] A deliberately world-readable identity file is rejected at load. (Unit tests in
      `p2p-platform`'s `windows.rs` and `p2p-crypto/tests/identity_parsing.rs`.)
- [x] The P0-005 startup warning is no longer emitted on Windows. The function stays, and
      is silent whenever the constant is `true`.
