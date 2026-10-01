package com.naeblis11.mealplanner.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The spec's privacy promise: the only system permissions are the two calendar ones (no INTERNET, camera or storage). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestPermissionsTest {
    @Test
    fun theOnlySystemPermissionsAreReadingAndWritingCalendars() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val system = info.requestedPermissions.orEmpty().filter { it.startsWith("android.permission.") }.toSet()
        assertEquals(setOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), system)
    }
}
