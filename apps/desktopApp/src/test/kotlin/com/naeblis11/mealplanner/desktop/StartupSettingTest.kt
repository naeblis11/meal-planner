package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.settings.BackupState
import com.naeblis11.mealplanner.settings.CalendarSetupState
import com.naeblis11.mealplanner.settings.STARTUP_LABEL
import com.naeblis11.mealplanner.settings.STARTUP_UNAVAILABLE
import com.naeblis11.mealplanner.settings.SettingsScreen
import com.naeblis11.mealplanner.settings.StartupState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StartupSettingTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: StartupState, onChange: (Boolean) -> Unit) = compose.showAt(600.dp) {
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
            startup = state,
            onStartupChange = onChange,
        )
    }

    @Test
    fun theSwitchShowsWhatWindowsDoesAndChangesIt() {
        var changed: Boolean? = null
        show(StartupState(available = true, on = true)) { changed = it }
        compose.onNode(hasText(STARTUP_LABEL) and isToggleable()).assertIsOn().click()
        assertEquals(false, changed)
    }

    @Test
    fun outsideTheInstalledAppItIsOffAndGreyed() {
        show(StartupState(available = false, on = false)) {}
        compose.onNode(hasText(STARTUP_LABEL) and isToggleable()).assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText(STARTUP_UNAVAILABLE).assertExists()
    }
}
