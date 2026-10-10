package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.settings.APP_REMOVED_MESSAGE
import com.naeblis11.mealplanner.settings.APP_REPLACED_MESSAGE
import com.naeblis11.mealplanner.settings.AppReplaced
import com.naeblis11.mealplanner.settings.RESTART_BY_HAND
import com.naeblis11.mealplanner.settings.RESTART_NOW_LABEL
import com.naeblis11.mealplanner.settings.RestartControls
import com.naeblis11.mealplanner.ui.LeaveGuard
import com.naeblis11.mealplanner.ui.MealPlannerApp
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** P7-R12: once the running copy was replaced on disk, the window says so above every screen, with Restart now when it can. */
class ReplacedBannerTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-replaced-ui").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    private class FakeRestart(initial: AppReplaced?, val onRestart: () -> Unit = {}) : RestartControls {
        val flow = MutableStateFlow(initial)
        override val replaced = flow
        var restarts = 0

        override fun restartNow() {
            restarts++
            onRestart()
        }
    }

    @Test
    fun nothingIsSaidWhileTheAppIsCurrent() {
        val restart = FakeRestart(null)
        compose.showAt(1000.dp) { MealPlannerApp(app.container, restart = restart) }
        compose.waitForText("New recipe")
        compose.onAllNodesWithText(APP_REPLACED_MESSAGE).assertCountEquals(0)
        compose.onAllNodesWithText(RESTART_NOW_LABEL).assertCountEquals(0)
        // Then the jar is found replaced.
        restart.flow.value = AppReplaced(canRestart = true)
        compose.waitForText(APP_REPLACED_MESSAGE)
    }

    @Test
    fun theInstalledAppOffersRestartNow() {
        val restart = FakeRestart(AppReplaced(canRestart = true))
        compose.showAt(1000.dp) { MealPlannerApp(app.container, restart = restart) }
        compose.waitForText(APP_REPLACED_MESSAGE)
        compose.onAllNodesWithText(RESTART_BY_HAND).assertCountEquals(0)
        compose.onNodeWithText(RESTART_NOW_LABEL).click()
        compose.waitForIdle()
        assertEquals(1, restart.restarts)
        // Above every screen.
        compose.tab("Settings").click()
        compose.waitForText(APP_REPLACED_MESSAGE)
    }

    @Test
    fun withoutAValidLauncherItSaysWhatToDoInstead() {
        val restart = FakeRestart(AppReplaced(canRestart = false))
        compose.showAt(1000.dp) { MealPlannerApp(app.container, restart = restart) }
        compose.waitForText(APP_REPLACED_MESSAGE)
        compose.waitForText(RESTART_BY_HAND)
        compose.onAllNodesWithText(RESTART_NOW_LABEL).assertCountEquals(0)
    }

    @Test
    fun anUninstalledAppGetsTheNeutralNoticeAndNoButton() {
        val restart = FakeRestart(AppReplaced(canRestart = false, removed = true))
        compose.showAt(1000.dp) { MealPlannerApp(app.container, restart = restart) }
        compose.waitForText(APP_REMOVED_MESSAGE)
        compose.onAllNodesWithText(APP_REPLACED_MESSAGE).assertCountEquals(0)
        compose.onAllNodesWithText(RESTART_NOW_LABEL).assertCountEquals(0)
        compose.onAllNodesWithText(RESTART_BY_HAND).assertCountEquals(0)
    }

    @Test
    fun restartNowWithAnUnsavedEditAsksFirst() {
        File(app.recipesDir, "soup.yaml").writeText(
            "recipe_name: Soup\ningredients:\n- Stock:\n    amounts:\n    - amount: 2\n      unit: cup\nsteps:\n- step: Simmer.\n",
        )
        runBlocking { app.folder.sync() }
        val guard = LeaveGuard()
        val shell = WindowShell(startMinimized = false, traySupported = true, notice = TrayNotice(MapSettings()))
        var quits = 0
        // As main wires it: Restart now is the guarded quit.
        val restart = FakeRestart(AppReplaced(canRestart = true), onRestart = guardedQuit(shell, guard) { quits++ })
        compose.showAt(1000.dp) { MealPlannerApp(app.container, leaveGuard = guard, restart = restart) }
        compose.waitForText("Soup")
        compose.onNodeWithText("Soup").click()
        compose.waitForText("Simmer.")
        compose.onNodeWithText("Edit").click()
        compose.waitForText("Edit recipe")
        compose.onNode(hasText("Title") and hasSetTextAction()).performTextReplacement("Soup with leeks")
        compose.waitForText("Soup with leeks")

        compose.onNodeWithText(RESTART_NOW_LABEL).click()
        compose.waitForText(DISCARD_TITLE)
        assertEquals(0, quits)
        // Keep editing: no restart, and the edit is still there.
        compose.onNodeWithText("Keep editing").click()
        compose.waitForIdle()
        assertEquals(0, quits)
        compose.onNodeWithText("Soup with leeks").assertExists()
        // Discard: the quit (and so the restart) goes ahead.
        compose.onNodeWithText(RESTART_NOW_LABEL).click()
        compose.waitForText(DISCARD_TITLE)
        compose.onNodeWithText("Discard").click()
        compose.waitUntil(5_000) { quits == 1 }
    }

    private companion object {
        const val DISCARD_TITLE = "Discard changes?"
    }
}
