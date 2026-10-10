package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.desktop.server.MealPlannerServer
import com.naeblis11.mealplanner.desktop.update.DesktopUpdates
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * P7-R6: smoke-packaged.ps1 is never run by a test, so what it hands the app is pinned here by reading it. Its
 * property names must be the ones the app reads (a renamed constant would turn a hard rule, like never the Run key or
 * never port 5000, into a no-op), and its list of self-checks must be the app's. Its choice of JDK is run from the
 * script's own function, lifted out with PowerShell's parser, on folders made here.
 */
class SmokeScriptTest {
    private val dir: File = Files.createTempDirectory("mp-smoke-script").toFile()
    private val smoke = File(
        File(System.getProperty("launcherOptionsScript") ?: error("launcherOptionsScript is not set; run through Gradle")).parentFile,
        "smoke-packaged.ps1",
    )
    private val text = smoke.readText()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun theScriptPassesTheAppsOwnPropertyNames() {
        val wanted = listOf(
            "'-D${StartWithWindows.GATE_PROPERTY}=off'",
            "'-D${PEERS_PROPERTY}=off'",
            "'-D${DesktopUpdates.GATE_PROPERTY}=off'",
            "'-D${SelfCheck.PROPERTY}=on'",
            "\"-D${DesktopPaths.DATA_DIR_PROPERTY}=\$dataDir\"",
            "\"-D${MealPlannerServer.PORT_PROPERTY}=\$port\"",
        )
        for (option in wanted) assertTrue("smoke-packaged.ps1 doesn't pass $option", option in text)
    }

    @Test
    fun theScriptWaitsForEverySelfCheckTheAppRuns() {
        val line = text.lines().singleOrNull { it.trimStart().startsWith("\$requiredChecks = @(") }
            ?: error("smoke-packaged.ps1 has no single \$requiredChecks line")
        val listed = Regex("'([^']*)'").findAll(line).map { it.groupValues[1] }.toList()
        val app = listOf("server") + SelfCheck.standardChecks().map { it.first }
        assertEquals(app.sorted(), listed.sorted())
        assertEquals("a check is listed twice: $listed", listed.size, listed.toSet().size)
    }

    @Test
    fun theScriptFollowsTheLaunchersTreeAndStopsOnlyItsOwnProcessesById() {
        // JDK 25's launcher runs the app in a child Meal Planner.exe (SmokeProcessTreeTest has the decisions).
        assertTrue("the smoke script doesn't load process-tree.ps1", ". (Join-Path \$PSScriptRoot 'process-tree.ps1')" in text)
        assertTrue("the smoke script doesn't walk the launcher's tree", "Get-ProcessTree" in text)
        assertTrue("the smoke script doesn't choose what to stop with Select-KillableProcessIds", "Select-KillableProcessIds" in text)
        for (line in text.lines()) {
            val code = line.substringBefore('#')
            assertTrue("stops a process by name: $line", !Regex("(?i)(Stop-Process|Get-Process|taskkill)[^;|]*(-Name|/IM)").containsMatchIn(code))
        }
    }

    @Test
    fun theScriptCountsTheLauncherAndItsChildAsTwoProcesses() {
        // The controller's real run found Update-Tree handing its callers the whole list as one item (a leading comma
        // in front of the returned array), so the child was never seen and the run waited out its 30 s.
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val tree = File(smoke.parentFile, "process-tree.ps1").absolutePath.replace("'", "''")
        val command = "\$ErrorActionPreference = 'Stop'; . '$tree'; " +
            "function Get-ProcessSnapshot { return , @(" +
            "[pscustomobject]@{ ProcessId = 10; ParentProcessId = 1; CreationDate = [datetime]'2026-10-06T10:00:00'; ExecutablePath = 'C:\\x\\Meal Planner.exe' }, " +
            "[pscustomobject]@{ ProcessId = 11; ParentProcessId = 10; CreationDate = [datetime]'2026-10-06T10:00:01'; ExecutablePath = 'C:\\x\\Meal Planner.exe' }) }; " +
            "\$ast = [System.Management.Automation.Language.Parser]::ParseFile('${smoke.absolutePath.replace("'", "''")}', [ref]\$null, [ref]\$null); " +
            "\$f = \$ast.Find({ param(\$n) \$n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and \$n.Name -eq 'Update-Tree' }, \$true); " +
            "if (\$null -eq \$f) { throw 'no function Update-Tree' }; . ([scriptblock]::Create(\$f.Extent.Text)); " +
            "\$process = [pscustomobject]@{ Id = 10 }; \$recorded = @{}; " +
            "Write-Output ('COUNT|' + @(Update-Tree).Count + '|' + \$recorded.Count)"
        assertEquals("COUNT|2|2", powershell(command).lines().single { it.startsWith("COUNT|") })
    }

    @Test
    fun theScriptsParseCleanly() {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        for (name in listOf("smoke-packaged.ps1", "process-tree.ps1", "launcher-options.ps1")) {
            val path = File(smoke.parentFile, name).absolutePath.replace("'", "''")
            val command = "\$errors = \$null; [void][System.Management.Automation.Language.Parser]::ParseFile('$path', [ref]\$null, [ref]\$errors); " +
                "Write-Output ('ERRORS|' + @(\$errors).Count); foreach (\$e in @(\$errors)) { Write-Output (\$e.Extent.StartLineNumber.ToString() + ': ' + \$e.Message) }"
            val output = powershell(command)
            assertTrue("$name:\n$output", output.lines().contains("ERRORS|0"))
        }
    }

    @Test
    fun aSetPackagingJdkWinsAndAWrongOneStopsTheScript() {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val good = File(dir, "jdk-good").also { File(it, "bin").mkdirs(); File(it, "bin/jpackage.exe").writeText("") }
        val wrong = File(dir, "jdk-wrong").also { File(it, "bin").mkdirs() }
        assertEquals("FOUND|${good.absolutePath}", findJdk(good.absolutePath))
        val refused = findJdk(wrong.absolutePath)
        assertTrue(refused, refused.startsWith("THREW|") && "MEAL_PLANNER_PACKAGING_JDK" in refused && wrong.absolutePath in refused)
        val missing = findJdk(File(dir, "nothing-here").absolutePath)
        assertTrue(missing, missing.startsWith("THREW|"))
    }

    // Runs the script's Get-JdkVersion and Find-PackagingJdk (and nothing else of it) with MEAL_PLANNER_PACKAGING_JDK
    // set to [named]. No double quotes in the command: Windows' command line would take them off.
    private fun findJdk(named: String): String {
        val command = "\$ErrorActionPreference = 'Stop'; " +
            "\$ast = [System.Management.Automation.Language.Parser]::ParseFile('${smoke.absolutePath.replace("'", "''")}', [ref]\$null, [ref]\$null); " +
            "foreach (\$name in @('Get-JdkVersion', 'Find-PackagingJdk')) { " +
            "\$f = \$ast.Find({ param(\$n) \$n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and \$n.Name -eq \$name }, \$true); " +
            "if (\$null -eq \$f) { throw ('no function ' + \$name) }; . ([scriptblock]::Create(\$f.Extent.Text)) }; " +
            "\$env:MEAL_PLANNER_PACKAGING_JDK = '${named.replace("'", "''")}'; " +
            "try { Write-Output ('FOUND|' + (Find-PackagingJdk)) } catch { Write-Output ('THREW|' + \$_.Exception.Message) }"
        return powershell(command).lines().single { it.startsWith("FOUND|") || it.startsWith("THREW|") }
    }

    private fun powershell(command: String): String {
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", command)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            fail("PowerShell didn't finish in 2 minutes")
        }
        val text = output.get(30, TimeUnit.SECONDS)
        assertEquals(text, 0, process.exitValue())
        return text
    }
}
