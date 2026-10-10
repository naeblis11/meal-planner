package com.naeblis11.mealplanner.domain

import java.math.BigInteger
import java.util.Locale

/** One entry in an amounts list, wherever it sits (an ingredient or a substitution). */
data class AmountSlot(val ingredientName: Any?, val amount: Any?, val notes: Any?)

/** An amount the parser couldn't read, for the "needs your attention" highlight. */
data class AmountIssue(
    val slotIndex: Int,
    val ingredientName: Any?,
    val amount: Any?,
    val unit: Any?,
    val ingredientLine: String,
)

/** A top-level ingredient as the editor shows it. */
data class EditableIngredient(
    val index: Int,
    val name: Any?,
    val amount: Any?,
    val unit: Any?,
    val section: Any?,
    val notes: List<String>,
    val multiAmount: Boolean,
    val needsInput: Boolean,
)

/** One row of the ingredient editor: a sub-recipe heading or an ingredient. */
data class EditorRow(
    val kind: Kind,
    val key: String,
    val name: Any?,
    val amount: Any?,
    val unit: Any?,
    val notes: String,
    val locked: Boolean,
    val needsInput: Boolean,
) {
    enum class Kind { SECTION, INGREDIENT }
}

/**
 * An editor row as submitted. Keys `e<n>` address the n-th original
 * ingredient; any other key is a new row. [notes] null means the row had no
 * notes field, so the ingredient keeps the notes it had.
 */
data class SubmittedRow(
    val key: String,
    val kind: String,
    val name: String,
    val amount: String = "",
    val unit: String = "",
    val notes: String? = null,
) {
    companion object {
        const val SECTION = "section"
        const val INGREDIENT = "ingredient"
    }
}

/** A step editor row as submitted; `e<n>` keys keep that original step's notes and HACCP. */
data class SubmittedStep(val key: String, val text: String)

/**
 * Editing a recipe's YAML map in place: ports of recipe_sync.py's editing
 * helpers and app.py's `_editor_rows`, `_ingredients_from_form`,
 * `_steps_from_form`, `_slugify` and `_unique_recipe_path`.
 */
object OrfEditing {
    private val YIELD_NUM = Regex("^(${Py.RE_DIGIT}+)${Py.RE_SPACE}*(.*)$")
    private val NON_SLUG = Regex("[^a-z0-9]+")

    /** Every amounts entry across the ingredients and their (nested) substitutions, depth first. */
    fun walkAmountSlots(doc: Map<Any?, Any?>): List<AmountSlot> {
        val slots = mutableListOf<AmountSlot>()
        fun walk(ingredient: Any?) {
            val (name, rawBody) = Orf.singleEntry(ingredient)
            val body = Orf.bodyMap(rawBody)
            val notes = body["notes"]
            val amounts = body["amounts"]
            if (Py.truthy(amounts)) for (amount in pyIter(amounts, "amounts")) slots += AmountSlot(name, amount, notes)
            val substitutions = body["substitutions"]
            if (Py.truthy(substitutions)) for (sub in pyIter(substitutions, "substitutions")) walk(sub)
        }
        val ingredients = doc["ingredients"]
        if (Py.truthy(ingredients)) for (ingredient in pyIter(ingredients, "ingredients")) walk(ingredient)
        return slots
    }

    fun findUnparseableAmountSlots(doc: Map<Any?, Any?>): List<AmountIssue> =
        walkAmountSlots(doc).withIndex().mapNotNull { (i, slot) ->
            val amount = slot.amount.asYamlMap("An amount")
            if (Amounts.parseAmount(amount["amount"]?.let { Py.str(it) }) != null) return@mapNotNull null
            AmountIssue(
                slotIndex = i,
                ingredientName = slot.ingredientName,
                amount = amount.pyGet("amount", ""),
                unit = amount.pyGet("unit", ""),
                ingredientLine = ingredientLine(slot.ingredientName, amount, slot.notes),
            )
        }

    // "1 sm Onion -- chopped", rebuilt from the parsed fields.
    private fun ingredientLine(name: Any?, amount: Map<Any?, Any?>, notes: Any?): String {
        var line = listOf(amount["amount"], amount["unit"], name)
            .filter { it != null && it != "" }
            .joinToString(" ") { Py.str(it) }
        if (Py.truthy(notes)) line += " -- " + pyIter(notes, "notes").joinToString(", ") { Py.str(it) }
        return line
    }

    /** Replace one amount slot with a corrected amount/unit, converted to US units. */
    fun applyAmountCorrection(doc: Map<Any?, Any?>, slotIndex: Int, amount: String, unit: String) {
        @Suppress("UNCHECKED_CAST")
        val slot = walkAmountSlots(doc)[slotIndex].amount as? YamlMap
            ?: throw RecipeFormatException("An amount must be a mapping")
        val (convertedAmount, convertedUnit) = Orf.toImperial(amount, unit)
        slot["amount"] = convertedAmount
        slot["unit"] = convertedUnit
    }

    /** An ingredient's notes as a list of strings (ORF says list; hand-written files may have a bare string). */
    fun notesList(notes: Any?): List<String> {
        val cleaned = Orf.cleanNone(notes) ?: return emptyList()
        if (cleaned is String) return listOf(cleaned)
        return pyIter(cleaned, "notes").filter { Orf.cleanNone(it) != null }.map { Py.str(it) }
    }

    /** The editor's single notes field back into a list; several notes are separated with ";". */
    fun parseNotesField(text: String?): List<String> =
        (text ?: "").split(";").map { Py.strip(it) }.filter { it.isNotEmpty() }

    fun buildEditableIngredients(doc: Map<Any?, Any?>): List<EditableIngredient> {
        val ingredients = doc["ingredients"]
        if (!Py.truthy(ingredients)) return emptyList()
        return pyIter(ingredients, "ingredients").mapIndexed { i, raw ->
            val (name, rawBody) = Orf.singleEntry(raw)
            val body = Orf.bodyMap(rawBody)
            val amountsValue = body["amounts"]
            val amounts: List<Any?> = when {
                !Py.truthy(amountsValue) -> emptyList()
                amountsValue is List<*> -> amountsValue
                else -> throw RecipeFormatException("amounts must be a list")
            }
            val first = if (amounts.isNotEmpty()) amounts[0].asYamlMap("An amount") else emptyMap()
            val slots = walkAmountSlots(mapOf<Any?, Any?>("ingredients" to listOf(raw)))
            EditableIngredient(
                index = i,
                name = name,
                amount = first.pyGet("amount", ""),
                unit = first.pyGet("unit", ""),
                section = body["section"],
                notes = notesList(body["notes"]),
                multiAmount = amounts.size > 1,
                needsInput = slots.any {
                    Amounts.parseAmount(it.amount.asYamlMap("An amount")["amount"]?.let { a -> Py.str(a) }) == null
                },
            )
        }
    }

    /**
     * The editor's single ordered list: a heading row starts each change of
     * section, and owns the ingredients below it until the next heading. A
     * heading with an empty name is a return to no section.
     */
    fun editorRows(editable: List<EditableIngredient>): List<EditorRow> {
        val rows = mutableListOf<EditorRow>()
        var current: Any? = null
        for (item in editable) {
            if (item.section != current) {
                current = item.section
                rows += EditorRow(
                    EditorRow.Kind.SECTION, "s${rows.size}", if (Py.truthy(current)) current else "",
                    "", "", "", locked = false, needsInput = false,
                )
            }
            rows += EditorRow(
                EditorRow.Kind.INGREDIENT, "e${item.index}", item.name, item.amount, item.unit,
                item.notes.joinToString("; "), locked = item.multiAmount, needsInput = item.needsInput,
            )
        }
        return rows
    }

    /**
     * A single-amount ingredient, amount converted to US units. [preserved]
     * supplies usda_num/processing/notes/substitutions to carry forward
     * from an ingredient being edited; its other keys are ignored.
     */
    fun buildNewIngredient(
        name: String,
        amount: String,
        unit: String,
        section: String?,
        preserved: Map<Any?, Any?>?,
    ): YamlMap {
        val (convertedAmount, convertedUnit) = Orf.toImperial(amount, unit)
        val body = linkedMapOf<Any?, Any?>(
            "amounts" to mutableListOf<Any?>(linkedMapOf<Any?, Any?>("amount" to convertedAmount, "unit" to convertedUnit)),
        )
        if (!section.isNullOrEmpty()) body["section"] = section
        if (!preserved.isNullOrEmpty()) {
            for (key in listOf("usda_num", "processing", "notes", "substitutions")) {
                if (Py.truthy(preserved[key])) body[key] = preserved[key]
            }
        }
        return linkedMapOf(name to body)
    }

    /**
     * The ORF ingredients list from the editor's submitted rows, in the
     * submitted order. An ingredient with several amounts isn't editable
     * here, so it is carried through unchanged apart from its section.
     */
    fun ingredientsFromRows(rows: List<SubmittedRow>, oldIngredients: List<Any?>): List<YamlMap> {
        val result = mutableListOf<YamlMap>()
        var section: String? = null
        for (row in rows) {
            val name = Py.strip(row.name)
            if (row.kind == SubmittedRow.SECTION) {
                section = name.ifEmpty { null }
                continue
            }
            if (name.isEmpty()) continue

            val original = originalAt(row.key, oldIngredients)
            val preserved: YamlMap
            if (original != null) {
                val (originalName, rawBody) = Orf.singleEntry(original)
                val body = Orf.bodyMap(rawBody)
                val amounts = body["amounts"]
                if ((if (Py.truthy(amounts)) pyLen(amounts, "amounts") else 0) > 1) {
                    val carried: YamlMap = LinkedHashMap(body)
                    if (section != null) carried["section"] = section else carried.remove("section")
                    result += linkedMapOf<Any?, Any?>(originalName to carried)
                    continue
                }
                preserved = LinkedHashMap(body)
                if (name != originalName) preserved.remove("usda_num")
            } else {
                preserved = LinkedHashMap()
            }

            if (row.notes != null) {
                val notes = parseNotesField(row.notes)
                if (notes.isNotEmpty()) preserved["notes"] = notes else preserved.remove("notes")
            }
            result += buildNewIngredient(name, row.amount, row.unit, section, preserved)
        }
        return result
    }

    /** The ORF steps list from the step editor's rows; blank rows are dropped. */
    fun stepsFromRows(rows: List<SubmittedStep>, oldSteps: List<Any?>): List<YamlMap> = rows.mapNotNull { row ->
        val text = Py.strip(row.text)
        if (text.isEmpty()) return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        val original = originalAt(row.key, oldSteps) as? Map<Any?, Any?>
        val step: YamlMap = if (original != null) LinkedHashMap(original) else LinkedHashMap()
        step["step"] = text
        step
    }

    // `e<n>` addresses the n-th original entry, when there is one.
    private fun originalAt(key: String, originals: List<Any?>): Any? {
        if (!key.startsWith("e")) return null
        val digits = key.substring(1)
        if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
        val index = digits.toBigInteger()
        return if (index < BigInteger.valueOf(originals.size.toLong())) originals[index.toInt()] else null
    }

    fun firstYield(doc: Map<Any?, Any?>): Map<String, Any?>? = Orf.normalizeYields(doc["yields"])?.firstOrNull()

    /** A leading whole number off free-text yield ("4 servings", "6"); null for "Serves a crowd" or 0. */
    fun parseYieldText(text: Any?): Map<String, Any?>? {
        val value = Orf.cleanNone(text)
        if (!Py.truthy(value)) return null
        val match = YIELD_NUM.find(Py.strip(Py.str(value))) ?: return null
        val amount = match.groupValues[1].toBigInteger()
        if (amount.signum() <= 0) return null
        val unit = Py.strip(match.groupValues[2]).lowercase(Locale.ROOT).ifEmpty { "servings" }
        return linkedMapOf("amount" to Py.intValue(amount), "unit" to unit)
    }

    fun slugify(name: String): String =
        name.lowercase(Locale.ROOT).replace(NON_SLUG, "-").trim('-').ifEmpty { "recipe" }

    /** `<slug>.yaml`, or `<slug>-2.yaml`, `-3`, ... when taken; the name chosen is added to [taken]. */
    fun uniqueFilename(baseName: String, taken: MutableSet<String>, exists: (String) -> Boolean = { false }): String {
        val slug = slugify(baseName)
        var candidate = "$slug.yaml"
        var counter = 2
        while (exists(candidate) || candidate in taken) {
            candidate = "$slug-$counter.yaml"
            counter++
        }
        taken += candidate
        return candidate
    }
}
