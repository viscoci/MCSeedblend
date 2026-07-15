# SeedBlend headless integration fixture (spec section 20).
#
# Drives a real dedicated dev server (Fabric or NeoForge) through the full reseed
# lifecycle using RCON for console commands (stdin forwarding through Gradle is not
# reliable across loaders) and logs/latest.log for startup-time assertions:
#   Phase 1: fresh world, seed A. Verify passive mode. Stage reseed to seed B.
#   Phase 2: restart. Transaction applies. Force-generate boundary + far chunks.
#   Phase 3: restart. Verify epochs, blending metadata, idempotence. Stage seed C.
#   Phase 4: restart. Verify multi-reseed (epochs 0 and 1 both old under epoch 2).
#
# Usage: powershell -ExecutionPolicy Bypass -File fixture\reseed-fixture.ps1 [-Loader neoforge]
# Exit code 0 = all assertions passed.

param(
    [ValidateSet('fabric', 'neoforge')][string]$Loader = 'fabric',
    # '' = the 1.21.1 build at the repo root; 'mc26.1' = the Minecraft 26.1 build.
    [string]$ProjectSubdir = '',
    [string]$JavaHome = 'C:\Users\ethan\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
if ($ProjectSubdir -ne '') { $RepoRoot = Join-Path $RepoRoot $ProjectSubdir }
$RunDir = Join-Path $RepoRoot "$Loader\run"
$StateFile = Join-Path $RunDir 'world\seedblend\state.json'
$LogFile = Join-Path $RunDir 'logs\latest.log'
$Jdk21 = $JavaHome
$RconPort = 25599
$RconPassword = 'seedblend-fixture'
$SeedA = 111111
$SeedB = 222222
$SeedC = 333333

$script:Failures = @()
function Assert($cond, $msg) {
    if ($cond) { Write-Host "  [PASS] $msg" -ForegroundColor Green }
    else { Write-Host "  [FAIL] $msg" -ForegroundColor Red; $script:Failures += $msg }
}

# ---------- RCON client (vanilla Source RCON protocol) ----------

function New-RconPacket([int]$id, [int]$type, [string]$body) {
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $len = 4 + 4 + $bodyBytes.Length + 2
    $ms = New-Object System.IO.MemoryStream
    $bw = New-Object System.IO.BinaryWriter($ms)
    $bw.Write([int]$len); $bw.Write([int]$id); $bw.Write([int]$type)
    $bw.Write($bodyBytes); $bw.Write([byte]0); $bw.Write([byte]0)
    $bw.Flush()
    return $ms.ToArray()
}

function Read-RconPacket($stream) {
    $header = New-Object byte[] 4
    $read = 0
    while ($read -lt 4) {
        $n = $stream.Read($header, $read, 4 - $read)
        if ($n -le 0) { throw 'RCON connection closed' }
        $read += $n
    }
    $len = [BitConverter]::ToInt32($header, 0)
    $payload = New-Object byte[] $len
    $read = 0
    while ($read -lt $len) {
        $n = $stream.Read($payload, $read, $len - $read)
        if ($n -le 0) { throw 'RCON connection closed mid-packet' }
        $read += $n
    }
    return @{
        Id   = [BitConverter]::ToInt32($payload, 0)
        Type = [BitConverter]::ToInt32($payload, 4)
        Body = [System.Text.Encoding]::UTF8.GetString($payload, 8, $len - 10)
    }
}

$script:Rcon = $null

function Connect-Rcon($timeoutSec) {
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSec)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $client = New-Object System.Net.Sockets.TcpClient
            $client.Connect('127.0.0.1', $RconPort)
            $stream = $client.GetStream()
            $stream.ReadTimeout = 30000
            $bytes = New-RconPacket 1 3 $RconPassword
            $stream.Write($bytes, 0, $bytes.Length)
            $resp = Read-RconPacket $stream
            if ($resp.Id -eq -1) { throw 'RCON auth failed' }
            $script:Rcon = @{ Client = $client; Stream = $stream; NextId = 2 }
            return
        } catch {
            if ($null -ne $client) { $client.Close() }
            Start-Sleep -Seconds 2
        }
    }
    throw "Could not connect to RCON on port $RconPort within ${timeoutSec}s"
}

# Sends a console command over RCON and returns the command's text response.
function Invoke-Server([string]$cmd) {
    $id = $script:Rcon.NextId++
    $bytes = New-RconPacket $id 2 $cmd
    $script:Rcon.Stream.Write($bytes, 0, $bytes.Length)
    $resp = Read-RconPacket $script:Rcon.Stream
    return $resp.Body
}

function Close-Rcon {
    if ($null -ne $script:Rcon) {
        $script:Rcon.Client.Close()
        $script:Rcon = $null
    }
}

# ---------- server process ----------

function Start-DevServer {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = Join-Path $RepoRoot 'gradlew.bat'
    $psi.Arguments = ":${Loader}:runServer --console=plain"
    $psi.WorkingDirectory = $RepoRoot
    $psi.UseShellExecute = $false
    $psi.EnvironmentVariables['JAVA_HOME'] = $Jdk21
    return [System.Diagnostics.Process]::Start($psi)
}

function Wait-ServerReady($proc) {
    Connect-Rcon 600
    if ($proc.HasExited) { throw 'Server process exited during startup' }
}

function Stop-DevServer($proc) {
    try {
        if ($null -ne $script:Rcon -and -not $proc.HasExited) {
            try { [void](Invoke-Server 'stop') } catch {}
        }
    } finally { Close-Rcon }
    if (-not $proc.WaitForExit(180000)) {
        & taskkill /T /F /PID $proc.Id | Out-Null
        throw 'Server did not stop cleanly; process tree killed'
    }
    Start-Sleep -Seconds 2
}

function Get-State {
    if (-not (Test-Path $StateFile)) { return $null }
    Get-Content $StateFile -Raw | ConvertFrom-Json
}

function Get-LogText {
    if (-not (Test-Path $LogFile)) { return '' }
    $fs = [System.IO.File]::Open($LogFile, 'Open', 'Read', 'ReadWrite')
    try {
        $reader = New-Object System.IO.StreamReader($fs)
        return $reader.ReadToEnd()
    } finally { $fs.Close() }
}

# ---------- Setup: clean run dir, eula, deterministic seed, RCON ----------
Write-Host "== Setup ($Loader): clean world, seed A = $SeedA ==" -ForegroundColor Cyan
if (Test-Path $RunDir) { Remove-Item -Recurse -Force $RunDir }
New-Item -ItemType Directory -Force $RunDir | Out-Null
Set-Content -Path (Join-Path $RunDir 'eula.txt') -Value 'eula=true' -Encoding ascii
@"
level-seed=$SeedA
online-mode=false
level-name=world
spawn-protection=0
view-distance=6
enable-rcon=true
rcon.port=$RconPort
rcon.password=$RconPassword
broadcast-rcon-to-ops=false
"@ | Set-Content -Path (Join-Path $RunDir 'server.properties') -Encoding ascii

# ---------- Phase 1 ----------
Write-Host "== Phase 1: fresh world on seed A, stage reseed to seed B ==" -ForegroundColor Cyan
$p = Start-DevServer
try {
    Wait-ServerReady $p

    # Guarantee a known epoch-0 area around chunk (0,0) regardless of where spawn is.
    # forceload persists, so these chunks reload in every later phase. Same for a
    # Nether area, to exercise transition blending outside the Overworld.
    [void](Invoke-Server 'forceload add 0 0 144 144')
    [void](Invoke-Server 'execute in minecraft:the_nether run forceload add 0 0 64 64')
    Start-Sleep -Seconds 15
    [void](Invoke-Server 'save-all flush')

    $out = Invoke-Server 'seedblend status'
    Assert ($out -match 'passive') 'P1: status reports passive mode before any commit'

    $out = Invoke-Server "seedblend plan $SeedB"
    Assert ($out -match 'canonical world seed will change') 'P1: plan prints the seed-change warning'
    Assert ($out -match 'backup') 'P1: plan recommends a backup'
    $m = [regex]::Match($out, '/seedblend commit ([0-9A-Fa-f]{6})')
    Assert $m.Success 'P1: plan issued a commit token'

    $out = Invoke-Server "seedblend commit $($m.Groups[1].Value)"
    Assert ($out -match 'RESTART REQUIRED') 'P1: commit staged the transaction (restart required)'
} finally { Stop-DevServer $p }

$state = Get-State
Assert ($null -ne $state) 'P1: state.json exists after commit'
Assert ($state.activeEpoch -eq 0) 'P1: active epoch still 0 (nothing applied before restart)'
Assert ($state.pendingTransaction.state -eq 'STAGED') 'P1: transaction is STAGED'
Assert ($state.pendingTransaction.targetSeed -eq $SeedB) 'P1: staged target seed is B'
Assert ($state.pendingTransaction.targetEpoch -eq 1) 'P1: staged target epoch is 1'
Assert ($state.originalSeed -eq $SeedA) 'P1: original seed recorded as A'

# ---------- Phase 2 ----------
Write-Host "== Phase 2: restart applies seed B; generate boundary + far chunks ==" -ForegroundColor Cyan
$p = Start-DevServer
try {
    Wait-ServerReady $p
    Start-Sleep -Seconds 3   # let SERVER_STARTED finalization land in the log
    $log = Get-LogText
    Assert ($log -match 'Applying staged reseed transaction') 'P2: staged transaction detected at startup'
    Assert ($log -match 'finalized') 'P2: transaction finalized after startup verification'
    Assert ($log -match 'Active epoch: 1') 'P2: startup summary reports epoch 1'
    Assert ($log -match 'Active generation seed: 222222') 'P2: startup summary reports seed B'
    Assert ($log -match 'Native blending enabled for minecraft:overworld') 'P2: overworld blending enabled'

    # New chunks adjacent to the old area (transition zone), a detached strip, and far
    # away (block coords; chunk = 16 blocks). Nether strip adjacent to its old area.
    [void](Invoke-Server 'forceload add 160 0 240 144')
    [void](Invoke-Server 'forceload add 256 0 400 144')
    [void](Invoke-Server 'forceload add 100000 100000 100016 100016')
    [void](Invoke-Server 'execute in minecraft:the_nether run forceload add 80 0 144 64')
    Start-Sleep -Seconds 20
    [void](Invoke-Server 'save-all flush')
    Start-Sleep -Seconds 5

    $out = Invoke-Server 'seedblend inspect chunk 0 0'
    Assert ($out -match 'Serialized generation epoch: 0') 'P2: chunk (0,0) kept epoch 0'
    Assert ($out -match 'Considered old: yes') 'P2: chunk (0,0) is old under epoch 1'
    Assert ($out -match 'Blending data present: yes' -or $out -match 'would be injected on load: yes') 'P2: old completed chunk is a blending source'

    $out = Invoke-Server 'seedblend inspect chunk 6250 6250'
    Assert ($out -match 'Serialized generation epoch: 1') 'P2: far new chunk stamped with epoch 1'
    Assert ($out -match 'Considered old: no') 'P2: far new chunk is current'
    Assert ($out -match 'Blending data present: no') 'P2: new chunk has no blending_data'
    Assert ($out -match 'Transition weight: 0%') 'P2: far chunk generated with no transition weight'

    # Transition zone. Note: forceload's entity-ticking ring promotes +2 chunks beyond
    # the P1 strip to FULL, so old terrain extends through chunk 11; the first NEW
    # chunk is 12, bordering old chunk 11 -> near-full old-seed weight (seamless side).
    $out = Invoke-Server 'seedblend inspect chunk 12 5'
    Assert ($out -match 'Serialized generation epoch: 1') 'P2: boundary chunk is epoch 1'
    Assert ($out -match 'Transition weight: (100|[1-9][0-9]?)%') 'P2: boundary chunk generated inside the transition zone'
    $m = [regex]::Match($out, 'Transition weight: (\d+)%')
    Assert ([int]$m.Groups[1].Value -ge 90) 'P2: weight at the old boundary is near 100 (seamless side)'

    # Normalization: weight decreases with distance from the boundary (range 4).
    $out = Invoke-Server 'seedblend inspect chunk 14 5'
    $m2 = [regex]::Match($out, 'Transition weight: (\d+)%')
    Assert ($m2.Success -and [int]$m2.Groups[1].Value -gt 0) 'P2: mid-transition chunk has partial weight'
    Assert ([int]$m2.Groups[1].Value -lt [int]$m.Groups[1].Value) 'P2: weight normalizes downward across the range'

    # Nether: transition blending works without any blending_data.
    $out = Invoke-Server 'seedblend inspect chunk 2 2 minecraft:the_nether'
    Assert ($out -match 'Serialized generation epoch: 0') 'P2: old nether chunk kept epoch 0'
    Assert ($out -match 'Considered old: yes') 'P2: old nether chunk classified old'
    Assert ($out -match 'Blending data present: no') 'P2: nether never receives synthetic blending_data'
    $out = Invoke-Server 'seedblend inspect chunk 7 2 minecraft:the_nether'
    Assert ($out -match 'Serialized generation epoch: 1') 'P2: new nether chunk stamped epoch 1'
    Assert ($out -match 'Transition weight: (100|[1-9][0-9]?)%') 'P2: nether boundary chunk transition-blended'

    $out = Invoke-Server 'seedblend verify'
    Assert ($out -match 'Result: OK') 'P2: verify reports OK'
    Assert ($out -match 'blendingInjected=[1-9]') 'P2: synthetic blending injected for loaded old chunks'
    Assert ($out -match 'oldCompleted=[1-9]') 'P2: old completed chunks identified'
    Assert ($out -match 'transitionChunks=[1-9]') 'P2: transition chunks were generated'
} finally { Stop-DevServer $p }

$state = Get-State
Assert ($state.activeEpoch -eq 1) 'P2: active epoch is 1 after finalize'
Assert ($state.activeSeed -eq $SeedB) 'P2: active seed is B'
Assert ($state.previousSeed -eq $SeedA) 'P2: previous seed recorded as A'
Assert ($null -eq $state.pendingTransaction) 'P2: no pending transaction after finalize'

# ---------- Phase 3 ----------
Write-Host "== Phase 3: restart on epoch 1; verify persistence; stage seed C ==" -ForegroundColor Cyan
$p = Start-DevServer
try {
    Wait-ServerReady $p
    Start-Sleep -Seconds 3
    $log = Get-LogText
    Assert ($log -notmatch 'Applying staged') 'P3: no transaction re-applied (idempotent)'

    $out = Invoke-Server 'seedblend inspect chunk 0 0'
    Assert ($out -match 'Serialized generation epoch: 0') 'P3: epoch 0 chunk immutable across restarts'
    Assert ($out -match 'Blending data present: yes' -or $out -match 'would be injected on load: yes') 'P3: old chunk remains a blending source'

    $out = Invoke-Server 'seedblend inspect chunk 6250 6250'
    Assert ($out -match 'Serialized generation epoch: 1') 'P3: epoch 1 chunk persisted'

    $out = Invoke-Server 'seedblend inspect chunk 12 5'
    Assert ($out -match 'Transition weight: (100|[1-9][0-9]?)%') 'P3: transition weight persisted across restart'

    $out = Invoke-Server 'seedblend verify'
    Assert ($out -match 'Result: OK') 'P3: verify OK after restart'

    $out = Invoke-Server "seedblend plan $SeedC"
    $m = [regex]::Match($out, '/seedblend commit ([0-9A-Fa-f]{6})')
    Assert $m.Success 'P3: second plan issued a token'
    $out = Invoke-Server "seedblend commit $($m.Groups[1].Value)"
    Assert ($out -match 'RESTART REQUIRED') 'P3: second reseed staged'
} finally { Stop-DevServer $p }

# ---------- Phase 4 ----------
Write-Host "== Phase 4: second reseed - epochs 0 and 1 both old under epoch 2 ==" -ForegroundColor Cyan
$p = Start-DevServer
try {
    Wait-ServerReady $p
    Start-Sleep -Seconds 3

    $out = Invoke-Server 'seedblend inspect chunk 0 0'
    Assert ($out -match 'Considered old: yes') 'P4: epoch 0 chunk old under epoch 2'

    $out = Invoke-Server 'seedblend inspect chunk 6250 6250'
    Assert ($out -match 'Serialized generation epoch: 1') 'P4: epoch 1 chunk epoch immutable'
    Assert ($out -match 'Considered old: yes') 'P4: epoch 1 chunk old under epoch 2'

    $out = Invoke-Server 'seedblend status'
    Assert ($out -match 'Active epoch: 2') 'P4: active epoch is 2'

    $out = Invoke-Server 'seedblend verify'
    Assert ($out -match 'Result: OK') 'P4: verify OK'
} finally { Stop-DevServer $p }

$state = Get-State
Assert ($state.activeEpoch -eq 2) 'P4: state epoch 2'
Assert ($state.activeSeed -eq $SeedC) 'P4: active seed C'
Assert ($state.originalSeed -eq $SeedA) 'P4: original seed still A'

# ---------- Summary ----------
Write-Host ''
if ($script:Failures.Count -eq 0) {
    Write-Host "FIXTURE RESULT ($Loader): ALL ASSERTIONS PASSED" -ForegroundColor Green
    exit 0
} else {
    Write-Host "FIXTURE RESULT ($Loader): $($script:Failures.Count) FAILURES" -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host " - $_" -ForegroundColor Red }
    exit 1
}
