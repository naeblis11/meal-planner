package com.naeblis11.mealplanner.desktop.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** An answer as the Python server gave it: an HTTP status and a small JSON object. */
data class JsonReply(val status: Int, val body: JsonObject) {
    companion object {
        /** {"ok": true}, then [extra] text fields in order: /healthz, and the extension's "received". */
        fun ok(vararg extra: Pair<String, String>): JsonReply =
            JsonReply(200, JsonObject(mapOf("ok" to JsonPrimitive(true)) + extra.associate { (key, value) -> key to JsonPrimitive(value) }))

        /** {"ok": false, "error": [message]}: what the extension shows when something is wrong. */
        fun error(status: Int, message: String): JsonReply =
            JsonReply(status, JsonObject(mapOf("ok" to JsonPrimitive(false), "error" to JsonPrimitive(message))))
    }
}

/** Sends [reply] as application/json. */
internal suspend fun ApplicationCall.reply(reply: JsonReply) =
    respondText(reply.body.toString(), ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
