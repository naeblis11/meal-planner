package com.naeblis11.mealplanner.recipes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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

    @Test
    fun marksCookbookRecipesAndFiltersByBook() {
        val toffee = RecipeSummary(3, "Toffee", "Desserts", null, null, null, "\"Flanders Family Cookbook\"")
        var picked: String? = "unset"
        compose.setContent {
            MealPlannerTheme {
                RecipeListScreen(
                    listOf(CategoryGroup("Desserts", emptyList(), listOf(toffee))), "", {}, {}, {}, {}, {}, { null },
                    books = listOf("Flanders Family Cookbook"), book = null, onBookChange = { picked = it },
                )
            }
        }
        compose.onNodeWithContentDescription("From Flanders Family Cookbook").assertIsDisplayed()
        compose.onNodeWithText("All recipes").assertIsDisplayed()
        compose.onNodeWithText("Flanders Family Cookbook").performClick()
        assertEquals("Flanders Family Cookbook", picked)
    }

    @Test
    @Config(qualifiers = "w411dp-h800dp")
    fun theSearchAndFiltersStayPutWhileTheListScrolls() {
        val many = (1..60).map { RecipeSummary(100L + it, "Recipe $it", "Main Dishes", null, null, 3) }
        compose.setContent {
            MealPlannerTheme {
                RecipeListScreen(
                    listOf(CategoryGroup("Main Dishes", emptyList(), many)), "", {}, {}, {}, {}, {}, { null },
                    books = listOf("Flanders Family Cookbook"),
                )
            }
        }
        compose.onNodeWithTag(RECIPE_LIST_TAG).performScrollToNode(hasText("Recipe 60"))
        compose.onNodeWithText("Recipe 60").assertIsDisplayed()
        compose.onNodeWithText("Recipe 1").assertIsNotDisplayed()
        compose.onNodeWithText("Search recipes").assertIsDisplayed()
        compose.onNodeWithText("All recipes").assertIsDisplayed()
    }

    @Test
    fun noCookbookFilterWithoutCookbooks() {
        compose.setContent { MealPlannerTheme { RecipeListScreen(groups, "", {}, {}, {}, {}, {}, { null }) } }
        compose.onNodeWithText("All recipes").assertDoesNotExist()
    }
}
