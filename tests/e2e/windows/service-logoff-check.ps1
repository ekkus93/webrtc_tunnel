<#
.SYNOPSIS
Manual check that the p2ptunnel service survives a logoff and does not need a logged-in user
(TODO P0-008: "Service runs with no user logged in and survives logoff").

.DESCRIPTION
A script cannot sign you out and keep running, so this is two phases around a manual step:

  1. Install and start the service, then:   .\service-logoff-check.ps1 -Phase Before
  2. Sign out of Windows (or restart), then sign back in.
  3. Run:                                     .\service-logoff-check.ps1 -Phase After

Before records the service's process id, start time, session and owner, and the current time,
in %LOCALAPPDATA%\p2ptunnel-logoff-baseline.json. After compares against it and prints a
verdict:

  PASS (logoff)  the same process, started before the baseline, is still running in session 0,
                 and a new interactive logon session (a new logon id) appeared after the
                 baseline. It was never restarted, so signing out did not touch it.
  PASS (boot)    the machine restarted after the baseline, and this boot's service process
                 started before the first interactive logon, so it ran with nobody signed in
                 (it needs start type auto, which install.ps1 sets as delayed-auto).
  INCONCLUSIVE   no new interactive logon since the baseline: sign out and back in first.
  FAIL           anything else, with the reason.

Neither phase needs elevation (starting the service does). Run both phases the same way: an
elevated shell sees more logon sessions than a normal one, so a run that mixes the two
reports INCONCLUSIVE. "Sign out" must be a real sign-out (Start > your name > Sign
out), not Lock and not Switch user. For the restart case use Restart, not Shut down: Fast
Startup can make a shutdown and power-on keep the user session's state.

.PARAMETER Phase
Before or After.

.PARAMETER ServiceName
Defaults to p2ptunnel.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('Before', 'After')][string]$Phase,
    [string]$ServiceName = 'p2ptunnel'
)

$ErrorActionPreference = 'Stop'
$baselinePath = Join-Path $env:LOCALAPPDATA 'p2ptunnel-logoff-baseline.json'

function Get-ServiceProcess {
    $query = & sc.exe queryex $ServiceName 2>&1 | Out-String
    if ($query -notmatch 'STATE\s+:\s+\d+\s+(\w+)') { return @{ State = 'NOT_INSTALLED'; Process = $null } }
    $state = $Matches[1]
    $processId = 0
    if ($query -match 'PID\s+:\s+(\d+)') { $processId = [int]$Matches[1] }
    $process = $null
    if ($processId -gt 0) { $process = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue }
    return @{ State = $state; Process = $process }
}

function Test-Elevated {
    ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Get-InteractiveLogons {
    # 2 = Interactive, 10 = RemoteInteractive (RDP), 11 = CachedInteractive.
    # The raw WMI time stamp is converted with its own UTC offset. Get-CimInstance returns
    # Win32_LogonSession.StartTime without applying it (it came back hours in the future here),
    # which would make an old logon look newer than the baseline.
    Add-Type -AssemblyName System.Management
    $searcher = New-Object System.Management.ManagementObjectSearcher(
        'SELECT LogonId, LogonType, StartTime FROM Win32_LogonSession WHERE LogonType = 2 OR LogonType = 10 OR LogonType = 11')
    foreach ($session in $searcher.Get()) {
        if ($session['StartTime']) {
            [pscustomobject]@{
                LogonId   = [string]$session['LogonId']
                LogonType = [int]$session['LogonType']
                StartTime = [System.Management.ManagementDateTimeConverter]::ToDateTime([string]$session['StartTime'])
            }
        }
    }
}

$current = Get-ServiceProcess

if ($Phase -eq 'Before') {
    if ($current.State -ne 'RUNNING' -or $null -eq $current.Process) {
        throw "service '$ServiceName' is $($current.State); install and start it first (sc.exe start $ServiceName)."
    }
    $p = $current.Process
    $baseline = [ordered]@{
        recordedAtUtc  = (Get-Date).ToUniversalTime().ToString('o')
        service        = $ServiceName
        processId      = $p.ProcessId
        processStartUtc = $p.CreationDate.ToUniversalTime().ToString('o')
        sessionId      = $p.SessionId
        bootUtc        = (Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToUniversalTime().ToString('o')
        # Logon ids are unique for the life of a boot, so a new one proves a new logon even if
        # the system clock was stepped in between (which timestamps cannot).
        interactiveLogonIds = @(Get-InteractiveLogons | ForEach-Object { $_.LogonId })
        # An elevated shell sees more logon sessions than a normal one, so the two phases must
        # run the same way or the extra sessions would look like new logons.
        elevated       = (Test-Elevated)
    }
    $baseline | ConvertTo-Json | Set-Content -LiteralPath $baselinePath -Encoding utf8
    Write-Host "[logoff-check] baseline written to $baselinePath"
    Write-Host "[logoff-check] service '$ServiceName': pid $($p.ProcessId), session $($p.SessionId), started $($p.CreationDate)"
    Write-Host '[logoff-check] now sign out of Windows (or restart), sign back in, then run: -Phase After'
    return
}

# ---- After ----------------------------------------------------------------------------------

if (-not (Test-Path -LiteralPath $baselinePath)) { throw "no baseline at $baselinePath; run -Phase Before first." }
$baseline = Get-Content -LiteralPath $baselinePath -Raw | ConvertFrom-Json
$recordedAt = [datetime]::Parse($baseline.recordedAtUtc).ToUniversalTime()
$baselineBoot = [datetime]::Parse($baseline.bootUtc).ToUniversalTime()
$bootNow = (Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToUniversalTime()

$logons = Get-InteractiveLogons
$newestLogon = ($logons | Sort-Object StartTime -Descending | Select-Object -First 1).StartTime
$oldestLogonThisBoot = ($logons | Where-Object { $_.StartTime.ToUniversalTime() -ge $bootNow } | Sort-Object StartTime | Select-Object -First 1).StartTime
$baselineIds = @($baseline.interactiveLogonIds)
$newLogonIds = @($logons | ForEach-Object { $_.LogonId } | Where-Object { $baselineIds -notcontains $_ })
$newLogonSinceBaseline = $newLogonIds.Count -gt 0

Write-Host "[logoff-check] baseline recorded : $($recordedAt.ToLocalTime())   (interactive logon ids then: $($baselineIds -join ', '))"
Write-Host "[logoff-check] interactive logon ids now: $(@($logons | ForEach-Object { $_.LogonId }) -join ', ')   new since baseline: $(if ($newLogonSinceBaseline) { $newLogonIds -join ', ' } else { 'none' })"
Write-Host "[logoff-check] newest interactive logon: $newestLogon"
Write-Host "[logoff-check] service state now : $($current.State)"
if ($current.Process) {
    Write-Host "[logoff-check] service process   : pid $($current.Process.ProcessId), session $($current.Process.SessionId), started $($current.Process.CreationDate)"
}

$verdict = $null
if ($null -ne $baseline.elevated -and [bool]$baseline.elevated -ne (Test-Elevated)) {
    $verdict = "INCONCLUSIVE: -Phase Before ran $(if ($baseline.elevated) { 'elevated' } else { 'without elevation' }) but this run is $(if (Test-Elevated) { 'elevated' } else { 'not elevated' }); an elevated shell sees more logon sessions, so the two cannot be compared. Run both phases the same way."
} elseif ($current.State -ne 'RUNNING' -or $null -eq $current.Process) {
    $verdict = "FAIL: the service is $($current.State) after the sign-out or restart."
} elseif ($bootNow -gt $baselineBoot.AddSeconds(5)) {
    # The machine restarted: the process must be new, and must predate the first logon of this boot.
    $started = $current.Process.CreationDate.ToUniversalTime()
    if ($null -eq $oldestLogonThisBoot) {
        $verdict = 'INCONCLUSIVE: restarted, but no interactive logon has been recorded yet for this boot.'
    } elseif ($started -lt $oldestLogonThisBoot.ToUniversalTime()) {
        $verdict = "PASS (boot): the service started at $($current.Process.CreationDate), before the first interactive logon of this boot ($oldestLogonThisBoot), so it ran with nobody signed in."
    } else {
        $verdict = "FAIL: the service started at $($current.Process.CreationDate), after the first interactive logon ($oldestLogonThisBoot); it did not start by itself at boot."
    }
} elseif (-not $newLogonSinceBaseline) {
    $verdict = 'INCONCLUSIVE: no new interactive logon since the baseline. Sign out (not lock) and sign back in, then run -Phase After again.'
} elseif ($current.Process.ProcessId -ne $baseline.processId -or
    [Math]::Abs(($current.Process.CreationDate.ToUniversalTime() - [datetime]::Parse($baseline.processStartUtc).ToUniversalTime()).TotalSeconds) -gt 2) {
    $verdict = "FAIL: the service process changed across the logoff (was pid $($baseline.processId), now pid $($current.Process.ProcessId)); it was restarted, so it did not simply survive."
} elseif ($current.Process.SessionId -ne 0) {
    $verdict = "FAIL: the service process is in session $($current.Process.SessionId), not session 0."
} else {
    $verdict = "PASS (logoff): the same process (pid $($baseline.processId), started $($current.Process.CreationDate)) is still running in session 0, and a new interactive logon ($($newLogonIds -join ', ')) began after the baseline. It was never restarted."
}

Write-Host "[logoff-check] $verdict"
if ($verdict -like 'FAIL*') { exit 1 }
if ($verdict -like 'INCONCLUSIVE*') { exit 2 }
exit 0
