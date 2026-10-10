package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.settings.SERVER_PANEL
import com.naeblis11.mealplanner.settings.ServerState
import com.naeblis11.mealplanner.settings.ServerStatus
import com.naeblis11.mealplanner.settings.portInUseNotice
import com.naeblis11.mealplanner.ui.MealPlannerApp
import java.io.File
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test

/** P4-R1: while port 5000 is taken the window says so above every screen, and Settings says it too. */
class PortInUseBannerTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-port").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    @Test
    fun theNoticeIsAboveEveryScreenAndInSettings() {
        val controls = FakeControls(ServerStatus(ServerState.PORT_IN_USE, 5000))
        compose.showAt(1000.dp) { MealPlannerApp(app.container, server = controls) }
        compose.waitForText(portInUseNotice(5000))
        compose.tab("Settings").click()
        compose.waitForText(SERVER_PANEL)
        compose.onAllNodesWithText(portInUseNotice(5000)).assertCountEquals(2)
    }

    @Test
    fun noNoticeWhileItListensUntilThePortIsTaken() {
        val controls = FakeControls(ServerStatus(ServerState.LISTENING, 5000))
        compose.showAt(1000.dp) { MealPlannerApp(app.container, server = controls) }
        compose.waitForText("New recipe")
        compose.onAllNodesWithText(portInUseNotice(5000)).assertCountEquals(0)
        controls.flow.value = ServerStatus(ServerState.PORT_IN_USE, 5000)
        compose.waitForText(portInUseNotice(5000))
    }
}
