package com.naeblis11.mealplanner.desktop

import com.naeblis11.mealplanner.calendar.ChosenGoogleCalendar
import com.naeblis11.mealplanner.calendar.GoogleCalendar
import com.naeblis11.mealplanner.calendar.GoogleMessages
import com.naeblis11.mealplanner.calendar.SendOutcome
import com.naeblis11.mealplanner.calendar.SignInCancelledException
import com.naeblis11.mealplanner.settings.GoogleControls
import com.naeblis11.mealplanner.settings.GoogleStatus
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Google as Settings and the Calendar see it, without Google: a sign-in that works (or fails, or waits to be cancelled). */
class FakeGoogleControls(initial: GoogleStatus = GoogleStatus(clientReady = true, signedIn = false)) : GoogleControls {
    val flow = MutableStateFlow(initial)
    override val status: StateFlow<GoogleStatus> = flow
    private val pickCount = MutableStateFlow(0)
    override val picks: StateFlow<Int> = pickCount
    val sent = CopyOnWriteArrayList<LocalDate>()

    /** The threads the blocking calls ran on: none may be the test's (or the UI's) own. */
    val threads = CopyOnWriteArrayList<String>()

    @Volatile
    var signInFails: IOException? = null

    /** The sign-in waits until cancelSignIn, then ends as cancelled. */
    @Volatile
    var waitForCancel = false
    private val cancelled = CountDownLatch(1)

    @Volatile
    var outcome: SendOutcome = SendOutcome.NeedsSettings(GoogleMessages.SIGN_IN)

    @Volatile
    var clientText: String? = null

    @Volatile
    var revokeFails: IOException? = null

    @Volatile
    var calendarsFail: IOException? = null

    @Volatile
    var chooseFails: RuntimeException? = null

    /** Thrown once by the next sign-in: an Error, as a linkage failure is, not an Exception. */
    @Volatile
    var signInError: Error? = null

    /** The calendars the signed-in account can see; another account's list, for a switched account. */
    @Volatile
    var calendarList = listOf(GoogleCalendar("family@group.calendar.google.com", "Family"), GoogleCalendar("me@example.com", "me@example.com", primary = true))

    @Volatile
    var cancels = 0

    override fun signIn(onUrl: (String) -> Unit) {
        threads += Thread.currentThread().name
        onUrl(URL)
        signInError?.let {
            signInError = null
            throw it
        }
        if (waitForCancel) {
            cancelled.await(5, TimeUnit.SECONDS)
            throw SignInCancelledException()
        }
        signInFails?.let { throw it }
        // As GoogleAccount does: the account is said again only by the next calendar list.
        flow.value = flow.value.copy(signedIn = true, account = null)
        // P5-T6a: as GoogleAccount does, a fresh sign-in counts as a pick.
        pickCount.value += 1
    }

    override fun cancelSignIn() {
        cancels++
        cancelled.countDown()
    }

    override fun calendars(): List<GoogleCalendar> {
        threads += Thread.currentThread().name
        calendarsFail?.let { throw it }
        val list = calendarList
        flow.value = flow.value.copy(account = list.firstOrNull { it.primary }?.id)
        return list
    }

    override fun choose(calendar: GoogleCalendar) {
        threads += Thread.currentThread().name
        chooseFails?.let { throw it }
        flow.value = flow.value.copy(calendar = ChosenGoogleCalendar(calendar.id, calendar.name))
        pickCount.value += 1
    }

    override fun signOut() {
        threads += Thread.currentThread().name
        flow.value = GoogleStatus(clientReady = flow.value.clientReady, signedIn = false)
    }

    @Volatile
    var revokes = 0

    override fun revokeAccess() {
        threads += Thread.currentThread().name
        revokeFails?.let { throw it }
        revokes++
        signOut()
    }

    override fun useClientFile(text: String) {
        threads += Thread.currentThread().name
        require("installed" in text) { BAD }
        clientText = text
        flow.value = flow.value.copy(clientReady = true)
    }

    override suspend fun sendWeek(start: LocalDate): SendOutcome {
        sent += start
        return outcome
    }

    companion object {
        const val URL = "https://accounts.google.com/o/oauth2/v2/auth?client_id=client-1"
        const val BAD = "That file isn't a Google OAuth client for a desktop app."
    }
}
