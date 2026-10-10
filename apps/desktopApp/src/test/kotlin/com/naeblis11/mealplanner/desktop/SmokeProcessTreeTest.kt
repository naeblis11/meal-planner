package com.naeblis11.mealplanner.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * P7-R6: JDK 25's jpackage launcher starts a child "Meal Planner.exe" that holds the JVM, the window and the sockets,
 * so smoke-packaged.ps1 follows the launcher's process tree and stops only that tree. The decisions are
 * process-tree.ps1's pure functions, run here on made-up process lists (as Win32_Process gives them): no real process
 * is listed, started or stopped.
 */
class SmokeProcessTreeTest {
    private val dir: File = Files.createTempDirectory("mp-process-tree").toFile()
    private val scripts = File(System.getProperty("launcherOptionsScript") ?: error("launcherOptionsScript is not set; run through Gradle")).parentFile
    private val treeScript = File(scripts, "process-tree.ps1")
    private val exe = "C:\\Users\\jo\\apps\\desktopApp\\build\\compose\\binaries\\main\\app\\Meal Planner\\Meal Planner.exe"

    @Before
    fun onWindowsOnly() {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun proc(id: Int, parent: Int, created: String?, path: String? = exe): String =
        """{"ProcessId": $id, "ParentProcessId": $parent, "CreationDate": ${created?.let { "\"$it\"" } ?: "null"}, "ExecutablePath": ${path?.let { "\"${it.replace("\\", "\\\\")}\"" } ?: "null"}}"""

    @Test
    fun theTreeIsTheLauncherAndItsDescendants() {
        val processes = listOf(
            proc(100, 4, "2026-10-06T10:00:00"),
            proc(200, 100, "2026-10-06T10:00:01"),
            proc(300, 200, "2026-10-06T10:00:02", "C:\\Windows\\System32\\conhost.exe"),
            proc(400, 1, "2026-10-06T10:00:03"),
            // Its parent id is 100, but it is older than 100: the id was reused, so it isn't 100's child.
            proc(500, 100, "2026-10-06T09:00:00"),
            proc(600, 700, "2026-10-06T10:00:00"),
            proc(700, 600, "2026-10-06T10:00:00"),
            proc(800, 200, null),
        )
        assertEquals(listOf(100, 200, 300), tree(processes, 100))
        assertEquals(emptyList<Int>(), tree(processes, 999))
        // A loop in the parent ids ends.
        assertEquals(listOf(600, 700), tree(processes, 600))
    }

    @Test
    fun onlyRecordedProcessesThatAreStillTheSameAppProcessMayBeStopped() {
        val recorded = listOf(
            proc(100, 4, "2026-10-06T10:00:00"),
            proc(200, 100, "2026-10-06T10:00:01"),
            proc(300, 200, "2026-10-06T10:00:02"),
            proc(350, 200, "2026-10-06T10:00:02", "C:\\Windows\\System32\\conhost.exe"),
            proc(360, 200, null),
            proc(370, 200, "2026-10-06T10:00:02"),
        )
        val current = listOf(
            // Still the same process.
            proc(100, 4, "2026-10-06T10:00:00"),
            // The same id, but a new process (created later): never stopped.
            proc(200, 1, "2026-10-06T11:00:00"),
            // The same id and time, but another program now.
            proc(300, 200, "2026-10-06T10:00:02", "C:\\Windows\\notepad.exe"),
            // Not the app's exe even when recorded.
            proc(350, 200, "2026-10-06T10:00:02", "C:\\Windows\\System32\\conhost.exe"),
            // No creation time recorded: can't be shown to be the same process.
            proc(360, 200, null),
            // Not recorded at all.
            proc(400, 1, "2026-10-06T10:00:03"),
            // 370 has ended.
        )
        assertEquals(listOf(100), killable(recorded, current))
        // The exe compares as a path: case and a doubled separator don't matter.
        val sameExe = listOf(proc(100, 4, "2026-10-06T10:00:00", exe.uppercase().replace("\\APP\\", "\\APP\\\\")))
        assertEquals(listOf(100), killable(listOf(proc(100, 4, "2026-10-06T10:00:00")), sameExe))
        assertEquals(emptyList<Int>(), killable(emptyList(), current))
    }

    private fun tree(processes: List<String>, root: Int): List<Int> =
        run("Get-ProcessTree -Processes \$a -RootId $root | ForEach-Object { [int]\$_.ProcessId }", processes, emptyList())

    private fun killable(recorded: List<String>, current: List<String>): List<Int> =
        run("Select-KillableProcessIds -Recorded \$a -Current \$b -Exe \$exe", recorded, current)

    // Writes the lists as JSON, dot-sources process-tree.ps1 and prints one id per line. No double quotes in the
    // command: Windows' command line would take them off.
    private fun run(call: String, a: List<String>, b: List<String>): List<Int> {
        val aFile = File(dir, "a.json").also { it.writeText("[" + a.joinToString(",") + "]", Charsets.UTF_8) }
        val bFile = File(dir, "b.json").also { it.writeText("[" + b.joinToString(",") + "]", Charsets.UTF_8) }
        fun q(s: String) = "'" + s.replace("'", "''") + "'"
        // Windows PowerShell's ConvertFrom-Json writes a JSON array as one object; foreach takes it apart.
        val command = "\$ErrorActionPreference = 'Stop'; . ${q(treeScript.absolutePath)}; " +
            "\$a = @(foreach (\$p in ([IO.File]::ReadAllText(${q(aFile.absolutePath)}) | ConvertFrom-Json)) { \$p }); " +
            "\$b = @(foreach (\$p in ([IO.File]::ReadAllText(${q(bFile.absolutePath)}) | ConvertFrom-Json)) { \$p }); " +
            "\$exe = ${q(exe)}; " +
            "foreach (\$id in @($call)) { Write-Output ('ID|' + \$id) }; Write-Output 'END'"
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", command)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            fail("process-tree.ps1 didn't finish in 2 minutes")
        }
        val text = output.get(30, TimeUnit.SECONDS)
        assertEquals(text, 0, process.exitValue())
        assertTrue(text, text.lines().contains("END"))
        return text.lines().filter { it.startsWith("ID|") }.map { it.removePrefix("ID|").trim().toInt() }.sorted()
    }
}
