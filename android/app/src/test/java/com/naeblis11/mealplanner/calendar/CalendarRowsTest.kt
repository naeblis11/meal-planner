package com.naeblis11.mealplanner.calendar

import android.provider.CalendarContract.Events
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarRowsTest {
    private val event = MealEvent("Dinner: Soup", "Added by Meal Planner.", 1_000L, 3_601_000L, "America/Toronto")

    @Test
    fun anInsertNamesTheCalendarAndMarksTheMealFree() {
        val values = CalendarRows.insertValues(7L, event)
        assertEquals(7L, values.getAsLong(Events.CALENDAR_ID))
        assertEquals("Dinner: Soup", values.getAsString(Events.TITLE))
        assertEquals("Added by Meal Planner.", values.getAsString(Events.DESCRIPTION))
        assertEquals(1_000L, values.getAsLong(Events.DTSTART))
        assertEquals(3_601_000L, values.getAsLong(Events.DTEND))
        assertEquals("America/Toronto", values.getAsString(Events.EVENT_TIMEZONE))
        assertEquals(Events.AVAILABILITY_FREE, values.getAsInteger(Events.AVAILABILITY))
        assertEquals(7, values.size())
    }

    @Test
    fun anUpdateNeverMovesAnEventToAnotherCalendar() {
        assertFalse(CalendarRows.updateValues(event).containsKey(Events.CALENDAR_ID))
    }

    @Test
    fun selectionsOnlyMatchOurEventInOurCalendar() {
        assertEquals("_id = ? AND calendar_id = ? AND description LIKE ?", CalendarRows.OUR_EVENT)
        assertEquals("_id = ? AND calendar_id = ? AND description LIKE ? AND deleted = 0", CalendarRows.LIVE_EVENT)
        assertArrayEquals(arrayOf("42", "7", "%Added by Meal Planner."), CalendarRows.eventArgs(calendarId = 7L, eventId = 42L))
        assertEquals("calendar_access_level >= ? AND visible = 1 AND sync_events = 1", CalendarRows.WRITABLE)
        assertArrayEquals(arrayOf("500"), CalendarRows.writableArgs())
    }

    @Test
    fun theOwnershipMarkerEndsEveryDescriptionAndHasNoLikeWildcards() {
        // The selection matches it with LIKE '%<marker>'; a % or _ in it would need escaping.
        assertFalse(MealEvents.MARKER.contains('%') || MealEvents.MARKER.contains('_'))
        assertTrue(MealEvents.description(null, emptyList()).endsWith(MealEvents.MARKER))
        assertTrue(MealEvents.description("4", listOf(EventIngredient("Rice", "1", "cup", null))).endsWith("\n" + MealEvents.MARKER))
    }

    @Test
    fun aProviderThatCannotAnswerIsAnErrorNotAnEmptyAnswer() {
        // Robolectric registers no calendar provider, so every query returns null.
        val gateway = AndroidCalendarGateway(ApplicationProvider.getApplicationContext<Context>())
        assertThrows(IllegalStateException::class.java) { gateway.eventExists(1L, 2L) }
        assertThrows(IllegalStateException::class.java) { gateway.writableCalendars() }
        assertThrows(IllegalStateException::class.java) { gateway.calendar(1L) }
    }
}
