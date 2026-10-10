package com.naeblis11.mealplanner.desktop.google

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Google's token, revoke and Calendar addresses in one server on 127.0.0.1 (an ephemeral port), so no test reaches the
 * network. It keeps every request, and answers as Google does:
 * - the token address checks the client, and the PKCE verifier against the challenge the sign-in page was opened with;
 * - an access token it never issued (or one revoked) gets a 401, a refresh token it no longer honours invalid_grant;
 * - an insert under an id already taken gets a 409; a delete leaves the event cancelled, and a second one gets a 410;
 * - an event or calendar it doesn't have gets a 404, and one [purged] a 410 whatever is asked of it;
 * - the calendar list comes in two pages, the second holding the primary calendar.
 */
class FakeGoogle : AutoCloseable {
    class Request(val method: String, val path: String, val query: String?, val authorization: String?, val body: String)

    val clientId = "client-1.apps.googleusercontent.com"
    val clientSecret = "secret-1"
    val client = GoogleClient(clientId, clientSecret)
    val requests = CopyOnWriteArrayList<Request>()

    /** Refresh tokens Google honours; a revoke takes one away. */
    val refreshTokens: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { add(REFRESH) }
    private val accessTokens: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val issued = AtomicInteger()

    /** What the sign-in page was opened with: the code exchange must carry the verifier and the same redirect. */
    @Volatile
    var challenge: String? = null

    @Volatile
    var redirectUri: String? = null

    /** False: the code exchange answers without a refresh token. */
    @Volatile
    var giveRefreshToken = true

    /** Calendar id to its summary, access role and whether it is the account's primary calendar. */
    val calendars = linkedMapOf(
        FAMILY to Triple("Family", "writer", false),
        "holidays@group.v.calendar.google.com" to Triple("Holidays", "reader", false),
        "me@example.com" to Triple("me@example.com", "owner", true),
    )

    /** (calendar, event id) to the event as stored, its status included. */
    val events = ConcurrentHashMap<Pair<String, String>, JsonObject>()

    /** What the revoke address answers; anything but 200 revokes nothing. */
    @Volatile
    var revokeStatus = 200

    /** True: the revoke address drops the connection without an answer, as a network failure does. */
    @Volatile
    var dropRevoke = false

    /** Runs as a calendar list page is asked for, before it is answered (a sign-out mid-list, say). */
    @Volatile
    var onCalendarList: () -> Unit = {}

    /** What the calendar list answers; anything but 200 is an error with no calendars. */
    @Volatile
    var calendarListStatus = 200

    /** (calendar, event id) of events deleted long ago and purged: every call on one answers 410. */
    val purged: MutableSet<Pair<String, String>> = ConcurrentHashMap.newKeySet()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
        createContext("/") { exchange ->
            try {
                answer(exchange)
            } finally {
                exchange.close()
            }
        }
        start()
    }

    val base = "http://127.0.0.1:${server.address.port}"
    val endpoints = GoogleEndpoints(auth = "$base/o/oauth2/v2/auth", token = "$base/token", revoke = "$base/revoke", api = "$base/calendar/v3")

    /** A GoogleHttp that can reach this server and nothing else. */
    fun http() = GoogleHttp(hosts = setOf("127.0.0.1"), httpsOnly = false)

    fun status(calendarId: String, eventId: String): String? = events[calendarId to eventId]?.get("status")?.jsonPrimitive?.content

    /** The user removes the app's access at Google: no refresh token or access token works any more. */
    fun revokeAll() {
        refreshTokens.clear()
        accessTokens.clear()
    }

    /**
     * The user's browser, as GoogleSignIn's browse: it notes what the sign-in page was opened with, then follows
     * Google's redirect back to the app as clicking Allow does. [change] alters that redirect's query.
     */
    fun browser(change: (MutableMap<String, String>) -> Unit = {}): (URI) -> Unit = { uri ->
        val asked = form(uri.rawQuery)
        challenge = asked["code_challenge"]
        redirectUri = asked["redirect_uri"]
        val back = linkedMapOf("code" to CODE, "state" to asked.getValue("state")).also(change)
        get(asked.getValue("redirect_uri") + "?" + back.entries.joinToString("&") { (k, v) -> "${GoogleHttp.encode(k)}=${GoogleHttp.encode(v)}" })
    }

    /** A GET from the browser; its status. Throws IOException when nothing listens there. */
    fun get(url: String): Int = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        .send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()

    override fun close() = server.stop(0)

    private fun answer(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val path = exchange.requestURI.rawPath
        val query = exchange.requestURI.rawQuery
        val authorization = exchange.requestHeaders.getFirst("Authorization")
        requests += Request(exchange.requestMethod, path, query, authorization, body)
        val (status, reply) = when {
            path == "/token" -> token(form(body))
            path == "/revoke" -> {
                // Closing the exchange unanswered (see the handler's finally) is a dropped connection to the caller.
                if (dropRevoke) throw java.io.IOException("connection dropped")
                if (revokeStatus != 200) {
                    revokeStatus to error(revokeStatus, "Backend Error")
                } else {
                    form(body)["token"]?.let(refreshTokens::remove)
                    200 to "{}"
                }
            }
            path.startsWith("/calendar/v3/") ->
                if (authorization.orEmpty().removePrefix("Bearer ") in accessTokens) {
                    calendar(exchange.requestMethod, path.removePrefix("/calendar/v3/"), form(query.orEmpty()), body)
                } else {
                    401 to error(401, "Invalid Credentials")
                }
            else -> 404 to error(404, "Not Found")
        }
        exchange.responseHeaders.add("Content-Type", "application/json")
        if (status == 204) {
            exchange.sendResponseHeaders(204, -1)
        } else {
            val bytes = reply.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
    }

    private fun token(form: Map<String, String>): Pair<Int, String> {
        if (form["client_id"] != clientId || form["client_secret"] != clientSecret) {
            return 401 to """{"error":"invalid_client","error_description":"The OAuth client was not found."}"""
        }
        return when (form["grant_type"]) {
            "authorization_code" -> {
                val verified = challenge != null && challengeOf(form["code_verifier"].orEmpty()) == challenge
                if (form["code"] != CODE || !verified || form["redirect_uri"] != redirectUri) {
                    400 to """{"error":"invalid_grant","error_description":"Bad Request"}"""
                } else {
                    200 to buildJsonObject {
                        put("access_token", issue())
                        put("expires_in", 3599)
                        put("token_type", "Bearer")
                        if (giveRefreshToken) put("refresh_token", REFRESH)
                    }.toString()
                }
            }
            "refresh_token" ->
                if (form["refresh_token"].orEmpty() in refreshTokens) {
                    200 to buildJsonObject {
                        put("access_token", issue())
                        put("expires_in", 3599)
                        put("token_type", "Bearer")
                    }.toString()
                } else {
                    400 to """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}"""
                }
            else -> 400 to """{"error":"unsupported_grant_type"}"""
        }
    }

    private fun issue(): String = "access-${issued.incrementAndGet()}".also { accessTokens += it }

    private fun calendar(method: String, rest: String, query: Map<String, String>, body: String): Pair<Int, String> {
        val parts = rest.split('/').map { URLDecoder.decode(it, Charsets.UTF_8) }
        if (parts == listOf("users", "me", "calendarList") && method == "GET") {
            onCalendarList()
            val status = calendarListStatus
            return if (status != 200) status to error(status, "Backend Error") else 200 to calendarPage(query["pageToken"])
        }
        if (parts.size < 3 || parts[0] != "calendars" || parts[2] != "events") return 404 to error(404, "Not Found")
        val calendarId = parts[1]
        if (calendarId !in calendars) return 404 to error(404, "Not Found")
        if (parts.size == 3 && method == "POST") return insert(calendarId, json(body))
        if (parts.size != 4) return 404 to error(404, "Not Found")
        val key = calendarId to parts[3]
        if (key in purged) return 410 to error(410, "Resource has been deleted")
        val stored = events[key]
        return when (method) {
            "GET" -> if (stored == null) 404 to error(404, "Not Found") else 200 to stored.toString()
            "PATCH" -> if (stored == null) {
                404 to error(404, "Not Found")
            } else {
                val merged = JsonObject(stored + json(body))
                events[key] = merged
                200 to merged.toString()
            }
            "DELETE" -> when {
                stored == null -> 404 to error(404, "Not Found")
                stored["status"]?.jsonPrimitive?.content == "cancelled" -> 410 to error(410, "Resource has been deleted")
                else -> {
                    events[key] = JsonObject(stored + ("status" to JsonPrimitive("cancelled")))
                    204 to ""
                }
            }
            else -> 405 to error(405, "Method Not Allowed")
        }
    }

    private fun insert(calendarId: String, event: JsonObject): Pair<Int, String> {
        val id = event["id"]?.jsonPrimitive?.content ?: "g${events.size + 1}"
        if (events.containsKey(calendarId to id)) return 409 to error(409, "The requested identifier already exists.")
        val stored = JsonObject(event + ("id" to JsonPrimitive(id)) + ("status" to (event["status"] ?: JsonPrimitive("confirmed"))))
        events[calendarId to id] = stored
        return 200 to stored.toString()
    }

    // Two pages, as Google pages a long list: the first two calendars, then the rest.
    private fun calendarPage(pageToken: String?): String {
        val all = calendars.entries.toList()
        val page = if (pageToken == null) all.take(2) else all.drop(2)
        return buildJsonObject {
            put(
                "items",
                buildJsonArray {
                    for ((id, about) in page) {
                        add(
                            buildJsonObject {
                                put("id", id)
                                put("summary", about.first)
                                put("accessRole", about.second)
                                if (about.third) put("primary", true)
                            },
                        )
                    }
                },
            )
            if (pageToken == null) put("nextPageToken", "page-2")
        }.toString()
    }

    private fun error(code: Int, message: String): String = buildJsonObject {
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }.toString()

    companion object {
        const val REFRESH = "refresh-1"
        const val CODE = "code-1"
        const val FAMILY = "family@group.calendar.google.com"

        /** PKCE S256: what a verifier's challenge is. */
        fun challengeOf(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        /** A form body or query string, decoded. */
        fun form(text: String?): Map<String, String> = text.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
            URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8)
        }

        private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    }
}
