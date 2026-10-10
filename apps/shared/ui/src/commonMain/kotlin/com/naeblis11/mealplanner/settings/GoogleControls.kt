package com.naeblis11.mealplanner.settings

import com.naeblis11.mealplanner.calendar.ChosenGoogleCalendar
import com.naeblis11.mealplanner.calendar.GoogleCalendar
import com.naeblis11.mealplanner.calendar.SendOutcome
import java.time.LocalDate
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the desktop's Google stands (P5-R5). [clientReady]: an OAuth client is known. [account]: the address signed
 * in as, once Google's calendar list has said it; null before that, or signed out.
 */
data class GoogleStatus(
    val clientReady: Boolean,
    val signedIn: Boolean,
    val account: String? = null,
    val calendar: ChosenGoogleCalendar? = null,
    /**
     * The hand-picked OAuth client in use, which Settings shows read-only (S2); null while there is none, and while the
     * client in use is the one built into the app, which has nothing to change by hand.
     */
    val clientId: String? = null,
)

/**
 * The desktop's Google Calendar for Settings and the Calendar screen, behind an interface so shared code never sees
 * java.net.http or DPAPI; null on Android, whose calendar send is unchanged. [cancelSignIn] returns at once and
 * [sendWeek] suspends; every other call blocks ([choose] only on writing a small setting, the rest on the network or
 * the disk), so call them off the UI thread. Failures are IOException with a sentence for the user.
 */
interface GoogleControls {
    val status: StateFlow<GoogleStatus>

    /** Calendars picked, and fresh sign-ins, since the app started; a pick clears the Calendar's "Settings" banner (P5-T6a). */
    val picks: StateFlow<Int>

    /**
     * Opens the browser on Google's consent page and waits (up to five minutes) for the answer; [onUrl] gets the page's
     * address first, for when no browser opens. A [cancelSignIn] ends it with SignInCancelledException.
     */
    fun signIn(onUrl: (String) -> Unit)

    fun cancelSignIn()

    /** The calendars the signed-in account can write to. */
    fun calendars(): List<GoogleCalendar>

    fun choose(calendar: GoogleCalendar)

    /** Forgets the sign-in on this PC (and the chosen calendar). Google is not told: another device may use the same client. */
    fun signOut()

    /**
     * Revokes Meal Planner's access at Google, then signs out; Settings asks first, as it also ends the link of other
     * devices (the old server, the Pi) that use the same client. A failure changes nothing.
     */
    fun revokeAccess()

    /**
     * Takes the OAuth client from Google's downloaded client file, only while no complete one (id and secret) is set up
     * (S2; a half-written one is replaced, P5-T5a); IllegalArgumentException with what to do otherwise, or for any other
     * file.
     */
    fun useClientFile(text: String)

    /** "Send this week" to the chosen Google calendar. */
    suspend fun sendWeek(start: LocalDate): SendOutcome
}
