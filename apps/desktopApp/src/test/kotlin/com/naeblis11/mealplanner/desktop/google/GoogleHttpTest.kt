package com.naeblis11.mealplanner.desktop.google

import java.io.IOException
import java.net.URI
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** P5-R6: https to Google's three hosts, nothing else, and nothing waits longer than 15 seconds. */
class GoogleHttpTest {
    @Test
    fun theAppTalksOnlyToGoogleOverHttps() {
        val http = GoogleHttp()
        for (url in listOf(GoogleEndpoints.GOOGLE.auth, GoogleEndpoints.GOOGLE.token, GoogleEndpoints.GOOGLE.revoke, GoogleEndpoints.GOOGLE.api + "/users/me/calendarList")) {
            assertEquals(URI(url), http.allowed(url))
            assertTrue(url, url.startsWith("https://"))
        }
        assertEquals(setOf("accounts.google.com", "oauth2.googleapis.com", "www.googleapis.com"), GoogleEndpoints.GOOGLE_HOSTS)
        // Refused before anything is sent: no request leaves the PC.
        for (url in listOf("http://www.googleapis.com/calendar/v3", "https://example.com/", "https://127.0.0.1/token", "https://www.googleapis.com.example.com/")) {
            assertThrows(url, IOException::class.java) { http.form(url, mapOf("a" to "b")) }
        }
        assertEquals(Duration.ofSeconds(15), GoogleHttp.TIMEOUT)
    }

    @Test
    fun googlesOwnWordsAboutAnErrorAreKept() {
        assertEquals(": The requested identifier already exists.", GoogleHttp.detail(GoogleHttp.Reply(409, """{"error":{"code":409,"message":"The requested identifier already exists."}}""")))
        assertEquals(": Token has been expired or revoked.", GoogleHttp.detail(GoogleHttp.Reply(400, """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""")))
        assertEquals(": invalid_client", GoogleHttp.detail(GoogleHttp.Reply(401, """{"error":"invalid_client"}""")))
        assertEquals("", GoogleHttp.detail(GoogleHttp.Reply(502, "<html>Bad Gateway</html>")))
    }

    @Test
    fun aFormIsEncodedAsGoogleReadsIt() {
        FakeGoogle().use { fake ->
            val reply = fake.http().form(fake.endpoints.revoke, mapOf("token" to "a b&c"))
            assertEquals(200, reply.status)
            assertEquals("token=a%20b%26c", fake.requests.single().body)
            assertEquals(mapOf("token" to "a b&c"), FakeGoogle.form(fake.requests.single().body))
        }
    }
}
