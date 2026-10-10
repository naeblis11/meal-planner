package com.naeblis11.mealplanner.calendar

import com.naeblis11.mealplanner.app.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Google calendar meals go to: a setting of this PC, kept in the platform's settings store. */
class GoogleCalendarChoiceTest {
    private class MemoryStore : SettingsStore {
        val values = HashMap<String, Any>()

        override fun getLong(key: String): Long? = values[key] as? Long

        override fun getString(key: String): String? = values[key] as? String

        override fun put(values: Map<String, Any>) {
            this.values.putAll(values)
        }
    }

    @Test
    fun aChoiceIsKeptAndEveryPickCounted() {
        val store = MemoryStore()
        val choice = GoogleCalendarChoice(store)
        assertNull(choice.chosen.value)
        choice.choose("family@group.calendar.google.com", "Family")
        choice.choose("family@group.calendar.google.com", "Family")
        assertEquals(2, choice.picks.value)
        assertEquals(ChosenGoogleCalendar("family@group.calendar.google.com", "Family"), GoogleCalendarChoice(store).chosen.value)
    }

    @Test
    fun aFreshSignInCountsAsAPickAndKeepsTheChoice() {
        // P5-T6a: the Calendar's "Sign in to Google again" banner goes with a sign-in to the calendar already chosen.
        val store = MemoryStore()
        val choice = GoogleCalendarChoice(store)
        choice.choose("family@group.calendar.google.com", "Family")
        choice.signedIn()
        assertEquals(2, choice.picks.value)
        assertEquals(ChosenGoogleCalendar("family@group.calendar.google.com", "Family"), choice.chosen.value)
    }

    @Test
    fun aChoiceIsRenamedAndCleared() {
        val store = MemoryStore()
        val choice = GoogleCalendarChoice(store)
        // Chosen under its id alone; Google's list names it later.
        choice.choose("family@group.calendar.google.com", "family@group.calendar.google.com")
        choice.rename("Family")
        assertEquals("Family", GoogleCalendarChoice(store).chosen.value!!.name)
        choice.clear()
        assertNull(choice.chosen.value)
        assertNull(GoogleCalendarChoice(store).chosen.value)
    }
}
