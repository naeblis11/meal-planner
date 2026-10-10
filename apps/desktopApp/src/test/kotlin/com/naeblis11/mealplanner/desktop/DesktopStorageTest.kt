package com.naeblis11.mealplanner.desktop

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P7-R10: Settings' two folders come from the app, open in Explorer when they are there, and are never made here. */
class DesktopStorageTest {
    private val root: File = Files.createTempDirectory("mp-storage-folders").toFile()
    private val library = File(root, "Documents/Meal Planner")
    private val appData = File(root, "Local/Meal Planner")
    private val app = DesktopApp(library, settingsFactory = { MapSettings() }, appDataDir = appData)
    private val opened = mutableListOf<File>()

    @After
    fun tearDown() {
        app.close()
        root.deleteRecursively()
    }

    @Test
    fun theTwoFoldersAreTheAppsAndOpenInExplorer() {
        val storage = DesktopStorage(app) { opened += it }
        assertEquals(library.absolutePath, storage.libraryPath)
        assertEquals(appData.absolutePath, storage.appDataPath)
        assertTrue(storage.libraryExists())
        assertTrue(storage.appDataExists())
        assertTrue(storage.openLibrary())
        assertTrue(storage.openAppData())
        assertEquals(listOf(library, appData), opened)
        assertNull(storage.libraryNotice.value)
    }

    @Test
    fun aFolderThatIsntThereIsNeitherOpenedNorMade() {
        val storage = DesktopStorage(app) { opened += it }
        assertTrue(library.deleteRecursively())
        assertFalse(storage.libraryExists())
        assertFalse(storage.openLibrary())
        assertFalse(library.exists())
        assertEquals(emptyList<File>(), opened)
    }

    @Test
    fun allowingRunsOneAtATimeAndSaysHowItWent() {
        // P7-R11: a fake runner stands for UAC; a second press while the first waits on it runs nothing.
        val local = File(root, "Local")
        val installDir = File(local, AllowApp.INSTALL_DIR_NAME).apply { mkdirs() }
        val exe = File(installDir, AllowApp.EXE_NAME).apply { writeText("x") }
        val system = File(root, "System32")
        File(system, "WindowsPowerShell/v1.0/powershell.exe").apply { parentFile.mkdirs(); writeText("x") }
        val folders = object : WindowsFolders {
            override fun system() = system
            override fun localAppData() = local
        }
        val gate = java.util.concurrent.CountDownLatch(1)
        val asking = java.util.concurrent.CountDownLatch(1)
        var runs = 0
        val runner = ElevatedRunner { _, _, _ ->
            runs++
            asking.countDown()
            gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
            ElevatedOutcome.NotStarted(AllowApp.ERROR_CANCELLED)
        }
        val allow = AllowApp(runner, launcher = { exe.path }, folders = folders, recheck = { null }, log = {})
        val storage = DesktopStorage(app, allow = allow) { opened += it }
        assertTrue(storage.canAllowApp)
        val first = Thread { storage.allowApp() }.apply { start() }
        assertTrue(asking.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(storage.allowing.value)
        storage.allowApp()
        gate.countDown()
        first.join(5_000)
        assertEquals(1, runs)
        assertFalse(storage.allowing.value)
        assertEquals(AllowApp.NOT_ALLOWED, storage.allowMessage.value)
        // The next press clears the last message before it asks; a library that can be written clears it too.
        val seen = mutableListOf<String?>()
        lateinit var watched: DesktopStorage
        val watching = ElevatedRunner { _, _, _ ->
            seen += watched.allowMessage.value
            ElevatedOutcome.NotStarted(AllowApp.ERROR_CANCELLED)
        }
        watched = DesktopStorage(app, allow = AllowApp(watching, launcher = { exe.path }, folders = folders, recheck = { null }, log = {})) { opened += it }
        watched.allowApp()
        assertEquals(AllowApp.NOT_ALLOWED, watched.allowMessage.value)
        watched.allowApp()
        assertEquals(listOf<String?>(null, null), seen)
        assertEquals(AllowApp.NOT_ALLOWED, watched.allowMessage.value)
        watched.checkAgain()
        assertNull(watched.allowMessage.value)
        // The preview, with no installed exe, never offers it.
        assertFalse(DesktopStorage(app) { opened += it }.canAllowApp)
    }

    @Test
    fun anExplorerThatFailsIsNeverAThrow() {
        val storage = DesktopStorage(app) { throw UnsupportedOperationException("no desktop") }
        assertFalse(storage.openAppData())
    }
}
