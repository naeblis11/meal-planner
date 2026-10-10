package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.recipes.IngredientView
import com.naeblis11.mealplanner.recipes.RecipeDetailScreen
import com.naeblis11.mealplanner.recipes.RecipeView
import com.naeblis11.mealplanner.ui.MealPlannerApp
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** P5-R7: each ingredient says whether the pantry covers it (the shopping list's own rule), and one tap adds it. */
class RecipePantryMarksTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-pantry-marks").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })

    @Before
    fun setUp() {
        File(app.recipesDir, "soup.yaml").writeText(
            "recipe_name: Soup\ningredients:\n- Stock:\n    amounts:\n    - amount: 2\n      unit: cup\n" +
                "- Kosher salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Simmer.\n",
        )
        runBlocking {
            app.folder.sync()
            // "salt" covers "Kosher salt", as on the shopping list; Stock is known but marked out.
            app.container.pantry.add("salt")
            app.container.pantry.add("Stock")
            app.container.pantry.setActive(app.container.database.pantryDao().byName("Stock")!!.id, false)
        }
    }

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    private fun openSoup(width: Dp) {
        compose.showAt(width) { MealPlannerApp(app.container) }
        compose.waitForText("Soup")
        compose.onNodeWithText("Soup").click()
        compose.waitForText("Simmer.")
    }

    @Test
    fun whatThePantryCoversIsMarkedOnAWideWindow() {
        openSoup(1000.dp)
        compose.onNodeWithContentDescription("Kosher salt is in your pantry").assertExists()
        compose.onAllNodesWithContentDescription("Add Kosher salt to your pantry").assertCountEquals(0)
        compose.onNodeWithContentDescription("Add Stock to your pantry").assertExists()
    }

    @Test
    fun plusPantryIsEasyToTap() {
        openSoup(400.dp)
        compose.onNodeWithContentDescription("Add Stock to your pantry").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun anIngredientWithNoNameHasNoPlusPantry() {
        fun line(name: String) = IngredientView(section = null, name = name, amount = "1", unit = "cup", notes = emptyList(), substitutions = emptyList())
        val view = RecipeView(
            id = 1, name = "Soup", category = null, subcategory = null, imageFilename = null, rating = null, author = null,
            sourceUrl = null, book = null, oven = null, notes = emptyList(), servings = null, servingsUnit = null, canScale = false,
            ingredients = listOf(line("Stock"), line(" ")), steps = emptyList(),
        )
        compose.showAt(400.dp) {
            RecipeDetailScreen(
                view = view, photo = null, message = null, loadError = null, onMessageShown = {}, onBack = {}, onEdit = {},
                onScale = {}, onRate = {}, onDelete = {}, onTakePhoto = null, onChoosePhoto = {}, onRemovePhoto = {},
                onAddToPantry = {},
            )
        }
        compose.onAllNodesWithText("+ Pantry").assertCountEquals(1)
        compose.onNodeWithContentDescription("Add Stock to your pantry").assertExists()
    }

    @Test
    fun oneTapPutsAnItemBackAndMarksItOnThePhone() {
        openSoup(400.dp)
        compose.onNodeWithContentDescription("Add Stock to your pantry").click()
        compose.waitForText("Added 'Stock' back to your pantry.")
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Stock is in your pantry").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(runBlocking { app.container.database.pantryDao().byName("Stock")!!.active })
    }

    @Test
    fun aNewIngredientIsAddedStampedToday() {
        runBlocking { app.container.pantry.delete(app.container.database.pantryDao().byName("Stock")!!.id) }
        openSoup(400.dp)
        compose.onNodeWithContentDescription("Add Stock to your pantry").click()
        compose.waitForText("Added 'Stock' to your pantry.")
        assertEquals(LocalDate.now().toString(), runBlocking { app.container.database.pantryDao().byName("Stock")!!.addedOn })
    }
}
