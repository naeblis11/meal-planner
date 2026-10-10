package com.naeblis11.mealplanner.desktop.google

import com.naeblis11.mealplanner.calendar.GoogleApiException
import com.naeblis11.mealplanner.calendar.GoogleAuthException
import com.naeblis11.mealplanner.calendar.GoogleCalendar
import com.naeblis11.mealplanner.calendar.GoogleEvent
import com.naeblis11.mealplanner.calendar.GoogleMessages
import com.naeblis11.mealplanner.desktop.google.FakeGoogle.Companion.FAMILY
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The five calls of GoogleCalendarApi over java.net.http, against the fake Google on 127.0.0.1. */
class HttpGoogleCalendarApiTest {
    private val fake = FakeGoogle()
    private val http = fake.http()
    private var now = 0L
    private val tokens = AccessTokens(http, fake.endpoints, fake.client, FakeGoogle.REFRESH) { now }
    private val api = HttpGoogleCalendarApi(http, fake.endpoints, tokens)
    private val event = GoogleEvent("Dinner: Soup", "Added by Meal Planner.", "2026-10-05T18:00:00-04:00", "2026-10-05T19:00:00-04:00")

    @After
    fun tearDown() = fake.close()

    private fun tokenRequests() = fake.requests.count { it.path == "/token" }

    @Test
    fun onlyCalendarsTheAccountCanWriteToAreListedFromEveryPage() {
        assertEquals(
            listOf(GoogleCalendar(FAMILY, "Family"), GoogleCalendar("me@example.com", "me@example.com", primary = true)),
            api.writableCalendars(),
        )
        val pages = fake.requests.filter { it.path == "/calendar/v3/users/me/calendarList" }
        assertEquals(2, pages.size)
        assertEquals("page-2", FakeGoogle.form(pages[1].query)["pageToken"])
    }

    @Test
    fun anEventIsInsertedUnderTheIdItWasGiven() {
        api.insertEvent(FAMILY, "mpabc12", event)

        val stored = fake.events.getValue(FAMILY to "mpabc12")
        assertEquals("Dinner: Soup", stored["summary"]!!.jsonPrimitive.content)
        assertEquals("transparent", stored["transparency"]!!.jsonPrimitive.content)
        val sent = fake.requests.single { it.method == "POST" && it.path.endsWith("/events") }
        // gcal.py's quote(calendar_id, safe=''): the at sign travels as %40.
        assertEquals("/calendar/v3/calendars/family%40group.calendar.google.com/events", sent.path)
        assertEquals("Bearer access-1", sent.authorization)
        assertTrue(sent.body, sent.body.startsWith("{\"id\":\"mpabc12\","))
    }

    @Test
    fun anIdAlreadyTakenIs409() {
        api.insertEvent(FAMILY, "mpabc12", event)
        val taken = assertThrows(GoogleApiException::class.java) { api.insertEvent(FAMILY, "mpabc12", event) }
        assertEquals(409, taken.status)
        assertEquals("Google answered 409: The requested identifier already exists.", taken.message)
    }

    @Test
    fun anEventsStatusUpdateAndDelete() {
        assertNull(api.eventStatus(FAMILY, "mpabc12"))
        assertEquals(404, assertThrows(GoogleApiException::class.java) { api.updateEvent(FAMILY, "mpabc12", event) }.status)
        api.insertEvent(FAMILY, "mpabc12", event)
        assertEquals("confirmed", api.eventStatus(FAMILY, "mpabc12"))

        assertTrue(api.deleteEvent(FAMILY, "mpabc12"))
        assertEquals("cancelled", api.eventStatus(FAMILY, "mpabc12"))
        // Already deleted: Google answers 410, which is "gone", not an error.
        assertFalse(api.deleteEvent(FAMILY, "mpabc12"))

        // An update is a PATCH that marks it confirmed, so an event deleted by hand comes back.
        api.updateEvent(FAMILY, "mpabc12", event.copy(summary = "Dinner: Stew"))
        assertEquals("confirmed", fake.status(FAMILY, "mpabc12"))
        assertEquals("Dinner: Stew", fake.events.getValue(FAMILY to "mpabc12")["summary"]!!.jsonPrimitive.content)
        // Two PATCHes (the 404 above, and this one), never a PUT.
        assertEquals(2, fake.requests.count { it.method == "PATCH" })
        assertEquals(0, fake.requests.count { it.method == "PUT" })
    }

    @Test
    fun anAccessTokenIsReusedUntilAMinuteBeforeItExpires() {
        api.writableCalendars()
        api.eventStatus(FAMILY, "mpabc12")
        assertEquals(1, tokenRequests())
        // Google said 3599 seconds; a minute before that a new one is asked for.
        now = 3_539_000L
        api.eventStatus(FAMILY, "mpabc12")
        assertEquals(2, tokenRequests())
        assertEquals("refresh_token", FakeGoogle.form(fake.requests.last { it.path == "/token" }.body)["grant_type"])
    }

    @Test
    fun aSignInGoogleNoLongerHonoursIsAnAuthProblemNotAnEventOne() {
        api.writableCalendars()
        fake.revokeAll()
        // The cached access token is refused...
        assertEquals(GoogleMessages.SIGN_IN_AGAIN, assertThrows(GoogleAuthException::class.java) { api.insertEvent(FAMILY, "mpabc12", event) }.message)
        // ...and so is the refresh token once it is needed again.
        now = 4_000_000L
        assertEquals(GoogleMessages.SIGN_IN_AGAIN, assertThrows(GoogleAuthException::class.java) { api.writableCalendars() }.message)
    }

    @Test
    fun anEventOrCalendarThatIsGoneIsNullAndFalseNeverAnError() {
        val elsewhere = "gone@group.calendar.google.com"
        api.insertEvent(FAMILY, "mpold", event)
        fake.purged += FAMILY to "mpold"

        // 404: no such event, or no such calendar.
        assertNull(api.eventStatus(FAMILY, "mpnever"))
        assertNull(api.eventStatus(elsewhere, "mpabc12"))
        assertFalse(api.deleteEvent(FAMILY, "mpnever"))
        assertFalse(api.deleteEvent(elsewhere, "mpabc12"))
        // 410: purged.
        assertNull(api.eventStatus(FAMILY, "mpold"))
        assertFalse(api.deleteEvent(FAMILY, "mpold"))
        // Each of those was really answered so.
        val answered = fake.requests.filter { it.method == "GET" || it.method == "DELETE" }.map { it.path.substringAfterLast('/') }
        assertEquals(listOf("mpnever", "mpabc12", "mpnever", "mpabc12", "mpold", "mpold"), answered)

        // An update of one is the error the sync turns into an insert under a fresh id.
        assertEquals(410, assertThrows(GoogleApiException::class.java) { api.updateEvent(FAMILY, "mpold", event) }.status)
        assertEquals(404, assertThrows(GoogleApiException::class.java) { api.updateEvent(elsewhere, "mpabc12", event) }.status)
        // An insert into a calendar that is gone is a 404 too (the sync's "choose again").
        assertEquals(404, assertThrows(GoogleApiException::class.java) { api.insertEvent(elsewhere, "mpabc12", event) }.status)
    }

    @Test
    fun anUpdateIsAPatchMarkedConfirmedWithoutAnId() {
        api.insertEvent(FAMILY, "mpabc12", event)
        api.deleteEvent(FAMILY, "mpabc12")
        assertEquals("cancelled", fake.status(FAMILY, "mpabc12"))

        api.updateEvent(FAMILY, "mpabc12", event)

        val patch = fake.requests.single { it.method == "PATCH" }
        assertEquals("/calendar/v3/calendars/family%40group.calendar.google.com/events/mpabc12", patch.path)
        assertTrue(patch.body, patch.body.contains("\"status\":\"confirmed\""))
        assertFalse(patch.body, patch.body.contains("\"id\""))
        assertEquals("confirmed", fake.status(FAMILY, "mpabc12"))
    }

    @Test
    fun every401IsAnAuthProblemEvenWhereGoneWouldBeNullOrFalse() {
        api.insertEvent(FAMILY, "mpabc12", event)
        fake.revokeAll()
        // The cached access token is now refused (401) by every call; none of them reads that as "gone".
        val calls = listOf<() -> Any?>(
            { api.writableCalendars() },
            { api.insertEvent(FAMILY, "mpnew", event) },
            { api.eventStatus(FAMILY, "mpabc12") },
            { api.updateEvent(FAMILY, "mpabc12", event) },
            { api.deleteEvent(FAMILY, "mpabc12") },
        )
        for (call in calls) {
            assertEquals(GoogleMessages.SIGN_IN_AGAIN, assertThrows(GoogleAuthException::class.java) { call() }.message)
        }
        // The insert, then the five refused, all with the one access token.
        assertEquals(6, fake.requests.count { it.path.startsWith("/calendar/v3/") && it.authorization == "Bearer access-1" })
        assertEquals("confirmed", fake.status(FAMILY, "mpabc12"))
    }

    @Test
    fun aRefreshGoogleRefusesIsAnAuthProblem() {
        // invalid_grant (400): the refresh token is revoked or expired.
        fake.refreshTokens.clear()
        assertEquals(GoogleMessages.SIGN_IN_AGAIN, assertThrows(GoogleAuthException::class.java) { api.eventStatus(FAMILY, "mpabc12") }.message)
        // invalid_client (401): the client's secret changed in the Cloud console.
        val wrongClient = AccessTokens(http, fake.endpoints, GoogleClient(fake.clientId, "not-the-secret"), FakeGoogle.REFRESH) { now }
        assertThrows(GoogleAuthException::class.java) { HttpGoogleCalendarApi(http, fake.endpoints, wrongClient).deleteEvent(FAMILY, "mpabc12") }
        // Nothing reached the Calendar API without a token.
        assertEquals(0, fake.requests.count { it.path.startsWith("/calendar/v3/") })
    }

    @Test
    fun noTokenOrSecretIsEverInAMessage() {
        val said = mutableListOf<String>()
        api.insertEvent(FAMILY, "mpabc12", event)
        said += assertThrows(GoogleApiException::class.java) { api.insertEvent(FAMILY, "mpabc12", event) }.message!!
        fake.revokeAll()
        said += assertThrows(GoogleAuthException::class.java) { api.writableCalendars() }.message!!
        said += assertThrows(GoogleAuthException::class.java) { AccessTokens(http, fake.endpoints, fake.client, FakeGoogle.REFRESH).get() }.message!!
        said += listOf(tokens.toString(), fake.client.toString(), api.toString())
        for (text in said) {
            for (secret in listOf(FakeGoogle.REFRESH, "access-", fake.clientSecret)) assertFalse("'$text' gives away a secret", text.contains(secret))
        }
        // The token is where it belongs: in the Authorization header.
        assertTrue(fake.requests.any { it.authorization == "Bearer access-1" })
    }
}
