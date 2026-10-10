package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.calendar.ChosenGoogleCalendar
import com.naeblis11.mealplanner.calendar.GoogleCalendar
import com.naeblis11.mealplanner.settings.ABOUT_DESKTOP_BUILT_WITH
import com.naeblis11.mealplanner.settings.ABOUT_DESKTOP_CALENDAR
import com.naeblis11.mealplanner.settings.ABOUT_DESKTOP_DATA
import com.naeblis11.mealplanner.settings.ABOUT_DESKTOP_FONTS
import com.naeblis11.mealplanner.settings.ABOUT_PHONE_DATA
import com.naeblis11.mealplanner.settings.BackupState
import com.naeblis11.mealplanner.settings.CALENDAR_NOT_IN_ACCOUNT
import com.naeblis11.mealplanner.settings.CHOOSE_ANOTHER_CALENDAR
import com.naeblis11.mealplanner.settings.CHOOSE_CLIENT_FILE
import com.naeblis11.mealplanner.settings.CLIENT_BY_HAND
import com.naeblis11.mealplanner.settings.CalendarSetupState
import com.naeblis11.mealplanner.settings.GOOGLE_LOADING
import com.naeblis11.mealplanner.settings.GOOGLE_NEEDS_CLIENT
import com.naeblis11.mealplanner.settings.GOOGLE_SIGNED_OUT
import com.naeblis11.mealplanner.settings.GOOGLE_SIGN_IN_AGAIN
import com.naeblis11.mealplanner.settings.GOOGLE_WAITING
import com.naeblis11.mealplanner.settings.GoogleActions
import com.naeblis11.mealplanner.settings.GooglePhase
import com.naeblis11.mealplanner.settings.GoogleState
import com.naeblis11.mealplanner.settings.GoogleStatus
import com.naeblis11.mealplanner.settings.KEEP_ACCESS
import com.naeblis11.mealplanner.settings.NO_GOOGLE_CALENDAR
import com.naeblis11.mealplanner.settings.REVOKE_ACCESS
import com.naeblis11.mealplanner.settings.REVOKE_CONFIRM
import com.naeblis11.mealplanner.settings.REVOKE_WARNING
import com.naeblis11.mealplanner.settings.SIGN_IN_WITH_GOOGLE
import com.naeblis11.mealplanner.settings.SIGN_OUT
import com.naeblis11.mealplanner.settings.SettingsScreen
import com.naeblis11.mealplanner.settings.clientInUse
import com.naeblis11.mealplanner.settings.sendingTo
import com.naeblis11.mealplanner.settings.signedInAs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** P5-R5: Settings > Google Calendar on the desktop: the client, Sign in, Signed in as, Choose calendar, Sign out. */
class GoogleSettingTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: GoogleState, actions: GoogleActions = GoogleActions()) = compose.showAt(600.dp) {
        SettingsScreen(
            backup = BackupState.Idle,
            calendar = CalendarSetupState(),
            version = "dev",
            onBack = {},
            onExport = {},
            onImport = {},
            onDismissBackup = {},
            onSetUpCalendar = {},
            onChooseCalendar = {},
            onCancelChoosing = {},
            onOpenAppSettings = {},
            google = state,
            googleActions = actions,
        )
    }

    @Test
    fun withoutAClientItSaysHowToMakeOneAndTakesItsFile() {
        var chosen = 0
        show(GoogleState(GoogleStatus(clientReady = false, signedIn = false)), GoogleActions(chooseClientFile = { chosen++ }))
        compose.onNodeWithText(GOOGLE_NEEDS_CLIENT).assertExists()
        compose.onNodeWithText(CHOOSE_CLIENT_FILE).click()
        assertEquals(1, chosen)
        // The phone's calendar setup is not offered on the desktop.
        compose.onAllNodesWithText("Set up calendar sending").assertCountEquals(0)
    }

    @Test
    fun signedOutItOffersSignIn() {
        var signIns = 0
        show(GoogleState(GoogleStatus(clientReady = true, signedIn = false)), GoogleActions(signIn = { signIns++ }))
        compose.onNodeWithText(GOOGLE_SIGNED_OUT).assertExists()
        compose.onNodeWithText(SIGN_IN_WITH_GOOGLE).click()
        assertEquals(1, signIns)
    }

    @Test
    fun whileSigningInItShowsTheAddressAndCancel() {
        var cancels = 0
        val url = "https://accounts.google.com/o/oauth2/v2/auth?client_id=client-1"
        show(
            GoogleState(GoogleStatus(clientReady = true, signedIn = false), phase = GooglePhase.SIGNING_IN, signInUrl = url),
            GoogleActions(cancelSignIn = { cancels++ }),
        )
        compose.onNodeWithText(GOOGLE_WAITING).assertExists()
        compose.onNodeWithText(url).assertExists()
        compose.onAllNodesWithText(SIGN_IN_WITH_GOOGLE).assertCountEquals(0)
        compose.onNodeWithText("Cancel").click()
        assertEquals(1, cancels)
    }

    @Test
    fun signedInItShowsTheAccountAndCalendarAndOffersSignOut() {
        var loads = 0
        var outs = 0
        show(
            GoogleState(GoogleStatus(true, true, "me@example.com", ChosenGoogleCalendar("family@group.calendar.google.com", "Family"))),
            GoogleActions(loadCalendars = { loads++ }, signOut = { outs++ }),
        )
        compose.onNodeWithText(signedInAs("me@example.com")).assertExists()
        compose.onNodeWithText(sendingTo("Family")).assertExists()
        compose.onNodeWithText(CHOOSE_ANOTHER_CALENDAR).click()
        compose.onNodeWithText(SIGN_OUT).click()
        assertEquals(1, loads)
        assertEquals(1, outs)
    }

    @Test
    fun theCalendarsAreListedToChooseFrom() {
        var picked: GoogleCalendar? = null
        val family = GoogleCalendar("family@group.calendar.google.com", "Family")
        show(
            GoogleState(GoogleStatus(true, true), calendars = listOf(family, GoogleCalendar("me@example.com", "me@example.com", primary = true))),
            GoogleActions(choose = { picked = it }),
        )
        compose.onNodeWithText(signedInAs(null)).assertExists()
        compose.onNodeWithText(NO_GOOGLE_CALENDAR).assertExists()
        compose.onNodeWithText("Family").click()
        assertEquals(family, picked)
    }

    @Test
    fun withAClientTheFilePickerIsNotOfferedAndTheClientIsShown() {
        // S2: a client already set up (the Python server's, say) is shown read-only, never replaced from here.
        show(GoogleState(GoogleStatus(clientReady = true, signedIn = false, clientId = "abc.apps.googleusercontent.com")))
        compose.onAllNodesWithText(CHOOSE_CLIENT_FILE).assertCountEquals(0)
        compose.onNodeWithText(clientInUse("abc.apps.googleusercontent.com")).assertExists()
        compose.onNodeWithText(CLIENT_BY_HAND).assertExists()
        compose.onNodeWithText(SIGN_IN_WITH_GOOGLE).assertExists()
    }

    @Test
    fun withTheBuiltInClientItJustOffersSignInAndShowsNoClient() {
        // Task 13: the client built into the app has no id in the status, so there is nothing to show or change by hand.
        show(GoogleState(GoogleStatus(clientReady = true, signedIn = false, clientId = null)))
        compose.onNodeWithText(SIGN_IN_WITH_GOOGLE).assertExists()
        compose.onAllNodesWithText(CHOOSE_CLIENT_FILE).assertCountEquals(0)
        compose.onAllNodesWithText(GOOGLE_NEEDS_CLIENT).assertCountEquals(0)
        compose.onAllNodesWithText(clientInUse(""), substring = true).assertCountEquals(0)
        compose.onAllNodesWithText(CLIENT_BY_HAND).assertCountEquals(0)
    }

    @Test
    fun revokingAccessAsksFirstAndSignOutDoesNotRevoke() {
        var revokes = 0
        var outs = 0
        show(GoogleState(GoogleStatus(true, true, "me@example.com")), GoogleActions(signOut = { outs++ }, revokeAccess = { revokes++ }))

        compose.onNodeWithText(REVOKE_ACCESS).click()
        compose.onNodeWithText(REVOKE_WARNING).assertExists()
        assertEquals(0, revokes)
        compose.onNodeWithText(KEEP_ACCESS).click()
        compose.onAllNodesWithText(REVOKE_WARNING).assertCountEquals(0)
        assertEquals(0, revokes)

        compose.onNodeWithText(REVOKE_ACCESS).click()
        compose.onNodeWithText(REVOKE_CONFIRM).click()
        assertEquals(1, revokes)

        // S1: Sign out is its own action, and never revokes.
        compose.onNodeWithText(SIGN_OUT).click()
        assertEquals(1, outs)
        assertEquals(1, revokes)
    }

    @Test
    fun signedInItOffersSignInAgainForWhenGoogleAsksForIt() {
        // P5-T6a: the Calendar's "Sign in to Google again in Settings." needs a sign-in here while still signed in.
        var signIns = 0
        show(
            GoogleState(GoogleStatus(true, true, "me@example.com", ChosenGoogleCalendar("family@group.calendar.google.com", "Family"))),
            GoogleActions(signIn = { signIns++ }),
        )
        compose.onNodeWithText(GOOGLE_SIGN_IN_AGAIN).click()
        assertEquals(1, signIns)
    }

    @Test
    fun whileTheCalendarsLoadNothingElseCanStart() {
        // A sign-out (or a revoke) mid-list would race the list's "Signed in as".
        show(GoogleState(GoogleStatus(true, true, "me@example.com"), phase = GooglePhase.LOADING))
        compose.onNodeWithText(GOOGLE_LOADING).assertExists()
        compose.onNodeWithText(SIGN_OUT).assertIsNotEnabled()
        compose.onNodeWithText(REVOKE_ACCESS).assertIsNotEnabled()
        compose.onNodeWithText(GOOGLE_SIGN_IN_AGAIN).assertIsNotEnabled()
    }

    @Test
    fun aCalendarTheNewAccountCantSeeIsSaidAboveTheList() {
        show(
            GoogleState(
                GoogleStatus(true, true, "you@example.com", ChosenGoogleCalendar("family@group.calendar.google.com", "Family")),
                calendars = listOf(GoogleCalendar("you@example.com", "you@example.com", primary = true)),
                note = CALENDAR_NOT_IN_ACCOUNT,
            ),
        )
        compose.onNodeWithText(CALENDAR_NOT_IN_ACCOUNT).assertExists()
        compose.onNodeWithText("you@example.com").assertExists()
    }

    @Test
    fun aboutSaysWhatTheDesktopSendsAndWhatItIsBuiltWith() {
        show(GoogleState(GoogleStatus(true, true, "me@example.com")))
        for (line in listOf(ABOUT_DESKTOP_DATA, ABOUT_DESKTOP_CALENDAR, ABOUT_DESKTOP_FONTS, ABOUT_DESKTOP_BUILT_WITH)) {
            compose.onNodeWithText(line).assertExists()
        }
        compose.onNodeWithText("Meal Planner is free software under the MIT License.").assertExists()
        // The phone's words aren't said on the PC.
        compose.onAllNodesWithText(ABOUT_PHONE_DATA).assertCountEquals(0)
        compose.onAllNodesWithText("It uses the phone's own fonts; none are bundled.").assertCountEquals(0)
    }

    @Test
    fun whenIdleTheSignedInActionsAreOpen() {
        show(GoogleState(GoogleStatus(true, true, "me@example.com")))
        compose.onNodeWithText(SIGN_OUT).assertIsEnabled()
        compose.onNodeWithText(REVOKE_ACCESS).assertIsEnabled()
        compose.onNodeWithText(GOOGLE_SIGN_IN_AGAIN).assertIsEnabled()
    }
}
