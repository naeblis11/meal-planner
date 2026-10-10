package com.naeblis11.mealplanner.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeGroupingTest {
    private data class R(val name: String, val category: String?, val subcategory: String?)

    private fun group(vararg items: R) = RecipeGrouping.group(items.toList(), { it.name }, { it.category }, { it.subcategory })

    @Test
    fun defaultCategoriesComeFirstInTheirOrderThenCustomSortedThenUncategorized() {
        val groups = group(
            R("Zeppole", "Desserts", null),
            R("Mystery", null, null),
            R("Pho", "Soups & Stews", null),
            R("Bao", "Zzz Custom", null),
            R("Toast", "", null),
            R("Kimchi", "A Custom", null),
        )
        assertEquals(listOf("Soups & Stews", "Desserts", "A Custom", "Zzz Custom", "Uncategorized"), groups.map { it.category })
        assertEquals(listOf("Mystery", "Toast"), groups.last().recipes.map { it.name })
    }

    @Test
    fun subcategoriesAreSortedAndRecipesWithoutOneSitDirectlyUnderTheCategory() {
        val groups = group(
            R("Stir fry", "Main Dishes", "Pork"),
            R("Roast", "Main Dishes", "Beef"),
            R("Burger", "Main Dishes", "Beef"),
            R("Lasagna", "Main Dishes", null),
            R("Bake", "Main Dishes", ""),
        )
        val main = groups.single()
        assertEquals(listOf("Bake", "Lasagna"), main.recipes.map { it.name })
        assertEquals(listOf("Beef", "Pork"), main.subcategories.map { it.subcategory })
        assertEquals(listOf("Burger", "Roast"), main.subcategories[0].recipes.map { it.name })
    }

    @Test
    fun defaultsMatchTheWebApp() {
        assertEquals(10, RecipeDefaults.CATEGORIES.size)
        assertEquals("Appetizers", RecipeDefaults.CATEGORIES.first())
        assertEquals(listOf("Beef", "Pork", "Chicken", "Turkey", "Seafood", "Vegetarian"), RecipeDefaults.SUBCATEGORIES)
        assertEquals("large", RecipeDefaults.UNITS.last())
    }
}
