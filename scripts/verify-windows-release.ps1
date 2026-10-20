<#
.SYNOPSIS
Checks a built Windows release .zip and .msi (tasks P2-001, P2-002).

.DESCRIPTION
Zip checks (no elevation needed):
  - entry names use forward slashes and sit under one top-level folder named after the zip;
  - the expected files are present, and nothing from this packaging step leaks in;
  - the three daemons/tools run (`--help`), the service wrapper is present;
  - the installer scripts parse, and install.ps1's default config templates resolve;
  - every relative link in docs/WINDOWS.md that points at a file the zip ships resolves.

MSI checks (need an elevated, 64-bit shell and a machine with no `p2ptunnel` service):
  - `msiexec /i ... ROLE=answer` succeeds and registers the service `p2ptunnel` as
    `NT SERVICE\p2ptunnel` with `--role answer`;
  - the install folder has the expected files;
  - %ProgramData%\p2ptunnel is locked down (no Users / Everyone / Authenticated Users entry);
  - `msiexec /x ... REMOVEDATA=1` removes the service, the install folder and the data.

The MSI is uninstalled even if a check fails. Exits non-zero (throws) on any failure and
prints every problem it found.

.PARAMETER Zip
Path to the release .zip.

.PARAMETER Msi
Path to the .msi. Omit it, or pass -SkipMsiInstall, to skip the MSI checks.

.PARAMETER SkipMsiInstall
Check only the zip.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Zip,
    [string]$Msi,
    [switch]$SkipMsiInstall
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$problems = New-Object System.Collections.Generic.List[string]
function Step([string]$text) { Write-Host "[verify-windows-release] $text" }
function Fail([string]$text) { $script:problems.Add($text); Write-Host "[verify-windows-release] FAIL: $text" }

# ---- Zip -----------------------------------------------------------------------------

$zipPath = (Resolve-Path -LiteralPath $Zip).ProviderPath
$label = [System.IO.Path]::GetFileNameWithoutExtension($zipPath)
Step "zip: $zipPath"

$expected = @(
    'p2pctl.exe', 'p2p-offer.exe', 'p2p-answer.exe', 'p2p-service-windows.exe',
    'README.md', 'LICENSE',
    'packaging/windows/install.ps1', 'packaging/windows/uninstall.ps1',
    'packaging/windows/p2ptunnel-common.ps1', 'packaging/windows/README.md',
    'docs/WINDOWS.md', 'docs/examples/answer-config.toml', 'docs/examples/offer-config.toml'
)
$archive = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    $names = @($archive.Entries | ForEach-Object { $_.FullName })
} finally {
    $archive.Dispose()
}
$backslashes = @($names | Where-Object { $_ -like '*\*' })
if ($backslashes.Count -gt 0) { Fail "zip entries use backslashes: $($backslashes -join ', ')" }
$outsideRoot = @($names | Where-Object { -not $_.StartsWith("$label/") })
if ($outsideRoot.Count -gt 0) { Fail "entries outside the top-level folder '$label/': $($outsideRoot -join ', ')" }
$files = @($names | Where-Object { -not $_.EndsWith('/') } | ForEach-Object { $_.Substring([Math]::Min($_.Length, $label.Length + 1)) })
foreach ($want in $expected) {
    if ($files -notcontains $want) { Fail "missing from the zip: $want" }
}
foreach ($extra in ($files | Where-Object { $expected -notcontains $_ })) {
    Fail "unexpected file in the zip: $extra"
}

$extractRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('p2ptunnel-verify-' + [guid]::NewGuid().ToString('N'))
try {
    [System.IO.Compression.ZipFile]::ExtractToDirectory($zipPath, $extractRoot)
    $root = Join-Path $extractRoot $label

    foreach ($bin in 'p2pctl', 'p2p-offer', 'p2p-answer') {
        $exe = Join-Path $root "$bin.exe"
        if (Test-Path -LiteralPath $exe) {
            & $exe --help *> $null
            if ($LASTEXITCODE -ne 0) { Fail "$bin.exe --help exited $LASTEXITCODE" } else { Step "$bin.exe --help ok" }
        }
    }
    $service = Join-Path $root 'p2p-service-windows.exe'
    if ((Test-Path -LiteralPath $service) -and (Get-Item -LiteralPath $service).Length -lt 1MB) {
        Fail 'p2p-service-windows.exe is suspiciously small'
    }

    foreach ($script in Get-ChildItem -LiteralPath (Join-Path $root 'packaging/windows') -Filter *.ps1 -ErrorAction SilentlyContinue) {
        $errors = $null
        $null = [System.Management.Automation.Language.Parser]::ParseFile($script.FullName, [ref]$null, [ref]$errors)
        if ($errors.Count -gt 0) { Fail "$($script.Name) has $($errors.Count) parse error(s)" }
    }
    foreach ($role in 'answer', 'offer') {
        $template = Join-Path $root "packaging/windows/../../docs/examples/$role-config.toml"
        if (-not (Test-Path -LiteralPath $template)) { Fail "install.ps1's default $role template does not resolve" }
    }

    # Links in docs/WINDOWS.md. These documents are in the repository but not in the zip, so
    # their links are expected to dangle; anything else must resolve.
    $unshipped = @('SYSTEMD.md', 'LAUNCHD.md', 'WEBRTC_TUNNEL_WINDOWS_SUPPORT_SPEC.md', 'WEBRTC_TUNNEL_WINDOWS_SUPPORT_TODO.md')
    $docsDir = Join-Path $root 'docs'
    $windowsMd = Join-Path $docsDir 'WINDOWS.md'
    if (Test-Path -LiteralPath $windowsMd) {
        $linkCount = 0
        foreach ($match in [regex]::Matches((Get-Content -LiteralPath $windowsMd -Raw), '\]\(([^)\s]+)\)')) {
            $target = $match.Groups[1].Value
            if ($target -match '^[a-z][a-z0-9+.-]*:' -or $target.StartsWith('#')) { continue }
            $path = ($target -split '#')[0]
            if ($unshipped -contains $path) { continue }
            $linkCount++
            if (-not (Test-Path -LiteralPath (Join-Path $docsDir $path))) { Fail "docs/WINDOWS.md links to '$target', which is not in the zip" }
        }
        Step "docs/WINDOWS.md: $linkCount relative link(s) checked"
    }
} finally {
    if (Test-Path -LiteralPath $extractRoot) { Remove-Item -LiteralPath $extractRoot -Recurse -Force }
}

# ---- MSI -----------------------------------------------------------------------------

if ($Msi -and -not $SkipMsiInstall) {
    $msiPath = (Resolve-Path -LiteralPath $Msi).ProviderPath
    Step "msi: $msiPath"
    $principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'the MSI checks need an elevated shell'
    }
    if (sc.exe query p2ptunnel 2>$null | Select-String 'SERVICE_NAME') {
        throw 'a p2ptunnel service already exists on this machine; refusing to run the MSI checks over it'
    }

    $logDir = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [System.IO.Path]::GetTempPath() }
    $installLog = Join-Path $logDir 'p2ptunnel-msi-install.log'
    $uninstallLog = Join-Path $logDir 'p2ptunnel-msi-uninstall.log'
    $installDir = Join-Path $env:ProgramFiles 'p2ptunnel'
    $dataRoot = Join-Path $env:ProgramData 'p2ptunnel'
    $installed = $false
    try {
        $install = Start-Process msiexec -ArgumentList '/i', "`"$msiPath`"", 'ROLE=answer', '/qn', '/l*v', "`"$installLog`"" -Wait -PassThru
        if ($install.ExitCode -ne 0) {
            Fail "msiexec /i exited $($install.ExitCode); see $installLog"
        } else {
            $installed = $true
            Step 'msiexec /i ROLE=answer ok'

            $config = (sc.exe qc p2ptunnel | Out-String)
            if ($config -notmatch '--role answer') { Fail "service binPath does not carry --role answer:`n$config" }
            if ($config -notmatch 'NT SERVICE\\p2ptunnel') { Fail "service does not run as NT SERVICE\p2ptunnel:`n$config" }
            foreach ($want in 'p2p-service-windows.exe', 'p2pctl.exe', 'p2p-answer.exe', 'p2p-offer.exe',
                'packaging\windows\install.ps1', 'packaging\windows\uninstall.ps1', 'docs\WINDOWS.md') {
                if (-not (Test-Path -LiteralPath (Join-Path $installDir $want))) { Fail "missing from $installDir : $want" }
            }
            $acl = (icacls $dataRoot | Out-String)
            if ($acl -match 'BUILTIN\\Users|Everyone|NT AUTHORITY\\Authenticated Users') {
                Fail "$dataRoot is readable by ordinary users:`n$acl"
            } else {
                Step "$dataRoot is locked down"
            }
        }
    } finally {
        if ($installed) {
            $uninstall = Start-Process msiexec -ArgumentList '/x', "`"$msiPath`"", 'REMOVEDATA=1', '/qn', '/l*v', "`"$uninstallLog`"" -Wait -PassThru
            if ($uninstall.ExitCode -ne 0) { Fail "msiexec /x exited $($uninstall.ExitCode); see $uninstallLog" }
            if (sc.exe query p2ptunnel 2>$null | Select-String 'SERVICE_NAME') { Fail 'the p2ptunnel service survived the uninstall' }
            if (Test-Path -LiteralPath $installDir) { Fail "$installDir survived the uninstall" }
            if (Test-Path -LiteralPath $dataRoot) { Fail "$dataRoot survived uninstall with REMOVEDATA=1" }
            if ($problems.Count -eq 0) { Step 'msiexec /x REMOVEDATA=1 left nothing behind' }
        }
    }
}

if ($problems.Count -gt 0) {
    throw ("verify-windows-release found $($problems.Count) problem(s):`n - " + ($problems -join "`n - "))
}
Step 'all checks passed'
