package com.naeblis11.mealplanner.desktop.google

import com.naeblis11.mealplanner.calendar.GoogleApiException
import com.naeblis11.mealplanner.calendar.GoogleAuthException
import com.naeblis11.mealplanner.calendar.GoogleCalendar
import com.naeblis11.mealplanner.calendar.GoogleCalendarApi
import com.naeblis11.mealplanner.calendar.GoogleEvent
import com.naeblis11.mealplanner.calendar.GoogleEvents
import com.naeblis11.mealplanner.calendar.GoogleMessages
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * gcal.GCalLink over java.net.http (P5-R1): the Calendar API at [endpoints].api, with an access token from [tokens] on
 * each call. A 401 is GoogleAuthException (so is a refresh Google refuses, from [tokens]); eventStatus and deleteEvent
 * read a 404 or 410 (the event, or its calendar, gone) as null and false; any other answer of 300 or more is
 * GoogleApiException with Google's words. An update's body is GoogleEvents.json, which marks it confirmed.
 */
class HttpGoogleCalendarApi(
    private val http: GoogleHttp,
    private val endpoints: GoogleEndpoints,
    private val tokens: AccessTokens,
) : GoogleCalendarApi {
    // set_gcal_oauth.list_calendars: only the ones the app could actually write to. Every page is read.
    override fun writableCalendars(): List<GoogleCalendar> {
        val found = mutableListOf<GoogleCalendar>()
        var page: String? = null
        do {
            val url = "${endpoints.api}/users/me/calendarList?maxResults=250" + (page?.let { "&pageToken=" + GoogleHttp.encode(it) } ?: "")
            val body = checked(call("GET", url, null)).json()
            for (item in body["items"] as? JsonArray ?: JsonArray(emptyList())) {
                val calendar = item as? JsonObject ?: continue
                val id = calendar.string("id") ?: continue
                if (calendar.string("accessRole").orEmpty() in WRITABLE) {
                    val primary = (calendar["primary"] as? JsonPrimitive)?.booleanOrNull == true
                    found += GoogleCalendar(id, calendar.string("summary") ?: id, primary)
                }
            }
            page = body.string("nextPageToken")
        } while (page != null)
        return found
    }

    override fun insertEvent(calendarId: String, eventId: String, event: GoogleEvent) {
        checked(call("POST", events(calendarId), GoogleEvents.json(event, eventId)))
    }

    override fun eventStatus(calendarId: String, eventId: String): String? {
        val reply = call("GET", event(calendarId, eventId), null)
        if (reply.status == 404 || reply.status == 410) return null
        return checked(reply).json().string("status") ?: "confirmed"
    }

    override fun updateEvent(calendarId: String, eventId: String, event: GoogleEvent) {
        checked(call("PATCH", event(calendarId, eventId), GoogleEvents.json(event)))
    }

    override fun deleteEvent(calendarId: String, eventId: String): Boolean {
        val reply = call("DELETE", event(calendarId, eventId), null)
        if (reply.status == 404 || reply.status == 410) return false
        checked(reply)
        return true
    }

    private fun call(method: String, url: String, body: String?): GoogleHttp.Reply = http.json(method, url, tokens.get(), body)

    private fun checked(reply: GoogleHttp.Reply): GoogleHttp.Reply {
        if (reply.status == 401) throw GoogleAuthException(GoogleMessages.SIGN_IN_AGAIN)
        if (reply.status >= 300) throw GoogleApiException(reply.status, "Google answered ${reply.status}${GoogleHttp.detail(reply)}")
        return reply
    }

    private fun events(calendarId: String): String = "${endpoints.api}/calendars/${GoogleHttp.encode(calendarId)}/events"

    private fun event(calendarId: String, eventId: String): String = events(calendarId) + "/" + GoogleHttp.encode(eventId)

    private companion object {
        val WRITABLE = setOf("owner", "writer")
    }
}
