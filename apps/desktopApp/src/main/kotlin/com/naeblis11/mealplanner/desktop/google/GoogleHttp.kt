package com.naeblis11.mealplanner.desktop.google

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where the sign-in, the tokens and the Calendar API are. Tests point them at FakeGoogle. */
data class GoogleEndpoints(val auth: String, val token: String, val revoke: String, val api: String) {
    companion object {
        val GOOGLE = GoogleEndpoints(
            auth = "https://accounts.google.com/o/oauth2/v2/auth",
            token = "https://oauth2.googleapis.com/token",
            revoke = "https://oauth2.googleapis.com/revoke",
            api = "https://www.googleapis.com/calendar/v3",
        )

        /** P5-R6: the only hosts Meal Planner ever sends a Google request to. */
        val GOOGLE_HOSTS: Set<String> = setOf("accounts.google.com", "oauth2.googleapis.com", "www.googleapis.com")
    }
}

/**
 * Every request to Google goes through here (P5-R6): https only, to [hosts] only, each waiting at most [TIMEOUT].
 * Nothing here logs; a reply's body is kept for the caller and its toString shows only the status. Tests pass
 * hosts = 127.0.0.1 and httpsOnly = false, for FakeGoogle.
 */
class GoogleHttp(
    private val hosts: Set<String> = GoogleEndpoints.GOOGLE_HOSTS,
    private val httpsOnly: Boolean = true,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .build(),
) {
    class Reply(val status: Int, val body: String) {
        /** The body as a JSON object; anything else (an HTML error page, nothing) is an empty one. */
        fun json(): JsonObject = try {
            Json.parseToJsonElement(body) as? JsonObject ?: JsonObject(emptyMap())
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }

        override fun toString(): String = "Reply($status)"
    }

    /** [url], refused with an IOException unless it is https on one of [hosts]; nothing is sent to an address refused. */
    fun allowed(url: String): URI {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw IOException("That isn't an address Meal Planner can use.")
        }
        if (uri.host.orEmpty() !in hosts || (httpsOnly && uri.scheme != "https")) {
            throw IOException("Meal Planner only talks to Google; it won't send to ${uri.scheme}://${uri.host}.")
        }
        return uri
    }

    /** A form POST (the token and revoke addresses). */
    fun form(url: String, fields: Map<String, String>): Reply =
        send("POST", url, fields.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }, "application/x-www-form-urlencoded", null)

    /** A Calendar API call with the access token [bearer]; [body] is JSON, or null for none. */
    fun json(method: String, url: String, bearer: String, body: String?): Reply =
        send(method, url, body, if (body != null) "application/json" else null, bearer)

    private fun send(method: String, url: String, body: String?, contentType: String?, bearer: String?): Reply {
        val builder = HttpRequest.newBuilder(allowed(url))
            .timeout(TIMEOUT)
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        if (contentType != null) builder.header("Content-Type", contentType)
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        val response = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Stopped while talking to Google.")
        }
        return Reply(response.statusCode(), response.body() ?: "")
    }

    companion object {
        /** P5-R6: how long a request may take, connecting included. */
        val TIMEOUT: Duration = Duration.ofSeconds(15)

        /** gcal.py's quote(..., safe=''): every reserved character escaped, a space as %20. */
        fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

        /** gcal._http_detail: Google's own words about an error, as ": words", or nothing. */
        fun detail(reply: Reply): String {
            val body = reply.json()
            val message = when (val error = body["error"]) {
                is JsonObject -> error.string("message")
                is JsonPrimitive -> body.string("error_description") ?: error.content
                else -> null
            }
            return if (message.isNullOrBlank()) "" else ": $message"
        }
    }
}

/** A string member of a JSON object, or null when it is missing or not a string. */
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
