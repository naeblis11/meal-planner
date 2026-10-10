package com.naeblis11.mealplanner.plan

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.app.AppContainer
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.Week
import com.naeblis11.mealplanner.domain.YamlMap
import com.naeblis11.mealplanner.ui.MealPlannerApp
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h6000dp")
class MealPlanFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        container = AppContainer(ApplicationProvider.getApplicationContext())
        @Suppress("UNCHECKED_CAST")
        val doc = RecipeYaml.load(
            "recipe_name: Soup\ningredients:\n- Stock:\n    amounts:\n    - amount: 2\n      unit: cup\nsteps:\n- step: Simmer.\n",
        ) as YamlMap
        runBlocking { container.recipes.save(doc) }
        compose.setContent { MealPlannerTheme { MealPlannerApp(container) } }
    }

    @After
    fun tearDown() = container.database.close()

    private fun await(matcher: SemanticsMatcher) =
        compose.waitUntil(5_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun planADinnerAndSeeItOnTheWeek() {
        val today = Week.dayLabel(LocalDate.now())
        // Every screen loads on real IO threads, which waitForIdle does not wait for.
        compose.onNodeWithText("Calendar").performClick()
        await(hasContentDescription("Add recipe for Dinner on $today"))
        compose.onNodeWithContentDescription("Add recipe for Dinner on $today").performClick()
        await(hasText("Plan dinner"))
        compose.onNodeWithText("Plan dinner").assertIsDisplayed()
        await(hasText("Servings (optional)"))
        compose.onNodeWithText("Servings (optional)").performTextInput("6")
        await(hasText("Soup"))
        compose.onNodeWithText("Soup").performClick()
        await(hasText("for 6"))
        await(hasText("Plan your meals for the week."))
        compose.onNodeWithText("Plan your meals for the week.").assertIsDisplayed()
        await(hasContentDescription("Remove Dinner on $today"))
        compose.onNodeWithContentDescription("Remove Dinner on $today").assertIsDisplayed()
    }
}
