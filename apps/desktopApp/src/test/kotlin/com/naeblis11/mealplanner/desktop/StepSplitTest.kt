package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.naeblis11.mealplanner.recipes.RecipeEditViewModel
import com.naeblis11.mealplanner.ui.MealPlannerApp
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** P5-R8: "Split here" (and Ctrl+Enter) cuts a step in two at the cursor, as static/step-editor.js does. */
@OptIn(ExperimentalTestApi::class)
class StepSplitTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-step-split").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })
    private val whole = "Brown the onions. Add the stock."

    @Before
    fun setUp() {
        File(app.recipesDir, "soup.yaml").writeText(
            "recipe_name: Soup\ningredients:\n- Stock:\n    amounts:\n    - amount: 2\n      unit: cup\n" +
                "steps:\n- step: $whole\n  notes:\n  - Use a wide pan.\n",
        )
        File(app.recipesDir, "stew.yaml").writeText(
            "recipe_name: Stew\ningredients:\n- Beef:\n    amounts:\n    - amount: 1\n      unit: lb\n" +
                "steps:\n- step: Chop it. Sear it.\n- step: Braise it. Serve it.\n- step: Rest.\n",
        )
        runBlocking { app.folder.sync() }
    }

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    private fun soupId() = runBlocking { app.container.recipes.allRecipes().single { it.name == "Soup" }.id }

    // Tall, so the whole form (the steps are at the bottom) is composed.
    private fun openTheEditor(width: Dp, recipe: String = "Soup", firstStep: String = whole) {
        compose.showAt(width, height = 2400.dp) { MealPlannerApp(app.container) }
        compose.waitForText(recipe)
        compose.onNodeWithText(recipe).click()
        compose.waitForText(firstStep)
        compose.onNodeWithText("Edit").click()
        compose.waitForText("Edit recipe")
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction() and hasText(firstStep)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ctrlEnter(field: SemanticsNodeInteraction, enter: Key = Key.Enter) {
        field.performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(enter)
            keyUp(Key.CtrlLeft)
        }
    }

    private fun stepField(text: String) = compose.onNode(hasSetTextAction() and hasText(text))

    private fun waitForStep(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction() and hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aSplitStepKeepsItsNotesAndTheRestBecomesTheNextStep() = runBlocking {
        val vm = RecipeEditViewModel(soupId(), app.container.recipes)
        try {
            val form = withTimeout(5_000) { vm.state.first { it.form != null } }.form!!
            vm.splitStep(form.steps.single().key, "Brown the onions.".length)
            assertEquals(listOf("Brown the onions.", "Add the stock."), vm.state.value.form!!.steps.map { it.text })

            vm.save()
            withTimeout(5_000) { vm.savedId.first { it != null } }
            @Suppress("UNCHECKED_CAST")
            val steps = app.container.recipes.doc(soupId())!!["steps"] as List<Map<Any?, Any?>>
            assertEquals(listOf("Brown the onions.", "Add the stock."), steps.map { it["step"] })
            assertEquals(listOf("Use a wide pan."), steps[0]["notes"])
            assertNull(steps[1]["notes"])
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun splitHereCutsTheStepWhereTheCursorIsOnThePhone() {
        openTheEditor(400.dp)
        stepField(whole).performTextInputSelection(TextRange(17))
        compose.onNodeWithContentDescription("Split step 1 at the cursor").click()
        waitForStep("Add the stock.")
        stepField("Brown the onions.").assertExists()
        compose.onNodeWithText("Step 2").assertExists()
    }

    @Test
    fun ctrlEnterSplitsTooOnAWideWindow() {
        openTheEditor(1000.dp)
        val field = stepField(whole)
        field.performSemanticsAction(SemanticsActions.RequestFocus)
        field.performTextInputSelection(TextRange(17))
        field.performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.Enter)
            keyUp(Key.CtrlLeft)
        }
        waitForStep("Add the stock.")
        stepField("Brown the onions.").assertExists()
    }

    @Test
    fun theNumericPadEnterSplitsToo() {
        openTheEditor(1000.dp)
        val field = stepField(whole)
        field.performSemanticsAction(SemanticsActions.RequestFocus)
        field.performTextInputSelection(TextRange(17))
        ctrlEnter(field, Key.NumPadEnter)
        waitForStep("Add the stock.")
        stepField("Brown the onions.").assertExists()
    }

    @Test
    fun ctrlEnterSplitsOnlyTheStepThatHasTheFocus() {
        openTheEditor(1000.dp, recipe = "Stew", firstStep = "Chop it. Sear it.")
        val second = stepField("Braise it. Serve it.")
        second.performSemanticsAction(SemanticsActions.RequestFocus)
        second.performTextInputSelection(TextRange("Braise it.".length))
        ctrlEnter(second)
        waitForStep("Serve it.")

        stepField("Chop it. Sear it.").assertExists()
        stepField("Braise it.").assertExists()
        stepField("Rest.").assertExists()
        compose.onNodeWithText("Step 4").assertExists()
        compose.onNodeWithText("Step 5").assertDoesNotExist()
    }

    @Test
    fun ctrlEnterWithTheCursorAtTheStartLeavesTheStepAlone() {
        openTheEditor(1000.dp)
        val field = stepField(whole)
        field.performSemanticsAction(SemanticsActions.RequestFocus)
        field.performTextInputSelection(TextRange(0))
        ctrlEnter(field)
        compose.waitForIdle()

        stepField(whole).assertExists()
        compose.onNodeWithText("Step 2").assertDoesNotExist()
    }
}
