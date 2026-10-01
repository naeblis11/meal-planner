package com.naeblis11.mealplanner.domain

data class SubcategoryGroup<T>(val subcategory: String, val recipes: List<T>)

/** One category on the library page: recipes with no subcategory, then each subcategory's recipes. */
data class CategoryGroup<T>(val category: String, val subcategories: List<SubcategoryGroup<T>>, val recipes: List<T>)

/** The library page's grouping: port of app.py `_group_recipes_by_category`. */
object RecipeGrouping {
    fun <T> group(
        items: List<T>,
        name: (T) -> String,
        category: (T) -> String?,
        subcategory: (T) -> String?,
    ): List<CategoryGroup<T>> {
        val byCategory = LinkedHashMap<String, LinkedHashMap<String, MutableList<T>>>()
        for (item in items) {
            val cat = category(item)?.takeIf { it.isNotEmpty() } ?: RecipeDefaults.UNCATEGORIZED
            val sub = subcategory(item) ?: ""
            byCategory.getOrPut(cat) { LinkedHashMap() }.getOrPut(sub) { mutableListOf() }.add(item)
        }
        val ordered = RecipeDefaults.CATEGORIES.filter { it in byCategory } +
            byCategory.keys.filter { it !in RecipeDefaults.CATEGORIES && it != RecipeDefaults.UNCATEGORIZED }.sorted() +
            listOfNotNull(RecipeDefaults.UNCATEGORIZED.takeIf { it in byCategory })
        return ordered.map { cat ->
            val subs = byCategory.getValue(cat)
            CategoryGroup(
                category = cat,
                subcategories = subs.keys.filter { it.isNotEmpty() }.sorted()
                    .map { SubcategoryGroup(it, subs.getValue(it).sortedBy(name)) },
                recipes = subs[""].orEmpty().sortedBy(name),
            )
        }
    }
}
