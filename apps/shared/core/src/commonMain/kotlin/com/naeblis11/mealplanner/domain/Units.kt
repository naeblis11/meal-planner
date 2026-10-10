package com.naeblis11.mealplanner.domain

import java.util.Locale

/** One ingredient line as written: name, amount text, unit text. */
data class Line(val name: String, val amount: String?, val unit: String?)

/** Unit normalization and US-customary conversion: port of unit_conversion.py. */
object Units {
    enum class Category(val key: String) { VOLUME("volume"), WEIGHT("weight") }

    private val ALIASES: Map<String, String> = mapOf(
        "tsp" to "tsp", "ts" to "tsp", "teaspoon" to "tsp", "teaspoons" to "tsp",
        "tbsp" to "tbsp", "tb" to "tbsp", "tbs" to "tbsp", "tablespoon" to "tbsp", "tablespoons" to "tbsp",
        "fl oz" to "fl oz", "floz" to "fl oz", "fluid ounce" to "fl oz", "fluid ounces" to "fl oz",
        "cup" to "cup", "cups" to "cup", "c" to "cup",
        "pt" to "pt", "pint" to "pt", "pints" to "pt",
        "qt" to "qt", "quart" to "qt", "quarts" to "qt",
        "gal" to "gal", "ga" to "gal", "gallon" to "gal", "gallons" to "gal",
        "oz" to "oz", "ounce" to "oz", "ounces" to "oz",
        "lb" to "lb", "lbs" to "lb", "pound" to "lb", "pounds" to "lb",
        "ml" to "ml", "milliliter" to "ml", "milliliters" to "ml", "millilitre" to "ml", "millilitres" to "ml",
        "l" to "l", "liter" to "l", "liters" to "l", "litre" to "l", "litres" to "l",
        "g" to "g", "gram" to "g", "grams" to "g",
        "kg" to "kg", "kilogram" to "kg", "kilograms" to "kg", "kilo" to "kg", "kilos" to "kg",
        "each" to "each", "ea" to "each",
        "clove" to "clove", "cl" to "clove", "cloves" to "clove",
        "slice" to "slice", "sl" to "slice", "slices" to "slice",
        "can" to "can", "cn" to "can", "cans" to "can",
        "package" to "package", "pk" to "package", "pkg" to "package", "packages" to "package",
        "pinch" to "pinch", "pn" to "pinch", "pinches" to "pinch",
        "dash" to "dash", "dr" to "dash", "dashes" to "dash",
        "small" to "small", "sm" to "small",
        "medium" to "medium", "md" to "medium",
        "large" to "large", "lg" to "large",
    )

    private val VOLUME_FACTORS: Map<String, Long> =
        linkedMapOf("tsp" to 1, "tbsp" to 3, "fl oz" to 6, "cup" to 48, "pt" to 96, "qt" to 192, "gal" to 768)
    private val WEIGHT_FACTORS: Map<String, Long> = linkedMapOf("oz" to 1, "lb" to 16)

    /** Units that take a trailing "s" plural; all other measurable units stay invariant. */
    internal val UNITS_WITH_S_PLURAL = setOf("cup")

    // Metric unit -> (category, how many base units one of it is):
    // 1 tsp = 5 ml, 1 l = 200 tsp; 1 oz = 28 g, 1 kg = 1000/28 oz.
    private val METRIC_TO_BASE: Map<String, Pair<Category, Fraction>> = mapOf(
        "ml" to (Category.VOLUME to Fraction.of(1, 5)),
        "l" to (Category.VOLUME to Fraction.of(200)),
        "g" to (Category.WEIGHT to Fraction.of(1, 28)),
        "kg" to (Category.WEIGHT to Fraction.of(1000, 28)),
    )

    fun normalizeUnit(text: String?): String {
        val cleaned = text?.trim()?.lowercase(Locale.ROOT) ?: ""
        return ALIASES[cleaned] ?: cleaned
    }

    fun isKnownUnitWord(word: String): Boolean = word.trim().lowercase(Locale.ROOT) in ALIASES

    fun unitCategory(unit: String): Category? = when (unit) {
        in VOLUME_FACTORS -> Category.VOLUME
        in WEIGHT_FACTORS -> Category.WEIGHT
        else -> null
    }

    private fun factorsFor(category: Category) =
        if (category == Category.VOLUME) VOLUME_FACTORS else WEIGHT_FACTORS

    internal fun toBase(category: Category, unit: String, amount: Fraction): Fraction =
        amount * Fraction.of(factorsFor(category).getValue(unit))

    /** The largest unit that keeps the amount at least 1, else the smallest unit. */
    fun bestUnit(category: Category, baseAmount: Fraction): Pair<String, Fraction> {
        val factors = factorsFor(category)
        for ((unit, factor) in factors.entries.sortedByDescending { it.value }) {
            val f = Fraction.of(factor)
            if (baseAmount >= f) return unit to baseAmount / f
        }
        val (smallest, factor) = factors.entries.minBy { it.value }
        return smallest to baseAmount / Fraction.of(factor)
    }

    /** Metric amounts become US customary; everything else comes back unchanged. */
    fun toImperial(amountText: String?, unitText: String?): Pair<String?, String?> {
        val amount = Amounts.parseAmount(amountText) ?: return amountText to unitText
        val (category, perUnit) = METRIC_TO_BASE[normalizeUnit(unitText)] ?: return amountText to unitText
        val (displayUnit, displayAmount) = bestUnit(category, amount * perUnit)
        return Amounts.formatAmount(displayAmount) to displayUnit
    }

    private class Entry(
        val parsed: Fraction?,
        val amountText: String?,
        val unitText: String?,
        val unit: String,
        val category: Category?,
    )

    /**
     * Merges lines for the same ingredient (case-insensitive; the first-seen
     * casing is kept). Amounts combine within one unit category -- volume or
     * weight -- or, for units like "clove", within the exact normalized unit.
     * Amounts that don't parse are never merged into anything, only
     * de-duplicated.
     */
    fun combineLines(rows: List<Line>): List<Line> {
        val groups = LinkedHashMap<String, Pair<String, LinkedHashMap<String, MutableList<Entry>>>>()
        for (row in rows) {
            val group = groups.getOrPut(row.name.lowercase(Locale.ROOT)) { row.name to LinkedHashMap() }
            val unit = normalizeUnit(row.unit)
            val category = unitCategory(unit)
            // Same keys as the Python version: "volume"/"weight", else the unit itself.
            val bucketKey = category?.key ?: unit
            group.second.getOrPut(bucketKey) { mutableListOf() }
                .add(Entry(Amounts.parseAmount(row.amount), row.amount, row.unit, unit, category))
        }

        val result = mutableListOf<Line>()
        for ((name, buckets) in groups.values) {
            for (entries in buckets.values) {
                val seenUnparseable = HashSet<Pair<String?, String?>>()
                for (e in entries.filter { it.parsed == null }) {
                    if (seenUnparseable.add(e.amountText to e.unitText)) {
                        result += Line(name, e.amountText, e.unitText)
                    }
                }

                val parseable = entries.filter { it.parsed != null }
                if (parseable.isEmpty()) continue
                if (parseable.size == 1) {
                    result += Line(name, parseable[0].amountText, parseable[0].unitText)
                    continue
                }

                val category = parseable[0].category
                if (category != null) {
                    val unitsUsed = parseable.map { it.unit }.toSet()
                    if (unitsUsed.size == 1) {
                        // All one unit: sum directly instead of converting, which
                        // would promote e.g. 3 cups to 1 1/2 pt.
                        val unit = unitsUsed.single()
                        val total = parseable.fold(Fraction.ZERO) { acc, e -> acc + e.parsed!! }
                        val displayUnit =
                            if (total == Fraction.ONE || unit !in UNITS_WITH_S_PLURAL) unit else unit + "s"
                        result += Line(name, Amounts.formatAmount(total), displayUnit)
                    } else {
                        val baseTotal = parseable.fold(Fraction.ZERO) { acc, e ->
                            acc + toBase(category, e.unit, e.parsed!!)
                        }
                        val (displayUnit, displayAmount) = bestUnit(category, baseTotal)
                        result += Line(name, Amounts.formatAmount(displayAmount), displayUnit)
                    }
                } else {
                    val total = parseable.fold(Fraction.ZERO) { acc, e -> acc + e.parsed!! }
                    result += Line(name, Amounts.formatAmount(total), parseable[0].unitText)
                }
            }
        }
        return result
    }
}
