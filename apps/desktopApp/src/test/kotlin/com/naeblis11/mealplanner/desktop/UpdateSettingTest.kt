package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.settings.BackupState
import com.naeblis11.mealplanner.settings.CHECK_FOR_UPDATES
import com.naeblis11.mealplanner.settings.CalendarSetupState
import com.naeblis11.mealplanner.settings.INSTALL_AND_CLOSE
import com.naeblis11.mealplanner.settings.INSTALL_CLOSES_APP
import com.naeblis11.mealplanner.settings.INSTALL_UPDATE
import com.naeblis11.mealplanner.settings.SettingsScreen
import com.naeblis11.mealplanner.settings.UPDATES_PANEL
import com.naeblis11.mealplanner.settings.UPDATE_AUTO_LABEL
import com.naeblis11.mealplanner.settings.UPDATE_NOT_CHECKED
import com.naeblis11.mealplanner.settings.UP_TO_DATE
import com.naeblis11.mealplanner.settings.UpdateActions
import com.naeblis11.mealplanner.settings.UpdateUiState
import com.naeblis11.mealplanner.settings.downloading
import com.naeblis11.mealplanner.settings.installTitle
import com.naeblis11.mealplanner.settings.lastChecked
import com.naeblis11.mealplanner.settings.readyToInstall
import com.naeblis11.mealplanner.settings.updateAvailable
import com.naeblis11.mealplanner.update.UpdateMessages
import com.naeblis11.mealplanner.update.UpdateOffer
import com.naeblis11.mealplanner.update.UpdatePhase
import com.naeblis11.mealplanner.update.UpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Spec "Updates": Settings' Check for updates, the Automatically switch, the last check, and Install (never by itself). */
class UpdateSettingTest {
    @get:Rule
    val compose = createComposeRule()

    private val offer = UpdateOffer("1.0.1", "release-2", "MealPlanner-1.0.1.msi", 1_000, "a".repeat(64))

    private fun show(state: UpdateUiState, actions: UpdateActions = UpdateActions()) = compose.showAt(600.dp) {
        SettingsScreen(
            backup = BackupState.Idle,
            calendar = CalendarSetupState(),
            version = "1.0.0",
            onBack = {},
            onExport = {},
            onImport = {},
            onDismissBackup = {},
            onSetUpCalendar = {},
            onChooseCalendar = {},
            onCancelChoosing = {},
            onOpenAppSettings = {},
            updates = state,
            updateActions = actions,
        )
    }

    @Test
    fun aCopyThatDoesntCheckSaysWhy() {
        show(UpdateUiState(UpdateStatus(offered = false, unavailableReason = "Only the installed app checks for updates.")))
        compose.onNodeWithText(UPDATES_PANEL).assertExists()
        compose.onNodeWithText("Only the installed app checks for updates.").assertExists()
        compose.onAllNodesWithText(CHECK_FOR_UPDATES).assertCountEquals(0)
    }

    @Test
    fun theSwitchAndTheButtonAskTheCheck() {
        val calls = mutableListOf<String>()
        show(
            UpdateUiState(UpdateStatus(offered = true, automatic = true)),
            UpdateActions(checkNow = { calls += "check" }, setAutomatic = { calls += "automatic $it" }),
        )
        compose.onNode(hasText(UPDATE_AUTO_LABEL) and isToggleable()).assertIsOn().click()
        compose.onNodeWithText(CHECK_FOR_UPDATES).click()
        assertEquals(listOf("automatic false", "check"), calls)
        compose.onNodeWithText(UPDATE_NOT_CHECKED).assertExists()
    }

    @Test
    fun upToDateSaysWhenItLastLooked() {
        show(UpdateUiState(UpdateStatus(offered = true, upToDate = true, lastChecked = 0), lastCheckedText = "1 Jan 1970 at 00:00"))
        compose.onNodeWithText(UP_TO_DATE).assertExists()
        compose.onNodeWithText(lastChecked("1 Jan 1970 at 00:00")).assertExists()
    }

    @Test
    fun anUpdateOffersInstall() {
        var installs = 0
        show(UpdateUiState(UpdateStatus(offered = true, offer = offer)), UpdateActions(install = { installs++ }))
        compose.onNodeWithText(updateAvailable("1.0.1")).assertExists()
        compose.onNodeWithText(INSTALL_UPDATE).click()
        assertEquals(1, installs)
    }

    @Test
    fun whileItDownloadsTheButtonsWait() {
        show(UpdateUiState(UpdateStatus(offered = true, offer = offer, phase = UpdatePhase.DOWNLOADING, downloaded = 250)))
        compose.onNodeWithText(downloading(250, 1_000)).assertExists()
        assertEquals("Downloading... 25%", downloading(250, 1_000))
        compose.onNodeWithText(CHECK_FOR_UPDATES).assertIsNotEnabled()
        compose.onAllNodesWithText(INSTALL_UPDATE).assertCountEquals(0)
    }

    @Test
    fun aFailedCheckIsQuietAndABadDownloadIsFlagged() {
        show(UpdateUiState(UpdateStatus(offered = true, message = UpdateMessages.CHECK_FAILED, problem = UpdateMessages.HASH_MISMATCH)))
        compose.onNodeWithText(UpdateMessages.CHECK_FAILED).assertExists()
        compose.onNodeWithText(UpdateMessages.HASH_MISMATCH).assertExists()
    }

    @Test
    fun noReleaseYetIsSaidQuietly() {
        show(UpdateUiState(UpdateStatus(offered = true, message = UpdateMessages.NO_LIST)))
        compose.onNodeWithText(UpdateMessages.NO_LIST).assertExists()
    }

    @Test
    fun aRedirectedFolderIsFlagged() {
        show(UpdateUiState(UpdateStatus(offered = true, problem = UpdateMessages.FOLDER_REDIRECTED)))
        compose.onNodeWithText(UpdateMessages.FOLDER_REDIRECTED).assertExists()
    }

    @Test
    fun aDownloadWaitingForTheAppSaysSoAndInstallsAgain() {
        var installs = 0
        show(UpdateUiState(UpdateStatus(offered = true, offer = offer, waitingToInstall = true)), UpdateActions(install = { installs++ }))
        compose.onNodeWithText(readyToInstall("1.0.1")).assertExists()
        compose.onNodeWithText(INSTALL_UPDATE).click()
        assertEquals(1, installs)
    }

    @Test
    fun installWaitsWhileTheCheckIsBusy() {
        show(UpdateUiState(UpdateStatus(offered = true, offer = offer, phase = UpdatePhase.CHECKING)))
        compose.onAllNodesWithText(INSTALL_UPDATE).assertCountEquals(0)
        compose.onNodeWithText(CHECK_FOR_UPDATES).assertIsNotEnabled()
    }

    @Test
    fun onThePcInstallAsksFirst() {
        val calls = mutableListOf<String>()
        show(
            UpdateUiState(UpdateStatus(offered = true, offer = offer, installClosesApp = true), confirming = true),
            UpdateActions(confirmInstall = { calls += "confirm" }, cancelInstall = { calls += "cancel" }),
        )
        compose.onNodeWithText(installTitle("1.0.1")).assertExists()
        compose.onNodeWithText(INSTALL_CLOSES_APP).assertExists()
        compose.onNodeWithText(INSTALL_AND_CLOSE).click()
        assertEquals(listOf("confirm"), calls)
    }
}
