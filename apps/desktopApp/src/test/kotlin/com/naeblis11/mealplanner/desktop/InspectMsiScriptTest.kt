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
 * P7-PF4, P7-T2a: inspect-msi.ps1 is what the build runs after every packageMsi (the inspectMsi task), and its verdict
 * is the only thing between a bad MSI and an uninstall that deletes %LOCALAPPDATA%\Meal Planner, the secrets folder.
 * It fails closed. Its decisions are pure functions over the MSI's tables; these run them on tables written here and
 * on the case files in src/test/inspect-msi-cases, never on an MSI, and nothing is installed or read outside them.
 */
class InspectMsiScriptTest {
    private val dir: File = Files.createTempDirectory("mp-inspect-msi").toFile()

    @Before
    fun onWindowsOnly() {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun theSelfTestAndEveryCaseFileGetTheirVerdicts() {
        val cases = File(System.getProperty("inspectMsiCases") ?: error("inspectMsiCases is not set; run through Gradle"))
        val names = cases.listFiles { f -> f.name.endsWith(".json") }.orEmpty().map { it.nameWithoutExtension }.sorted()
        assertTrue("too few case files in $cases: $names", names.size >= 22)
        // The final reviews' cases (P7-T2a, P7-FW1): an exe action, an unread table, WiX 4's RemoveFolderEx, a DLL
        // action jpackage doesn't have, another product's upgrade code and a type 51 action on another property each
        // fail, the old two-level install folder (P7-R2b) fails, and the jpackage-shaped MSI with every allowlisted table still
        val named = listOf(
            "ca34-rd-shortname", "unknown-table", "wix4-removefolderex-secrets", "dll-unknown-entry", "upgrade-other-product",
            "ca51-other-property", "old-programs-installdir", "signature-row-fails", "signature-empty-passes", "jpackage-cleanup-passes",
            "jpackage25-wix311-real",
        )
        for (name in named) {
            assertTrue("$name is missing from $cases", name in names)
        }
        val (exit, output) = inspect("-SelfTest", "-CasesDir", cases.absolutePath)
        assertEquals(output, 0, exit)
        assertTrue(output, output.contains("The self-test passed"))
        assertTrue(output, output.contains("self-test ok: INSTALLDIR in the secrets folder"))
        assertTrue(output, output.contains("self-test ok: an unknown table, even empty"))
        assertTrue(output, output.contains("self-test ok: a type 34 action"))
        for (name in names) assertTrue("$name\n$output", output.contains("case ok: $name"))
    }

    @Test
    fun aJpackageLikePerUserMsiPasses() {
        val (exit, output) = inspect("-TablesJson", tables(installDir = """["INSTALLDIR", "LocalAppDataFolder", "MEAL-P~1|Meal-Planner"]""").path, "-Version", "1.0.0")
        assertEquals(output, 0, exit)
        assertTrue(output, output.contains("ok: it installs into LocalAppDataFolder\\Meal-Planner"))
        assertTrue(output, output.contains("The MSI check passed."))
    }

    @Test
    fun anMsiThatInstallsIntoTheSecretsFolderFails() {
        val (exit, output) = inspect("-TablesJson", tables(installDir = """["INSTALLDIR", "LocalAppDataFolder", "MEALPL~1|Meal Planner"]""").path, "-Version", "1.0.0")
        assertEquals(output, 1, exit)
        assertTrue(output, output.contains("FAIL: INSTALLDIR is LocalAppDataFolder\\Meal Planner, the secrets folder"))
        assertTrue(output, output.contains("never hand it out"))
    }

    private fun tables(installDir: String): File {
        val json = """
            {
              "Property": [["ProductName", "Meal Planner"], ["Manufacturer", "Meal Planner"], ["ProductVersion", "1.0.0"],
                           ["UpgradeCode", "{D96C85B6-DAAF-4386-A1F2-CD677885477B}"]],
              "Directory": [["TARGETDIR", "", "SourceDir"], ["LocalAppDataFolder", "TARGETDIR", "."],
                            $installDir,
                            ["ProgramMenuFolder", "TARGETDIR", "."], ["dirMenu", "ProgramMenuFolder", "Meal Planner"],
                            ["DesktopFolder", "TARGETDIR", "."]],
              "Shortcut": [["scMenu", "dirMenu", "Meal Planner"], ["scDesktop", "DesktopFolder", "Meal Planner"]],
              "CustomAction": [["JpSetARPINSTALLLOCATION", 51, "ARPINSTALLLOCATION", "[INSTALLDIR]"]],
              "SummaryInformation": [[15, 10]]
            }
        """.trimIndent()
        return File(dir, "tables.json").also { it.writeText(json, Charsets.UTF_8) }
    }

    // The output is read on another thread, so the timeout bounds the process even if it never closes its output.
    private fun inspect(vararg args: String): Pair<Int, String> {
        val script = File(System.getProperty("inspectMsiScript") ?: error("inspectMsiScript is not set; run through Gradle"))
        val process = ProcessBuilder(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath) + args)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            fail("inspect-msi.ps1 didn't finish in 2 minutes")
        }
        return process.exitValue() to output.get(30, TimeUnit.SECONDS)
    }
}
