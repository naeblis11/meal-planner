package com.naeblis11.mealplanner.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.naeblis11.mealplanner.domain.CategoryOptions

const val CATEGORY_SUGGESTIONS_TAG = "category-suggestions"
const val SUBCATEGORY_SUGGESTIONS_TAG = "subcategory-suggestions"

/**
 * Category and Subcategory as every category editor shows them (the edit page, the recipe page's
 * Category form, the import review; owner, 2026-10-10): each field suggests as you type, with a
 * row of one-tap buttons under it. Both come from [options]: the built-in lists, then whatever the
 * library already uses, and the subcategories the chosen category's recipes use come first.
 */
@Composable
fun CategoryFields(
    category: String,
    subcategory: String,
    options: CategoryOptions,
    onCategory: (String) -> Unit,
    onSubcategory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val subcategories = options.subcategoriesFor(category)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SuggestField(category, onCategory, options.categories, "Category")
        SuggestionChips(options.categories, CATEGORY_SUGGESTIONS_TAG, onCategory)
        SuggestField(subcategory, onSubcategory, subcategories, "Subcategory")
        SuggestionChips(subcategories, SUBCATEGORY_SUGGESTIONS_TAG, onSubcategory)
    }
}

@Composable
private fun SuggestionChips(options: List<String>, tag: String, onPick: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(tag),
    ) {
        for (option in options) AssistChip(onClick = { onPick(option) }, label = { Text(option) })
    }
}
