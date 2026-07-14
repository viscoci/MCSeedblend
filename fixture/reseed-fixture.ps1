# SeedBlend headless integration fixture (spec section 20).
#
# Drives a real Fabric dedicated dev server through the full reseed lifecycle:
#   Phase 1: fresh world, seed A. Verify passive mode. Stage reseed to seed B.
#   Phase 2: restart. Transaction applies. Force-generate boundary + far chunks.
#   Phase 3: restart. Verify epochs, blending metadata, seed. Stage reseed to seed C.
#   Phase 4: restart. Verify multi-reseed (epochs 0 and 1 both old under epoch 2).
#
# Usage: powershell -ExecutionPolicy Bypass -File fixture\reseed-fixture.ps1
# Exit code 0 = all assertions passed.

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$RunDir = Join-Path $RepoRoot 'fabric\run'
$StateFile = Join-Path $RunDir 'world\seedblend\state.json'
$Jdk21 = 'C:\Users\ethan\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
$SeedA = 111111
$SeedB = 222222
$SeedC = 333333

$script:Failures = @()
function Assert($cond, $msg) {
    if ($cond) { Write-Host "  [PASS] $msg" -ForegroundColor Green }
    else { Write-Host "  [FAIL] $msg" -ForegroundColor Red; $script:Failures += $msg }
}

$script:ReadTask = $null

function Start-DevServer {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = Join-Path $RepoRoot 'gradlew.bat'
    $psi.Arguments = ':fabric:runServer --console=plain'
    $psi.WorkingDirectory = $RepoRoot
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $false
    $psi.EnvironmentVariables['JAVA_HOME'] = $Jdk21
    # .NET Framework derives the child's stdin encoding from the parent console; a
    # UTF-8 preamble (BOM) would corrupt the first console command otherwise.
    try { [Console]::InputEncoding = New-Object System.Text.ASCIIEncoding } catch {}
    $proc = [System.Diagnostics.Process]::Start($psi)
    $script:ReadTask = $null
    # Sacrificial first line: absorbs any BOM the writer still emits. The server logs
    # an ignorable unknown-command error at worst.
    $proc.StandardInput.WriteLine('')
    $proc.StandardInput.Flush()
    return $proc
}

# Reads one line with a soft timeout; a pending read survives across calls so the
# stream is never issued two concurrent ReadLineAsync operations.
function Read-ServerLine($proc, $waitMs) {
    if ($null -eq $script:ReadTask) {
        $script:ReadTask = $proc.StandardOutput.ReadLineAsync()
    }
    if (-not $script:ReadTask.Wait($waitMs)) { return $null }   # still pending; retry later
    $line = $script:ReadTask.Result
    $script:ReadTask = $null
    return $line
}

# Reads server stdout until $pattern matches or timeout; returns all lines read.
function Wait-ForOutput($proc, $pattern, $timeoutSec) {
    $lines = New-Object System.Collections.ArrayList
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSec)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($proc.HasExited -and $null -eq $script:ReadTask) { break }
        $line = Read-ServerLine $proc 2000
        if ($null -eq $line) { continue }
        [void]$lines.Add($line)
        if ($line -match $pattern) { return ,$lines }
    }
    throw "Timed out after ${timeoutSec}s waiting for pattern: $pattern`nLast lines:`n$(($lines | Select-Object -Last 15) -join "`n")"
}

function Send-Command($proc, $cmd) {
    $proc.StandardInput.WriteLine($cmd)
    $proc.StandardInput.Flush()
}

function Stop-DevServer($proc) {
    if (-not $proc.HasExited) {
        Send-Command $proc 'stop'
        # Keep draining stdout so the pipe never fills while the server shuts down.
        $deadline = [DateTime]::UtcNow.AddSeconds(120)
        while (-not $proc.HasExited -and [DateTime]::UtcNow -lt $deadline) {
            [void](Read-ServerLine $proc 1000)
        }
        if (-not $proc.HasExited) {
            & taskkill /T /F /PID $proc.Id | Out-Null
            throw 'Server did not stop cleanly'
        }
    }
    Start-Sleep -Seconds 2
}

function Get-State {
    if (-not (Test-Path $StateFile)) { return $null }
    Get-Content $StateFile -Raw | ConvertFrom-Json
}

# ---------- Setup: clean run dir, eula, deterministic seed ----------
Write-Host "== Setup: clean world, seed A = $SeedA ==" -ForegroundColor Cyan
if (Test-Path $RunDir) { Remove-Item -Recurse -Force $RunDir }
New-Item -ItemType Directory -Force $RunDir | Out-Null
Set-Content -Path (Join-Path $RunDir 'eula.txt') -Value 'eula=true' -Encoding ascii
@"
level-seed=$SeedA
online-mode=false
level-name=world
spawn-protection=0
view-distance=6
"@ | Set-Content -Path (Join-Path $RunDir 'server.properties') -Encoding ascii

# ---------- Phase 1 ----------
Write-Host "== Phase 1: fresh world on seed A, stage reseed to seed B ==" -ForegroundColor Cyan
$p = Start-DevServer
try {
    [void](Wait-ForOutput $p 'Done \(' 600)

    # Guarantee a known epoch-0 area exists around chunk (0,0) regardless of where the
    # seed puts world spawn. forceload persists, so these chunks reload in every phase.
    Send-Command $p 'forceload add 0 0 144 144'
    [void](Wait-ForOutput $p 'Marked|forceloaded|force loaded' 120)
    Start-Sleep -Seconds 10
    Send-Command $p 'save-all flush'
    [void](Wait-ForOutput $p 'Saved the game' 120)

    Send-Command $p 'seedblend status'
    $out = Wait-ForOutput $p 'Blending dimensions' 30
    Assert (($out -join ' ') -match 'passive') 'P1: status reports passive mode before any commit'

    Send-Command $p "seedblend plan $SeedB"
    $out = Wait-ForOutput $p 'seedblend commit' 30
    $tokenLine = $out | Where-Object { $_ -match '/seedblend commit ([0-9A-Fa-f]{6})' } | Select-Object -Last 1
    Assert ($null -ne $tokenLine) 'P1: plan issued a commit token'
    $token = [regex]::Match($tokenLine, '/seedblend commit ([0-9A-Fa-f]{6})').Groups[1].Value

    Send-Command $p "seedblend commit $token"
    $out = Wait-ForOutput $p 'RESTART REQUIRED' 30
    Assert (($out -join ' ') -match 'Reseed staged') 'P1: commit staged the transaction'
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
    $startupLines = Wait-ForOutput $p 'Done \(' 600
    $joined = $startupLines -join "`n"
    Assert ($joined -match 'Applying staged reseed transaction') 'P2: staged transaction detected at startup'
    $finalized = $startupLines | Where-Object { $_ -match 'finalized' }
    if (-not $finalized) {
        # Finalization logs right after Done via SERVER_STARTED; read a bit more.
        $more = Wait-ForOutput $p 'Reseed transaction .* finalized|Active epoch: 1' 60
        $joined += "`n" + ($more -join "`n")
    }
    Assert ($joined -match 'finalized') 'P2: transaction finalized after startup verification'

    # Boundary chunks: spawn area exists from P1 (epoch 0). Force new generation adjacent
    # to it and far away (block coords; 1 chunk = 16 blocks).
    Send-Command $p 'forceload add 256 0 400 144'      # near boundary, new under epoch 1
    [void](Wait-ForOutput $p 'Marked|forceloaded' 60)
    Send-Command $p 'forceload add 100000 100000 100016 100016'  # far from any old terrain
    [void](Wait-ForOutput $p 'Marked|forceloaded' 60)
    Start-Sleep -Seconds 10   # let generation finish
    Send-Command $p 'save-all flush'
    [void](Wait-ForOutput $p 'Saved the game' 120)

    Send-Command $p 'seedblend inspect chunk 0 0'
    $out = Wait-ForOutput $p 'Blending sections' 30
    $text = $out -join "`n"
    Assert ($text -match 'Serialized generation epoch: 0') 'P2: chunk (0,0) kept epoch 0'
    Assert ($text -match 'Considered old: yes') 'P2: chunk (0,0) is old under epoch 1'
    # Injection happens on load; disk persistence follows the chunk's next save. Either
    # the tag already landed on disk, or inspect confirms it will be injected on load.
    Assert ($text -match 'Blending data present: yes' -or $text -match 'would be injected on load: yes') 'P2: old completed chunk is a blending source (present or injected on load)'

    Send-Command $p 'seedblend inspect chunk 6250 6250'
    $out = Wait-ForOutput $p 'Blending sections' 30
    $text = $out -join "`n"
    Assert ($text -match 'Serialized generation epoch: 1') 'P2: far new chunk stamped with epoch 1'
    Assert ($text -match 'Considered old: no') 'P2: far new chunk is current'
    Assert ($text -match 'Blending data present: no') 'P2: new chunk has no blending_data'

    Send-Command $p 'seedblend verify'
    $out = Wait-ForOutput $p 'Result:' 30
    $text = $out -join ' '
    Assert ($text -match 'Result: OK') 'P2: verify reports OK'
    Assert ($text -match 'blendingInjected=[1-9]') 'P2: synthetic blending was injected for loaded old chunks'
    Assert ($text -match 'oldCompleted=[1-9]') 'P2: old completed chunks were identified'
} finally { Stop-DevServer $p }

$state = Get-State
Assert ($state.activeEpoch -eq 1) 'P2: active epoch is 1 after finalize'
Assert ($state.activeSeed -eq $SeedB) 'P2: active seed is B'
Assert ($state.previousSeed -eq $SeedA) 'P2: previous seed recorded as A'
Assert ($null -eq $state.pendingTransaction) 'P2: no pending transaction after finalize'

# level.dat seed check via NBT is covered by in-game verify; epoch immutability re-checked in P3.

# ---------- Phase 3 ----------
Write-Host "== Phase 3: restart on epoch 1; verify persistence; stage seed C ==" -ForegroundColor Cyan
$p = Start-DevServer
try {
    $startupLines = Wait-ForOutput $p 'Done \(' 600
    Assert (($startupLines -join ' ') -notmatch 'Applying staged') 'P3: no transaction re-applied (idempotent)'

    Send-Command $p 'seedblend inspect chunk 0 0'
    $out = Wait-ForOutput $p 'Blending sections' 30
    $text = $out -join "`n"
    Assert ($text -match 'Serialized generation epoch: 0') 'P3: epoch 0 chunk immutable across restarts'
    Assert ($text -match 'Blending data present: yes' -or $text -match 'would be injected on load: yes') 'P3: old chunk remains a blending source across restarts'

    Send-Command $p 'seedblend inspect chunk 6250 6250'
    $out = Wait-ForOutput $p 'Blending sections' 30
    Assert (($out -join ' ') -match 'Serialized generation epoch: 1') 'P3: epoch 1 chunk persisted'

    Send-Command $p 'seedblend verify'
    $out = Wait-ForOutput $p 'Result:' 30
    Assert (($out -join ' ') -match 'Result: OK') 'P3: verify OK after restart'

    Send-Command $p "seedblend plan $SeedC"
    $out = Wait-ForOutput $p 'seedblend commit' 30
    $token = [regex]::Match(($out | Where-Object { $_ -match '/seedblend commit' } | Select-Object -Last 1),
        '/seedblend commit ([0-9A-Fa-f]{6})').Groups[1].Value
    Send-Command $p "seedblend commit $token"
    [void](Wait-ForOutput $p 'RESTART REQUIRED' 30)
} finally { Stop-DevServer $p }

# ---------- Phase 4 ----------
Write-Host "== Phase 4: second reseed - epochs 0 and 1 both old under epoch 2 ==" -ForegroundColor Cyan
$p = Start-DevServer
try {
    [void](Wait-ForOutput $p 'Done \(' 600)

    Send-Command $p 'seedblend inspect chunk 0 0'
    $out = Wait-ForOutput $p 'Blending sections' 30
    Assert (($out -join ' ') -match 'Considered old: yes') 'P4: epoch 0 chunk old under epoch 2'

    Send-Command $p 'seedblend inspect chunk 6250 6250'
    $out = Wait-ForOutput $p 'Blending sections' 30
    $text = $out -join "`n"
    Assert ($text -match 'Serialized generation epoch: 1') 'P4: epoch 1 chunk epoch immutable'
    Assert ($text -match 'Considered old: yes') 'P4: epoch 1 chunk old under epoch 2'

    Send-Command $p 'seedblend status'
    $out = Wait-ForOutput $p 'Blending dimensions' 30
    Assert (($out -join ' ') -match 'Active epoch: 2') 'P4: active epoch is 2'

    Send-Command $p 'seedblend verify'
    $out = Wait-ForOutput $p 'Result:' 30
    Assert (($out -join ' ') -match 'Result: OK') 'P4: verify OK'
} finally { Stop-DevServer $p }

$state = Get-State
Assert ($state.activeEpoch -eq 2) 'P4: state epoch 2'
Assert ($state.activeSeed -eq $SeedC) 'P4: active seed C'
Assert ($state.originalSeed -eq $SeedA) 'P4: original seed still A'

# ---------- Summary ----------
Write-Host ''
if ($script:Failures.Count -eq 0) {
    Write-Host 'FIXTURE RESULT: ALL ASSERTIONS PASSED' -ForegroundColor Green
    exit 0
} else {
    Write-Host "FIXTURE RESULT: $($script:Failures.Count) FAILURES" -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host " - $_" -ForegroundColor Red }
    exit 1
}
