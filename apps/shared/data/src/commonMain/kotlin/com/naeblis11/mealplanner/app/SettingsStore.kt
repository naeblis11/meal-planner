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

/** Settings that last only as long as the process: tests, and a platform that keeps none. */
class MemorySettings : SettingsStore {
    private val values = java.util.concurrent.ConcurrentHashMap<String, Any>()

    override fun getLong(key: String): Long? = values[key] as? Long

    override fun getString(key: String): String? = values[key] as? String

    override fun put(values: Map<String, Any>) {
        this.values.putAll(values)
    }
}
