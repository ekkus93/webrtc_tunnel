# Windows service installer

Installs p2ptunnel as a Windows service, per
[`docs/WEBRTC_TUNNEL_WINDOWS_SUPPORT_SPEC.md`](../../docs/WEBRTC_TUNNEL_WINDOWS_SUPPORT_SPEC.md)
§2.6, §2.7, §6.3 and §6.4 (TODO task P0-008).

| File | What it is |
|---|---|
| `install.ps1` | Registers the service and locks down `%ProgramData%\p2ptunnel`. |
| `uninstall.ps1` | Stops and deletes the service. Data is kept unless you pass `-RemoveData`. |
| `p2ptunnel-common.ps1` | Shared constants and functions, dot-sourced by both scripts. |
| `../../scripts/check-windows-service-scripts.ps1` | Static check, no admin needed (see [Static check](#static-check)). |

Both scripts need **Windows PowerShell 5.1 or later, 64-bit, elevated** ("Run as
administrator"). They stop with a clear message otherwise.

## The layout

There is exactly **one** service per machine:

```text
Service name   p2ptunnel
Account        NT SERVICE\p2ptunnel        (virtual account, no password)
binPath        "<InstallDir>\p2p-service-windows.exe" --role <answer|offer>
Start type     Automatic (delayed start), restart on failure after 5 s / 5 s / 30 s
Config         %ProgramData%\p2ptunnel\<role>\config.toml
Identity       %ProgramData%\p2ptunnel\<role>\identity
State          %ProgramData%\p2ptunnel\<role>\state\
Logs           %ProgramData%\p2ptunnel\<role>\state\log\p2ptunnel.log
```

The role is fixed when you install. `p2p-service-windows.exe` gets only `--role`, and
finds its config under `%ProgramData%\p2ptunnel\<role>\` by itself. To switch a machine
from one role to the other, uninstall and install again. The other role's directory, if
there is one, is kept and is not reused.

## Install

```powershell
# from the repository root, in an elevated Windows PowerShell
powershell -NoProfile -ExecutionPolicy Bypass -File packaging\windows\install.ps1 `
    -Role answer -BinaryPath target\release\p2p-service-windows.exe
```

| Parameter | Default | Meaning |
|---|---|---|
| `-Role` | (required) | `answer` or `offer`. |
| `-InstallDir` | `%ProgramFiles%\p2ptunnel` | Where `p2p-service-windows.exe` lives. Must not be writable by non-administrators. |
| `-BinaryPath` | none | A built `p2p-service-windows.exe` to copy into `-InstallDir`. Without it the binary must already be there. |
| `-ConfigTemplate` | `docs\examples\<role>-config.toml` | Used only if `config.toml` does not exist yet. |

What it does, in order:

1. **Pre-flight, read-only.** Checks for elevation and a 64-bit shell. **Fails if a service
   named `p2ptunnel` already exists**, saying which role is installed. It never changes
   the role of an existing service. Refuses an `-InstallDir` that a non-administrator could
   write to or replace through its parent, because the service would then run that user's
   code. Renders the seeded config, so a bad template fails here. Up to this point nothing
   on the machine has changed.
2. **Copies the binary** if `-BinaryPath` was given.
3. **Creates the service** with
   `sc.exe create p2ptunnel binPath= "\"...\p2p-service-windows.exe\" --role <role>" obj= "NT SERVICE\p2ptunnel" start= delayed-auto`.
   Creating the service is what brings the virtual account into existence. Then it runs
   `sc.exe sidtype p2ptunnel unrestricted`, sets a description and the failure actions,
   and confirms that `NT SERVICE\p2ptunnel` resolves to the SID it computed
   (`S-1-5-80-1253224334-2237551498-3137952417-4040730996-425943683`, the same value
   `sc.exe showsid p2ptunnel` prints).
4. **Locks down `%ProgramData%\p2ptunnel`** before creating anything under it:
   ```text
   icacls "%ProgramData%\p2ptunnel" /inheritance:r /grant:r
          "*S-1-5-18:(OI)(CI)F"           NT AUTHORITY\SYSTEM     full control
          "*S-1-5-32-544:(OI)(CI)F"       BUILTIN\Administrators  full control
          "*S-1-5-80-...:(OI)(CI)RX"      NT SERVICE\p2ptunnel    read & execute
   icacls "%ProgramData%\p2ptunnel" /setowner *S-1-5-32-544
   ```
   `/inheritance:r` cuts off the `BUILTIN\Users` access that `%ProgramData%` would
   otherwise pass down. Every grant is `(OI)(CI)`, so everything created below, including
   an identity copied in later, is protected **when it is created**. All grants use SIDs,
   so this works on non-English Windows.
5. **Audits what was already there.** If the directory existed before (for example from an
   earlier install whose data was kept), the script refuses to continue if anything in it
   is a junction or symlink, or is owned by someone other than SYSTEM, Administrators, the
   service or you. Any user can pre-create a directory under `%ProgramData%`, and an owner
   can always rewrite the DACL. Then it runs `icacls <child> /reset /T` on each existing
   child, so everything below the root carries only inherited ACEs.
6. **Creates `<role>\`, `<role>\state\` and `<role>\state\log\`**, and grants the service
   `(OI)(CI)M` on `<role>\state\` only. That is where the daemon writes its status file and
   log. Everything else stays read-only to the service.
7. **Seeds `<role>\config.toml`** if there isn't one. The template's placeholders become
   the paths above, written as TOML single-quoted literal strings (`'C:\ProgramData\...'`)
   so the backslashes need no escaping. It also sets
   `file_logging = true`, `stdout_logging = false` and
   `log_file = '...\<role>\state\log\p2ptunnel.log'`, because a service has no console
   (spec §6.3). An existing `config.toml` is never overwritten. The script only warns if it
   would log to stdout alone.
8. **Verifies** the result with `Get-Acl`. The root must hold exactly the three ACEs above
   and be protected. `state\` must hold exactly one explicit ACE. Everything else must hold
   none. The owner of the root must be Administrators. The script then prints
   `icacls` and the root's SDDL.

If any step after 3 fails, the script deletes the half-created service and leaves
`%ProgramData%\p2ptunnel` as it is.

**The script does not start the service.** The seeded config still has the example values.
The script prints what is left to do:

1. In `config.toml`, replace `[node] peer_id`, `[broker]` `url`, `client_id`, `username`
   and `password_file`, the `[[forwards]]`, and for `offer`, `[peer] remote_peer_id`.
2. **Identity.** `p2pctl keygen <peer_id>` has no output-directory option. It writes to
   *your* profile, `%USERPROFILE%\.config\p2ptunnel\identity`. **Copy** that file (do not
   move it) to `%ProgramData%\p2ptunnel\<role>\identity`, then delete the copy in your
   profile. A copied file is created fresh in the directory and inherits its DACL. A moved
   file keeps the ACL it had before. If you moved it by mistake, run
   `icacls "%ProgramData%\p2ptunnel\<role>\identity" /reset`.
3. Put the peer's public key in `authorized_keys`, and the broker's CA certificate in
   `ca.crt`, in the same directory.
4. Run `p2pctl check-config --config "%ProgramData%\p2ptunnel\<role>\config.toml"`.
5. Run `sc.exe start p2ptunnel`.

## Uninstall

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File packaging\windows\uninstall.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File packaging\windows\uninstall.ps1 -RemoveData
```

- Stops the service and waits up to 60 seconds for it to stop. Then it runs
  `sc.exe delete p2ptunnel`. The virtual account `NT SERVICE\p2ptunnel` exists only while
  the service does, so it is removed with it.
- It refuses to delete a `p2ptunnel` service whose binPath is not `p2p-service-windows.exe`
  unless you pass `-Force`.
- **By default it keeps `%ProgramData%\p2ptunnel`** (configs, identities, logs) with its
  restrictive DACL, and says so. The kept ACE for the service now names a deleted account.
  A later `install.ps1` gives the same name the same SID, so it simply applies again.
- `-RemoveData` deletes `%ProgramData%\p2ptunnel` and everything in it, for every role.
  That also removes the only ACL the installer created. It refuses if the tree contains a
  junction or symlink.
- It leaves the binary in `-InstallDir`. Delete it yourself if you no longer need it.

If something holds a handle to the service (services.msc, Event Viewer), the SCM only
marks it for deletion. `install.ps1` refuses to run until it is gone. Close those tools,
or reboot.

## Verifying an installation

Run these from an elevated prompt unless noted otherwise.

```powershell
# Service is registered with the right account and binPath
sc.exe qc p2ptunnel                 # SERVICE_START_NAME : NT SERVICE\p2ptunnel
                                    # BINARY_PATH_NAME   : "...\p2p-service-windows.exe" --role answer
sc.exe showsid p2ptunnel            # S-1-5-80-1253224334-...-425943683

# DACL on the tree: no (I) on the root's ACEs, no BUILTIN\Users anywhere
icacls "$env:ProgramData\p2ptunnel"
icacls "$env:ProgramData\p2ptunnel" /T
(Get-Acl "$env:ProgramData\p2ptunnel").Sddl
#   expected: O:BA ... D:PAI(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x1200a9;;;S-1-5-80-...)
#   (D:P = protected, i.e. inheritance from %ProgramData% disabled. The ACE order may differ.)

# The identity inherits, and has no explicit ACEs of its own: every line ends in (I)
icacls "$env:ProgramData\p2ptunnel\answer\identity"
```

`icacls` prints localized account names. `Get-Acl ... .Sddl` uses locale-independent
aliases (`SY`, `BA`) and SIDs, so compare that instead on non-English systems.

**A standard user cannot read the identity.** From a **non-elevated** prompt, signed in as
a standard (non-admin) user:

```powershell
Get-Content "$env:ProgramData\p2ptunnel\answer\identity"
#   expected: Access to the path ... is denied.
Get-ChildItem "$env:ProgramData\p2ptunnel"
#   expected: Access to the path ... is denied.
```

If you have no second account, `runas /user:<standard-user> powershell` opens a prompt as
one.

**The service runs under the virtual account and logs to its file:**

```powershell
sc.exe start p2ptunnel
sc.exe query p2ptunnel              # STATE : 4  RUNNING
Get-CimInstance Win32_Service -Filter "Name='p2ptunnel'" | Select-Object Name, State, StartName, ProcessId
Get-Content "$env:ProgramData\p2ptunnel\answer\state\log\p2ptunnel.log" -Tail 20
sc.exe stop p2ptunnel               # graceful stop, see P0-007
```

**It runs with nobody logged in.** Sign out every interactive user (or reboot and do not
sign in). Then, from another machine, connect through the tunnel. Sign back in and confirm
the service never stopped:

```powershell
Get-WinEvent -FilterHashtable @{ LogName = 'System'; ProviderName = 'Service Control Manager' } -MaxEvents 50 |
    Where-Object { $_.Message -match 'p2ptunnel' }
```

**Uninstall leaves nothing behind:**

```powershell
.\packaging\windows\uninstall.ps1 -RemoveData
sc.exe query p2ptunnel              # exit code 1060: the service does not exist
Test-Path "$env:ProgramData\p2ptunnel"  # False
```

## Static check

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-windows-service-scripts.ps1
```

This is the Windows counterpart of `scripts/check-systemd-units.sh` and
`scripts/check-launchd-plists.sh`. It needs no admin rights and changes nothing. It parses
every script with the PowerShell parser, rejects PowerShell 7-only syntax and non-ASCII
bytes, checks that elevation is asserted before any `sc.exe`/`icacls.exe` call, and then
dot-sources `p2ptunnel-common.ps1` to test its pure functions directly. That covers the
`sc.exe create` command line (`p2ptunnel`, `NT SERVICE\p2ptunnel`, `--role`, no
`--config`), the computed service SID against `sc.exe showsid`, the exact DACL, the
SID-only `icacls` grants, and the config seeded from both example templates
(`stdout_logging = false`, `file_logging = true`, `log_file` under the role's
`state\log`, literal-string paths).

It does not run the installer. That needs an elevated shell and the P0-007 binary.
