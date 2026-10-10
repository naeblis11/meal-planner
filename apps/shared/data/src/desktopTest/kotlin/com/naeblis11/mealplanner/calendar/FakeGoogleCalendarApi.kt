package com.naeblis11.mealplanner.calendar

import java.io.IOException

/**
 * Google Calendar in memory, as gcal.py's tests fake it, plus what the desktop's ids add: an insert under an id already
 * taken (live or cancelled) answers 409, and a delete leaves the event cancelled, as Google does. It keeps
 * GoogleCalendarApi's contract as HttpGoogleCalendarApi does: eventStatus and deleteEvent answer null and false for an
 * event or calendar that is gone, and never throw for it. [writes] lists every write ("insert id", "update id",
 * "delete id").
 */
class FakeGoogleCalendarApi : GoogleCalendarApi {
    class Stored(val event: GoogleEvent, val status: String)

    val calendars = mutableListOf(GoogleCalendar(FAMILY, "Family"), GoogleCalendar("me@example.com", "me@example.com", primary = true))
    val events = linkedMapOf<Pair<String, String>, Stored>()
    val writes = mutableListOf<String>()

    /** Event ids whose insert, update or delete Google refuses (500). */
    val failing = mutableSetOf<String>()

    /** Event ids Google answers 410 for (deleted long ago and purged): an update of one is refused. */
    val goneForGood = mutableSetOf<String>()

    /** Google no longer honours the sign-in: every call throws GoogleAuthException. */
    var signedOut = false

    /** Google can't be reached: every call throws IOException. */
    var offline = false

    override fun writableCalendars(): List<GoogleCalendar> {
        gate()
        return calendars.toList()
    }

    override fun insertEvent(calendarId: String, eventId: String, event: GoogleEvent) {
        gate()
        calendar(calendarId)
        if (eventId in failing) throw GoogleApiException(500, "Google answered 500: Backend Error")
        if ((calendarId to eventId) in events) throw GoogleApiException(409, "Google answered 409: The requested identifier already exists.")
        events[calendarId to eventId] = Stored(event, "confirmed")
        writes += "insert $eventId"
    }

    override fun eventStatus(calendarId: String, eventId: String): String? {
        gate()
        // As HttpGoogleCalendarApi: a 404, for the event or for its calendar, is "no such event".
        return events[calendarId to eventId]?.takeIf { hasCalendar(calendarId) }?.status
    }

    override fun updateEvent(calendarId: String, eventId: String, event: GoogleEvent) {
        gate()
        calendar(calendarId)
        if (eventId in failing) throw GoogleApiException(500, "Google answered 500: Backend Error")
        if (eventId in goneForGood) throw GoogleApiException(410, "Google answered 410: Resource has been deleted")
        if ((calendarId to eventId) !in events) throw GoogleApiException(404, "Google answered 404: Not Found")
        events[calendarId to eventId] = Stored(event, "confirmed")
        writes += "update $eventId"
    }

    override fun deleteEvent(calendarId: String, eventId: String): Boolean {
        gate()
        if (eventId in failing) throw GoogleApiException(500, "Google answered 500: Backend Error")
        // As HttpGoogleCalendarApi: a 404 (the event or its calendar) or a 410 is "already gone".
        if (!hasCalendar(calendarId)) return false
        val stored = events[calendarId to eventId]
        if (stored == null || stored.status == "cancelled") return false
        events[calendarId to eventId] = Stored(stored.event, "cancelled")
        writes += "delete $eventId"
        return true
    }

    /** The live events of [calendarId], by id. */
    fun live(calendarId: String = FAMILY): Map<String, GoogleEvent> =
        events.filter { it.key.first == calendarId && it.value.status == "confirmed" }.mapKeys { it.key.second }.mapValues { it.value.event }

    fun status(id: String, calendarId: String = FAMILY): String? = events[calendarId to id]?.status

    /** The family deletes the event in Google Calendar: Google keeps it, cancelled. */
    fun deleteByHand(id: String, calendarId: String = FAMILY) {
        val stored = events.getValue(calendarId to id)
        events[calendarId to id] = Stored(stored.event, "cancelled")
    }

    private fun gate() {
        if (signedOut) throw GoogleAuthException(GoogleMessages.SIGN_IN_AGAIN)
        if (offline) throw IOException("Connection refused")
    }

    private fun hasCalendar(id: String): Boolean = calendars.any { it.id == id }

    private fun calendar(id: String) {
        if (!hasCalendar(id)) throw GoogleApiException(404, "Google answered 404: Not Found")
    }

    companion object {
        const val FAMILY = "family@group.calendar.google.com"
    }
}
