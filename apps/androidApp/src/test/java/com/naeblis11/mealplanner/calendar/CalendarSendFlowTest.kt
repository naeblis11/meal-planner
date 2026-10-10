package com.naeblis11.mealplanner.calendar

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.app.AppContainer
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import com.naeblis11.mealplanner.ui.MealPlannerApp
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h6000dp")
class CalendarSendFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val gateway = FakeCalendarGateway()
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        container = AppContainer(ApplicationProvider.getApplicationContext(), gateway)
        @Suppress("UNCHECKED_CAST")
        val doc = RecipeYaml.load(
            "recipe_name: Soup\ningredients:\n- Stock:\n    amounts:\n    - amount: 2\n      unit: cup\nsteps:\n- step: Simmer.\n",
        ) as YamlMap
        runBlocking { container.plans.assign(LocalDate.now(), "Dinner", container.recipes.save(doc), null) }
        compose.setContent { MealPlannerTheme { MealPlannerApp(container) } }
    }

    @After
    fun tearDown() = container.database.close()

    // Screens load and sends run on real IO threads, which waitForIdle does not wait for. 10 s, as NavigationTest's:
    // under the whole suite's load the first send's real IO has taken more than 5 s.
    private fun await(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun setUpACalendarThenSendTheWeekTwice() {
        compose.onNodeWithText("Calendar").performClick()
        await("Send this week to Google Calendar")
        compose.onNodeWithText("Send this week to Google Calendar").performClick()
        await(CalendarMessages.NOT_SET_UP)

        compose.onNodeWithText("Open Settings").performClick()
        await("Set up calendar sending")
        compose.onNodeWithText("Set up calendar sending").performClick()
        await("Family (family@example.com)")
        compose.onNodeWithText("Family (family@example.com)").performClick()
        await("Sending to Family (family@example.com)")
        compose.onNodeWithText("Back").performClick()

        await("Send this week to Google Calendar")
        compose.onNodeWithText("Send this week to Google Calendar").performClick()
        await("Calendar updated: 1 added.")
        assertEquals(listOf("Dinner: Soup"), gateway.titles())

        await("Send this week to Google Calendar")
        compose.onNodeWithText("Send this week to Google Calendar").performClick()
        await("Your calendar was already up to date.")
        assertEquals(listOf("insert 101"), gateway.writes)
    }
}
