package com.naeblis11.mealplanner.plan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.naeblis11.mealplanner.data.RecipeSummary
import com.naeblis11.mealplanner.domain.ServingsInput
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class AssignMealScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val soup = RecipeSummary(4, "Soup", null, null, null, null)

    @Test
    fun picksARecipeWithServings() {
        // The fields are stateless, so the test holds their state as the destination does.
        var servings by mutableStateOf("")
        var query by mutableStateOf("")
        var picked = 0L
        compose.setContent {
            MealPlannerTheme {
                AssignMealScreen("Dinner", "Monday, Sep 28", query, servings, listOf(soup), null, { query = it }, { servings = it }, { picked = it }, {})
            }
        }
        compose.onNodeWithText("Plan dinner").assertIsDisplayed()
        compose.onNodeWithText("Monday, Sep 28").assertIsDisplayed()
        compose.onNodeWithText("Servings (optional)").performTextInput("6")
        compose.onNodeWithText("Search recipes").performTextInput("so")
        compose.onNodeWithText("Soup").performClick()
        assertEquals("6", servings)
        assertEquals("so", query)
        assertEquals(4L, picked)
    }

    @Test
    fun showsTheHintAndAnEmptyLibrary() {
        compose.setContent {
            MealPlannerTheme {
                AssignMealScreen("Lunch", "Monday, Sep 28", "", "lots", emptyList(), ServingsInput.HINT, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText(ServingsInput.HINT).assertIsDisplayed()
        compose.onNodeWithText("No recipes yet. Add one from Recipes first.").assertIsDisplayed()
    }
}
