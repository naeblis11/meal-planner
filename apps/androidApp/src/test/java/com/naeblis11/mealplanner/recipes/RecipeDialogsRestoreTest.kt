package com.naeblis11.mealplanner.recipes

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** P5-R7: what was chosen in the assign and category dialogs survives a rotation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class RecipeDialogsRestoreTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 5)

    private val view = RecipeView(
        id = 1, name = "Soup", category = null, subcategory = null, imageFilename = null, rating = null, author = null,
        sourceUrl = null, book = null, oven = null, notes = emptyList(), servings = "4", servingsUnit = "servings",
        canScale = true, ingredients = listOf(IngredientView(null, "Stock", "2", "cup", emptyList(), emptyList())),
        steps = listOf(StepView(1, "Simmer.", emptyList())),
    )

    private var assigned: Triple<LocalDate, String, String>? = null
    private var category: Pair<String, String>? = null

    private fun show(): StateRestorationTester {
        val restorer = StateRestorationTester(compose)
        restorer.setContent {
            MealPlannerTheme {
                RecipeDetailScreen(
                    view, null, null, null, {}, {}, {}, {}, {}, {}, null, {}, {},
                    onAssign = { day, slot, servings -> assigned = Triple(day, slot, servings) },
                    today = today,
                    onSetCategory = { main, sub -> category = main to sub },
                )
            }
        }
        return restorer
    }

    @Test
    fun theAssignDialogKeepsItsDaySlotAndServings() {
        val restorer = show()
        compose.onNodeWithText(ASSIGN_TO_CALENDAR).performClick()
        compose.onNodeWithContentDescription("Next day").performClick()
        compose.onNodeWithText("Lunch").performClick()
        compose.onNode(hasText(ASSIGN_SERVINGS_LABEL) and hasSetTextAction()).performTextReplacement("3")

        restorer.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Assign").performClick()
        assertEquals(Triple(today.plusDays(1), "Lunch", "3"), assigned)
    }

    @Test
    fun theCategoryDialogKeepsWhatWasTyped() {
        val restorer = show()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Category").performClick()
        compose.onNode(hasText("Category") and hasSetTextAction()).performTextReplacement("Soups & Stews")
        compose.onNode(hasText("Subcategory") and hasSetTextAction()).performTextReplacement("Beef")

        restorer.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Save category").performClick()
        assertEquals("Soups & Stews" to "Beef", category)
    }
}
