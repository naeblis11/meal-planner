package com.naeblis11.mealplanner.domain

import java.math.BigInteger
import java.util.Date
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Converts between the values YAML loads into (maps, lists, strings,
 * numbers, booleans, null) and JSON, as Python's json module does. Used for
 * the index columns the Pi stores as JSON and for comparing against the
 * parity fixtures.
 */
object JsonTree {
    fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is BigInteger -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> key(k) to toJson(v) })
        is List<*> -> JsonArray(value.map { toJson(it) })
        is Date -> JsonPrimitive(value.toInstant().toString())
        else -> JsonPrimitive(value.toString())
    }

    // json.dumps turns non-string keys into text: True -> "true", None -> "null", 1.5 -> "1.5".
    private fun key(k: Any?): String = when (k) {
        null -> "null"
        is Boolean -> k.toString()
        is Double -> Py.str(k)
        else -> k.toString()
    }

    fun fromJson(element: JsonElement): Any? = when (element) {
        JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.content.any { it == '.' || it == 'e' || it == 'E' } -> element.content.toDouble()
            else -> Py.intValue(element.content.toBigInteger())
        }
        is JsonObject -> element.entries.associateTo(LinkedHashMap<Any?, Any?>()) { (k, v) -> k to fromJson(v) }
        is JsonArray -> element.mapTo(ArrayList()) { fromJson(it) }
    }

    /** The Pi's `_dump`: null stays null, anything else becomes JSON text. */
    fun encode(value: Any?): String? = if (value == null) null else toJson(value).toString()

    fun decode(text: String?): Any? = text?.let { fromJson(Json.parseToJsonElement(it)) }
}
