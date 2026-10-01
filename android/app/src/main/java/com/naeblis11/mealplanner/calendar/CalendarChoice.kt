package com.naeblis11.mealplanner.calendar

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The calendar meals are sent to, by id, with the label to show for it. */
data class ChosenCalendar(val id: Long, val name: String)

/**
 * The user's calendar choice, kept in SharedPreferences: a setting, not library data, so
 * it is neither in Room nor in a backup. A calendar that disappears stays chosen until
 * another is picked (it may come back after a sync).
 */
class CalendarChoice(private val prefs: SharedPreferences) {
    private val _chosen = MutableStateFlow(read())
    val chosen: StateFlow<ChosenCalendar?> = _chosen.asStateFlow()
    private val _picks = MutableStateFlow(0)

    /**
     * How many times a calendar has been picked since the app started. [chosen] does not
     * change when the same calendar is picked again (one that had gone and came back), but
     * this does, so the Calendar screen can drop a "choose a calendar" banner either way.
     */
    val picks: StateFlow<Int> = _picks.asStateFlow()

    fun choose(calendar: CalendarInfo) {
        prefs.edit().putLong(KEY_ID, calendar.id).putString(KEY_NAME, calendar.label).apply()
        _chosen.value = ChosenCalendar(calendar.id, calendar.label)
        _picks.update { it + 1 }
    }

    private fun read(): ChosenCalendar? =
        if (prefs.contains(KEY_ID)) ChosenCalendar(prefs.getLong(KEY_ID, 0L), prefs.getString(KEY_NAME, null) ?: "Calendar") else null

    companion object {
        const val FILE = "calendar"
        private const val KEY_ID = "calendar_id"
        private const val KEY_NAME = "calendar_name"
    }
}
