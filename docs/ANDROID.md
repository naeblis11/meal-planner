# Meal Planner for Android

A standalone Android version of Meal Planner: recipe library, meal calendar, pantry and pantry-aware shopping list, on the phone, with no account. It works on its own, and it can also join the Meal Planner PC at home and sync with it over your Wi-Fi (see [Sync with your PC](#sync-with-your-pc)). It can put the week's meals on a calendar on the phone (your Google calendar, for example). It goes online only to check GitHub for a newer Meal Planner (and, when you choose, to download it) and, when you press **Sync**, to reach the PC you joined. It carries none of the integrations (Home Assistant, Alexa, voice, the Chrome extension).

A library moves between the phone and the Windows app as a zip of recipe files (see [Back up and move a library](#back-up-and-move-a-library)).

## Install

Android 8.0 or newer.

1. On the phone, open this repository's **Releases** page on GitHub and download the latest `MealPlanner-<version>.apk` from **Assets**.
2. Open the downloaded file. Android asks whether your browser (or file app) may install unknown apps: allow it for that app, install, and turn the setting off again afterwards if you like.
3. Open **Meal Planner**.

**Updating:** Meal Planner looks for a newer version on GitHub when you open it, at most once a day. When there is one, a notice says so: tap **See update** (or **Settings** -> **Updates**), then **Install**. The first time, Android asks you to allow Meal Planner to install unknown apps: tap **Open settings**, turn on **Allow from this source**, go back, and tap **Install** again. Android then shows its own install screen. If you leave the app while the update is downloading, it waits: come back and tap **Install** again. Your library, meal plan, pantry and shopping list stay. **Check for updates automatically** (on by default; off, it never looks when you open the app) and **Check for updates** are in **Settings** -> **Updates**. A download that doesn't match the signed release list is deleted and nothing is installed, and Android itself refuses an update not signed with the same key as the app you have.

Meal Planner 1.0.0 has no update check: install the next version by hand once, by downloading its APK from the Releases page as above. A debug build never checks.

**If the phone already has an earlier Meal Planner build** (a debug build from before version 1.0.0, which has the same package name `com.naeblis11.mealplanner`), the release cannot install over it, because the two are signed with different keys. Move your recipes across:

1. In the old app: **Recipes** -> **Settings** -> **Export backup**, and save the zip somewhere that survives an uninstall (Drive, Downloads).
2. Uninstall the old Meal Planner.
3. Install the release as above.
4. In the new app: **Recipes** -> **Import**, and choose the backup zip.

A backup holds recipes and photos only. **The meal plan, pantry and shopping list are not in a backup and will be lost** when you uninstall.

Debug builds made from now on install beside the release as **Meal Planner debug** and keep their own separate library, so this clash does not happen again.

## Sync with your PC

The phone can keep the same recipes, meal plan, pantry and shopping list as the Meal Planner PC at home. It syncs
only when you press **Sync**, over your home Wi-Fi; nothing runs in the background.

### Join a PC

1. On the PC, open **Settings** -> **Phones and PCs** and press **Pair a device**. A QR code and a 6-digit code show
   for five minutes.
2. On the phone, connect to your home Wi-Fi, then open **Settings** -> **Join a PC** and tap **Scan code**. Point the
   phone at the QR code. (Google Play services does the scanning, so Meal Planner needs no camera permission.)
3. If the phone can't scan, tap **Type code** and type the 6 digits. The phone looks for the PC on your Wi-Fi; if it
   finds more than one Meal Planner PC, choose yours by name.

Scanning is safer: typing works only on a home network you trust. When it worked, Settings says **Paired with** and
the PC's name.

### Sync

Each main screen (Recipes, Calendar, Pantry, Shopping) has the sync mark in its top bar, with when the phone last
synced beneath it. Tap it to sync. When it's done, a message says what moved, such as "Sent 3 changes, got 12". If
the phone can't reach the PC it says "Couldn't reach your PC — is it on and are you on the home Wi-Fi?". If the PC got
a new address, the phone finds it again by itself.

### The first sync: Merge or Replace

The first time you sync with a PC, the phone asks:

- **Merge** keeps this phone's recipes, plan and lists and combines them with the PC's. Recipes with the same name on
  both sides are then listed under **Possible duplicates**: for each, choose **Keep both**, or **Keep the PC's** to
  remove this phone's copy (from the PC too, at the next sync); meals planned with this phone's copy move to the PC's.
  Nothing is removed until you choose.
- **Replace** makes this phone a copy of the PC. It first asks where to save a backup of the phone's recipes (as
  **Export backup** does); if you cancel that, nothing changes.

### Leave

**Settings** -> **Join a PC** -> **Leave** stops this phone syncing with the PC. It asks first. The phone keeps all its
data, and the PC removes it from its list. If the PC removes this phone itself, the phone says so at its next sync and
keeps its data. You can join again at any time.

### Device check (owner only)

Before a release, the owner checks pairing and sync on a real phone (see the LAN sync spec's device checklist).

## Put the week's meals on your calendar

Meal Planner writes events into a calendar that is already on the phone; Android's own calendar sync carries them to Google. There is no Google sign-in or Cloud project in the app.

1. Make sure the phone has your Google account (phone **Settings** -> **Passwords & accounts**, or **Accounts**) with its calendar syncing.
2. In Meal Planner: **Recipes** -> **Settings** -> **Google Calendar** -> **Set up calendar sending**. Allow access to your calendar when Android asks, then pick the calendar to send meals to. (**Choose another calendar** changes it later.)
3. On the **Calendar** tab, show the week you want and tap **Send this week to Google Calendar**.

Each planned meal becomes an event such as "Dinner: Chili": breakfast at 7:00, lunch at 12:00, dinner at 18:00, one hour, in the phone's time zone, shown as free (not busy). The description has the servings, the ingredients (with their sections) and "Added by Meal Planner."

The app says what it did: "Calendar updated: 2 added, 1 removed." or "Your calendar was already up to date." If some meals could not be sent, a red notice names each one; send the week again to retry.

Sending is safe to repeat. Only what changed is written: new meals are added, changed meals update their event, and meals you removed from the plan have their event deleted. A second send with nothing changed writes nothing. If you delete one of the app's events in your calendar, the next send puts it back. Events you made yourself are never touched: every event the app writes ends with the line "Added by Meal Planner.", and the app only ever changes or deletes an event that carries that line and that it recorded when it sent it. Other weeks are left alone.

If the Calendar tab says to choose a calendar first, tap **Open Settings** and set up sending. If you said no to the calendar permission, tap **Open app settings** where the app explains it, then **Permissions** -> **Calendar** -> **Allow**. If the calendar you chose disappears from the phone, the app asks you to choose one again in Settings. Choosing a different calendar later leaves events already sent to the old one where they are; if you switch back, the app picks up managing them again.

## Back up and move a library

**Settings** -> **Export backup** saves one zip of every recipe and photo wherever you choose (Drive, Downloads, a USB stick). The meal plan, pantry, shopping list and calendar links are not in it.

**Import** (the Recipes screen's **Import**, or **Settings** -> **Import recipes**) takes a recipe file (`.yaml`, `.yml`), a Meal Master file (`.mmf`) or a zip. You review everything before anything is saved: a recipe with the same name as one you have is skipped unless you rename it, and one that is the same recipe (same `recipe_uuid`) is offered as an update.

- **Phone to PC:** the backup zip has the Windows app's library layout (`recipes/` and `recipe-images/`). With Meal Planner closed on the PC, unzip it into `Documents\Meal Planner`; the app indexes the files when it starts (see [WINDOWS.md](WINDOWS.md), "Bringing in recipe files").
- **PC to phone:** zip the library's `recipes` folder (with `recipe-images` beside it if you want the photos), copy the zip to the phone and import it.
- **Old phone to new phone:** Android's phone-to-phone transfer does not carry the library (the app keeps its database out of transfers so calendar links can't point at the wrong events). Export a backup on the old phone and import it on the new one.

## Build from source

You need Android Studio (see the README's Requirements). From the repository folder in PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\apps\gradlew.bat -p apps :shared:core:desktopTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

The APK is `apps\androidApp\build\outputs\apk\debug\androidApp-debug.apk`. A debug build installs as **Meal Planner debug** (`com.naeblis11.mealplanner.debug`), beside the released app, with its own separate library.

## Building a signed release

Releases are signed with your own keystore, which lives outside the repository in `%LOCALAPPDATA%\Meal Planner\android-release.jks`. Its path and passwords go in `apps\keystore.properties`, which git ignores (`apps\keystore.properties.example` shows the format). The public export refuses to copy either.

Run this once, in Windows PowerShell, from the repository folder. It makes the keystore if there isn't one yet, then writes `apps\keystore.properties` from a password you type. The password is never written on a command line or in this document.

When `keytool` asks, choose a keystore password (at least 6 characters) and type it again to confirm. It asks no name questions: the certificate is named "Meal Planner", because whatever is in it can be read by anyone who downloads the app, for every version. (It does not ask for a separate key password: the key uses the same one.) Then type the same password once more at the script's own prompt, "Type the keystore password again".

```powershell
if (-not (Test-Path "apps\gradlew.bat")) { Write-Warning "Run this from the Meal Planner repository folder." } else {
$keytool = "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
$dir = Join-Path $env:LOCALAPPDATA "Meal Planner"
$jks = Join-Path $dir "android-release.jks"
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
if (-not (Test-Path $jks)) {
  & $keytool -genkeypair -keystore $jks -storetype PKCS12 -alias meal-planner -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Meal Planner"
}
if (Test-Path $jks) {
  $secure = Read-Host "Type the keystore password again" -AsSecureString
  $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try { $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
  $lines = @(
    ("storeFile=" + ($jks -replace '\\', '/')),
    ("storePassword=" + ($plain -replace '\\', '\\')),
    "keyAlias=meal-planner",
    ("keyPassword=" + ($plain -replace '\\', '\\'))
  )
  $text = ($lines -join "`n") + "`n"
  [System.IO.File]::WriteAllText((Join-Path (Get-Location) "apps\keystore.properties"), $text, (New-Object System.Text.UTF8Encoding $false))
  $plain = $null
  git check-ignore -q apps/keystore.properties
  if ($LASTEXITCODE -ne 0) { Write-Warning "apps\keystore.properties is not ignored by git: do not commit it" } else { Write-Host "Done: apps\keystore.properties written." }
}
}
```

The file uses forward slashes in `storeFile` and doubles any backslash in the password (Java reads a backslash in a `.properties` file as an escape), and it is written without a byte-order mark.

If the password you typed at the script's prompt does not match the keystore's, `assembleRelease` fails with "keystore password was incorrect". Run the block again: it skips `keytool` when the `.jks` already exists and just rewrites `apps\keystore.properties`.

**Back up the keystore file (`android-release.jks`) and its password somewhere safe** (a password manager is a good place). Every update must be signed with the same key: if you lose either, nobody can install a new version over the old one; they have to uninstall first, losing their meal plan, pantry and shopping list.

Then build the release:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\apps\gradlew.bat -p apps assembleRelease
```

The signed APK is `apps\androidApp\build\outputs\apk\release\androidApp-release.apk`. Without a complete `apps\keystore.properties` the release build stops with a message that points here; debug builds never need it.

## Releasing a new version

The version is in `apps/gradle.properties`: raise `mealplanner.androidVersionCode` by one for every release and set `mealplanner.androidVersionName` (`1.0.1`, `1.1.0`, ...). One release carries both the Android and the Windows app, with the signed `latest.json` the apps check against; `tools/release.ps1` (in the private working repository, outside the public one) builds, signs and (after asking) publishes it. See [RELEASING.md](RELEASING.md).

## Privacy

- No analytics, no accounts.
- Internet: only to ask GitHub (github.com and its download hosts, over https) whether there is a newer Meal Planner, at most once a day when the app opens and when you tap **Check for updates**, and to download it when you tap **Install**. It sends nothing about you.
- Your home network: when you press **Sync** or join a PC, the phone talks only to the Meal Planner PC you joined, over an encrypted connection checked against that PC's own certificate. It finds the PC on your Wi-Fi with Android's own network discovery. Scanning the PC's code uses Google's code scanner in Google Play services. Meal Planner removes the scanner library's own upload pipeline; Google Play services, which runs the scanner, may record that it was used.
- Installing apps: only to hand a downloaded update, once checked against the signed release list, to Android's own installer, when you tap **Install**.
- Reading and writing calendars, asked for when you set up calendar sending, and used only to write the meal events.
- Everything lives in the app's private storage on the phone and in the backups you export. Android's own cloud backup of the app is turned off.
