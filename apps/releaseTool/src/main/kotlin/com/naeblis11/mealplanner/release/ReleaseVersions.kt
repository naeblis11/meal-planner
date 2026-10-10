package com.naeblis11.mealplanner.release

import java.io.File
import java.util.Properties

/**
 * Each app's version, read from apps/gradle.properties: the same keys the builds read (desktopApp's
 * mealplanner.desktopVersion, plan 7; androidApp's mealplanner.androidVersionCode and androidVersionName, plan 8), so
 * latest.json names exactly what was built. Their rules are ReleaseManifest's, checked when the list is made.
 */
data class ReleaseVersions(val desktop: String, val androidName: String, val androidCode: Long) {
    companion object {
        const val DESKTOP_KEY = "mealplanner.desktopVersion"
        const val ANDROID_NAME_KEY = "mealplanner.androidVersionName"
        const val ANDROID_CODE_KEY = "mealplanner.androidVersionCode"

        fun read(file: File): ReleaseVersions {
            if (!file.isFile) throw ReleaseToolException("There is no $file.")
            val props = Properties().apply { file.reader(Charsets.UTF_8).use { load(it) } }
            fun value(key: String): String =
                props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: throw ReleaseToolException("$file has no $key.")
            val code = value(ANDROID_CODE_KEY).toLongOrNull() ?: throw ReleaseToolException("$ANDROID_CODE_KEY in $file isn't a whole number.")
            return ReleaseVersions(value(DESKTOP_KEY), value(ANDROID_NAME_KEY), code)
        }
    }
}
