package com.naeblis11.mealplanner.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.app.AppContainer
import com.naeblis11.mealplanner.sync.FakeSyncControls
import com.naeblis11.mealplanner.sync.PossibleDuplicate
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** LAN sync stage 2c (D5): after a Merge, recipes with the same name are settled one by one. Nothing is removed without a click. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class DuplicatesDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val soup = PossibleDuplicate("Soup", 1L, 2L)
    private val chili = PossibleDuplicate("Chili", 3L, 4L)

    @Test
    fun eachRowHasKeepBothAndKeepThePcs() {
        val said = mutableListOf<String>()
        compose.setContent {
            MealPlannerTheme {
                DuplicatesDialog(
                    listOf(soup, chili),
                    onKeepBoth = { said += "both ${it.name}" },
                    onKeepPcs = { said += "pc ${it.name}" },
                    onLater = { said += "later" },
                )
            }
        }
        compose.onNodeWithText(POSSIBLE_DUPLICATES).assertIsDisplayed()
        compose.onNodeWithText(DUPLICATES_HELP).assertIsDisplayed()
        compose.onNodeWithText("Soup").assertIsDisplayed()
        compose.onAllNodesWithText(KEEP_BOTH)[0].performClick()
        compose.onAllNodesWithText(KEEP_PCS)[1].performClick()
        compose.onNodeWithText(LATER).performClick()
        assertEquals(listOf("both Soup", "pc Chili", "later"), said)
    }

    @Test
    fun inTheAppTheListShowsAfterAMergeUntilEachIsSettled() {
        val controls = FakeSyncControls(pcName = "KITCHEN")
        val container = AppContainer(ApplicationProvider.getApplicationContext())
        try {
            compose.setContent { MealPlannerTheme { MealPlannerApp(container, join = controls, sync = controls) } }
            controls.duplicates.value = listOf(soup)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(POSSIBLE_DUPLICATES).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(KEEP_PCS).performClick()
            compose.onAllNodesWithText(POSSIBLE_DUPLICATES).assertCountEquals(0)
            assertEquals(listOf("keepPcs Soup"), controls.calls)
        } finally {
            container.database.close()
        }
    }
}
