package com.naeblis11.mealplanner.recipes

import com.naeblis11.mealplanner.domain.Amounts
import com.naeblis11.mealplanner.domain.EditorRow
import com.naeblis11.mealplanner.domain.Fraction
import com.naeblis11.mealplanner.domain.OrfEditing
import com.naeblis11.mealplanner.domain.Orf
import com.naeblis11.mealplanner.domain.Py
import com.naeblis11.mealplanner.domain.SubmittedRow
import com.naeblis11.mealplanner.domain.SubmittedStep
import com.naeblis11.mealplanner.domain.YamlMap
import java.math.BigInteger
import java.util.Locale

/** One ingredient-editor row as the screen holds it. Ingredient rows always submit their notes field. */
data class EditorRowState(
    val key: String,
    val kind: String,
    val name: String,
    val amount: String = "",
    val unit: String = "",
    val notes: String = "",
    val locked: Boolean = false,
    val needsInput: Boolean = false,
) {
    fun toSubmitted(): SubmittedRow =
        SubmittedRow(key, kind, name, amount, unit, notes = if (kind == SubmittedRow.INGREDIENT) notes else null)
}

data class StepState(val key: String, val text: String)

/** The edit screen's fields, as text. */
data class RecipeForm(
    val name: String = "",
    val category: String = "",
    val subcategory: String = "",
    val author: String = "",
    val sourceUrl: String = "",
    val servingsAmount: String = "",
    val servingsUnit: String = "servings",
    val ovenTempAmount: String = "",
    val ovenTempUnit: String = "F",
    val ovenTime: String = "",
    val notes: String = "",
    val rows: List<EditorRowState> = emptyList(),
    val steps: List<StepState> = emptyList(),
)

/** The form can't be saved; the message says why, in the Pi's words. */
class RecipeEditException(message: String) : Exception(message)

/** The edit screen's rules: ports of app.py `recipe_edit_view` (prefill) and `recipe_edit_confirm` (save). */
object RecipeEdits {
    fun newRecipeDoc(): YamlMap = linkedMapOf(
        "recipe_name" to "",
        "ingredients" to mutableListOf<Any?>(),
        "steps" to mutableListOf<Any?>(),
    )

    /** app.py `_display_value`: the "no value" words show as an empty field. */
    fun display(value: Any?): String = Orf.cleanNone(value)?.let { Py.str(it) } ?: ""

    fun editorRowsFor(doc: Map<Any?, Any?>): List<EditorRowState> =
        OrfEditing.editorRows(OrfEditing.buildEditableIngredients(doc)).map { row ->
            EditorRowState(
                key = row.key,
                kind = if (row.kind == EditorRow.Kind.SECTION) SubmittedRow.SECTION else SubmittedRow.INGREDIENT,
                name = display(row.name),
                amount = display(row.amount),
                unit = display(row.unit),
                notes = row.notes,
                locked = row.locked,
                needsInput = row.needsInput,
            )
        }

    fun formFor(doc: Map<Any?, Any?>): RecipeForm {
        @Suppress("UNCHECKED_CAST")
        val temp = (doc["oven_temp"] as? List<*>)?.firstOrNull() as? Map<Any?, Any?>
        val firstYield = OrfEditing.firstYield(doc)
        val steps = doc["steps"].let { if (Py.truthy(it)) it as? List<*> ?: emptyList<Any?>() else emptyList<Any?>() }
        return RecipeForm(
            name = display(doc["recipe_name"]),
            category = display(doc["category"]),
            subcategory = display(doc["subcategory"]),
            author = display(doc["author"]),
            sourceUrl = display(doc["source_url"]),
            servingsAmount = firstYield?.let { display(it["amount"]) } ?: "",
            servingsUnit = firstYield?.let { display(it["unit"]) }?.ifEmpty { null } ?: "servings",
            ovenTempAmount = temp?.let { display(it["amount"]) } ?: "",
            ovenTempUnit = temp?.let { display(it["unit"]) }?.ifEmpty { null } ?: "F",
            ovenTime = display(doc["oven_time"]),
            notes = notesLines(doc["notes"]),
            rows = editorRowsFor(doc),
            steps = steps.mapIndexed { i, step ->
                @Suppress("UNCHECKED_CAST")
                StepState("e$i", display((step as? Map<Any?, Any?>)?.get("step")))
            },
        )
    }

    // app.py `_notes_lines`: a list becomes one note per line; a bare string stays as written.
    private fun notesLines(notes: Any?): String {
        val cleaned = Orf.cleanNone(notes) ?: return ""
        if (cleaned is String) return cleaned
        return (cleaned as? List<*>)?.joinToString("\n") { Py.str(it) } ?: Py.str(cleaned)
    }

    /**
     * Writes [form] into [doc] (changed in place and returned). Fields are
     * checked in the Pi's order; the first problem throws
     * [RecipeEditException] with the Pi's message. [otherNamesLower] is
     * every other recipe's name, lower-cased.
     */
    fun apply(doc: YamlMap, form: RecipeForm, otherNamesLower: Set<String>): YamlMap {
        val name = Py.strip(form.name)
        if (name.isEmpty()) throw RecipeEditException("A recipe needs a title.")
        if (name.lowercase(Locale.ROOT) in otherNamesLower) {
            throw RecipeEditException("Another recipe is already called '$name'. Pick a different title.")
        }
        doc["recipe_name"] = name

        // A blank single-line detail removes the entry, as deleting the line from the YAML would.
        setOrRemove(doc, "author", form.author)
        setOrRemove(doc, "source_url", form.sourceUrl)
        setOrRemove(doc, "oven_time", form.ovenTime)
        doc["category"] = Py.strip(form.category).ifEmpty { "None" }
        doc["subcategory"] = Py.strip(form.subcategory).ifEmpty { "None" }

        val notes = Py.splitLines(form.notes).map { Py.strip(it) }.filter { it.isNotEmpty() }
        if (notes.isNotEmpty()) doc["notes"] = notes.toMutableList<Any?>() else doc.remove("notes")

        val temp = Py.strip(form.ovenTempAmount)
        if (temp.isNotEmpty()) {
            val unit = Py.strip(form.ovenTempUnit).uppercase(Locale.ROOT).ifEmpty { "F" }
            val amount: Any = if (temp.all { it in '0'..'9' }) Py.intValue(BigInteger(temp)) else temp
            doc["oven_temp"] = mutableListOf<Any?>(linkedMapOf<Any?, Any?>("amount" to amount, "unit" to unit))
        } else {
            doc.remove("oven_temp")
        }

        val servingsText = Py.strip(form.servingsAmount)
        if (servingsText.isNotEmpty()) {
            val amount = Amounts.parseAmount(servingsText)
            if (amount == null || amount <= Fraction.ZERO) throw RecipeEditException("Enter a valid serving amount.")
            doc["yields"] = mutableListOf<Any?>(
                linkedMapOf<Any?, Any?>(
                    "amount" to if (amount.denominator == BigInteger.ONE) Py.intValue(amount.numerator) else Amounts.formatAmount(amount),
                    "unit" to Py.strip(form.servingsUnit).ifEmpty { "servings" },
                ),
            )
        } else {
            doc.remove("yields")
        }

        val steps = OrfEditing.stepsFromRows(form.steps.map { SubmittedStep(it.key, it.text) }, listValue(doc["steps"]))
        if (steps.isEmpty()) throw RecipeEditException("A recipe needs at least one instruction step.")
        doc["steps"] = steps

        val ingredients = OrfEditing.ingredientsFromRows(form.rows.map { it.toSubmitted() }, listValue(doc["ingredients"]))
        if (ingredients.isEmpty()) throw RecipeEditException("A recipe needs at least one ingredient.")
        doc["ingredients"] = ingredients
        return doc
    }

    private fun setOrRemove(doc: YamlMap, key: String, text: String) {
        val value = Py.strip(text)
        if (value.isNotEmpty()) doc[key] = value else doc.remove(key)
    }

    // `data.get(key) or []`, as a list.
    private fun listValue(value: Any?): List<Any?> =
        if (Py.truthy(value)) (value as? List<*>)?.toList() ?: emptyList() else emptyList()
}
