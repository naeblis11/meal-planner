# smoke-packaged.ps1's process decisions (plan 7, P7-R6), apart so SmokeProcessTreeTest can run them on made-up
# process lists, without ever running the smoke script or touching a real process. Dot-source it; it only defines
# functions, and neither lists, starts nor stops a process.
#
# JDK 25's jpackage launcher, "Meal Planner.exe", starts a child "Meal Planner.exe" that holds the JVM, the window and
# the sockets; the launcher itself has none. So the smoke run looks at the launcher's whole tree, and stops only the
# processes it recorded from that tree, each by id and only while it is still the same process.
#
# Each process is an object with ProcessId, ParentProcessId, CreationDate and ExecutablePath, as Win32_Process gives
# them (CreationDate a DateTime, or a string that casts to one).

# The process with ProcessId $RootId and its descendants, through ParentProcessId. A child is never older than its
# parent: Windows reuses ids, so a process created before the one whose id it names as parent belongs to an earlier
# process with that id, and is left out, as is one whose creation time is unknown. Outputs the process objects.
function Get-ProcessTree([object[]]$Processes, [int]$RootId) {
    $all = @($Processes | Where-Object { $null -ne $_ })
    $root = @($all | Where-Object { [int]$_.ProcessId -eq $RootId }) | Select-Object -First 1
    if ($null -eq $root -or $null -eq $root.CreationDate) { return }
    $seen = New-Object System.Collections.Generic.HashSet[int]
    $queue = New-Object System.Collections.Generic.Queue[object]
    [void]$seen.Add($RootId)
    $queue.Enqueue($root)
    while ($queue.Count -gt 0) {
        $parent = $queue.Dequeue()
        Write-Output $parent
        $parentCreated = [datetime]$parent.CreationDate
        foreach ($candidate in $all) {
            if ([int]$candidate.ParentProcessId -ne [int]$parent.ProcessId) { continue }
            if ($null -eq $candidate.CreationDate) { continue }
            if ([datetime]$candidate.CreationDate -lt $parentCreated) { continue }
            # A loop in the parent ids ends here.
            if (-not $seen.Add([int]$candidate.ProcessId)) { continue }
            $queue.Enqueue($candidate)
        }
    }
}

function Test-SamePath([string]$a, [string]$b) {
    if ([string]::IsNullOrWhiteSpace($a) -or [string]::IsNullOrWhiteSpace($b)) { return $false }
    try {
        return [string]::Equals([IO.Path]::GetFullPath($a), [IO.Path]::GetFullPath($b), [StringComparison]::OrdinalIgnoreCase)
    } catch {
        return $false
    }
}

# The ids the smoke run may stop: each process it recorded from the launcher's tree ($Recorded) that is still running
# ($Current, a fresh list) as the same process: the same id, the same creation time, and the image's exe ($Exe) both
# when recorded and now. Anything else (a process that ended and whose id was reused, another program, a process
# whose creation time is unknown, conhost.exe) is never stopped. Outputs the ids.
function Select-KillableProcessIds([object[]]$Recorded, [object[]]$Current, [string]$Exe) {
    foreach ($was in @($Recorded | Where-Object { $null -ne $_ })) {
        if ($null -eq $was.CreationDate -or -not (Test-SamePath ([string]$was.ExecutablePath) $Exe)) { continue }
        $now = @($Current | Where-Object { $null -ne $_ -and [int]$_.ProcessId -eq [int]$was.ProcessId }) | Select-Object -First 1
        if ($null -eq $now -or $null -eq $now.CreationDate) { continue }
        if ([datetime]$now.CreationDate -ne [datetime]$was.CreationDate) { continue }
        if (-not (Test-SamePath ([string]$now.ExecutablePath) $Exe)) { continue }
        Write-Output ([int]$was.ProcessId)
    }
}
