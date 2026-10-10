package com.naeblis11.mealplanner.domain

import java.util.Locale

/**
 * What every category editor (the edit page, the recipe page's Category form, the import review)
 * suggests: the built-in lists ([RecipeDefaults]) first, in their order, then every other
 * category and subcategory the library already uses, alphabetically (owner, 2026-10-10).
 * Matching ignores case, and a built-in spelling beats the library's.
 */
class CategoryOptions private constructor(
    val categories: List<String>,
    val subcategories: List<String>,
    // Lower-cased category -> the subcategories its recipes use, alphabetically.
    private val usedUnder: Map<String, List<String>>,
) {
    /**
     * The subcategory suggestions for [category]: the ones its recipes already use, then the rest,
     * so Desserts offers Cookies before Beef.
     */
    fun subcategoriesFor(category: String): List<String> {
        val used = usedUnder[key(category)].orEmpty()
        if (used.isEmpty()) return subcategories
        return used + subcategories.filterNot { s -> used.any { it.equals(s, ignoreCase = true) } }
    }

    companion object {
        val DEFAULT: CategoryOptions = from(emptyList())

        /** Built from the library's (category, subcategory) pairs; blanks, "None" and Uncategorized are left out. */
        fun from(used: List<Pair<String?, String?>>): CategoryOptions {
            val pairs = used.map { (c, s) -> clean(c) to clean(s) }
            val usedUnder = LinkedHashMap<String, MutableList<String>>()
            for ((c, s) in pairs) {
                if (c == null || s == null) continue
                val list = usedUnder.getOrPut(key(c)) { mutableListOf() }
                if (list.none { it.equals(s, ignoreCase = true) }) list += s
            }
            return CategoryOptions(
                categories = merged(RecipeDefaults.CATEGORIES, pairs.mapNotNull { it.first }),
                subcategories = merged(RecipeDefaults.SUBCATEGORIES, pairs.mapNotNull { it.second }),
                usedUnder = usedUnder.mapValues { (_, subs) -> subs.sortedWith(String.CASE_INSENSITIVE_ORDER) },
            )
        }

        private fun merged(defaults: List<String>, used: List<String>): List<String> {
            val seen = defaults.mapTo(HashSet()) { key(it) }
            val extra = used.filter { seen.add(key(it)) }.sortedWith(String.CASE_INSENSITIVE_ORDER)
            return defaults + extra
        }

        private fun clean(value: String?): String? = value?.trim()?.takeUnless {
            it.isEmpty() || it.equals("None", ignoreCase = true) || it.equals(RecipeDefaults.UNCATEGORIZED, ignoreCase = true)
        }

        private fun key(value: String): String = value.trim().lowercase(Locale.ROOT)
    }
}
