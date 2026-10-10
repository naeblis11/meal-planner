package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.ui.MealPlannerApp
import com.naeblis11.mealplanner.ui.RAIL_WIDTH
import com.naeblis11.mealplanner.ui.RailItem
import com.naeblis11.mealplanner.ui.Routes
import com.naeblis11.mealplanner.ui.isWide
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** P3-R4: from 840 dp wide and 480 dp high a rail on the left with Settings; otherwise the phone's bottom tabs. */
class WideShellTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-shell").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    @Test
    fun theWideLayoutNeeds840dpWideAnd480dpHigh() {
        assertFalse(isWide(839.dp, 800.dp))
        assertTrue(isWide(840.dp, 800.dp))
        // A phone on its side (S2): wide enough, but too short.
        assertFalse(isWide(915.dp, 412.dp))
        assertFalse(isWide(1200.dp, 479.dp))
        assertTrue(isWide(1200.dp, 480.dp))
    }

    @Test
    fun everyPageLightsItsSectionOfTheRail() {
        for (item in RailItem.entries) assertEquals(item, RailItem.forRoute(item.route))
        assertEquals(RailItem.CALENDAR, RailItem.forRoute(Routes.ASSIGN))
        for (route in listOf(Routes.RECIPE, Routes.EDIT, Routes.NEW, Routes.IMPORT, Routes.NEEDS_ATTENTION)) {
            assertEquals(route, RailItem.RECIPES, RailItem.forRoute(route))
        }
        assertNull(RailItem.forRoute(null))
    }

    @Test
    fun aWideWindowHasTheRailWithSettings() {
        compose.showAt(1000.dp) { MealPlannerApp(app.container) }
        compose.waitForText("New recipe")
        compose.tab("Recipes").assertIsSelected()
        assertTrue(compose.tab("Calendar").getBoundsInRoot().right < RAIL_WIDTH + 20.dp)
        compose.tab("Settings").click()
        compose.waitForText("Backup")
        compose.tab("Settings").assertIsSelected()
        compose.tab("Pantry").click()
        compose.waitForText("What you've already got on hand.")
        compose.tab("Pantry").assertIsSelected()
    }

    @Test
    fun aNarrowWindowKeepsThePhoneTabs() {
        compose.showAt(400.dp) { MealPlannerApp(app.container) }
        compose.waitForText("New recipe")
        compose.tab("Recipes").assertIsSelected()
        compose.onAllNodes(hasText("Settings") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertCountEquals(0)
        assertTrue(compose.tab("Shopping").getBoundsInRoot().top > 600.dp)
    }
}
