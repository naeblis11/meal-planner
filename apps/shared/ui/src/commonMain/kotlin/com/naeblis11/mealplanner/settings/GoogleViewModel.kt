package com.naeblis11.mealplanner.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.calendar.GoogleCalendar
import com.naeblis11.mealplanner.calendar.SignInCancelledException
import com.naeblis11.mealplanner.ui.PickedFile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What Settings' Google section is doing; one thing at a time. CHOOSING: a calendar choice being saved. */
enum class GooglePhase { IDLE, SIGNING_IN, LOADING, CHOOSING, SIGNING_OUT, READING_FILE }

/** Settings' Google section: where Google stands, what is under way, the calendars to choose from (open when not null), and what went wrong. */
data class GoogleState(
    val status: GoogleStatus,
    val phase: GooglePhase = GooglePhase.IDLE,
    val signInUrl: String? = null,
    val calendars: List<GoogleCalendar>? = null,
    val error: String? = null,
    /** Said above the calendars: why they are open (P5-T6b). */
    val note: String? = null,
)

const val SIGN_IN_FAILED = "The Google sign-in didn't finish. Try again."
const val CALENDARS_FAILED = "Couldn't get your calendars from Google. Try again."
const val CHOOSE_FAILED = "Couldn't keep that calendar choice. Try again."
const val SIGN_OUT_FAILED = "Couldn't sign out of Google. Try again."
const val CLIENT_FILE_FAILED = "That client file couldn't be used. Try again."
const val CLIENT_FILE_TOO_BIG =
    "That file is too big to be a Google OAuth client file. Choose the JSON file you downloaded for your Desktop app client."
const val CALENDAR_NOT_IN_ACCOUNT = "This Google account can't see the calendar you chose before. Choose one to send to."

/** A Google client file is well under a kilobyte; anything over this is not one, and isn't read. */
const val MAX_CLIENT_FILE_BYTES = 64 * 1024

/**
 * Settings' Google Calendar section on the desktop (P5-R5). Every call to [controls] but cancelSignIn runs on
 * [ioDispatcher] (they wait on the network, the disk or a settings write); one runs at a time, and a tap while one runs
 * is ignored. Every sign-in goes on to Google's calendar list (P5-T6b): it says who is signed in, and whether the
 * calendar chosen before is still that account's. Settings leaving the screen ([left]), or this view model being
 * cleared, cancels a sign-in still waiting on the browser.
 */
class GoogleViewModel(
    private val controls: GoogleControls,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Local(
        val phase: GooglePhase = GooglePhase.IDLE,
        val signInUrl: String? = null,
        val calendars: List<GoogleCalendar>? = null,
        val error: String? = null,
        val note: String? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<GoogleState> = combine(controls.status, local) { status, mine ->
        GoogleState(status, mine.phase, mine.signInUrl, mine.calendars, mine.error, mine.note)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GoogleState(controls.status.value))

    /**
     * Signs in (again, too: Google may have stopped honouring the last sign-in), then lists the account's calendars
     * (P5-T6b). The calendar chosen before, when the list has it, stays chosen and the list stays closed; otherwise the
     * list opens, saying why when a calendar had been chosen. A list that fails says so; the sign-in itself is done.
     */
    fun signIn() = claim(GooglePhase.SIGNING_IN) {
        try {
            withContext(ioDispatcher) { controls.signIn { url -> local.update { it.copy(signInUrl = url) } } }
        } catch (e: SignInCancelledException) {
            local.value = Local()
            return@claim
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            local.value = Local(error = e.message ?: SIGN_IN_FAILED)
            return@claim
        } catch (e: Exception) {
            local.value = Local(error = SIGN_IN_FAILED)
            return@claim
        }
        val chosen = controls.status.value.calendar
        local.value = Local(GooglePhase.LOADING)
        listCalendars { found ->
            when {
                chosen == null -> Local(calendars = found)
                found.any { it.id == chosen.id } -> Local()
                else -> Local(calendars = found, note = CALENDAR_NOT_IN_ACCOUNT)
            }
        }
    }

    fun cancelSignIn() = controls.cancelSignIn()

    /** Settings has left the screen: a sign-in waiting on the browser is cancelled, so its answer address closes. */
    fun left() {
        if (local.value.phase == GooglePhase.SIGNING_IN) controls.cancelSignIn()
    }

    fun loadCalendars() = claim(GooglePhase.LOADING) { listCalendars { found -> Local(calendars = found) } }

    fun choose(calendar: GoogleCalendar) = claim(GooglePhase.CHOOSING) {
        try {
            withContext(ioDispatcher) { controls.choose(calendar) }
            local.value = Local()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            local.value = Local(error = CHOOSE_FAILED)
        }
    }

    fun cancelChoosing() {
        local.update { it.copy(calendars = null, note = null) }
    }

    fun signOut() = leave(controls::signOut)

    /** S1: revokes Meal Planner's access at Google, once the user has confirmed in Settings; then it is signed out. */
    fun revokeAccess() = leave(controls::revokeAccess)

    /** Reads Google's client file the user picked (no more than MAX_CLIENT_FILE_BYTES) and hands it to [controls]. */
    fun useClientFile(file: PickedFile) = claim(GooglePhase.READING_FILE) {
        try {
            withContext(ioDispatcher) {
                val bytes = file.open().use { readAtMost(it, MAX_CLIENT_FILE_BYTES + 1) }
                require(bytes.size <= MAX_CLIENT_FILE_BYTES) { CLIENT_FILE_TOO_BIG }
                controls.useClientFile(bytes.toString(Charsets.UTF_8))
            }
            local.value = Local()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            local.value = Local(error = e.message ?: CLIENT_FILE_FAILED)
        } catch (e: Exception) {
            local.value = Local(error = CLIENT_FILE_FAILED)
        }
    }

    override fun onCleared() = left()

    // Sign out, or revoke then sign out: a failure's IOException says what happened (GoogleAccount's REVOKE_FAILED or
    // REVOKE_UNREADABLE, say), and the user stays signed in.
    private fun leave(call: () -> Unit) = claim(GooglePhase.SIGNING_OUT) {
        try {
            withContext(ioDispatcher) { call() }
            local.value = Local()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            local.value = Local(error = e.message ?: SIGN_OUT_FAILED)
        } catch (e: Exception) {
            local.value = Local(error = SIGN_OUT_FAILED)
        }
    }

    private suspend fun listCalendars(then: (List<GoogleCalendar>) -> Local) {
        try {
            val found = withContext(ioDispatcher) { controls.calendars() }
            local.value = then(found)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            local.value = Local(error = e.message ?: CALENDARS_FAILED)
        } catch (e: Exception) {
            local.value = Local(error = CALENDARS_FAILED)
        }
    }

    // Runs [work] once [phase] is claimed: nothing else under way, and an error on screen goes with it. Each work ends
    // by setting the state it leaves; one that throws instead (an Error, such as a linkage failure, gets past every
    // catch) still gives the phase back, so the section is never stuck, and the failure is logged by its kind only.
    private fun claim(phase: GooglePhase, work: suspend () -> Unit) {
        if (!begin(phase)) return
        viewModelScope.launch(REPORT_FAILURE) {
            var ended = false
            try {
                work()
                ended = true
            } finally {
                if (!ended) local.value = Local()
            }
        }
    }

    private fun begin(phase: GooglePhase): Boolean {
        while (true) {
            val now = local.value
            if (now.phase != GooglePhase.IDLE) return false
            if (local.compareAndSet(now, Local(phase))) return true
        }
    }
}

// What gets past the work's own catches lands in the log (by kind: a message might say more than it should), as an
// uncaught failure on the UI thread would.
private val REPORT_FAILURE = CoroutineExceptionHandler { _, failure ->
    System.err.println("Meal Planner: Settings' Google section stopped (${failure.javaClass.name}).")
}

// Up to [limit] bytes of [input] (InputStream.readNBytes is newer than Android's minimum, and this is common code).
private fun readAtMost(input: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
