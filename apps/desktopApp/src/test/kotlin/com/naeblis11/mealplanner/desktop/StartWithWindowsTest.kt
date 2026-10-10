package com.naeblis11.mealplanner.desktop

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3-R3: the Run key, through a fake reg.exe; the real registry is never touched. */
class StartWithWindowsTest {
    private val root: File = Files.createTempDirectory("mp-start-with-windows").toFile()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private class FakeFolders(val system: File?) : WindowsFolders {
        override fun system(): File? = system

        override fun localAppData(): File? = null
    }

    /** A process that has already ended with [code], having printed [output]. */
    private class EndedProcess(private val code: Int, output: String = "") : Process() {
        private val out = ByteArrayInputStream(output.toByteArray())

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = out

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = code

        override fun exitValue(): Int = code

        override fun destroy() {}
    }

    @Test
    fun regExeIsTheOneInTheSystemFolderWindowsNames() {
        // P8-PF2: the known-folder API's System32, never an environment variable whatever started the app may have set.
        val system = File(root, "Windows\\System32").apply { mkdirs() }
        val reg = File(system, RegExe.EXE_NAME).apply { writeText("not really reg") }
        assertEquals(reg, RegExe.exe(FakeFolders(system)))
        val started = mutableListOf<List<String>>()
        val runner = RegExe(FakeFolders(system), log = {}, start = { command -> started += command; EndedProcess(0) })
        assertEquals(0, runner.run(StartWithWindows.queryArgs()))
        assertEquals(listOf(listOf(reg.path) + StartWithWindows.queryArgs()), started)
        assertTrue(started.single().first().startsWith(system.path + File.separator))
    }

    @Test
    fun withoutASystemFolderNothingRunsAndItIsSaidOnce() {
        val said = mutableListOf<String>()
        val started = mutableListOf<List<String>>()
        for (folders in listOf(FakeFolders(null), FakeFolders(File("Windows\\System32")), FakeFolders(File(root, "no-reg-here").apply { mkdirs() }))) {
            assertNull(RegExe.exe(folders))
            val runner = RegExe(folders, log = { said += it }, start = { command -> started += command; EndedProcess(0) })
            assertEquals(RegExe.NOT_RUN, runner.run(StartWithWindows.queryArgs()))
            assertEquals(RegExe.NOT_RUN, runner.run(StartWithWindows.addArgs(launcher)))
            assertEquals(RegExe.NOT_RUN, runner.run(StartWithWindows.deleteArgs()))
        }
        assertEquals(emptyList<List<String>>(), started)
        assertEquals(3, said.size)
        assertTrue(said.all { "reg.exe" in it })
        // Read as not on, and a change as not taken: the user's choice is kept as it was.
        val startup = StartWithWindows(RegExe(FakeFolders(null), log = {}, start = { error("never") }), settings, installed = true, launcher = { launcher })
        startup.applyAtStartup()
        assertFalse(startup.isOn())
        assertFalse(startup.setOn(true))
        assertNull(settings.getString(StartWithWindows.PREF_KEY))
    }

    @Test
    fun aFailedRegAddIsLoggedByItsCodeAndOutput() {
        val system = File(root, "System32").apply { mkdirs() }
        File(system, RegExe.EXE_NAME).writeText("x")
        val said = mutableListOf<String>()
        val runner = RegExe(FakeFolders(system), log = { said += it }, start = { EndedProcess(1, "ERROR: Access is denied.") })
        assertEquals(1, runner.run(StartWithWindows.addArgs(launcher)))
        assertEquals(listOf("Meal Planner: reg.exe add failed (1): ERROR: Access is denied."), said)
        // A query that finds nothing is the usual answer, not a failure to log.
        assertEquals(1, runner.run(StartWithWindows.queryArgs()))
        assertEquals(1, said.size)
    }

    @Test
    fun theSourceNeverReadsTheEnvironmentForRegExe() {
        val source = File(
            File(System.getProperty("launcherOptionsScript") ?: error("launcherOptionsScript is not set; run through Gradle")).parentFile,
            "src/main/kotlin/com/naeblis11/mealplanner/desktop/StartWithWindows.kt",
        ).readText()
        val code = source.lines().map { it.substringBefore("//") }.filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") }
        for (line in code) {
            assertFalse(line, line.contains("getenv"))
            assertFalse(line, line.contains("System" + "Root"))
            assertFalse(line, line.contains("C:\\\\Windows"))
        }
    }

    /** reg.exe as far as these calls go: [exit] for add and delete, and whether the value is [present]. */
    private class FakeReg(var exit: Int = 0, var present: Boolean = false) : RegistryRunner {
        val calls = mutableListOf<List<String>>()

        override fun run(args: List<String>): Int {
            calls += args
            return when (args.first()) {
                "query" -> if (present) 0 else 1
                "add" -> exit.also { if (it == 0) present = true }
                "delete" -> exit.also { if (it == 0) present = false }
                else -> 1
            }
        }
    }

    private val launcher = "C:\\Users\\jo\\AppData\\Local\\Meal-Planner\\Meal Planner.exe"
    private val settings = MapSettings()

    private fun startup(reg: RegistryRunner, installed: Boolean = true) =
        StartWithWindows(reg, settings, installed = installed, launcher = { launcher })

    @Test
    fun theRunValueStartsThisLauncherInTheTray() {
        assertEquals("\"$launcher\" --minimized", StartWithWindows.runValue(launcher))
        assertEquals(
            listOf(
                "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "Meal Planner",
                "/t", "REG_SZ", "/d", "\\\"$launcher\\\" --minimized", "/f",
            ),
            StartWithWindows.addArgs(launcher),
        )
    }

    @Test
    fun theFirstInstalledStartTurnsItOn() {
        val reg = FakeReg()
        startup(reg).applyAtStartup()
        assertEquals(listOf(StartWithWindows.addArgs(launcher)), reg.calls)
        assertEquals("on", settings.getString(StartWithWindows.PREF_KEY))
        assertTrue(startup(reg).isOn())
    }

    @Test
    fun anInstalledStartPointsTheRunKeyAtThisLauncherAgain() {
        settings.put(mapOf(StartWithWindows.PREF_KEY to "on"))
        val reg = FakeReg(present = true)
        startup(reg).applyAtStartup()
        assertEquals(listOf(StartWithWindows.addArgs(launcher)), reg.calls)
    }

    @Test
    fun turnedOffItStaysOffAtTheNextStart() {
        val reg = FakeReg(present = true)
        assertTrue(startup(reg).setOn(false))
        assertEquals(listOf(StartWithWindows.deleteArgs()), reg.calls)
        assertEquals("off", settings.getString(StartWithWindows.PREF_KEY))
        reg.calls.clear()
        startup(reg).applyAtStartup()
        assertEquals(emptyList<List<String>>(), reg.calls)
        assertFalse(startup(reg).isOn())
    }

    @Test
    fun aRefusedWriteKeepsTheChoiceAsItWas() {
        val reg = FakeReg(exit = 1)
        assertFalse(startup(reg).setOn(true))
        assertNull(settings.getString(StartWithWindows.PREF_KEY))
    }

    @Test
    fun developmentNeverTouchesTheRegistry() {
        val reg = FakeReg()
        val dev = startup(reg, installed = false)
        dev.applyAtStartup()
        assertFalse(dev.available)
        assertFalse(dev.isOn())
        assertFalse(dev.setOn(true))
        assertEquals(emptyList<List<String>>(), reg.calls)
        assertNull(settings.getString(StartWithWindows.PREF_KEY))
    }

    @Test
    fun anOffSwitchBeatsTheInstalledLauncher() {
        // P7-R6: JAVA_TOOL_OPTIONS can add -Dmealplanner.startWithWindows=off but can't override the launcher's own
        // -Dmealplanner.installed=true (the JVM reads it before the command line), so the off has to win here.
        assertTrue(StartWithWindows.allowed(installed = "true", gate = null))
        assertTrue(StartWithWindows.allowed(installed = "true", gate = "on"))
        assertFalse(StartWithWindows.allowed(installed = "true", gate = "off"))
        assertFalse(StartWithWindows.allowed(installed = "true", gate = "no"))
        assertFalse(StartWithWindows.allowed(installed = "true", gate = ""))
        assertFalse(StartWithWindows.allowed(installed = null, gate = null))
        assertFalse(StartWithWindows.allowed(installed = "false", gate = "on"))
    }

    @Test
    fun anInstalledRunSwitchedOffNeverTouchesTheRegistry() {
        val reg = FakeReg()
        val gated = startup(reg, installed = StartWithWindows.allowed(installed = "true", gate = "off"))
        gated.applyAtStartup()
        assertFalse(gated.available)
        assertFalse(gated.isOn())
        assertFalse(gated.setOn(true))
        assertEquals(emptyList<List<String>>(), reg.calls)
        assertNull(settings.getString(StartWithWindows.PREF_KEY))
    }

    @Test
    fun theLauncherIsThePathJpackageSets() {
        assertEquals(launcher, StartWithWindows.launcherPath(appPath = launcher) { "C:\\elsewhere\\other.exe" })
    }

    @Test
    fun withoutJpackagesPathTheProcessIsTheLauncher() {
        assertEquals(launcher, StartWithWindows.launcherPath(appPath = null) { launcher })
        assertEquals(launcher, StartWithWindows.launcherPath(appPath = "  ") { launcher })
        assertNull(StartWithWindows.launcherPath(appPath = null) { null })
    }

    @Test
    fun theJdksJavaIsNeverTheLauncher() {
        // A Run key that starts the bare JDK would open nothing.
        assertNull(StartWithWindows.launcherPath(appPath = null) { "C:\\jdk\\bin\\java.exe" })
        assertNull(StartWithWindows.launcherPath(appPath = null) { "C:\\jdk\\bin\\JAVAW.EXE" })
        assertEquals(launcher, StartWithWindows.launcherPath(appPath = "C:\\jdk\\bin\\java.exe") { launcher })
    }

    @Test
    fun startedByJavaItIsNotOffered() {
        val reg = FakeReg()
        val viaJava = StartWithWindows(
            reg,
            settings,
            installed = true,
            launcher = { StartWithWindows.launcherPath(appPath = null) { "C:\\jdk\\bin\\javaw.exe" } },
        )
        viaJava.applyAtStartup()
        assertFalse(viaJava.available)
        assertFalse(viaJava.setOn(true))
        assertEquals(emptyList<List<String>>(), reg.calls)
    }
}
