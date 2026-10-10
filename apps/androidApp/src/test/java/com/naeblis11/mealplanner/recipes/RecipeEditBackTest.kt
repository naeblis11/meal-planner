package com.naeblis11.mealplanner.recipes

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Leaving the editor never loses typing without asking, and never interrupts a save. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class RecipeEditBackTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val form = RecipeForm(name = "Soup", steps = listOf(StepState("e0", "Stir.")))
    private var left = 0

    private fun show(state: EditState) = compose.setContent {
        MealPlannerTheme {
            RecipeEditScreen(
                state, onCancel = { left++ }, onSave = {}, onEdit = {},
                rowActions = RowActions({ _, _ -> }, {}, { _, _ -> }, {}, {}),
                stepActions = StepActions({ _, _ -> }, {}, { _, _ -> }, {}),
            )
        }
    }

    private fun pressBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test
    fun backWithUnsavedChangesAsksFirst() {
        show(EditState(form = form, dirty = true))
        pressBack()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        assertEquals(0, left)

        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Discard").performClick()
        assertEquals(1, left)
    }

    @Test
    fun cancelWithNothingChangedLeavesAtOnce() {
        show(EditState(form = form))
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, left)
        compose.onAllNodesWithText("Discard changes?").assertCountEquals(0)
    }

    @Test
    fun backDuringASaveIsIgnored() {
        show(EditState(form = form, dirty = true, saving = true))
        pressBack()
        compose.waitForIdle()
        assertEquals(0, left)
        // Without the guard, Back would finish the bare activity.
        assertFalse(compose.activity.isFinishing)
        compose.onAllNodesWithText("Discard changes?").assertCountEquals(0)
    }
}
