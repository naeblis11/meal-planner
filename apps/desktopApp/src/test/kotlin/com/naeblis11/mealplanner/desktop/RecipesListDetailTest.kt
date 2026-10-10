package com.naeblis11.mealplanner.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.recipes.EDITING_ELSEWHERE
import com.naeblis11.mealplanner.ui.CHOOSE_A_RECIPE
import com.naeblis11.mealplanner.ui.LIST_PANE_WIDTH
import com.naeblis11.mealplanner.ui.MealPlannerApp
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** P3-R4: wide, the recipe opens (and is edited) beside the list; narrow, it is its own page as on the phone. */
class RecipesListDetailTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-list-detail").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })

    private fun recipe(name: String, step: String) =
        "recipe_name: $name\ningredients:\n- Stock:\n    amounts:\n    - amount: 2\n      unit: cup\nsteps:\n- step: $step\n"

    @Before
    fun setUp() {
        File(app.recipesDir, "soup.yaml").writeText(recipe("Soup", "Simmer."))
        File(app.recipesDir, "stew.yaml").writeText(recipe("Stew", "Braise."))
        runBlocking { app.folder.sync() }
    }

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    @Test
    fun aWideWindowOpensTheRecipeBesideTheList() {
        compose.showAt(1000.dp) { MealPlannerApp(app.container) }
        compose.waitForText(CHOOSE_A_RECIPE)
        compose.waitForText("Soup")
        compose.onNodeWithText("Soup").click()
        compose.waitForText("Simmer.")
        compose.onNodeWithText("Search recipes").assertIsDisplayed()
        compose.onNode(hasText("Soup") and isSelected()).assertExists()
        assertTrue(compose.onNodeWithText("Simmer.").getBoundsInRoot().left > LIST_PANE_WIDTH)

        compose.onNodeWithText("Stew").click()
        compose.waitForText("Braise.")
        compose.onAllNodesWithText("Simmer.").assertCountEquals(0)
    }

    @Test
    fun editingInThePaneHoldsTheListUntilSavedOrCancelled() {
        compose.showAt(1000.dp) { MealPlannerApp(app.container) }
        compose.waitForText("Soup")
        compose.onNodeWithText("Soup").click()
        compose.waitForText("Simmer.")
        compose.onNodeWithText("Edit").click()
        compose.waitForText("Edit recipe")
        compose.waitForText(EDITING_ELSEWHERE)
        compose.onNodeWithText("Search recipes").assertIsDisplayed()
        compose.onNode(hasText("Stew")).assertIsNotEnabled()
        compose.onAllNodesWithText("New recipe").assertCountEquals(0)

        compose.onNodeWithText("Cancel").click()
        compose.waitForText("Simmer.")
        compose.onAllNodesWithText("Edit recipe").assertCountEquals(0)
        compose.onAllNodesWithText(EDITING_ELSEWHERE).assertCountEquals(0)
        compose.onNode(hasText("Stew")).assertIsEnabled()
    }

    @Test
    fun aNarrowWindowOpensTheRecipeAsItsOwnPage() {
        compose.showAt(400.dp) { MealPlannerApp(app.container) }
        compose.waitForText("Soup")
        compose.onNodeWithText("Soup").click()
        compose.waitForText("Simmer.")
        compose.onAllNodesWithText("Search recipes").assertCountEquals(0)
        compose.onAllNodesWithText(CHOOSE_A_RECIPE).assertCountEquals(0)
    }

    @Test
    fun anUnsavedEditInThePaneSurvivesTheWindowNarrowingAndWideningAgain() {
        var width by mutableStateOf(1000.dp)
        compose.setTestContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(width, 760.dp)) { MealPlannerTheme { MealPlannerApp(app.container) } }
            }
        }
        compose.waitForText("Soup")
        compose.onNodeWithText("Soup").click()
        compose.waitForText("Simmer.")
        compose.onNodeWithText("Edit").click()
        compose.waitForText("Edit recipe")
        compose.onNode(hasText("Title") and hasSetTextAction()).performTextReplacement("Soup with leeks")
        compose.waitForText("Soup with leeks")

        // Snapped to half a small screen: the form fills the window, unsaved change and all.
        width = 400.dp
        compose.waitForIdle()
        compose.onNodeWithText("Edit recipe").assertIsDisplayed()
        compose.onNodeWithText("Soup with leeks").assertExists()
        compose.onAllNodesWithText("Search recipes").assertCountEquals(0)

        // And back: the list returns beside the same form.
        width = 1000.dp
        compose.waitForIdle()
        compose.onNodeWithText("Edit recipe").assertIsDisplayed()
        compose.onNodeWithText("Soup with leeks").assertExists()
        compose.onNodeWithText("Search recipes").assertIsDisplayed()
    }

    @Test
    fun anotherSectionAndTheOpenRecipeSurviveTheWindowNarrowingAndWideningAgain() {
        var width by mutableStateOf(1000.dp)
        compose.setTestContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(width, 760.dp)) { MealPlannerTheme { MealPlannerApp(app.container) } }
            }
        }
        compose.waitForText("Soup")
        compose.onNodeWithText("Soup").click()
        compose.waitForText("Simmer.")
        compose.tab("Pantry").click()
        compose.waitForText(PANTRY_INTRO)

        // Narrow: still the Pantry, now under the phone's tabs.
        width = 400.dp
        compose.waitForIdle()
        compose.onNodeWithText(PANTRY_INTRO).assertIsDisplayed()
        compose.tab("Pantry").assertIsSelected()

        // Wide again: the Pantry on the rail, and Recipes still has Soup open beside the list.
        width = 1000.dp
        compose.waitForIdle()
        compose.onNodeWithText(PANTRY_INTRO).assertIsDisplayed()
        compose.tab("Pantry").assertIsSelected()
        compose.tab("Recipes").click()
        compose.waitForText("Simmer.")
        compose.onNode(hasText("Soup") and isSelected()).assertExists()
        compose.onNodeWithText("Search recipes").assertIsDisplayed()
    }

    @Test
    fun aRailTapAsksBeforeDroppingAnUnsavedEdit() {
        compose.showAt(1000.dp) { MealPlannerApp(app.container) }
        compose.waitForText("Soup")
        compose.onNodeWithText("Soup").click()
        compose.waitForText("Simmer.")
        compose.onNodeWithText("Edit").click()
        compose.waitForText("Edit recipe")
        compose.onNode(hasText("Title") and hasSetTextAction()).performTextReplacement("Soup with leeks")
        compose.waitForText("Soup with leeks")

        // Keep editing: nothing moves, and the change is still there.
        compose.tab("Pantry").click()
        compose.waitForText(DISCARD_TITLE)
        compose.onNodeWithText("Keep editing").click()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(DISCARD_TITLE).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Edit recipe").assertIsDisplayed()
        compose.onNodeWithText("Soup with leeks").assertExists()
        compose.tab("Recipes").assertIsSelected()
        compose.onAllNodesWithText(PANTRY_INTRO).assertCountEquals(0)

        // Discard: the rail's choice opens, and the form (with its change) is gone from the pane.
        compose.tab("Pantry").click()
        compose.waitForText(DISCARD_TITLE)
        compose.onNodeWithText("Discard").click()
        compose.waitForText(PANTRY_INTRO)
        compose.tab("Pantry").assertIsSelected()
        compose.tab("Recipes").click()
        compose.waitForText("Simmer.")
        compose.onAllNodesWithText("Edit recipe").assertCountEquals(0)
        compose.onAllNodesWithText("Soup with leeks").assertCountEquals(0)
    }

    @Test
    fun importAndNeedsAttentionWaitWhileAFormHoldsThePane() {
        File(app.recipesDir, "bad.yaml").writeText("recipe_name: [unclosed\n")
        runBlocking { app.folder.sync() }
        compose.showAt(1000.dp) { MealPlannerApp(app.container) }
        compose.waitForText("Soup")
        compose.waitForText(ATTENTION_BANNER)
        compose.onNodeWithText("Import").assertIsEnabled()
        compose.onNodeWithText(ATTENTION_BANNER).assertIsEnabled()
        compose.onNodeWithText("Soup").click()
        compose.waitForText("Simmer.")
        compose.onNodeWithText("Edit").click()
        compose.waitForText("Edit recipe")

        // Either would open its own page over the list and leave the form behind.
        compose.onNodeWithText("Import").assertIsNotEnabled()
        compose.onNodeWithText(ATTENTION_BANNER).assertIsNotEnabled()

        compose.onNodeWithText("Cancel").click()
        compose.waitForText("Simmer.")
        compose.onNodeWithText("Import").assertIsEnabled()
        compose.onNodeWithText(ATTENTION_BANNER).assertIsEnabled()
    }

    @Test
    fun aTabTapOnANarrowWindowAsksBeforeDroppingAnUnsavedEdit() {
        var width by mutableStateOf(1000.dp)
        compose.setTestContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(width, 760.dp)) { MealPlannerTheme { MealPlannerApp(app.container) } }
            }
        }
        compose.waitForText("Soup")
        compose.onNodeWithText("Soup").click()
        compose.waitForText("Simmer.")
        compose.onNodeWithText("Edit").click()
        compose.waitForText("Edit recipe")
        compose.onNode(hasText("Title") and hasSetTextAction()).performTextReplacement("Soup with leeks")
        compose.waitForText("Soup with leeks")

        // Narrowed: the form fills the window above the phone's tabs, which ask too.
        width = 400.dp
        compose.waitForIdle()
        compose.onNodeWithText("Edit recipe").assertIsDisplayed()
        compose.tab("Pantry").click()
        compose.waitForText(DISCARD_TITLE)
        compose.onNodeWithText("Keep editing").click()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(DISCARD_TITLE).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Soup with leeks").assertExists()
        compose.tab("Recipes").assertIsSelected()
        compose.onAllNodesWithText(PANTRY_INTRO).assertCountEquals(0)

        compose.tab("Pantry").click()
        compose.waitForText(DISCARD_TITLE)
        compose.onNodeWithText("Discard").click()
        compose.waitForText(PANTRY_INTRO)
        compose.tab("Pantry").assertIsSelected()
    }

    private companion object {
        const val ATTENTION_BANNER = "1 recipe file needs attention"
        const val PANTRY_INTRO = "What you've already got on hand."
        const val DISCARD_TITLE = "Discard changes?"
    }
}
