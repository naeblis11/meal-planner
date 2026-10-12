package com.naeblis11.mealplanner.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings > Appearance (owner, 2026-10-12): kept across a restart, and Match system is the default. */
class ThemePreferenceTest {
    @Test
    fun matchesTheSystemUntilSomethingIsChosen() {
        assertEquals(ThemeMode.SYSTEM, ThemePreference(MemorySettings()).mode.value)
    }

    @Test
    fun aChoiceIsKeptAcrossARestart() {
        val settings = MemorySettings()
        val first = ThemePreference(settings)
        first.set(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, first.mode.value)
        assertEquals("dark", settings.getString(ThemePreference.KEY))
        assertEquals(ThemeMode.DARK, ThemePreference(settings).mode.value)

        first.set(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, ThemePreference(settings).mode.value)
    }

    @Test
    fun anUnknownStoredValueMatchesTheSystem() {
        val settings = MemorySettings().apply { put(mapOf(ThemePreference.KEY to "sepia")) }
        assertEquals(ThemeMode.SYSTEM, ThemePreference(settings).mode.value)
    }

    @Test
    fun onlyMatchSystemFollowsTheSystem() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
    }
}
