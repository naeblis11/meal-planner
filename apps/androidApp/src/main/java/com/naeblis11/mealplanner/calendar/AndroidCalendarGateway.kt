package com.naeblis11.mealplanner.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events

/**
 * The phone's calendars through CalendarContract: any calendar synced to the phone,
 * Google's included; Android's own sync carries the events on. No network, no sign-in.
 * Every call blocks: CalendarSync runs it on its IO dispatcher.
 */
class AndroidCalendarGateway(context: Context) : CalendarGateway {
    private val context = context.applicationContext
    private val resolver get() = context.contentResolver

    override fun hasPermission(): Boolean =
        CalendarPermissions.ALL.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun writableCalendars(): List<CalendarInfo> = calendars(CalendarRows.WRITABLE, CalendarRows.writableArgs())

    override fun calendar(id: Long): CalendarInfo? =
        calendars("${Calendars._ID} = ? AND ${CalendarRows.WRITABLE}", arrayOf(id.toString()) + CalendarRows.writableArgs()).firstOrNull()

    private fun calendars(selection: String, args: Array<String>): List<CalendarInfo> =
        resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME),
            selection,
            args,
            "${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    add(CalendarInfo(id, cursor.getString(1) ?: "Calendar $id", cursor.getString(2).orEmpty()))
                }
            }
        } ?: throw IllegalStateException("The calendar did not answer.")

    override fun eventExists(calendarId: Long, eventId: Long): Boolean =
        resolver.query(Events.CONTENT_URI, arrayOf(Events._ID), CalendarRows.LIVE_EVENT, CalendarRows.eventArgs(calendarId, eventId), null)
            ?.use { it.moveToFirst() } ?: throw IllegalStateException("The calendar did not answer.")

    override fun insertEvent(calendarId: Long, event: MealEvent): Long {
        val uri = resolver.insert(Events.CONTENT_URI, CalendarRows.insertValues(calendarId, event))
            ?: throw IllegalStateException("The calendar did not accept the event.")
        return ContentUris.parseId(uri)
    }

    override fun updateEvent(calendarId: Long, eventId: Long, event: MealEvent): Boolean =
        resolver.update(Events.CONTENT_URI, CalendarRows.updateValues(event), CalendarRows.LIVE_EVENT, CalendarRows.eventArgs(calendarId, eventId)) > 0

    override fun deleteEvent(calendarId: Long, eventId: Long): Boolean =
        resolver.delete(Events.CONTENT_URI, CalendarRows.LIVE_EVENT, CalendarRows.eventArgs(calendarId, eventId)) > 0
}

/** What the gateway writes and which rows it may touch, kept apart so a JVM test can check them. */
internal object CalendarRows {
    /** Writable, shown on the phone and syncing events: a hidden or non-syncing calendar would take events nobody sees. */
    val WRITABLE = "${Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${Calendars.VISIBLE} = 1 AND ${Calendars.SYNC_EVENTS} = 1"

    fun writableArgs(): Array<String> = arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString())

    /**
     * One event, only in the calendar it was recorded for, and only if it is one the app wrote:
     * its description ends with [MealEvents.MARKER]. Event ids are the provider's local row ids,
     * so after Calendar Storage is cleared a recorded id may name one of the family's own
     * events; the marker keeps the app off it (the slot is then sent again as a new event).
     */
    val OUR_EVENT = "${Events._ID} = ? AND ${Events.CALENDAR_ID} = ? AND ${Events.DESCRIPTION} LIKE ?"

    /** As [OUR_EVENT], and not deleted: a deleted event stays in the provider, marked, until it syncs. */
    val LIVE_EVENT = "$OUR_EVENT AND ${Events.DELETED} = 0"

    // The marker holds no LIKE wildcard (% or _); if it ever did, they would need escaping
    // here with an ESCAPE clause in the selection (CalendarRowsTest checks this).
    fun eventArgs(calendarId: Long, eventId: Long): Array<String> =
        arrayOf(eventId.toString(), calendarId.toString(), "%" + MealEvents.MARKER)

    /** Everything a meal event carries; never the calendar, so an update can't move it. */
    fun updateValues(event: MealEvent): ContentValues = ContentValues().apply {
        put(Events.TITLE, event.title)
        put(Events.DESCRIPTION, event.description)
        put(Events.DTSTART, event.startMillis)
        put(Events.DTEND, event.endMillis)
        put(Events.EVENT_TIMEZONE, event.timeZone)
        // Free, not busy: nobody's calendar should show them unavailable because dinner is planned.
        put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
    }

    fun insertValues(calendarId: Long, event: MealEvent): ContentValues =
        updateValues(event).apply { put(Events.CALENDAR_ID, calendarId) }
}
