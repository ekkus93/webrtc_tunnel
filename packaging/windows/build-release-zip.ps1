<#
.SYNOPSIS
Builds the Windows release .zip (task P2-002).

.DESCRIPTION
Packages the four release executables, the service installer scripts, the example
configs and the docs into <Label>.zip, in the layout install.ps1 expects:

    <Label>\
        p2pctl.exe  p2p-offer.exe  p2p-answer.exe  p2p-service-windows.exe
        README.md  LICENSE
        packaging\windows\   install.ps1  uninstall.ps1  p2ptunnel-common.ps1  README.md ...
        docs\                WINDOWS.md
        docs\examples\       answer-config.toml  offer-config.toml

install.ps1 dot-sources p2ptunnel-common.ps1 from its own directory and finds its default
config template at ..\..\docs\examples\<role>-config.toml. docs\WINDOWS.md stays under
docs\ so its repository-relative links (../README.md, ../packaging/windows/README.md,
examples/) keep working.

Entry names use forward slashes (written explicitly), so the archive is the same whether
this runs under Windows PowerShell 5.1 or PowerShell 7.

Used by the release-artifacts job and the package-windows dry-run job in ci.yml, so there is
exactly one implementation. Writes the path of the zip to the pipeline.

.PARAMETER Label
Name of the top-level folder inside the zip, and of the zip itself, for example
rust_webrtc-v0.3.2-windows-latest.

.PARAMETER BinDir
Directory holding the four release executables. Default: target\release.

.PARAMETER OutputDir
Where to write <Label>.zip. Default: the current directory.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Label,
    [string]$BinDir = 'target/release',
    [string]$OutputDir = '.'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).ProviderPath
$binDirPath = (Resolve-Path -LiteralPath $BinDir).ProviderPath
if (-not (Test-Path -LiteralPath $OutputDir -PathType Container)) {
    $null = New-Item -ItemType Directory -Path $OutputDir
}
$outputPath = (Resolve-Path -LiteralPath $OutputDir).ProviderPath
$zipPath = Join-Path $outputPath "$Label.zip"
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }

$stage = Join-Path ([System.IO.Path]::GetTempPath()) ('p2ptunnel-zip-' + [guid]::NewGuid().ToString('N'))
try {
    $artifactDir = Join-Path $stage $Label
    $null = New-Item -ItemType Directory -Path $artifactDir

    foreach ($bin in 'p2pctl', 'p2p-offer', 'p2p-answer', 'p2p-service-windows') {
        Copy-Item -LiteralPath (Join-Path $binDirPath "$bin.exe") -Destination $artifactDir
    }
    Copy-Item -LiteralPath (Join-Path $repo 'README.md'), (Join-Path $repo 'LICENSE') -Destination $artifactDir

    $serviceDir = Join-Path $artifactDir 'packaging\windows'
    $null = New-Item -ItemType Directory -Path $serviceDir
    # Exactly the files the MSI also installs; this packaging script itself is not shipped.
    foreach ($name in 'install.ps1', 'uninstall.ps1', 'p2ptunnel-common.ps1', 'README.md') {
        Copy-Item -LiteralPath (Join-Path $repo "packaging\windows\$name") -Destination $serviceDir
    }

    $docsDir = Join-Path $artifactDir 'docs'
    $examplesDir = Join-Path $docsDir 'examples'
    $null = New-Item -ItemType Directory -Path $examplesDir
    Copy-Item -LiteralPath (Join-Path $repo 'docs\examples\answer-config.toml'), (Join-Path $repo 'docs\examples\offer-config.toml') -Destination $examplesDir
    Copy-Item -LiteralPath (Join-Path $repo 'docs\WINDOWS.md') -Destination $docsDir

    # Entry names are written by hand with '/': ZipFile.CreateFromDirectory and
    # Compress-Archive both write backslashes under Windows PowerShell 5.1.
    $archive = [System.IO.Compression.ZipFile]::Open($zipPath, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        $prefix = $stage.TrimEnd('\') + '\'
        foreach ($file in Get-ChildItem -LiteralPath $artifactDir -Recurse -File) {
            $entryName = $file.FullName.Substring($prefix.Length).Replace('\', '/')
            $null = [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $archive, $file.FullName, $entryName, [System.IO.Compression.CompressionLevel]::Optimal)
        }
    } finally {
        $archive.Dispose()
    }
} finally {
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
}

Write-Host "[build-release-zip] wrote $zipPath"
$zipPath
