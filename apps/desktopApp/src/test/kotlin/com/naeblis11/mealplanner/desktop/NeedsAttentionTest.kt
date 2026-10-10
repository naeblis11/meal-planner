package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.naeblis11.mealplanner.data.AppMetaEntity
import com.naeblis11.mealplanner.folder.RecipeFileProblem
import com.naeblis11.mealplanner.folder.RecipeFolder
import com.naeblis11.mealplanner.folder.folderMovedMessage
import com.naeblis11.mealplanner.folder.missingFilesMessage
import com.naeblis11.mealplanner.folder.pointBackInstructions
import com.naeblis11.mealplanner.recipes.FILES_MISSING
import com.naeblis11.mealplanner.recipes.FOLDER_MOVED
import com.naeblis11.mealplanner.recipes.NeedsAttentionScreen
import com.naeblis11.mealplanner.recipes.POINT_BACK
import com.naeblis11.mealplanner.recipes.REMOVE_IT
import com.naeblis11.mealplanner.recipes.REMOVE_THEM
import com.naeblis11.mealplanner.recipes.USE_NEW_FOLDER
import com.naeblis11.mealplanner.recipes.removeMissingQuestion
import com.naeblis11.mealplanner.ui.MealPlannerApp
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.prefs.Preferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private const val FOLDER_BANNER = "The recipe folder is missing or can't be read"

class NeedsAttentionTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-attention").toFile()
    private val prefsNode = "com/naeblis11/mealplanner/test-${System.nanoTime()}"
    private val app = DesktopApp(dir, prefsNode)

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
        Preferences.userRoot().node(prefsNode).removeNode()
    }

    private fun recipe(name: String) =
        "recipe_uuid: dupe-1\nrecipe_name: $name\ningredients:\n- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\nsteps:\n- step: Stir.\n"

    private fun waitForText(text: String, millis: Long = 5_000) =
        compose.waitUntil(millis) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aDuplicateIdIsListedAndAssignNewIdKeepsBothRecipes() {
        File(app.recipesDir, "a.yaml").writeText(recipe("Recipe A"))
        File(app.recipesDir, "b.yaml").writeText(recipe("Recipe B"))
        runBlocking { app.folder.sync() }
        compose.setTestContent { MealPlannerTheme { MealPlannerApp(app.container) } }

        waitForText("1 recipe file needs attention")
        compose.onNodeWithText("Open recipe folder").assertExists()
        compose.onNodeWithText("1 recipe file needs attention").click()
        waitForText("b.yaml")
        compose.onNodeWithText("Assign new ID").click()
        waitForText("Nothing needs attention.", 10_000)

        assertEquals(listOf("Recipe A", "Recipe B"), runBlocking { app.container.recipes.allRecipes() }.map { it.name })
        assertFalse(File(app.recipesDir, "b.yaml").readText().contains("dupe-1"))
    }

    @Test
    fun aFolderThatCantBeOpenedSaysSoOnTheRecipesScreen() {
        // A plain file where the folder should be: the sync lists it, and openFolder() returns false (Explorer never opens).
        app.recipesDir.deleteRecursively()
        app.recipesDir.writeText("not a folder")
        runBlocking { runCatching { app.folder.sync() } }
        compose.setTestContent { MealPlannerTheme { MealPlannerApp(app.container) } }

        waitForText(FOLDER_BANNER)
        compose.onNodeWithText("Open recipe folder").click()
        waitForText("Couldn't open the recipe folder.")
    }

    @Test
    fun aMissingFolderIsSaidPlainlyNotCountedAsARecipeFile() {
        File(app.recipesDir, "a.yaml").writeText(recipe("Recipe A"))
        runBlocking { app.folder.sync() }
        assertTrue(app.recipesDir.deleteRecursively())
        runBlocking { runCatching { app.folder.sync() } }
        compose.setTestContent { MealPlannerTheme { MealPlannerApp(app.container) } }

        waitForText(FOLDER_BANNER)
        assertEquals(0, compose.onAllNodesWithText("1 recipe file needs attention").fetchSemanticsNodes().size)
        compose.onNodeWithText(FOLDER_BANNER).click()
        waitForText("Recipe folder")
        compose.onNodeWithText("The recipe folder is missing: ${app.recipesDir.path}. Meal Planner will pick it up again when it is back.").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Fix these in the recipe files", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun aFolderThatCantBeOpenedSaysSoOnTheNeedsAttentionScreen() {
        val problems = listOf(RecipeFileProblem("b.yaml", RecipeFileProblem.Kind.UNREADABLE, "Not valid YAML."))
        compose.setTestContent {
            MealPlannerTheme {
                NeedsAttentionScreen(
                    problems = problems,
                    message = null,
                    onMessageShown = {},
                    onBack = {},
                    onAssignNewId = {},
                    onOpenFolder = { false },
                )
            }
        }
        compose.onNodeWithText("Open recipe folder").click()
        waitForText("Couldn't open the recipe folder.")
    }

    @Test
    fun theBannerIsAButton() {
        File(app.recipesDir, "a.yaml").writeText(recipe("Recipe A"))
        File(app.recipesDir, "b.yaml").writeText(recipe("Recipe B"))
        runBlocking { app.folder.sync() }
        compose.setTestContent { MealPlannerTheme { MealPlannerApp(app.container) } }
        waitForText("1 recipe file needs attention")
        compose.onNode(
            hasText("1 recipe file needs attention") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
        ).assertExists()
    }

    @Test
    fun missingFilesAreHeldUntilTheUserRemovesThem() {
        File(app.recipesDir, "a.yaml").writeText(recipe("Recipe A").replace("dupe-1", "u-a"))
        File(app.recipesDir, "b.yaml").writeText(recipe("Recipe B").replace("dupe-1", "u-b"))
        runBlocking {
            app.folder.sync()
            val a = app.container.recipes.allRecipes().single { it.name == "Recipe A" }.id
            app.container.plans.assign(LocalDate.of(2026, 10, 5), "Dinner", a, null)
        }
        // Moved out in Explorer: the folder is there, its files aren't.
        assertTrue(File(app.recipesDir, "a.yaml").delete())
        assertTrue(File(app.recipesDir, "b.yaml").delete())
        runBlocking { app.folder.sync() }
        compose.setTestContent { MealPlannerTheme { MealPlannerApp(app.container) } }

        // Said as missing files, not as a missing folder.
        waitForText(FILES_MISSING)
        assertEquals(0, compose.onAllNodesWithText(FOLDER_BANNER).fetchSemanticsNodes().size)
        compose.onNodeWithText(FILES_MISSING).click()
        waitForText(missingFilesMessage(2))
        assertEquals(listOf("Recipe A", "Recipe B"), runBlocking { app.container.recipes.allRecipes() }.map { it.name })

        // Asked first, with what goes: Keep them removes nothing.
        val question = "Remove 2 recipes from the app? 1 planned meal uses them and will be removed too. " +
            "Their files are already gone from the folder."
        assertEquals(question, removeMissingQuestion(2, 1))
        compose.onNodeWithText(REMOVE_THEM).click()
        waitForText(question)
        compose.onNodeWithText("Keep them").click()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(question).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf("Recipe A", "Recipe B"), runBlocking { app.container.recipes.allRecipes() }.map { it.name })
        assertEquals("Recipe A", runBlocking { app.container.database.mealPlanDao().assignment("2026-10-05", "Dinner") }!!.recipeName)

        // Remove: they go, and their planned meal with them.
        compose.onNodeWithText(REMOVE_THEM).click()
        waitForText(question)
        compose.onNodeWithText("Remove").click()
        waitForText("Nothing needs attention.", 10_000)
        assertEquals(emptyList<String>(), runBlocking { app.container.recipes.allRecipes() }.map { it.name })
        assertNull(runBlocking { app.container.database.mealPlanDao().assignment("2026-10-05", "Dinner") })
    }

    @Test
    fun aFolderOtherThanTheDatabasesRemovesNothingUntilTheUserChoosesIt() {
        // P7-R10c: the database was made from another recipe folder; one of its files isn't in this one.
        File(app.recipesDir, "a.yaml").writeText(recipe("Recipe A").replace("dupe-1", "u-a"))
        File(app.recipesDir, "b.yaml").writeText(recipe("Recipe B").replace("dupe-1", "u-b"))
        val old = "C:\\Users\\someone\\OneDrive\\Documents\\Meal Planner\\recipes"
        runBlocking {
            app.folder.sync()
            app.container.database.appMetaDao().put(AppMetaEntity(RecipeFolder.LIBRARY_PATH_KEY, old))
        }
        assertTrue(File(app.recipesDir, "b.yaml").delete())
        runBlocking { app.folder.sync() }
        compose.setTestContent { MealPlannerTheme { MealPlannerApp(app.container) } }

        waitForText(FOLDER_MOVED)
        compose.onNodeWithText(FOLDER_MOVED).click()
        waitForText(folderMovedMessage(old, app.recipesDir.canonicalPath))
        assertEquals(0, compose.onAllNodesWithText(REMOVE_IT).fetchSemanticsNodes().size)
        compose.onNodeWithText(POINT_BACK).click()
        waitForText(pointBackInstructions(old))
        assertEquals(listOf("Recipe A", "Recipe B"), runBlocking { app.container.recipes.allRecipes() }.map { it.name })

        compose.onNodeWithText(USE_NEW_FOLDER).click()
        // P7-R10e: it only accepts the folder. Recipe B is still there, held for the usual Remove confirmation.
        waitForText(missingFilesMessage(1), 10_000)
        compose.onNodeWithText(REMOVE_IT).assertExists()
        assertEquals(listOf("Recipe A", "Recipe B"), runBlocking { app.container.recipes.allRecipes() }.map { it.name })
        assertEquals(app.recipesDir.canonicalPath, runBlocking { app.container.database.appMetaDao().get(RecipeFolder.LIBRARY_PATH_KEY) })
    }

    /** Clicks on the UI thread (see DesktopAppTest). */
    private fun SemanticsNodeInteraction.click() {
        performSemanticsAction(SemanticsActions.OnClick)
    }
}
