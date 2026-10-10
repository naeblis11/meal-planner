package com.naeblis11.mealplanner.app

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan 8: the APK carries exactly the version in apps/gradle.properties, where the release reads it for latest.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppVersionTest {
    @Test
    fun theBuildsVersionIsTheOneInGradleProperties() {
        val props = Properties().apply {
            File(System.getProperty("gradleProperties") ?: error("gradleProperties is not set; run through Gradle")).reader(Charsets.UTF_8).use { load(it) }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals(props.getProperty("mealplanner.androidVersionCode").toLong(), PackageInfoCompat.getLongVersionCode(info))
        // Debug builds add "-debug" (versionNameSuffix); the release's name is the property's.
        assertEquals(props.getProperty("mealplanner.androidVersionName") + "-debug", info.versionName)
    }
}
