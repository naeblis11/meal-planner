<#
.SYNOPSIS
  Puts the Meal Planner first-boot kit onto a freshly flashed Raspberry Pi
  SD card, so the Pi installs and starts everything by itself.

.DESCRIPTION
  Run this on Windows after Raspberry Pi Imager has written Raspberry Pi OS
  to the card and while the card is still in the reader. It copies three
  files to the card's boot partition and arranges for them to run on the
  first boot. See docs/RASPBERRY-PI.md for the whole procedure.

.EXAMPLE
  .\pi\prepare-sd.ps1
  .\pi\prepare-sd.ps1 -Drive E: -Password 'choose-a-password' -TailscaleAuthKey 'tskey-auth-...'
#>
[CmdletBinding()]
param(
  # The SD card's boot partition, e.g. E:. Found automatically if omitted.
  [string] $Drive,
  # Household password for the app. Prompted for if omitted; may be left empty
  # to set it later over SSH.
  [string] $Password,
  # Written by configure.py: the app's settings with the password already hashed.
  [string] $SecretsFile,
  # A Google service-account key to install on the Pi.
  [string] $GcalKeyFile,
  # Tailscale auth key, for remote access without touching the Pi. Optional.
  [string] $TailscaleAuthKey,
  # Wi-Fi for a Pi with no Ethernet. Normally you set this in Raspberry Pi
  # Imager instead; use these to add or change it without re-flashing.
  [string] $WifiSsid,
  [string] $WifiPassword,
  [string] $WifiCountry = "US"
)
$ErrorActionPreference = "Stop"
$kit = Join-Path $PSScriptRoot "boot"

function Find-BootPartition {
  # Raspberry Pi OS's boot partition is a small FAT volume holding config.txt.
  $hits = Get-Volume | Where-Object { $_.DriveLetter -and $_.FileSystem -match 'FAT' } |
    Where-Object { Test-Path (Join-Path "$($_.DriveLetter):" "config.txt") }
  if (-not $hits) { throw "No Raspberry Pi boot partition found. Flash the card with Raspberry Pi Imager first, leave it in the reader, or pass -Drive E:" }
  if ($hits.Count -gt 1) { throw "Found several: $($hits.DriveLetter -join ', '). Re-run with -Drive <letter>:" }
  return "$($hits.DriveLetter):"
}

if ($SecretsFile -and $PSBoundParameters.ContainsKey('Password') -and $Password) {
  throw "Use either -SecretsFile (the password is already hashed in it) or -Password, not both: -Password would put the plain password on the card."
}
if ($SecretsFile -and -not (Test-Path $SecretsFile)) { throw "No file at $SecretsFile" }
if ($GcalKeyFile -and -not (Test-Path $GcalKeyFile)) { throw "No file at $GcalKeyFile" }

if (-not $Drive) { $Drive = Find-BootPartition }
$Drive = $Drive.TrimEnd('\')
if ($Drive -notmatch '^[A-Za-z]:$') { throw "-Drive should look like E:" }
if (-not (Test-Path (Join-Path $Drive "config.txt"))) {
  throw "$Drive does not look like a Raspberry Pi boot partition (no config.txt)."
}
Write-Host "Using boot partition $Drive" -ForegroundColor Cyan

if (-not $PSBoundParameters.ContainsKey('Password') -and -not $SecretsFile) {
  $secure = Read-Host "Household password for the app (Enter to skip and set it later over SSH)" -AsSecureString
  $Password = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}

# --- the three files -------------------------------------------------------
Copy-Item (Join-Path $kit "mealplanner-provision.sh") (Join-Path $Drive "mealplanner-provision.sh") -Force
Copy-Item (Join-Path $kit "mealplanner-hook.sh")      (Join-Path $Drive "mealplanner-hook.sh")      -Force

$conf = Get-Content (Join-Path $kit "mealplanner.conf") -Raw
$conf = $conf -replace '(?m)^HOUSEHOLD_PASSWORD=.*$', "HOUSEHOLD_PASSWORD=$Password"
if ($TailscaleAuthKey) { $conf = $conf -replace '(?m)^TAILSCALE_AUTH_KEY=.*$', "TAILSCALE_AUTH_KEY=$TailscaleAuthKey" }
if ($WifiSsid) {
  $conf = $conf -replace '(?m)^WIFI_SSID=.*$',     "WIFI_SSID=$WifiSsid"
  $conf = $conf -replace '(?m)^WIFI_PASSWORD=.*$', "WIFI_PASSWORD=$WifiPassword"
  $conf = $conf -replace '(?m)^WIFI_COUNTRY=.*$',  "WIFI_COUNTRY=$WifiCountry"
}
# The Pi reads these with `sh`, so they must have Unix line endings.
[IO.File]::WriteAllText((Join-Path $Drive "mealplanner.conf"), ($conf -replace "`r`n", "`n"))
foreach ($f in "mealplanner-provision.sh", "mealplanner-hook.sh") {
  $p = Join-Path $Drive $f
  [IO.File]::WriteAllText($p, ([IO.File]::ReadAllText($p) -replace "`r`n", "`n"))
}
Write-Host "  copied mealplanner-provision.sh, mealplanner-hook.sh, mealplanner.conf"

if ($SecretsFile) {
  $text = [IO.File]::ReadAllText($SecretsFile) -replace "`r`n", "`n"
  [IO.File]::WriteAllText((Join-Path $Drive "mealplanner-secrets.env"), $text)
  Write-Host "  copied the app's settings (the Pi installs them, then deletes them from the card)"
} else {
  # A file left by an earlier run would be installed by the Pi.
  $stale = Join-Path $Drive "mealplanner-secrets.env"
  if (Test-Path $stale) { Remove-Item $stale -Force; Write-Host "  removed the settings file an earlier run left on the card" }
}
if ($GcalKeyFile) {
  Copy-Item $GcalKeyFile (Join-Path $Drive "mealplanner-gcal-key.json") -Force
  Write-Host "  copied the Google Calendar key"
} else {
  $stale = Join-Path $Drive "mealplanner-gcal-key.json"
  if (Test-Path $stale) { Remove-Item $stale -Force; Write-Host "  removed the calendar key an earlier run left on the card" }
}

# --- make the Pi run the hook on its first boot ----------------------------
$firstrun = Join-Path $Drive "firstrun.sh"
$userdata = Join-Path $Drive "user-data"
$customToml = Join-Path $Drive "custom.toml"
$cmdline  = Join-Path $Drive "cmdline.txt"
$call     = "bash /boot/firmware/mealplanner-hook.sh"
$hookMarker = "mealplanner-hook.sh"

if (Test-Path $firstrun) {
  # Imager's own customisation is in charge of the first boot; add our call
  # just before the part where it removes itself.
  $text = [IO.File]::ReadAllText($firstrun)
  if ($text -match [regex]::Escape($call)) {
    Write-Host "  firstrun.sh already calls the hook"
  } else {
    if ($text -match '(?m)^\s*rm\s+-f\s+/boot/firmware/firstrun\.sh') {
      $text = $text -replace '(?m)^(\s*rm\s+-f\s+/boot/firmware/firstrun\.sh)', "$call`n`$1"
    } else {
      $text = $text.TrimEnd() + "`n$call`n"
    }
    [IO.File]::WriteAllText($firstrun, ($text -replace "`r`n", "`n"))
    Write-Host "  hooked into Imager's firstrun.sh"
  }
} elseif (Test-Path $userdata) {
  # Newer Raspberry Pi Imager versions (2.x+) customise the card with
  # cloud-init instead of firstrun.sh: a #cloud-config user-data file
  # alongside meta-data/network-config. Hook into its runcmd list instead --
  # cloud-init runs runcmd after networking is configured, so this timing is
  # safe (unlike the cmdline.txt fallback below, which must never be combined
  # with cloud-init: it would run the hook via a kernel-command-line rescue
  # target that boots instead of, not alongside, cloud-init's own first boot).
  $text = [IO.File]::ReadAllText($userdata)
  if ($text -match [regex]::Escape($hookMarker)) {
    Write-Host "  user-data already calls the hook"
  } else {
    $entry = "  - [ bash, /boot/firmware/mealplanner-hook.sh ]"
    if ($text -match '(?m)^runcmd:\s*$') {
      $text = $text -replace '(?m)^(runcmd:\s*)$', "`$1`n$entry"
    } else {
      $text = $text.TrimEnd() + "`nruncmd:`n$entry`n"
    }
    [IO.File]::WriteAllText($userdata, ($text -replace "`r`n", "`n"))
    Write-Host "  hooked into cloud-init's user-data"
  }
} else {
  # Neither of Imager's script hooks. Two quite different cards land here:
  #
  #   * Imager 1.9+ wrote custom.toml. raspberrypi-sys-mods' firstboot applies
  #     it from `init=` on the very first boot -- before systemd exists -- then
  #     strips its own init= and reboots. custom.toml has no "run a script"
  #     field, so we take the systemd.run slot as below; the reboot lands on it
  #     next boot, by which time custom.toml's user, Wi-Fi and SSH are in
  #     place. That ordering is what we want, so this is not a fallback.
  #
  #   * A card with no customisation at all, which is worth warning about: the
  #     Pi would come up with no user account and no way in.
  $customised = Test-Path $customToml
  $text = ([IO.File]::ReadAllText($cmdline)).Trim()
  if ($text -match 'mealplanner-hook') {
    Write-Host "  cmdline.txt already calls the hook"
  } elseif ($text -match 'systemd\.run=') {
    throw "cmdline.txt already has a systemd.run entry that isn't ours. Re-flash the card, or add '$call' to firstrun.sh by hand."
  } else {
    $text = "$text systemd.run=/boot/firmware/mealplanner-hook.sh systemd.run_success_action=reboot systemd.unit=kernel-command-line.target"
    [IO.File]::WriteAllText($cmdline, "$text`n")
    if ($customised) {
      Write-Host "  hooked into cmdline.txt (alongside Imager's custom.toml)"
    } else {
      Write-Host "  hooked into cmdline.txt"
      Write-Warning "No Imager customisation found on this card. Without it the Pi has no user account, Wi-Fi or SSH. Re-flash with Imager's settings (gear icon) unless you are using Ethernet and have set those another way."
    }
  }
}

Write-Host ""
Write-Host "Card ready." -ForegroundColor Green
Write-Host "Eject it, put it in the Pi, and power on. First boot takes 5-10 minutes"
Write-Host "while it installs; then the app is at http://<pi-name>.local:5000."
if (-not $Password -and -not $SecretsFile) { Write-Host "No password was set: after it boots, ssh in and run set_password.py (see docs/RASPBERRY-PI.md)." -ForegroundColor Yellow }
