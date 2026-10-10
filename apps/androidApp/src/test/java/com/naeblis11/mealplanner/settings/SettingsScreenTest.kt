package com.naeblis11.mealplanner.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.naeblis11.mealplanner.calendar.CalendarInfo
import com.naeblis11.mealplanner.calendar.CalendarMessages
import com.naeblis11.mealplanner.calendar.ChosenCalendar
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val family = CalendarInfo(1L, "Family", "family@example.com")
    private val work = CalendarInfo(2L, "Work", "work@example.com")

    private fun show(
        calendar: CalendarSetupState = CalendarSetupState(),
        backup: BackupState = BackupState.Idle,
        onExport: () -> Unit = {},
        onImport: () -> Unit = {},
        onSetUp: () -> Unit = {},
        onChoose: (CalendarInfo) -> Unit = {},
        onCancel: () -> Unit = {},
        onAppSettings: () -> Unit = {},
    ) {
        compose.setContent {
            MealPlannerTheme {
                SettingsScreen(
                    backup = backup,
                    calendar = calendar,
                    version = "1.0.0",
                    onBack = {},
                    onExport = onExport,
                    onImport = onImport,
                    onDismissBackup = {},
                    onSetUpCalendar = onSetUp,
                    onChooseCalendar = onChoose,
                    onCancelChoosing = onCancel,
                    onOpenAppSettings = onAppSettings,
                )
            }
        }
    }

    @Test
    fun offersBackupAndImportAndSaysNothingLeavesThePhone() {
        var exported = false
        var imported = false
        show(backup = BackupState.Done("Saved 3 recipe(s) and 1 photo(s)."), onExport = { exported = true }, onImport = { imported = true })
        compose.onNodeWithText("Saved 3 recipe(s) and 1 photo(s).").assertIsDisplayed()
        compose.onNodeWithText("Export backup").performClick()
        compose.onNodeWithText("Import recipes").performClick()
        compose.onNodeWithText(ABOUT_PHONE_DATA).assertExists()
        assertTrue(exported)
        assertTrue(imported)
    }

    @Test
    fun aboutNamesTheVersionAndLicences() {
        show()
        compose.onNodeWithText("Meal Planner 1.0.0").assertExists()
        compose.onNodeWithText("Meal Planner is free software under the MIT License.").assertExists()
        compose.onNodeWithText("It uses the phone's own fonts; none are bundled.").assertExists()
    }

    @Test
    fun withNoCalendarChosenItOffersSetUp() {
        var setUp = 0
        show(onSetUp = { setUp++ })
        compose.onNodeWithText("Google Calendar").assertIsDisplayed()
        compose.onNodeWithText("Set up calendar sending").performClick()
        assertEquals(1, setUp)
    }

    @Test
    fun showsTheChosenCalendarAndOffersAnother() {
        var setUp = 0
        show(calendar = CalendarSetupState(chosen = ChosenCalendar(1L, "Family (family@example.com)")), onSetUp = { setUp++ })
        compose.onNodeWithText("Sending to Family (family@example.com)").assertIsDisplayed()
        compose.onNodeWithText("Choose another calendar").performClick()
        assertEquals(1, setUp)
    }

    @Test
    fun listsTheCalendarsToChooseFrom() {
        var chosen: CalendarInfo? = null
        var cancelled = 0
        show(calendar = CalendarSetupState(picker = CalendarPicker.Choosing(listOf(family, work))), onChoose = { chosen = it }, onCancel = { cancelled++ })
        compose.onNodeWithText("Choose the calendar to send meals to:").assertIsDisplayed()
        compose.onNodeWithText("Work (work@example.com)").performClick()
        assertEquals(work, chosen)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun aPhoneWithoutWritableCalendarsSaysSo() {
        show(calendar = CalendarSetupState(picker = CalendarPicker.Choosing(emptyList())))
        compose.onNodeWithText(CalendarMessages.NO_CALENDARS).assertIsDisplayed()
    }

    @Test
    fun aRefusedPermissionIsExplainedWithTheWayToAllowIt() {
        var opened = 0
        show(calendar = CalendarSetupState(permissionDenied = true), onAppSettings = { opened++ })
        compose.onNodeWithText(CalendarMessages.PERMISSION_DENIED).assertIsDisplayed()
        compose.onNodeWithText("Open app settings").performClick()
        assertEquals(1, opened)
    }
}