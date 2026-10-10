package com.naeblis11.mealplanner.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.backup.ImportAction
import com.naeblis11.mealplanner.backup.StagedKind
import com.naeblis11.mealplanner.domain.EditorMoves
import com.naeblis11.mealplanner.domain.SubmittedRow
import com.naeblis11.mealplanner.importing.ImportScreen
import com.naeblis11.mealplanner.importing.ImportState
import com.naeblis11.mealplanner.importing.ReviewRow
import com.naeblis11.mealplanner.recipes.EditorRowState
import com.naeblis11.mealplanner.recipes.IngredientEditor
import com.naeblis11.mealplanner.recipes.StepActions
import com.naeblis11.mealplanner.recipes.StepEditor
import com.naeblis11.mealplanner.recipes.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * P5-R9: on the desktop, ingredient and step rows drag by their handle; a heading moves alone, as on the server. A drag
 * let go outside its list, or cancelled, leaves the order as it was.
 */
@OptIn(ExperimentalTestApi::class)
class DragReorderTest {
    @get:Rule
    val compose = createComposeRule()

    private fun ingredient(key: String, name: String) = EditorRowState(key, SubmittedRow.INGREDIENT, name)

    private fun heading(key: String, name: String) = EditorRowState(key, SubmittedRow.SECTION, name)

    private val flourSugarEggs = listOf(ingredient("a", "Flour"), ingredient("b", "Sugar"), ingredient("c", "Eggs"))

    // Whether the window has the focus, as the editors see it.
    private var windowFocused by mutableStateOf(true)

    /**
     * [content] at [density], so a whole list fits the 1024 x 768 test window: a press must start inside it. The
     * moves and bounds are in pixels, so the drop rules are the same at any density.
     */
    private fun showCompact(density: Float = 0.5f, content: @Composable () -> Unit) = compose.showAt(1000.dp) {
        CompositionLocalProvider(LocalDensity provides Density(density)) { WithWindowFocus(content) }
    }

    // The editor over rows held here; the drop moves them as the view model does. Returns a reader of the rows.
    private fun showIngredients(start: List<EditorRowState>, dropping: Boolean = true): () -> List<EditorRowState> {
        var rows by mutableStateOf(start)
        showCompact {
            IngredientEditor(
                rows,
                { _, _ -> },
                {},
                { _, _ -> },
                {},
                {},
                onMoveTo = if (dropping) ({ key, to -> rows = EditorMoves.movedTo(rows, rows.indexOfFirst { it.key == key }, to) }) else null,
            )
        }
        return { rows }
    }

    @Composable
    private fun WithWindowFocus(content: @Composable () -> Unit) {
        val real = LocalWindowInfo.current
        val info = remember(real) {
            object : WindowInfo by real {
                override val isWindowFocused: Boolean get() = windowFocused
            }
        }
        CompositionLocalProvider(LocalWindowInfo provides info) { content() }
    }

    private fun bounds(node: SemanticsNodeInteraction): Rect = node.fetchSemanticsNode().boundsInRoot

    private fun handle(label: String) = compose.onNodeWithContentDescription("Drag $label")

    // Every editable field's top edge, the highest first: the first row's top is a little above the first one.
    private fun firstFieldTop(): Float = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().minOf { it.boundsInRoot.top }

    // The list's last line ("Add ingredient", "Add step") ends a little above the list's bottom edge.
    private fun listBottom(lastButton: String): Float = bounds(compose.onNodeWithText(lastButton)).bottom

    /** Presses on [label]'s handle and moves the mouse, in steps, until it is at [y] in the window. */
    private fun pressAndMoveTo(label: String, y: Float, x: Float = 0f) {
        val from = bounds(handle(label)).center
        handle(label).performMouseInput {
            moveTo(center)
            press()
            repeat(10) { moveBy(Offset(x / 10f, (y - from.y) / 10f)) }
        }
        compose.waitForIdle()
    }

    private fun release(label: String) {
        handle(label).performMouseInput { release() }
        compose.waitForIdle()
    }

    private fun dragTo(label: String, y: Float, x: Float = 0f) {
        pressAndMoveTo(label, y, x)
        release(label)
    }

    @Test
    fun anIngredientDraggedDownLandsBelowTheRowsItPassed() {
        val rows = showIngredients(flourSugarEggs)
        dragTo("Flour", listBottom("Add ingredient") - 2f)
        assertEquals(listOf("Sugar", "Eggs", "Flour"), rows().map { it.name })
    }

    @Test
    fun anIngredientDraggedUpLandsAboveThem() {
        val rows = showIngredients(flourSugarEggs)
        dragTo("Eggs", firstFieldTop() - 4f)
        assertEquals(listOf("Eggs", "Flour", "Sugar"), rows().map { it.name })
    }

    @Test
    fun anIngredientDraggedPastOneMiddleMovesOnePlace() {
        val rows = showIngredients(flourSugarEggs)
        // Flour's handle to just past Sugar's handle: Flour's middle passes Sugar's, not Eggs'.
        dragTo("Flour", bounds(handle("Sugar")).center.y + 10f)
        assertEquals(listOf("Sugar", "Flour", "Eggs"), rows().map { it.name })
    }

    @Test
    fun theDraggedRowFollowsThePointerAllTheWay() {
        showIngredients(flourSugarEggs)
        val target = bounds(handle("Eggs")).center.y
        pressAndMoveTo("Flour", target)
        // The handle under the pointer, not behind it: the row moved as far as the mouse did.
        assertEquals(target, bounds(handle("Flour")).center.y, 1f)
        release("Flour")
    }

    @Test
    fun aHeadingMovesAloneSoTheIngredientBelowLeavesItsSubRecipe() {
        val rows = showIngredients(listOf(ingredient("a", "Flour"), heading("b", "Sauce"), ingredient("c", "Butter")))
        dragTo("heading Sauce", listBottom("Add ingredient") - 2f)
        assertEquals(listOf("Flour", "Butter", "Sauce"), rows().map { it.name })
        assertEquals(SubmittedRow.SECTION, rows().last().kind)
    }

    @Test
    fun aStepIsDraggedToo() {
        var steps by mutableStateOf(listOf(StepState("e0", "Chop."), StepState("e1", "Fry."), StepState("e2", "Serve.")))
        showCompact {
            StepEditor(
                steps,
                StepActions({ _, _ -> }, {}, { _, _ -> }, {}, moveTo = { key, to -> steps = EditorMoves.movedTo(steps, steps.indexOfFirst { it.key == key }, to) }),
            )
        }
        dragTo("step 1", listBottom("Add step") - 2f)
        assertEquals(listOf("Fry.", "Serve.", "Chop."), steps.map { it.text })
    }

    @Test
    fun withoutADropThereIsNoHandle() {
        showIngredients(listOf(ingredient("a", "Flour"), heading("b", "Sauce")), dropping = false)
        compose.onAllNodesWithContentDescription("Drag Flour").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Drag heading Sauce").assertCountEquals(0)
        // Up and Down stay.
        compose.onNodeWithContentDescription("Move Flour down").assertExists()
    }

    @Test
    fun aDragLetGoBelowTheListLeavesTheOrderAsItWas() {
        val rows = showIngredients(flourSugarEggs)
        dragTo("Flour", listBottom("Add ingredient") + 60f)
        assertEquals(listOf("Flour", "Sugar", "Eggs"), rows().map { it.name })
        dragTo("Eggs", 5_000f)
        assertEquals(listOf("Flour", "Sugar", "Eggs"), rows().map { it.name })
    }

    @Test
    fun aDragLetGoAboveOrBesideTheListLeavesTheOrderAsItWas() {
        val rows = showIngredients(flourSugarEggs)
        dragTo("Eggs", -50f)
        assertEquals(listOf("Flour", "Sugar", "Eggs"), rows().map { it.name })
        // Down past Eggs, but off the list's right edge.
        dragTo("Flour", listBottom("Add ingredient") - 2f, x = 2_000f)
        assertEquals(listOf("Flour", "Sugar", "Eggs"), rows().map { it.name })
    }

    @Test
    fun onlyThePrimaryButtonDrags() {
        val rows = showIngredients(flourSugarEggs)
        val to = listBottom("Add ingredient") - 2f
        for (button in listOf(MouseButton.Secondary, MouseButton.Tertiary)) {
            val from = bounds(handle("Flour")).center
            handle("Flour").performMouseInput {
                moveTo(center)
                press(button)
                repeat(10) { moveBy(Offset(0f, (to - from.y) / 10f)) }
                release(button)
            }
            compose.waitForIdle()
            assertEquals("$button", listOf("Flour", "Sugar", "Eggs"), rows().map { it.name })
        }
    }

    private fun field(text: String) = compose.onNode(hasSetTextAction() and hasText(text))

    @Test
    fun theFieldThatHadTheFocusHasItAgainAfterADrop() {
        val rows = showIngredients(flourSugarEggs)
        field("Sugar").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        dragTo("Flour", listBottom("Add ingredient") - 2f)
        assertEquals(listOf("Sugar", "Eggs", "Flour"), rows().map { it.name })
        field("Sugar").assertIsFocused()
        handle("Flour").assertIsNotFocused()
    }

    @Test
    fun theFieldThatHadTheFocusHasItAgainAfterEscape() {
        showIngredients(flourSugarEggs)
        field("Eggs").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        pressAndMoveTo("Flour", listBottom("Add ingredient") - 2f)
        handle("Flour").assertIsFocused()
        handle("Flour").performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        release("Flour")
        field("Eggs").assertIsFocused()
        handle("Flour").assertIsNotFocused()
    }

    @Test
    fun withNothingFocusedBeforeTheHandleKeepsNoFocusAfter() {
        showIngredients(flourSugarEggs)
        dragTo("Flour", listBottom("Add ingredient") - 2f)
        handle("Flour").assertIsNotFocused()
    }

    @Test
    fun escapeCancelsADrag() {
        val rows = showIngredients(flourSugarEggs)
        pressAndMoveTo("Flour", listBottom("Add ingredient") - 2f)
        handle("Flour").assertIsFocused()
        handle("Flour").performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        release("Flour")
        assertEquals(listOf("Flour", "Sugar", "Eggs"), rows().map { it.name })
        // The next drag works as before.
        dragTo("Flour", listBottom("Add ingredient") - 2f)
        assertEquals(listOf("Sugar", "Eggs", "Flour"), rows().map { it.name })
    }

    @Test
    fun theWindowLosingFocusCancelsADrag() {
        val rows = showIngredients(flourSugarEggs)
        pressAndMoveTo("Flour", listBottom("Add ingredient") - 2f)
        windowFocused = false
        compose.waitForIdle()
        release("Flour")
        assertEquals(listOf("Flour", "Sugar", "Eggs"), rows().map { it.name })
    }

    @Test
    fun inTheImportReviewADragMovesRowsOnlyWithinItsOwnRecipe() {
        // Two recipes whose rows share keys, as the review's rows do.
        fun recipe(id: Int, title: String, names: List<String>) = ReviewRow(
            tempId = id, kind = StagedKind.NEW, originalTitle = title, title = title, category = "", subcategory = "",
            servingsAmount = "", servingsUnit = "", action = ImportAction.IMPORT,
            rows = names.mapIndexed { i, name -> ingredient("e$i", name) }, amountIssues = 1,
        )
        var review by mutableStateOf(
            ImportState.Reviewing(
                "two.zip",
                listOf(recipe(0, "Cake", listOf("Flour", "Sugar", "Eggs")), recipe(1, "Bread", listOf("Yeast", "Water", "Salt"))),
                emptyList(),
            ),
        )
        showCompact(density = 0.3f) {
            ImportScreen(
                review,
                onUpdate = { id, change -> review = review.copy(rows = review.rows.map { if (it.tempId == id) change(it) else it }) },
                onConfirm = {},
                onCancel = {},
                onDone = {},
            )
        }
        compose.waitForIdle()
        // Flour down past Eggs, inside Cake's list: Cake's rows move, Bread's don't.
        // Cake's "Add ingredient" comes first.
        val cakeBottom = compose.onAllNodes(hasText("Add ingredient")).fetchSemanticsNodes().first().boundsInRoot.bottom
        dragTo("Flour", cakeBottom - 2f)
        assertEquals(listOf("Sugar", "Eggs", "Flour"), review.rows[0].rows.map { it.name })
        assertEquals(listOf("Yeast", "Water", "Salt"), review.rows[1].rows.map { it.name })
        // Down into Bread's list is outside Cake's: nothing moves.
        dragTo("Sugar", bounds(handle("Water")).center.y)
        assertEquals(listOf("Sugar", "Eggs", "Flour"), review.rows[0].rows.map { it.name })
        assertEquals(listOf("Yeast", "Water", "Salt"), review.rows[1].rows.map { it.name })
    }

    @Test
    fun theHandleStillLetsTheButtonsWrapOnASmallPhoneWithLargeText() {
        val steps = listOf(StepState("e0", "Simmer."), StepState("e1", "Serve."))
        compose.showAt(320.dp) {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                Box(Modifier.padding(16.dp)) { StepEditor(steps, StepActions({ _, _ -> }, {}, { _, _ -> }, {}, moveTo = { _, _ -> })) }
            }
        }
        compose.waitForIdle()
        for (description in listOf("Drag step 1", "Split step 1 at the cursor", "Move step 1 up", "Move step 1 down", "Remove step 1")) {
            val node = compose.onNodeWithContentDescription(description)
            node.assertIsDisplayed()
            val box = node.getBoundsInRoot()
            assertTrue("$description at $box starts left of the window", box.left >= 0.dp)
            assertTrue("$description at $box ends right of the window", box.right <= 320.dp - 16.dp)
        }
        compose.onNodeWithContentDescription("Split step 1 at the cursor").assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Split step 1 at the cursor").assertHeightIsAtLeast(48.dp)
    }
}
