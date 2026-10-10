package com.naeblis11.mealplanner.calendar

import java.io.IOException

/** A Google calendar the signed-in account can add events to (calendarList accessRole owner or writer). */
data class GoogleCalendar(val id: String, val name: String, val primary: Boolean = false)

/**
 * One planned meal as a Google event (gcal.build_event, without the link back: the PC has no address for it). [start]
 * and [end] are RFC 3339 stamps carrying that date's UTC offset.
 */
data class GoogleEvent(val summary: String, val description: String, val start: String, val end: String)

/** Google answered [status] (409: the id is taken; 404 or 410: the event or calendar is gone); [message] says what it said. */
class GoogleApiException(val status: Int, message: String) : IOException(message)

/** Google refused the sign-in itself (revoked, expired, or signed out elsewhere): only signing in again helps. */
class GoogleAuthException(message: String) : IOException(message)

/** The user cancelled a sign-in that was waiting for the browser; nothing is wrong and nothing was saved. */
class SignInCancelledException : IOException("The Google sign-in was cancelled.")

/**
 * The Calendar API calls a one-way push needs (P5-R1), so GoogleCalendarSync is tested against a fake. Every call
 * blocks: run it off the main thread. Each throws GoogleAuthException when the sign-in is no longer good,
 * GoogleApiException for an answer it doesn't expect, and IOException when Google can't be reached.
 */
interface GoogleCalendarApi {
    /** The calendars the account can write to, all pages of them. */
    fun writableCalendars(): List<GoogleCalendar>

    /** Adds [event] under the client-chosen [eventId]; GoogleApiException(409) when the id is taken, live or cancelled. */
    fun insertEvent(calendarId: String, eventId: String, event: GoogleEvent)

    /** The event's status ("confirmed", or "cancelled" once deleted), or null when Google has no such event (404 or 410). */
    fun eventStatus(calendarId: String, eventId: String): String?

    /**
     * Rewrites the event's fields and marks it confirmed (gcal.update_event's PATCH), which also brings back one deleted
     * by hand; GoogleApiException(404 or 410) when it is gone for good.
     */
    fun updateEvent(calendarId: String, eventId: String, event: GoogleEvent)

    /** Deletes the event; false when it was already gone (404 or 410). */
    fun deleteEvent(calendarId: String, eventId: String): Boolean
}
