package com.naeblis11.mealplanner.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.naeblis11.mealplanner.sync.FoundPc
import com.naeblis11.mealplanner.sync.SyncMessages
import com.naeblis11.mealplanner.sync.SyncStatus
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** LAN sync stage 2c (D3): Settings' "Join a PC" section. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class JoinSettingTest {
    @get:Rule
    val compose = createComposeRule()

    private val unpaired = SyncStatus(known = true)
    private val kitchen = FoundPc("KITCHEN", "192.0.2.20", 5001)
    private val office = FoundPc("OFFICE", "192.0.2.30", 5001)
    private val calls = mutableListOf<String>()
    private val actions = JoinActions(
        scan = { calls += "scan" },
        openTyping = { calls += "type" },
        cancelTyping = { calls += "cancel" },
        submitCode = { calls += "code $it" },
        choose = { calls += "choose ${it.name}" },
        askLeave = { calls += "askLeave" },
        confirmLeave = { calls += "leave" },
        cancelLeave = { calls += "keep" },
        openWifiSettings = { calls += "wifi" },
    )

    private fun show(state: JoinState) = compose.setContent {
        MealPlannerTheme {
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
                join = state,
                joinActions = actions,
            )
        }
    }

    @Test
    fun notPairedItOffersScanAndType() {
        show(JoinState(status = unpaired))
        compose.onNodeWithText(JOIN_PANEL).assertIsDisplayed()
        compose.onNodeWithText(JOIN_INTRO).assertIsDisplayed()
        compose.onNodeWithText(SCAN_CODE).performClick()
        compose.onNodeWithText(TYPE_CODE).performClick()
        assertEquals(listOf("scan", "type"), calls)
    }

    @Test
    fun withoutWifiItAsksForTheHomeWifiFirst() {
        show(JoinState(status = unpaired, onWifi = false))
        compose.onNodeWithText(WIFI_FIRST).assertIsDisplayed()
        compose.onAllNodesWithText(SCAN_CODE).assertCountEquals(0)
        compose.onNodeWithText(OPEN_WIFI_SETTINGS).performClick()
        assertEquals(listOf("wifi"), calls)
    }

    @Test
    fun scanningUnavailableSaysToTypeInstead() {
        show(JoinState(status = unpaired, scanUnavailable = true))
        compose.onNodeWithText(SCAN_UNAVAILABLE).assertIsDisplayed()
        compose.onNodeWithText(TYPE_CODE).assertIsEnabled()
    }

    @Test
    fun typingTakesSixDigitsAndSaysScanningIsSafer() {
        show(JoinState(status = unpaired, typing = true))
        compose.onNodeWithText(TYPE_SAFER).assertIsDisplayed()
        compose.onNodeWithText(PAIR).assertIsNotEnabled()
        compose.onNodeWithText(CODE_LABEL).performTextInput("12a3456789")
        compose.onNodeWithText(PAIR).assertIsEnabled().performClick()
        assertEquals(listOf("code 123456"), calls)
    }

    @Test
    fun severalPcsAreOfferedByName() {
        show(JoinState(status = unpaired, typing = true, choices = listOf(kitchen, office)))
        compose.onNodeWithText(CHOOSE_PC).assertIsDisplayed()
        compose.onNodeWithText("OFFICE").performClick()
        assertEquals(listOf("choose OFFICE"), calls)
    }

    @Test
    fun anErrorWhileTypingShowsInTheDialog() {
        show(JoinState(status = unpaired, typing = true, error = SyncMessages.NO_PC_FOUND))
        compose.onNode(hasText(SyncMessages.NO_PC_FOUND) and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    @Test
    fun anErrorAfterScanningShowsInTheSection() {
        show(JoinState(status = unpaired, error = SyncMessages.WRONG_CODE))
        compose.onNodeWithText(SyncMessages.WRONG_CODE).assertIsDisplayed()
    }

    @Test
    fun pairedItNamesThePcAndLeaveAsksFirst() {
        show(JoinState(status = SyncStatus(known = true, pcName = "KITCHEN")))
        compose.onNodeWithText(pairedWith("KITCHEN")).assertIsDisplayed()
        compose.onAllNodesWithText(SCAN_CODE).assertCountEquals(0)
        compose.onNodeWithText(LEAVE).performClick()
        assertEquals(listOf("askLeave"), calls)
    }

    @Test
    fun theLeaveQuestionNamesThePc() {
        show(JoinState(status = SyncStatus(known = true, pcName = "KITCHEN"), leaveAsk = true))
        compose.onNodeWithText(leaveQuestion("KITCHEN")).assertIsDisplayed()
        compose.onNode(hasText(JOIN_CANCEL) and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasText(LEAVE) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf("keep", "leave"), calls)
    }

    @Test
    fun whileASyncRunsScanTypeAndLeaveAreOff() {
        show(JoinState(status = unpaired, syncing = true))
        compose.onNodeWithText(SCAN_CODE).assertIsNotEnabled()
        compose.onNodeWithText(TYPE_CODE).assertIsNotEnabled()
    }

    @Test
    fun whileASyncRunsLeaveIsOff() {
        show(JoinState(status = SyncStatus(known = true, pcName = "KITCHEN"), syncing = true, leaveAsk = true))
        compose.onNode(hasText(LEAVE) and !hasAnyAncestor(isDialog())).assertIsNotEnabled()
        compose.onNode(hasText(LEAVE) and hasAnyAncestor(isDialog())).assertIsNotEnabled()
    }
}
