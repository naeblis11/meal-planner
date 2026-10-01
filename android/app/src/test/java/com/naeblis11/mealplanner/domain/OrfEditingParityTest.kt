package com.naeblis11.mealplanner.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class OrfEditingParityTest {
    private val cases = ParityFixtures.load("orf_editing.json").obj

    @Suppress("UNCHECKED_CAST")
    private fun doc(json: JsonElement) = JsonTree.fromJson(json) as YamlMap

    @Suppress("UNCHECKED_CAST")
    private fun list(json: JsonElement) = JsonTree.fromJson(json) as List<Any?>

    private fun AmountIssue.asJson() = linkedMapOf(
        "slot_index" to slotIndex, "ingredient_name" to ingredientName, "amount" to amount,
        "unit" to unit, "ingredient_line" to ingredientLine,
    )

    private fun EditableIngredient.asJson() = linkedMapOf(
        "index" to index, "name" to name, "amount" to amount, "unit" to unit, "section" to section,
        "notes" to notes, "multi_amount" to multiAmount, "needs_input" to needsInput,
    )

    // Section rows carry no needs_input key in the Python version.
    private fun EditorRow.asJson() = linkedMapOf<String, Any?>(
        "kind" to kind.name.lowercase(), "key" to key, "name" to name, "amount" to amount,
        "unit" to unit, "notes" to notes, "locked" to locked,
    ).apply { if (kind == EditorRow.Kind.INGREDIENT) put("needs_input", needsInput) }

    @Test
    fun documentHelpers() {
        for (case in cases["docs"]!!.jsonArray) {
            val name = case.obj["name"]!!.str()
            val doc = doc(case.obj["doc"]!!)
            assertEquals("$name unparseable", case.obj["unparseable"],
                JsonTree.toJson(OrfEditing.findUnparseableAmountSlots(doc).map { it.asJson() }))
            val editable = OrfEditing.buildEditableIngredients(doc)
            assertEquals("$name editable", case.obj["editable"], JsonTree.toJson(editable.map { it.asJson() }))
            assertEquals("$name editor rows", case.obj["editor_rows"],
                JsonTree.toJson(OrfEditing.editorRows(editable).map { it.asJson() }))
            assertEquals("$name first yield", case.obj["first_yield"], JsonTree.toJson(OrfEditing.firstYield(doc)))
        }
    }

    @Test
    fun ingredientsFromRows() {
        for (case in cases["ingredients_from_form"]!!.jsonArray) {
            val rows = case.obj["rows"]!!.jsonArray.map { r ->
                SubmittedRow(
                    key = r.obj["key"]!!.str()!!, kind = r.obj["kind"]!!.str()!!, name = r.obj["name"]!!.str()!!,
                    amount = r.obj["amount"]?.str() ?: "", unit = r.obj["unit"]?.str() ?: "",
                    notes = r.obj["notes"]?.str(),
                )
            }
            assertEquals(case.obj["name"]!!.str(), case.obj["expected"],
                JsonTree.toJson(OrfEditing.ingredientsFromRows(rows, list(case.obj["old"]!!))))
        }
    }

    @Test
    fun stepsFromRows() {
        for (case in cases["steps_from_form"]!!.jsonArray) {
            val rows = case.obj["rows"]!!.jsonArray.map { SubmittedStep(it.obj["key"]!!.str()!!, it.obj["text"]!!.str()!!) }
            assertEquals(case.obj["name"]!!.str(), case.obj["expected"],
                JsonTree.toJson(OrfEditing.stepsFromRows(rows, list(case.obj["old"]!!))))
        }
    }

    @Test
    fun amountCorrections() {
        val corrections = cases["amount_corrections"]!!.obj
        for (case in corrections["cases"]!!.jsonArray) {
            val doc = doc(corrections["doc"]!!)
            val slot = case.obj["slot_index"]!!.jsonPrimitive.int
            OrfEditing.applyAmountCorrection(doc, slot, case.obj["amount"]!!.str()!!, case.obj["unit"]!!.str()!!)
            assertEquals("slot $slot", case.obj["expected_ingredients"], JsonTree.toJson(doc["ingredients"]))
        }
    }

    @Test
    fun buildNewIngredient() {
        for (case in cases["build_new_ingredient"]!!.jsonArray) {
            val args = case.obj["args"]!!.jsonArray
            @Suppress("UNCHECKED_CAST")
            val preserved = JsonTree.fromJson(args[4]) as Map<Any?, Any?>?
            val built = OrfEditing.buildNewIngredient(args[0].str()!!, args[1].str()!!, args[2].str()!!, args[3].str(), preserved)
            assertEquals(args.toString(), case.obj["expected"], JsonTree.toJson(built))
        }
    }

    @Test
    fun smallHelpers() {
        for (case in cases["parse_yield_text"]!!.jsonArray) {
            val text = case.obj["text"]!!.str()
            assertEquals("parse_yield_text($text)", case.obj["expected"], JsonTree.toJson(OrfEditing.parseYieldText(text)))
        }
        for (case in cases["normalize_rating"]!!.jsonArray) {
            val value = JsonTree.fromJson(case.obj["value"]!!)
            assertEquals("normalize_rating($value)", case.obj["expected"], JsonTree.toJson(Orf.normalizeRating(value)))
        }
        for (case in cases["parse_notes_field"]!!.jsonArray) {
            val text = case.obj["text"]!!.str()
            assertEquals("parse_notes_field($text)", case.obj["expected"], JsonTree.toJson(OrfEditing.parseNotesField(text)))
        }
        for (case in cases["slugify"]!!.jsonArray) {
            val name = case.obj["name"]!!.str()!!
            assertEquals("slugify($name)", case.obj["expected"]!!.str(), OrfEditing.slugify(name))
        }
        val unique = cases["unique_filenames"]!!.obj
        val taken = mutableSetOf<String>()
        assertEquals(
            unique["expected"]!!.jsonArray.map { it.str() },
            unique["names"]!!.jsonArray.map { OrfEditing.uniqueFilename(it.str()!!, taken) },
        )
    }
}
