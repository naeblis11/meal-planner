package com.naeblis11.mealplanner.desktop.google

import com.naeblis11.mealplanner.app.SettingsStore
import com.naeblis11.mealplanner.calendar.GoogleAuthException
import com.naeblis11.mealplanner.calendar.GoogleCalendar
import com.naeblis11.mealplanner.calendar.GoogleCalendarApi
import com.naeblis11.mealplanner.calendar.GoogleCalendarChoice
import com.naeblis11.mealplanner.calendar.GoogleCalendarSync
import com.naeblis11.mealplanner.calendar.GoogleMessages
import com.naeblis11.mealplanner.calendar.SendOutcome
import com.naeblis11.mealplanner.data.AppDatabase
import com.naeblis11.mealplanner.data.Household
import com.naeblis11.mealplanner.desktop.server.SecretsFile
import com.naeblis11.mealplanner.settings.GoogleControls
import com.naeblis11.mealplanner.settings.GoogleStatus
import java.awt.Desktop
import java.io.IOException
import java.net.URI
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The desktop's Google Calendar (P5-R4, P5-R5): the OAuth client, the sealed refresh token in [tokens], the chosen
 * calendar and the account's address in [settings], and "Send this week" through GoogleCalendarSync over
 * HttpGoogleCalendarApi.
 *
 * - The client is a hand-picked one in [secrets], else [builtInClient] (GoogleClients.inUse, Task 13); sign-in, the
 *   token refresh and the status all take it in that order.
 * - Sign out forgets the sign-in on this PC only; revoking at Google is the separate [revokeAccess] (S1).
 * - A client already in the secrets file (id and secret both) is never replaced from the app (S2); a half-written
 *   one is (P5-T5a). A hand-picked client file also overrides the built-in client, which isn't in the file.
 * - The only sign-in is the one made here, sealed in [tokens]. A Python server's sign-in in the secrets file is
 *   never read or taken over (no migration code, owner's decision 2026-10-05).
 *
 * Nothing here logs or says a token or the client secret.
 */
class GoogleAccount(
    private val secrets: SecretsFile,
    private val tokens: GoogleTokenStore,
    private val settings: SettingsStore,
    database: () -> AppDatabase,
    // Task 13: the client built into the app (GoogleClients.builtIn), used when the secrets file has none. Only Main
    // passes it, so no test ever picks up a client a developer's build put on the classpath.
    private val builtInClient: GoogleClient? = null,
    private val http: GoogleHttp = GoogleHttp(),
    private val endpoints: GoogleEndpoints = GoogleEndpoints.GOOGLE,
    browse: (URI) -> Unit = ::openInBrowser,
    private val log: (String) -> Unit = { System.err.println(it) },
    zone: () -> ZoneId = ZoneId::systemDefault,
    signInTimeoutMillis: Long = GoogleSignIn.TIMEOUT_MILLIS,
    // P6-R8: while an older household's Meal Planner PC is on the network, the sentence that refuses the send; null
    // otherwise. Main passes PeerWatch's yield; tests pass their own.
    private val sendRefusal: () -> String? = { null },
    // P6-FR1: one bounded look for other Meal Planner PCs before each send (PeerWatch.lookNow); Main passes it, and with
    // no discovery (tests, a preview without peers) there is nothing to look with and the send goes straight on.
    private val lookFirst: suspend () -> Unit = {},
    // P6-R4: the app's one Household (DesktopApp.identity.household), which dates an old database's household from its
    // file however the id is first made, a send before startup's dating included. Asked for at the first send. Null (tests)
    // is a Household of its own over the database.
    private val household: (() -> Household)? = null,
) : GoogleControls {
    private val choice = GoogleCalendarChoice(settings)
    private val loopback = GoogleSignIn(http, endpoints, browse, timeoutMillis = signInTimeoutMillis)

    // Guards the status's read-and-publish and the sign-in state it reads (the token file, the account's address), so a
    // sign-out can't interleave with a calendar list writing "Signed in as". Never held across a network call.
    private val stateLock = Any()

    // The access tokens for the refresh token in the file; dropped whenever that changes.
    @Volatile
    private var access: AccessTokens? = null

    private val _status = MutableStateFlow(readStatus())
    override val status: StateFlow<GoogleStatus> = _status.asStateFlow()
    override val picks: StateFlow<Int> = choice.picks

    private val sync: GoogleCalendarSync by lazy {
        val db = database()
        GoogleCalendarSync(
            db,
            api = ::api,
            calendarId = { choice.chosen.value?.id },
            household = household?.invoke() ?: Household(db),
            zone = zone,
        )
    }

    override fun signIn(onUrl: (String) -> Unit) {
        val client = GoogleClients.inUse(secrets, builtInClient) ?: throw GoogleSignInException(NO_CLIENT)
        val refresh = try {
            loopback.run(client, onUrl)
        } catch (e: SignInAlreadyWaitingException) {
            // Settings cancels its sign-in when it leaves the screen, so this shouldn't happen; if one is left waiting
            // anyway (its screen gone before the cancel could reach it), it would block every sign-in for five minutes.
            // It is stopped, and this one asks to be tried again.
            loopback.cancel()
            throw GoogleSignInException(SIGN_IN_WAS_WAITING)
        }
        try {
            tokens.save(refresh)
        } catch (e: IOException) {
            log("Meal Planner: the Google sign-in couldn't be saved (${e.javaClass.simpleName}).")
            throw GoogleSignInException(NOT_SAVED)
        }
        synchronized(this) { access = null }
        synchronized(stateLock) {
            settings.put(mapOf(ACCOUNT_KEY to ""))
            refreshStatus()
        }
        // P5-T6a: a sign-in to the calendar already chosen is all "Sign in to Google again" asks for.
        choice.signedIn()
    }

    override fun cancelSignIn() = loopback.cancel()

    override fun calendars(): List<GoogleCalendar> {
        val found = try {
            (api() ?: throw GoogleSignInException(GoogleMessages.SIGN_IN)).writableCalendars()
        } catch (e: GoogleAuthException) {
            throw GoogleSignInException(GoogleMessages.SIGN_IN_AGAIN)
        } catch (e: GoogleSignInException) {
            throw e
        } catch (e: IOException) {
            log("Meal Planner: couldn't get the Google calendars (${e.javaClass.simpleName}).")
            throw GoogleSignInException(CANT_LIST)
        }
        synchronized(stateLock) {
            // A sign-out while Google was answering wins: no "Signed in as" for an account no longer signed in.
            if (tokens.exists()) {
                // The primary calendar's id is the account's address: "Signed in as" without asking for the email scope.
                found.firstOrNull { it.primary }?.let { settings.put(mapOf(ACCOUNT_KEY to it.id)) }
                choice.chosen.value?.let { chosen -> found.firstOrNull { it.id == chosen.id }?.let { choice.rename(it.name) } }
            }
            refreshStatus()
        }
        return found
    }

    override fun choose(calendar: GoogleCalendar) {
        choice.choose(calendar.id, calendar.name)
        refreshStatus()
    }

    // S1: on this PC only. Google isn't told: the old server, or the Pi, may use this client and keep sending.
    override fun signOut() = synchronized(stateLock) {
        if (!tokens.delete()) throw IOException(NOT_FORGOTTEN)
        synchronized(this) { access = null }
        // Another account may sign in next; its calendars are its own. The google_event records stay.
        choice.clear()
        settings.put(mapOf(ACCOUNT_KEY to ""))
        refreshStatus()
    }

    // S1: only once the user has confirmed in Settings. Google is told first, and only once it has agreed is the sign-in
    // forgotten here, so a failure leaves everything as it was, to try again. P5-T5b: so does a token file that can't
    // be read: without the token nothing can be revoked, and signing out would leave the shared grant live while the
    // user believed it gone. With no token file at all there is nothing to revoke, and it signs out.
    override fun revokeAccess() {
        val refresh = try {
            tokens.load()
        } catch (e: IOException) {
            log("Meal Planner: the saved Google sign-in can't be read, so it wasn't revoked (${e.javaClass.simpleName}).")
            throw IOException(REVOKE_UNREADABLE)
        }
        if (refresh != null) {
            val reply = try {
                http.form(endpoints.revoke, mapOf("token" to refresh))
            } catch (e: IOException) {
                log("Meal Planner: couldn't reach Google to revoke the sign-in (${e.javaClass.simpleName}).")
                throw IOException(REVOKE_FAILED)
            }
            // 400: Google no longer knows the token (revoked already); it is gone there either way.
            if (reply.status != 200 && reply.status != 400) {
                log("Meal Planner: Google didn't revoke the sign-in (${reply.status}).")
                throw IOException(REVOKE_FAILED)
            }
        }
        signOut()
    }

    // S2: only while no client is set up; one already in the secrets file (the Python server's, perhaps) stays.
    // P5-T5a: a half-written client (an id without its secret, or the other way round) can refresh no token, so nothing
    // depends on it; the file's pair replaces both halves rather than leaving no way to sign in. The built-in client is
    // not in the secrets file, so a hand-picked file is taken over it (Task 13).
    override fun useClientFile(text: String) {
        require(GoogleClients.fromValues(secrets.read()) == null) { CLIENT_ALREADY_SET }
        GoogleClients.save(secrets, GoogleClients.fromJson(text))
        synchronized(this) { access = null }
        refreshStatus()
    }

    override suspend fun sendWeek(start: LocalDate): SendOutcome {
        // P6-FR1: look first (bounded, or wait for a look under way), so an older PC that came up since the last look is
        // known. The Calendar's "Sending to your calendar..." covers the wait.
        lookFirst()
        // P6-R8: only the older household's PC sends; nothing from here reaches Google meanwhile.
        sendRefusal()?.let { return SendOutcome.Failed(it) }
        return sync.sendWeek(start)
    }

    override fun toString(): String = "GoogleAccount(signedIn=${_status.value.signedIn})"

    /** The Calendar API for the sign-in in the token file; null when nobody is signed in. */
    @Synchronized
    private fun api(): GoogleCalendarApi? {
        val refresh = try {
            tokens.load()
        } catch (e: IOException) {
            log("Meal Planner: the saved Google sign-in can't be read here (${e.javaClass.simpleName}); sign in again.")
            throw GoogleAuthException(GoogleMessages.SIGN_IN_AGAIN)
        } ?: return null
        val client = GoogleClients.inUse(secrets, builtInClient) ?: throw GoogleAuthException(GoogleMessages.SIGN_IN_AGAIN)
        val current = access?.takeIf { it.isFor(refresh) } ?: AccessTokens(http, endpoints, client, refresh).also { access = it }
        return HttpGoogleCalendarApi(http, endpoints, current)
    }

    private fun readStatus(): GoogleStatus {
        val handPicked = GoogleClients.fromSecrets(secrets)
        return GoogleStatus(
            clientReady = (handPicked ?: builtInClient) != null,
            signedIn = tokens.exists(),
            account = settings.getString(ACCOUNT_KEY)?.takeIf { it.isNotEmpty() },
            calendar = choice.chosen.value,
            // Only a hand-picked client is shown, with how to change it by hand; the built-in one has nothing to change.
            clientId = handPicked?.id,
        )
    }

    // Read and published under one lock, so an older read can never be published over a newer one.
    private fun refreshStatus() {
        synchronized(stateLock) { _status.value = readStatus() }
    }

    companion object {
        /** Settings: the signed-in account's address. */
        const val ACCOUNT_KEY = "google_account"

        const val NO_CLIENT = "Choose your Google OAuth client file first."
        const val SIGN_IN_WAS_WAITING = "An earlier Google sign-in was still waiting for the browser. It has been stopped: sign in again."
        const val NOT_SAVED = "Signed in, but the sign-in couldn't be kept on this PC. Try again."
        const val NOT_FORGOTTEN = "The Google sign-in file couldn't be deleted. Close anything that has it open, then try again."
        const val CANT_LIST = "Couldn't get your calendars from Google. Check this PC's internet connection, then try again."
        const val CLIENT_ALREADY_SET =
            "A Google OAuth client is already set up in the secrets file, so Meal Planner leaves it as it is. Change it there by hand to use another."
        const val REVOKE_FAILED = "Couldn't revoke access at Google, so nothing was changed. Check this PC's internet connection, then try again."
        const val REVOKE_UNREADABLE =
            "Meal Planner can't read its saved Google sign-in, so it can't revoke it. Remove Meal Planner's access at myaccount.google.com/permissions, then Sign out here."
    }
}

/** The user's default browser; throws when Windows has none, and Settings shows the address to open instead. */
internal fun openInBrowser(uri: URI) {
    Desktop.getDesktop().browse(uri)
}
