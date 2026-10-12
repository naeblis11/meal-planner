package com.naeblis11.mealplanner.recipes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.naeblis11.mealplanner.data.RecipeSummary
import com.naeblis11.mealplanner.domain.CategoryGroup
import com.naeblis11.mealplanner.domain.SubcategoryGroup
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
        var picked: BookFilter? = null
        compose.setContent {
            MealPlannerTheme {
                RecipeListScreen(
                    listOf(CategoryGroup("Desserts", emptyList(), listOf(toffee))), "", {}, {}, {}, {}, {}, { null },
                    books = listOf("Flanders Family Cookbook"), onBookChange = { picked = it },
                )
            }
        }
        compose.onNodeWithContentDescription("From Flanders Family Cookbook").assertIsDisplayed()
        compose.onNodeWithText("All recipes").assertIsDisplayed()
        compose.onNodeWithText("Flanders Family Cookbook").performClick()
        assertEquals(BookFilter.Only("Flanders Family Cookbook"), picked)
        // Owner, 2026-10-10: everything but the cookbook.
        compose.onNodeWithText(NOT_FROM_A_COOKBOOK).performClick()
        assertEquals(BookFilter.NoBook, picked)
    }

    @Test
    fun filtersByRatingAndSaysWhenTheFiltersLeaveNothing() {
        var min = -1
        compose.setContent {
            MealPlannerTheme {
                RecipeListScreen(emptyList(), "", {}, {}, {}, {}, {}, { null }, minRating = 3, onMinRatingChange = { min = it })
            }
        }
        compose.onNodeWithText(RATED_AT_LEAST).assertIsDisplayed()
        compose.onNodeWithText(NO_FILTER_MATCHES).assertIsDisplayed()
        compose.onNodeWithContentDescription("$RATING_FILTER_ACTION 4 stars").performClick()
        assertEquals(4, min)
        // The filled star's second tap lifts the filter.
        compose.onNodeWithContentDescription(CLEAR_RATING_FILTER).performClick()
        assertEquals(0, min)
    }

    // Owner, 2026-10-11: on a phone the rating filter's stars ran under Collapse all (and its label was crushed into a
    // sliver). The label stays on one line, and Collapse all moves below the stars when they don't share a line.
    @Test
    @Config(qualifiers = "w360dp-h800dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun onAPhoneTheRatingFilterAndCollapseAllDontOverlap() = ratingRowFits()

    @Test
    @Config(qualifiers = "w360dp-h800dp", fontScale = 1.3f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun withLargeTextTheRatingFilterAndCollapseAllDontOverlap() = ratingRowFits()

    @Test
    @Config(qualifiers = "w320dp-h700dp", fontScale = 1.3f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun onASmallPhoneWithLargeTextTheStarsStayOnScreen() = ratingRowFits()

    @Test
    @Config(qualifiers = "w360dp-h800dp", fontScale = 2.0f)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun withTheLargestTextTheStarsStayOnScreen() = ratingRowFits()

    private fun ratingRowFits() {
        compose.setContent { MealPlannerTheme { RecipeListScreen(groups, "", {}, {}, {}, {}, {}, { null }) } }
        val label = compose.onNodeWithText(RATED_AT_LEAST).getUnclippedBoundsInRoot()
        val stars = (1..5).map { compose.onNodeWithContentDescription("$RATING_FILTER_ACTION $it " + if (it == 1) "star" else "stars").getUnclippedBoundsInRoot() }
        val button = compose.onNodeWithText(COLLAPSE_ALL).getUnclippedBoundsInRoot()
        // One line of text, however large the font: a crushed label wraps into a tall column.
        assertTrue("the label is crushed: $label", label.height < 40.dp && label.width > label.height)
        // A squeezed star keeps drawing its icon past its zero-width box, under the button.
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        for (star in stars) {
            assertTrue("a star is squeezed: $star", star.width >= 24.dp)
            assertTrue("a star runs off the screen: $star", star.right <= screen.right)
        }
        for (part in listOf(label) + stars) {
            val apart = part.right <= button.left || part.left >= button.right || part.bottom <= button.top || part.top >= button.bottom
            assertTrue("$part overlaps Collapse all $button", apart)
            assertTrue("$part runs past the label's left edge", part.left >= label.left)
        }
        compose.onNodeWithText(COLLAPSE_ALL).assertIsDisplayed()
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
