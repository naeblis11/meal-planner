package com.naeblis11.mealplanner.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.ui.MealPlannerApp
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** P5-R9 in the app: a drag in Edit Recipe reaches the view model, and Save writes the new order to the file. */
@OptIn(ExperimentalTestApi::class)
class DragReorderEditTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-drag-edit").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })
    private val file get() = File(app.recipesDir, "cake.yaml")

    @Before
    fun setUp() {
        val amount = "    amounts:\n    - amount: 1\n      unit: cup\n"
        file.writeText(
            "recipe_name: Cake\ningredients:\n- Flour:\n$amount- Sugar:\n$amount- Eggs:\n$amount" +
                "steps:\n- step: Mix it.\n- step: Bake it.\n- step: Cool it.\n",
        )
        runBlocking { app.folder.sync() }
    }

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    private fun handle(label: String) = compose.onNodeWithContentDescription("Drag $label")

    private fun dragTo(label: String, y: Float) {
        val from = handle(label).fetchSemanticsNode().boundsInRoot.center
        handle(label).performMouseInput {
            moveTo(center)
            press()
            repeat(10) { moveBy(Offset(0f, (y - from.y) / 10f)) }
            release()
        }
        compose.waitForIdle()
    }

    // Small, so the whole form fits the test window: a press must start inside it.
    private fun openTheEditor() {
        compose.showAt(1000.dp) {
            CompositionLocalProvider(LocalDensity provides Density(0.3f)) { MealPlannerApp(app.container) }
        }
        compose.waitForText("Cake")
        compose.onNodeWithText("Cake").click()
        compose.waitForText("Edit")
        compose.onNodeWithText("Edit").click()
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Drag step 3")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun inOrder(text: String, vararg parts: String): Boolean =
        parts.map { text.indexOf(it) }.let { at -> at.all { it >= 0 } && at.zipWithNext().all { (a, b) -> a < b } }

    @Test
    fun aDraggedIngredientAndStepAreSavedInTheirNewPlaces() {
        openTheEditor()
        val before = file.readText()
        dragTo("Flour", compose.onNodeWithText("Add ingredient").fetchSemanticsNode().boundsInRoot.bottom - 1f)
        dragTo("step 1", compose.onNodeWithText("Add step").fetchSemanticsNode().boundsInRoot.bottom - 1f)
        compose.onNode(hasText("Save")).click()
        compose.waitUntil(5_000) { file.readText() != before }

        val saved = file.readText()
        assertTrue(saved, inOrder(saved, "Sugar", "Eggs", "Flour"))
        assertTrue(saved, inOrder(saved, "Bake it.", "Cool it.", "Mix it."))
    }
}
