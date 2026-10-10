package com.naeblis11.mealplanner.release

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionsTest {
    private val apps = File(System.getProperty("appsDir") ?: error("appsDir is not set; run through Gradle"))
    private val dir: File = Files.createTempDirectory("mp-release-versions").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun itReadsTheThreeVersions() {
        val file = File(dir, "gradle.properties")
        file.writeText(
            "org.gradle.jvmargs=-Xmx2048m\nmealplanner.desktopVersion=1.2.3\nmealplanner.androidVersionCode=7\nmealplanner.androidVersionName=1.2.0\n",
        )
        assertEquals(ReleaseVersions("1.2.3", "1.2.0", 7), ReleaseVersions.read(file))
    }

    @Test
    fun aMissingOrBadVersionStops() {
        val missing = File(dir, "a.properties").apply { writeText("mealplanner.desktopVersion=1.2.3\nmealplanner.androidVersionName=1.2.0\n") }
        assertThrows(ReleaseToolException::class.java) { ReleaseVersions.read(missing) }
        val bad = File(dir, "b.properties").apply {
            writeText("mealplanner.desktopVersion=1.2.3\nmealplanner.androidVersionCode=seven\nmealplanner.androidVersionName=1.2.0\n")
        }
        assertThrows(ReleaseToolException::class.java) { ReleaseVersions.read(bad) }
        assertThrows(ReleaseToolException::class.java) { ReleaseVersions.read(File(dir, "none.properties")) }
    }

    @Test
    fun latestJsonTakesItsVersionsFromWhereTheBuildsDo() {
        // The builds read these keys from apps/gradle.properties (AppVersionTest shows the APK carries them; plan 7's
        // checkPackagingConfig, the MSI); the release reads the same file, so latest.json can't name another version.
        val android = File(apps, "androidApp/build.gradle.kts").readText()
        val desktop = File(apps, "desktopApp/build.gradle.kts").readText()
        assertTrue(android.contains("gradleProperty(\"${ReleaseVersions.ANDROID_CODE_KEY}\")"))
        assertTrue(android.contains("gradleProperty(\"${ReleaseVersions.ANDROID_NAME_KEY}\")"))
        assertTrue(desktop.contains("gradleProperty(\"${ReleaseVersions.DESKTOP_KEY}\")"))
        assertFalse("androidApp/build.gradle.kts sets a version of its own", Regex("""version(Code|Name)\s*=\s*["0-9]""").containsMatchIn(android))
        assertTrue(ReleaseVersions.read(File(apps, "gradle.properties")).androidCode >= 1)
    }
}
