package com.naeblis11.mealplanner.update

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Spec "Updates", installing: into the app's cache, size and SHA-256 checked, anything that doesn't match deleted. */
class UpdatesInstallTest {
    private val releases = FakeReleases()
    private val fixture = ReleaseFixture(releases)
    private val dir: File = Files.createTempDirectory("mp-updates-install").toFile()
    private val folder = File(dir, "updates")
    private val settings = MemorySettings()
    private val installer = FakeInstaller()
    private var now = START

    @After
    fun tearDown() {
        releases.close()
        dir.deleteRecursively()
    }

    private fun updates(running: RunningApp = RunningApp.Desktop("1.0.0"), http: ReleaseHttp = releases.http()) =
        Updates(running, fixture.keys.public, installer, settings, folder, http, releases.endpoints, clock = { now })

    private suspend fun offered(updates: Updates = updates()): Updates {
        updates.check(manual = true)
        check(updates.state.value.offer != null) { "nothing was offered" }
        return updates
    }

    @Test
    fun installDownloadsVerifiesAndHandsOverTheFile() = runBlocking {
        fixture.publish()
        val updates = offered()
        updates.installOffer()
        assertEquals(listOf(File(folder, "update-MealPlanner-1.0.1.msi").canonicalFile), installer.files.map { it.canonicalFile })
        assertArrayEquals(fixture.msi, installer.installed.single())
        // Only the verified file is left: no .part, nothing else.
        assertEquals(listOf("update-MealPlanner-1.0.1.msi"), folder.list()!!.toList())
        assertNull(updates.state.value.problem)
        assertEquals(UpdatePhase.IDLE, updates.state.value.phase)
    }

    @Test
    fun aDownloadThatDoesntMatchTheListIsDeletedAndReported() = runBlocking {
        fixture.publish(listedMsiSha = Sha256.of(byteArrayOf(1, 2, 3)))
        val updates = offered()
        updates.installOffer()
        assertEquals(UpdateMessages.HASH_MISMATCH, updates.state.value.problem)
        assertEquals(emptyList<File>(), installer.files)
        assertEquals(emptyList<String>(), folder.list()!!.toList())
    }

    @Test
    fun aDownloadOfAnotherSizeIsDeletedAndReported() = runBlocking {
        fixture.publish(listedMsiSize = fixture.msi.size + 10L)
        val updates = offered()
        updates.installOffer()
        assertEquals(UpdateMessages.SIZE_MISMATCH, updates.state.value.problem)
        // And without a Content-Length, past the listed size mid-stream.
        fixture.publish(listedMsiSize = fixture.msi.size - 10L)
        releases.chunked = true
        val again = offered(updates())
        again.installOffer()
        assertEquals(UpdateMessages.SIZE_MISMATCH, again.state.value.problem)
        assertEquals(emptyList<File>(), installer.files)
        assertEquals(emptyList<String>(), folder.list()!!.toList())
    }

    @Test
    fun withoutPermissionNothingIsDownloaded() = runBlocking {
        fixture.publish()
        installer.allowed = false
        val phone = offered(updates(RunningApp.Android(versionCode = 1, versionName = "1.0.0")))
        val asked = releases.requests.size
        phone.installOffer()
        assertTrue(phone.state.value.needsPermission)
        assertEquals(asked, releases.requests.size)
        phone.openInstallPermission()
        assertEquals(1, installer.permissionAsks.get())
        // Back from Android's page with the permission given, Install again goes ahead.
        installer.allowed = true
        phone.installOffer()
        assertFalse(phone.state.value.needsPermission)
        assertArrayEquals(fixture.apk, installer.installed.single())
    }

    @Test
    fun comingBackWithThePermissionClearsTheBannerAndInstallsNothing() = runBlocking {
        fixture.publish()
        installer.allowed = false
        val phone = offered(updates(RunningApp.Android(versionCode = 1, versionName = "1.0.0")))
        phone.installOffer()
        assertTrue(phone.state.value.needsPermission)
        // Back without allowing it: the banner stays.
        phone.recheckPermission()
        assertTrue(phone.state.value.needsPermission)
        // Back having allowed it: the banner goes, and Install is still the user's to press.
        installer.allowed = true
        val asked = releases.requests.size
        phone.recheckPermission()
        assertFalse(phone.state.value.needsPermission)
        assertEquals(asked, releases.requests.size)
        assertEquals(emptyList<File>(), installer.files)
        assertNotNull(phone.state.value.offer)
    }

    @Test
    fun aVerifiedDownloadIsntFetchedAgain() = runBlocking {
        fixture.publish()
        val updates = offered()
        updates.installOffer()
        val asked = releases.requests.size
        updates.installOffer()
        assertEquals(asked, releases.requests.size)
        assertEquals(2, installer.files.size)
    }

    @Test
    fun aChangedFileInTheCacheIsFetchedAgain() = runBlocking {
        fixture.publish()
        val updates = offered()
        val cached = File(folder, "update-MealPlanner-1.0.1.msi")
        folder.mkdirs()
        cached.writeBytes(ByteArray(fixture.msi.size))
        updates.installOffer()
        assertArrayEquals(fixture.msi, installer.installed.single())
        assertArrayEquals(fixture.msi, cached.readBytes())
    }

    @Test
    fun anInstallerThatDoesntStartSaysSo() = runBlocking {
        fixture.publish()
        installer.fails = IOException("no installer")
        val updates = offered()
        updates.installOffer()
        assertEquals(UpdateMessages.INSTALL_FAILED, updates.state.value.problem)
        assertEquals(UpdatePhase.IDLE, updates.state.value.phase)
    }

    @Test
    fun leftDuringTheDownloadTheNoticeOffersInstallOnReturn() = runBlocking {
        // P8-PF11: Android starts the installer only in the foreground; the verified file waits for Install.
        fixture.publish()
        installer.foreground = false
        val phone = offered(updates(RunningApp.Android(versionCode = 1, versionName = "1.0.0")))
        phone.installOffer()
        assertEquals(emptyList<File>(), installer.files)
        assertTrue(phone.state.value.waitingToInstall)
        assertTrue(phone.state.value.showsNotice)
        assertNull(phone.state.value.problem)
        assertEquals(listOf("update-MealPlanner-1.0.1.apk"), folder.list()!!.toList())
        val asked = releases.requests.size
        installer.foreground = true
        phone.installOffer()
        assertEquals(asked, releases.requests.size)
        assertArrayEquals(fixture.apk, installer.installed.single())
        assertFalse(phone.state.value.waitingToInstall)
        assertFalse(phone.state.value.showsNotice)
    }

    @Test
    fun aCancelledDownloadStopsAndLeavesNoPart() = runBlocking {
        fixture.publish()
        val updates = offered()
        releases.dripMillis = 5
        val job = launch(Dispatchers.Default) { updates.installOffer() }
        withTimeout(10_000) { updates.state.first { it.downloaded > 0 } }
        val started = System.nanoTime()
        withTimeout(10_000) { job.cancelAndJoin() }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        assertEquals(emptyList<String>(), folder.list()!!.toList())
        assertEquals(emptyList<File>(), installer.files)
        assertEquals(UpdatePhase.IDLE, updates.state.value.phase)
        assertEquals(0L, updates.state.value.downloaded)
        // Nothing is held: Install works again afterwards.
        releases.dripMillis = 0
        updates.installOffer()
        assertArrayEquals(fixture.msi, installer.installed.single())
    }

    @Test
    fun aDownloadPastItsDeadlineIsDeletedAndReported() = runBlocking {
        fixture.publish()
        val updates = offered(updates(http = releases.http(downloadTimeoutMillis = 300)))
        releases.dripMillis = 5
        updates.installOffer()
        assertEquals(UpdateMessages.DOWNLOAD_FAILED, updates.state.value.problem)
        assertEquals(emptyList<String>(), folder.list()!!.toList())
        assertEquals(emptyList<File>(), installer.files)
    }

    @Test
    fun downloadsADayOldAreDeletedAtLaunch() = runBlocking {
        folder.mkdirs()
        val old = File(folder, "update-MealPlanner-1.0.0.msi").apply { writeBytes(byteArrayOf(1)) }
        val oldPart = File(folder, "update-MealPlanner-1.0.0.msi.part").apply { writeBytes(byteArrayOf(1)) }
        val fresh = File(folder, "update-MealPlanner-1.0.1.msi").apply { writeBytes(byteArrayOf(2)) }
        check(old.setLastModified(now - 2 * UpdateSchedule.DAY_MILLIS) && oldPart.setLastModified(now - 2 * UpdateSchedule.DAY_MILLIS))
        check(fresh.setLastModified(now - 3_600_000L))
        // The clean-up runs even when the check itself is switched off.
        settings.put(mapOf(Updates.AUTOMATIC to Updates.OFF))
        updates().checkAtLaunch()
        assertFalse(old.exists())
        assertFalse(oldPart.exists())
        assertTrue(fresh.exists())
        assertEquals(emptyList<String>(), releases.requests)
    }

    @Test
    fun theCleanUpTakesOnlyItsOwnFilesAndNeverRecurses() = runBlocking {
        // P8-PF6: only plain update-* files in the updates folder; anything else there is left alone.
        folder.mkdirs()
        val longAgo = now - 30 * UpdateSchedule.DAY_MILLIS
        val other = File(folder, "keep.txt").apply { writeBytes(byteArrayOf(1)) }
        val sub = File(folder, "update-folder").apply { mkdirs() }
        val inner = File(sub, "update-inner.msi").apply { writeBytes(byteArrayOf(1)) }
        val stale = File(folder, "update-MealPlanner-0.9.0.msi").apply { writeBytes(byteArrayOf(1)) }
        for (f in listOf(other, sub, inner, stale)) check(f.setLastModified(longAgo))
        settings.put(mapOf(Updates.AUTOMATIC to Updates.OFF))
        updates().checkAtLaunch()
        assertTrue(other.exists())
        assertTrue(inner.exists())
        assertFalse(stale.exists())
        // A download clears its older downloads, and nothing else.
        val older = File(folder, "update-MealPlanner-1.0.0.msi").apply { writeBytes(byteArrayOf(1)) }
        fixture.publish()
        settings.put(mapOf(Updates.AUTOMATIC to Updates.ON))
        offered().installOffer()
        assertFalse(older.exists())
        assertTrue(other.exists())
        assertTrue(inner.exists())
        assertEquals(setOf("keep.txt", "update-folder", "update-MealPlanner-1.0.1.msi"), folder.list()!!.toSet())
    }

    private fun msiRequests(): Int = releases.requests.count { it.endsWith("/MealPlanner-1.0.1.msi") }

    @Test
    fun aPlantedPartIsNeverWrittenThroughOrRemoved() = runBlocking {
        fixture.publish()
        val updates = offered()
        folder.mkdirs()
        // A folder at the .part's name: refused before anything is fetched, and left where it is.
        val partFolder = File(folder, "update-MealPlanner-1.0.1.msi.part").apply { mkdirs() }
        updates.installOffer()
        assertEquals(UpdateMessages.DOWNLOAD_FAILED, updates.state.value.problem)
        assertTrue(partFolder.isDirectory)
        assertEquals(0, msiRequests())
        assertEquals(emptyList<File>(), installer.files)
        // A link at the .part's name (a symbolic link, or a junction where links aren't allowed): never followed.
        check(partFolder.delete())
        val victim = File(dir, "victim").apply { mkdirs() }
        val victimFile = File(victim, "keep.txt").apply { writeBytes(byteArrayOf(5)) }
        val linked = TestLinks.make(File(folder, "update-MealPlanner-1.0.1.msi.part"), victimFile) ||
            TestLinks.make(File(folder, "update-MealPlanner-1.0.1.msi.part"), victim)
        if (linked) {
            updates.installOffer()
            assertEquals(UpdateMessages.DOWNLOAD_FAILED, updates.state.value.problem)
            assertTrue(Files.exists(File(folder, "update-MealPlanner-1.0.1.msi.part").toPath(), LinkOption.NOFOLLOW_LINKS))
            assertArrayEquals(byteArrayOf(5), victimFile.readBytes())
            assertEquals(listOf("keep.txt"), victim.list()!!.toList())
            assertEquals(0, msiRequests())
            assertEquals(emptyList<File>(), installer.files)
        } else {
            System.err.println("aPlantedPartIsNeverWrittenThroughOrRemoved: this machine can make neither a link nor a junction; the folder case ran")
        }
    }

    @Test
    fun anEmptyFolderNamedLikeTheDownloadIsNeverReplaced() = runBlocking {
        fixture.publish()
        val updates = offered()
        val taken = File(folder, "update-MealPlanner-1.0.1.msi").apply { mkdirs() }
        updates.installOffer()
        assertEquals(UpdateMessages.DOWNLOAD_FAILED, updates.state.value.problem)
        assertTrue(taken.isDirectory)
        assertEquals(listOf("update-MealPlanner-1.0.1.msi"), folder.list()!!.toList())
        assertEquals(emptyList<File>(), installer.files)
        assertEquals(0, msiRequests())
    }

    @Test
    fun aRedirectedUpdatesFolderIsRefused() = runBlocking {
        fixture.publish()
        val elsewhere = File(dir, "elsewhere").apply { mkdirs() }
        val stale = File(elsewhere, "update-MealPlanner-0.9.0.msi").apply { writeBytes(byteArrayOf(1)) }
        check(stale.setLastModified(now - 30 * UpdateSchedule.DAY_MILLIS))
        assumeTrue("this machine can make neither a link nor a junction", TestLinks.make(folder, elsewhere))
        val updates = updates()
        assertFalse(updates.state.value.offered)
        assertEquals(UpdateMessages.FOLDER_REDIRECTED, updates.unavailableReason)
        updates.checkAtLaunch()
        updates.check(manual = true)
        updates.installOffer()
        assertTrue(stale.exists())
        assertEquals(emptyList<String>(), releases.requests)
        assertEquals(emptyList<File>(), installer.files)
    }

    @Test
    fun aParentReachedThroughALinkIsAllowed() = runBlocking {
        // A redirected profile or %LOCALAPPDATA%: only the updates folder itself is judged.
        fixture.publish()
        val realParent = File(dir, "real-parent").apply { mkdirs() }
        val linkedParent = File(dir, "linked-parent")
        assumeTrue("this machine can make neither a link nor a junction", TestLinks.make(linkedParent, realParent))
        val inside = File(linkedParent, "updates")
        assertFalse(Updates.redirected(inside))
        inside.mkdirs()
        assertFalse(Updates.redirected(inside))
        val stale = File(inside, "update-MealPlanner-0.9.0.msi").apply { writeBytes(byteArrayOf(1)) }
        check(stale.setLastModified(now - 30 * UpdateSchedule.DAY_MILLIS))
        val updates = Updates(RunningApp.Desktop("1.0.0"), fixture.keys.public, installer, settings, inside, releases.http(), releases.endpoints, clock = { now })
        assertNull(updates.unavailableReason)
        assertTrue(updates.state.value.offered)
        updates.checkAtLaunch()
        assertFalse(stale.exists())
        updates.check(manual = true)
        updates.installOffer()
        assertNull(updates.state.value.problem)
        assertArrayEquals(fixture.msi, installer.installed.single())
        assertEquals(listOf("update-MealPlanner-1.0.1.msi"), File(realParent, "updates").list()!!.toList())
    }

    @Test
    fun theUpdatesFolderItselfAsALinkIsRedirected() {
        val elsewhere = File(dir, "elsewhere").apply { mkdirs() }
        assertFalse(Updates.redirected(folder))
        assumeTrue("this machine can make neither a link nor a junction", TestLinks.make(folder, elsewhere))
        assertTrue(Updates.redirected(folder))
        // Also through a parent that is itself a link: the folder is still judged on its own.
        val realParent = File(dir, "real-parent").apply { mkdirs() }
        val linkedParent = File(dir, "linked-parent")
        assumeTrue(TestLinks.make(linkedParent, realParent))
        val other = File(dir, "other").apply { mkdirs() }
        assumeTrue(TestLinks.make(File(realParent, "updates"), other))
        assertTrue(Updates.redirected(File(linkedParent, "updates")))
        // A plain file named updates isn't a folder at all.
        val plain = File(other, "updates").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(Updates.redirected(plain))
    }

    @Test
    fun aFolderRedirectedAfterStartIsNeitherCleanedNorUsed() = runBlocking {
        fixture.publish()
        val updates = offered()
        val elsewhere = File(dir, "elsewhere").apply { mkdirs() }
        val stale = File(elsewhere, "update-MealPlanner-0.9.0.msi").apply { writeBytes(byteArrayOf(1)) }
        check(stale.setLastModified(now - 30 * UpdateSchedule.DAY_MILLIS))
        assumeTrue("this machine can make neither a link nor a junction", TestLinks.make(folder, elsewhere))
        updates.installOffer()
        assertEquals(UpdateMessages.FOLDER_REDIRECTED, updates.state.value.problem)
        assertTrue(stale.exists())
        assertEquals(listOf("update-MealPlanner-0.9.0.msi"), elsewhere.list()!!.toList())
        assertEquals(0, msiRequests())
        assertEquals(emptyList<File>(), installer.files)
    }

    @Test
    fun aFolderNotNamedUpdatesIsRefused() {
        for (name in listOf("Meal Planner", "Updates", "updates2", "")) {
            assertThrows(name, IllegalArgumentException::class.java) {
                Updates(RunningApp.Desktop("1.0.0"), fixture.keys.public, installer, settings, File(dir, name), releases.http(), releases.endpoints)
            }
        }
    }

    private companion object {
        const val START = 1_000_000_000_000L
    }
}
