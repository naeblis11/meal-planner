package com.naeblis11.mealplanner.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import com.naeblis11.mealplanner.data.RecipeSummary
import com.naeblis11.mealplanner.domain.CategoryGroup
import com.naeblis11.mealplanner.recipes.RECIPE_LIST_TAG
import com.naeblis11.mealplanner.recipes.RecipeListScreen
import com.naeblis11.mealplanner.ui.LIST_PANE_WIDTH
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Owner, 2026-10-09: the search stays put while the recipes scroll, and the rows are compact so more fit. */
class CompactRecipeListTest {
    @get:Rule
    val compose = createComposeRule()

    private val many = (1..60).map { RecipeSummary(100L + it, "Recipe $it", "Main Dishes", null, null, 3) }

    private fun show() = compose.setContent {
        MealPlannerTheme {
            Box(Modifier.requiredSize(LIST_PANE_WIDTH, 800.dp)) {
                RecipeListScreen(
                    listOf(CategoryGroup("Main Dishes", emptyList(), many)), "", {}, {}, {}, {}, {}, { null },
                    books = listOf("Flanders Family Cookbook"), showSettings = false,
                )
            }
        }
    }

    @Test
    fun theSearchAndFiltersStayPutWhileTheListScrolls() {
        show()
        compose.onNodeWithTag(RECIPE_LIST_TAG).performScrollToNode(hasText("Recipe 60"))
        compose.onNodeWithText("Recipe 60").assertIsDisplayed()
        compose.onNodeWithText("Search recipes").assertIsDisplayed()
        compose.onNodeWithText("All recipes").assertIsDisplayed()
    }

    @Test
    fun aRatedRowIsOneShortLine() {
        // A 36 dp photo, the name and small stars that only show the rating, in a 48 dp row.
        show()
        val first = compose.onNodeWithText("Recipe 1").getUnclippedBoundsInRoot().top
        val second = compose.onNodeWithText("Recipe 2").getUnclippedBoundsInRoot().top
        assertTrue("rows are ${second - first} apart", second - first < 50.dp)
    }

    @Test
    fun aClickOnARowOpensTheRecipe() {
        var opened = -1L
        compose.setContent {
            MealPlannerTheme {
                Box(Modifier.requiredSize(LIST_PANE_WIDTH, 800.dp)) {
                    RecipeListScreen(
                        listOf(CategoryGroup("Main Dishes", emptyList(), many)), "", {}, { opened = it }, {}, {}, {}, { null },
                        showSettings = false,
                    )
                }
            }
        }
        compose.onNodeWithText("Recipe 1").performClick()
        compose.waitForIdle()
        assertEquals(101L, opened)
    }
}
