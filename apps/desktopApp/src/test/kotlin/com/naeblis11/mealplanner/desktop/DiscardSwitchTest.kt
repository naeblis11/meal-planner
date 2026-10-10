package com.naeblis11.mealplanner.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.naeblis11.mealplanner.recipes.EditState
import com.naeblis11.mealplanner.recipes.RecipeEditScreen
import com.naeblis11.mealplanner.recipes.RecipeForm
import com.naeblis11.mealplanner.recipes.RowActions
import com.naeblis11.mealplanner.recipes.StepActions
import com.naeblis11.mealplanner.recipes.StepState
import com.naeblis11.mealplanner.ui.LeaveGuard
import com.naeblis11.mealplanner.ui.LocalLeaveGuard
import com.naeblis11.mealplanner.ui.MealPlannerApp
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Discard on a switch of section closes the form first, and switches only if the form did close. */
class DiscardSwitchTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val form = RecipeForm(name = "Soup", steps = listOf(StepState("e0", "Stir.")))

    @Test
    fun discardSwitchesSectionOnlyAfterTheFormClosed() {
        val guard = LeaveGuard()
        var formCloses = false
        var closes = 0
        var switches = 0
        compose.showAt(600.dp) {
            CompositionLocalProvider(LocalLeaveGuard provides guard) {
                RecipeEditScreen(
                    EditState(form = form, dirty = true),
                    onCancel = {},
                    onSave = {},
                    onEdit = {},
                    rowActions = RowActions({ _, _ -> }, {}, { _, _ -> }, {}, {}),
                    stepActions = StepActions({ _, _ -> }, {}, { _, _ -> }, {}),
                    onLeave = { switchSection ->
                        closes++
                        if (formCloses) switchSection()
                    },
                )
            }
        }

        // The close is dropped (the page wasn't resumed): the form stays, so the section must too.
        compose.runOnIdle { guard.request { switches++ } }
        compose.waitForText("Discard changes?")
        compose.onNodeWithText("Discard").click()
        compose.waitForIdle()
        assertEquals(1 to 0, closes to switches)

        // The close happens: the switch follows it.
        formCloses = true
        compose.runOnIdle { guard.request { switches++ } }
        compose.waitForText("Discard changes?")
        compose.onNodeWithText("Discard").click()
        compose.waitForIdle()
        assertEquals(2 to 1, closes to switches)
    }

    /** The window's lifecycle, moved by the test: below RESUMED, every page under it is too, and drops its closes. */
    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun theRecipePaneKeepsTheFormWhenItsCloseIsDropped() {
        val dir: File = Files.createTempDirectory("mp-discard").toFile()
        val app = DesktopApp(dir, settingsFactory = { MapSettings() })
        closing.add {
            app.close()
            dir.deleteRecursively()
        }
        File(app.recipesDir, "soup.yaml").writeText(
            "recipe_name: Soup\ningredients:\n- Stock:\n    amounts:\n    - amount: 2\n      unit: cup\nsteps:\n- step: Simmer.\n",
        )
        runBlocking { app.folder.sync() }
        val owner = TestOwner()
        compose.showAt(1000.dp) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) { MealPlannerApp(app.container) }
        }
        compose.waitForText("Soup")
        compose.onNodeWithText("Soup").click()
        compose.waitForText("Simmer.")
        compose.onNodeWithText("Edit").click()
        compose.waitForText("Edit recipe")
        compose.onNode(hasText("Title") and hasSetTextAction()).performTextReplacement("Soup with leeks")
        compose.waitForText("Soup with leeks")

        // The pane's Edit page isn't resumed now, so Discard's close is dropped: the section must stay with it.
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.tab("Pantry").click()
        compose.waitForText(DISCARD_TITLE)
        compose.onNodeWithText("Discard").click()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(DISCARD_TITLE).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText("Edit recipe").assertIsDisplayed()
        compose.onNodeWithText("Soup with leeks").assertExists()
        compose.tab("Recipes").assertIsSelected()
        compose.onAllNodesWithText(PANTRY_INTRO).assertCountEquals(0)

        // Resumed again: Discard closes the form and the switch follows.
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.tab("Pantry").click()
        compose.waitForText(DISCARD_TITLE)
        compose.onNodeWithText("Discard").click()
        compose.waitForText(PANTRY_INTRO)
        compose.tab("Pantry").assertIsSelected()
    }

    private companion object {
        const val PANTRY_INTRO = "What you've already got on hand."
        const val DISCARD_TITLE = "Discard changes?"
    }
}
