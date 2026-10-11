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

/**
 * The spec's privacy promise, as amended by "Updates" (plan 8) and LAN sync (stage 2c): the two calendar permissions for
 * "Send this week", INTERNET and REQUEST_INSTALL_PACKAGES for the update check, its install and the sync with the paired
 * PC, and ACCESS_NETWORK_STATE for Join a PC's Wi-Fi check. No camera, storage or anything else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManifestPermissionsTest {
    @Test
    fun theOnlySystemPermissionsAreCalendarsUpdatesAndTheWifiCheck() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val system = info.requestedPermissions.orEmpty().filter { it.startsWith("android.permission.") }.toSet()
        assertEquals(
            setOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
                Manifest.permission.INTERNET,
                Manifest.permission.REQUEST_INSTALL_PACKAGES,
                Manifest.permission.ACCESS_NETWORK_STATE,
            ),
            system,
        )
    }
}
