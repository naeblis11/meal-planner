package com.naeblis11.mealplanner.app

import java.util.prefs.Preferences

/** Settings in the user's Java preferences (the registry under HKCU on Windows). */
class PreferencesStore(private val node: Preferences) : SettingsStore {
    override fun getLong(key: String): Long? = node.get(key, null)?.toLongOrNull()

    override fun getString(key: String): String? = node.get(key, null)

    override fun put(values: Map<String, Any>) {
        values.forEach { (key, value) ->
            require(value is Long || value is String) { "Unsupported setting type for $key: ${value::class.simpleName}" }
        }
        values.forEach { (key, value) -> node.put(key, value.toString()) }
        node.flush()
    }
}
