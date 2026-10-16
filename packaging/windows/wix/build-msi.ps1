<#
.SYNOPSIS
Builds the p2ptunnel MSI (task P2-001) with the WiX Toolset CLI.

.DESCRIPTION
Packages the four release binaries, packaging\windows\*.ps1 and docs\examples\*-config.toml
into an MSI from Product.wxs. The MSI runs the reviewed install.ps1 / uninstall.ps1 as
custom actions; see README.md next to this script.

Needs the `wix` .NET tool (v4 or later) and its Util extension at the same version:

    dotnet tool install --global wix --version <version>
    wix extension add -g WixToolset.Util.wixext/<version>

WiX v6 and later are licensed under the Open Source Maintenance Fee (OSMF) EULA, and v7
refuses to run until it is accepted. This script never accepts it for you: pass
-AcceptEula <id> (for example wix7) yourself once you have decided to accept it.

Without -SignCert the MSI and the binaries in it are UNSIGNED. Unsigned builds are for
testing only.

.PARAMETER BinDir
Directory holding p2p-service-windows.exe, p2p-answer.exe, p2p-offer.exe and p2pctl.exe,
e.g. target\release.

.PARAMETER OutputPath
The .msi file to write.

.PARAMETER Version
MSI ProductVersion, major.minor.patch. Defaults to [workspace.package] version in
Cargo.toml, with any pre-release or build suffix dropped.

.PARAMETER AcceptEula
Passed to `wix build -acceptEula`. Only set this if you accept the WiX OSMF EULA.

.PARAMETER SignCert
Signs the four binaries (before packaging) and then the MSI with signtool. Either the
SHA-1 thumbprint of a code-signing certificate in the certificate store (preferred) or the
path to a .pfx file.

.PARAMETER SignCertPassword
Password for a .pfx -SignCert. It is handed to signtool on its command line, where other
processes on this machine can read it; prefer a certificate-store thumbprint.

.PARAMETER TimestampUrl
RFC 3161 timestamp server for signtool /tr. Without one, signatures stop validating when
the certificate expires.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BinDir,

    [Parameter(Mandatory = $true)]
    [string]$OutputPath,

    [string]$Version,

    [string]$AcceptEula,

    [string]$SignCert,

    [System.Security.SecureString]$SignCertPassword,

    [string]$TimestampUrl
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$BinaryNames = @('p2p-service-windows.exe', 'p2p-answer.exe', 'p2p-offer.exe', 'p2pctl.exe')
$UtilExtension = 'WixToolset.Util.wixext'

function Write-BuildStep {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host "[build-msi] $Message"
}

function Get-FullPathNoTrailingSlash {
    # An absolute path without a trailing backslash: the value ends up inside a quoted
    # native argument, where a trailing backslash would escape the closing quote.
    param([Parameter(Mandatory = $true)][string]$Path)
    $full = [System.IO.Path]::GetFullPath($Path)
    if ($full.Length -gt 3) { $full = $full.TrimEnd('\') }
    return $full
}

function Get-WorkspaceVersion {
    param([Parameter(Mandatory = $true)][string]$CargoToml)
    $text = [System.IO.File]::ReadAllText($CargoToml)
    $section = [regex]::Match($text, '(?ms)^\[workspace\.package\][ \t]*\r?\n(.*?)(?=^\[|\z)')
    if (-not $section.Success) {
        throw "No [workspace.package] section in $CargoToml; pass -Version."
    }
    $match = [regex]::Match($section.Groups[1].Value, '(?m)^[ \t]*version[ \t]*=[ \t]*"([^"]+)"')
    if (-not $match.Success) {
        throw "No version in [workspace.package] of $CargoToml; pass -Version."
    }
    # 1.2.3-rc.1+abc -> 1.2.3. MSI versions are numeric only.
    return ($match.Groups[1].Value -split '[-+]')[0]
}

function Assert-MsiVersion {
    param([Parameter(Mandatory = $true)][string]$Value)
    $match = [regex]::Match($Value, '^(\d+)\.(\d+)\.(\d+)$')
    if (-not $match.Success) {
        throw "Version must be major.minor.patch (got '$Value')."
    }
    # Windows Installer limits: major and minor < 256, build < 65536.
    if ([int]$match.Groups[1].Value -gt 255 -or [int]$match.Groups[2].Value -gt 255 -or
        [long]$match.Groups[3].Value -gt 65535) {
        throw "Version $Value is out of range for an MSI ProductVersion (255.255.65535 at most)."
    }
}

function Find-SignTool {
    $command = Get-Command 'signtool.exe' -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    $kits = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits\10\bin'
    if (Test-Path -LiteralPath $kits) {
        $candidates = @(Get-ChildItem -LiteralPath $kits -Directory |
                Where-Object { $_.Name -match '^\d+\.\d+\.\d+\.\d+$' } |
                Sort-Object { [version]$_.Name } -Descending |
                ForEach-Object { Join-Path $_.FullName 'x64\signtool.exe' } |
                Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
        if ($candidates.Count -gt 0) { return $candidates[0] }
    }
    throw ('-SignCert was given but signtool.exe was not found on PATH or under ' +
        "$kits\<version>\x64. Install the Windows SDK signing tools, or build unsigned.")
}

function Invoke-SignTool {
    param(
        [Parameter(Mandatory = $true)][string]$SignTool,
        [Parameter(Mandatory = $true)][string]$File
    )
    $arguments = @('sign', '/fd', 'SHA256', '/d', 'p2ptunnel')
    if ($TimestampUrl) {
        $arguments += @('/tr', $TimestampUrl, '/td', 'SHA256')
    }
    if (Test-Path -LiteralPath $SignCert -PathType Leaf) {
        $arguments += @('/f', (Resolve-Path -LiteralPath $SignCert).ProviderPath)
        if ($null -ne $SignCertPassword) {
            $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($SignCertPassword)
            try {
                $arguments += @('/p', [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr))
            } finally {
                [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
            }
        }
    } elseif ($SignCert -match '^[0-9A-Fa-f]{40}$') {
        $arguments += @('/sha1', $SignCert)
    } else {
        throw "-SignCert must be a .pfx file or a 40-hex-digit certificate thumbprint (got '$SignCert')."
    }
    $arguments += $File
    Write-BuildStep "signing $File"
    & $SignTool @arguments | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "signtool sign failed for $File (exit code $LASTEXITCODE)."
    }
    & $SignTool verify /pa $File | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw "signtool verify failed for $File (exit code $LASTEXITCODE)."
    }
}

# --- Tooling.

$wix = Get-Command 'wix' -ErrorAction SilentlyContinue
if ($null -eq $wix) {
    throw ("The WiX Toolset CLI ('wix') is not on PATH. Install it, and its Util extension at " +
        "the same version, with:`n" +
        "    dotnet tool install --global wix --version <version>`n" +
        "    wix extension add -g $UtilExtension/<version>`n" +
        'Then open a new shell so %USERPROFILE%\.dotnet\tools is on PATH. WiX v6 and later ' +
        'are under the OSMF EULA (https://wixtoolset.org/osmf/); see README.md.')
}
$wixVersion = ''
try {
    $wixVersion = ((& $wix.Source --version) | Out-String).Trim()
} catch {
    Write-Warning "Could not read 'wix --version': $_"
}
if ($wixVersion) {
    Write-BuildStep "using wix $wixVersion ($($wix.Source))"
    $majorMatch = [regex]::Match($wixVersion, '^(\d+)\.')
    if ($majorMatch.Success) {
        $wixMajor = [int]$majorMatch.Groups[1].Value
        if ($wixMajor -lt 4) {
            throw "WiX $wixVersion is too old; Product.wxs needs WiX v4 or later."
        }
        if ($wixMajor -ge 7 -and -not $AcceptEula) {
            Write-Warning ("WiX v$wixMajor refuses to build until its OSMF EULA is accepted. " +
                "If you accept it, pass -AcceptEula wix$wixMajor (or run 'wix eula accept " +
                "wix$wixMajor' once). This script does not accept it for you.")
        }
    }
}

if ($SignCert -and -not $TimestampUrl) {
    Write-Warning 'No -TimestampUrl: the signatures will stop validating when the certificate expires.'
}
$signTool = $null
if ($SignCert) {
    $signTool = Find-SignTool
    Write-BuildStep "using $signTool"
}

# --- Inputs.

$wixDir = $PSScriptRoot
$repoRoot = Get-FullPathNoTrailingSlash -Path (Join-Path $wixDir '..\..\..')
$productWxs = Join-Path $wixDir 'Product.wxs'

if (-not (Test-Path -LiteralPath $BinDir -PathType Container)) {
    throw "BinDir not found: $BinDir. Build first, e.g. cargo build --release --workspace."
}
$BinDir = Get-FullPathNoTrailingSlash -Path (Resolve-Path -LiteralPath $BinDir).ProviderPath
foreach ($name in $BinaryNames) {
    $path = Join-Path $BinDir $name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "$path not found. BinDir must hold all of: $($BinaryNames -join ', ')."
    }
}

# Product.wxs takes these from RepoRoot; check them here for a clearer error than wix's.
$repoFiles = @(
    'packaging\windows\install.ps1',
    'packaging\windows\uninstall.ps1',
    'packaging\windows\p2ptunnel-common.ps1',
    'packaging\windows\README.md',
    'docs\examples\answer-config.toml',
    'docs\examples\offer-config.toml',
    'docs\WINDOWS.md',
    'README.md',
    'LICENSE'
)
foreach ($relative in $repoFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $repoRoot $relative) -PathType Leaf)) {
        throw "Missing $relative under $repoRoot."
    }
}

if (-not $Version) {
    $Version = Get-WorkspaceVersion -CargoToml (Join-Path $repoRoot 'Cargo.toml')
}
Assert-MsiVersion -Value $Version

$OutputPath = Get-FullPathNoTrailingSlash -Path $OutputPath
if ([System.IO.Path]::GetExtension($OutputPath) -ne '.msi') {
    throw "OutputPath must end in .msi (got '$OutputPath')."
}
$outputDir = Split-Path -Parent $OutputPath
if (-not (Test-Path -LiteralPath $outputDir -PathType Container)) {
    $null = New-Item -ItemType Directory -Path $outputDir
}

# --- Signing the binaries happens on a staged copy, so BinDir is never modified.

$stagingDir = $null
$packageBinDir = $BinDir
try {
    if ($SignCert) {
        $stagingDir = Join-Path ([System.IO.Path]::GetTempPath()) ('p2ptunnel-msi-' + [guid]::NewGuid().ToString('N'))
        $null = New-Item -ItemType Directory -Path $stagingDir
        foreach ($name in $BinaryNames) {
            $staged = Join-Path $stagingDir $name
            Copy-Item -LiteralPath (Join-Path $BinDir $name) -Destination $staged
            Invoke-SignTool -SignTool $signTool -File $staged
        }
        $packageBinDir = $stagingDir
    }

    # --- Build.

    $arguments = @(
        'build',
        '-arch', 'x64',
        '-ext', $UtilExtension,
        '-d', "BinDir=$packageBinDir",
        '-d', "RepoRoot=$repoRoot",
        '-d', "Version=$Version",
        '-o', $OutputPath
    )
    if ($AcceptEula) {
        $arguments += @('-acceptEula', $AcceptEula)
    }
    $arguments += $productWxs

    Write-BuildStep "building $OutputPath (version $Version) from $packageBinDir"
    & $wix.Source @arguments | Out-Host
    if ($LASTEXITCODE -ne 0) {
        throw ("wix build failed (exit code $LASTEXITCODE). If it could not find " +
            "$UtilExtension, run: wix extension add -g $UtilExtension/<your wix version>. " +
            'If it asked for the OSMF EULA, see -AcceptEula.')
    }
} finally {
    if ($null -ne $stagingDir -and (Test-Path -LiteralPath $stagingDir)) {
        Remove-Item -LiteralPath $stagingDir -Recurse -Force
    }
}

if ($SignCert) {
    Invoke-SignTool -SignTool $signTool -File $OutputPath
} else {
    Write-Warning "$OutputPath and the binaries in it are UNSIGNED. Use this build for testing only."
}

$hash = (Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash
Write-BuildStep "wrote $OutputPath"
Write-BuildStep "SHA256 $hash"
