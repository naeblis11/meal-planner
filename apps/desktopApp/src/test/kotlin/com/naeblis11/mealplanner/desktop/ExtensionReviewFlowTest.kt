package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.desktop.server.ExtensionImport
import com.naeblis11.mealplanner.desktop.server.jpegBytes
import com.naeblis11.mealplanner.importing.CHROME_RECIPE_WAITING
import com.naeblis11.mealplanner.importing.ImportInbox
import com.naeblis11.mealplanner.ui.LeaveGuard
import com.naeblis11.mealplanner.ui.MealPlannerApp
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * P4-R4: a recipe from the extension opens the same review as a file import, with the same duplicate rule, one at a
 * time, and never over a form with unsaved changes.
 */
class ExtensionReviewFlowTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-extension-flow").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })
    private val inbox = ImportInbox()
    private val staged = mutableListOf<File>()
    private val importer = ExtensionImport({ app.container.newImportStagingDir().also { staged += it } }, fetchImage = { jpegBytes() }, inbox = inbox, onReceived = {}, log = {})

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    private fun send(name: String) = importer.receive(
        linkedMapOf<Any?, Any?>(
            "name" to name,
            "ingredients" to listOf("2 cups flour, sifted", "Kosher salt, to taste"),
            "steps" to listOf("Boil water.", "Add flour."),
            "source_url" to "https://www.example.com/soup",
            "image_url" to "https://www.example.com/soup.jpg",
        ),
    )

    @Test
    fun aSentRecipeOpensTheReviewAndConfirmSavesItWithItsPhoto() {
        send("Extracted Soup")
        compose.showAt(1000.dp) { MealPlannerApp(app.container, imports = inbox) }
        compose.waitForText("From www.example.com")
        compose.onNodeWithText("Confirm").click()
        compose.waitForText("Imported 1 recipe(s) from www.example.com")
        val saved = runBlocking { app.container.recipes.allRecipes() }.single()
        assertEquals("Extracted Soup", saved.name)
        assertTrue(File(app.imagesDir, saved.imageFilename!!).isFile)
    }

    @Test
    fun aRecipeSentDuringAReviewWaitsItsTurn() {
        send("Recipe A")
        send("Recipe B")
        compose.showAt(1000.dp) { MealPlannerApp(app.container, imports = inbox) }
        compose.waitForText("Recipe A")
        assertEquals(1, inbox.waiting.value)
        compose.onNodeWithText("Cancel").click()
        compose.waitForText("Recipe B")
        assertEquals(0, inbox.waiting.value)
    }

    @Test
    fun aRecipeAlreadyInTheLibraryIsADuplicate() {
        File(app.recipesDir, "soup.yaml").writeText(
            "recipe_name: Extracted Soup\ningredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n",
        )
        runBlocking { app.folder.sync() }
        send("extracted soup")
        compose.showAt(1000.dp) { MealPlannerApp(app.container, imports = inbox) }
        compose.waitForText("Already in your library. Change the title to import a copy; otherwise it is skipped.")
    }

    @Test
    fun anUnsavedEditIsNeverDroppedForARecipeThatArrives() {
        File(app.recipesDir, "soup.yaml").writeText(
            "recipe_name: Soup\ningredients:\n- Stock:\n    amounts:\n    - amount: 2\n      unit: cup\nsteps:\n- step: Simmer.\n",
        )
        runBlocking { app.folder.sync() }
        val guard = LeaveGuard()
        compose.showAt(1000.dp) { MealPlannerApp(app.container, leaveGuard = guard, imports = inbox) }
        compose.waitForText("Soup")
        compose.onNodeWithText("Soup").click()
        compose.waitForText("Simmer.")
        compose.onNodeWithText("Edit").click()
        compose.waitForText("Edit recipe")
        compose.onNode(hasText("Title") and hasSetTextAction()).performTextReplacement("Soup with leeks")
        compose.waitForText("Soup with leeks")

        // A recipe arrives while the form has unsaved changes: it waits, and the form is left alone.
        send("Extracted Soup")
        compose.waitForIdle()
        assertEquals(1, inbox.waiting.value)
        compose.onNodeWithText("Soup with leeks").assertExists()
        compose.onAllNodesWithText("From www.example.com").assertCountEquals(0)

        // Asked to leave, the user keeps editing: nothing is dropped, and the recipe still waits.
        compose.onNodeWithText("Cancel").click()
        compose.waitForText(DISCARD_TITLE)
        compose.onNodeWithText("Keep editing").click()
        compose.waitForIdle()
        assertEquals(1, inbox.waiting.value)
        compose.onNodeWithText("Soup with leeks").assertExists()

        // Saved: the form lets go of the guard, and only then does the recipe open on the review.
        compose.onNodeWithText("Save").click()
        compose.waitForText("From www.example.com")
        assertEquals(0, inbox.waiting.value)
        assertTrue(File(app.recipesDir, "soup.yaml").readText().contains("Soup with leeks"))
    }

    @Test
    fun leavingAReviewThroughTheRailAsksFirstThenTheNextRecipeOpens() {
        // A review left by the rail would otherwise stay open off screen, and the inbox would never drain.
        send("Recipe A")
        send("Recipe B")
        compose.showAt(1000.dp) { MealPlannerApp(app.container, imports = inbox) }
        compose.waitForText("Recipe A")
        val first = staged.first()

        compose.tab("Calendar").click()
        compose.waitForText(LEAVE_TITLE)
        compose.onNodeWithText(LEAVE_TEXT).assertExists()
        compose.onNodeWithText("Keep reviewing").click()
        compose.waitForIdle()
        compose.onAllNodesWithText(LEAVE_TITLE).assertCountEquals(0)
        compose.onNodeWithText("Recipe A").assertExists()
        assertEquals(1, inbox.waiting.value)
        assertTrue(first.isDirectory)

        compose.tab("Calendar").click()
        compose.waitForText(LEAVE_TITLE)
        compose.onNodeWithText("Leave").click()
        compose.waitForText("Recipe B")
        assertEquals(0, inbox.waiting.value)
        // Leave is the import's Cancel: its staged photos go.
        assertFalse(first.exists())
    }

    @Test
    fun aFinishedImportLeftThroughTheRailLetsTheNextRecipeOpen() {
        // Review, 2026-10-10: only a review holds the LeaveGuard, so Calendar on the rail used to pop a finished import's
        // screen and leave its state Finished, and the inbox (which waits for Idle) never drained.
        send("Recipe A")
        compose.showAt(1000.dp) { MealPlannerApp(app.container, imports = inbox) }
        compose.waitForText("Recipe A")
        compose.onNodeWithText("Confirm").click()
        compose.waitForText("Imported 1 recipe(s) from www.example.com")
        send("Recipe B")
        compose.waitForText(CHROME_RECIPE_WAITING)

        compose.tab("Calendar").click()
        compose.waitForText("Recipe B")
        compose.waitForIdle()
        assertEquals(0, inbox.waiting.value)
        compose.onAllNodesWithText(CHROME_RECIPE_WAITING).assertCountEquals(0)
        compose.onAllNodesWithText("Imported 1 recipe(s) from www.example.com").assertCountEquals(0)
    }

    @Test
    fun aRecipeWaitingBehindAReviewIsNoticedUntilItOpens() {
        send("Recipe A")
        send("Recipe B")
        compose.showAt(1000.dp) { MealPlannerApp(app.container, imports = inbox) }
        compose.waitForText("Recipe A")
        compose.onNodeWithText(CHROME_RECIPE_WAITING).assertExists()
        compose.onNodeWithText("Cancel").click()
        compose.waitForText("Recipe B")
        compose.waitForIdle()
        compose.onAllNodesWithText(CHROME_RECIPE_WAITING).assertCountEquals(0)
    }

    @Test
    fun aRecipeWaitingBehindAnEditIsNoticed() {
        File(app.recipesDir, "soup.yaml").writeText(
            "recipe_name: Soup\ningredients:\n- Stock:\n    amounts:\n    - amount: 2\n      unit: cup\nsteps:\n- step: Simmer.\n",
        )
        runBlocking { app.folder.sync() }
        val guard = LeaveGuard()
        compose.showAt(1000.dp) { MealPlannerApp(app.container, leaveGuard = guard, imports = inbox) }
        compose.waitForText("Soup")
        compose.onNodeWithText("Soup").click()
        compose.waitForText("Simmer.")
        compose.onNodeWithText("Edit").click()
        compose.waitForText("Edit recipe")
        compose.onAllNodesWithText(CHROME_RECIPE_WAITING).assertCountEquals(0)
        compose.onNode(hasText("Title") and hasSetTextAction()).performTextReplacement("Soup with leeks")
        compose.waitForText("Soup with leeks")
        send("Extracted Soup")
        compose.waitForText(CHROME_RECIPE_WAITING)
        compose.onNodeWithText("Save").click()
        compose.waitForText("From www.example.com")
        compose.onAllNodesWithText(CHROME_RECIPE_WAITING).assertCountEquals(0)
    }

    @Test
    fun aRecipeWaitingWhenTheAppQuitsNeverOpens() {
        // Quit leaves the review (Leave is its Cancel), which would otherwise start the next recipe mid-shutdown.
        send("Recipe A")
        send("Recipe B")
        val guard = LeaveGuard()
        val shell = WindowShell(startMinimized = false, traySupported = true, notice = TrayNotice(MapSettings()))
        val quit = guardedQuit(shell, guard) { shell.quit() }
        compose.showAt(1000.dp) { MealPlannerApp(app.container, leaveGuard = guard, imports = inbox, quitting = { shell.quitting }) }
        compose.waitForText("Recipe A")
        compose.runOnIdle { quit() }
        compose.waitForText(LEAVE_TITLE)
        compose.onNodeWithText("Leave").click()
        compose.waitUntil(5_000) { shell.quitting && compose.onAllNodesWithText("Recipe A").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        assertEquals(1, inbox.waiting.value)
        compose.onAllNodesWithText("Recipe B").assertCountEquals(0)
    }

    private companion object {
        const val DISCARD_TITLE = "Discard changes?"
        const val LEAVE_TITLE = "Leave this import?"
        const val LEAVE_TEXT = "The recipes you haven't added will be dropped."
    }
}
