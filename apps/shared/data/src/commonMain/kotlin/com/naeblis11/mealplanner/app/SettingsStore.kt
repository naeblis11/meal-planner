package com.naeblis11.mealplanner.app

/**
 * The few small settings the app keeps outside its library (SharedPreferences on Android,
 * java.util.prefs on the desktop). Values are Long or String.
 */
interface SettingsStore {
    fun getLong(key: String): Long?

    fun getString(key: String): String?

    /** Writes all of [values] together. */
    fun put(values: Map<String, Any>)
}
