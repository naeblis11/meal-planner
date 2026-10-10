package com.naeblis11.mealplanner.importing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.naeblis11.mealplanner.backup.ImportAction
import com.naeblis11.mealplanner.backup.StagedKind
import com.naeblis11.mealplanner.recipes.EditorRowState
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h3000dp")
class ImportScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun row(id: Int, kind: StagedKind, title: String) = ReviewRow(
        id, kind, title, title, "", "", "", "servings",
        if (kind == StagedKind.UPDATE) ImportAction.UPDATE else ImportAction.IMPORT, emptyList(), 0,
    )

    @Test
    fun explainsEachKindAndConfirms() {
        var confirmed = false
        val state = ImportState.Reviewing(
            "backup.zip",
            listOf(row(0, StagedKind.NEW, "Soup"), row(1, StagedKind.DUPLICATE, "Stew"), row(2, StagedKind.UPDATE, "Pho")),
            listOf("bad.yaml" to "Missing required field(s): steps"),
        )
        compose.setContent { MealPlannerTheme { ImportScreen(state, { _, _ -> }, { confirmed = true }, {}, {}) } }
        compose.onNodeWithText("New recipe").assertIsDisplayed()
        compose.onNodeWithText("Already in your library. Change the title to import a copy; otherwise it is skipped.").assertExists()
        compose.onNodeWithText("Updates the recipe already in your library.").assertExists()
        compose.onNodeWithText("bad.yaml: Missing required field(s): steps").assertExists()
        compose.onNodeWithText("Confirm").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun aConfirmErrorIsScrolledIntoViewAndAnnounced() {
        val rows = (0 until 30).map { row(it, StagedKind.NEW, "Recipe $it") }
        var state: ImportState by mutableStateOf(ImportState.Reviewing("backup.zip", rows, emptyList()))
        compose.setContent { MealPlannerTheme { ImportScreen(state, { _, _ -> }, {}, {}, {}) } }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Recipe 29"))
        compose.onAllNodesWithText("From backup.zip").assertCountEquals(0)

        state = (state as ImportState.Reviewing).copy(error = "Can't import 'Soup'.")
        compose.waitForIdle()
        compose.onNodeWithText("Can't import 'Soup'.")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun theAmountBannerCountsTheRowsStillNeedingInput() {
        val rows = listOf(
            EditorRowState("e0", "ingredient", "Salt", "a pinch", needsInput = true),
            EditorRowState("e1", "ingredient", "Pepper", "some", needsInput = true),
        )
        var state: ImportState by mutableStateOf(
            ImportState.Reviewing("soup.yaml", listOf(row(0, StagedKind.NEW, "Soup").copy(rows = rows, amountIssues = 2)), emptyList()),
        )
        compose.setContent { MealPlannerTheme { ImportScreen(state, { _, _ -> }, {}, {}, {}) } }
        compose.onNodeWithText("2 amount(s) need your attention").assertExists()

        val review = state as ImportState.Reviewing
        state = review.copy(rows = review.rows.map { r -> r.copy(rows = r.rows.mapIndexed { i, e -> if (i == 0) e.copy(needsInput = false) else e }) })
        compose.onNodeWithText("1 amount(s) need your attention").assertExists()

        state = review.copy(rows = review.rows.map { r -> r.copy(rows = r.rows.map { it.copy(needsInput = false) }) })
        compose.onAllNodesWithText("amount(s) need your attention", substring = true).assertCountEquals(0)
    }

    @Test
    fun backToRecipesAsksForTheRecipeListNotJustBack() {
        var toRecipes = false
        var done = false
        compose.setContent {
            MealPlannerTheme {
                ImportScreen(ImportState.Finished("Imported 1 recipe(s)"), { _, _ -> }, {}, {}, onDone = { done = true }, onBackToRecipes = { toRecipes = true })
            }
        }
        compose.onNodeWithText("Back to recipes").performClick()
        assertTrue(toRecipes)
        assertFalse(done)
    }
}
