package com.naeblis11.mealplanner.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** An Android tablet wide enough gets the desktop's wide layout (P3-R4); phones keep the tabs (TabsTest). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp")
class WideLayoutTest {
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
    fun aTabletGetsTheRailWithSettings() {
        tab("Recipes").assertIsSelected()
        tab("Settings").performClick()
        awaitText("Backup")
        compose.onNodeWithText("Backup").assertIsDisplayed()
        tab("Settings").assertIsSelected()
    }

    @Test
    fun aRecipeOpensBesideTheList() {
        awaitText("Soup")
        compose.onNodeWithText("Soup").performClick()
        awaitText("Simmer.")
        compose.onNodeWithText("Simmer.").assertIsDisplayed()
        compose.onNodeWithText("Search recipes").assertIsDisplayed()
    }
}
