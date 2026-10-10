package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.desktop.server.ExtensionImport
import com.naeblis11.mealplanner.desktop.server.JsonReply
import com.naeblis11.mealplanner.desktop.server.eventually
import com.naeblis11.mealplanner.importing.ImportInbox
import com.naeblis11.mealplanner.settings.AppReplaced
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7-R12: a new MSI installed over the running app is noticed whenever the window is shown, and Restart now quits as
 * Quit does, then opens the installed launcher. Temp files, fake Windows folders and a fake process starter only:
 * nothing here starts a process; the single-instance check uses 127.0.0.1 and an ephemeral port.
 */
class AppReplacedTest {
    private val root: File = Files.createTempDirectory("mp-replaced").toFile()
    // The installed app's `app\` folder: the jar the code came from, another jar, and the launcher's cfg.
    private val appDir = File(root, "app").apply { mkdirs() }
    private val jarFile = File(appDir, "desktopApp-0a1b2c.jar").apply { writeText("the old code") }
    private val otherJar = File(appDir, "shared-ui-3d4e5f.jar").apply { writeText("more old code") }
    private val cfgFile = File(appDir, AppJar.CFG_NAME).apply { writeText("[Application]\napp.classpath=\$APPDIR\\desktopApp-0a1b2c.jar\n") }
    private val local = File(root, "Local").apply { mkdirs() }
    private val windows = File(root, "Windows").apply { mkdirs() }
    private val system = File(windows, "System32").apply { mkdirs() }
    private val explorer = File(windows, Relauncher.EXPLORER).apply { writeText("not really explorer") }
    private val exe = File(File(local, AllowApp.INSTALL_DIR_NAME).apply { mkdirs() }, AllowApp.EXE_NAME).apply { writeText("not really an exe") }
    private val opened = mutableListOf<SingleInstance>()
    private val said = mutableListOf<String>()

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        root.deleteRecursively()
    }

    private class FakeFolders(val system: File?, val local: File?) : WindowsFolders {
        override fun system(): File? = system

        override fun localAppData(): File? = local
    }

    private class FakeStarter(var throws: Boolean = false, val calls: MutableList<String>? = null) : ProcessStarter {
        val started = mutableListOf<List<String>>()

        override fun start(command: List<String>) {
            calls?.add("start")
            if (throws) throw IOException("no such program")
            started += command
        }
    }

    /** The real reader, until [failure] is set: then every read throws it (a read that failed, not a missing file). */
    private class FlakyReader : InstallReader {
        var failure: Exception? = null

        private fun check() {
            failure?.let { throw it }
        }

        override fun sizeAndTime(path: Path): Pair<Long, Long> = check().let { NioInstallReader.sizeAndTime(path) }

        override fun jarNames(dir: Path): Set<String> = check().let { NioInstallReader.jarNames(dir) }

        override fun bytes(path: Path): ByteArray = check().let { NioInstallReader.bytes(path) }
    }

    private fun relauncher(
        installed: Boolean = true,
        launcher: String? = exe.path,
        folders: WindowsFolders = FakeFolders(system, local),
        starter: ProcessStarter = FakeStarter(),
    ) = Relauncher(installed = installed, launcher = { launcher }, folders = folders, starter = starter, log = { said += it })

    private fun notice(relauncher: Relauncher = relauncher(), restart: () -> Unit = {}) =
        ReplacedNotice(AppJar.of(jarFile), relauncher, restart = restart, log = { said += it })

    private fun shell(notice: ReplacedNotice) =
        WindowShell(startMinimized = true, traySupported = true, notice = TrayNotice(MapSettings()), look = { notice.look() })

    // The look runs after the show, on the shell's own thread: wait for the [count]th to have finished.
    private fun awaitLooks(shell: WindowShell, count: Int) = eventually { shell.looksCompleted >= count }

    private fun replaceJar() {
        jarFile.writeText("the new code, a little longer")
    }

    @Test
    fun anUnchangedInstallIsTheSame() {
        val jar = AppJar.of(jarFile)!!
        assertEquals(InstallLook.SAME, jar.look())
        assertEquals(InstallLook.SAME, jar.look())
        assertFalse(jar.replaced())
    }

    @Test
    fun aJarThatChangedSizeOrTimeOrWentAwayIsReplaced() {
        val resized = AppJar.of(jarFile)!!
        replaceJar()
        assertEquals(InstallLook.CHANGED, resized.look())

        val retimed = AppJar.of(jarFile)!!
        assertTrue(jarFile.setLastModified(jarFile.lastModified() - 60_000))
        assertEquals(InstallLook.CHANGED, retimed.look())

        val gone = AppJar.of(jarFile)!!
        assertTrue(jarFile.delete())
        assertEquals(InstallLook.CHANGED, gone.look())
    }

    @Test
    fun aNewCfgShowsTheNoticeWhileTheHeldJarIsUnchanged() {
        // msiexec couldn't replace the jar the JVM holds open (left for the reboot), but it did write the new cfg.
        val notice = notice()
        assertFalse(notice.look())
        cfgFile.writeText("[Application]\napp.classpath=\$APPDIR\\desktopApp-9f8e7d.jar\n")
        assertTrue(notice.look())
        assertEquals(AppReplaced(canRestart = true), notice.replaced.value)
    }

    @Test
    fun aChangedJarSetShowsTheNoticeWhileTheHeldJarIsUnchanged() {
        val added = notice()
        File(appDir, "desktopApp-9f8e7d.jar").writeText("the new code")
        assertTrue(added.look())

        val removed = notice()
        assertTrue(otherJar.delete())
        assertTrue(removed.look())

        // Anything else in the folder isn't watched.
        val other = notice()
        File(appDir, "notes.txt").writeText("x")
        assertFalse(other.look())
    }

    @Test
    fun aGoneCfgOrFolderIsAChange() {
        val jar = AppJar.of(jarFile)!!
        assertTrue(cfgFile.delete())
        assertEquals(InstallLook.CHANGED, jar.look())
        assertTrue(appDir.deleteRecursively())
        assertEquals(InstallLook.CHANGED, jar.look())
    }

    @Test
    fun aReadThatFailsIsUnknownAndShowsNothing() {
        val reader = FlakyReader()
        val notice = ReplacedNotice(AppJar.of(jarFile, reader), relauncher(), log = { said += it })
        reader.failure = IOException("The process cannot access the file because it is being used by another process")
        repeat(3) { assertFalse(notice.look()) }
        reader.failure = SecurityException("denied")
        assertFalse(notice.look())
        assertNull(notice.replaced.value)
        // Once the reads work again, an unchanged install is still the same.
        reader.failure = null
        assertFalse(notice.look())
        assertNull(notice.replaced.value)
        // And a real change is still seen.
        replaceJar()
        assertTrue(notice.look())
    }

    @Test
    fun aFailedReadIsUnknownEvenBesideAnUnchangedInstall() {
        val reader = FlakyReader()
        val jar = AppJar.of(jarFile, reader)!!
        assertEquals(InstallLook.SAME, jar.look())
        reader.failure = IOException("busy")
        assertEquals(InstallLook.UNKNOWN, jar.look())
        assertFalse(jar.replaced())
    }

    @Test
    fun onlyAJarFileIsWatched() {
        // A development run loads its classes from a folder: never "replaced".
        assertNull(AppJar.of(root))
        assertNull(AppJar.of(File(appDir, "missing.jar")))
        assertNull(AppJar.of(File(root, "notes.txt").apply { writeText("x") }))
        // The tests run from Gradle's classes folder, not a jar.
        assertNull(AppJar.running(AppReplacedTest::class.java))
        assertNotNull(AppJar.of(jarFile))
        // With no cfg beside it, the jar and the jar set are still watched.
        assertTrue(cfgFile.delete())
        val noCfg = AppJar.of(jarFile)!!
        assertEquals(InstallLook.SAME, noCfg.look())
        File(appDir, "desktopApp-9f8e7d.jar").writeText("the new code")
        assertEquals(InstallLook.CHANGED, noCfg.look())
    }

    @Test
    fun aShowRequestFromASecondLaunchLooks() {
        val notice = notice()
        val shell = shell(notice)
        val first = CountDownLatch(1)
        val second = CountDownLatch(2)
        val cache = File(root, "cache")
        SingleInstance.acquire(cache) { shell.show(); first.countDown(); second.countDown() }!!.also { opened += it }

        // Unchanged: the window comes forward and nothing is said.
        assertNull(SingleInstance.acquire(cache) {})
        assertTrue(first.await(10, TimeUnit.SECONDS))
        awaitLooks(shell, 1)
        assertNull(notice.replaced.value)
        replaceJar()
        // The MSI replaced the app; the next launch's request finds it.
        assertNull(SingleInstance.acquire(cache) {})
        assertTrue(second.await(10, TimeUnit.SECONDS))
        awaitLooks(shell, 2)
        assertEquals(AppReplaced(canRestart = true), notice.replaced.value)
    }

    @Test
    fun theTraysOpenLooks() {
        val notice = notice()
        val shell = shell(notice)
        shell.show()
        awaitLooks(shell, 1)
        assertNull(notice.replaced.value)
        assertTrue(jarFile.delete())
        shell.show()
        assertTrue(shell.isVisible)
        awaitLooks(shell, 2)
        assertEquals(AppReplaced(canRestart = true), notice.replaced.value)
        assertEquals(TRAY_REPLACED_TOOLTIP, trayTooltip(notice.replaced.value))
        assertEquals(TrayNotice.TITLE, trayTooltip(null))
    }

    @Test
    fun aRecipeFromTheExtensionLooksToo() {
        val notice = notice()
        val shell = shell(notice)
        var stagings = 0
        val importer = ExtensionImport(
            newStagingDir = { File(root, "staging-${stagings++}").apply { mkdirs() } },
            fetchImage = { throw IOException("no network in tests") },
            inbox = ImportInbox(),
            onReceived = shell::show,
            newUuid = { "uuid-1" },
            log = {},
        )
        replaceJar()
        val reply = importer.receive(
            linkedMapOf<Any?, Any?>(
                "name" to "Extracted Soup",
                "ingredients" to listOf("2 cups flour"),
                "steps" to listOf("Boil water."),
                "source_url" to "https://www.example.com/soup",
                "image_url" to null,
            ),
        )
        assertEquals(JsonReply.ok(), reply)
        assertTrue(shell.isVisible)
        awaitLooks(shell, 1)
        assertEquals(AppReplaced(canRestart = true), notice.replaced.value)
    }

    @Test
    fun aQuittingShellDoesntLook() {
        val notice = notice()
        val shell = shell(notice)
        assertTrue(shell.quit())
        replaceJar()
        shell.show()
        // Nothing was queued: a quitting shell returns before any look.
        assertEquals(0, shell.looksCompleted)
        assertNull(notice.replaced.value)
    }

    @Test
    fun anUnchangedInstallShowsNothing() {
        val notice = notice()
        repeat(3) { assertFalse(notice.look()) }
        assertNull(notice.replaced.value)
        // A development run, with no jar, never says so.
        val dev = ReplacedNotice(null, relauncher())
        assertFalse(dev.look())
        assertNull(dev.replaced.value)
    }

    @Test
    fun theNoticeStaysOnceSeen() {
        val notice = notice()
        replaceJar()
        assertTrue(notice.look())
        assertFalse(notice.look())
        assertEquals(AppReplaced(canRestart = true), notice.replaced.value)
    }

    @Test
    fun anUninstalledAppSaysSoNeutrally() {
        val notice = notice()
        // Uninstalled, not reinstalled: the jars and the launcher are gone.
        assertTrue(appDir.deleteRecursively())
        assertTrue(exe.delete())
        assertTrue(notice.look())
        assertEquals(AppReplaced(canRestart = false, removed = true), notice.replaced.value)
        assertEquals(TRAY_REMOVED_TOOLTIP, trayTooltip(notice.replaced.value))
        // Not the installed app: no launcher to be gone, so only "quit and open it again".
        val dev = ReplacedNotice(AppJar.of(File(root, "dev.jar").apply { writeText("x") }), relauncher(installed = false))
        File(root, "dev.jar").writeText("changed, longer")
        assertTrue(dev.look())
        assertEquals(AppReplaced(canRestart = false, removed = false), dev.replaced.value)
    }

    @Test
    fun canRestartIsRecomputedOnEachLookAndAtTheClick() {
        var restarts = 0
        val notice = notice(restart = { restarts++ })
        // The install is under way: the jars changed and the launcher isn't there yet.
        assertTrue(exe.delete())
        replaceJar()
        assertTrue(notice.look())
        assertEquals(AppReplaced(canRestart = false, removed = true), notice.replaced.value)
        // It finished: the next look offers Restart now.
        exe.writeText("the new exe")
        assertFalse(notice.look())
        assertEquals(AppReplaced(canRestart = true), notice.replaced.value)
        // The launcher went away again before the click: no quit, and the notice says what to do instead.
        assertTrue(exe.delete())
        notice.restartNow()
        assertEquals(0, restarts)
        assertEquals(AppReplaced(canRestart = false, removed = true), notice.replaced.value)
        // Back again: the click restarts.
        exe.writeText("the new exe")
        notice.restartNow()
        assertEquals(1, restarts)
        assertEquals(AppReplaced(canRestart = true), notice.replaced.value)
    }

    @Test
    fun restartIsOfferedOnlyInTheInstalledAppWithAValidLauncher() {
        assertTrue(relauncher().available)
        assertEquals(listOf(explorer.path, exe.canonicalFile.path), relauncher().command())
        // Not the installed app (development, the preview).
        assertFalse(relauncher(installed = false).available)
        // No launcher, the bare JDK, one outside the install folder, or one that isn't there.
        assertFalse(relauncher(launcher = null).available)
        assertFalse(relauncher(launcher = File(exe.parentFile, "java.exe").apply { writeText("x") }.path).available)
        val outside = File(File(local, "Elsewhere").apply { mkdirs() }, AllowApp.EXE_NAME).apply { writeText("x") }
        assertFalse(relauncher(launcher = outside.path).available)
        // Windows' folders unknown, or no explorer.exe where Windows is.
        assertFalse(relauncher(folders = FakeFolders(system, null)).available)
        assertFalse(relauncher(folders = FakeFolders(null, local)).available)
        assertTrue(explorer.delete())
        assertFalse(relauncher().available)
        // Only a launcher that is gone counts as removed.
        assertFalse(relauncher().launcherGone())
        explorer.writeText("x")
        assertTrue(exe.delete())
        assertFalse(relauncher().available)
        assertTrue(relauncher().launcherGone())
        assertFalse(relauncher(installed = false).launcherGone())

        // The notice offers Restart now only then; otherwise only "quit and open it again", and the button does nothing.
        var restarts = 0
        val byHand = ReplacedNotice(AppJar.of(jarFile), relauncher(installed = false), restart = { restarts++ })
        replaceJar()
        assertTrue(byHand.look())
        assertEquals(AppReplaced(canRestart = false), byHand.replaced.value)
        byHand.restartNow()
        assertEquals(0, restarts)
    }

    @Test
    fun restartNowRunsTheRestart() {
        var restarts = 0
        val notice = notice(restart = { restarts++ })
        // Not before the notice is up.
        notice.restartNow()
        assertEquals(0, restarts)
        replaceJar()
        notice.look()
        notice.restartNow()
        assertEquals(1, restarts)
    }

    @Test
    fun restartClosesReleasesTheLockThenStartsTheLauncher() {
        val calls = mutableListOf<String>()
        val starter = FakeStarter(calls = calls)
        val relaunch = relauncher(starter = starter)
        runBlocking {
            closeForQuit(
                shutdown = { calls += "shutdown"; true },
                release = { calls += "release" },
                log = { said += it },
                afterRelease = { relaunch.launch() },
            )
        }
        assertEquals(listOf("shutdown", "release", "start"), calls)
        assertEquals(listOf(listOf(explorer.path, exe.canonicalFile.path)), starter.started)
    }

    @Test
    fun aRestartWhoseCloseTimedOutStartsNothing() {
        val starter = FakeStarter()
        val relaunch = relauncher(starter = starter)
        var released = 0
        runBlocking { closeForQuit(shutdown = { false }, release = { released++ }, log = { said += it }, afterRelease = { relaunch.launch() }) }
        assertEquals(0, released)
        assertTrue(starter.started.isEmpty())
        assertEquals(listOf(QUIT_TIMED_OUT, RESTART_SKIPPED), said)
        // A close that failed starts nothing either.
        assertThrows(IOException::class.java) {
            runBlocking { closeForQuit(shutdown = { throw IOException("database is locked") }, release = { released++ }, afterRelease = { relaunch.launch() }) }
        }
        assertTrue(starter.started.isEmpty())
    }

    @Test
    fun aLaunchThatFailsIsLoggedNotThrown() {
        assertFalse(relauncher(starter = FakeStarter(throws = true)).launch())
        assertFalse(relauncher(installed = false).launch())
        assertEquals(2, said.size)
        assertTrue(said.all { it.startsWith("Meal Planner: restarting after an update:") })
        // The log never holds the path.
        assertTrue(said.none { it.contains(exe.name) })
    }
}
