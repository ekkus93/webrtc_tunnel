# Shared definitions for install.ps1 and uninstall.ps1 (task P0-008).
#
# This file is dot-sourced. It must contain only constant assignments and function
# definitions: scripts/check-windows-service-scripts.ps1 enforces that, then dot-sources it
# without admin rights to test the pure functions below (sc.exe arguments, icacls
# arguments, the seeded config). Anything that touches the machine lives in a function and
# only runs when install.ps1 or uninstall.ps1 calls it.
#
# Must stay Windows PowerShell 5.1 compatible and pure ASCII (5.1 reads a BOM-less file
# as ANSI).

# The service name is fixed: Windows derives the virtual account NT SERVICE\<name> from it
# (spec 2.6), and there is exactly one such service per machine (spec 6.4).
$P2pServiceName = 'p2ptunnel'
$P2pServiceAccount = 'NT SERVICE\p2ptunnel'
$P2pServiceExeName = 'p2p-service-windows.exe'
$P2pRoles = @('answer', 'offer')

# Well-known SIDs, so nothing depends on localized account names.
$P2pSidSystem = 'S-1-5-18'
$P2pSidAdministrators = 'S-1-5-32-544'
$P2pSidTrustedInstaller = 'S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464'

# Native exit codes the scripts branch on.
$P2pErrServiceDoesNotExist = 1060
$P2pErrServiceNotActive = 1062
$P2pErrServiceMarkedForDelete = 1072

# FileSystemRights masks, as icacls writes them and Get-Acl reads them back.
#   F  = FullControl
#   RX = ReadAndExecute | Synchronize
#   M  = Modify | Synchronize
$P2pRightsMask = @{
    'F'  = 2032127
    'RX' = 1179817
    'M'  = 1245631
}

function Assert-P2pWindowsHost {
    if ([System.Environment]::OSVersion.Platform -ne [System.PlatformID]::Win32NT) {
        throw 'This script manages a Windows service and only runs on Windows.'
    }
    if ([System.Environment]::Is64BitOperatingSystem -and -not [System.Environment]::Is64BitProcess) {
        throw ('This is a 32-bit PowerShell on 64-bit Windows. Registry and System32 views ' +
            'would be redirected. Re-run from the 64-bit Windows PowerShell.')
    }
}

function Assert-P2pElevated {
    $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object System.Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw ('This script must run elevated: it registers a Windows service and sets ACLs ' +
            'under %ProgramData%. Open Windows PowerShell with "Run as administrator" and ' +
            'run it again.')
    }
}

function Get-P2pSystemTool {
    param([Parameter(Mandatory = $true)][string]$Name)
    # Absolute System32 path: never resolve sc.exe/icacls.exe through PATH, and never hit
    # PowerShell's own `sc` alias (Set-Content).
    if (-not $env:SystemRoot) {
        throw 'SystemRoot is not set; cannot locate Windows system tools.'
    }
    $path = Join-Path (Join-Path $env:SystemRoot 'System32') $Name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Windows system tool not found: $path"
    }
    return $path
}

function Invoke-P2pNative {
    # Runs a System32 tool with an exact, caller-built command line. PowerShell 5.1's own
    # native-argument passing mangles embedded double quotes, and sc.exe's binPath needs
    # them (\"...\"), so the command line is handed to the process verbatim instead.
    param(
        [Parameter(Mandatory = $true)][string]$Tool,
        [Parameter(Mandatory = $true)][string]$Arguments,
        [int[]]$AllowedExitCodes = @(0)
    )
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = Get-P2pSystemTool -Name $Tool
    $psi.Arguments = $Arguments
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($psi)
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $output = ($stdoutTask.Result + $stderrTask.Result).Trim()
    $exitCode = $process.ExitCode
    if ($AllowedExitCodes -notcontains $exitCode) {
        throw "$Tool $Arguments`nfailed with exit code $exitCode`:`n$output"
    }
    return New-Object PSObject -Property @{ ExitCode = $exitCode; Output = $output }
}

function ConvertTo-P2pQuotedArgument {
    # Quotes one path for a native command line. Windows paths cannot contain '"', and a
    # trailing backslash would escape the closing quote, so both are rejected.
    param([Parameter(Mandatory = $true)][string]$Value)
    if ($Value.Contains('"')) {
        throw "Path must not contain a double quote: $Value"
    }
    if ($Value.EndsWith('\')) {
        throw "Path must not end with a backslash: $Value"
    }
    return '"' + $Value + '"'
}

function Get-P2pServiceSid {
    # The per-service SID Windows assigns to NT SERVICE\<name>:
    # S-1-5-80- followed by the SHA-1 of the upper-cased UTF-16LE service name, read as five
    # little-endian 32-bit integers. Same value `sc.exe showsid <name>` prints. Computing it
    # lets the DACL use a SID string instead of the localized account name.
    param([Parameter(Mandatory = $true)][string]$ServiceName)
    $sha1 = [System.Security.Cryptography.SHA1]::Create()
    try {
        $hash = $sha1.ComputeHash([System.Text.Encoding]::Unicode.GetBytes($ServiceName.ToUpperInvariant()))
    } finally {
        $sha1.Dispose()
    }
    $parts = @()
    for ($i = 0; $i -lt 5; $i++) {
        $parts += [System.BitConverter]::ToUInt32($hash, $i * 4)
    }
    return 'S-1-5-80-' + ($parts -join '-')
}

function Get-P2pScCreateArguments {
    # sc.exe create command line for the single p2ptunnel service. The role is fixed here, in
    # binPath; the service binary finds %ProgramData%\p2ptunnel\<role>\config.toml itself, so
    # no --config is passed. No password= is given: a virtual account has none.
    param(
        [Parameter(Mandatory = $true)][string]$ExePath,
        [Parameter(Mandatory = $true)][string]$Role
    )
    if ($P2pRoles -cnotcontains $Role) {
        throw "Role must be one of: $($P2pRoles -join ', ') (got '$Role')."
    }
    $null = ConvertTo-P2pQuotedArgument -Value $ExePath
    $binPath = '"\"' + $ExePath + '\" --role ' + $Role + '"'
    return ('create ' + $P2pServiceName +
        ' binPath= ' + $binPath +
        ' obj= "' + $P2pServiceAccount + '"' +
        ' start= delayed-auto' +
        ' DisplayName= "p2ptunnel (' + $Role + ')"')
}

function Get-P2pDataRoot {
    # Must agree with p2p_platform::service_config_dir, which reads %ProgramData%.
    if (-not $env:ProgramData) {
        throw 'ProgramData is not set; cannot locate the service data directory.'
    }
    return Join-Path $env:ProgramData $P2pServiceName
}

function Get-P2pRoleLayout {
    # Spec 6.3 filesystem layout for one role.
    param(
        [Parameter(Mandatory = $true)][string]$DataRoot,
        [Parameter(Mandatory = $true)][string]$Role
    )
    if ($P2pRoles -cnotcontains $Role) {
        throw "Role must be one of: $($P2pRoles -join ', ') (got '$Role')."
    }
    $roleDir = Join-Path $DataRoot $Role
    $stateDir = Join-Path $roleDir 'state'
    $logDir = Join-Path $stateDir 'log'
    return New-Object PSObject -Property @{
        Role           = $Role
        RoleDir        = $roleDir
        StateDir       = $stateDir
        LogDir         = $logDir
        Config         = Join-Path $roleDir 'config.toml'
        Identity       = Join-Path $roleDir 'identity'
        AuthorizedKeys = Join-Path $roleDir 'authorized_keys'
        CaFile         = Join-Path $roleDir 'ca.crt'
        LogFile        = Join-Path $logDir 'p2ptunnel.log'
        StatusFile     = Join-Path $stateDir 'status.json'
    }
}

function Get-P2pRootAclSpec {
    # The complete DACL for %ProgramData%\p2ptunnel. Inheritance from %ProgramData% is
    # disabled (or BUILTIN\Users read flows back in); every grant is (OI)(CI) so files
    # created later, including the identity, inherit it on creation. The service account
    # only reads the tree; it gets Modify on its role's state directory (see
    # Get-P2pStateAclSpec).
    param([Parameter(Mandatory = $true)][string]$ServiceSid)
    return @(
        (New-Object PSObject -Property @{ Sid = $P2pSidSystem; Rights = 'F'; Inheritance = '(OI)(CI)' }),
        (New-Object PSObject -Property @{ Sid = $P2pSidAdministrators; Rights = 'F'; Inheritance = '(OI)(CI)' }),
        (New-Object PSObject -Property @{ Sid = $ServiceSid; Rights = 'RX'; Inheritance = '(OI)(CI)' })
    )
}

function Get-P2pStateAclSpec {
    # The one explicit ACE below the root: the daemon writes its status file and log under
    # <role>\state. It sits on top of the inherited root grants.
    param([Parameter(Mandatory = $true)][string]$ServiceSid)
    return @(
        (New-Object PSObject -Property @{ Sid = $ServiceSid; Rights = 'M'; Inheritance = '(OI)(CI)' })
    )
}

function ConvertTo-P2pIcaclsGrants {
    param([Parameter(Mandatory = $true)][object[]]$Spec)
    $grants = @()
    foreach ($entry in $Spec) {
        $grants += '"*' + $entry.Sid + ':' + $entry.Inheritance + $entry.Rights + '"'
    }
    return ($grants -join ' ')
}

function Get-P2pIcaclsRootArguments {
    # /inheritance:r drops what was inherited from %ProgramData%; /grant:r replaces any
    # existing explicit grant for these SIDs. Anything else left on the root is caught by
    # Test-P2pAclMatchesSpec afterwards and fails the install.
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$ServiceSid
    )
    return ((ConvertTo-P2pQuotedArgument -Value $Root) + ' /inheritance:r /grant:r ' +
        (ConvertTo-P2pIcaclsGrants -Spec (Get-P2pRootAclSpec -ServiceSid $ServiceSid)))
}

function Get-P2pIcaclsStateArguments {
    param(
        [Parameter(Mandatory = $true)][string]$StateDir,
        [Parameter(Mandatory = $true)][string]$ServiceSid
    )
    return ((ConvertTo-P2pQuotedArgument -Value $StateDir) + ' /grant:r ' +
        (ConvertTo-P2pIcaclsGrants -Spec (Get-P2pStateAclSpec -ServiceSid $ServiceSid)))
}

function ConvertTo-P2pTomlLiteral {
    # A TOML literal string: backslashes are taken as-is, so Windows paths need no
    # escaping. A literal string cannot contain a single quote or a newline.
    param([Parameter(Mandatory = $true)][string]$Value)
    if ($Value.Contains("'") -or $Value.Contains("`n") -or $Value.Contains("`r")) {
        throw "Cannot write this path as a TOML literal string: $Value"
    }
    return "'" + $Value + "'"
}

function Set-P2pTomlKey {
    # Replaces the value of a key that must appear exactly once in the template.
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][string]$Value
    )
    $pattern = '(?m)^[ \t]*' + [regex]::Escape($Key) + '[ \t]*=[^\r\n]*'
    $found = [regex]::Matches($Text, $pattern).Count
    if ($found -ne 1) {
        throw "Config template must set '$Key' exactly once; found $found."
    }
    # '$$' is a literal '$' in a .NET replacement, so the value is never read as a group.
    $replacement = ($Key + ' = ' + $Value).Replace('$', '$$')
    return [regex]::Replace($Text, $pattern, $replacement)
}

function ConvertTo-P2pSeededConfig {
    # Turns docs/examples/<role>-config.toml into a service config for the spec 6.3 layout:
    # Windows paths as TOML literal strings, file logging on and stdout logging off (a
    # service has no console, spec 6.3). Peer IDs, broker URL/credentials, forwards and the
    # identity/CA files themselves are left for the operator.
    param(
        [Parameter(Mandatory = $true)][string]$Template,
        [Parameter(Mandatory = $true)][object]$Layout
    )
    $text = $Template
    $text = Set-P2pTomlKey -Text $text -Key 'identity' -Value (ConvertTo-P2pTomlLiteral $Layout.Identity)
    $text = Set-P2pTomlKey -Text $text -Key 'authorized_keys' -Value (ConvertTo-P2pTomlLiteral $Layout.AuthorizedKeys)
    $text = Set-P2pTomlKey -Text $text -Key 'state_dir' -Value (ConvertTo-P2pTomlLiteral $Layout.StateDir)
    $text = Set-P2pTomlKey -Text $text -Key 'log_dir' -Value (ConvertTo-P2pTomlLiteral $Layout.LogDir)
    $text = Set-P2pTomlKey -Text $text -Key 'ca_file' -Value (ConvertTo-P2pTomlLiteral $Layout.CaFile)
    $text = Set-P2pTomlKey -Text $text -Key 'log_file' -Value (ConvertTo-P2pTomlLiteral $Layout.LogFile)
    $text = Set-P2pTomlKey -Text $text -Key 'status_file' -Value (ConvertTo-P2pTomlLiteral $Layout.StatusFile)
    $text = Set-P2pTomlKey -Text $text -Key 'file_logging' -Value 'true'
    $text = Set-P2pTomlKey -Text $text -Key 'stdout_logging' -Value 'false'
    $text = Set-P2pTomlKey -Text $text -Key 'role' -Value ('"' + $Layout.Role + '"')
    $leftover = [regex]::Matches($text, '__[A-Z_]+__')
    if ($leftover.Count -gt 0) {
        throw "Config template still has unfilled placeholders: $(($leftover | ForEach-Object { $_.Value }) -join ', ')"
    }
    return $text
}

function Get-P2pRegisteredService {
    # Reads the service's registry key rather than parsing sc.exe output, which is localized.
    $key = 'HKLM:\SYSTEM\CurrentControlSet\Services\' + $P2pServiceName
    if (-not (Test-Path -LiteralPath $key)) {
        return $null
    }
    $props = Get-ItemProperty -LiteralPath $key
    $imagePath = ''
    $objectName = ''
    $deleteFlag = 0
    if ($props.PSObject.Properties['ImagePath']) { $imagePath = [string]$props.ImagePath }
    if ($props.PSObject.Properties['ObjectName']) { $objectName = [string]$props.ObjectName }
    if ($props.PSObject.Properties['DeleteFlag']) { $deleteFlag = [int]$props.DeleteFlag }
    $role = ''
    $match = [regex]::Match($imagePath, '--role\s+([A-Za-z0-9_-]+)')
    if ($match.Success) { $role = $match.Groups[1].Value }
    return New-Object PSObject -Property @{
        ImagePath         = $imagePath
        ObjectName        = $objectName
        Role              = $role
        PendingDelete     = ($deleteFlag -eq 1)
        IsP2pServiceImage = ($imagePath -match [regex]::Escape($P2pServiceExeName))
    }
}

function Get-P2pTreeItems {
    # Every file and directory under $Root (inclusive). Reparse points (junctions,
    # symlinks) are returned but never descended into, so an ACL or delete walk cannot be
    # redirected outside the tree.
    param([Parameter(Mandatory = $true)][string]$Root)
    $items = New-Object System.Collections.Generic.List[System.IO.FileSystemInfo]
    $rootInfo = New-Object System.IO.DirectoryInfo($Root)
    $items.Add($rootInfo)
    $pending = New-Object System.Collections.Generic.Stack[System.IO.DirectoryInfo]
    if (($rootInfo.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -eq 0) {
        $pending.Push($rootInfo)
    }
    while ($pending.Count -gt 0) {
        $dir = $pending.Pop()
        foreach ($child in $dir.GetFileSystemInfos()) {
            $items.Add($child)
            $isDir = ($child.Attributes -band [System.IO.FileAttributes]::Directory) -ne 0
            $isReparse = ($child.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0
            if ($isDir -and -not $isReparse) {
                $pending.Push([System.IO.DirectoryInfo]$child)
            }
        }
    }
    return $items
}

function Get-P2pReparsePoints {
    param([Parameter(Mandatory = $true)][string]$Root)
    return @(Get-P2pTreeItems -Root $Root | Where-Object {
            ($_.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0
        })
}

function Get-P2pOwnerSid {
    param([Parameter(Mandatory = $true)][string]$Path)
    $acl = Get-Acl -LiteralPath $Path
    return $acl.GetOwner([System.Security.Principal.SecurityIdentifier]).Value
}

function Get-P2pUntrustedOwners {
    # Items whose owner is not SYSTEM, Administrators, TrustedInstaller, the service, or the
    # elevated user running the installer. Under %ProgramData% any user can pre-create a
    # directory, and an owner keeps the right to rewrite the DACL whatever it says, so a
    # tree owned by someone else must not be trusted with the identity.
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$ServiceSid
    )
    $current = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $trusted = @($P2pSidSystem, $P2pSidAdministrators, $P2pSidTrustedInstaller, $ServiceSid, $current)
    $bad = @()
    foreach ($item in (Get-P2pTreeItems -Root $Root)) {
        $owner = Get-P2pOwnerSid -Path $item.FullName
        if ($trusted -notcontains $owner) {
            $bad += "$($item.FullName) (owner $owner)"
        }
    }
    return $bad
}

function Get-P2pExplicitRules {
    param([Parameter(Mandatory = $true)][string]$Path)
    $acl = Get-Acl -LiteralPath $Path
    return New-Object PSObject -Property @{
        Protected = $acl.AreAccessRulesProtected
        Explicit  = @($acl.GetAccessRules($true, $false, [System.Security.Principal.SecurityIdentifier]))
        All       = @($acl.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier]))
    }
}

function Test-P2pAclMatchesSpec {
    # Returns a list of problems (empty when the explicit ACEs on $Path are exactly $Spec,
    # all Allow, and inheritance-from-parent is in the expected state).
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][object[]]$Spec,
        [Parameter(Mandatory = $true)][bool]$ExpectProtected
    )
    $problems = @()
    $rules = Get-P2pExplicitRules -Path $Path
    if ($rules.Protected -ne $ExpectProtected) {
        $problems += "$Path : inheritance from parent protected=$($rules.Protected), expected $ExpectProtected"
    }
    $expectedFlags = [System.Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    $remaining = New-Object System.Collections.ArrayList
    foreach ($entry in $Spec) { $null = $remaining.Add($entry) }
    foreach ($rule in $rules.Explicit) {
        $sid = $rule.IdentityReference.Value
        $mask = [int]$rule.FileSystemRights
        $matched = $null
        foreach ($entry in $remaining) {
            if ($entry.Sid -eq $sid -and $P2pRightsMask[$entry.Rights] -eq $mask) {
                $matched = $entry
                break
            }
        }
        $ok = ($null -ne $matched) -and
            ($rule.AccessControlType -eq [System.Security.AccessControl.AccessControlType]::Allow) -and
            ($rule.InheritanceFlags -eq $expectedFlags) -and
            ($rule.PropagationFlags -eq [System.Security.AccessControl.PropagationFlags]::None)
        if ($ok) {
            $remaining.Remove($matched)
        } else {
            $problems += "$Path : unexpected ACE $($rule.AccessControlType) $sid rights=$mask inherit=$($rule.InheritanceFlags) propagate=$($rule.PropagationFlags)"
        }
    }
    foreach ($entry in $remaining) {
        $problems += "$Path : missing ACE $($entry.Sid) $($entry.Inheritance)$($entry.Rights)"
    }
    return $problems
}

function Get-P2pNonAdminWriters {
    # Allow ACEs on $Path that give someone other than SYSTEM / Administrators /
    # TrustedInstaller a right in $Mask. Inherit-only ACEs do not apply to $Path itself and
    # are skipped. Used to refuse a service binary that a non-admin could replace: the
    # service would then run that user's code as NT SERVICE\p2ptunnel.
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][long]$Mask
    )
    $trusted = @($P2pSidSystem, $P2pSidAdministrators, $P2pSidTrustedInstaller)
    $acl = Get-Acl -LiteralPath $Path
    $found = @()
    $owner = $acl.GetOwner([System.Security.Principal.SecurityIdentifier]).Value
    $current = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    if (($trusted + $current) -notcontains $owner) {
        $found += "owner $owner"
    }
    foreach ($rule in $acl.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier])) {
        if ($rule.AccessControlType -ne [System.Security.AccessControl.AccessControlType]::Allow) { continue }
        if (($rule.PropagationFlags -band [System.Security.AccessControl.PropagationFlags]::InheritOnly) -ne 0) { continue }
        $sid = $rule.IdentityReference.Value
        if ($trusted -contains $sid) { continue }
        if (([long][int]$rule.FileSystemRights -band $Mask) -ne 0) {
            $found += "$sid rights=$([int]$rule.FileSystemRights)"
        }
    }
    return $found
}

# Rights that let a principal replace or re-permission a file or directory:
# WriteData/CreateFiles, AppendData/CreateDirectories, WriteEA, DeleteChild,
# WriteAttributes, Delete, WRITE_DAC, WRITE_OWNER, GENERIC_ALL, GENERIC_WRITE.
$P2pWriteMask = 0x2 -bor 0x4 -bor 0x10 -bor 0x40 -bor 0x100 -bor 0x10000 -bor 0x40000 -bor 0x80000 -bor 0x10000000 -bor 0x40000000
# On the parent of the install directory only the rights that would let someone remove or
# rename the install directory itself, or take over the parent, matter.
$P2pParentReplaceMask = 0x40 -bor 0x40000 -bor 0x80000 -bor 0x10000000 -bor 0x40000000

function Write-P2pStep {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host "[p2ptunnel] $Message"
}
