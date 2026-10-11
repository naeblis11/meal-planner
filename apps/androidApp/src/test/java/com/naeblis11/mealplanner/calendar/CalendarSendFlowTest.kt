package com.naeblis11.mealplanner.calendar

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
    private fun await(text: String) = await(hasText(text))

    private fun await(matcher: SemanticsMatcher) =
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    // "Send this week" shows its words while still disabled (no week loaded yet, or a send running), and a click on a
    // disabled button does nothing. The screen's transition runs on the test clock, the week's load on real IO threads,
    // so under load the button can show before the week is there: wait for it to be enabled before clicking.
    private fun click(text: String) {
        await(hasText(text) and isEnabled())
        compose.onNode(hasText(text) and isEnabled()).performClick()
    }

    @Test
    fun setUpACalendarThenSendTheWeekTwice() {
        click("Calendar")
        click("Send this week to Google Calendar")
        await(CalendarMessages.NOT_SET_UP)

        click("Open Settings")
        click("Set up calendar sending")
        click("Family (family@example.com)")
        await("Sending to Family (family@example.com)")
        click("Back")

        click("Send this week to Google Calendar")
        await("Calendar updated: 1 added.")
        assertEquals(listOf("Dinner: Soup"), gateway.titles())

        click("Send this week to Google Calendar")
        await("Your calendar was already up to date.")
        assertEquals(listOf("insert 101"), gateway.writes)
    }
}
