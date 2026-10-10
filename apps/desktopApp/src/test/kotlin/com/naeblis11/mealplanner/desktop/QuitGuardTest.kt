package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
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

/** Quit (the tray's, or closing the window with no tray) never drops an unsaved edit without asking. */
class QuitGuardTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Test
    fun aQuitWithAnUnsavedEditAsksFirst() {
        val dir: File = Files.createTempDirectory("mp-quit").toFile()
        val app = DesktopApp(dir, settingsFactory = { MapSettings() })
        closing.add {
            app.close()
            dir.deleteRecursively()
        }
        File(app.recipesDir, "soup.yaml").writeText(
            "recipe_name: Soup\ningredients:\n- Stock:\n    amounts:\n    - amount: 2\n      unit: cup\nsteps:\n- step: Simmer.\n",
        )
        runBlocking { app.folder.sync() }
        val guard = LeaveGuard()
        val shell = WindowShell(startMinimized = false, traySupported = true, notice = TrayNotice(MapSettings()))
        var quits = 0
        val quit = guardedQuit(shell, guard) { quits++ }
        compose.showAt(1000.dp) { MealPlannerApp(app.container, leaveGuard = guard) }
        compose.waitForText("Soup")
        compose.onNodeWithText("Soup").click()
        compose.waitForText("Simmer.")
        compose.onNodeWithText("Edit").click()
        compose.waitForText("Edit recipe")
        compose.onNode(hasText("Title") and hasSetTextAction()).performTextReplacement("Soup with leeks")
        compose.waitForText("Soup with leeks")
        // The window was closed to the tray with the edit still open; then the tray's Quit.
        compose.runOnIdle { shell.closeRequested() }
        assertFalse(shell.isVisible)

        compose.runOnIdle { quit() }
        compose.waitForText(DISCARD_TITLE)
        // Shown again, so the question can be seen.
        assertTrue(shell.isVisible)
        assertEquals(0, quits)

        // Keep editing: no quit, and the edit is still there.
        compose.onNodeWithText("Keep editing").click()
        compose.waitForIdle()
        assertEquals(0, quits)
        assertFalse(shell.quitting)
        compose.onNodeWithText("Soup with leeks").assertExists()

        // Discard: the app quits.
        compose.runOnIdle { quit() }
        compose.waitForText(DISCARD_TITLE)
        compose.onNodeWithText("Discard").click()
        compose.waitUntil(5_000) { quits == 1 }
    }

    @Test
    fun aQuitWithNothingUnsavedQuitsAtOnceWithoutShowingTheWindow() {
        // Started in the tray and never opened: the screens were never composed, and must not be just to quit.
        val shell = WindowShell(startMinimized = true, traySupported = true, notice = TrayNotice(MapSettings()))
        var quits = 0
        guardedQuit(shell, LeaveGuard()) { quits++ }()
        assertEquals(1, quits)
        assertFalse(shell.hasBeenShown)
    }

    private companion object {
        const val DISCARD_TITLE = "Discard changes?"
    }
}
