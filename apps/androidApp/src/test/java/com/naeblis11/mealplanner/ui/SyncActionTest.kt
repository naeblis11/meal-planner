package com.naeblis11.mealplanner.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.settings.NEVER_SYNCED
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** LAN sync stage 2c (D4): the Sync button and its "Last synced" line. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncActionTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun itSaysWhenThePhoneLastSyncedAndSyncsOnATap() {
        var taps = 0
        compose.setContent { MealPlannerTheme { SyncButton(now - 5 * minute, syncing = false, now = now, onSync = { taps++ }) } }
        compose.onNodeWithText("5 min ago").assertIsDisplayed()
        compose.onNodeWithTag(SYNC_ACTION_TAG).assertContentDescriptionEquals("Last synced 5 min ago").assertIsEnabled().performClick()
        assertEquals(1, taps)
    }

    @Test
    fun whileSyncingItCantBeTapped() {
        compose.setContent { MealPlannerTheme { SyncButton(null, syncing = true, now = now, onSync = {}) } }
        compose.onNodeWithText("Never").assertIsDisplayed()
        compose.onNodeWithTag(SYNC_ACTION_TAG).assertContentDescriptionEquals(NEVER_SYNCED).assertIsNotEnabled()
    }

    @Test
    fun itFitsTheTopBarAtALargeFontScale() {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 1.3f)) {
                MealPlannerTheme { Box(Modifier.height(64.dp)) { SyncButton(now - 3 * 60 * minute, syncing = false, now = now, onSync = {}) } }
            }
        }
        compose.onNodeWithText("3 h ago").assertIsDisplayed()
        // Compose has no assertHeightIsAtMost: compare the bounds. The button fits the bar, and its line fits the button.
        val button = compose.onNodeWithTag(SYNC_ACTION_TAG).getUnclippedBoundsInRoot()
        val line = compose.onNodeWithText("3 h ago", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("button ${button.bottom - button.top}", button.bottom - button.top <= 64.dp)
        assertTrue("line ${line.bottom} under the button's ${button.bottom}", line.bottom <= button.bottom)
    }

    @Test
    fun withNoSyncControlsNothingShows() {
        compose.setContent { MealPlannerTheme { CompositionLocalProvider(LocalSyncControls provides null) { SyncAction() } } }
        compose.onAllNodesWithTag(SYNC_ACTION_TAG).assertCountEquals(0)
    }

    @Test
    fun agoIsShortAndPlain() {
        assertEquals("Never", syncedAgo(null, now))
        assertEquals("Just now", syncedAgo(now - 59_000L, now))
        assertEquals("Just now", syncedAgo(now + 5_000L, now))
        assertEquals("1 min ago", syncedAgo(now - minute, now))
        assertEquals("59 min ago", syncedAgo(now - 59 * minute, now))
        assertEquals("2 h ago", syncedAgo(now - 120 * minute, now))
        assertEquals("Yesterday", syncedAgo(now - 30 * 60 * minute, now))
        assertEquals("3 days ago", syncedAgo(now - 3 * 24 * 60 * minute, now))
        assertEquals(NEVER_SYNCED, lastSyncedLabel(null, now))
        assertEquals("Last synced just now", lastSyncedLabel(now, now))
        assertEquals("Last synced yesterday", lastSyncedLabel(now - 30 * 60 * minute, now))
    }
}
