package com.naeblis11.mealplanner.desktop.update

import com.naeblis11.mealplanner.desktop.ProcessStarter
import com.naeblis11.mealplanner.desktop.Relauncher
import com.naeblis11.mealplanner.desktop.WindowsFolders
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Spec "Updates", P8-PF2, P8-R6a: the verified MSI is handed to Windows' own explorer.exe (outside the launcher's job
 * object), which opens it with msiexec; then the app quits. Only a plain update-*.msi directly in a plain updates
 * folder, with a path explorer can take, is ever handed over, and only while the app can quit. No test starts explorer,
 * msiexec or an installer: the starter is a fake. (The junction test runs cmd's mklink, in its own temp folder.)
 */
class MsiInstallerTest {
    private val root: File = Files.createTempDirectory("mp-msi-installer").toFile()
    private val windows = File(root, "Windows").apply { mkdirs() }
    private val system = File(windows, "System32").apply { mkdirs() }
    private val explorer = File(windows, Relauncher.EXPLORER).apply { writeText("not really explorer") }
    private val updates = File(File(root, "Meal Planner"), DesktopUpdates.FOLDER).apply { mkdirs() }
    private val events = mutableListOf<String>()
    private val started = mutableListOf<List<String>>()

    private class FakeFolders(val system: File?) : WindowsFolders {
        override fun system(): File? = system

        override fun localAppData(): File? = null
    }

    private val starter = ProcessStarter { command ->
        events += "start"
        started += command
    }

    private fun installer(
        dir: File = updates,
        folders: WindowsFolders = FakeFolders(system),
        start: ProcessStarter = starter,
        canQuit: () -> Boolean = { true },
    ) = MsiInstaller(dir, quit = { events += "quit" }, canQuit = canQuit, folders = folders, starter = start)

    private fun msi(name: String = "update-MealPlanner-1.0.1.msi", dir: File = updates): File =
        File(dir, name).apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }

    // Only the happy paths need a temp folder explorer can take; every refusal holds whatever the folder is.
    private fun assumeSafeTempFolder() {
        assumeTrue(MsiInstaller.isHandOffSafe(root.absolutePath))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun itHandsTheMsiToExplorerThenQuits() {
        assumeSafeTempFolder()
        val file = msi()
        val installer = installer()
        installer.install(file)
        assertEquals(listOf(listOf(explorer.path, file.absolutePath)), started)
        assertEquals(listOf("start", "quit"), events)
        assertTrue(installer.closesApp)
        assertTrue(installer.canInstall())
        assertTrue(installer.canStartNow())
        assertEquals(updates, installer.updatesDir)
    }

    @Test
    fun aProfileWithAnApostropheAnAccentOrAnAmpersandStillInstalls() {
        // P8-R6a: one ProcessBuilder argument takes these; only PowerShell's AllowApp needs the stricter rule.
        assumeSafeTempFolder()
        for (folder in listOf("O'Brien", "Jos\u00e9", "R&D")) {
            val dir = File(File(root, folder), DesktopUpdates.FOLDER)
            val file = msi(dir = dir)
            installer(dir = dir).install(file)
            assertEquals(listOf(explorer.path, file.absolutePath), started.last())
        }
        assertEquals(listOf("start", "quit", "start", "quit", "start", "quit"), events)
    }

    @Test
    fun theHandOffRule() {
        assertTrue(MsiInstaller.isHandOffSafe("C:\\Users\\O'Brien\\AppData\\Local\\Meal Planner\\updates\\update-x.msi"))
        assertTrue(MsiInstaller.isHandOffSafe("C:\\Users\\Jos\u00e9\\AppData\\Local\\Meal Planner\\updates\\update-x.msi"))
        assertTrue(MsiInstaller.isHandOffSafe("d:\\R&D (x)\\updates\\update-x.msi"))
        for (refused in listOf(
            "C:\\Users\\a,b\\updates\\update-x.msi",
            "C:\\Users\\a\"b\\updates\\update-x.msi",
            "C:\\Users\\a\tb\\updates\\update-x.msi",
            "C:\\Users\\a\u0000b\\updates\\update-x.msi",
            "C:\\Users/a\\updates\\update-x.msi",
            "\\\\server\\share\\updates\\update-x.msi",
            "/home/a/updates/update-x.msi",
            "C:updates\\update-x.msi",
            "",
        )) {
            assertFalse(refused, MsiInstaller.isHandOffSafe(refused))
        }
    }

    @Test
    fun aPathWithACommaIsRefused() {
        val dir = File(File(root, "a,b"), DesktopUpdates.FOLDER)
        val file = msi(dir = dir)
        assertThrows(IOException::class.java) { installer(dir = dir).install(file) }
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun nothingStartsUntilTheAppCanQuit() {
        // P8-R6a: before main's quit is there, the installer would run beside an app that can't get out of its way.
        val file = msi()
        var canQuit = false
        val installer = installer(canQuit = { canQuit })
        assertFalse(installer.canStartNow())
        assertThrows(IOException::class.java) { installer.install(file) }
        assertEquals(emptyList<String>(), events)
        assumeSafeTempFolder()
        canQuit = true
        assertTrue(installer.canStartNow())
        installer.install(file)
        assertEquals(listOf("start", "quit"), events)
    }

    @Test
    fun anInstallerThatDoesntStartLeavesTheAppRunning() {
        val file = msi()
        val installer = installer(start = { throw IOException("no explorer") })
        assertThrows(IOException::class.java) { installer.install(file) }
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun withoutWindowsOwnExplorerNothingStarts() {
        val file = msi()
        assertThrows(IOException::class.java) { installer(folders = FakeFolders(null)).install(file) }
        // A relative System32 (never trusted) and one whose Windows folder has no explorer.exe.
        assertThrows(IOException::class.java) { installer(folders = FakeFolders(File("Windows", "System32"))).install(file) }
        explorer.delete()
        assertThrows(IOException::class.java) { installer().install(file) }
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun onlyAnUpdateMsiDirectlyInTheUpdatesFolderIsHandedOver() {
        val refused = listOf(
            File(updates, "update-missing.msi"),
            msi("setup.exe"),
            msi("update-setup.exe"),
            msi("MealPlanner-1.0.1.msi"),
            msi("update-.msi"),
            msi("update-_x.msi"),
            msi("update-MealPlanner-1.0.1.MSI"),
            msi("update-MealPlanner-1.0.1.msi.part"),
            msi("update-MealPlanner 1.0.1.msi"),
            msi("update-" + "a".repeat(101) + ".msi"),
            // Not directly in the updates folder.
            msi(dir = File(updates, "inner")),
            msi(dir = File(root, "Downloads")),
            msi(dir = File(File(root, "elsewhere"), "updates")),
            // A folder with the right name isn't a file.
            File(updates, "update-folder.msi").apply { mkdirs() },
        )
        val installer = installer()
        for (file in refused) {
            assertThrows(file.path, IOException::class.java) { installer.install(file) }
        }
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun aFolderThatIsntNamedUpdatesIsRefused() {
        val other = File(root, "downloads").apply { mkdirs() }
        val file = msi(dir = other)
        assertThrows(IOException::class.java) { installer(dir = other).install(file) }
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun anUpdatesFolderThatIsAJunctionIsRefused() {
        assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))
        val target = File(root, "elsewhere").apply { mkdirs() }
        val junction = File(File(root, "Junctioned"), DesktopUpdates.FOLDER).apply { parentFile.mkdirs() }
        // cmd's mklink /J needs no privilege; both paths are in this test's own temp folder.
        val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", junction.absolutePath, target.absolutePath)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        process.inputStream.readBytes()
        assertTrue("mklink didn't finish", process.waitFor(30, TimeUnit.SECONDS))
        assumeTrue(process.exitValue() == 0 && junction.isDirectory)
        val file = msi(dir = target)
        val throughJunction = File(junction, file.name)
        assertTrue(throughJunction.isFile)
        assertThrows(IOException::class.java) { installer(dir = junction).install(throughJunction) }
        assertEquals(emptyList<String>(), events)
        // The junction itself goes; its target's contents stay to be deleted with the temp folder.
        Files.delete(junction.toPath())
    }

    @Test
    fun aLinkInTheUpdatesFolderIsRefused() {
        val target = msi(dir = File(root, "elsewhere"))
        val link = File(updates, "update-linked.msi")
        val made = try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        } catch (e: Exception) {
            false
        }
        // Windows lets only an administrator or Developer Mode make one.
        assumeTrue(made)
        assertThrows(IOException::class.java) { installer().install(link) }
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun theSourceNeverStartsMsiexecOrReadsTheEnvironment() {
        val source = File(
            File(System.getProperty("launcherOptionsScript") ?: error("launcherOptionsScript is not set; run through Gradle")).parentFile,
            "src/main/kotlin/com/naeblis11/mealplanner/desktop/update/MsiInstaller.kt",
        ).readText()
        val code = source.lines().map { it.substringBefore("//") }.filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/**") }
        for (line in code) {
            assertFalse(line, line.contains("msiexec", ignoreCase = true))
            assertFalse(line, line.contains("getenv"))
            assertFalse(line, line.contains("SystemRoot"))
            assertFalse(line, line.contains("cmd.exe", ignoreCase = true))
        }
    }
}
