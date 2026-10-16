<#
.SYNOPSIS
Manual P1-001 proof: a Windows offer<->answer tunnel over a real mqtts:// broker.

.DESCRIPTION
Runs everything on this machine, over loopback:

  client -> p2p-offer listener -> WebRTC -> p2p-answer -> echo target -> back

1. Generates a throwaway CA + broker server cert with openssl (SAN localhost/127.0.0.1).
2. Starts a native mosquitto with a TLS listener on a free 127.0.0.1 port.
3. Generates two identities with `p2pctl keygen`, each against its own throwaway
   %USERPROFILE%, and cross-authorizes them with `p2pctl add-authorized-key`.
4. Writes offer/answer configs from docs\examples\*-config.toml (Windows paths as
   single-quoted TOML literal strings) and checks them with `p2pctl check-config`.
5. Starts p2p-answer and p2p-offer, waits for both status files to reach steady state,
   pushes bytes through the forwarded port to a local echo server and verifies they
   come back unchanged.
6. Tears everything down. The work directory is deleted on success and kept (and
   printed) on failure.

The binaries are run directly from target\<profile>, never through cargo: this script
changes USERPROFILE for p2pctl, which would break a rustup-proxied `cargo run`.

Windows PowerShell 5.1 compatible. Exit code 0 means the round trip succeeded.

.PARAMETER MosquittoBin
mosquitto.exe to run. Defaults to $env:P2P_MOSQUITTO_BIN, then mosquitto on PATH, then
%ProgramFiles%\mosquitto\mosquitto.exe (where the Chocolatey package installs it).

.PARAMETER OpenSslBin
openssl.exe to use. Defaults to openssl on PATH, then the copy bundled with Git for
Windows.

.PARAMETER TargetDir
Directory holding p2pctl.exe, p2p-offer.exe and p2p-answer.exe. Defaults to
<repo>\target\debug.

.PARAMETER TimeoutSeconds
How long each wait (broker up, daemons steady, round trip) may take.

.PARAMETER KeepWorkDir
Keep the work directory (certs, configs, logs) even on success.

.EXAMPLE
cargo build -p p2p-offer -p p2p-answer -p p2pctl
powershell -NoProfile -ExecutionPolicy Bypass -File tests\e2e\windows\mqtts-loopback.ps1
#>
[CmdletBinding()]
param(
    [string]$MosquittoBin,
    [string]$OpenSslBin,
    [string]$TargetDir,
    [int]$TimeoutSeconds = 60,
    [switch]$KeepWorkDir
)

Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$OfferPeer = 'offer-home'
$AnswerPeer = 'answer-office'
$ForwardId = 'web'

function Write-Step([string]$Message) {
    Write-Host "[mqtts-loopback] $Message" -ForegroundColor Cyan
}

function Write-Failure([string]$Message) {
    Write-Host "[mqtts-loopback FAIL] $Message" -ForegroundColor Red
}

# Runs a native executable and throws with its combined output if it exits non-zero.
# ErrorActionPreference is relaxed for the call because Windows PowerShell 5.1 turns
# every redirected stderr line into an ErrorRecord, which 'Stop' would throw on even
# when the program succeeds.
function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$Exe,
        [string[]]$Arguments = @(),
        [Parameter(Mandatory = $true)][string]$What
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $Exe @Arguments 2>&1 | ForEach-Object { "$_" }
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($exitCode -ne 0) {
        throw "$What failed (exit $exitCode):`n$($output -join "`n")"
    }
    return $output
}

function Resolve-Tool {
    param([string]$Explicit, [string]$EnvValue, [string]$CommandName, [string[]]$Fallbacks)
    if ($Explicit) {
        if (-not (Test-Path -LiteralPath $Explicit)) { throw "$Explicit does not exist" }
        return (Resolve-Path -LiteralPath $Explicit).Path
    }
    if ($EnvValue) {
        if (-not (Test-Path -LiteralPath $EnvValue)) { throw "$EnvValue does not exist" }
        return (Resolve-Path -LiteralPath $EnvValue).Path
    }
    $command = Get-Command $CommandName -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($command) { return $command.Source }
    foreach ($candidate in $Fallbacks) {
        if ($candidate -and (Test-Path -LiteralPath $candidate)) { return $candidate }
    }
    return $null
}

function Get-FreePort {
    $listener = New-Object System.Net.Sockets.TcpListener -ArgumentList ([System.Net.IPAddress]::Loopback), 0
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}

function Test-TcpPort([int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect([System.Net.IPAddress]::Loopback, $Port)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

# A Windows path as a TOML literal string. Literal strings take backslashes verbatim;
# the only thing they cannot hold is a single quote.
function ConvertTo-TomlLiteral([string]$Path) {
    if ($Path.Contains("'")) { throw "path contains a single quote, cannot be a TOML literal: $Path" }
    return "'$Path'"
}

function Get-LogTail([string]$Path, [int]$Lines = 40) {
    if (Test-Path -LiteralPath $Path) {
        return ((Get-Content -LiteralPath $Path -Tail $Lines) -join "`n")
    }
    return '(no log)'
}

function Start-Logged {
    param([string]$Exe, [string]$Arguments, [string]$LogBase)
    return Start-Process -FilePath $Exe -ArgumentList $Arguments -NoNewWindow -PassThru `
        -RedirectStandardOutput "$LogBase.out.log" -RedirectStandardError "$LogBase.err.log"
}

function Assert-Running([System.Diagnostics.Process]$Process, [string]$Label, [string]$LogBase) {
    if ($Process.HasExited) {
        throw "$Label exited early (exit $($Process.ExitCode)).`n--- stdout ---`n" +
            (Get-LogTail "$LogBase.out.log") + "`n--- stderr ---`n" + (Get-LogTail "$LogBase.err.log")
    }
}

function Wait-Status {
    param([string]$StatusFile, [string]$Expected, [string]$Label,
        [System.Diagnostics.Process]$Process, [string]$LogBase)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $last = '(no status file yet)'
    while ((Get-Date) -lt $deadline) {
        Assert-Running $Process $Label $LogBase
        if (Test-Path -LiteralPath $StatusFile) {
            try {
                $status = Get-Content -LiteralPath $StatusFile -Raw | ConvertFrom-Json
                $last = $status.current_state
                if ($last -eq $Expected) { return }
            } catch {
                # The daemon may be mid-write; try again.
                $last = '(unparseable status file)'
            }
        }
        Start-Sleep -Milliseconds 250
    }
    throw "$Label never reached '$Expected' within ${TimeoutSeconds}s (last: $last).`n" +
        (Get-LogTail "$LogBase.out.log")
}

# One client round trip through the offer's forwarded port. Returns the echoed bytes,
# or throws.
function Invoke-RoundTrip([int]$Port, [byte[]]$Payload) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect([System.Net.IPAddress]::Loopback, $Port)
        $stream = $client.GetStream()
        $stream.ReadTimeout = 20000
        $stream.Write($Payload, 0, $Payload.Length)
        $received = New-Object byte[] $Payload.Length
        $offset = 0
        while ($offset -lt $Payload.Length) {
            $n = $stream.Read($received, $offset, $Payload.Length - $offset)
            if ($n -le 0) { throw "connection closed after $offset of $($Payload.Length) bytes" }
            $offset += $n
        }
        return , $received
    } finally {
        $client.Close()
    }
}

# Echo target served from a separate runspace so it runs alongside this script. It
# handles one connection at a time, which is all the tunnel needs here, and exits when
# the listener is stopped during teardown.
$EchoScript = {
    param($Listener)
    while ($true) {
        try { $client = $Listener.AcceptTcpClient() } catch { return }
        try {
            $stream = $client.GetStream()
            $stream.ReadTimeout = 30000
            $buffer = New-Object byte[] 4096
            while ($true) {
                $n = $stream.Read($buffer, 0, $buffer.Length)
                if ($n -le 0) { break }
                $stream.Write($buffer, 0, $n)
            }
        } catch {
            # Client reset or idle timeout: drop it and accept the next one.
            $null = $_
        } finally {
            $client.Close()
        }
    }
}

# --- prerequisites -------------------------------------------------------------------

if (-not $TargetDir) { $TargetDir = Join-Path $RepoRoot 'target\debug' }
$P2pctl = Join-Path $TargetDir 'p2pctl.exe'
$OfferExe = Join-Path $TargetDir 'p2p-offer.exe'
$AnswerExe = Join-Path $TargetDir 'p2p-answer.exe'
foreach ($bin in @($P2pctl, $OfferExe, $AnswerExe)) {
    if (-not (Test-Path -LiteralPath $bin)) {
        Write-Failure "$bin not found. Build first: cargo build -p p2p-offer -p p2p-answer -p p2pctl"
        exit 1
    }
}

$programFilesMosquitto = $null
if ($env:ProgramFiles) { $programFilesMosquitto = Join-Path $env:ProgramFiles 'mosquitto\mosquitto.exe' }
$Mosquitto = Resolve-Tool -Explicit $MosquittoBin -EnvValue $env:P2P_MOSQUITTO_BIN `
    -CommandName 'mosquitto' -Fallbacks @($programFilesMosquitto)
if (-not $Mosquitto) {
    Write-Failure ('mosquitto not found. Install it (e.g. `choco install mosquitto -y`), then ' +
        'pass -MosquittoBin or set P2P_MOSQUITTO_BIN.')
    exit 1
}

$gitOpenSsl = @()
if ($env:ProgramFiles) {
    $gitOpenSsl += Join-Path $env:ProgramFiles 'Git\mingw64\bin\openssl.exe'
    $gitOpenSsl += Join-Path $env:ProgramFiles 'Git\usr\bin\openssl.exe'
}
$OpenSsl = Resolve-Tool -Explicit $OpenSslBin -EnvValue $null -CommandName 'openssl' -Fallbacks $gitOpenSsl
if (-not $OpenSsl) {
    Write-Failure ('openssl not found. It generates the throwaway broker CA/cert. Git for ' +
        'Windows bundles one (Git\mingw64\bin\openssl.exe); pass -OpenSslBin, or install ' +
        'OpenSSL (e.g. `choco install openssl -y`).')
    exit 1
}

Write-Step "binaries:  $TargetDir"
Write-Step "mosquitto: $Mosquitto"
Write-Step "openssl:   $OpenSsl"

$WorkDir = Join-Path ([System.IO.Path]::GetTempPath()) ("p2p-mqtts-loopback-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $WorkDir | Out-Null
Write-Step "work dir:  $WorkDir"

$processes = @()
$echoListener = $null
$echoPowerShell = $null
$savedUserProfile = $env:USERPROFILE
$succeeded = $false

try {
    # --- TLS: throwaway CA + broker server cert --------------------------------------
    Write-Step 'generating CA + broker server cert'
    $certDir = Join-Path $WorkDir 'certs'
    New-Item -ItemType Directory -Path $certDir | Out-Null
    $caKey = Join-Path $certDir 'ca.key'
    $caCrt = Join-Path $certDir 'ca.crt'
    $serverKey = Join-Path $certDir 'server.key'
    $serverCsr = Join-Path $certDir 'server.csr'
    $serverCrt = Join-Path $certDir 'server.crt'
    $caExt = Join-Path $certDir 'ca.ext'
    $sanExt = Join-Path $certDir 'san.ext'
    # Extension files rather than -addext keep this working on older openssl builds,
    # and avoid passing '=' / ',' through Windows command-line quoting.
    Set-Content -LiteralPath $caExt -Encoding Ascii -Value @(
        'basicConstraints=critical,CA:TRUE',
        'keyUsage=critical,keyCertSign,cRLSign'
    )
    Set-Content -LiteralPath $sanExt -Encoding Ascii -Value @(
        'subjectAltName=DNS:localhost,IP:127.0.0.1',
        'basicConstraints=CA:FALSE',
        'extendedKeyUsage=serverAuth'
    )
    $null = Invoke-Native $OpenSsl @('req', '-newkey', 'rsa:2048', '-nodes', '-keyout', $caKey,
        '-out', (Join-Path $certDir 'ca.csr'), '-subj', '/CN=p2p-mqtts-loopback-ca') 'openssl CA request'
    $null = Invoke-Native $OpenSsl @('x509', '-req', '-in', (Join-Path $certDir 'ca.csr'),
        '-signkey', $caKey, '-out', $caCrt, '-days', '3', '-extfile', $caExt) 'openssl CA self-sign'
    $null = Invoke-Native $OpenSsl @('req', '-newkey', 'rsa:2048', '-nodes', '-keyout', $serverKey,
        '-out', $serverCsr, '-subj', '/CN=localhost') 'openssl server request'
    $null = Invoke-Native $OpenSsl @('x509', '-req', '-in', $serverCsr, '-CA', $caCrt, '-CAkey', $caKey,
        '-CAcreateserial', '-out', $serverCrt, '-days', '3', '-extfile', $sanExt) 'openssl server sign'

    # --- broker ---------------------------------------------------------------------
    $brokerPort = Get-FreePort
    Write-Step "starting mosquitto (TLS) on 127.0.0.1:$brokerPort"
    # Bound to loopback only so Windows Firewall does not prompt. Forward slashes keep
    # the paths unambiguous in mosquitto.conf.
    $mosquittoConf = Join-Path $WorkDir 'mosquitto.conf'
    Set-Content -LiteralPath $mosquittoConf -Encoding Ascii -Value @(
        "listener $brokerPort 127.0.0.1",
        'allow_anonymous true',
        'persistence false',
        "cafile $($caCrt.Replace('\', '/'))",
        "certfile $($serverCrt.Replace('\', '/'))",
        "keyfile $($serverKey.Replace('\', '/'))",
        'require_certificate false'
    )
    $mosquittoLog = Join-Path $WorkDir 'mosquitto'
    $mosquittoProcess = Start-Logged -Exe $Mosquitto -Arguments "-v -c `"$mosquittoConf`"" -LogBase $mosquittoLog
    $processes += $mosquittoProcess
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while (-not (Test-TcpPort $brokerPort)) {
        Assert-Running $mosquittoProcess 'mosquitto' $mosquittoLog
        if ((Get-Date) -gt $deadline) { throw "mosquitto never listened on $brokerPort" }
        Start-Sleep -Milliseconds 200
    }
    Start-Sleep -Milliseconds 500

    # --- identities + cross-authorization ---------------------------------------------
    # Each p2pctl call gets its own throwaway USERPROFILE, so keygen and
    # add-authorized-key write under <home>\.config\p2ptunnel and never touch the real
    # profile.
    Write-Step 'generating identities and cross-authorizing them'
    $offerHome = Join-Path $WorkDir 'home-offer'
    $answerHome = Join-Path $WorkDir 'home-answer'
    $offerCfgDir = Join-Path $offerHome '.config\p2ptunnel'
    $answerCfgDir = Join-Path $answerHome '.config\p2ptunnel'
    New-Item -ItemType Directory -Path $offerHome, $answerHome | Out-Null
    try {
        $env:USERPROFILE = $offerHome
        $null = Invoke-Native $P2pctl @('keygen', $OfferPeer) 'p2pctl keygen (offer)'
        $env:USERPROFILE = $answerHome
        $null = Invoke-Native $P2pctl @('keygen', $AnswerPeer) 'p2pctl keygen (answer)'
        $env:USERPROFILE = $offerHome
        $null = Invoke-Native $P2pctl @('add-authorized-key', (Join-Path $answerCfgDir 'identity.pub')) 'p2pctl add-authorized-key (offer trusts answer)'
        $env:USERPROFILE = $answerHome
        $null = Invoke-Native $P2pctl @('add-authorized-key', (Join-Path $offerCfgDir 'identity.pub')) 'p2pctl add-authorized-key (answer trusts offer)'
    } finally {
        $env:USERPROFILE = $savedUserProfile
    }

    # --- configs ----------------------------------------------------------------------
    $listenPort = Get-FreePort
    $echoListener = New-Object System.Net.Sockets.TcpListener -ArgumentList ([System.Net.IPAddress]::Loopback), 0
    $echoListener.Start()
    $targetPort = $echoListener.LocalEndpoint.Port
    $echoPowerShell = [powershell]::Create()
    $null = $echoPowerShell.AddScript($EchoScript).AddArgument($echoListener)
    $null = $echoPowerShell.BeginInvoke()
    Write-Step "echo target on 127.0.0.1:$targetPort; offer will listen on 127.0.0.1:$listenPort"

    $brokerUrl = "mqtts://localhost:$brokerPort"
    $configs = @{}
    foreach ($role in @('offer', 'answer')) {
        if ($role -eq 'offer') { $cfgDir = $offerCfgDir } else { $cfgDir = $answerCfgDir }
        $stateDir = Join-Path $WorkDir "$role-state"
        $logDir = Join-Path $WorkDir "$role-log"
        New-Item -ItemType Directory -Path $stateDir, $logDir | Out-Null

        $template = Get-Content -LiteralPath (Join-Path $RepoRoot "docs\examples\$role-config.toml") -Raw
        $replacements = [ordered]@{
            '"__IDENTITY__"'        = ConvertTo-TomlLiteral (Join-Path $cfgDir 'identity')
            '"__AUTHORIZED_KEYS__"' = ConvertTo-TomlLiteral (Join-Path $cfgDir 'authorized_keys')
            '"__STATE_DIR__"'       = ConvertTo-TomlLiteral $stateDir
            '"__LOG_DIR__"'         = ConvertTo-TomlLiteral $logDir
            '"__CA_FILE__"'         = ConvertTo-TomlLiteral $caCrt
            '"__LOG_FILE__"'        = ConvertTo-TomlLiteral (Join-Path $logDir 'p2ptunnel.log')
            '"__STATUS_FILE__"'     = ConvertTo-TomlLiteral (Join-Path $stateDir 'status.json')
            '"mqtts://broker.example.com:8883"' = "`"$brokerUrl`""
        }
        $config = $template
        foreach ($key in $replacements.Keys) {
            if (-not $config.Contains($key)) { throw "docs\examples\$role-config.toml no longer contains $key" }
            $config = $config.Replace($key, $replacements[$key])
        }
        # Loopback only: no public STUN server.
        $config = [regex]::Replace($config, '(?m)^stun_urls = [^\r\n]*', 'stun_urls = []')
        # Replace the example's forwards (ssh / web-ui) with the one forward under test.
        if ($role -eq 'offer') {
            $forward = "[[forwards]]`nid = `"$ForwardId`"`n`n[forwards.offer]`n" +
                "listen_host = `"127.0.0.1`"`nlisten_port = $listenPort`n`n"
        } else {
            $forward = "[[forwards]]`nid = `"$ForwardId`"`n`n[forwards.answer]`n" +
                "target_host = `"127.0.0.1`"`ntarget_port = $targetPort`n" +
                "allow_remote_peers = [`"$OfferPeer`"]`n`n"
        }
        $withForward = [regex]::Replace($config, '(?s)\[\[forwards\]\].*?(?=^\[reconnect\])', $forward,
            [System.Text.RegularExpressions.RegexOptions]::Multiline)
        if ($withForward -eq $config) { throw "could not find the [[forwards]] block in $role-config.toml" }

        $configPath = Join-Path $WorkDir "$role.toml"
        # UTF-8 without a BOM: Windows PowerShell 5.1's -Encoding UTF8 writes a BOM.
        [System.IO.File]::WriteAllText($configPath, $withForward, (New-Object System.Text.UTF8Encoding $false))
        $null = Invoke-Native $P2pctl @('check-config', '--config', $configPath) "p2pctl check-config ($role)"
        $configs[$role] = @{ Path = $configPath; Status = (Join-Path $stateDir 'status.json') }
    }

    # --- daemons ----------------------------------------------------------------------
    Write-Step 'starting p2p-answer and p2p-offer'
    $answerLog = Join-Path $WorkDir 'answer'
    $offerLog = Join-Path $WorkDir 'offer'
    $answerProcess = Start-Logged -Exe $AnswerExe -Arguments "run --config `"$($configs['answer'].Path)`"" -LogBase $answerLog
    $processes += $answerProcess
    $offerProcess = Start-Logged -Exe $OfferExe -Arguments "run --config `"$($configs['offer'].Path)`"" -LogBase $offerLog
    $processes += $offerProcess

    Wait-Status -StatusFile $configs['answer'].Status -Expected 'serving' -Label 'p2p-answer' `
        -Process $answerProcess -LogBase $answerLog
    Wait-Status -StatusFile $configs['offer'].Status -Expected 'waiting_for_local_client' -Label 'p2p-offer' `
        -Process $offerProcess -LogBase $offerLog
    Write-Step 'both daemons reached steady state over the TLS broker'

    # --- data through the tunnel ------------------------------------------------------
    $payloadText = "GET /health HTTP/1.0`r`n`r`nhello-through-the-tunnel-" + [guid]::NewGuid().ToString('N')
    $payload = [System.Text.Encoding]::ASCII.GetBytes($payloadText)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $echoed = $null
    $lastError = ''
    while ((Get-Date) -lt $deadline) {
        Assert-Running $offerProcess 'p2p-offer' $offerLog
        Assert-Running $answerProcess 'p2p-answer' $answerLog
        try {
            $echoed = Invoke-RoundTrip $listenPort $payload
            break
        } catch {
            $lastError = $_.Exception.Message
            Start-Sleep -Milliseconds 500
        }
    }
    if ($null -eq $echoed) { throw "tunnel round trip never succeeded; last error: $lastError" }
    $echoedText = [System.Text.Encoding]::ASCII.GetString($echoed)
    if ($echoedText -ne $payloadText) {
        throw "tunnel corrupted the payload.`n sent: $payloadText`n got:  $echoedText"
    }
    Write-Step "PASS: $($payload.Length) bytes round-tripped through mqtts signaling + WebRTC"
    $succeeded = $true
} catch {
    Write-Failure $_.Exception.Message
    foreach ($base in @('mosquitto', 'answer', 'offer')) {
        $logBase = Join-Path $WorkDir $base
        if (Test-Path -LiteralPath "$logBase.err.log") {
            Write-Host "--- $base stderr (tail) ---"
            Write-Host (Get-LogTail "$logBase.err.log" 20)
        }
    }
} finally {
    Write-Step 'tearing down'
    $env:USERPROFILE = $savedUserProfile
    foreach ($process in $processes) {
        if ($process -and -not $process.HasExited) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            $null = $process.WaitForExit(5000)
        }
    }
    if ($echoListener) { $echoListener.Stop() }
    if ($echoPowerShell) { $echoPowerShell.Dispose() }
    if ($succeeded -and -not $KeepWorkDir) {
        Remove-Item -LiteralPath $WorkDir -Recurse -Force -ErrorAction SilentlyContinue
    } else {
        Write-Step "kept work dir (certs, configs, logs): $WorkDir"
    }
}

if ($succeeded) { exit 0 }
exit 1
