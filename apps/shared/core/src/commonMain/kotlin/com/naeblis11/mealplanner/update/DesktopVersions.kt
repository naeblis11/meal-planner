package com.naeblis11.mealplanner.update

/**
 * The Windows app's version, as its MSI carries it (plan 7's rule in desktopApp/build.gradle.kts): MAJOR.MINOR.BUILD,
 * MAJOR 1 to 255, MINOR 0 to 255, BUILD 0 to 65535. Windows only installs a newer MSI over an older one, so the update
 * check compares the same way, part by part, as numbers.
 */
object DesktopVersions {
    // ASCII digits by intent: a version is the installer's and the manifest's, never a digit from another script.
    private val PATTERN = Regex("""([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,5})""")

    /** The three parts, or null when [version] isn't one an MSI can carry ("dev", "1.0", "0.1.0"). */
    fun parse(version: String): List<Int>? {
        val parts = PATTERN.matchEntire(version)?.groupValues?.drop(1)?.map(String::toInt) ?: return null
        return parts.takeIf { it[0] in 1..255 && it[1] <= 255 && it[2] <= 65535 }
    }

    /** True only when both are versions and [candidate] is higher. */
    fun isNewer(candidate: String, running: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(running) ?: return false
        for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }
}
