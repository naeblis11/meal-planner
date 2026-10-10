package com.naeblis11.mealplanner.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopVersionsTest {
    @Test
    fun eachPartIsComparedAsANumber() {
        assertTrue(DesktopVersions.isNewer("1.0.10", "1.0.9"))
        assertTrue(DesktopVersions.isNewer("1.10.0", "1.9.9"))
        assertTrue(DesktopVersions.isNewer("2.0.0", "1.255.65535"))
        assertFalse(DesktopVersions.isNewer("1.0.0", "1.0.0"))
        assertFalse(DesktopVersions.isNewer("1.0.9", "1.0.10"))
    }

    @Test
    fun onlyWhatAnMsiCanCarryIsAVersion() {
        // The MSI's own rule (plan 7, build.gradle.kts): MAJOR 1-255, MINOR 0-255, BUILD 0-65535.
        assertEquals(listOf(1, 2, 3), DesktopVersions.parse("1.2.3"))
        for (bad in listOf("1.2", "1.2.3.4", "0.1.0", "256.0.0", "1.256.0", "1.0.65536", "v1.0.0", "1.0.0-beta", "", "dev")) {
            assertNull(bad, DesktopVersions.parse(bad))
        }
        assertFalse(DesktopVersions.isNewer("1.0.1", "dev"))
        assertFalse(DesktopVersions.isNewer("dev", "1.0.0"))
    }
}
