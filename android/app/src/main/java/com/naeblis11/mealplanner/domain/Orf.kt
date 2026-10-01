package com.naeblis11.mealplanner.domain

import java.math.BigInteger

/**
 * Open Recipe Format as the Pi reads it: port of recipe_sync.py's parser.
 * Values stay as loose as YAML allows (a name can be a number, notes a bare
 * string), exactly like the Python dicts, so the parity fixtures compare
 * directly.
 */
object Orf {
    private val NONE_TOKENS = setOf("none", "None", "")
    private val MAX_RATING = BigInteger.valueOf(5)

    /** `_clean_none`: the words a hand-written file uses for "no value" become null. */
    fun cleanNone(value: Any?): Any? = if (value is String && value in NONE_TOKENS) null else value

    /** `parse_recipe_yaml`. Throws [RecipeFormatException] for anything the Pi would reject. */
    fun parse(yamlText: String): Map<String, Any?> {
        val loaded = RecipeYaml.load(yamlText)
        val data: Map<Any?, Any?> = if (!Py.truthy(loaded)) emptyMap() else loaded.asYamlMap("A recipe file")
        val missing = listOf("recipe_name", "steps", "ingredients").filter { data[it] == null }
        if (missing.isNotEmpty()) {
            throw RecipeFormatException("Missing required field(s): ${missing.joinToString(", ")}")
        }
        val ovenFan = when (val fan = cleanNone(data["oven_fan"])) {
            true -> "On"
            false -> "Off"
            else -> fan
        }
        return linkedMapOf(
            "name" to data["recipe_name"],
            "recipe_uuid" to cleanNone(data["recipe_uuid"]),
            "author" to cleanNone(data["author"]),
            "source_authors" to normalizeSourceAuthors(data["source_authors"]),
            "source_url" to cleanNone(data["source_url"]),
            "source_book" to cleanNone(data["source_book"]),
            "oven_temp" to cleanNone(data["oven_temp"]),
            "oven_fan" to ovenFan,
            "oven_time" to cleanNone(data["oven_time"]),
            "yields" to normalizeYields(data["yields"]),
            "notes" to cleanNone(data["notes"]),
            "category" to cleanNone(data["category"]),
            "subcategory" to cleanNone(data["subcategory"]),
            "image" to cleanNone(data["image"]),
            "rating" to normalizeRating(data["rating"]),
            "ingredients" to pyIter(data["ingredients"], "ingredients").map { normalizeIngredient(it) },
            "steps" to pyIter(data["steps"], "steps").map { normalizeStep(it) },
        )
    }

    /** Yields in either ORF shape (`{amount, unit}` or `{unit: amount}`) as `{amount, unit}`; null when absent. */
    fun normalizeYields(raw: Any?): List<Map<String, Any?>>? {
        val yields = cleanNone(raw) ?: return null
        return pyIter(yields, "yields").map { normalizeYieldEntry(it) }
    }

    private fun normalizeYieldEntry(entry: Any?): Map<String, Any?> {
        val map = entry.asYamlMap("A yield")
        if (map.containsKey("amount") && map.containsKey("unit")) {
            return linkedMapOf("amount" to map["amount"], "unit" to map["unit"])
        }
        if (map.size != 1) throw RecipeFormatException("A yield needs exactly one unit: amount pair")
        val (unit, amount) = map.entries.single()
        return linkedMapOf("amount" to amount, "unit" to unit)
    }

    /** A star rating 1..5, or null for anything else (missing, blank, a boolean, "3.0", 9). */
    fun normalizeRating(value: Any?): Int? {
        val cleaned = cleanNone(value) ?: return null
        if (cleaned is Boolean) return null
        val rating = Py.parseInt(Py.str(cleaned)) ?: return null
        return if (rating >= BigInteger.ONE && rating <= MAX_RATING) rating.toInt() else null
    }

    /**
     * `unit_conversion.to_imperial` for YAML values: a metric amount becomes
     * US customary text; anything else (including numbers and text that
     * isn't an amount) comes back as the same objects.
     */
    fun toImperial(amount: Any?, unit: Any?): Pair<Any?, Any?> {
        val amountText = amount?.let { Py.str(it) }
        if (Amounts.parseAmount(amountText) == null) return amount to unit
        val unitText = unit?.let { Py.str(it) }
        val converted = Units.toImperial(amountText, unitText)
        return if (converted.first == amountText && converted.second == unitText) amount to unit else converted
    }

    private fun normalizeSourceAuthors(value: Any?): List<Any?>? {
        val cleaned = cleanNone(value) ?: return null
        return if (cleaned is String) listOf(cleaned) else pyIter(cleaned, "source_authors")
    }

    private fun normalizeIngredient(raw: Any?): Map<String, Any?> {
        val (name, rawBody) = singleEntry(raw)
        val body = bodyMap(rawBody)
        val substitutions = body["substitutions"]
        return linkedMapOf(
            "name" to name,
            "usda_num" to cleanNone(body["usda_num"])?.let { Py.str(it) },
            "amounts" to pyIter(body.pyGet("amounts", emptyList<Any?>()), "amounts").map { convertAmount(it) },
            "processing" to body["processing"],
            "notes" to body["notes"],
            "section" to body["section"],
            "substitutions" to if (Py.truthy(substitutions)) {
                pyIter(substitutions, "substitutions").map { normalizeIngredient(it) }
            } else {
                null
            },
        )
    }

    // {**entry, "amount": amount, "unit": unit}: other keys kept, in place.
    private fun convertAmount(entry: Any?): Map<Any?, Any?> {
        val map = entry.asYamlMap("An amount")
        val (amount, unit) = toImperial(map.pyGet("amount", ""), map.pyGet("unit", ""))
        return LinkedHashMap(map).apply {
            put("amount", amount)
            put("unit", unit)
        }
    }

    private fun normalizeStep(raw: Any?): Map<String, Any?> {
        val map = raw.asYamlMap("A step")
        if (!map.containsKey("step")) throw RecipeFormatException("A step needs a 'step' field")
        return linkedMapOf("step_text" to map["step"], "notes" to map["notes"], "haccp" to map["haccp"])
    }

    /** `(name, body), = raw.items()`: an ingredient is a one-entry mapping. */
    fun singleEntry(raw: Any?): Pair<Any?, Any?> {
        val map = raw.asYamlMap("An ingredient")
        if (map.size != 1) throw RecipeFormatException("An ingredient must be a single 'name: details' entry")
        val entry = map.entries.single()
        return entry.key to entry.value
    }

    /** `body = body or {}`, then used as a mapping. */
    fun bodyMap(body: Any?): Map<Any?, Any?> =
        if (!Py.truthy(body)) emptyMap() else body.asYamlMap("An ingredient's details")
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.asYamlMap(what: String): Map<Any?, Any?> =
    this as? Map<Any?, Any?> ?: throw RecipeFormatException("$what must be a mapping")

/** `dict.get(key, default)`: the default only when the key is absent (a present null stays null). */
internal fun Map<Any?, Any?>.pyGet(key: Any?, default: Any?): Any? = if (containsKey(key)) get(key) else default

/** `for x in value`: a list's items, a mapping's keys, a string's characters; anything else is an error. */
internal fun pyIter(value: Any?, what: String): List<Any?> = when (value) {
    is List<*> -> value
    is Map<*, *> -> value.keys.toList()
    is String -> value.map { it.toString() }
    else -> throw RecipeFormatException("$what must be a list")
}

/** `len(value)`. */
internal fun pyLen(value: Any?, what: String): Int = when (value) {
    is Collection<*> -> value.size
    is Map<*, *> -> value.size
    is String -> value.length
    else -> throw RecipeFormatException("$what has no length")
}
