# smoke-packaged.ps1's launcher check (plan 7, P7-R6), apart so SmokeLauncherOptionsTest can run it on .cfg files it
# writes, without ever running the smoke script. Dot-source it; it only defines a function.
#
# The app image's "app\Meal Planner.cfg" holds the launcher's java-options, and those beat the smoke run's
# JAVA_TOOL_OPTIONS (the JVM reads JAVA_TOOL_OPTIONS first). A leaked -Dmealplanner.port=5000, startWithWindows=on,
# dataDir, peers or updates would bind port 5000, write the Run key, use another library, announce on the LAN or
# contact GitHub (plan 8) before anything noticed, so the smoke run refuses to start an image unless its options are
# exactly these:
# - the installed app's own two: -Dmealplanner.installed=true and -Dmealplanner.version=<version> (each once);
# - jpackage's own -Djpackage.app-version=<version>;
# - Compose's own: -Dcompose.application.configure.swing.globals=true, -Dskiko.library.path=$APPDIR and
#   -Dcompose.application.resources.dir=$APPDIR\<folder> (Compose 1.12.1's AbstractJPackageTask adds the last two).
# Anything else, a .cfg that is missing, empty or can't be read, or a smoke or preview property anywhere in it, is a
# problem. It fails closed: an option a later jpackage or Compose adds stops the run until it is listed here.

function Get-LauncherOptionProblems([string]$CfgFile, [string]$Version) {
    $problems = New-Object System.Collections.Generic.List[string]
    if ([string]::IsNullOrWhiteSpace($Version)) {
        $problems.Add('there is no app version to compare the launcher options with')
        return $problems.ToArray()
    }
    if ([string]::IsNullOrWhiteSpace($CfgFile) -or -not (Test-Path -LiteralPath $CfgFile -PathType Leaf)) {
        $problems.Add("there is no launcher .cfg at $CfgFile")
        return $problems.ToArray()
    }
    try {
        $lines = @([IO.File]::ReadAllLines($CfgFile))
    } catch {
        $problems.Add("the launcher .cfg at $CfgFile can't be read: $($_.Exception.Message)")
        return $problems.ToArray()
    }

    $installed = '-Dmealplanner.installed=true'
    $ownVersion = "-Dmealplanner.version=$Version"
    $allowed = @(
        $installed,
        $ownVersion,
        "-Djpackage.app-version=$Version",
        '-Dcompose.application.configure.swing.globals=true',
        '-Dskiko.library.path=$APPDIR'
    )
    $resourcesDir = '^-Dcompose\.application\.resources\.dir=\$APPDIR(\\{1,2}|/)[A-Za-z0-9._-]+$'
    $options = New-Object System.Collections.Generic.List[string]
    foreach ($line in $lines) {
        if ($line -match 'mealplanner\.(dataDir|port|peers|startWithWindows|selfCheck|updates)') {
            $problems.Add("the launcher .cfg sets a smoke or preview property: $($line.Trim())")
            continue
        }
        if ($line -match '^\s*java-options\s*=(.*)$') {
            $value = $Matches[1].Trim()
            $options.Add($value)
            if (-not ($allowed -ccontains $value) -and $value -cnotmatch $resourcesDir) {
                $problems.Add("the launcher passes $value, which is none of the installed app's, jpackage's or Compose's own options")
            }
        }
    }
    foreach ($wanted in @($installed, $ownVersion)) {
        $count = @($options | Where-Object { $_ -ceq $wanted }).Count
        if ($count -ne 1) { $problems.Add("the launcher passes $wanted $count times, not once") }
    }
    return $problems.ToArray()
}
