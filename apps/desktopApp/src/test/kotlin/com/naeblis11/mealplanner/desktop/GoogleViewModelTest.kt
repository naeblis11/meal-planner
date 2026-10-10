package com.naeblis11.mealplanner.desktop

import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.calendar.ChosenGoogleCalendar
import com.naeblis11.mealplanner.calendar.GoogleCalendar
import com.naeblis11.mealplanner.desktop.google.GoogleAccount
import com.naeblis11.mealplanner.settings.CALENDAR_NOT_IN_ACCOUNT
import com.naeblis11.mealplanner.settings.CHOOSE_FAILED
import com.naeblis11.mealplanner.settings.CLIENT_FILE_FAILED
import com.naeblis11.mealplanner.settings.CLIENT_FILE_TOO_BIG
import com.naeblis11.mealplanner.settings.GooglePhase
import com.naeblis11.mealplanner.settings.MAX_CLIENT_FILE_BYTES
import com.naeblis11.mealplanner.settings.GoogleStatus
import com.naeblis11.mealplanner.settings.GoogleViewModel
import com.naeblis11.mealplanner.ui.PickedFile
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P5-R5: Settings' Google section, one step at a time, each off the UI thread. */
class GoogleViewModelTest {
    private val created = mutableListOf<GoogleViewModel>()

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
    }

    private fun viewModel(controls: FakeGoogleControls, io: CoroutineDispatcher = Dispatchers.IO) =
        GoogleViewModel(controls, io).also { created += it }

    @Test
    fun aFirstSignInGoesStraightOnToTheCalendars() = runBlocking {
        val vm = viewModel(FakeGoogleControls())
        vm.signIn()
        val choosing = withTimeout(5_000) { vm.state.first { it.calendars != null } }
        assertEquals(listOf("Family", "me@example.com"), choosing.calendars!!.map { it.name })
        assertTrue(choosing.status.signedIn)
        assertEquals(GooglePhase.IDLE, choosing.phase)
    }

    @Test
    fun choosingACalendarClosesTheListAndCountsAsAPick() = runBlocking {
        val controls = FakeGoogleControls(GoogleStatus(clientReady = true, signedIn = true))
        val vm = viewModel(controls)
        vm.loadCalendars()
        val listed = withTimeout(5_000) { vm.state.first { it.calendars != null } }.calendars!!
        vm.choose(listed.first())
        val chosen = withTimeout(5_000) { vm.state.first { it.calendars == null && it.status.calendar != null } }
        assertEquals("Family", chosen.status.calendar!!.name)
        assertEquals(1, controls.picks.value)
    }

    @Test
    fun aSignInThatFailsSaysWhy() = runBlocking {
        val vm = viewModel(FakeGoogleControls().apply { signInFails = IOException("Timed out waiting for the Google sign-in. Try again.") })
        vm.signIn()
        val failed = withTimeout(5_000) { vm.state.first { it.error != null } }
        assertEquals("Timed out waiting for the Google sign-in. Try again.", failed.error)
        assertEquals(GooglePhase.IDLE, failed.phase)
        assertFalse(failed.status.signedIn)
    }

    @Test
    fun aCancelledSignInGoesBackQuietly() = runBlocking {
        val vm = viewModel(FakeGoogleControls().apply { waitForCancel = true })
        vm.signIn()
        val waiting = withTimeout(5_000) { vm.state.first { it.signInUrl != null } }
        assertEquals(GooglePhase.SIGNING_IN, waiting.phase)
        assertEquals(FakeGoogleControls.URL, waiting.signInUrl)
        vm.cancelSignIn()
        val back = withTimeout(5_000) { vm.state.first { it.phase == GooglePhase.IDLE } }
        assertNull(back.error)
        assertNull(back.signInUrl)
    }

    @Test
    fun signingOutForgetsTheAccountAndCalendar() = runBlocking {
        val vm = viewModel(
            FakeGoogleControls(GoogleStatus(true, true, "me@example.com", ChosenGoogleCalendar("family@group.calendar.google.com", "Family"))),
        )
        vm.signOut()
        val out = withTimeout(5_000) { vm.state.first { !it.status.signedIn && it.phase == GooglePhase.IDLE } }
        assertNull(out.status.calendar)
        assertNull(out.status.account)
    }

    @Test
    fun aClientFileIsTakenAndAnyOtherFileSaysWhatToDo() = runBlocking {
        val controls = FakeGoogleControls(GoogleStatus(clientReady = false, signedIn = false))
        val vm = viewModel(controls)
        vm.useClientFile(PickedFile({ "notes.json" }) { "{}".byteInputStream() })
        assertEquals(FakeGoogleControls.BAD, withTimeout(5_000) { vm.state.first { it.error != null } }.error)

        vm.useClientFile(PickedFile({ "client.json" }) { "{\"installed\":{}}".byteInputStream() })
        val taken = withTimeout(5_000) { vm.state.first { it.status.clientReady && it.phase == GooglePhase.IDLE } }
        assertNull(taken.error)
        assertEquals("{\"installed\":{}}", controls.clientText)
    }

    @Test
    fun revokingAccessForgetsTheAccount() = runBlocking {
        val controls = FakeGoogleControls(GoogleStatus(true, true, "me@example.com"))
        val vm = viewModel(controls)
        vm.revokeAccess()
        withTimeout(5_000) { vm.state.first { !it.status.signedIn && it.phase == GooglePhase.IDLE } }
        assertEquals(1, controls.revokes)
    }

    @Test
    fun aRevokeThatFailsSaysWhyAndStaysSignedIn() = runBlocking {
        // P5-T5b: GoogleAccount's own sentence (where to remove the access by hand) is what the user reads.
        val controls = FakeGoogleControls(GoogleStatus(true, true, "me@example.com")).apply { revokeFails = IOException(GoogleAccount.REVOKE_UNREADABLE) }
        val vm = viewModel(controls)
        vm.revokeAccess()
        val failed = withTimeout(5_000) { vm.state.first { it.error != null } }
        assertEquals(GoogleAccount.REVOKE_UNREADABLE, failed.error)
        assertEquals(GooglePhase.IDLE, failed.phase)
        assertTrue(failed.status.signedIn)
        assertEquals(0, controls.revokes)
    }

    @Test
    fun signingInAgainToTheSameAccountSaysWhoAndKeepsTheCalendar() = runBlocking {
        // P5-T6a: the Calendar's "Sign in to Google again" banner goes on this pick, with no calendar chosen again.
        // P5-T6b: the account is checked again, so "Signed in as" comes back and the calendar is seen to be its.
        val family = ChosenGoogleCalendar("family@group.calendar.google.com", "Family")
        val controls = FakeGoogleControls(GoogleStatus(true, true, "me@example.com", family))
        val vm = viewModel(controls)
        vm.signIn()
        val done = withTimeout(5_000) { vm.state.first { it.phase == GooglePhase.IDLE && controls.picks.value == 1 } }
        assertNull(done.calendars)
        assertNull(done.note)
        assertNull(done.error)
        assertEquals(family, done.status.calendar)
        assertEquals("me@example.com", done.status.account)
    }

    @Test
    fun signingInToAnAccountThatCantSeeTheCalendarOpensTheList() = runBlocking {
        // P5-T6b: another account signed in; the calendar chosen before isn't among its calendars.
        val controls = FakeGoogleControls(GoogleStatus(true, true, "me@example.com", ChosenGoogleCalendar("family@group.calendar.google.com", "Family")))
        controls.calendarList = listOf(GoogleCalendar("you@example.com", "you@example.com", primary = true))
        val vm = viewModel(controls)
        vm.signIn()
        val choosing = withTimeout(5_000) { vm.state.first { it.calendars != null } }
        assertEquals(CALENDAR_NOT_IN_ACCOUNT, choosing.note)
        assertEquals(listOf("you@example.com"), choosing.calendars!!.map { it.id })
        assertEquals(GooglePhase.IDLE, choosing.phase)
        assertEquals("you@example.com", choosing.status.account)
        // Choosing one closes the list and the note.
        vm.choose(choosing.calendars!!.single())
        val chosen = withTimeout(5_000) { vm.state.first { it.calendars == null && it.status.calendar?.id == "you@example.com" } }
        assertNull(chosen.note)
    }

    @Test
    fun aCalendarListThatFailsAfterASignInLeavesItSignedIn() = runBlocking {
        val family = ChosenGoogleCalendar("family@group.calendar.google.com", "Family")
        val controls = FakeGoogleControls(GoogleStatus(true, true, "me@example.com", family)).apply { calendarsFail = IOException(GoogleAccount.CANT_LIST) }
        val vm = viewModel(controls)
        vm.signIn()
        val failed = withTimeout(5_000) { vm.state.first { it.error != null } }
        assertEquals(GoogleAccount.CANT_LIST, failed.error)
        assertEquals(GooglePhase.IDLE, failed.phase)
        assertNull(failed.calendars)
        assertTrue(failed.status.signedIn)
        assertEquals(family, failed.status.calendar)
        assertEquals(1, controls.picks.value)
    }

    @Test
    fun aChoiceThatCantBeKeptSaysSo() = runBlocking {
        val controls = FakeGoogleControls(GoogleStatus(clientReady = true, signedIn = true)).apply { chooseFails = IllegalStateException("settings") }
        val vm = viewModel(controls)
        vm.loadCalendars()
        val listed = withTimeout(5_000) { vm.state.first { it.calendars != null } }.calendars!!
        vm.choose(listed.first())
        val failed = withTimeout(5_000) { vm.state.first { it.error != null } }
        assertEquals(CHOOSE_FAILED, failed.error)
        assertEquals(GooglePhase.IDLE, failed.phase)
        assertNull(failed.status.calendar)
    }

    @Test
    fun aClientFileThatCantBeReadSaysSo() = runBlocking {
        val controls = FakeGoogleControls(GoogleStatus(clientReady = false, signedIn = false))
        val vm = viewModel(controls)
        vm.useClientFile(PickedFile({ "client.json" }) { throw IOException("The file is locked") })
        val failed = withTimeout(5_000) { vm.state.first { it.error != null } }
        assertEquals(CLIENT_FILE_FAILED, failed.error)
        assertEquals(GooglePhase.IDLE, failed.phase)
        assertNull(controls.clientText)
    }

    @Test
    fun aClientFileTooBigIsNotTaken() = runBlocking {
        val controls = FakeGoogleControls(GoogleStatus(clientReady = false, signedIn = false))
        val vm = viewModel(controls)
        val big = "{\"installed\":{}}" + " ".repeat(MAX_CLIENT_FILE_BYTES)
        vm.useClientFile(PickedFile({ "client.json" }) { big.byteInputStream() })
        assertEquals(CLIENT_FILE_TOO_BIG, withTimeout(5_000) { vm.state.first { it.error != null } }.error)
        assertNull(controls.clientText)
        // One just under the cap is read.
        val fits = "{\"installed\":{}}".padEnd(MAX_CLIENT_FILE_BYTES)
        vm.useClientFile(PickedFile({ "client.json" }) { fits.byteInputStream() })
        withTimeout(5_000) { vm.state.first { it.status.clientReady && it.phase == GooglePhase.IDLE } }
        assertEquals(fits, controls.clientText)
    }

    @Test
    fun anErrorThatIsNoExceptionLeavesTheSectionUsable() = runBlocking {
        // A linkage Error (not an Exception) gets past every catch: the phase still goes back to IDLE.
        val controls = FakeGoogleControls().apply { signInError = NoClassDefFoundError("a missing class") }
        val vm = viewModel(controls)
        vm.signIn()
        // A section left stuck ignores every later tap; one back at IDLE takes the next.
        withTimeout(5_000) {
            while (vm.state.value.calendars == null) {
                vm.signIn()
                delay(20)
            }
        }
        assertTrue(vm.state.value.status.signedIn)
        assertNull(controls.signInError)
    }

    @Test
    fun nothingElseStartsWhileASignInIsUnderWay() = runBlocking {
        val controls = FakeGoogleControls(GoogleStatus(true, true, "me@example.com")).apply { waitForCancel = true }
        val vm = viewModel(controls)
        vm.signIn()
        withTimeout(5_000) { vm.state.first { it.signInUrl != null } }
        vm.signOut()
        vm.revokeAccess()
        vm.loadCalendars()
        vm.useClientFile(PickedFile({ "client.json" }) { "{\"installed\":{}}".byteInputStream() })
        assertEquals(GooglePhase.SIGNING_IN, vm.state.value.phase)
        assertEquals(0, controls.revokes)
        assertNull(controls.clientText)
        assertTrue(controls.status.value.signedIn)
        vm.cancelSignIn()
        assertNull(withTimeout(5_000) { vm.state.first { it.phase == GooglePhase.IDLE } }.error)
    }

    @Test
    fun everyCallThatWaitsRunsOnTheIoDispatcher() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "google-io") }
        try {
            val controls = FakeGoogleControls(GoogleStatus(clientReady = false, signedIn = false))
            val vm = viewModel(controls, executor.asCoroutineDispatcher())
            vm.useClientFile(PickedFile({ "client.json" }) { "{\"installed\":{}}".byteInputStream() })
            withTimeout(5_000) { vm.state.first { it.status.clientReady && it.phase == GooglePhase.IDLE } }
            vm.signIn()
            val listed = withTimeout(5_000) { vm.state.first { it.calendars != null } }.calendars!!
            vm.choose(listed.first())
            withTimeout(5_000) { vm.state.first { it.status.calendar != null && it.phase == GooglePhase.IDLE } }
            vm.signOut()
            withTimeout(5_000) { vm.state.first { !it.status.signedIn && it.phase == GooglePhase.IDLE } }
            assertEquals(listOf("useClientFile", "signIn", "calendars", "choose", "signOut").size, controls.threads.size)
            // Coroutines' debug mode adds " @coroutine#n" to the thread's name.
            assertTrue(controls.threads.toString(), controls.threads.all { it.substringBefore(" @") == "google-io" })
        } finally {
            executor.shutdownNow()
        }
    }
}
