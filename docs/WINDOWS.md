# Running on Windows

`p2p-offer` and `p2p-answer` are ordinary foreground processes on Windows, as they are
everywhere else. This guide covers running them from a terminal, and running one of them
as the native Windows service `p2ptunnel`. Manual execution remains fully supported
without the service; see the main [README](../README.md#running-offer-and-answer).

The Windows counterparts of [`docs/SYSTEMD.md`](SYSTEMD.md) and
[`docs/LAUNCHD.md`](LAUNCHD.md) are this file plus
[`packaging/windows/README.md`](../packaging/windows/README.md), which documents the
installer scripts in detail. The design is in
[`WEBRTC_TUNNEL_WINDOWS_SUPPORT_SPEC.md`](WEBRTC_TUNNEL_WINDOWS_SUPPORT_SPEC.md) and task
status is in [`WEBRTC_TUNNEL_WINDOWS_SUPPORT_TODO.md`](WEBRTC_TUNNEL_WINDOWS_SUPPORT_TODO.md).
Read [Current limitations](#current-limitations) before relying on this for anything
important.

## What runs on Windows

| Program | What it is |
|---|---|
| `p2p-offer.exe`, `p2p-answer.exe` | The daemons, run in a terminal as `p2p-answer run --config ...`. Stop with `Ctrl+C`. |
| `p2pctl.exe` | `keygen`, `fingerprint`, `add-authorized-key`, `check-config`, `status`. |
| `p2p-service-windows.exe` | Hosts one daemon under the Service Control Manager (SCM). Takes `--role answer` or `--role offer`. It is SCM plumbing only and has no tunnel logic. You do not run it by hand. |

The service is a **single** Windows service named `p2ptunnel`, running as the virtual
account `NT SERVICE\p2ptunnel`. Windows derives that account's name from the service name,
so the name is fixed. Its role (`answer` or `offer`) is chosen when you install and is part
of the service's `binPath`.

**One role per machine runs as a service.** To switch a machine from one role to the other,
uninstall and install again. The other role can still run on the same machine as an
ordinary foreground `p2p-offer` / `p2p-answer` process using the interactive layout below.

`p2p-offer` and `p2p-answer` have no service mode and never will; they stay ordinary
foreground processes. The service is a separate binary because Windows requires a service
process to connect to the SCM itself (spec §6.4). A service runs in Session 0 with no
desktop and no console, and keeps running with nobody logged in.

## Interactive and service layouts

Which layout applies is chosen by whoever starts the process, not detected. An explicit
`--config` always wins. The service wrapper reads `config.toml` from the service layout
by itself.

```text
Interactive user (p2p-offer / p2p-answer / p2pctl with no --config):
  config     %USERPROFILE%\.config\p2ptunnel\config.toml
  identity   %USERPROFILE%\.config\p2ptunnel\identity
  identity.pub, authorized_keys   same directory

Service (NT SERVICE\p2ptunnel), one tree, one <role>:
  config     %ProgramData%\p2ptunnel\<role>\config.toml
  identity   %ProgramData%\p2ptunnel\<role>\identity
  authorized_keys, ca.crt         same directory
  state      %ProgramData%\p2ptunnel\<role>\state\          (status.json)
  logs       %ProgramData%\p2ptunnel\<role>\state\log\p2ptunnel.log
```

The interactive directory is protected by your profile's default ACL (you, SYSTEM and
Administrators). The service tree is **not** protected by default: `%ProgramData%` normally
lets every local user read what is created under it. `install.ps1` fixes that; see
[Security model](#security-model).

## 1. Build and install binaries

```powershell
cargo build --release -p p2p-offer -p p2p-answer -p p2pctl -p p2p-service-windows
```

The binaries land in `target\release\`. For foreground use, put `p2p-offer.exe`,
`p2p-answer.exe` and `p2pctl.exe` anywhere on your `PATH`. `p2p-service-windows.exe` is
copied into place by the installer (step 5 below). The commands in this guide work from
native Windows PowerShell; you do not need Git Bash.

## 2. Generate an identity

`p2pctl keygen` takes the peer id as a positional argument and writes to your interactive
directory, `%USERPROFILE%\.config\p2ptunnel\`, unless you pass `--dir`:

```powershell
p2pctl keygen answer-office                                          # interactive
p2pctl keygen answer-office --dir "$env:ProgramData\p2ptunnel\answer"   # service (elevated)
```

For the service, always use `--dir`: the key is then created inside the locked-down
directory and inherits its ACL from the start.

This writes `identity` (the private keys) and `identity.pub` (the public record). It
refuses to overwrite existing identity files unless you pass `--force`. The `peer_id`
inside `identity` must match `[node].peer_id` in the config, or the daemon refuses to
start.

To print a public identity's fingerprint:

```powershell
p2pctl fingerprint "$env:USERPROFILE\.config\p2ptunnel\identity.pub"
```

## 3. Authorize the peer

Exchange `identity.pub` files with the other side out of band, as on other platforms.

For an interactive setup, `add-authorized-key` appends the peer to
`%USERPROFILE%\.config\p2ptunnel\authorized_keys`. It refuses a peer id that is already
listed:

```powershell
p2pctl add-authorized-key .\offer-home.identity.pub
```

For the service, point it at the service's file with `--authorized-keys`, from an elevated
PowerShell:

```powershell
p2pctl add-authorized-key .\offer-home.identity.pub --authorized-keys "$env:ProgramData\p2ptunnel\answer\authorized_keys"
```

## 4. Write `config.toml`

Start from [`docs/examples/answer-config.toml`](examples/answer-config.toml) or
[`docs/examples/offer-config.toml`](examples/offer-config.toml). They contain `__IDENTITY__`,
`__STATE_DIR__` and similar placeholders that you must replace. (For the service,
`install.ps1` fills these in for you.) The config must be complete; `p2pctl check-config`
does not accept fragments.

### Backslashes in TOML paths

A normal TOML string (`"..."`) treats `\` as an escape character, and Windows paths are
full of backslashes. Use either of these:

```toml
identity = 'C:\ProgramData\p2ptunnel\answer\identity'    # single quotes: literal, no escapes
identity = "C:/ProgramData/p2ptunnel/answer/identity"    # forward slashes work on Windows
```

Do **not** write `identity = "C:\ProgramData\..."`. Depending on the next character it
either fails to parse (see [Troubleshooting](#troubleshooting)) or, worse, parses as
something else: `"C:\temp"` contains a tab. A literal string cannot contain a single
quote; if a path does, use forward slashes. A leading `~/` is expanded to your profile
directory (`%USERPROFILE%`); `~\` is not.

### Interactive example

```toml
[paths]
identity = '~/.config/p2ptunnel/identity'
authorized_keys = '~/.config/p2ptunnel/authorized_keys'
state_dir = 'C:\Users\you\AppData\Local\p2ptunnel\state'
log_dir = 'C:\Users\you\AppData\Local\p2ptunnel\log'

[logging]
level = "info"
format = "text"
file_logging = false
stdout_logging = true
log_file = 'C:\Users\you\AppData\Local\p2ptunnel\log\p2ptunnel.log'
redact_secrets = true
redact_sdp = true
redact_candidates = true
log_rotation = "none"
```

Replace `you`. `log_file` is still required by the schema; with `file_logging = false` it is
not opened. Stdout logging is right for a terminal.

### Service example (role `answer`)

This is what `install.ps1` seeds. `[paths]` and the `[broker.tls]`/`[health]` file paths
point into the service tree, and logging goes to a file because **Session 0 has no
console**: with `stdout_logging = true` and `file_logging = false` a service runs fine and
appears to emit nothing.

```toml
[paths]
identity = 'C:\ProgramData\p2ptunnel\answer\identity'
authorized_keys = 'C:\ProgramData\p2ptunnel\answer\authorized_keys'
state_dir = 'C:\ProgramData\p2ptunnel\answer\state'
log_dir = 'C:\ProgramData\p2ptunnel\answer\state\log'

[broker.tls]
ca_file = 'C:\ProgramData\p2ptunnel\answer\ca.crt'

[logging]
level = "info"
format = "text"
file_logging = true
stdout_logging = false
log_file = 'C:\ProgramData\p2ptunnel\answer\state\log\p2ptunnel.log'
redact_secrets = true
redact_sdp = true
redact_candidates = true
log_rotation = "none"

[health]
write_status_file = true
status_file = 'C:\ProgramData\p2ptunnel\answer\state\status.json'
```

`[node].role` must equal the role the service was installed with. A mismatch makes the
service stop at startup with exit code 1.

### Check it

```powershell
p2pctl check-config                                                      # interactive default
p2pctl check-config --config "$env:ProgramData\p2ptunnel\answer\config.toml"   # elevated shell
```

`check-config` loads and validates the config, then confirms the identity file exists and
matches `peer_id`, and that every required remote peer is in `authorized_keys`. For the
service tree you need an **elevated** shell, because the installer's ACL deliberately
denies standard users. It does not inspect permissions and does not check the role against
the installed service.

## 5. Run in the foreground

```powershell
p2p-answer run --config "$env:USERPROFILE\.config\p2ptunnel\config.toml"
p2p-offer  run --config "$env:USERPROFILE\.config\p2ptunnel\config.toml"
```

`--config` can be omitted to use that same default path. `Ctrl+C` requests a graceful
shutdown. `--broker-url` overrides the broker URL, as on other platforms.

### Firewall and same-network peers

- **Windows Firewall.** A new `.exe` gets an "Allow access" prompt the first time it listens.
  If the process was started somewhere you can't see the prompt (a hidden window, a remote
  session), no rule is created and unsolicited inbound UDP is dropped on a Public network.
  Across the internet this usually doesn't matter, because replies to your own outbound ICE
  checks are allowed back in. It matters when the only working path is the *other* peer
  reaching you first. To allow it explicitly, from an elevated PowerShell:

  ```powershell
  New-NetFirewallRule -DisplayName "p2p-offer" -Direction Inbound -Action Allow -Protocol UDP -Program "<full path to p2p-offer.exe>" -Profile Any -RemoteAddress LocalSubnet
  ```

- **Both peers on the same LAN.** ICE only offers the host addresses each side advertises,
  plus the shared public address. If the other peer runs in a Docker bridge network it
  advertises only its container address (such as `172.17.0.x`), which you cannot reach, and
  most routers do not loop the public address back to the LAN (no hairpin NAT). The tunnel
  then fails with `ice_failed`. Run the container with host networking, give it a LAN address
  (macvlan/ipvlan), or test from a different network, such as a phone hotspot.

## Security model

The wire protocol and identity format are identical on every platform. Only how the
private key's on-disk protection is expressed differs: POSIX mode bits on Unix, NTFS ACLs
on Windows.

`security.refuse_world_readable_identity` and `security.refuse_world_writable_paths` are
checked at startup on every platform, and the config schema refuses to load if you turn
them off.

- **On Unix**, `identity` must be mode `0600` or stricter, and configured paths must not be
  world-writable.
- **On Windows**, the daemon reads each path's owner and ACL. `identity` is refused if any
  ACE grants read or write access to anyone other than its owner, `SYSTEM`,
  `Administrators`, the account the daemon runs as, or the service account
  `NT SERVICE\p2ptunnel` (always accepted, so an elevated `p2pctl check-config` still
  passes on the service's own files). The other configured paths, and the nearest existing parent of a path that
  doesn't exist yet, are refused if anyone else can write to them. Inherited ACEs count.
  So does a file owned by a broad group such as `Users`, which is refused because its owner
  could rewrite the ACL. Any error while reading the ACL is a refusal, never a pass. The
  rules are in [TODO P2-004](WEBRTC_TUNNEL_WINDOWS_SUPPORT_TODO.md).

That makes the ACL on the identity's directory what decides whether the daemon starts.
It is set once, at install time:

- **Service tree, `%ProgramData%\p2ptunnel`.** `install.ps1` removes inheritance from
  `%ProgramData%` (which would otherwise let `BUILTIN\Users` read) and grants only
  `NT AUTHORITY\SYSTEM` (full), `BUILTIN\Administrators` (full) and `NT SERVICE\p2ptunnel`
  (read and execute; modify on `<role>\state\` only). Every grant is inherited by
  files created later, so an identity copied in is protected when it is created.
  Verification commands are in the
  [installer README](../packaging/windows/README.md#verifying-an-installation).
- **Interactive tree, `%USERPROFILE%\.config\p2ptunnel`.** Your profile's default ACL already
  limits it to you, SYSTEM and Administrators. Nothing more is done.

**What this means for a hand-made config directory.** A directory you create yourself
outside your profile gets whatever ACL it inherits, and the daemon will **refuse to start**
if that ACL is loose. A new folder under `%ProgramData%` usually lets every local user read
it, and one directly under `C:\` gives Authenticated Users modify access. Expect an error
naming the path and the SID that has too much access. Either use the installer's tree, or
lock the directory down before you put the key in it, for example:

```powershell
$me = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
icacls C:\p2p /inheritance:r /grant:r "*S-1-5-18:(OI)(CI)F" "*S-1-5-32-544:(OI)(CI)F" "*${me}:(OI)(CI)F"
```

Then check with `icacls C:\p2p`. Create the identity inside the directory afterwards, or
copy it in (a copy inherits the directory's ACL). **A moved file keeps the ACL it had**; if
you moved one by mistake, run `icacls <file> /reset`.

Administrators can always read the identity, and so can anything running as SYSTEM or as
the service account. The ACL protects the key from other ordinary local users, not from an
administrator.

## 6. Install the service

The scripts are `packaging\windows\install.ps1` and `uninstall.ps1`. Full details,
including every step and the verification checklist, are in
[`packaging/windows/README.md`](../packaging/windows/README.md). They need Windows
PowerShell 5.1 or later, 64-bit, **elevated** ("Run as administrator"), and they stop with
a clear message otherwise.

```powershell
# from the repository root, in an elevated Windows PowerShell
powershell -NoProfile -ExecutionPolicy Bypass -File packaging\windows\install.ps1 `
    -Role answer -BinaryPath target\release\p2p-service-windows.exe
```

| Parameter | Default | Meaning |
|---|---|---|
| `-Role` | (required) | `answer` or `offer`. Fixed for this installation. |
| `-InstallDir` | `%ProgramFiles%\p2ptunnel` | Where `p2p-service-windows.exe` lives. Refused if a non-administrator could write to it. |
| `-BinaryPath` | none | A built `p2p-service-windows.exe` to copy into `-InstallDir`. Without it, the binary must already be there. |
| `-ConfigTemplate` | `docs\examples\<role>-config.toml` | Seeds `config.toml` only if none exists yet. |

At the `sc.exe` level this registers the service `p2ptunnel` with
`binPath= "<InstallDir>\p2p-service-windows.exe" --role <role>`, `obj= "NT SERVICE\p2ptunnel"`
and `start= delayed-auto`, sets failure actions (restart after 5 s, 5 s, 30 s), applies the
ACL above, creates `<role>\`, `<role>\state\` and `<role>\state\log\`, and seeds
`<role>\config.toml` with the logging settings shown earlier. It never overwrites an
existing `config.toml`. It **fails, changing nothing, if a service named `p2ptunnel`
already exists**, and never changes the role of an existing service.

**It does not start the service.** The seeded config still holds example values, so a start
at this point would fail. Finish setup first:

1. Edit `%ProgramData%\p2ptunnel\<role>\config.toml`: `[node] peer_id`, `[broker]` `url`,
   `client_id`, `username` and `password_file`, the `[[forwards]]`, and for `offer`,
   `[peer] remote_peer_id`.
2. Run `p2pctl keygen <peer_id> --dir "$env:ProgramData\p2ptunnel\<role>"`, so the key is
   created in place under the directory's ACL ([step 2](#2-generate-an-identity)).
3. Add the peer's public key with `add-authorized-key --authorized-keys`
   ([step 3](#3-authorize-the-peer)) and
   put the broker's CA certificate in `ca.crt`, both in the same directory.
4. Run `p2pctl check-config --config "$env:ProgramData\p2ptunnel\<role>\config.toml"` from an
   elevated shell.

## 7. Start, stop, status

All of these need an elevated prompt. Use `sc.exe`, not `sc`: in PowerShell `sc` is an
alias for `Set-Content`.

```powershell
sc.exe start p2ptunnel
sc.exe stop p2ptunnel
sc.exe query p2ptunnel
sc.exe qc p2ptunnel          # configuration: account and binPath, including --role
p2pctl status --config "$env:ProgramData\p2ptunnel\answer\config.toml"
```

There is no `sc.exe restart`; stop, then start. The service also appears in `services.msc`.

`sc.exe stop` makes the SCM send `SERVICE_CONTROL_STOP`. The wrapper reports `STOP_PENDING`
with a checkpoint that advances every few seconds while the daemon drains its active
session and listeners, then reports `STOPPED`. If the daemon has not finished 30 seconds
after the stop request, the service gives up and reports a failed stop (exit code 5
below). This matches `TimeoutStopSec=30s` in the shipped systemd units.

`p2pctl status` reads the daemon's `status.json` and prints the peer id, role, MQTT state
and active sessions. A cleanly stopped daemon leaves a final `closed` status.

### Exit codes

When the service stops on its own, or fails to stop cleanly, it reports a
service-specific exit code. `sc.exe query p2ptunnel` shows it as `SERVICE_EXIT_CODE`, with
`WIN32_EXIT_CODE` set to `1066` (`ERROR_SERVICE_SPECIFIC_ERROR`). A clean stop reports 0.

| Code | Phase | Typical causes |
|---|---|---|
| 1 | Config | `config.toml` missing or unparseable (including the backslash error below); validation failure; `[node].role` does not match the installed `--role`; runtime directories could not be created. |
| 2 | Logging | The logging setup failed, for example `log_file` cannot be opened. |
| 3 | Identity or keys | `identity` or `authorized_keys` missing or unreadable; the identity's `peer_id` does not match `[node].peer_id`. |
| 4 | Daemon error | The daemon returned an error after startup; also failure to start the async runtime or to report status to the SCM. |
| 5 | Stop timeout | Sessions did not drain within 30 s of the stop request. |

**Errors before logging starts go to `startup-error.log`.** The config is read and
validated before logging is initialised, so failures with codes 1 and 2 can't reach the
log file. The wrapper appends them to
`%ProgramData%\p2ptunnel\<role>\state\startup-error.log` instead, one line per failure with
a Unix timestamp, the exit code and the message. That write is best-effort: if it fails
too, run `p2pctl check-config` against the same config from an elevated shell. Codes 3 to 5
happen after logging is up, so their reason is in the normal log file. There is no Event
Log integration.

## 8. Logs

```powershell
Get-Content "$env:ProgramData\p2ptunnel\answer\state\log\p2ptunnel.log" -Tail 50
Get-Content "$env:ProgramData\p2ptunnel\answer\state\log\p2ptunnel.log" -Wait
```

Only administrators can read the file. Near the top of each run you will see a
`p2ptunnel service starting` line. A refused identity or path shows up as exit code 3 or
1, with the offending SID in the message; see [Security model](#security-model).
Service start, stop and failure events also appear in the Windows `System` event log
under the source `Service Control Manager`.

`log_rotation = "none"` means the file grows without bound.

## 9. Uninstall

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File packaging\windows\uninstall.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File packaging\windows\uninstall.ps1 -RemoveData
```

It stops the service (waiting up to 60 s), then runs `sc.exe delete p2ptunnel`. The virtual
account exists only while the service does, so it goes with it.

- By default `%ProgramData%\p2ptunnel` is **kept**, with its ACL, so config and identity
  survive a reinstall and stay protected meanwhile.
- `-RemoveData` deletes that whole tree: config, identity, state and logs for every role.
- `-Force` deletes a service named `p2ptunnel` that was not installed by `install.ps1`
  (its binPath is not `p2p-service-windows.exe`); without it the script refuses.
- The binary in `-InstallDir` is left in place.

If something holds a handle to the service (`services.msc`, Event Viewer), the SCM only
marks it for deletion, and `install.ps1` refuses to run until it is gone. Close those tools
or reboot.

## Troubleshooting

**The service starts and immediately stops.** Run `sc.exe query p2ptunnel` and look at
`SERVICE_EXIT_CODE`, then use the [table above](#exit-codes). The seeded config contains
example values, so a first start before editing it fails with code 1 or 3.

**The service is running but there is no log output.** Check that the config sets
`file_logging = true` and `stdout_logging = false`, and that `log_file` is under
`<role>\state\`, the only place the service account can write. `install.ps1` warns if an
existing config would log to stdout only.

**Git Bash and `HOME`.** Git Bash (and MSYS/Cygwin) sets `HOME` to a unix-shaped path such
as `/c/Users/you`, which is not a valid Windows path to a native program. The binaries
therefore resolve your home from `USERPROFILE` first and fall back to `HOME` only if
`USERPROFILE` is unset. This applies to the default config directory and to a leading `~/`
in config paths. So the tools find the same directory from Git Bash and native PowerShell.
If you have overridden `HOME` or `USERPROFILE` to point somewhere else, the tools follow
`USERPROFILE`, not what your shell's `~` expands to. When in doubt, pass `--config` with
an explicit Windows path. Older builds that predate this fix read only `HOME`, which native
PowerShell and cmd do not set.

**Running `p2p-service-windows.exe` by hand.** It is meant to be started by the SCM. From
a console it exits with status 1 and tells you to use `p2p-answer` or `p2p-offer` instead:

```text
Error: p2p-service-windows must be started by the Service Control Manager
(sc.exe start p2ptunnel); to run a daemon in this console, use p2p-answer or p2p-offer instead
```

(`--role` is required; without it you get a usage error first.) To debug a service
config in a console, run the daemon directly with the same file:
`p2p-answer run --config "$env:ProgramData\p2ptunnel\answer\config.toml"` from an
elevated shell. That runs it as you rather than as `NT SERVICE\p2ptunnel`, so it proves the
config but not the service account's permissions.

**`invalid unicode 8-digit hex code` when loading the config.** A backslash in a
double-quoted TOML string, typically `"C:\Users\..."`, where `\U` starts an 8-digit unicode
escape. Switch the value to a single-quoted literal string or forward slashes (see
[Backslashes in TOML paths](#backslashes-in-toml-paths)). Other sequences either fail with a
similar escape error or silently turn into control characters (`\t`, `\n`), so fix every
Windows path in the file, not only the one named in the error.

**`install.ps1` says `p2ptunnel` already exists.** Only one service can exist. Uninstall
first (its message shows the installed role), then install again.

**Access denied reading `%ProgramData%\p2ptunnel` or `identity`.** Working as intended for a
standard user. Use an elevated shell.

## Current limitations

- **Verified by hand (2026-10-17):** a foreground `p2p-offer.exe` completed a tunnel to a
  Linux `p2p-answer` over `mqtts://broker.emqx.io:8883` from a different network (a phone
  hotspot), using the system certificate store (`ca_file = ""`) (TODO P1-004, P1-005). That
  was STUN only: symmetric NAT and carrier-grade NAT, where there is no TURN fallback, are
  untested. The answer role on Windows has not been checked against a remote offer peer, and
  the scripted TLS broker test (P1-001) has not been run.
- **The service has been through most of its acceptance list.** Elevated runs on
  2026-10-17 installed it, ran tunnels as `NT SERVICE\p2ptunnel` in the offer role (with
  the scripts) and in the answer role (installed with the MSI), stopped it cleanly (exit
  code 0, no listener left) and uninstalled it with no leftovers. Still open (TODO P0-007,
  P0-008): surviving logoff and running with no user logged in, and an actual read attempt
  on the identity by another account (the ACL was checked with `icacls`). The installer
  README lists commands for each.
- **Runtime ACL checks rely on an old crate.** They use `windows-permissions` 0.2.4, pinned
  exactly; it has been unmaintained since 2021. Any panic or error inside it is treated as a
  refusal.
- **The MSI is unsigned and new.** There is an MSI (P2-001, built from
  `packaging/windows/wix` in the repository; see its README) that installs the binaries and
  runs the same `install.ps1` as the `.zip`, with `msiexec /i p2ptunnel.msi ROLE=answer|offer
  /qn`. It has been tested locally for install, upgrade, uninstall and repair with the offer
  role, but it is not code-signed, so SmartScreen will warn. Tagged releases are set up to
  publish a Windows `.zip` and this `.msi` (P2-001, P2-002), but that release job has not run
  on a tag yet. Do not install both the `.zip` and the `.msi` on one machine; the MSI refuses
  when a `p2ptunnel` service it did not install already exists.
- **The Windows shutdown lifecycle test needs an elevated run.** The Unix signal test does
  not run on Windows. The Service Control Manager test (P1-002) passes when run from an
  elevated shell and skips otherwise; it has not yet run in CI.
- **Early startup failures go to `<role>\state\startup-error.log`**, not to the Event Log.
- **Automatic restart after a failure is not yet observed on a real install.** By default
  Windows runs restart-on-failure actions only when the service process dies without
  reporting `STOPPED`. The wrapper reports every failure as `STOPPED` with a service-specific
  exit code, so the installer also sets `sc.exe failureflag p2ptunnel 1`, which makes those
  failures trigger the actions too. Confirm it on your machine with
  `sc.exe qfailureflag p2ptunnel` and by forcing a failure.
- **One role per machine as a service.**
