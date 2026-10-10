package com.naeblis11.mealplanner.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.naeblis11.mealplanner.app.AppContainer
import com.naeblis11.mealplanner.domain.RecipeYaml
import com.naeblis11.mealplanner.domain.YamlMap
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A fast double tap must act once: no blank NavHost, no stacked screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class NavigationTest {
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
        // The list and every recipe page load on real IO threads, which waitForIdle does not wait for.
        awaitText("Soup")
    }

    private fun awaitText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @After
    fun tearDown() = container.database.close()

    private fun openSoup() {
        compose.onNodeWithText("Soup").performClick()
        awaitText("Simmer.")
        compose.onNodeWithText("Simmer.").assertIsDisplayed()
    }

    @Test
    fun aDoubleTapOnBackStaysOnRecipes() {
        openSoup()
        compose.onNodeWithText("Back").performTouchInput { click(); click() }
        compose.waitForIdle()
        compose.onNode(hasText("Recipes") and isSelected()).assertIsDisplayed()
        compose.onNodeWithText("New recipe").assertIsDisplayed()
    }

    @Test
    fun aDoubleTapOnEditOpensOneEditor() {
        openSoup()
        compose.onNodeWithText("Edit").performTouchInput { click(); click() }
        compose.waitForIdle()
        awaitText("Edit recipe")
        compose.onNodeWithText("Edit recipe").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Edit recipe").assertCountEquals(0)
        compose.onNodeWithText("Simmer.").assertIsDisplayed()
    }

    @Test
    fun aDoubleTapOnARecipeOpensItOnce() {
        compose.onNodeWithText("Soup").performTouchInput { click(); click() }
        compose.waitForIdle()
        compose.onNodeWithText("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("New recipe").assertIsDisplayed()
        compose.onAllNodesWithText("Simmer.").assertCountEquals(0)
    }
}
