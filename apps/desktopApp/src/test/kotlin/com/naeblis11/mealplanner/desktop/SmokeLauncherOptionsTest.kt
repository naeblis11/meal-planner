package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.desktop.update.DesktopUpdates
import java.io.File
import java.io.RandomAccessFile
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
 * P7-R6: smoke-packaged.ps1 refuses to start an app image whose launcher options aren't exactly the installed app's,
 * since the .cfg's java-options beat the run's JAVA_TOOL_OPTIONS: a leaked port, Run-key gate, data folder or discovery
 * switch would act before anything noticed. The decision is launcher-options.ps1's Get-LauncherOptionProblems, run here
 * on .cfg files written to a temp folder. The smoke script itself is never run; its order is only read.
 */
class SmokeLauncherOptionsTest {
    private val dir: File = Files.createTempDirectory("mp-launcher-options").toFile()
    private val script = File(System.getProperty("launcherOptionsScript") ?: error("launcherOptionsScript is not set; run through Gradle"))
    private val smoke = File(script.parentFile, "smoke-packaged.ps1")

    // What jpackage writes for the packaged app: its own version option, Compose's options, then the installed app's.
    private val good = listOf(
        "[Application]",
        "app.classpath=\$APPDIR\\desktopApp.jar",
        "app.mainclass=com.naeblis11.mealplanner.desktop.MainKt",
        "",
        "[JavaOptions]",
        "java-options=-Djpackage.app-version=1.0.0",
        "java-options=-Dcompose.application.configure.swing.globals=true",
        "java-options=-Dmealplanner.installed=true",
        "java-options=-Dmealplanner.version=1.0.0",
        "java-options=-Dskiko.library.path=\$APPDIR",
        "java-options=-Dcompose.application.resources.dir=\$APPDIR\\\\resources",
    )

    @Before
    fun onWindowsOnly() {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun theInstalledAppsOptionsPass() {
        val verdicts = verdicts(mapOf("good" to cfg("good", good)))
        assertEquals(emptyList<String>(), verdicts.getValue("good"))
    }

    @Test
    fun everySmokeOrPreviewPropertyIsRefused() {
        val leaks = listOf(
            "-Dmealplanner.port=5000",
            "-Dmealplanner.startWithWindows=on",
            "-Dmealplanner.dataDir=C:\\Users\\jo\\build\\preview-data",
            "-Dmealplanner.peers=on",
            "-Dmealplanner.selfCheck=on",
            "-D${DesktopUpdates.GATE_PROPERTY}=on",
        )
        val cases = leaks.mapIndexed { i, leak -> "leak$i" to cfg("leak$i", good + "java-options=$leak") }.toMap()
        val verdicts = verdicts(cases)
        leaks.forEachIndexed { i, leak ->
            val problems = verdicts.getValue("leak$i")
            assertTrue("$leak: $problems", problems.any { it.contains(leak) })
        }
    }

    @Test
    fun anOptionNobodyListedIsRefused() {
        val verdicts = verdicts(
            mapOf(
                "xmx" to cfg("xmx", good + "java-options=-Xmx2g"),
                "oldVersion" to cfg("oldVersion", good.map { it.replace("mealplanner.version=1.0.0", "mealplanner.version=0.9.0") }),
                "agent" to cfg("agent", good + "java-options=-javaagent:C:\\x\\agent.jar"),
                "resourcesElsewhere" to cfg("resourcesElsewhere", good + "java-options=-Dcompose.application.resources.dir=C:\\x"),
            ),
        )
        assertTrue(verdicts.toString(), verdicts.getValue("xmx").any { it.contains("-Xmx2g") })
        assertTrue(verdicts.toString(), verdicts.getValue("oldVersion").any { it.contains("-Dmealplanner.version=0.9.0") })
        assertTrue(verdicts.toString(), verdicts.getValue("agent").any { it.contains("-javaagent") })
        assertTrue(verdicts.toString(), verdicts.getValue("resourcesElsewhere").any { it.contains("C:\\x") })
    }

    @Test
    fun aLeakOutsideJavaOptionsIsRefusedToo() {
        val verdicts = verdicts(mapOf("args" to cfg("args", good + listOf("[ArgOptions]", "arguments=-Dmealplanner.port=5000"))))
        assertTrue(verdicts.toString(), verdicts.getValue("args").any { it.contains("mealplanner.port") })
    }

    @Test
    fun theInstalledAppsOwnOptionsMustEachBeThereOnce() {
        val verdicts = verdicts(
            mapOf(
                "noInstalled" to cfg("noInstalled", good - "java-options=-Dmealplanner.installed=true"),
                "noVersion" to cfg("noVersion", good - "java-options=-Dmealplanner.version=1.0.0"),
                "twice" to cfg("twice", good + "java-options=-Dmealplanner.installed=true"),
            ),
        )
        assertTrue(verdicts.toString(), verdicts.getValue("noInstalled").any { it.contains("-Dmealplanner.installed=true") })
        assertTrue(verdicts.toString(), verdicts.getValue("noVersion").any { it.contains("-Dmealplanner.version=1.0.0") })
        assertTrue(verdicts.toString(), verdicts.getValue("twice").any { it.contains("-Dmealplanner.installed=true") })
    }

    @Test
    fun aMissingEmptyOrUnreadableCfgIsRefused() {
        val empty = cfg("empty", emptyList())
        val locked = cfg("locked", good)
        RandomAccessFile(locked, "rw").use { file ->
            file.channel.lock().use {
                val verdicts = verdicts(mapOf("missing" to File(dir, "nothing-here.cfg"), "empty" to empty, "locked" to locked))
                assertTrue(verdicts.toString(), verdicts.getValue("missing").any { it.contains("no launcher .cfg") })
                assertTrue(verdicts.toString(), verdicts.getValue("empty").isNotEmpty())
                assertTrue(verdicts.toString(), verdicts.getValue("locked").any { it.contains("can't be read") })
            }
        }
    }

    @Test
    fun theSmokeScriptChecksTheOptionsAndThrowsBeforeItStartsTheApp() {
        val text = smoke.readText()
        val load = text.indexOf(". (Join-Path \$PSScriptRoot 'launcher-options.ps1')")
        val check = text.indexOf("Get-LauncherOptionProblems -CfgFile \$cfgFile")
        val refuse = text.indexOf("throw", check)
        val start = text.indexOf("[System.Diagnostics.Process]::Start(")
        assertTrue("the smoke script doesn't load launcher-options.ps1", load >= 0)
        assertTrue("the smoke script doesn't check the launcher options", check > load)
        assertTrue("the smoke script doesn't throw on bad launcher options before starting the app", refuse in check until start)
    }

    @Test
    fun theSmokeRunNeverChecksForUpdates() {
        // Plan 8 (P8-PF10): the packaged app checks GitHub at launch; the smoke run's throwaway copy never does.
        val text = smoke.readText()
        val options = text.substring(
            text.indexOf("EnvironmentVariables['JAVA_TOOL_OPTIONS']"),
            text.indexOf("EnvironmentVariables['MEAL_PLANNER_DATA_DIR']"),
        )
        assertTrue(options, options.contains("'-D${DesktopUpdates.GATE_PROPERTY}=off'"))
    }

    private fun cfg(name: String, lines: List<String>): File =
        File(dir, "$name.cfg").also { it.writeText(lines.joinToString("\r\n", postfix = if (lines.isEmpty()) "" else "\r\n"), Charsets.UTF_8) }

    // One PowerShell for all the cases: each case's problems, one line each, then its end marker. No double quotes in
    // the command: Windows' command line would take them off.
    private fun verdicts(cases: Map<String, File>): Map<String, List<String>> {
        val list = File(dir, "cases.txt")
        list.writeText(cases.entries.joinToString("\n") { "${it.key}|${it.value.absolutePath}" }, Charsets.UTF_8)
        val command = ". '${script.absolutePath.replace("'", "''")}'; " +
            "foreach (\$case in [IO.File]::ReadAllLines('${list.absolutePath.replace("'", "''")}')) { " +
            "\$name, \$path = \$case.Split('|'); " +
            "foreach (\$p in @(Get-LauncherOptionProblems -CfgFile \$path -Version '1.0.0')) { Write-Output ('PROBLEM|' + \$name + '|' + \$p) }; " +
            "Write-Output ('END|' + \$name) }"
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", command)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            fail("launcher-options.ps1 didn't finish in 2 minutes")
        }
        val text = output.get(30, TimeUnit.SECONDS)
        assertEquals(text, 0, process.exitValue())
        val lines = text.lines()
        return cases.keys.associateWith { name ->
            assertTrue("no verdict for $name:\n$text", lines.contains("END|$name"))
            lines.filter { it.startsWith("PROBLEM|$name|") }.map { it.removePrefix("PROBLEM|$name|") }
        }
    }
}
