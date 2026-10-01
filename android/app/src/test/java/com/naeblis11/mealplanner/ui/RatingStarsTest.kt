package com.naeblis11.mealplanner.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class RatingStarsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun displayOnlyStarsAreOneNodeWithTheRating() {
        compose.setContent { MealPlannerTheme { RatingStars(4) } }
        compose.onNodeWithContentDescription("Rated 4 of 5").assertIsDisplayed()
        compose.onNodeWithContentDescription("Rate 1 star").assertDoesNotExist()
    }

    @Test
    fun interactiveStarsAreEasyToTap() {
        var rated = 0
        compose.setContent { MealPlannerTheme { RatingStars(2, onRate = { rated = it }) } }
        val star = compose.onNodeWithContentDescription("Rate 5 stars")
        star.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        star.performClick()
        assertEquals(5, rated)
    }
}
