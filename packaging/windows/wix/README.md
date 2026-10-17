# Windows MSI package

An MSI that installs p2ptunnel and registers the Windows service. This is TODO task P2-001
in [`docs/WEBRTC_TUNNEL_WINDOWS_SUPPORT_TODO.md`](../../../docs/WEBRTC_TUNNEL_WINDOWS_SUPPORT_TODO.md).

| File | What it is |
|---|---|
| `Product.wxs` | The WiX v4+ package definition. |
| `build-msi.ps1` | Builds the MSI from the release binaries, and optionally signs it. |

**The MSI does not implement the service or ACL setup itself.** It copies files, then runs
the reviewed P0-008 scripts, [`../install.ps1`](../install.ps1) and
[`../uninstall.ps1`](../uninstall.ps1), as deferred custom actions that run elevated as
LocalSystem. Everything [`../README.md`](../README.md) says about the service, the
`NT SERVICE\p2ptunnel` account and the `%ProgramData%\p2ptunnel` DACL applies unchanged.
There is one implementation, and the MSI uses it.

## What gets installed

```text
%ProgramFiles%\p2ptunnel\
    p2p-service-windows.exe  p2p-answer.exe  p2p-offer.exe  p2pctl.exe
    README.md  LICENSE
    packaging\windows\   install.ps1  uninstall.ps1  p2ptunnel-common.ps1  README.md
    docs\                WINDOWS.md
    docs\examples\       answer-config.toml  offer-config.toml
```

This is the same layout as the release `.zip` (P2-002). `install.ps1` dot-sources
`p2ptunnel-common.ps1` from its own directory, and its default config template is
`..\..\docs\examples\<role>-config.toml`.

Then the install custom action runs:

```text
powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass
    -File "<INSTALLFOLDER>\packaging\windows\install.ps1" -Role <ROLE> -InstallDir "<INSTALLFOLDER>"
```

That registers the `p2ptunnel` service as `NT SERVICE\p2ptunnel`, locks down
`%ProgramData%\p2ptunnel`, and seeds `<role>\config.toml`. **It does not start the
service.** The config still holds the example values. Finish the steps that
`install.ps1` prints. They are in the MSI log, and also in
[`../README.md`](../README.md#install).

## Build

You need the release binaries and the WiX Toolset CLI, v4 or later. Install the CLI and its
Util extension at the **same** version:

```powershell
dotnet tool install --global wix --version <version>
wix extension add -g WixToolset.Util.wixext/<version>
```

> **Licence.** This package was built and tested with **WiX 5.0.2**
> (`dotnet tool install --global wix --version 5.0.2`), which is MS-RL and needs no EULA
> or fee. WiX v6 and later are under the Open Source Maintenance Fee (OSMF) EULA, and v7
> refuses to run until you accept it ([docs.firegiant.com/wix/osmf](https://docs.firegiant.com/wix/osmf/)).
> `build-msi.ps1` never accepts it for you. If you decide to accept it, pass
> `-AcceptEula wix7`, which is forwarded to `wix build -acceptEula`. WiX 6 and 7 have not
> been tried with this package.

Then build, from the repository root, in Windows PowerShell 5.1 or later:

```powershell
cargo build --release --workspace
powershell -NoProfile -ExecutionPolicy Bypass -File packaging\windows\wix\build-msi.ps1 `
    -BinDir target\release -OutputPath dist\p2ptunnel.msi
```

| Parameter | Default | Meaning |
|---|---|---|
| `-BinDir` | (required) | Holds `p2p-service-windows.exe`, `p2p-answer.exe`, `p2p-offer.exe`, `p2pctl.exe`. |
| `-OutputPath` | (required) | The `.msi` to write. |
| `-Version` | `[workspace.package] version` from `Cargo.toml` | The MSI ProductVersion, as `major.minor.patch`. Any pre-release suffix is dropped. |
| `-AcceptEula` | none | Forwarded to `wix build -acceptEula`. |
| `-SignCert` | none | A certificate thumbprint from the store (preferred), or a `.pfx` path. |
| `-SignCertPassword` | none | A `SecureString` for a `.pfx` `-SignCert`. |
| `-TimestampUrl` | none | An RFC 3161 timestamp server, passed to signtool `/tr`. |

The script fails with an install hint if `wix` is not on `PATH`. The scripts and example
configs come from this repository, located relative to the script.

### Signing

Without `-SignCert` the MSI and the binaries inside it are **unsigned**. Unsigned builds
are for testing only. SmartScreen warns on them, and nothing proves where they came from.

With `-SignCert`, the script finds `signtool.exe` on `PATH` or in the newest Windows 10/11
SDK under `%ProgramFiles(x86)%\Windows Kits\10\bin\<version>\x64`. It signs a staged
copy of the four binaries, so `-BinDir` is never modified. It then builds from that copy,
signs the MSI, and runs `signtool verify /pa` on each file. Always pass `-TimestampUrl`
when you sign, or the signatures stop validating when the certificate expires. A `.pfx`
password reaches signtool on its command line, where other processes on the machine can
read it. Prefer a thumbprint, with the certificate in the store or on a hardware token.

## Install

Elevated, from a command prompt or PowerShell:

```bat
msiexec /i p2ptunnel.msi ROLE=answer /qn /l*v install.log
```

| Property | Values | Meaning |
|---|---|---|
| `ROLE` | `answer` or `offer` | **Required. There is no default.** It is passed to `install.ps1 -Role`, and fixed for the life of the install. Validated case-insensitively. Anything else stops the install before any change is made. |
| `INSTALLFOLDER` | a path | Defaults to `%ProgramFiles%\p2ptunnel`. `install.ps1` refuses a folder that non-administrators can write to, or that they can replace through its parent. |

The install **fails before changing anything** in either of these cases:

- `ROLE` is missing or invalid.
- A service named `p2ptunnel` already exists and this package did not install it, for
  example one from the `.zip` and `install.ps1`. Remove that one first with
  `packaging\windows\uninstall.ps1`.

If `install.ps1` fails, it deletes the service it half-created and the MSI rolls back the
files. If a later MSI action fails after `install.ps1` succeeded, a rollback action runs
`uninstall.ps1`. Either way `%ProgramData%\p2ptunnel` is left as it is, as `install.ps1`
itself does on failure. The reason is in `install.log`: search it for `[p2ptunnel]`, or
for `WixQuietExec`.

`ROLE` is remembered in `HKLM\SOFTWARE\p2ptunnel\Role`. A repair, or an upgrade run without
`ROLE`, reuses it. A `ROLE` on the command line always wins.

Double-clicking the `.msi` runs the same install, but it cannot set `ROLE`, so it stops
with the "ROLE is required" message. Use `msiexec` as above.

## Uninstall

```bat
msiexec /x p2ptunnel.msi /qn /l*v uninstall.log
msiexec /x p2ptunnel.msi REMOVEDATA=1 /qn /l*v uninstall.log
```

You can also uninstall from Settings > Apps, which keeps the data.

The uninstall custom action runs `uninstall.ps1` before any files are removed. It stops the
service, waiting up to 60 s for it to drain, and deletes it. The virtual account goes with
the service.

| Property | Values | Meaning |
|---|---|---|
| `REMOVEDATA` | `1` | Also runs `uninstall.ps1 -RemoveData`. That **deletes `%ProgramData%\p2ptunnel`**: every role's config, identity, state and logs. |
| | `0` / unset | Default. Keeps `%ProgramData%\p2ptunnel` with its restrictive DACL, the same default as `uninstall.ps1`. |

`uninstall.ps1` can fail. The service may not stop within 60 s, or the `p2ptunnel` service
may no longer point at `p2p-service-windows.exe`. When that happens, the MSI uninstall fails
and leaves everything installed. Fix the cause, or run `packaging\windows\uninstall.ps1`
(with `-Force` if needed) from `%ProgramFiles%\p2ptunnel` by hand, then run `msiexec /x`
again. Once the service is gone, `uninstall.ps1` reports "not installed" and succeeds.

If the uninstall is rolled back after `uninstall.ps1` ran, a rollback action runs
`install.ps1 -Role <remembered role>` to register the service again. After
`REMOVEDATA=1`, that seeds a fresh example config. The old data cannot be restored.

## Upgrade

```bat
msiexec /i p2ptunnel-<newer>.msi /qn /l*v upgrade.log
```

This is a major upgrade. The old package is removed completely first. Its `uninstall.ps1`
stops and deletes the service, and keeps `%ProgramData%\p2ptunnel` even if `REMOVEDATA=1`
is passed. Then the new package installs, and `install.ps1` registers the service again
with the remembered role, or with `ROLE` if you pass one. `install.ps1` keeps the existing
`config.toml` and identity.

**After an upgrade the service is stopped.** `install.ps1` never starts it. It starts at
the next boot (delayed auto-start), or you can run `sc.exe start p2ptunnel`. Do not upgrade
over a tunnel you depend on, for example an RDP session forwarded by this service, unless
you can reach the machine some other way.

A rebuild with the same version also upgrades (`AllowSameVersionUpgrades`). Downgrades are
refused.

## Not handled

- **Repair** (`msiexec /f`) restores files and the remembered-role registry value only.
  It does not register the service again. If the service was deleted by hand, uninstall
  the MSI and install it again.
- **Group Policy execution policy.** `-ExecutionPolicy Bypass` does not override an
  execution policy set by Group Policy (`MachinePolicy` / `UserPolicy`). On such machines
  the custom actions fail until the scripts are signed or the policy allows them.
- **No UI.** The MSI has no dialogs. Run it with `msiexec` and the properties above.

## What is verified

Built with WiX 5.0.2 and run elevated on Windows 11 on 2026-10-17, with the `offer` role
unless noted. Everything below passed:

- `wix build` accepts `Product.wxs` (including the `WixQuietExec` reference) and ICE
  validation passes.
- A missing or invalid `ROLE` fails with 1603 before any change; `ROLE=ANSWER` (upper
  case) is accepted.
- Install registers `NT SERVICE\p2ptunnel` (delayed auto, failure flag set), applies the
  `%ProgramData%\p2ptunnel` DACL, and writes the remembered role and the Apps & Features
  entry. `install.ps1` behaves the same under LocalSystem through WixQuietExec as in an
  elevated shell.
- With a real config and identity, the MSI-installed service connected to the broker and
  stopped with exit code 0.
- A major upgrade with a rebuilt MSI and no `ROLE` kept the role, config and identity.
- Uninstall keeps `%ProgramData%\p2ptunnel` by default; `REMOVEDATA=1` removes it; a
  reinstall over kept data keeps the existing `config.toml`.
- A service installed from the zip with `install.ps1` blocks the MSI (1603), and the
  existing service is left untouched.
- Repair restores a deleted file and leaves the service alone.

Not verified yet:

- The rollback actions (a failing `install.ps1` or a later MSI action failing).
- The `answer` role running as an MSI-installed service. Only registration was checked.
- Signing with a real certificate.
- The release job's MSI step on a hosted runner. The `release-artifacts` Windows leg now
  installs WiX 5.0.2 and builds and uploads an unsigned MSI; its commands were run locally,
  but the job only runs on a tag.
- WiX 6 or 7.

Known cosmetic issues: `install.ps1`'s next-steps text goes through MSI log formatting, which
blanks square-bracketed words such as `[node]`; read the steps in `../README.md` instead.
`ROLE` is remembered exactly as typed (`ANSWER`), which later installs accept.
