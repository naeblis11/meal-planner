<#
.SYNOPSIS
Reads a built Meal Planner MSI and checks what it would install (plan 7). Installs nothing.

.DESCRIPTION
Opens the MSI's database read-only through Windows Installer's COM API, reads its tables and its summary
information, and checks them. It fails closed (P7-T2a): whatever it can't show is safe, it rejects.
- That every table is on $allowedTables, each listed with why it is safe: the tables it judges, and those jpackage's
  per-user MSI has that can't point anything at a folder. Any other table fails, even empty, and so does a removal,
  move or copy table it doesn't model (*RemoveFolder*, MoveFile, DuplicateFile, RemoveRegistry*).
- That every custom action is one of the allowed base types: a DLL from the Binary table (1), and then only one of
  jpackage's exact (Action, Binary, entry point) triples in $allowedDllActions, an error (19), a
  JpSetARP* action setting its own ARP property ($allowedPropertyActions) (51), or INSTALLDIR set to exactly the
  install folder (35 or 51). An exe, a script or a nested install fails, and so does any other type.
- That the Upgrade table names only this app's upgrade code, so no other product is found or removed.
- The product name, manufacturer, version and upgrade code.
- That it installs per user without elevation: ALLUSERS is empty, or 2 with MSIINSTALLPERUSER=1, and the
  summary's word count has bit 8 (no elevated rights needed).
- That INSTALLDIR is %LOCALAPPDATA%\Meal-Planner (P7-R2b: one level, since WiX's ICE64 rejects the two-level
  per-user folder jpackage would make), and that nothing in the MSI can point a folder, a removal or a command at
  %LOCALAPPDATA%\Meal Planner (the secrets folder) or Documents\Meal Planner (the library):
  jpackage's uninstall removes the install folder with everything in it (P7-PF4). In particular:
  - no folder in the Directory table resolves to either, and none has a MEALPL~ short name directly under
    LocalAppData or Documents;
  - no Property row, type 35 or 51 custom action or AppSearch sets a folder (a Directory id or a Windows Installer
    folder property), except INSTALLDIR set to exactly the install folder;
  - no custom action's target, property or registry value mentions either folder;
  - every folder removed on uninstall (util:RemoveFolderEx in WiX 3 or 4, RemoveFile) is inside the install folder,
    traced through AppSearch, RegLocator and the Registry row that writes it. Only one empty-folder removal outside it is allowed:
    the Start-menu group, which Windows Installer removes only when it is empty.
- The Start-menu and desktop shortcuts.
It never installs, repairs or extracts anything, and writes no file. The build runs it after every packageMsi
(the inspectMsi task), which renames a rejected MSI to .msi.rejected.

.PARAMETER Msi
The MSI to read; by default the newest under apps\desktopApp\build\compose\binaries\main\msi.

.PARAMETER TablesJson
Judge tables from a JSON file instead of an MSI (for the tests): an object whose keys are table names and whose
values are arrays of rows, each an array of the columns in $tableColumns, in order. A missing key is a missing table.
"SummaryInformation" holds [PID, value] rows. Keys starting with "_" are notes.

.PARAMETER Version
The ProductVersion to expect; by default mealplanner.desktopVersion from apps\gradle.properties. The build passes it.

.PARAMETER UpgradeCode
The UpgradeCode to expect; by default msiUpgradeUuid from apps\desktopApp\build.gradle.kts. The build passes it.

.PARAMETER SelfTest
Runs the checks against built-in tables, a good set and one bad change at a time, and then every *.json in
-CasesDir (each says in "_expect" the failure it must give, or null to pass), and exits 0 when each verdict is the
expected one. Needs no MSI.

.PARAMETER CasesDir
With -SelfTest: a folder of case files, judged as version 1.0.0.
#>
param([string]$Msi, [string]$TablesJson, [string]$Version, [string]$UpgradeCode, [switch]$SelfTest, [string]$CasesDir)

$ErrorActionPreference = 'Stop'
$appsDir = Split-Path -Parent $PSScriptRoot
# P7-R2b: one level under LocalAppData. Not the secrets folder (Meal Planner, with a space), its 8.3 name is MEAL-P~1,
# never MEALPL~, and it never contains AppData\Local\Meal Planner, so the protections below hold as they are.
$expectedInstallDir = 'LocalAppDataFolder\Meal-Planner'
$secretsName = 'the secrets folder'
$documentsName = 'Documents\Meal Planner'
# The only empty-folder removal allowed outside the install folder (see the description).
$emptyFolderRemovalsAllowed = @('ProgramMenuFolder\Meal Planner')

# Windows Installer's folder properties (and the root), which name folders like Directory ids do.
$standardFolders = @(
    'TARGETDIR', 'ROOTDRIVE', 'SourceDir', 'AdminToolsFolder', 'AppDataFolder', 'CommonAppDataFolder', 'CommonFiles64Folder',
    'CommonFiles6432Folder', 'CommonFilesFolder', 'DesktopFolder', 'FavoritesFolder', 'FontsFolder', 'LocalAppDataFolder',
    'MyPicturesFolder', 'NetHoodFolder', 'PersonalFolder', 'PrintHoodFolder', 'ProgramFiles64Folder', 'ProgramFiles6432Folder',
    'ProgramFilesFolder', 'ProgramMenuFolder', 'RecentFolder', 'SendToFolder', 'StartMenuFolder', 'StartupFolder',
    'System16Folder', 'System64Folder', 'System6432Folder', 'SystemFolder', 'TempFolder', 'TemplateFolder', 'WindowsFolder',
    'WindowsVolume'
)
# Where the per-user folders are, so "..", %APPDATA% and literal paths compare in one spelling.
$folderLayout = @{
    'LocalAppDataFolder' = '%USERPROFILE%\AppData\Local'
    'AppDataFolder'      = '%USERPROFILE%\AppData\Roaming'
    'PersonalFolder'     = '%USERPROFILE%\Documents'
    'DesktopFolder'      = '%USERPROFILE%\Desktop'
    'FavoritesFolder'    = '%USERPROFILE%\Favorites'
    'MyPicturesFolder'   = '%USERPROFILE%\Pictures'
    'StartMenuFolder'    = '%USERPROFILE%\AppData\Roaming\Microsoft\Windows\Start Menu'
    'ProgramMenuFolder'  = '%USERPROFILE%\AppData\Roaming\Microsoft\Windows\Start Menu\Programs'
    'StartupFolder'      = '%USERPROFILE%\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup'
    'TempFolder'         = '%USERPROFILE%\AppData\Local\Temp'
}
$environmentFolders = @{ 'LOCALAPPDATA' = 'LocalAppDataFolder'; 'APPDATA' = 'AppDataFolder'; 'TEMP' = 'TempFolder'; 'TMP' = 'TempFolder' }

# The columns read from each table, in order. Every value is kept as a string.
$tableColumns = [ordered]@{
    'Property'          = @('Property', 'Value')
    'Directory'         = @('Directory', 'Directory_Parent', 'DefaultDir')
    'Shortcut'          = @('Shortcut', 'Directory_', 'Name')
    'CustomAction'      = @('Action', 'Type', 'Source', 'Target')
    'Registry'          = @('Registry', 'Root', 'Key', 'Name', 'Value')
    'AppSearch'         = @('Property', 'Signature_')
    'RegLocator'        = @('Signature_', 'Root', 'Key', 'Name', 'Type')
    'WixRemoveFolderEx' = @('WixRemoveFolderEx', 'Property')
    'Wix4RemoveFolderEx' = @('Wix4RemoveFolderEx', 'Property')
    'RemoveFile'        = @('FileKey', 'DirProperty', 'FileName')
    'Upgrade'           = @('UpgradeCode', 'ActionProperty')
    'Signature'         = @('Signature')
}

# P7-T2a: the only tables an MSI may have. Any other table fails the check, even empty: a table this script doesn't read
# could install, run or remove something it never sees. Seeded with what jpackage 25's per-user MSI has when built with
# WiX 3.11 (main.wxs, os-condition.wxf, WixAppImageFragmentBuilder; no file associations, services, license or dialogs:
# with no folder chooser, shortcut prompt or license, WixUiFragmentBuilder writes an empty <UI Id="JpUI"/>) plus the
# tables WiX 3.11's light adds by itself. A table that can't be shown to be in that MSI is left off: the first real MSI
# names it, and only that table is added, with its reason (CLAUDE.md, "Packaging").
$allowedTables = [ordered]@{
    # Read and judged by Test-MsiTables.
    'Property'            = 'checked: no folder is set (bar INSTALLDIR, exactly) and no value names a protected folder'
    'Directory'           = 'checked: INSTALLDIR, no protected folder, no MEALPL~ short name under LocalAppData or Documents'
    'CustomAction'        = 'checked: only DLL-in-Binary (1), error (19), property (51) and INSTALLDIR (35) actions'
    'Registry'            = 'checked: no value names a protected folder; traced for the cleaner below'
    'AppSearch'           = 'checked: sets no folder; traced back to the Registry row it reads (jpackage''s RegistrySearch)'
    'RegLocator'          = 'checked: traced with AppSearch (jpackage''s directory cleaner reads HKCU)'
    'WixRemoveFolderEx'   = 'checked: WiX 3 util:RemoveFolderEx (jpackage''s rm -rf of INSTALLDIR) stays inside the install folder'
    'Wix4RemoveFolderEx'  = 'checked: WiX 4''s name for the same table, judged the same way'
    'RemoveFile'          = 'checked: per-user RemoveFolder rows stay inside the install folder, or are the empty Start-menu folders'
    'Shortcut'            = 'checked: the Start-menu and desktop shortcuts'
    # Not read: nothing in them can point an install, a removal or a command at a folder the checks above don't see.
    '_Validation'         = 'column metadata light writes for validation; nothing is installed or run from it'
    'InstallExecuteSequence' = 'when actions run (main.wxs schedules JpSetARP*, JpFindRelatedProducts, JpDisallow*); every custom action is in CustomAction, checked'
    'InstallUISequence'   = 'the same for the UI sequence (JpFindRelatedProducts)'
    'AdminExecuteSequence' = 'light''s default sequence of standard actions for an administrative install'
    'AdminUISequence'     = 'light''s default sequence of standard actions for an administrative install'
    'AdvtExecuteSequence' = 'light''s default sequence of standard actions for advertising'
    'Feature'             = 'main.wxs DefaultFeature: grouping only'
    'FeatureComponents'   = 'ties components to DefaultFeature: grouping only'
    'Component'           = 'the components of the files, registry key paths, shortcuts and cleaner, each in a Directory row (checked)'
    'File'                = 'the app image''s files, each in its component''s folder under INSTALLDIR (Directory checked)'
    'MsiFileHash'         = 'hashes light records for unversioned files: data only'
    'Media'               = 'main.wxs Media Data.cab: where the files come from'
    'Binary'              = 'JpCaDll (wixhelper.dll) and WixCA (util''s DLL): code runs only through CustomAction rows, checked by type'
    'Icon'                = 'JpARPPRODUCTICON and the shortcuts'' icons: data only'
    'CreateFolder'        = 'the app image''s empty folders (WixAppImageFragmentBuilder), in Directory rows (checked); removed only if empty'
    'Upgrade'             = 'checked: main.wxs Upgrade finds other versions for RemoveExistingProducts, so only this app''s upgrade code may appear'
    'LaunchCondition'     = 'os-condition.wxf: the Windows version check, which can only stop an install'
    # P7-FR2: the first real MSI (jpackage 25, WiX 3.11) has it with no rows; its cleaner's AppSearch reads the registry
    # (RegLocator), never a file. A row would make AppSearch look for a file or folder this check doesn't model.
    'Signature'           = 'checked: only with no rows (WiX 3.11 writes it empty for jpackage''s registry-only AppSearch)'
}
# Tables that remove, move or copy files or registry entries in ways the checks above don't model.
$unmodeledRemovals = @('*RemoveFolder*', 'MoveFile', 'DuplicateFile', 'RemoveRegistry*')
# P7-FW1: a DLL action (base type 1) can do anything, so only these exact (Action, Binary, entry point) triples pass,
# each one jpackage's per-user MSI has. Widen by exact triple only, with its source.
$allowedDllActions = @(
    # main.wxs: <CustomAction Id="JpFindRelatedProducts" BinaryKey="JpCaDll" DllEntry="FindRelatedProductsEx" />
    'JpFindRelatedProducts|JpCaDll|FindRelatedProductsEx'
    # WiX 3.11's util extension, pulled in by the util:RemoveFolderEx of jpackage's directory cleaner
    # (WixAppImageFragmentBuilder.addDirectoryCleaner); type 65, 1 with "continue on error".
    'WixRemoveFoldersEx|WixCA|WixRemoveFoldersEx'
)
# The only properties a type 51 action may set (bar the folder rule's INSTALLDIR), each by its own action, exactly as
# main.wxs declares them: <CustomAction Id="JpSetARPx" Property="ARPx" Value="..." />.
$allowedPropertyActions = @(
    'JpSetARPINSTALLLOCATION|ARPINSTALLLOCATION'
    'JpSetARPCOMMENTS|ARPCOMMENTS'
    'JpSetARPCONTACT|ARPCONTACT'
    'JpSetARPSIZE|ARPSIZE'
    'JpSetARPHELPLINK|ARPHELPLINK'
    'JpSetARPURLINFOABOUT|ARPURLINFOABOUT'
    'JpSetARPURLUPDATEINFO|ARPURLUPDATEINFO'
)
# Custom action base types (Type with the option bits, 64 and up, taken off): an exe, a script or a nested install.
$programActionTypes = @(2, 5, 6, 7, 18, 21, 22, 23, 34, 37, 38, 39, 50, 53, 54)

# ---------------------------------------------------------------------------------------------------------------
# The decision logic: pure functions over the tables, no COM and no files.
# ---------------------------------------------------------------------------------------------------------------

# DefaultDir is "short|long", optionally "target:source"; the long target name is what Explorer shows.
function Get-LongName([string]$defaultDir) {
    $names = $defaultDir.Split(':')[0].Split('|')
    return $names[$names.Length - 1]
}

function Get-ShortName([string]$defaultDir) {
    return $defaultDir.Split(':')[0].Split('|')[0]
}

# One spelling for every path, as Windows reads it: backslashes, ".." taken off with the folder before it, "." and
# empty parts dropped, and trailing dots and spaces trimmed from each part.
function Format-MsiPath([string]$path) {
    $segments = New-Object System.Collections.Generic.List[string]
    foreach ($raw in $path.Replace('/', '\').Split('\')) {
        if ($raw.TrimEnd(' ') -eq '..') {
            if ($segments.Count -gt 0) { $segments.RemoveAt($segments.Count - 1) }
            continue
        }
        $segment = $raw.TrimEnd('.', ' ')
        if ($segment -ne '') { $segments.Add($segment) }
    }
    return ($segments -join '\')
}

# A path with every per-user folder name replaced by where it is, then formatted: compare paths in this form.
function ConvertTo-Canonical([string]$path) {
    $text = [regex]::Replace($path, '(?<=^|[\s"\\])([A-Za-z0-9]+Folder)(?=$|[\s"\\])', {
        param($match)
        $name = $match.Groups[1].Value
        if ($folderLayout.ContainsKey($name)) { return $folderLayout[$name] }
        return $match.Value
    })
    return (Format-MsiPath $text)
}

# A Directory row's path from the nearest standard folder, as "LocalAppDataFolder\Meal-Planner" (as written:
# compare it with ConvertTo-Canonical).
function Resolve-Folder([string]$id, [hashtable]$folders) {
    $parts = New-Object System.Collections.Generic.List[string]
    $current = $id
    for ($depth = 0; $depth -lt 32 -and $current; $depth++) {
        if ($standardFolders -contains $current) { $parts.Insert(0, $current); break }
        $entry = $folders[$current]
        if ($null -eq $entry) { $parts.Insert(0, "?$current"); break }
        $name = Get-LongName $entry.DefaultDir
        if ($name -ne '.') { $parts.Insert(0, $name) }
        if ($entry.Parent -eq $current) { break }
        $current = $entry.Parent
    }
    return ($parts -join '\')
}

# What a formatted value (a custom action's target, a property, a registry value) would become, as far as the tables
# tell: [DirId] and [PROPERTY] are filled in (a few levels deep), and [%LOCALAPPDATA%]-like references become folders.
function Resolve-Formatted([string]$text, [hashtable]$folders, [hashtable]$properties) {
    $value = [string]$text
    for ($round = 0; $round -lt 4; $round++) {
        $value = [regex]::Replace($value, '\[%([A-Za-z_]+)%\]|%([A-Za-z_]+)%', {
            param($match)
            $name = $match.Groups[1].Value + $match.Groups[2].Value
            if ($environmentFolders.ContainsKey($name)) { return $environmentFolders[$name] + '\' }
            if ($name -eq 'USERPROFILE') { return '%USERPROFILE%\' }
            return $match.Value
        })
        $value = [regex]::Replace($value, '\[([A-Za-z_][A-Za-z0-9_.]*)\]', {
            param($match)
            $name = $match.Groups[1].Value
            if ($standardFolders -contains $name -or $folders.ContainsKey($name)) { return (Resolve-Folder $name $folders) + '\' }
            if ($properties.ContainsKey($name)) { return $properties[$name] }
            return $match.Value
        })
    }
    return $value
}

# Which protected folder a path is, if any: the secrets folder or Documents\Meal Planner, however it is spelled.
function Get-ProtectedName([string]$path) {
    $text = ConvertTo-Canonical $path
    if ($text -match '(^|\\)AppData\\Local\\Meal Planner$') { return $secretsName }
    if ($text -match '(^|\\)Documents\\Meal Planner$') { return $documentsName }
    return $null
}

# Which protected folder a text (a command line, a value) mentions anywhere, if any, ignoring case.
function Find-ProtectedMention([string]$text) {
    foreach ($piece in @(@($text) + $text.Split('"'))) {
        $flat = ConvertTo-Canonical $piece
        if ($flat.IndexOf('AppData\Local\Meal Planner', [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $secretsName }
        if ($flat.IndexOf('Documents\Meal Planner', [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $documentsName }
    }
    return $null
}

function Test-IsInstallDir([string]$path) {
    return (ConvertTo-Canonical $path) -eq (ConvertTo-Canonical $expectedInstallDir)
}

# The install folder or a folder inside it.
function Test-IsInsideInstallDir([string]$path) {
    $text = ConvertTo-Canonical $path
    $root = ConvertTo-Canonical $expectedInstallDir
    return ($text -eq $root) -or $text.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)
}

# Judges the tables: returns @{ Failures = [...]; Passes = [...] }. $tables maps a table name to its rows (each an
# array of strings in $tableColumns' order); a missing or null entry is a table the MSI doesn't have.
function Test-MsiTables([hashtable]$tables, [string]$version, [string]$upgradeCode) {
    $failures = New-Object System.Collections.Generic.List[string]
    $passes = New-Object System.Collections.Generic.List[string]
    function Rows([string]$name) { if ($tables.ContainsKey($name) -and $null -ne $tables[$name]) { return , @($tables[$name]) } return , @() }

    # Only allowlisted tables (SummaryInformation is the summary stream, not a table).
    foreach ($name in @($tables.Keys | Where-Object { $_ -ne 'SummaryInformation' } | Sort-Object)) {
        # Case-sensitive, as Windows Installer's table names are.
        if (@($allowedTables.Keys) -ccontains $name) { continue }
        $removal = @($unmodeledRemovals | Where-Object { $name -like $_ }).Count -gt 0
        if ($removal) { $failures.Add("the MSI has the table $name, which removes, moves or copies files or registry entries in ways this check doesn't model") }
        else { $failures.Add("the MSI has the table $name, which isn't on the allowlist") }
    }

    # The product.
    $properties = @{}
    foreach ($row in (Rows 'Property')) { $properties[[string]$row[0]] = [string]$row[1] }
    foreach ($pair in @(@('ProductName', 'Meal Planner'), @('Manufacturer', 'Meal Planner'), @('ProductVersion', $version), @('UpgradeCode', $upgradeCode))) {
        $actual = $properties[$pair[0]]
        if ($actual -eq $pair[1]) { $passes.Add("$($pair[0]) is $actual") } else { $failures.Add("$($pair[0]) is '$actual', not '$($pair[1])'") }
    }

    # Per user, without elevation.
    $allUsers = [string]$properties['ALLUSERS']
    if ($allUsers -eq '1') {
        $failures.Add('ALLUSERS is 1: a per-machine install, which needs admin rights')
    } elseif ($allUsers -eq '2' -and $properties['MSIINSTALLPERUSER'] -ne '1') {
        $failures.Add('ALLUSERS is 2 without MSIINSTALLPERUSER=1: Windows may install it per machine')
    } elseif ($allUsers -ne '' -and $allUsers -ne '2') {
        $failures.Add("ALLUSERS is '$allUsers', not empty or 2")
    } else {
        $passes.Add("a per-user install (ALLUSERS is '$allUsers')")
    }
    $wordCount = $null
    foreach ($row in (Rows 'SummaryInformation')) { if ([string]$row[0] -eq '15') { $wordCount = [string]$row[1] } }
    $words = 0
    if ($null -eq $wordCount -or -not [int]::TryParse($wordCount, [ref]$words)) {
        $failures.Add('the summary information has no word count, so it can''t show the install needs no elevation')
    } elseif (($words -band 8) -eq 0) {
        $failures.Add("the summary's word count is $($words): it asks for elevated rights (bit 8 isn't set)")
    } else {
        $passes.Add("the summary's word count is $($words): no elevated rights needed")
    }

    # The folders.
    $folders = @{}
    foreach ($row in (Rows 'Directory')) { $folders[[string]$row[0]] = @{ Parent = [string]$row[1]; DefaultDir = [string]$row[2] } }
    $folderNames = @($standardFolders) + @($folders.Keys)
    function Test-IsFolderName([string]$name) { return ($folderNames -contains $name) }
    if (-not $folders.ContainsKey('INSTALLDIR')) {
        $failures.Add('the Directory table has no INSTALLDIR')
    } else {
        $installDir = Resolve-Folder 'INSTALLDIR' $folders
        $protected = Get-ProtectedName $installDir
        if ($protected) {
            $failures.Add("INSTALLDIR is $installDir, $protected, which uninstalling would delete")
        } elseif (Test-IsInstallDir $installDir) {
            $passes.Add("it installs into $installDir")
        } else {
            $failures.Add("INSTALLDIR is $installDir, not $expectedInstallDir")
        }
    }
    $sensitiveParents = @((ConvertTo-Canonical 'LocalAppDataFolder'), (ConvertTo-Canonical 'PersonalFolder'))
    foreach ($id in @($folders.Keys | Sort-Object)) {
        $path = Resolve-Folder $id $folders
        if ($id -ne 'INSTALLDIR') {
            $protected = Get-ProtectedName $path
            if ($protected) { $failures.Add("the folder $id is $path, $protected") }
        }
        $short = Get-ShortName $folders[$id].DefaultDir
        $parent = Resolve-Folder $folders[$id].Parent $folders
        if ($short -like 'MEALPL~*' -and $sensitiveParents -contains (ConvertTo-Canonical $parent)) {
            $failures.Add("the folder $id has the short name $short directly under $parent, which could be $secretsName or $documentsName")
        }
    }

    # Nothing may set a folder, except INSTALLDIR to exactly the install folder.
    $setters = @{}
    function Add-Setter([string]$name, [string]$value) {
        if (-not $setters.ContainsKey($name)) { $setters[$name] = New-Object System.Collections.Generic.List[string] }
        $setters[$name].Add($value)
    }
    foreach ($name in @($properties.Keys | Sort-Object)) {
        $value = Resolve-Formatted $properties[$name] $folders $properties
        if (Test-IsFolderName $name) {
            if (-not ($name -eq 'INSTALLDIR' -and (Test-IsInstallDir $value))) { $failures.Add("the Property table sets the folder $name to $value") }
        }
        $mention = Find-ProtectedMention $value
        if ($mention) { $failures.Add("the property $name names $($mention): $value") }
    }
    foreach ($row in (Rows 'CustomAction')) {
        $action = [string]$row[0]
        $source = [string]$row[2]
        $target = Resolve-Formatted ([string]$row[3]) $folders $properties
        $mention = Find-ProtectedMention $target
        if ($mention) { $failures.Add("the custom action $action's target names $($mention): $target") }
        $type = 0
        if (-not [int]::TryParse([string]$row[1], [ref]$type)) { $failures.Add("the custom action $action has the type '$($row[1])'"); continue }
        # P7-T2a: allowed by base type (the type without its option bits, 64 and up). 1: a function in a DLL from the
        # Binary table, and only one of $allowedDllActions' exact triples (P7-FW1). 19: shows an error and stops
        # (JpDisallowUpgrade, JpDisallowDowngrade). 51: sets a property that isn't a folder (JpSetARP*). 35: sets a
        # folder, INSTALLDIR to exactly the install folder only.
        $base = $type -band 0x3F
        $shown = "$type"
        if ($base -ne $type) { $shown = "$type ($base with option bits)" }
        if ($programActionTypes -contains $base) { $failures.Add("the custom action $action has the type $shown, which runs a program, a script or another install"); continue }
        if ($base -eq 1) {
            # Case-sensitive, as Windows Installer's keys and DLL entry points are.
            if ($allowedDllActions -cnotcontains "$action|$source|$([string]$row[3])") {
                $failures.Add("the custom action $action runs $source's $([string]$row[3]) (type $shown), which isn't one of jpackage's DLL actions")
            }
            continue
        }
        if ($base -eq 19) { continue }
        if ($base -ne 35 -and $base -ne 51) { $failures.Add("the custom action $action has the type $shown, which isn't one this check allows"); continue }
        Add-Setter $source $target
        # Type 35 always sets a folder.
        if ($base -eq 35 -or (Test-IsFolderName $source)) {
            if (-not ($source -eq 'INSTALLDIR' -and (Test-IsInstallDir $target))) { $failures.Add("the custom action $action sets the folder $source to $target") }
        } elseif ($allowedPropertyActions -cnotcontains "$action|$source") {
            # Any other property could steer the install (MSIFASTINSTALL, REINSTALLMODE, a removal's property, ...).
            $failures.Add("the custom action $action sets the property $source, which isn't the ARP property its jpackage JpSetARP* action sets")
        }
    }

    # The Upgrade table: FindRelatedProducts and RemoveExistingProducts act on every product it names, so it may name
    # only this app (main.wxs: two UpgradeVersion rows, both with JpProductUpgradeCode).
    # P7-FR2: the Signature table passes only empty.
    $signatures = (Rows 'Signature').Count
    if ($signatures -gt 0) { $failures.Add("the Signature table has $signatures row(s), which would make AppSearch look for files this check doesn't model") }

    foreach ($row in (Rows 'Upgrade')) {
        if ([string]$row[0] -eq $upgradeCode) { $passes.Add("the Upgrade row $($row[1]) finds this app's upgrade code") }
        else { $failures.Add("the Upgrade table finds the upgrade code $($row[0]) ($($row[1])), not this app's $upgradeCode, and could remove that product") }
    }
    foreach ($row in (Rows 'AppSearch')) {
        if (Test-IsFolderName ([string]$row[0])) { $failures.Add("AppSearch sets the folder $($row[0]) while installing") }
    }
    foreach ($row in (Rows 'Registry')) {
        $value = Resolve-Formatted ([string]$row[4]) $folders $properties
        $mention = Find-ProtectedMention $value
        if ($mention) { $failures.Add("the registry value $($row[0]) names $($mention): $value") }
    }

    # What a property names, when a removal uses it: a folder, its Property value and setters, or what AppSearch reads
    # back from the Registry row this MSI writes. Returns the paths, or $null when something can't be traced.
    function Resolve-RemovalTargets([string]$name) {
        $targets = New-Object System.Collections.Generic.List[string]
        if (Test-IsFolderName $name) { $targets.Add((Resolve-Folder $name $folders)); return , $targets }
        if ($properties.ContainsKey($name)) { $targets.Add((Resolve-Formatted $properties[$name] $folders $properties)) }
        if ($setters.ContainsKey($name)) { foreach ($t in $setters[$name]) { $targets.Add($t) } }
        foreach ($search in @((Rows 'AppSearch') | Where-Object { [string]$_[0] -eq $name })) {
            $locators = @((Rows 'RegLocator') | Where-Object { [string]$_[0] -eq [string]$search[1] })
            if ($locators.Count -eq 0) { return $null }
            foreach ($locator in $locators) {
                $key = (Resolve-Formatted ([string]$locator[2]) $folders $properties).Trim('\')
                $value = Resolve-Formatted ([string]$locator[3]) $folders $properties
                $written = @((Rows 'Registry') | Where-Object {
                    $root = [string]$_[1]
                    if ($root -eq '-1') { $root = '1' } # per user (checked above), so HKCU
                    ($root -eq [string]$locator[1]) -and
                        ((Resolve-Formatted ([string]$_[2]) $folders $properties).Trim('\') -eq $key) -and
                        ((Resolve-Formatted ([string]$_[3]) $folders $properties) -eq $value)
                })
                if ($written.Count -eq 0) { return $null }
                foreach ($row in $written) { $targets.Add((Resolve-Formatted ([string]$row[4]) $folders $properties)) }
            }
        }
        if ($targets.Count -eq 0) { return $null }
        return , $targets
    }

    # The uninstall's removals: util:RemoveFolderEx removes a folder with everything in it (WixRemoveFolderEx in WiX 3,
    # Wix4RemoveFolderEx in WiX 4 and later, judged alike).
    foreach ($row in @((Rows 'WixRemoveFolderEx') + (Rows 'Wix4RemoveFolderEx'))) {
        $property = [string]$row[1]
        $targets = Resolve-RemovalTargets $property
        if ($null -eq $targets) { $failures.Add("the uninstall removes the folder in $property, which can't be traced to a value this MSI writes"); continue }
        foreach ($target in $targets) {
            if (Test-IsInsideInstallDir $target) { $passes.Add("the uninstall removes $target ($property)") }
            else { $failures.Add("the uninstall removes $target ($property), which isn't inside the install folder") }
        }
    }
    foreach ($row in (Rows 'RemoveFile')) {
        $key = [string]$row[0]
        $property = [string]$row[1]
        $fileName = [string]$row[2]
        $targets = Resolve-RemovalTargets $property
        if ($null -eq $targets) { $failures.Add("the RemoveFile row $key removes from $property, which can't be traced to a value this MSI writes"); continue }
        foreach ($target in $targets) {
            if (Test-IsInsideInstallDir $target) { continue }
            $emptyFolder = ($fileName -eq '') -and @($emptyFolderRemovalsAllowed | Where-Object { (ConvertTo-Canonical $_) -eq (ConvertTo-Canonical $target) }).Count -gt 0
            if ($emptyFolder) { $passes.Add("the uninstall removes $target if it is empty ($key)") }
            else { $failures.Add("the RemoveFile row $key removes '$fileName' in $target, which isn't inside the install folder") }
        }
    }

    # The shortcuts.
    $shortcuts = @()
    if (-not $tables.ContainsKey('Shortcut') -or $null -eq $tables['Shortcut']) {
        $failures.Add('the MSI has no Shortcut table')
    } else {
        $shortcuts = @(foreach ($row in (Rows 'Shortcut')) { (Format-MsiPath (Resolve-Folder ([string]$row[1]) $folders)) + '\' + (Get-LongName ([string]$row[2])) })
    }
    foreach ($wanted in @('ProgramMenuFolder\Meal Planner\Meal Planner', 'DesktopFolder\Meal Planner')) {
        if ($shortcuts -contains $wanted) { $passes.Add("a shortcut at $wanted") } else { $failures.Add("no shortcut at $wanted (found: $($shortcuts -join ', '))") }
    }
    return @{ Failures = $failures; Passes = $passes }
}

# ---------------------------------------------------------------------------------------------------------------
# Reading: an MSI through COM (read-only), or a JSON file of tables.
# ---------------------------------------------------------------------------------------------------------------

function Invoke-Com($target, [string]$member, [string]$kind, [object[]]$arguments) {
    return $target.GetType().InvokeMember($member, [System.Reflection.BindingFlags]$kind, $null, $target, $arguments)
}

function Get-Rows($database, [string]$query, [int]$columns) {
    $rows = New-Object System.Collections.Generic.List[object]
    $view = Invoke-Com $database 'OpenView' 'InvokeMethod' @($query)
    try {
        [void](Invoke-Com $view 'Execute' 'InvokeMethod' $null)
        while ($true) {
            $record = Invoke-Com $view 'Fetch' 'InvokeMethod' $null
            if ($null -eq $record) { break }
            $row = New-Object string[] $columns
            for ($i = 1; $i -le $columns; $i++) { $row[$i - 1] = [string](Invoke-Com $record 'StringData' 'GetProperty' @($i)) }
            $rows.Add($row)
        }
    } finally {
        [void](Invoke-Com $view 'Close' 'InvokeMethod' $null)
    }
    return , $rows
}

function Read-MsiTables([string]$path) {
    $tables = @{}
    $installer = New-Object -ComObject WindowsInstaller.Installer
    # 0 is msiOpenDatabaseModeReadOnly: nothing can be written back.
    $database = Invoke-Com $installer 'OpenDatabase' 'InvokeMethod' @($path, 0)
    try {
        $present = @(foreach ($row in (Get-Rows $database 'SELECT `Name` FROM `_Tables`' 1)) { $row[0] })
        # Every table gets a key, so the allowlist sees them all; the ones not judged are listed without their rows.
        # The keys ignore case, so two names that differ only in case can't both be kept: refuse them.
        $clash = @($present | Group-Object { $_.ToUpperInvariant() } | Where-Object { $_.Count -gt 1 })
        if ($clash.Count -gt 0) { throw "The MSI has tables whose names differ only in case: $(($clash | ForEach-Object { $_.Group -join ' and ' }) -join '; ')." }
        foreach ($name in $present) {
            if ($tableColumns.Keys -ccontains $name) { continue }
            if ($name -eq 'SummaryInformation') { $tables['SummaryInformation (a table)'] = @(); continue }
            $tables[$name] = @()
        }
        foreach ($name in $tableColumns.Keys) {
            if ($present -cnotcontains $name) { continue }
            $columns = $tableColumns[$name]
            $query = 'SELECT ' + (($columns | ForEach-Object { '`' + $_ + '`' }) -join ', ') + ' FROM `' + $name + '`'
            $tables[$name] = (Get-Rows $database $query $columns.Count).ToArray()
        }
        # The summary stream, read-only (0 updates allowed). PID 15 is the word count.
        $summary = Invoke-Com $database 'SummaryInformation' 'GetProperty' @(0)
        try {
            $wordCount = Invoke-Com $summary 'Property' 'GetProperty' @(15)
            if ($null -ne $wordCount) { $tables['SummaryInformation'] = @(, [string[]]@('15', [string]$wordCount)) }
        } finally {
            [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($summary)
        }
    } finally {
        [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($database)
        [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer)
    }
    return $tables
}

# Returns @{ Tables = ...; Expect = the "_expect" note, or $null }.
function Read-JsonTables([string]$path) {
    $parsed = Get-Content -Raw -LiteralPath $path | ConvertFrom-Json
    $tables = @{}
    $expect = $null
    foreach ($property in $parsed.PSObject.Properties) {
        if ($property.Name -eq '_expect') { $expect = $property.Value; continue }
        if ($property.Name.StartsWith('_')) { continue }
        $tables[$property.Name] = @(foreach ($row in @($property.Value)) { , [string[]]@($row | ForEach-Object { [string]$_ }) })
    }
    return @{ Tables = $tables; Expect = $expect }
}

# ---------------------------------------------------------------------------------------------------------------
# The self-test: a jpackage-like per-user MSI's tables, and one bad change at a time.
# ---------------------------------------------------------------------------------------------------------------

function New-GoodTables([string]$upgradeCode) {
    $key = 'Software\Meal Planner\Meal Planner\1.0.0'
    return @{
        'Property'           = @(
            , @('ProductName', 'Meal Planner')
            , @('Manufacturer', 'Meal Planner')
            , @('ProductVersion', '1.0.0')
            , @('UpgradeCode', $upgradeCode)
            , @('ARPPRODUCTICON', 'JpARPPRODUCTICON')
        )
        'Directory'          = @(
            , @('TARGETDIR', '', 'SourceDir')
            , @('LocalAppDataFolder', 'TARGETDIR', '.')
            , @('INSTALLDIR', 'LocalAppDataFolder', 'MEAL-P~1|Meal-Planner')
            , @('dirApp', 'INSTALLDIR', 'app')
            , @('ProgramMenuFolder', 'TARGETDIR', '.')
            , @('dirMenu', 'ProgramMenuFolder', 'MEALPL~1|Meal Planner')
            , @('DesktopFolder', 'TARGETDIR', '.')
        )
        'Shortcut'           = @(
            , @('scMenu', 'dirMenu', 'MEALPL~1|Meal Planner')
            , @('scDesktop', 'DesktopFolder', 'MEALPL~1|Meal Planner')
        )
        'CustomAction'       = @(
            , @('JpSetARPINSTALLLOCATION', '51', 'ARPINSTALLLOCATION', '[INSTALLDIR]')
            , @('JpSetARPCOMMENTS', '51', 'ARPCOMMENTS', 'Meal Planner')
            , @('JpSetARPCONTACT', '51', 'ARPCONTACT', 'Meal Planner')
            , @('JpSetARPSIZE', '51', 'ARPSIZE', '250000')
            , @('JpFindRelatedProducts', '1', 'JpCaDll', 'FindRelatedProductsEx')
            , @('JpDisallowDowngrade', '19', '', 'A later version of [ProductName] is already installed.')
            , @('WixRemoveFoldersEx', '65', 'WixCA', 'WixRemoveFoldersEx')
        )
        'Registry'           = @(
            , @('regCleaner', '1', $key, 'RM_RF_INSTALLDIR', '[INSTALLDIR]')
            , @('regProductCode', '1', $key, 'product_code', '[ProductCode]')
        )
        'AppSearch'          = @(, @('RM_RF_INSTALLDIR', 'regSearchCleaner'))
        'RegLocator'         = @(, @('regSearchCleaner', '1', $key, 'RM_RF_INSTALLDIR', '2'))
        'WixRemoveFolderEx'  = @(, @('rmrfInstallDir', 'RM_RF_INSTALLDIR'))
        'RemoveFile'         = @(
            , @('rmDirApp', 'dirApp', '')
            , @('rmInstallDir', 'INSTALLDIR', '')
            , @('rmMenu', 'dirMenu', '')
        )
        'SummaryInformation' = @(, @('15', '10'))
        # The tables this script lists but doesn't read, as a jpackage MSI has them (their rows don't matter here).
        '_Validation' = @(); 'InstallExecuteSequence' = @(); 'InstallUISequence' = @(); 'AdminExecuteSequence' = @()
        'AdminUISequence' = @(); 'AdvtExecuteSequence' = @(); 'Feature' = @(); 'FeatureComponents' = @(); 'Component' = @()
        'File' = @(); 'MsiFileHash' = @(); 'Media' = @(); 'Binary' = @(); 'Icon' = @(); 'CreateFolder' = @()
        'LaunchCondition' = @()
        'Upgrade' = @(
            , @($upgradeCode, 'JP_UPGRADABLE_FOUND')
            , @($upgradeCode, 'JP_DOWNGRADABLE_FOUND')
        )
    }
}

function Copy-Tables([hashtable]$tables) {
    $copy = @{}
    foreach ($key in $tables.Keys) { $copy[$key] = @(foreach ($row in @($tables[$key])) { , [string[]]@($row) }) }
    return $copy
}

function Set-Row([hashtable]$tables, [string]$table, [string]$key, [string[]]$row) {
    $tables[$table] = @(@(foreach ($r in @($tables[$table])) { if ($null -ne $r -and $r[0] -ne $key) { , $r } }) + @(, $row))
}

function Remove-Row([hashtable]$tables, [string]$table, [string]$key) {
    $tables[$table] = @(foreach ($r in @($tables[$table])) { if ($r[0] -ne $key) { , $r } })
}

function Test-Verdict($result, $expect) {
    if ($null -eq $expect) { return $result.Failures.Count -eq 0 }
    return @($result.Failures | Where-Object { $_.StartsWith([string]$expect) }).Count -gt 0
}

function Invoke-SelfTest([string]$upgradeCode, [string]$casesDir) {
    $cases = New-Object System.Collections.Generic.List[object]
    function Case([string]$name, [scriptblock]$change, $expect) { $cases.Add(@{ Name = $name; Change = $change; Expect = $expect }) }

    Case 'a jpackage-like per-user MSI passes' { } $null
    Case 'ALLUSERS 2 with MSIINSTALLPERUSER 1 passes' { param($t) Set-Row $t 'Property' 'ALLUSERS' @('ALLUSERS', '2'); Set-Row $t 'Property' 'MSIINSTALLPERUSER' @('MSIINSTALLPERUSER', '1') } $null
    Case 'INSTALLDIR set to exactly the install folder passes' { param($t) Set-Row $t 'CustomAction' 'caSame' @('caSame', '51', 'INSTALLDIR', '[LocalAppDataFolder]Meal-Planner\') } $null
    Case 'the uninstall removes a folder inside the install folder' { param($t) Set-Row $t 'WixRemoveFolderEx' 'rmApp' @('rmApp', 'dirApp') } $null
    Case 'a folder chain that loops ends' { param($t) Set-Row $t 'Directory' 'dirLoopA' @('dirLoopA', 'dirLoopB', 'a'); Set-Row $t 'Directory' 'dirLoopB' @('dirLoopB', 'dirLoopA', 'b') } $null
    Case 'INSTALLDIR in the secrets folder' { param($t) Set-Row $t 'Directory' 'INSTALLDIR' @('INSTALLDIR', 'LocalAppDataFolder', 'MEALPL~1|Meal Planner') } 'INSTALLDIR is LocalAppDataFolder\Meal Planner, the secrets folder'
    Case 'INSTALLDIR in Documents' { param($t) Set-Row $t 'Directory' 'PersonalFolder' @('PersonalFolder', 'TARGETDIR', '.'); Set-Row $t 'Directory' 'INSTALLDIR' @('INSTALLDIR', 'PersonalFolder', 'Meal Planner') } 'INSTALLDIR is PersonalFolder\Meal Planner, Documents\Meal Planner'
    Case 'INSTALLDIR is LocalAppData itself' { param($t) Set-Row $t 'Directory' 'INSTALLDIR' @('INSTALLDIR', 'LocalAppDataFolder', '.') } 'INSTALLDIR is LocalAppDataFolder, not'
    Case 'INSTALLDIR through ..' { param($t) Set-Row $t 'Directory' 'dirSub' @('dirSub', 'LocalAppDataFolder', 'Sub'); Set-Row $t 'Directory' 'dirUp' @('dirUp', 'dirSub', '..'); Set-Row $t 'Directory' 'INSTALLDIR' @('INSTALLDIR', 'dirUp', 'Meal Planner') } 'INSTALLDIR is LocalAppDataFolder\Sub\..\Meal Planner, the secrets folder'
    Case 'INSTALLDIR somewhere else' { param($t) Set-Row $t 'Directory' 'INSTALLDIR' @('INSTALLDIR', 'LocalAppDataFolder', 'Other') } 'INSTALLDIR is LocalAppDataFolder\Other, not'
    # P7-R2b: the old two-level folder (WiX's ICE64 rejects it for a per-user jpackage MSI) is no longer the install folder.
    Case 'INSTALLDIR in the old Programs folder' { param($t) Set-Row $t 'Directory' 'dirPrograms' @('dirPrograms', 'LocalAppDataFolder', 'PROGRA~1|Programs'); Set-Row $t 'Directory' 'INSTALLDIR' @('INSTALLDIR', 'dirPrograms', 'MEALPL~1|Meal Planner') } 'INSTALLDIR is LocalAppDataFolder\Programs\Meal Planner, not'
    Case 'RemoveFile empties LocalAppData\Programs' { param($t) Set-Row $t 'Directory' 'dirPrograms' @('dirPrograms', 'LocalAppDataFolder', 'PROGRA~1|Programs'); Set-Row $t 'RemoveFile' 'rmPrograms' @('rmPrograms', 'dirPrograms', '') } 'the RemoveFile row rmPrograms removes '''' in LocalAppDataFolder\Programs'
    Case 'no INSTALLDIR' { param($t) Remove-Row $t 'Directory' 'INSTALLDIR' } 'the Directory table has no INSTALLDIR'
    Case 'another folder in the secrets folder' { param($t) Set-Row $t 'Directory' 'dirOdd' @('dirOdd', 'LocalAppDataFolder', 'Meal Planner') } 'the folder dirOdd is LocalAppDataFolder\Meal Planner, the secrets folder'
    Case 'a folder with a trailing space' { param($t) Set-Row $t 'Directory' 'dirOdd' @('dirOdd', 'LocalAppDataFolder', 'Meal Planner ') } 'the folder dirOdd is LocalAppDataFolder\Meal Planner , the secrets folder'
    Case 'a MEALPL~ short name under Documents' { param($t) Set-Row $t 'Directory' 'PersonalFolder' @('PersonalFolder', 'TARGETDIR', '.'); Set-Row $t 'Directory' 'dirOdd' @('dirOdd', 'PersonalFolder', 'MEALPL~2|Other') } 'the folder dirOdd has the short name MEALPL~2 directly under PersonalFolder'
    Case 'a type 51 action sets INSTALLDIR to the secrets folder' { param($t) Set-Row $t 'CustomAction' 'caBad' @('caBad', '51', 'INSTALLDIR', '[LocalAppDataFolder]Meal Planner\') } 'the custom action caBad sets the folder INSTALLDIR to'
    Case 'a type 35 action with option bits sets INSTALLDIR there' { param($t) Set-Row $t 'CustomAction' 'caBad' @('caBad', '291', 'INSTALLDIR', '[%LOCALAPPDATA%]\Meal Planner') } 'the custom action caBad sets the folder INSTALLDIR to'
    Case 'a type 35 action sets any other folder' { param($t) Set-Row $t 'CustomAction' 'caUp' @('caUp', '35', 'dirApp', '[INSTALLDIR]app2') } 'the custom action caUp sets the folder dirApp to'
    Case 'a type 51 action sets a standard folder' { param($t) Set-Row $t 'CustomAction' 'caRoot' @('caRoot', '51', 'PersonalFolder', '[TempFolder]') } 'the custom action caRoot sets the folder PersonalFolder to'
    Case 'a type 51 action moves INSTALLDIR elsewhere' { param($t) Set-Row $t 'CustomAction' 'caMove' @('caMove', '51', 'INSTALLDIR', '[LocalAppDataFolder]Elsewhere') } 'the custom action caMove sets the folder INSTALLDIR to'
    Case 'an action names the secrets folder by a literal path' { param($t) Set-Row $t 'CustomAction' 'caLit' @('caLit', '51', 'SOMEDIR', 'C:\Users\jo\AppData\Local\Meal Planner') } 'the custom action caLit''s target names the secrets folder'
    Case 'an action reaches the secrets folder from AppData' { param($t) Set-Row $t 'CustomAction' 'caUp' @('caUp', '51', 'SOMEDIR', '[%APPDATA%]..\Local\MEAL PLANNER') } 'the custom action caUp''s target names the secrets folder'
    Case 'an action through a property names the secrets folder' { param($t) Set-Row $t 'Property' 'BASE' @('BASE', '[LocalAppDataFolder]'); Set-Row $t 'CustomAction' 'caProp' @('caProp', '51', 'OTHER', '[BASE]Meal Planner') } 'the custom action caProp''s target names the secrets folder'
    Case 'an exe action names Documents' { param($t) Set-Row $t 'CustomAction' 'caExe' @('caExe', '50', 'cmd', '/c rd /s /q "%USERPROFILE%\Documents\Meal Planner"') } 'the custom action caExe''s target names Documents\Meal Planner'
    Case 'the Property table sets INSTALLDIR elsewhere' { param($t) Set-Row $t 'Property' 'INSTALLDIR' @('INSTALLDIR', '[LocalAppDataFolder]Meal Planner') } 'the Property table sets the folder INSTALLDIR to'
    Case 'the Property table sets a parent folder' { param($t) Set-Row $t 'Property' 'LocalAppDataFolder' @('LocalAppDataFolder', '[AppDataFolder]') } 'the Property table sets the folder LocalAppDataFolder to'
    Case 'AppSearch sets a folder' { param($t) Set-Row $t 'AppSearch' 'LocalAppDataFolder' @('LocalAppDataFolder', 'regSearchCleaner') } 'AppSearch sets the folder LocalAppDataFolder'
    Case 'a property names the secrets folder' { param($t) Set-Row $t 'Property' 'CLEANUP' @('CLEANUP', '[LocalAppDataFolder]Meal Planner') } 'the property CLEANUP names the secrets folder'
    Case 'a registry value names the secrets folder' { param($t) Set-Row $t 'Registry' 'regBad' @('regBad', '1', 'Software\X', 'x', '[LocalAppDataFolder]Meal Planner') } 'the registry value regBad names the secrets folder'
    Case 'the uninstall removes LocalAppData' { param($t) Set-Row $t 'WixRemoveFolderEx' 'rmBad' @('rmBad', 'LocalAppDataFolder') } 'the uninstall removes LocalAppDataFolder (LocalAppDataFolder), which isn''t inside'
    Case 'the cleaner reads back a value written elsewhere' { param($t) Set-Row $t 'Registry' 'regCleaner' @('regCleaner', '1', 'Software\Meal Planner\Meal Planner\1.0.0', 'RM_RF_INSTALLDIR', '[LocalAppDataFolder]') } 'the uninstall removes LocalAppDataFolder\ (RM_RF_INSTALLDIR), which isn''t inside'
    Case 'the cleaner reads a value this MSI never writes' { param($t) Set-Row $t 'RegLocator' 'regSearchCleaner' @('regSearchCleaner', '1', 'Software\Other', 'RM_RF_INSTALLDIR', '2') } 'the uninstall removes the folder in RM_RF_INSTALLDIR, which can''t be traced'
    Case 'the cleaner reads HKLM while the MSI writes HKCU' { param($t) Set-Row $t 'RegLocator' 'regSearchCleaner' @('regSearchCleaner', '2', 'Software\Meal Planner\Meal Planner\1.0.0', 'RM_RF_INSTALLDIR', '2') } 'the uninstall removes the folder in RM_RF_INSTALLDIR, which can''t be traced'
    Case 'the cleaner searches files, not the registry' { param($t) Remove-Row $t 'RegLocator' 'regSearchCleaner' } 'the uninstall removes the folder in RM_RF_INSTALLDIR, which can''t be traced'
    Case 'the cleaner property is also set elsewhere' { param($t) Set-Row $t 'CustomAction' 'caClean' @('caClean', '51', 'RM_RF_INSTALLDIR', '[LocalAppDataFolder]') } 'the uninstall removes LocalAppDataFolder\ (RM_RF_INSTALLDIR), which isn''t inside'
    Case 'RemoveFile empties a standard folder' { param($t) Set-Row $t 'RemoveFile' 'rmDesk' @('rmDesk', 'DesktopFolder', '') } 'the RemoveFile row rmDesk removes '''' in DesktopFolder'
    Case 'RemoveFile deletes files beside the install folder' { param($t) Set-Row $t 'RemoveFile' 'rmBeside' @('rmBeside', 'LocalAppDataFolder', '*.*') } 'the RemoveFile row rmBeside removes ''*.*'' in LocalAppDataFolder'
    Case 'RemoveFile through an untraceable property' { param($t) Set-Row $t 'RemoveFile' 'rmX' @('rmX', 'SOMEPROP', '*.*') } 'the RemoveFile row rmX removes from SOMEPROP, which can''t be traced'
    Case 'a per-machine install' { param($t) Set-Row $t 'Property' 'ALLUSERS' @('ALLUSERS', '1') } 'ALLUSERS is 1'
    Case 'ALLUSERS 2 alone' { param($t) Set-Row $t 'Property' 'ALLUSERS' @('ALLUSERS', '2') } 'ALLUSERS is 2 without MSIINSTALLPERUSER=1'
    Case 'a word count that asks for elevation' { param($t) $t['SummaryInformation'] = @(, @('15', '2')) } 'the summary''s word count is 2: it asks for elevated rights'
    Case 'no summary information' { param($t) $t.Remove('SummaryInformation') } 'the summary information has no word count'
    Case 'another upgrade code' { param($t) Set-Row $t 'Property' 'UpgradeCode' @('UpgradeCode', '{00000000-0000-0000-0000-000000000000}') } 'UpgradeCode is ''{00000000-0000-0000-0000-000000000000}'''
    Case 'another version' { param($t) Set-Row $t 'Property' 'ProductVersion' @('ProductVersion', '9.9.9') } 'ProductVersion is ''9.9.9'', not ''1.0.0'''
    Case 'another manufacturer' { param($t) Set-Row $t 'Property' 'Manufacturer' @('Manufacturer', 'Unknown') } 'Manufacturer is ''Unknown'''
    Case 'no desktop shortcut' { param($t) Remove-Row $t 'Shortcut' 'scDesktop' } 'no shortcut at DesktopFolder\Meal Planner'
    Case 'no Shortcut table' { param($t) $t.Remove('Shortcut') } 'the MSI has no Shortcut table'
    # P7-T2a: tables by allowlist.
    Case 'an unknown table, even empty' { param($t) $t['CustomTable'] = @() } 'the MSI has the table CustomTable, which isn''t on the allowlist'
    Case 'an unmodeled RemoveFolder table' { param($t) $t['Wix5RemoveFolderEx'] = @(, @('rmX', 'INSTALLDIR')) } 'the MSI has the table Wix5RemoveFolderEx, which removes, moves or copies'
    Case 'a MoveFile table' { param($t) $t['MoveFile'] = @() } 'the MSI has the table MoveFile, which removes, moves or copies'
    Case 'a DuplicateFile table' { param($t) $t['DuplicateFile'] = @() } 'the MSI has the table DuplicateFile, which removes, moves or copies'
    Case 'a RemoveRegistry table' { param($t) $t['RemoveRegistry'] = @() } 'the MSI has the table RemoveRegistry, which removes, moves or copies'
    Case 'Wix4RemoveFolderEx inside the install folder passes' { param($t) $t.Remove('WixRemoveFolderEx'); $t['Wix4RemoveFolderEx'] = @(, @('rmrfInstallDir', 'RM_RF_INSTALLDIR')) } $null
    Case 'Wix4RemoveFolderEx removes LocalAppData' { param($t) $t['Wix4RemoveFolderEx'] = @(, @('rmBad', 'LocalAppDataFolder')) } 'the uninstall removes LocalAppDataFolder (LocalAppDataFolder), which isn''t inside'
    # P7-T2a: custom actions by base type.
    # P7-FW1: DLL actions by exact (Action, Binary, entry point) triple.
    Case 'a DLL action with an unknown entry point' { param($t) Set-Row $t 'CustomAction' 'caDll' @('caDll', '1025', 'JpCaDll', 'Something') } 'the custom action caDll runs JpCaDll''s Something (type 1025 (1 with option bits))'
    Case 'a known entry point under another action name' { param($t) Set-Row $t 'CustomAction' 'caFind' @('caFind', '1', 'JpCaDll', 'FindRelatedProductsEx') } 'the custom action caFind runs JpCaDll''s FindRelatedProductsEx'
    Case 'a known action from another binary' { param($t) Set-Row $t 'CustomAction' 'JpFindRelatedProducts' @('JpFindRelatedProducts', '1', 'OtherDll', 'FindRelatedProductsEx') } 'the custom action JpFindRelatedProducts runs OtherDll''s FindRelatedProductsEx'
    # The re-review's minors: the Upgrade table and type 51 actions.
    Case 'an Upgrade row for another product' { param($t) Set-Row $t 'Upgrade' '{00000000-0000-0000-0000-000000000000}' @('{00000000-0000-0000-0000-000000000000}', 'OTHER_FOUND') } 'the Upgrade table finds the upgrade code {00000000-0000-0000-0000-000000000000}'
    Case 'a type 51 action sets another property' { param($t) Set-Row $t 'CustomAction' 'caProp' @('caProp', '51', 'SOMEPROP', 'x') } 'the custom action caProp sets the property SOMEPROP, which isn''t'
    Case 'an ARP property set by another action' { param($t) Set-Row $t 'CustomAction' 'caArp' @('caArp', '51', 'ARPCOMMENTS', 'x') } 'the custom action caArp sets the property ARPCOMMENTS, which isn''t'
    Case 'a JpSetARP action setting another property' { param($t) Set-Row $t 'CustomAction' 'JpSetARPCOMMENTS' @('JpSetARPCOMMENTS', '51', 'ARPNOREMOVE', '1') } 'the custom action JpSetARPCOMMENTS sets the property ARPNOREMOVE, which isn''t'
    Case 'a known triple in another case' { param($t) Set-Row $t 'CustomAction' 'WixRemoveFoldersEx' @('WixRemoveFoldersEx', '65', 'WixCA', 'wixremovefoldersex') } 'the custom action WixRemoveFoldersEx runs WixCA''s wixremovefoldersex'
    foreach ($programType in $programActionTypes) {
        $change = [scriptblock]::Create("param(`$t) Set-Row `$t 'CustomAction' 'caRun' @('caRun', '$programType', 'TARGETDIR', 'run.exe')")
        Case "a type $programType action" $change "the custom action caRun has the type $programType, which runs a program"
    }
    Case 'a type 34 action with option bits' { param($t) Set-Row $t 'CustomAction' 'caRun' @('caRun', '3106', 'TARGETDIR', 'run.exe') } 'the custom action caRun has the type 3106 (34 with option bits), which runs a program'
    Case 'a DLL action from an installed file (17)' { param($t) Set-Row $t 'CustomAction' 'caFile' @('caFile', '17', 'fileDll', 'Entry') } 'the custom action caFile has the type 17, which isn''t one this check allows'
    Case 'a property set from a folder (51) stays a folder setter' { param($t) Set-Row $t 'CustomAction' 'caAny' @('caAny', '51', 'INSTALLDIR', '[TempFolder]') } 'the custom action caAny sets the folder INSTALLDIR to'

    $wrong = 0
    $total = 0
    foreach ($case in $cases) {
        $total++
        $tables = Copy-Tables (New-GoodTables $upgradeCode)
        [void](& $case.Change $tables)
        $result = Test-MsiTables $tables '1.0.0' $upgradeCode
        if (Test-Verdict $result $case.Expect) {
            Write-Host "self-test ok: $($case.Name)"
        } else {
            $wrong++
            Write-Host "self-test WRONG: $($case.Name) (failures: $($result.Failures -join ' | '))" -ForegroundColor Red
        }
    }
    if ($casesDir) {
        foreach ($file in @(Get-ChildItem -LiteralPath $casesDir -Filter '*.json' | Sort-Object Name)) {
            $total++
            $case = Read-JsonTables $file.FullName
            $result = Test-MsiTables $case.Tables '1.0.0' $upgradeCode
            if (Test-Verdict $result $case.Expect) {
                Write-Host "case ok: $($file.BaseName)"
            } else {
                $wrong++
                Write-Host "case WRONG: $($file.BaseName), expected '$($case.Expect)' (failures: $($result.Failures -join ' | '))" -ForegroundColor Red
            }
        }
    }
    if ($wrong -gt 0) {
        Write-Host "The self-test FAILED ($wrong of $total)." -ForegroundColor Red
        return 1
    }
    Write-Host "The self-test passed ($total cases)."
    return 0
}

# ---------------------------------------------------------------------------------------------------------------

# The upgrade code has one source, build.gradle.kts's msiUpgradeUuid; the build passes it, and a manual run reads it.
if (-not $UpgradeCode) {
    $found = Select-String -Path (Join-Path $PSScriptRoot 'build.gradle.kts') -Pattern '^val msiUpgradeUuid = "([0-9a-fA-F-]{36})"$'
    if ($null -eq $found) { throw 'No msiUpgradeUuid in build.gradle.kts; pass -UpgradeCode.' }
    $UpgradeCode = $found.Matches[0].Groups[1].Value
}
$UpgradeCode = '{' + $UpgradeCode.Trim('{', '}').ToUpperInvariant() + '}'

if ($SelfTest) { exit (Invoke-SelfTest $UpgradeCode $CasesDir) }

if (-not $Version) {
    $Version = ((Select-String -Path (Join-Path $appsDir 'gradle.properties') -Pattern '^mealplanner\.desktopVersion=(.+)$').Matches[0].Groups[1].Value).Trim()
}
if ($TablesJson) {
    $source = (Resolve-Path -LiteralPath $TablesJson).Path
    Write-Host "Reading the tables in $source"
    $tables = (Read-JsonTables $source).Tables
} else {
    if (-not $Msi) {
        $found = Get-ChildItem (Join-Path $PSScriptRoot 'build\compose\binaries\main\msi') -Filter '*.msi' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($null -eq $found) { throw 'No MSI found. Build one with :desktopApp:packageMsi (docs\WINDOWS.md, "Build the installer").' }
        $Msi = $found.FullName
    }
    $Msi = (Resolve-Path -LiteralPath $Msi).Path
    Write-Host "Reading $Msi"
    $tables = Read-MsiTables $Msi
}

$result = Test-MsiTables $tables $Version $UpgradeCode
foreach ($line in $result.Passes) { Write-Host "ok: $line" -ForegroundColor Green }
foreach ($line in $result.Failures) { Write-Host "FAIL: $line" -ForegroundColor Red }
if ($result.Failures.Count -gt 0) {
    Write-Host "The MSI check FAILED ($($result.Failures.Count)). Delete this MSI; never hand it out." -ForegroundColor Red
    exit 1
}
Write-Host 'The MSI check passed.' -ForegroundColor Green
exit 0
