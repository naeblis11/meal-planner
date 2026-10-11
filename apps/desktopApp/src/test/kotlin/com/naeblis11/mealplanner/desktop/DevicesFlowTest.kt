package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.settings.PAIR_DEVICE
import com.naeblis11.mealplanner.ui.MealPlannerApp
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** LAN sync stage 2b (D10): a pairing window opened in Settings closes when Settings is left. */
class DevicesFlowTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-devices").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    @Test
    fun aPairingWindowClosesWhenSettingsIsLeft() {
        val controls = FakePairingControls()
        compose.showAt(1000.dp) { MealPlannerApp(app.container, devices = controls) }
        compose.waitForText("New recipe")
        compose.tab("Settings").click()
        compose.waitForText(PAIR_DEVICE)
        compose.onNodeWithText(PAIR_DEVICE).click()
        compose.waitForText("123 456")
        compose.tab("Recipes").click()
        compose.waitForText("New recipe")
        compose.waitUntil(5_000) { controls.closes.get() == 1 }
        assertNull(controls.offer.value)
    }
}
