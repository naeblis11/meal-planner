package com.naeblis11.mealplanner.domain

import java.util.Locale

data class MealMasterRecipe(val title: String, val data: YamlMap)

/** Parsed recipes, plus (recipe identifier, reason) for each chunk that couldn't be read. */
data class MealMasterResult(val recipes: List<MealMasterRecipe>, val errors: List<Pair<String, String>>)

/** Meal Master (.mmf) text into Open Recipe Format data: port of meal_master.py. */
object MealMaster {
    // (?d): only \n ends a line for ^, as in Python's re.MULTILINE.
    private val RECIPE_START = Regex("(?md)^MMMMM-+")
    private val UNITS = setOf(
        "ts", "tb", "c", "oz", "lb", "pt", "qt", "ga", "ml", "l", "g", "kg",
        "ea", "x", "sm", "md", "lg", "pk", "cn", "sl", "cl", "dr", "pn", "fl",
    )
    private val AMOUNT = Regex("^((?:${Py.RE_DIGIT}+${Py.RE_SPACE}+)?${Py.RE_DIGIT}+(?:/${Py.RE_DIGIT}+)?)${Py.RE_SPACE}*(.*)$")
    private val SECTION_HEADER = Regex("^=+${Py.RE_SPACE}*(.+?)${Py.RE_SPACE}*=+$")
    private val STOP_MARKERS = setOf("Source:", "Notes:", "Yield:")

    /** Meal Master files are Windows-1252 text. */
    fun decode(bytes: ByteArray): String = Py.decodeCp1252(bytes)

    fun parse(text: String): MealMasterResult {
        val recipes = mutableListOf<MealMasterRecipe>()
        val errors = mutableListOf<Pair<String, String>>()
        splitChunks(text).forEachIndexed { i, chunk ->
            val id = chunkIdentifier(chunk, i)
            try {
                val data = parseChunk(chunk)
                recipes += MealMasterRecipe(data["recipe_name"] as String, data)
            } catch (e: RecipeFormatException) {
                errors += id to (e.message ?: "")
            }
        }
        return MealMasterResult(recipes, errors)
    }

    private fun splitChunks(text: String): List<String> {
        val positions = RECIPE_START.findAll(text).map { it.range.first }.toMutableList()
        if (positions.isEmpty()) return emptyList()
        positions += text.length
        return positions.zipWithNext { start, end -> text.substring(start, end) }
    }

    private fun chunkIdentifier(chunk: String, index: Int): String {
        for (line in Py.splitLines(chunk)) {
            val stripped = Py.strip(line)
            if (stripped.isNotEmpty() && !stripped.startsWith("MMMMM")) return stripped.take(60)
        }
        return "recipe #${index + 1}"
    }

    private fun findFieldLineIndex(lines: List<String>, label: String): Int = lines.indexOfFirst { line ->
        val stripped = Py.strip(line)
        stripped.startsWith(label) && Py.strip(stripped.substring(label.length)).isNotEmpty()
    }

    private fun findField(lines: List<String>, label: String): String {
        val index = findFieldLineIndex(lines, label)
        return if (index < 0) "" else Py.strip(Py.strip(lines[index]).substring(label.length))
    }

    private fun extractIngredientLines(lines: List<String>, titleIndex: Int): Pair<List<String>, Int> {
        var i = titleIndex + 1
        while (i < lines.size) {
            val stripped = Py.strip(lines[i])
            if (stripped.isEmpty() || stripped.startsWith("Categories:") || stripped.startsWith("Yield:")) {
                i++
                continue
            }
            break
        }
        val ingredientLines = mutableListOf<String>()
        while (i < lines.size) {
            val line = lines[i]
            if (Py.strip(line).isEmpty()) {
                i++
                continue
            }
            if (!Py.isSpace(line[0])) break
            ingredientLines += line
            i++
        }
        return ingredientLines to i
    }

    private fun joinContinuations(ingredientLines: List<String>): List<String> {
        val joined = mutableListOf<String>()
        for (line in ingredientLines) {
            val stripped = Py.strip(line)
            if (stripped.startsWith("-")) {
                if (joined.isNotEmpty()) joined[joined.size - 1] = joined.last() + Py.lstrip(stripped.substring(1))
                continue
            }
            joined += stripped
        }
        return joined
    }

    private fun parseIngredientLine(line: String): YamlMap {
        val match = AMOUNT.find(line)
        val amount = match?.groupValues?.get(1) ?: ""
        var remainder = match?.groupValues?.get(2) ?: line
        val tokens = Py.split(remainder, 1)
        var unit = ""
        if (tokens.isNotEmpty() && tokens[0].lowercase(Locale.ROOT) in UNITS) {
            unit = tokens[0]
            remainder = if (tokens.size > 1) tokens[1] else ""
        }
        val (name, note) = if (" -- " in remainder) {
            remainder.split(" -- ", limit = 2).let { it[0] to it[1] }
        } else {
            remainder to ""
        }
        val body = linkedMapOf<Any?, Any?>(
            "amounts" to mutableListOf<Any?>(linkedMapOf<Any?, Any?>("amount" to amount, "unit" to unit)),
        )
        if (Py.strip(note).isNotEmpty()) body["notes"] = mutableListOf<Any?>(Py.strip(note))
        return linkedMapOf(Py.strip(name) to body)
    }

    private fun parseIngredients(ingredientLines: List<String>): List<YamlMap> {
        val ingredients = mutableListOf<YamlMap>()
        var section: String? = null
        for (line in joinContinuations(ingredientLines)) {
            if (line.isEmpty()) continue
            val header = SECTION_HEADER.find(line)
            if (header != null) {
                section = Py.title(Py.strip(header.groupValues[1]))
                continue
            }
            val ingredient = parseIngredientLine(line)
            if (section != null) {
                @Suppress("UNCHECKED_CAST")
                (ingredient.values.single() as YamlMap)["section"] = section
            }
            ingredients += ingredient
        }
        return ingredients
    }

    private fun extractInstructions(lines: List<String>, start: Int): Pair<List<YamlMap>, Int> {
        var i = start
        val bodyLines = mutableListOf<String>()
        while (i < lines.size) {
            val stripped = Py.strip(lines[i])
            if (stripped in STOP_MARKERS || stripped.startsWith("S(\"") || stripped.startsWith("MMMMM")) break
            bodyLines += lines[i]
            i++
        }
        val steps = mutableListOf<YamlMap>()
        val paragraph = mutableListOf<String>()
        for (line in bodyLines) {
            if (Py.strip(line).isEmpty()) {
                if (paragraph.isNotEmpty()) {
                    steps += linkedMapOf<Any?, Any?>("step" to paragraph.joinToString(" "))
                    paragraph.clear()
                }
                continue
            }
            paragraph += Py.strip(line)
        }
        if (paragraph.isNotEmpty()) steps += linkedMapOf<Any?, Any?>("step" to paragraph.joinToString(" "))
        return steps to i
    }

    private fun readQuotedBlock(lines: List<String>, start: Int): Pair<String, Int> {
        val collected = mutableListOf<String>()
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            collected += Py.strip(line)
            i++
            if (Py.rstrip(line).endsWith("\"")) break
        }
        var text = Py.strip(collected.filter { it.isNotEmpty() }.joinToString(" "))
        if (text.startsWith("\"")) text = text.substring(1)
        if (text.endsWith("\"")) text = text.dropLast(1)
        return Py.strip(text) to i
    }

    private class Tail(val source: String, val realYield: String, val extraNotes: List<String>, val cuisine: String)

    private fun parseTailFields(lines: List<String>, start: Int): Tail {
        var source = ""
        var realYield = ""
        val extraNotes = mutableListOf<String>()
        var cuisine = ""
        var i = start
        while (i < lines.size) {
            val stripped = Py.strip(lines[i])
            when {
                stripped == "Source:" -> readQuotedBlock(lines, i + 1).let { (text, next) -> source = text; i = next }
                stripped == "Notes:" -> readQuotedBlock(lines, i + 1).let { (text, next) ->
                    if (text.isNotEmpty()) extraNotes += text
                    i = next
                }
                stripped == "Yield:" -> readQuotedBlock(lines, i + 1).let { (text, next) -> realYield = text; i = next }
                stripped.startsWith("S(\"") -> readQuotedBlock(lines, i + 1).let { (text, next) -> cuisine = text; i = next }
                else -> i++
            }
        }
        return Tail(source, realYield, extraNotes, cuisine)
    }

    private fun parseChunk(chunk: String): YamlMap {
        val lines = Py.splitLines(chunk)
        val titleIndex = findFieldLineIndex(lines, "Title:")
        if (titleIndex < 0) throw RecipeFormatException("Missing Title: line")
        val title = Py.strip(Py.strip(lines[titleIndex]).substring("Title:".length))
        val categories = findField(lines, "Categories:")
        val headerYield = findField(lines, "Yield:")

        val (ingredientLines, bodyStart) = extractIngredientLines(lines, titleIndex)
        val ingredients = parseIngredients(ingredientLines)
        if (ingredients.isEmpty()) throw RecipeFormatException("No ingredients found")
        val (steps, tailStart) = extractInstructions(lines, bodyStart)
        if (steps.isEmpty()) throw RecipeFormatException("No instructions found")
        val tail = parseTailFields(lines, tailStart)

        val notes = mutableListOf<Any?>()
        if (tail.source.isNotEmpty()) notes += "Source: ${tail.source}"
        notes.addAll(tail.extraNotes)
        if (tail.cuisine.isNotEmpty()) notes += "Cuisine: ${tail.cuisine}"
        val yields = OrfEditing.parseYieldText(tail.realYield) ?: OrfEditing.parseYieldText(headerYield)

        val data = linkedMapOf<Any?, Any?>(
            "recipe_uuid" to "None",
            "recipe_name" to title,
            "category" to "None",
            "subcategory" to categories.ifEmpty { "None" },
            "ingredients" to ingredients,
            "steps" to steps,
        )
        if (yields != null) data["yields"] = mutableListOf<Any?>(LinkedHashMap<Any?, Any?>(yields))
        if (notes.isNotEmpty()) data["notes"] = notes
        return data
    }
}
