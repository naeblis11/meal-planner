package com.naeblis11.mealplanner.recipes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.naeblis11.mealplanner.domain.SubmittedRow
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class RecipeEditScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val form = RecipeForm(
        name = "Soup",
        rows = listOf(EditorRowState("e0", SubmittedRow.INGREDIENT, "Salt", "a pinch", "", needsInput = true)),
        steps = listOf(StepState("e0", "Stir.")),
    )

    @Test
    fun showsTheErrorAndCallsSaveAndAdd() {
        var saved = false
        var added = false
        compose.setContent {
            MealPlannerTheme {
                RecipeEditScreen(
                    state = EditState(form = form, error = "A recipe needs a title."),
                    onCancel = {}, onSave = { saved = true }, onEdit = {},
                    rowActions = RowActions({ _, _ -> }, {}, { _, _ -> }, { added = true }, {}),
                    stepActions = StepActions({ _, _ -> }, {}, { _, _ -> }, {}),
                )
            }
        }
        compose.onNodeWithText("A recipe needs a title.").assertIsDisplayed()
        compose.onNodeWithText("Check this amount").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Add ingredient").performClick()
        assertTrue(saved)
        assertTrue(added)
    }

    @androidx.compose.runtime.Composable
    private fun screen(state: EditState) {
        RecipeEditScreen(
            state = state, onCancel = {}, onSave = {}, onEdit = {},
            rowActions = RowActions({ _, _ -> }, {}, { _, _ -> }, {}, {}),
            stepActions = StepActions({ _, _ -> }, {}, { _, _ -> }, {}),
        )
    }

    @Test
    fun anErrorAfterScrollingDownIsScrolledIntoViewAndAnnounced() {
        val long = form.copy(steps = (1..80).map { StepState("e$it", "Step text $it") })
        var state by mutableStateOf(EditState(form = long))
        compose.setContent { MealPlannerTheme { screen(state) } }
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText("Add step"))
        compose.onAllNodesWithText("Title").assertCountEquals(0)

        state = state.copy(error = "A recipe needs a title.")
        compose.waitForIdle()
        compose.onNodeWithText("A recipe needs a title.")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun rowButtonsNameTheirRow() {
        val rows = listOf(
            EditorRowState("e0", SubmittedRow.SECTION, "Sauce"),
            EditorRowState("e1", SubmittedRow.INGREDIENT, "Flour", "1", "cup"),
            EditorRowState("e2", SubmittedRow.INGREDIENT, ""),
        )
        compose.setContent { MealPlannerTheme { screen(EditState(form = form.copy(rows = rows))) } }
        compose.onNodeWithContentDescription("Move Flour up").assertExists()
        compose.onNodeWithContentDescription("Move Flour down").assertExists()
        compose.onNodeWithContentDescription("Remove Flour").assertExists()
        compose.onNodeWithContentDescription("Remove heading Sauce").assertExists()
        compose.onNodeWithContentDescription("Remove ingredient 3").assertExists()
        compose.onNodeWithContentDescription("Move step 1 up").assertExists()
        compose.onNodeWithContentDescription("Remove step 1").assertExists()
    }
}
