package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.naeblis11.mealplanner.domain.ServingsInput
import com.naeblis11.mealplanner.domain.Week
import com.naeblis11.mealplanner.recipes.ASSIGN_SERVINGS_LABEL
import com.naeblis11.mealplanner.recipes.ASSIGN_TO_CALENDAR
import com.naeblis11.mealplanner.recipes.IngredientView
import com.naeblis11.mealplanner.recipes.RecipeDetailScreen
import com.naeblis11.mealplanner.recipes.RecipeView
import com.naeblis11.mealplanner.recipes.StepView
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P5-R7: the assign dialog on a 320 dp phone, its day stepper, and servings that don't read. */
@OptIn(ExperimentalTestApi::class)
class AssignDialogTest {
    // The longest weekday name, so the day label is at its widest.
    private val wednesday = LocalDate.of(2026, 9, 30)

    private val view = RecipeView(
        id = 1, name = "Soup", category = null, subcategory = null, imageFilename = null, rating = null, author = null,
        sourceUrl = null, book = null, oven = null, notes = emptyList(), servings = "4", servingsUnit = "servings",
        canScale = true, ingredients = listOf(IngredientView(null, "Stock", "2", "cup", emptyList(), emptyList())),
        steps = listOf(StepView(1, "Simmer.", emptyList())),
    )

    private var assigned: Triple<LocalDate, String, String>? = null

    // The whole window is 320 dp wide here (density 1), so the dialog is laid out as on a small phone.
    private fun phone(block: DesktopComposeUiTest.() -> Unit) = runDesktopComposeUiTest(width = 320, height = 760) {
        assertEquals(1f, density.density)
        setContent {
            MealPlannerTheme {
                RecipeDetailScreen(
                    view = view, photo = null, message = null, loadError = null, onMessageShown = {}, onBack = {}, onEdit = {},
                    onScale = {}, onRate = {}, onDelete = {}, onTakePhoto = null, onChoosePhoto = {}, onRemovePhoto = {},
                    onAssign = { day, slot, servings -> assigned = Triple(day, slot, servings) }, today = wednesday,
                )
            }
        }
        onNodeWithText(ASSIGN_TO_CALENDAR).performSemanticsAction(SemanticsActions.OnClick)
        block()
    }

    private fun SemanticsNodeInteraction.assertOnScreen() {
        assertIsDisplayed()
        val bounds = getUnclippedBoundsInRoot()
        assertTrue("$bounds is clipped", bounds.left.value >= 0f && bounds.right.value <= 320f)
    }

    @Test
    fun everythingFitsAPhoneOf320dp() = phone {
        val label = onNodeWithText(Week.dayLabel(wednesday))
        label.assertOnScreen()
        // One line, not wrapped under the steppers.
        val labelBounds = label.getUnclippedBoundsInRoot()
        assertTrue("$labelBounds wraps", (labelBounds.bottom - labelBounds.top).value < 32f)
        onNodeWithContentDescription("Previous day").assertOnScreen()
        onNodeWithContentDescription("Next day").assertOnScreen()
        for (slot in Week.SLOTS) onNodeWithText(slot).assertOnScreen()
        onNode(hasText(ASSIGN_SERVINGS_LABEL) and hasSetTextAction()).assertOnScreen()
        onNodeWithText("Assign").assertOnScreen()
        onNodeWithText("Cancel").assertOnScreen()
    }

    @Test
    fun previousDayStepsBack() = phone {
        onNodeWithContentDescription("Previous day").performSemanticsAction(SemanticsActions.OnClick)
        onNodeWithText(Week.dayLabel(wednesday.minusDays(1))).assertIsDisplayed()
        onNodeWithText("Assign").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(Triple(wednesday.minusDays(1), "Dinner", ""), assigned)
    }

    @Test
    fun servingsThatDontReadKeepTheDialogOpenWithTheHint() = phone {
        onNode(hasText(ASSIGN_SERVINGS_LABEL) and hasSetTextAction()).performTextReplacement("lots")
        onNodeWithText("Assign").performSemanticsAction(SemanticsActions.OnClick)
        onNodeWithText(ServingsInput.HINT).assertIsDisplayed()
        onNodeWithText("Assign").assertIsDisplayed()
        assertNull(assigned)
    }
}
