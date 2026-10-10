package com.naeblis11.mealplanner.calendar

/** A calendar on the phone that the app may add events to. */
data class CalendarInfo(val id: Long, val displayName: String, val accountName: String) {
    /** "Family (family@example.com)"; just the name when the account adds nothing. */
    val label: String
        get() = if (accountName.isBlank() || accountName == displayName) displayName else "$displayName ($accountName)"
}

/**
 * The calls a one-way push needs, so CalendarSync is tested against a fake. Every call
 * blocks: run it off the main thread. Without permission, all but [hasPermission] throw
 * SecurityException. Updates and deletes name the calendar too, so they can only ever
 * touch an event in the calendar it was recorded for.
 */
interface CalendarGateway {
    fun hasPermission(): Boolean

    /** Calendars whose access level lets the app add events (CAL_ACCESS_CONTRIBUTOR or higher). */
    fun writableCalendars(): List<CalendarInfo>

    /** [id] if it is still on the phone and writable, else null. */
    fun calendar(id: Long): CalendarInfo?

    /** True if the event is in that calendar and not deleted (a read, never a write). */
    fun eventExists(calendarId: Long, eventId: Long): Boolean

    /** Adds the event; returns its id. */
    fun insertEvent(calendarId: Long, event: MealEvent): Long

    /** Rewrites the event; false when there is no such live event in that calendar. */
    fun updateEvent(calendarId: Long, eventId: Long, event: MealEvent): Boolean

    /** Deletes the event; false when it was already gone. */
    fun deleteEvent(calendarId: Long, eventId: Long): Boolean
}
