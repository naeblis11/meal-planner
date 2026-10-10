package com.naeblis11.mealplanner.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.naeblis11.mealplanner.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppShellTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun opensOnTheRecipesScreen() {
        compose.onNode(hasText("Recipes") and isSelected()).assertIsDisplayed()
    }
}
