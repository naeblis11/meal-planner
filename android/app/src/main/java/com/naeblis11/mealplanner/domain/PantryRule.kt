package com.naeblis11.mealplanner.domain

import java.util.Locale

/**
 * A pantry item as the shopping list sees it. By default it covers any
 * ingredient whose name contains it ("salt" covers "kosher salt"); an exact
 * item covers only the same name. Port of shopping_list.matches_pantry_item.
 */
data class PantryRule(val name: String, val exactMatch: Boolean) {
    fun covers(ingredientName: String): Boolean {
        val pantryName = name.lowercase(Locale.ROOT)
        val ingredient = ingredientName.lowercase(Locale.ROOT)
        return if (exactMatch) pantryName == ingredient else pantryName in ingredient
    }
}
