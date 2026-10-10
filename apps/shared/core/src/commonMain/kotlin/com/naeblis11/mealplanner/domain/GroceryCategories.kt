package com.naeblis11.mealplanner.domain

import java.util.Locale

/**
 * Best-guess grocery aisle for an ingredient: port of grocery_categories.py.
 * Only a fallback -- a remembered aisle for that ingredient always wins.
 */
object GroceryCategories {
    const val UNCATEGORIZED = "Uncategorized"

    val AISLE_ORDER: List<String> = listOf(
        "Produce", "Dairy & Eggs", "Meat & Seafood", "Frozen", "Bakery",
        "Dry Goods & Pasta", "Canned & Jarred", "Condiments & Sauces",
        "Spices & Baking", "Beverages", "Snacks", "Household",
    )

    private val KEYWORD_CATEGORIES: List<Pair<String, List<String>>> = listOf(
        "Produce" to listOf(
            "onion", "garlic", "tomato", "potato", "carrot", "celery", "lettuce",
            "spinach", "kale", "broccoli", "cauliflower", "cucumber", "zucchini",
            "squash", "mushroom", "avocado", "lemon", "lime", "apple", "banana",
            "berry", "grape", "orange", "peach", "pear", "melon", "cilantro",
            "parsley", "basil", "mint", "scallion", "shallot", "ginger", "cabbage",
            "corn", "eggplant", "asparagus", "bell pepper", "jalapeno", "poblano",
        ),
        "Dairy & Eggs" to listOf(
            "milk", "cream", "butter", "cheese", "yogurt", "egg", "buttermilk",
            "sour cream", "half and half",
        ),
        "Meat & Seafood" to listOf(
            "chicken", "beef", "pork", "turkey", "bacon", "sausage", "ham",
            "steak", "shrimp", "salmon", "tuna", "fish", "lamb", "veal", "crab",
            "scallop",
        ),
        "Frozen" to listOf("frozen", "ice cream"),
        "Bakery" to listOf(
            "bread", "bun", "roll", "tortilla", "bagel", "pita", "baguette", "croissant",
        ),
        "Dry Goods & Pasta" to listOf(
            "flour", "sugar", "rice", "pasta", "noodle", "oats", "cereal", "cornstarch",
        ),
        "Canned & Jarred" to listOf(
            "broth", "stock", "bean", "coconut milk", "tomato sauce", "tomato paste",
        ),
        "Condiments & Sauces" to listOf(
            "vinegar", "mustard", "ketchup", "mayonnaise", "soy sauce",
            "olive oil", "vegetable oil", "honey", "syrup",
        ),
        "Spices & Baking" to listOf(
            "salt", "cinnamon", "cumin", "paprika", "oregano", "thyme",
            "rosemary", "nutmeg", "vanilla", "black pepper", "baking soda",
            "baking powder", "cocoa", "yeast",
        ),
        "Beverages" to listOf("juice", "soda", "coffee", "tea", "wine", "beer"),
        "Snacks" to listOf("chip", "cracker", "pretzel", "chocolate"),
        "Household" to listOf(
            "foil", "plastic wrap", "paper towel", "toilet paper", "dish soap",
            "trash bag", "sponge", "napkin", "detergent",
        ),
    )

    /** Whole-word phrase patterns for the multi-word keywords, built once. */
    private val PHRASES: Map<String, Regex> = KEYWORD_CATEGORIES
        .flatMap { it.second }
        .filter { ' ' in it }
        .associateWith { Regex("\\b" + Regex.escape(it) + "\\b") }

    private val WORD = Regex("[a-z]+")

    /** Lowercase words plus naive singulars, so "eggs"/"tomatoes" match "egg"/"tomato". */
    private fun normalizedTokens(name: String): Set<String> {
        val tokens = WORD.findAll(name.lowercase(Locale.ROOT)).map { it.value }.toList()
        val normalized = tokens.toMutableSet()
        for (token in tokens) {
            if (token.endsWith("ies") && token.length > 3) {
                normalized += token.dropLast(3) + "y"
            } else if (token.endsWith("es") && token.length > 2) {
                normalized += token.dropLast(2)
            }
            if (token.endsWith("s") && token.length > 1) normalized += token.dropLast(1)
        }
        return normalized
    }

    /**
     * Single-word keywords match whole normalized tokens ("corn" doesn't fire
     * on "cornstarch"); multi-word keywords match as a whole-word phrase.
     */
    fun categorize(ingredientName: String): String {
        val name = ingredientName.lowercase(Locale.ROOT)
        val tokens = normalizedTokens(name)
        for ((category, keywords) in KEYWORD_CATEGORIES) {
            for (keyword in keywords) {
                if (' ' in keyword) {
                    if (PHRASES.getValue(keyword).containsMatchIn(name)) return category
                } else if (keyword in tokens) {
                    return category
                }
            }
        }
        return UNCATEGORIZED
    }
}
