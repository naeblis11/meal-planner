package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.calendar.ChosenGoogleCalendar
import com.naeblis11.mealplanner.calendar.GoogleMessages
import com.naeblis11.mealplanner.calendar.SendOutcome
import com.naeblis11.mealplanner.desktop.google.FakeGoogle
import com.naeblis11.mealplanner.desktop.google.FakeGoogle.Companion.FAMILY
import com.naeblis11.mealplanner.desktop.google.FakeProtector
import com.naeblis11.mealplanner.desktop.google.GoogleAccount
import com.naeblis11.mealplanner.desktop.google.GoogleClients
import com.naeblis11.mealplanner.desktop.google.GoogleTokenStore
import com.naeblis11.mealplanner.desktop.server.SecretsFile
import com.naeblis11.mealplanner.settings.GOOGLE_SIGN_IN_AGAIN
import com.naeblis11.mealplanner.settings.GOOGLE_WAITING
import com.naeblis11.mealplanner.settings.GoogleStatus
import com.naeblis11.mealplanner.settings.SIGN_IN_WITH_GOOGLE
import com.naeblis11.mealplanner.settings.SIGN_OUT
import com.naeblis11.mealplanner.ui.MealPlannerApp
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** P5-R5: on the desktop, the Calendar's "Send this week" goes to Google, and what it can't do points to Settings. */
class GoogleSendFlowTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-google-send").toFile()
    private val settings = MapSettings()
    private val app = DesktopApp(dir, settingsFactory = { settings })
    private val sendThisWeek = "Send this week to Google Calendar"

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    @Test
    fun aSendBeforeSigningInLeadsToGooglesSignIn() {
        val google = FakeGoogleControls()
        compose.showAt(1000.dp) { MealPlannerApp(app.container, google = google) }
        compose.tab("Calendar").click()
        compose.waitForText(sendThisWeek)
        compose.onNodeWithText(sendThisWeek).click()
        compose.waitForText(GoogleMessages.SIGN_IN)
        assertEquals(1, google.sent.size)
        compose.onNodeWithText("Open Settings").click()
        compose.waitForText(SIGN_IN_WITH_GOOGLE)
    }

    @Test
    fun aSendThatWorksSaysWhatChanged() {
        val google = FakeGoogleControls(GoogleStatus(true, true, "me@example.com", ChosenGoogleCalendar("family@group.calendar.google.com", "Family")))
            .apply { outcome = SendOutcome.Sent(1, 0, 0, 0, emptyList()) }
        compose.showAt(1000.dp) { MealPlannerApp(app.container, google = google) }
        compose.tab("Calendar").click()
        compose.waitForText(sendThisWeek)
        compose.onNodeWithText(sendThisWeek).click()
        compose.waitForText("Calendar updated: 1 added.")
    }

    @Test
    fun leavingSettingsMidSignInCancelsItAndSignInStillWorks() {
        // The rail keeps Settings' entry (saveState), so its view model isn't cleared: leaving has to cancel the wait,
        // or the browser's answer address stays open and every later Sign in finds a sign-in still pending.
        val google = FakeGoogleControls().apply { waitForCancel = true }
        compose.showAt(1000.dp) { MealPlannerApp(app.container, google = google) }
        compose.tab("Settings").click()
        compose.waitForText(SIGN_IN_WITH_GOOGLE)
        compose.onNodeWithText(SIGN_IN_WITH_GOOGLE).click()
        compose.waitForText(GOOGLE_WAITING)

        compose.tab("Calendar").click()
        compose.waitForText(sendThisWeek)
        compose.waitUntil(3_000) { google.cancels == 1 }

        google.waitForCancel = false
        compose.tab("Settings").click()
        compose.waitForText(SIGN_IN_WITH_GOOGLE)
        compose.onNodeWithText(SIGN_IN_WITH_GOOGLE).click()
        // A first sign-in goes on to the calendars.
        compose.waitForText("Family")
        assertEquals(true, google.status.value.signedIn)
        assertEquals(1, google.cancels)
    }

    @Test
    fun aFreshSignInAloneClearsTheSignInAgainBanner() {
        // P5-T6a, end to end against the fake Google: signed in to Family, then Google stops honouring the sign-in.
        val fake = FakeGoogle().also { closing.add(it::close) }
        val secrets = SecretsFile(File(dir, ".env"))
        GoogleClients.save(secrets, fake.client)
        val account = GoogleAccount(
            secrets,
            GoogleTokenStore(File(dir, GoogleTokenStore.FILE_NAME), FakeProtector(), log = {}),
            settings,
            database = { app.container.database },
            http = fake.http(),
            endpoints = fake.endpoints,
            browse = fake.browser(),
            log = {},
        )
        File(app.recipesDir, "soup.yaml").writeText(
            "recipe_name: Soup\ningredients:\n- Rice:\n    amounts:\n    - amount: 1\n      unit: cup\nsteps:\n- step: Cook.\n",
        )
        runBlocking {
            app.folder.sync()
            app.container.plans.assign(LocalDate.now(), "Dinner", app.container.recipes.allRecipes().single().id, null)
        }
        account.signIn {}
        account.choose(account.calendars().first { it.id == FAMILY })
        fake.revokeAll()

        compose.showAt(1000.dp) { MealPlannerApp(app.container, google = account) }
        compose.tab("Calendar").click()
        compose.waitForText(sendThisWeek)
        compose.onNodeWithText(sendThisWeek).click()
        compose.waitForText(GoogleMessages.SIGN_IN_AGAIN)

        // Only a sign-in clears it: a look at Settings alone leaves it there.
        compose.onNodeWithText("Open Settings").click()
        compose.waitForText(GOOGLE_SIGN_IN_AGAIN)
        compose.onNodeWithText("Back").click()
        compose.waitForText(sendThisWeek)
        compose.onNodeWithText(GoogleMessages.SIGN_IN_AGAIN).assertExists()

        // Signing in again, to the calendar already chosen: Google honours the new grant, and nothing is chosen again.
        fake.refreshTokens += FakeGoogle.REFRESH
        val picks = account.picks.value
        compose.onNodeWithText("Open Settings").click()
        compose.waitForText(GOOGLE_SIGN_IN_AGAIN)
        compose.onNodeWithText(GOOGLE_SIGN_IN_AGAIN).click()
        compose.waitUntil(5_000) { account.picks.value == picks + 1 }
        compose.waitForText(SIGN_OUT)
        compose.onNodeWithText("Back").click()
        compose.waitForText(sendThisWeek)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(GoogleMessages.SIGN_IN_AGAIN).fetchSemanticsNodes().isEmpty() }
        assertEquals(FAMILY, account.status.value.calendar!!.id)
        assertEquals(picks + 1, account.picks.value)
    }
}
