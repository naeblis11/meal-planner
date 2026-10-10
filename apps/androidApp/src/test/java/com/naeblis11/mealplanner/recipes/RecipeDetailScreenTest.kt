package com.naeblis11.mealplanner.recipes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
class RecipeDetailScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val view = RecipeView(
        id = 1, name = "Soup", category = "Soups & Stews", subcategory = null, imageFilename = null, rating = 3,
        author = null, sourceUrl = null, book = null, oven = null, notes = listOf("Freezes well."), servings = "4",
        servingsUnit = "servings", canScale = true,
        ingredients = listOf(IngredientView(null, "Stock", "2", "cup", emptyList(), emptyList())),
        steps = listOf(StepView(1, "Simmer.", emptyList())),
    )

    @Test
    fun showsTheRecipeAndScales() {
        var scaled = ""
        var rated = 0
        compose.setContent {
            MealPlannerTheme {
                RecipeDetailScreen(view, null, null, null, {}, {}, {}, { scaled = it }, { rated = it }, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Soup").assertIsDisplayed()
        compose.onNodeWithText("2 cup Stock").assertIsDisplayed()
        compose.onNodeWithText("Simmer.").assertIsDisplayed()
        compose.onNodeWithText("Servings").performTextReplacement("8")
        compose.onNodeWithText("Scale").performClick()
        assertEquals("8", scaled)
        compose.onNodeWithContentDescription("Rate 5 stars").performClick()
        assertEquals(5, rated)
    }

    @Test
    fun showsTheCookbookARecipeCameFrom() {
        compose.setContent {
            MealPlannerTheme {
                RecipeDetailScreen(view.copy(author = "Ann", book = "Flanders Family Cookbook"), null, null, null, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Flanders Family Cookbook").assertIsDisplayed()
        compose.onNodeWithText("Source: Ann \u00b7 Flanders Family Cookbook").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun deletingAsksFirst() {
        var deleted = false
        compose.setContent {
            MealPlannerTheme {
                RecipeDetailScreen(view, null, null, null, {}, {}, {}, {}, {}, { deleted = true }, {}, {}, {})
            }
        }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Delete recipe").performClick()
        compose.onNodeWithText("Delete this recipe?").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        assertTrue(deleted)
    }

    @Test
    fun takePhotoIsOfferedWhenThereIsACamera() {
        var taken = false
        compose.setContent {
            MealPlannerTheme {
                RecipeDetailScreen(view, null, null, null, {}, {}, {}, {}, {}, {}, { taken = true }, {}, {})
            }
        }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Choose photo").assertIsDisplayed()
        compose.onNodeWithText("Take photo").performClick()
        assertTrue(taken)
    }

    @Test
    fun takePhotoIsHiddenWithoutACamera() {
        compose.setContent {
            MealPlannerTheme {
                RecipeDetailScreen(view, null, null, null, {}, {}, {}, {}, {}, {}, null, {}, {})
            }
        }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Choose photo").assertIsDisplayed()
        compose.onNodeWithText("Take photo").assertDoesNotExist()
    }

    @Test
    fun aRecipeThatCantBeShownCanStillBeDeleted() {
        var deleted = false
        compose.setContent {
            MealPlannerTheme {
                RecipeDetailScreen(null, null, null, "This recipe can't be shown: bad", {}, {}, {}, {}, {}, { deleted = true }, {}, {}, {})
            }
        }
        compose.onNodeWithText("This recipe can't be shown: bad").assertIsDisplayed()
        compose.onNodeWithText("Delete recipe").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertTrue(deleted)
    }

    @Test
    fun deletingAPlannedRecipeSaysItIsOnTheMealPlan() {
        compose.setContent {
            MealPlannerTheme {
                RecipeDetailScreen(view, null, null, null, {}, {}, {}, {}, {}, {}, {}, {}, {}, plannedMeals = 2)
            }
        }
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Delete recipe").performClick()
        compose.onNodeWithText(
            "It is on your meal plan 2 times; deleting it removes those meals from the plan too. This can't be undone.",
        ).assertIsDisplayed()
    }
}
