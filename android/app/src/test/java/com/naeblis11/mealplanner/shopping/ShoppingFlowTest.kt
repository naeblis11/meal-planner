package com.naeblis11.mealplanner.shopping

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class ShoppingFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        container = AppContainer(ApplicationProvider.getApplicationContext())
        runBlocking {
            @Suppress("UNCHECKED_CAST")
            val id = container.recipes.save(
                RecipeYaml.load(
                    "recipe_name: Pancakes\ningredients:\n" +
                        "- Flour:\n    amounts:\n    - amount: 2\n      unit: cup\n" +
                        "- Salt:\n    amounts:\n    - amount: 1\n      unit: tsp\n" +
                        "- Black pepper:\n    amounts:\n    - amount: 1\n      unit: tsp\n" +
                        "steps:\n- step: Mix.\n",
                ) as YamlMap,
            )
            container.plans.assign(Week.start(LocalDate.now()), "Dinner", id, null)
            container.pantry.add("salt")
            container.pantry.add("pepper")
            // Crossed out: the household has run out, so pepper needs buying.
            container.pantry.setActive(container.database.pantryDao().byName("pepper")!!.id, false)
        }
        compose.setContent { MealPlannerTheme { MealPlannerApp(container) } }
    }

    @After
    fun tearDown() = container.database.close()

    @Test
    fun addThisWeekGroupsTheListAndKeepsTheKitchenApart() {
        compose.onNode(hasText("Shopping") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
        compose.onNodeWithText("Add this week's meals").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("2 cup Flour")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Need to buy").assertIsDisplayed()
        compose.onNodeWithText("Dry Goods & Pasta").assertIsDisplayed()
        compose.onNodeWithText("1 tsp Black pepper").assertIsDisplayed()
        val kitchen = compose.onNodeWithText("Already in My Kitchen").fetchSemanticsNode().boundsInRoot.top
        assertTrue(compose.onNodeWithText("1 tsp Black pepper").fetchSemanticsNode().boundsInRoot.top < kitchen)
        assertTrue(compose.onNodeWithText("Salt").fetchSemanticsNode().boundsInRoot.top > kitchen)
    }
}
