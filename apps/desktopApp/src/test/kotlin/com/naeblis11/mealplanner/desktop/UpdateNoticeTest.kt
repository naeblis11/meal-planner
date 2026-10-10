package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.settings.INSTALL_AND_CLOSE
import com.naeblis11.mealplanner.settings.INSTALL_UPDATE
import com.naeblis11.mealplanner.settings.SAVE_THEN_INSTALL
import com.naeblis11.mealplanner.settings.SEE_UPDATE
import com.naeblis11.mealplanner.settings.UPDATES_PANEL
import com.naeblis11.mealplanner.settings.UPDATE_NOT_NOW
import com.naeblis11.mealplanner.settings.readyToInstall
import com.naeblis11.mealplanner.settings.updateAvailable
import com.naeblis11.mealplanner.ui.MealPlannerApp
import com.naeblis11.mealplanner.update.UpdateOffer
import com.naeblis11.mealplanner.update.UpdateStatus
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** An update the launch check found shows above every screen until it is opened or put away; never installs by itself. */
class UpdateNoticeTest {
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-update-notice").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })
    private val offer = UpdateOffer("1.0.1", "release-2", "MealPlanner-1.0.1.msi", 1_000, "a".repeat(64))

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    @Test
    fun anUpdateFoundAtLaunchIsNoticedAndSeeUpdateOpensSettings() {
        val updates = FakeUpdateControls(UpdateStatus(offered = true, offer = offer, foundAutomatically = true))
        compose.showAt(1000.dp) { MealPlannerApp(app.container, updates = updates) }
        compose.waitForText(updateAvailable("1.0.1"))
        compose.onNodeWithText(SEE_UPDATE).click()
        compose.waitForText(UPDATES_PANEL)
        compose.onNodeWithText(INSTALL_UPDATE).assertExists()
        // P8-PF4: opening Settings puts the notice away; it never installs anything.
        assertEquals(listOf("dismiss"), updates.calls)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(SEE_UPDATE).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun anUpdateDownloadedInTheBackgroundOffersInstallAgain() {
        val updates = FakeUpdateControls(UpdateStatus(offered = true, offer = offer, waitingToInstall = true))
        compose.showAt(1000.dp) { MealPlannerApp(app.container, updates = updates) }
        compose.waitForText(readyToInstall("1.0.1"))
        compose.onAllNodesWithText(SEE_UPDATE).assertCountEquals(0)
        compose.onNodeWithText(INSTALL_UPDATE).click()
        assertEquals(listOf("install"), updates.calls)
    }

    @Test
    fun onThePcInstallFromTheNoticeOpensSettingsToAskFirst() {
        val updates = FakeUpdateControls(UpdateStatus(offered = true, offer = offer, waitingToInstall = true, installClosesApp = true))
        compose.showAt(1000.dp) { MealPlannerApp(app.container, updates = updates) }
        // P8-F1: on the PC the download waits only while something unsaved is open, so the notice says what to do.
        compose.waitForText("Meal Planner 1.0.1 is ready to install. Save or leave what you're editing, then Install.")
        assertEquals(readyToInstall("1.0.1", closesApp = true), "Meal Planner 1.0.1 is ready to install. $SAVE_THEN_INSTALL")
        compose.onNodeWithText(INSTALL_UPDATE).click()
        compose.waitForText(UPDATES_PANEL)
        assertEquals(listOf("dismiss"), updates.calls)
    }

    @Test
    fun onThePcInstallAsksThenInstallsOnce() {
        val updates = FakeUpdateControls(UpdateStatus(offered = true, offer = offer, foundAutomatically = true, installClosesApp = true))
        compose.showAt(1000.dp) { MealPlannerApp(app.container, updates = updates) }
        compose.waitForText(updateAvailable("1.0.1"))
        compose.onNodeWithText(SEE_UPDATE).click()
        compose.waitForText(UPDATES_PANEL)
        compose.onNodeWithText(INSTALL_UPDATE).click()
        compose.waitForText(INSTALL_AND_CLOSE)
        assertEquals(listOf("dismiss"), updates.calls)
        compose.onNodeWithText(INSTALL_AND_CLOSE).click()
        compose.waitUntil(5_000) { updates.calls.size == 2 }
        assertEquals(listOf("dismiss", "install"), updates.calls)
    }

    @Test
    fun notNowPutsTheNoticeAway() {
        val updates = FakeUpdateControls(UpdateStatus(offered = true, offer = offer, foundAutomatically = true))
        compose.showAt(1000.dp) { MealPlannerApp(app.container, updates = updates) }
        compose.waitForText(updateAvailable("1.0.1"))
        compose.onNodeWithText(UPDATE_NOT_NOW).click()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(SEE_UPDATE).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf("dismiss"), updates.calls)
    }

    @Test
    fun anUpdateFoundFromSettingsAddsNoNotice() {
        val updates = FakeUpdateControls(UpdateStatus(offered = true, offer = offer, foundAutomatically = false))
        compose.showAt(1000.dp) { MealPlannerApp(app.container, updates = updates) }
        compose.waitForText("New recipe")
        compose.onAllNodesWithText(SEE_UPDATE).assertCountEquals(0)
    }
}
