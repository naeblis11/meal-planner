package com.naeblis11.mealplanner.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.app.AppContainer
import com.naeblis11.mealplanner.sync.FakeSyncControls
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** LAN sync stage 2c (D4, D5): the Sync button in every main screen's top bar, and the first sync's question. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class SyncFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var container: AppContainer
    private val controls = FakeSyncControls(pcName = "KITCHEN")

    @Before
    fun setUp() {
        container = AppContainer(ApplicationProvider.getApplicationContext())
        compose.setContent { MealPlannerTheme { MealPlannerApp(container, join = controls, sync = controls) } }
    }

    @After
    fun tearDown() = container.database.close()

    private fun tab(label: String) =
        compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

    @Test
    fun everyMainScreenHasTheSyncButton() {
        compose.onNodeWithTag(SYNC_ACTION_TAG).assertIsDisplayed()
        for (label in listOf("Calendar", "Pantry", "Shopping", "Recipes")) {
            tab(label).performClick()
            compose.onNodeWithTag(SYNC_ACTION_TAG).assertIsDisplayed()
        }
    }

    @Test
    fun theFirstSyncAsksThenMergesAndSaysWhatMoved() {
        compose.onNodeWithTag(SYNC_ACTION_TAG).performClick()
        compose.onNodeWithText(firstSyncTitle("KITCHEN")).assertIsDisplayed()
        compose.onNodeWithText(MERGE_HELP).assertIsDisplayed()
        compose.onNodeWithText(REPLACE_HELP).assertIsDisplayed()
        compose.onNodeWithText(MERGE).performClick()
        compose.onAllNodesWithText(firstSyncTitle("KITCHEN")).assertCountEquals(0)
        assertEquals(listOf("sync MERGE"), controls.calls)
        controls.outcome.value = "Sent 1 change, got 2"
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Sent 1 change, got 2").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun replaceOpensTheBackupPickerFirstAndACancelChangesNothing() {
        compose.onNodeWithTag(SYNC_ACTION_TAG).performClick()
        compose.onNodeWithText(REPLACE).performClick()
        val started = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.action)
        assertTrue(started.intent.getStringExtra(Intent.EXTRA_TITLE)!!.startsWith("meal-planner-backup-"))
        compose.onAllNodesWithText(firstSyncTitle("KITCHEN")).assertCountEquals(0)
        // The picker never answered (as when the user backs out): nothing was backed up, replaced or synced.
        assertTrue(controls.calls.isEmpty())
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun onANarrowPhoneTheRecipesBarKeepsSyncImportAndSettings() {
        compose.onNodeWithTag(SYNC_ACTION_TAG).assertIsDisplayed()
        compose.onNodeWithText("Import").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun whileASyncOrALeaveRunsTheButtonIsBusy() {
        // PhoneSyncControls holds `syncing` for a sync, Replace's backup and a Leave alike.
        controls.syncing.value = true
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(SYNC_ACTION_TAG).fetchSemanticsNodes().any { SemanticsProperties.Disabled in it.config } }
        compose.onNodeWithTag(SYNC_ACTION_TAG).assertIsNotEnabled().performClick()
        assertTrue(controls.calls.isEmpty())
        controls.syncing.value = false
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(SYNC_ACTION_TAG).fetchSemanticsNodes().none { SemanticsProperties.Disabled in it.config } }
        compose.onNodeWithTag(SYNC_ACTION_TAG).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(firstSyncTitle("KITCHEN")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun anUnpairedPhoneHasNoSyncButton() {
        controls.status.value = controls.status.value.copy(pcName = null)
        compose.onAllNodesWithTag(SYNC_ACTION_TAG).assertCountEquals(0)
    }
}
