package com.naeblis11.mealplanner.calendar

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.naeblis11.mealplanner.app.CalendarChoice

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarChoiceTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("calendar-choice-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test
    fun aChoiceIsKeptForNextTime() {
        assertEquals(null, CalendarChoice(prefs).chosen.value)
        val choice = CalendarChoice(prefs)
        choice.choose(CalendarInfo(3L, "Family", "family@example.com"))
        assertEquals(ChosenCalendar(3L, "Family (family@example.com)"), choice.chosen.value)
        assertEquals(ChosenCalendar(3L, "Family (family@example.com)"), CalendarChoice(prefs).chosen.value)
    }

    @Test
    fun everyPickIsCountedEvenOfTheSameCalendar() {
        val choice = CalendarChoice(prefs)
        assertEquals(0, choice.picks.value)
        choice.choose(CalendarInfo(3L, "Family", ""))
        choice.choose(CalendarInfo(3L, "Family", ""))
        assertEquals(2, choice.picks.value)
    }

    @Test
    fun aLabelNamesTheAccountOnlyWhenThatAddsSomething() {
        assertEquals("Family (family@example.com)", CalendarInfo(1L, "Family", "family@example.com").label)
        assertEquals("me@example.com", CalendarInfo(1L, "me@example.com", "me@example.com").label)
        assertEquals("Holidays", CalendarInfo(1L, "Holidays", "").label)
    }

    @Test
    fun readsWhatAnExistingPhoneHasStored() {
        // The file and keys the app wrote before the settings store existed; an update must keep the choice.
        val stored = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(CalendarChoice.FILE, Context.MODE_PRIVATE)
        stored.edit().clear().putLong("calendar_id", 3L).putString("calendar_name", "Family (family@example.com)").commit()
        assertEquals(ChosenCalendar(3L, "Family (family@example.com)"), CalendarChoice(stored).chosen.value)
    }

    @Test
    fun aStoredIdWithoutANameFallsBackToCalendar() {
        val stored = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(CalendarChoice.FILE, Context.MODE_PRIVATE)
        stored.edit().clear().putLong("calendar_id", 3L).commit()
        assertEquals(ChosenCalendar(3L, "Calendar"), CalendarChoice(stored).chosen.value)
    }
}
