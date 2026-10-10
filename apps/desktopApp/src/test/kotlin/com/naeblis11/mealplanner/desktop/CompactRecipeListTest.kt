package com.naeblis11.mealplanner.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import com.naeblis11.mealplanner.domain.SubcategoryGroup
import com.naeblis11.mealplanner.recipes.COLLAPSE_ALL
import com.naeblis11.mealplanner.recipes.EXPAND_ALL
import com.naeblis11.mealplanner.recipes.RecipeListViewModel
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
    fun headingsCollapseSearchShowsEverythingAndOneButtonFoldsOrOpensAll() {
        // Owner, 2026-10-10: categories and subcategories collapse; Collapse all / Expand all.
        val soup = RecipeSummary(1, "Pho", "Soups", "Asian", null, null)
        val stew = RecipeSummary(2, "Goulash", "Soups", null, null, null)
        val cake = RecipeSummary(3, "Sponge", "Desserts", null, null, null)
        val groups = listOf(
            CategoryGroup("Desserts", emptyList(), listOf(cake)),
            CategoryGroup("Soups", listOf(SubcategoryGroup("Asian", listOf(soup))), listOf(stew)),
        )
        var collapsed by mutableStateOf(setOf<String>())
        var query by mutableStateOf("")
        var collapsedAll: List<String>? = null
        compose.setContent {
            MealPlannerTheme {
                Box(Modifier.requiredSize(LIST_PANE_WIDTH, 800.dp)) {
                    RecipeListScreen(
                        groups, query, {}, {}, {}, {}, {}, { null }, showSettings = false,
                        collapsed = collapsed,
                        onToggle = { key -> collapsed = if (key in collapsed) collapsed - key else collapsed + key },
                        onCollapseAll = { collapsedAll = it; collapsed = collapsed + it.map(RecipeListViewModel::categoryKey) },
                        onExpandAll = { collapsed = emptySet() },
                    )
                }
            }
        }
        // A subcategory folds on its own; the category's other recipes stay.
        compose.onNodeWithText("Asian").performClick()
        compose.onAllNodesWithText("Pho").assertCountEquals(0)
        compose.onNodeWithText("Goulash").assertIsDisplayed()
        // The category folds everything under it, its count still showing.
        compose.onNodeWithText("Soups").performClick()
        compose.onAllNodesWithText("Goulash").assertCountEquals(0)
        compose.onNodeWithText("(2)").assertIsDisplayed()
        // A search shows every match, folded or not, and hides the button.
        query = "o"
        compose.onNodeWithText("Pho").assertIsDisplayed()
        compose.onAllNodesWithText(COLLAPSE_ALL).assertCountEquals(0)
        query = ""
        compose.onAllNodesWithText("Pho").assertCountEquals(0)
        // Collapse all folds every category; then the button opens them all again.
        compose.onNodeWithText(COLLAPSE_ALL).performClick()
        assertEquals(listOf("Desserts", "Soups"), collapsedAll)
        compose.onAllNodesWithText("Sponge").assertCountEquals(0)
        compose.onNodeWithText(EXPAND_ALL).performClick()
        compose.onNodeWithText("Sponge").assertIsDisplayed()
        compose.onNodeWithText("Pho").assertIsDisplayed()
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
