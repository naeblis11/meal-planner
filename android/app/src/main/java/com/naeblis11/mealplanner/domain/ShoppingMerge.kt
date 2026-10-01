package com.naeblis11.mealplanner.domain

import java.util.Locale

/**
 * "Add this week" for the shopping list as pure logic: port of
 * shopping_list.generate(). The caller loads the week's planned meals, the
 * pantry, remembered aisles and the current list, then saves what comes back.
 */
object ShoppingMerge {
    /**
     * One planned meal: its servings (null = the recipe's own yield) and the recipe's lines.
     * [yieldAmount] is the recipe's first yield amount as text, formatted as
     * Python's `str()` would (e.g. YAML `4` becomes `"4"`).
     */
    data class PlannedMeal(val servings: String?, val yieldAmount: String?, val ingredients: List<Line>)

    /** One shopping-list row; [id] is null for a row this pass adds. */
    data class ListRow(
        val id: Long?,
        val name: String,
        val amount: String?,
        val unit: String?,
        val aisle: String?,
        val inPantry: Boolean,
        val checked: Boolean,
    )

    /** Scale for one planned meal: its servings over the recipe's first yield, else 1. */
    fun plannedRatio(servings: String?, yieldAmount: String?): Fraction =
        if (servings.isNullOrEmpty() || yieldAmount == null) Fraction.ONE
        else Amounts.servingsRatio(servings, yieldAmount)

    /**
     * [row]'s amount and unit after folding in another line of the same
     * ingredient, or null when they can't combine (cups vs pounds). An
     * amountless side (a hand-added "olive oil") takes the other's amount.
     */
    fun mergeAmounts(row: ListRow, amount: String?, unit: String?): Pair<String?, String?>? {
        if (row.amount == null) return amount to unit
        if (amount == null) return row.amount to row.unit
        val combined = Units.combineLines(listOf(Line(row.name, row.amount, row.unit), Line(row.name, amount, unit)))
        return if (combined.size == 1) combined[0].amount to combined[0].unit else null
    }

    /**
     * The whole list after adding [meals]. Additive: rows are only ever
     * updated or added, never removed. A generated line folds into the first
     * same-name row it can combine with -- which becomes unchecked, since
     * what needs buying changed -- or else becomes a new row.
     *
     * Comparisons read [existing] as passed in (a snapshot), exactly as the
     * Python version reads the table once before writing. [pantry] must hold
     * only items on hand: a crossed-out item is one that needs buying.
     *
     * [existing] must be in row-id order (merges go into the first matching
     * row). Returned rows with `id == null` are inserts; the others are updates.
     */
    fun addWeek(
        existing: List<ListRow>,
        meals: List<PlannedMeal>,
        pantry: List<PantryRule>,
        knownAisles: Map<String, String>,
    ): List<ListRow> {
        val known = knownAisles.mapKeys { it.key.lowercase(Locale.ROOT) }
        // One entry per planned meal, not per recipe: a dish planned twice is bought twice.
        val lines = Units.combineLines(
            meals.flatMap { meal ->
                val ratio = plannedRatio(meal.servings, meal.yieldAmount)
                meal.ingredients.map { Line(it.name, Amounts.scaleAmountText(it.amount, ratio), it.unit) }
            },
        )

        val result = existing.toMutableList()
        for (line in lines) {
            val inPantry = pantry.any { it.covers(line.name) }
            // A remembered manual correction always beats the keyword guess.
            val aisle = known[line.name.lowercase(Locale.ROOT)]?.takeIf { it.isNotEmpty() }
                ?: GroceryCategories.categorize(line.name)

            var merged = false
            // Read the rows as merged so far, so a later line of the same ingredient sees
            // what an earlier one wrote (an amountless row that took "2 cups" can't then
            // take "1 lb" as if it were still amountless). Only existing rows take merges.
            for (i in existing.indices) {
                val row = result[i]
                if (row.name.lowercase(Locale.ROOT) != line.name.lowercase(Locale.ROOT)) continue
                val amounts = mergeAmounts(row, line.amount, line.unit) ?: continue
                result[i] = row.copy(
                    amount = amounts.first,
                    unit = amounts.second,
                    aisle = row.aisle?.takeIf { it.isNotEmpty() } ?: aisle,
                    inPantry = inPantry,
                    checked = false,
                )
                merged = true
                break
            }
            if (!merged) {
                result += ListRow(null, line.name, line.amount, line.unit, aisle, inPantry, checked = false)
            }
        }
        return result
    }
}
