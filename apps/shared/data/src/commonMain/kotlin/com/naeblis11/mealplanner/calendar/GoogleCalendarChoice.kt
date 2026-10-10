package com.naeblis11.mealplanner.calendar

import com.naeblis11.mealplanner.app.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The Google calendar meals are sent to: its id, and the name Settings shows for it. */
data class ChosenGoogleCalendar(val id: String, val name: String)

/**
 * The chosen calendar, the Google calendar meals are sent to, kept in the platform's settings store: a setting of this
 * PC, not library data. A SettingsStore has no remove, so a cleared choice is written as empty strings.
 */
class GoogleCalendarChoice(private val store: SettingsStore) {
    private val _chosen = MutableStateFlow(read())
    val chosen: StateFlow<ChosenGoogleCalendar?> = _chosen.asStateFlow()
    private val _picks = MutableStateFlow(0)

    /**
     * Calendars picked since the app started, the same one again included, and fresh sign-ins ([signedIn]): the
     * Calendar's "Settings" banner goes on a pick.
     */
    val picks: StateFlow<Int> = _picks.asStateFlow()

    fun choose(id: String, name: String) {
        store.put(mapOf(KEY_ID to id, KEY_NAME to name))
        _chosen.value = ChosenGoogleCalendar(id, name)
        _picks.update { it + 1 }
    }

    /**
     * A fresh sign-in counts as a pick (P5-T6a): the Calendar's "Sign in to Google again" banner goes with it, even when
     * the calendar already chosen stays.
     */
    fun signedIn() {
        _picks.update { it + 1 }
    }

    /** The chosen calendar's name as Google lists it now (it may have been renamed in Google since it was chosen). */
    fun rename(name: String) {
        val current = _chosen.value ?: return
        if (current.name == name) return
        store.put(mapOf(KEY_NAME to name))
        _chosen.value = current.copy(name = name)
    }

    fun clear() {
        store.put(mapOf(KEY_ID to "", KEY_NAME to ""))
        _chosen.value = null
    }

    private fun read(): ChosenGoogleCalendar? {
        val id = store.getString(KEY_ID)?.takeIf { it.isNotEmpty() } ?: return null
        return ChosenGoogleCalendar(id, store.getString(KEY_NAME)?.takeIf { it.isNotEmpty() } ?: id)
    }

    companion object {
        const val KEY_ID = "google_calendar_id"
        const val KEY_NAME = "google_calendar_name"
    }
}
