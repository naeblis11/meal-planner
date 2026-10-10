package com.naeblis11.mealplanner.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.app.AppContainer
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.Week
import com.naeblis11.mealplanner.domain.YamlMap
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
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class TabsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

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

    private fun awaitText(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun tab(label: String) =
        compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

    @Test
    fun opensOnRecipesWithItsTabSelected() {
        tab("Recipes").assertIsSelected()
        compose.onNodeWithText("New recipe").assertIsDisplayed()
    }

    @Test
    fun eachTabOpensItsScreen() {
        tab("Calendar").performClick()
        compose.onNodeWithText("Plan your meals for the week.").assertIsDisplayed()
        tab("Calendar").assertIsSelected()
        tab("Pantry").performClick()
        compose.onNodeWithText("What you've already got on hand.").assertIsDisplayed()
        tab("Shopping").performClick()
        compose.onNodeWithText("Crossed against what's already in your pantry.").assertIsDisplayed()
        tab("Recipes").performClick()
        compose.onNodeWithText("New recipe").assertIsDisplayed()
    }

    @Test
    fun backFromATabReturnsToRecipes() {
        tab("Pantry").performClick()
        tab("Shopping").performClick()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        tab("Recipes").assertIsSelected()
        compose.onNodeWithText("New recipe").assertIsDisplayed()
    }

    @Test
    fun aRecipePageHasNoTabBar() {
        awaitText("Soup")
        compose.onNodeWithText("Soup").performClick()
        awaitText("Simmer.")
        compose.onNodeWithText("Simmer.").assertIsDisplayed()
        compose.onAllNodesWithText("Shopping").assertCountEquals(0)
    }

    @Test
    fun theCalendarKeepsItsWeekAcrossATabRoundTrip() {
        val thisWeek = Week.label(Week.start(LocalDate.now()))
        val nextWeek = Week.label(Week.start(LocalDate.now()).plusWeeks(1))
        tab("Calendar").performClick()
        awaitText(thisWeek)
        compose.onNodeWithText("Next").performClick()
        awaitText(nextWeek)

        tab("Recipes").performClick()
        awaitText("New recipe")
        tab("Calendar").performClick()
        awaitText(nextWeek)
        compose.onNodeWithText(nextWeek).assertIsDisplayed()
        compose.onAllNodesWithText(thisWeek).assertCountEquals(0)
    }
}
