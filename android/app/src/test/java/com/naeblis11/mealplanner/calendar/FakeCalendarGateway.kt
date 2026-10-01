package com.naeblis11.mealplanner.calendar

/**
 * A calendar provider the tests can inspect. Event ids are handed out from 101 in order.
 * Every write is logged in [writes]; reads are not, so "a second send writes nothing"
 * is `writes` staying empty. Called from IO threads in full-app tests, hence synchronized.
 * Like AndroidCalendarGateway's selections, lookups, updates and deletes only match an event
 * in the named calendar whose description ends with MealEvents.MARKER.
 */
class FakeCalendarGateway : CalendarGateway {
    data class Stored(val calendarId: Long, val event: MealEvent)

    @Volatile var permission = true

    /** Permission withdrawn mid-send: hasPermission still says yes, but writes throw SecurityException. */
    @Volatile var refuseWritesWithSecurity = false
    val calendars = mutableListOf(CalendarInfo(1L, "Family", "family@example.com"))
    val events = linkedMapOf<Long, Stored>()
    val writes = mutableListOf<String>()

    /** Titles whose insert or update fails, as a provider error would. */
    val failTitles = mutableSetOf<String>()

    /** Event ids whose update or delete fails, as a provider error would. */
    val failEventIds = mutableSetOf<Long>()

    /** Permission withdrawn after this many successful writes (-1: never). */
    @Volatile var securityAfterWrites = -1

    /** When set, insertEvent waits here (outside the fake's lock) after signalling [insertEntered]. */
    @Volatile var insertGate: java.util.concurrent.CountDownLatch? = null
    val insertEntered = java.util.concurrent.CountDownLatch(1)
    private var nextId = 100L

    /** CalendarRows.LIVE_EVENT: that id, in that calendar, and written by the app. */
    private fun ours(calendarId: Long, eventId: Long): Boolean =
        events[eventId]?.let { it.calendarId == calendarId && it.event.description.endsWith(MealEvents.MARKER) } ?: false

    private fun allowed() {
        if (!permission) throw SecurityException("Calendar permission denied")
    }

    private fun writable(event: MealEvent?) {
        allowed()
        if (securityAfterWrites >= 0 && writes.size >= securityAfterWrites) throw SecurityException("Calendar permission withdrawn")
        if (refuseWritesWithSecurity) throw SecurityException("Calendar permission withdrawn")
        if (event != null && event.title in failTitles) throw IllegalStateException("The calendar refused ${event.title}")
    }

    @Synchronized override fun hasPermission(): Boolean = permission

    @Synchronized override fun writableCalendars(): List<CalendarInfo> {
        allowed()
        return calendars.toList()
    }

    @Synchronized override fun calendar(id: Long): CalendarInfo? {
        allowed()
        return calendars.firstOrNull { it.id == id }
    }

    @Synchronized override fun eventExists(calendarId: Long, eventId: Long): Boolean {
        allowed()
        return ours(calendarId, eventId)
    }

    override fun insertEvent(calendarId: Long, event: MealEvent): Long {
        insertGate?.let {
            insertEntered.countDown()
            it.await()
        }
        return insertLocked(calendarId, event)
    }

    @Synchronized private fun insertLocked(calendarId: Long, event: MealEvent): Long {
        writable(event)
        val id = ++nextId
        events[id] = Stored(calendarId, event)
        writes += "insert $id"
        return id
    }

    @Synchronized override fun updateEvent(calendarId: Long, eventId: Long, event: MealEvent): Boolean {
        writable(event)
        if (eventId in failEventIds) throw IllegalStateException("The calendar refused event $eventId")
        if (!ours(calendarId, eventId)) return false
        events[eventId] = Stored(calendarId, event)
        writes += "update $eventId"
        return true
    }

    @Synchronized override fun deleteEvent(calendarId: Long, eventId: Long): Boolean {
        writable(null)
        if (eventId in failEventIds) throw IllegalStateException("The calendar refused event $eventId")
        writes += "delete $eventId"
        if (!ours(calendarId, eventId)) return false
        events.remove(eventId)
        return true
    }

    /** The user deletes an event in their calendar app. */
    @Synchronized fun deleteByHand(eventId: Long) {
        events.remove(eventId)
    }

    /** An event the family put on the calendar themselves. */
    @Synchronized fun addTheirOwn(calendarId: Long, title: String): Long {
        val id = ++nextId
        events[id] = Stored(calendarId, MealEvent(title, "", 0L, 0L, "UTC"))
        return id
    }

    /** The provider reuses [eventId] for an event the family made themselves (no Meal Planner line). */
    @Synchronized fun theirOwnUnder(eventId: Long, calendarId: Long, title: String) {
        events[eventId] = Stored(calendarId, MealEvent(title, "", 0L, 0L, "UTC"))
    }

    @Synchronized fun titles(): List<String> = events.values.map { it.event.title }.sorted()
}
