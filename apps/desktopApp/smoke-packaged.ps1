<#
.SYNOPSIS
Smoke check of the packaged Meal Planner app (plan 7, P7-R6). Installs nothing.

.DESCRIPTION
Builds the app image (:desktopApp:createDistributable, not the MSI) with the full JDK in
MEAL_PLANNER_PACKAGING_JDK (it stops if that has no jpackage; unset, the newest
C:\Program Files\Eclipse Adoptium\jdk-25*), checks its
launcher options and runtime modules, then runs the packaged "Meal Planner.exe" once and checks that
/healthz answers on 127.0.0.1, the window opens, the app's self-check passes (the server, WebP, JNA,
TLS, the HTTP modules, JmDNS, the launcher's own path), the log names no missing class, and the app
quits by itself with exit code 0, leaving no process. Its window shows for about half a minute.

Hard rules (P7-R6). JAVA_TOOL_OPTIONS can add Java properties but never override the launcher's own,
so the run is given:
- never the registry Run key: -Dmealplanner.startWithWindows=off (and the value is compared before
  and after);
- never Documents\Meal Planner (the library) or %LOCALAPPDATA%\Meal Planner (the secrets and, since P7-R10, the app's
  database and log): -Dmealplanner.dataDir (both in one folder), plus MEAL_PLANNER_DATA_DIR (the library) and
  MEAL_PLANNER_HOME (the app data), all in a temp folder;
- never port 5000: -Dmealplanner.port, a free port the system picks;
- no discovery: -Dmealplanner.peers=off (and no UDP 5353 socket is checked);
- no update check: -Dmealplanner.updates=off, so it never contacts GitHub (plan 8).
The launcher's own options would beat all five, so an image whose options aren't exactly the installed
app's is never started (launcher-options.ps1); the script stops with an error instead.
JDK 25's launcher runs the app in a child "Meal Planner.exe" (the JVM, the window and the sockets), so
the window and socket checks look at the launcher's whole process tree (process-tree.ps1); the exit
code is the launcher's, which passes on the app's.
It stops only processes of its own tree: the launcher and the descendants it recorded, each by id
(never by name) and only while it is still that process (the same creation time and exe). It deletes
only its own %TEMP%\mp-smoke-xxxxxxxx folder. It reads the Start with Windows entry; it never writes it.

.PARAMETER SkipBuild
Reuse the app image already built.

.PARAMETER Keep
Keep the temp folder afterwards; it is kept anyway when a check fails.

.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File .\apps\desktopApp\smoke-packaged.ps1
#>
param(
    [switch]$SkipBuild,
    [switch]$Keep
)

$ErrorActionPreference = 'Stop'

$appsDir = Split-Path -Parent $PSScriptRoot
$imageDir = Join-Path $PSScriptRoot 'build\compose\binaries\main\app\Meal Planner'
$exe = Join-Path $imageDir 'Meal Planner.exe'
$cfgFile = Join-Path $imageDir 'app\Meal Planner.cfg'
$releaseFile = Join-Path $imageDir 'runtime\release'
$jbr = 'C:\Program Files\Android\Android Studio\jbr'
$runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$runName = 'Meal Planner'
$requiredModules = @('java.base', 'java.desktop', 'java.instrument', 'java.logging', 'java.management', 'java.net.http', 'java.prefs', 'java.sql', 'jdk.accessibility', 'jdk.httpserver', 'jdk.unsupported')
$requiredChecks = @('server', 'webp', 'jna', 'tls', 'http-client', 'http-server', 'jmdns', 'launcher')
$badInLog = @('ClassNotFoundException', 'NoClassDefFoundError', 'ServiceConfigurationError', 'UnsatisfiedLinkError', 'ExceptionInInitializerError', 'stopped by an error', 'closing failed', 'closing timed out', "didn't stop within", 'self-check .* FAILED')

$failures = New-Object System.Collections.Generic.List[string]
function Add-Failure([string]$text) { $failures.Add($text); Write-Host "FAIL: $text" -ForegroundColor Red }
function Write-Ok([string]$text) { Write-Host "ok: $text" -ForegroundColor Green }

# Get-LauncherOptionProblems: whether the app image's launcher options are exactly the installed app's.
. (Join-Path $PSScriptRoot 'launcher-options.ps1')
# Get-ProcessTree and Select-KillableProcessIds: the launcher's tree, and which of its processes this run may stop.
. (Join-Path $PSScriptRoot 'process-tree.ps1')

# Deletes this run's own folder and nothing else: a %TEMP%\mp-smoke-* folder, checked again here.
function Remove-SmokeDir {
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    $full = [IO.Path]::GetFullPath($smokeDir)
    if ((Split-Path -Leaf $full) -like 'mp-smoke-*' -and $full.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $full -Recurse -Force
    } else {
        Write-Host "Not deleting $full, which isn't this run's temp folder." -ForegroundColor Red
    }
}

function Get-RunValue {
    $item = Get-ItemProperty -Path $runKey -Name $runName -ErrorAction SilentlyContinue
    if ($null -eq $item) { return $null }
    return [string]$item.$runName
}

# jdk-25.0.10+7 is newer than jdk-25.0.9+10, which sorting the names as text gets wrong.
function Get-JdkVersion([string]$folderName) {
    if ($folderName -match '^jdk-(\d+(\.\d+){0,3})') {
        $text = $Matches[1]
        if ($text -notmatch '\.') { $text = $text + '.0' }
        return [version]$text
    }
    return [version]'0.0'
}

# MEAL_PLANNER_PACKAGING_JDK wins, as in the build (checkPackagingJdk); set but without jpackage, it stops the script
# rather than quietly building with another JDK. Unset or blank, the newest Temurin 25 is used.
function Find-PackagingJdk {
    $named = $env:MEAL_PLANNER_PACKAGING_JDK
    if (-not [string]::IsNullOrWhiteSpace($named)) {
        if (Test-Path -LiteralPath (Join-Path $named 'bin\jpackage.exe') -PathType Leaf) { return $named }
        throw "MEAL_PLANNER_PACKAGING_JDK is set to $named, which has no bin\jpackage.exe. Point it at a full JDK 25 (docs\WINDOWS.md, ""Build the installer"": re-run its one-liner after a Temurin update) or clear it."
    }
    $adoptium = 'C:\Program Files\Eclipse Adoptium'
    if (Test-Path $adoptium) {
        $found = Get-ChildItem $adoptium -Directory -Filter 'jdk-25*' | Where-Object { Test-Path (Join-Path $_.FullName 'bin\jpackage.exe') } | Sort-Object { Get-JdkVersion $_.Name } | Select-Object -Last 1
        if ($null -ne $found) { return $found.FullName }
    }
    return $null
}

# 1. Build the app image (not the MSI), then check its launcher options and its runtime.
if (-not $SkipBuild) {
    $jdk = Find-PackagingJdk
    if ($null -eq $jdk) {
        throw 'No full JDK with jpackage found. Install one (winget install --id EclipseAdoptium.Temurin.25.JDK -e) or set MEAL_PLANNER_PACKAGING_JDK to its folder; see docs\WINDOWS.md, "Build the installer".'
    }
    $savedJavaHome = $env:JAVA_HOME
    $savedJdk = $env:MEAL_PLANNER_PACKAGING_JDK
    # P7-PF3: Gradle and the JVM write warnings to stderr, which Windows PowerShell 5.1 turns into error records when
    # this script's output is redirected; under Stop the first one would end the script mid-build. Gradle's exit code
    # alone says how it went.
    $savedPreference = $ErrorActionPreference
    $gradleExit = 1
    try {
        $ErrorActionPreference = 'Continue'
        if (-not $env:JAVA_HOME) { $env:JAVA_HOME = $jbr }
        $env:MEAL_PLANNER_PACKAGING_JDK = $jdk
        & (Join-Path $appsDir 'gradlew.bat') -p $appsDir ':desktopApp:checkPackagingConfig' ':desktopApp:createDistributable'
        $gradleExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedPreference
        $env:JAVA_HOME = $savedJavaHome
        $env:MEAL_PLANNER_PACKAGING_JDK = $savedJdk
    }
    if ($gradleExit -ne 0) { throw "Gradle failed with exit code $gradleExit." }
}
if (-not (Test-Path $exe)) { throw "No app image at $exe. Run without -SkipBuild." }

# The launcher's java-options beat this run's JAVA_TOOL_OPTIONS, so an image whose options aren't exactly the
# installed app's (a leaked port, Run-key gate, data folder, discovery or update switch; an image from before plan 7's
# checks; a .cfg that is missing or unreadable) is never started: this throws first (launcher-options.ps1).
$versionMatch = Select-String -Path (Join-Path $appsDir 'gradle.properties') -Pattern '^mealplanner\.desktopVersion=(.+)$' | Select-Object -First 1
$version = ''
if ($null -ne $versionMatch) { $version = $versionMatch.Matches[0].Groups[1].Value.Trim() }
$cfgProblems = @(Get-LauncherOptionProblems -CfgFile $cfgFile -Version $version)
if ($cfgProblems.Count -gt 0) {
    $cfgProblems | ForEach-Object { Write-Host "FAIL: $_" -ForegroundColor Red }
    throw "The app image's launcher options aren't the installed app's, and they would beat this run's own (port, Start with Windows, data folder, discovery, updates), so the app was not started. Rebuild the image (run without -SkipBuild)."
}
Write-Ok "the launcher passes only the installed app's (version $version), jpackage's and Compose's own options"
$modulesLine = @(Get-Content $releaseFile | Where-Object { $_ -like 'MODULES=*' })
$modules = @()
if ($modulesLine.Count -gt 0) { $modules = $modulesLine[0].Substring(8).Trim('"').Split(' ') }
$missing = @($requiredModules | Where-Object { $modules -notcontains $_ })
if ($missing.Count -eq 0) { Write-Ok ('the runtime has ' + ($requiredModules -join ', ')) } else { Add-Failure ('the runtime lacks ' + ($missing -join ', ')) }

# 2. A throwaway home for the run: never the real library or the secrets folder, never port 5000.
$smokeDir = Join-Path ([IO.Path]::GetTempPath()) ('mp-smoke-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
if ($smokeDir.Contains(' ')) { throw "The temp folder $smokeDir has a space in it, which JAVA_TOOL_OPTIONS can't carry. Point TEMP at a folder without spaces." }
# Both names are checked before either is used: Join-Path on an unset LOCALAPPDATA would throw (under -ErrorAction
# Stop) before the plain message below could.
$documentsDir = [Environment]::GetFolderPath('MyDocuments')
$localAppData = $env:LOCALAPPDATA
if ([string]::IsNullOrEmpty($documentsDir) -or [string]::IsNullOrEmpty($localAppData)) { throw 'Windows named no Documents or LOCALAPPDATA folder, so the run cannot prove it stays out of them.' }
foreach ($real in @($documentsDir, (Join-Path $localAppData 'Meal Planner'))) {
    if ($smokeDir.StartsWith($real, [StringComparison]::OrdinalIgnoreCase)) { throw "The temp folder $smokeDir is inside $real." }
}
$dataDir = Join-Path $smokeDir 'data'
$homeDir = Join-Path $smokeDir 'home'
$tmpDir = Join-Path $smokeDir 'tmp'
New-Item -ItemType Directory -Force -Path $homeDir, $tmpDir | Out-Null

$listener = New-Object System.Net.Sockets.TcpListener -ArgumentList ([System.Net.IPAddress]::Loopback), 0
$listener.Start()
$port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
$listener.Stop()
if ($port -eq 5000 -or $port -lt 1024) { throw "The system offered port $port; run the script again." }

$runBefore = Get-RunValue

# 3. Run the packaged app once, with its self-check on.
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $exe
$psi.WorkingDirectory = $smokeDir
$psi.UseShellExecute = $false
$psi.EnvironmentVariables['JAVA_TOOL_OPTIONS'] = (@(
    "-Dmealplanner.dataDir=$dataDir",
    "-Dmealplanner.port=$port",
    '-Dmealplanner.startWithWindows=off',
    '-Dmealplanner.peers=off',
    '-Dmealplanner.updates=off',
    '-Dmealplanner.selfCheck=on',
    "-Djava.io.tmpdir=$tmpDir"
) -join ' ')
$psi.EnvironmentVariables['MEAL_PLANNER_DATA_DIR'] = $dataDir
$psi.EnvironmentVariables['MEAL_PLANNER_HOME'] = $homeDir
# _JAVA_OPTIONS is read after the launcher's options and could override them; neither is used here.
foreach ($override in @('_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS')) { $psi.EnvironmentVariables.Remove($override) }

# Every process, as process-tree.ps1 takes them.
function Get-ProcessSnapshot {
    return , @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop | Select-Object ProcessId, ParentProcessId, CreationDate, ExecutablePath)
}

# The launcher's tree now; each process in it is recorded (its id, creation time and exe) the first time it is seen.
# Only recorded processes are ever stopped.
$recorded = @{}
function Update-Tree {
    $tree = @(Get-ProcessTree -Processes (Get-ProcessSnapshot) -RootId $process.Id)
    foreach ($p in $tree) { if (-not $recorded.ContainsKey([int]$p.ProcessId)) { $recorded[[int]$p.ProcessId] = $p } }
    # Unrolled on purpose: callers collect with @(...), and a leading comma would hand them the whole list as one item.
    return $tree
}

# The recorded processes still running as themselves (the same id, creation time and exe).
function Get-OwnLiveIds {
    return @(Select-KillableProcessIds -Recorded @($recorded.Values) -Current (Get-ProcessSnapshot) -Exe $exe)
}

function Test-TreeWindow([object[]]$tree) {
    foreach ($p in $tree) {
        $live = Get-Process -Id ([int]$p.ProcessId) -ErrorAction SilentlyContinue
        if ($null -ne $live -and $live.MainWindowTitle -eq 'Meal Planner') { return $true }
    }
    return $false
}

$process = $null
$healthy = $false
$windowSeen = $false
try {
    $process = [System.Diagnostics.Process]::Start($psi)
    Write-Host "Started Meal Planner.exe (process $($process.Id)) on port $port, in $smokeDir"
    # JDK 25's launcher runs the app in a child Meal Planner.exe: wait up to 30 s for it.
    $treeDeadline = (Get-Date).AddSeconds(30)
    $tree = @(Update-Tree)
    while (-not $process.HasExited -and $tree.Count -lt 2 -and (Get-Date) -lt $treeDeadline) {
        Start-Sleep -Milliseconds 500
        $tree = @(Update-Tree)
    }
    if ($tree.Count -ge 2) {
        Write-Host ('The launcher (' + $process.Id + ') runs the app in ' + (($tree | Where-Object { [int]$_.ProcessId -ne $process.Id } | ForEach-Object { $_.ProcessId }) -join ', '))
    } else {
        Write-Host 'note: the launcher started no child process within 30 s; checking the launcher alone' -ForegroundColor Yellow
    }
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline -and -not ($healthy -and $windowSeen)) {
        $tree = @(Update-Tree)
        if ($tree.Count -eq 0 -and @(Get-OwnLiveIds).Count -eq 0) { break }
        if (-not $healthy) {
            try {
                $reply = Invoke-RestMethod -Uri "http://127.0.0.1:$port/healthz" -TimeoutSec 2
                if ($reply.ok -eq $true) { $healthy = $true }
            } catch {
                # Not listening yet.
            }
        }
        if (-not $windowSeen -and (Test-TreeWindow $tree)) { $windowSeen = $true }
        if (-not ($healthy -and $windowSeen)) { Start-Sleep -Milliseconds 500 }
    }
    if ($healthy) { Write-Ok "/healthz answered on 127.0.0.1:$port" } else { Add-Failure "/healthz never answered on 127.0.0.1:$port" }
    if ($windowSeen) { Write-Ok 'the window opened' } else { Add-Failure 'no window titled Meal Planner opened' }

    # The sockets of the whole tree, while it is alive: the self-check holds the app up for 20 s after it is ready.
    # Reading them is harmless, so every process in the tree counts, recorded or not.
    $tree = @(Update-Tree)
    $owners = @(@($tree | ForEach-Object { [int]$_.ProcessId }) + @(Get-OwnLiveIds) | Sort-Object -Unique)
    if ($owners.Count -eq 0) {
        Add-Failure 'it quit before its sockets could be checked'
    } else {
        $listening = @(foreach ($id in $owners) { Get-NetTCPConnection -OwningProcess $id -State Listen -ErrorAction SilentlyContinue })
        if (@($listening | Where-Object { $_.LocalPort -eq 5000 }).Count -gt 0) { Add-Failure 'it listens on port 5000' } else { Write-Ok 'nothing on port 5000' }
        $server = @($listening | Where-Object { $_.LocalPort -eq $port })
        $wide = @($server | Where-Object { $_.LocalAddress -ne '127.0.0.1' })
        if ($server.Count -gt 0 -and $wide.Count -eq 0) { Write-Ok "the server listens on 127.0.0.1:$port only" } else { Add-Failure "the server isn't on 127.0.0.1:$port only" }
        $mdns = @(foreach ($id in $owners) { Get-NetUDPEndpoint -OwningProcess $id -LocalPort 5353 -ErrorAction SilentlyContinue })
        if ($mdns.Count -eq 0) { Write-Ok 'no discovery socket (UDP 5353)' } else { Add-Failure 'it opened UDP 5353 (discovery)' }
    }

    # 4. The self-check quits the app by itself once its hold is over. The launcher passes on the app's exit code.
    if ($process.WaitForExit(180000)) {
        if ($process.ExitCode -eq 0) { Write-Ok 'it quit by itself with exit code 0' } else { Add-Failure "it quit with exit code $($process.ExitCode) (3: a self-check failed; 4: closing timed out)" }
    } else {
        Add-Failure 'it did not quit by itself within 3 minutes; stopping it'
    }
} finally {
    # Only this run's own tree: the processes recorded from it, each by id and only while it is still that process
    # (process-tree.ps1), children before the launcher, whose own handle is used. Never by name.
    if ($null -ne $process) {
        try { [void](Update-Tree) } catch { Write-Host "Couldn't list the launcher's processes: $($_.Exception.Message)" -ForegroundColor Red }
        $toStop = @(Get-OwnLiveIds | Sort-Object { if ($_ -eq $process.Id) { 1 } else { 0 } })
        foreach ($id in $toStop) {
            try {
                if ($id -eq $process.Id) { $process.Kill() } else { Stop-Process -Id $id -Force -ErrorAction Stop }
                Write-Host "Stopped process $id, this run's own." -ForegroundColor Yellow
            } catch {
                Write-Host "Couldn't stop process $($id): $($_.Exception.Message)" -ForegroundColor Red
            }
        }
        if ($toStop.Count -gt 0) { [void]$process.WaitForExit(10000) }
    }
}

# Nothing this run started is left: every recorded process has ended (or its id now belongs to another process).
$left = @(Get-OwnLiveIds)
for ($i = 0; $i -lt 20 -and $left.Count -gt 0; $i++) {
    Start-Sleep -Milliseconds 500
    $left = @(Get-OwnLiveIds)
}
$ownIds = (@($recorded.Keys) | Sort-Object) -join ', '
if ($left.Count -eq 0 -and $null -ne $process -and $process.HasExited) {
    Write-Ok "the processes this run started ($ownIds) have ended"
} else {
    $running = @($left)
    if ($null -ne $process -and -not $process.HasExited -and $running -notcontains $process.Id) { $running += $process.Id }
    Add-Failure "processes this run started are still running: $(($running | Sort-Object) -join ', ')"
}
# Another copy from the app image is not this run's to stop; it is only named.
$others = @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe -and -not $recorded.ContainsKey([int]$_.Id) })
if ($others.Count -gt 0) {
    Write-Host ('note: other Meal Planner.exe processes from the app image are running, left alone: ' + (($others | ForEach-Object { $_.Id }) -join ', ')) -ForegroundColor Yellow
}

# 5. The log: every self-check passed and nothing is missing.
$log = Join-Path $dataDir '.cache\meal-planner.log'
if (-not (Test-Path $log)) {
    Add-Failure "no log at $log"
} else {
    $logText = Get-Content -Raw $log
    # P7-PF1: DesktopLog starts every line with its time and INFO ("2026-10-06 12:34:56 INFO Meal Planner: ..."), so
    # nothing here is anchored at the line's start; the prefix is only taken off for printing.
    $selfCheckLines = @(Get-Content $log | Where-Object { $_ -like '*Meal Planner: self-check*' })
    $selfCheckLines | ForEach-Object { Write-Host ('  ' + ($_ -replace '^.*?(?=Meal Planner: self-check)', '')) }
    foreach ($name in $requiredChecks) {
        if (@($selfCheckLines | Where-Object { $_ -like "*Meal Planner: self-check $name ok*" }).Count -eq 1) { Write-Ok "self-check $name" } else { Add-Failure "self-check $name didn't pass" }
    }
    $done = @($selfCheckLines | Where-Object { $_ -match 'Meal Planner: self-check done: (\d+) of (\d+) passed$' -and $Matches[1] -eq $Matches[2] -and [int]$Matches[2] -eq $requiredChecks.Count })
    if ($done.Count -eq 1) { Write-Ok ($done[0] -replace '^.*?(?=Meal Planner: self-check)', '') } else { Add-Failure "the self-check summary is missing, counts a failure or doesn't count $($requiredChecks.Count) checks" }
    foreach ($bad in $badInLog) {
        if ($logText -match $bad) { Add-Failure "the log matches '$bad'" }
    }
}

# 6. The Start with Windows entry is as it was.
$runAfter = Get-RunValue
if ($runAfter -eq $runBefore) { Write-Ok 'the Start with Windows entry is as it was' } else { Add-Failure "the Start with Windows entry changed from '$runBefore' to '$runAfter'" }

if ($failures.Count -eq 0) {
    if ($Keep) { Write-Host "Kept $smokeDir" } else { Remove-SmokeDir }
    Write-Host 'Smoke check passed.' -ForegroundColor Green
    exit 0
}
Write-Host "Smoke check FAILED ($($failures.Count)). The run's folder is kept: $smokeDir" -ForegroundColor Red
exit 1
