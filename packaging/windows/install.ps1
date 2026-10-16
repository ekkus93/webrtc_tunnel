<#
.SYNOPSIS
Installs the single p2ptunnel Windows service (task P0-008).

.DESCRIPTION
Registers the service `p2ptunnel`, running as the virtual account NT SERVICE\p2ptunnel,
with its role fixed at install time:

    binPath = "<InstallDir>\p2p-service-windows.exe" --role <answer|offer>

and locks down %ProgramData%\p2ptunnel with a DACL granting only that account, SYSTEM and
Administrators (spec 2.6, 2.7, 6.3, 6.4). Creates <role>\, <role>\state\ and
<role>\state\log\, and seeds <role>\config.toml from docs\examples\<role>-config.toml when
none exists. It does not start the service: the seeded config still needs the operator's
identity, broker and peer settings. See packaging\windows\README.md.

Fails, changing nothing, if a service named p2ptunnel already exists. To switch roles run
uninstall.ps1 first.

.PARAMETER Role
answer or offer. Fixed for the life of this installation.

.PARAMETER InstallDir
Directory holding p2p-service-windows.exe. Defaults to %ProgramFiles%\p2ptunnel. Must not
be writable by non-administrators.

.PARAMETER BinaryPath
Optional built p2p-service-windows.exe to copy into InstallDir. Without it, the binary
must already be in InstallDir.

.PARAMETER ConfigTemplate
Template to seed config.toml from. Defaults to docs\examples\<role>-config.toml in this
repository. Only read when config.toml does not exist yet.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('answer', 'offer')]
    [string]$Role,

    [string]$InstallDir,

    [string]$BinaryPath,

    [string]$ConfigTemplate
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'p2ptunnel-common.ps1')

Assert-P2pWindowsHost
Assert-P2pElevated

# ValidateSet is case-insensitive; the binPath, directory names and seeded config are not.
$Role = $Role.ToLowerInvariant()

# --- Pre-flight: everything below only reads. Nothing on the machine changes until the
# --- service is created, so any failure here leaves the machine as it was.

$existing = Get-P2pRegisteredService
if ($null -ne $existing) {
    if ($existing.PendingDelete) {
        throw ("A service named '$P2pServiceName' is still pending deletion from an earlier " +
            'uninstall. Close services.msc / Event Viewer (or reboot) so it is removed, then ' +
            'run install.ps1 again.')
    }
    $installedRole = $existing.Role
    if (-not $installedRole) { $installedRole = 'unknown' }
    throw ("A service named '$P2pServiceName' is already installed (role: $installedRole; " +
        "binPath: $($existing.ImagePath)). Only one p2ptunnel service can exist per machine " +
        'and install.ps1 never changes the role of an existing one. To switch roles, run ' +
        "uninstall.ps1, then install.ps1 -Role $Role.")
}

if (-not $InstallDir) {
    $programFiles = $env:ProgramW6432
    if (-not $programFiles) { $programFiles = $env:ProgramFiles }
    $InstallDir = Join-Path $programFiles 'p2ptunnel'
}
if ($InstallDir -notmatch '^[A-Za-z]:\\') {
    throw "InstallDir must be an absolute path on a local drive (got '$InstallDir')."
}
$InstallDir = [System.IO.Path]::GetFullPath($InstallDir).TrimEnd('\')
$exePath = Join-Path $InstallDir $P2pServiceExeName

if ($BinaryPath) {
    if (-not (Test-Path -LiteralPath $BinaryPath -PathType Leaf)) {
        throw "BinaryPath not found: $BinaryPath"
    }
    $BinaryPath = (Resolve-Path -LiteralPath $BinaryPath).ProviderPath
} elseif (-not (Test-Path -LiteralPath $exePath -PathType Leaf)) {
    throw ("$exePath not found. Build p2p-service-windows (P0-007) and either copy it there " +
        'or pass -BinaryPath to have this script copy it.')
}

# The service binary must not be replaceable by a non-admin, or the service would run that
# user's code as NT SERVICE\p2ptunnel. Check whatever already exists now; a directory this
# script creates is checked again after creation.
$installParent = Split-Path -Parent $InstallDir
if (Test-Path -LiteralPath $installParent -PathType Container) {
    $writers = @(Get-P2pNonAdminWriters -Path $installParent -Mask $P2pParentReplaceMask)
    if ($writers.Count -gt 0) {
        throw "Non-administrators can replace '$InstallDir' through its parent: $($writers -join '; '). Choose another -InstallDir."
    }
}
foreach ($path in @($InstallDir, $exePath)) {
    if (Test-Path -LiteralPath $path) {
        $writers = @(Get-P2pNonAdminWriters -Path $path -Mask $P2pWriteMask)
        if ($writers.Count -gt 0) {
            throw "Non-administrators can modify '$path': $($writers -join '; '). Choose another -InstallDir or fix its ACL."
        }
    }
}

$dataRoot = Get-P2pDataRoot
$layout = Get-P2pRoleLayout -DataRoot $dataRoot -Role $Role
$serviceSid = Get-P2pServiceSid -ServiceName $P2pServiceName

if (Test-Path -LiteralPath $dataRoot) {
    $rootItem = Get-Item -LiteralPath $dataRoot -Force
    if (-not $rootItem.PSIsContainer) {
        throw "$dataRoot exists and is not a directory. Inspect and remove it, then run install.ps1 again."
    }
    if (($rootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$dataRoot is a junction or symbolic link. Inspect and remove it, then run install.ps1 again."
    }
}

# Render the seeded config now, so a bad template fails before anything is changed.
$seededConfig = $null
if (-not (Test-Path -LiteralPath $layout.Config)) {
    if (-not $ConfigTemplate) {
        $ConfigTemplate = Join-Path $PSScriptRoot ("..\..\docs\examples\$Role-config.toml")
    }
    if (-not (Test-Path -LiteralPath $ConfigTemplate -PathType Leaf)) {
        throw "Config template not found: $ConfigTemplate. Pass -ConfigTemplate <path to $Role-config.toml>."
    }
    $template = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $ConfigTemplate).ProviderPath)
    $seededConfig = ConvertTo-P2pSeededConfig -Template $template -Layout $layout
}

# --- Changes start here.

if ($BinaryPath) {
    if (-not (Test-Path -LiteralPath $InstallDir)) {
        Write-P2pStep "creating $InstallDir"
        $null = New-Item -ItemType Directory -Path $InstallDir
        $writers = @(Get-P2pNonAdminWriters -Path $InstallDir -Mask $P2pWriteMask)
        if ($writers.Count -gt 0) {
            throw "Non-administrators can modify the new '$InstallDir' (inherited from its parent): $($writers -join '; '). Choose another -InstallDir."
        }
    }
    if ($BinaryPath -ne $exePath) {
        Write-P2pStep "copying $BinaryPath to $exePath"
        Copy-Item -LiteralPath $BinaryPath -Destination $exePath -Force
    }
}

Write-P2pStep "creating service '$P2pServiceName' (role $Role) as $P2pServiceAccount"
$null = Invoke-P2pNative -Tool 'sc.exe' -Arguments (Get-P2pScCreateArguments -ExePath $exePath -Role $Role)

try {
    # The virtual account needs an unrestricted service SID; set it explicitly rather than
    # relying on sc.exe create to imply it.
    $null = Invoke-P2pNative -Tool 'sc.exe' -Arguments "sidtype $P2pServiceName unrestricted"
    $null = Invoke-P2pNative -Tool 'sc.exe' -Arguments ("description $P2pServiceName " +
        "`"WebRTC port-forwarding tunnel ($Role). Config: $($layout.Config)`"")
    # Restart on failure, like Restart=on-failure / RestartSec=5s in the systemd units.
    $null = Invoke-P2pNative -Tool 'sc.exe' -Arguments ("failure $P2pServiceName reset= 86400 " +
        'actions= restart/5000/restart/5000/restart/30000')

    # The account now exists. Cross-check the computed SID the DACL is about to use.
    $resolvedSid = (New-Object System.Security.Principal.NTAccount($P2pServiceAccount)).Translate(
        [System.Security.Principal.SecurityIdentifier]).Value
    if ($resolvedSid -ne $serviceSid) {
        throw "$P2pServiceAccount resolved to $resolvedSid, expected $serviceSid."
    }

    if (-not (Test-Path -LiteralPath $dataRoot)) {
        Write-P2pStep "creating $dataRoot"
        $null = New-Item -ItemType Directory -Path $dataRoot
    }

    # The root DACL goes on first, before anything is created beneath it. Until then the
    # directory inherits %ProgramData%'s ACL, which lets any user add entries to it.
    Write-P2pStep "restricting $dataRoot to SYSTEM, Administrators and $P2pServiceAccount"
    $null = Invoke-P2pNative -Tool 'icacls.exe' -Arguments (Get-P2pIcaclsRootArguments -Root $dataRoot -ServiceSid $serviceSid)
    $null = Invoke-P2pNative -Tool 'icacls.exe' -Arguments ((ConvertTo-P2pQuotedArgument -Value $dataRoot) + " /setowner *$P2pSidAdministrators")

    # Now that no one else can add to the tree, audit what is already in it: anything left
    # from before (an earlier install, or someone who pre-created the directory).
    $reparse = @(Get-P2pReparsePoints -Root $dataRoot)
    if ($reparse.Count -gt 0) {
        throw "Junctions or symbolic links found under $dataRoot; refusing to continue: $(($reparse | ForEach-Object { $_.FullName }) -join ', ')"
    }
    $untrusted = @(Get-P2pUntrustedOwners -Root $dataRoot -ServiceSid $serviceSid)
    if ($untrusted.Count -gt 0) {
        throw ("Items under $dataRoot are owned by an account that is not an administrator, " +
            "SYSTEM or the service, and that owner could rewrite their ACLs. Inspect them, " +
            "remove them, and run install.ps1 again:`n  $($untrusted -join "`n  ")")
    }

    # Pre-existing descendants (e.g. an identity kept by a previous uninstall) drop any
    # explicit ACEs and take only what they inherit from the root.
    foreach ($child in (Get-ChildItem -LiteralPath $dataRoot -Force)) {
        Write-P2pStep "resetting $($child.FullName) to inherit from $dataRoot"
        $null = Invoke-P2pNative -Tool 'icacls.exe' -Arguments ((ConvertTo-P2pQuotedArgument -Value $child.FullName) + ' /reset /T')
    }

    foreach ($dir in @($layout.RoleDir, $layout.StateDir, $layout.LogDir)) {
        if (-not (Test-Path -LiteralPath $dir)) {
            Write-P2pStep "creating $dir"
            $null = New-Item -ItemType Directory -Path $dir
        }
    }
    Write-P2pStep "granting $P2pServiceAccount modify on $($layout.StateDir)"
    $null = Invoke-P2pNative -Tool 'icacls.exe' -Arguments (Get-P2pIcaclsStateArguments -StateDir $layout.StateDir -ServiceSid $serviceSid)

    if ($null -ne $seededConfig) {
        Write-P2pStep "seeding $($layout.Config) from $ConfigTemplate"
        # UTF-8 without a BOM; Set-Content -Encoding UTF8 in 5.1 would add one.
        [System.IO.File]::WriteAllText($layout.Config, $seededConfig, (New-Object System.Text.UTF8Encoding($false)))
    } else {
        Write-P2pStep "keeping existing $($layout.Config)"
        $current = [System.IO.File]::ReadAllText($layout.Config)
        if ($current -match '(?m)^[ \t]*stdout_logging[ \t]*=[ \t]*true' -or
            $current -notmatch '(?m)^[ \t]*file_logging[ \t]*=[ \t]*true') {
            Write-Warning ("$($layout.Config) does not set file_logging = true and " +
                'stdout_logging = false. A service has no console, so its log output will be ' +
                'lost (spec 6.3).')
        }
    }

    # Verify the result rather than trusting the tools' exit codes alone.
    $problems = @()
    $problems += @(Test-P2pAclMatchesSpec -Path $dataRoot -Spec (Get-P2pRootAclSpec -ServiceSid $serviceSid) -ExpectProtected $true)
    $problems += @(Test-P2pAclMatchesSpec -Path $layout.StateDir -Spec (Get-P2pStateAclSpec -ServiceSid $serviceSid) -ExpectProtected $false)
    foreach ($item in (Get-P2pTreeItems -Root $dataRoot)) {
        $path = $item.FullName.TrimEnd('\')
        if ($path -eq $dataRoot -or $path -eq $layout.StateDir) { continue }
        # Everything else carries no explicit ACE and inherits from the root.
        $problems += @(Test-P2pAclMatchesSpec -Path $path -Spec @() -ExpectProtected $false)
    }
    if ((Get-P2pOwnerSid -Path $dataRoot) -ne $P2pSidAdministrators) {
        $problems += "$dataRoot : owner is not BUILTIN\Administrators"
    }
    if ($problems.Count -gt 0) {
        throw "ACL verification failed:`n  $($problems -join "`n  ")"
    }
} catch {
    Write-Warning "Install failed; deleting the half-created '$P2pServiceName' service. $dataRoot is left as it is."
    $null = Invoke-P2pNative -Tool 'sc.exe' -Arguments "delete $P2pServiceName" -AllowedExitCodes @(0, $P2pErrServiceDoesNotExist, $P2pErrServiceMarkedForDelete)
    throw
}

Write-P2pStep 'DACL verified. icacls view of the data root:'
Write-Host (Invoke-P2pNative -Tool 'icacls.exe' -Arguments (ConvertTo-P2pQuotedArgument -Value $dataRoot)).Output
Write-P2pStep "SDDL: $((Get-Acl -LiteralPath $dataRoot).Sddl)"

Write-Host ''
Write-P2pStep "Installed service '$P2pServiceName' (role $Role, $P2pServiceAccount, start: delayed auto). It has NOT been started."
Write-Host @"

Before starting it, finish $($layout.Config):
  1. [node] peer_id, [broker] url / client_id / username / password_file,
     [[forwards]], and for offer [peer] remote_peer_id: replace the example values.
  2. Identity: run ``p2pctl keygen <peer_id>`` (it writes to YOUR profile,
     %USERPROFILE%\.config\p2ptunnel\), then COPY (not move) the identity file to
       $($layout.Identity)
     so it inherits this directory's DACL, and delete the copy in your profile.
     A moved file keeps its old ACL.
  3. Put the peer's public key in $($layout.AuthorizedKeys)
     and the broker CA certificate in $($layout.CaFile).
  4. p2pctl check-config --config "$($layout.Config)"
  5. sc.exe start $P2pServiceName
     Logs: $($layout.LogFile)

Verify the ACL with: icacls "$dataRoot" /T
"@
