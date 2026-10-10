package com.naeblis11.mealplanner.app

import android.content.SharedPreferences
import com.naeblis11.mealplanner.calendar.CalendarChoice

class SharedPreferencesStore(private val prefs: SharedPreferences) : SettingsStore {
    override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun put(values: Map<String, Any>) {
        val editor = prefs.edit()
        values.forEach { (key, value) ->
            when (value) {
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                else -> throw IllegalArgumentException("Unsupported setting type for $key: ${value::class.simpleName}")
            }
        }
        editor.apply()
    }
}

/** Keeps CalendarChoice(prefs) working on Android. */
fun CalendarChoice(prefs: SharedPreferences): CalendarChoice = CalendarChoice(SharedPreferencesStore(prefs))
