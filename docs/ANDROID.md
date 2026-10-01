# Meal Planner for Android

A standalone Android version of Meal Planner: recipe library, meal calendar, pantry and pantry-aware shopping list, on the phone, with no server, PC or account. It can put the week's meals on a calendar on the phone (your Google calendar, for example). Its only permissions are for that calendar sending; it has no internet permission. It carries none of the integrations (Home Assistant, Alexa, voice, the Chrome extension).

A library moves between the phone and the Pi or PC version as a zip of recipe files (see [Back up and move a library](#back-up-and-move-a-library)).

## Install

Android 8.0 or newer.

1. On the phone, open this repository's **Releases** page on GitHub and download the latest `meal-planner-<version>.apk` from **Assets**.
2. Open the downloaded file. Android asks whether your browser (or file app) may install unknown apps: allow it for that app, install, and turn the setting off again afterwards if you like.
3. Open **Meal Planner**.

**Updating:** download the newer APK and install it over the old one; your library, meal plan, pantry and shopping list stay.

**If the phone already has an earlier Meal Planner build** (a debug build from before version 1.0.0, which has the same package name `com.naeblis11.mealplanner`), the release cannot install over it, because the two are signed with different keys. Move your recipes across:

1. In the old app: **Recipes** -> **Settings** -> **Export backup**, and save the zip somewhere that survives an uninstall (Drive, Downloads).
2. Uninstall the old Meal Planner.
3. Install the release as above.
4. In the new app: **Recipes** -> **Import**, and choose the backup zip.

A backup holds recipes and photos only. **The meal plan, pantry and shopping list are not in a backup and will be lost** when you uninstall.

Debug builds made from now on install beside the release as **Meal Planner debug** and keep their own separate library, so this clash does not happen again.

## Put the week's meals on your calendar

Meal Planner writes events into a calendar that is already on the phone; Android's own calendar sync carries them to Google. There is no Google sign-in, Cloud project or network access in the app.

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

- **Phone to Pi or PC:** the backup zip has the Pi's own layout (`recipes/` and `recipe-images/`). Unzip it into the Meal Planner data folder there, then use **Rescan recipes/ folder** on the recipe list.
- **Pi or PC to phone:** zip the data folder's `recipes` folder (with `recipe-images` beside it if you want the photos), copy the zip to the phone and import it.
- **Old phone to new phone:** Android's phone-to-phone transfer does not carry the library (the app keeps its database out of transfers so calendar links can't point at the wrong events). Export a backup on the old phone and import it on the new one.

## Build from source

You need Android Studio (see the README's Requirements). From the repository folder in PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\android\gradlew.bat -p android testDebugUnitTest assembleDebug
```

The APK is `android\app\build\outputs\apk\debug\app-debug.apk`. A debug build installs as **Meal Planner debug** (`com.naeblis11.mealplanner.debug`), beside the released app, with its own separate library.

## Building a signed release

Releases are signed with your own keystore, which lives outside the repository in `%LOCALAPPDATA%\Meal Planner\android-release.jks`. Its path and passwords go in `android\keystore.properties`, which git ignores (`android\keystore.properties.example` shows the format). The public export refuses to copy either.

Run this once, in Windows PowerShell, from the repository folder. It makes the keystore if there isn't one yet, then writes `android\keystore.properties` from a password you type. The password is never written on a command line or in this document.

When `keytool` asks, choose a keystore password (at least 6 characters), type it again to confirm, and answer the name questions however you like, and type `yes` at the last question ("Is CN=... correct?"): pressing Enter there means no and sends you back to the name questions. (It does not ask for a separate key password: the key uses the same one.) Then type the same password once more at the script's own prompt, "Type the keystore password again".

```powershell
if (-not (Test-Path "android\gradlew.bat")) { Write-Warning "Run this from the Meal Planner repository folder." } else {
$keytool = "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
$dir = Join-Path $env:LOCALAPPDATA "Meal Planner"
$jks = Join-Path $dir "android-release.jks"
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
if (-not (Test-Path $jks)) {
  & $keytool -genkeypair -keystore $jks -storetype PKCS12 -alias meal-planner -keyalg RSA -keysize 4096 -validity 10000
}
if (Test-Path $jks) {
  $secure = Read-Host "Type the keystore password again" -AsSecureString
  $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try { $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
  $lines = @(
    "storeFile=" + ($jks -replace '\\', '/'),
    "storePassword=" + ($plain -replace '\\', '\\'),
    "keyAlias=meal-planner",
    "keyPassword=" + ($plain -replace '\\', '\\')
  )
  $text = ($lines -join "`n") + "`n"
  [System.IO.File]::WriteAllText((Join-Path (Get-Location) "android\keystore.properties"), $text, (New-Object System.Text.UTF8Encoding $false))
  $plain = $null
  git check-ignore -q android/keystore.properties
  if ($LASTEXITCODE -ne 0) { Write-Warning "android\keystore.properties is not ignored by git: do not commit it" } else { Write-Host "Done: android\keystore.properties written." }
}
}
```

The file uses forward slashes in `storeFile` and doubles any backslash in the password (Java reads a backslash in a `.properties` file as an escape), and it is written without a byte-order mark.

If the password you typed at the script's prompt does not match the keystore's, `assembleRelease` fails with "keystore password was incorrect". Run the block again: it skips `keytool` when the `.jks` already exists and just rewrites `android\keystore.properties`.

**Back up the keystore file (`android-release.jks`) and its password somewhere safe** (a password manager is a good place). Every update must be signed with the same key: if you lose either, nobody can install a new version over the old one; they have to uninstall first, losing their meal plan, pantry and shopping list.

Then build the release:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\android\gradlew.bat -p android assembleRelease
```

The signed APK is `android\app\build\outputs\apk\release\app-release.apk`. Without a complete `android\keystore.properties` the release build stops with a message that points here; debug builds never need it.

## Releasing a new version

1. In `android/app/build.gradle.kts`, raise `versionCode` by one and set `versionName` to the new version (`1.0.1`, `1.1.0`, ...). The first release is `versionCode = 1`, `versionName = "1.0.0"`.
2. Commit, run the tests, and build the release as above.
3. Export the public copy and push it (`tools/export_public.py`).
4. Create a GitHub Release tagged `android-v<versionName>` on the public repository (the prefix keeps these tags apart from the Pi app's), with the APK renamed `meal-planner-<versionName>.apk` attached. You run this yourself, with the [GitHub CLI](https://cli.github.com/) signed in; for 1.0.0, from the repository folder in PowerShell:

```powershell
Copy-Item android\app\build\outputs\apk\release\app-release.apk "$env:TEMP\meal-planner-1.0.0.apk"
gh release create android-v1.0.0 "$env:TEMP\meal-planner-1.0.0.apk" --repo naeblis11/meal-planner --target master --title "Meal Planner for Android 1.0.0" --notes "The first Android release. Install and setup: docs/ANDROID.md."
```

For a later version, change the three places that say `1.0.0` (and the notes).

## Privacy

- No internet permission, no analytics, no accounts.
- The only permissions are reading and writing calendars, asked for when you set up calendar sending, and used only to write the meal events.
- Everything lives in the app's private storage on the phone and in the backups you export. Android's own cloud backup of the app is turned off.
