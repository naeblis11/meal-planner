<#
.SYNOPSIS
  Installs Meal Planner: on this Windows PC, or on a Raspberry Pi by
  preparing its SD card from here.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File install.ps1
#>
[CmdletBinding()]
param()
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$venvPy = Join-Path $root ".venv\Scripts\python.exe"
$venvPyw = Join-Path $root ".venv\Scripts\pythonw.exe"

function Test-Python([string[]] $cmd) {
  $exe = $cmd[0]; $rest = @($cmd | Select-Object -Skip 1)
  if (-not (Get-Command $exe -ErrorAction SilentlyContinue)) { return $false }
  # Windows PowerShell 5.1 turns a native command's stderr into a terminating
  # error under -ErrorAction Stop (the Store "python" alias and "py -3" with
  # no runtime both write to stderr), so a failed probe must not escape.
  $previous = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    & $exe @rest -c "import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)" 2>$null | Out-Null
    return ($LASTEXITCODE -eq 0)
  } catch {
    return $false
  } finally {
    $ErrorActionPreference = $previous
  }
}

Write-Host "Meal Planner installer" -ForegroundColor Cyan
Write-Host ""

if (-not (Test-Path $venvPy)) {
  $python = $null
  foreach ($candidate in @(@("py", "-3"), @("python"))) {
    if (Test-Python $candidate) { $python = $candidate; break }
  }
  if (-not $python) {
    Write-Host "Python 3.11 or newer is needed and wasn't found."
    $answer = ""
    if (Get-Command winget -ErrorAction SilentlyContinue) {
      $answer = Read-Host "Install Python 3.12 now with winget? [Y/n]"
    }
    $hasWinget = [bool](Get-Command winget -ErrorAction SilentlyContinue)
    if (-not $hasWinget) {
      Write-Host "winget isn't available on this PC, so Python has to be installed by hand."
      Write-Host "Get it from https://www.python.org/downloads/ (tick 'Add python.exe to PATH'),"
      Write-Host "then close this window, open a new PowerShell window and run this installer again."
    } elseif ($answer -eq "" -or $answer -match '^[Yy]') {
      winget install --id Python.Python.3.12 -e --accept-source-agreements
      Write-Host ""
      Write-Host "Python is installed. Close this window, open a new PowerShell window," -ForegroundColor Yellow
      Write-Host "and run this installer again so Windows can find it." -ForegroundColor Yellow
    } else {
      Write-Host "Get it from https://www.python.org/downloads/ (tick 'Add python.exe to PATH'), then run this again."
    }
    exit 1
  }
  Write-Host "Setting up the app's Python environment (a minute or two)..."
  $exe = $python[0]; $rest = @($python | Select-Object -Skip 1)
  & $exe @rest -m venv (Join-Path $root ".venv")
  if ($LASTEXITCODE -ne 0) { throw "Could not create the Python environment." }
}
& $venvPy -m pip install -q --upgrade pip
& $venvPy -m pip install -q -r (Join-Path $root "requirements.txt")
if ($LASTEXITCODE -ne 0) { throw "Installing the app's packages failed (see above)." }

$report = Join-Path ([IO.Path]::GetTempPath()) ("meal-planner-target-" + [Guid]::NewGuid().ToString("N") + ".txt")
& $venvPy (Join-Path $root "configure.py") --report $report
$code = $LASTEXITCODE
$target = if (Test-Path $report) { (Get-Content $report -Raw).Trim() } else { "" }
Remove-Item $report -ErrorAction SilentlyContinue
if ($code -ne 0) { exit $code }
if ($target -ne "windows") { exit 0 }

Write-Host ""
$startup = [Environment]::GetFolderPath("Startup")
$shortcut = Join-Path $startup "Meal Planner.lnk"
$answer = Read-Host "Start Meal Planner automatically when you sign in to Windows? [Y/n]"
if ($answer -eq "" -or $answer -match '^[Yy]') {
  $shell = New-Object -ComObject WScript.Shell
  $link = $shell.CreateShortcut($shortcut)
  $link.TargetPath = $venvPyw
  $link.Arguments = "`"$(Join-Path $root 'app.py')`""
  $link.WorkingDirectory = $root
  $link.Description = "Meal Planner"
  $link.Save()
  Write-Host "  added to your Startup folder"
}

$running = $false
try { $running = (Invoke-RestMethod -Uri "http://127.0.0.1:5000/healthz" -TimeoutSec 2).ok } catch {}
if ($running) {
  Write-Host "Meal Planner is already running; restart it to pick up the new settings."
} else {
  $answer = Read-Host "Start Meal Planner now? [Y/n]"
  if ($answer -eq "" -or $answer -match '^[Yy]') {
    Start-Process -FilePath $venvPyw -ArgumentList "`"$(Join-Path $root 'app.py')`"" -WorkingDirectory $root -WindowStyle Hidden
    $up = $false
    for ($i = 0; $i -lt 30 -and -not $up; $i++) {
      Start-Sleep -Seconds 1
      try { $up = [bool](Invoke-RestMethod -Uri "http://127.0.0.1:5000/healthz" -TimeoutSec 2).ok } catch {}
    }
    if ($up) {
      Start-Process "http://127.0.0.1:5000"
    } else {
      Write-Host "Meal Planner did not start within 30 seconds." -ForegroundColor Yellow
      Write-Host "To see why, run this in a PowerShell window in this folder:"
      Write-Host "  .venv\Scripts\python app.py"
    }
  }
}
