package com.naeblis11.mealplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryOptionsTest {
    @Test
    fun withAnEmptyLibraryTheSuggestionsAreTheBuiltInLists() {
        assertEquals(RecipeDefaults.CATEGORIES, CategoryOptions.DEFAULT.categories)
        assertEquals(RecipeDefaults.SUBCATEGORIES, CategoryOptions.DEFAULT.subcategories)
        assertEquals(RecipeDefaults.SUBCATEGORIES, CategoryOptions.DEFAULT.subcategoriesFor("Desserts"))
    }

    @Test
    fun theLibrarysOwnCategoriesFollowTheBuiltInOnesAlphabetically() {
        val options = CategoryOptions.from(
            listOf("Cookies" to null, "Main Dishes" to "Beef", "candy" to null, "Cookies" to "Bars"),
        )
        assertEquals(RecipeDefaults.CATEGORIES + listOf("candy", "Cookies"), options.categories)
        assertEquals(RecipeDefaults.SUBCATEGORIES + "Bars", options.subcategories)
    }

    @Test
    fun caseAndSpacesNeverMakeASecondSuggestionAndTheBuiltInSpellingWins() {
        val options = CategoryOptions.from(listOf(" desserts " to "BEEF", "Cookies" to null, "cookies" to null))
        assertEquals(RecipeDefaults.CATEGORIES + "Cookies", options.categories)
        assertEquals(RecipeDefaults.SUBCATEGORIES, options.subcategories)
    }

    @Test
    fun blanksNoneAndUncategorizedAreNeverSuggested() {
        val options = CategoryOptions.from(
            listOf(null to null, "" to "  ", "None" to "None", "Uncategorized" to null, "  " to "Bars"),
        )
        assertEquals(RecipeDefaults.CATEGORIES, options.categories)
        assertEquals(RecipeDefaults.SUBCATEGORIES + "Bars", options.subcategories)
    }

    @Test
    fun aCategoryOffersTheSubcategoriesItsRecipesUseFirst() {
        val options = CategoryOptions.from(
            listOf("Desserts" to "Cookies", "Desserts" to "Bars", "Main Dishes" to "Beef", "Main Dishes" to "Casseroles"),
        )
        assertEquals(
            listOf("Bars", "Cookies") + RecipeDefaults.SUBCATEGORIES + "Casseroles",
            options.subcategoriesFor("desserts"),
        )
        // Beef is a built-in, so it moves to the front rather than showing twice.
        assertEquals(
            listOf("Beef", "Casseroles") + (RecipeDefaults.SUBCATEGORIES - "Beef") + listOf("Bars", "Cookies"),
            options.subcategoriesFor("Main Dishes"),
        )
        assertEquals(options.subcategories, options.subcategoriesFor(""))
    }
}
