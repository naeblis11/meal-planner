package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.calendar.CalendarGateway
import com.naeblis11.mealplanner.calendar.CalendarInfo
import com.naeblis11.mealplanner.calendar.MealEvent

/** Until the Google Calendar client arrives (phase 1, plan 5): no calendars to send to. */
class NoCalendarGateway : CalendarGateway {
    override fun hasPermission(): Boolean = true

    override fun writableCalendars(): List<CalendarInfo> = emptyList()

    override fun calendar(id: Long): CalendarInfo? = null

    override fun eventExists(calendarId: Long, eventId: Long): Boolean = false

    override fun insertEvent(calendarId: Long, event: MealEvent): Long = unsupported()

    override fun updateEvent(calendarId: Long, eventId: Long, event: MealEvent): Boolean = unsupported()

    override fun deleteEvent(calendarId: Long, eventId: Long): Boolean = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("Calendar sending isn't available on the desktop yet.")
}
