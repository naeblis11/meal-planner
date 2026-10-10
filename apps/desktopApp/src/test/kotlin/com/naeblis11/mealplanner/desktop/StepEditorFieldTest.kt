package com.naeblis11.mealplanner.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.recipes.StepActions
import com.naeblis11.mealplanner.recipes.StepEditor
import com.naeblis11.mealplanner.recipes.StepState
import com.naeblis11.mealplanner.domain.EditorMoves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The step editor on its own: a field that stays in step with the list it edits, and a row of buttons that fits a phone. */
class StepEditorFieldTest {
    @get:Rule
    val compose = createComposeRule()

    // What the view model holds. The screen is handed it a frame later, as a flow collected into state is.
    private var steps = listOf(StepState("e0", "Simmer."), StepState("e1", "Serve."))
    private var version by mutableStateOf(0)

    private fun set(next: List<StepState>) {
        steps = next
        version++
    }

    private val actions = StepActions(
        change = { key, text -> set(steps.map { if (it.key == key) it.copy(text = text) else it }) },
        remove = { key -> set(steps.filterNot { it.key == key }) },
        move = { _, _ -> },
        add = {},
        split = { key, cursor ->
            val index = steps.indexOfFirst { it.key == key }
            val parts = EditorMoves.splitStep(steps[index].text, cursor)
            if (parts != null) {
                set(
                    steps.toMutableList().apply {
                        this[index] = this[index].copy(text = parts.first)
                        add(index + 1, StepState("n$index", parts.second))
                    },
                )
            }
        },
    )

    @Composable
    private fun LaggingEditor() {
        var shown by remember { mutableStateOf(steps) }
        LaunchedEffect(version) {
            withFrameNanos { }
            shown = steps
        }
        StepEditor(shown, actions)
    }

    private fun field(text: String) = compose.onNode(hasSetTextAction() and hasText(text))

    private fun fieldCount(text: String) = compose.onAllNodes(hasSetTextAction() and hasText(text)).fetchSemanticsNodes().size

    @Test
    fun aLetterTypedAndDeletedWhileTheListIsAFrameBehindStaysDeleted() {
        compose.showAt(400.dp) { LaggingEditor() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        field("Simmer.").performTextReplacement("Simmer.a")
        // The field shows "Simmer.a" now, while the list it was handed still says "Simmer.".
        compose.mainClock.advanceTimeByFrame()
        field("Simmer.a").performTextReplacement("Simmer.")
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertEquals(listOf("Simmer.", "Serve."), steps.map { it.text })
        assertEquals(1, fieldCount("Simmer."))
        assertEquals(0, fieldCount("Simmer.a"))
    }

    @Test
    fun aKeyTypedBeforeTheFrameThatShowsAnEarlierEchoIsKept() {
        // The screen's list is state written outside the frame, as collectAsStateWithLifecycle writes it, so an echo
        // of one keystroke can be waiting for the frame while the next keystroke is typed.
        var shown by mutableStateOf(steps)
        val viewModel = StepActions(
            change = { key, text -> steps = steps.map { if (it.key == key) it.copy(text = text) else it } },
            remove = {},
            move = { _, _ -> },
            add = {},
            split = { _, _ -> },
        )
        compose.showAt(400.dp) { StepEditor(shown, viewModel) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        field("Simmer.").performTextReplacement("Simmer.a")
        compose.mainClock.advanceTimeByFrame()
        // The echo of "Simmer.a" lands, and before its frame runs the next key is typed.
        shown = steps
        field("Simmer.a").performTextReplacement("Simmer.ab")
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertEquals(1, fieldCount("Simmer.ab"))
        assertEquals(0, fieldCount("Simmer.a"))
        assertEquals(listOf("Simmer.ab", "Serve."), steps.map { it.text })
    }

    @Test
    fun aSplitUpdatesTheFirstHalfsFieldAndAddsTheSecond() {
        compose.showAt(400.dp) { LaggingEditor() }
        field("Simmer.").performTextInputSelection(TextRange(3))
        compose.onNodeWithContentDescription("Split step 1 at the cursor").click()
        compose.waitUntil(5_000) { fieldCount("mer.") == 1 }

        assertEquals(1, fieldCount("Sim"))
        assertEquals(listOf("Sim", "mer.", "Serve."), steps.map { it.text })
    }

    @Test
    fun aChangeFromOutsideReplacesWhatTheFieldShows() {
        compose.showAt(400.dp) { LaggingEditor() }
        field("Serve.").performTextReplacement("Serve hot.")
        compose.waitForIdle()
        set(steps.map { if (it.key == "e1") it.copy(text = "Serve cold.") else it })
        compose.waitUntil(5_000) { fieldCount("Serve cold.") == 1 }
        assertEquals(0, fieldCount("Serve hot."))
    }

    @Test
    fun theButtonsWrapOnASmallPhoneWithLargeText() {
        // 320 dp wide with the screen's 16 dp sides, and text at twice the size.
        compose.showAt(320.dp) {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                Box(Modifier.padding(16.dp)) { StepEditor(steps, actions) }
            }
        }
        compose.waitForIdle()
        for (description in listOf("Split step 1 at the cursor", "Move step 1 up", "Move step 1 down", "Remove step 1")) {
            val button = compose.onNodeWithContentDescription(description)
            button.assertIsDisplayed()
            assertInside(button)
        }
        compose.onNodeWithContentDescription("Split step 1 at the cursor").assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Split step 1 at the cursor").assertHeightIsAtLeast(48.dp)
    }

    private fun assertInside(node: SemanticsNodeInteraction) {
        val bounds = node.getBoundsInRoot()
        assertTrue("$bounds starts left of the window", bounds.left >= 0.dp)
        assertTrue("$bounds ends right of the window", bounds.right <= 320.dp - 16.dp)
    }
}
