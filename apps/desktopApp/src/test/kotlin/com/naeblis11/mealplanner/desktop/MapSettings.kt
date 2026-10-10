package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.app.SettingsStore
import java.util.concurrent.ConcurrentHashMap

/** Settings in memory, so a test never writes the registry (java.util.prefs is the registry on Windows). */
class MapSettings : SettingsStore {
    val values = ConcurrentHashMap<String, Any>()

    override fun getLong(key: String): Long? = values[key] as? Long

    override fun getString(key: String): String? = values[key] as? String

    override fun put(values: Map<String, Any>) {
        this.values.putAll(values)
    }
}
