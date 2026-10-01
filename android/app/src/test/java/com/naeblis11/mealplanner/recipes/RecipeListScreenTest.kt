package com.naeblis11.mealplanner.recipes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.naeblis11.mealplanner.data.RecipeSummary
import com.naeblis11.mealplanner.domain.CategoryGroup
import com.naeblis11.mealplanner.domain.SubcategoryGroup
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class RecipeListScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val roast = RecipeSummary(1, "Roast", "Main Dishes", "Beef", null, 5)
    private val lasagna = RecipeSummary(2, "Lasagna", "Main Dishes", null, null, null)
    private val groups = listOf(CategoryGroup("Main Dishes", listOf(SubcategoryGroup("Beef", listOf(roast))), listOf(lasagna)))

    @Test
    fun showsCategoriesSubcategoriesAndRecipes() {
        var opened = -1L
        var typed = ""
        compose.setContent {
            MealPlannerTheme {
                RecipeListScreen(groups, "", { typed = it }, { opened = it }, {}, {}, {}, { null })
            }
        }
        compose.onNodeWithText("Main Dishes").assertIsDisplayed()
        compose.onNodeWithText("Beef").assertIsDisplayed()
        compose.onNodeWithText("Lasagna").performClick()
        assertEquals(2L, opened)
        compose.onNodeWithText("Search recipes").performTextInput("ro")
        assertEquals("ro", typed)
    }

    @Test
    fun explainsAnEmptyLibrary() {
        compose.setContent { MealPlannerTheme { RecipeListScreen(emptyList(), "", {}, {}, {}, {}, {}, { null }) } }
        compose.onNodeWithText("No recipes yet. Add one, or import a file.").assertIsDisplayed()
    }
}
