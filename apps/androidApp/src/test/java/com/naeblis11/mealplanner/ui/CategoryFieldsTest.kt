package com.naeblis11.mealplanner.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.naeblis11.mealplanner.domain.CategoryOptions
import com.naeblis11.mealplanner.domain.RecipeDefaults
import com.naeblis11.mealplanner.ui.theme.MealPlannerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Owner, 2026-10-10: every category editor suggests as you type and offers the library's categories as buttons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h900dp")
class CategoryFieldsTest {
    @get:Rule
    val compose = createComposeRule()

    private val options = CategoryOptions.from(listOf("Cookies" to "Bars", "Desserts" to "Cakes"))
    private var category by mutableStateOf("")
    private var subcategory by mutableStateOf("")

    private fun show() = compose.setContent {
        MealPlannerTheme {
            CategoryFields(category, subcategory, options, onCategory = { category = it }, onSubcategory = { subcategory = it })
        }
    }

    private fun chips(tag: String): List<String> =
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(tag))).fetchSemanticsNodes().map { node ->
            node.config[SemanticsProperties.Text].joinToString { it.text }
        }

    private fun SemanticsNodeInteractionsProvider.count(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun theButtonsShowTheBuiltInCategoriesThenTheLibrarysOwn() {
        show()
        assertEquals(RecipeDefaults.CATEGORIES + "Cookies", chips(CATEGORY_SUGGESTIONS_TAG))
        compose.onNode(hasText("Cookies") and hasAnyAncestor(hasTestTag(CATEGORY_SUGGESTIONS_TAG))).performScrollTo().performClick()
        assertEquals("Cookies", category)
    }

    @Test
    fun typingSuggestsTheMatchingCategories() {
        show()
        compose.onNode(hasText("Category") and hasSetTextAction()).performTextInput("coo")
        compose.waitForIdle()
        // Cookies is both a button and a suggestion; Desserts, which doesn't match, is only a button.
        assertEquals(2, compose.count("Cookies"))
        assertEquals(1, compose.count("Desserts"))
        compose.onNode(hasText("Cookies") and !hasAnyAncestor(hasTestTag(CATEGORY_SUGGESTIONS_TAG))).performClick()
        assertEquals("Cookies", category)
    }

    @Test
    fun theSubcategoryButtonsPutTheChosenCategorysOwnFirst() {
        category = "Desserts"
        show()
        assertEquals(listOf("Cakes") + RecipeDefaults.SUBCATEGORIES + "Bars", chips(SUBCATEGORY_SUGGESTIONS_TAG))
        compose.onNode(hasText("Cakes") and hasAnyAncestor(hasTestTag(SUBCATEGORY_SUGGESTIONS_TAG))).performScrollTo().performClick()
        assertEquals("Cakes", subcategory)
    }
}
