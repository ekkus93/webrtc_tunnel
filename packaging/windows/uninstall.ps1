<#
.SYNOPSIS
Removes the p2ptunnel Windows service (task P0-008).

.DESCRIPTION
Stops and deletes the service `p2ptunnel`. Its virtual account, NT SERVICE\p2ptunnel,
exists only while the service does, so it goes with it.

By default %ProgramData%\p2ptunnel is kept, with its restrictive DACL, so the config and
private identity survive a reinstall (for example to switch roles) and stay protected
meanwhile. Pass -RemoveData to delete that directory as well, which also removes the only
ACL this installer created.

.PARAMETER RemoveData
Also delete %ProgramData%\p2ptunnel: every role's config, identity, state and logs.

.PARAMETER Force
Delete a service named p2ptunnel even if its binPath does not point at
p2p-service-windows.exe (i.e. it was not installed by install.ps1).
#>
[CmdletBinding()]
param(
    [switch]$RemoveData,

    [switch]$Force
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'p2ptunnel-common.ps1')

Assert-P2pWindowsHost
Assert-P2pElevated

$dataRoot = Get-P2pDataRoot
$existing = Get-P2pRegisteredService

if ($null -eq $existing) {
    Write-P2pStep "service '$P2pServiceName' is not installed"
} else {
    if (-not $existing.IsP2pServiceImage -and -not $Force) {
        throw ("A service named '$P2pServiceName' exists but its binPath is '$($existing.ImagePath)', " +
            "not $P2pServiceExeName. It was not installed by install.ps1; pass -Force to delete it anyway.")
    }

    Write-P2pStep "stopping '$P2pServiceName' (role $($existing.Role))"
    $null = Invoke-P2pNative -Tool 'sc.exe' -Arguments "stop $P2pServiceName" -AllowedExitCodes @(0, $P2pErrServiceNotActive)
    $service = Get-Service -Name $P2pServiceName
    try {
        $service.WaitForStatus([System.ServiceProcess.ServiceControllerStatus]::Stopped, [TimeSpan]::FromSeconds(60))
    } catch {
        throw "'$P2pServiceName' did not stop within 60 seconds; not deleting it. Check $dataRoot\<role>\state\log and the System event log. ($_)"
    }

    Write-P2pStep "deleting '$P2pServiceName'"
    $null = Invoke-P2pNative -Tool 'sc.exe' -Arguments "delete $P2pServiceName" -AllowedExitCodes @(0, $P2pErrServiceMarkedForDelete)

    # The SCM removes the service once no one holds a handle to it (services.msc, Event
    # Viewer, a monitoring tool). Until then install.ps1 refuses to run.
    $deadline = (Get-Date).AddSeconds(15)
    do {
        $query = Invoke-P2pNative -Tool 'sc.exe' -Arguments "query $P2pServiceName" -AllowedExitCodes @(0, $P2pErrServiceDoesNotExist)
        $gone = ($query.ExitCode -eq $P2pErrServiceDoesNotExist)
        if (-not $gone) { Start-Sleep -Milliseconds 500 }
    } while (-not $gone -and (Get-Date) -lt $deadline)
    if ($gone) {
        Write-P2pStep "service '$P2pServiceName' and its account $P2pServiceAccount are gone"
    } else {
        Write-Warning ("'$P2pServiceName' is marked for deletion but still registered, because " +
            'something holds a handle to it. Close services.msc / Event Viewer (or reboot) ' +
            'before running install.ps1 again.')
    }
}

if ($RemoveData) {
    if (Test-Path -LiteralPath $dataRoot) {
        # Never follow a junction or symlink out of the tree while deleting recursively.
        $reparse = @(Get-P2pReparsePoints -Root $dataRoot)
        if ($reparse.Count -gt 0) {
            throw "Junctions or symbolic links found under $dataRoot; remove them by hand first: $(($reparse | ForEach-Object { $_.FullName }) -join ', ')"
        }
        Write-P2pStep "deleting $dataRoot (configs, identities, state and logs for every role)"
        Remove-Item -LiteralPath $dataRoot -Recurse -Force
        Write-P2pStep "removed $dataRoot and its ACL"
    } else {
        Write-P2pStep "$dataRoot does not exist; nothing to remove"
    }
} elseif (Test-Path -LiteralPath $dataRoot) {
    Write-P2pStep ("kept $dataRoot (config, identity, logs) with its restrictive DACL. Its ACE " +
        "for $P2pServiceAccount now names a deleted account; install.ps1 reuses it. Run " +
        'uninstall.ps1 -RemoveData to delete the directory.')
}

Write-P2pStep 'the service binary in the install directory was left in place; delete it by hand if no longer needed'
