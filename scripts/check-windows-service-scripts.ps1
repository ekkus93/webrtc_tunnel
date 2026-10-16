<#
.SYNOPSIS
Static validation of the Windows service installer under packaging/windows/ (task P0-008).

.DESCRIPTION
The Windows counterpart of scripts/check-systemd-units.sh and
scripts/check-launchd-plists.sh. Needs no admin rights and changes nothing on the machine.
It checks that:

  - every script parses (System.Management.Automation.Language.Parser) and uses no
    syntax newer than Windows PowerShell 5.1 (??, ?., ternary, && / ||);
  - every script is pure ASCII (5.1 reads a BOM-less file as ANSI);
  - install.ps1 and uninstall.ps1 set $ErrorActionPreference = 'Stop', and assert
    elevation before any sc.exe / icacls.exe call;
  - p2ptunnel-common.ps1 has no top-level side effects (only constants and functions),
    after which it is dot-sourced so its pure functions can be tested directly:
      * the service name is exactly p2ptunnel and the account exactly NT SERVICE\p2ptunnel;
      * the sc.exe create command line names that service, obj= that account, and a
        binPath of "<exe>" --role <role> with no --config;
      * the computed service SID matches `sc.exe showsid p2ptunnel` (read-only);
      * the root DACL is exactly SYSTEM F, Administrators F, service RX, all (OI)(CI),
        with inheritance from %ProgramData% removed, and only SID strings in icacls;
      * the config seeded from each docs/examples/<role>-config.toml has
        file_logging = true, stdout_logging = false, log_file under that role's
        state\log, all paths as TOML literal strings, and no placeholders left.
  - nothing still uses the retired per-role service names p2ptunnel-answer/-offer.

What it cannot do: run the installer. That needs an elevated shell and the P0-007 binary;
see packaging/windows/README.md for the manual verification steps.

Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-windows-service-scripts.ps1
#>
[CmdletBinding()]
param()

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$packaging = Join-Path $root 'packaging\windows'
$commonPath = Join-Path $packaging 'p2ptunnel-common.ps1'
$installPath = Join-Path $packaging 'install.ps1'
$uninstallPath = Join-Path $packaging 'uninstall.ps1'
$readmePath = Join-Path $packaging 'README.md'
$scripts = @($commonPath, $installPath, $uninstallPath, $PSCommandPath)

$script:failures = New-Object System.Collections.Generic.List[string]

function Write-Log {
    param([string]$Message)
    Write-Host "[check-windows-service-scripts] $Message"
}

function Add-Failure {
    param([string]$Message)
    $script:failures.Add($Message)
    Write-Host "[check-windows-service-scripts FAIL] $Message" -ForegroundColor Red
}

function Assert-That {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { Add-Failure $Message }
}

function Get-ParsedScript {
    param([string]$Path)
    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$errors)
    return New-Object PSObject -Property @{ Ast = $ast; Tokens = $tokens; Errors = $errors }
}

function Get-CommandAsts {
    param($Ast, [string]$Name)
    return @($Ast.FindAll({
                param($node)
                ($node -is [System.Management.Automation.Language.CommandAst]) -and
                ($node.GetCommandName() -eq $Name)
            }, $true))
}

foreach ($path in @($commonPath, $installPath, $uninstallPath, $readmePath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Add-Failure "missing file: $path"
    }
}
if ($script:failures.Count -gt 0) {
    throw "check-windows-service-scripts: required files are missing"
}

# --- Parse, PowerShell 5.1 compatibility, ASCII.

# Token kinds that only exist in PowerShell 7+. Compared by name so this also runs under
# 5.1, whose TokenKind enum does not define them (there the parser rejects them outright).
$newerTokenKinds = @('QuestionQuestion', 'QuestionQuestionEquals', 'QuestionDot',
    'QuestionLBracket', 'QuestionMark', 'AndAnd', 'OrOr')

$parsed = @{}
foreach ($path in $scripts) {
    $name = Split-Path -Leaf $path
    $result = Get-ParsedScript -Path $path
    $parsed[$path] = $result
    foreach ($err in $result.Errors) {
        Add-Failure "$name`:$($err.Extent.StartLineNumber): parse error: $($err.Message)"
    }
    foreach ($token in $result.Tokens) {
        if ($newerTokenKinds -contains $token.Kind.ToString()) {
            Add-Failure "$name`:$($token.Extent.StartLineNumber): '$($token.Text)' needs PowerShell 7; these scripts must run on Windows PowerShell 5.1"
        }
    }
    $bytes = [System.IO.File]::ReadAllBytes($path)
    for ($i = 0; $i -lt $bytes.Length; $i++) {
        if ($bytes[$i] -gt 0x7F) {
            Add-Failure "$name`: non-ASCII byte 0x$('{0:X2}' -f $bytes[$i]) at offset $i (Windows PowerShell 5.1 reads BOM-less files as ANSI)"
            break
        }
    }
}
Write-Log "parsed $($scripts.Count) scripts with PowerShell $($PSVersionTable.PSVersion)"

# --- install.ps1 / uninstall.ps1 structure.

foreach ($path in @($installPath, $uninstallPath)) {
    $name = Split-Path -Leaf $path
    $ast = $parsed[$path].Ast

    $stopAssignments = @($ast.EndBlock.Statements | Where-Object {
            ($_ -is [System.Management.Automation.Language.AssignmentStatementAst]) -and
            ($_.Left.Extent.Text -eq '$ErrorActionPreference') -and
            ($_.Right.Extent.Text -eq "'Stop'")
        })
    Assert-That ($stopAssignments.Count -eq 1) "$name must set `$ErrorActionPreference = 'Stop' at top level"

    $elevation = @(Get-CommandAsts -Ast $ast -Name 'Assert-P2pElevated')
    Assert-That ($elevation.Count -ge 1) "$name must call Assert-P2pElevated"
    if ($elevation.Count -ge 1) {
        $elevationOffset = $elevation[0].Extent.StartOffset
        Assert-That ($null -ne ($ast.EndBlock.Statements | Where-Object { $_ -eq $elevation[0].Parent })) `
            "$name must call Assert-P2pElevated at top level, not inside a branch"
        foreach ($native in (Get-CommandAsts -Ast $ast -Name 'Invoke-P2pNative')) {
            Assert-That ($native.Extent.StartOffset -gt $elevationOffset) `
                "$name`:$($native.Extent.StartLineNumber): Invoke-P2pNative runs before Assert-P2pElevated"
        }
    }

    # Native tools only through Invoke-P2pNative, which checks exit codes; never via PATH
    # or PowerShell's `sc` alias.
    foreach ($tool in @('sc', 'sc.exe', 'icacls', 'icacls.exe', 'takeown', 'takeown.exe')) {
        $direct = @(Get-CommandAsts -Ast $ast -Name $tool)
        Assert-That ($direct.Count -eq 0) "$name calls $tool directly; use Invoke-P2pNative so its exit code is checked"
    }
}

$installAst = $parsed[$installPath].Ast
$createCalls = @(Get-CommandAsts -Ast $installAst -Name 'Get-P2pScCreateArguments')
Assert-That ($createCalls.Count -eq 1) 'install.ps1 must build its sc.exe create command with Get-P2pScCreateArguments'
foreach ($required in @('Get-P2pIcaclsRootArguments', 'Get-P2pIcaclsStateArguments', 'ConvertTo-P2pSeededConfig', 'Test-P2pAclMatchesSpec', 'Get-P2pRegisteredService')) {
    Assert-That (@(Get-CommandAsts -Ast $installAst -Name $required).Count -ge 1) "install.ps1 must call $required"
}
$roleParam = @($installAst.ParamBlock.Parameters | Where-Object { $_.Name.VariablePath.UserPath -eq 'Role' })
Assert-That ($roleParam.Count -eq 1) 'install.ps1 must take a -Role parameter'
if ($roleParam.Count -eq 1) {
    $validateSet = @($roleParam[0].Attributes | Where-Object { $_.TypeName.Name -eq 'ValidateSet' })
    $values = @()
    if ($validateSet.Count -eq 1) { $values = @($validateSet[0].PositionalArguments | ForEach-Object { $_.Value }) }
    Assert-That (($values -join ',') -eq 'answer,offer') "install.ps1 -Role must be ValidateSet('answer', 'offer'), got '$($values -join ',')'"
}
$uninstallAst = $parsed[$uninstallPath].Ast
Assert-That (@($uninstallAst.ParamBlock.Parameters | Where-Object { $_.Name.VariablePath.UserPath -eq 'RemoveData' }).Count -eq 1) `
    'uninstall.ps1 must take a -RemoveData switch (data is kept by default)'

# --- Retired naming.

foreach ($path in @($commonPath, $installPath, $uninstallPath, $readmePath)) {
    $text = [System.IO.File]::ReadAllText($path)
    Assert-That ($text -notmatch 'p2ptunnel-(answer|offer)') "$(Split-Path -Leaf $path) still names a per-role service (p2ptunnel-answer/-offer); spec 6.4 has one service, p2ptunnel"
}

# --- Dot-source p2ptunnel-common.ps1, but only after proving it has no side effects.

$commonAst = $parsed[$commonPath].Ast
$sideEffects = @()
foreach ($statement in $commonAst.EndBlock.Statements) {
    if ($statement -is [System.Management.Automation.Language.FunctionDefinitionAst]) { continue }
    if ($statement -is [System.Management.Automation.Language.AssignmentStatementAst]) {
        $commands = @($statement.Right.FindAll({ param($n) $n -is [System.Management.Automation.Language.CommandAst] }, $true))
        $calls = @($statement.Right.FindAll({ param($n) $n -is [System.Management.Automation.Language.InvokeMemberExpressionAst] }, $true))
        if ($commands.Count -eq 0 -and $calls.Count -eq 0) { continue }
    }
    $sideEffects += "line $($statement.Extent.StartLineNumber): $($statement.Extent.Text.Split("`n")[0])"
}
foreach ($entry in $sideEffects) {
    Add-Failure "p2ptunnel-common.ps1 top level must be constants and functions only; found $entry"
}
if ($sideEffects.Count -gt 0 -or $script:failures.Count -gt 0) {
    throw "check-windows-service-scripts: $($script:failures.Count) problem(s) found before the functional checks; not dot-sourcing p2ptunnel-common.ps1"
}

. $commonPath

Assert-That ($P2pServiceName -ceq 'p2ptunnel') "service name must be exactly 'p2ptunnel', got '$P2pServiceName'"
$platformLib = Join-Path $PSScriptRoot '..\crates\p2p-platform\src\lib.rs'
$rustName = $null
if ((Get-Content -LiteralPath $platformLib -Raw) -match '(?m)^pub const SERVICE_NAME: &str = "([^"]+)";') {
    $rustName = $Matches[1]
}
Assert-That ($rustName -ceq $P2pServiceName) "p2p_platform::SERVICE_NAME ('$rustName') must equal the installer's service name ('$P2pServiceName')"
Assert-That ($P2pServiceAccount -ceq 'NT SERVICE\p2ptunnel') "service account must be exactly 'NT SERVICE\p2ptunnel', got '$P2pServiceAccount'"
Assert-That ($P2pSidSystem -eq 'S-1-5-18') 'SYSTEM SID constant is wrong'
Assert-That ($P2pSidAdministrators -eq 'S-1-5-32-544') 'Administrators SID constant is wrong'
Assert-That (($P2pRoles -join ',') -ceq 'answer,offer') "roles must be answer and offer, got '$($P2pRoles -join ',')'"

# Service SID: computed vs the SCM's own answer. `sc.exe showsid` only hashes the name; it
# needs no admin rights and the service does not have to exist.
$serviceSid = Get-P2pServiceSid -ServiceName $P2pServiceName
$showSid = Invoke-P2pNative -Tool 'sc.exe' -Arguments "showsid $P2pServiceName"
$match = [regex]::Match($showSid.Output, 'S-1-5-80(-\d+){5}')
Assert-That ($match.Success -and $match.Value -eq $serviceSid) "computed service SID $serviceSid does not match sc.exe showsid: $($showSid.Output)"
Write-Log "service SID for NT SERVICE\p2ptunnel: $serviceSid (matches sc.exe showsid)"

# sc.exe create command line.
$exe = 'C:\Program Files\p2ptunnel\p2p-service-windows.exe'
foreach ($role in $P2pRoles) {
    $createArgs = Get-P2pScCreateArguments -ExePath $exe -Role $role
    $expected = 'create p2ptunnel binPath= "\"C:\Program Files\p2ptunnel\p2p-service-windows.exe\" --role ' + $role +
        '" obj= "NT SERVICE\p2ptunnel" start= delayed-auto DisplayName= "p2ptunnel (' + $role + ')"'
    Assert-That ($createArgs -ceq $expected) "sc.exe create arguments for $role are`n    $createArgs`n  expected`n    $expected"
    Assert-That ($createArgs -notmatch '--config') "sc.exe create for $role must not pass --config; the binary finds its config from --role"
    Assert-That ($createArgs -notmatch 'password=') "sc.exe create for $role must not pass a password; virtual accounts have none"
}
$rejected = $false
try { $null = Get-P2pScCreateArguments -ExePath $exe -Role 'Answer' } catch { $rejected = $true }
Assert-That $rejected 'Get-P2pScCreateArguments must reject a role that is not exactly answer/offer'

# DACL.
$rootSpec = @(Get-P2pRootAclSpec -ServiceSid $serviceSid)
$specText = ($rootSpec | ForEach-Object { "$($_.Sid):$($_.Inheritance)$($_.Rights)" } | Sort-Object) -join ' '
$expectedSpec = (@("S-1-5-18:(OI)(CI)F", "S-1-5-32-544:(OI)(CI)F", "$serviceSid`:(OI)(CI)RX") | Sort-Object) -join ' '
Assert-That ($specText -ceq $expectedSpec) "root DACL spec is '$specText', expected '$expectedSpec' (SYSTEM, Administrators and the service only)"
$stateSpec = @(Get-P2pStateAclSpec -ServiceSid $serviceSid)
Assert-That ($stateSpec.Count -eq 1 -and $stateSpec[0].Sid -eq $serviceSid -and $stateSpec[0].Rights -eq 'M') `
    'state DACL must add exactly one ACE: the service, Modify'

$sampleRoot = 'C:\ProgramData\p2ptunnel'
$icaclsRoot = Get-P2pIcaclsRootArguments -Root $sampleRoot -ServiceSid $serviceSid
$expectedIcacls = '"C:\ProgramData\p2ptunnel" /inheritance:r /grant:r "*S-1-5-18:(OI)(CI)F" "*S-1-5-32-544:(OI)(CI)F" "*' + $serviceSid + ':(OI)(CI)RX"'
Assert-That ($icaclsRoot -ceq $expectedIcacls) "icacls root arguments are`n    $icaclsRoot`n  expected`n    $expectedIcacls"
foreach ($grant in [regex]::Matches($icaclsRoot + ' ' + (Get-P2pIcaclsStateArguments -StateDir "$sampleRoot\answer\state" -ServiceSid $serviceSid), '"([^"]*:\([^"]*)"')) {
    Assert-That ($grant.Groups[1].Value -match '^\*S-1-') "icacls grant '$($grant.Groups[1].Value)' must name a SID (*S-1-...), not a localized account name"
}

# Seeded configs.
foreach ($role in $P2pRoles) {
    $templatePath = Join-Path $root "docs\examples\$role-config.toml"
    if (-not (Test-Path -LiteralPath $templatePath -PathType Leaf)) {
        Add-Failure "missing config template $templatePath"
        continue
    }
    $layout = Get-P2pRoleLayout -DataRoot $sampleRoot -Role $role
    $seeded = ConvertTo-P2pSeededConfig -Template ([System.IO.File]::ReadAllText($templatePath)) -Layout $layout
    $logging = [regex]::Match($seeded, '(?ms)^\[logging\][^\[]*')
    Assert-That $logging.Success "seeded $role config has no [logging] table"
    $section = $logging.Value
    Assert-That ($section -match '(?m)^stdout_logging = false\r?$') "seeded $role config must set stdout_logging = false (a service has no console, spec 6.3)"
    Assert-That ($section -match '(?m)^file_logging = true\r?$') "seeded $role config must set file_logging = true"
    $expectedLog = "log_file = 'C:\ProgramData\p2ptunnel\$role\state\log\p2ptunnel.log'"
    Assert-That ($section -match ('(?m)^' + [regex]::Escape($expectedLog) + '\r?$')) "seeded $role config must set $expectedLog"
    Assert-That ($seeded -match "(?m)^role = `"$role`"\r?$") "seeded $role config must keep role = `"$role`""
    foreach ($key in @('identity', 'authorized_keys', 'state_dir', 'log_dir', 'ca_file', 'log_file', 'status_file')) {
        $line = [regex]::Match($seeded, "(?m)^$key = ([^\r\n]*)")
        Assert-That ($line.Success -and $line.Groups[1].Value -match "^'C:\\ProgramData\\p2ptunnel\\$role\\[^']*'$") `
            "seeded $role config: $key must be a single-quoted TOML literal path under C:\ProgramData\p2ptunnel\$role, got '$($line.Groups[1].Value)'"
    }
    Assert-That ($seeded -notmatch '__[A-Z_]+__') "seeded $role config still has placeholders"
}
$rejected = $false
try { $null = ConvertTo-P2pTomlLiteral -Value "C:\it's\here" } catch { $rejected = $true }
Assert-That $rejected 'ConvertTo-P2pTomlLiteral must reject a path containing a single quote'

if ($script:failures.Count -gt 0) {
    throw "check-windows-service-scripts: $($script:failures.Count) problem(s) found; see FAIL lines above"
}
Write-Log 'PASS - Windows service scripts parse on PowerShell 5.1 and match spec 2.6/2.7/6.3/6.4 statically'
Write-Log 'NOT covered here: running install.ps1/uninstall.ps1 (needs elevation and the P0-007 binary); see packaging/windows/README.md'
