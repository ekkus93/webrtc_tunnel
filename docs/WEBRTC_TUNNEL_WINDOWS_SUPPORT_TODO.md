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
- [ ] **Confirm green on the first real run.** It has only ever been validated on a local
      Windows machine; the hosted runner may differ. If it fails, fix forward rather than
      demoting the gate.

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

## P0-007 — `p2p-service-windows` binary with SCM integration

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

### Acceptance criteria
- [ ] Registered as two independent services per spec §6.4 (`p2ptunnel-answer`,
      `p2ptunnel-offer`), one binary with `--role`, each with its own config directory.
- [ ] `sc.exe start` / `sc.exe stop` work for each.
- [ ] Stop is graceful: exit code 0, terminal `Closed` status, no lingering listeners.
- [ ] Binary contains no tunnel logic.
- [ ] `p2p-offer` and `p2p-answer` are unchanged — no supervisor-specific mode is added to
      either, per the rule quoted in spec §6.4.

---

## P0-008 — Service installer: virtual account and restrictive DACL

### Files
```text
packaging/windows/                      (new)
scripts/
```

### Goal
Install the service under `NT SERVICE\p2ptunnel` and lock down the config directory.

### Recommended implementation
- Create the service with `sc.exe create ... obj= "NT SERVICE\p2ptunnel"`, which
  materializes the virtual account without password management.
- Apply a DACL to `%ProgramData%\p2ptunnel` granting only that account and
  `BUILTIN\Administrators`, with inheritance on so the identity file is protected at
  creation rather than by a later fixup.
- Mirror the validation style of `scripts/check-systemd-units.sh` and
  `scripts/check-launchd-plists.sh` with an equivalent Windows check script.
- Any config the installer seeds must set `file_logging = true` and
  `stdout_logging = false`, with `log_file` under the role's state/log directory. A service
  has no console, so a stdout-only config yields a service that works correctly while
  appearing to emit nothing. See spec §6.3.

### Acceptance criteria
- [ ] A standard non-admin user cannot read the installed identity file. Verify with
      `icacls` and by attempting a read as another account.
- [ ] The service starts and reaches steady state under the virtual account.
- [ ] Log output lands in the role's log file, and is not lost to a nonexistent console.
- [ ] Service runs with no user logged in and survives logoff.
- [ ] Uninstall removes the service and does not leave the account or ACLs behind.

---

# P1 tasks

## P1-001 — Prove real `mqtts://` signaling on Windows

### Goal
The Docker broker E2E is Linux-only, so real MQTT-over-TLS signaling has never run on
Windows. rumqttc and rustls are cross-platform and the risk is low, but it is unverified.

### Recommended implementation
Install mosquitto natively on Windows (chocolatey) and run a manual offer↔answer tunnel
against it, forwarding one port. This can precede the automated version in P2-003.

### Acceptance criteria
- [ ] A Windows peer completes signaling against a real TLS broker and passes data.

---

## P1-002 — Windows shutdown lifecycle test

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
- [ ] Test is `#![cfg(windows)]` and runs in the `test-windows` CI job.
- [ ] Asserts the same truthfulness properties as the unix test.

---

## P1-003 — Android test-harness portability on a Windows host

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
- [ ] `./gradlew check` is green on a Windows host.
- [ ] Still green on Linux CI.
- [ ] No production Kotlin behavior changed.

---

## P1-004 — Validate the offer role on Windows

### Goal
Spec §1 requires both roles. Only the answer role is exercised by the initial deployment.

### Acceptance criteria
- [ ] A Windows offer peer completes a tunnel against a non-Windows answer peer over a
      real broker and real network.

---

## P1-005 — Real-network ICE validation

### Goal
All WebRTC validation so far is loopback. NAT traversal and STUN behaviour on Windows are
unverified.

### Acceptance criteria
- [ ] A tunnel is established between two hosts on different networks with a Windows peer
      at one end.

---

# P2 tasks

## P2-001 — Windows packaging

### Goal
An installable artifact, matching the intent of `packaging/debian` and `packaging/macos`.

### Acceptance criteria
- [ ] Produces a signed-installable package that performs P0-008's account and ACL setup.
- [ ] A documented uninstall path.

---

## P2-002 — Add Windows to the release-artifacts matrix

### Files
```text
.github/workflows/ci.yml
```

### Goal
`release-artifacts` currently builds `ubuntu-latest` and `macos-latest` only.

### Acceptance criteria
- [ ] Tagged builds publish a Windows artifact.
- [ ] Archive format is appropriate for Windows (zip, not tar.gz).

---

## P2-003 — Windows-native broker E2E

### Goal
Replace the Docker dependency for Windows with a native mosquitto so the real-broker path
is covered in CI.

### Acceptance criteria
- [ ] Equivalent coverage to `real_broker_tunnel.rs`, running in the `test-windows` job.

---

## P2-004 — Runtime DACL validation

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

### Acceptance criteria
- [ ] `ENFORCES_FILE_PERMISSIONS` is `true` on Windows.
- [ ] A deliberately world-readable identity file is rejected at load.
- [ ] The P0-005 startup warning is removed as no longer applicable.
