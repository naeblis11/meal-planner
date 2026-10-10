package com.naeblis11.mealplanner.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.calendar.AndroidCalendarGateway
import com.naeblis11.mealplanner.calendar.FakeCalendarGateway
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppContainerTest {
    @Test
    fun startingDeletesLeftoverImportStagingButNotTheCamerasFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val leftover = File(context.cacheDir, "import/123/photo.jpg").apply { parentFile!!.mkdirs(); writeText("x") }
        val capture = File(context.cacheDir, "camera/capture.jpg").apply { parentFile!!.mkdirs(); writeText("y") }

        val container = AppContainer(context)

        assertFalse(leftover.exists())
        assertEquals("y", capture.readText())
        // A new import can still stage after the clean-up.
        assertTrue(container.newImportStagingDir().isDirectory)
    }

    @Test
    fun everyImportStagingFolderIsNewAndEmpty() {
        // The desktop's extension can stage two recipes at the same moment: each must get a folder of its own.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = AppContainer(context)
        val made = List(200) { container.newImportStagingDir() }
        assertEquals(200, made.toSet().size)
        for (dir in made) {
            assertTrue(dir.isDirectory)
            assertEquals(0, dir.list()!!.size)
            assertEquals(File(context.cacheDir, "import").canonicalFile, dir.parentFile!!.canonicalFile)
        }
    }

    @Test
    fun startingDeletesStrayPhotoCopiesButKeepsThePhotos() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val images = File(context.filesDir, "images").apply { mkdirs() }
        val photo = File(images, "u.jpg").apply { writeText("p") }
        val earlier = System.currentTimeMillis() - 60_000
        val backup = File(images, "u.jpg.0.bak").apply { writeText("old"); setLastModified(earlier) }
        val temp = File(images, "u.jpg.tmp").apply { writeText("half"); setLastModified(earlier) }

        val container = AppContainer(context)
        container.startupCleanup.join()

        assertEquals("p", photo.readText())
        assertFalse(backup.exists())
        assertFalse(temp.exists())
    }

    @Test
    fun aGivenCalendarGatewayIsUsedAndTheRealOneOtherwise() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fake = FakeCalendarGateway()
        assertSame(fake, AppContainer(context, fake).calendarGateway)
        assertTrue(AppContainer(context).calendarGateway is AndroidCalendarGateway)
    }

    @Test
    fun theCalendarSyncIsOneInstance() {
        val container = AppContainer(ApplicationProvider.getApplicationContext<Context>(), FakeCalendarGateway())
        assertSame(container.calendarSync, container.calendarSync)
    }
}
