package com.naeblis11.mealplanner.desktop.google

import com.naeblis11.mealplanner.calendar.SignInCancelledException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A sign-in that didn't finish, in a sentence for Settings. */
class GoogleSignInException(message: String) : IOException(message)

/** A run while another is still waiting for the browser; that one goes on. */
class SignInAlreadyWaitingException : IllegalStateException("A Google sign-in is already waiting for the browser.")

/**
 * set_gcal_oauth.sign_in for the desktop (P5-R4): OAuth for an installed app. A one-shot answer address on 127.0.0.1
 * at a port the system picks, the browser opened on Google's consent page through [browse], PKCE (S256) and a state
 * that must come back. The code is exchanged for a refresh token, which [run] returns. Blocking: run it off the UI
 * thread; [cancel] ends a wait from any thread. The code, the verifier and the tokens are never logged or said.
 */
class GoogleSignIn(
    private val http: GoogleHttp,
    private val endpoints: GoogleEndpoints,
    private val browse: (URI) -> Unit,
    private val random: SecureRandom = SecureRandom(),
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
    /** Opens the answer address; tests pass one that fails, to see NO_LOOPBACK. */
    private val bind: (InetSocketAddress) -> HttpServer = { HttpServer.create(it, 0) },
) {
    // The running sign-in's answer, set as the run starts (before the answer address opens) so a cancel at any point of
    // the run reaches it. Null when none is running; a cancel then does nothing.
    private val lock = Any()
    private var pending: CompletableFuture<Map<String, String>>? = null

    /**
     * Signs in with [client]; [onUrl] gets the consent page's address first, for when no browser opens. One at a time:
     * a second run while one is waiting is SignInAlreadyWaitingException, and the first goes on.
     */
    fun run(client: GoogleClient, onUrl: (String) -> Unit): String {
        val answer = CompletableFuture<Map<String, String>>()
        synchronized(lock) {
            if (pending != null) throw SignInAlreadyWaitingException()
            pending = answer
        }
        var server: HttpServer? = null
        try {
            val verifier = token(64)
            val challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
            val state = token(16)
            if (answer.isCancelled) throw SignInCancelledException()
            server = try {
                bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0)).apply {
                    createContext("/") { exchange -> reply(exchange, answer, state) }
                    start()
                }
            } catch (e: IOException) {
                throw GoogleSignInException(NO_LOOPBACK)
            }
            val redirect = "http://$LOOPBACK:${server.address.port}/"
            val url = endpoints.auth + "?" + linkedMapOf(
                "client_id" to client.id,
                "redirect_uri" to redirect,
                "response_type" to "code",
                "scope" to SCOPES,
                // offline and consent together are what guarantee a refresh token (set_gcal_oauth.py).
                "access_type" to "offline",
                "prompt" to "consent",
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "state" to state,
            ).entries.joinToString("&") { (k, v) -> "${GoogleHttp.encode(k)}=${GoogleHttp.encode(v)}" }
            if (answer.isCancelled) throw SignInCancelledException()
            onUrl(url)
            try {
                browse(URI(url))
            } catch (e: Exception) {
                // No browser could be opened: the address is on screen, to open by hand.
            }
            // Only an answer with this sign-in's state ends the wait (see reply), so an error in it is Google's own.
            val params = await(answer)
            params["error"]?.let { throw GoogleSignInException(if (it == "access_denied") DENIED else "Google said: $it") }
            val code = params["code"]?.takeIf { it.isNotEmpty() } ?: throw GoogleSignInException(NO_CODE)
            return exchange(client, code, verifier, redirect)
        } finally {
            server?.stop(0)
            synchronized(lock) { pending = null }
        }
    }

    /**
     * Ends the running sign-in while it is still waiting: before the browser has answered, its run throws
     * SignInCancelledException. A cancel that arrives once the browser has answered, during the exchange of the code
     * for the token, has no effect: that run finishes (or fails) as it would have. With none running it does nothing,
     * so it can't end the next one.
     */
    fun cancel() {
        synchronized(lock) { pending }?.cancel(false)
    }

    private fun await(answer: CompletableFuture<Map<String, String>>): Map<String, String> =
        try {
            answer.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw GoogleSignInException(TIMED_OUT)
        } catch (e: CancellationException) {
            throw SignInCancelledException()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SignInCancelledException()
        } catch (e: ExecutionException) {
            throw GoogleSignInException(NO_CODE)
        }

    private fun exchange(client: GoogleClient, code: String, verifier: String, redirect: String): String {
        val reply = try {
            http.form(
                endpoints.token,
                mapOf(
                    "client_id" to client.id,
                    "client_secret" to client.secret,
                    "code" to code,
                    "code_verifier" to verifier,
                    "grant_type" to "authorization_code",
                    "redirect_uri" to redirect,
                ),
            )
        } catch (e: IOException) {
            throw GoogleSignInException(UNREACHABLE)
        }
        if (reply.status != 200) throw GoogleSignInException("Google rejected the sign-in (${reply.status})${GoogleHttp.detail(reply)}.")
        return reply.json().string("refresh_token") ?: throw GoogleSignInException(NO_REFRESH_TOKEN)
    }

    // Google's redirect, carrying this sign-in's [state], is the answer. Anything else (a favicon, a stray local request,
    // an answer meant for another sign-in) gets a 404 and the wait goes on: it can neither end nor refuse this sign-in.
    private fun reply(exchange: HttpExchange, answer: CompletableFuture<Map<String, String>>, state: String) {
        try {
            val params = query(exchange.requestURI.rawQuery)
            val ours = exchange.requestMethod == "GET" && exchange.requestURI.rawPath == "/" && params["state"] == state
            if (ours) answer.complete(params)
            val page = if (ours) PAGE else NOT_FOUND
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(if (ours) 200 else 404, page.size.toLong())
            exchange.responseBody.write(page)
        } finally {
            exchange.close()
        }
    }

    private fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate { pair ->
        URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8)
    }

    // secrets.token_urlsafe(n): n random bytes, base64url without padding.
    private fun token(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    companion object {
        const val LOOPBACK = "127.0.0.1"

        /** set_gcal_oauth.SIGN_IN_TIMEOUT: five minutes to click Allow. */
        const val TIMEOUT_MILLIS = 300_000L

        /** set_gcal_oauth.SCOPES: write the meals, and list the calendars to choose from. Neither reads other calendars. */
        const val SCOPES = "https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/calendar.calendarlist.readonly"

        const val TIMED_OUT = "Timed out waiting for the Google sign-in. Try again."
        const val DENIED = "You didn't allow Meal Planner in the browser, so it isn't signed in."
        const val NO_CODE = "No sign-in code came back from Google. Try again."
        const val NO_REFRESH_TOKEN =
            "Google returned no refresh token. Remove Meal Planner at https://myaccount.google.com/permissions, then sign in again."
        const val NO_LOOPBACK = "Couldn't open a local address on this PC for the Google sign-in to answer on. Try again."
        const val UNREACHABLE = "Couldn't reach Google to finish signing in. Check this PC's internet connection, then try again."

        private val PAGE = (
            "<!doctype html><meta charset=\"utf-8\"><title>Meal Planner</title>" +
                "<body style=\"font-family: system-ui, sans-serif; margin: 4rem auto; max-width: 30rem; text-align: center\">" +
                "<h1 style=\"color:#1c7a4d\">Done</h1><p>You can close this tab and go back to Meal Planner.</p>"
            ).toByteArray(Charsets.UTF_8)
        private val NOT_FOUND = "Not found".toByteArray(Charsets.UTF_8)
    }
}
