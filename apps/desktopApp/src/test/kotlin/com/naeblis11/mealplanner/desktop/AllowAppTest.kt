package com.naeblis11.mealplanner.desktop

import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7-R11: "Allow Meal Planner (asks for admin)" adds the installed exe to Controlled folder access's allowed apps,
 * elevated through UAC. Tests only ever use a fake ElevatedRunner and fake Windows folders: nothing here elevates,
 * touches Defender or calls the known-folder API.
 */
class AllowAppTest {
    private val root: File = Files.createTempDirectory("mp-allow").toFile()
    private val local = File(root, "Local").apply { mkdirs() }
    private val system = File(root, "System32").apply { mkdirs() }
    private val powerShell = File(system, "WindowsPowerShell\\v1.0\\powershell.exe").apply {
        parentFile.mkdirs()
        writeText("not really PowerShell")
    }
    private val installDir = File(local, AllowApp.INSTALL_DIR_NAME).apply { mkdirs() }
    private val exe = File(installDir, AllowApp.EXE_NAME).apply { writeText("not really an exe") }
    private val said = mutableListOf<String>()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** Fake known folders; null stands for a lookup that failed. */
    private class FakeFolders(val system: File?, val local: File?) : WindowsFolders {
        override fun system(): File? = system

        override fun localAppData(): File? = local
    }

    /** Records each call and answers [outcome]; [throws] stands for JNA failing. */
    private class FakeRunner(var outcome: ElevatedOutcome = ElevatedOutcome.Exited(0), var throws: Boolean = false) : ElevatedRunner {
        val calls = mutableListOf<Triple<String, String, Long>>()

        override fun run(file: String, parameters: String, timeoutMillis: Long): ElevatedOutcome {
            calls += Triple(file, parameters, timeoutMillis)
            if (throws) throw IllegalStateException("no shell")
            return outcome
        }
    }

    private fun allow(
        runner: ElevatedRunner,
        launcher: String? = exe.path,
        folders: WindowsFolders = FakeFolders(system, local),
        recheck: () -> String? = { null },
    ) = AllowApp(runner, launcher = { launcher }, folders = folders, recheck = recheck, log = { said += it })

    private fun decoded(parameters: String): String {
        val prefix = "-NoProfile -NonInteractive -EncodedCommand "
        assertTrue(parameters.startsWith(prefix))
        return String(Base64.getDecoder().decode(parameters.removePrefix(prefix)), Charsets.UTF_16LE)
    }

    @Test
    fun theInstalledExeIsAcceptedAndOthersAreNot() {
        assertEquals(exe.canonicalFile, AllowApp.validatedExe(exe.path, local))
        // Windows paths ignore case.
        assertEquals(exe.canonicalFile, AllowApp.validatedExe(exe.path.uppercase(), local))
        assertNull(AllowApp.validatedExe(null, local))
        // Missing, another name, outside the install folder.
        exe.delete()
        assertNull(AllowApp.validatedExe(exe.path, local))
        exe.writeText("x")
        val java = File(installDir, "java.exe").apply { writeText("x") }
        assertNull(AllowApp.validatedExe(java.path, local))
        val outside = File(File(local, "Elsewhere").apply { mkdirs() }, AllowApp.EXE_NAME).apply { writeText("x") }
        assertNull(AllowApp.validatedExe(outside.path, local))
        // A folder named like the exe is not a file.
        val other = File(root, "Other").apply { mkdirs() }
        File(File(other, AllowApp.INSTALL_DIR_NAME), AllowApp.EXE_NAME).mkdirs()
        assertNull(AllowApp.validatedExe(AllowApp.installedExe(other).path, other))
        // A quote of either kind is never put into a command.
        assertNull(AllowApp.validatedExe(exe.path + "\"", local))
    }

    @Test
    fun onlyTheExactInstalledPathIsAccepted() {
        // P7-R11a: nested below the install folder, or in a sibling folder that starts the same, is refused.
        val nested = File(File(installDir, "x").apply { mkdirs() }, AllowApp.EXE_NAME).apply { writeText("x") }
        assertNull(AllowApp.validatedExe(nested.path, local))
        val sibling = File(File(local, "Meal-Planner2").apply { mkdirs() }, AllowApp.EXE_NAME).apply { writeText("x") }
        assertNull(AllowApp.validatedExe(sibling.path, local))
        // A path that only reaches the exe through "..", still canonically the exe, is the exe.
        assertEquals(exe.canonicalFile, AllowApp.validatedExe(File(installDir, "x\\..\\" + AllowApp.EXE_NAME).path, local))
    }

    @Test
    fun aTypographicQuoteAnywhereInThePathIsRefused() {
        // Windows PowerShell ends a single-quoted string at U+2018 to U+201B as well as at '.
        for (quote in listOf('\'', '\u2018', '\u2019', '\u201A', '\u201B', '`', '$', ';', '&')) {
            val odd = File(root, "Local${quote}x").apply { mkdirs() }
            val oddExe = AllowApp.installedExe(odd).apply { parentFile.mkdirs(); writeText("x") }
            assertNull("refused: U+%04X".format(quote.code), AllowApp.validatedExe(oddExe.path, odd))
            val runner = FakeRunner()
            val app = allow(runner, launcher = oddExe.path, folders = FakeFolders(system, odd))
            assertFalse(app.available)
            assertEquals(AllowApp.failedMessage("not the installed app"), app.run())
            assertTrue(runner.calls.isEmpty())
        }
        assertFalse(AllowApp.isSafePath("C:\\x\u2019; echo pwned; \u2019\\Meal Planner.exe"))
        assertTrue(AllowApp.isSafePath("C:\\Users\\Jo Smith (2)\\AppData\\Local\\Meal-Planner\\Meal Planner.exe"))
    }

    @Test
    fun theCommandIsEncodedAndDecodesToExactlyTheScript() {
        val path = File("C:\\Users\\someone\\AppData\\Local\\Meal-Planner\\Meal Planner.exe")
        val script = "Add-MpPreference -ControlledFolderAccessAllowedApplications " +
            "'C:\\Users\\someone\\AppData\\Local\\Meal-Planner\\Meal Planner.exe'"
        assertEquals(script, AllowApp.script(path))
        val parameters = AllowApp.parameters(path)
        assertFalse(parameters.contains("-Command"))
        assertFalse(parameters.contains("Meal"))
        assertEquals(script, decoded(parameters))
    }

    @Test
    fun everySingleQuoteKindIsDoubled() {
        // A safeguard behind the character check: a quote that got through would still be read as a literal.
        assertEquals("a''b\u2018\u2018c\u2019\u2019d\u201A\u201Ae\u201B\u201Bf", AllowApp.doubleQuotes("a'b\u2018c\u2019d\u201Ae\u201Bf"))
        assertEquals(
            "Add-MpPreference -ControlledFolderAccessAllowedApplications 'C:\\x\u2019\u2019\\y.exe'",
            AllowApp.script(File("C:\\x\u2019\\y.exe")),
        )
    }

    @Test
    fun powerShellComesFromTheSystemFolder() {
        val runner = FakeRunner()
        allow(runner).run()
        assertEquals(powerShell.path, runner.calls.single().first)
        assertEquals(AllowApp.script(exe.canonicalFile), decoded(runner.calls.single().second))
    }

    @Test
    fun onlyTheInstalledAppOffersIt() {
        assertTrue(allow(FakeRunner()).available)
        assertFalse(allow(FakeRunner(), launcher = null).available)
        assertFalse(allow(FakeRunner(), launcher = "C:\\Program Files\\Java\\bin\\java.exe").available)
    }

    @Test
    fun aFailedFolderLookupShowsNoButton() {
        for (folders in listOf(FakeFolders(null, local), FakeFolders(system, null), FakeFolders(File(root, "NoSystem"), local))) {
            val runner = FakeRunner()
            val app = allow(runner, folders = folders)
            assertFalse(app.available)
            assertEquals(AllowApp.failedMessage("not the installed app"), app.run())
            assertTrue(runner.calls.isEmpty())
        }
    }

    @Test
    fun allowedThenCheckedAgainClearsTheNotice() {
        val runner = FakeRunner(ElevatedOutcome.Exited(0))
        var checks = 0
        val result = allow(runner, recheck = { checks++; null }).run()
        assertNull(result)
        assertEquals(1, checks)
        assertEquals(AllowApp.TIMEOUT_MILLIS, runner.calls.single().third)
        assertTrue(decoded(runner.calls.single().second).contains(exe.canonicalPath))
        // The log says how it went, never the command line or the path.
        assertEquals(listOf("Meal Planner: allowing the app through Controlled folder access: allowed (exit 0)"), said)
    }

    @Test
    fun allowedButStillBlockedSaysToRestart() {
        val result = allow(FakeRunner(ElevatedOutcome.Exited(0)), recheck = { "still blocked" }).run()
        assertEquals(AllowApp.STILL_BLOCKED, result)
    }

    @Test
    fun aDeclinedPromptChangesNothing() {
        var checks = 0
        val result = allow(FakeRunner(ElevatedOutcome.NotStarted(AllowApp.ERROR_CANCELLED)), recheck = { checks++; null }).run()
        assertEquals("Not allowed. Nothing changed.", result)
        assertEquals(0, checks)
        assertEquals(listOf("Meal Planner: allowing the app through Controlled folder access: cancelled (error 1223)"), said)
    }

    @Test
    fun eachOtherOutcomeSaysWhatWentWrongWithTheManualSteps() {
        val cases = listOf(
            ElevatedOutcome.Exited(1) to "code 1",
            ElevatedOutcome.NotStarted(5) to "error 5",
            ElevatedOutcome.NoProcess to "no process",
            ElevatedOutcome.TimedOut to "no answer in 2 minutes",
            // WAIT_FAILED is its own failure with its own error, never mistaken for the timeout.
            ElevatedOutcome.WaitFailed(6) to "wait failed, error 6",
            ElevatedOutcome.NoExitCode(6) to "no exit code, error 6",
        )
        for ((outcome, detail) in cases) {
            var checks = 0
            val result = allow(FakeRunner(outcome), recheck = { checks++; null }).run()
            assertEquals(AllowApp.failedMessage(detail), result)
            assertTrue(result!!.startsWith("Couldn't change the setting ($detail). You can allow Meal Planner yourself in Windows Security"))
            assertTrue(result.contains("Allow an app through Controlled folder access"))
            assertEquals(0, checks)
        }
        assertEquals(AllowApp.failedMessage("couldn't ask Windows"), allow(FakeRunner(throws = true)).run())
        assertFalse(said.any { "Meal Planner.exe" in it || "Add-MpPreference" in it || "EncodedCommand" in it })
        assertTrue(said.contains("Meal Planner: allowing the app through Controlled folder access: wait failed (error 6)"))
    }

    @Test
    fun anExeThatIsntTheInstalledOneIsNeverRun() {
        val runner = FakeRunner()
        val result = allow(runner, launcher = File(local, "nowhere.exe").path).run()
        assertEquals(AllowApp.failedMessage("not the installed app"), result)
        assertTrue(runner.calls.isEmpty())
    }
}
