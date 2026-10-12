package com.naeblis11.mealplanner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.naeblis11.mealplanner.app.ThemeMode
import com.naeblis11.mealplanner.settings.APPEARANCE_PANEL
import com.naeblis11.mealplanner.settings.BackupState
import com.naeblis11.mealplanner.settings.CalendarSetupState
import com.naeblis11.mealplanner.settings.SettingsScreen
import com.naeblis11.mealplanner.ui.theme.MealColors
import com.naeblis11.mealplanner.ui.theme.MealPalette
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Owner, 2026-10-12: a dark mode, chosen in Settings > Appearance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class DarkModeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theDarkThemeGivesEveryScreenTheDarkPalette() {
        var paper = Color.Unspecified
        var surface = Color.Unspecified
        var accent = Color.Unspecified
        compose.setContent {
            MealPlannerTheme(dark = true) {
                paper = MealColors.Paper
                surface = MaterialTheme.colorScheme.surface
                accent = MaterialTheme.colorScheme.primary
            }
        }
        compose.waitForIdle()
        assertEquals(MealPalette.Dark.paper, paper)
        assertEquals(MealPalette.Dark.paper, surface)
        assertEquals(MealPalette.Dark.accent, accent)
    }

    @Test
    fun theLightThemeIsUnchanged() {
        var paper = Color.Unspecified
        var surface = Color.Unspecified
        compose.setContent {
            MealPlannerTheme(dark = false) {
                paper = MealColors.Paper
                surface = MaterialTheme.colorScheme.surface
            }
        }
        compose.waitForIdle()
        assertEquals(Color.White, paper)
        assertEquals(Color.White, surface)
    }

    @Test
    fun settingsOffersMatchSystemLightAndDark() {
        var chosen: ThemeMode? = null
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
                    themeMode = ThemeMode.SYSTEM,
                    onThemeModeChange = { chosen = it },
                )
            }
        }
        compose.onNodeWithText(APPEARANCE_PANEL).assertExists()
        compose.onNodeWithText("Match system").assertExists()
        compose.onNodeWithText("Light").assertExists()
        compose.onNodeWithText("Dark").performClick()
        assertEquals(ThemeMode.DARK, chosen)
    }
}
