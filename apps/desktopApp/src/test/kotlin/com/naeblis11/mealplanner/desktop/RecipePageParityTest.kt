package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.data.missingFileMessage
import com.naeblis11.mealplanner.recipes.ASSIGN_SERVINGS_LABEL
import com.naeblis11.mealplanner.recipes.ASSIGN_TO_CALENDAR
import com.naeblis11.mealplanner.recipes.planningNote
import com.naeblis11.mealplanner.recipes.plannedMessage
import com.naeblis11.mealplanner.ui.MealPlannerApp
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** P5-R7: the server's recipe page and list: Assign to calendar at the scaled servings, the category in place, stars that rate. */
class RecipePageParityTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-page-parity").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })
    private val soupFile: File get() = File(app.recipesDir, "soup.yaml")

    @Before
    fun setUp() {
        soupFile.writeText(
            "recipe_name: Soup\nyields:\n- servings: 4\ningredients:\n- Stock:\n    amounts:\n    - amount: 2\n      unit: cup\nsteps:\n- step: Simmer.\n",
        )
        File(app.recipesDir, "stew.yaml").writeText(
            "recipe_name: Stew\ningredients:\n- Beef:\n    amounts:\n    - amount: 1\n      unit: lb\nsteps:\n- step: Braise.\n",
        )
        runBlocking { app.folder.sync() }
    }

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    private fun id(name: String) = runBlocking { app.container.recipes.allRecipes().single { it.name == name }.id }

    private fun planned(day: LocalDate, slot: String) = runBlocking { app.container.plans.assignment(day, slot) }

    // The list shows the rating once it is indexed; a test ends there, not at the file write, so no list query is
    // still running when the database closes.
    private fun waitForDescription(description: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }

    private fun openSoup(width: Dp) {
        compose.showAt(width) { MealPlannerApp(app.container) }
        compose.waitForText("Soup")
        compose.onNodeWithText("Soup").click()
        compose.waitForText("Simmer.")
    }

    @Test
    fun aScaledRecipeIsPlannedAtItsScaledServings() {
        openSoup(1000.dp)
        compose.onNode(hasText("Servings") and hasSetTextAction()).performTextReplacement("8")
        compose.onNodeWithText("Scale").click()
        compose.waitForText(planningNote("8", "servings"))

        compose.onNodeWithText(ASSIGN_TO_CALENDAR).click()
        compose.onNode(hasText(ASSIGN_SERVINGS_LABEL) and hasSetTextAction()).assertTextContains("8")
        compose.onNodeWithContentDescription("Next day").click()
        compose.onNodeWithText("Lunch").click()
        compose.onNodeWithText("Assign").click()

        val tomorrow = LocalDate.now().plusDays(1)
        compose.waitForText(plannedMessage("Lunch", tomorrow, null))
        assertEquals("8", planned(tomorrow, "Lunch")!!.servings)
    }

    @Test
    fun anUnscaledRecipeIsPlannedAsWritten() {
        openSoup(400.dp)
        compose.onAllNodesWithText(planningNote("4", "servings")).assertCountEquals(0)
        compose.onNodeWithText(ASSIGN_TO_CALENDAR).click()
        compose.onNodeWithText("Assign").click()

        val today = LocalDate.now()
        compose.waitForText(plannedMessage("Dinner", today, null))
        assertEquals("Soup", planned(today, "Dinner")!!.recipeName)
        assertNull(planned(today, "Dinner")!!.servings)
    }

    @Test
    fun planningOverAnotherMealSaysWhichItReplaced() {
        val today = LocalDate.now()
        runBlocking { app.container.plans.assign(today, "Dinner", id("Stew"), null) }
        openSoup(1000.dp)
        compose.onNodeWithText(ASSIGN_TO_CALENDAR).click()
        compose.onNodeWithText("Assign").click()
        compose.waitForText(plannedMessage("Dinner", today, "Stew"))
        assertEquals("Soup", planned(today, "Dinner")!!.recipeName)
    }

    @Test
    fun theCategoryIsChangedInPlace() {
        openSoup(400.dp)
        compose.onNodeWithText("More").click()
        compose.onNodeWithText("Category").click()
        compose.onNodeWithText("Soups & Stews").click()
        compose.onNode(hasText("Subcategory") and hasSetTextAction()).performTextReplacement("Beef")
        compose.onNodeWithText("Save category").click()

        compose.waitForText("Category updated.")
        compose.waitForText("Soups & Stews \u00b7 Beef")
        val text = soupFile.readText()
        assertTrue(text, text.contains("category: Soups & Stews"))
        assertTrue(text, text.contains("subcategory: Beef"))
    }

    @Test
    fun theListsStarsOnlyShowTheRating() {
        // Owner, 2026-10-09: the list reports a rating; it is changed on the recipe page.
        runBlocking { app.container.recipes.setRating(id("Stew"), 3) }
        compose.showAt(1000.dp) { MealPlannerApp(app.container) }
        compose.waitForText("Stew")
        waitForDescription("Rated 3 of 5")
        compose.onAllNodesWithContentDescription("Rate Stew 4 stars").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Rate Soup 4 stars").assertCountEquals(0)
        // An unrated recipe shows no stars at all.
        compose.onAllNodesWithContentDescription("Not rated").assertCountEquals(0)
    }

    @Test
    fun aListRowIsOneShortLineOnThePhoneAndThePc() {
        runBlocking { app.container.recipes.setRating(id("Stew"), 3) }
        for (width in listOf(400.dp, 1000.dp)) {
            compose.showAt(width) { MealPlannerApp(app.container) }
            compose.waitForText("Stew")
            val row = compose.onNode(hasText("Stew")).getBoundsInRoot()
            // The row's 48 dp minimum: the 36 dp photo, the name and the small stars all fit inside it.
            assertEquals("at $width", ROW_HEIGHT, (row.bottom - row.top).value, 0.5f)
        }
    }

    @Test
    fun aListRowKeepsItsHeightWhileAnEditHoldsThePane() {
        runBlocking { app.container.recipes.setRating(id("Stew"), 3) }
        openSoup(1000.dp)
        val before = compose.onNode(hasText("Stew")).getBoundsInRoot()
        compose.onNodeWithText("Edit").click()
        compose.waitForText("Edit recipe")
        val locked = compose.onNode(hasText("Stew")).getBoundsInRoot()
        assertEquals(ROW_HEIGHT, (before.bottom - before.top).value, 0.5f)
        assertEquals(ROW_HEIGHT, (locked.bottom - locked.top).value, 0.5f)
        waitForEditorFields()
    }

    // The editor has loaded the recipe into its fields: the test ends on a settled screen, with no load of the edit
    // still querying the database when it closes.
    private fun waitForEditorFields() =
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasSetTextAction() and hasText("Title") and hasText("Soup")).fetchSemanticsNodes().isNotEmpty()
        }

    private companion object {
        // A list row: its 48 dp minimum (owner, 2026-10-09: compact rows, stars only show the rating).
        const val ROW_HEIGHT = 48f
    }

    @Test
    fun aRatingOnTheRecipePageThatCantBeSavedSaysWhy() {
        openSoup(400.dp)
        assertTrue(soupFile.delete())
        compose.onNodeWithContentDescription("Rate 4 stars").click()
        compose.waitForText(missingFileMessage("soup.yaml"))
    }

    @Test
    fun aCategoryThatCantBeSavedSaysWhy() {
        openSoup(400.dp)
        assertTrue(soupFile.delete())
        compose.onNodeWithText("More").click()
        compose.onNodeWithText("Category").click()
        compose.onNodeWithText("Soups & Stews").click()
        compose.onNodeWithText("Save category").click()
        compose.waitForText(missingFileMessage("soup.yaml"))
    }
}
