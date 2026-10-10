package com.naeblis11.mealplanner.desktop.server

import com.naeblis11.mealplanner.domain.JsonTree
import com.naeblis11.mealplanner.domain.Py
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The request body, or null when it is, or says it is, over [maxBytes] (P4-R2). */
internal suspend fun ApplicationCall.readBody(maxBytes: Int): ByteArray? {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > maxBytes) return null
    val channel = receiveChannel()
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read < 0) return out.toByteArray()
        if (out.size() + read > maxBytes) return null
        out.write(buffer, 0, read)
    }
}

/**
 * A JSON object body as JsonTree's values (as request.get_json(silent=True) or {}): anything else, broken JSON, an
 * array, or nesting too deep to walk, is an empty object. So is one holding an integer of more than 4300 digits,
 * which Python's json refuses (Py.MAX_INT_DIGITS), and which BigInteger would take a long while to read.
 */
internal fun jsonObject(bytes: ByteArray): Map<Any?, Any?> = try {
    val element = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
    @Suppress("UNCHECKED_CAST")
    if (element !is JsonObject || hasOverlongInt(element)) emptyMap() else JsonTree.fromJson(element) as Map<Any?, Any?>
} catch (e: Exception) {
    emptyMap()
} catch (e: StackOverflowError) {
    emptyMap()
}

// Walks [root] with a stack of its own, so a deep body can't overflow the thread's.
private fun hasOverlongInt(root: JsonElement): Boolean {
    val pending = ArrayDeque<JsonElement>()
    pending.addLast(root)
    while (pending.isNotEmpty()) {
        when (val element = pending.removeLast()) {
            is JsonObject -> pending.addAll(element.values)
            is JsonArray -> pending.addAll(element)
            is JsonPrimitive -> if (!element.isString && Py.overIntLimit(element.content) &&
                element.content.none { it == '.' || it == 'e' || it == 'E' }
            ) {
                return true
            }
        }
    }
    return false
}
