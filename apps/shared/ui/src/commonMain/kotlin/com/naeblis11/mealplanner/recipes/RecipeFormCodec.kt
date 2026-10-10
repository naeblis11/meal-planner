package com.naeblis11.mealplanner.recipes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An unsaved edit form as one string, so the draft fits in the saved instance state and survives process death. */
object RecipeFormCodec {
    fun encode(form: RecipeForm): String = JsonObject(
        mapOf(
            "name" to JsonPrimitive(form.name),
            "category" to JsonPrimitive(form.category),
            "subcategory" to JsonPrimitive(form.subcategory),
            "author" to JsonPrimitive(form.author),
            "sourceUrl" to JsonPrimitive(form.sourceUrl),
            "servingsAmount" to JsonPrimitive(form.servingsAmount),
            "servingsUnit" to JsonPrimitive(form.servingsUnit),
            "ovenTempAmount" to JsonPrimitive(form.ovenTempAmount),
            "ovenTempUnit" to JsonPrimitive(form.ovenTempUnit),
            "ovenTime" to JsonPrimitive(form.ovenTime),
            "notes" to JsonPrimitive(form.notes),
            "rows" to JsonArray(
                form.rows.map { row ->
                    JsonObject(
                        mapOf(
                            "key" to JsonPrimitive(row.key),
                            "kind" to JsonPrimitive(row.kind),
                            "name" to JsonPrimitive(row.name),
                            "amount" to JsonPrimitive(row.amount),
                            "unit" to JsonPrimitive(row.unit),
                            "notes" to JsonPrimitive(row.notes),
                            "locked" to JsonPrimitive(row.locked),
                            "needsInput" to JsonPrimitive(row.needsInput),
                        ),
                    )
                },
            ),
            "steps" to JsonArray(form.steps.map { JsonObject(mapOf("key" to JsonPrimitive(it.key), "text" to JsonPrimitive(it.text))) }),
        ),
    ).toString()

    fun decode(text: String): RecipeForm {
        val o = Json.parseToJsonElement(text).jsonObject
        fun JsonObject.s(key: String) = getValue(key).jsonPrimitive.content
        fun JsonObject.b(key: String) = getValue(key).jsonPrimitive.boolean
        return RecipeForm(
            name = o.s("name"),
            category = o.s("category"),
            subcategory = o.s("subcategory"),
            author = o.s("author"),
            sourceUrl = o.s("sourceUrl"),
            servingsAmount = o.s("servingsAmount"),
            servingsUnit = o.s("servingsUnit"),
            ovenTempAmount = o.s("ovenTempAmount"),
            ovenTempUnit = o.s("ovenTempUnit"),
            ovenTime = o.s("ovenTime"),
            notes = o.s("notes"),
            rows = o.getValue("rows").jsonArray.map {
                val r = it.jsonObject
                EditorRowState(r.s("key"), r.s("kind"), r.s("name"), r.s("amount"), r.s("unit"), r.s("notes"), r.b("locked"), r.b("needsInput"))
            },
            steps = o.getValue("steps").jsonArray.map { StepState(it.jsonObject.s("key"), it.jsonObject.s("text")) },
        )
    }
}
