package com.naeblis11.mealplanner.update

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.R
import com.naeblis11.mealplanner.app.MealPlannerApplication
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** Spec "Updates": the verified APK goes to Android's package installer; no test starts it (Robolectric records the intent). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidUpdateInstallerTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    // FileProvider's root check expects '/', so on a Windows test host it maps nothing; the tests stand in for it with
    // the URI it gives on a phone, and record what it was asked (theRealProviderMapsTheUpdatesFolder runs it elsewhere).
    private val asked = mutableListOf<File>()
    private val foreground = (context as MealPlannerApplication).foreground
    private val installer = AndroidUpdateInstaller(context, foreground::any, uriFor = { file ->
        asked += file
        providerUri(context, file.name)
    })

    @Test
    fun withoutPermissionItOpensAndroidsInstallUnknownAppsPage() {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        assertFalse(installer.canInstall())
        installer.openInstallPermission()
        val started = shadowOf(context).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, started.action)
        assertEquals(Uri.parse("package:${context.packageName}"), started.data)
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        assertTrue(installer.canInstall())
    }

    @Test
    fun itHandsTheApkToThePackageInstallerThroughItsOwnProvider() {
        val folder = AndroidUpdateInstaller.folder(context).apply { mkdirs() }
        val apk = File(folder, "MealPlanner-1.0.1.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        // A downloaded app that claims APKs comes first in the list; the intent still names the system installer.
        addApkHandler(context, providerUri(context, apk.name), OTHER_INSTALLER, system = false)
        addApkHandler(context, providerUri(context, apk.name), SYSTEM_INSTALLER, system = true)
        installer.install(apk)
        assertEquals(listOf(apk), asked)
        val started = shadowOf(context).nextStartedActivity
        assertEquals(SYSTEM_INSTALLER, started.`package`)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(AndroidUpdateInstaller.APK_TYPE, started.type)
        assertEquals("content", started.data!!.scheme)
        assertEquals("${context.packageName}.files", started.data!!.authority)
        assertEquals("/updates/MealPlanner-1.0.1.apk", started.data!!.path)
        assertTrue((started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertFalse(installer.closesApp)
    }

    @Test
    fun withoutASystemInstallerNothingIsStartedOrGranted() {
        val folder = AndroidUpdateInstaller.folder(context).apply { mkdirs() }
        val apk = File(folder, "MealPlanner-1.0.1.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        // None at all, then only a downloaded app that claims APKs: both refused before any intent (or grant) exists.
        assertThrows(IOException::class.java) { installer.install(apk) }
        addApkHandler(context, providerUri(context, apk.name), OTHER_INSTALLER, system = false)
        assertThrows(IOException::class.java) { installer.install(apk) }
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun onlyTheUpdatesFolderIsShared() {
        val folder = AndroidUpdateInstaller.folder(context).apply { mkdirs() }
        val elsewhere = listOf(
            File(context.filesDir, "MealPlanner-1.0.1.apk"),
            // The cache itself, beside the updates folder, and a folder inside it aren't shared either.
            File(context.cacheDir, "MealPlanner-1.0.1.apk"),
            File(File(folder, "inner").apply { mkdirs() }, "MealPlanner-1.0.1.apk"),
            File(context.cacheDir, "camera/MealPlanner-1.0.1.apk").apply { parentFile!!.mkdirs() },
        )
        for (file in elsewhere) {
            file.writeBytes(byteArrayOf(1))
            assertThrows(IllegalArgumentException::class.java) { installer.install(file) }
        }
        assertThrows(IllegalArgumentException::class.java) { installer.install(File(folder, "../MealPlanner-1.0.1.apk")) }
        assertEquals(emptyList<File>(), asked)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test
    fun theProviderSharesOnlyTheCameraAndTheUpdatesFolder() {
        // A narrow paths file: never the whole cache or files folder, nothing external.
        val provider = context.packageManager.resolveContentProvider(AndroidUpdateInstaller.authority(context), PackageManager.GET_META_DATA)!!
        assertEquals("androidx.core.content.FileProvider", provider.name)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertEquals(R.xml.file_paths, provider.metaData.getInt("android.support.FILE_PROVIDER_PATHS"))
        val paths = mutableListOf<String>()
        context.resources.getXml(R.xml.file_paths).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name != "paths") {
                    paths += "${xml.name} ${xml.getAttributeValue(null, "name")} ${xml.getAttributeValue(null, "path")}"
                }
            }
        }
        assertEquals(listOf("cache-path camera camera/", "cache-path updates updates/"), paths)
    }

    @Test
    fun theRealProviderMapsTheUpdatesFolder() {
        assumeTrue("FileProvider's root check needs a '/' file system", File.separatorChar == '/')
        val real = AndroidUpdateInstaller(context, foreground::any)
        val folder = AndroidUpdateInstaller.folder(context).apply { mkdirs() }
        addApkHandler(context, providerUri(context, "MealPlanner-1.0.1.apk"), SYSTEM_INSTALLER, system = true)
        real.install(File(folder, "MealPlanner-1.0.1.apk").apply { writeBytes(byteArrayOf(1)) })
        assertEquals("/updates/MealPlanner-1.0.1.apk", shadowOf(context).nextStartedActivity.data!!.path)
    }

    @Test
    fun theDownloadsFolderIsTheAppsPrivateCacheNamedUpdates() {
        assertEquals(File(context.cacheDir, "updates"), AndroidUpdateInstaller.folder(context))
        assertEquals(Updates.DIR_NAME, AndroidUpdateInstaller.FOLDER)
    }

    @Test
    fun theInstallerStartsOnlyWhileAnActivityIsInTheForeground() {
        // P8-PF11: Android blocks an activity started from the background without saying so.
        assertFalse(installer.canStartNow())
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        assertTrue(installer.canStartNow())
        activity.pause()
        assertFalse(installer.canStartNow())
        activity.resume()
        assertTrue(installer.canStartNow())
        activity.pause().stop().destroy()
        assertFalse(installer.canStartNow())
    }
}
