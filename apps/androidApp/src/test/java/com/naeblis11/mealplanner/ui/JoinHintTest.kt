package com.naeblis11.mealplanner.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.app.AppContainer
import com.naeblis11.mealplanner.settings.JOIN_PANEL
import com.naeblis11.mealplanner.sync.FakeSyncControls
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** LAN sync stage 2c (D3): the phone's first-run hint on Recipes, until it is paired or put away. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class JoinHintTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        container = AppContainer(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = container.database.close()

    private fun show(controls: FakeSyncControls?) =
        compose.setContent { MealPlannerTheme { MealPlannerApp(container, join = controls, sync = controls) } }

    @Test
    fun anUnpairedPhoneIsToldWhereToJoinAndJoinOpensSettings() {
        show(FakeSyncControls(pcName = null))
        compose.onNodeWithText(JOIN_HINT).assertIsDisplayed()
        compose.onNodeWithText(JOIN_HINT_ACTION).performClick()
        compose.onNodeWithText(JOIN_PANEL).assertExists()
    }

    @Test
    fun putAwayItStaysAway() {
        show(FakeSyncControls(pcName = null))
        compose.onNodeWithContentDescription(DISMISS_HINT).performClick()
        compose.onAllNodesWithText(JOIN_HINT).assertCountEquals(0)
        assertEquals(1L, container.settings.getLong(JOIN_HINT_DISMISSED_KEY))
    }

    @Test
    fun aHintPutAwayEarlierDoesntComeBack() {
        container.settings.put(mapOf(JOIN_HINT_DISMISSED_KEY to 1L))
        show(FakeSyncControls(pcName = null))
        compose.onNodeWithText("New recipe").assertIsDisplayed()
        compose.onAllNodesWithText(JOIN_HINT).assertCountEquals(0)
    }

    @Test
    fun aPairedPhoneHasNoHint() {
        show(FakeSyncControls(pcName = "KITCHEN"))
        compose.onNodeWithTag(SYNC_ACTION_TAG).assertIsDisplayed()
        compose.onAllNodesWithText(JOIN_HINT).assertCountEquals(0)
    }

    @Test
    fun withoutSyncControlsThereIsNoHint() {
        show(null)
        compose.onNodeWithText("New recipe").assertIsDisplayed()
        compose.onAllNodesWithText(JOIN_HINT).assertCountEquals(0)
    }
}
