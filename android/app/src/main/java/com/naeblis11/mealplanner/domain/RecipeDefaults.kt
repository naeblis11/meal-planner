package com.naeblis11.mealplanner.domain

/** The web app's suggestion lists (app.py DEFAULT_CATEGORIES / DEFAULT_SUBCATEGORIES / DEFAULT_UNITS). */
object RecipeDefaults {
    const val UNCATEGORIZED = "Uncategorized"

    val CATEGORIES: List<String> = listOf(
        "Appetizers", "Soups & Stews", "Salads", "Main Dishes", "Side Dishes",
        "Breads & Baking", "Desserts", "Beverages", "Sauces & Condiments", "Breakfast",
    )

    val SUBCATEGORIES: List<String> = listOf("Beef", "Pork", "Chicken", "Turkey", "Seafood", "Vegetarian")

    val UNITS: List<String> = listOf(
        "tsp", "tbsp", "fl oz", "cup", "pt", "qt", "gal", "oz", "lb",
        "each", "clove", "slice", "can", "package", "pinch", "dash",
        "small", "medium", "large",
    )
}
