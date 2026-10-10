package com.naeblis11.mealplanner.desktop.google

import com.naeblis11.mealplanner.calendar.GoogleAuthException
import com.naeblis11.mealplanner.calendar.GoogleMessages
import java.io.IOException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** An OAuth client (Google Cloud console, type Desktop app): a hand-picked one, or the one built into the app (GoogleClients). */
data class GoogleClient(val id: String, val secret: String) {
    // A data class would print the secret.
    override fun toString(): String = "GoogleClient(id=$id)"
}

/**
 * gcal.OAuthUser: the refresh token swapped for an hour's access token, kept until a minute before it expires. A
 * refresh Google refuses (400 or 401: revoked, expired, or a client that changed) is GoogleAuthException, and only
 * signing in again helps. Neither token ever appears in a message or in [toString].
 */
class AccessTokens(
    private val http: GoogleHttp,
    private val endpoints: GoogleEndpoints,
    private val client: GoogleClient,
    private val refreshToken: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var token: String? = null
    private var expiresAt = 0L

    /** A live access token; blocking. */
    @Synchronized
    fun get(): String {
        token?.let { if (clock() < expiresAt) return it }
        val reply = http.form(
            endpoints.token,
            mapOf(
                "client_id" to client.id,
                "client_secret" to client.secret,
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token",
            ),
        )
        if (reply.status == 400 || reply.status == 401) throw GoogleAuthException(GoogleMessages.SIGN_IN_AGAIN)
        if (reply.status != 200) throw IOException("Google would not renew the sign-in (${reply.status})${GoogleHttp.detail(reply)}.")
        val body = reply.json()
        val fresh = body.string("access_token") ?: throw IOException("Google returned no access token.")
        val seconds = (body["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600L
        token = fresh
        expiresAt = clock() + seconds * 1000 - 60_000
        return fresh
    }

    /** Whether these are the tokens for [refresh] (the one in the token file now). */
    fun isFor(refresh: String): Boolean = refresh == refreshToken

    override fun toString(): String = "AccessTokens(client=${client.id})"
}
