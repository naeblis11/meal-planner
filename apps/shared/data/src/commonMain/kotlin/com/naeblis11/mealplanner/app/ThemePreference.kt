package com.naeblis11.mealplanner.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings > Appearance (owner, 2026-10-12): follow the system's light or dark setting, or always one of them. */
enum class ThemeMode(val stored: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    /** Whether the app is dark in this mode, given what the system is set to. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** A missing or unknown stored value follows the system. */
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.stored == value } ?: SYSTEM
    }
}

/** The chosen [ThemeMode], kept in [settings] under [KEY] so a restart keeps it; the app recolours as it changes. */
class ThemePreference(private val settings: SettingsStore) {
    private val _mode = MutableStateFlow(ThemeMode.fromStored(settings.getString(KEY)))
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    fun set(mode: ThemeMode) {
        settings.put(mapOf(KEY to mode.stored))
        _mode.value = mode
    }

    companion object {
        const val KEY = "theme_mode"
    }
}
