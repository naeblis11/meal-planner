package com.naeblis11.mealplanner.desktop

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.naeblis11.mealplanner.recipes.SYNC_KEEP
import com.naeblis11.mealplanner.recipes.SYNC_REMOVE
import com.naeblis11.mealplanner.recipes.syncHeldBanner
import com.naeblis11.mealplanner.recipes.syncHeldText
import com.naeblis11.mealplanner.recipes.syncRemoveQuestion
import com.naeblis11.mealplanner.ui.MealPlannerApp
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** LAN sync stage 2b (D11): held sync removals on the Recipes banner and under Needs attention. */
class SyncAttentionTest {
    // Outermost: the app and its database close only after the compose rule has disposed the composition.
    @get:Rule(order = 0)
    val closing = CloseAfterCompose({ tearDown() })

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val dir: File = Files.createTempDirectory("mp-sync-attention").toFile()
    private val app = DesktopApp(dir, settingsFactory = { MapSettings() })

    private fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    @Test
    fun heldRemovalsAreOnTheBannerAndRemovedOnlyAfterAsking() {
        val attention = FakeSyncAttention(listOf("Soup", "Stew"), meals = 3)
        compose.setTestContent { MealPlannerTheme { MealPlannerApp(app.container, syncAttention = attention) } }
        compose.waitForText(syncHeldBanner(2))
        compose.onNodeWithText(syncHeldBanner(2)).click()
        compose.waitForText(syncHeldText(2))
        compose.onNodeWithText("Soup, Stew").assertExists()
        compose.onNodeWithText(SYNC_REMOVE).click()
        compose.waitForText(syncRemoveQuestion(2, 3))
        assertEquals(0, attention.confirms.get())
        compose.onNodeWithText("Remove").click()
        compose.waitUntil(5_000) { attention.confirms.get() == 1 }
        compose.waitForText("Nothing needs attention.")
    }

    @Test
    fun keepThemKeepsThem() {
        val attention = FakeSyncAttention(listOf("Soup"))
        compose.setTestContent { MealPlannerTheme { MealPlannerApp(app.container, syncAttention = attention) } }
        compose.waitForText(syncHeldBanner(1))
        compose.onNodeWithText(syncHeldBanner(1)).click()
        compose.waitForText(syncHeldText(1))
        compose.onNodeWithText(SYNC_KEEP).click()
        compose.waitUntil(5_000) { attention.keeps.get() == 1 }
        assertEquals(0, attention.confirms.get())
    }
}
