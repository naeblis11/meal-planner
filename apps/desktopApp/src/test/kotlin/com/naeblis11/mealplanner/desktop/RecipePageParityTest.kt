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
    fun starsInTheListRateAndTheCurrentOneClears() {
        compose.showAt(1000.dp) { MealPlannerApp(app.container) }
        compose.waitForText("Soup")
        compose.onNodeWithContentDescription("Rate Soup 4 stars").click()
        compose.waitUntil(5_000) { soupFile.readText().contains("rating: 4") }

        waitForDescription("Clear the rating of Soup")
        compose.onNodeWithContentDescription("Clear the rating of Soup").click()
        compose.waitUntil(5_000) { soupFile.readText().contains("rating: None") }
        waitForDescription("Rate Soup 4 stars")
    }

    @Test
    fun whileAnEditHoldsThePaneTheListsStarsOnlyShow() {
        // P5-PF4: a rating saved under an open edit of the same recipe would stop its Save.
        runBlocking { app.container.recipes.setRating(id("Stew"), 3) }
        openSoup(1000.dp)
        compose.onNodeWithText("Edit").click()
        compose.waitForText("Edit recipe")
        compose.onAllNodesWithContentDescription("Rate Stew 2 stars").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Rated 3 of 5").assertCountEquals(1)
        waitForEditorFields()
    }

    @Test
    fun starsInThePhonesListRateToo() {
        compose.showAt(400.dp) { MealPlannerApp(app.container) }
        compose.waitForText("Stew")
        compose.onNodeWithContentDescription("Rate Stew 2 stars").click()
        compose.waitUntil(5_000) { File(app.recipesDir, "stew.yaml").readText().contains("rating: 2") }
        waitForDescription("Clear the rating of Stew")
    }

    @Test
    fun theListsStarsFitAPhoneOf320dp() {
        compose.showAt(320.dp) { MealPlannerApp(app.container) }
        compose.waitForText("Stew")
        // Five stars of 48 dp each on their own line under the name, inside the list's padding (the search field
        // spans it), none squeezed below the touch size.
        val search = compose.onNodeWithText("Search recipes").getBoundsInRoot()
        for (n in 1..5) {
            val star = compose.onNodeWithContentDescription("Rate Stew $n star${if (n == 1) "" else "s"}")
            star.assertWidthIsAtLeast(48.dp)
            star.assertHeightIsAtLeast(48.dp)
            val bounds = star.getBoundsInRoot()
            assertTrue("$bounds inside $search", bounds.left >= search.left && bounds.right <= search.right)
        }
        compose.onNodeWithContentDescription("Rate Stew 5 stars").click()
        compose.waitUntil(5_000) { File(app.recipesDir, "stew.yaml").readText().contains("rating: 5") }
        waitForDescription("Clear the rating of Stew")
    }

    @Test
    fun theStarLineStartsClearOfTheNameAndEndsTheRow() {
        compose.showAt(400.dp) { MealPlannerApp(app.container) }
        compose.waitForText("Stew")
        val row = compose.onNode(hasText("Stew")).getBoundsInRoot()
        // 10 dp above the 56 dp photo-and-name band, 4 dp under it, and no padding below the 48 dp stars: a thumb
        // just under the name opens the recipe.
        for (n in 1..5) {
            val node = compose.onNodeWithContentDescription("Rate Stew $n star${if (n == 1) "" else "s"}")
            node.assertWidthIsAtLeast(48.dp)
            node.assertHeightIsAtLeast(48.dp)
            val star = node.getBoundsInRoot()
            assertEquals(70f, (star.top - row.top).value, 0.5f)
            assertEquals(row.bottom.value, star.bottom.value, 0.5f)
        }
        assertEquals(ROW_WITH_STARS, (row.bottom - row.top).value, 0.5f)
    }

    @Test
    fun aListRowKeepsItsHeightWhileAnEditHoldsThePane() {
        openSoup(1000.dp)
        val before = compose.onNode(hasText("Stew")).getBoundsInRoot()
        compose.onNodeWithText("Edit").click()
        compose.waitForText("Edit recipe")
        val locked = compose.onNode(hasText("Stew")).getBoundsInRoot()
        // The 48 dp star line is there either way: rating stars before, showing stars while the edit is open.
        assertEquals(ROW_WITH_STARS, (before.bottom - before.top).value, 0.5f)
        assertEquals(ROW_WITH_STARS, (locked.bottom - locked.top).value, 0.5f)
        waitForEditorFields()
    }

    @Test
    fun theStarsStayPutAndKeepTheirSizeWhileAnEditHoldsThePane() {
        runBlocking { app.container.recipes.setRating(id("Stew"), 3) }
        openSoup(1000.dp)
        val one = compose.onNodeWithContentDescription("Rate Stew 1 star").getBoundsInRoot()
        val rowBefore = compose.onNode(hasText("Stew")).getBoundsInRoot()
        compose.onNodeWithText("Edit").click()
        compose.waitForText("Edit recipe")
        val rowLocked = compose.onNode(hasText("Stew")).getBoundsInRoot()
        // Showing stars sit in the same five 48 dp boxes the rating stars had, so the line keeps its place and size.
        // The row merges its children, so the stars' own node is in the unmerged tree.
        val shown = compose.onNodeWithContentDescription("Rated 3 of 5", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(one.left.value, shown.left.value, 0.5f)
        assertEquals(5 * 48f, (shown.right - shown.left).value, 0.5f)
        assertEquals((one.top - rowBefore.top).value, (shown.top - rowLocked.top).value, 0.5f)
        waitForEditorFields()
    }

    // The editor has loaded the recipe into its fields: the test ends on a settled screen, with no load of the edit
    // still querying the database when it closes.
    private fun waitForEditorFields() =
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasSetTextAction() and hasText("Title") and hasText("Soup")).fetchSemanticsNodes().isNotEmpty()
        }

    private companion object {
        // 10 dp above the photo, the 56 dp photo-and-name band, 4 dp, and the 48 dp star line.
        const val ROW_WITH_STARS = 10f + 56f + 4f + 48f
    }

    @Test
    fun aRatingFromTheListThatCantBeSavedSaysWhy() {
        compose.showAt(1000.dp) { MealPlannerApp(app.container) }
        compose.waitForText("Soup")
        assertTrue(soupFile.delete())
        compose.onNodeWithContentDescription("Rate Soup 4 stars").click()
        compose.waitForText(missingFileMessage("soup.yaml"))
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
