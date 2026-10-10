package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.settings.CREATE_TOKEN
import com.naeblis11.mealplanner.settings.NEW_TOKEN
import com.naeblis11.mealplanner.settings.ServerState
import com.naeblis11.mealplanner.settings.ServerStatus
import com.naeblis11.mealplanner.settings.secretsLine
import com.naeblis11.mealplanner.ui.MealPlannerApp
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** P4-R6: a token made while the user went elsewhere is never lost: Settings shows it on their return, once. */
class TokenRevealFlowTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-reveal").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    @Test
    fun leavingSettingsMidCreateThenReturningShowsTheTokenOnce() {
        val controls = FakeControls(ServerStatus(ServerState.LISTENING, 5000)).apply { gate = CountDownLatch(1) }
        compose.showAt(1000.dp) { MealPlannerApp(app.container, server = controls) }
        compose.waitForText("New recipe")
        compose.tab("Settings").click()
        compose.waitForText(CREATE_TOKEN)
        compose.onNodeWithText(CREATE_TOKEN).click()
        assertTrue(controls.entered.await(5, TimeUnit.SECONDS))
        compose.tab("Recipes").click()
        compose.waitForText("New recipe")
        controls.gate!!.countDown()
        compose.waitUntil(5_000) { controls.pendingReveal.waiting.value }
        compose.onAllNodesWithText(secretsLine("abc123")).assertCountEquals(0)

        compose.tab("Settings").click()
        compose.waitForText(secretsLine("abc123"))
        compose.onNodeWithText("Done").click()
        compose.tab("Recipes").click()
        compose.waitForText("New recipe")
        compose.tab("Settings").click()
        compose.waitForText(NEW_TOKEN)
        compose.onAllNodesWithText(secretsLine("abc123")).assertCountEquals(0)
    }

    @Test
    fun aTokenOnScreenLeavesWithSettings() {
        val controls = FakeControls(ServerStatus(ServerState.LISTENING, 5000))
        compose.showAt(1000.dp) { MealPlannerApp(app.container, server = controls) }
        compose.waitForText("New recipe")
        compose.tab("Settings").click()
        compose.waitForText(CREATE_TOKEN)
        compose.onNodeWithText(CREATE_TOKEN).click()
        compose.waitForText(secretsLine("abc123"))
        compose.tab("Recipes").click()
        compose.waitForText("New recipe")
        compose.tab("Settings").click()
        compose.waitForText(NEW_TOKEN)
        compose.onAllNodesWithText(secretsLine("abc123")).assertCountEquals(0)
    }
}
