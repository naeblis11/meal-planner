package com.naeblis11.mealplanner.sync

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The synced tables, as sync_change names them, in the order an exchange applies them (a meal needs its recipe). */
object SyncTables {
    const val RECIPE = "recipe"
    const val MEAL = "meal_plan"
    const val PANTRY = "pantry_item"
    const val AISLE = "ingredient_aisle"
    const val SHOPPING = "shopping_list_item"
    val ORDER = listOf(RECIPE, MEAL, PANTRY, AISLE, SHOPPING)
}

/**
 * One record's state (LAN sync spec, section 3): its table and sync key, its last change (when, by which device), and
 * its fields, or none for a tombstone. [data]'s fields per table are SyncRecords'.
 */
data class SyncRecord(
    val table: String,
    val key: String,
    val updatedAt: Long,
    val updatedBy: String,
    val deleted: Boolean,
    val data: JsonObject?,
)

/**
 * A client's half of one exchange: its clock, the hub cursor it has, whether it sends everything, its changes, and the
 * id of the hub that gave the cursor ([hubId], null before the first sync).
 */
data class ExchangeRequest(
    val deviceTime: Long,
    val cursor: Long,
    val full: Boolean,
    val records: List<SyncRecord>,
    val hubId: String? = null,
)

/** A photo the hub wants: the recipe's uuid and the image_hash the bytes must have. */
data class PhotoWant(val uuid: String, val hash: String)

/**
 * The hub's half: its clock, the new cursor, whether this is everything, the changes, the names of records it couldn't
 * apply, the photos it wants, and its sync identity ([hubId], which the cursor belongs to).
 */
data class ExchangeReply(
    val hubTime: Long,
    val cursor: Long,
    val full: Boolean,
    val records: List<SyncRecord>,
    val skipped: List<String>,
    val wantPhotos: List<PhotoWant>,
    val hubId: String,
)

/** The exchange as JSON. Decoding trusts nothing: any malformed part throws IllegalArgumentException. */
object SyncWire {
    private const val MAX_KEY = 512
    private const val MAX_DEVICE = 64
    private const val MAX_HASH = 128

    fun encode(request: ExchangeRequest): String = buildJsonObject {
        put("deviceTime", request.deviceTime)
        put("cursor", request.cursor)
        put("full", request.full)
        put("hubId", request.hubId)
        put("records", records(request.records))
    }.toString()

    fun encode(reply: ExchangeReply): String = buildJsonObject {
        put("hubTime", reply.hubTime)
        put("cursor", reply.cursor)
        put("full", reply.full)
        put("records", records(reply.records))
        put("skipped", buildJsonArray { reply.skipped.forEach { add(JsonPrimitive(it)) } })
        put("wantPhotos", buildJsonArray { reply.wantPhotos.forEach { w -> add(buildJsonObject { put("uuid", w.uuid); put("hash", w.hash) }) } })
        put("hubId", reply.hubId)
    }.toString()

    fun encodeRecord(record: SyncRecord): String = record(record).toString()

    fun decodeRequest(text: String): ExchangeRequest {
        val o = parse(text)
        if ("hubId" !in o) bad("hubId is missing")
        val hubId = textOrNull(o, "hubId")?.also { if (it.isEmpty() || it.length > MAX_DEVICE) bad("bad hubId") }
        return ExchangeRequest(long(o, "deviceTime"), long(o, "cursor"), flag(o, "full"), records(o), hubId)
    }

    fun decodeReply(text: String): ExchangeReply {
        val o = parse(text)
        return ExchangeReply(
            long(o, "hubTime"), long(o, "cursor"), flag(o, "full"), records(o), strings(o, "skipped"), photoWants(o),
            text(o, "hubId").takeIf { it.isNotEmpty() && it.length <= MAX_DEVICE } ?: bad("bad hubId"),
        )
    }

    fun decodeRecord(text: String): SyncRecord = record(parse(text))

    /** A required text field of a record's data. */
    fun text(o: JsonObject, name: String): String = textOrNull(o, name) ?: bad("$name is missing")

    fun textOrNull(o: JsonObject, name: String): String? = when (val e = o[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (e.isString) e.content else bad("$name is not text")
        else -> bad("$name is not text")
    }

    fun flag(o: JsonObject, name: String): Boolean =
        (o[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: bad("$name is not true or false")

    private fun long(o: JsonObject, name: String): Long =
        (o[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: bad("$name is not a number")

    private fun records(list: List<SyncRecord>) = buildJsonArray { list.forEach { add(record(it)) } }

    private fun record(r: SyncRecord): JsonObject = buildJsonObject {
        put("table", r.table)
        put("key", r.key)
        put("updatedAt", r.updatedAt)
        put("updatedBy", r.updatedBy)
        put("deleted", r.deleted)
        r.data?.let { put("data", it) }
    }

    private fun records(o: JsonObject): List<SyncRecord> =
        (o["records"] as? JsonArray ?: bad("records is not a list")).map { record(it as? JsonObject ?: bad("a record is not an object")) }

    private fun record(o: JsonObject): SyncRecord {
        val table = text(o, "table").takeIf { it in SyncTables.ORDER } ?: bad("unknown table")
        val key = text(o, "key").takeIf { it.isNotEmpty() && it.length <= MAX_KEY } ?: bad("bad key")
        val by = text(o, "updatedBy").takeIf { it.length <= MAX_DEVICE } ?: bad("bad device")
        val deleted = flag(o, "deleted")
        val data = o["data"]?.let { it as? JsonObject ?: bad("data is not an object") }
        if (deleted != (data == null)) bad("a record has data exactly when it isn't deleted")
        return SyncRecord(table, key, long(o, "updatedAt"), by, deleted, data)
    }

    private fun strings(o: JsonObject, name: String): List<String> =
        (o[name] as? JsonArray ?: bad("$name is not a list")).map { e: JsonElement ->
            (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("$name holds a non-text")
        }

    private fun photoWants(o: JsonObject): List<PhotoWant> =
        (o["wantPhotos"] as? JsonArray ?: bad("wantPhotos is not a list")).map { e ->
            val w = e as? JsonObject ?: bad("a wanted photo is not an object")
            PhotoWant(
                text(w, "uuid").takeIf { it.isNotEmpty() && it.length <= MAX_KEY } ?: bad("bad photo uuid"),
                text(w, "hash").takeIf { it.isNotEmpty() && it.length <= MAX_HASH } ?: bad("bad photo hash"),
            )
        }

    private fun parse(text: String): JsonObject = try {
        Json.parseToJsonElement(text) as? JsonObject ?: bad("not an object")
    } catch (e: SerializationException) {
        bad("not JSON")
    }

    private fun bad(why: String): Nothing = throw IllegalArgumentException("Bad sync message: $why.")
}
