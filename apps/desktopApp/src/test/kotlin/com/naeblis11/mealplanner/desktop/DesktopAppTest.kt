package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.naeblis11.mealplanner.ui.MealPlannerApp
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import java.io.File
import java.nio.file.Files
import java.util.prefs.Preferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DesktopAppTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-desktop").toFile()
    private val prefsNode = "com/naeblis11/mealplanner/test-${System.nanoTime()}"
    private val app = DesktopApp(dir, prefsNode)

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
        Preferences.userRoot().node(prefsNode).removeNode()
    }

    @Test
    fun theSharedAppOpensOnRecipesAndSwitchesTabs() {
        compose.setTestContent { MealPlannerTheme { MealPlannerApp(app.container) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Shopping").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Shopping").click()
        compose.onNodeWithText("Pantry").click()
        compose.waitForIdle()
        assertTrue(File(dir, "mealplanner-app.db").isFile)
        // The Python server's DB name must never appear in a desktop data folder.
        assertFalse(File(dir, "mealplanner.db").exists())
    }

    /**
     * Clicks on the UI thread. performClick() injects the pointer events on the test thread, and
     * navigating then moves a NavBackStackEntry's lifecycle there, which LifecycleRegistry rejects
     * ("must be called on the main thread"). A real click arrives on the Swing thread.
     */
    private fun SemanticsNodeInteraction.click() {
        performSemanticsAction(SemanticsActions.OnClick)
    }
}
