package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.settings.CANCEL_HANDOVER
import com.naeblis11.mealplanner.settings.DeviceRow
import com.naeblis11.mealplanner.settings.HandoverView
import com.naeblis11.mealplanner.settings.MAKE_MAIN_PC
import com.naeblis11.mealplanner.settings.handingOver
import com.naeblis11.mealplanner.settings.handoverQuestion
import com.naeblis11.mealplanner.settings.handoverWaiting
import com.naeblis11.mealplanner.ui.MealPlannerApp
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** LAN sync stage 4b: Settings' Make it the main PC, for a paired PC only, asked first, then waiting for that PC's Sync. */
class HandoverFlowTest {
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-handover-flow").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    private val study = DeviceRow("a".repeat(32), "STUDY", "pc", null)
    private val pixel = DeviceRow("b".repeat(32), "Pixel", "phone", null)

    @Test
    fun aPcIsMadeTheMainPcAfterAskingAndTheOfferCanBeCancelled() {
        val controls = FakePairingControls().apply { devices.value = listOf(pixel, study) }
        compose.showAt(1000.dp, height = 1600.dp) { MealPlannerApp(app.container, devices = controls) }
        compose.waitForText("New recipe")
        compose.tab("Settings").click()
        compose.waitForText("STUDY")
        // Only the PC has the button.
        assertEquals(1, compose.onAllNodesWithText(MAKE_MAIN_PC).fetchSemanticsNodes().size)
        compose.onNodeWithText(MAKE_MAIN_PC).click()
        compose.waitForText(handoverQuestion("STUDY"))
        compose.onAllNodesWithText(MAKE_MAIN_PC)[1].click()
        compose.waitForText(handoverWaiting("STUDY"))
        assertEquals(listOf(study.deviceId), controls.offeredTo.toList())
        compose.onNodeWithText(CANCEL_HANDOVER).click()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(handoverWaiting("STUDY")).fetchSemanticsNodes().isEmpty() }
        assertNull(controls.handover.value)
        // Once that PC has taken it, the line says so and there is nothing to cancel.
        controls.handover.value = HandoverView(study.deviceId, "STUDY", Long.MAX_VALUE, handing = true)
        compose.waitForText(handingOver("STUDY"))
        compose.onNodeWithText(CANCEL_HANDOVER).assertDoesNotExist()
    }
}
