<#
.SYNOPSIS
Installs the WiX Toolset CLI and its Util extension, pinned, for build-msi.ps1.

.DESCRIPTION
WiX 5.0.2 is pinned on purpose: it is MS-RL and needs no EULA, whereas WiX 6 and later fall
under the Open Source Maintenance Fee EULA, which build-msi.ps1 never accepts for you. The
Util extension must be the same version as the tool.

Needs the .NET SDK (6.0 or later) on PATH. Installs `wix` as a global dotnet tool (a small
download from nuget.org), puts the tools directory on PATH for the rest of this session and,
under GitHub Actions, for later steps (GITHUB_PATH).

.PARAMETER Version
WiX tool and extension version. Default: 5.0.2.
#>
[CmdletBinding()]
param([string]$Version = '5.0.2')

$ErrorActionPreference = 'Stop'

& dotnet tool update --global wix --version $Version
if ($LASTEXITCODE -ne 0) { throw "installing the wix tool $Version failed ($LASTEXITCODE)" }

$tools = Join-Path $env:USERPROFILE '.dotnet\tools'
$env:PATH = "$tools;$env:PATH"
if ($env:GITHUB_PATH) { Add-Content -LiteralPath $env:GITHUB_PATH -Value $tools }

& wix extension add -g "WixToolset.Util.wixext/$Version"
if ($LASTEXITCODE -ne 0) { throw "adding the WiX Util extension $Version failed ($LASTEXITCODE)" }

Write-Host "[install-wix] wix $((& wix --version | Out-String).Trim())"
