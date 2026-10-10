package com.naeblis11.mealplanner.calendar

import com.naeblis11.mealplanner.app.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The calendar meals are sent to, by id, with the label to show for it. */
data class ChosenCalendar(val id: Long, val name: String)

/**
 * The user's calendar choice, kept in the platform's settings store: a setting, not library data, so
 * it is neither in Room nor in a backup. A calendar that disappears stays chosen until
 * another is picked (it may come back after a sync).
 */
class CalendarChoice(private val store: SettingsStore) {
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
        store.put(mapOf(KEY_ID to calendar.id, KEY_NAME to calendar.label))
        _chosen.value = ChosenCalendar(calendar.id, calendar.label)
        _picks.update { it + 1 }
    }

    private fun read(): ChosenCalendar? =
        store.getLong(KEY_ID)?.let { ChosenCalendar(it, store.getString(KEY_NAME) ?: "Calendar") }

    companion object {
        const val FILE = "calendar"
        private const val KEY_ID = "calendar_id"
        private const val KEY_NAME = "calendar_name"
    }
}
