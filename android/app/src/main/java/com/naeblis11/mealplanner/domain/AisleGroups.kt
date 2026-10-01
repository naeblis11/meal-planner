package com.naeblis11.mealplanner.domain

import java.util.Locale

data class AisleGroup<T>(val aisle: String, val items: List<T>)

/**
 * app.py `_group_by_aisle`: the store's aisle order, then any other aisles A-Z, then
 * Uncategorized (where a blank aisle goes); items A-Z, ignoring case, within each.
 */
object AisleGroups {
    fun <T> group(items: List<T>, aisle: (T) -> String?, name: (T) -> String): List<AisleGroup<T>> {
        val byAisle = LinkedHashMap<String, MutableList<T>>()
        for (item in items) {
            val key = aisle(item)?.takeIf { it.isNotEmpty() } ?: GroceryCategories.UNCATEGORIZED
            byAisle.getOrPut(key) { mutableListOf() } += item
        }
        val ordered = GroceryCategories.AISLE_ORDER.filter { it in byAisle } +
            byAisle.keys.filter { it !in GroceryCategories.AISLE_ORDER && it != GroceryCategories.UNCATEGORIZED }.sorted() +
            listOfNotNull(GroceryCategories.UNCATEGORIZED.takeIf { it in byAisle })
        return ordered.map { key -> AisleGroup(key, byAisle.getValue(key).sortedBy { name(it).lowercase(Locale.ROOT) }) }
    }
}
