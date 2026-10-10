package com.naeblis11.mealplanner.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import com.naeblis11.mealplanner.update.UpdateOffer
import com.naeblis11.mealplanner.update.UpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spec "Updates": the family allows "install unknown apps" for Meal Planner once; Settings says how. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class UpdatePanelPhoneTest {
    @get:Rule
    val compose = createComposeRule()

    private val offer = UpdateOffer("1.0.1", "release-2", "MealPlanner-1.0.1.apk", 1_000, "a".repeat(64))

    @Test
    fun withoutPermissionSettingsSaysHowToAllowInstalls() {
        val calls = mutableListOf<String>()
        compose.setContent {
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
                    updates = UpdateUiState(UpdateStatus(offered = true, offer = offer, needsPermission = true)),
                    updateActions = UpdateActions(install = { calls += "install" }, openInstallPermission = { calls += "permission" }),
                )
            }
        }
        compose.onNodeWithText(ALLOW_INSTALLS).assertExists()
        compose.onNodeWithText(OPEN_INSTALL_SETTINGS).performClick()
        compose.onNodeWithText(INSTALL_UPDATE).performClick()
        assertEquals(listOf("permission", "install"), calls)
        // P8-PF5: from plan 8 the phone goes online for its updates, so no screen may still say it never does.
        compose.onNodeWithText("never goes online", substring = true).assertDoesNotExist()
        compose.onNodeWithText("goes online only to check GitHub for its own updates", substring = true).assertExists()
    }
}
