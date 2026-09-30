<#
.SYNOPSIS
  Tests pi/prepare-sd.ps1 against each kind of card Raspberry Pi Imager
  produces, without needing a card.

.DESCRIPTION
  Builds fake boot partitions in a temp folder and mounts each one as a
  drive letter with `subst`, because prepare-sd.ps1 takes a drive, not a
  path -- and that check is worth keeping, so the test works around it
  rather than weakening it.

  Run it from the repo:  .\pi\test-prepare-sd.ps1
#>
[CmdletBinding()]
param()
$ErrorActionPreference = "Stop"
$script:pass = 0
$script:fail = 0

function ok   ($m) { $script:pass++; Write-Host "  PASS  $m" }
function bad  ($m) { $script:fail++; Write-Host "  FAIL  $m" -ForegroundColor Red }
function check($m, [bool]$cond) { if ($cond) { ok $m } else { bad $m } }

$prepare = Join-Path $PSScriptRoot "prepare-sd.ps1"
if (-not (Test-Path $prepare)) { throw "prepare-sd.ps1 not found next to this test" }

function Get-FreeDriveLetter {
  $used = (Get-PSDrive -PSProvider FileSystem).Name
  foreach ($c in [char[]]'TUVWXYZ') { if ($used -notcontains "$c") { return "${c}:" } }
  throw "no free drive letter to mount a fake card on"
}

# A freshly flashed card: config.txt is what prepare-sd.ps1 identifies it by.
function New-FakeCard {
  param([string] $Variant)
  $dir = Join-Path ([IO.Path]::GetTempPath()) ("fakecard-" + [Guid]::NewGuid().ToString("N").Substring(0, 8))
  New-Item -ItemType Directory -Path $dir | Out-Null
  Set-Content -Path (Join-Path $dir "config.txt") -Value "# fake" -Encoding ascii
  $cmdline = "console=serial0,115200 root=PARTUUID=deadbeef-02 rootfstype=ext4 fsck.repair=yes rootwait"
  switch ($Variant) {
    "firstrun"  { Set-Content (Join-Path $dir "firstrun.sh") -Value "#!/bin/bash`nsome imager setup`nrm -f /boot/firmware/firstrun.sh`n" -Encoding ascii
                  $cmdline += " systemd.run=/boot/firmware/firstrun.sh systemd.run_success_action=reboot systemd.unit=kernel-command-line.target" }
    "clouddata" { Set-Content (Join-Path $dir "user-data") -Value "#cloud-config`nhostname: meal-planner`nruncmd:`n  - [ echo, hi ]`n" -Encoding ascii }
    # Imager 1.9+ / 2.x: settings in custom.toml, applied by firstboot via init=.
    "customtoml"{ Set-Content (Join-Path $dir "custom.toml") -Value "config_version = 1`n[system]`nhostname = `"meal-planner`"`n" -Encoding ascii
                  $cmdline += " init=/usr/lib/raspberrypi-sys-mods/firstboot" }
    "bare"      { }
  }
  Set-Content -Path (Join-Path $dir "cmdline.txt") -Value $cmdline -Encoding ascii
  return $dir
}

function Invoke-Prepare {
  param([string] $Dir)
  $drive = Get-FreeDriveLetter
  & subst $drive $Dir | Out-Null
  try {
    # 3>&1 folds in Write-Warning, 6>&1 Write-Host (the information stream),
    # so both kinds of message can be asserted on.
    $out = & $prepare -Drive $drive -Password 'test-pw' 3>&1 6>&1 | Out-String
    return $out
  } finally {
    & subst $drive /d | Out-Null
  }
}

Write-Host "==> a card customised the old way (firstrun.sh)"
$dir = New-FakeCard -Variant "firstrun"
$out = Invoke-Prepare -Dir $dir
$firstrun = Get-Content (Join-Path $dir "firstrun.sh") -Raw
$cmdline  = Get-Content (Join-Path $dir "cmdline.txt") -Raw
check "hooked into firstrun.sh" ($firstrun -match 'bash /boot/firmware/mealplanner-hook\.sh')
check "ran the hook before Imager's script deletes itself" `
  ($firstrun.IndexOf("mealplanner-hook") -lt $firstrun.IndexOf("rm -f /boot/firmware/firstrun.sh"))
check "left cmdline.txt alone" (-not ($cmdline -match 'mealplanner-hook'))
check "said so" ($out -match "hooked into Imager's firstrun\.sh")
Remove-Item $dir -Recurse -Force

Write-Host "==> a card customised with cloud-init (user-data)"
$dir = New-FakeCard -Variant "clouddata"
$out = Invoke-Prepare -Dir $dir
$userdata = Get-Content (Join-Path $dir "user-data") -Raw
$cmdline  = Get-Content (Join-Path $dir "cmdline.txt") -Raw
check "added the hook to runcmd" ($userdata -match '- \[ bash, /boot/firmware/mealplanner-hook\.sh \]')
check "kept cloud-init's own runcmd entry" ($userdata -match '- \[ echo, hi \]')
check "never touches cmdline.txt when cloud-init is in charge" (-not ($cmdline -match 'systemd\.run'))
Remove-Item $dir -Recurse -Force

Write-Host "==> a card customised the new way (custom.toml)"
# The regression: this card IS customised, but neither script hook exists, so
# prepare-sd.ps1 used to warn that the Pi would come up with no user or Wi-Fi.
$dir = New-FakeCard -Variant "customtoml"
$out = Invoke-Prepare -Dir $dir
$cmdline = Get-Content (Join-Path $dir "cmdline.txt") -Raw
check "hooked into cmdline.txt" ($cmdline -match 'systemd\.run=/boot/firmware/mealplanner-hook\.sh')
check "kept firstboot's init=, which applies custom.toml first" `
  ($cmdline -match 'init=/usr/lib/raspberrypi-sys-mods/firstboot')
check "did NOT claim the card is uncustomised" (-not ($out -match 'No Imager customisation found'))
check "said it is working alongside custom.toml" ($out -match "alongside Imager's custom\.toml")
check "left custom.toml untouched" ((Get-Content (Join-Path $dir "custom.toml") -Raw) -match 'config_version = 1')

# Re-running on the same card must not hook twice.
$out2 = Invoke-Prepare -Dir $dir
$cmdline2 = Get-Content (Join-Path $dir "cmdline.txt") -Raw
check "re-running does not hook a second time" `
  (([regex]::Matches($cmdline2, 'mealplanner-hook')).Count -eq 1)
check "re-running says it is already hooked" ($out2 -match 'already calls the hook')
Remove-Item $dir -Recurse -Force

Write-Host "==> a card with no customisation at all (the warning must stay)"
$dir = New-FakeCard -Variant "bare"
$out = Invoke-Prepare -Dir $dir
$cmdline = Get-Content (Join-Path $dir "cmdline.txt") -Raw
check "hooked into cmdline.txt" ($cmdline -match 'systemd\.run=/boot/firmware/mealplanner-hook\.sh')
check "warns that the Pi will have no user, Wi-Fi or SSH" ($out -match 'No Imager customisation found')
Remove-Item $dir -Recurse -Force

Write-Host "==> the kit itself"
$dir = New-FakeCard -Variant "customtoml"
Invoke-Prepare -Dir $dir | Out-Null
foreach ($f in "mealplanner-provision.sh", "mealplanner-hook.sh", "mealplanner.conf") {
  check "copied $f" (Test-Path (Join-Path $dir $f))
  $bytes = [IO.File]::ReadAllBytes((Join-Path $dir $f))
  # The Pi reads these with sh; a stray CR is a syntax error there.
  check "$f has Unix line endings" (-not ($bytes -contains 13))
}
check "put the password into mealplanner.conf" `
  ((Get-Content (Join-Path $dir "mealplanner.conf") -Raw) -match '(?m)^HOUSEHOLD_PASSWORD=test-pw$')
Remove-Item $dir -Recurse -Force

Write-Host "==> a card prepared by the installer (secrets file + key, no password)"
$dir = New-FakeCard -Variant "customtoml"
$secrets = Join-Path ([IO.Path]::GetTempPath()) "mp-secrets-test.env"
[IO.File]::WriteAllText($secrets, "MEAL_PLANNER_PASSWORD_HASH=scrypt:fake`r`nMEAL_PLANNER_HOST=0.0.0.0`r`n")
$key = Join-Path ([IO.Path]::GetTempPath()) "mp-key-test.json"
[IO.File]::WriteAllText($key, '{"type": "service_account"}')
$drive = Get-FreeDriveLetter
& subst $drive $dir | Out-Null
try { $out = & $prepare -Drive $drive -SecretsFile $secrets -GcalKeyFile $key 3>&1 6>&1 | Out-String }
finally { & subst $drive /d | Out-Null }
$cardSecrets = Join-Path $dir "mealplanner-secrets.env"
check "copied the secrets file" (Test-Path $cardSecrets)
check "secrets file has Unix line endings" (-not ([IO.File]::ReadAllBytes($cardSecrets) -contains 13))
check "copied the calendar key" (Test-Path (Join-Path $dir "mealplanner-gcal-key.json"))
check "did not put a password in mealplanner.conf" `
  ((Get-Content (Join-Path $dir "mealplanner.conf") -Raw) -match '(?m)^HOUSEHOLD_PASSWORD=$')
check "did not prompt for a password" (-not ($out -match 'Household password'))
Remove-Item $dir -Recurse -Force; Remove-Item $secrets, $key

Write-Host "==> -SecretsFile and -Password together, and stale files from an earlier run"
$dir = New-FakeCard -Variant "customtoml"
$secrets = Join-Path ([IO.Path]::GetTempPath()) "mp-secrets-test2.env"
[IO.File]::WriteAllText($secrets, "MEAL_PLANNER_PASSWORD_HASH=scrypt:fake`
")
$key = Join-Path ([IO.Path]::GetTempPath()) "mp-key-test2.json"
[IO.File]::WriteAllText($key, '{"type": "service_account"}')
$drive = Get-FreeDriveLetter
& subst $drive $dir | Out-Null
try {
  $threw = $false
  try { & $prepare -Drive $drive -SecretsFile $secrets -Password 'plain' 3>&1 6>&1 | Out-Null } catch { $threw = $true }
  check "refuses -SecretsFile together with -Password" $threw
  check "wrote nothing to the card when it refused" (-not (Test-Path (Join-Path $dir "mealplanner.conf")))
  & $prepare -Drive $drive -SecretsFile $secrets -GcalKeyFile $key 3>&1 6>&1 | Out-Null
  $out = & $prepare -Drive $drive -Password 'again' 3>&1 6>&1 | Out-String
} finally { & subst $drive /d | Out-Null }
check "re-running without -SecretsFile removes the old secrets file" (-not (Test-Path (Join-Path $dir "mealplanner-secrets.env")))
check "re-running without -GcalKeyFile removes the old key" (-not (Test-Path (Join-Path $dir "mealplanner-gcal-key.json")))
check "said it removed them" ($out -match 'removed the settings file')
Remove-Item $dir -Recurse -Force; Remove-Item $secrets, $key

Write-Host ""
Write-Host "==> $script:pass passed, $script:fail failed"
if ($script:fail -gt 0) { exit 1 }
